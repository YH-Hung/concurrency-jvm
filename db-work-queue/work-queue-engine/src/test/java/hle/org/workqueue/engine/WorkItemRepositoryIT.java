package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static hle.org.workqueue.engine.PersistResult.*;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

/** IT 2 of spec §11.2: every repository operation against real Db2. */
class WorkItemRepositoryIT {

    private static HikariDataSource worker;
    private static WorkItemRepository repository;

    private final WorkItems rows = new WorkItems(Db2TestSupport.adminDataSource());

    @BeforeAll
    static void startPool() {
        worker = Db2TestSupport.workerDataSource("wq-it-repository", 8);
        repository = Db2TestSupport.repository(worker);
    }

    @AfterAll
    static void closePool() {
        worker.close();
    }

    @BeforeEach
    void clean() {
        rows.deleteAll();
    }

    @Test
    void readsTheNamespace() {
        assertThat(repository.readNamespace()).isEqualTo(Db2TestSupport.NAMESPACE);
    }

    @Test
    void claimSetsTheClaimFields() {
        long id = rows.insert("op-1", "payload-1");

        List<ClaimedItem> claimed = repository.claim("owner-a", 5);

        assertThat(claimed).containsExactly(new ClaimedItem(id, "op-1", "payload-1", 1));
        WorkItems.Row row = rows.row(id);
        assertThat(row.status()).isEqualTo("CLAIMED");
        assertThat(row.owner()).isEqualTo("owner-a");
        assertThat(row.claimToken()).isEqualTo(1);
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(rows.availableIn(id)).isBetween(Duration.ofSeconds(25), Duration.ofSeconds(30));
    }

    @Test
    void claimSkipsRowsThatAreNotClaimable() {
        long future = rows.insert();
        rows.setAvailableAt(future, 60);
        long done = rows.insert();
        rows.setStatus(done, "DONE");
        long failed = rows.insert();
        rows.setStatus(failed, "FAILED");
        long liveClaim = rows.insert();
        rows.setClaim(liveClaim, "owner-b", 1, 1, 60);
        long exhaustedClaim = rows.insert();
        rows.setClaim(exhaustedClaim, "owner-dead", 5, 5, -1);
        long exhaustedPending = rows.insert();
        rows.setAttempts(exhaustedPending, 5);

        assertThat(repository.claim("owner-a", 10)).isEmpty();
    }

    @Test
    void claimTakesExpiredClaimsFirstThenPendingOldestFirst() {
        long newest = rows.insert();
        rows.setAvailableAt(newest, -10);
        long oldest = rows.insert();
        rows.setAvailableAt(oldest, -30);
        long middle = rows.insert();
        rows.setAvailableAt(middle, -20);
        long expired = rows.insert();
        rows.setClaim(expired, "owner-dead", 1, 1, -1);   // expired most recently, still served first

        List<ClaimedItem> claimed = repository.claim("owner-a", 3);

        assertThat(claimed).hasSize(3);
        assertThat(claimed.getFirst().id()).isEqualTo(expired);
        assertThat(claimed.getFirst().claimToken()).isEqualTo(2);
        assertThat(claimed.subList(1, 3)).extracting(ClaimedItem::id).containsExactlyInAnyOrder(oldest, middle);
        assertThat(rows.row(newest).status()).isEqualTo("PENDING");
    }

    @Test
    void reclaimAfterExpiryGetsTheNextToken() {
        long id = rows.insert();
        ClaimedItem first = repository.claim("owner-a", 1).getFirst();
        rows.forceExpiry(id);

        ClaimedItem second = repository.claim("owner-b", 1).getFirst();

        assertThat(second.id()).isEqualTo(id);
        assertThat(second.claimToken()).isEqualTo(first.claimToken() + 1);
        WorkItems.Row row = rows.row(id);
        assertThat(row.owner()).isEqualTo("owner-b");
        assertThat(row.attempts()).isEqualTo(2);
    }

