package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/** Application entry point: one handler, one datasource, configuration; Spring owns the lifecycle. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(WorkQueueProperties.class)
public final class WorkQueueAutoConfiguration {
    @Bean static WorkQueueDataSourcePostProcessor workQueueDataSourcePostProcessor(ObjectProvider<WorkQueueProperties> properties) {
        return new WorkQueueDataSourcePostProcessor(properties);
    }
    @Bean WorkItemRepository workQueueRepository(Map<String, DataSource> sources,
            Map<String, ExternalService> handlers, WorkQueueProperties properties) {
        if (handlers.size() != 1) throw new IllegalStateException("Work queue requires exactly one ExternalService bean");
        if (sources.size() != 1 || !(sources.get("dataSource") instanceof HikariDataSource)) {
            throw new IllegalStateException("Work queue requires exactly one unwrapped HikariDataSource named dataSource; lazy proxies are unsupported");
        }
        WorkQueueDataSourcePostProcessor.validate(properties);
        return new WorkItemRepository(sources.get("dataSource"), properties.getDb().toTimeouts(),
            new WorkItemRepository.Settings(properties.getLeaseDuration(), properties.getMaxAttempts(), properties.getRetryBackoff()));
    }
    /** One identity shared by processing and claiming, created only after preflight succeeds. */
    record Identity(String owner, String namespace) {}
    @Bean Identity workQueueIdentity(WorkItemRepository repository, WorkQueueProperties properties) {
        return new Identity(UUID.randomUUID().toString(), new SchemaCheck(repository).verify(properties.getExpectedNamespace()));
    }
    @Bean ItemProcessor workQueueProcessor(WorkItemRepository repository, ExternalService service,
            Identity identity, WorkQueueProperties properties) {
        return new ItemProcessor(repository, service, identity.owner(), identity.namespace(), ItemProcessor.Settings.from(properties));
    }
    @Bean QueueRunner workQueueRunner(WorkItemRepository repository, ItemProcessor processor,
            Identity identity, WorkQueueProperties properties) {
        return new QueueRunner(repository, processor::process, identity.owner(), EngineSettings.from(properties));
    }
    @Bean WorkQueueMetrics workQueueMetrics(QueueRunner runner, ItemProcessor processor, ObjectProvider<MeterRegistry> registries) {
        WorkQueueMetrics metrics = new WorkQueueMetrics(runner::snapshot, processor::callStatistics);
        registries.orderedStream().forEach(metrics::bindTo);
        return metrics;
    }
    @Bean WorkQueueHealth workQueueHealth(QueueRunner runner, WorkQueueProperties properties) {
        return new WorkQueueHealth(runner::snapshot, WorkQueueHealth.Settings.from(properties));
    }
    @Bean HealthIndicator workQueueLiveness(WorkQueueHealth health) { return health::liveness; }
    @Bean HealthIndicator workQueueReadiness(WorkQueueHealth health) { return health::readiness; }
}
