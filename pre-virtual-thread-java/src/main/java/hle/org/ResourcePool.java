package hle.org;

import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

public final class ResourcePool<T> implements AutoCloseable {
    private final GenericObjectPool<T> pool;
    private final GenericObjectPoolConfig<T> config;

    public ResourcePool(PooledObjectFactory<T> factory, GenericObjectPoolConfig<T> config) {
        this.config = config;
        this.pool = new GenericObjectPool<>(factory, config);
    }

    public T borrow() throws Exception {
        return pool.borrowObject();
    }

    public void release(T resource) {
        if (resource != null) {
            pool.returnObject(resource);
        }
    }

    public int getNumActive() {
        return pool.getNumActive();
    }

    public int getNumIdle() {
        return pool.getNumIdle();
    }

    public int getMaxTotal() {
        return config.getMaxTotal();
    }

    public int getMaxIdle() {
        return config.getMaxIdle();
    }

    public int getMinIdle() {
        return config.getMinIdle();
    }

    @Override
    public void close() {
        pool.close();
    }
}
