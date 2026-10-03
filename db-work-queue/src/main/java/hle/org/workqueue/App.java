package hle.org.workqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootApplication
@EnableConfigurationProperties(WorkQueue.Settings.class)
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    WorkQueue workQueue(JdbcClient db, WorkQueue.Settings settings) {
        return new WorkQueue(db, settings, (operationId, payload) -> {
            // Your job goes here. It can run more than once per row: send operationId downstream as the
            // idempotency key. Throw to retry.
            log.info("processed {}: {}", operationId, payload);
        });
    }
}
