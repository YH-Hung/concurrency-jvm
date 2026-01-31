package hle.org.client;

/**
 * Exception thrown when remote client operations fail.
 */
public class RemoteClientException extends RuntimeException {

    private final boolean retryable;

    public RemoteClientException(String message) {
        super(message);
        this.retryable = false;
    }

    public RemoteClientException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public RemoteClientException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = false;
    }

    public RemoteClientException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns true if this error is retryable (e.g., timeout, temporary network issue).
     */
    public boolean isRetryable() {
        return retryable;
    }
}
