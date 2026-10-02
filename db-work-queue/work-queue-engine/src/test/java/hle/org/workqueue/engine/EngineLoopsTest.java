package hle.org.workqueue.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import hle.org.workqueue.engine.ClaimHandle.CancelReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Spec §11.1 {@code EngineLoopsTest}: every scenario ends with the permit invariant (see endEveryTask). */
@Timeout(30)   // a deadlock fails the test instead of hanging the build
class EngineLoopsTest {

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
    private final Logger runnerLog = (Logger) LoggerFactory.getLogger(EngineLoops.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private EngineFixture engine = engine(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>());

    @BeforeEach
    void captureLogs() {
        logged.start();
        runnerLog.addAppender(logged);
    }

    @AfterEach
    void endEveryTask() {
        runnerLog.detachAppender(logged);
        engine.loops.abort();
        tasks.releaseAll();
        await().untilAsserted(() -> assertThat(handles).allMatch(ClaimHandle::isEnded));
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(engine.execution.snapshot(System.nanoTime()).availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void theSweeperAndTheBacklogSamplerRunEveryIntervalUntilStop() {
        engine = liveEngine();

        engine.loops.start();

        await().atMost(ofSeconds(5)).until(() -> repository.sweepSizes().size() >= 2 && repository.samples() >= 2);
        engine.loops.stopPolling(ofSeconds(1));
        engine.loops.stopBackground(ofSeconds(1));
        assertThat(loops).extracting(Thread::getName).containsExactly("workqueue-poll", "workqueue-renewal",
                "workqueue-supervisor", "workqueue-sweeper", "workqueue-backlog-sampler");
        assertThat(loops).noneMatch(Thread::isAlive);
    }

    @Test
    void anErrorEndsItsLoopAndIsLoggedByClassNameOnly() {
        engine = engine(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
        repository.thenClaim(() -> {
            throw new StackOverflowError("row of order-7:charge");
        });

        engine.loops.start();

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
        await().until(() -> engine.loops.deadLoops().equals(List.of("poll")));
    }

    @Test
    void aLoopThatDiesIsReportedDeadUntilTheRunnerStops() {
        Set<String> dying = Set.of("workqueue-poll", "workqueue-renewal", "workqueue-supervisor");
        engine = new EngineFixture(repository, tasks, OWNER, SETTINGS, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>() {
                    @Override
                    public Collection<ClaimHandle> values() {   // each of the three loops reads the registry
                        if (dying.contains(Thread.currentThread().getName())) {
                            throw new StackOverflowError();
                        }
                        return super.values();
                    }
                }, recordingLoops());

        engine.loops.start();

        await().until(() -> engine.loops.deadLoops().equals(List.of("poll", "renewal", "supervisor")));
        engine.loops.stopPolling(ofSeconds(1));
        engine.loops.stopBackground(ofSeconds(1));
        assertThat(engine.loops.deadLoops()).isEmpty();
    }

    @Test
    void aSweeperOrBacklogSamplerLoopThatDiesIsLoggedByClassNameOnlyAndReportedDeadUntilTheRunnerStops() {
        repository.thenSweep(() -> {
            throw new StackOverflowError("row of order-7:charge");
        }).thenSample(() -> {
            throw new StackOverflowError("row of order-7:charge");
        });
        engine = liveEngine();

        engine.loops.start();

        await().until(() -> engine.loops.deadLoops().equals(List.of("sweeper", "backlog-sampler")));
        synchronized (logged) {
            assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactlyInAnyOrder(
                    "Sweeper loop of owner instance-a died: java.lang.StackOverflowError",
                    "Backlog sampler loop of owner instance-a died: java.lang.StackOverflowError");
        }
        engine.loops.stopPolling(ofSeconds(1));
        engine.loops.stopBackground(ofSeconds(1));
        assertThat(engine.loops.deadLoops()).isEmpty();
    }

    @Test
    void aFailureStartingTheFirstLoopCancelsClaimsAndConsumesTheStart() throws Exception {
        engine = new EngineFixture(repository, tasks, OWNER, SETTINGS, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), (name, loop) -> { throw new OutOfMemoryError("no loop"); });
        repository.thenClaim(item(1, 1));
        engine.execution.pollOnce();
        await().until(() -> tasks.started().size() == 1);

        assertThatThrownBy(engine.loops::start).isInstanceOf(OutOfMemoryError.class);

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.SHUTDOWN);
        assertThat(engine.loops.deadLoops()).isEmpty();
        assertThatThrownBy(engine.loops::start).isInstanceOf(IllegalStateException.class);
        assertPermitInvariant();
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
        engine = new EngineFixture(repository, tasks, OWNER, SETTINGS, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), (name, loop) -> {
                    if (name.equals("workqueue-renewal")) {
                        awaitIgnoringInterrupts(claiming);   // the poll loop is inside its claim
                        throw new OutOfMemoryError("unable to create thread");
                    }
                    return recordingLoops().start(name, loop);
                });

        assertThatThrownBy(engine.loops::start).isInstanceOf(OutOfMemoryError.class);
        claimReturns.countDown();

        await().until(() -> handles.stream().anyMatch(handle -> handle.key().equals(key(1, 1)) && handle.isEnded()));
        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.SHUTDOWN);
        assertThat(tasks.started()).as("the in-flight claim's task never ran").isEmpty();
        assertThat(loops).extracting(Thread::getName).containsExactly("workqueue-poll");
        assertThat(loops.getFirst().join(ofSeconds(10))).as("the poll loop ended").isTrue();
        assertThat(engine.loops.deadLoops()).isEmpty();
        assertThatThrownBy(engine.loops::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLoopThreadThatFailsToStartLastEndsEveryLoopStartedBeforeIt() {
        CountDownLatch sweeping = new CountDownLatch(1);
        repository.thenSweep(() -> {
            sweeping.countDown();
            return 0;
        });
        engine = new EngineFixture(repository, tasks, OWNER, MINUTE_PASSES, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), (name, loop) -> {
                    if (name.equals("workqueue-backlog-sampler")) {
                        awaitIgnoringInterrupts(sweeping);   // the sweeper is in its first pass: only an interrupt ends it
                        throw new OutOfMemoryError("unable to create thread");
                    }
                    return recordingLoops().start(name, loop);
                });

        assertThatThrownBy(engine.loops::start).isInstanceOf(OutOfMemoryError.class);

        assertThat(loops).extracting(Thread::getName)
                .containsExactly("workqueue-poll", "workqueue-renewal", "workqueue-supervisor", "workqueue-sweeper");
        await().atMost(ofSeconds(5)).until(() -> loops.stream().noneMatch(Thread::isAlive));
        assertThat(engine.loops.deadLoops()).isEmpty();
    }

    // ---- Helpers --------------------------------------------------------------------------------------------

    /** Spec §5.2 with held = 0: call it only while no pollOnce is running. */
    private void assertPermitInvariant() {
        long notEnded = handles.stream().filter(handle -> !handle.isEnded()).count();
        assertThat(engine.execution.snapshot(System.nanoTime()).availablePermits() + notEnded).as("permits.available + handles not ended")
                .isEqualTo(CONCURRENCY);
    }

    private EngineFixture engine(ClaimExecution.Processor processor, ClaimExecution.TaskThreads threads, LongSupplier clock,
                               ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        return new EngineFixture(repository, processor, OWNER, SETTINGS, threads, clock, registry);
    }

    // A runner on the real clock whose loop threads are recorded.
    private EngineFixture liveEngine() {
        return liveEngine(SETTINGS);
    }

    private EngineFixture liveEngine(EngineSettings settings) {
        return new EngineFixture(repository, tasks, OWNER, settings, recordingThreads(), System::nanoTime,
                new ConcurrentHashMap<>(), recordingLoops());
    }

    private static EngineSettings minutePasses() {
        WorkQueueProperties properties = ItConfig.properties();
        properties.setSweepInterval(ofMinutes(1));
        properties.setBacklogSampleInterval(ofMinutes(1));
        return EngineSettings.from(properties);
    }

    // Production's loop threads, recorded in the order they start.
    private EngineLoops.LoopThreads recordingLoops() {
        return (name, loop) -> {
            Thread thread = EngineLoops.VIRTUAL_LOOP_THREADS.start(name, loop);
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
