package hle.org.workqueue.engine;

import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Spec §9.6 metrics: every meter exists under its name and reads the engine's state when scraped. */
@Timeout(30)
class WorkQueueMetricsTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String OWNER = "instance-a";
    private static final DataAccessResourceFailureException UNREACHABLE =
            new DataAccessResourceFailureException("Db2 unreachable");

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final Tasks tasks = new Tasks();
    private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
    private final AtomicBoolean registerLate = new AtomicBoolean();
    private final EngineFixture engine = new EngineFixture(repository, tasks, OWNER,
            EngineSettings.from(ItConfig.properties()), (handle, body) -> {
                handles.add(handle);
                if (registerLate.get()) {
                    now.addAndGet(SECOND);   // past registration-allowance (200ms)
                }
                return ClaimExecution.VIRTUAL_THREADS.newThread(handle, body);
            }, now::get, new ConcurrentHashMap<>());
    private final QueueRunner runner = engine.runner;
    private final ItemProcessor processor = new ItemProcessor(repository, (key, token, payload, timeout) -> {
        now.addAndGet(2 * SECOND);   // every call takes 2s
        return new CallResult("receipt");
    }, OWNER, "it", ItemProcessor.Settings.from(ItConfig.properties()), duration -> { }, now::get);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void bind() {
        new WorkQueueMetrics(runner::snapshot, processor::callStatistics).bindTo(registry);
    }

    @AfterEach
    void endEveryTask() {
        runner.crash();
        tasks.releaseAll();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
    }

    @Test
    void everyMeterOfTheSpecIsRegistered() {
        Set<String> names = registry.getMeters().stream().map(meter -> meter.getId().getName()).collect(toSet());

        assertThat(names).containsExactlyInAnyOrder("workqueue.claims", "workqueue.claim.rows",
                "workqueue.claim.duration", "workqueue.claim.errors", "workqueue.outcomes",
                "workqueue.call.duration", "workqueue.renewal.duration", "workqueue.renewal.errors",
                "workqueue.renewal.lag", "workqueue.claims.lost", "workqueue.db.last_success_age",
                "workqueue.inflight", "workqueue.permits.available", "workqueue.tasks.hung",
                "workqueue.registration.late", "workqueue.invariant.violations", "workqueue.backlog",
                "workqueue.backlog.oldest_pending_age", "workqueue.claims.expired", "workqueue.backlog.sample_age",
                "workqueue.backlog.sample.errors");
        assertThat(tagValues("workqueue.outcomes", "outcome")).containsExactlyInAnyOrder("completed",
                "retry_scheduled", "failed", "fenced", "abandoned", "interrupted", "cancelled");
        assertThat(tagValues("workqueue.call.duration", "result"))
                .containsExactlyInAnyOrder("ok", "error", "timeout", "interrupted");
        assertThat(tagValues("workqueue.backlog", "status")).containsExactlyInAnyOrder("pending", "claimed", "failed");
    }

    @Test
    void claimMetersCountAndTimeEveryClaimOperation() throws Exception {
        repository.thenClaim(() -> {
            now.addAndGet(2 * SECOND);
            return List.of();
        }).thenClaim(() -> {
            now.addAndGet(3 * SECOND);
            throw UNREACHABLE;
        });

        engine.execution.pollOnce();
        engine.execution.pollOnce();
        engine.execution.pollOnce();   // unscripted: empty at once

        FunctionTimer claims = registry.get("workqueue.claim.duration").functionTimer();
        assertThat(claims.count()).isEqualTo(3);
        assertThat(claims.totalTime(SECONDS)).isEqualTo(5);
        assertThat(counter("workqueue.claims")).isEqualTo(2);
        assertThat(counter("workqueue.claim.errors")).isEqualTo(1);
    }

    @Test
    void renewalMetersTimeTheRoundsThatRanAndTheLagGrows() throws Exception {
        claimAndStart(item(1));
        now.addAndGet(4 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(SECOND);
            throw UNREACHABLE;
        }).thenRenew(requested -> {
            now.addAndGet(SECOND);
            throw UNREACHABLE;
        });

        engine.execution.renewOnce();
        engine.execution.renewOnce();   // two failed rounds, so renewal.errors (2) differs from claims (1)

        FunctionTimer rounds = registry.get("workqueue.renewal.duration").functionTimer();
        assertThat(rounds.count()).isEqualTo(2);
        assertThat(rounds.totalTime(SECONDS)).isEqualTo(2);
        assertThat(counter("workqueue.renewal.errors")).isEqualTo(2);
        assertThat(counter("workqueue.claims")).isEqualTo(1);
        assertThat(registry.get("workqueue.renewal.lag").timeGauge().value(SECONDS)).isEqualTo(6);
    }

    @Test
    void outcomesAreCountedByOutcome() throws Exception {
        tasks.endWith(new ClaimKey(2, 1), Outcome.FAILED);
        claimAndStart(item(1), item(2));

        tasks.releaseAll();

        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
        assertThat(counter("workqueue.outcomes", "outcome", "completed")).isEqualTo(1);
        assertThat(counter("workqueue.outcomes", "outcome", "failed")).isEqualTo(1);
        assertThat(counter("workqueue.outcomes", "outcome", "abandoned")).isZero();
    }

    @Test
    void callsAreTimedByHowTheyEnded() {
        repository.thenReturn(PersistResult.DONE);

        processor.process(item(1), () -> false);

        FunctionTimer ok = registry.get("workqueue.call.duration").tag("result", "ok").functionTimer();
        assertThat(ok.count()).isEqualTo(1);
        assertThat(ok.totalTime(SECONDS)).isEqualTo(2);
        assertThat(registry.get("workqueue.call.duration").tag("result", "timeout").functionTimer().count()).isZero();
    }

    @Test
    void capacityGaugesReadTheRunner() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1), item(2), item(3));
        assertThat(gauge("workqueue.inflight")).isEqualTo(3);
        assertThat(gauge("workqueue.permits.available")).isEqualTo(1);
        assertThat(gauge("workqueue.tasks.hung")).isZero();

        now.addAndGet(25 * SECOND);
        engine.execution.superviseOnce();   // all three cancelled at their deadline
        now.addAndGet(2 * SECOND);
        engine.execution.superviseOnce();   // all three hung

        assertThat(gauge("workqueue.tasks.hung")).isEqualTo(3);
    }

    @Test
    void countersReadTheRunner() throws Exception {
        claimAndStart(item(1), item(2));
        repository.thenClaim(item(1));   // the same claim again while it runs: a key collision
        engine.execution.pollOnce();
        repository.thenRenewLosing(new ClaimKey(1, 1), new ClaimKey(2, 1));
        engine.execution.renewOnce();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));   // both interrupted
        registerLate.set(true);
        claimAndStart(item(3), item(4), item(5));
        engine.execution.pollOnce();   // an empty claim, so claims (4) differs from registration.late (3)

        assertThat(counter("workqueue.invariant.violations")).isEqualTo(1);
        assertThat(counter("workqueue.claims.lost")).isEqualTo(2);
        assertThat(counter("workqueue.registration.late")).isEqualTo(3);
        assertThat(counter("workqueue.claims")).isEqualTo(4);
        assertThat(counter("workqueue.claim.rows")).as("2 + 1 + 3 + 0 rows").isEqualTo(6);
    }

    @Test
    void theDbAgeIsTheTimeSinceTheLastDbSuccess() throws Exception {
        now.addAndGet(7 * SECOND);
        assertThat(registry.get("workqueue.db.last_success_age").timeGauge().value(SECONDS)).isEqualTo(7);

        engine.execution.pollOnce();

        assertThat(registry.get("workqueue.db.last_success_age").timeGauge().value(SECONDS)).isZero();
    }

    @Test
    void backlogGaugesHaveNoValueBeforeTheFirstSampleThenReadTheLatest() {
        assertThat(gauge("workqueue.backlog", "status", "pending")).isNaN();
        assertThat(gauge("workqueue.claims.expired")).isNaN();
        assertThat(registry.get("workqueue.backlog.oldest_pending_age").timeGauge().value(SECONDS)).isNaN();

        repository.thenSample(new BacklogSample(12, 4, 1, 2, ofSeconds(30)));
        engine.sampler.sampleOnce();

        assertThat(gauge("workqueue.backlog", "status", "pending")).isEqualTo(12);
        assertThat(gauge("workqueue.backlog", "status", "claimed")).isEqualTo(4);
        assertThat(gauge("workqueue.backlog", "status", "failed")).isEqualTo(1);
        assertThat(gauge("workqueue.claims.expired")).isEqualTo(2);
        assertThat(registry.get("workqueue.backlog.oldest_pending_age").timeGauge().value(SECONDS)).isEqualTo(30);
    }

    @Test
    void theSampleAgeGrowsWhileSamplesFailAlthoughClaimsKeepTheDbFresh() throws Exception {
        now.addAndGet(4 * SECOND);
        assertThat(registry.get("workqueue.backlog.sample_age").timeGauge().value(SECONDS)).isEqualTo(4);
        repository.thenSample(new BacklogSample(0, 0, 0, 0, ofSeconds(0)))
                .thenSampleThrow(UNREACHABLE).thenSampleThrow(UNREACHABLE);
        engine.sampler.sampleOnce();

        now.addAndGet(60 * SECOND);
        engine.sampler.sampleOnce();
        engine.sampler.sampleOnce();
        engine.execution.pollOnce();   // an empty claim returns

        assertThat(registry.get("workqueue.db.last_success_age").timeGauge().value(SECONDS)).isZero();
        assertThat(gauge("workqueue.backlog", "status", "pending")).as("the latest successful sample").isZero();
        assertThat(registry.get("workqueue.backlog.sample_age").timeGauge().value(SECONDS)).isEqualTo(60);
        assertThat(counter("workqueue.backlog.sample.errors")).isEqualTo(2);
    }

    @Test
    void metersKeepTheirSummarySuppliersAliveAfterBinding() throws Exception {
        // Micrometer keeps weak references to FunctionTimer/FunctionCounter sources. A transient supplier must
        // remain reachable through its registered callback after the MeterBinder itself leaves the caller's scope.
        System.gc();
        repository.thenClaim(() -> { now.addAndGet(3 * SECOND); return List.of(); });
        engine.execution.pollOnce();
        repository.thenReturn(PersistResult.DONE);
        processor.process(item(1), () -> false);
        repository.thenSample(new BacklogSample(12, 0, 0, 0, ofSeconds(30)));
        engine.sampler.sampleOnce();
        assertThat(counter("workqueue.claims")).isEqualTo(1);
        assertThat(registry.get("workqueue.claim.duration").functionTimer().count()).isEqualTo(1);
        assertThat(registry.get("workqueue.call.duration").tag("result", "ok").functionTimer().count()).isEqualTo(1);
        assertThat(gauge("workqueue.backlog", "status", "pending")).isEqualTo(12);
    }

    @Test
    void metersBoundOnceObserveLaterClaimsCallsAndSamples() throws Exception {
        FunctionTimer claims = registry.get("workqueue.claim.duration").functionTimer();
        FunctionTimer calls = registry.get("workqueue.call.duration").tag("result", "ok").functionTimer();
        assertThat(claims.count()).isZero();
        assertThat(calls.count()).isZero();
        for (int pass = 1; pass <= 2; pass++) {
            repository.thenClaim(() -> { now.addAndGet(3 * SECOND); return List.of(); });
            engine.execution.pollOnce();
            repository.thenReturn(PersistResult.DONE);
            processor.process(item(pass), () -> false);
            repository.thenSample(new BacklogSample(12L * pass, 0, 0, 0, ofSeconds(30)));
            engine.sampler.sampleOnce();
            assertThat(claims.count()).isEqualTo(pass);
            assertThat(claims.totalTime(SECONDS)).isEqualTo(3 * pass);
            assertThat(counter("workqueue.claims")).isEqualTo(pass);
            assertThat(calls.count()).isEqualTo(pass);
            assertThat(calls.totalTime(SECONDS)).isEqualTo(2 * pass);
            assertThat(gauge("workqueue.backlog", "status", "pending")).isEqualTo(12 * pass);
        }
    }

    @Test
    void aClaimAlreadyEndedInTheDbIsNotCountedLost() throws Exception {
        claimAndStart(item(1));
        repository.thenRenewEnded(new ClaimKey(1, 1));
        engine.execution.renewOnce();
        assertThat(counter("workqueue.claims.lost")).isZero();
        assertThat(gauge("workqueue.inflight")).isEqualTo(1);
        assertThat(handles).noneMatch(ClaimHandle::isCancelled);
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        engine.execution.pollOnce();
        List<ClaimKey> keys = Arrays.stream(items).map(ClaimedItem::key).toList();
        await().until(() -> tasks.started().containsAll(keys));
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).functionCounter().count();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private Set<String> tagValues(String name, String tag) {
        return registry.find(name).meters().stream().map(Meter::getId).map(id -> id.getTag(tag)).collect(toSet());
    }

    private static ClaimedItem item(long id) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, 1);
    }
}
