package hle.org.executor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * An executor service that provides bounded concurrency control with backpressure.
 * 
 * <p>This executor is designed to handle thousands of tasks with a controlled
 * degree of parallelism, preventing thread starvation and resource exhaustion.
 * 
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li>Bounded work queue with configurable rejection policy</li>
 *   <li>Semaphore-based concurrency limiting</li>
 *   <li>Support for task timeouts</li>
 *   <li>Progress tracking and statistics</li>
 *   <li>Graceful shutdown with pending task completion</li>
 * </ul>
 * 
 * <p><strong>Thread Starvation Prevention:</strong>
 * <br>This executor uses a separate thread pool from application thread pools,
 * ensuring that blocking I/O operations don't starve other parts of the application.
 * The concurrency limit ensures that at most N tasks run simultaneously.
 * 
 * <p><strong>Production Integration:</strong>
 * <br>For Spring applications, create as a @Bean with @PreDestroy shutdown.
 * For JBoss, register as a managed service with lifecycle callbacks.
 * 
 * <p>Example usage:
 * <pre>{@code
 * BoundedConcurrencyExecutor executor = new BoundedConcurrencyExecutor(
 *     ExecutorConfig.builder()
 *         .concurrency(20)
 *         .queueCapacity(10000)
 *         .build()
 * );
 * 
 * // Submit thousands of tasks
 * List<CompletableFuture<TaskResult<String>>> futures = new ArrayList<>();
 * for (int i = 0; i < 10000; i++) {
 *     futures.add(executor.submit("task-" + i, () -> doSomething()));
 * }
 * 
 * // Wait for all results
 * List<TaskResult<String>> results = executor.awaitAll(futures);
 * 
 * // Shutdown when done
 * executor.shutdown();
 * }</pre>
 */
public class BoundedConcurrencyExecutor implements AutoCloseable {

    private final ExecutorService workerPool;
    private final Semaphore concurrencyLimiter;
    private final BlockingQueue<Runnable> workQueue;
    private final ExecutorConfig config;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    
    // Statistics
    private final AtomicLong submittedCount = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /**
     * Creates a new executor with the given configuration.
     */
    public BoundedConcurrencyExecutor(ExecutorConfig config) {
        this.config = config;
        this.concurrencyLimiter = new Semaphore(config.getConcurrency(), true);
        this.workQueue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        
        // Create thread factory with meaningful names
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(config.getThreadNamePrefix() + "-" + counter.incrementAndGet());
                thread.setDaemon(config.isDaemon());
                return thread;
            }
        };
        
        // Create rejection handler based on policy
        RejectedExecutionHandler rejectionHandler = createRejectionHandler();
        
        // Use ThreadPoolExecutor for fine-grained control
        // The pool size equals concurrency because the semaphore controls actual parallelism
        this.workerPool = new ThreadPoolExecutor(
            config.getConcurrency(),
            config.getConcurrency(),
            60L, TimeUnit.SECONDS,
            workQueue,
            threadFactory,
            rejectionHandler
        );
    }

    /**
     * Creates a new executor with default configuration.
     */
    public BoundedConcurrencyExecutor() {
        this(ExecutorConfig.defaultConfig());
    }

    private RejectedExecutionHandler createRejectionHandler() {
        switch (config.getRejectionPolicy()) {
            case BLOCK:
                // Block until space is available - this is the default for preventing overload
                return (r, executor) -> {
                    if (!executor.isShutdown()) {
                        try {
                            executor.getQueue().put(r);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RejectedExecutionException("Interrupted while waiting for queue space", e);
                        }
                    }
                };
            case CALLER_RUNS:
                return new ThreadPoolExecutor.CallerRunsPolicy();
            case REJECT:
            default:
                return new ThreadPoolExecutor.AbortPolicy();
        }
    }

    /**
     * Submits a task for execution with automatic result wrapping.
     * 
     * @param taskId a unique identifier for the task (for tracking/logging)
     * @param task the task to execute
     * @param <T> the return type of the task
     * @return a CompletableFuture that will contain the TaskResult
     */
    public <T> CompletableFuture<TaskResult<T>> submit(String taskId, Supplier<T> task) {
        if (shutdown.get()) {
            return CompletableFuture.completedFuture(
                TaskResult.failure(taskId, new RejectedExecutionException("Executor is shutdown"),
                    Instant.now(), Instant.now())
            );
        }

        submittedCount.incrementAndGet();
        CompletableFuture<TaskResult<T>> future = new CompletableFuture<>();

        Runnable wrappedTask = () -> {
            Instant startTime = Instant.now();
            try {
                // Acquire semaphore to respect concurrency limit
                concurrencyLimiter.acquire();
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
                future.complete(TaskResult.failure(taskId, e, startTime, Instant.now()));
                failedCount.incrementAndGet();
            }
        };

        try {
            workerPool.execute(wrappedTask);
        } catch (RejectedExecutionException e) {
            future.complete(TaskResult.failure(taskId, e, Instant.now(), Instant.now()));
            failedCount.incrementAndGet();
        }

        return future;
    }

    /**
     * Submits a task with an auto-generated task ID.
     */
    public <T> CompletableFuture<TaskResult<T>> submit(Supplier<T> task) {
        return submit("task-" + submittedCount.get(), task);
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
                                             java.time.Duration timeout) throws TimeoutException {
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
     * Gets the number of tasks waiting in the queue.
     */
    public int getQueueSize() {
        return workQueue.size();
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
            "BoundedExecutor[submitted=%d, completed=%d, failed=%d, active=%d, queued=%d, permits=%d/%d]",
            submittedCount.get(), completedCount.get(), failedCount.get(),
            activeCount.get(), getQueueSize(), 
            getAvailablePermits(), config.getConcurrency()
        );
    }

    /**
     * Initiates an orderly shutdown where previously submitted tasks are executed,
     * but no new tasks will be accepted.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            workerPool.shutdown();
        }
    }

    /**
     * Attempts to stop all actively executing tasks and halts the processing
     * of waiting tasks.
     */
    public List<Runnable> shutdownNow() {
        shutdown.set(true);
        return workerPool.shutdownNow();
    }

    /**
     * Blocks until all tasks have completed execution after a shutdown request,
     * or the timeout occurs.
     */
    public boolean awaitTermination(java.time.Duration timeout) throws InterruptedException {
        return workerPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        return workerPool.isTerminated();
    }

    @Override
    public void close() {
        shutdown();
        try {
            if (!awaitTermination(java.time.Duration.ofSeconds(30))) {
                shutdownNow();
            }
        } catch (InterruptedException e) {
            shutdownNow();
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
