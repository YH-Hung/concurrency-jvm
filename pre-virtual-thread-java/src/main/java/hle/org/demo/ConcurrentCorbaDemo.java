package hle.org.demo;

import hle.org.client.SimulatedCorbaClient;
import hle.org.executor.BoundedConcurrencyExecutor;
import hle.org.executor.ExecutorConfig;
import hle.org.executor.TaskResult;
import hle.org.pool.ResourcePool;
import hle.org.pool.ResourcePoolConfig;
import hle.org.service.PooledExecutorService;
import hle.org.service.PooledExecutorService.OperationSubmission;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Demonstration of performing thousands of CORBA-like requests with
 * controlled concurrency and resource pooling.
 * 
 * <p>This demo shows how to:
 * <ul>
 *   <li>Process thousands of requests without thread starvation</li>
 *   <li>Control the degree of parallelism</li>
 *   <li>Efficiently reuse expensive resources (like CORBA connections)</li>
 *   <li>Handle failures gracefully</li>
 *   <li>Monitor progress and collect statistics</li>
 * </ul>
 */
public class ConcurrentCorbaDemo {

    private static final int TOTAL_REQUESTS = 1000;
    private static final int POOL_SIZE = 10;
    private static final int CONCURRENCY = 10;
    private static final int QUEUE_CAPACITY = 5000;

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("Concurrent CORBA Request Demo");
        System.out.println("=".repeat(70));
        System.out.println(String.format("Total Requests: %d", TOTAL_REQUESTS));
        System.out.println(String.format("Pool Size: %d", POOL_SIZE));
        System.out.println(String.format("Concurrency: %d", CONCURRENCY));
        System.out.println(String.format("Queue Capacity: %d", QUEUE_CAPACITY));
        System.out.println("=".repeat(70));
        System.out.println();

        // Reset global counter for clean stats
        SimulatedCorbaClient.resetGlobalCounter();

        // Run the demonstration using the high-level PooledExecutorService
        runPooledExecutorDemo();

        System.out.println();
        System.out.println("Demo complete!");
    }

    /**
     * Demonstrates using the high-level PooledExecutorService.
     * This is the recommended approach for production use.
     */
    private static void runPooledExecutorDemo() {
        System.out.println("--- Using PooledExecutorService (Recommended) ---");
        System.out.println();

        // Create the pooled executor service with coordinated settings
        // The resource pool manages CORBA clients, the executor manages concurrency
        PooledExecutorService<SimulatedCorbaClient, String> service = 
            new PooledExecutorService<>(
                // Resource supplier - creates new CORBA clients as needed
                () -> SimulatedCorbaClient.builder()
                        .endpoint("corba://demo-server:1234/service")
                        .latency(50, 150)  // 50-150ms simulated latency
                        .failureRate(0.02)  // 2% failure rate
                        .verbose(false)     // Set to true for detailed logging
                        .build(),
                // Pool configuration
                ResourcePoolConfig.builder()
                    .minPoolSize(2)
                    .maxPoolSize(POOL_SIZE)
                    .maxWaitTime(Duration.ofSeconds(30))
                    .build(),
                // Executor configuration  
                ExecutorConfig.builder()
                    .concurrency(CONCURRENCY)
                    .queueCapacity(QUEUE_CAPACITY)
                    .threadNamePrefix("corba-worker")
                    .rejectionPolicy(ExecutorConfig.RejectionPolicy.BLOCK)
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

            System.out.println(String.format("Submitting %d requests...", TOTAL_REQUESTS));
            System.out.println();

            // Submit all requests - they will be processed with bounded concurrency
            List<CompletableFuture<TaskResult<String>>> futures = 
                service.executeAllAsync(operations);

            // Monitor progress
            monitorProgress(service, futures);

            // Collect results
            List<TaskResult<String>> results = service.getExecutor().awaitAll(futures);

            Instant endTime = Instant.now();
            Duration totalDuration = Duration.between(startTime, endTime);

            // Print summary statistics
            printSummary(results, totalDuration);

        } finally {
            System.out.println();
            System.out.println("Final stats: " + service.getStats());
            service.close();
        }
    }

    /**
     * Monitors progress while requests are being processed.
     */
    private static void monitorProgress(PooledExecutorService<?, ?> service,
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
    private static void printSummary(List<TaskResult<String>> results, Duration totalDuration) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("SUMMARY");
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

        // Latency statistics
        LongSummaryStatistics latencyStats = results.stream()
                .mapToLong(r -> r.getDuration().toMillis())
                .summaryStatistics();

        System.out.println();
        System.out.println("Latency Statistics:");
        System.out.println(String.format("  Min:              %d ms", latencyStats.getMin()));
        System.out.println(String.format("  Max:              %d ms", latencyStats.getMax()));
        System.out.println(String.format("  Average:          %.1f ms", latencyStats.getAverage()));

        // Calculate percentiles
        List<Long> sortedLatencies = results.stream()
                .map(r -> r.getDuration().toMillis())
                .sorted()
                .collect(Collectors.toList());

        System.out.println(String.format("  P50:              %d ms", 
                sortedLatencies.get(sortedLatencies.size() / 2)));
        System.out.println(String.format("  P90:              %d ms", 
                sortedLatencies.get((int) (sortedLatencies.size() * 0.9))));
        System.out.println(String.format("  P99:              %d ms", 
                sortedLatencies.get((int) (sortedLatencies.size() * 0.99))));

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

    /**
     * Alternative demo showing direct use of ResourcePool and BoundedConcurrencyExecutor.
     * Use this approach when you need more control over the components.
     */
    @SuppressWarnings("unused")
    private static void runSeparateComponentsDemo() {
        System.out.println("--- Using Separate Components ---");
        System.out.println();

        // Create resource pool for CORBA clients
        ResourcePool<SimulatedCorbaClient> clientPool = new ResourcePool<>(
            () -> SimulatedCorbaClient.builder()
                    .endpoint("corba://demo-server:1234/service")
                    .latency(50, 150)
                    .failureRate(0.02)
                    .verbose(false)
                    .build(),
            ResourcePoolConfig.builder()
                .minPoolSize(2)
                .maxPoolSize(POOL_SIZE)
                .build()
        );

        // Create bounded executor
        BoundedConcurrencyExecutor executor = new BoundedConcurrencyExecutor(
            ExecutorConfig.builder()
                .concurrency(CONCURRENCY)
                .queueCapacity(QUEUE_CAPACITY)
                .build()
        );

        try {
            Instant startTime = Instant.now();
            List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();

            // Submit all requests
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                final int requestId = i;
                futures.add(executor.submit(
                    "request-" + i,
                    () -> clientPool.execute(client -> 
                        client.execute("payload-" + requestId)
                    )
                ));
            }

            // Wait for all to complete
            List<TaskResult<String>> results = executor.awaitAll(futures);

            Instant endTime = Instant.now();
            printSummary(results, Duration.between(startTime, endTime));

        } finally {
            executor.close();
            clientPool.close();
        }
    }
}
