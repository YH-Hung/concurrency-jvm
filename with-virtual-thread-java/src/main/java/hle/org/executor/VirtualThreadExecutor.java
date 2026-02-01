package hle.org.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * An executor service that uses virtual threads with bounded concurrency control.
 * 
 * <p>This executor leverages JDK 21+ virtual threads for efficient handling of
 * thousands of concurrent tasks. Unlike traditional thread pools:
 * <ul>
 *   <li>No pool sizing needed - virtual threads are cheap to create</li>
 *   <li>No bounded queue needed - virtual threads don't block carrier threads</li>
 *   <li>Semaphore-based concurrency limiting - still needed for downstream services</li>
 * </ul>
 * 
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li>Virtual thread per task - no thread pool management</li>
 *   <li>Semaphore-based concurrency limiting for downstream protection</li>
 *   <li>Task timeout support</li>
 *   <li>Progress tracking and statistics</li>
 *   <li>Graceful shutdown</li>
 * </ul>
 * 
 * <p><strong>Virtual Thread Benefits:</strong>
 * <br>Virtual threads automatically unmount from carrier threads during blocking I/O,
 * allowing the JVM to efficiently multiplex millions of virtual threads onto a small
 * number of carrier threads. This eliminates the need for complex async/reactive code.
 * 
 * <p>Example usage:
 * <pre>{@code
 * VirtualThreadExecutor executor = new VirtualThreadExecutor(
 *     ExecutorConfig.builder()
 *         .concurrency(20)  // Limit concurrent downstream calls
 *         .build()
 * );
 * 
 * // Submit thousands of tasks - each gets its own virtual thread
 * List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
 * for (int i = 0; i < 10000; i++) {
 *     futures.add(executor.submit("task-" + i, () -> doBlockingIO()));
 * }
 * 
 * // Wait for all results
 * List<TaskResult<String>> results = executor.awaitAll(futures);
 * 
 * executor.close();
 * }</pre>
 */
public class VirtualThreadExecutor implements AutoCloseable {

    private final ExecutorService virtualThreadExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final Semaphore concurrencyLimiter;
    private final ExecutorConfig config;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    
    // Counter for auto-generated task IDs
    private final AtomicLong autoTaskIdCounter = new AtomicLong(0);
    
