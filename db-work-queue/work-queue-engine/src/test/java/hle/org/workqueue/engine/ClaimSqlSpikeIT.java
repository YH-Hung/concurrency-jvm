package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 spike (spec §6 "Claim SQL"): which claim statement forms does Db2 accept, and which of them
 * take the oldest rows while skipping rows locked by another claim instead of waiting? Writes
 * target/claim-sql-spike.md. The form chosen from this evidence lives in WorkItemRepository.
 */
class ClaimSqlSpikeIT {

    private static final String SELECTION =
            "WHERE STATUS = 'PENDING' AND AVAILABLE_AT <= CURRENT TIMESTAMP AND ATTEMPTS < 5";
    private static final String UPDATABLE_COLUMNS = "ID, STATUS, OWNER, CLAIM_TOKEN, ATTEMPTS, AVAILABLE_AT, UPDATED_AT";
    private static final String SET_CLAIMED = """
            SET STATUS = 'CLAIMED', OWNER = 'spike', CLAIM_TOKEN = CLAIM_TOKEN + 1, ATTEMPTS = ATTEMPTS + 1,
                AVAILABLE_AT = CURRENT TIMESTAMP + 60 SECONDS, UPDATED_AT = CURRENT TIMESTAMP""";

    @FunctionalInterface
    private interface ClaimStatement {
        List<Long> claim(Connection connection, int n) throws SQLException;
    }

    private record Candidate(String id, String form, ClaimStatement statement) {
    }

    private record Result(Candidate candidate, boolean accepted, boolean skipsLocked, boolean oldestFirst,
                          Duration secondClaimTook, String error) {
        boolean usable() {
            return accepted && skipsLocked;
        }
    }

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("A1", "single statement; SKIP LOCKED DATA ends the UPDATE inside FINAL TABLE", (c, n) -> ids(c, """
                    SELECT ID FROM FINAL TABLE (
                      UPDATE (SELECT %s FROM WORK_ITEM %s ORDER BY AVAILABLE_AT FETCH FIRST %d ROWS ONLY)
                      %s
                      SKIP LOCKED DATA)
                    """.formatted(UPDATABLE_COLUMNS, SELECTION, n, SET_CLAIMED))),
            new Candidate("A2", "single statement; SKIP LOCKED DATA ends the outer SELECT", (c, n) -> ids(c, """
                    SELECT ID FROM FINAL TABLE (
                      UPDATE (SELECT %s FROM WORK_ITEM %s ORDER BY AVAILABLE_AT FETCH FIRST %d ROWS ONLY)
                      %s)
                    SKIP LOCKED DATA
                    """.formatted(UPDATABLE_COLUMNS, SELECTION, n, SET_CLAIMED))),
            new Candidate("A3", "single statement; SKIP LOCKED DATA ends the inner fullselect", (c, n) -> ids(c, """
                    SELECT ID FROM FINAL TABLE (
                      UPDATE (SELECT %s FROM WORK_ITEM %s ORDER BY AVAILABLE_AT FETCH FIRST %d ROWS ONLY SKIP LOCKED DATA)
                      %s)
                    """.formatted(UPDATABLE_COLUMNS, SELECTION, n, SET_CLAIMED))),
            new Candidate("B", "SELECT ... WITH RS USE AND KEEP UPDATE LOCKS SKIP LOCKED DATA, then UPDATE by ID",
                    (c, n) -> lockThenUpdate(c, n, "ORDER BY AVAILABLE_AT")),
            new Candidate("C", "as B, without ORDER BY", (c, n) -> lockThenUpdate(c, n, "")));

    private static HikariDataSource worker;
    private final WorkItems rows = new WorkItems(Db2TestSupport.adminDataSource());

    @BeforeAll
    static void startPool() {
        worker = Db2TestSupport.workerDataSource("wq-it-spike", 2);
    }

    @AfterAll
    static void closePool() {
        worker.close();
    }

    @Test
    void reportWhichClaimFormsDb2Accepts() throws Exception {
        List<Result> results = new ArrayList<>();
        for (Candidate candidate : CANDIDATES) {
            results.add(evaluate(candidate));
        }

        String report = render(results);
        System.out.println(report);
        Files.writeString(Path.of("target", "claim-sql-spike.md"), report);

        assertThat(results).as("at least one claim form must be accepted and skip locked rows").anyMatch(Result::usable);
    }

    private Result evaluate(Candidate candidate) throws SQLException {
        rows.deleteAll();
        List<Long> oldestFirst = new ArrayList<>();
        for (int age = 10; age <= 50; age += 10) {
            long id = rows.insert();
            rows.setAvailableAt(id, -age);
            oldestFirst.addFirst(id);
        }
        try (Connection first = worker.getConnection(); Connection second = worker.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try {
                List<Long> firstClaim;
                try {
                    firstClaim = candidate.statement().claim(first, 1);
                } catch (SQLException e) {
                    return new Result(candidate, false, false, false, Duration.ZERO, describe(e));
                }
                long start = System.nanoTime();
                try {
                    List<Long> secondClaim = candidate.statement().claim(second, 2);
                    Duration took = Duration.ofNanos(System.nanoTime() - start);
                    boolean skipsLocked = firstClaim.size() == 1 && secondClaim.size() == 2
                            && !secondClaim.contains(firstClaim.getFirst());
                    boolean ordered = firstClaim.equals(oldestFirst.subList(0, 1))
                            && new HashSet<>(secondClaim).equals(Set.copyOf(oldestFirst.subList(1, 3)));
                    return new Result(candidate, true, skipsLocked, ordered, took, "");
                } catch (SQLException e) {
                    return new Result(candidate, true, false, false, Duration.ofNanos(System.nanoTime() - start), describe(e));
                }
            } finally {
                first.rollback();
                second.rollback();
            }
        }
    }

    private static List<Long> ids(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
            return ids;
        }
    }

    private static List<Long> lockThenUpdate(Connection connection, int n, String orderBy) throws SQLException {
        List<Long> locked = ids(connection, "SELECT ID FROM WORK_ITEM %s %s FETCH FIRST %d ROWS ONLY WITH RS USE AND KEEP UPDATE LOCKS SKIP LOCKED DATA"
                .formatted(SELECTION, orderBy, n));
        if (locked.isEmpty()) {
            return locked;
        }
        String idList = locked.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return ids(connection, "SELECT ID FROM FINAL TABLE (UPDATE WORK_ITEM %s WHERE ID IN (%s))".formatted(SET_CLAIMED, idList));
    }

    private static String describe(SQLException e) {
        return ("SQLCODE=" + e.getErrorCode() + " SQLSTATE=" + e.getSQLState() + " " + e.getMessage())
                .replace('|', '/').replace('\n', ' ');
    }

    private static String render(List<Result> results) {
        StringBuilder out = new StringBuilder("""
                | Id | Form | Accepted | Skips locked rows | Oldest first | Second claim took | Error |
                |---|---|---|---|---|---|---|
                """);
        for (Result r : results) {
            out.append("| %s | %s | %s | %s | %s | %d ms | %s |%n".formatted(r.candidate().id(), r.candidate().form(),
                    r.accepted(), r.skipsLocked(), r.oldestFirst(), r.secondClaimTook().toMillis(), r.error()));
        }
        return out.toString();
    }
}
