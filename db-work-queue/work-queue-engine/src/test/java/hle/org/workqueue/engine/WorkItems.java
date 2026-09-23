package hle.org.workqueue.engine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Test-only access to WORK_ITEM through the admin pool: seeding, forcing state, reading rows. */
final class WorkItems {

    record Row(long id, String operationId, String payload, String status, String owner, long claimToken,
               int attempts, String resultValue, String lastError, LocalDateTime availableAt, LocalDateTime updatedAt) {
    }

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    WorkItems(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    void deleteAll() {
        jdbc.sql("DELETE FROM WORK_ITEM").update();
    }

    /** The upstream insert contract (spec §4): only OPERATION_ID and PAYLOAD. */
    long insert(String operationId, String payload) {
        return jdbc.sql("SELECT ID FROM FINAL TABLE (INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (:operationId, :payload))")
                .param("operationId", operationId)
                .param("payload", payload)
                .query(Long.class)
                .single();
    }

    long insert() {
        return insert(UUID.randomUUID().toString(), "payload");
    }

    /** Replaces every row with {@code count} new PENDING rows and returns their ids. */
    List<Long> seed(int count) {
        deleteAll();
        List<Object[]> args = IntStream.range(0, count)
                .mapToObj(i -> new Object[] {UUID.randomUUID().toString(), "payload-" + i})
                .toList();
        new JdbcTemplate(dataSource).batchUpdate("INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (?, ?)", args);
        return jdbc.sql("SELECT ID FROM WORK_ITEM ORDER BY ID").query(Long.class).list();
    }

    void setAvailableAt(long id, int secondsFromNow) {
        jdbc.sql("UPDATE WORK_ITEM SET AVAILABLE_AT = CURRENT TIMESTAMP + (CAST(:seconds AS INTEGER)) SECONDS WHERE ID = :id")
                .param("seconds", secondsFromNow)
                .param("id", id)
                .update();
    }

    void forceExpiry(long id) {
        setAvailableAt(id, -1);
    }

    void setAttempts(long id, int attempts) {
        jdbc.sql("UPDATE WORK_ITEM SET ATTEMPTS = :attempts WHERE ID = :id").param("attempts", attempts).param("id", id).update();
    }

    void setStatus(long id, String status) {
        jdbc.sql("UPDATE WORK_ITEM SET STATUS = :status WHERE ID = :id").param("status", status).param("id", id).update();
    }

    /** Makes the row a claim held by {@code owner}; a negative {@code availableInSeconds} makes it expired. */
    void setClaim(long id, String owner, long token, int attempts, int availableInSeconds) {
        jdbc.sql("""
                UPDATE WORK_ITEM
                   SET STATUS = 'CLAIMED', OWNER = :owner, CLAIM_TOKEN = :token, ATTEMPTS = :attempts,
                       AVAILABLE_AT = CURRENT TIMESTAMP + (CAST(:seconds AS INTEGER)) SECONDS
                 WHERE ID = :id""")
                .param("owner", owner)
                .param("token", token)
                .param("attempts", attempts)
                .param("seconds", availableInSeconds)
                .param("id", id)
                .update();
    }

    void setLastError(long id, String lastError) {
        jdbc.sql("UPDATE WORK_ITEM SET LAST_ERROR = :lastError WHERE ID = :id").param("lastError", lastError).param("id", id).update();
    }

    void setUpdatedAt(long id, int secondsFromNow) {
        jdbc.sql("UPDATE WORK_ITEM SET UPDATED_AT = CURRENT TIMESTAMP + (CAST(:seconds AS INTEGER)) SECONDS WHERE ID = :id")
                .param("seconds", secondsFromNow)
                .param("id", id)
                .update();
    }

    LocalDateTime dbNow() {
        return jdbc.sql("SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1").query(Timestamp.class).single().toLocalDateTime();
    }

    Row row(long id) {
        return jdbc.sql("""
                SELECT ID, OPERATION_ID, PAYLOAD, STATUS, OWNER, CLAIM_TOKEN, ATTEMPTS, RESULT_VALUE, LAST_ERROR,
                       AVAILABLE_AT, UPDATED_AT
                  FROM WORK_ITEM WHERE ID = :id""")
                .param("id", id)
                .query((rs, rowNum) -> new Row(rs.getLong("ID"), rs.getString("OPERATION_ID"), rs.getString("PAYLOAD"),
                        rs.getString("STATUS"), rs.getString("OWNER"), rs.getLong("CLAIM_TOKEN"), rs.getInt("ATTEMPTS"),
                        rs.getString("RESULT_VALUE"), rs.getString("LAST_ERROR"),
                        rs.getTimestamp("AVAILABLE_AT").toLocalDateTime(), rs.getTimestamp("UPDATED_AT").toLocalDateTime()))
                .single();
    }

    /** AVAILABLE_AT minus the Db2 clock: positive while a lease or backoff runs, negative once claimable. */
    Duration availableIn(long id) {
        return jdbc.sql("SELECT AVAILABLE_AT, CURRENT TIMESTAMP AS DB_NOW FROM WORK_ITEM WHERE ID = :id")
                .param("id", id)
                .query((rs, rowNum) -> Duration.between(
                        rs.getTimestamp("DB_NOW").toLocalDateTime(), rs.getTimestamp("AVAILABLE_AT").toLocalDateTime()))
                .single();
    }

    int count(String predicate) {
        return jdbc.sql("SELECT COUNT(*) FROM WORK_ITEM WHERE " + predicate).query(Integer.class).single();
    }
}
