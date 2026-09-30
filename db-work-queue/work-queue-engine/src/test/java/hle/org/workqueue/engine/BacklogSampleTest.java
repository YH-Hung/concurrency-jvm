package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacklogSampleTest {

    @Test
    void rejectsANegativeCount() {
        assertThatThrownBy(() -> new BacklogSample(-1, 0, 0, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pending");
        assertThatThrownBy(() -> new BacklogSample(0, -1, 0, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claimed");
        assertThatThrownBy(() -> new BacklogSample(0, 0, -1, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failed");
        assertThatThrownBy(() -> new BacklogSample(0, 0, 0, -1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expiredClaims");
    }

    @Test
    void rejectsAMissingOrNegativeAge() {
        assertThatThrownBy(() -> new BacklogSample(0, 0, 0, 0, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("oldestPendingAge");
        assertThatThrownBy(() -> new BacklogSample(0, 0, 0, 0, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oldestPendingAge");
    }
}
