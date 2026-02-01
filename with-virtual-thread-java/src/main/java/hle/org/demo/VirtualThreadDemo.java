package hle.org.demo;

import hle.org.client.SimulatedCorbaClient;
import hle.org.executor.ExecutorConfig;
import hle.org.executor.TaskResult;
import hle.org.pool.ResourcePoolConfig;
import hle.org.service.VirtualPooledService;
import hle.org.service.VirtualPooledService.OperationSubmission;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Demonstration of performing thousands of CORBA-like requests with
 * virtual threads and controlled concurrency.
 * 
 * <p>This demo shows how to:
 * <ul>
 *   <li>Process thousands of requests using virtual threads</li>
 *   <li>Control the degree of parallelism with semaphores</li>
 *   <li>Efficiently reuse expensive resources (like CORBA connections)</li>
 *   <li>Handle failures gracefully</li>
 *   <li>Monitor progress and collect statistics</li>
 *   <li>Use Structured Concurrency for task lifecycle management</li>
 * </ul>
 * 
 * <p><strong>Key Differences from Pre-Virtual Thread Approach:</strong>
 * <ul>
 *   <li>No thread pool sizing - virtual threads are cheap to create</li>
 *   <li>No bounded queue - task submission is non-blocking</li>
 *   <li>Simpler code - no async/reactive complexity</li>
 *   <li>Semaphore still controls concurrency to downstream services</li>
 * </ul>
 */
public class VirtualThreadDemo {

    private static final int TOTAL_REQUESTS = 1000;
    private static final int POOL_SIZE = 10;
    private static final int CONCURRENCY = 10;

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("Virtual Thread CORBA Request Demo (JDK 25)");
        System.out.println("=".repeat(70));
        System.out.println(String.format("Total Requests: %d", TOTAL_REQUESTS));
        System.out.println(String.format("Pool Size: %d", POOL_SIZE));
        System.out.println(String.format("Concurrency: %d", CONCURRENCY));
        System.out.println(String.format("Virtual Threads: Enabled"));
        System.out.println(String.format("Structured Concurrency: Available (Preview)"));
        System.out.println("=".repeat(70));
        System.out.println();

        // Reset global counter for clean stats
        SimulatedCorbaClient.resetGlobalCounter();

        // Run the demonstration using VirtualPooledService
        runVirtualPooledServiceDemo();

        System.out.println();
        System.out.println("-".repeat(70));
        System.out.println();

        // Also demonstrate Structured Concurrency approach
        SimulatedCorbaClient.resetGlobalCounter();
        runStructuredConcurrencyDemo();

