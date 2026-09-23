package hle.org.workqueue.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.db2.Db2Container;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;

/**
 * One Db2 container per test JVM (reused across runs when testcontainers.reuse.enable=true), migrated
 * once with a clean V1 schema. Every IT class shares it; each test resets WORK_ITEM itself.
 */
final class Db2TestSupport {

    static final String NAMESPACE = "it";

    /** IT column of spec §5.3. */
    static final DbTimeouts IT_TIMEOUTS = new DbTimeouts(
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1));

    /** IT column of spec §6 for the settings the repository uses. */
    static final WorkItemRepository.Settings IT_SETTINGS =
            new WorkItemRepository.Settings(Duration.ofSeconds(25), 5, Duration.ofMillis(100));

    private static final Db2Container DB2 = new Db2Container(DockerImageName.parse("icr.io/db2_community/db2:12.1.5.0"))
            .acceptLicense()
            .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"))
            .withStartupTimeout(Duration.ofMinutes(15))
            .withReuse(true);

    private static final HikariDataSource ADMIN;

    static {
        DB2.start();
        ADMIN = new HikariDataSource(baseConfig("wq-it-admin", 4));
        // Clean first: a reused container may hold a schema from an earlier revision of V1.
        Flyway flyway = Flyway.configure()
                .dataSource(ADMIN)
                .locations("classpath:db/migration/workqueue")
                .placeholders(Map.of("workqueueNamespace", NAMESPACE))
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private Db2TestSupport() {
    }

    /** Untimed pool for migrations, seeding and assertions. Engine code under test never uses it. */
    static DataSource adminDataSource() {
        return ADMIN;
    }

    /** A pool configured like a production worker, with the IT timeouts. The caller closes it. */
    static HikariDataSource workerDataSource(String poolName, int poolSize) {
        return workerDataSource(poolName, poolSize, IT_TIMEOUTS);
    }

    static HikariDataSource workerDataSource(String poolName, int poolSize, DbTimeouts timeouts) {
        HikariConfig config = baseConfig(poolName, poolSize);
        timeouts.applyTo(config);
        return new HikariDataSource(config);
    }

    static WorkItemRepository repository(DataSource worker) {
        return repository(worker, IT_SETTINGS);
    }

    static WorkItemRepository repository(DataSource worker, WorkItemRepository.Settings settings) {
        return new WorkItemRepository(worker, IT_TIMEOUTS, settings);
    }

    private static HikariConfig baseConfig(String poolName, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(DB2.getJdbcUrl());
        config.setUsername(DB2.getUsername());
        config.setPassword(DB2.getPassword());
        config.setMaximumPoolSize(poolSize);
        return config;
    }
}
