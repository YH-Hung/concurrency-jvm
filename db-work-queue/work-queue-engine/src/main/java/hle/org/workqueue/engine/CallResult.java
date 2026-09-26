package hle.org.workqueue.engine;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * What an external call returned (spec §5.4), stored as RESULT_VALUE when its claim completes. {@link #toString()}
 * does not reveal the value, because the engine never logs results.
 *
 * @param value at most {@value #MAX_VALUE_BYTES} UTF-8 bytes
 */
public record CallResult(String value) {

    /** RESULT_VALUE is VARCHAR(1000), counted in bytes. */
    public static final int MAX_VALUE_BYTES = 1000;

    public CallResult {
        Objects.requireNonNull(value, "value");
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("value must be at most " + MAX_VALUE_BYTES + " UTF-8 bytes");
        }
    }

    @Override
    public String toString() {
        return "CallResult[redacted]";
    }
}
