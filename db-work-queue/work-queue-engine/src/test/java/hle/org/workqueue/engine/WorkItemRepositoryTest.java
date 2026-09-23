package hle.org.workqueue.engine;

import hle.org.workqueue.engine.WorkItemRepository.Settings;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemRepositoryTest {

    private final WorkItemRepository repository = new WorkItemRepository(
            new DriverManagerDataSource(), DbTimeouts.defaults(), new Settings(ofSeconds(90), 5, ofSeconds(5)));

    @Test
    void settingsRejectAFractionalLease() {
        assertThatThrownBy(() -> new Settings(ofMillis(1500), 5, ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
    }

    @Test
    void settingsRejectFewerThanOneAttempt() {
        assertThatThrownBy(() -> new Settings(ofSeconds(90), 0, ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxAttempts");
    }

    @Test
    void settingsRejectANegativeOrOversizedRetryBackoff() {
        assertThatThrownBy(() -> new Settings(ofSeconds(90), 5, ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retryBackoff");
        assertThatThrownBy(() -> new Settings(ofSeconds(90), 5, WorkItemRepository.MAX_RETRY_BACKOFF.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retryBackoff");
    }

    @Test
    void claimRejectsABlankOrOversizedOwner() {
        assertThatThrownBy(() -> repository.claim(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claim("o".repeat(WorkItemRepository.MAX_OWNER_BYTES + 1), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimRejectsAnEmptyBatch() {
        assertThatThrownBy(() -> repository.claim("owner-a", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimSurfacesAConnectionFailureAsADataAccessException() {
        WorkItemRepository unreachable = new WorkItemRepository(new DriverManagerDataSource("jdbc:unknown:nowhere"),
                DbTimeouts.defaults(), new Settings(ofSeconds(90), 5, ofSeconds(5)));

        assertThatThrownBy(() -> unreachable.claim("owner-a", 1)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void completeAndRetryOrFailRejectANullClaim() {
        assertThatThrownBy(() -> repository.complete("owner-a", null, "result"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.retryOrFail("owner-a", null, "boom"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void truncateUtf8KeepsShortTextUnchanged() {
        assertThat(WorkItemRepository.truncateUtf8("boom", 1000)).isEqualTo("boom");
    }

    @Test
    void truncateUtf8CutsToTheByteLimit() {
        assertThat(WorkItemRepository.truncateUtf8("x".repeat(1500), 1000)).hasSize(1000);
    }

    @Test
    void truncateUtf8NeverSplitsAMultiByteCharacter() {
        String text = "a".repeat(999) + "é";   // é is two bytes: 1001 bytes in total

        assertThat(WorkItemRepository.truncateUtf8(text, 1000)).isEqualTo("a".repeat(999));
    }
}
