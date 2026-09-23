package hle.org.workqueue.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemSchemaIT {

    private final JdbcClient admin = JdbcClient.create(Db2TestSupport.adminDataSource());
    private final WorkItems rows = new WorkItems(Db2TestSupport.adminDataSource());

    @BeforeEach
    void clean() {
        rows.deleteAll();
    }

    @AfterEach
    void removeStrayNamespaces() {
        admin.sql("DELETE FROM WORK_QUEUE_META WHERE NAMESPACE <> :namespace").param("namespace", Db2TestSupport.NAMESPACE).update();
    }

    @Test
    void migrationWritesTheNamespacePlaceholder() {
        assertThat(admin.sql("SELECT NAMESPACE FROM WORK_QUEUE_META").query(String.class).list())
                .containsExactly(Db2TestSupport.NAMESPACE);
    }

    @Test
    void namespaceContainingAColonIsRejected() {
        assertThatThrownBy(() -> admin.sql("INSERT INTO WORK_QUEUE_META (NAMESPACE) VALUES ('a:b')").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upstreamInsertMakesTheRowImmediatelyClaimable() {
        long id = rows.insert("order-8812:charge", "payload");

        WorkItems.Row row = rows.row(id);
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.operationId()).isEqualTo("order-8812:charge");
        assertThat(row.claimToken()).isZero();
        assertThat(row.attempts()).isZero();
        assertThat(row.owner()).isNull();
        assertThat(rows.availableIn(id)).isLessThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void duplicateOperationIdIsRejected() {
        rows.insert("op-duplicate", "first");

        assertThatThrownBy(() -> rows.insert("op-duplicate", "second")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void unknownStatusIsRejected() {
        long id = rows.insert();

        assertThatThrownBy(() -> rows.setStatus(id, "BOGUS")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
