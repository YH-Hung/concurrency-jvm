package hle.org;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for creating {@link SimulatedResource} instances.
 */
public final class SimulatedResourceFactory implements ResourceFactory<SimulatedResource> {
    private final AtomicInteger idGenerator = new AtomicInteger();

    @Override
    public SimulatedResource create() {
        return new SimulatedResource(idGenerator.incrementAndGet());
    }
}