        System.out.println();
        System.out.println("Demo complete!");
    }

    /**
     * Demonstrates using the VirtualPooledService with CompletableFuture approach.
     * Similar to the pre-virtual-thread demo but with virtual threads under the hood.
     */
    private static void runVirtualPooledServiceDemo() {
        System.out.println("--- Using VirtualPooledService (CompletableFuture) ---");
        System.out.println();

        // Create the virtual pooled service with coordinated settings
        VirtualPooledService<SimulatedCorbaClient, String> service = 
            new VirtualPooledService<>(
                // Resource supplier - creates new CORBA clients as needed
                () -> SimulatedCorbaClient.builder()
                        .endpoint("corba://demo-server:1234/service")
                        .latency(50, 150)  // 50-150ms simulated latency
                        .failureRate(0.02)  // 2% failure rate
                        .verbose(false)     // Set to true for detailed logging
                        .build(),
                // Pool configuration (same as before - pooling still valuable)
                ResourcePoolConfig.builder()
                    .minPoolSize(2)
                    .maxPoolSize(POOL_SIZE)
                    .maxWaitTime(Duration.ofSeconds(30))
                    .build(),
                // Executor configuration (simplified - no pool sizing needed)
                ExecutorConfig.builder()
                    .concurrency(CONCURRENCY)  // Semaphore limit for downstream
                    .threadNamePrefix("vt-corba-worker")
                    .build()
            );

        try {
            Instant startTime = Instant.now();

            // Create all operation submissions
            List<OperationSubmission<SimulatedCorbaClient, String>> operations = 
                IntStream.range(0, TOTAL_REQUESTS)
                    .mapToObj(i -> OperationSubmission.<SimulatedCorbaClient, String>of(
                        "request-" + i,
                        client -> client.execute("payload-" + i)
                    ))
                    .collect(Collectors.toList());

            System.out.println(String.format("Submitting %d requests (each on its own virtual thread)...", 
                    TOTAL_REQUESTS));
            System.out.println();

            // Submit all requests - each gets its own virtual thread
            List<CompletableFuture<TaskResult<String>>> futures = 
                service.executeAllAsync(operations);

            // Monitor progress
            monitorProgress(service, futures);

            // Collect results
            List<TaskResult<String>> results = service.getExecutor().awaitAll(futures);

            Instant endTime = Instant.now();
            Duration totalDuration = Duration.between(startTime, endTime);

            // Print summary statistics
            printSummary("VirtualPooledService", results, totalDuration);

        } finally {
            System.out.println();
            System.out.println("Final stats: " + service.getStats());
            service.close();
        }
    }

    /**
     * Demonstrates using Structured Concurrency (JDK 25 preview feature).
     * This provides better task lifecycle management and automatic cancellation.
     */
    private static void runStructuredConcurrencyDemo() {
        System.out.println("--- Using Structured Concurrency (JDK 25 Preview) ---");
        System.out.println();

        // Use a smaller batch to keep the demo fast
        final int BATCH_SIZE = 100;

        VirtualPooledService<SimulatedCorbaClient, String> service = 
            new VirtualPooledService<>(
                () -> SimulatedCorbaClient.builder()
                        .endpoint("corba://demo-server:1234/service")
                        .latency(50, 150)
                        .failureRate(0.02)
                        .verbose(false)
                        .build(),
                ResourcePoolConfig.builder()
                    .minPoolSize(2)
                    .maxPoolSize(POOL_SIZE)
                    .build(),
                ExecutorConfig.builder()
                    .concurrency(CONCURRENCY)
                    .threadNamePrefix("vt-structured")
                    .build()
            );

        try {
            Instant startTime = Instant.now();

            List<OperationSubmission<SimulatedCorbaClient, String>> operations = 
                IntStream.range(0, BATCH_SIZE)
                    .mapToObj(i -> OperationSubmission.<SimulatedCorbaClient, String>of(
                        "structured-" + i,
                        client -> client.execute("payload-" + i)
                    ))
                    .collect(Collectors.toList());

            System.out.println(String.format("Submitting %d requests using StructuredTaskScope...", 
                    BATCH_SIZE));
            System.out.println();

            // Use structured concurrency - collects all results including failures
            List<TaskResult<String>> results = service.executeAllStructuredCollectAll(operations);

            Instant endTime = Instant.now();
            Duration totalDuration = Duration.between(startTime, endTime);

            printSummary("Structured Concurrency", results, totalDuration);

            System.out.println();
            System.out.println("Structured Concurrency Benefits:");
            System.out.println("  - Clear task ownership and lifecycle");
            System.out.println("  - Automatic cancellation on failure (with ShutdownOnFailure)");
            System.out.println("  - No orphaned virtual threads");
            System.out.println("  - Works naturally with try-with-resources");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Demo interrupted: " + e.getMessage());
        } finally {
            service.close();
        }
    }

    /**
     * Monitors progress while requests are being processed.
     */
    private static void monitorProgress(VirtualPooledService<?, ?> service,
                                         List<CompletableFuture<TaskResult<String>>> futures) {
        int lastReported = 0;
        while (true) {
            long completed = futures.stream().filter(CompletableFuture::isDone).count();
            
            // Report progress every 10%
            int percentComplete = (int) (completed * 100 / futures.size());
            if (percentComplete / 10 > lastReported / 10) {
                System.out.println(String.format("Progress: %d%% (%d/%d) | %s",
                    percentComplete, completed, futures.size(), service.getStats()));
                lastReported = percentComplete;
            }

            if (completed == futures.size()) {
                break;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Prints summary statistics for the completed requests.
     */
    private static void printSummary(String approach, List<TaskResult<String>> results, 
                                      Duration totalDuration) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("SUMMARY - " + approach);
        System.out.println("=".repeat(70));

        long successCount = results.stream().filter(TaskResult::isSuccess).count();
        long failureCount = results.stream().filter(TaskResult::isFailure).count();

        System.out.println(String.format("Total Requests:     %d", results.size()));
        System.out.println(String.format("Successful:         %d (%.1f%%)", 
                successCount, 100.0 * successCount / results.size()));
        System.out.println(String.format("Failed:             %d (%.1f%%)", 
                failureCount, 100.0 * failureCount / results.size()));
        System.out.println(String.format("Total Duration:     %d ms", totalDuration.toMillis()));
        System.out.println(String.format("Throughput:         %.1f requests/second",
                1000.0 * results.size() / totalDuration.toMillis()));

        // Latency statistics (only for results with valid durations)
        List<Long> latencies = results.stream()
                .map(r -> r.getDuration().toMillis())
                .filter(l -> l >= 0)
                .collect(Collectors.toList());

        if (!latencies.isEmpty()) {
            LongSummaryStatistics latencyStats = latencies.stream()
                    .mapToLong(l -> l)
                    .summaryStatistics();

            System.out.println();
            System.out.println("Latency Statistics:");
            System.out.println(String.format("  Min:              %d ms", latencyStats.getMin()));
            System.out.println(String.format("  Max:              %d ms", latencyStats.getMax()));
            System.out.println(String.format("  Average:          %.1f ms", latencyStats.getAverage()));

            // Calculate percentiles
            List<Long> sortedLatencies = latencies.stream()
                    .sorted()
                    .collect(Collectors.toList());

            if (sortedLatencies.size() > 1) {
                System.out.println(String.format("  P50:              %d ms", 
                        sortedLatencies.get(sortedLatencies.size() / 2)));
                System.out.println(String.format("  P90:              %d ms", 
                        sortedLatencies.get((int) (sortedLatencies.size() * 0.9))));
                System.out.println(String.format("  P99:              %d ms", 
                        sortedLatencies.get((int) (sortedLatencies.size() * 0.99))));
            }
        }

        System.out.println("=".repeat(70));

        // Show sample failures if any
        if (failureCount > 0) {
            System.out.println();
            System.out.println("Sample Failures (first 5):");
            results.stream()
                    .filter(TaskResult::isFailure)
                    .limit(5)
                    .forEach(r -> {
                        System.out.println(String.format("  - %s: %s", 
                                r.getTaskId(), 
                                r.getException().map(Throwable::getMessage).orElse("unknown")));
                    });
        }
    }
}
