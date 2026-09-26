package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Discrete-event model of one instance's lease timeline (spec §11.1), the evidence for B2 and E5. Time runs
 * in 10ms steps, and every next round start comes from the production {@link RenewalSchedule}.
 *
 * <p>The search is exhaustive over these choices of the adversary, not over every behaviour: a successful
 * renewal round lasts 0 or W and writes the lease at its start or its end; a round fails or not as the
 * {@link Faults} allow; the one failed round of {@link OneFailedRound} takes 0 or W; a failed outage round takes
 * the durations {@link FailedRounds} lists. A new claim is registered at every step of a round gap after the
 * start of a round whose snapshot missed it, with its lease written 0, G, W or W + G before registration: the
 * extremes of the claim operation (write at c or c + W, acknowledgement at the write or c + W, registration at
 * the acknowledgement or G later). A claim is lost when a renewal write lands at or after its lease expiry, the
 * moment another instance may claim it.
 *
 * <p>A failed outage round may take any time up to W, but by default ({@link FailedRounds#REDUCED}) the model
 * reduces it to the earliest it can fail, W, or the duration that makes the next round start on the outage's
 * last step. That reduction is not proven: LeaseSimulationTest backs it by comparing it with every duration
 * ({@link FailedRounds#EVERY_STEP}) on small configurations.
 *
 * <p>A claim's future depends only on its registration and lease expiry relative to the current round start
 * and on the fault state, and an earlier expiry is never better for it. So the search keeps, per
 * (registration, fault state), only the earliest expiry seen, which keeps it small without losing a case. For
 * the same reason the rounds an outage fails are played as one choice: they write nothing, and a claim joins
 * the snapshot of the first round that starts after its registration whether the rounds before it failed or
 * not, so all that matters is how many steps pass until the first round start after the outage.
 */
final class LeaseSimulation {

    static final Duration STEP = Duration.ofMillis(10);

    /** Which claims the search starts from. */
    enum Start { NEW_CLAIMS, MAINTAINED_CLAIMS }

    /** How long a round that an {@link Outage} fails may take. */
    enum FailedRounds {
        /**
         * A failed round fails as early as it can, at W, or at the time that makes the next round start on the
         * outage's last step (aligned). As early as it can is 0 for a round that starts while the outage is on,
         * and the later of its start and the outage's start for the round the outage begins in.
         */
        REDUCED,
        /**
         * Every duration: the round the outage begins in fails at every step from the later of its start and the
         * outage's start to W, and a round that starts while the outage is on takes every duration from 0 to W.
         * Only for small configurations.
         */
        EVERY_STEP
    }

    sealed interface Faults permits OneFailedRound, Outage {
    }

    /** Exactly one renewal round fails, whichever the adversary picks. */
    record OneFailedRound() implements Faults {
    }

    /**
     * One Db2 outage of at most this length, beginning at any step. It fails every round it overlaps,
     * including one it begins in a step before that round ends, and a failed round takes up to W. The first
     * round it fails may start before or after it begins, so every shorter outage is covered too.
     */
    record Outage(Duration length) implements Faults {

        Outage {
            Objects.requireNonNull(length, "length");
            if (length.isNegative()) {
                throw new IllegalArgumentException("length must not be negative: " + length);
            }
        }
    }

    // The fault state at a round start, an int whose meaning depends on the fault model.
    // OneFailedRound: FAILURE_LEFT until the failed round, then NO_FAILURE_LEFT.
    // Outage: OUTAGE_NOT_STARTED, then OUTAGE_OVER. No round of the search starts while the outage is on, because
    // the rounds it fails are one choice (outageChoices); building that choice identifies a round that starts
    // while the outage is on by its fault: the steps from the round's start to the outage's end, always positive.
    private static final int FAILURE_LEFT = 1;
    private static final int NO_FAILURE_LEFT = 0;
    private static final int OUTAGE_NOT_STARTED = -1;
    private static final int OUTAGE_OVER = 0;

    private static final long STEP_NANOS = STEP.toNanos();
    /** Registration value of a claim that is in this round's snapshot. */
    private static final int COVERED = -1;
    private static final int NO_WRITE = -1;
    private static final int[] NO_WRITES = new int[0];

    private final int w;
    private final int g;
    private final int lease;
    private final Faults faults;
    private final FailedRounds failedRounds;
    /** Steps from a round's start to the next round's start, by [succeeded][duration]. */
    private final int[][] nextStart;
    /** The choices while the fault is still to come (FAILURE_LEFT, OUTAGE_NOT_STARTED). */
    private final List<Choice> choicesBeforeFault;
    /** The choices once it is over (NO_FAILURE_LEFT, OUTAGE_OVER). */
    private final List<Choice> choicesAfterFault;

    LeaseSimulation(LeaseTiming timing, Faults faults) {
        this(timing, faults, FailedRounds.REDUCED);
    }

    LeaseSimulation(LeaseTiming timing, Faults faults, FailedRounds failedRounds) {
        this.faults = Objects.requireNonNull(faults, "faults");
        this.failedRounds = Objects.requireNonNull(failedRounds, "failedRounds");
        this.w = steps(timing.worstCaseOperation());
        this.g = steps(timing.registrationAllowance());
        this.lease = steps(timing.lease());
        RenewalSchedule schedule = new RenewalSchedule(timing.renewInterval(), timing.renewRetryDelay());
        this.nextStart = new int[2][w + 1];
        for (int duration = 0; duration <= w; duration++) {
            for (boolean succeeded : new boolean[] {false, true}) {
                nextStart[succeeded ? 1 : 0][duration] =
                        steps(Duration.ofNanos(schedule.next(0, duration * STEP_NANOS, succeeded)));
            }
        }
        switch (faults) {
            case OneFailedRound _ -> {
                this.choicesBeforeFault = oneFailureChoices(FAILURE_LEFT);
                this.choicesAfterFault = oneFailureChoices(NO_FAILURE_LEFT);
            }
            case Outage(Duration length) -> {
                List<Choice> before = successes(OUTAGE_NOT_STARTED);
                before.addAll(outageChoices(steps(length)));
                this.choicesBeforeFault = before;
                this.choicesAfterFault = successes(OUTAGE_OVER);
            }
        }
    }

    /** One interleaving that loses a claim, described step by step, or empty if none does. */
    Optional<String> findLoss(Start start) {
        Search search = new Search();
        int fault = switch (faults) {
            case OneFailedRound _ -> FAILURE_LEFT;
            case Outage _ -> OUTAGE_NOT_STARTED;
        };
        if (start == Start.NEW_CLAIMS) {
            int gap = Math.max(next(w, true), next(w, false));
            for (int registration = 0; registration < gap; registration++) {
                for (int writtenBefore : distinct(0, g, w, w + g)) {
                    search.offer(new State(registration, fault), registration - writtenBefore + lease,
                            Path.start("new claim registered " + time(registration) + " after a round start whose "
                                    + "snapshot missed it, lease written " + time(writtenBefore) + " before registration"));
                }
            }
        } else {
            for (int duration : distinct(0, w)) {
                for (int write : distinct(0, duration)) {
                    search.offer(new State(COVERED, fault), write + lease - next(duration, true),
                            Path.start("claim renewed by a " + time(duration) + " round writing at +" + time(write)));
                }
            }
        }
        return search.run();
    }

    private final class Search {

        private final Map<State, Visit> earliest = new HashMap<>();
        private final ArrayDeque<Queued> queue = new ArrayDeque<>();
        private String loss;

        void offer(State state, int expiry, Path path) {
            offer(state, expiry, path, null, NO_WRITE);
        }

        // Records the state if its expiry is the earliest yet; the path is extended only then.
        private void offer(State state, int expiry, Path previous, Choice choice, int write) {
            if (loss != null) {
                return;
            }
            if (expiry <= 0) {
                loss = extend(previous, choice, write).render()
                        + " -> the lease expired before the next round started: claim lost";
                return;
            }
            Visit known = earliest.get(state);
            if (known == null || expiry < known.expiry()) {
                earliest.put(state, new Visit(expiry, extend(previous, choice, write)));
                queue.add(new Queued(state, expiry));
            }
        }

        private static Path extend(Path previous, Choice choice, int write) {
            return choice == null ? previous : new Path(previous, choice, write, null);
        }

        Optional<String> run() {
            while (loss == null && !queue.isEmpty()) {
                Queued queued = queue.poll();
                State state = queued.state();
                Visit visit = earliest.get(state);
                if (visit.expiry() != queued.expiry()) {
                    continue; // improved since it was queued; the newer entry expands it
                }
                for (Choice choice : choices(state.fault())) {
                    play(state, visit, choice);
                    if (loss != null) {
                        break;
                    }
                }
            }
            return Optional.ofNullable(loss);
        }

        private void play(State state, Visit visit, Choice choice) {
            int next = choice.next();
            boolean covered = state.registration() == COVERED;
            int registration = covered || state.registration() - next < 0 ? COVERED : state.registration() - next;
            State following = new State(registration, choice.fault());
            if (!choice.succeeded() || !covered) {
                offer(following, visit.expiry() - next, visit.path(), choice, NO_WRITE);
                return;
            }
            for (int write : choice.writes()) {
                if (write >= visit.expiry()) {
                    loss = extend(visit.path(), choice, write).render()
                            + " -> the write is at or after the lease expiry at +" + time(visit.expiry()) + ": claim lost";
                    return;
                }
                offer(following, write + lease - next, visit.path(), choice, write);
            }
        }
    }

    /** Every choice for the next round, given the fault state at its start. */
    private List<Choice> choices(int fault) {
        return switch (faults) {
            case OneFailedRound _ -> fault == FAILURE_LEFT ? choicesBeforeFault : choicesAfterFault;
            case Outage _ -> fault == OUTAGE_NOT_STARTED ? choicesBeforeFault : choicesAfterFault;
        };
    }

    private List<Choice> successes(int fault) {
        List<Choice> choices = new ArrayList<>();
        for (int duration : distinct(0, w)) {
            choices.add(new Choice(true, next(duration, true), fault, distinct(0, duration),
                    "a " + time(duration) + " round succeeds"));
        }
        return choices;
    }

    private List<Choice> oneFailureChoices(int fault) {
        List<Choice> choices = successes(fault);
        if (fault == FAILURE_LEFT) {
            for (int duration : distinct(0, w)) {
                choices.add(new Choice(false, next(duration, false), NO_FAILURE_LEFT, NO_WRITES,
                        "a " + time(duration) + " round fails"));
            }
        }
        return choices;
    }

    /**
     * An outage that overlaps this round, played to the first round start after it: one choice per number of
     * steps to that start, described by the first way found to get there.
     */
    private List<Choice> outageChoices(int outage) {
        if (outage == 0) {
            return List.of(); // an empty outage overlaps no round
        }
        // The outage ends a step after this round starts at the earliest, and at the latest begins a step before
        // a round of W ends.
        int lastEnd = w - 1 + outage;
        List<Map<Integer, Integer>> inOutage = inOutageContinuations(lastEnd);
        Map<Integer, Choice> byNext = new LinkedHashMap<>();
        for (int end = 1; end <= lastEnd; end++) {
            int earliest = Math.max(0, end - outage);
            int[] failures = switch (failedRounds) {
                case REDUCED -> distinct(earliest, w, aligned(end, earliest));
                case EVERY_STEP -> IntStream.rangeClosed(earliest, w).toArray();
            };
            for (int failure : failures) {
                int retry = next(failure, false);
                String begins = "an outage ending " + time(end) + " after the round start fails it at +" + time(failure);
                if (retry >= end) {
                    byNext.putIfAbsent(retry, new Choice(false, retry, OUTAGE_OVER, NO_WRITES, begins));
                    continue;
                }
                for (int after : inOutage.get(end - retry).keySet()) {
                    int next = retry + after;
                    if (!byNext.containsKey(next)) {
                        byNext.put(next, new Choice(false, next, OUTAGE_OVER, NO_WRITES,
                                begins + describeInOutage(inOutage, end - retry, after)));
                    }
                }
            }
        }
        return List.copyOf(byNext.values());
    }

    /**
     * For a round that starts while the outage is on, indexed by its fault: every number of steps from its start
     * to the first round start after the outage, mapped to the duration this round takes on the first way found.
     */
    private List<Map<Integer, Integer>> inOutageContinuations(int maxFault) {
        List<Map<Integer, Integer>> continuations = new ArrayList<>(List.of(Map.of()));
        for (int fault = 1; fault <= maxFault; fault++) {
            int[] durations = switch (failedRounds) {
                case REDUCED -> distinct(0, w, aligned(fault, 0));
                case EVERY_STEP -> IntStream.rangeClosed(0, w).toArray();
            };
            Map<Integer, Integer> continuation = new LinkedHashMap<>();
            for (int duration : durations) {
                int retry = next(duration, false);
                if (retry >= fault) {
                    continuation.putIfAbsent(retry, duration);
                } else {
                    for (int after : continuations.get(fault - retry).keySet()) {
                        continuation.putIfAbsent(retry + after, duration);
                    }
                }
            }
            continuations.add(continuation);
        }
        return continuations;
    }

    private String describeInOutage(List<Map<Integer, Integer>> inOutage, int fault, int toNextRound) {
        StringBuilder description = new StringBuilder();
        for (int left = fault, steps = toNextRound; ; ) {
            int duration = inOutage.get(left).get(steps);
            description.append(" -> a ").append(time(duration)).append(" round fails in the outage");
            int retry = next(duration, false);
            if (retry >= left) {
                return description.toString();
            }
            left -= retry;
            steps -= retry;
        }
    }

    /**
     * The failed-round duration, at least earliest, that makes the next round start on the outage's last step,
     * the latest a round can start in it; end is the steps from the round's start to the outage's end.
     */
    private int aligned(int end, int earliest) {
        return Math.clamp(end - 1 - next(0, false), earliest, w);
    }

    private int next(int duration, boolean succeeded) {
        return nextStart[succeeded ? 1 : 0][duration];
    }

    private static int steps(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos % STEP_NANOS != 0) {
            throw new IllegalArgumentException(duration + " is not a whole number of " + STEP + " steps");
        }
        return Math.toIntExact(nanos / STEP_NANOS);
    }

    private static int[] distinct(int... values) {
        return IntStream.of(values).distinct().sorted().toArray();
    }

    private static String time(int steps) {
        return Durations.seconds(STEP.multipliedBy(steps));
    }

    /** registration: steps from this round's start until the claim is registered, or COVERED. */
    private record State(int registration, int fault) {
    }

    /** expiry: the claim's lease expiry, in steps after this round's start. */
    private record Visit(int expiry, Path path) {
    }

    private record Queued(State state, int expiry) {
    }

    /** How the search reached a state; described only when a loss is reported. */
    private record Path(Path previous, Choice choice, int write, String start) {

        static Path start(String description) {
            return new Path(null, null, NO_WRITE, description);
        }

        String render() {
            List<String> steps = new ArrayList<>();
            for (Path path = this; path != null; path = path.previous()) {
                if (path.start() != null) {
                    steps.add(path.start());
                } else {
                    String write = path.write() == NO_WRITE ? "" : ", writing at +" + time(path.write());
                    steps.add(path.choice().description() + write);
                }
            }
            Collections.reverse(steps);
            return String.join(" -> ", steps);
        }
    }

    /**
     * One choice for the next round, or for all the rounds an outage fails: next is the steps from this round's
     * start to the round start that follows it, fault the fault state there, and writes the steps at which a
     * successful round may write the lease.
     */
    private record Choice(boolean succeeded, int next, int fault, int[] writes, String description) {
    }
}
