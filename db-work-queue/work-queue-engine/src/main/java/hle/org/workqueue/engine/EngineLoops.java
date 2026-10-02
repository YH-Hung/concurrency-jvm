package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Owns the five independent loop threads and their scheduling, startup rollback, interruption and joins. */
final class EngineLoops {
    private static final Logger log = LoggerFactory.getLogger(EngineLoops.class);

    /** Starts the thread that runs one of the five loops. */
    @FunctionalInterface
    interface LoopThreads {
        Thread start(String name, Runnable loop);
    }

    /** One named virtual thread per loop. */
    static final LoopThreads VIRTUAL_LOOP_THREADS = (name, loop) -> Thread.ofVirtual().name(name).start(loop);

    private final ClaimExecution execution;
    private final Sweeper sweeper;
    private final BacklogSampler sampler;
    private final String owner;
    private final EngineSettings settings;
    private final LongSupplier clock;
    private final LoopThreads loopThreads;
    private final RenewalSchedule schedule;
    private boolean started;
    private volatile boolean aborted;

    private volatile boolean polling;
    private volatile boolean renewing;
    private volatile boolean supervising;
    private volatile boolean sweeping;
    private volatile boolean sampling;
    private volatile Thread pollThread;
    private volatile Thread renewalThread;
    private volatile Thread supervisorThread;
    private volatile Thread sweeperThread;
    private volatile Thread samplerThread;

    EngineLoops(ClaimExecution execution, Sweeper sweeper, BacklogSampler sampler, String owner,
                EngineSettings settings, LongSupplier clock) {
        this(execution, sweeper, sampler, owner, settings, clock, VIRTUAL_LOOP_THREADS);
    }

    /** Internal seam for partial-start failure tests. */
    EngineLoops(ClaimExecution execution, Sweeper sweeper, BacklogSampler sampler, String owner,
                EngineSettings settings, LongSupplier clock, LoopThreads loopThreads) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.loopThreads = Objects.requireNonNull(loopThreads, "loopThreads");
        this.schedule = new RenewalSchedule(settings.renewInterval(), settings.renewRetryDelay());
    }

    /** Called once under the runner's lifecycle lock; abort may race with it. */
    void start() {
        if (started || aborted) {
            throw new IllegalStateException("engine loops cannot be restarted");
        }
        started = true;
        polling = renewing = supervising = sweeping = sampling = true;
        if (aborted) {
            clearRunFlags();
            throw new IllegalStateException("engine loops were aborted");
        }
        try {
            pollThread = loopThreads.start("workqueue-poll", this::pollLoop);
            renewalThread = loopThreads.start("workqueue-renewal", this::renewalLoop);
            supervisorThread = loopThreads.start("workqueue-supervisor", this::supervisorLoop);
            sweeperThread = loopThreads.start("workqueue-sweeper", this::sweeperLoop);
            samplerThread = loopThreads.start("workqueue-backlog-sampler", this::samplerLoop);
        } catch (Throwable t) {
            clearRunFlags();
            execution.cancelForShutdown();
            interruptLoops();
            throw t;
        }
        // A concurrent abort can precede publication of one of the thread references.
        if (aborted) {
            clearRunFlags();
            interruptLoops();
        }
    }

    void stopPolling(Duration timeout) {
        polling = false;
        pollThread.interrupt();
        join(pollThread, timeout);
    }

    /** One shared budget for all four loops that continue during the claim drain. */
    void stopBackground(Duration timeout) {
        renewing = supervising = sweeping = sampling = false;
        List<Thread> background = List.of(renewalThread, supervisorThread, sweeperThread, samplerThread);
        background.forEach(Thread::interrupt);
        long deadline = clock.getAsLong() + timeout.toNanos();
        for (Thread loop : background) {
            join(loop, remaining(deadline));
        }
    }

    /** Never drains or takes the runner's lifecycle lock; reaches claims transferred but not yet registered. */
    void abort() {
        aborted = true;
        clearRunFlags();
        execution.abort();
        interruptLoops();
    }

    private void clearRunFlags() {
        polling = renewing = supervising = sweeping = sampling = false;
    }

    private void interruptLoops() {
        for (Thread loop : Arrays.asList(pollThread, renewalThread, supervisorThread, sweeperThread, samplerThread)) {
            if (loop != null) {
                loop.interrupt();
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
                    pause = execution.pollOnce();
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
                    succeeded = execution.renewOnce();
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



    // ---- Supervisor ------------------------------------------------------------------------------------------

    private void supervisorLoop() {
        try {
            while (supervising) {
                try {
                    execution.superviseOnce();
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



    // ---- Sweeper and backlog sampler -------------------------------------------------------------------------

    private void sweeperLoop() {
        passLoop("Sweeper", () -> sweeping, sweeper::sweepOnce, settings.sweepInterval());
    }

    private void samplerLoop() {
        passLoop("Backlog sampler", () -> sampling, sampler::sampleOnce, settings.backlogSampleInterval());
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
        List<String> dead = new ArrayList<>(5);
        if (pollThread != null && !pollThread.isAlive() && polling) {
            dead.add("poll");
        }
        if (renewalThread != null && !renewalThread.isAlive() && renewing) {
            dead.add("renewal");
        }
        if (supervisorThread != null && !supervisorThread.isAlive() && supervising) {
            dead.add("supervisor");
        }
        if (sweeperThread != null && !sweeperThread.isAlive() && sweeping) {
            dead.add("sweeper");
        }
        if (samplerThread != null && !samplerThread.isAlive() && sampling) {
            dead.add("backlog-sampler");
        }
        return dead;
    }

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
