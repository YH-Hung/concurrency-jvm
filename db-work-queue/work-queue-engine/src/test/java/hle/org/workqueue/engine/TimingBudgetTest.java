package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimingBudgetTest {

    /** concurrency + 4 with the default concurrency of 16. */
    private static final int DEFAULT_POOL_SIZE = 20;

    @Test
    void theDefaultConfigPassesWithTheSpecTimingValues() {
        TimingBudget budget = TimingBudget.check(new WorkQueueProperties(), DEFAULT_POOL_SIZE);

        assertThat(budget.worstCaseOperation()).isEqualTo(ofSeconds(18));
        assertThat(budget.leasePreservationTarget()).isEqualTo(ofSeconds(8));
    }

    @Test
    void theItConfigPassesWithTheSpecTimingValues() {
        TimingBudget budget = TimingBudget.check(ItConfig.properties(), ItConfig.POOL_SIZE);

        assertThat(budget.worstCaseOperation()).isEqualTo(ofMillis(5500));
        assertThat(budget.leasePreservationTarget()).isEqualTo(ofMillis(2100));
    }

    @Test
    void b1RejectsALockWaitThatIsNotShorterThanTheTransaction() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setLockWait(ofSeconds(5));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B1")
                .hasMessageContaining("5s >= 5s")
                .hasMessageNotContaining("B2");
    }

    @Test
    void b1AcceptsALockWaitJustShorterThanTheTransaction() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setLockWait(ofSeconds(4));

        TimingBudget.check(properties, DEFAULT_POOL_SIZE);
    }

    @Test
    void b2RejectsTheSpecExample() {
        // I = 15s, W = 5s, d = 1s, G = 1s, L = 26s: 15 + 15 + 1 + 1 = 32, not < 26
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setPoolWait(ofSeconds(1));
        properties.getDb().setLogin(ofSeconds(1));
        properties.getDb().setTransaction(ofSeconds(2));
        properties.getDb().setRead(ofSeconds(1));
        properties.getDb().setLockWait(ofSeconds(1));
        properties.setLeaseDuration(ofSeconds(26));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B2")
                .hasMessageContaining("32s >= 26s")
                .hasMessageNotContaining("B4");
    }

    @Test
    void b2RejectsTheDefaultTimeoutsWithA60sLease() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setLeaseDuration(ofSeconds(60));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B2")
                .hasMessageContaining("74s >= 60s");
    }

    @Test
    void b3RejectsAPoolWithoutFourSpareConnections() {
        assertThatThrownBy(() -> TimingBudget.check(new WorkQueueProperties(), 19))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B3")
                .hasMessageContaining("19 < 20");
    }

    @Test
    void b3DoesNotOverflowForAHugeConcurrency() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setConcurrency(Integer.MAX_VALUE);

        assertThatThrownBy(() -> TimingBudget.check(properties, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B3")
                .hasMessageContaining("1 < 2147483651");
    }

    @Test
    void b4RejectsAProcessingTimeThatCutsOffASlowHealthyTask() {
        // 30s + (3 + 1)·18s + 3·1s = 105s
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setMaxProcessingTime(ofSeconds(104));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B4")
                .hasMessageContaining("104s < 105s");

        properties.setMaxProcessingTime(ofSeconds(105));
        TimingBudget.check(properties, DEFAULT_POOL_SIZE);
    }

    @Test
    void b5RejectsAStalenessLimitAHealthyIdleInstanceCanReach() {
        // 1.5·1s + 18s = 19.5s
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setDbStalenessLimit(ofMillis(19_500));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B5")
                .hasMessageContaining("19.5s <= 19.5s");
    }

    @Test
    void namesEveryViolatedConstraint() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setLeaseDuration(ofSeconds(60));

        assertThatThrownBy(() -> TimingBudget.check(properties, 19))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B2")
                .hasMessageContaining("B3");
    }

    @Test
    void rejectsOutOfRangeSettingsBeforeCheckingTheConstraints() {
        WorkQueueProperties zeroInterval = new WorkQueueProperties();
        zeroInterval.setRenewInterval(Duration.ZERO);
        WorkQueueProperties negativeRetries = new WorkQueueProperties();
        negativeRetries.setCompletionRetries(-1);

        assertThatThrownBy(() -> TimingBudget.check(zeroInterval, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("renew-interval");
        assertThatThrownBy(() -> TimingBudget.check(negativeRetries, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completion-retries");
        assertThatThrownBy(() -> TimingBudget.check(new WorkQueueProperties(), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pool size");
    }

    @Test
    void rejectsALeaseThatIsNotAWholeNumberOfSeconds() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setLeaseDuration(ofMillis(100_500));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease-duration");
    }
}
