package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * JDBC time bounds of one engine DB operation (spec §5.3).
 *
 * @param poolWait    T_pool: waiting for a pooled connection (Hikari {@code connectionTimeout})
 * @param login       T_login: opening a physical connection (JCC {@code loginTimeout})
 * @param transaction T_tx: all statements of one operation (Spring transaction timeout, applied as the
 *                    JDBC query timeout of every statement)
 * @param read        T_read: any single round trip, including commit (JCC {@code blockingReadConnectionTimeout})
 * @param lockWait    T_lock: a row-lock wait (Db2 {@code CURRENT LOCK TIMEOUT})
 */
public record DbTimeouts(Duration poolWait, Duration login, Duration transaction, Duration read, Duration lockWait) {

    /** Hikari rejects connection and validation timeouts below 250ms. */
    private static final Duration HIKARI_MIN_TIMEOUT = Duration.ofMillis(250);

    /** JCC {@code queryTimeoutInterruptProcessingMode} 2: close the socket when a query times out. */
    private static final String CLOSE_SOCKET_ON_QUERY_TIMEOUT = "2";

    public DbTimeouts {
        Objects.requireNonNull(poolWait, "poolWait");
        if (poolWait.compareTo(HIKARI_MIN_TIMEOUT) < 0) {
            throw new IllegalArgumentException("poolWait must be at least " + HIKARI_MIN_TIMEOUT + ": " + poolWait);
        }
        Durations.requirePositiveWholeSeconds("login", login);
        Durations.requirePositiveWholeSeconds("transaction", transaction);
        Durations.requirePositiveWholeSeconds("read", read);
        Durations.requirePositiveWholeSeconds("lockWait", lockWait);
    }

    /** The default column of spec §5.3. */
    public static DbTimeouts defaults() {
        return new DbTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5),
                Duration.ofSeconds(8), Duration.ofSeconds(3));
    }

    /** T_tx as a Spring transaction timeout. */
    public int transactionSeconds() {
        return Math.toIntExact(transaction.toSeconds());
    }

    /**
     * Applies these bounds to a Hikari pool before it starts. Driver settings go through data-source
     * properties, not the JDBC URL, so they also apply to URLs supplied by Testcontainers or the
     * environment. Validation stays below the pool wait so a dead pooled connection cannot use it up.
     */
    public void applyTo(HikariConfig config) {
        config.setConnectionTimeout(poolWait.toMillis());
        config.setValidationTimeout(Math.max(HIKARI_MIN_TIMEOUT.toMillis(), poolWait.toMillis() / 2));
        config.setConnectionInitSql("SET CURRENT LOCK TIMEOUT " + lockWait.toSeconds());
        config.addDataSourceProperty("loginTimeout", String.valueOf(login.toSeconds()));
        config.addDataSourceProperty("blockingReadConnectionTimeout", String.valueOf(read.toSeconds()));
        config.addDataSourceProperty("queryTimeoutInterruptProcessingMode", CLOSE_SOCKET_ON_QUERY_TIMEOUT);
    }
}
