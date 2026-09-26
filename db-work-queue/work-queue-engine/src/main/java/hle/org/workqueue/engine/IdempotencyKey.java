package hle.org.workqueue.engine;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The operation identity sent downstream (spec §5.4). {@link #value()} is unambiguous: the namespace never
 * contains ':', so the first ':' always separates the two parts, and the operation id may contain more.
 * {@link #toString()} does not reveal the key, because the engine never logs idempotency keys.
 *
 * @param namespace   {@code ^[a-z0-9][a-z0-9-]{0,31}$}
 * @param operationId {@code ^[!-~]{1,64}$}: printable ASCII, no spaces
 */
public record IdempotencyKey(String namespace, String operationId) {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern OPERATION_ID = Pattern.compile("[!-~]{1,64}");

    public IdempotencyKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(operationId, "operationId");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace must match ^[a-z0-9][a-z0-9-]{0,31}$");
        }
        if (!OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId must be 1 to 64 printable ASCII characters without spaces");
        }
    }

    /** {@code NAMESPACE:OPERATION_ID}, compared exactly (case-sensitive) by the downstream. */
    public String value() {
        return namespace + ":" + operationId;
    }

    @Override
    public String toString() {
        return "IdempotencyKey[redacted]";
    }
}
