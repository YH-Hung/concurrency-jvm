package hle.org.workqueue.engine;

import java.time.Duration;

/**
 * The downstream the engine calls, at most once per claim (spec §5.4). Claim-token fencing protects only the
 * database: a stale owner, a retry or a replay can call again with the same key. An implementation must therefore
 * be durably idempotent on {@code key.value()}, compared exactly (case-sensitive): repeated calls apply the effect
 * at most once and return the result of the first application. A downstream that cannot do that is not supported.
 */
@FunctionalInterface
public interface ExternalService {

    /**
     * Applies this operation's effect, or returns the stored result if it was already applied.
     *
     * @param key        the operation identity; never log it
     * @param claimToken the claim making this call, for the downstream's own records
     * @param payload    the row's PAYLOAD; never log it
     * @param timeout    return or throw within this time, throwing {@link java.util.concurrent.TimeoutException}
     *                   when it expires; the engine enforces it only as a backstop (deadline cancel, hung detection)
     * @return the result to store: the same for every repeat of the key; a null return is recorded as a failed
     *         attempt
     * @throws InterruptedException if interrupted; implementations should respond to interruption, and the engine
     *                              then writes nothing
     * @throws Exception            any other failure: the engine records a failed attempt, unless the thread's
     *                              interrupt status is set, in which case it writes nothing
     */
    CallResult call(IdempotencyKey key, long claimToken, String payload, Duration timeout) throws Exception;
}
