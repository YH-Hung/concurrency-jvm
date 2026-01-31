package hle.org.pool;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import java.util.function.Supplier;

/**
 * A generic factory for creating and managing pooled resources.
 * This class bridges the gap between our {@link PooledResource} interface
 * and Apache Commons Pool2's factory requirements.
 * 
 * <p>Usage example:
 * <pre>{@code
 * PooledResourceFactory<MyClient> factory = new PooledResourceFactory<>(
 *     () -> new MyClient("config")
 * );
 * }</pre>
 * 
 * @param <R> the type of PooledResource this factory creates
 */
public class PooledResourceFactory<R extends PooledResource<?>> extends BasePooledObjectFactory<R> {

    private final Supplier<R> resourceSupplier;

    /**
     * Creates a new factory with the given resource supplier.
     * 
     * @param resourceSupplier a supplier that creates new resource instances
     */
    public PooledResourceFactory(Supplier<R> resourceSupplier) {
        this.resourceSupplier = resourceSupplier;
    }

    @Override
    public R create() throws Exception {
        return resourceSupplier.get();
    }

    @Override
    public PooledObject<R> wrap(R resource) {
        return new DefaultPooledObject<>(resource);
    }

    @Override
    public boolean validateObject(PooledObject<R> pooledObject) {
        return pooledObject.getObject().isValid();
    }

    @Override
    public void destroyObject(PooledObject<R> pooledObject) throws Exception {
        pooledObject.getObject().close();
    }

    @Override
    public void passivateObject(PooledObject<R> pooledObject) throws Exception {
        pooledObject.getObject().reset();
    }
}