    @Test
    void claimSkipsRowsLockedByAnotherClaimWithoutWaiting() throws Exception {
        // Inserted newest first, so ID order is the reverse of AVAILABLE_AT order.
        long newest = rows.insert();
        rows.setAvailableAt(newest, -10);
        long newer = rows.insert();
        rows.setAvailableAt(newer, -20);
        long older = rows.insert();
        rows.setAvailableAt(older, -30);
        long oldest = rows.insert();
        rows.setAvailableAt(oldest, -40);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<ClaimedItem>> heldByA = executor.submit(() -> repository.inTransaction(jdbc -> {
                List<ClaimedItem> claimed = repository.claim("owner-a", 1);   // joins this open transaction
                locked.countDown();
                await(release);
                return claimed;
            }));
            await(locked);

            long start = System.nanoTime();
            List<ClaimedItem> claimedByB = repository.claim("owner-b", 2);
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            release.countDown();

            assertThat(heldByA.get(10, TimeUnit.SECONDS)).extracting(ClaimedItem::id).containsExactly(oldest);
            assertThat(claimedByB).extracting(ClaimedItem::id).containsExactlyInAnyOrder(older, newer);
            assertThat(took).isLessThan(Db2TestSupport.IT_TIMEOUTS.lockWait());
            assertThat(rows.row(newest).status()).isEqualTo("PENDING");
        }
    }

    @Test
    void renewRenewsExactlyThisOwnersMatchingClaimedPairs() {
        long r1 = rows.insert();
        rows.setAvailableAt(r1, -20);
        long r2 = rows.insert();
        rows.setAvailableAt(r2, -10);
        long r3 = rows.insert();
        rows.setAvailableAt(r3, -5);
        ClaimKey mine = repository.claim("owner-a", 1).getFirst().key();      // r1
        ClaimKey others = repository.claim("owner-b", 1).getFirst().key();    // r2
        ClaimKey finished = repository.claim("owner-a", 1).getFirst().key();  // r3
        repository.complete("owner-a", finished, "result");
        rows.setAvailableAt(r1, 2);
        ClaimKey staleToken = new ClaimKey(r1, mine.token() + 1);

        RenewalResult result = repository.renew("owner-a", List.of(mine, others, staleToken, finished));

        assertThat(result.renewed()).containsExactly(mine);
        assertThat(result.ended()).containsExactly(finished);
        assertThat(result.lost()).containsExactlyInAnyOrder(others, staleToken);
        assertThat(rows.availableIn(r1)).isGreaterThan(Duration.ofSeconds(20));
    }

    @Test
    void renewReportsAClaimItsOwnerEndedAsEndedAndOneTakenFromItAsLost() {
        long completed = rows.insert();
        rows.setAvailableAt(completed, -40);
        long retried = rows.insert();
        rows.setAvailableAt(retried, -30);
        long failed = rows.insert();
        rows.setAvailableAt(failed, -20);
        rows.setAttempts(failed, 4);
        long revoked = rows.insert();
        rows.setAvailableAt(revoked, -10);
        Map<Long, ClaimKey> keys = repository.claim("owner-a", 4).stream()
                .collect(toMap(ClaimedItem::id, ClaimedItem::key));
        assertThat(repository.complete("owner-a", keys.get(completed), "result")).isEqualTo(DONE);
        assertThat(repository.retryOrFail("owner-a", keys.get(retried), "boom")).isEqualTo(RETRY_SCHEDULED);
        assertThat(repository.retryOrFail("owner-a", keys.get(failed), "boom")).isEqualTo(FAILED);
        assertThat(repository.revokeOwner("owner-a", false)).isEqualTo(1);   // the only row still CLAIMED

        RenewalResult result = repository.renew("owner-a", keys.values());

        assertThat(result.renewed()).isEmpty();
        assertThat(result.ended()).containsExactlyInAnyOrder(keys.get(completed), keys.get(retried), keys.get(failed));
        assertThat(result.lost()).containsExactly(keys.get(revoked));
    }

    @Test
    void renewWithNoClaimsReturnsNothing() {
        assertThat(repository.renew("owner-a", List.of())).isEqualTo(RenewalResult.NOTHING);
    }

    @Test
    void claimRenewAndSweepDoNotDependOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("fa-IR"));
        try {
            rows.insert();

            List<ClaimedItem> claimed = repository.claim("owner-a", 1);

            assertThat(claimed).hasSize(1);
            ClaimKey key = claimed.getFirst().key();

            assertThat(repository.renew("owner-a", List.of(key)).renewed()).containsExactly(key);

            long expired = rows.insert();
            rows.setClaim(expired, "owner-dead", 5, 5, -1);

            assertThat(repository.sweep(10)).isEqualTo(1);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void completeByTheCurrentClaimMarksTheRowDone() {
        long id = rows.insert();
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();

        assertThat(repository.complete("owner-a", key, "result-1")).isEqualTo(DONE);

        WorkItems.Row row = rows.row(id);
        assertThat(row.status()).isEqualTo("DONE");
        assertThat(row.resultValue()).isEqualTo("result-1");
        assertThat(row.claimToken()).isEqualTo(key.token());
        assertThat(row.owner()).isEqualTo("owner-a");
    }

    @Test
    void writesWithAStaleTokenAreFenced() {
        long id = rows.insert();
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();
        ClaimKey stale = new ClaimKey(id, key.token() - 1);
        WorkItems.Row before = rows.row(id);

        assertThat(repository.complete("owner-a", stale, "late")).isEqualTo(FENCED);
        assertThat(repository.retryOrFail("owner-a", stale, "late")).isEqualTo(FENCED);
        assertThat(repository.renew("owner-a", List.of(stale)).lost()).containsExactly(stale);
        assertThat(rows.row(id)).isEqualTo(before);
    }

    @Test
    void writesByADifferentOwnerAreFenced() {
        long id = rows.insert();
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();
        WorkItems.Row before = rows.row(id);

        assertThat(repository.complete("owner-b", key, "late")).isEqualTo(FENCED);
        assertThat(repository.retryOrFail("owner-b", key, "late")).isEqualTo(FENCED);
        assertThat(repository.renew("owner-b", List.of(key)).lost()).containsExactly(key);
        assertThat(rows.row(id)).isEqualTo(before);
    }

    @Test
    void readBackRecognisesThisOwnersAlreadyCommittedCompletion() {
        long id = rows.insert();
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();
        repository.complete("owner-a", key, "result-1");
        WorkItems.Row committed = rows.row(id);

        // The same persist again, as after a lost commit acknowledgement: 0 rows updated, read-back sees our write.
        assertThat(repository.complete("owner-a", key, "result-1")).isEqualTo(DONE);
        assertThat(rows.row(id)).isEqualTo(committed);
    }

    @Test
    void readBackRecognisesThisOwnersAlreadyCommittedRetry() {
        long id = rows.insert();
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();
        repository.retryOrFail("owner-a", key, "boom");
        WorkItems.Row committed = rows.row(id);

        assertThat(repository.retryOrFail("owner-a", key, "boom")).isEqualTo(RETRY_SCHEDULED);
        assertThat(rows.row(id)).isEqualTo(committed);
    }

    @Test
    void readBackReportsFencedWhenAnotherClaimCompletedTheRow() {
        long id = rows.insert();
        ClaimKey stale = repository.claim("owner-a", 1).getFirst().key();
        rows.forceExpiry(id);
        ClaimKey current = repository.claim("owner-b", 1).getFirst().key();
        repository.complete("owner-b", current, "result-b");

        assertThat(repository.complete("owner-a", stale, "result-a")).isEqualTo(FENCED);
        assertThat(rows.row(id).resultValue()).isEqualTo("result-b");
    }

    @Test
    void readBackReportsFencedForADifferentKindOfPersist() {
        // DONE (own complete): retryOrFail reads back, but DONE is not a retryOrFail target status.
        long doneId = rows.insert();
        ClaimKey doneKey = repository.claim("owner-a", 1).getFirst().key();
        assertThat(doneKey.id()).as("claimed the DONE-case row").isEqualTo(doneId);
        repository.complete("owner-a", doneKey, "result-1");
        WorkItems.Row doneRow = rows.row(doneId);

        assertThat(repository.retryOrFail("owner-a", doneKey, "boom")).isEqualTo(FENCED);
        assertThat(rows.row(doneId)).isEqualTo(doneRow);

        // PENDING (own retryOrFail, attempts left): complete reads back, but PENDING is not a complete target status.
        long pendingId = rows.insert();
        ClaimKey pendingKey = repository.claim("owner-a", 1).getFirst().key();
        assertThat(pendingKey.id()).as("claimed the PENDING-case row").isEqualTo(pendingId);
        repository.retryOrFail("owner-a", pendingKey, "boom");
        WorkItems.Row pendingRow = rows.row(pendingId);

        assertThat(repository.complete("owner-a", pendingKey, "result")).isEqualTo(FENCED);
        assertThat(rows.row(pendingId)).isEqualTo(pendingRow);

        // FAILED (own retryOrFail, last attempt): complete reads back, but FAILED is not a complete target status.
        long failedId = rows.insert();
        rows.setAttempts(failedId, 4);
        ClaimKey failedKey = repository.claim("owner-a", 1).getFirst().key();   // the 5th and last attempt
        assertThat(failedKey.id()).as("claimed the FAILED-case row").isEqualTo(failedId);
        repository.retryOrFail("owner-a", failedKey, "boom");
        WorkItems.Row failedRow = rows.row(failedId);

        assertThat(repository.complete("owner-a", failedKey, "result")).isEqualTo(FENCED);
        assertThat(rows.row(failedId)).isEqualTo(failedRow);
    }

    @Test
    void readBackRecognisesThisOwnersAlreadyCommittedFailure() {
        long id = rows.insert();
        rows.setAttempts(id, 4);
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();   // the 5th and last attempt
        assertThat(key.id()).as("claimed this test's row").isEqualTo(id);
        repository.retryOrFail("owner-a", key, "boom");
        WorkItems.Row committed = rows.row(id);

        assertThat(repository.retryOrFail("owner-a", key, "boom")).isEqualTo(FAILED);
        assertThat(rows.row(id)).isEqualTo(committed);
    }

    @Test
    void retryOrFailSchedulesARetryAfterTheBackoffWhileAttemptsRemain() {
        WorkItemRepository slowRetries = Db2TestSupport.repository(worker,
                new WorkItemRepository.Settings(Duration.ofSeconds(25), 5, Duration.ofSeconds(60)));
        long id = rows.insert();
        ClaimKey key = slowRetries.claim("owner-a", 1).getFirst().key();

        assertThat(slowRetries.retryOrFail("owner-a", key, "boom")).isEqualTo(RETRY_SCHEDULED);

        WorkItems.Row row = rows.row(id);
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.lastError()).isEqualTo("boom");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.owner()).isEqualTo("owner-a");
        assertThat(row.claimToken()).isEqualTo(key.token());
        assertThat(rows.availableIn(id)).isBetween(Duration.ofSeconds(55), Duration.ofSeconds(60));
        assertThat(slowRetries.claim("owner-b", 1)).isEmpty();
    }

    @Test
    void retryOrFailFailsTheRowWhenAttemptsAreExhausted() {
        long id = rows.insert();
        rows.setAttempts(id, 4);
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();   // the 5th and last attempt

        assertThat(repository.retryOrFail("owner-a", key, "boom")).isEqualTo(FAILED);

        WorkItems.Row row = rows.row(id);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.attempts()).isEqualTo(5);
        assertThat(row.lastError()).isEqualTo("boom");
    }

    @Test
    void retryOrFailTruncatesLongErrorsToTheColumnSize() {
        long id = rows.insert();
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();

        repository.retryOrFail("owner-a", key, "x".repeat(5000));

        assertThat(rows.row(id).lastError()).hasSize(WorkItemRepository.MAX_ERROR_BYTES);
    }

    @Test
    void sweepFailsExpiredClaimsWithExhaustedAttemptsInBatches() {
        List<Long> exhausted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long id = rows.insert();
            rows.setClaim(id, "owner-dead", 5, 5, -1);
            exhausted.add(id);
        }
        long live = rows.insert();
        rows.setClaim(live, "owner-a", 5, 5, 60);
        long retryable = rows.insert();
        rows.setClaim(retryable, "owner-dead", 1, 1, -1);

        assertThat(repository.sweep(2)).isEqualTo(2);
        assertThat(repository.sweep(2)).isEqualTo(1);
        assertThat(repository.sweep(2)).isZero();

        assertThat(exhausted).allSatisfy(id -> {
            WorkItems.Row row = rows.row(id);
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.lastError()).isEqualTo(WorkItemRepository.SWEPT_ERROR);
            assertThat(row.claimToken()).isEqualTo(6);
            assertThat(row.owner()).isNull();
            assertThat(row.attempts()).isEqualTo(5);
        });
        assertThat(rows.row(live).status()).isEqualTo("CLAIMED");
        assertThat(rows.row(retryable).status()).isEqualTo("CLAIMED");
    }

    @Test
    void lateWritesBySweptOwnerAreFenced() {
        long id = rows.insert();
        rows.setAttempts(id, 4);
        ClaimKey key = repository.claim("owner-a", 1).getFirst().key();
        rows.forceExpiry(id);

        assertThat(repository.sweep(10)).isEqualTo(1);

        assertThat(repository.renew("owner-a", List.of(key)).lost()).containsExactly(key);
        assertThat(repository.retryOrFail("owner-a", key, "late")).isEqualTo(FENCED);
        assertThat(repository.complete("owner-a", key, "late")).isEqualTo(FENCED);
        WorkItems.Row row = rows.row(id);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.lastError()).isEqualTo(WorkItemRepository.SWEPT_ERROR);
        assertThat(row.resultValue()).isNull();
    }

    @Test
    void sweepSkipsRowsLockedByAnotherTransactionWithoutWaiting() throws Exception {
        long locked = rows.insert();
        rows.setClaim(locked, "owner-dead", 5, 5, -1);
        long free = rows.insert();
        rows.setClaim(free, "owner-dead", 5, 5, -1);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> holder = executor.submit(() -> repository.inTransaction(jdbc -> {
                int updated = jdbc.sql("UPDATE WORK_ITEM SET UPDATED_AT = CURRENT TIMESTAMP WHERE ID = :id")
                        .param("id", locked)
                        .update();
                held.countDown();
                await(release);
                return updated;
            }));
            await(held);

            assertThat(repository.sweep(10)).isEqualTo(1);
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(rows.row(free).status()).isEqualTo("FAILED");
        assertThat(rows.row(locked).status()).isEqualTo("CLAIMED");
    }

    @Test
    void sampleBacklogCountsTheUnfinishedRowsTheExpiredClaimsAndTheOldestClaimablePendingRow() {
        long oldest = rows.insert();
        rows.setAvailableAt(oldest, -120);
        long newer = rows.insert();
        rows.setAvailableAt(newer, -30);
        long backingOff = rows.insert();
        rows.setAvailableAt(backingOff, 60);                 // PENDING in retry-backoff: not waiting on capacity
        long live = rows.insert();
        rows.setClaim(live, "owner-a", 1, 1, 20);
        long recentlyExpired = rows.insert();
        rows.setClaim(recentlyExpired, "owner-b", 1, 1, -10);   // expired less than one lease (30s) ago
        long abandoned = rows.insert();
        rows.setClaim(abandoned, "owner-c", 1, 1, -31);         // expired more than one lease ago
        long failed = rows.insert();
        rows.setStatus(failed, "FAILED");
        long done = rows.insert();
        rows.setStatus(done, "DONE");

        BacklogSample sample = repository.sampleBacklog();

        assertThat(sample.pending()).isEqualTo(3);
        assertThat(sample.claimed()).isEqualTo(3);
        assertThat(sample.failed()).isEqualTo(1);
        assertThat(sample.expiredClaims()).isEqualTo(1);
        assertThat(sample.oldestPendingAge()).isBetween(Duration.ofSeconds(120), Duration.ofSeconds(125));
    }

    @Test
    void sampleBacklogOfAnEmptyQueueIsAllZero() {
        long done = rows.insert();
        rows.setStatus(done, "DONE");
        long waiting = rows.insert();
        rows.setAvailableAt(waiting, 60);

        assertThat(repository.sampleBacklog()).isEqualTo(new BacklogSample(1, 0, 0, 0, Duration.ZERO));
    }

    @Test
    void sampleBacklogDoesNotWaitForRowsLockedByAnotherTransaction() throws Exception {
        long locked = rows.insert();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> holder = executor.submit(() -> repository.inTransaction(jdbc -> {
                int updated = jdbc.sql("UPDATE WORK_ITEM SET STATUS = 'CLAIMED', OWNER = 'owner-a' WHERE ID = :id")
                        .param("id", locked)
                        .update();
                held.countDown();
                await(release);
                return updated;
            }));
            await(held);

            long start = System.nanoTime();
            BacklogSample sample = repository.sampleBacklog();

            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no lock wait")
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(sample.pending()).as("the uncommitted state").isZero();
            assertThat(sample.claimed()).isEqualTo(1);
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    @Test
    void replayDryRunCountsAndExecuteRequeuesKeepingTokenAndOperationId() {
        long matching = failedRow("downstream 503");
        long other = failedRow("bad payload");
        ReplayFilter filter = new ReplayFilter(List.of(), "503", null);
        WorkItems.Row before = rows.row(matching);

        assertThat(repository.replay(filter, true)).isEqualTo(1);
        assertThat(rows.row(matching)).isEqualTo(before);

        assertThat(repository.replay(filter, false)).isEqualTo(1);

        WorkItems.Row replayed = rows.row(matching);
        assertThat(replayed.status()).isEqualTo("PENDING");
        assertThat(replayed.attempts()).isZero();
        assertThat(replayed.owner()).isNull();
        assertThat(replayed.claimToken()).isEqualTo(before.claimToken());
        assertThat(replayed.operationId()).isEqualTo(before.operationId());
        assertThat(rows.availableIn(matching)).isLessThanOrEqualTo(Duration.ZERO);
        assertThat(rows.row(other).status()).isEqualTo("FAILED");

        ClaimedItem reclaimed = repository.claim("owner-b", 1).getFirst();
        assertThat(reclaimed.id()).isEqualTo(matching);
        assertThat(reclaimed.claimToken()).isEqualTo(before.claimToken() + 1);
    }

    @Test
    void replayFiltersByIdsAndByFailedBefore() {
        long oldFailure = failedRow("old");
        rows.setUpdatedAt(oldFailure, -3600);
        long recentFailure = failedRow("recent");
        long done = rows.insert();
        rows.setStatus(done, "DONE");

        assertThat(repository.replay(new ReplayFilter(List.of(), null, rows.dbNow().minusMinutes(1)), true)).isEqualTo(1);
        assertThat(repository.replay(new ReplayFilter(List.of(recentFailure, done), null, null), false)).isEqualTo(1);

        assertThat(rows.row(recentFailure).status()).isEqualTo("PENDING");
        assertThat(rows.row(done).status()).isEqualTo("DONE");
        assertThat(rows.row(oldFailure).status()).isEqualTo("FAILED");
    }

    @Test
    void revokeOwnerBumpsTheTokenClearsTheOwnerAndRequeuesOrFails() {
        long retryable = rows.insert();
        rows.setAvailableAt(retryable, -20);
        long exhausted = rows.insert();
        rows.setAvailableAt(exhausted, -10);
        rows.setAttempts(exhausted, 4);
        long othersRow = rows.insert();
        rows.setClaim(othersRow, "owner-b", 1, 1, 60);
        Map<Long, ClaimKey> keys = repository.claim("owner-a", 2).stream()
                .collect(toMap(ClaimedItem::id, ClaimedItem::key));
        WorkItems.Row othersBefore = rows.row(othersRow);

        assertThat(repository.revokeOwner("owner-a", true)).isEqualTo(2);
        assertThat(rows.row(retryable).status()).isEqualTo("CLAIMED");

        assertThat(repository.revokeOwner("owner-a", false)).isEqualTo(2);

        WorkItems.Row requeued = rows.row(retryable);
        assertThat(requeued.status()).isEqualTo("PENDING");
        assertThat(requeued.owner()).isNull();
        assertThat(requeued.claimToken()).isEqualTo(keys.get(retryable).token() + 1);
        assertThat(requeued.attempts()).isEqualTo(1);
        assertThat(rows.availableIn(retryable)).isLessThanOrEqualTo(Duration.ZERO);
        WorkItems.Row failed = rows.row(exhausted);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.lastError()).isEqualTo(WorkItemRepository.REVOKED_ERROR);
        assertThat(failed.claimToken()).isEqualTo(keys.get(exhausted).token() + 1);
        assertThat(failed.owner()).isNull();
        assertThat(rows.row(othersRow)).isEqualTo(othersBefore);

        assertThat(repository.renew("owner-a", keys.values()).lost())
                .containsExactlyInAnyOrderElementsOf(keys.values());
        assertThat(repository.complete("owner-a", keys.get(retryable), "late")).isEqualTo(FENCED);
        assertThat(repository.retryOrFail("owner-a", keys.get(exhausted), "late")).isEqualTo(FENCED);
    }

    private long failedRow(String lastError) {
        long id = rows.insert();
        rows.setClaim(id, "owner-a", 3, 5, -1);
        rows.setStatus(id, "FAILED");
        rows.setLastError(id, lastError);
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
