package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** IT 5 of spec §11.2: revokeOwner racing the owner's own writes on separate connections. */
class RevokeRaceIT {

    private static final int RACES = 200;

    private enum Write { COMPLETE, RETRY_OR_FAIL, RENEW }

    private record Claim(String owner, long id, ClaimKey key) {
    }

    private record Outcome(int revoked, Object written, WorkItems.Row row) {
    }

    private static HikariDataSource worker;
    private static WorkItemRepository repository;

    private final WorkItems rows = new WorkItems(Db2TestSupport.adminDataSource());

    @BeforeAll
    static void startPool() {
        worker = Db2TestSupport.workerDataSource("wq-it-revoke-race", 8);
        repository = Db2TestSupport.repository(worker);
    }

    @AfterAll
    static void closePool() {
        worker.close();
    }

    @Test
    void revokeRacingCompleteLetsExactlyOneWin() throws Exception {
        raceRepeatedly(Write.COMPLETE);
    }

    @Test
    void revokeRacingRetryOrFailLetsExactlyOneWin() throws Exception {
        raceRepeatedly(Write.RETRY_OR_FAIL);
    }

    @Test
    void revokeRacingRenewAlwaysRevokes() throws Exception {
        raceRepeatedly(Write.RENEW);
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void ownerWriteCommittedBeforeRevoke(Write write) {
        Claim claim = newClaim("first-" + write);

        Object written = perform(write, claim);
        int revoked = repository.revokeOwner(claim.owner(), false);

        assertValidOutcome(write, claim, new Outcome(revoked, written, rows.row(claim.id())));
        if (write == Write.RENEW) {
            assertThat(written).isEqualTo(Set.of(claim.key()));
        } else {
            assertThat(revoked).isZero();
        }
        assertOldOwnerIsFenced(claim);
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void revokeCommittedBeforeOwnerWrite(Write write) {
        Claim claim = newClaim("second-" + write);

        int revoked = repository.revokeOwner(claim.owner(), false);
        Object written = perform(write, claim);

        assertValidOutcome(write, claim, new Outcome(revoked, written, rows.row(claim.id())));
        assertThat(revoked).isEqualTo(1);
        assertThat(written).isEqualTo(write == Write.RENEW ? Set.of() : PersistResult.FENCED);
        assertOldOwnerIsFenced(claim);
    }

    private void raceRepeatedly(Write write) throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        for (int i = 0; i < RACES; i++) {
            Claim claim = newClaim("race-" + i);
            Outcome outcome = race(write, claim);
            assertValidOutcome(write, claim, outcome);
            assertOldOwnerIsFenced(claim);
            seen.merge(order(write, outcome), 1, Integer::sum);
        }
        System.out.printf("RevokeRaceIT %s over %d races: %s%n", write, RACES, seen);
    }

    private Claim newClaim(String owner) {
        rows.deleteAll();
        long id = rows.insert();
        ClaimKey key = repository.claim(owner, 1).getFirst().key();
        return new Claim(owner, id, key);
    }

    private Outcome race(Write write, Claim claim) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> revoked = executor.submit(() -> {
                barrier.await();
                return repository.revokeOwner(claim.owner(), false);
            });
            Future<Object> written = executor.submit(() -> {
                barrier.await();
                return perform(write, claim);
            });
            return new Outcome(revoked.get(30, TimeUnit.SECONDS), written.get(30, TimeUnit.SECONDS), rows.row(claim.id()));
        }
    }

    private static Object perform(Write write, Claim claim) {
        return switch (write) {
            case COMPLETE -> repository.complete(claim.owner(), claim.key(), "result");
            case RETRY_OR_FAIL -> repository.retryOrFail(claim.owner(), claim.key(), "boom");
            case RENEW -> repository.renew(claim.owner(), List.of(claim.key()));
        };
    }

    private static void assertValidOutcome(Write write, Claim claim, Outcome outcome) {
        WorkItems.Row row = outcome.row();
        switch (write) {
            case COMPLETE -> {
                if (outcome.revoked() == 0) {
                    assertThat(outcome.written()).isEqualTo(PersistResult.DONE);
                    assertThat(row.status()).isEqualTo("DONE");
                    assertThat(row.resultValue()).isEqualTo("result");
                    assertStillOwned(claim, row);
                } else {
                    assertThat(outcome.revoked()).isEqualTo(1);
                    assertThat(outcome.written()).isEqualTo(PersistResult.FENCED);
                    assertRevoked(claim, row);
                }
            }
            case RETRY_OR_FAIL -> {
                if (outcome.revoked() == 0) {
                    assertThat(outcome.written()).isEqualTo(PersistResult.RETRY_SCHEDULED);
                    assertThat(row.status()).isEqualTo("PENDING");
                    assertThat(row.lastError()).isEqualTo("boom");
                    assertStillOwned(claim, row);
                } else {
                    assertThat(outcome.revoked()).isEqualTo(1);
                    assertThat(outcome.written()).isEqualTo(PersistResult.FENCED);
                    assertRevoked(claim, row);
                }
            }
            case RENEW -> {
                assertThat(outcome.revoked()).isEqualTo(1);
                assertThat(outcome.written()).isIn(Set.of(), Set.of(claim.key()));
                assertRevoked(claim, row);
            }
        }
    }

    private static void assertStillOwned(Claim claim, WorkItems.Row row) {
        assertThat(row.claimToken()).isEqualTo(claim.key().token());
        assertThat(row.owner()).isEqualTo(claim.owner());
    }

    private static void assertRevoked(Claim claim, WorkItems.Row row) {
        assertThat(row.claimToken()).isEqualTo(claim.key().token() + 1);
        assertThat(row.owner()).isNull();
        assertThat(row.status()).isEqualTo("PENDING");   // ATTEMPTS 1 < max-attempts 5
    }

    /** After either order, no further write by the old owner changes the row. */
    private void assertOldOwnerIsFenced(Claim claim) {
        WorkItems.Row before = rows.row(claim.id());

        assertThat(repository.renew(claim.owner(), List.of(claim.key()))).isEmpty();
        repository.complete(claim.owner(), claim.key(), "late");
        repository.retryOrFail(claim.owner(), claim.key(), "late");

        assertThat(rows.row(claim.id())).isEqualTo(before);
    }

    private static String order(Write write, Outcome outcome) {
        if (write == Write.RENEW) {
            return outcome.written().equals(Set.of()) ? "revoke first" : "renew first";
        }
        return outcome.revoked() == 1 ? "revoke first" : "owner write first";
    }
}
