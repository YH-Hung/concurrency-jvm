package hle.org.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Represents the result of an asynchronous task execution.
 * Contains either a successful result or an exception, along with metadata.
 * 
 * @param <T> the type of the result value
 */
public final class TaskResult<T> {

    private final String taskId;
    private final T value;
    private final Throwable exception;
    private final Instant startTime;
    private final Instant endTime;
    private final boolean success;

    private TaskResult(String taskId, T value, Throwable exception, 
                       Instant startTime, Instant endTime, boolean success) {
        this.taskId = taskId;
        this.value = value;
        this.exception = exception;
        this.startTime = startTime;
        this.endTime = endTime;
        this.success = success;
    }

    /**
     * Creates a successful result.
     */
    public static <T> TaskResult<T> success(String taskId, T value, 
                                             Instant startTime, Instant endTime) {
        return new TaskResult<>(taskId, value, null, startTime, endTime, true);
    }

    /**
     * Creates a failed result.
     */
    public static <T> TaskResult<T> failure(String taskId, Throwable exception,
                                             Instant startTime, Instant endTime) {
        return new TaskResult<>(taskId, null, exception, startTime, endTime, false);
    }

    public String getTaskId() {
        return taskId;
    }

    public Optional<T> getValue() {
        return Optional.ofNullable(value);
    }

    public Optional<Throwable> getException() {
        return Optional.ofNullable(exception);
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    /**
     * Gets the execution duration.
     */
    public Duration getDuration() {
        return Duration.between(startTime, endTime);
    }

    /**
     * Gets the value or throws the exception if failed.
     */
    public T getOrThrow() throws Exception {
        if (success) {
            return value;
        }
        if (exception instanceof Exception) {
            throw (Exception) exception;
        }
        throw new RuntimeException(exception);
    }

    /**
     * Gets the value or returns a default if failed.
     */
    public T getOrDefault(T defaultValue) {
        return success ? value : defaultValue;
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("TaskResult[taskId=%s, success=true, value=%s, duration=%dms]",
                    taskId, value, getDuration().toMillis());
        } else {
            String errorMessage = exception != null 
                    ? (exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName())
                    : "unknown error";
            return String.format("TaskResult[taskId=%s, success=false, error=%s, duration=%dms]",
                    taskId, errorMessage, getDuration().toMillis());
        }
    }
}
