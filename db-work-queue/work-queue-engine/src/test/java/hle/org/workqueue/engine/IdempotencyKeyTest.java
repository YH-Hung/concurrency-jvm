package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void rejectsANamespaceContainingAColon() {
        assertThatThrownBy(() -> new IdempotencyKey("a:b", "c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnOperationIdContainingColons() {
        assertThat(new IdempotencyKey("a", "b:c").value()).isEqualTo("a:b:c");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "-a", "a_b", "a b", "a.b", "é", "abcdefghijklmnopqrstuvwxyz0123456"})
    void rejectsNamespacesOutsideTheFormat(String namespace) {
        assertThatThrownBy(() -> new IdempotencyKey(namespace, "op-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "a-", "prod-eu-1", "abcdefghijklmnopqrstuvwxyz012345"})
    void acceptsNamespacesInTheFormat(String namespace) {
        assertThat(new IdempotencyKey(namespace, "op-1").namespace()).isEqualTo(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "tab\there", "é", "\u007f",
            "0123456789012345678901234567890123456789012345678901234567890123x"})
    void rejectsOperationIdsOutsideTheFormat(String operationId) {
        assertThatThrownBy(() -> new IdempotencyKey("it", operationId)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "~", "order-8812:charge", "0123456789012345678901234567890123456789012345678901234567890123"})
    void acceptsOperationIdsInTheFormat(String operationId) {
        assertThat(new IdempotencyKey("it", operationId).operationId()).isEqualTo(operationId);
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new IdempotencyKey(null, "op-1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyKey("it", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void distinctPairsGiveDistinctValuesAndTheFirstColonSplitsThem() {
        Random random = new Random(20260925);
        Map<String, IdempotencyKey> byValue = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            // Small alphabets make equal values likely if the encoding were ambiguous.
            IdempotencyKey key = new IdempotencyKey(
                    randomString(random, "ab0", "ab0-", 4),
                    randomString(random, "ab:-~!", "ab:-~!", 6));
            IdempotencyKey previous = byValue.putIfAbsent(key.value(), key);
            assertThat(previous).as("value %s", key.value()).isIn(null, key);

            String value = key.value();
            int separator = value.indexOf(':');
            assertThat(new IdempotencyKey(value.substring(0, separator), value.substring(separator + 1))).isEqualTo(key);
        }
    }

    @Test
    void toStringDoesNotRevealTheKey() {
        assertThat(new IdempotencyKey("it", "order-8812:charge").toString())
                .doesNotContain("order-8812")
                .doesNotContain("it:");
    }

    private static String randomString(Random random, String firstCharacters, String characters, int maxLength) {
        int length = 1 + random.nextInt(maxLength);
        StringBuilder text = new StringBuilder(length);
        text.append(firstCharacters.charAt(random.nextInt(firstCharacters.length())));
        for (int i = 1; i < length; i++) {
            text.append(characters.charAt(random.nextInt(characters.length())));
        }
        return text.toString();
    }
}
