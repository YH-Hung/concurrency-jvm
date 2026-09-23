package hle.org.workqueue.engine;

import java.util.Objects;

/** A row returned by a claim operation. {@code claimToken} fences every later write for this claim. */
public record ClaimedItem(long id, String operationId, String payload, long claimToken) {

    public ClaimedItem {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(payload, "payload");
    }

    public ClaimKey key() {
        return new ClaimKey(id, claimToken);
    }
}
