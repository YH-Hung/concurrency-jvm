package hle.org.service;

import hle.org.executor.ExecutorConfig;
import hle.org.executor.TaskResult;
import hle.org.executor.VirtualThreadExecutor;
import hle.org.pool.PooledResource;
import hle.org.pool.ResourcePool;
import hle.org.pool.ResourcePoolConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A high-level service that combines a resource pool with virtual thread execution.
 * 
 * <p>This is the main entry point for executing thousands of requests with:
 * <ul>
 *   <li>Virtual threads - efficient handling of blocking I/O</li>
 *   <li>Controlled concurrency - prevents overwhelming downstream services</li>
 *   <li>Resource pooling - efficient reuse of expensive connections</li>
 *   <li>Structured Concurrency - clear task lifecycle management (JDK 25 preview)</li>
 * </ul>
 * 
 * <p><strong>Virtual Thread Advantages:</strong>
 * <ul>
 *   <li>No thread pool sizing decisions - virtual threads are cheap</li>
 *   <li>No async/reactive complexity - write synchronous code</li>
 *   <li>Automatic carrier thread management during blocking I/O</li>
 * </ul>
 * 
 * <p><strong>Structured Concurrency Support:</strong>
 * <br>This service provides methods that leverage {@link StructuredTaskScope} for
 * better task lifecycle management, automatic cancellation, and clear ownership.
 * 
 * <p><strong>Production Integration:</strong>
 * <pre>{@code
 * // Spring Configuration
 * @Configuration
 * public class ConcurrencyConfig {
 *     
 *     @Bean(destroyMethod = "close")
 *     public VirtualPooledService<MyCorbaClient, String> corbaService() {
 *         return new VirtualPooledService<>(
 *             () -> new MyCorbaClient(endpoint),
 *             ResourcePoolConfig.builder().maxPoolSize(10).build(),
 *             ExecutorConfig.builder().concurrency(10).build()
 *         );
 *     }
 * }
 * }</pre>
 * 
 * @param <R> the type of pooled resource
 * @param <T> the response type from resource operations
 */
public class VirtualPooledService<R extends PooledResource<T>, T> implements AutoCloseable {

    private final ResourcePool<R> resourcePool;
    private final VirtualThreadExecutor executor;

    /**
     * Creates a new virtual pooled service.
     * 
     * @param resourceSupplier supplier that creates new resource instances
     * @param poolConfig configuration for the resource pool
     * @param executorConfig configuration for the executor
     */
    public VirtualPooledService(Supplier<R> resourceSupplier,
                                 ResourcePoolConfig poolConfig,
                                 ExecutorConfig executorConfig) {
        this.resourcePool = new ResourcePool<>(resourceSupplier, poolConfig);
        this.executor = new VirtualThreadExecutor(executorConfig);
    }

    /**
     * Creates a new virtual pooled service with default configuration.
     * Uses pool size of 10 and concurrency of 10.
     */
    public VirtualPooledService(Supplier<R> resourceSupplier) {
        this(resourceSupplier, 
             ResourcePoolConfig.builder().maxPoolSize(10).build(),
             ExecutorConfig.builder().concurrency(10).build());
    }

    /**
     * Creates a service with coordinated pool and executor sizes.
     * 
     * @param resourceSupplier supplier that creates new resource instances
     * @param poolAndConcurrencySize size for both pool and executor concurrency
     */
    public VirtualPooledService(Supplier<R> resourceSupplier, int poolAndConcurrencySize) {
        this(resourceSupplier,
             ResourcePoolConfig.builder()
                 .maxPoolSize(poolAndConcurrencySize)
                 .minPoolSize(Math.min(2, poolAndConcurrencySize))
                 .build(),
             ExecutorConfig.builder()
                 .concurrency(poolAndConcurrencySize)
                 .build());
    }

