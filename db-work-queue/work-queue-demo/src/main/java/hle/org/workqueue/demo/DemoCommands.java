package hle.org.workqueue.demo;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Demo-only maintenance. Workers never invoke these commands. */
public final class DemoCommands {
    private final DataSource source;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    @FunctionalInterface interface Sleeper { void sleep(long nanos) throws InterruptedException; }

    public DemoCommands(DataSource source) {
        this(source, System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
    }
    DemoCommands(DataSource source, LongSupplier nanoTime, Sleeper sleeper) {
        this.source = source;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public void migrate(String namespace) throws SQLException {
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9-]{0,31}"))
            throw new IllegalArgumentException("Invalid demo namespace");
        try (var connection = source.getConnection()) { requireDemoDatabase(connection); }
        Flyway.configure().dataSource(source).locations("classpath:db/migration/workqueue")
            .placeholders(Map.of("workqueueNamespace", namespace)).cleanDisabled(true).load().migrate();
    }

    /** Returns IDs only after the entire batch commits. No existing rows are modified. */
    public List<Long> seed(int count) throws SQLException {
        if (count <= 0) throw new IllegalArgumentException("demo.count must be positive");
        try (var connection = source.getConnection()) {
            requireDemoDatabase(connection);
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("""
                    SELECT ID FROM FINAL TABLE (
                        INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (?, ?)
                    )
                    """)) {
                insert.setQueryTimeout(5);
                var ids = new ArrayList<Long>();
                for (int i = 1; i <= count; i++) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, "job-" + i);
                    try (var row = insert.executeQuery()) {
                        if (!row.next()) throw new SQLException("Insert did not return an ID");
                        ids.add(row.getLong(1));
                    }
                }
                connection.commit();
                return List.copyOf(ids);
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    public record Verification(int total, int done, int failed, int pending, int claimed, int missing) {
        public boolean succeeded() { return total > 0 && done == total && failed == 0 && pending == 0 && claimed == 0 && missing == 0; }
    }

    public Verification verify(List<Long> ids, Duration timeout) throws SQLException, InterruptedException {
        validateIds(ids);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("demo.timeout must be positive");
        final long allowance;
        try { allowance = timeout.toNanos(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("demo.timeout is too large"); }
        // Elapsed subtraction remains correct across nanoTime wraparound; no start + timeout overflow.
        long start = nanoTime.getAsLong();
        try (var connection = source.getConnection()) { requireDemoDatabase(connection); }
        while (true) {
            Verification result = snapshot(ids);
            long remaining = allowance - (nanoTime.getAsLong() - start);
            if (result.succeeded() || result.failed() > 0 || result.missing() > 0 || remaining <= 0) return result;
            sleeper.sleep(Math.min(remaining, Duration.ofMillis(250).toNanos()));
        }
    }

    private Verification snapshot(List<Long> ids) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT ID, STATUS FROM WORK_ITEM WHERE ID IN (" + placeholders + ")")) {
            statement.setQueryTimeout(5);
            for (int i = 0; i < ids.size(); i++) statement.setLong(i + 1, ids.get(i));
            int done = 0, failed = 0, pending = 0, claimed = 0;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    switch (rows.getString(2)) {
                        case "DONE" -> done++;
                        case "FAILED" -> failed++;
                        case "PENDING" -> pending++;
                        case "CLAIMED" -> claimed++;
                        default -> throw new IllegalStateException("Unknown job status");
                    }
                }
            }
            return new Verification(ids.size(), done, failed, pending, claimed, ids.size() - done - failed - pending - claimed);
        }
    }

    static void validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("Batch must contain distinct positive row IDs");
    }
    private void requireDemoDatabase(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("VALUES CURRENT SERVER")) {
            statement.setQueryTimeout(5);
            try (var row = statement.executeQuery()) {
                if (!row.next() || !"WORKQ".equals(row.getString(1).trim()))
                    throw new IllegalStateException("Demo maintenance requires the dedicated WORKQ database");
            }
        }
    }
}
