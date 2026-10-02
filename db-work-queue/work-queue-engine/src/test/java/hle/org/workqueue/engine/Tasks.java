package hle.org.workqueue.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A QueueRunner processor for unit tests: tasks that run until released, then end with their scripted outcome
 * (COMPLETED by default). An interrupt ends a task INTERRUPTED at once, unless the tasks ignore interrupts.
 */
final class Tasks implements ClaimExecution.Processor {

    private final Map<ClaimKey, CountDownLatch> releases = new ConcurrentHashMap<>();
    private final Map<ClaimKey, Outcome> outcomes = new ConcurrentHashMap<>();
    private final List<ClaimKey> started = new CopyOnWriteArrayList<>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger highWater = new AtomicInteger();
    private volatile boolean allReleased;
    private volatile boolean ignoreInterrupts;

    @Override
    public Outcome process(ClaimedItem item, BooleanSupplier cancelled) {
        started.add(item.key());
        highWater.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
            CountDownLatch release = latch(item.key());
            // releaseAll sets allReleased before it counts down the latches, so a task that misses the flag
            // has its latch counted down.
            while (!allReleased && release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    if (!ignoreInterrupts) {
                        return Outcome.INTERRUPTED;
                    }
                }
            }
            return outcomes.getOrDefault(item.key(), Outcome.COMPLETED);
        } finally {
            running.decrementAndGet();
        }
    }

    List<ClaimKey> started() {
        return List.copyOf(started);
    }

    int highWater() {
        return highWater.get();
    }

    void ignoreInterrupts() {
        ignoreInterrupts = true;
    }

    void endWith(ClaimKey key, Outcome outcome) {
        outcomes.put(key, outcome);
    }

    void release(ClaimKey key) {
        latch(key).countDown();
    }

    void releaseAll() {
        allReleased = true;
        releases.values().forEach(CountDownLatch::countDown);
    }

    private CountDownLatch latch(ClaimKey key) {
        return releases.computeIfAbsent(key, k -> new CountDownLatch(1));
    }
}
