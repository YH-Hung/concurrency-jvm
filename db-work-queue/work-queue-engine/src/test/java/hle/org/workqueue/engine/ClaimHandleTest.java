package hle.org.workqueue.engine;

import hle.org.workqueue.engine.ClaimHandle.CancelReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@Timeout(60)
class ClaimHandleTest {

    private static final long SECOND = 1_000_000_000L;
    private static final Duration MAX_PROCESSING_TIME = ofSeconds(25);
    private static final int RACE_ITERATIONS = 10_000;

    // Each handle under test owns one permit that the semaphore gets back only through finish().
    private final Semaphore permits = new Semaphore(0);
    private final Map<ClaimKey, ClaimHandle> registry = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newFixedThreadPool(3);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void constructionHasNoSideEffects() {
        ClaimHandle handle = handle(1, 7, 10 * SECOND);

        assertThat(registry).isEmpty();
        assertThat(permits.availablePermits()).isZero();
        assertThat(handle.key()).isEqualTo(new ClaimKey(1, 7));
        assertThat(handle.claimedAt()).isEqualTo(10 * SECOND);
        assertThat(handle.deadline()).isEqualTo(35 * SECOND);
    }

    @Test
    void rejectsANonPositiveMaxProcessingTime() {
        assertThatThrownBy(() -> new ClaimHandle(item(1, 1), 0, 0, Duration.ZERO, registry, permits))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxProcessingTime");
    }

