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

/** Spec §11.1 {@code ClaimExecutionTest}: every scenario ends with the permit invariant (see endEveryTask). */
@Timeout(30)   // a deadlock fails the test instead of hanging the build
class ClaimExecutionTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String OWNER = "instance-a";
    /**
     * The IT column: concurrency 4, claim-batch-size 20, max-processing-time 25s, registration-allowance 200ms,
     * idle-poll-interval 100ms, poll-backoff-max 2s, renew-interval 1s, supervisor-interval 100ms, hung-grace 2s,
     * hung-task-limit 1, shutdown-grace 2s, shutdown-cancel-wait 1s.
     */
    private static final EngineSettings SETTINGS = EngineSettings.from(ItConfig.properties());
    private static final int CONCURRENCY = SETTINGS.concurrency();
    /** The IT column with a minute between sweeps and between samples: those loops end soon only if interrupted. */
    private static final DataAccessResourceFailureException UNREACHABLE =
            new DataAccessResourceFailureException("Db2 unreachable");

    private final ScriptedRepository repository = new ScriptedRepository();
    // The fake clock starts 5s before overflow, so every deadline in these tests wraps around.
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    // Every handle that received a permit, registered or not, for the permit invariant.
    private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
    private final Tasks tasks = new Tasks();
    // Every loop thread a execution built by liveRunner() started, for the tests of its loops.
    private final List<Thread> loops = new CopyOnWriteArrayList<>();
    private final Logger executionLog = (Logger) LoggerFactory.getLogger(ClaimExecution.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private ClaimExecution execution = execution(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>());

    @BeforeEach
    void captureLogs() {
        logged.start();
        executionLog.addAppender(logged);
    }

    @AfterEach
    void endEveryTask() {
        executionLog.detachAppender(logged);
        execution.abort();
        tasks.releaseAll();
        await().untilAsserted(() -> assertThat(handles).allMatch(ClaimHandle::isEnded));
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aClaimAsksForEveryFreePermit() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));

        execution.pollOnce();

        assertThat(repository.claimSizes()).containsExactly(4, 2);
        assertThat(execution.snapshot(now.get()).inflight()).isEqualTo(2);
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void aClaimAsksForNoMoreThanTheBatchSize() throws Exception {
        WorkQueueProperties properties = ItConfig.properties();
        properties.setClaimBatchSize(3);
        execution = new ClaimExecution(repository, tasks, OWNER, EngineSettings.from(properties), new DbActivity(now::get),
                now::get, recordingThreads(), new ConcurrentHashMap<>());

        execution.pollOnce();

        assertThat(repository.claimSizes()).containsExactly(3);
    }

    @Test
    void anEmptyClaimReturnsEveryPermitAndPausesForTheJitteredIdleInterval() throws Exception {
        Duration pause = execution.pollOnce();

        assertThat(pause).isBetween(ofMillis(50), ofMillis(150));
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aPartialClaimStartsItsRowsReturnsTheOtherPermitsAndPollsAgainAtOnce() throws Exception {
        repository.thenClaim(item(1, 1));

        assertThat(execution.pollOnce()).isZero();

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void aFailedClaimRegistersNothingReturnsEveryPermitAndBacksOffExponentially() throws Exception {
        List<Duration> pauses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            repository.thenClaimThrow(UNREACHABLE);
            pauses.add(execution.pollOnce());
            assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY);
        }

        assertThat(pauses).containsExactly(ofMillis(100), ofMillis(200), ofMillis(400), ofMillis(800),
                ofMillis(1600), ofSeconds(2), ofSeconds(2));
        assertThat(handles).isEmpty();
        assertThat(execution.snapshot(now.get()).inflight()).isZero();
    }

    @Test
    void aClaimThatSucceedsResetsTheBackoff() throws Exception {
        repository.thenClaimThrow(UNREACHABLE).thenClaimThrow(UNREACHABLE).thenClaim().thenClaimThrow(UNREACHABLE);
        execution.pollOnce();
        execution.pollOnce();
        execution.pollOnce();

        assertThat(execution.pollOnce()).isEqualTo(ofMillis(100));
    }

    @Test
    void aFailedClaimLogsOnlyClassNames() throws Exception {
        repository.thenClaimThrow(new DataAccessResourceFailureException("row of order-7:charge"));

        execution.pollOnce();

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

        assertThat(execution.pollOnce()).isEqualTo(ofMillis(100));

        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY);
        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).isEqualTo(
                "Claim by owner instance-a failed; nothing claimed, next claim in PT0.1S: "
                        + failure.getClass().getName());
    }

    @Test
    void anExceptionConstructingOneRowsHandleLeavesTheEarlierRowsRunningAndReturnsTheOtherPermits() throws Exception {
        repository.thenClaim(item(1, 1), null, item(3, 1), item(4, 1));   // a null row fails the handle's constructor

        assertThat(execution.pollOnce()).isEqualTo(ofMillis(100));
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .satisfies(event -> {
                    assertThat(event.getThrowableProxy()).isNull();
                    assertThat(event.getFormattedMessage()).isEqualTo("Poll of owner instance-a failed; next claim in"
                            + " PT0.1S: java.lang.NullPointerException");
                });

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(handles).hasSize(1);
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void anExceptionCreatingOneRowsThreadLeavesTheEarlierRowsRunningAndReturnsTheOtherPermits() throws Exception {
        execution = execution(tasks, (handle, body) -> {
            if (handle.key().id() == 2) {
                throw new IllegalStateException("no thread");
            }
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThat(execution.pollOnce()).isEqualTo(ofMillis(100));
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .satisfies(event -> {
                    assertThat(event.getThrowableProxy()).isNull();
                    assertThat(event.getFormattedMessage()).isEqualTo("Poll of owner instance-a failed; next claim in"
                            + " PT0.1S: java.lang.IllegalStateException");
                });

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void constructionFailureAndClaimFailuresUseTheSameBackoffCounter() throws Exception {
        repository.thenClaim(item(1, 1), null);
        assertThat(execution.pollOnce()).isEqualTo(ofMillis(100));
        assertThat(execution.snapshot(now.get()).claimErrors()).isZero();
        await().until(() -> tasks.started().contains(key(1, 1)));
        List<Duration> pauses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            repository.thenClaimThrow(UNREACHABLE);
            pauses.add(execution.pollOnce());
            assertThat(execution.snapshot(now.get()).claimErrors()).isEqualTo(i + 1);
            assertPermitInvariant();
        }
        assertThat(pauses).containsExactly(ofMillis(200), ofMillis(400), ofMillis(800), ofMillis(1600),
                ofSeconds(2), ofSeconds(2));
        assertThat(execution.pollOnce()).isBetween(ofMillis(50), ofMillis(150));
        repository.thenClaimThrow(UNREACHABLE);
        assertThat(execution.pollOnce()).isEqualTo(ofMillis(100));
        assertThat(execution.snapshot(now.get()).claimErrors()).isEqualTo(7);
        assertPermitInvariant();
    }

    @Test
    void aRegistrationThatThrowsFinishesOnlyThatHandle() throws Exception {
        execution = execution(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>() {
            @Override
            public ClaimHandle putIfAbsent(ClaimKey key, ClaimHandle value) {
                if (key.id() == 2) {
                    throw new IllegalStateException("registry broken");
                }
                return super.putIfAbsent(key, value);
            }
        });
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThat(execution.pollOnce()).isZero();

        await().until(() -> Set.copyOf(tasks.started()).equals(Set.of(key(1, 1), key(3, 1))));
        assertThat(handle(key(2, 1)).isEnded()).isTrue();
        assertThat(execution.snapshot(now.get()).inflight()).isEqualTo(2);
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void aKeyCollisionFinishesTheNewHandleAndCountsAnInvariantViolation() throws Exception {
        claimAndStart(item(1, 5));
        repository.thenClaim(item(1, 5));

        execution.pollOnce();

        assertThat(execution.snapshot(now.get()).invariantViolations()).isEqualTo(1);
        assertThat(handles).extracting(ClaimHandle::isEnded).containsExactly(false, true);
        assertThat(tasks.started()).containsExactly(key(1, 5));
        assertThat(execution.snapshot(now.get()).inflight()).isEqualTo(1);
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

        assertThat(execution.pollOnce()).isZero();

        await().until(() -> tasks.started().size() == CONCURRENCY);
        assertThat(handles).extracting(ClaimHandle::key)
                .containsExactly(key(1, 1), key(2, 1), key(3, 1), key(4, 1));
        assertThat(execution.snapshot(now.get()).invariantViolations()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).claimedRows()).as("every row the claim returned is CLAIMED").isEqualTo(5);
        assertThat(execution.snapshot(now.get()).availablePermits()).isZero();
        assertPermitInvariant();
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Invariant violation: claim by owner instance-a"
                    + " returned 5 rows for 4 permits; 1 not started");
        });
    }

    @Test
    void aThreadThatFailsToStartFinishesOnlyItsHandle() throws Exception {
        execution = execution(tasks, (handle, body) -> {
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

        assertThat(execution.pollOnce()).isZero();

        await().until(() -> Set.copyOf(tasks.started()).equals(Set.of(key(1, 1), key(3, 1))));
        assertThat(handle(key(2, 1)).isEnded()).isTrue();
        assertThat(execution.snapshot(now.get()).inflight()).isEqualTo(2);
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void anInterruptWhileWaitingForAPermitLeavesEveryPermitWithItsHandle() throws Exception {
        claimAndStart(item(1, 1), item(2, 1), item(3, 1), item(4, 1));
        FutureTask<Duration> poll = new FutureTask<>(execution::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        await().until(() -> poller.getState() == Thread.State.WAITING);

        poller.interrupt();

        assertThatThrownBy(() -> poll.get(10, SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        assertThat(repository.claimSizes()).containsExactly(4);
        assertThat(execution.snapshot(now.get()).availablePermits()).isZero();
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
        FutureTask<Duration> poll = new FutureTask<>(execution::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        claiming.await();

        poller.interrupt();

        assertThat(poll.get(10, SECONDS)).as("a failed claim backs off").isEqualTo(ofMillis(100));
        assertThat(handles).isEmpty();
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aHandleCancelledBeforeItsBodyRunsIsNeverProcessedAndFinishesOnce() throws Exception {
        execution = execution(tasks, (handle, body) -> recordingThreads().newThread(handle, () -> {
            handle.cancel(CancelReason.SHUTDOWN, now.get());
            body.run();
        }), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        execution.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(tasks.started()).isEmpty();
        assertThat(execution.snapshot(now.get()).outcomes().get(Outcome.CANCELLED)).isEqualTo(1);
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aRegistrationLaterThanTheAllowanceIsCounted() throws Exception {
        execution = execution(tasks, (handle, body) -> {
            // Row 1 registers exactly registration-allowance after the claim returned, row 2 a nanosecond later.
            now.addAndGet(handle.key().id() == 1 ? ofMillis(200).toNanos() : 1);
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1));

        execution.pollOnce();

        assertThat(execution.snapshot(now.get()).registrationsLate()).isEqualTo(1);
    }

    @Test
    void aTaskThatThrowsIsLoggedByClassNameOnlyAndStillFinishes() throws Exception {
        execution = execution((item, cancelled) -> {
            throw new StackOverflowError("payload-1");
        }, recordingThreads(), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        execution.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Task for claim ClaimHandle[id=1, token=1] of owner"
                    + " instance-a failed: java.lang.StackOverflowError");
        });
        assertThat(Arrays.stream(Outcome.values()).mapToLong(outcome -> execution.snapshot(now.get()).outcomes().get(outcome)).sum()).as("no outcome").isZero();
    }

    @Test
    void aTaskFailureWhoseCauseCannotBeReadIsLoggedWithoutReachingTheUncaughtHandler() throws Exception {
        RuntimeException failure = withUnreadableCause();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        execution = execution((item, cancelled) -> {
            throw failure;
        }, (handle, body) -> {
            Thread thread = recordingThreads().newThread(handle, body);
            thread.setUncaughtExceptionHandler((ignored, e) -> uncaught.add(e));
            threads.add(thread);
            return thread;
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        execution.pollOnce();

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
        execution = execution(processor::process, recordingThreads(), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1)).thenReturn(PersistResult.DONE);

        execution.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(repository.writes()).containsExactly(new Write(COMPLETE, OWNER, key(1, 1), "receipt-payload-1"));
        assertThat(logged.list).as("an unscripted persist fails the task, which logs it")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    // ---- Renewal (spec §5.3) --------------------------------------------------------------------------------

    @Test
    void aRoundWithNothingToRenewIsSkippedAndSucceeds() {
        assertThat(execution.renewOnce()).isTrue();

        assertThat(repository.renewRequests()).isEmpty();
    }

    @Test
    void theRowsOfAnUncertainClaimAreNeverRenewedOrProcessed() throws Exception {
        // The claim's rows committed, but its commit acknowledgement was lost: the repository throws.
        repository.thenClaim(() -> {
            throw new DataAccessResourceFailureException("commit acknowledgement lost");
        });

        execution.pollOnce();

        assertThat(execution.renewOnce()).isTrue();
        assertThat(repository.renewRequests()).as("nothing to renew").isEmpty();
        assertThat(tasks.started()).as("nothing called").isEmpty();
        assertThat(handles).isEmpty();
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aRoundRenewsEveryRenewableClaimInOneRequest() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));

        assertThat(execution.renewOnce()).isTrue();

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
        execution.renewOnce();                          // claim 2 is lost: cancelled, but its task keeps running
        tasks.release(key(1, 1));                    // claim 1 is abandoned: ended
        await().until(() -> handle(key(1, 1)).isEnded());
        now.addAndGet(15 * SECOND);                  // claim 3's deadline; no supervisor pass has cancelled it

        execution.renewOnce();

        assertThat(handle(key(2, 1)).isEnded()).isFalse();
        assertThat(repository.renewRequests()).containsExactly(
                Set.of(key(1, 1), key(2, 1), key(3, 1), key(4, 1)), Set.of(key(4, 1)));
    }

    @Test
    void aClaimReportedLostIsCountedAndCancelled() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));
        repository.thenRenewLosing(key(1, 1));

        assertThat(execution.renewOnce()).isTrue();

        assertThat(execution.snapshot(now.get()).claimsLost()).isEqualTo(1);
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.LOST);
        assertThat(handle(key(2, 1)).isCancelled()).isFalse();
        await().until(() -> handle(key(1, 1)).isEnded());   // its task was interrupted
        execution.renewOnce();
        assertThat(repository.renewRequests().getLast()).containsExactly(key(2, 1));
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aClaimItsOwnTaskAlreadyEndedIsNeitherLostNorCancelled() throws Exception {
        claimAndStart(item(1, 1));
        repository.thenRenewEnded(key(1, 1));   // its task persisted after the round took its snapshot

        assertThat(execution.renewOnce()).isTrue();

        assertThat(execution.snapshot(now.get()).claimsLost()).isZero();
        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
    }

    @Test
    void aClaimReportedLostThatTheRoundDidNotRequestIsAnInvariantViolationAndSkipped() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));
        repository.thenRenew(requested -> new RenewalResult(Set.of(key(2, 1)), Set.of(), Set.of(key(1, 1), key(9, 1))));

        assertThat(execution.renewOnce()).isTrue();

        assertThat(execution.snapshot(now.get()).invariantViolations()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).claimsLost()).isEqualTo(1);
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

        assertThat(execution.renewOnce()).isFalse();

        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
        assertThat(execution.snapshot(now.get()).claimsLost()).isZero();
    }

    @Test
    void aFailedRoundCancelsNothingAndTheNextRoundRenewsTheSameClaims() throws Exception {
        claimAndStart(item(1, 1));
        repository.thenRenewThrow(UNREACHABLE);

        assertThat(execution.renewOnce()).isFalse();
        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
        assertThat(execution.renewOnce()).isTrue();

        assertThat(repository.renewRequests()).containsExactly(Set.of(key(1, 1)), Set.of(key(1, 1)));
    }

    @Test
    void anOldHandleEndingAfterItsRowWasReclaimedLeavesTheNewClaimRegisteredAndRenewed() throws Exception {
        claimAndStart(item(7, 1));
        claimAndStart(item(7, 2));   // the same row, re-claimed by this owner after its lease expired
        tasks.release(key(7, 1));
        await().until(() -> handle(key(7, 1)).isEnded());

        execution.renewOnce();

        assertThat(execution.snapshot(now.get()).inflight()).isEqualTo(1);
        assertThat(repository.renewRequests()).containsExactly(Set.of(key(7, 2)));
        await().untilAsserted(this::assertPermitInvariant);
    }

    // ---- Supervisor (spec §5.2) -----------------------------------------------------------------------------

    @Test
    void theSupervisorCancelsAClaimAtItsDeadlineAndNotBefore() throws Exception {
        claimAndStart(item(1, 1));
        now.addAndGet(25 * SECOND - 1);
        execution.superviseOnce();
        assertThat(handle(key(1, 1)).isCancelled()).isFalse();

        now.addAndGet(1);
        execution.superviseOnce();

        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.DEADLINE);
        await().until(() -> handle(key(1, 1)).isEnded());   // its task was interrupted
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aCancelledTaskThatIgnoresInterruptsIsReportedHungOnceAndKeepsItsPermit() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1));
        now.addAndGet(25 * SECOND);
        execution.superviseOnce();                     // cancelled at its deadline
        now.addAndGet(2 * SECOND - 1);
        execution.superviseOnce();
        assertThat(execution.snapshot(now.get()).hungTasks()).isZero();

        now.addAndGet(1);                           // hung-grace after the cancel
        execution.superviseOnce();
        execution.superviseOnce();

        assertThat(execution.snapshot(now.get()).hungTasks()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY - 1);
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage).asString()
                .startsWith("Claim ClaimHandle[id=1, token=1] of owner instance-a is hung: still running PT2S after"
                        + " it was cancelled (DEADLINE)")
                .contains("\tat ");
        tasks.releaseAll();
        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(execution.snapshot(now.get()).hungTasks()).isZero();
    }

    @Test
    void reachingTheHungTaskLimitStopsClaimingUntilTheHungTaskEnds() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1));
        now.addAndGet(25 * SECOND);
        execution.superviseOnce();
        now.addAndGet(2 * SECOND);
        execution.superviseOnce();
        assertThat(execution.snapshot(now.get()).hungTasks()).as("hung-task-limit is 1").isEqualTo(1);
        assertThat(execution.snapshot(now.get()).hungTaskLimitReached()).isTrue();

        assertThat(execution.pollOnce()).as("the supervisor interval").isEqualTo(ofMillis(100));
        assertThat(repository.claimSizes()).containsExactly(4);
        assertThat(execution.snapshot(now.get()).availablePermits()).isEqualTo(CONCURRENCY - 1);

        tasks.releaseAll();
        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(execution.snapshot(now.get()).hungTaskLimitReached()).isFalse();
        execution.pollOnce();
        assertThat(repository.claimSizes()).containsExactly(4, 4);
    }

    @Test
    void cancelledTasksThatIgnoreInterruptsStillCountAgainstConcurrency() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1), item(2, 1), item(3, 1), item(4, 1));
        now.addAndGet(25 * SECOND);
        execution.superviseOnce();                     // all four cancelled at their deadline; none ends
        FutureTask<Duration> poll = new FutureTask<>(execution::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        await().until(() -> poller.getState() == Thread.State.WAITING);

        tasks.release(key(1, 1));

        poll.get(10, SECONDS);
        assertThat(repository.claimSizes()).containsExactly(4, 1);
        assertThat(tasks.highWater()).isEqualTo(CONCURRENCY);
    }

    // ---- Sweeper and backlog sampler (spec §6) --------------------------------------------------------------

    @Test
    void aClaimIsTimedAndCountedAndItsLeaseCountsFromTheClaimsStart() throws Exception {
        long start = now.get();
        repository.thenClaim(() -> {
            now.addAndGet(3 * SECOND);   // the claim takes 3s
            return List.of(item(1, 1), item(2, 1));
        });

        execution.pollOnce();

        assertThat(execution.snapshot(now.get()).claims()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).claimedRows()).isEqualTo(2);
        assertThat(execution.snapshot(now.get()).claimErrors()).isZero();
        assertThat(execution.snapshot(now.get()).claimTimes().count()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).claimTimes().totalNanos()).isEqualTo(3 * SECOND);
        assertThat(handle(key(1, 1)).leaseWrittenAt()).isEqualTo(start);
        assertThat(execution.snapshot(now.get()).renewalLag()).isEqualTo(ofSeconds(3));
    }

    @Test
    void aFailedClaimIsTimedAndCountedAsAnError() throws Exception {
        repository.thenClaim(() -> {
            now.addAndGet(2 * SECOND);
            throw UNREACHABLE;
        });

        execution.pollOnce();

        assertThat(execution.snapshot(now.get()).claims()).isZero();
        assertThat(execution.snapshot(now.get()).claimedRows()).isZero();
        assertThat(execution.snapshot(now.get()).claimErrors()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).claimTimes().count()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).claimTimes().totalNanos()).isEqualTo(2 * SECOND);
    }

    @Test
    void theRenewalLagIgnoresEndedCancelledAndPastDeadlineClaims() throws Exception {
        assertThat(execution.snapshot(now.get()).renewalLag()).as("no claims").isZero();
        tasks.ignoreInterrupts();
        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
        now.addAndGet(5 * SECOND);
        tasks.release(key(1, 1));                    // claim 1 ends
        await().until(() -> handle(key(1, 1)).isEnded());
        repository.thenRenewLosing(key(2, 1));
        execution.renewOnce();                          // claim 2 is lost: cancelled, but its task keeps running
        now.addAndGet(20 * SECOND);                  // claim 3's deadline; no supervisor pass has cancelled it

        assertThat(execution.snapshot(now.get()).renewalLag()).isZero();
        assertThat(execution.snapshot(now.get()).inflight()).isEqualTo(2);
    }

    @Test
    void aRoundThatRenewsAClaimRestartsItsLagFromTheRoundsStart() throws Exception {
        claimAndStart(item(1, 1));
        now.addAndGet(5 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(SECOND);   // the round takes a second
            return new RenewalResult(requested, Set.of(), Set.of());
        });

        assertThat(execution.renewOnce()).isTrue();

        assertThat(execution.snapshot(now.get()).renewalLag()).isEqualTo(ofSeconds(1));
        assertThat(execution.snapshot(now.get()).renewalTimes().count()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).renewalTimes().totalNanos()).isEqualTo(SECOND);
        assertThat(execution.snapshot(now.get()).renewalErrors()).isZero();
    }

    @Test
    void aClaimTheRoundDidNotRenewKeepsItsLag() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));
        now.addAndGet(5 * SECOND);
        repository.thenRenewEnded(key(2, 1));   // claim 2's task persisted after the snapshot and is still ending

        execution.renewOnce();

        assertThat(handle(key(1, 1)).leaseWrittenAt()).isEqualTo(now.get());
        assertThat(execution.snapshot(now.get()).renewalLag()).isEqualTo(ofSeconds(5));
    }

    @Test
    void aFailedRoundLeavesTheLagGrowingAndCountsAnError() throws Exception {
        claimAndStart(item(1, 1));
        now.addAndGet(5 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(SECOND);
            throw UNREACHABLE;
        });

        assertThat(execution.renewOnce()).isFalse();

        assertThat(execution.snapshot(now.get()).renewalLag()).isEqualTo(ofSeconds(6));
        assertThat(execution.snapshot(now.get()).renewalErrors()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).renewalTimes().count()).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).renewalTimes().totalNanos()).isEqualTo(SECOND);
    }

    @Test
    void everyTaskEndIsCountedByItsOutcome() throws Exception {
        tasks.endWith(key(2, 1), Outcome.RETRY_SCHEDULED);
        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
        repository.thenRenewLosing(key(3, 1));
        execution.renewOnce();                          // claim 3 is cancelled: its task is interrupted

        tasks.release(key(1, 1));
        tasks.release(key(2, 1));
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));

        assertThat(execution.snapshot(now.get()).outcomes().get(Outcome.COMPLETED)).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).outcomes().get(Outcome.RETRY_SCHEDULED)).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).outcomes().get(Outcome.INTERRUPTED)).isEqualTo(1);
        assertThat(execution.snapshot(now.get()).outcomes().get(Outcome.FAILED)).isZero();
    }

    // ---- start, stop and crash (spec §5.2) ------------------------------------------------------------------

    // ---- Helpers --------------------------------------------------------------------------------------------

    /** Spec §5.2 with held = 0: call it only while no pollOnce is running. */
    private void assertPermitInvariant() {
        long notEnded = handles.stream().filter(handle -> !handle.isEnded()).count();
        assertThat(execution.snapshot(now.get()).availablePermits() + notEnded).as("permits.available + handles not ended")
                .isEqualTo(CONCURRENCY);
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        execution.pollOnce();
        List<ClaimKey> keys = Arrays.stream(items).map(ClaimedItem::key).toList();
        await().until(() -> tasks.started().containsAll(keys));
    }

    private ClaimExecution execution(ClaimExecution.Processor processor, ClaimExecution.TaskThreads threads, LongSupplier clock,
                               ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        return new ClaimExecution(repository, processor, OWNER, SETTINGS, new DbActivity(clock), clock, threads, registry);
    }

    // Production's virtual threads, recording every handle that receives a thread and with it a permit.
    private ClaimExecution.TaskThreads recordingThreads() {
        return (handle, body) -> {
            handles.add(handle);
            return ClaimExecution.VIRTUAL_THREADS.newThread(handle, body);
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
