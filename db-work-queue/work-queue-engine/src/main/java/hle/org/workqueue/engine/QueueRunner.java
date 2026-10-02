package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Coordinates instance lifecycle; ClaimExecution owns the active-claim protocol. */
final class QueueRunner implements SmartLifecycle {

    /** Starts the thread that runs one of the five loops. */
    @FunctionalInterface
    interface LoopThreads {
        Thread start(String name, Runnable loop);
    }

    /** One named virtual thread per loop. */
    static final LoopThreads VIRTUAL_LOOP_THREADS = (name, loop) -> Thread.ofVirtual().name(name).start(loop);

    /**
     * How long {@link #stop()} waits, in all, for the loops that outlive the drain after interrupting them; E2 leaves
     * 5s for this and exit.
     */
    private static final Duration LOOP_JOIN_TIMEOUT = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(QueueRunner.class);

    private final ClaimExecution execution;
    private final String owner;
    private final EngineSettings settings;
    private final LoopThreads loopThreads;
    private final LongSupplier clock;
    private final RenewalSchedule schedule;
    private final DbActivity dbActivity;
    private final Sweeper sweeper;
    private final BacklogSampler sampler;
    private volatile boolean aborted;

    private final Object lifecycle = new Object();
    private boolean started;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile boolean polling;
    private volatile boolean renewing;
    private volatile boolean supervising;
    private volatile boolean sweeping;
    private volatile boolean sampling;
    private Thread pollThread;
    private Thread renewalThread;
    private Thread supervisorThread;
    private Thread sweeperThread;
    private Thread samplerThread;

    QueueRunner(WorkItemRepository repository, ClaimExecution.Processor processor, String owner, EngineSettings settings) {
        this(repository, processor, owner, settings, ClaimExecution.VIRTUAL_THREADS, System::nanoTime, new ConcurrentHashMap<>());
    }

    /** For tests: the task threads, the clock and the registry are injectable. */
    QueueRunner(WorkItemRepository repository, ClaimExecution.Processor processor, String owner, EngineSettings settings,
                ClaimExecution.TaskThreads taskThreads, LongSupplier clock, ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        this(repository, processor, owner, settings, taskThreads, clock, registry, VIRTUAL_LOOP_THREADS);
    }

    /** For tests of a loop thread that fails to start. */
    QueueRunner(WorkItemRepository repository, ClaimExecution.Processor processor, String owner, EngineSettings settings,
                ClaimExecution.TaskThreads taskThreads, LongSupplier clock, ConcurrentMap<ClaimKey, ClaimHandle> registry,
                LoopThreads loopThreads) {
        WorkItemRepository.requireOwner(owner);
        this.owner = owner;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.loopThreads = Objects.requireNonNull(loopThreads, "loopThreads");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schedule = new RenewalSchedule(settings.renewInterval(), settings.renewRetryDelay());
        this.dbActivity = new DbActivity(clock);
        this.execution = new ClaimExecution(repository, processor, owner, settings, dbActivity, clock,
                taskThreads, registry);
        this.sweeper = new Sweeper(repository, owner, settings.sweepBatchSize(), dbActivity);
        this.sampler = new BacklogSampler(repository, owner, dbActivity, clock);
    }

    // ---- Lifecycle -------------------------------------------------------------------------------------------

