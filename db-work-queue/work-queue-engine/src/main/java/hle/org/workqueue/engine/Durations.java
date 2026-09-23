package hle.org.workqueue.engine;

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
}
