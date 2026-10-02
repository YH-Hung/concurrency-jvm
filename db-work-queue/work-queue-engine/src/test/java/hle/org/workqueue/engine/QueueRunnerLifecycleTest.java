package hle.org.workqueue.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import hle.org.workqueue.engine.ClaimHandle.CancelReason;
import hle.org.workqueue.engine.ScriptedRepository.Write;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static hle.org.workqueue.engine.ScriptedRepository.Operation.COMPLETE;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Spec §11.1 {@code QueueRunnerLifecycleTest}: every scenario ends with the permit invariant (see endEveryTask). */
@Timeout(30)   // a deadlock fails the test instead of hanging the build
class QueueRunnerLifecycleTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String OWNER = "instance-a";
    /**
     * The IT column: concurrency 4, claim-batch-size 20, max-processing-time 25s, registration-allowance 200ms,
     * idle-poll-interval 100ms, poll-backoff-max 2s, renew-interval 1s, supervisor-interval 100ms, hung-grace 2s,
     * hung-task-limit 1, shutdown-grace 2s, shutdown-cancel-wait 1s.
     */
    private static final QueueRunner.Settings SETTINGS = QueueRunner.Settings.from(ItConfig.properties());
    private static final int CONCURRENCY = SETTINGS.concurrency();
    /** The IT column with a minute between sweeps and between samples: those loops end soon only if interrupted. */
    private static final QueueRunner.Settings MINUTE_PASSES = minutePasses();
    private static final DataAccessResourceFailureException UNREACHABLE =
            new DataAccessResourceFailureException("Db2 unreachable");

    private final ScriptedRepository repository = new ScriptedRepository();
    // The fake clock starts 5s before overflow, so every deadline in these tests wraps around.
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    // Every handle that received a permit, registered or not, for the permit invariant.
    private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
    private final Tasks tasks = new Tasks();
    // Every loop thread a runner built by liveRunner() started, for the tests of its loops.
    private final List<Thread> loops = new CopyOnWriteArrayList<>();
    private final Logger runnerLog = (Logger) LoggerFactory.getLogger(QueueRunner.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private QueueRunner runner = runner(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>());

    @BeforeEach
    void captureLogs() {
        logged.start();
        runnerLog.addAppender(logged);
    }

    @AfterEach
    void endEveryTask() {
        runnerLog.detachAppender(logged);
        runner.crash();
        tasks.releaseAll();
        await().untilAsserted(() -> assertThat(handles).allMatch(ClaimHandle::isEnded));
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
    }

    // ---- Poll loop and permits (spec §5.2 steps 1–3) ----------------------------------------------------------

    @Test
    void aClaimAsksForEveryFreePermit() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));

        runner.pollOnce();

        assertThat(repository.claimSizes()).containsExactly(4, 2);
        assertThat(runner.inflight()).isEqualTo(2);
        assertThat(runner.availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void aClaimAsksForNoMoreThanTheBatchSize() throws Exception {
        WorkQueueProperties properties = ItConfig.properties();
        properties.setClaimBatchSize(3);
        runner = new QueueRunner(repository, tasks, OWNER, QueueRunner.Settings.from(properties), recordingThreads(),
                now::get, new ConcurrentHashMap<>());

        runner.pollOnce();

        assertThat(repository.claimSizes()).containsExactly(3);
    }

    @Test
    void anEmptyClaimReturnsEveryPermitAndPausesForTheJitteredIdleInterval() throws Exception {
        Duration pause = runner.pollOnce();

        assertThat(pause).isBetween(ofMillis(50), ofMillis(150));
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aPartialClaimStartsItsRowsReturnsTheOtherPermitsAndPollsAgainAtOnce() throws Exception {
        repository.thenClaim(item(1, 1));

        assertThat(runner.pollOnce()).isZero();

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(runner.availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void aFailedClaimRegistersNothingReturnsEveryPermitAndBacksOffExponentially() throws Exception {
        List<Duration> pauses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            repository.thenClaimThrow(UNREACHABLE);
            pauses.add(runner.pollOnce());
            assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
        }

        assertThat(pauses).containsExactly(ofMillis(100), ofMillis(200), ofMillis(400), ofMillis(800),
                ofMillis(1600), ofSeconds(2), ofSeconds(2));
        assertThat(handles).isEmpty();
        assertThat(runner.inflight()).isZero();
    }

    @Test
    void aClaimThatSucceedsResetsTheBackoff() throws Exception {
        repository.thenClaimThrow(UNREACHABLE).thenClaimThrow(UNREACHABLE).thenClaim().thenClaimThrow(UNREACHABLE);
        runner.pollOnce();
        runner.pollOnce();
        runner.pollOnce();

        assertThat(runner.pollOnce()).isEqualTo(ofMillis(100));
    }

    @Test
    void aFailedClaimLogsOnlyClassNames() throws Exception {
        repository.thenClaimThrow(new DataAccessResourceFailureException("row of order-7:charge"));

        runner.pollOnce();

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Claim by owner instance-a failed; nothing claimed, next claim in PT0.1S: "
                            + "org.springframework.dao.DataAccessResourceFailureException");
        });
    }

    @Test
    void aClaimFailureWhoseCauseCannotBeReadStillBacksOff() throws Exception {
        RuntimeException failure = withUnreadableCause();
        repository.thenClaimThrow(failure);

        assertThat(runner.pollOnce()).isEqualTo(ofMillis(100));

        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).isEqualTo(
                "Claim by owner instance-a failed; nothing claimed, next claim in PT0.1S: "
                        + failure.getClass().getName());
    }

    @Test
    void anExceptionConstructingOneRowsHandleLeavesTheEarlierRowsRunningAndReturnsTheOtherPermits() {
        repository.thenClaim(item(1, 1), null, item(3, 1), item(4, 1));   // a null row fails the handle's constructor

        assertThatThrownBy(runner::pollOnce).isInstanceOf(NullPointerException.class);

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(handles).hasSize(1);
        assertThat(runner.availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void anExceptionCreatingOneRowsThreadLeavesTheEarlierRowsRunningAndReturnsTheOtherPermits() {
        runner = runner(tasks, (handle, body) -> {
            if (handle.key().id() == 2) {
                throw new IllegalStateException("no thread");
            }
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThatThrownBy(runner::pollOnce).isInstanceOf(IllegalStateException.class);

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(runner.availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void aRegistrationThatThrowsFinishesOnlyThatHandle() throws Exception {
        runner = runner(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>() {
            @Override
            public ClaimHandle putIfAbsent(ClaimKey key, ClaimHandle value) {
                if (key.id() == 2) {
                    throw new IllegalStateException("registry broken");
                }
                return super.putIfAbsent(key, value);
            }
        });
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThat(runner.pollOnce()).isZero();

        await().until(() -> Set.copyOf(tasks.started()).equals(Set.of(key(1, 1), key(3, 1))));
        assertThat(handle(key(2, 1)).isEnded()).isTrue();
        assertThat(runner.inflight()).isEqualTo(2);
        assertThat(runner.availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void aKeyCollisionFinishesTheNewHandleAndCountsAnInvariantViolation() throws Exception {
        claimAndStart(item(1, 5));
        repository.thenClaim(item(1, 5));

        runner.pollOnce();

        assertThat(runner.invariantViolations()).isEqualTo(1);
        assertThat(handles).extracting(ClaimHandle::isEnded).containsExactly(false, true);
        assertThat(tasks.started()).containsExactly(key(1, 5));
        assertThat(runner.inflight()).isEqualTo(1);
        assertPermitInvariant();
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Invariant violation: claim ClaimHandle[id=1, token=5]"
                    + " of owner instance-a is already registered; not started");
        });
    }

    @Test
    void aClaimReturningMoreRowsThanItHoldsPermitsForStartsOnlyThatManyAndCountsAnInvariantViolation()
            throws Exception {
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1), item(4, 1), item(5, 1));   // it asks for 4

        assertThat(runner.pollOnce()).isZero();

        await().until(() -> tasks.started().size() == CONCURRENCY);
        assertThat(handles).extracting(ClaimHandle::key)
                .containsExactly(key(1, 1), key(2, 1), key(3, 1), key(4, 1));
        assertThat(runner.invariantViolations()).isEqualTo(1);
        assertThat(runner.claimedRows()).as("every row the claim returned is CLAIMED").isEqualTo(5);
        assertThat(runner.availablePermits()).isZero();
        assertPermitInvariant();
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Invariant violation: claim by owner instance-a"
                    + " returned 5 rows for 4 permits; 1 not started");
        });
    }

    @Test
    void aThreadThatFailsToStartFinishesOnlyItsHandle() throws Exception {
        runner = runner(tasks, (handle, body) -> {
            if (handle.key().id() == 2) {
                handles.add(handle);
                return new Thread(body) {
                    @Override
                    public void start() {
                        throw new OutOfMemoryError("unable to create native thread");
                    }
                };
            }
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThat(runner.pollOnce()).isZero();

        await().until(() -> Set.copyOf(tasks.started()).equals(Set.of(key(1, 1), key(3, 1))));
        assertThat(handle(key(2, 1)).isEnded()).isTrue();
        assertThat(runner.inflight()).isEqualTo(2);
        assertThat(runner.availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void anInterruptWhileWaitingForAPermitLeavesEveryPermitWithItsHandle() throws Exception {
        claimAndStart(item(1, 1), item(2, 1), item(3, 1), item(4, 1));
        FutureTask<Duration> poll = new FutureTask<>(runner::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        await().until(() -> poller.getState() == Thread.State.WAITING);

        poller.interrupt();

        assertThatThrownBy(() -> poll.get(10, SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        assertThat(repository.claimSizes()).containsExactly(4);
        assertThat(runner.availablePermits()).isZero();
        assertPermitInvariant();
    }

    @Test
    void anInterruptDuringTheClaimReturnsEveryHeldPermit() throws Exception {
        CountDownLatch claiming = new CountDownLatch(1);
        repository.thenClaim(() -> {
            claiming.countDown();
            try {
                new CountDownLatch(1).await();   // until interrupted
                throw new AssertionError("not interrupted");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataAccessResourceFailureException("interrupted during the claim", e);
            }
        });
        FutureTask<Duration> poll = new FutureTask<>(runner::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        claiming.await();

        poller.interrupt();

        assertThat(poll.get(10, SECONDS)).as("a failed claim backs off").isEqualTo(ofMillis(100));
        assertThat(handles).isEmpty();
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aHandleCancelledBeforeItsBodyRunsIsNeverProcessedAndFinishesOnce() throws Exception {
        runner = runner(tasks, (handle, body) -> recordingThreads().newThread(handle, () -> {
            handle.cancel(CancelReason.SHUTDOWN, now.get());
            body.run();
        }), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        runner.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(tasks.started()).isEmpty();
        assertThat(runner.outcomes(Outcome.CANCELLED)).isEqualTo(1);
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aRegistrationLaterThanTheAllowanceIsCounted() throws Exception {
        runner = runner(tasks, (handle, body) -> {
            // Row 1 registers exactly registration-allowance after the claim returned, row 2 a nanosecond later.
            now.addAndGet(handle.key().id() == 1 ? ofMillis(200).toNanos() : 1);
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1));

        runner.pollOnce();

        assertThat(runner.registrationsLate()).isEqualTo(1);
    }

    @Test
    void aTaskThatThrowsIsLoggedByClassNameOnlyAndStillFinishes() throws Exception {
        runner = runner((item, cancelled) -> {
            throw new StackOverflowError("payload-1");
        }, recordingThreads(), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        runner.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Task for claim ClaimHandle[id=1, token=1] of owner"
                    + " instance-a failed: java.lang.StackOverflowError");
        });
        assertThat(Arrays.stream(Outcome.values()).mapToLong(runner::outcomes).sum()).as("no outcome").isZero();
    }

    @Test
    void aTaskFailureWhoseCauseCannotBeReadIsLoggedWithoutReachingTheUncaughtHandler() throws Exception {
        RuntimeException failure = withUnreadableCause();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        runner = runner((item, cancelled) -> {
            throw failure;
        }, (handle, body) -> {
            Thread thread = recordingThreads().newThread(handle, body);
            thread.setUncaughtExceptionHandler((ignored, e) -> uncaught.add(e));
            threads.add(thread);
            return thread;
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        runner.pollOnce();

        for (Thread thread : threads) {
            assertThat(thread.join(ofSeconds(10))).as("task thread ended").isTrue();
        }
        assertThat(uncaught).as("nothing reached the uncaught-exception handler").isEmpty();
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Task for claim ClaimHandle[id=1, token=1] of owner"
                    + " instance-a failed: " + failure.getClass().getName());
        });
    }

    @Test
    void theItemProcessorRunsAClaimedRowToCompletion() throws Exception {
        ItemProcessor processor = new ItemProcessor(repository,
                (key, token, payload, timeout) -> new CallResult("receipt-" + payload), OWNER, "it",
                ItemProcessor.Settings.from(ItConfig.properties()));
        runner = runner(processor::process, recordingThreads(), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1)).thenReturn(PersistResult.DONE);

        runner.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(repository.writes()).containsExactly(new Write(COMPLETE, OWNER, key(1, 1), "receipt-payload-1"));
        assertThat(logged.list).as("an unscripted persist fails the task, which logs it")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    // ---- Renewal (spec §5.3) --------------------------------------------------------------------------------

    @Test
    void aRoundWithNothingToRenewIsSkippedAndSucceeds() {
        assertThat(runner.renewOnce()).isTrue();

        assertThat(repository.renewRequests()).isEmpty();
    }

    @Test
    void theRowsOfAnUncertainClaimAreNeverRenewedOrProcessed() throws Exception {
        // The claim's rows committed, but its commit acknowledgement was lost: the repository throws.
        repository.thenClaim(() -> {
            throw new DataAccessResourceFailureException("commit acknowledgement lost");
        });

        runner.pollOnce();

        assertThat(runner.renewOnce()).isTrue();
        assertThat(repository.renewRequests()).as("nothing to renew").isEmpty();
        assertThat(tasks.started()).as("nothing called").isEmpty();
        assertThat(handles).isEmpty();
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aRoundRenewsEveryRenewableClaimInOneRequest() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));

        assertThat(runner.renewOnce()).isTrue();

        assertThat(repository.renewRequests()).containsExactly(Set.of(key(1, 1), key(2, 1)));
    }

    @Test
    void aRoundSkipsEndedCancelledAndPastDeadlineClaims() throws Exception {
        tasks.ignoreInterrupts();
        tasks.endWith(key(1, 1), Outcome.ABANDONED);
        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
        now.addAndGet(10 * SECOND);
        claimAndStart(item(4, 1));
        repository.thenRenewLosing(key(2, 1));
        runner.renewOnce();                          // claim 2 is lost: cancelled, but its task keeps running
        tasks.release(key(1, 1));                    // claim 1 is abandoned: ended
        await().until(() -> handle(key(1, 1)).isEnded());
        now.addAndGet(15 * SECOND);                  // claim 3's deadline; no supervisor pass has cancelled it

        runner.renewOnce();

        assertThat(handle(key(2, 1)).isEnded()).isFalse();
        assertThat(repository.renewRequests()).containsExactly(
                Set.of(key(1, 1), key(2, 1), key(3, 1), key(4, 1)), Set.of(key(4, 1)));
    }

    @Test
    void aClaimReportedLostIsCountedAndCancelled() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));
        repository.thenRenewLosing(key(1, 1));

        assertThat(runner.renewOnce()).isTrue();

        assertThat(runner.claimsLost()).isEqualTo(1);
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.LOST);
        assertThat(handle(key(2, 1)).isCancelled()).isFalse();
        await().until(() -> handle(key(1, 1)).isEnded());   // its task was interrupted
        runner.renewOnce();
        assertThat(repository.renewRequests().getLast()).containsExactly(key(2, 1));
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aClaimItsOwnTaskAlreadyEndedIsNeitherLostNorCancelled() throws Exception {
        claimAndStart(item(1, 1));
        repository.thenRenewEnded(key(1, 1));   // its task persisted after the round took its snapshot

        assertThat(runner.renewOnce()).isTrue();

        assertThat(runner.claimsLost()).isZero();
        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
    }

    @Test
    void aClaimReportedLostThatTheRoundDidNotRequestIsAnInvariantViolationAndSkipped() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));
        repository.thenRenew(requested -> new RenewalResult(Set.of(key(2, 1)), Set.of(), Set.of(key(1, 1), key(9, 1))));

        assertThat(runner.renewOnce()).isTrue();

        assertThat(runner.invariantViolations()).isEqualTo(1);
        assertThat(runner.claimsLost()).isEqualTo(1);
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.LOST);
        assertThat(handle(key(2, 1)).isCancelled()).isFalse();
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage).isEqualTo("Invariant violation: renewal of owner"
                        + " instance-a reported claim ClaimKey[id=9, token=1] lost, which it did not request");
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aRenewalThatReportsAClaimInTwoSetsFailsTheRoundAndCancelsNothing() throws Exception {
        claimAndStart(item(1, 1));
        repository.thenRenew(requested -> new RenewalResult(requested, Set.of(), requested));

        assertThat(runner.renewOnce()).isFalse();

        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
        assertThat(runner.claimsLost()).isZero();
    }

    @Test
    void aFailedRoundCancelsNothingAndTheNextRoundRenewsTheSameClaims() throws Exception {
        claimAndStart(item(1, 1));
        repository.thenRenewThrow(UNREACHABLE);

        assertThat(runner.renewOnce()).isFalse();
        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
        assertThat(runner.renewOnce()).isTrue();

        assertThat(repository.renewRequests()).containsExactly(Set.of(key(1, 1)), Set.of(key(1, 1)));
    }

    @Test
    void anOldHandleEndingAfterItsRowWasReclaimedLeavesTheNewClaimRegisteredAndRenewed() throws Exception {
        claimAndStart(item(7, 1));
        claimAndStart(item(7, 2));   // the same row, re-claimed by this owner after its lease expired
        tasks.release(key(7, 1));
        await().until(() -> handle(key(7, 1)).isEnded());

        runner.renewOnce();

        assertThat(runner.inflight()).isEqualTo(1);
        assertThat(repository.renewRequests()).containsExactly(Set.of(key(7, 2)));
        await().untilAsserted(this::assertPermitInvariant);
    }

    // ---- Supervisor (spec §5.2) -----------------------------------------------------------------------------

    @Test
    void theSupervisorCancelsAClaimAtItsDeadlineAndNotBefore() throws Exception {
        claimAndStart(item(1, 1));
        now.addAndGet(25 * SECOND - 1);
        runner.superviseOnce();
        assertThat(handle(key(1, 1)).isCancelled()).isFalse();

        now.addAndGet(1);
        runner.superviseOnce();

        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.DEADLINE);
        await().until(() -> handle(key(1, 1)).isEnded());   // its task was interrupted
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aCancelledTaskThatIgnoresInterruptsIsReportedHungOnceAndKeepsItsPermit() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1));
        now.addAndGet(25 * SECOND);
        runner.superviseOnce();                     // cancelled at its deadline
        now.addAndGet(2 * SECOND - 1);
        runner.superviseOnce();
        assertThat(runner.hungTasks()).isZero();

        now.addAndGet(1);                           // hung-grace after the cancel
        runner.superviseOnce();
        runner.superviseOnce();

        assertThat(runner.hungTasks()).isEqualTo(1);
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY - 1);
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage).asString()
                .startsWith("Claim ClaimHandle[id=1, token=1] of owner instance-a is hung: still running PT2S after"
                        + " it was cancelled (DEADLINE)")
                .contains("\tat ");
        tasks.releaseAll();
        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(runner.hungTasks()).isZero();
    }

    @Test
    void reachingTheHungTaskLimitStopsClaimingUntilTheHungTaskEnds() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1));
        now.addAndGet(25 * SECOND);
        runner.superviseOnce();
        now.addAndGet(2 * SECOND);
        runner.superviseOnce();
        assertThat(runner.hungTasks()).as("hung-task-limit is 1").isEqualTo(1);
        assertThat(runner.hungTaskLimitReached()).isTrue();

        assertThat(runner.pollOnce()).as("the supervisor interval").isEqualTo(ofMillis(100));
        assertThat(repository.claimSizes()).containsExactly(4);
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY - 1);

        tasks.releaseAll();
        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(runner.hungTaskLimitReached()).isFalse();
        runner.pollOnce();
        assertThat(repository.claimSizes()).containsExactly(4, 4);
    }

    @Test
    void cancelledTasksThatIgnoreInterruptsStillCountAgainstConcurrency() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1), item(2, 1), item(3, 1), item(4, 1));
        now.addAndGet(25 * SECOND);
        runner.superviseOnce();                     // all four cancelled at their deadline; none ends
        FutureTask<Duration> poll = new FutureTask<>(runner::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        await().until(() -> poller.getState() == Thread.State.WAITING);

        tasks.release(key(1, 1));

        poll.get(10, SECONDS);
        assertThat(repository.claimSizes()).containsExactly(4, 1);
        assertThat(tasks.highWater()).isEqualTo(CONCURRENCY);
    }

    // ---- Sweeper and backlog sampler (spec §6) --------------------------------------------------------------

    @Test
    void aSweepPassSweepsBatchesOfTheSweepBatchSizeAndIsADbSuccess() {
        now.addAndGet(5 * SECOND);
        repository.thenSweep(100, 3);

        assertThat(runner.sweepOnce()).isEqualTo(103);

        assertThat(repository.sweepSizes()).containsExactly(100, 100);
        assertThat(runner.dbLastSuccessAge()).isZero();
    }

    @Test
    void theLatestBacklogSampleIsKeptAndIsADbSuccess() {
        BacklogSample sample = new BacklogSample(12, 4, 1, 0, ofSeconds(30));
        now.addAndGet(5 * SECOND);
        assertThat(runner.backlog()).isNull();
        assertThat(runner.backlogSampleAge()).as("counted from the runner's creation").isEqualTo(ofSeconds(5));
        repository.thenSample(sample);

        assertThat(runner.sampleOnce()).isTrue();

        assertThat(runner.backlog()).isEqualTo(sample);
        assertThat(runner.backlogSampleAge()).isZero();
        assertThat(runner.dbLastSuccessAge()).isZero();
    }

    @Test
    void aFailedBacklogSampleIsCountedAndTheSampleAgeKeepsGrowing() {
        repository.thenSampleThrow(UNREACHABLE);
        now.addAndGet(5 * SECOND);

        assertThat(runner.sampleOnce()).isFalse();

        assertThat(runner.backlog()).isNull();
        assertThat(runner.backlogSampleErrors()).isEqualTo(1);
        assertThat(runner.backlogSampleAge()).isEqualTo(ofSeconds(5));
    }

    // ---- What health and the meters read (spec §9.6) ---------------------------------------------------------

    @Test
    void aClaimIsTimedAndCountedAndItsLeaseCountsFromTheClaimsStart() throws Exception {
        long start = now.get();
        repository.thenClaim(() -> {
            now.addAndGet(3 * SECOND);   // the claim takes 3s
            return List.of(item(1, 1), item(2, 1));
        });

        runner.pollOnce();

        assertThat(runner.claims()).isEqualTo(1);
        assertThat(runner.claimedRows()).isEqualTo(2);
        assertThat(runner.claimErrors()).isZero();
        assertThat(runner.claimTimes().count()).isEqualTo(1);
        assertThat(runner.claimTimes().totalNanos()).isEqualTo(3 * SECOND);
        assertThat(handle(key(1, 1)).leaseWrittenAt()).isEqualTo(start);
        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(3));
    }

    @Test
    void aFailedClaimIsTimedAndCountedAsAnError() throws Exception {
        repository.thenClaim(() -> {
            now.addAndGet(2 * SECOND);
            throw UNREACHABLE;
        });

        runner.pollOnce();

        assertThat(runner.claims()).isZero();
        assertThat(runner.claimedRows()).isZero();
        assertThat(runner.claimErrors()).isEqualTo(1);
        assertThat(runner.claimTimes().count()).isEqualTo(1);
        assertThat(runner.claimTimes().totalNanos()).isEqualTo(2 * SECOND);
    }

    @Test
    void theRenewalLagIgnoresEndedCancelledAndPastDeadlineClaims() throws Exception {
        assertThat(runner.renewalLag()).as("no claims").isZero();
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
        now.addAndGet(5 * SECOND);
        tasks.release(key(1, 1));                    // claim 1 ends
        await().until(() -> handle(key(1, 1)).isEnded());
        repository.thenRenewLosing(key(2, 1));
        runner.renewOnce();                          // claim 2 is lost: cancelled, but its task keeps running
        now.addAndGet(20 * SECOND);                  // claim 3's deadline; no supervisor pass has cancelled it

        assertThat(runner.renewalLag()).isZero();
        assertThat(runner.inflight()).isEqualTo(2);
    }

    @Test
    void aRoundThatRenewsAClaimRestartsItsLagFromTheRoundsStart() throws Exception {
        claimAndStart(item(1, 1));
        now.addAndGet(5 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(SECOND);   // the round takes a second
            return new RenewalResult(requested, Set.of(), Set.of());
        });

        assertThat(runner.renewOnce()).isTrue();

        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(1));
        assertThat(runner.renewalTimes().count()).isEqualTo(1);
        assertThat(runner.renewalTimes().totalNanos()).isEqualTo(SECOND);
        assertThat(runner.renewalErrors()).isZero();
    }

    @Test
    void aClaimTheRoundDidNotRenewKeepsItsLag() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));
        now.addAndGet(5 * SECOND);
        repository.thenRenewEnded(key(2, 1));   // claim 2's task persisted after the snapshot and is still ending

        runner.renewOnce();

        assertThat(handle(key(1, 1)).leaseWrittenAt()).isEqualTo(now.get());
        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(5));
    }

    @Test
    void aFailedRoundLeavesTheLagGrowingAndCountsAnError() throws Exception {
        claimAndStart(item(1, 1));
        now.addAndGet(5 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(SECOND);
            throw UNREACHABLE;
        });

        assertThat(runner.renewOnce()).isFalse();

        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(6));
        assertThat(runner.renewalErrors()).isEqualTo(1);
        assertThat(runner.renewalTimes().count()).isEqualTo(1);
        assertThat(runner.renewalTimes().totalNanos()).isEqualTo(SECOND);
    }

    @Test
    void theDbAgeCountsFromCreationThenFromTheLastClaimOrRoundThatReturned() throws Exception {
        now.addAndGet(5 * SECOND);
        assertThat(runner.dbLastSuccessAge()).isEqualTo(ofSeconds(5));
        runner.renewOnce();                          // skipped: nothing to renew
        assertThat(runner.dbLastSuccessAge()).as("a skipped round is no DB success").isEqualTo(ofSeconds(5));
        assertThat(runner.renewalTimes().count()).as("nor a round that ran").isZero();

        runner.pollOnce();                           // an empty claim
        assertThat(runner.dbLastSuccessAge()).isZero();

        now.addAndGet(3 * SECOND);
        repository.thenClaimThrow(UNREACHABLE);
        runner.pollOnce();
        assertThat(runner.dbLastSuccessAge()).isEqualTo(ofSeconds(3));

        claimAndStart(item(1, 1));
        now.addAndGet(2 * SECOND);
        repository.thenRenewThrow(UNREACHABLE);
        runner.renewOnce();
        assertThat(runner.dbLastSuccessAge()).isEqualTo(ofSeconds(2));
        runner.renewOnce();
        assertThat(runner.dbLastSuccessAge()).isZero();
    }

    @Test
    void everyTaskEndIsCountedByItsOutcome() throws Exception {
        tasks.endWith(key(2, 1), Outcome.RETRY_SCHEDULED);
        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
        repository.thenRenewLosing(key(3, 1));
        runner.renewOnce();                          // claim 3 is cancelled: its task is interrupted

        tasks.release(key(1, 1));
        tasks.release(key(2, 1));
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));

        assertThat(runner.outcomes(Outcome.COMPLETED)).isEqualTo(1);
        assertThat(runner.outcomes(Outcome.RETRY_SCHEDULED)).isEqualTo(1);
        assertThat(runner.outcomes(Outcome.INTERRUPTED)).isEqualTo(1);
        assertThat(runner.outcomes(Outcome.FAILED)).isZero();
    }

    // ---- start, stop and crash (spec §5.2) ------------------------------------------------------------------

    @Test
    void stopDrainsTheRunningTasksWhileRenewingThemAndClaimsNothingMore() throws Exception {
        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1));
        runner.start();
        await().until(() -> tasks.started().size() == 2);
        FutureTask<Void> stop = new FutureTask<>(runner::stop, null);
        Thread.ofVirtual().start(stop);
        await().until(runner::isStopping);
        int roundsBefore = repository.renewRequests().size();
        // stop() joins the poll loop before it drains, so claiming ends at once: at most one claim that was already
        // in flight lands after isStopping(), and it only restarts the 300ms window. A loop still idle-polling
        // (every 50-150ms) never leaves the count alone that long, and the await fails well inside shutdown-grace.
        AtomicInteger claims = new AtomicInteger(-1);
        await().during(ofMillis(300)).atMost(ofSeconds(1)).until(() -> {
            int count = repository.claimSizes().size();
            return claims.getAndSet(count) == count;
        });

        await().until(() -> repository.renewRequests().size() > roundsBefore);   // renewal continues while draining
        tasks.releaseAll();
        stop.get(10, SECONDS);

        assertThat(repository.claimSizes()).as("no claim after the poll loop stopped").hasSize(claims.get());
        assertThat(handles).noneMatch(ClaimHandle::isCancelled);
        assertThat(repository.writes()).as("nothing released in Db2").isEmpty();
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void stopCancelsTheTasksStillRunningAtTheGraceDeadline() throws Exception {
        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));
        runner.start();
        await().until(() -> tasks.started().size() == 1);
        long start = System.nanoTime();

        runner.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("shutdown-grace, then a prompt drain").isBetween(ofSeconds(2), ofSeconds(3));
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.SHUTDOWN);
        assertThat(handle(key(1, 1)).isEnded()).isTrue();
        assertThat(repository.writes()).isEmpty();
    }

    @Test
    void stopReturnsAfterTheCancelWaitEvenIfATaskIgnoresInterrupts() throws Exception {
        tasks.ignoreInterrupts();
        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));
        runner.start();
        await().until(() -> tasks.started().size() == 1);
        long start = System.nanoTime();

        runner.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("shutdown-grace + shutdown-cancel-wait").isBetween(ofSeconds(3), ofSeconds(4));
        assertThat(handle(key(1, 1)).isEnded()).as("still running").isFalse();
        assertThat(runner.availablePermits()).as("it keeps its permit").isEqualTo(CONCURRENCY - 1);
    }

    @Test
    void crashCancelsEveryClaimAtOnceAndStopsTheLoops() throws Exception {
        runner = liveRunner(MINUTE_PASSES);
        repository.thenClaim(item(1, 1), item(2, 1));
        runner.start();
        await().until(() -> tasks.started().size() == 2);
        // Past their first passes, the sweeper and sampler sleep for a minute: only crash()'s interrupt ends them.
        await().until(() -> repository.sweepSizes().size() == 1 && repository.samples() == 1);
        long start = System.nanoTime();

        runner.crash();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no waiting").isLessThan(ofMillis(500));
        assertThat(handles).extracting(ClaimHandle::cancelReason).containsOnly(CancelReason.CRASH);
        assertThat(runner.isRunning()).isFalse();
        int claims = repository.claimSizes().size();
        await().during(ofMillis(300)).atMost(ofSeconds(2))
                .until(() -> repository.claimSizes().size() == claims);
        assertThat(loops).hasSize(5);
        await().atMost(ofSeconds(5)).until(() -> loops.stream().noneMatch(Thread::isAlive));
    }

    @Test
    void theSweeperAndTheBacklogSamplerRunEveryIntervalUntilStop() {
        runner = liveRunner();

        runner.start();

        await().atMost(ofSeconds(5)).until(() -> repository.sweepSizes().size() >= 2 && repository.samples() >= 2);
        runner.stop();
        assertThat(loops).extracting(Thread::getName).containsExactly("workqueue-poll", "workqueue-renewal",
                "workqueue-supervisor", "workqueue-sweeper", "workqueue-backlog-sampler");
        assertThat(loops).noneMatch(Thread::isAlive);
    }

    @Test
    void stopWaitsForAllFourLoopsAfterThePollLoopWithinOneSecondInAll() {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch sweeping = new CountDownLatch(1);
        CountDownLatch sampling = new CountDownLatch(1);
        repository.thenSweep(() -> {
            sweeping.countDown();
            awaitIgnoringInterrupts(release);
            return 0;
        }).thenSample(() -> {
            sampling.countDown();
            awaitIgnoringInterrupts(release);
            return new BacklogSample(0, 0, 0, 0, Duration.ZERO);
        });
        runner = liveRunner();
        runner.start();
        awaitIgnoringInterrupts(sweeping);
        awaitIgnoringInterrupts(sampling);
        long start = System.nanoTime();

        runner.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("one shared second, not one per loop").isBetween(ofSeconds(1), ofMillis(1500));
        release.countDown();
        await().until(() -> loops.stream().noneMatch(Thread::isAlive));
    }

    @Test
    void crashAlsoCancelsAClaimTransferredButNotYetRegistered() throws Exception {
        runner = runner(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>() {
            @Override
            public ClaimHandle putIfAbsent(ClaimKey key, ClaimHandle value) {
                runner.crash();   // the crash lands between the transfer and the registration
                return super.putIfAbsent(key, value);
            }
        });
        repository.thenClaim(item(1, 1));

        runner.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.CRASH);
        assertThat(tasks.started()).isEmpty();
    }

    @Test
    void anErrorEndsItsLoopAndIsLoggedByClassNameOnly() {
        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
        repository.thenClaim(() -> {
            throw new StackOverflowError("row of order-7:charge");
        });

        runner.start();

        await().until(() -> {
            synchronized (logged) {   // the appender appends under its own lock
                return !logged.list.isEmpty();
            }
        });
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Poll loop of owner instance-a died: java.lang.StackOverflowError");
        });
        await().until(() -> runner.deadLoops().equals(List.of("poll")));
    }

    @Test
    void aLoopThatDiesIsReportedDeadUntilTheRunnerStops() {
        Set<String> dying = Set.of("workqueue-poll", "workqueue-renewal", "workqueue-supervisor");
        runner = new QueueRunner(repository, tasks, OWNER, SETTINGS, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>() {
                    @Override
                    public Collection<ClaimHandle> values() {   // each of the three loops reads the registry
                        if (dying.contains(Thread.currentThread().getName())) {
                            throw new StackOverflowError();
                        }
                        return super.values();
                    }
                }, recordingLoops());

        runner.start();

        await().until(() -> runner.deadLoops().equals(List.of("poll", "renewal", "supervisor")));
        runner.stop();
        assertThat(runner.deadLoops()).isEmpty();
    }

    @Test
    void loopsThatStopEndsAreNotDead() throws Exception {
        runner = liveRunner();
        repository.thenClaim(item(1, 1));
        runner.start();
        await().until(() -> tasks.started().size() == 1);
        FutureTask<Void> stop = new FutureTask<>(runner::stop, null);
        Thread.ofVirtual().start(stop);
        Thread poll = loops.getFirst();
        await().until(() -> runner.isStopping() && !poll.isAlive());   // stop drains the task with the poll loop ended

        assertThat(runner.deadLoops()).isEmpty();

        tasks.releaseAll();
        stop.get(10, SECONDS);
    }

    @Test
    void aSweeperOrBacklogSamplerLoopThatDiesIsLoggedByClassNameOnlyAndReportedDeadUntilTheRunnerStops() {
        repository.thenSweep(() -> {
            throw new StackOverflowError("row of order-7:charge");
        }).thenSample(() -> {
            throw new StackOverflowError("row of order-7:charge");
        });
        runner = liveRunner();

        runner.start();

        await().until(() -> runner.deadLoops().equals(List.of("sweeper", "backlog-sampler")));
        synchronized (logged) {
            assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactlyInAnyOrder(
                    "Sweeper loop of owner instance-a died: java.lang.StackOverflowError",
                    "Backlog sampler loop of owner instance-a died: java.lang.StackOverflowError");
        }
        runner.stop();
        assertThat(runner.deadLoops()).isEmpty();
    }

    @Test
    void aLoopThreadThatFailsToStartEndsTheLoopsAndCancelsAClaimAlreadyInFlight() throws Exception {
        CountDownLatch claiming = new CountDownLatch(1);
        CountDownLatch claimReturns = new CountDownLatch(1);
        repository.thenClaim(() -> {
            claiming.countDown();
            awaitIgnoringInterrupts(claimReturns);   // the claim commits and returns although start() interrupts it
            return List.of(item(1, 1));
        });
        runner = new QueueRunner(repository, tasks, OWNER, SETTINGS, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), (name, loop) -> {
                    if (name.equals("workqueue-renewal")) {
                        awaitIgnoringInterrupts(claiming);   // the poll loop is inside its claim
                        throw new OutOfMemoryError("unable to create thread");
                    }
                    return recordingLoops().start(name, loop);
                });

        assertThatThrownBy(runner::start).isInstanceOf(OutOfMemoryError.class);
        claimReturns.countDown();

        await().until(() -> handles.stream().anyMatch(handle -> handle.key().equals(key(1, 1)) && handle.isEnded()));
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.SHUTDOWN);
        assertThat(tasks.started()).as("the in-flight claim's task never ran").isEmpty();
        assertThat(loops).extracting(Thread::getName).containsExactly("workqueue-poll");
        assertThat(loops.getFirst().join(ofSeconds(10))).as("the poll loop ended").isTrue();
        assertThat(runner.isRunning()).isFalse();
        assertThatThrownBy(runner::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLoopThreadThatFailsToStartLastEndsEveryLoopStartedBeforeIt() {
        CountDownLatch sweeping = new CountDownLatch(1);
        repository.thenSweep(() -> {
            sweeping.countDown();
            return 0;
        });
        runner = new QueueRunner(repository, tasks, OWNER, MINUTE_PASSES, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), (name, loop) -> {
                    if (name.equals("workqueue-backlog-sampler")) {
                        awaitIgnoringInterrupts(sweeping);   // the sweeper is in its first pass: only an interrupt ends it
                        throw new OutOfMemoryError("unable to create thread");
                    }
                    return recordingLoops().start(name, loop);
                });

        assertThatThrownBy(runner::start).isInstanceOf(OutOfMemoryError.class);

        assertThat(loops).extracting(Thread::getName)
                .containsExactly("workqueue-poll", "workqueue-renewal", "workqueue-supervisor", "workqueue-sweeper");
        await().atMost(ofSeconds(5)).until(() -> loops.stream().noneMatch(Thread::isAlive));
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void aRunnerCannotBeRestarted() {
        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
        runner.start();
        runner.stop();

        assertThatThrownBy(runner::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void springDoesNotPauseTheRunnerSinceItCannotRestart() {
        assertThat(runner.isPauseable()).isFalse();
    }

    // ---- Settings -------------------------------------------------------------------------------------------

    @Test
    void settingsComeFromTheProperties() {
        assertThat(QueueRunner.Settings.from(new WorkQueueProperties())).isEqualTo(new QueueRunner.Settings(16, 20,
                ofSeconds(1), ofSeconds(30), ofSeconds(1), ofSeconds(15), ofSeconds(1), ofSeconds(120), ofSeconds(1),
                ofSeconds(30), 4, ofSeconds(20), ofSeconds(5), ofSeconds(30), 100, ofSeconds(30)));
    }

    @Test
    void settingsRejectANonPositiveDurationOrACountBelowOne() {
        Map<String, Consumer<WorkQueueProperties>> invalid = new LinkedHashMap<>();
        invalid.put("concurrency", properties -> properties.setConcurrency(0));
        invalid.put("claimBatchSize", properties -> properties.setClaimBatchSize(0));
        invalid.put("hungTaskLimit", properties -> properties.setHungTaskLimit(0));
        invalid.put("idlePollInterval", properties -> properties.setIdlePollInterval(Duration.ZERO));
        invalid.put("pollBackoffMax", properties -> properties.setPollBackoffMax(Duration.ZERO));
        invalid.put("supervisorInterval", properties -> properties.setSupervisorInterval(Duration.ZERO));
        invalid.put("hungGrace", properties -> properties.setHungGrace(ofSeconds(-1)));
        invalid.put("shutdownGrace", properties -> properties.setShutdownGrace(Duration.ZERO));
        invalid.put("shutdownCancelWait", properties -> properties.setShutdownCancelWait(Duration.ZERO));
        invalid.put("sweepInterval", properties -> properties.setSweepInterval(Duration.ZERO));
        invalid.put("sweepBatchSize", properties -> properties.setSweepBatchSize(0));
        invalid.put("backlogSampleInterval", properties -> properties.setBacklogSampleInterval(Duration.ZERO));

        invalid.forEach((name, change) -> {
            WorkQueueProperties properties = ItConfig.properties();
            change.accept(properties);
            assertThatThrownBy(() -> QueueRunner.Settings.from(properties))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        });
    }

    @Test
    void rejectsAnInvalidOwner() {
        assertThatThrownBy(() -> new QueueRunner(repository, tasks, " ", SETTINGS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Helpers --------------------------------------------------------------------------------------------

    /** Spec §5.2 with held = 0: call it only while no pollOnce is running. */
    private void assertPermitInvariant() {
        long notEnded = handles.stream().filter(handle -> !handle.isEnded()).count();
        assertThat(runner.availablePermits() + notEnded).as("permits.available + handles not ended")
                .isEqualTo(CONCURRENCY);
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        runner.pollOnce();
        List<ClaimKey> keys = Arrays.stream(items).map(ClaimedItem::key).toList();
        await().until(() -> tasks.started().containsAll(keys));
    }

    private QueueRunner runner(QueueRunner.Processor processor, QueueRunner.TaskThreads threads, LongSupplier clock,
                               ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        return new QueueRunner(repository, processor, OWNER, SETTINGS, threads, clock, registry);
    }

    // A runner on the real clock whose loop threads are recorded.
    private QueueRunner liveRunner() {
        return liveRunner(SETTINGS);
    }

    private QueueRunner liveRunner(QueueRunner.Settings settings) {
        return new QueueRunner(repository, tasks, OWNER, settings, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), recordingLoops());
    }

    private static QueueRunner.Settings minutePasses() {
        WorkQueueProperties properties = ItConfig.properties();
        properties.setSweepInterval(ofMinutes(1));
        properties.setBacklogSampleInterval(ofMinutes(1));
        return QueueRunner.Settings.from(properties);
    }

    // Production's loop threads, recorded in the order they start.
    private QueueRunner.LoopThreads recordingLoops() {
        return (name, loop) -> {
            Thread thread = QueueRunner.VIRTUAL_LOOP_THREADS.start(name, loop);
            loops.add(thread);
            return thread;
        };
    }

    // Production's virtual threads, recording every handle that receives a thread and with it a permit.
    private QueueRunner.TaskThreads recordingThreads() {
        return (handle, body) -> {
            handles.add(handle);
            return QueueRunner.VIRTUAL_THREADS.newThread(handle, body);
        };
    }

    private ClaimHandle handle(ClaimKey key) {
        return handles.stream().filter(handle -> handle.key().equals(key)).findFirst().orElseThrow();
    }

    private static ClaimedItem item(long id, long token) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, token);
    }

    // Waits like a JDBC call that completes although its thread was interrupted, then restores the interrupt.
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(10, SECONDS)) {
                        throw new AssertionError("latch not released");
                    }
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A failure whose message is business data and whose cause accessor throws an Error carrying more of it. */
    private static RuntimeException withUnreadableCause() {
        return new IllegalStateException("sensitive-payload-from-message") {
            @Override
            public synchronized Throwable getCause() {
                throw new AssertionError("sensitive-payload-from-accessor");
            }
        };
    }

    private static ClaimKey key(long id, long token) {
        return new ClaimKey(id, token);
    }
}
