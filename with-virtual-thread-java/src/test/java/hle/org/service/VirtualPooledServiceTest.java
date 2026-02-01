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
 * Tests for the VirtualPooledService integration.
 */
class VirtualPooledServiceTest {

    private VirtualPooledService<SimulatedCorbaClient, String> service;

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
        service = createService(5, 5);

        TaskResult<String> result = service.execute("test-1", 
            client -> client.execute("hello"));

        assertTrue(result.isSuccess());
        assertTrue(result.getValue().isPresent());
        assertTrue(result.getValue().get().contains("hello"));
    }

    @Test
    void shouldExecuteOnVirtualThread() throws Exception {
        service = createService(5, 5);

        CompletableFuture<TaskResult<Boolean>> future = service.executeAsync("vt-check",
            client -> Thread.currentThread().isVirtual());

        TaskResult<Boolean> result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertTrue(result.getValue().orElse(false), "Task should run on a virtual thread");
    }

    @Test
    void shouldExecuteAsyncRequest() throws Exception {
        service = createService(5, 5);

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

        service = createService(poolSize, concurrency);

        List<VirtualPooledService.OperationSubmission<SimulatedCorbaClient, String>> operations =
            IntStream.range(0, requestCount)
                .mapToObj(i -> VirtualPooledService.OperationSubmission.<SimulatedCorbaClient, String>of(
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
        
        service = new VirtualPooledService<>(
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
        service = createService(5, 5);

        String stats = service.getStats();
        
        assertNotNull(stats);
        assertTrue(stats.contains("ResourcePool"));
        assertTrue(stats.contains("VirtualExecutor"));
    }

    @Test
    void shouldHandleFailures() {
        service = new VirtualPooledService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(10, 20)
                    .failureRate(1.0)  // 100% failure rate
                    .verbose(false)
                    .build(),
            5
        );

        TaskResult<String> result = service.execute("fail-test",
            client -> client.execute("should-fail"));

        assertTrue(result.isFailure());
        assertTrue(result.getException().isPresent());
    }

    @Test
    void shouldSupportTimeout() throws Exception {
        service = createService(2, 2);

        List<VirtualPooledService.OperationSubmission<SimulatedCorbaClient, String>> operations =
            IntStream.range(0, 10)
                .mapToObj(i -> VirtualPooledService.OperationSubmission.<SimulatedCorbaClient, String>of(
                    "timeout-" + i,
                    client -> client.execute("payload")
                ))
                .collect(Collectors.toList());

        // Should complete within timeout
        List<TaskResult<String>> results = service.executeAll(operations, Duration.ofSeconds(30));
        assertEquals(10, results.size());
    }

    @Test
    @Timeout(10)
    void shouldSupportStructuredConcurrencyCollectAll() throws Exception {
        service = createService(5, 5);

        List<VirtualPooledService.OperationSubmission<SimulatedCorbaClient, String>> operations =
            IntStream.range(0, 20)
                .mapToObj(i -> VirtualPooledService.OperationSubmission.<SimulatedCorbaClient, String>of(
                    "structured-" + i,
                    client -> client.execute("payload-" + i)
                ))
                .collect(Collectors.toList());

        // Use structured concurrency - collect all results
        List<TaskResult<String>> results = service.executeAllStructuredCollectAll(operations);

        assertEquals(20, results.size());
        
        long successCount = results.stream().filter(TaskResult::isSuccess).count();
        assertTrue(successCount >= 18, "Expected mostly successes");
    }

    @Test
    @Timeout(10)
    void shouldSupportStructuredConcurrencyFailFast() throws Exception {
        // Create service with no failures for this test
        service = new VirtualPooledService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(10, 50)
                    .failureRate(0.0)  // No failures
                    .verbose(false)
                    .build(),
            ResourcePoolConfig.builder()
                .maxPoolSize(5)
                .minPoolSize(1)
                .build(),
            ExecutorConfig.builder()
                .concurrency(5)
                .build()
        );

        List<VirtualPooledService.OperationSubmission<SimulatedCorbaClient, String>> operations =
            IntStream.range(0, 10)
                .mapToObj(i -> VirtualPooledService.OperationSubmission.<SimulatedCorbaClient, String>of(
                    "failfast-" + i,
                    client -> client.execute("payload-" + i)
                ))
                .collect(Collectors.toList());

        // Use structured concurrency with fail-fast
        List<TaskResult<String>> results = service.executeAllStructured(operations);

        assertEquals(10, results.size());
        assertTrue(results.stream().allMatch(TaskResult::isSuccess));
    }

    @Test
    void shouldFailFastInStructuredConcurrency() {
        service = new VirtualPooledService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(50, 100)
                    .failureRate(0.0)
                    .verbose(false)
                    .build(),
            5
        );

        List<VirtualPooledService.OperationSubmission<SimulatedCorbaClient, String>> operations = new ArrayList<>();
        operations.add(VirtualPooledService.OperationSubmission.of("ok-1", client -> client.execute("ok")));
        operations.add(VirtualPooledService.OperationSubmission.of("fail-1", client -> {
            throw new RuntimeException("forced failure");
        }));
        operations.add(VirtualPooledService.OperationSubmission.of("ok-2", client -> {
            try {
                Thread.sleep(1000); // Should be cancelled
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return client.execute("ok");
        }));

        assertThrows(Exception.class, () -> service.executeAllStructured(operations));
    }

    @Test
    void shouldHandleFailuresInStructuredCollectAll() throws Exception {
        service = new VirtualPooledService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(10, 20)
                    .failureRate(0.0)
                    .verbose(false)
                    .build(),
            5
        );

        List<VirtualPooledService.OperationSubmission<SimulatedCorbaClient, String>> operations = new ArrayList<>();
        operations.add(VirtualPooledService.OperationSubmission.of("ok", client -> "success"));
        operations.add(VirtualPooledService.OperationSubmission.of("fail", client -> {
            throw new RuntimeException("forced failure");
        }));

        List<TaskResult<String>> results = service.executeAllStructuredCollectAll(operations);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.getTaskId().equals("ok") && r.isSuccess()));
        assertTrue(results.stream().anyMatch(r -> r.isFailure()));
    }

    @Test
    void shouldCloseGracefully() {
        service = createService(5, 5);

        // Execute some requests
        service.execute("close-test", client -> client.execute("test"));

        // Close should not throw
        assertDoesNotThrow(() -> service.close());
    }

    @Test
    void shouldExposeUnderlyingComponents() {
        service = createService(5, 5);

        assertNotNull(service.getResourcePool());
        assertNotNull(service.getExecutor());
        assertEquals(5, service.getResourcePool().getMaxPoolSize());
    }

    @Test
    void shouldCreateWithCoordinatedSize() {
        service = new VirtualPooledService<>(
            () -> SimulatedCorbaClient.builder()
                    .latency(10, 50)
                    .failureRate(0)
                    .verbose(false)
                    .build(),
            8  // Sets both pool size and concurrency
        );

        assertEquals(8, service.getResourcePool().getMaxPoolSize());
        // Can't directly check concurrency but it should be 8 as well
    }

    /**
     * Helper to create a service with common settings.
     */
    private VirtualPooledService<SimulatedCorbaClient, String> createService(
            int poolSize, int concurrency) {
        return new VirtualPooledService<>(
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
                .build()
        );
    }
}
