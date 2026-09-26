package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenewalScheduleTest {

    private static final long SECOND = ofSeconds(1).toNanos();
    private static final RenewalSchedule SCHEDULE = new RenewalSchedule(ofSeconds(15), ofSeconds(1));

    @Test
    void aRoundThatEndsEarlyIsFollowedOneIntervalAfterItsStart() {
        assertThat(SCHEDULE.next(0, 2 * SECOND, true)).isEqualTo(15 * SECOND);
    }

    @Test
    void aRoundThatEndsExactlyAtTheIntervalIsFollowedThen() {
        assertThat(SCHEDULE.next(0, 15 * SECOND, true)).isEqualTo(15 * SECOND);
    }

    @Test
    void aRoundThatOverrunsTheIntervalIsFollowedAsSoonAsItEnds() {
        assertThat(SCHEDULE.next(0, 18 * SECOND, true)).isEqualTo(18 * SECOND);
    }

    @Test
    void aFailedRoundIsRetriedTheRetryDelayAfterItEnds() {
        assertThat(SCHEDULE.next(0, 5 * SECOND, false)).isEqualTo(6 * SECOND);
        assertThat(SCHEDULE.next(0, 0, false)).isEqualTo(SECOND);
    }

    @Test
    void comparesNanoTimeReadingsAcrossOverflow() {
        long start = Long.MAX_VALUE - SECOND;

        assertThat(SCHEDULE.next(start, start + 2 * SECOND, true)).isEqualTo(start + 15 * SECOND);
        assertThat(SCHEDULE.next(start, start + 20 * SECOND, true)).isEqualTo(start + 20 * SECOND);
    }

    @Test
    void rejectsARoundThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> SCHEDULE.next(SECOND, 0, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveSettings() {
        assertThatThrownBy(() -> new RenewalSchedule(Duration.ZERO, ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interval");
        assertThatThrownBy(() -> new RenewalSchedule(ofSeconds(15), ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retryDelay");
    }
}