    @Test
    void rejectsAClaimThatReturnedBeforeItStarted() {
        assertThatThrownBy(() -> new ClaimHandle(item(1, 1), 2 * SECOND, SECOND, MAX_PROCESSING_TIME, registry,
                permits)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claimStartedAt");
    }

    @Test
    void theLeaseCountsFromTheClaimOperationsStartUntilARoundRenewsIt() {
        ClaimHandle handle = new ClaimHandle(item(1, 1), Long.MAX_VALUE - SECOND, Long.MAX_VALUE + SECOND,
                MAX_PROCESSING_TIME, registry, permits);   // the claim took 2s, across the overflow
        assertThat(handle.leaseWrittenAt()).isEqualTo(Long.MAX_VALUE - SECOND);

        handle.leaseRenewed(Long.MAX_VALUE + 15 * SECOND);

        assertThat(handle.leaseWrittenAt()).isEqualTo(Long.MAX_VALUE + 15 * SECOND);
    }

    @Test
    void finishRemovesTheHandleAndReturnsItsPermitOnce() {
        ClaimHandle handle = handle(1, 1);
        assertThat(handle.register()).isTrue();

        assertThat(handle.finish()).isTrue();
        assertThat(handle.finish()).isFalse();

        assertThat(handle.isEnded()).isTrue();
        assertThat(registry).isEmpty();
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void finishOfAnUnregisteredHandleOnlyReturnsItsPermit() {
        ClaimHandle other = handle(2, 1);
        other.register();
        ClaimHandle handle = handle(1, 1);

        assertThat(handle.finish()).isTrue();

        assertThat(registry).containsExactly(entry(new ClaimKey(2, 1), other));
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void anOldClaimEndingLeavesTheNewerClaimOfTheSameRowRegistered() {
        ClaimHandle old = handle(1, 1);
        old.register();
        ClaimHandle newer = handle(1, 2);
        newer.register();

        old.finish();

        assertThat(registry).containsExactly(entry(new ClaimKey(1, 2), newer));
    }

    @Test
    void aCollidingHandleIsNotRegisteredAndItsFinishLeavesTheRegisteredOne() {
        ClaimHandle registered = handle(1, 1);
        registered.register();
        ClaimHandle colliding = handle(1, 1);

        assertThat(colliding.register()).isFalse();
        assertThat(colliding.finish()).isTrue();

        assertThat(registry).containsExactly(entry(new ClaimKey(1, 1), registered));
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void cancelNeverReleasesThePermitOrTouchesTheRegistry() {
        ClaimHandle handle = handle(1, 1);
        handle.register();

        assertThat(handle.cancel(CancelReason.DEADLINE, 30 * SECOND)).isTrue();

        assertThat(handle.isCancelled()).isTrue();
        assertThat(handle.isEnded()).isFalse();
        assertThat(registry).containsKey(new ClaimKey(1, 1));
        assertThat(permits.availablePermits()).isZero();
    }

    @Test
    void onlyTheFirstCancelCounts() {
        ClaimHandle handle = handle(1, 1);

        assertThat(handle.cancel(CancelReason.LOST, 10 * SECOND)).isTrue();
        assertThat(handle.cancel(CancelReason.SHUTDOWN, 20 * SECOND)).isFalse();

        assertThat(handle.cancelReason()).isEqualTo(CancelReason.LOST);
        // The hung grace counts from the first cancel.
        assertThat(handle.markHungIfOverdue(12 * SECOND, ofSeconds(2))).isTrue();
    }

    @Test
    void aHandleCancelledBeforeItsBodyRunsDoesNotRun() {
        ClaimHandle handle = handle(1, 1);
        handle.cancel(CancelReason.SHUTDOWN, 0);

        assertThat(handle.markRunning()).isFalse();
    }

    @Test
    void cancelInterruptsARunningBody() throws InterruptedException {
        ClaimHandle handle = handle(1, 1);
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch running = new CountDownLatch(1);
        Thread body = Thread.ofVirtual().start(() -> {
            ran.set(handle.markRunning());
            running.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        handle.cancel(CancelReason.DEADLINE, 0);

        assertThat(body.join(ofSeconds(5))).isTrue();
        assertThat(ran).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void markRunningAndCancelNeverMissEachOther() throws Exception {
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            ClaimHandle handle = handle(i, 1);
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicBoolean cancelDone = new AtomicBoolean();
            Future<Boolean> ranUninterrupted = threads.submit(() -> {
                start.await();
                boolean ran = handle.markRunning();
                while (!cancelDone.get()) {
                    Thread.onSpinWait();
                }
                return ran && !Thread.interrupted();   // also clears the flag for the pool thread's next task
            });
            Future<?> cancel = threads.submit(() -> {
                start.await();
                handle.cancel(CancelReason.SHUTDOWN, 0);
                cancelDone.set(true);
                return null;
            });

            cancel.get();
            assertThat(ranUninterrupted.get()).as("iteration %d: the body ran and was not interrupted", i).isFalse();
        }
    }

    @Test
    void finishRunsExactlyOnceUnderConcurrentFinishAndCancel() throws Exception {
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            Semaphore racePermits = new Semaphore(0);
            Map<ClaimKey, ClaimHandle> raceRegistry = new ConcurrentHashMap<>();
            ClaimHandle handle = new ClaimHandle(item(i, 1), 0, 0, MAX_PROCESSING_TIME, raceRegistry, racePermits);
            handle.register();
            CyclicBarrier start = new CyclicBarrier(3);

            Future<Boolean> first = threads.submit(() -> {
                start.await();
                return handle.finish();
            });
            Future<Boolean> second = threads.submit(() -> {
                start.await();
                return handle.finish();
            });
            Future<Boolean> cancel = threads.submit(() -> {
                start.await();
                return handle.cancel(CancelReason.SHUTDOWN, 0);
            });

            assertThat(first.get() ^ second.get()).as("iteration %d: exactly one finish did the work", i).isTrue();
            // cancel races finish(): true means it took effect on a live handle, false that the handle had ended first.
            assertThat(handle.isCancelled()).as("iteration %d: cancel's result matches the handle", i).isEqualTo(cancel.get());
            assertThat(racePermits.availablePermits()).as("iteration %d", i).isEqualTo(1);
            assertThat(raceRegistry).as("iteration %d", i).isEmpty();
        }
    }

    @Test
    void aCancelAfterFinishChangesNothingAndInterruptsNothing() {
        ClaimHandle handle = handle(1, 1);
        try {
            assertThat(handle.markRunning()).isTrue();
            assertThat(handle.finish()).isTrue();

            assertThat(handle.cancel(CancelReason.LOST, 0)).isFalse();

            assertThat(handle.isCancelled()).isFalse();
            assertThat(handle.cancelReason()).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();   // a regression must not leak an interrupt into later tests
        }
    }

    @Test
    void anEndedHandleDoesNotRun() {
        ClaimHandle handle = handle(1, 1);
        handle.finish();

        assertThat(handle.markRunning()).isFalse();
    }

    @Test
    void isRenewableUntilTheDeadline() {
        ClaimHandle handle = handle(1, 1, 10 * SECOND);

        assertThat(handle.isRenewable(35 * SECOND - 1)).isTrue();
        assertThat(handle.isPastDeadline(35 * SECOND - 1)).isFalse();
        assertThat(handle.isRenewable(35 * SECOND)).isFalse();
        assertThat(handle.isPastDeadline(35 * SECOND)).isTrue();
    }

    @Test
    void theDeadlineIsComparedOverflowSafely() {
        long claimedAt = Long.MAX_VALUE - SECOND;
        ClaimHandle handle = handle(1, 1, claimedAt);

        assertThat(handle.isRenewable(Long.MAX_VALUE)).isTrue();
        assertThat(handle.isRenewable(claimedAt + 25 * SECOND - 1)).isTrue();
        assertThat(handle.isRenewable(claimedAt + 25 * SECOND)).isFalse();
    }

    @Test
    void aCancelledOrEndedHandleIsNotRenewable() {
        ClaimHandle cancelled = handle(1, 1);
        cancelled.cancel(CancelReason.LOST, 0);
        ClaimHandle ended = handle(2, 1);
        ended.finish();

        assertThat(cancelled.isRenewable(0)).isFalse();
        assertThat(ended.isRenewable(0)).isFalse();
    }

    @Test
    void aCancelledHandleThatHasNotEndedIsMarkedHungOnceAfterTheGrace() {
        ClaimHandle handle = handle(1, 1);
        handle.register();
        Duration grace = ofSeconds(2);

        assertThat(handle.markHungIfOverdue(100 * SECOND, grace)).as("not cancelled").isFalse();
        handle.cancel(CancelReason.DEADLINE, 10 * SECOND);
        assertThat(handle.markHungIfOverdue(12 * SECOND - 1, grace)).isFalse();
        assertThat(handle.markHungIfOverdue(12 * SECOND, grace)).isTrue();
        assertThat(handle.markHungIfOverdue(13 * SECOND, grace)).as("only once").isFalse();

        assertThat(handle.isHung()).isTrue();
        assertThat(permits.availablePermits()).as("a hung task keeps its permit").isZero();

        handle.finish();
        assertThat(handle.isHung()).isFalse();
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void anEndedHandleIsNeverMarkedHung() {
        ClaimHandle handle = handle(1, 1);
        handle.cancel(CancelReason.DEADLINE, 0);
        handle.finish();

        assertThat(handle.markHungIfOverdue(100 * SECOND, ofSeconds(2))).isFalse();
        assertThat(handle.isHung()).isFalse();
    }

    @Test
    void theRunnerStackTraceIsEmptyUntilTheBodyRuns() {
        ClaimHandle handle = handle(1, 1);
        assertThat(handle.runnerStackTrace()).isEmpty();

        handle.markRunning();

        assertThat(handle.runnerStackTrace()).isNotEmpty();
    }

    @Test
    void toStringShowsOnlyTheIdAndToken() {
        ClaimHandle handle = new ClaimHandle(new ClaimedItem(1, "op-secret", "payload-secret", 7), 0, 0,
                MAX_PROCESSING_TIME, registry, permits);

        assertThat(handle).hasToString("ClaimHandle[id=1, token=7]");
    }

    private ClaimHandle handle(long id, long token) {
        return handle(id, token, 0);
    }

    private ClaimHandle handle(long id, long token, long claimedAt) {
        return new ClaimHandle(item(id, token), claimedAt, claimedAt, MAX_PROCESSING_TIME, registry, permits);
    }

    private static ClaimedItem item(long id, long token) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, token);
    }
}
