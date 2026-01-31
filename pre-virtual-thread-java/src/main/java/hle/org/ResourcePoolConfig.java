package hle.org;

import java.time.Duration;

/**
 * Configuration for a resource pool.
 * Use {@link #builder()} to create instances.
 */
public final class ResourcePoolConfig {
    private final int maxTotal;
    private final int maxIdle;
    private final int minIdle;
    private final Duration maxWait;
    private final boolean blockWhenExhausted;

    private ResourcePoolConfig(Builder builder) {
        this.maxTotal = builder.maxTotal;
        this.maxIdle = builder.maxIdle;
        this.minIdle = builder.minIdle;
        this.maxWait = builder.maxWait;
        this.blockWhenExhausted = builder.blockWhenExhausted;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public int getMaxIdle() {
        return maxIdle;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public Duration getMaxWait() {
        return maxWait;
    }

    public boolean isBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    public static final class Builder {
        private int maxTotal = 8;
        private int maxIdle = 8;
        private int minIdle = 0;
        private Duration maxWait = Duration.ofSeconds(30);
        private boolean blockWhenExhausted = true;

        private Builder() {
        }

        public Builder maxTotal(int maxTotal) {
            this.maxTotal = maxTotal;
            return this;
        }

        public Builder maxIdle(int maxIdle) {
            this.maxIdle = maxIdle;
            return this;
        }

        public Builder minIdle(int minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        public Builder maxWait(Duration maxWait) {
            this.maxWait = maxWait;
            return this;
        }

        public Builder blockWhenExhausted(boolean blockWhenExhausted) {
            this.blockWhenExhausted = blockWhenExhausted;
            return this;
        }

        public ResourcePoolConfig build() {
            return new ResourcePoolConfig(this);
        }
    }
}
