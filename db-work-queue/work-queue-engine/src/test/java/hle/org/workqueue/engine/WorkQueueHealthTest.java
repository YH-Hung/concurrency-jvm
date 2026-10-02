package hle.org.workqueue.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Spec §11.1 {@code WorkQueueHealthTest}, on a fake clock and the production defaults: lease 100s, renew-interval 15s,
 * renew-retry-delay 1s, registration-allowance 1s, W 18s, idle-poll-interval 1s, db-staleness-limit 90s,
 * max-processing-time 120s, hung-grace 30s, hung-task-limit 4.
 */
@Timeout(30)
class WorkQueueHealthTest {

    private static final long SECOND = 1_000_000_000L;
    private static final WorkQueueProperties DEFAULTS = new WorkQueueProperties();
    private static final DataAccessResourceFailureException UNREACHABLE =
            new DataAccessResourceFailureException("Db2 unreachable");

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final Tasks tasks = new Tasks();
    private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
    private final QueueRunner runner = new QueueRunner(repository, tasks, "instance-a",
            QueueRunner.Settings.from(DEFAULTS), (handle, body) -> {
                handles.add(handle);
                return QueueRunner.VIRTUAL_THREADS.newThread(handle, body);
            }, now::get, new ConcurrentHashMap<>());
    private final WorkQueueHealth health = new WorkQueueHealth(runner, WorkQueueHealth.Settings.from(DEFAULTS));

    @AfterEach
    void endEveryTask() {
        runner.crash();
        tasks.releaseAll();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
    }

    // ---- Readiness ------------------------------------------------------------------------------------------

    @Test
    void anIdleInstanceWithNoClaimsStaysReadyForTenLeases() throws Exception {
        long end = now.get() + 10 * 100 * SECOND;
        while (end - now.get() > 0) {
            Duration pause = runner.pollOnce();   // an empty claim
            runner.renewOnce();                   // skipped: nothing to renew
            now.addAndGet(pause.toNanos());

            assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
            assertThat(runner.renewalLag()).isZero();
        }
    }

    @Test
    void oneFailedRenewalRoundKeepsReadinessUpEvenInTheWorstCaseOfB2() throws Exception {
        // Spec §5.3: the claim operation takes W, registration G, the first round that includes the claim starts
        // max(I, W) later and fails after W, and its retry starts d later and writes after W: 74s after the claim
        // operation started, under the 100s lease.
        repository.thenClaim(() -> {
            now.addAndGet(18 * SECOND);
            return List.of(item(1));
        });
        runner.pollOnce();
        await().until(() -> tasks.started().size() == 1);
        now.addAndGet(SECOND + 18 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(18 * SECOND);
            throw UNREACHABLE;
        });
        assertThat(runner.renewOnce()).isFalse();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
        now.addAndGet(SECOND);
        AtomicReference<Health> beforeTheRetryWrites = new AtomicReference<>();
        repository.thenRenew(requested -> {
            now.addAndGet(18 * SECOND);
            beforeTheRetryWrites.set(health.readiness());
            return new RenewalResult(requested, Set.of(), Set.of());
        });

        assertThat(runner.renewOnce()).isTrue();

