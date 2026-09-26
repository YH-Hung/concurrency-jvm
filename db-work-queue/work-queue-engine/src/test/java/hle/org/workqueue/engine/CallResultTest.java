package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallResultTest {

    @Test
    void acceptsUpToAThousandUtf8Bytes() {
        assertThat(new CallResult("").value()).isEmpty();
        assertThat(new CallResult("a".repeat(1000)).value()).hasSize(1000);
        assertThat(new CallResult("é".repeat(500)).value()).hasSize(500);
    }

    @Test
    void rejectsALongerValueWithoutEchoingIt() {
        String tooLong = "x".repeat(1001);

        assertThatThrownBy(() -> new CallResult(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value must be at most 1000 UTF-8 bytes");
        assertThatThrownBy(() -> new CallResult("é".repeat(500) + "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new CallResult(null)).isInstanceOf(NullPointerException.class).hasMessage("value");
    }

    @Test
    void toStringDoesNotRevealTheValue() {
        assertThat(new CallResult("receipt-secret")).hasToString("CallResult[redacted]");
    }
}
