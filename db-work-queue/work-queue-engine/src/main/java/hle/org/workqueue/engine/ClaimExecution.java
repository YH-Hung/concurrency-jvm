package hle.org.workqueue.engine;

import hle.org.workqueue.engine.ClaimHandle.CancelReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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

/**
 * Owns active claims, from acquiring capacity through actual task exit (spec §5.2–5.3).
 * Every transferred permit belongs to its handle until finish; cancellation never frees capacity.
 * Polling, renewal and supervision are commands scheduled independently by EngineLoops.
 */
final class ClaimExecution {
    private static final Logger log = LoggerFactory.getLogger(ClaimExecution.class);
    private static final Duration DRAIN_CHECK_INTERVAL = Duration.ofMillis(10);

    /** Processes one claimed row: {@link ItemProcessor#process} in production. */
    @FunctionalInterface
    interface Processor {
        Outcome process(ClaimedItem item, BooleanSupplier cancelled);
    }

    /** Creates, without starting it, the thread that runs one claim's body. */
    @FunctionalInterface
    interface TaskThreads {
        Thread newThread(ClaimHandle handle, Runnable body);
    }

    /** One virtual thread per claim, named after its row and token (spec §5.2 execution model). */
    static final TaskThreads VIRTUAL_THREADS = (handle, body) -> Thread.ofVirtual()
            .name("workqueue-task-" + handle.key().id() + "-" + handle.key().token())
            .unstarted(body);

    private final WorkItemRepository repository;
    private final Processor processor;
    private final String owner;
    private final EngineSettings settings;
    private final TaskThreads taskThreads;
    private final LongSupplier clock;
    private final ConcurrentMap<ClaimKey, ClaimHandle> registry;
    private final Semaphore permits;
    private final DbActivity dbActivity;

    // What health and the meters read (spec §9.6); WorkQueueMetrics binds them.
    private final AtomicLong invariantViolations = new AtomicLong();
    private final AtomicLong registrationsLate = new AtomicLong();
    private final AtomicLong claimsLost = new AtomicLong();
    private final AtomicLong claims = new AtomicLong();
    private final AtomicLong claimedRows = new AtomicLong();
    private final AtomicLong claimErrors = new AtomicLong();
    private final OperationStats claimTimes = new OperationStats();
    private final AtomicLong renewalErrors = new AtomicLong();
    private final OperationStats renewalTimes = new OperationStats();
    private final Map<Outcome, AtomicLong> outcomes = new EnumMap<>(Outcome.class);

    // cancelAll sets cancelOnRegister and walks the registry under this lock, and registerAndStart registers a
    // handle and reads cancelOnRegister under it, so a handle is either in the registry when a cancel pass walks it
    // or reads that pass's reason. stop() takes it inside lifecycle, crash() before lifecycle, and registerAndStart
    // never takes lifecycle. It is reentrant, for a test that crashes the runner from inside a registration.
    private final Object registrationLock = new Object();

    // Once set, every handle registered from then on is cancelled before its thread starts, so abort() and cancelForShutdown()
    // also reach a handle the poll loop has transferred but not yet registered.
    private volatile CancelReason cancelOnRegister;

    // Only the poll loop reads or writes this.
    private int claimFailures;

    ClaimExecution(WorkItemRepository repository, Processor processor, String owner, EngineSettings settings,
                   DbActivity dbActivity, LongSupplier clock) {
        this(repository, processor, owner, settings, dbActivity, clock, VIRTUAL_THREADS, new ConcurrentHashMap<>());
    }

