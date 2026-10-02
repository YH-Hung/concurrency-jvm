package hle.org.workqueue.engine;

import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Test assembly: the production modules with clocks and failure seams supplied to their owners. */
final class EngineFixture {
    final ClaimExecution execution;
    final Sweeper sweeper;
    final BacklogSampler sampler;
    final EngineLoops loops;
    final QueueRunner runner;

    EngineFixture(WorkItemRepository repository, ClaimExecution.Processor processor, String owner,
                  EngineSettings settings, ClaimExecution.TaskThreads taskThreads, LongSupplier clock,
                  ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        this(repository, processor, owner, settings, taskThreads, clock, registry, EngineLoops.VIRTUAL_LOOP_THREADS);
    }

    EngineFixture(WorkItemRepository repository, ClaimExecution.Processor processor, String owner,
                  EngineSettings settings, ClaimExecution.TaskThreads taskThreads, LongSupplier clock,
                  ConcurrentMap<ClaimKey, ClaimHandle> registry, EngineLoops.LoopThreads loopThreads) {
        DbActivity db = new DbActivity(clock);
        execution = new ClaimExecution(repository, processor, owner, settings, db, clock, taskThreads, registry);
        sweeper = new Sweeper(repository, owner, settings.sweepBatchSize(), db);
        sampler = new BacklogSampler(repository, owner, db, clock);
        loops = new EngineLoops(execution, sweeper, sampler, owner, settings, clock, loopThreads);
        runner = new QueueRunner(execution, loops, sampler, db, settings, clock);
    }
}
