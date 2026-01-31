package hle.org.service;

import hle.org.client.SimulatedCorbaClient;
import hle.org.executor.ExecutorConfig;
import hle.org.executor.TaskResult;
import hle.org.pool.ResourcePoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PooledExecutorService integration.
 */
class PooledExecutorServiceTest {

    private PooledExecutorService<SimulatedCorbaClient, String> service;

    @BeforeEach
    void setUp() {
        SimulatedCorbaClient.resetGlobalCounter();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void shouldExecuteSingleRequest() {
        service = createService(5, 5, 100);

        TaskResult<String> result = service.execute("test-1", 
            client -> client.execute("hello"));

        assertTrue(result.isSuccess());
        assertTrue(result.getValue().isPresent());
        assertTrue(result.getValue().get().contains("hello"));
    }

    @Test
    void shouldExecuteAsyncRequest() throws Exception {
        service = createService(5, 5, 100);

        CompletableFuture<TaskResult<String>> future = service.executeAsync("async-1",
            client -> client.execute("async-hello"));

        TaskResult<String> result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals("async-1", result.getTaskId());
    }

    @Test
    @Timeout(30)
    void shouldHandleHundredsOfRequests() {
        int requestCount = 500;
        int poolSize = 10;
        int concurrency = 10;

        service = createService(poolSize, concurrency, 1000);

        List<PooledExecutorService.OperationSubmission<SimulatedCorbaClient, String>> operations =
            IntStream.range(0, requestCount)
                .mapToObj(i -> PooledExecutorService.OperationSubmission.<SimulatedCorbaClient, String>of(
                    "req-" + i,
                    client -> client.execute("payload-" + i)
                ))
                .collect(Collectors.toList());

        List<TaskResult<String>> results = service.executeAll(operations);

        assertEquals(requestCount, results.size());
        
        long successCount = results.stream().filter(TaskResult::isSuccess).count();
        // Allow for some failures due to simulated error rate
        assertTrue(successCount >= requestCount * 0.9, 
            "Expected at least 90% success but got " + successCount + "/" + requestCount);
    }

    @Test
    void shouldRespectPoolAndConcurrencyLimits() throws Exception {
        int poolSize = 3;
        int concurrency = 3;
        
        service = new PooledExecutorService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(100, 100)  // Fixed latency for predictability
                    .failureRate(0)
                    .verbose(false)
                    .build(),
            ResourcePoolConfig.builder()
                .maxPoolSize(poolSize)
                .minPoolSize(1)
                .build(),
            ExecutorConfig.builder()
                .concurrency(concurrency)
                .queueCapacity(100)
                .build()
        );

        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        List<CompletableFuture<TaskResult<Integer>>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(service.executeAsync("conc-" + i, client -> {
                int current = currentConcurrent.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                currentConcurrent.decrementAndGet();
                return current;
            }));
        }

        // Wait for all
        for (CompletableFuture<TaskResult<Integer>> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }

        // Max concurrent should not exceed the minimum of pool and concurrency
        int expectedMax = Math.min(poolSize, concurrency);
        assertTrue(maxConcurrent.get() <= expectedMax,
            "Max concurrent was " + maxConcurrent.get() + " but expected <= " + expectedMax);
    }

    @Test
    void shouldProvideStats() {
        service = createService(5, 5, 100);

        String stats = service.getStats();
        
        assertNotNull(stats);
        assertTrue(stats.contains("ResourcePool"));
        assertTrue(stats.contains("BoundedExecutor"));
    }

    @Test
    void shouldHandleFailures() {
        service = new PooledExecutorService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(10, 20)
                    .failureRate(1.0)  // 100% failure rate
                    .verbose(false)
                    .build(),
            5, 100
        );

        TaskResult<String> result = service.execute("fail-test",
            client -> client.execute("should-fail"));

        assertTrue(result.isFailure());
        assertTrue(result.getException().isPresent());
    }

    @Test
    void shouldSupportTimeout() throws Exception {
        service = createService(2, 2, 100);

        List<PooledExecutorService.OperationSubmission<SimulatedCorbaClient, String>> operations =
            IntStream.range(0, 10)
                .mapToObj(i -> PooledExecutorService.OperationSubmission.<SimulatedCorbaClient, String>of(
                    "timeout-" + i,
                    client -> client.execute("payload")
                ))
                .collect(Collectors.toList());

        // Should complete within timeout
        List<TaskResult<String>> results = service.executeAll(operations, Duration.ofSeconds(30));
        assertEquals(10, results.size());
    }

    @Test
    void shouldCloseGracefully() {
        service = createService(5, 5, 100);

        // Execute some requests
        service.execute("close-test", client -> client.execute("test"));

        // Close should not throw
        assertDoesNotThrow(() -> service.close());
    }

    @Test
    void shouldExposeUnderlyingComponents() {
        service = createService(5, 5, 100);

        assertNotNull(service.getResourcePool());
        assertNotNull(service.getExecutor());
        assertEquals(5, service.getResourcePool().getMaxPoolSize());
    }

    /**
     * Helper to create a service with common settings.
     */
    private PooledExecutorService<SimulatedCorbaClient, String> createService(
            int poolSize, int concurrency, int queueCapacity) {
        return new PooledExecutorService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(10, 50)
                    .failureRate(0.02)
                    .verbose(false)
                    .build(),
            ResourcePoolConfig.builder()
                .maxPoolSize(poolSize)
                .minPoolSize(1)
                .build(),
            ExecutorConfig.builder()
                .concurrency(concurrency)
                .queueCapacity(queueCapacity)
                .build()
        );
    }
}
