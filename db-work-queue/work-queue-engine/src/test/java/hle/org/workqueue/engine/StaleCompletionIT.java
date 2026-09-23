package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static hle.org.workqueue.engine.PersistResult.DONE;
import static hle.org.workqueue.engine.PersistResult.FENCED;
import static org.assertj.core.api.Assertions.assertThat;

/** IT 3 of spec §11.2: a stale owner loses its renewal and its completion is fenced. */
class StaleCompletionIT {

    private static HikariDataSource worker;
    private static WorkItemRepository repository;

    private final WorkItems rows = new WorkItems(Db2TestSupport.adminDataSource());

    @BeforeAll
    static void startPool() {
        worker = Db2TestSupport.workerDataSource("wq-it-stale", 4);
        repository = Db2TestSupport.repository(worker);
    }

    @AfterAll
    static void closePool() {
        worker.close();
    }

    @Test
    void staleOwnerIsFencedAndTheNewOwnersResultIsStored() {
        rows.deleteAll();
        long id = rows.insert();
        ClaimKey a = repository.claim("owner-a", 1).getFirst().key();
        rows.forceExpiry(id);
        ClaimKey b = repository.claim("owner-b", 1).getFirst().key();
        assertThat(b.token()).isEqualTo(a.token() + 1);

        assertThat(repository.renew("owner-a", List.of(a))).as("A's renewal reports the claim lost").isEmpty();
        assertThat(repository.complete("owner-a", a, "result-a")).isEqualTo(FENCED);
        assertThat(repository.complete("owner-b", b, "result-b")).isEqualTo(DONE);
        assertThat(repository.complete("owner-a", a, "result-a")).as("still fenced after B completed").isEqualTo(FENCED);

        WorkItems.Row row = rows.row(id);
        assertThat(row.status()).isEqualTo("DONE");
        assertThat(row.resultValue()).isEqualTo("result-b");
        assertThat(row.owner()).isEqualTo("owner-b");
        assertThat(row.claimToken()).isEqualTo(b.token());
        assertThat(row.attempts()).isEqualTo(2);
    }
}