    // Statistics
    private final AtomicLong submittedCount = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicLong timedOutCount = new AtomicLong(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /**
     * Creates a new executor with the given configuration.
     */
    public VirtualThreadExecutor(ExecutorConfig config) {
        this.config = config;
        this.concurrencyLimiter = new Semaphore(config.getConcurrency(), true);
        
        // Create virtual thread executor with custom thread factory for naming
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
            .name(config.getThreadNamePrefix(), 0)
            .factory();
        
        this.virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        
        // Create a single-threaded scheduler for task timeout enforcement
        // Use a platform thread for the scheduler since it's lightweight
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofPlatform()
                .name(config.getThreadNamePrefix() + "-timeout-scheduler")
                .daemon(true)
                .unstarted(r);
            return t;
        });
    }

    /**
     * Creates a new executor with default configuration.
     */
    public VirtualThreadExecutor() {
        this(ExecutorConfig.defaultConfig());
    }

    /**
     * Submits a task for execution on a virtual thread with automatic result wrapping.
     * 
     * <p>Each task gets its own virtual thread, but actual execution is controlled
     * by the semaphore to limit concurrent operations on downstream services.
     * 
     * @param taskId a unique identifier for the task (for tracking/logging)
     * @param task the task to execute
     * @param <T> the return type of the task
     * @return a CompletableFuture that will contain the TaskResult
     * @throws NullPointerException if taskId or task is null
     */
    public <T> CompletableFuture<TaskResult<T>> submit(String taskId, Supplier<T> task) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(task, "task cannot be null");
        
        if (shutdown.get()) {
            return CompletableFuture.completedFuture(
                TaskResult.failure(taskId, new RejectedExecutionException("Executor is shutdown"),
                    Instant.now(), Instant.now())
            );
        }

        submittedCount.incrementAndGet();
        CompletableFuture<TaskResult<T>> future = new CompletableFuture<>();
        final Thread[] executingThread = new Thread[1];
        final Instant[] startTimeHolder = new Instant[1];

        Runnable wrappedTask = () -> {
            Instant queuedTime = Instant.now();
            try {
                // Acquire semaphore to respect concurrency limit
                // Virtual thread will unmount from carrier thread while waiting
                concurrencyLimiter.acquire();
                
                // Capture actual execution start time (after semaphore wait)
                Instant startTime = Instant.now();
                startTimeHolder[0] = startTime;
                executingThread[0] = Thread.currentThread();
                activeCount.incrementAndGet();
                
                try {
                    T result = task.get();
                    Instant endTime = Instant.now();
                    future.complete(TaskResult.success(taskId, result, startTime, endTime));
                    completedCount.incrementAndGet();
                } catch (Exception e) {
                    Instant endTime = Instant.now();
                    future.complete(TaskResult.failure(taskId, e, startTime, endTime));
                    failedCount.incrementAndGet();
                } finally {
                    activeCount.decrementAndGet();
                    concurrencyLimiter.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Instant startTime = startTimeHolder[0] != null ? startTimeHolder[0] : queuedTime;
                future.complete(TaskResult.failure(taskId, e, startTime, Instant.now()));
                failedCount.incrementAndGet();
            }
        };

        try {
            // Submit to virtual thread executor - each task gets its own virtual thread
            virtualThreadExecutor.execute(wrappedTask);
            
            // Schedule timeout enforcement if configured
            Duration timeout = config.getTaskTimeout();
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                timeoutScheduler.schedule(() -> {
                    if (!future.isDone()) {
                        TimeoutException timeoutEx = new TimeoutException(
                            "Task " + taskId + " exceeded timeout of " + timeout);
                        Instant startTime = startTimeHolder[0] != null ? startTimeHolder[0] : Instant.now();
                        if (future.complete(TaskResult.failure(taskId, timeoutEx, startTime, Instant.now()))) {
                            timedOutCount.incrementAndGet();
                            failedCount.incrementAndGet();
                            // Interrupt the executing thread if it's still running
                            Thread thread = executingThread[0];
                            if (thread != null) {
                                thread.interrupt();
                            }
                        }
                    }
                }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException e) {
            future.complete(TaskResult.failure(taskId, e, Instant.now(), Instant.now()));
            failedCount.incrementAndGet();
        }

        return future;
    }

    /**
     * Submits a task with an auto-generated task ID.
     * 
     * @throws NullPointerException if task is null
     */
    public <T> CompletableFuture<TaskResult<T>> submit(Supplier<T> task) {
        return submit("task-" + autoTaskIdCounter.incrementAndGet(), task);
    }

    /**
     * Submits multiple tasks and returns their futures.
     * 
     * @param tasks list of tasks with their IDs
     * @param <T> the return type of the tasks
     * @return list of CompletableFutures
     */
    public <T> List<CompletableFuture<TaskResult<T>>> submitAll(List<TaskSubmission<T>> tasks) {
        return tasks.stream()
            .map(t -> submit(t.getTaskId(), t.getTask()))
            .collect(Collectors.toList());
    }

    /**
     * Waits for all futures to complete and returns their results.
     * 
     * @param futures the futures to wait for
     * @param <T> the result type
     * @return list of TaskResults
     */
    public <T> List<TaskResult<T>> awaitAll(List<CompletableFuture<TaskResult<T>>> futures) {
        return futures.stream()
            .map(f -> {
                try {
                    return f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return TaskResult.<T>failure("unknown", e, Instant.now(), Instant.now());
                } catch (ExecutionException e) {
                    return TaskResult.<T>failure("unknown", e.getCause(), Instant.now(), Instant.now());
                }
            })
            .collect(Collectors.toList());
    }

    /**
     * Waits for all futures to complete with a timeout.
     */
    public <T> List<TaskResult<T>> awaitAll(List<CompletableFuture<TaskResult<T>>> futures, 
                                             Duration timeout) throws TimeoutException {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return awaitAll(futures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for tasks", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Unexpected execution exception", e);
        }
    }

    /**
     * Gets the number of tasks currently being executed.
     */
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * Gets the total number of tasks submitted.
     */
    public long getSubmittedCount() {
        return submittedCount.get();
    }

    /**
     * Gets the total number of tasks completed successfully.
     */
    public long getCompletedCount() {
        return completedCount.get();
    }

    /**
     * Gets the total number of tasks that failed.
     */
    public long getFailedCount() {
        return failedCount.get();
    }

    /**
     * Gets the total number of tasks that timed out.
     */
    public long getTimedOutCount() {
        return timedOutCount.get();
    }

    /**
     * Gets the available permits in the concurrency limiter.
     */
    public int getAvailablePermits() {
        return concurrencyLimiter.availablePermits();
    }

    /**
     * Returns executor statistics as a formatted string.
     */
    public String getStats() {
        return String.format(
            "VirtualExecutor[submitted=%d, completed=%d, failed=%d, timedOut=%d, active=%d, permits=%d/%d]",
            submittedCount.get(), completedCount.get(), failedCount.get(), timedOutCount.get(),
            activeCount.get(), getAvailablePermits(), config.getConcurrency()
        );
    }

    /**
     * Initiates an orderly shutdown where previously submitted tasks are executed,
     * but no new tasks will be accepted.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            virtualThreadExecutor.shutdown();
        }
    }

    /**
     * Attempts to stop all actively executing tasks and halts the processing
     * of waiting tasks.
     */
    public List<Runnable> shutdownNow() {
        shutdown.set(true);
        return virtualThreadExecutor.shutdownNow();
    }

    /**
     * Blocks until all tasks have completed execution after a shutdown request,
     * or the timeout occurs.
     */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return virtualThreadExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Returns true if this executor has been shut down.
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Returns true if all tasks have completed following shut down.
     */
    public boolean isTerminated() {
        return virtualThreadExecutor.isTerminated();
    }

    @Override
    public void close() {
        shutdown();
        timeoutScheduler.shutdown();
        try {
            if (!awaitTermination(Duration.ofSeconds(30))) {
                shutdownNow();
            }
            // Also wait for timeout scheduler to terminate
            timeoutScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            shutdownNow();
            timeoutScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Helper class for submitting tasks with IDs.
     */
    public static class TaskSubmission<T> {
        private final String taskId;
        private final Supplier<T> task;

        public TaskSubmission(String taskId, Supplier<T> task) {
            this.taskId = taskId;
            this.task = task;
        }

        public String getTaskId() {
            return taskId;
        }

        public Supplier<T> getTask() {
            return task;
        }

        public static <T> TaskSubmission<T> of(String taskId, Supplier<T> task) {
            return new TaskSubmission<>(taskId, task);
        }
    }
}
