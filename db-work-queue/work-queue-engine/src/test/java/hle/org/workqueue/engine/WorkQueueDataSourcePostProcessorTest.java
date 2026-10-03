package hle.org.workqueue.engine;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
class WorkQueueDataSourcePostProcessorTest {
    WorkQueueDataSourcePostProcessor processor() {
        WorkQueueProperties properties = new WorkQueueProperties(); properties.setExpectedNamespace("demo");
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("properties", properties);
        return new WorkQueueDataSourcePostProcessor(beans.getBeanProvider(WorkQueueProperties.class));
    }
    @Test void rejectsUnsupportedClosedAndAlreadyStartedPools() throws Exception {
        assertThatThrownBy(() -> processor().postProcessBeforeInitialization(new DriverManagerDataSource(),"dataSource"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Hikari");
        var closed = new HikariDataSource(); closed.close();
        assertThatThrownBy(() -> processor().postProcessBeforeInitialization(closed,"dataSource")).isInstanceOf(IllegalStateException.class);
        StartupDatabase.reset();
        try (var running = new HikariDataSource()) {
            running.setJdbcUrl("jdbc:queue-test:db"); running.setMaximumPoolSize(20); running.setMinimumIdle(0);
            try(var ignored = running.getConnection()) { }
            assertThatThrownBy(() -> processor().postProcessBeforeInitialization(running,"dataSource"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("before");
        }
    }
    @Test void rejectsSuppliedAndJndiDriverSourcesWhoseTimeoutsHikariCannotApply() {
        StartupDatabase.reset();
        for (boolean supplied : new boolean[]{true, false}) {
            try (var pool = new HikariDataSource()) {
                pool.setMaximumPoolSize(20);
                if (supplied) pool.setDataSource(new DriverManagerDataSource("jdbc:queue-test:db"));
                else pool.setDataSourceJNDI("java:comp/env/jdbc/workQueue");
                assertThatThrownBy(() -> processor().postProcessBeforeInitialization(pool, "dataSource"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("driver timeouts");
                assertThat(StartupDatabase.connections).hasValue(0);
            }
        }
    }
    @Test void doesNotAlterUnrelatedPools() {
        try (var pool = new HikariDataSource()) {
            long original = pool.getConnectionTimeout();
            assertThat(processor().postProcessBeforeInitialization(pool,"otherPool")).isSameAs(pool);
            assertThat(pool.getConnectionTimeout()).isEqualTo(original);
        }
    }
}
