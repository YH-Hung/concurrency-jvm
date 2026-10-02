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

    // ---- State for health, metrics and tests -----------------------------------------------------------------

    private EngineSnapshot.Execution executionSnapshot() {
        return execution.snapshot(clock.getAsLong());
    }

    int availablePermits() { return executionSnapshot().availablePermits(); }
    int inflight() { return executionSnapshot().inflight(); }
    int hungTasks() { return executionSnapshot().hungTasks(); }
    boolean hungTaskLimitReached() { return executionSnapshot().hungTaskLimitReached(); }

    List<String> deadLoops() {
        return running ? loops.deadLoops() : List.of();
    }

    /** The latest backlog sample (spec §9.6 backlog gauges), or null before the first. */
    BacklogSample backlog() {
        return sampler.latest();
    }

    /** Spec §9.6 {@code backlog.sample_age}: how old the sample the backlog gauges read is. */
    Duration backlogSampleAge() {
        return sampler.age();
    }

    long backlogSampleErrors() {
        return sampler.errors();
    }

    long invariantViolations() { return executionSnapshot().invariantViolations(); }
    long registrationsLate() { return executionSnapshot().registrationsLate(); }
    long claimsLost() { return executionSnapshot().claimsLost(); }
    long claims() { return executionSnapshot().claims(); }
    long claimedRows() { return executionSnapshot().claimedRows(); }
    long claimErrors() { return executionSnapshot().claimErrors(); }
    OperationStats.Totals claimTimes() { return executionSnapshot().claimTimes(); }
    long renewalErrors() { return executionSnapshot().renewalErrors(); }
    OperationStats.Totals renewalTimes() { return executionSnapshot().renewalTimes(); }
    long outcomes(Outcome outcome) { return executionSnapshot().outcomes().get(outcome); }
    Duration renewalLag() { return executionSnapshot().renewalLag(); }

    /** Spec §9.6 {@code db.last_success_age}. */
    Duration dbLastSuccessAge() {
        return dbActivity.lastSuccessAge();
    }

    /** True from the start of {@link #stop()}: readiness reports DOWN. */
    boolean isStopping() {
        return stopping;
    }

    private Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - clock.getAsLong()));
    }
}
