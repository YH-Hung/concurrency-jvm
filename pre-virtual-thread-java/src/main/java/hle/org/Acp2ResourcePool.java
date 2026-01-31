package hle.org;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * A {@link ResourcePool} implementation backed by Apache Commons Pool 2.
 *
 * @param <T> the type of resource managed by this pool
 */
public final class Acp2ResourcePool<T> implements ResourcePool<T> {
    private final GenericObjectPool<T> pool;
    private final ResourcePoolConfig config;

    public Acp2ResourcePool(ResourceFactory<T> factory, ResourcePoolConfig config) {
        this.config = config;
        this.pool = new GenericObjectPool<>(new FactoryAdapter<>(factory), toPoolConfig(config));
    }

    private static <T> GenericObjectPoolConfig<T> toPoolConfig(ResourcePoolConfig config) {
        GenericObjectPoolConfig<T> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getMaxTotal());
        poolConfig.setMaxIdle(config.getMaxIdle());
        poolConfig.setMinIdle(config.getMinIdle());
        poolConfig.setMaxWait(config.getMaxWait());
        poolConfig.setBlockWhenExhausted(config.isBlockWhenExhausted());
        return poolConfig;
    }

    @Override
    public T borrow() throws Exception {
        return pool.borrowObject();
    }

    @Override
    public void release(T resource) {
        if (resource != null) {
            pool.returnObject(resource);
        }
    }

    @Override
    public int getNumActive() {
        return pool.getNumActive();
    }

    @Override
    public int getNumIdle() {
        return pool.getNumIdle();
    }

    @Override
    public int getMaxTotal() {
        return config.getMaxTotal();
    }

    @Override
    public int getMaxIdle() {
        return config.getMaxIdle();
    }

    @Override
    public int getMinIdle() {
        return config.getMinIdle();
    }

    @Override
    public void close() {
        pool.close();
    }

    /**
     * Adapts a {@link ResourceFactory} to Apache Commons Pool 2's {@link BasePooledObjectFactory}.
     */
    private static final class FactoryAdapter<T> extends BasePooledObjectFactory<T> {
        private final ResourceFactory<T> factory;

        FactoryAdapter(ResourceFactory<T> factory) {
            this.factory = factory;
        }

        @Override
        public T create() {
            return factory.create();
        }

        @Override
        public PooledObject<T> wrap(T obj) {
            return new DefaultPooledObject<>(obj);
        }
    }
}
