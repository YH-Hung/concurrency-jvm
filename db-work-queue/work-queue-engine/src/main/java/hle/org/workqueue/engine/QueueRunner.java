package hle.org.workqueue.engine;

import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Coordinates instance lifecycle and assembles observations; each module owns its own execution state. */
final class QueueRunner implements SmartLifecycle {
    private static final Duration LOOP_JOIN_TIMEOUT = Duration.ofSeconds(1);
    private final ClaimExecution execution;
    private final EngineLoops loops;
    private final BacklogSampler sampler;
    private final DbActivity dbActivity;
    private final EngineSettings settings;
    private final LongSupplier clock;
    private final Object lifecycle = new Object();
    private boolean started;
    private volatile boolean aborted;
    private volatile boolean running;
    private volatile boolean stopping;

    QueueRunner(WorkItemRepository repository, ClaimExecution.Processor processor, String owner, EngineSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = System::nanoTime;
        this.dbActivity = new DbActivity(clock);
        this.execution = new ClaimExecution(repository, processor, owner, settings, dbActivity, clock);
        this.sampler = new BacklogSampler(repository, owner, dbActivity, clock);
        this.loops = new EngineLoops(execution, new Sweeper(repository, owner, settings.sweepBatchSize(), dbActivity),
                sampler, owner, settings, clock);
    }

    /** Tests assemble the same modules with seams injected into their respective owners. */
    QueueRunner(ClaimExecution execution, EngineLoops loops, BacklogSampler sampler, DbActivity dbActivity,
                EngineSettings settings, LongSupplier clock) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.loops = Objects.requireNonNull(loops, "loops");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.dbActivity = Objects.requireNonNull(dbActivity, "dbActivity");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts once; repeated start while running is harmless. */
    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            if (started || aborted) {
                throw new IllegalStateException("a QueueRunner cannot be restarted");
            }
            started = true;
            loops.start();
            running = true;
        }
    }

    @Override
    public boolean isPauseable() {
        return false;
    }

    /** Readiness down → stop claiming → drain with renewal active → cancel → stop background loops (spec §5.2). */
    @Override
    public void stop() {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            stopping = true;
            long graceEnd = clock.getAsLong() + settings.shutdownGrace().toNanos();
            loops.stopPolling(remaining(graceEnd));
            execution.awaitDrained(graceEnd);
            execution.cancelForShutdown();
            execution.awaitDrained(clock.getAsLong() + settings.shutdownCancelWait().toNanos());
            loops.stopBackground(LOOP_JOIN_TIMEOUT);
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Tests only: publish cancellation before waiting on lifecycle, without draining or joining. */
    void crash() {
        aborted = true;
        loops.abort();
        synchronized (lifecycle) {
            running = false;
        }
    }

    /** One clock reading, no DB calls or lifecycle/registration lock; each owner supplies immutable summaries. */
    EngineSnapshot snapshot() {
        long now = clock.getAsLong();
        boolean active = running;
        return new EngineSnapshot(execution.snapshot(now), sampler.snapshot(now),
                new EngineSnapshot.Runtime(active, stopping, active ? loops.deadLoops() : List.of()), dbActivity.ageAt(now));
    }

    private Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - clock.getAsLong()));
    }
}
