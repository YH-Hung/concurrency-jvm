package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One claim's lifecycle (spec §5.2). From the moment the poll loop transfers a permit to it, the handle owns that
 * permit and returns it exactly once, in {@link #finish()}, on every path. Cancelling only stops renewal and
 * interrupts the handle's thread: the permit stays taken until that thread ends, so a task that ignores
 * interruption still counts against concurrency. Times are {@code System.nanoTime()} readings, compared
 * overflow-safely.
 */
final class ClaimHandle {

    /** Why a claim was cancelled. */
    enum CancelReason {
        /** max-processing-time has passed since the claim. */
        DEADLINE,
        /** Renewal reported the claim lost: another owner may hold the row. */
        LOST,
        /** The instance is stopping and shutdown-grace has passed. */
        SHUTDOWN,
        /** {@code QueueRunner.crash()}, tests only. */
        CRASH
    }

    private final ClaimedItem item;
    private final long claimedAt;
    private final long deadline;
    private final Map<ClaimKey, ClaimHandle> registry;
    private final Semaphore permits;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean hung = new AtomicBoolean();

    // markRunning and cancel decide under this lock whether the body runs and whether its thread is interrupted,
    // so a cancel can never slip between the check and the start of processing.
    private final Object lock = new Object();
    private Thread runner;
    private CancelReason cancelReason;
    private long cancelledAt;

    // Only the renewal loop writes it after construction; the gauges read it.
    private volatile long leaseWrittenAt;

    /**
     * Has no side effects: the poll loop still owns the permit until it transfers it after construction.
     *
     * @param claimStartedAt when the claim operation started: its lease-setting write came no earlier (spec §5.3)
     * @param claimedAt      when the claim operation returned; the deadline and the registration-late check count
     *                       from it
     */
    ClaimHandle(ClaimedItem item, long claimStartedAt, long claimedAt, Duration maxProcessingTime,
                Map<ClaimKey, ClaimHandle> registry, Semaphore permits) {
        this.item = Objects.requireNonNull(item, "item");
        Durations.requirePositive("maxProcessingTime", maxProcessingTime);
        if (claimedAt - claimStartedAt < 0) {
            throw new IllegalArgumentException("claimedAt is before claimStartedAt");
        }
        this.claimedAt = claimedAt;
        this.deadline = claimedAt + maxProcessingTime.toNanos();
        this.leaseWrittenAt = claimStartedAt;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permits = Objects.requireNonNull(permits, "permits");
    }

    ClaimKey key() {
        return item.key();
    }

    ClaimedItem item() {
        return item;
    }

    long claimedAt() {
        return claimedAt;
    }

    /** claimedAt + max-processing-time: the supervisor cancels the claim here, and renewal stops. */
    long deadline() {
        return deadline;
    }

    /**
     * The start of the operation that last wrote this claim's lease: its claim operation, then the last renewal round
     * that renewed it. The write itself came no earlier, so the lease lasts at least lease-duration from here (spec
     * §5.3); {@code renewal.lag} counts from it (spec §9.6).
     */
    long leaseWrittenAt() {
        return leaseWrittenAt;
    }

    /** A renewal round that started at {@code roundStart} renewed this claim's lease. */
    void leaseRenewed(long roundStart) {
        leaseWrittenAt = roundStart;
    }

    /**
     * Adds this handle to the registry. False if another handle already holds its key, which is an invariant
     * violation: claim tokens are unique per claim.
     */
    boolean register() {
        return registry.putIfAbsent(key(), this) == null;
    }

    /**
     * Called first by the handle's own thread. False if the handle was already cancelled or has ended: the body
     * then skips processing and only finishes. Once this returns true, a later cancel interrupts the calling
     * thread.
     */
    boolean markRunning() {
        synchronized (lock) {
            if (cancelReason != null || ended.get()) {
                return false;
            }
            runner = Thread.currentThread();
            return true;
        }
    }

    /**
     * Stops renewal of this claim and interrupts its thread if the body is running. Never touches the registry or
     * the permit. False, changing nothing, if the handle was already cancelled or has ended.
     */
    boolean cancel(CancelReason reason, long now) {
        Objects.requireNonNull(reason, "reason");
        synchronized (lock) {
            if (cancelReason != null || ended.get()) {
                return false;
            }
            cancelReason = reason;
            cancelledAt = now;
            if (runner != null) {
                runner.interrupt();
            }
            return true;
        }
    }

    boolean isCancelled() {
        return cancelReason() != null;
    }

    /** The reason given to the first cancel, or null if the handle was never cancelled. */
    CancelReason cancelReason() {
        synchronized (lock) {
            return cancelReason;
        }
    }

    boolean isEnded() {
        return ended.get();
    }

    /** Renewal eligibility (spec §5.2): not ended, not cancelled, and before the deadline. */
    boolean isRenewable(long now) {
        return !isEnded() && !isCancelled() && !isPastDeadline(now);
    }

    boolean isPastDeadline(long now) {
        return now - deadline >= 0;
    }

    /**
     * Marks this handle hung if it was cancelled at least {@code hungGrace} before {@code now} and has not ended.
     * True only the first time, so the supervisor logs each hung task once.
     */
    boolean markHungIfOverdue(long now, Duration hungGrace) {
        synchronized (lock) {
            if (cancelReason == null || now - cancelledAt < hungGrace.toNanos()) {
                return false;
            }
        }
        return !isEnded() && hung.compareAndSet(false, true);
    }

    /** Marked hung and still running. It keeps its permit until its thread ends. */
    boolean isHung() {
        return hung.get() && !isEnded();
    }

    /** The body's stack, for the hung-task log; empty if the body has not started. */
    StackTraceElement[] runnerStackTrace() {
        Thread thread;
        synchronized (lock) {
            thread = runner;
        }
        return thread == null ? new StackTraceElement[0] : thread.getStackTrace();
    }

    /**
     * Ends this handle exactly once, whoever calls it: removes it from the registry if it is registered there
     * (never another handle with the same key) and returns its permit. True for the call that did so.
     */
    boolean finish() {
        if (!ended.compareAndSet(false, true)) {
            return false;
        }
        try {
            registry.remove(key(), this);
        } finally {
            permits.release();
        }
        return true;
    }

    /** Only the row id and token: the engine never logs operation ids or payloads. */
    @Override
    public String toString() {
        return "ClaimHandle[id=" + item.id() + ", token=" + item.claimToken() + "]";
    }
}