        assertThat(beforeTheRetryWrites.get().getStatus()).isEqualTo(Status.UP);
        assertThat(beforeTheRetryWrites.get().getDetails()).containsEntry("renewalLag", "74s");
        assertThat(health.readiness().getDetails()).containsEntry("renewalLag", "18s");
    }

    @Test
    void aClaimUnrenewedForMoreThanALeaseTurnsReadinessDownUntilARoundRenewsIt() throws Exception {
        claimAndStart(item(1));
        now.addAndGet(50 * SECOND);
        runner.pollOnce();                            // claims keep Db2 fresh while renewal fails
        repository.thenRenewThrow(UNREACHABLE);
        runner.renewOnce();
        now.addAndGet(50 * SECOND);
        runner.pollOnce();
        assertThat(health.readiness().getStatus()).as("exactly one lease").isEqualTo(Status.UP);

        now.addAndGet(1);

        Health unready = health.readiness();
        assertThat(unready.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unready.getDetails()).containsEntry("renewalLag", "100s").containsEntry("dbLastSuccessAge", "0s");
        runner.renewOnce();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aStaleDbTurnsReadinessDownWithNoClaimsUntilAClaimReturns() throws Exception {
        repository.thenClaimThrow(UNREACHABLE).thenClaimThrow(UNREACHABLE);
        now.addAndGet(45 * SECOND);
        runner.pollOnce();
        now.addAndGet(45 * SECOND);
        runner.pollOnce();
        assertThat(health.readiness().getStatus()).as("exactly db-staleness-limit").isEqualTo(Status.UP);

        now.addAndGet(1);

        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        runner.pollOnce();                            // an empty claim
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aSweepOrABacklogSampleAlsoKeepsTheDbFresh() {
        now.addAndGet(91 * SECOND);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        runner.sweepOnce();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);

        now.addAndGet(91 * SECOND);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        runner.sampleOnce();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void readinessIsDownFromTheStartOfStop() throws Exception {
        repository.thenClaim(item(1));
        runner.start();
        await().until(() -> tasks.started().size() == 1);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
        FutureTask<Void> stop = new FutureTask<>(runner::stop, null);
        Thread.ofVirtual().start(stop);
        await().until(runner::isStopping);

        Health draining = health.readiness();

        assertThat(draining.getStatus()).isEqualTo(Status.DOWN);
        assertThat(draining.getDetails()).containsEntry("stopping", true);
        tasks.releaseAll();
        stop.get(10, SECONDS);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.liveness().getStatus()).as("the loops stop ended are not dead").isEqualTo(Status.UP);
    }

    // ---- Liveness -------------------------------------------------------------------------------------------

    @Test
    void aHealthyInstanceIsLive() {
        Health live = health.liveness();

        assertThat(live.getStatus()).isEqualTo(Status.UP);
        assertThat(live.getDetails()).containsEntry("hungTasks", 0).containsEntry("invariantViolations", 0L)
                .containsEntry("deadLoops", List.of());
    }

    @Test
    void anInvariantViolationTurnsLivenessDownForGood() throws Exception {
        claimAndStart(item(1));
        repository.thenClaim(item(1));   // the same claim again while it runs: a key collision

        runner.pollOnce();

        assertThat(health.liveness().getStatus()).isEqualTo(Status.DOWN);
        tasks.releaseAll();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
        assertThat(health.liveness().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.liveness().getDetails()).containsEntry("invariantViolations", 1L);
    }

    @Test
    void reachingTheHungTaskLimitTurnsLivenessDownUntilAHungTaskEnds() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1), item(2), item(3), item(4));
        now.addAndGet(120 * SECOND);
        runner.superviseOnce();          // all four cancelled at their deadline
        now.addAndGet(30 * SECOND);

        runner.superviseOnce();          // all four hung: the limit

        assertThat(health.liveness().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.liveness().getDetails()).containsEntry("hungTasks", 4);
        tasks.release(new ClaimKey(1, 1));
        await().until(() -> runner.hungTasks() == 3);
        assertThat(health.liveness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aDeadLoopTurnsLivenessDown() {
        repository.thenClaim(() -> {
            throw new StackOverflowError();
        });

        runner.start();

        await().until(() -> health.liveness().getStatus().equals(Status.DOWN));
        assertThat(health.liveness().getDetails()).containsEntry("deadLoops", List.of("poll"));
    }

    @Test
    void aDeadSweeperOrBacklogSamplerTurnsLivenessDownWhileTheInstanceStaysReady() {
        repository.thenSweep(() -> {
            throw new StackOverflowError();
        }).thenSample(() -> {
            throw new StackOverflowError();
        });

        runner.start();

        await().until(() -> runner.deadLoops().size() == 2);
        assertThat(health.liveness().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.liveness().getDetails()).containsEntry("deadLoops", List.of("sweeper", "backlog-sampler"));
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
    }

    // ---- Settings -------------------------------------------------------------------------------------------

    @Test
    void settingsComeFromTheProperties() {
        assertThat(WorkQueueHealth.Settings.from(DEFAULTS))
                .isEqualTo(new WorkQueueHealth.Settings(ofSeconds(100), ofSeconds(90)));
    }

    @Test
    void settingsRejectANonPositiveDuration() {
        assertThatThrownBy(() -> new WorkQueueHealth.Settings(Duration.ZERO, ofSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        assertThatThrownBy(() -> new WorkQueueHealth.Settings(ofSeconds(100), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dbStalenessLimit");
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        runner.pollOnce();
        List<ClaimKey> keys = Arrays.stream(items).map(ClaimedItem::key).toList();
        await().until(() -> tasks.started().containsAll(keys));
    }

    private static ClaimedItem item(long id) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, 1);
    }
}
