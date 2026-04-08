package hle.org.executor;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorConfigTest {

    @Test
    void defaultConfigHasExpectedValues() {
        ExecutorConfig config = ExecutorConfig.defaultConfig();
        assertEquals(10, config.getConcurrency());
        assertEquals(10, config.getCorePoolSize());
        assertEquals(10, config.getMaxPoolSize());
        assertEquals(1000, config.getQueueCapacity());
        assertEquals(Duration.ofMinutes(5), config.getTaskTimeout());
        assertFalse(config.isDaemon());
        assertEquals("bounded-executor", config.getThreadNamePrefix());
        assertEquals(ExecutorConfig.RejectionPolicy.BLOCK, config.getRejectionPolicy());
    }

    @Test
    void builderSetsConcurrency() {
        ExecutorConfig config = ExecutorConfig.builder().concurrency(20).build();
        assertEquals(20, config.getConcurrency());
        // corePoolSize and maxPoolSize default to concurrency
        assertEquals(20, config.getCorePoolSize());
        assertEquals(20, config.getMaxPoolSize());
    }

    @Test
    void builderThrowsOnZeroConcurrency() {
        assertThrows(IllegalArgumentException.class, () ->
            ExecutorConfig.builder().concurrency(0).build());
    }

    @Test
    void builderThrowsOnNegativeCorePoolSize() {
        assertThrows(IllegalArgumentException.class, () ->
            ExecutorConfig.builder().corePoolSize(-1).build());
    }

    @Test
    void builderThrowsOnZeroMaxPoolSize() {
        assertThrows(IllegalArgumentException.class, () ->
            ExecutorConfig.builder().maxPoolSize(0).build());
    }

    @Test
    void builderThrowsOnZeroQueueCapacity() {
        assertThrows(IllegalArgumentException.class, () ->
            ExecutorConfig.builder().queueCapacity(0).build());
    }

    @Test
    void builderSetsDaemonTrue() {
        ExecutorConfig config = ExecutorConfig.builder().daemon(true).build();
        assertTrue(config.isDaemon());
    }

    @Test
    void builderSetsThreadNamePrefix() {
        ExecutorConfig config = ExecutorConfig.builder().threadNamePrefix("my-pool").build();
        assertEquals("my-pool", config.getThreadNamePrefix());
    }

    @Test
    void builderSetsRejectionPolicyReject() {
        ExecutorConfig config = ExecutorConfig.builder()
            .rejectionPolicy(ExecutorConfig.RejectionPolicy.REJECT).build();
        assertEquals(ExecutorConfig.RejectionPolicy.REJECT, config.getRejectionPolicy());
    }

    @Test
    void builderSetsRejectionPolicyCallerRuns() {
        ExecutorConfig config = ExecutorConfig.builder()
            .rejectionPolicy(ExecutorConfig.RejectionPolicy.CALLER_RUNS).build();
        assertEquals(ExecutorConfig.RejectionPolicy.CALLER_RUNS, config.getRejectionPolicy());
    }

    @Test
    void builderSetsTaskTimeout() {
        Duration timeout = Duration.ofSeconds(10);
        ExecutorConfig config = ExecutorConfig.builder().taskTimeout(timeout).build();
        assertEquals(timeout, config.getTaskTimeout());
    }

    @Test
    void builderSetsQueueCapacity() {
        ExecutorConfig config = ExecutorConfig.builder().queueCapacity(500).build();
        assertEquals(500, config.getQueueCapacity());
    }

    @Test
    void explicitCorePoolSizeOverridesDefault() {
        // corePoolSize < concurrency requires queueCapacity <= concurrency to pass validation
        ExecutorConfig config = ExecutorConfig.builder()
            .concurrency(10)
            .corePoolSize(5)
            .maxPoolSize(10)
            .queueCapacity(10)   // <= concurrency so validation passes
            .build();
        assertEquals(5, config.getCorePoolSize());
    }

    @Test
    void explicitMaxPoolSizeOverridesDefault() {
        ExecutorConfig config = ExecutorConfig.builder()
            .concurrency(5)
            .maxPoolSize(10)
            .build();
        assertEquals(10, config.getMaxPoolSize());
    }
}
