package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * One backlog sample (spec §9.6), taken by one query on the Db2 clock. DONE rows are not counted: they only
 * accumulate, and no alert reads them.
 *
 * @param pending          PENDING rows, claimable now or waiting out retry-backoff
 * @param claimed          CLAIMED rows, live or expired
 * @param failed           FAILED rows, waiting for an operator's replay
 * @param expiredClaims    CLAIMED rows whose lease ended more than one lease ago: nobody is picking them up
 * @param oldestPendingAge how long the oldest PENDING row that is claimable now has been claimable; zero if none
 */
public record BacklogSample(long pending, long claimed, long failed, long expiredClaims, Duration oldestPendingAge) {

    public BacklogSample {
        requireNotNegative("pending", pending);
        requireNotNegative("claimed", claimed);
        requireNotNegative("failed", failed);
        requireNotNegative("expiredClaims", expiredClaims);
        Objects.requireNonNull(oldestPendingAge, "oldestPendingAge");
        if (oldestPendingAge.isNegative()) {
            throw new IllegalArgumentException("oldestPendingAge must not be negative: " + oldestPendingAge);
        }
    }

    private static void requireNotNegative(String name, long count) {
        if (count < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + count);
        }
    }
}
