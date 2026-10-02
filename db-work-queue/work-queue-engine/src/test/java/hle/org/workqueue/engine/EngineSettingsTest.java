package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineSettingsTest {
    @Test
    void settingsComeFromTheProperties() {
        assertThat(EngineSettings.from(new WorkQueueProperties())).isEqualTo(new EngineSettings(16, 20,
                ofSeconds(1), ofSeconds(30), ofSeconds(1), ofSeconds(15), ofSeconds(1), ofSeconds(120), ofSeconds(1),
                ofSeconds(30), 4, ofSeconds(20), ofSeconds(5), ofSeconds(30), 100, ofSeconds(30)));
    }

    @Test
    void settingsRejectANonPositiveDurationOrACountBelowOne() {
        Map<String, Consumer<WorkQueueProperties>> invalid = new LinkedHashMap<>();
        invalid.put("concurrency", properties -> properties.setConcurrency(0));
        invalid.put("claimBatchSize", properties -> properties.setClaimBatchSize(0));
        invalid.put("hungTaskLimit", properties -> properties.setHungTaskLimit(0));
        invalid.put("idlePollInterval", properties -> properties.setIdlePollInterval(Duration.ZERO));
        invalid.put("pollBackoffMax", properties -> properties.setPollBackoffMax(Duration.ZERO));
        invalid.put("supervisorInterval", properties -> properties.setSupervisorInterval(Duration.ZERO));
        invalid.put("hungGrace", properties -> properties.setHungGrace(ofSeconds(-1)));
        invalid.put("shutdownGrace", properties -> properties.setShutdownGrace(Duration.ZERO));
        invalid.put("shutdownCancelWait", properties -> properties.setShutdownCancelWait(Duration.ZERO));
        invalid.put("sweepInterval", properties -> properties.setSweepInterval(Duration.ZERO));
        invalid.put("sweepBatchSize", properties -> properties.setSweepBatchSize(0));
        invalid.put("backlogSampleInterval", properties -> properties.setBacklogSampleInterval(Duration.ZERO));

        invalid.forEach((name, change) -> {
            WorkQueueProperties properties = ItConfig.properties();
            change.accept(properties);
            assertThatThrownBy(() -> EngineSettings.from(properties))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        });
    }

}
