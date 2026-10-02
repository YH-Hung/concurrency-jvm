package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static hle.org.workqueue.engine.Durations.seconds;

/**
 * The startup timing constraints B1–B5 of spec §5.3. {@link #check} fails startup with an
 * IllegalStateException that names every violated constraint; a budget exists only for a configuration
 * that passes, and reports the timing values the spec derives from it.
 */
final class TimingBudget {

    private final LeaseTiming leaseTiming;

    private TimingBudget(LeaseTiming leaseTiming) {
        this.leaseTiming = leaseTiming;
    }

    /**
     * @param poolSize the maximum size of the engine's connection pool
     * @throws IllegalArgumentException if a setting the constraints use is out of range
     * @throws IllegalStateException    if any of B1–B5 is violated
     */
    public static TimingBudget check(WorkQueueProperties properties, int poolSize) {
        Objects.requireNonNull(properties, "properties");
        requireInRange(properties, poolSize);
        DbTimeouts db = properties.getDb().toTimeouts();
        Duration w = db.worstCaseOperation();
        LeaseTiming leaseTiming = new LeaseTiming(properties.getRenewInterval(), w, properties.getRenewRetryDelay(),
                properties.getRegistrationAllowance(), properties.getLeaseDuration());

        List<String> violations = new ArrayList<>();
        if (db.lockWait().compareTo(db.transaction()) >= 0) {
            violations.add("B1: T_lock < T_tx, but " + seconds(db.lockWait()) + " >= " + seconds(db.transaction()));
        }
        if (!leaseTiming.b2Holds()) {
            violations.add("B2: max(I, W) + 3W + d + G < L, but "
                    + seconds(leaseTiming.b2Bound()) + " >= " + seconds(properties.getLeaseDuration()));
        }
        long minPoolSize = properties.getConcurrency() + 4L;
        if (poolSize < minPoolSize) {
            violations.add("B3: pool size >= concurrency + 4, but " + poolSize + " < " + minPoolSize);
        }
        // The deadline counts from the claim's return, and the task starts up to G later.
        Duration slowHealthyTask = properties.getRegistrationAllowance()
                .plus(properties.getExternalCallTimeout())
                .plus(w.multipliedBy(properties.getCompletionRetries() + 1L))
                .plus(properties.getCompletionRetryDelay().multipliedBy(properties.getCompletionRetries()));
        if (properties.getMaxProcessingTime().compareTo(slowHealthyTask) < 0) {
            violations.add("B4: max-processing-time >= G + external-call-timeout + (completion-retries + 1)·W"
                    + " + completion-retries·completion-retry-delay, but "
                    + seconds(properties.getMaxProcessingTime()) + " < " + seconds(slowHealthyTask));
        }
        Duration idleStaleness = properties.getIdlePollInterval().multipliedBy(3).dividedBy(2).plus(w);
        if (properties.getDbStalenessLimit().compareTo(idleStaleness) <= 0) {
            violations.add("B5: db-staleness-limit > 1.5·idle-poll-interval + W, but "
                    + seconds(properties.getDbStalenessLimit()) + " <= " + seconds(idleStaleness));
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Timing budget violated (spec §5.3): " + String.join("; ", violations));
        }
        return new TimingBudget(leaseTiming);
    }

    /** W: the worst case for one DB operation. */
    public Duration worstCaseOperation() {
        return leaseTiming.worstCaseOperation();
    }

    /** E5: an outage up to this long loses no claim (the lease-preservation target, spec §7). */
    public Duration leasePreservationTarget() {
        return leaseTiming.leasePreservationTarget();
    }

    LeaseTiming leaseTiming() {
        return leaseTiming;
    }

    private static void requireInRange(WorkQueueProperties properties, int poolSize) {
        if (properties.getConcurrency() < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1: " + properties.getConcurrency());
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("pool size must be at least 1: " + poolSize);
        }
        if (properties.getCompletionRetries() < 0) {
            throw new IllegalArgumentException("completion-retries must not be negative: " + properties.getCompletionRetries());
        }
        Durations.requirePositiveWholeSeconds("lease-duration", properties.getLeaseDuration());
        Durations.requirePositive("renew-interval", properties.getRenewInterval());
        Durations.requirePositive("renew-retry-delay", properties.getRenewRetryDelay());
        Durations.requirePositive("registration-allowance", properties.getRegistrationAllowance());
        Durations.requirePositive("idle-poll-interval", properties.getIdlePollInterval());
        Durations.requirePositive("external-call-timeout", properties.getExternalCallTimeout());
        Durations.requirePositive("completion-retry-delay", properties.getCompletionRetryDelay());
        Durations.requirePositive("max-processing-time", properties.getMaxProcessingTime());
        Durations.requirePositive("db-staleness-limit", properties.getDbStalenessLimit());
    }
}