    /** Starts the five loops. A runner starts once: after stop() or crash() it cannot be started again. */
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
            polling = true;
            renewing = true;
            supervising = true;
            sweeping = true;
            sampling = true;
            try {
                pollThread = loopThreads.start("workqueue-poll", this::pollLoop);
                renewalThread = loopThreads.start("workqueue-renewal", this::renewalLoop);
                supervisorThread = loopThreads.start("workqueue-supervisor", this::supervisorLoop);
                sweeperThread = loopThreads.start("workqueue-sweeper", this::sweeperLoop);
                samplerThread = loopThreads.start("workqueue-backlog-sampler", this::samplerLoop);
            } catch (Throwable t) {
                // running stays false, so stop() would do nothing: end the loops that did start here, and cancel
                // whatever the poll loop registers from a claim that was already in flight.
                polling = false;
                renewing = false;
                supervising = false;
                sweeping = false;
                sampling = false;
                execution.cancelForShutdown();
                for (Thread loop : Arrays.asList(pollThread, renewalThread, supervisorThread, sweeperThread)) {
                    if (loop != null) {
                        loop.interrupt();
                    }
                }
                throw t;
            }
            running = true;
        }
    }

    /** False: a runner cannot be restarted, so Spring's pause and restart must not stop it. */
    @Override
    public boolean isPauseable() {
        return false;
    }

    /**
     * The stop sequence of spec §5.2: stop claiming (a claim that already returned is still started), wait up to
     * shutdown-grace for the running tasks with renewal still running, cancel what is left, wait up to
     * shutdown-cancel-wait, then stop renewal, the supervisor, the sweeper and the backlog sampler, waiting at most
     * 1s for them in all. Nothing is released in Db2: a claim still held expires with its attempt consumed.
     */
    @Override
    public void stop() {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            stopping = true;
            long graceEnd = clock.getAsLong() + settings.shutdownGrace().toNanos();
            polling = false;
            pollThread.interrupt();
            join(pollThread, remaining(graceEnd));
            execution.awaitDrained(graceEnd);
            execution.cancelForShutdown();
            execution.awaitDrained(clock.getAsLong() + settings.shutdownCancelWait().toNanos());
            renewing = false;
            supervising = false;
            sweeping = false;
            sampling = false;
            List<Thread> loops = List.of(renewalThread, supervisorThread, sweeperThread, samplerThread);
            loops.forEach(Thread::interrupt);
            long joinDeadline = clock.getAsLong() + LOOP_JOIN_TIMEOUT.toNanos();
            for (Thread loop : loops) {
                join(loop, remaining(joinDeadline));
            }
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Tests only (spec §5.2): stops every loop and cancels every claim at once, without draining or waiting. */
    void crash() {
        aborted = true;
        polling = false;
        renewing = false;
        supervising = false;
        sweeping = false;
        sampling = false;
        execution.abort();
        synchronized (lifecycle) {
            if (running) {
                pollThread.interrupt();
                renewalThread.interrupt();
                supervisorThread.interrupt();
                sweeperThread.interrupt();
                samplerThread.interrupt();
                running = false;
            }
        }
    }

    // ---- Poll loop -------------------------------------------------------------------------------------------

    // Each loop survives a RuntimeException. An Error ends the loop, which deadLoops() then reports to liveness; it is
    // caught only to be logged by its diagnostics: the thread's default handler would print its message (spec §5.4).
    private void pollLoop() {
        try {
            while (polling) {
                Duration pause;
                try {
                    pause = pollOnce();
                } catch (InterruptedException e) {
                    return;

                }
                if (!sleep(pause)) {
                    return;
                }
            }
        } catch (Throwable t) {
            log.error("Poll loop of owner {} died: {}", owner, Diagnostics.describe(t));
        }
    }

    Duration pollOnce() throws InterruptedException {
        return execution.pollOnce();
    }

    // ---- Renewal loop ----------------------------------------------------------------------------------------

    private void renewalLoop() {
        try {
            long next = clock.getAsLong();
            while (renewing) {
                if (!sleep(Duration.ofNanos(Math.max(0, next - clock.getAsLong())))) {
                    return;
                }
                long start = clock.getAsLong();
                boolean succeeded;
                try {
                    succeeded = renewOnce();
                } catch (RuntimeException e) {
                    succeeded = false;
                    log.error("Renewal round of owner {} failed: {}", owner, Diagnostics.describe(e));
                }
                next = schedule.next(start, clock.getAsLong(), succeeded);
            }
        } catch (Throwable t) {
            log.error("Renewal loop of owner {} died: {}", owner, Diagnostics.describe(t));
        }
    }

    boolean renewOnce() {
        return execution.renewOnce();
    }

    // ---- Supervisor ------------------------------------------------------------------------------------------

    private void supervisorLoop() {
        try {
            while (supervising) {
                try {
                    superviseOnce();
                } catch (RuntimeException e) {
                    log.error("Supervisor pass of owner {} failed: {}", owner, Diagnostics.describe(e));
                }
                if (!sleep(settings.supervisorInterval())) {
                    return;
                }
            }
        } catch (Throwable t) {
            log.error("Supervisor loop of owner {} died: {}", owner, Diagnostics.describe(t));
        }
    }

    void superviseOnce() {
        execution.superviseOnce();
    }

    // ---- Sweeper and backlog sampler -------------------------------------------------------------------------

    private void sweeperLoop() {
        passLoop("Sweeper", () -> sweeping, this::sweepOnce, settings.sweepInterval());
    }

    private void samplerLoop() {
        passLoop("Backlog sampler", () -> sampling, this::sampleOnce, settings.backlogSampleInterval());
    }

    // A pass, then the interval, until stopped. A pass logs its own failures, so only an Error ends the loop; it is
    // logged, and deadLoops() reports it to liveness as it does the other three.
    private void passLoop(String name, BooleanSupplier active, Runnable pass, Duration interval) {
        try {
            while (active.getAsBoolean()) {
                pass.run();
                if (!sleep(interval)) {
                    return;
                }
            }
        } catch (Throwable t) {
            log.error("{} loop of owner {} died: {}", name, owner, Diagnostics.describe(t));
        }
    }

    /** One sweeper pass (spec §6), which the sweeper loop repeats every sweep-interval. Returns the rows swept. */
    int sweepOnce() {
        return sweeper.sweepOnce();
    }

    /** One backlog sample (spec §9.6), which the sampler loop repeats every backlog-sample-interval. */
    boolean sampleOnce() {
        return sampler.sampleOnce();
    }

    // ---- State for health, metrics and tests -----------------------------------------------------------------

    private EngineSnapshot.Execution executionSnapshot() {
        return execution.snapshot(clock.getAsLong());
    }

    int availablePermits() { return executionSnapshot().availablePermits(); }
    int inflight() { return executionSnapshot().inflight(); }
    int hungTasks() { return executionSnapshot().hungTasks(); }
    boolean hungTaskLimitReached() { return executionSnapshot().hungTaskLimitReached(); }

    /**
     * The loops (spec §9.6) that ended while the runner still wanted them: an Error ended them, or an interrupt that
     * was not stop()'s or crash()'s. Loops that stop() or crash() ended are not dead. A restart is the only remedy
     * for any of the five: without the sweeper, a single instance leaves exhausted claims CLAIMED; without the
     * sampler, the backlog gauges stop changing. Each check
     * reads the thread before its run flag: a thread's end happens-before it is seen ended, and stop() and crash()
     * clear the flag before they interrupt, so a loop they ended always shows its flag cleared. Reading the flag
     * first could see it still set just before stop() cleared it and the loop ended.
     */
    List<String> deadLoops() {
        if (!running) {
            return List.of();
        }
        List<String> dead = new ArrayList<>(5);
        if (!pollThread.isAlive() && polling) {
            dead.add("poll");
        }
        if (!renewalThread.isAlive() && renewing) {
            dead.add("renewal");
        }
        if (!supervisorThread.isAlive() && supervising) {
            dead.add("supervisor");
        }
        if (!sweeperThread.isAlive() && sweeping) {
            dead.add("sweeper");
        }
        if (!samplerThread.isAlive() && sampling) {
            dead.add("backlog-sampler");
        }
        return dead;
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

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - clock.getAsLong()));
    }

    // False if interrupted, with the interrupt status restored.
    private static boolean sleep(Duration pause) {
        try {
            if (pause.isPositive()) {
                Thread.sleep(pause);
            } else if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void join(Thread thread, Duration timeout) {
        try {
            if (timeout.isPositive()) {
                thread.join(timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
