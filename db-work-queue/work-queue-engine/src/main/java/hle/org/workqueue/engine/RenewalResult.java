package hle.org.workqueue.engine;

import java.util.Collections;
import java.util.Set;

/**
 * What one renewal round found (spec §5.3). Every requested claim is in exactly one set: {@code renewed} has a new
 * lease; {@code ended} was already ended by this owner's own complete or retryOrFail, because its task finished after
 * the round took its snapshot; {@code lost} is no longer this owner's, because it was swept, revoked or re-claimed.
 * The constructor rejects a claim in more than one set, so a broken repository fails the round instead of having
 * the runner cancel a claim it also reports renewed.
 */
record RenewalResult(Set<ClaimKey> renewed, Set<ClaimKey> ended, Set<ClaimKey> lost) {

    static final RenewalResult NOTHING = new RenewalResult(Set.of(), Set.of(), Set.of());

    public RenewalResult {
        renewed = Set.copyOf(renewed);
        ended = Set.copyOf(ended);
        lost = Set.copyOf(lost);
        if (!Collections.disjoint(renewed, ended) || !Collections.disjoint(renewed, lost)
                || !Collections.disjoint(ended, lost)) {
            throw new IllegalArgumentException("a claim can be in only one of renewed, ended and lost");
        }
    }
}
