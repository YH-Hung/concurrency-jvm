package hle.org.pool;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A generic, thread-safe resource pool that manages the lifecycle of pooled resources.
 * This class provides a high-level API over Apache Commons Pool2.
 * 
 * <p>Key features:
 * <ul>
 *   <li>Controlled concurrency through pool size limits</li>
 *   <li>Automatic resource validation and cleanup</li>
 *   <li>Support for blocking when pool is exhausted (prevents thread starvation)</li>
 *   <li>Easy integration with try-with-resources pattern</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * ResourcePool<MyClient> pool = new ResourcePool<>(
 *     () -> new MyClient("config"),
 *     ResourcePoolConfig.builder()
 *         .maxPoolSize(10)
 *         .build()
 * );
 * 
 * // Execute an operation using a pooled resource
 * String result = pool.execute(client -> client.doSomething());
 * 
 * // Don't forget to close the pool when done
 * pool.close();
 * }</pre>
 * 
 * @param <R> the type of PooledResource managed by this pool
 */
public class ResourcePool<R extends PooledResource<?>> implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ResourcePool.class);
    
    private final GenericObjectPool<R> internalPool;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new resource pool with the given supplier and configuration.
     * 
     * @param resourceSupplier supplier that creates new resource instances
     * @param config pool configuration
     */
    public ResourcePool(Supplier<R> resourceSupplier, ResourcePoolConfig config) {
        PooledResourceFactory<R> factory = new PooledResourceFactory<>(resourceSupplier);
        GenericObjectPoolConfig<R> poolConfig = createPoolConfig(config);
        this.internalPool = new GenericObjectPool<>(factory, poolConfig);
    }

    /**
     * Creates a new resource pool with default configuration.
     * 
     * @param resourceSupplier supplier that creates new resource instances
     */
    public ResourcePool(Supplier<R> resourceSupplier) {
        this(resourceSupplier, ResourcePoolConfig.defaultConfig());
    }

    private GenericObjectPoolConfig<R> createPoolConfig(ResourcePoolConfig config) {
        GenericObjectPoolConfig<R> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMinIdle(config.getMinPoolSize());
        poolConfig.setMaxIdle(config.getMaxPoolSize());
        poolConfig.setMaxTotal(config.getMaxPoolSize());
        poolConfig.setMaxWait(config.getMaxWaitTime());
        poolConfig.setMinEvictableIdleDuration(config.getMaxIdleTime());
        poolConfig.setTestOnBorrow(config.isTestOnBorrow());
        poolConfig.setTestOnReturn(config.isTestOnReturn());
        poolConfig.setTestWhileIdle(config.isTestWhileIdle());
        poolConfig.setTimeBetweenEvictionRuns(config.getTimeBetweenEvictionRuns());
        poolConfig.setBlockWhenExhausted(config.isBlockWhenExhausted());
        // Use LIFO for better cache locality
        poolConfig.setLifo(true);
        // Enable JMX for monitoring in production
        poolConfig.setJmxEnabled(true);
        return poolConfig;
    }

    /**
     * Borrows a resource from the pool, executes the given function, and returns the resource.
     * This is the primary method for using pooled resources.
     * 
     * @param operation the operation to execute with the borrowed resource
     * @param <T> the return type of the operation
     * @return the result of the operation
     * @throws ResourcePoolException if the resource cannot be borrowed or the operation fails
     * @throws NullPointerException if operation is null
     */
    public <T> T execute(Function<R, T> operation) {
        Objects.requireNonNull(operation, "operation cannot be null");
        
        if (closed.get()) {
            throw new ResourcePoolException("Pool is closed");
        }

        R resource = null;
        boolean valid = true;
        try {
            resource = internalPool.borrowObject();
            return operation.apply(resource);
        } catch (Exception e) {
            // Note: We do not set valid=false here. We rely on resource.isValid() in returnResource
            // to determine if the resource should be invalidated. This allows reusing resources
            // even if the operation failed due to business logic errors.
            if (e instanceof ResourcePoolException) {
                throw (ResourcePoolException) e;
            }
            throw new ResourcePoolException("Failed to execute operation with pooled resource", e);
        } finally {
            if (resource != null) {
                returnResource(resource, valid);
            }
        }
    }

    /**
     * Borrows a resource from the pool with a custom timeout.
     * 
     * @param timeout maximum time to wait for a resource
     * @param operation the operation to execute
     * @param <T> the return type
     * @return the result of the operation
     * @throws NullPointerException if timeout or operation is null
     */
    public <T> T executeWithTimeout(Duration timeout, Function<R, T> operation) {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        
        if (closed.get()) {
            throw new ResourcePoolException("Pool is closed");
        }

        R resource = null;
        boolean valid = true;
        try {
            resource = internalPool.borrowObject(timeout.toMillis());
            return operation.apply(resource);
        } catch (Exception e) {
            // Note: We do not set valid=false here. We rely on resource.isValid() in returnResource
            // to determine if the resource should be invalidated.
            if (e instanceof ResourcePoolException) {
                throw (ResourcePoolException) e;
            }
            throw new ResourcePoolException("Failed to execute operation with pooled resource", e);
        } finally {
            if (resource != null) {
                returnResource(resource, valid);
            }
        }
    }

    private void returnResource(R resource, boolean valid) {
        try {
            if (valid && resource.isValid()) {
                internalPool.returnObject(resource);
            } else {
                // Invalidate the resource if it's no longer valid
                try {
                    internalPool.invalidateObject(resource);
                } catch (Exception e) {
                    // Best effort - resource will be destroyed
                    logger.warn("Failed to invalidate resource: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            // Log but don't throw - we don't want to mask the original exception
            logger.warn("Failed to return resource to pool: {}", e.getMessage());
        }
    }

    /**
     * Gets the number of resources currently borrowed from the pool.
     */
    public int getActiveCount() {
        return internalPool.getNumActive();
    }

    /**
     * Gets the number of idle resources in the pool.
     */
    public int getIdleCount() {
        return internalPool.getNumIdle();
    }

    /**
     * Gets the total number of resources (active + idle).
     */
    public int getTotalCount() {
        return getActiveCount() + getIdleCount();
    }

    /**
     * Gets the maximum pool size.
     */
    public int getMaxPoolSize() {
        return internalPool.getMaxTotal();
    }

    /**
     * Gets the number of threads waiting for a resource.
     */
    public int getWaitingCount() {
        return internalPool.getNumWaiters();
    }

    /**
     * Closes the pool and releases all resources.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            internalPool.close();
        }
    }

    /**
     * Returns pool statistics as a formatted string.
     */
    public String getStats() {
        return String.format("ResourcePool[active=%d, idle=%d, waiting=%d, max=%d]",
                getActiveCount(), getIdleCount(), getWaitingCount(), getMaxPoolSize());
    }
}
