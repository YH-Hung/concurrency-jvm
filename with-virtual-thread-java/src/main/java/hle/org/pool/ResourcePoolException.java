package hle.org.pool;

/**
 * Exception thrown when resource pool operations fail.
 */
public class ResourcePoolException extends RuntimeException {

    public ResourcePoolException(String message) {
        super(message);
    }

    public ResourcePoolException(String message, Throwable cause) {
        super(message, cause);
    }
}
