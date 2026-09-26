package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseTimingTest {

    /** IT column: I = 1s, W = 5.5s, d = 200ms, G = 200ms, L = 30s. */
    private static final LeaseTiming IT = new LeaseTiming(ofSeconds(1), ofMillis(5500), ofMillis(200), ofMillis(200), ofSeconds(30));

    /** Default column (I = 15s, W = 18s, d = 1s, G = 1s) with the given lease. */
    private static LeaseTiming defaultsWithLease(long leaseSeconds) {
        return new LeaseTiming(ofSeconds(15), ofSeconds(18), ofSeconds(1), ofSeconds(1), ofSeconds(leaseSeconds));
    }

    @Test
    void b2BoundIsMaxOfIAndWPlusThreeWPlusDPlusG() {
        assertThat(defaultsWithLease(100).b2Bound()).isEqualTo(ofSeconds(74));
        assertThat(IT.b2Bound()).isEqualTo(ofMillis(22_400));
    }

    @Test
    void b2HoldsOnlyBelowItsBound() {
        assertThat(defaultsWithLease(74).b2Holds()).isFalse();
        assertThat(defaultsWithLease(75).b2Holds()).isTrue();
    }

    @Test
    void leasePreservationTargetIsPositiveExactlyWhenB2Holds() {
        assertThat(defaultsWithLease(74).leasePreservationTarget()).isEqualTo(Duration.ZERO);
        for (long lease = 40; lease <= 160; lease++) {
            LeaseTiming timing = defaultsWithLease(lease);
            assertThat(timing.leasePreservationTarget().isPositive()).as("lease %ds", lease).isEqualTo(timing.b2Holds());
        }
    }

    @Test
    void leasePreservationTargetIsTheLeaseLeftAfterTheWorstRetryChainButAtLeastD() {
        // L − max(I, W) − 4W − d − G = L − 92s, and never below d = 1s
        assertThat(defaultsWithLease(75).leasePreservationTarget()).isEqualTo(ofSeconds(1));
        assertThat(defaultsWithLease(90).leasePreservationTarget()).isEqualTo(ofSeconds(1));
        assertThat(defaultsWithLease(93).leasePreservationTarget()).isEqualTo(ofSeconds(1));
        assertThat(defaultsWithLease(94).leasePreservationTarget()).isEqualTo(ofSeconds(2));
        assertThat(defaultsWithLease(100).leasePreservationTarget()).isEqualTo(ofSeconds(8));
        assertThat(defaultsWithLease(112).leasePreservationTarget()).isEqualTo(ofSeconds(20));
    }

    @Test
    void theItColumnSurvivesA2point1sOutage() {
        // 30s − 5.5s − 22s − 0.2s − 0.2s
        assertThat(IT.leasePreservationTarget()).isEqualTo(ofMillis(2100));
    }

    @Test
    void rejectsNonPositiveInputs() {
        assertThatThrownBy(() -> new LeaseTiming(ofSeconds(15), ofSeconds(18), ofSeconds(1), ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        assertThatThrownBy(() -> new LeaseTiming(ofSeconds(15), ofSeconds(18), Duration.ZERO, ofSeconds(1), ofSeconds(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("renewRetryDelay");
    }
}
