package hle.org.workqueue.engine;

import java.math.BigDecimal;
import org.springframework.dao.DataAccessException;

/** Read-only startup gate. A failed check never starts workers and never performs DDL. */
final class SchemaCheck {
    private final WorkItemRepository repository;
    SchemaCheck(WorkItemRepository repository) { this.repository = repository; }
    String verify(String expectedNamespace) {
        try {
            return repository.inTransaction(jdbc -> {
                long migrations = jdbc.sql("""
                    SELECT COUNT(*) FROM "flyway_schema_history"
                    WHERE "version" = '1' AND "type" = 'SQL'
                      AND "script" = 'V1__work_queue.sql' AND "success" = 1
                    """).query(Long.class).single();
                if (migrations != 1) throw new IllegalStateException("Schema check failed: apply the engine V1 migration before starting workers");
                jdbc.sql("""
                    SELECT ID, OPERATION_ID, PAYLOAD, STATUS, AVAILABLE_AT, OWNER, CLAIM_TOKEN,
                           ATTEMPTS, RESULT_VALUE, LAST_ERROR, CREATED_AT, UPDATED_AT
                    FROM WORK_ITEM WHERE 1 = 0
                    """).query((rs, row) -> rs.getLong(1)).list();
                var namespaces = jdbc.sql("SELECT NAMESPACE FROM WORK_QUEUE_META").query(String.class).list();
                if (namespaces.size() != 1) throw new IllegalStateException("Schema check failed: WORK_QUEUE_META must contain exactly one namespace");
                String actual = namespaces.getFirst();
                try { IdempotencyKey.requireNamespace(actual); }
                catch (RuntimeException e) { throw new IllegalStateException("Schema check failed: stored namespace is invalid"); }
                if (!actual.equals(expectedNamespace)) throw new IllegalStateException("Schema check failed: workqueue.expected-namespace does not match the database");
                BigDecimal timezone = jdbc.sql("VALUES CURRENT TIMEZONE").query(BigDecimal.class).single();
                if (timezone.compareTo(BigDecimal.ZERO) != 0) throw new IllegalStateException("Schema check failed: database must use UTC (CURRENT TIMEZONE = 0)");
                return actual;
            });
        } catch (DataAccessException e) {
            // Attaching the original cause would make Spring print the raw driver message at startup.
            throw new IllegalStateException("Schema check failed: verify the migration, database access and connection settings; " + Diagnostics.describe(e));
        }
    }
}
