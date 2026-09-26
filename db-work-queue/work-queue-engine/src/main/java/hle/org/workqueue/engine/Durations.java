package hle.org.workqueue.engine;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

final class Durations {

    private Durations() {
    }

    /** Db2 labeled durations, JCC timeouts and Spring transaction timeouts take whole seconds. */
    static void requirePositiveWholeSeconds(String name, Duration value) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero() || value.toNanosPart() != 0) {
            throw new IllegalArgumentException(name + " must be a positive whole number of seconds: " + value);
        }
    }

    static void requirePositive(String name, Duration value) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }

    /** Seconds as the spec writes them, e.g. "18s" or "22.4s" (millisecond precision). */
    static String seconds(Duration value) {
        return BigDecimal.valueOf(value.toMillis(), 3).stripTrailingZeros().toPlainString() + "s";
    }
}
