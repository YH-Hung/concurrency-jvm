package hle.org.workqueue.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.stream.Stream;

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

    @ParameterizedTest
    @MethodSource("canonicalOperationIds")
    void canonicalOperationIdIsAccepted(String operationId) {
        long id = rows.insert(operationId, "payload");

        assertThat(rows.row(id).operationId()).isEqualTo(operationId);
    }

    @ParameterizedTest
    @MethodSource("nonCanonicalOperationIds")
    void nonCanonicalOperationIdIsRejected(String operationId) {
        assertThatThrownBy(() -> rows.insert(operationId, "payload")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rows.count("1 = 1")).isZero();
    }

    @ParameterizedTest
    @MethodSource("canonicalOperationIds")
    void operationIdDifferingOnlyInTrailingBlanksIsRejectedAsInvalidNotAsDuplicate(String operationId) {
        rows.insert(operationId, "first");

        // Db2 compares strings blank-padded and checks uniqueness before the format CHECK: with a unique key on
        // OPERATION_ID alone this insert fails as a duplicate, and a producer following the insert contract drops
        // it as "already enqueued". At 64 characters, a column of exactly 64 would also cut the blank off on
        // assignment, before the CHECK and the unique key see the value.
        assertThatThrownBy(() -> rows.insert(operationId + " ", "second"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
        assertThat(rows.count("1 = 1")).isEqualTo(1);
    }

    @Test
    void operationIdsDifferingOnlyInCaseAreDistinct() {
        rows.insert("order-1", "lower");
        rows.insert("ORDER-1", "upper");

        assertThat(rows.count("1 = 1")).isEqualTo(2);
    }

    @Test
    void unknownStatusIsRejected() {
        long id = rows.insert();

        assertThatThrownBy(() -> rows.setStatus(id, "BOGUS")).isInstanceOf(DataIntegrityViolationException.class);
    }

    static Stream<String> canonicalOperationIds() {
        return Stream.of("!", "~", "order-8812:charge", "0f8fad5b-d9cb-411f-a162-f0cb3c1b4d9b", "x".repeat(64));
    }

    static Stream<Named<String>> nonCanonicalOperationIds() {
        return Stream.of(
                Named.of("empty", ""),
                Named.of("blank", " "),
                Named.of("trailing blank", "order-1 "),
                Named.of("leading blank", " order-1"),
                Named.of("inner blank", "order 1"),
                Named.of("tab", "order-1\t"),
                Named.of("trailing newline", "order-1\n"),
                Named.of("DEL", "order-1\u007F"),
                Named.of("non-ASCII letter", "caf\u00E9"),
                Named.of("22 characters, 66 UTF-8 bytes", "\u6F22".repeat(22)),
                Named.of("64 characters and a trailing blank", "x".repeat(64) + " "),
                Named.of("64 characters and two trailing blanks", "x".repeat(64) + "  "),
                Named.of("65 characters", "x".repeat(65)),
                Named.of("65 characters and a trailing blank", "x".repeat(65) + " "),
                Named.of("66 characters", "x".repeat(66)));
    }
}
