package hle.org.workqueue.demo;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.db2.Db2Container;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** Tests the same external-package application and auto-discovery used by the executable jar. */
class FirstRunIT {
    @Test void firstRunRestartAndPreflightFailuresUseOnlyThePublicApplicationPath() throws Exception {
        try (var db = new Db2Container(DockerImageName.parse("icr.io/db2_community/db2:12.1.5.0"))
                .acceptLicense().withDatabaseName("WORKQ").withEnv("TZ", "UTC")
                .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"))
                .withStartupTimeout(Duration.ofMinutes(15))) {
            db.start();
            try (var admin = new HikariDataSource()) {
                admin.setJdbcUrl(db.getJdbcUrl()); admin.setUsername(db.getUsername()); admin.setPassword(db.getPassword());
                admin.setMaximumPoolSize(2); admin.setConnectionTimeout(2000);
                admin.addDataSourceProperty("loginTimeout", "3");
                admin.addDataSourceProperty("blockingReadConnectionTimeout", "8");
                admin.addDataSourceProperty("queryTimeoutInterruptProcessingMode", "2");
                var commands = new DemoCommands(admin);
                commands.migrate("demo");
                var first = commands.seed(3);
                try (var worker = DemoApplication.start("worker", args(db, "demo"))) {
                    assertThat(worker.getBeansOfType(hle.org.workqueue.engine.ExternalService.class)).hasSize(1);
                    assertThat(commands.verify(first, Duration.ofSeconds(120)).succeeded()).isTrue();
                }
                var before = rows(admin, first);
                assertThat(before).allMatch(row -> row.contains("|DONE|1|processed:job-"));
                var second = commands.seed(3);
                assertThat(second).doesNotContainAnyElementsOf(first);
                try (var worker = DemoApplication.start("worker", args(db, "demo"))) {
                    assertThat(commands.verify(second, Duration.ofSeconds(120)).succeeded()).isTrue();
                }
                assertThat(rows(admin, first)).isEqualTo(before);
                assertThat(rows(admin, second).getFirst().split("\\|")[4]).isNotEqualTo(before.getFirst().split("\\|")[4]);
                var unclaimed = commands.seed(1);
                assertThatThrownBy(() -> DemoApplication.start("worker", args(db, "wrong")))
                    .hasStackTraceContaining("expected-namespace does not match");
                var missingSchemaArgs = new ArrayList<>(List.of(args(db, "demo")));
                missingSchemaArgs.add("--spring.datasource.hikari.data-source-properties.currentSchema=UNMIGRATED");
                assertThatThrownBy(() -> DemoApplication.start("worker", missingSchemaArgs.toArray(String[]::new)))
                    .hasStackTraceContaining("Schema check failed");
                assertThat(rows(admin, unclaimed)).allMatch(row -> row.contains("|PENDING|0|null"));
            }
        }
    }

    private static String[] args(Db2Container db, String namespace) {
        // Testcontainers credentials are test fixture values, not the local demo's credentials.
        return new String[]{"--spring.datasource.url=" + db.getJdbcUrl(),
            "--spring.datasource.username=" + db.getUsername(), "--spring.datasource.password=" + db.getPassword(),
            "--workqueue.expected-namespace=" + namespace};
    }
    private static List<String> rows(HikariDataSource source, List<Long> ids) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT ID, STATUS, ATTEMPTS, RESULT_VALUE, OWNER FROM WORK_ITEM WHERE ID = ?")) {
            statement.setQueryTimeout(5);
            for (long id : ids) {
                statement.setLong(1,id);
                try (var row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    result.add(row.getLong(1) + "|" + row.getString(2) + "|" + row.getInt(3) + "|" + row.getString(4) + "|" + row.getString(5));
                }
            }
        }
        return result;
    }
}
