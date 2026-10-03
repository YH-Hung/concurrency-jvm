package hle.org.workqueue.demo;

import hle.org.workqueue.engine.ExternalService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Run --demo.mode=migrate|seed|worker|verify. Only worker keeps the JVM alive. */
public final class DemoApplication {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Worker {
        @Bean ExternalService exampleHandler() { return new ExampleHandler(); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "hle.org.workqueue.engine.WorkQueueAutoConfiguration")
    static class Maintenance {}

    public static void main(String[] args) {
        try {
            String mode = mode(args);
            if (mode.equals("worker")) { start(mode, args); return; }
            try (var context = start(mode, args)) {
                var commands = new DemoCommands(context.getBean(DataSource.class));
                var env = context.getEnvironment();
                switch (mode) {
                    case "migrate" -> { commands.migrate("demo"); System.out.println("Migration complete."); }
                    case "seed" -> {
                        Path path = Path.of(env.getRequiredProperty("demo.batch-file"));
                        if (Files.exists(path)) throw new IllegalArgumentException("Use a new batch file for every seed");
                        var ids = commands.seed(env.getProperty("demo.count", Integer.class, 10));
                        writeBatch(path, ids);
                        System.out.println("Seeded " + ids.size() + " jobs.");
                    }
                    case "verify" -> {
                        var result = commands.verify(readBatch(Path.of(env.getRequiredProperty("demo.batch-file"))),
                            Duration.ofSeconds(env.getProperty("demo.timeout-seconds", Long.class, 120L)));
                        System.out.printf("total=%d done=%d failed=%d pending=%d claimed=%d missing=%d%n",
                            result.total(), result.done(), result.failed(), result.pending(), result.claimed(), result.missing());
                        if (!result.succeeded()) throw new IllegalStateException("Batch did not complete");
                    }
                    default -> throw new IllegalArgumentException("Unsupported demo mode");
                }
            }
        } catch (Exception e) {
            // Driver and configuration messages may contain data or connection details.
            System.err.println("Demo failed (" + e.getClass().getSimpleName() + "). Check the mode, batch file and local database configuration.");
            System.exit(1);
        }
    }

    static String mode(String... args) {
        var values = new DefaultApplicationArguments(args).getOptionValues("demo.mode");
        if (values == null) return "worker";
        if (values.size() != 1 || !List.of("worker", "migrate", "seed", "verify").contains(values.getFirst()))
            throw new IllegalArgumentException("demo.mode must be worker, migrate, seed or verify");
        return values.getFirst();
    }

    static ConfigurableApplicationContext start(String mode, String... args) {
        boolean worker = mode.equals("worker");
        String profile = worker ? "worker" : "maintenance";
        var app = new SpringApplication(worker ? Worker.class : Maintenance.class) {
            @Override protected void configurePropertySources(ConfigurableEnvironment environment, String[] arguments) {
                super.configurePropertySources(environment, arguments);
                // Mode is authoritative even when the parent shell has Spring overrides.
                environment.getPropertySources().addFirst(new MapPropertySource("demo-mode", Map.of(
                    "spring.main.keep-alive", String.valueOf(worker), "spring.main.web-application-type", "none",
                    "spring.profiles.active", profile, "spring.flyway.enabled", "false", "spring.sql.init.mode", "never")));
            }
        };
        return app.run(args);
    }

    static List<Long> readBatch(Path path) throws IOException {
        final List<Long> ids;
        try { ids = Files.readAllLines(path).stream().map(Long::valueOf).toList(); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Batch must contain one positive numeric row ID per line"); }
        DemoCommands.validateIds(ids);
        return ids;
    }
    static void writeBatch(Path path, List<Long> ids) throws IOException {
        DemoCommands.validateIds(ids);
        Path absolute = path.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), ".batch-", ".tmp");
        try {
            Files.write(temp, ids.stream().map(String::valueOf).toList());
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
