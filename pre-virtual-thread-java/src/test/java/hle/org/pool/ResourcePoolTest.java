package hle.org.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ResourcePool implementation.
 */
class ResourcePoolTest {

    private ResourcePool<TestResource> pool;
    private AtomicInteger createCount;

    @BeforeEach
    void setUp() {
        createCount = new AtomicInteger(0);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void shouldBorrowAndReturnResources() {
        pool = new ResourcePool<>(
            () -> new TestResource(createCount.incrementAndGet()),
            ResourcePoolConfig.builder()
                .minPoolSize(0)
                .maxPoolSize(5)
                .build()
        );

        String result = pool.execute(resource -> "result-" + resource.getId());

        assertEquals("result-test-1", result);
        assertEquals(0, pool.getActiveCount());
        assertEquals(1, pool.getIdleCount());
    }

    @Test
    void shouldReuseResources() {
        pool = new ResourcePool<>(
            () -> new TestResource(createCount.incrementAndGet()),
            ResourcePoolConfig.builder()
                .minPoolSize(0)
                .maxPoolSize(5)
                .build()
        );

        // Execute multiple times
        for (int i = 0; i < 10; i++) {
            pool.execute(resource -> resource.getId());
        }

        // Only one resource should have been created (reused each time)
        assertEquals(1, createCount.get());
    }

    @Test
    @Timeout(5)
    void shouldBlockWhenPoolExhausted() throws Exception {
        pool = new ResourcePool<>(
            () -> new TestResource(createCount.incrementAndGet()),
            ResourcePoolConfig.builder()
                .maxPoolSize(2)
                .maxWaitTime(Duration.ofSeconds(10))
                .blockWhenExhausted(true)
                .build()
        );

        CountDownLatch resourcesAcquired = new CountDownLatch(2);
        CountDownLatch releaseResources = new CountDownLatch(1);
        AtomicInteger completedCount = new AtomicInteger(0);

        // Start 2 threads that hold resources
        ExecutorService executor = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                pool.execute(resource -> {
                    resourcesAcquired.countDown();
                    try {
                        releaseResources.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    completedCount.incrementAndGet();
                    return null;
                });
            });
        }

        // Wait for both resources to be acquired
        resourcesAcquired.await();
        assertEquals(2, pool.getActiveCount());

        // Start a third thread that should block
        Future<String> blockedFuture = executor.submit(() -> 
            pool.execute(resource -> "third-" + resource.getId())
        );

        // Give it time to start blocking
        Thread.sleep(100);
        assertFalse(blockedFuture.isDone());

        // Release the resources
        releaseResources.countDown();

        // Now the third request should complete
        String result = blockedFuture.get(2, TimeUnit.SECONDS);
        assertNotNull(result);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldLimitPoolSize() throws Exception {
        pool = new ResourcePool<>(
            () -> new TestResource(createCount.incrementAndGet()),
            ResourcePoolConfig.builder()
                .maxPoolSize(3)
                .build()
        );

        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();

        // Submit 10 tasks but only 3 should run concurrently
        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                pool.execute(resource -> {
                    allStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            }));
        }

        // Wait for 3 to start
        boolean started = allStarted.await(2, TimeUnit.SECONDS);
        assertTrue(started);
        
        // Only 3 resources should have been created
        assertEquals(3, createCount.get());
        assertEquals(3, pool.getMaxPoolSize());

        // Release and cleanup
        release.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();
    }

    @Test
    void shouldInvalidateFailedResources() {
        AtomicInteger useCount = new AtomicInteger(0);
        
        pool = new ResourcePool<>(
            () -> {
                int resourceNum = createCount.incrementAndGet();
                return new TestResource(resourceNum) {
                    private boolean markedInvalid = false;
                    
                    @Override
                    public boolean isValid() {
                        // First resource becomes invalid after being used
                        if (resourceNum == 1 && useCount.get() > 0) {
                            markedInvalid = true;
                        }
                        return !markedInvalid;
                    }
                };
            },
            ResourcePoolConfig.builder()
                .maxPoolSize(5)
                .testOnBorrow(true)
                .testOnReturn(false)
                .build()
        );

        // First execution - resource is valid during execution
        pool.execute(r -> {
            useCount.incrementAndGet();
            return "first";
        });
        
        // Second execution should create a new resource since first is now invalid
        pool.execute(r -> "second");

        // Should have created 2 resources (first was invalidated on second borrow)
        assertEquals(2, createCount.get());
    }

    @Test
    void shouldHandleExceptionsInOperations() {
        pool = new ResourcePool<>(
            () -> new TestResource(createCount.incrementAndGet()),
            ResourcePoolConfig.defaultConfig()
        );

        // Exception should be wrapped in ResourcePoolException
        assertThrows(ResourcePoolException.class, () -> {
            pool.execute(resource -> {
                throw new RuntimeException("Simulated error");
            });
        });

        // Resource should still be in pool (operation failed, not the resource)
        assertEquals(0, pool.getActiveCount());
    }

    @Test
    void shouldProvideAccurateStatistics() {
        pool = new ResourcePool<>(
            () -> new TestResource(createCount.incrementAndGet()),
            ResourcePoolConfig.builder()
                .maxPoolSize(5)
                .build()
        );

        assertEquals(0, pool.getActiveCount());
        assertEquals(0, pool.getIdleCount());
        assertEquals(0, pool.getTotalCount());
        assertEquals(5, pool.getMaxPoolSize());

        pool.execute(r -> "test");

        assertEquals(0, pool.getActiveCount());
        assertEquals(1, pool.getIdleCount());
        assertEquals(1, pool.getTotalCount());
    }

    @Test
    void shouldRejectOperationsAfterClose() {
        pool = new ResourcePool<>(
            () -> new TestResource(createCount.incrementAndGet()),
            ResourcePoolConfig.defaultConfig()
        );

        pool.close();

        assertThrows(ResourcePoolException.class, () -> {
            pool.execute(r -> "should fail");
        });
    }

    /**
     * Simple test implementation of PooledResource.
     */
    static class TestResource implements PooledResource<String> {
        private final String id;
        private boolean valid = true;
        private boolean closed = false;

        TestResource(int number) {
            this.id = "test-" + number;
        }

        @Override
        public boolean isValid() {
            return valid && !closed;
        }

        @Override
        public void reset() {
            // No-op
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public String getId() {
            return id;
        }

        void setValid(boolean valid) {
            this.valid = valid;
        }
    }
}
