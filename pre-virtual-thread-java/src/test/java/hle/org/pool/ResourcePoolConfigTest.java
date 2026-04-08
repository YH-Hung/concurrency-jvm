package hle.org.pool;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePoolConfigTest {

    @Test
    void defaultConfigHasExpectedValues() {
        ResourcePoolConfig config = ResourcePoolConfig.defaultConfig();
        assertEquals(2, config.getMinPoolSize());
        assertEquals(10, config.getMaxPoolSize());
        assertEquals(Duration.ofSeconds(30), config.getMaxWaitTime());
        assertEquals(Duration.ofMinutes(5), config.getMaxIdleTime());
        assertTrue(config.isTestOnBorrow());
        assertFalse(config.isTestOnReturn());
        assertTrue(config.isTestWhileIdle());
        assertEquals(Duration.ofSeconds(30), config.getTimeBetweenEvictionRuns());
        assertTrue(config.isBlockWhenExhausted());
    }

    @Test
    void builderSetsMinPoolSizeToZero() {
        ResourcePoolConfig config = ResourcePoolConfig.builder().minPoolSize(0).build();
        assertEquals(0, config.getMinPoolSize());
    }

    @Test
    void builderThrowsOnNegativeMinPoolSize() {
        assertThrows(IllegalArgumentException.class, () ->
            ResourcePoolConfig.builder().minPoolSize(-1).build());
    }

    @Test
    void builderSetsMaxPoolSize() {
        ResourcePoolConfig config = ResourcePoolConfig.builder().maxPoolSize(20).build();
        assertEquals(20, config.getMaxPoolSize());
    }

    @Test
    void builderThrowsOnZeroMaxPoolSize() {
        assertThrows(IllegalArgumentException.class, () ->
            ResourcePoolConfig.builder().maxPoolSize(0).build());
    }

    @Test
    void builderThrowsWhenMinExceedsMax() {
        assertThrows(IllegalArgumentException.class, () ->
            ResourcePoolConfig.builder().minPoolSize(5).maxPoolSize(3).build());
    }

    @Test
    void builderSetsMaxWaitTime() {
        Duration d = Duration.ofSeconds(60);
        ResourcePoolConfig config = ResourcePoolConfig.builder().maxWaitTime(d).build();
        assertEquals(d, config.getMaxWaitTime());
    }

    @Test
    void builderSetsMaxIdleTime() {
        Duration d = Duration.ofMinutes(10);
        ResourcePoolConfig config = ResourcePoolConfig.builder().maxIdleTime(d).build();
        assertEquals(d, config.getMaxIdleTime());
    }

    @Test
    void builderSetsTestOnBorrowFalse() {
        ResourcePoolConfig config = ResourcePoolConfig.builder().testOnBorrow(false).build();
        assertFalse(config.isTestOnBorrow());
    }

    @Test
    void builderSetsTestOnReturnTrue() {
        ResourcePoolConfig config = ResourcePoolConfig.builder().testOnReturn(true).build();
        assertTrue(config.isTestOnReturn());
    }

    @Test
    void builderSetsTestWhileIdleFalse() {
        ResourcePoolConfig config = ResourcePoolConfig.builder().testWhileIdle(false).build();
        assertFalse(config.isTestWhileIdle());
    }

    @Test
    void builderSetsTimeBetweenEvictionRuns() {
        Duration d = Duration.ofMinutes(1);
        ResourcePoolConfig config = ResourcePoolConfig.builder().timeBetweenEvictionRuns(d).build();
        assertEquals(d, config.getTimeBetweenEvictionRuns());
    }

    @Test
    void builderSetsBlockWhenExhaustedFalse() {
        ResourcePoolConfig config = ResourcePoolConfig.builder().blockWhenExhausted(false).build();
        assertFalse(config.isBlockWhenExhausted());
    }
}
