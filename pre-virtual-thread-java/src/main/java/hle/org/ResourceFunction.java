package hle.org;

/**
 * A function that uses a pooled resource and returns a result.
 * This is similar to {@link java.util.function.Function} but accepts a resource and can throw checked exceptions.
 *
 * @param <T> the type of resource used by this function
 * @param <R> the type of result returned by this function
 */
@FunctionalInterface
public interface ResourceFunction<T, R> {

    /**
     * Applies this function to the given resource.
     *
     * @param resource the resource to use
     * @return the result
     * @throws Exception if the function fails
     */
    R apply(T resource) throws Exception;
}
