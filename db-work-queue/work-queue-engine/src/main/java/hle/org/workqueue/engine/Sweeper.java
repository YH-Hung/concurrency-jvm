package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The Sweeper (spec §6): fails the expired claims whose attempts are exhausted, which no claim can take again. One
 * pass sweeps batches of sweep-batch-size until one comes back less than full; QueueRunner's sweeper loop runs a pass
 * every sweep-interval. It is idempotent and runs on every instance: concurrent sweeps skip each other's rows.
 */
final class Sweeper {

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final WorkItemRepository repository;
    private final String owner;
    private final int batchSize;
    private final DbActivity db;

    Sweeper(WorkItemRepository repository, String owner, int batchSize, DbActivity db) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.owner = Objects.requireNonNull(owner, "owner");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        this.batchSize = batchSize;
        this.db = Objects.requireNonNull(db, "db");
    }

    /**
     * One pass. A failed sweep ends it, logged by its diagnostics, and the batches before it stay swept; so does an
     * interrupt, after the current batch. Returns the number of rows swept.
     */
    int sweepOnce() {
        int total = 0;
        try {
            int swept;
            do {
                swept = repository.sweep(batchSize);
                db.succeeded();
                total += swept;
            } while (swept == batchSize && !Thread.currentThread().isInterrupted());
        } catch (RuntimeException e) {
            log.warn("Sweep by owner {} failed after {} rows: {}", owner, total, Diagnostics.describe(e));
        }
        if (total > 0) {
            log.warn("Sweeper of owner {} marked {} expired claims with exhausted attempts FAILED", owner, total);
        }
        return total;
    }
}
