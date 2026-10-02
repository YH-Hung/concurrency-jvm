package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The BacklogSampler (spec §6): one query for the DB-wide gauges of spec §9.6, which QueueRunner's sampler loop runs
 * every backlog-sample-interval. The gauges read the latest successful sample: none before the first, and the last
 * one after a failure. {@link #age()} shows how old that sample is ({@code backlog.sample_age}): a claim, renewal or
 * sweep keeps {@code db.last_success_age} fresh, but not the sample.
 */
final class BacklogSampler {

    private static final Logger log = LoggerFactory.getLogger(BacklogSampler.class);

    private final WorkItemRepository repository;
    private final String owner;
    private final DbActivity db;
    private final DbActivity sampled;
    private final AtomicLong errors = new AtomicLong();
    private volatile BacklogSample latest;

    /** Until the first sample, its age counts from here: the runner's creation at startup. */
    BacklogSampler(WorkItemRepository repository, String owner, DbActivity db, LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.db = Objects.requireNonNull(db, "db");
        this.sampled = new DbActivity(clock);
    }

    /** Takes one sample. A failure is counted, logged by its diagnostics, and keeps the previous sample. */
    boolean sampleOnce() {
        try {
            latest = Objects.requireNonNull(repository.sampleBacklog(), "sample");
            sampled.succeeded();
            db.succeeded();
            return true;
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            log.warn("Backlog sample by owner {} failed: {}", owner, Diagnostics.describe(e));
            return false;
        }
    }

    EngineSnapshot.Backlog snapshot(long now) {
        return new EngineSnapshot.Backlog(latest, sampled.ageAt(now), errors.get());
    }

    /** The latest successful sample, or null before the first. */
    BacklogSample latest() {
        return latest;
    }

    /** Time since the latest successful sample returned. */
    Duration age() {
        return sampled.lastSuccessAge();
    }

    /** Samples that failed. */
    long errors() {
        return errors.get();
    }
}
