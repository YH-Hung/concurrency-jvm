package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/** Applies the queue's bounds after Boot binding, before the supported pool can open a connection. */
final class WorkQueueDataSourcePostProcessor implements BeanPostProcessor, Ordered {
    private final ObjectProvider<WorkQueueProperties> properties;
    WorkQueueDataSourcePostProcessor(ObjectProvider<WorkQueueProperties> properties) { this.properties = properties; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE; }
    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (!name.equals("dataSource")) return bean;
        if (!(bean instanceof HikariDataSource pool)) {
            throw new IllegalStateException("Work queue requires one unwrapped HikariDataSource named dataSource");
        }
        if (pool.isClosed() || pool.getHikariPoolMXBean() != null) {
            throw new IllegalStateException("Configure the work queue before the Hikari pool starts; use Boot's lazy pool construction");
        }
        WorkQueueProperties p = properties.getObject();
        validate(p);
        p.getDb().toTimeouts().applyTo(pool);
        TimingBudget.check(p, pool.getMaximumPoolSize());
        return bean;
    }
    static void validate(WorkQueueProperties p) {
        try { IdempotencyKey.requireNamespace(p.getExpectedNamespace()); }
        catch (RuntimeException e) { throw new IllegalStateException("workqueue.expected-namespace is required and must be a valid namespace"); }
        EngineSettings.from(p);
        ItemProcessor.Settings.from(p);
        new WorkItemRepository.Settings(p.getLeaseDuration(), p.getMaxAttempts(), p.getRetryBackoff());
    }
}
