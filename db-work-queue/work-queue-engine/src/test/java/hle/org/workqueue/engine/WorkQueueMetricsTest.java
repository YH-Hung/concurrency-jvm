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
    private final QueueRunner runner = new QueueRunner(repository, tasks, OWNER,
            QueueRunner.Settings.from(ItConfig.properties()), (handle, body) -> {
                handles.add(handle);
                if (registerLate.get()) {
                    now.addAndGet(SECOND);   // past registration-allowance (200ms)
                }
                return QueueRunner.VIRTUAL_THREADS.newThread(handle, body);
            }, now::get, new ConcurrentHashMap<>());
    private final ItemProcessor processor = new ItemProcessor(repository, (key, token, payload, timeout) -> {
        now.addAndGet(2 * SECOND);   // every call takes 2s
        return new CallResult("receipt");
    }, OWNER, "it", ItemProcessor.Settings.from(ItConfig.properties()), duration -> { }, now::get);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void bind() {
        new WorkQueueMetrics(runner, processor).bindTo(registry);
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

        assertThat(names).containsExactlyInAnyOrder("workqueue.claims", "workqueue.claim.duration",
                "workqueue.claim.errors", "workqueue.outcomes", "workqueue.call.duration",
                "workqueue.renewal.duration", "workqueue.renewal.errors", "workqueue.renewal.lag",
                "workqueue.claims.lost", "workqueue.db.last_success_age", "workqueue.inflight",
                "workqueue.permits.available", "workqueue.tasks.hung", "workqueue.registration.late",
                "workqueue.invariant.violations", "workqueue.backlog", "workqueue.backlog.oldest_pending_age",
                "workqueue.claims.expired");
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

        runner.pollOnce();
        runner.pollOnce();
        runner.pollOnce();   // unscripted: empty at once

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

        runner.renewOnce();
        runner.renewOnce();   // two failed rounds, so renewal.errors (2) differs from claims (1)

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
        runner.superviseOnce();   // all three cancelled at their deadline
        now.addAndGet(2 * SECOND);
        runner.superviseOnce();   // all three hung

        assertThat(gauge("workqueue.tasks.hung")).isEqualTo(3);
    }

    @Test
    void countersReadTheRunner() throws Exception {
        claimAndStart(item(1), item(2));
        repository.thenClaim(item(1));   // the same claim again while it runs: a key collision
        runner.pollOnce();
        repository.thenRenewLosing(new ClaimKey(1, 1), new ClaimKey(2, 1));
        runner.renewOnce();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));   // both interrupted
        registerLate.set(true);
        claimAndStart(item(3), item(4), item(5));
        runner.pollOnce();   // an empty claim, so claims (4) differs from registration.late (3)

        assertThat(counter("workqueue.invariant.violations")).isEqualTo(1);
        assertThat(counter("workqueue.claims.lost")).isEqualTo(2);
        assertThat(counter("workqueue.registration.late")).isEqualTo(3);
        assertThat(counter("workqueue.claims")).isEqualTo(4);
    }

    @Test
    void theDbAgeIsTheTimeSinceTheLastDbSuccess() throws Exception {
        now.addAndGet(7 * SECOND);
        assertThat(registry.get("workqueue.db.last_success_age").timeGauge().value(SECONDS)).isEqualTo(7);

        runner.pollOnce();

        assertThat(registry.get("workqueue.db.last_success_age").timeGauge().value(SECONDS)).isZero();
    }

    @Test
    void backlogGaugesHaveNoValueBeforeTheFirstSampleThenReadTheLatest() {
        assertThat(gauge("workqueue.backlog", "status", "pending")).isNaN();
        assertThat(gauge("workqueue.claims.expired")).isNaN();
        assertThat(registry.get("workqueue.backlog.oldest_pending_age").timeGauge().value(SECONDS)).isNaN();

        repository.thenSample(new BacklogSample(12, 4, 1, 2, ofSeconds(30)));
        runner.sampleOnce();

        assertThat(gauge("workqueue.backlog", "status", "pending")).isEqualTo(12);
        assertThat(gauge("workqueue.backlog", "status", "claimed")).isEqualTo(4);
        assertThat(gauge("workqueue.backlog", "status", "failed")).isEqualTo(1);
        assertThat(gauge("workqueue.claims.expired")).isEqualTo(2);
        assertThat(registry.get("workqueue.backlog.oldest_pending_age").timeGauge().value(SECONDS)).isEqualTo(30);
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        runner.pollOnce();
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
