package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class WorkQueueAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    static class Application {}
    @BeforeEach void reset() { StartupDatabase.reset(); }
    ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(Application.class)
            .withPropertyValues("spring.datasource.url=jdbc:queue-test:db", "spring.datasource.driver-class-name=" + StartupDatabase.class.getName(),
                "spring.datasource.hikari.maximum-pool-size=20", "spring.datasource.hikari.minimum-idle=0",
                "spring.sql.init.mode=never", "workqueue.expected-namespace=demo");
    }
    ApplicationContextRunner worker() {
        return context().withBean(ExternalService.class, () -> (key, token, payload, timeout) -> new CallResult("done"));
    }
    @Test void autoDiscoveryStartsAndStopsTheRunnerWithBoundedConnections() {
        QueueRunner[] runner = new QueueRunner[1];
        worker().run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(QueueRunner.class);
            runner[0] = c.getBean(QueueRunner.class);
            assertThat(runner[0].isRunning()).isTrue();
            HikariDataSource pool = c.getBean(HikariDataSource.class);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(20);
            assertThat(pool.getConnectionInitSql()).isEqualTo("SET CURRENT LOCK TIMEOUT 3");
            assertThat(StartupDatabase.connectionProperties).containsEntry("loginTimeout", "3")
                .containsEntry("blockingReadConnectionTimeout", "8").containsEntry("queryTimeoutInterruptProcessingMode", "2");
        });
        assertThat(runner[0].isRunning()).isFalse();
    }
    @Test void missingHandlerFailsBeforeOpeningTheDatabase() {
        context().run(c -> { assertThat(c).hasFailed(); assertThat(StartupDatabase.connections).hasValue(0); });
    }
    @Test void duplicateHandlerFailsBeforeOpeningTheDatabase() {
        worker().withBean("otherHandler", ExternalService.class, () -> (k,t,p,d) -> new CallResult("other"))
            .run(c -> { assertThat(c).hasFailed(); assertThat(StartupDatabase.connections).hasValue(0); });
    }
    @Test void tooSmallPoolFailsBeforeConnection() {
        worker().withPropertyValues("spring.datasource.hikari.maximum-pool-size=19")
            .run(c -> { assertThat(c).hasFailed(); assertThat(c.getStartupFailure()).hasStackTraceContaining("B3");
                assertThat(StartupDatabase.connections).hasValue(0); });
    }
    @Test void defaultPoolSizeAndMissingNamespaceFailBeforeConnection() {
        for (String property : new String[]{"spring.datasource.hikari.maximum-pool-size=10", "workqueue.expected-namespace="}) {
            worker().withPropertyValues(property).run(c -> {
                assertThat(c).hasFailed();
                assertThat(StartupDatabase.connections).hasValue(0);
            });
        }
    }
    @Test void multipleSourcesAndLazyProxyFailBeforeConnection() {
        worker().withBean("otherDataSource", javax.sql.DataSource.class, HikariDataSource::new)
            .run(c -> { assertThat(c).hasFailed(); assertThat(StartupDatabase.connections).hasValue(0); });
        worker().withPropertyValues("spring.datasource.connection-fetch=lazy")
            .run(c -> { assertThat(c).hasFailed(); assertThat(StartupDatabase.connections).hasValue(0); });
    }
    private ApplicationContextRunner unconfiguredWorker() {
        return new ApplicationContextRunner().withUserConfiguration(Application.class)
            .withBean(ExternalService.class, () -> (key, token, payload, timeout) -> new CallResult("done"))
            .withPropertyValues("spring.datasource.url=jdbc:queue-test:db", "spring.datasource.driver-class-name=" + StartupDatabase.class.getName(),
                "spring.datasource.hikari.minimum-idle=0", "spring.sql.init.mode=never");
    }
    @Test void absentMaximumUsesEffectiveHikariDefaultForSmallConcurrency() {
        unconfiguredWorker().withPropertyValues("workqueue.expected-namespace=demo", "workqueue.concurrency=6")
            .run(c -> {
                assertThat(c).hasNotFailed();
                assertThat(c.getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(10);
            });
    }
    @Test void absentMaximumFailsDefaultConcurrencyWithB3BeforeConnection() {
        unconfiguredWorker().withPropertyValues("workqueue.expected-namespace=demo")
            .run(c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure()).hasStackTraceContaining("B3");
                assertThat(StartupDatabase.connections).hasValue(0);
            });
    }
    @Test void absentNamespaceFailsBeforeConnection() {
        unconfiguredWorker().withPropertyValues("spring.datasource.hikari.maximum-pool-size=20")
            .run(c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure()).hasStackTraceContaining("workqueue.expected-namespace is required");
                assertThat(StartupDatabase.connections).hasValue(0);
            });
    }
    @Test void twoActualSourcesFailBeforeConnection() {
        worker().withBean("dataSource", HikariDataSource.class, () -> {
                var pool = new HikariDataSource();
                pool.setJdbcUrl("jdbc:queue-test:db"); pool.setMaximumPoolSize(20); return pool;
            }).withBean("otherDataSource", javax.sql.DataSource.class, HikariDataSource::new)
            .run(c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure()).hasStackTraceContaining("exactly one unwrapped HikariDataSource");
                assertThat(StartupDatabase.connections).hasValue(0);
            });
    }
    @Test void invalidNamespaceAndTimingFailBeforeConnection() {
        for (String property : new String[]{"workqueue.expected-namespace=", "workqueue.expected-namespace=bad:name",
                "workqueue.db.lock-wait=5s", "workqueue.lease-duration=60s", "workqueue.max-processing-time=1s", "workqueue.db-staleness-limit=1s"}) {
            worker().withPropertyValues(property).run(c -> { assertThat(c).hasFailed(); assertThat(StartupDatabase.connections).hasValue(0); });
        }
    }
    @Test void preflightFailurePreventsClaimingAndDoesNotLeakSqlMessages() {
        StartupDatabase.sqlFailure = true;
        worker().run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasStackTraceContaining("Schema check failed").hasStackTraceContaining("08001");
            assertThat(c.getStartupFailure()).hasStackTraceContaining("SQLException");
            assertThat(StartupDatabase.queries).noneMatch(sql -> sql.contains("FINAL TABLE"));
            assertThat(c.getStartupFailure()).hasStackTraceContaining("Schema check failed");
            java.io.StringWriter trace = new java.io.StringWriter();
            c.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("secret-database-detail");
        });
    }
    @Test void maintenanceExcludesWorkersWithoutRequiringHandlerOrNamespace() {
        context().withPropertyValues("spring.autoconfigure.exclude=hle.org.workqueue.engine.WorkQueueAutoConfiguration", "workqueue.expected-namespace=")
            .run(c -> { assertThat(c).hasNotFailed().doesNotHaveBean(QueueRunner.class); assertThat(StartupDatabase.connections).hasValue(0); });
    }
}
