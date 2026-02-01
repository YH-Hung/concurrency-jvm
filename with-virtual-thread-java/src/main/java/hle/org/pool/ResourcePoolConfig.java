package hle.org.pool;

import java.time.Duration;

/**
 * Configuration for {@link ResourcePool}.
 * Uses the builder pattern for flexible configuration.
 * 
 * <p>Default values are suitable for most use cases but should be
 * tuned based on your specific requirements and resource characteristics.
 */
public class ResourcePoolConfig {

    private final int minPoolSize;
    private final int maxPoolSize;
    private final Duration maxWaitTime;
    private final Duration maxIdleTime;
    private final boolean testOnBorrow;
    private final boolean testOnReturn;
    private final boolean testWhileIdle;
    private final Duration timeBetweenEvictionRuns;
    private final boolean blockWhenExhausted;

    private ResourcePoolConfig(Builder builder) {
        this.minPoolSize = builder.minPoolSize;
        this.maxPoolSize = builder.maxPoolSize;
        this.maxWaitTime = builder.maxWaitTime;
        this.maxIdleTime = builder.maxIdleTime;
        this.testOnBorrow = builder.testOnBorrow;
        this.testOnReturn = builder.testOnReturn;
        this.testWhileIdle = builder.testWhileIdle;
        this.timeBetweenEvictionRuns = builder.timeBetweenEvictionRuns;
        this.blockWhenExhausted = builder.blockWhenExhausted;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a default configuration suitable for most use cases.
     */
    public static ResourcePoolConfig defaultConfig() {
        return builder().build();
    }

    public int getMinPoolSize() {
        return minPoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public Duration getMaxWaitTime() {
        return maxWaitTime;
    }

    public Duration getMaxIdleTime() {
        return maxIdleTime;
    }

    public boolean isTestOnBorrow() {
        return testOnBorrow;
    }

    public boolean isTestOnReturn() {
        return testOnReturn;
    }

    public boolean isTestWhileIdle() {
        return testWhileIdle;
    }

    public Duration getTimeBetweenEvictionRuns() {
        return timeBetweenEvictionRuns;
    }

    public boolean isBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    public static class Builder {
        private int minPoolSize = 2;
        private int maxPoolSize = 10;
        private Duration maxWaitTime = Duration.ofSeconds(30);
        private Duration maxIdleTime = Duration.ofMinutes(5);
        private boolean testOnBorrow = true;
        private boolean testOnReturn = false;
        private boolean testWhileIdle = true;
        private Duration timeBetweenEvictionRuns = Duration.ofSeconds(30);
        private boolean blockWhenExhausted = true;

        private Builder() {}

        /**
         * Sets the minimum number of resources to keep in the pool.
         * Default: 2
         */
        public Builder minPoolSize(int minPoolSize) {
            if (minPoolSize < 0) {
                throw new IllegalArgumentException("minPoolSize must be >= 0");
            }
            this.minPoolSize = minPoolSize;
            return this;
        }

        /**
         * Sets the maximum number of resources the pool can create.
         * This controls the maximum degree of concurrency for resource usage.
         * Default: 10
         */
        public Builder maxPoolSize(int maxPoolSize) {
            if (maxPoolSize < 1) {
                throw new IllegalArgumentException("maxPoolSize must be >= 1");
            }
            this.maxPoolSize = maxPoolSize;
            return this;
        }

        /**
         * Sets how long to wait for a resource when the pool is exhausted.
         * Default: 30 seconds
         */
        public Builder maxWaitTime(Duration maxWaitTime) {
            this.maxWaitTime = maxWaitTime;
            return this;
        }

        /**
         * Sets how long a resource can remain idle before being eligible for eviction.
         * Default: 5 minutes
         */
        public Builder maxIdleTime(Duration maxIdleTime) {
            this.maxIdleTime = maxIdleTime;
            return this;
        }

        /**
         * Whether to validate resources when borrowing from the pool.
         * Default: true
         */
        public Builder testOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
            return this;
        }

        /**
         * Whether to validate resources when returning to the pool.
         * Default: false
         */
        public Builder testOnReturn(boolean testOnReturn) {
            this.testOnReturn = testOnReturn;
            return this;
        }

        /**
         * Whether to validate idle resources periodically.
         * Default: true
         */
        public Builder testWhileIdle(boolean testWhileIdle) {
            this.testWhileIdle = testWhileIdle;
            return this;
        }

        /**
         * How often to run the idle resource eviction check.
         * Default: 30 seconds
         */
        public Builder timeBetweenEvictionRuns(Duration timeBetweenEvictionRuns) {
            this.timeBetweenEvictionRuns = timeBetweenEvictionRuns;
            return this;
        }

        /**
         * Whether to block when the pool is exhausted (true) or fail immediately (false).
         * Default: true
         */
        public Builder blockWhenExhausted(boolean blockWhenExhausted) {
            this.blockWhenExhausted = blockWhenExhausted;
            return this;
        }

        public ResourcePoolConfig build() {
            if (minPoolSize > maxPoolSize) {
                throw new IllegalArgumentException("minPoolSize cannot be greater than maxPoolSize");
            }
            return new ResourcePoolConfig(this);
        }
    }
}
