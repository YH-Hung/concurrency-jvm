package hle.org;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Executes tasks using pooled resources with controlled concurrency.
 *
 * @param <T> the type of resource managed by the pool
 */
public final class RequestRunner<T> {
    private final ResourcePool<T> pool;

    public RequestRunner(ResourcePool<T> pool) {
        this.pool = pool;
    }

    /**
     * Runs N identical tasks (no return values) - useful for benchmarking.
     * Returns statistics about the run.
     *
     * @param requests    the number of requests to execute
     * @param concurrency the maximum number of concurrent threads
     * @param queueSize   the size of the work queue
     * @param task        the task to execute with each borrowed resource
     * @return the run result with statistics
     * @throws InterruptedException if the run is interrupted
     */
    public RunResult run(int requests,
                         int concurrency,
                         int queueSize,
                         ResourceTask<T> task) throws InterruptedException {
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
                T resource = null;
                try {
                    resource = pool.borrow();
                    task.execute(resource);
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

    /**
     * Processes a list of items with return values.
     * Results are returned in the same order as inputs.
     *
     * @param items       the items to process
     * @param concurrency the maximum number of concurrent threads
     * @param queueSize   the size of the work queue
     * @param processor   a function that takes a resource and an item, returns a result
     * @param <I>         the input item type
     * @param <R>         the result type
     * @return a list of results in the same order as the input items
     * @throws InterruptedException if the processing is interrupted
     * @throws ExecutionException   if any processing fails
     */
    public <I, R> List<R> processAll(List<I> items,
                                      int concurrency,
                                      int queueSize,
                                      BiFunction<T, I, R> processor)
            throws InterruptedException, ExecutionException {

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadPoolExecutor.CallerRunsPolicy());

        try {
            List<CompletableFuture<R>> futures = items.stream()
                    .map(item -> CompletableFuture.supplyAsync(() -> {
                        T resource = null;
                        try {
                            resource = pool.borrow();
                            return processor.apply(resource, item);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            pool.release(resource);
                        }
                    }, executor))
                    .collect(Collectors.toList());

            // Wait for all futures to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

            // Collect results in order
            List<R> results = new ArrayList<>(items.size());
            for (CompletableFuture<R> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Processes items with callbacks (memory-efficient streaming).
     * Uses backpressure to limit the number of in-flight requests.
     *
     * @param items         the items to process
     * @param concurrency   the maximum number of concurrent threads
     * @param processor     a function that takes a resource and an item, returns a result
     * @param resultHandler called with each item and its result on success
     * @param errorHandler  called with each item and its error on failure
     * @param <I>           the input item type
     * @param <R>           the result type
     */
    public <I, R> void processWithCallback(
            List<I> items,
            int concurrency,
            BiFunction<T, I, R> processor,
            BiConsumer<I, R> resultHandler,
            BiConsumer<I, Throwable> errorHandler) {

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency),
                new ThreadPoolExecutor.CallerRunsPolicy());

        Semaphore inFlight = new Semaphore(concurrency);
        CountDownLatch completion = new CountDownLatch(items.size());

        for (I item : items) {
            try {
                inFlight.acquire();  // Backpressure: wait if too many in-flight

                executor.execute(() -> {
                    T resource = null;
                    try {
                        resource = pool.borrow();
                        R result = processor.apply(resource, item);
                        resultHandler.accept(item, result);
                    } catch (Exception e) {
                        errorHandler.accept(item, e);
                    } finally {
                        pool.release(resource);
                        inFlight.release();
                        completion.countDown();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        try {
            completion.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
