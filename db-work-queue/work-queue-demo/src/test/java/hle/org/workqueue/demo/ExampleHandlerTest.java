package hle.org.workqueue.demo;
import hle.org.workqueue.engine.IdempotencyKey;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ExampleHandlerTest {
    @Test void transformsThePayloadDeterministically() throws Exception {
        var handler = new ExampleHandler();
        var key = new IdempotencyKey("demo", "one");
        assertThat(handler.call(key, 1, "job-1", Duration.ofSeconds(30)).value()).isEqualTo("processed:job-1");
        assertThat(handler.call(key, 2, "job-1", Duration.ofSeconds(30))).isEqualTo(handler.call(key, 1, "job-1", Duration.ofSeconds(30)));
    }
}
