package hle.org.workqueue.engine;

import hle.org.workqueue.engine.LeaseSimulation.FailedRounds;
import hle.org.workqueue.engine.LeaseSimulation.OneFailedRound;
import hle.org.workqueue.engine.LeaseSimulation.Outage;
import hle.org.workqueue.engine.LeaseSimulation.Start;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static hle.org.workqueue.engine.LeaseSimulation.STEP;
import static hle.org.workqueue.engine.LeaseSimulation.Start.MAINTAINED_CLAIMS;
import static hle.org.workqueue.engine.LeaseSimulation.Start.NEW_CLAIMS;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evidence for B2 and E5 (spec §11.1): a finite grid of configurations, not a proof. */
class LeaseSimulationTest {

    private static final LeaseTiming DEFAULTS = TimingBudget.check(new WorkQueueProperties(), 20).leaseTiming();
    private static final LeaseTiming IT = TimingBudget.check(ItConfig.properties(), ItConfig.POOL_SIZE).leaseTiming();

    @Test
    void theDefaultAndItConfigsSurviveOneFailedRound() {
        for (LeaseTiming timing : List.of(DEFAULTS, IT)) {
            LeaseSimulation simulation = new LeaseSimulation(timing, new OneFailedRound());
            assertThat(simulation.findLoss(NEW_CLAIMS)).as("%s", timing).isEmpty();
            assertThat(simulation.findLoss(MAINTAINED_CLAIMS)).as("%s", timing).isEmpty();
        }
    }

    @Test
    void theDefaultAndItConfigsSurviveAnOutageUpToE5AndNoLonger() {
        for (LeaseTiming timing : List.of(DEFAULTS, IT)) {
            assertOutageTargetHoldsAndIsTight(timing);
        }
    }

    @Test
    void theRevision8DefaultLeaseLosesAClaimToA1point2sOutage() {
        LeaseTiming lease90s = withLease(DEFAULTS, ofSeconds(90));

        assertThat(new LeaseSimulation(lease90s, new Outage(ofMillis(1200))).findLoss(NEW_CLAIMS)).isPresent();
        assertThat(new LeaseSimulation(lease90s, new Outage(ofSeconds(1))).findLoss(NEW_CLAIMS)).isEmpty();
    }

    @Test
    void theRevision9TargetLosesAClaimToAn8point02sOutage() {
        // Revision 9 claimed 20s: a fast-failing retry chain whose last round takes W loses a claim after 8.02s.
        assertThat(new LeaseSimulation(DEFAULTS, new Outage(ofMillis(8020))).findLoss(NEW_CLAIMS)).isPresent();
        assertThat(new LeaseSimulation(DEFAULTS, new Outage(ofSeconds(8))).findLoss(NEW_CLAIMS)).isEmpty();
    }

    @Test
    void theOneFailureGridIncludesConfigsB2Rejects() {
        List<LeaseTiming> grid = oneFailureGrid();

        assertThat(grid).hasSize(180);
        assertThat(grid).filteredOn(timing -> !timing.b2Holds()).hasSize(36);
    }

    @ParameterizedTest
    @MethodSource("oneFailureGrid")
    void oneFailedRoundLosesAClaimExactlyWhenB2Rejects(LeaseTiming timing) {
        LeaseSimulation simulation = new LeaseSimulation(timing, new OneFailedRound());

        if (timing.b2Holds()) {
            assertThat(simulation.findLoss(NEW_CLAIMS)).isEmpty();
            assertThat(simulation.findLoss(MAINTAINED_CLAIMS)).isEmpty();
        } else {
            assertThat(simulation.findLoss(NEW_CLAIMS)).isPresent();
        }
    }

    @ParameterizedTest
    @MethodSource("outageGrid")
    void noOutageUpToE5LosesAClaimAndOneTwoStepsLongerDoes(LeaseTiming timing) {
        assertOutageTargetHoldsAndIsTight(timing);
    }

    @ParameterizedTest
    @MethodSource("crossCheckConfigs")
    void theReducedFailedRoundsFindTheSameFirstLossAsEveryDuration(LeaseTiming timing) {
        Optional<Duration> newClaimLoss = firstLosingOutage(timing, NEW_CLAIMS, FailedRounds.REDUCED);

        assertThat(newClaimLoss).as("%s", timing).isPresent();
        assertThat(newClaimLoss).as("%s, new claims", timing)
                .isEqualTo(firstLosingOutage(timing, NEW_CLAIMS, FailedRounds.EVERY_STEP));
        assertThat(firstLosingOutage(timing, MAINTAINED_CLAIMS, FailedRounds.REDUCED)).as("%s, maintained claims", timing)
                .isEqualTo(firstLosingOutage(timing, MAINTAINED_CLAIMS, FailedRounds.EVERY_STEP));
    }

    @Test
    void aZeroLengthOutageFailsNoRound() {
        // Renewal without failures needs max(I, W) + 2W + G = 320ms < 400ms; B2 (440ms) rejects one failed round.
        LeaseTiming timing = new LeaseTiming(ofMillis(50), ofMillis(100), ofMillis(20), ofMillis(20), ofMillis(400));

        assertThat(new LeaseSimulation(timing, new OneFailedRound()).findLoss(NEW_CLAIMS)).isPresent();
        for (FailedRounds failedRounds : FailedRounds.values()) {
            LeaseSimulation noOutage = new LeaseSimulation(timing, new Outage(Duration.ZERO), failedRounds);
            assertThat(noOutage.findLoss(NEW_CLAIMS)).as("%s, new claims", failedRounds).isEmpty();
            assertThat(noOutage.findLoss(MAINTAINED_CLAIMS)).as("%s, maintained claims", failedRounds).isEmpty();
        }
    }

