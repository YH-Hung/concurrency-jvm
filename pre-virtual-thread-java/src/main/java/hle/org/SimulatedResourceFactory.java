package hle.org;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import java.util.concurrent.atomic.AtomicInteger;

public final class SimulatedResourceFactory extends BasePooledObjectFactory<SimulatedResource> {
    private final AtomicInteger idGenerator = new AtomicInteger();

    @Override
    public SimulatedResource create() {
        return new SimulatedResource(idGenerator.incrementAndGet());
    }

    @Override
    public PooledObject<SimulatedResource> wrap(SimulatedResource obj) {
        return new DefaultPooledObject<>(obj);
    }
}
