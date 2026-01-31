package hle.org;

/**
 * A generic resource pool interface that manages borrowing and releasing of pooled resources.
 *
 * @param <T> the type of resource managed by this pool
 */
public interface ResourcePool<T> extends AutoCloseable {

    /**
     * Borrows a resource from the pool.
     *
     * @return a resource from the pool
     * @throws Exception if the resource cannot be borrowed
     */
    T borrow() throws Exception;

    /**
     * Releases a resource back to the pool.
     *
     * @param resource the resource to release (may be null, in which case this is a no-op)
     */
    void release(T resource);

    /**
     * Returns the number of currently active (borrowed) resources.
     *
     * @return the number of active resources
     */
    int getNumActive();

    /**
     * Returns the number of idle resources in the pool.
     *
     * @return the number of idle resources
     */
    int getNumIdle();

    /**
     * Returns the maximum total number of resources allowed in the pool.
     *
     * @return the maximum total resources
     */
    int getMaxTotal();

    /**
     * Returns the maximum number of idle resources allowed in the pool.
     *
     * @return the maximum idle resources
     */
    int getMaxIdle();

    /**
     * Returns the minimum number of idle resources to maintain in the pool.
     *
     * @return the minimum idle resources
     */
    int getMinIdle();

    /**
     * Closes the pool and releases all resources.
     */
    @Override
    void close();
}
