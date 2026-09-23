package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IT 4 of spec §11.2: statements return within their time bounds on the real JCC driver. */
class QueryTimeoutIT {

    private static HikariDataSource worker;
    private static WorkItemRepository repository;

    private final WorkItems rows = new WorkItems(Db2TestSupport.adminDataSource());

    @BeforeAll
    static void startPool() {
        worker = Db2TestSupport.workerDataSource("wq-it-query-timeout", 4);
        repository = Db2TestSupport.repository(worker);
    }

    @AfterAll
    static void closePool() {
        worker.close();
    }

    @BeforeEach
    void clean() {
        rows.deleteAll();
    }

    @Test
    void aStatementStuckPastTheTransactionTimeoutReturnsWithinTTxPlusOneSecond() throws Exception {
        // IT values, except T_read = 30s: only the query timeout (close-socket mode) can end the statement.
        DbTimeouts slowReads = new DbTimeouts(ofMillis(500), ofSeconds(1), ofSeconds(2), ofSeconds(30), ofSeconds(1));
        long id = rows.insert();
        try (HikariDataSource pool = Db2TestSupport.workerDataSource("wq-it-slow-read", 2, slowReads);
             Connection holder = lockRow(id)) {
            WorkItemRepository slowReadRepository = new WorkItemRepository(pool, slowReads, Db2TestSupport.IT_SETTINGS);

            long start = System.nanoTime();
            assertThatThrownBy(() -> slowReadRepository.inTransaction(jdbc -> {
                jdbc.sql("SET CURRENT LOCK TIMEOUT 30").update();
                return jdbc.sql("UPDATE WORK_ITEM SET UPDATED_AT = CURRENT TIMESTAMP WHERE ID = :id").param("id", id).update();
            })).isInstanceOf(DataAccessException.class)
                    .satisfies(e -> assertThat(firstSqlException(e).getSQLState()).as("JCC close-socket query timeout").isEqualTo("08001"));
            assertThat(elapsedSince(start)).isLessThan(slowReads.transaction().plusSeconds(1));

            holder.rollback();
            // The timed-out connection was closed; the pool must hand out a working one.
            assertThat(slowReadRepository.readNamespace()).isEqualTo(Db2TestSupport.NAMESPACE);
        }
    }

    @Test
    void aRowLockWaitEndsWithALockTimeoutWithinTLockPlusOneSecond() throws Exception {
        long id = rows.insert();
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();
        try (Connection holder = lockRow(id)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> repository.complete("owner-a", key, "result"))
                    .satisfies(e -> assertThat(firstSqlException(e).getErrorCode()).as("Db2 lock timeout").isIn(-911, -913));
            assertThat(elapsedSince(start)).isLessThan(Db2TestSupport.IT_TIMEOUTS.lockWait().plusSeconds(1));
            holder.rollback();
        }
        assertThat(repository.complete("owner-a", key, "result")).isEqualTo(PersistResult.DONE);
    }

    /** Holds a row lock on an admin connection until the caller rolls it back or closes it. */
    private static Connection lockRow(long id) throws SQLException {
        Connection holder = Db2TestSupport.adminDataSource().getConnection();
        holder.setAutoCommit(false);
        try (PreparedStatement update = holder.prepareStatement("UPDATE WORK_ITEM SET UPDATED_AT = CURRENT TIMESTAMP WHERE ID = ?")) {
            update.setLong(1, id);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        return holder;
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static SQLException firstSqlException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        throw new AssertionError("no SQLException in the cause chain", e);
    }
}
