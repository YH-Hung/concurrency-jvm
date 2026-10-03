package hle.org.workqueue.demo;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class DemoApplicationTest {
    @TempDir Path dir;
    @Test void selectsOnlySupportedModesBeforeOpeningContext() {
        assertThat(DemoApplication.mode()).isEqualTo("worker");
        for (String mode : List.of("worker", "migrate", "seed", "verify"))
            assertThat(DemoApplication.mode("--demo.mode=" + mode)).isEqualTo(mode);
        assertThatThrownBy(() -> DemoApplication.mode("--demo.mode=oops")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoApplication.mode("--demo.mode=seed", "--demo.mode=worker")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void manifestRoundTripsAndRejectsInvalidOrMissingIds() throws Exception {
        Path file = dir.resolve("batch.ids");
        DemoApplication.writeBatch(file, List.of(11L, 15L));
        assertThat(DemoApplication.readBatch(file)).containsExactly(11L, 15L);
        for (String invalid : List.of("", "0", "-1", "1\n1", "abc", "9999999999999999999999999")) {
            Files.writeString(file, invalid);
            assertThatThrownBy(() -> DemoApplication.readBatch(file)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void maintenanceCannotStartTheEngineEvenWithInheritedWorkerProfile() {
        try (var context = DemoApplication.start("seed", "--spring.profiles.active=worker", "--spring.main.keep-alive=true")) {
            assertThat(context.getBeansOfType(hle.org.workqueue.engine.ExternalService.class)).isEmpty();
            assertThat(context.containsBean("workQueueRunner")).isFalse();
            assertThat(context.getEnvironment().getProperty("spring.main.keep-alive")).isEqualTo("false");
        }
    }
}
