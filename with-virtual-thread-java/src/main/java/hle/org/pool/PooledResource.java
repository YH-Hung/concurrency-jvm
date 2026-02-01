package hle.org.pool;

/**
 * Interface representing a resource that can be pooled.
 * Implementations should be stateful objects that can be reused,
 * such as CORBA clients, database connections, HTTP clients, etc.
 * 
 * <p>This interface is designed to be generic and not coupled to any
 * specific technology. It provides lifecycle methods for resource management.
 * 
 * <p>Thread Safety: Implementations are NOT required to be thread-safe,
 * as each resource will be used by only one thread at a time when borrowed
 * from the pool.
 * 
 * @param <T> the type of response this resource produces
 */
public interface PooledResource<T> extends AutoCloseable {

    /**
     * Validates whether this resource is still in a usable state.
     * Called by the pool to verify resource health.
     * 
     * @return true if the resource is valid and can be used, false otherwise
     */
    boolean isValid();

    /**
     * Resets the resource to a clean state for reuse.
     * Called when the resource is returned to the pool.
     */
    void reset();

    /**
     * Closes this resource and releases any underlying resources.
     * Called when the resource is evicted from the pool or the pool is closed.
     */
    @Override
    void close();

    /**
     * Gets a unique identifier for this resource instance.
     * Useful for logging and debugging.
     * 
     * @return a unique identifier string
     */
    String getId();
}
