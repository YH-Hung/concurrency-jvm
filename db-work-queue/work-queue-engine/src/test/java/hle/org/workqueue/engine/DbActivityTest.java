package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

class DbActivityTest {

    private static final long SECOND = 1_000_000_000L;

    // Starts 5s before overflow, so the ages below wrap around.
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final DbActivity db = new DbActivity(now::get);

    @Test
    void beforeTheFirstSuccessTheAgeCountsFromCreation() {
        assertThat(db.lastSuccessAge()).isZero();

        now.addAndGet(7 * SECOND);

        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(7));
    }

    @Test
    void aSuccessRestartsTheAge() {
        now.addAndGet(7 * SECOND);
        db.succeeded();
        now.addAndGet(2 * SECOND);

        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(2));
    }

    @Test
    void aReportThatReadTheClockEarlierDoesNotMoveTheLastSuccessBack() {
        // The clock answers the constructor, a report at 10s, a slower report that read 4s, and the age at 12s.
        AtomicLong reading = new AtomicLong();
        List<Long> readings = List.of(0L, 10 * SECOND, 4 * SECOND, 12 * SECOND);
        DbActivity reordered = new DbActivity(() -> readings.get((int) reading.getAndIncrement()));

        reordered.succeeded();
        reordered.succeeded();

        assertThat(reordered.lastSuccessAge()).isEqualTo(ofSeconds(2));
    }

    @Test
    void theAgeIsNeverNegative() {
        now.addAndGet(-1);

        assertThat(db.lastSuccessAge()).isZero();
    }
}
