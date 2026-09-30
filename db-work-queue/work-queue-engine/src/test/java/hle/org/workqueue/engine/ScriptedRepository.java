package hle.org.workqueue.engine;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toSet;

/**
 * A WorkItemRepository for unit tests that never touches a database. Every operation answers from its own script,
 * in order, and is recorded. An unscripted persist fails the test with an AssertionError; an unscripted claim
 * finds nothing, an unscripted renewal renews every claim, an unscripted sweep sweeps nothing, and an unscripted
 * sample finds an empty queue. It is thread-safe: the runner's loops and task threads call it concurrently, and a
 * step runs outside any lock, so one that blocks holds up only its own caller.
 */
class ScriptedRepository extends WorkItemRepository {

    enum Operation { COMPLETE, RETRY_OR_FAIL }

    /** One persist call; {@code value} is the result value of a complete or the error of a retryOrFail. */
    record Write(Operation operation, String owner, ClaimKey claim, String value) {
    }

    private final Deque<Supplier<PersistResult>> persists = new ConcurrentLinkedDeque<>();
    private final List<Write> writes = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<List<ClaimedItem>>> claims = new ConcurrentLinkedDeque<>();
    private final List<Integer> claimSizes = new CopyOnWriteArrayList<>();
    private final Deque<Function<Set<ClaimKey>, RenewalResult>> renewals = new ConcurrentLinkedDeque<>();
    private final List<Set<ClaimKey>> renewRequests = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<Integer>> sweeps = new ConcurrentLinkedDeque<>();
    private final List<Integer> sweepSizes = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<BacklogSample>> samples = new ConcurrentLinkedDeque<>();
    private final AtomicInteger sampleCount = new AtomicInteger();

    ScriptedRepository() {
        super(new DriverManagerDataSource(), DbTimeouts.defaults(),
                new Settings(Duration.ofSeconds(100), 5, Duration.ofSeconds(5)));
    }

    ScriptedRepository thenReturn(PersistResult result) {
        return then(() -> result);
    }

    ScriptedRepository thenThrow(RuntimeException failure) {
        return then(() -> {
            throw failure;
        });
    }

    /** The next persist runs {@code step}. */
    ScriptedRepository then(Supplier<PersistResult> step) {
        persists.add(step);
        return this;
    }

    /** The next claim returns {@code items}, whatever it asked for. */
    ScriptedRepository thenClaim(ClaimedItem... items) {
        List<ClaimedItem> claimed = Arrays.asList(items);   // may hold null, to fail handle construction
        return thenClaim(() -> claimed);
    }

    ScriptedRepository thenClaimThrow(RuntimeException failure) {
        return thenClaim(() -> {
            throw failure;
        });
    }

    /** The next claim runs {@code step}. */
    ScriptedRepository thenClaim(Supplier<List<ClaimedItem>> step) {
        claims.add(step);
        return this;
    }

    /** The next renewal reports these claims lost and renews the rest. */
    ScriptedRepository thenRenewLosing(ClaimKey... lost) {
        Set<ClaimKey> lostSet = Set.of(lost);
        return thenRenew(requested -> new RenewalResult(minus(requested, lostSet), Set.of(), lostSet));
    }

    /** The next renewal reports these claims ended by their own tasks and renews the rest. */
    ScriptedRepository thenRenewEnded(ClaimKey... ended) {
        Set<ClaimKey> endedSet = Set.of(ended);
        return thenRenew(requested -> new RenewalResult(minus(requested, endedSet), endedSet, Set.of()));
    }

    ScriptedRepository thenRenewThrow(RuntimeException failure) {
        return thenRenew(requested -> {
            throw failure;
        });
    }

    /** The next renewal answers with {@code step}, given the claims it was asked to renew. */
    ScriptedRepository thenRenew(Function<Set<ClaimKey>, RenewalResult> step) {
        renewals.add(step);
        return this;
    }

    /** The next sweeps return these counts, one sweep per count. */
    ScriptedRepository thenSweep(int... counts) {
        for (int count : counts) {
            thenSweep(() -> count);
        }
        return this;
    }

    ScriptedRepository thenSweepThrow(RuntimeException failure) {
        return thenSweep(() -> {
            throw failure;
        });
    }

    /** The next sweep runs {@code step}. */
    ScriptedRepository thenSweep(Supplier<Integer> step) {
        sweeps.add(step);
        return this;
    }

    ScriptedRepository thenSample(BacklogSample sample) {
        return thenSample(() -> sample);
    }

    ScriptedRepository thenSampleThrow(RuntimeException failure) {
        return thenSample(() -> {
            throw failure;
        });
    }

    /** The next backlog sample runs {@code step}. */
    ScriptedRepository thenSample(Supplier<BacklogSample> step) {
        samples.add(step);
        return this;
    }

    List<Write> writes() {
        return List.copyOf(writes);
    }

    /** The {@code n} of every claim, in order. */
    List<Integer> claimSizes() {
        return List.copyOf(claimSizes);
    }

    /** The claims every renewal round asked to renew, in order. */
    List<Set<ClaimKey>> renewRequests() {
        return List.copyOf(renewRequests);
    }

    /** The batch size of every sweep, in order. */
    List<Integer> sweepSizes() {
        return List.copyOf(sweepSizes);
    }

    /** How many backlog samples were taken. */
    int samples() {
        return sampleCount.get();
    }

    @Override
    public List<ClaimedItem> claim(String owner, int n) {
        claimSizes.add(n);
        Supplier<List<ClaimedItem>> step = claims.poll();
        return step == null ? List.of() : step.get();
    }

    @Override
    public RenewalResult renew(String owner, Collection<ClaimKey> claims) {
        Set<ClaimKey> requested = Set.copyOf(claims);
        renewRequests.add(requested);
        Function<Set<ClaimKey>, RenewalResult> step = renewals.poll();
        return step == null ? new RenewalResult(requested, Set.of(), Set.of()) : step.apply(requested);
    }

    @Override
    public int sweep(int batchSize) {
        sweepSizes.add(batchSize);
        Supplier<Integer> step = sweeps.poll();
        return step == null ? 0 : step.get();
    }

    @Override
    public BacklogSample sampleBacklog() {
        sampleCount.incrementAndGet();
        Supplier<BacklogSample> step = samples.poll();
        return step == null ? new BacklogSample(0, 0, 0, 0, Duration.ZERO) : step.get();
    }

    @Override
    public PersistResult complete(String owner, ClaimKey claim, String resultValue) {
        return next(new Write(Operation.COMPLETE, owner, claim, resultValue));
    }

    @Override
    public PersistResult retryOrFail(String owner, ClaimKey claim, String error) {
        return next(new Write(Operation.RETRY_OR_FAIL, owner, claim, error));
    }

    private PersistResult next(Write write) {
        writes.add(write);
        Supplier<PersistResult> step = persists.poll();
        if (step == null) {
            throw new AssertionError("unscripted " + write.operation());
        }
        return step.get();
    }

    private static Set<ClaimKey> minus(Set<ClaimKey> requested, Set<ClaimKey> removed) {
        return requested.stream().filter(key -> !removed.contains(key)).collect(toSet());
    }
}
