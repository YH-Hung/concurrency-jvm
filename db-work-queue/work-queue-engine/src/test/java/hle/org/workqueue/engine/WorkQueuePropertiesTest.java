package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkQueuePropertiesTest {

    @Test
    void defaultsAreTheSpecDefaultColumn() {
        WorkQueueProperties properties = new WorkQueueProperties();

        assertThat(properties.getConcurrency()).isEqualTo(16);
        assertThat(properties.getClaimBatchSize()).isEqualTo(20);
        assertThat(properties.getLeaseDuration()).isEqualTo(ofSeconds(100));
        assertThat(properties.getRenewInterval()).isEqualTo(ofSeconds(15));
        assertThat(properties.getRenewRetryDelay()).isEqualTo(ofSeconds(1));
        assertThat(properties.getRegistrationAllowance()).isEqualTo(ofSeconds(1));
        assertThat(properties.getIdlePollInterval()).isEqualTo(ofSeconds(1));
        assertThat(properties.getPollBackoffMax()).isEqualTo(ofSeconds(30));
        assertThat(properties.getSweepInterval()).isEqualTo(ofSeconds(30));
        assertThat(properties.getSweepBatchSize()).isEqualTo(100);
        assertThat(properties.getSupervisorInterval()).isEqualTo(ofSeconds(1));
        assertThat(properties.getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRetryBackoff()).isEqualTo(ofSeconds(5));
        assertThat(properties.getExternalCallTimeout()).isEqualTo(ofSeconds(30));
        assertThat(properties.getCompletionRetries()).isEqualTo(3);
        assertThat(properties.getCompletionRetryDelay()).isEqualTo(ofSeconds(1));
        assertThat(properties.getMaxProcessingTime()).isEqualTo(ofSeconds(120));
        assertThat(properties.getHungGrace()).isEqualTo(ofSeconds(30));
        assertThat(properties.getHungTaskLimit()).isEqualTo(4);
        assertThat(properties.getShutdownGrace()).isEqualTo(ofSeconds(20));
        assertThat(properties.getShutdownCancelWait()).isEqualTo(ofSeconds(5));
        assertThat(properties.getBacklogSampleInterval()).isEqualTo(ofSeconds(30));
        assertThat(properties.getDbStalenessLimit()).isEqualTo(ofSeconds(90));
    }

    @Test
    void dbDefaultsAreTheDefaultTimeouts() {
        assertThat(new WorkQueueProperties().getDb().toTimeouts()).isEqualTo(DbTimeouts.defaults());
    }

    @Test
    void dbSettingsAreValidatedAsDbTimeouts() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setLogin(ofMillis(1500));

        assertThatThrownBy(() -> properties.getDb().toTimeouts())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("login");
    }

    @Test
    void bindsTheSpecPropertyNames() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "workqueue.lease-duration", "30s",
                "workqueue.renew-retry-delay", "200ms",
                "workqueue.completion-retries", "2",
                "workqueue.db.pool-wait", "500ms",
                "workqueue.db.lock-wait", "1s"));

        WorkQueueProperties properties = new Binder(source)
                .bind("workqueue", Bindable.ofInstance(new WorkQueueProperties()))
                .get();

        assertThat(properties.getLeaseDuration()).isEqualTo(ofSeconds(30));
        assertThat(properties.getRenewRetryDelay()).isEqualTo(ofMillis(200));
        assertThat(properties.getCompletionRetries()).isEqualTo(2);
        assertThat(properties.getDb().getPoolWait()).isEqualTo(ofMillis(500));
        assertThat(properties.getDb().getLockWait()).isEqualTo(ofSeconds(1));
        assertThat(properties.getConcurrency()).isEqualTo(16);
    }
}
