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
 * Tests for the VirtualThreadExecutor.
 */
class VirtualThreadExecutorTest {

    private VirtualThreadExecutor executor;

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
        executor = new VirtualThreadExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit("test-1", () -> "hello");
        TaskResult<String> result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals("hello", result.getValue().orElse(null));
        assertEquals("test-1", result.getTaskId());
    }

    @Test
    void shouldExecuteOnVirtualThread() throws Exception {
        executor = new VirtualThreadExecutor();

        CompletableFuture<TaskResult<Boolean>> future = executor.submit("vt-check", () -> 
            Thread.currentThread().isVirtual()
        );
        TaskResult<Boolean> result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertTrue(result.getValue().orElse(false), "Task should run on a virtual thread");
    }

    @Test
    void shouldHandleTaskFailure() throws Exception {
        executor = new VirtualThreadExecutor();

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
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .concurrency(concurrencyLimit)
                .build()
        );

        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);

        // Submit many tasks - each gets its own virtual thread
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
        executor = new VirtualThreadExecutor(
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
    @Timeout(30)
    void shouldHandleThousandsOfTasks() throws Exception {
        int taskCount = 1000;
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .concurrency(50)  // Virtual threads can handle more concurrency
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

        List<TaskResult<Integer>> results = executor.awaitAll(futures, Duration.ofSeconds(60));

        assertEquals(taskCount, results.size());
        long successCount = results.stream().filter(TaskResult::isSuccess).count();
        assertEquals(taskCount, successCount);
    }

    @Test
    void shouldProvideFormattedStats() {
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .concurrency(10)
                .build()
        );

        String stats = executor.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("VirtualExecutor"));
        assertTrue(stats.contains("submitted="));
        assertTrue(stats.contains("completed="));
        assertTrue(stats.contains("permits="));
    }

    @Test
    void shouldShutdownGracefully() throws Exception {
        executor = new VirtualThreadExecutor();

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
        executor = new VirtualThreadExecutor();
        executor.shutdown();

        CompletableFuture<TaskResult<String>> future = executor.submit("rejected", () -> "test");
        TaskResult<String> result = future.join();

        assertTrue(result.isFailure());
    }

    @Test
    void shouldSupportSubmitAll() throws Exception {
        executor = new VirtualThreadExecutor();

        List<VirtualThreadExecutor.TaskSubmission<Integer>> submissions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int num = i;
            submissions.add(VirtualThreadExecutor.TaskSubmission.of(
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
        executor = new VirtualThreadExecutor();

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
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .threadNamePrefix("custom-vt")
                .concurrency(1)
                .build()
        );

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> 
            Thread.currentThread().getName()
        );

        TaskResult<String> result = future.get();
        assertTrue(result.getValue().orElse("").startsWith("custom-vt"));
    }

    @Test
    void shouldGetOrDefaultOnFailure() throws Exception {
        executor = new VirtualThreadExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> {
            throw new RuntimeException("fail");
        });

        TaskResult<String> result = future.get();
        assertEquals("default", result.getOrDefault("default"));
    }

    @Test
    void shouldGetOrThrowOnSuccess() throws Exception {
        executor = new VirtualThreadExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> "value");
        TaskResult<String> result = future.get();

        assertEquals("value", result.getOrThrow());
    }

    @Test
    void shouldGetOrThrowOnFailure() throws Exception {
        executor = new VirtualThreadExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit(() -> {
            throw new IllegalStateException("test error");
        });

        TaskResult<String> result = future.get();
        
        assertThrows(IllegalStateException.class, result::getOrThrow);
    }

    @Test
    @Timeout(10)
    void shouldEnforceTaskTimeout() throws Exception {
        // Configure with a short timeout
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .concurrency(2)
                .taskTimeout(Duration.ofMillis(200))
                .build()
        );

        // Submit a task that takes longer than the timeout
        CompletableFuture<TaskResult<String>> future = executor.submit("slow-task", () -> {
            try {
                Thread.sleep(5000); // Much longer than timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted");
            }
            return "should not reach here";
        });

        // Wait for result - should be a timeout failure
        TaskResult<String> result = future.get(5, TimeUnit.SECONDS);
        
        assertTrue(result.isFailure());
        assertTrue(result.getException().isPresent());
        assertTrue(result.getException().get() instanceof TimeoutException);
        assertEquals(1, executor.getTimedOutCount());
    }

    @Test
    void shouldRejectNullTaskId() {
        executor = new VirtualThreadExecutor();

        assertThrows(NullPointerException.class, () -> 
            executor.submit(null, () -> "test")
        );
    }

    @Test
    void shouldRejectNullTask() {
        executor = new VirtualThreadExecutor();

        assertThrows(NullPointerException.class, () -> 
            executor.submit("task-id", null)
        );
    }

    @Test
    void shouldGenerateUniqueAutoTaskIds() throws Exception {
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .concurrency(10)
                .build()
        );

        // Submit multiple tasks without explicit IDs
        List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> "result"));
        }

        List<TaskResult<String>> results = executor.awaitAll(futures);
        
        // Collect all task IDs and verify they are unique
        java.util.Set<String> taskIds = results.stream()
            .map(TaskResult::getTaskId)
            .collect(java.util.stream.Collectors.toSet());
        
        assertEquals(100, taskIds.size(), "All task IDs should be unique");
    }

    @Test
    void shouldHandleAwaitAllTimeout() throws Exception {
        executor = new VirtualThreadExecutor();

        List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
        futures.add(executor.submit("slow", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }));

        assertThrows(TimeoutException.class, () -> 
            executor.awaitAll(futures, Duration.ofMillis(100))
        );
    }

    @Test
    void shouldHandleShutdownNow() throws Exception {
        executor = new VirtualThreadExecutor();

        CompletableFuture<TaskResult<String>> future = executor.submit("to-be-cancelled", () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return "done";
        });

        // Give it a moment to start
        Thread.sleep(100);

        List<Runnable> neverStarted = executor.shutdownNow();
        assertTrue(executor.isShutdown());
        
        // The task should be interrupted
        TaskResult<String> result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.isFailure());
    }

    @Test
    void shouldHandleInterruptionDuringAwaitAll() throws Exception {
        executor = new VirtualThreadExecutor();
        
        List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
        futures.add(new CompletableFuture<>());
        
        Thread testThread = Thread.currentThread();
        new Thread(() -> {
            try {
                Thread.sleep(200);
                testThread.interrupt();
            } catch (InterruptedException e) {}
        }).start();

        assertThrows(RuntimeException.class, () -> 
            executor.awaitAll(futures, Duration.ofSeconds(5))
        );
        
        // Clear interrupted status
        Thread.interrupted();
    }

    @Test
    void shouldMeasureActualExecutionTime() throws Exception {
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .concurrency(1) // Only 1 concurrent, so second task waits for semaphore
                .build()
        );

        // First task holds the semaphore for 200ms
        CompletableFuture<TaskResult<String>> first = executor.submit("first", () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "first";
        });

        // Wait a bit then submit second task - it will wait for semaphore
        Thread.sleep(50);
        
        CompletableFuture<TaskResult<String>> second = executor.submit("second", () -> {
            try {
                Thread.sleep(100); // Actual execution: 100ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "second";
        });

        // Get results
        first.get();
        TaskResult<String> secondResult = second.get();

        // The second task's duration should be ~100ms (execution time), 
        // not ~250ms (semaphore wait + execution time)
        // Allow some tolerance for test flakiness
        long duration = secondResult.getDuration().toMillis();
        assertTrue(duration < 200, 
            "Duration should reflect execution time (~100ms), not semaphore wait time. Actual: " + duration + "ms");
    }

    @Test
    void shouldConfigureWithSimplifiedSettings() {
        // Virtual thread executor has simpler config - no pool sizing
        ExecutorConfig config = ExecutorConfig.builder()
            .concurrency(15)
            .threadNamePrefix("my-vt")
            .taskTimeout(Duration.ofMinutes(2))
            .build();
        
        assertEquals(15, config.getConcurrency());
        assertEquals("my-vt", config.getThreadNamePrefix());
        assertEquals(Duration.ofMinutes(2), config.getTaskTimeout());
    }

    @Test
    @Timeout(10)
    void virtualThreadsShouldUnmountDuringBlocking() throws Exception {
        // This test verifies that virtual threads properly unmount during blocking
        // by running many more tasks than carrier threads
        int taskCount = 100;
        int concurrency = 50;
        
        executor = new VirtualThreadExecutor(
            ExecutorConfig.builder()
                .concurrency(concurrency)
                .build()
        );

        List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            final int taskNum = i;
            futures.add(executor.submit("task-" + i, () -> {
                // Simulate blocking I/O - virtual thread should unmount
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "completed-" + taskNum;
            }));
        }

        List<TaskResult<String>> results = executor.awaitAll(futures);

        assertEquals(taskCount, results.size());
        assertTrue(results.stream().allMatch(TaskResult::isSuccess));
    }
}
