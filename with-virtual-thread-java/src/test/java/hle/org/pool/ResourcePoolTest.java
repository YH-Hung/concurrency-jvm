package hle.org.pool;

import hle.org.client.SimulatedCorbaClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePoolTest {

    private ResourcePool<SimulatedCorbaClient> pool;

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void shouldBorrowAndReturnResource() {
        pool = new ResourcePool<>(
            () -> SimulatedCorbaClient.builder().failureRate(0).verbose(false).build(),
            ResourcePoolConfig.builder().maxPoolSize(2).build()
        );

        String result = pool.execute(client -> {
            assertNotNull(client);
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(0, pool.getActiveCount());
        assertEquals(1, pool.getIdleCount());
    }

    @Test
    void shouldHandleExhaustedPoolWithTimeout() {
        pool = new ResourcePool<>(
            () -> SimulatedCorbaClient.builder().failureRate(0).verbose(false).build(),
            ResourcePoolConfig.builder()
                .minPoolSize(0)
                .maxPoolSize(1)
                .maxWaitTime(Duration.ofMillis(100))
                .blockWhenExhausted(true)
                .build()
        );

        // Borrow the only resource and hold it
        new Thread(() -> {
            pool.execute(client -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            });
        }).start();

        // Wait a bit for the thread to start and borrow
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Trying to borrow should fail after timeout
        assertThrows(ResourcePoolException.class, () -> 
            pool.execute(client -> "fail")
        );
    }

    @Test
    void shouldInvalidateResourceOnFailureIfIndicated() {
        AtomicInteger createCount = new AtomicInteger(0);
        pool = new ResourcePool<>(
            () -> {
                createCount.incrementAndGet();
                return SimulatedCorbaClient.builder().failureRate(0).verbose(false).build();
            },
            ResourcePoolConfig.builder()
                .minPoolSize(0)
                .maxPoolSize(1)
                .build()
        );

        // First use
        pool.execute(client -> "ok");
        assertEquals(1, createCount.get());

        // Use and invalidate
        try {
            pool.execute(client -> {
                client.close(); // Simulating client becomes invalid
                throw new RuntimeException("fail");
            });
        } catch (Exception e) {
            // Expected
        }

        // Should create a new one because the previous one was closed/invalid
        pool.execute(client -> "ok");
        assertTrue(createCount.get() >= 2, "Should have created at least 2 resources, but got " + createCount.get());
    }

    @Test
    void shouldHandleNullOperation() {
        pool = new ResourcePool<>(() -> SimulatedCorbaClient.builder().build());
        assertThrows(NullPointerException.class, () -> pool.execute(null));
    }

    @Test
    void shouldHandleClosedPool() {
        pool = new ResourcePool<>(() -> SimulatedCorbaClient.builder().build());
        pool.close();
        assertThrows(ResourcePoolException.class, () -> pool.execute(client -> "fail"));
    }

    @Test
    void shouldProvideStats() {
        pool = new ResourcePool<>(
            () -> SimulatedCorbaClient.builder().failureRate(0).verbose(false).build(),
            ResourcePoolConfig.builder().maxPoolSize(5).build()
        );

        String stats = pool.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("ResourcePool"));
        assertTrue(stats.contains("active=0"));
        assertTrue(stats.contains("max=5"));
    }

    @Test
    void shouldExecuteWithCustomTimeout() {
        pool = new ResourcePool<>(
            () -> SimulatedCorbaClient.builder().failureRate(0).verbose(false).build(),
            ResourcePoolConfig.builder()
                .minPoolSize(0)
                .maxPoolSize(1)
                .build()
        );

        // Block the pool
        new Thread(() -> {
            pool.execute(client -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            });
        }).start();

        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // This should fail quickly
        assertThrows(ResourcePoolException.class, () -> 
            pool.executeWithTimeout(Duration.ofMillis(50), client -> "fail")
        );
    }
}
