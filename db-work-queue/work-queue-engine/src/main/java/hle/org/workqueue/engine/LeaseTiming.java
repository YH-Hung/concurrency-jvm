package hle.org.workqueue.engine;

import java.time.Duration;

/**
 * The inputs of the lease timing argument (spec §5.3) and what follows from them: B2 and E5.
 *
 * @param renewInterval         I
 * @param worstCaseOperation    W, the worst case for one DB operation
 * @param renewRetryDelay       d
 * @param registrationAllowance G
 * @param lease                 L
 */
record LeaseTiming(Duration renewInterval, Duration worstCaseOperation, Duration renewRetryDelay,
                   Duration registrationAllowance, Duration lease) {

    LeaseTiming {
        Durations.requirePositive("renewInterval", renewInterval);
        Durations.requirePositive("worstCaseOperation", worstCaseOperation);
        Durations.requirePositive("renewRetryDelay", renewRetryDelay);
        Durations.requirePositive("registrationAllowance", registrationAllowance);
        Durations.requirePositive("lease", lease);
    }

    /** B2: every claim, including a new one, survives one failed renewal round. */
    boolean b2Holds() {
        return b2Bound().compareTo(lease) < 0;
    }

    /** {@code max(I, W) + 3W + d + G}, which B2 requires to be below L. */
    Duration b2Bound() {
        return untilFirstCoveringRoundEnds().plus(retry());
    }

    /**
     * E5: an outage up to this long loses no claim (the lease-preservation target, spec §7),
     * {@code max(d, L − max(I, W) − 4W − d − G)}, or zero when B2 does not hold. An outage of length D that
     * begins as the first round including a new claim ends (by {@code c + max(I, W) + 2W + G}) fails it, the
     * last round it fails can start just before it ends and still take W, and the retry writes by {@code d + W}
     * later, so the write lands by {@code c + max(I, W) + 4W + G + D + d}; an outage no longer than d fails at
     * most one round, which B2 covers.
     */
    Duration leasePreservationTarget() {
        if (!b2Holds()) {
            return Duration.ZERO;
        }
        Duration afterWorstRetryChain = lease.minus(longerOfIAndW()).minus(worstCaseOperation.multipliedBy(4))
                .minus(renewRetryDelay).minus(registrationAllowance);
        return afterWorstRetryChain.compareTo(renewRetryDelay) > 0 ? afterWorstRetryChain : renewRetryDelay;
    }

    // max(I, W) + 2W + G: a new claim's lease is written by c, it is registered by c + W + G, the first round
    // that includes it starts by c + W + G + max(I, W) and writes by W later.
    private Duration untilFirstCoveringRoundEnds() {
        return longerOfIAndW().plus(worstCaseOperation.multipliedBy(2)).plus(registrationAllowance);
    }

    private Duration longerOfIAndW() {
        return renewInterval.compareTo(worstCaseOperation) >= 0 ? renewInterval : worstCaseOperation;
    }

    // W + d: one failed round and the delay before its retry.
    private Duration retry() {
        return worstCaseOperation.plus(renewRetryDelay);
    }
}