    /** Internal seams for controlled registration and task-construction failures. */
    ClaimExecution(WorkItemRepository repository, Processor processor, String owner, EngineSettings settings,
                   DbActivity dbActivity, LongSupplier clock, TaskThreads taskThreads,
                   ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        WorkItemRepository.requireOwner(owner);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.owner = owner;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dbActivity = Objects.requireNonNull(dbActivity, "dbActivity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.taskThreads = Objects.requireNonNull(taskThreads, "taskThreads");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permits = new Semaphore(settings.concurrency());
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, new AtomicLong());
        }
    }

    void cancelForShutdown() {
        cancelAll(CancelReason.SHUTDOWN);
    }

    void abort() {
        cancelAll(CancelReason.CRASH);
    }

    EngineSnapshot.Execution snapshot(long now) {
        int hung = hungTasks();
        Map<Outcome, Long> ended = new EnumMap<>(Outcome.class);
        outcomes.forEach((outcome, count) -> ended.put(outcome, count.get()));
        return new EngineSnapshot.Execution(permits.availablePermits(), registry.size(), hung,
                hung >= settings.hungTaskLimit(), renewalLag(now), claims.get(), claimedRows.get(), claimErrors.get(),
                renewalErrors.get(), claimsLost.get(), registrationsLate.get(), invariantViolations.get(),
                claimTimes.snapshot(), renewalTimes.snapshot(), ended);
    }

    /**
     * One poll-loop iteration (spec §5.2 steps 1–3): acquire permits, claim that many rows, then transfer one permit
     * to each claimed row's handle, register it and start its thread. Every permit the loop still holds is returned
     * on every path. Returns the pause before the next iteration: none after a claim that found rows, the jittered
     * idle interval after an empty one, a growing backoff after a failed one.
     *
     * @throws InterruptedException if interrupted while waiting for a permit
     */
    Duration pollOnce() throws InterruptedException {
        try {
            return claimAndStartOnce();
        } catch (RuntimeException e) {
            Duration pause = backoff();
            log.error("Poll of owner {} failed; next claim in {}: {}", owner, pause, Diagnostics.describe(e));
            return pause;
        }
    }

    private Duration claimAndStartOnce() throws InterruptedException {
        int held = 0;
        try {
            permits.acquire();
            held = 1;
            while (held < settings.claimBatchSize() && permits.tryAcquire()) {
                held++;
            }
            if (hungTasks() >= settings.hungTaskLimit()) {
                return settings.supervisorInterval();
            }
            long claimStartedAt = clock.getAsLong();
            List<ClaimedItem> claimed;
            try {
                claimed = repository.claim(owner, held);
            } catch (RuntimeException e) {
                claimTimes.record(clock.getAsLong() - claimStartedAt);
                claimErrors.incrementAndGet();
                // The outcome is uncertain: rows may have committed. They are never registered, so they expire
                // unrenewed with their attempt consumed (spec §5.2).
                Duration pause = backoff();
                log.warn("Claim by owner {} failed; nothing claimed, next claim in {}: {}", owner, pause,
                        Diagnostics.describe(e));
                return pause;
            }
            long claimedAt = clock.getAsLong();
            claimTimes.record(claimedAt - claimStartedAt);
            claims.incrementAndGet();
            claimedRows.addAndGet(claimed.size());
            dbActivity.succeeded();
            claimFailures = 0;
            if (claimed.size() > held) {
                // Only the first held rows get a permit. The rest are CLAIMED but never registered, so, like the rows
                // of an uncertain claim, they expire unrenewed with their attempt consumed.
                invariantViolations.incrementAndGet();
                log.error("Invariant violation: claim by owner {} returned {} rows for {} permits; {} not started",
                        owner, claimed.size(), held, claimed.size() - held);
                claimed = claimed.subList(0, held);
            }
            for (ClaimedItem item : claimed) {
                ClaimHandle handle = new ClaimHandle(item, claimStartedAt, claimedAt, settings.maxProcessingTime(),
                        registry, permits);
                Thread thread = Objects.requireNonNull(taskThreads.newThread(handle, () -> runTask(handle)), "thread");
                held--;   // the transfer: from here on the handle owns this permit
                registerAndStart(handle, thread, claimedAt);
            }
            return claimed.isEmpty() ? idlePause() : Duration.ZERO;
        } finally {
            permits.release(held);
        }
    }

    // Spec §5.2 step 3. Every failure between the transfer and a successful start ends the handle through finish().
    // Nothing may follow thread.start() in this try: once the thread runs, only its own body may finish the handle.
    private void registerAndStart(ClaimHandle handle, Thread thread, long claimedAt) {
        try {
            synchronized (registrationLock) {
                if (!handle.register()) {
                    invariantViolations.incrementAndGet();
                    log.error("Invariant violation: claim {} of owner {} is already registered; not started",
                            handle, owner);
                    handle.finish();
                    return;
                }
                CancelReason reason = cancelOnRegister;
                if (reason != null) {
                    handle.cancel(reason, clock.getAsLong());
                }
            }
            if (clock.getAsLong() - claimedAt > settings.registrationAllowance().toNanos()) {
                registrationsLate.incrementAndGet();
            }
            thread.start();
        } catch (Throwable t) {
            handle.finish();
            log.error("Could not start claim {} of owner {}: {}", handle, owner, Diagnostics.describe(t));
        }
    }

    // Spec §5.2 step 4. Catches every Throwable: an uncaught one would reach the thread's default handler, which
    // prints its message (spec §5.4). A task cancelled before its body ran ends CANCELLED; one that threw has no
    // outcome.
    private void runTask(ClaimHandle handle) {
        try {
            Outcome outcome = handle.markRunning()
                    ? processor.process(handle.item(), handle::isCancelled)
                    : Outcome.CANCELLED;
            outcomes.get(Objects.requireNonNull(outcome, "outcome")).incrementAndGet();
            log.debug("Claim {} of owner {} ended {}", handle, owner, outcome);
        } catch (Throwable t) {
            log.error("Task for claim {} of owner {} failed: {}", handle, owner, Diagnostics.describe(t));
        } finally {
            handle.finish();
        }
    }

    // The idle interval ± 50%, so idle instances do not poll in step.
    private Duration idlePause() {
        long idle = settings.idlePollInterval().toNanos();
        return Duration.ofNanos(idle / 2 + ThreadLocalRandom.current().nextLong(idle + 1));
    }

    // idle-poll-interval, doubled after every consecutive failure, capped at poll-backoff-max.
    private Duration backoff() {
        Duration pause = settings.idlePollInterval();
        for (int i = 0; i < claimFailures && pause.compareTo(settings.pollBackoffMax()) < 0; i++) {
            pause = pause.multipliedBy(2);
        }
        claimFailures++;
        return pause.compareTo(settings.pollBackoffMax()) < 0 ? pause : settings.pollBackoffMax();
    }

    /**
     * One renewal round (spec §5.3) over a snapshot, taken at its start, of the handles that are renewable then.
     * Every claim the round renews has its lease counted from the round's start. Every claim it reports lost is
     * counted and cancelled; a claim its own task already ended is neither, and a lost claim the round did not
     * request is an invariant violation.
     * Returns whether the round succeeded; a round with nothing to renew is skipped and succeeds.
     */
    boolean renewOnce() {
        long start = clock.getAsLong();
        Map<ClaimKey, ClaimHandle> snapshot = new HashMap<>();
        for (ClaimHandle handle : registry.values()) {
            if (handle.isRenewable(start)) {
                snapshot.put(handle.key(), handle);
            }
        }
        if (snapshot.isEmpty()) {
            return true;
        }
        RenewalResult result;
        try {
            result = repository.renew(owner, snapshot.keySet());
        } catch (RuntimeException e) {
            renewalTimes.record(clock.getAsLong() - start);
            renewalErrors.incrementAndGet();
            log.warn("Renewal of {} claims of owner {} failed: {}", snapshot.size(), owner, Diagnostics.describe(e));
            return false;
        }
        long now = clock.getAsLong();
        renewalTimes.record(now - start);
        dbActivity.succeeded();
        for (ClaimKey key : result.renewed()) {
            ClaimHandle handle = snapshot.get(key);
            if (handle != null) {   // the repository renews only the pairs it was given
                handle.leaseRenewed(start);
            }
        }
        for (ClaimKey key : result.lost()) {
            ClaimHandle handle = snapshot.get(key);
            if (handle == null) {
                invariantViolations.incrementAndGet();
                log.error("Invariant violation: renewal of owner {} reported claim {} lost, which it did not request",
                        owner, key);
                continue;
            }
            claimsLost.incrementAndGet();
            log.warn("Claim {} of owner {} was lost; cancelling it", key, owner);
            handle.cancel(CancelReason.LOST, now);
        }
        return true;
    }

    /**
     * One supervisor pass (spec §5.2): cancels every claim past its deadline, and marks hung every cancelled claim
     * whose thread is still running hung-grace after the cancel, logging it once with its stack. Never waits on Db2.
     */
    void superviseOnce() {
        long now = clock.getAsLong();
        for (ClaimHandle handle : registry.values()) {
            if (handle.isPastDeadline(now) && handle.cancel(CancelReason.DEADLINE, now)) {
                log.warn("Claim {} of owner {} reached max-processing-time; cancelling it", handle, owner);
            }
            if (handle.markHungIfOverdue(now, settings.hungGrace())) {
                log.error("Claim {} of owner {} is hung: still running {} after it was cancelled ({}); it keeps its"
                        + " permit until its thread ends{}", handle, owner, settings.hungGrace(),
                        handle.cancelReason(), stack(handle.runnerStackTrace()));
            }
        }
    }

    private static String stack(StackTraceElement[] frames) {
        StringBuilder text = new StringBuilder();
        for (StackTraceElement frame : frames) {
            text.append(System.lineSeparator()).append("\tat ").append(frame);
        }
        return text.toString();
    }

    private int hungTasks() {
        int hung = 0;
        for (ClaimHandle handle : registry.values()) {
            if (handle.isHung()) {
                hung++;
            }
        }
        return hung;
    }

    private Duration renewalLag(long now) {
        long lag = 0;
        for (ClaimHandle handle : registry.values()) {
            if (handle.isRenewable(now)) {
                lag = Math.max(lag, now - handle.leaseWrittenAt());
            }
        }
        return Duration.ofNanos(lag);
    }

    private void cancelAll(CancelReason reason) {
        synchronized (registrationLock) {
            cancelOnRegister = reason;
            long now = clock.getAsLong();
            for (ClaimHandle handle : registry.values()) {
                handle.cancel(reason, now);
            }
        }
    }

    void awaitDrained(long deadline) {
        while (!registry.isEmpty()) {
            Duration left = remaining(deadline);
            if (left.isZero() || !sleep(left.compareTo(DRAIN_CHECK_INTERVAL) < 0 ? left : DRAIN_CHECK_INTERVAL)) {
                return;
            }
        }
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

}
