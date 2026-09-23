package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** IT 1 of spec §11.2: 16 owners claim and complete 1000 rows; no row is claimed twice. */
class ConcurrentClaimIT {

    private static final int ROWS = 1000;
    private static final int OWNERS = 16;
    private static final int BATCH = 20;

    private final WorkItems rows = new WorkItems(Db2TestSupport.adminDataSource());
    private final Queue<Long> claimNanos = new ConcurrentLinkedQueue<>();
    private final Queue<Long> completeNanos = new ConcurrentLinkedQueue<>();

    @Test
    void sixteenOwnersClaimEveryRowExactlyOnce() throws Exception {
        List<Long> seeded = rows.seed(ROWS);
        List<List<Long>> perOwner = new ArrayList<>();

        try (HikariDataSource worker = Db2TestSupport.workerDataSource("wq-it-concurrent", OWNERS + 4)) {
            // A 5-minute lease: nothing expires during the test, so a row claimed twice is a bug, not an expiry.
            WorkItemRepository repository = Db2TestSupport.repository(worker,
                    new WorkItemRepository.Settings(Duration.ofMinutes(5), 5, Duration.ofMillis(100)));
            CyclicBarrier start = new CyclicBarrier(OWNERS);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<List<Long>>> futures = new ArrayList<>();
                for (int i = 0; i < OWNERS; i++) {
                    String owner = "owner-" + i;
                    futures.add(executor.submit(() -> claimAndCompleteUntilEmpty(repository, owner, start)));
                }
                for (Future<List<Long>> future : futures) {
                    perOwner.add(future.get(10, TimeUnit.MINUTES));
                }
            }
        }

        List<Long> claimed = perOwner.stream().flatMap(List::stream).toList();
        assertThat(claimed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(seeded);
        assertThat(perOwner).filteredOn(ids -> !ids.isEmpty()).as("owners that received rows").hasSizeGreaterThan(1);
        assertThat(rows.count("STATUS = 'DONE' AND CLAIM_TOKEN = 1 AND ATTEMPTS = 1")).isEqualTo(ROWS);
        report();
    }

    private List<Long> claimAndCompleteUntilEmpty(WorkItemRepository repository, String owner, CyclicBarrier start)
            throws Exception {
        start.await();
        List<Long> claimed = new ArrayList<>();
        while (true) {
            long claimStart = System.nanoTime();
            List<ClaimedItem> batch = repository.claim(owner, BATCH);
            claimNanos.add(System.nanoTime() - claimStart);
            if (batch.isEmpty()) {
                return claimed;
            }
            for (ClaimedItem item : batch) {
                long completeStart = System.nanoTime();
                PersistResult result = repository.complete(owner, item.key(), "result-" + item.id());
                completeNanos.add(System.nanoTime() - completeStart);
                assertThat(result).isEqualTo(PersistResult.DONE);
                claimed.add(item.id());
            }
        }
    }

    private void report() throws IOException {
        String report = String.join(System.lineSeparator(),
                "ConcurrentClaimIT: %d rows, %d owners, batch %d, %s".formatted(ROWS, OWNERS, BATCH, Db2TestSupport.IT_TIMEOUTS),
                summarize("claim", claimNanos),
                summarize("complete", completeNanos));
        System.out.println(report);
        Files.writeString(Path.of("target", "concurrent-claim-latency.txt"), report + System.lineSeparator());
    }

    private static String summarize(String operation, Collection<Long> nanos) {
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        return "%s: n=%d p50=%dms p99=%dms max=%dms".formatted(operation, sorted.length,
                millis(percentile(sorted, 0.50)), millis(percentile(sorted, 0.99)), millis(sorted[sorted.length - 1]));
    }

    private static long percentile(long[] sorted, double p) {
        return sorted[(int) Math.ceil(p * sorted.length) - 1];
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
