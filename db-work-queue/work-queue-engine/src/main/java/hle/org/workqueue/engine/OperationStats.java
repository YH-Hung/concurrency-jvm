package hle.org.workqueue.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The count and total duration of one kind of operation (spec §9.6 timers), which a Micrometer FunctionTimer reads
 * when scraped. Durations are differences of {@code System.nanoTime()} readings, so never negative.
 */
final class OperationStats {

    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    record Totals(long count, long totalNanos) {}

    Totals snapshot() {
        return new Totals(count.get(), totalNanos.get());
    }

    void record(long nanos) {
        count.incrementAndGet();
        totalNanos.addAndGet(nanos);
    }

}