    /**
     * Executes a single operation using a pooled resource.
     * The operation is executed asynchronously on a virtual thread.
     * 
     * @param taskId unique identifier for tracking
     * @param operation the operation to perform with the resource
     * @param <V> the return type of the operation
     * @return a future containing the task result
     * @throws NullPointerException if taskId or operation is null
     */
    public <V> CompletableFuture<TaskResult<V>> executeAsync(String taskId, 
                                                              Function<R, V> operation) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        return executor.submit(taskId, () -> resourcePool.execute(operation));
    }

    /**
     * Executes a single operation and waits for the result.
     * 
     * @param taskId unique identifier for tracking
     * @param operation the operation to perform with the resource
     * @param <V> the return type of the operation
     * @return the task result
     * @throws NullPointerException if taskId or operation is null
     */
    public <V> TaskResult<V> execute(String taskId, Function<R, V> operation) {
        try {
            return executeAsync(taskId, operation).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TaskResult.failure(taskId, e, Instant.now(), Instant.now());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return TaskResult.failure(taskId, cause, Instant.now(), Instant.now());
        } catch (Exception e) {
            return TaskResult.failure(taskId, e, Instant.now(), Instant.now());
        }
    }

    /**
     * Executes multiple operations in parallel using virtual threads.
     * 
     * @param operations list of operations with their task IDs
     * @param <V> the return type of the operations
     * @return list of futures for each operation
     */
    public <V> List<CompletableFuture<TaskResult<V>>> executeAllAsync(
            List<OperationSubmission<R, V>> operations) {
        List<CompletableFuture<TaskResult<V>>> futures = new ArrayList<>();
        for (OperationSubmission<R, V> op : operations) {
            futures.add(executeAsync(op.getTaskId(), op.getOperation()));
        }
        return futures;
    }

    /**
     * Executes multiple operations and waits for all results.
     * 
     * @param operations list of operations with their task IDs
     * @param <V> the return type of the operations
     * @return list of task results
     */
    public <V> List<TaskResult<V>> executeAll(List<OperationSubmission<R, V>> operations) {
        return executor.awaitAll(executeAllAsync(operations));
    }

    /**
     * Executes multiple operations with a timeout.
     * 
     * @param operations list of operations with their task IDs
     * @param timeout maximum time to wait for all operations
     * @param <V> the return type of the operations
     * @return list of task results
     * @throws TimeoutException if the timeout is exceeded
     */
    public <V> List<TaskResult<V>> executeAll(List<OperationSubmission<R, V>> operations,
                                               Duration timeout) throws TimeoutException {
        return executor.awaitAll(executeAllAsync(operations), timeout);
    }

    /**
     * Executes multiple operations using Structured Concurrency (JDK 25 preview).
     * 
     * <p>This method uses {@link Joiner#allSuccessfulOrThrow()} to:
     * <ul>
     *   <li>Fork each operation as a subtask on its own virtual thread</li>
     *   <li>Wait for all subtasks to complete successfully</li>
     *   <li>Propagate the first exception if any subtask fails</li>
     *   <li>Cancel remaining subtasks on failure</li>
     * </ul>
     * 
     * <p>Use this when you want fail-fast behavior where any failure should
     * stop all other operations.
     * 
     * @param operations list of operations with their task IDs
     * @param <V> the return type of the operations
     * @return list of task results (all successful)
     * @throws Exception if any operation fails
     */
    public <V> List<TaskResult<V>> executeAllStructured(
            List<OperationSubmission<R, V>> operations) throws Exception {
        
        try (var scope = StructuredTaskScope.open(Joiner.<TaskResult<V>>allSuccessfulOrThrow())) {
            for (OperationSubmission<R, V> op : operations) {
                scope.fork(() -> {
                    TaskResult<V> result = executeWithPool(op.getTaskId(), op.getOperation());
                    if (result.isFailure()) {
                        Throwable t = result.getException().orElse(new RuntimeException("Task failed: " + op.getTaskId()));
                        if (t instanceof Exception e) throw e;
                        throw new RuntimeException(t);
                    }
                    return result;
                });
            }
            
            return scope.join().map(subtask -> subtask.get()).toList();
        } catch (Throwable t) {
            if (t instanceof Exception e) throw e;
            throw new RuntimeException(t);
        }
    }

    /**
     * Executes multiple operations using Structured Concurrency, collecting all results
     * including failures (no fail-fast behavior).
     * 
     * <p>Unlike {@link #executeAllStructured}, this method collects all results
     * even if some operations fail. Use this when you want to process as many
     * operations as possible and handle failures individually.
     * 
     * @param operations list of operations with their task IDs
     * @param <V> the return type of the operations
     * @return list of task results (may include failures)
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public <V> List<TaskResult<V>> executeAllStructuredCollectAll(
            List<OperationSubmission<R, V>> operations) throws InterruptedException {
        
        try (var scope = StructuredTaskScope.open(Joiner.<TaskResult<V>>awaitAll())) {
            List<Subtask<TaskResult<V>>> subtasks = new ArrayList<>();
            
            for (OperationSubmission<R, V> op : operations) {
                subtasks.add(scope.fork(() -> executeWithPool(op.getTaskId(), op.getOperation())));
            }
            
            scope.join();  // Wait for all tasks to complete
            
            // Collect all results, handling both success and failure
            List<TaskResult<V>> results = new ArrayList<>();
            for (var subtask : subtasks) {
                if (subtask.state() == Subtask.State.SUCCESS) {
                    results.add(subtask.get());
                } else if (subtask.state() == Subtask.State.FAILED) {
                    // Create a failure result from the exception
                    Throwable exception = subtask.exception();
                    results.add(TaskResult.failure("unknown", exception, Instant.now(), Instant.now()));
                } else if (subtask.state() == Subtask.State.UNAVAILABLE) {
                    results.add(TaskResult.failure("unknown",
                        new CancellationException("Subtask cancelled or unavailable"), Instant.now(), Instant.now()));
                }
            }
            return results;
        }
    }

    /**
     * Internal method to execute an operation with the pool.
     * This is used by structured concurrency methods.
     */
    private <V> TaskResult<V> executeWithPool(String taskId, Function<R, V> operation) {
        Instant startTime = Instant.now();
        try {
            V result = resourcePool.execute(operation);
            return TaskResult.success(taskId, result, startTime, Instant.now());
        } catch (Exception e) {
            return TaskResult.failure(taskId, e, startTime, Instant.now());
        }
    }

    /**
     * Gets combined statistics from both the pool and executor.
     */
    public String getStats() {
        return String.format("%s, %s", resourcePool.getStats(), executor.getStats());
    }

    /**
     * Gets the underlying resource pool (for advanced use cases).
     */
    public ResourcePool<R> getResourcePool() {
        return resourcePool;
    }

    /**
     * Gets the underlying executor (for advanced use cases).
     */
    public VirtualThreadExecutor getExecutor() {
        return executor;
    }

    @Override
    public void close() {
        executor.close();
        // Wait for borrowed resources to be returned before closing the pool (with timeout)
        long deadline = System.currentTimeMillis() + 5000;
        while (resourcePool.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int active = resourcePool.getActiveCount();
        if (active > 0) {
            throw new IllegalStateException(
                "Timed out waiting for " + active + " borrowed resources to be returned");
        }
        resourcePool.close();
    }

    /**
     * Helper class for submitting operations.
     */
    public static class OperationSubmission<R, V> {
        private final String taskId;
        private final Function<R, V> operation;

        public OperationSubmission(String taskId, Function<R, V> operation) {
            this.taskId = taskId;
            this.operation = operation;
        }

        public String getTaskId() {
            return taskId;
        }

        public Function<R, V> getOperation() {
            return operation;
        }

        public static <R, V> OperationSubmission<R, V> of(String taskId, Function<R, V> operation) {
            return new OperationSubmission<>(taskId, operation);
        }
    }
}
