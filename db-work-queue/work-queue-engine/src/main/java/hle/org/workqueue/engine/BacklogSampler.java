package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The BacklogSampler (spec §6): one query for the DB-wide gauges of spec §9.6, which QueueRunner's sampler loop runs
 * every backlog-sample-interval. The gauges read the latest successful sample: none before the first, and the last
 * one after a failure. Nothing yet shows how old that sample is ({@code db.last_success_age} also counts claims,
 * renewals and sweeps), so while sampling keeps failing the gauges hold their last values.
 */
final class BacklogSampler {

    private static final Logger log = LoggerFactory.getLogger(BacklogSampler.class);

    private final WorkItemRepository repository;
    private final String owner;
    private final DbActivity db;
    private volatile BacklogSample latest;

    BacklogSampler(WorkItemRepository repository, String owner, DbActivity db) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.db = Objects.requireNonNull(db, "db");
    }

    /** Takes one sample. A failure is logged by its diagnostics and keeps the previous sample. */
    boolean sampleOnce() {
        try {
            latest = Objects.requireNonNull(repository.sampleBacklog(), "sample");
            db.succeeded();
            return true;
        } catch (RuntimeException e) {
            log.warn("Backlog sample by owner {} failed: {}", owner, Diagnostics.describe(e));
            return false;
        }
    }

    /** The latest successful sample, or null before the first. */
    BacklogSample latest() {
        return latest;
    }
}
