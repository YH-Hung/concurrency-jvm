package hle.org;

/**
 * A factory for creating pooled resources.
 * This is a simplified factory interface that hides pool implementation details.
 *
 * @param <T> the type of resource created by this factory
 */
@FunctionalInterface
public interface ResourceFactory<T> {

    /**
     * Creates a new resource instance.
     *
     * @return a new resource
     */
    T create();
}