    @Test
    void anOutageLengthMustNotBeNullOrNegative() {
        assertThatThrownBy(() -> new Outage(null)).isInstanceOf(NullPointerException.class).hasMessageContaining("length");
        assertThatThrownBy(() -> new Outage(ofMillis(-10))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    /**
     * Small enough to try every failed-round duration: I < W, I > W + d, a larger d, and a lease one step above
     * the B2 bound, where the round an outage begins in must be able to fail early for the retry chain to reach
     * the outage's last step. A d close to W needs the in-outage 0-duration choice, and the last config has
     * d < E5 < 2d, where E5 is conservative.
     */
    static List<LeaseTiming> crossCheckConfigs() {
        LeaseTiming shortInterval = new LeaseTiming(ofMillis(50), ofMillis(100), ofMillis(20), ofMillis(20), ofMillis(730));
        LeaseTiming longInterval = new LeaseTiming(ofMillis(200), ofMillis(100), ofMillis(20), ofMillis(20), ofSeconds(1));
        LeaseTiming longRetryDelay = new LeaseTiming(ofMillis(50), ofMillis(100), ofMillis(50), ofMillis(20), ofSeconds(1));
        return List.of(shortInterval,
                withLease(longInterval, longInterval.b2Bound().plus(ofMillis(120))),
                withLease(longRetryDelay, longRetryDelay.b2Bound().plus(ofMillis(300))),
                withLease(shortInterval, shortInterval.b2Bound().plus(STEP)),
                new LeaseTiming(ofMillis(100), ofMillis(40), ofMillis(30), ofMillis(10), ofMillis(360)),
                withLease(longRetryDelay, ofMillis(640)));
    }

    /** I × W × d × G, each with leases at, just above and well above the B2 bound. */
    static List<LeaseTiming> oneFailureGrid() {
        return grid(List.of(ofSeconds(1), ofSeconds(5), ofSeconds(15)), List.of(ofMillis(3500), ofMillis(5500), ofSeconds(9)),
                List.of(ofMillis(200), ofSeconds(1)), List.of(ofMillis(200), ofSeconds(1)), true);
    }

    /**
     * A smaller grid for outages, which branch at every step; only configs B2 accepts. I = 5s with W = 3.5s
     * covers I > W + d, where a new claim can stay out of the snapshot across a failed round.
     */
    static List<LeaseTiming> outageGrid() {
        return grid(List.of(ofSeconds(1), ofSeconds(5)), List.of(ofMillis(3500), ofSeconds(9)),
                List.of(ofMillis(200), ofSeconds(1)), List.of(ofSeconds(1)), false);
    }

    private static List<LeaseTiming> grid(List<Duration> intervals, List<Duration> operations, List<Duration> retryDelays,
                                          List<Duration> allowances, boolean includeRejected) {
        List<LeaseTiming> grid = new ArrayList<>();
        for (Duration i : intervals) {
            for (Duration w : operations) {
                for (Duration d : retryDelays) {
                    for (Duration g : allowances) {
                        Duration bound = new LeaseTiming(i, w, d, g, ofSeconds(1)).b2Bound();
                        Duration retry = w.plus(d);
                        List<Duration> leases = new ArrayList<>(List.of(bound.plus(STEP), bound.plus(retry),
                                bound.plus(retry.multipliedBy(2)).plus(ofSeconds(1))));
                        if (includeRejected) {
                            leases.addFirst(bound);
                            leases.add(2, bound.plus(STEP.multipliedBy(retry.dividedBy(STEP) / 2)));
                        }
                        for (Duration lease : leases) {
                            grid.add(new LeaseTiming(i, w, d, g, lease));
                        }
                    }
                }
            }
        }
        return grid;
    }

    private static void assertOutageTargetHoldsAndIsTight(LeaseTiming timing) {
        Duration target = timing.leasePreservationTarget();

        LeaseSimulation atTarget = new LeaseSimulation(timing, new Outage(target));
        assertThat(atTarget.findLoss(NEW_CLAIMS)).as("%s, outage %s", timing, target).isEmpty();
        assertThat(atTarget.findLoss(MAINTAINED_CLAIMS)).as("%s, outage %s", timing, target).isEmpty();

        Duration longer = target.plus(STEP.multipliedBy(2));
        assertThat(new LeaseSimulation(timing, new Outage(longer)).findLoss(NEW_CLAIMS))
                .as("%s, outage %s", timing, longer).isPresent();
    }

    /** The shortest outage, in steps from zero up to the lease, that loses a claim. */
    private static Optional<Duration> firstLosingOutage(LeaseTiming timing, Start start, FailedRounds failedRounds) {
        for (Duration outage = Duration.ZERO; outage.compareTo(timing.lease()) <= 0; outage = outage.plus(STEP)) {
            if (new LeaseSimulation(timing, new Outage(outage), failedRounds).findLoss(start).isPresent()) {
                return Optional.of(outage);
            }
        }
        return Optional.empty();
    }

    private static LeaseTiming withLease(LeaseTiming timing, Duration lease) {
        return new LeaseTiming(timing.renewInterval(), timing.worstCaseOperation(), timing.renewRetryDelay(),
                timing.registrationAllowance(), lease);
    }
}
