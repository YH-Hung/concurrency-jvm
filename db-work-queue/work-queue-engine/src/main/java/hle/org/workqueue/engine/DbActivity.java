package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * When an engine DB operation last succeeded (spec §9.6 {@code db.last_success_age}). The claims, renewal rounds,
 * sweeps and backlog samples that return report here; readiness turns DOWN once the age passes db-staleness-limit.
 * The BacklogSampler keeps a second one for its own samples ({@code backlog.sample_age}). Times are readings of the
 * runner's clock, compared overflow-safely.
 */
final class DbActivity {

    private final LongSupplier clock;
    private final AtomicLong lastSuccess;

    /** Until the first success, the age counts from here: the runner's creation at startup. */
    DbActivity(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastSuccess = new AtomicLong(clock.getAsLong());
    }

    void succeeded() {
        long now = clock.getAsLong();
        // Loops report concurrently: a report that read the clock earlier but arrives later must not move it back.
        lastSuccess.accumulateAndGet(now, (last, reported) -> reported - last > 0 ? reported : last);
    }

    Duration lastSuccessAge() {
        return ageAt(clock.getAsLong());
    }

    Duration ageAt(long now) {
        return Duration.ofNanos(Math.max(0, now - lastSuccess.get()));
    }
}
