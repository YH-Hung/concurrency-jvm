package hle.org;

/**
 * A task that uses a pooled resource without returning a result.
 * This is similar to {@link Runnable} but accepts a resource and can throw checked exceptions.
 *
 * @param <T> the type of resource used by this task
 */
@FunctionalInterface
public interface ResourceTask<T> {

    /**
     * Executes the task using the provided resource.
     *
     * @param resource the resource to use
     * @throws Exception if the task fails
     */
    void execute(T resource) throws Exception;
}
