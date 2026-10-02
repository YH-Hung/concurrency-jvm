package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class EngineSnapshotTest {
    @Test
    void retainedCollectionsAreCopiesAndRejectMutation() {
        Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
        outcomes.put(Outcome.COMPLETED, 1L);
        EngineSnapshot.Execution execution = new EngineSnapshot.Execution(4, 0, 0, false, Duration.ZERO,
                0, 0, 0, 0, 0, 0, 0, new OperationStats.Totals(0, 0), new OperationStats.Totals(0, 0), outcomes);
        List<String> dead = new ArrayList<>(List.of("poll"));
        EngineSnapshot.Runtime runtime = new EngineSnapshot.Runtime(true, false, dead);
        outcomes.put(Outcome.COMPLETED, 2L);
        dead.clear();

        assertThat(execution.outcomes()).containsEntry(Outcome.COMPLETED, 1L);
        assertThat(runtime.deadLoops()).containsExactly("poll");
        assertThatThrownBy(() -> execution.outcomes().put(Outcome.COMPLETED, 3L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> runtime.deadLoops().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void timingTotalsStayUnchangedAfterAnotherOperation() {
        OperationStats stats = new OperationStats();
        stats.record(10);
        OperationStats.Totals before = stats.snapshot();
        stats.record(20);

        assertThat(before).isEqualTo(new OperationStats.Totals(1, 10));
        assertThat(stats.snapshot()).isEqualTo(new OperationStats.Totals(2, 30));
    }

    @Test
    void retainedEngineAndCallSnapshotsStayUnchangedWhileFreshOnesAdvance() throws Exception {
        ScriptedRepository repository = new ScriptedRepository();
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5_000_000_000L);
        Tasks tasks = new Tasks();
        EngineFixture engine = fixture(repository, tasks, now, ItConfig.properties(), new ArrayList<>());
        ItemProcessor processor = new ItemProcessor(repository,
                (key, token, payload, timeout) -> new CallResult("receipt"), "instance-a", "it",
                ItemProcessor.Settings.from(ItConfig.properties()), duration -> {}, now::get);
        EngineSnapshot before = engine.runner.snapshot();
        Map<EngineSnapshot.CallStatus, OperationStats.Totals> callsBefore = processor.callStatistics();
        try {
            repository.thenClaim(new ClaimedItem(1, "op-1", "payload-1", 1));
            engine.execution.pollOnce();
            await().until(() -> tasks.started().size() == 1);
            tasks.releaseAll();
            await().until(() -> engine.runner.snapshot().execution().outcomes().get(Outcome.COMPLETED) == 1);
            repository.thenSample(new BacklogSample(12, 4, 1, 2, Duration.ofSeconds(30)));
            engine.sampler.sampleOnce();
            repository.thenReturn(PersistResult.DONE);
            processor.process(new ClaimedItem(2, "op-2", "payload-2", 1), () -> false);

            assertThat(before.execution().claims()).isZero();
            assertThat(before.execution().claimTimes().count()).isZero();
            assertThat(before.execution().outcomes().get(Outcome.COMPLETED)).isZero();
            assertThat(before.backlog().latest()).isNull();
            assertThat(callsBefore.get(EngineSnapshot.CallStatus.OK).count()).isZero();
            assertThat(engine.runner.snapshot().execution().claims()).isEqualTo(1);
            assertThat(engine.runner.snapshot().execution().claimTimes().count()).isEqualTo(1);
            assertThat(engine.runner.snapshot().backlog().latest().pending()).isEqualTo(12);
            assertThat(processor.callStatistics().get(EngineSnapshot.CallStatus.OK).count()).isEqualTo(1);
            assertThatThrownBy(() -> processor.callStatistics().clear()).isInstanceOf(UnsupportedOperationException.class);
        } finally {
            engine.runner.crash();
            tasks.releaseAll();
        }
    }

    @Test
    void observationReturnsWhileARepositoryOperationIsBlocked() throws Exception {
        ScriptedRepository repository = new ScriptedRepository();
        AtomicLong now = new AtomicLong(0);
        EngineFixture engine = fixture(repository, new Tasks(), now, ItConfig.properties(), new ArrayList<>());
        CountDownLatch claiming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.thenClaim(() -> {
            claiming.countDown();
            try {
                if (!release.await(10, SECONDS)) throw new AssertionError("claim not released");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return List.of();
        });
        FutureTask<Duration> poll = new FutureTask<>(engine.execution::pollOnce);
        Thread.ofVirtual().start(poll);
        assertThat(claiming.await(5, SECONDS)).isTrue();
        try {
            FutureTask<EngineSnapshot> observe = new FutureTask<>(engine.runner::snapshot);
            Thread.ofVirtual().start(observe);
            EngineSnapshot snapshot = observe.get(2, SECONDS);
            assertThat(snapshot.execution().claims()).isZero();
            assertThat(snapshot.execution().availablePermits()).isZero();
        } finally {
            release.countDown();
            poll.get(5, SECONDS);
            engine.runner.crash();
        }
    }

    @Test
    void oneClockReadingDatesTheSnapshotAcrossWraparound() throws Exception {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 1_000_000_000L);
        AtomicInteger readings = new AtomicInteger();
        EngineFixture engine = new EngineFixture(new ScriptedRepository(), new Tasks(), "instance-a",
                EngineSettings.from(ItConfig.properties()), ClaimExecution.VIRTUAL_THREADS,
                () -> { readings.incrementAndGet(); return now.get(); }, new ConcurrentHashMap<>());
        now.addAndGet(2_000_000_000L);
        readings.set(0);

        EngineSnapshot before = engine.runner.snapshot();

        assertThat(readings).hasValue(1);
        assertThat(before.dbLastSuccessAge()).isEqualTo(Duration.ofSeconds(2));
        assertThat(before.backlog().sampleAge()).isEqualTo(Duration.ofSeconds(2));
        assertThat(before.execution().renewalLag()).isZero();
        engine.execution.pollOnce();
        EngineSnapshot after = engine.runner.snapshot();
        assertThat(after.dbLastSuccessAge()).isZero();
        assertThat(after.backlog().sampleAge()).isEqualTo(Duration.ofSeconds(2));
        engine.runner.crash();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void theConfiguredHungThresholdAgreesWithPollAdmission(int limit) throws Exception {
        WorkQueueProperties properties = ItConfig.properties();
        properties.setHungTaskLimit(limit);
        properties.setConcurrency(limit + 1);
        ScriptedRepository repository = new ScriptedRepository();
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5_000_000_000L);
        Tasks tasks = new Tasks();
        tasks.ignoreInterrupts();
        List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
        EngineFixture engine = fixture(repository, tasks, now, properties, handles);
        repository.thenClaim(java.util.stream.LongStream.rangeClosed(1, limit)
                .mapToObj(id -> new ClaimedItem(id, "op-" + id, "payload-" + id, 1)).toArray(ClaimedItem[]::new));
        try {
            engine.execution.pollOnce();
            await().until(() -> tasks.started().size() == limit);
            for (int count = 1; count <= limit; count++) {
                handles.get(count - 1).cancel(ClaimHandle.CancelReason.DEADLINE, now.get());
                now.addAndGet(properties.getHungGrace().toNanos());
                engine.execution.superviseOnce();
                EngineSnapshot.Execution snapshot = engine.runner.snapshot().execution();
                assertThat(snapshot.hungTasks()).isEqualTo(count);
                assertThat(snapshot.hungTaskLimitReached()).isEqualTo(count >= limit);
                int claimsBefore = repository.claimSizes().size();
                Duration pause = engine.execution.pollOnce();
                if (count >= limit) {
                    assertThat(pause).isEqualTo(properties.getSupervisorInterval());
                    assertThat(repository.claimSizes()).hasSize(claimsBefore);
                } else {
                    assertThat(repository.claimSizes()).hasSize(claimsBefore + 1);
                }
            }
            tasks.release(handles.getFirst().key());
            await().until(() -> engine.runner.snapshot().execution().hungTasks() == limit - 1);
            assertThat(engine.runner.snapshot().execution().hungTaskLimitReached()).isFalse();
            int claimsBefore = repository.claimSizes().size();
            engine.execution.pollOnce();
            assertThat(repository.claimSizes()).hasSize(claimsBefore + 1);
        } finally {
            engine.runner.crash();
            tasks.releaseAll();
            await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
            assertThat(engine.runner.snapshot().execution().availablePermits()).isEqualTo(limit + 1);
        }
    }

    @Test
    void readModelsContainOnlySummaryTypes() {
        Set<Class<?>> allowed = Set.of(int.class, long.class, boolean.class, Duration.class, Map.class, List.class,
                EngineSnapshot.Execution.class, EngineSnapshot.Backlog.class, EngineSnapshot.Runtime.class,
                OperationStats.Totals.class, BacklogSample.class);
        for (Class<?> type : List.of(EngineSnapshot.class, EngineSnapshot.Execution.class, EngineSnapshot.Backlog.class,
                EngineSnapshot.Runtime.class, OperationStats.Totals.class, BacklogSample.class)) {
            assertThat(type.isRecord()).isTrue();
            for (var component : type.getRecordComponents()) {
                assertThat(allowed).as(type.getSimpleName() + "." + component.getName()).contains(component.getType());
            }
        }
    }

    private static EngineFixture fixture(ScriptedRepository repository, Tasks tasks, AtomicLong now,
                                         WorkQueueProperties properties, List<ClaimHandle> handles) {
        return new EngineFixture(repository, tasks, "instance-a", EngineSettings.from(properties), (handle, body) -> {
            handles.add(handle);
            return ClaimExecution.VIRTUAL_THREADS.newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
    }
}
