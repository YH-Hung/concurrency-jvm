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
    private static final EngineSettings SETTINGS = EngineSettings.from(ItConfig.properties());
    private static final int CONCURRENCY = SETTINGS.concurrency();
    /** The IT column with a minute between sweeps and between samples: those loops end soon only if interrupted. */
    private static final EngineSettings MINUTE_PASSES = minutePasses();
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

    private QueueRunner runner(ClaimExecution.Processor processor, ClaimExecution.TaskThreads threads, LongSupplier clock,
                               ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        return new QueueRunner(repository, processor, OWNER, SETTINGS, threads, clock, registry);
    }

    // A runner on the real clock whose loop threads are recorded.
    private QueueRunner liveRunner() {
        return liveRunner(SETTINGS);
    }

    private QueueRunner liveRunner(EngineSettings settings) {
        return new QueueRunner(repository, tasks, OWNER, settings, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), recordingLoops());
    }

    private static EngineSettings minutePasses() {
        WorkQueueProperties properties = ItConfig.properties();
        properties.setSweepInterval(ofMinutes(1));
        properties.setBacklogSampleInterval(ofMinutes(1));
        return EngineSettings.from(properties);
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
