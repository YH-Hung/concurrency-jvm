package hle.org.executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the BoundedConcurrencyExecutor.
 */
class BoundedConcurrencyExecutorTest {

    private BoundedConcurrencyExecutor executor;

    @BeforeEach
    void setUp() {
        // Default executor for most tests
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.close();
        }
    }

    @Test
    void shouldExecuteSimpleTask() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit("test-1", () -> "hello");
        TaskResult<String> result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals("hello", result.getValue().orElse(null));
        assertEquals("test-1", result.getTaskId());
    }

    @Test
    void shouldHandleTaskFailure() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit("fail-task", () -> {
            throw new RuntimeException("Intentional failure");
        });
        TaskResult<String> result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isFailure());
        assertFalse(result.getValue().isPresent());
        assertTrue(result.getException().isPresent());
        assertEquals("Intentional failure", result.getException().get().getMessage());
    }

    @Test
    @Timeout(10)
    void shouldRespectConcurrencyLimit() throws Exception {
        int concurrencyLimit = 5;
        executor = new BoundedConcurrencyExecutor(
            ExecutorConfig.builder()
                .concurrency(concurrencyLimit)
                .queueCapacity(1000)
                .build()
        );

        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        CountDownLatch allStarted = new CountDownLatch(1);

        // Submit many tasks
        List<CompletableFuture<TaskResult<Integer>>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(executor.submit("task-" + i, () -> {
                int concurrent = currentConcurrent.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));
                try {
                    Thread.sleep(50); // Hold for a bit
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                currentConcurrent.decrementAndGet();
                return concurrent;
            }));
        }

        // Wait for all tasks
        List<TaskResult<Integer>> results = executor.awaitAll(futures);

        // All should complete successfully
        assertEquals(50, results.size());
        assertTrue(results.stream().allMatch(TaskResult::isSuccess));

        // Max concurrent should not exceed limit
        assertTrue(maxConcurrent.get() <= concurrencyLimit,
            "Max concurrent was " + maxConcurrent.get() + " but limit is " + concurrencyLimit);
    }

    @Test
    void shouldTrackStatistics() throws Exception {
        executor = new BoundedConcurrencyExecutor(
            ExecutorConfig.builder()
                .concurrency(2)
                .build()
        );

        assertEquals(0, executor.getSubmittedCount());
        assertEquals(0, executor.getCompletedCount());
        assertEquals(0, executor.getFailedCount());

        // Submit successful task
        executor.submit("s1", () -> "success").get();
        assertEquals(1, executor.getSubmittedCount());
        assertEquals(1, executor.getCompletedCount());
        assertEquals(0, executor.getFailedCount());

        // Submit failing task
        executor.submit("f1", () -> {
            throw new RuntimeException("fail");
        }).get();
        assertEquals(2, executor.getSubmittedCount());
        assertEquals(1, executor.getCompletedCount());
        assertEquals(1, executor.getFailedCount());
    }

    @Test
    @Timeout(10)
    void shouldHandleThousandsOfTasks() throws Exception {
        int taskCount = 1000;
        executor = new BoundedConcurrencyExecutor(
            ExecutorConfig.builder()
                .concurrency(20)
                .queueCapacity(5000)
                .build()
        );

        List<CompletableFuture<TaskResult<Integer>>> futures = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            final int taskNum = i;
            futures.add(executor.submit("task-" + i, () -> {
                try {
                    Thread.sleep(1); // Tiny delay
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return taskNum;
            }));
        }

        List<TaskResult<Integer>> results = executor.awaitAll(futures, Duration.ofSeconds(30));

        assertEquals(taskCount, results.size());
        long successCount = results.stream().filter(TaskResult::isSuccess).count();
        assertEquals(taskCount, successCount);
    }

    @Test
    void shouldProvideFormattedStats() {
        executor = new BoundedConcurrencyExecutor(
            ExecutorConfig.builder()
                .concurrency(10)
                .build()
        );

        String stats = executor.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("submitted="));
        assertTrue(stats.contains("completed="));
        assertTrue(stats.contains("permits="));
    }

    @Test
    void shouldShutdownGracefully() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        // Submit some tasks
        List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }));
        }

        // Initiate shutdown
        executor.shutdown();
        assertTrue(executor.isShutdown());

        // Wait for completion
        boolean terminated = executor.awaitTermination(Duration.ofSeconds(10));
        assertTrue(terminated);
        assertTrue(executor.isTerminated());

        // All submitted tasks should have completed
        for (CompletableFuture<TaskResult<String>> future : futures) {
            assertTrue(future.isDone());
        }
    }

    @Test
    void shouldRejectTasksAfterShutdown() {
        executor = new BoundedConcurrencyExecutor();
        executor.shutdown();

        CompletableFuture<TaskResult<String>> future = executor.submit("rejected", () -> "test");
        TaskResult<String> result = future.join();

        assertTrue(result.isFailure());
    }

    @Test
    void shouldSupportSubmitAll() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        List<BoundedConcurrencyExecutor.TaskSubmission<Integer>> submissions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int num = i;
            submissions.add(BoundedConcurrencyExecutor.TaskSubmission.of(
                "batch-" + i, () -> num * 2
            ));
        }

        List<CompletableFuture<TaskResult<Integer>>> futures = executor.submitAll(submissions);
        List<TaskResult<Integer>> results = executor.awaitAll(futures);

        assertEquals(10, results.size());
        assertTrue(results.stream().allMatch(TaskResult::isSuccess));
        
        // Verify values
        for (int i = 0; i < 10; i++) {
            assertEquals(i * 2, results.get(i).getValue().orElse(-1));
        }
    }

    @Test
    void shouldTrackTaskDuration() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit("timed", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        });

        TaskResult<String> result = future.get();
        
        assertTrue(result.isSuccess());
        assertNotNull(result.getStartTime());
        assertNotNull(result.getEndTime());
        assertTrue(result.getDuration().toMillis() >= 100);
    }

    @Test
    void shouldUseCustomThreadNames() throws Exception {
        executor = new BoundedConcurrencyExecutor(
            ExecutorConfig.builder()
                .threadNamePrefix("custom-worker")
                .concurrency(1)
                .build()
        );

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> 
            Thread.currentThread().getName()
        );

        TaskResult<String> result = future.get();
        assertTrue(result.getValue().orElse("").startsWith("custom-worker-"));
    }

    @Test
    void shouldGetOrDefaultOnFailure() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> {
            throw new RuntimeException("fail");
        });

        TaskResult<String> result = future.get();
        assertEquals("default", result.getOrDefault("default"));
    }

    @Test
    void shouldGetOrThrowOnSuccess() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> "value");
        TaskResult<String> result = future.get();

        assertEquals("value", result.getOrThrow());
    }

    @Test
    void shouldGetOrThrowOnFailure() throws Exception {
        executor = new BoundedConcurrencyExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> {
            throw new IllegalStateException("test error");
        });

        TaskResult<String> result = future.get();
        
        assertThrows(IllegalStateException.class, result::getOrThrow);
    }
}
