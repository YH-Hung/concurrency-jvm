package hle.org;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class RequestRunner {
    private final ResourcePool<SimulatedResource> pool;

    public RequestRunner(ResourcePool<SimulatedResource> pool) {
        this.pool = pool;
    }

    public RunResult run(int requests,
                         int concurrency,
                         int queueSize,
                         int sleepMs,
                         int cpuIterations) throws InterruptedException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadPoolExecutor.CallerRunsPolicy());

        CountDownLatch latch = new CountDownLatch(requests);
        AtomicInteger index = new AtomicInteger();
        LongAdder failures = new LongAdder();
        long[] durationsNanos = new long[requests];

        long startNanos = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            long submitNanos = System.nanoTime();
            executor.execute(() -> {
                int idx = index.getAndIncrement();
                SimulatedResource resource = null;
                try {
                    resource = pool.borrow();
                    resource.perform(sleepMs, cpuIterations);
                } catch (Exception e) {
                    failures.increment();
                } finally {
                    pool.release(resource);
                    long endNanos = System.nanoTime();
                    durationsNanos[idx] = endNanos - submitNanos;
                    latch.countDown();
                }
            });
        }

        latch.await();
        long totalTimeNanos = System.nanoTime() - startNanos;
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        Stats stats = Stats.from(durationsNanos, failures.intValue(), totalTimeNanos);
        return new RunResult(stats, pool.getNumActive(), pool.getNumIdle());
    }
}
