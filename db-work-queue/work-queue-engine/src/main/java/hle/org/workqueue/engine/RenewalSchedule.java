package hle.org.workqueue.engine;

import java.time.Duration;

/**
 * When the next renewal round starts (spec §5.3): after a successful round that started at {@code s} and
 * ended at {@code e}, at {@code max(s + I, e)}; after a failed round, at {@code e + d}. Times are
 * {@code System.nanoTime()} readings, compared overflow-safely. QueueRunner and LeaseSimulationTest share it.
 *
 * @param interval   I, renew-interval
 * @param retryDelay d, renew-retry-delay
 */
public record RenewalSchedule(Duration interval, Duration retryDelay) {

    public RenewalSchedule {
        Durations.requirePositive("interval", interval);
        Durations.requirePositive("retryDelay", retryDelay);
    }

    public long next(long start, long end, boolean succeeded) {
        if (end - start < 0) {
            throw new IllegalArgumentException("a round cannot end before it starts: start " + start + ", end " + end);
        }
        if (!succeeded) {
            return end + retryDelay.toNanos();
        }
        long earliest = start + interval.toNanos();
        return earliest - end >= 0 ? earliest : end;
    }
}
