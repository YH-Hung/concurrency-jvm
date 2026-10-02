package hle.org.workqueue.engine;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * All engine SQL (spec §6). Each public method is one DB operation: one transaction bounded by T_tx,
 * every timestamp taken from the Db2 clock. Every write by a claim holder is fenced on ID, CLAIM_TOKEN,
 * OWNER and STATUS = 'CLAIMED' (spec §5.1). Invalid arguments throw IllegalArgumentException or
 * NullPointerException before any database access. Every database failure (no connection, a statement or
 * transaction timeout, a failed commit or rollback) surfaces as a DataAccessException, after which the
 * operation's outcome is unknown: callers must assume it may or may not have committed. Operations join a
 * transaction already bound to the thread; callers must not wrap them in their own transaction, because the
 * timing budget assumes one operation per transaction.
 */
class WorkItemRepository {

    /**
     * Queue settings the SQL needs.
     *
     * @param lease        a claim or renewal sets AVAILABLE_AT to now + lease
     * @param maxAttempts  claims allowed between two replays
     * @param retryBackoff a failed attempt with retries left sets AVAILABLE_AT to now + retryBackoff
     */
    public record Settings(Duration lease, int maxAttempts, Duration retryBackoff) {

        public Settings {
            Durations.requirePositiveWholeSeconds("lease", lease);
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
            }
            Objects.requireNonNull(retryBackoff, "retryBackoff");
            if (retryBackoff.isNegative() || retryBackoff.compareTo(MAX_RETRY_BACKOFF) > 0) {
                throw new IllegalArgumentException(
                        "retryBackoff must be between 0 and " + MAX_RETRY_BACKOFF + ": " + retryBackoff);
            }
        }
    }

    /** retryBackoff is written as an INTEGER number of microseconds. */
    static final Duration MAX_RETRY_BACKOFF = Duration.of(Integer.MAX_VALUE, ChronoUnit.MICROS);

    /** OWNER is VARCHAR(64), counted in bytes. */
    static final int MAX_OWNER_BYTES = 64;

    /** LAST_ERROR is VARCHAR(1000), counted in bytes. */
    static final int MAX_ERROR_BYTES = 1000;

    static final String SWEPT_ERROR = "lease expired; attempts exhausted";
    static final String REVOKED_ERROR = "revoked; attempts exhausted";

    private static final RowMapper<ClaimedItem> CLAIMED_ITEM = (rs, rowNum) -> new ClaimedItem(
            rs.getLong("ID"), rs.getString("OPERATION_ID"), rs.getString("PAYLOAD"), rs.getLong("CLAIM_TOKEN"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Settings settings;

    public WorkItemRepository(DataSource dataSource, DbTimeouts timeouts, Settings settings) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.transactions.setTimeout(timeouts.transactionSeconds());
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Claims up to {@code n} rows for {@code owner} in one transaction (spec §5.1): expired claims first,
     * then PENDING rows, each selection oldest AVAILABLE_AT first, skipping rows locked by concurrent
     * claimers. Every claimed row gets CLAIM_TOKEN + 1, ATTEMPTS + 1 and a fresh lease.
     */
    public List<ClaimedItem> claim(String owner, int n) {
        requireOwner(owner);
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1: " + n);
        }
        return inTransaction(jdbc -> {
            List<ClaimedItem> claimed = new ArrayList<>(claimSelection(jdbc, "CLAIMED", owner, n));
            if (claimed.size() < n) {
                claimed.addAll(claimSelection(jdbc, "PENDING", owner, n - claimed.size()));
            }
            return claimed;
        });
    }

    /**
     * One renewal round (spec §5.3): pushes the lease of every listed claim that is still CLAIMED by
     * {@code owner} with the same token, in one statement. In the same transaction, a claim it did not renew is
     * reported ended if this owner's own complete or retryOrFail already ended it (the row still has this owner and
     * token but is no longer CLAIMED), and lost otherwise.
     */
    public RenewalResult renew(String owner, Collection<ClaimKey> claims) {
        requireOwner(owner);
        Set<ClaimKey> requested = Set.copyOf(claims);
        if (requested.isEmpty()) {
            return RenewalResult.NOTHING;
        }
        return inTransaction(jdbc -> {
            Map<String, Object> params = new HashMap<>();
            params.put("owner", owner);
            params.put("leaseSeconds", leaseSeconds());
            Set<ClaimKey> renewed = claimKeys(jdbc, """
                    SELECT ID, CLAIM_TOKEN FROM FINAL TABLE (
                      UPDATE WORK_ITEM
                         SET AVAILABLE_AT = CURRENT TIMESTAMP + (CAST(:leaseSeconds AS INTEGER)) SECONDS,
                             UPDATED_AT = CURRENT TIMESTAMP
                       WHERE STATUS = 'CLAIMED' AND OWNER = :owner AND (%s))
                    """, params, requested);
            Set<ClaimKey> missing = new HashSet<>(requested);
            missing.removeAll(renewed);
            if (missing.isEmpty()) {
                return new RenewalResult(renewed, Set.of(), Set.of());
            }
            Set<ClaimKey> ended = claimKeys(jdbc, """
                    SELECT ID, CLAIM_TOKEN FROM WORK_ITEM
                     WHERE STATUS <> 'CLAIMED' AND OWNER = :owner AND (%s)
                    """, Map.of("owner", owner), missing);
            missing.removeAll(ended);
            return new RenewalResult(renewed, ended, missing);
        });
    }

    /** Stores the result of this claim's call and marks the row DONE (fenced, with read-back). */
    public PersistResult complete(String owner, ClaimKey claim, String resultValue) {
        requireOwner(owner);
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(resultValue, "resultValue");
        return inTransaction(jdbc -> {
            int updated = jdbc.sql("""
                    UPDATE WORK_ITEM
                       SET STATUS = 'DONE', RESULT_VALUE = :resultValue, UPDATED_AT = CURRENT TIMESTAMP
                     WHERE ID = :id AND CLAIM_TOKEN = :token AND OWNER = :owner AND STATUS = 'CLAIMED'
                    """)
                    .param("resultValue", resultValue)
                    .param("id", claim.id())
                    .param("token", claim.token())
                    .param("owner", owner)
                    .update();
            return updated == 1 ? PersistResult.DONE : readBack(jdbc, owner, claim, Set.of("DONE"));
        });
    }

    /**
     * Records a failed attempt (fenced, with read-back): PENDING after retry-backoff while attempts
     * remain, otherwise FAILED. {@code error} is cut to the column size.
     */
    public PersistResult retryOrFail(String owner, ClaimKey claim, String error) {
        requireOwner(owner);
        Objects.requireNonNull(claim, "claim");
        String lastError = truncateUtf8(Objects.requireNonNull(error, "error"), MAX_ERROR_BYTES);
        return inTransaction(jdbc -> jdbc.sql("""
                        SELECT STATUS FROM FINAL TABLE (
                          UPDATE WORK_ITEM
                             SET STATUS = CASE WHEN ATTEMPTS >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                                 AVAILABLE_AT = CASE WHEN ATTEMPTS >= :maxAttempts THEN AVAILABLE_AT
                                                     ELSE CURRENT TIMESTAMP + (CAST(:backoffMicros AS INTEGER)) MICROSECONDS END,
                                 LAST_ERROR = :lastError,
                                 UPDATED_AT = CURRENT TIMESTAMP
                           WHERE ID = :id AND CLAIM_TOKEN = :token AND OWNER = :owner AND STATUS = 'CLAIMED')
                        """)
                .param("maxAttempts", settings.maxAttempts())
                .param("backoffMicros", Math.toIntExact(settings.retryBackoff().toNanos() / 1_000))
                .param("lastError", lastError)
                .param("id", claim.id())
                .param("token", claim.token())
                .param("owner", owner)
                .query(String.class)
                .optional()
                .map(WorkItemRepository::persistResultOf)
                .orElseGet(() -> readBack(jdbc, owner, claim, Set.of("PENDING", "FAILED"))));
    }

    /**
     * One sweep batch (spec §6 Sweeper): up to {@code batchSize} expired CLAIMED rows with exhausted
     * attempts become FAILED, skipping rows another transaction holds. Like revokeOwner, it bumps the token
     * and clears the owner, so the swept owner's late writes are fenced instead of reading back FAILED as
     * their own (spec §5.1). Returns the number swept; the caller repeats while a batch comes back full.
     */
    public int sweep(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        return inTransaction(jdbc -> jdbc.sql(String.format(Locale.ROOT, """
                SELECT ID FROM FINAL TABLE (
                  UPDATE (SELECT ID, STATUS, OWNER, CLAIM_TOKEN, LAST_ERROR, UPDATED_AT
                            FROM WORK_ITEM
                           WHERE STATUS = 'CLAIMED'
                             AND AVAILABLE_AT <= CURRENT TIMESTAMP
                             AND ATTEMPTS >= :maxAttempts
                           FETCH FIRST %d ROWS ONLY)
                     SET STATUS = 'FAILED', CLAIM_TOKEN = CLAIM_TOKEN + 1, OWNER = NULL,
                         LAST_ERROR = :error, UPDATED_AT = CURRENT TIMESTAMP)
                SKIP LOCKED DATA
                """, batchSize))
                .param("maxAttempts", settings.maxAttempts())
                .param("error", SWEPT_ERROR)
                .query(Long.class)
                .list()
                .size());
    }

    /**
     * One backlog sample (spec §9.6) in one query. It reads only unfinished rows, so Db2 can answer from
     * IX_WORK_ITEM_CLAIM without visiting DONE rows. It is an uncommitted read: it never waits for row locks, whatever
     * the database's cur_commit setting, and may count a claim or persist still in flight, which gauges tolerate.
     */
    public BacklogSample sampleBacklog() {
        return inTransaction(jdbc -> jdbc.sql("""
                SELECT COUNT(CASE WHEN STATUS = 'PENDING' THEN 1 END) AS PENDING,
                       COUNT(CASE WHEN STATUS = 'CLAIMED' THEN 1 END) AS CLAIMED,
                       COUNT(CASE WHEN STATUS = 'FAILED' THEN 1 END) AS FAILED,
                       COUNT(CASE WHEN STATUS = 'CLAIMED'
                                   AND AVAILABLE_AT < CURRENT TIMESTAMP - (CAST(:leaseSeconds AS INTEGER)) SECONDS
                                  THEN 1 END) AS EXPIRED_CLAIMS,
                       SECONDS_BETWEEN(CURRENT TIMESTAMP,
                                       MIN(CASE WHEN STATUS = 'PENDING' AND AVAILABLE_AT <= CURRENT TIMESTAMP
                                                THEN AVAILABLE_AT END)) AS OLDEST_PENDING_SECONDS
                  FROM WORK_ITEM
                 WHERE STATUS IN ('PENDING', 'CLAIMED', 'FAILED')
                 WITH UR
                """)
                .param("leaseSeconds", leaseSeconds())
                // OLDEST_PENDING_SECONDS is NULL without a claimable PENDING row, and getLong reads NULL as 0.
                .query((rs, rowNum) -> new BacklogSample(rs.getLong("PENDING"), rs.getLong("CLAIMED"),
                        rs.getLong("FAILED"), rs.getLong("EXPIRED_CLAIMS"),
                        Duration.ofSeconds(rs.getLong("OLDEST_PENDING_SECONDS"))))
                .single());
    }

    /**
     * Operator replay of FAILED rows (spec §9.7). A dry run counts the matching rows; otherwise they become
     * PENDING with ATTEMPTS = 0 and no owner, keeping CLAIM_TOKEN and OPERATION_ID.
     */
    public int replay(ReplayFilter filter, boolean dryRun) {
        Objects.requireNonNull(filter, "filter");
        StringBuilder where = new StringBuilder("STATUS = 'FAILED'");
        Map<String, Object> params = new HashMap<>();
        if (!filter.ids().isEmpty()) {
            where.append(" AND ID IN (:ids)");
            params.put("ids", filter.ids());
        }
        if (filter.lastErrorContains() != null) {
            where.append(" AND LOCATE(CAST(:lastErrorContains AS VARCHAR(1000)), LAST_ERROR) > 0");
            params.put("lastErrorContains", filter.lastErrorContains());
        }
        if (filter.failedBefore() != null) {
            where.append(" AND UPDATED_AT < :failedBefore");
            params.put("failedBefore", Timestamp.valueOf(filter.failedBefore()));
        }
        if (dryRun) {
            return inTransaction(jdbc -> jdbc.sql("SELECT COUNT(*) FROM WORK_ITEM WHERE " + where)
                    .params(params)
                    .query(Integer.class)
                    .single());
        }
        return inTransaction(jdbc -> jdbc.sql("""
                UPDATE WORK_ITEM
                   SET STATUS = 'PENDING', ATTEMPTS = 0, OWNER = NULL,
                       AVAILABLE_AT = CURRENT TIMESTAMP, UPDATED_AT = CURRENT TIMESTAMP
                 WHERE %s
                """.formatted(where))
                .params(params)
                .update());
    }

    /**
     * Revokes every claim {@code owner} holds, in one statement (spec §9.7): token + 1, no owner, claimable
     * now, and PENDING, or FAILED when attempts are exhausted. Once it commits, every write by the old owner
     * is fenced. Row locks serialise it with the owner's own writes, so it does not skip locked rows.
     */
    public int revokeOwner(String owner, boolean dryRun) {
        requireOwner(owner);
        if (dryRun) {
            return inTransaction(jdbc -> jdbc.sql("SELECT COUNT(*) FROM WORK_ITEM WHERE STATUS = 'CLAIMED' AND OWNER = :owner")
                    .param("owner", owner)
                    .query(Integer.class)
                    .single());
        }
        return inTransaction(jdbc -> jdbc.sql("""
                UPDATE WORK_ITEM
                   SET CLAIM_TOKEN = CLAIM_TOKEN + 1,
                       OWNER = NULL,
                       AVAILABLE_AT = CURRENT TIMESTAMP,
                       STATUS = CASE WHEN ATTEMPTS >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                       LAST_ERROR = CASE WHEN ATTEMPTS >= :maxAttempts THEN CAST(:revokedError AS VARCHAR(1000))
                                         ELSE LAST_ERROR END,
                       UPDATED_AT = CURRENT TIMESTAMP
                 WHERE STATUS = 'CLAIMED' AND OWNER = :owner
                """)
                .param("maxAttempts", settings.maxAttempts())
                .param("revokedError", REVOKED_ERROR)
                .param("owner", owner)
                .update());
    }

    /** This queue's namespace (spec §5.4). */
    public String readNamespace() {
        List<String> namespaces = inTransaction(jdbc -> jdbc.sql("SELECT NAMESPACE FROM WORK_QUEUE_META").query(String.class).list());
        if (namespaces.size() != 1) {
            throw new IllegalStateException("WORK_QUEUE_META must hold exactly one row, found " + namespaces.size());
        }
        return namespaces.getFirst();
    }

    /** Runs {@code work} as one DB operation: one transaction bounded by T_tx (spec §5.3). */
    <T> T inTransaction(Function<JdbcClient, T> work) {
        try {
            return transactions.execute(status -> work.apply(jdbc));
        } catch (TransactionSystemException e) {
            // Commit or rollback failed. After a close-socket query timeout the rollback fails on the closed
            // connection; surface the statement's own error, keeping the rollback failure as suppressed.
            Throwable original = e.getApplicationException();
            if (original instanceof RuntimeException runtime) {
                runtime.addSuppressed(e);
                throw runtime;
            }
            if (original instanceof Error error) {
                error.addSuppressed(e);
                throw error;
            }
            throw new DataAccessResourceFailureException(e.getMessage(), e);
        } catch (TransactionException e) {
            // No connection (pool wait, login, Db2 unreachable) or T_tx expired between statements.
            throw new DataAccessResourceFailureException(e.getMessage(), e);
        }
    }

    // Claim form chosen by the Phase 1 spike (docs/claim-sql-spike.md). FETCH FIRST takes a literal;
    // n is an int, so formatting it into the statement is safe.
    private List<ClaimedItem> claimSelection(JdbcClient jdbc, String status, String owner, int n) {
        return jdbc.sql(String.format(Locale.ROOT, """
                SELECT ID, OPERATION_ID, PAYLOAD, CLAIM_TOKEN FROM FINAL TABLE (
                  UPDATE (SELECT ID, OPERATION_ID, PAYLOAD, STATUS, OWNER, CLAIM_TOKEN, ATTEMPTS, AVAILABLE_AT, UPDATED_AT
                            FROM WORK_ITEM
                           WHERE STATUS = :status
                             AND AVAILABLE_AT <= CURRENT TIMESTAMP
                             AND ATTEMPTS < :maxAttempts
                           ORDER BY AVAILABLE_AT
                           FETCH FIRST %d ROWS ONLY)
                     SET STATUS = 'CLAIMED', OWNER = :owner,
                         CLAIM_TOKEN = CLAIM_TOKEN + 1, ATTEMPTS = ATTEMPTS + 1,
                         AVAILABLE_AT = CURRENT TIMESTAMP + (CAST(:leaseSeconds AS INTEGER)) SECONDS,
                         UPDATED_AT = CURRENT TIMESTAMP)
                SKIP LOCKED DATA
                """, n))
                .param("status", status)
                .param("maxAttempts", settings.maxAttempts())
                .param("owner", owner)
                .param("leaseSeconds", leaseSeconds())
                .query(CLAIMED_ITEM)
                .list();
    }

    private int leaseSeconds() {
        return Math.toIntExact(settings.lease().toSeconds());
    }

    // Runs sql, whose %s is replaced by one (ID, CLAIM_TOKEN) match per claim, and returns the pairs it selects.
    private static Set<ClaimKey> claimKeys(JdbcClient jdbc, String sql, Map<String, Object> params,
                                           Collection<ClaimKey> claims) {
        Map<String, Object> allParams = new HashMap<>(params);
        StringJoiner pairs = new StringJoiner(" OR ");
        int i = 0;
        for (ClaimKey claim : claims) {
            pairs.add("(ID = :id" + i + " AND CLAIM_TOKEN = :token" + i + ")");
            allParams.put("id" + i, claim.id());
            allParams.put("token" + i, claim.token());
            i++;
        }
        return jdbc.sql(sql.formatted(pairs))
                .params(allParams)
                .query((rs, rowNum) -> new ClaimKey(rs.getLong("ID"), rs.getLong("CLAIM_TOKEN")))
                .set();
    }

    static void requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank() || owner.getBytes(StandardCharsets.UTF_8).length > MAX_OWNER_BYTES) {
            throw new IllegalArgumentException("owner must be non-blank and at most " + MAX_OWNER_BYTES + " bytes");
        }
    }

    // The fenced write updated 0 rows. If the row still carries this claim's token and owner in a state
    // this persist writes, an earlier attempt committed but its acknowledgement was lost.
    private static PersistResult readBack(JdbcClient jdbc, String owner, ClaimKey claim, Set<String> targetStatuses) {
        return jdbc.sql("SELECT STATUS FROM WORK_ITEM WHERE ID = :id AND CLAIM_TOKEN = :token AND OWNER = :owner")
                .param("id", claim.id())
                .param("token", claim.token())
                .param("owner", owner)
                .query(String.class)
                .optional()
                .filter(targetStatuses::contains)
                .map(WorkItemRepository::persistResultOf)
                .orElse(PersistResult.FENCED);
    }

    private static PersistResult persistResultOf(String status) {
        return switch (status) {
            case "DONE" -> PersistResult.DONE;
            case "PENDING" -> PersistResult.RETRY_SCHEDULED;
            case "FAILED" -> PersistResult.FAILED;
            default -> throw new IllegalStateException("not a persisted status: " + status);
        };
    }

    /** Cuts {@code text} to at most {@code maxBytes} UTF-8 bytes without splitting a character. */
    static String truncateUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {   // bytes[end] continues a character: back up
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
