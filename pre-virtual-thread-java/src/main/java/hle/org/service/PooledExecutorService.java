package hle.org.service;

import hle.org.executor.BoundedConcurrencyExecutor;
import hle.org.executor.ExecutorConfig;
import hle.org.executor.TaskResult;
import hle.org.pool.PooledResource;
import hle.org.pool.ResourcePool;
import hle.org.pool.ResourcePoolConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A high-level service that combines a resource pool with bounded concurrency execution.
 * 
 * <p>This is the main entry point for executing thousands of requests with:
 * <ul>
 *   <li>Controlled concurrency (prevents overwhelming downstream services)</li>
 *   <li>Resource pooling (efficient reuse of expensive connections)</li>
 *   <li>Backpressure (prevents memory exhaustion from unbounded queues)</li>
 *   <li>Thread starvation prevention (dedicated executor threads)</li>
 * </ul>
 * 
 * <p><strong>Design Philosophy:</strong>
 * <br>The concurrency level and pool size should be coordinated:
 * <ul>
 *   <li>Pool size limits how many resources can exist simultaneously</li>
 *   <li>Executor concurrency limits how many tasks run simultaneously</li>
 *   <li>Typically: executor concurrency ≥ pool size (to keep all resources busy)</li>
 * </ul>
 * 
 * <p><strong>Production Integration:</strong>
 * <pre>{@code
 * // Spring Configuration
 * @Configuration
 * public class ConcurrencyConfig {
 *     
 *     @Bean
 *     @PreDestroy
 *     public PooledExecutorService<MyCorbaClient, String> corbaService() {
 *         return new PooledExecutorService<>(
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
public class PooledExecutorService<R extends PooledResource<T>, T> implements AutoCloseable {

    private final ResourcePool<R> resourcePool;
    private final BoundedConcurrencyExecutor executor;

    /**
     * Creates a new pooled executor service.
     * 
     * @param resourceSupplier supplier that creates new resource instances
     * @param poolConfig configuration for the resource pool
     * @param executorConfig configuration for the executor
     */
    public PooledExecutorService(Supplier<R> resourceSupplier,
                                  ResourcePoolConfig poolConfig,
                                  ExecutorConfig executorConfig) {
        this.resourcePool = new ResourcePool<>(resourceSupplier, poolConfig);
        this.executor = new BoundedConcurrencyExecutor(executorConfig);
    }

    /**
     * Creates a new pooled executor service with default configuration.
     * Uses pool size of 10 and concurrency of 10.
     */
    public PooledExecutorService(Supplier<R> resourceSupplier) {
        this(resourceSupplier, 
             ResourcePoolConfig.builder().maxPoolSize(10).build(),
             ExecutorConfig.builder().concurrency(10).build());
    }

    /**
     * Creates a service with coordinated pool and executor sizes.
     * 
     * @param resourceSupplier supplier that creates new resource instances
     * @param poolAndConcurrencySize size for both pool and executor concurrency
     * @param queueCapacity maximum pending tasks
     */
    public PooledExecutorService(Supplier<R> resourceSupplier, 
                                  int poolAndConcurrencySize,
                                  int queueCapacity) {
        this(resourceSupplier,
             ResourcePoolConfig.builder()
                 .maxPoolSize(poolAndConcurrencySize)
                 .minPoolSize(Math.min(2, poolAndConcurrencySize))
                 .build(),
             ExecutorConfig.builder()
                 .concurrency(poolAndConcurrencySize)
                 .queueCapacity(queueCapacity)
                 .build());
    }

    /**
     * Executes a single operation using a pooled resource.
     * The operation is executed asynchronously.
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
        } catch (Exception e) {
            return TaskResult.failure(taskId, e, Instant.now(), Instant.now());
        }
    }

    /**
     * Executes multiple operations in parallel with bounded concurrency.
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
    public BoundedConcurrencyExecutor getExecutor() {
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
