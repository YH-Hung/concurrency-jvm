package hle.org.executor;

import java.time.Duration;

/**
 * Simplified configuration for {@link VirtualThreadExecutor}.
 * 
 * <p>With virtual threads, many traditional thread pool settings are no longer needed:
 * <ul>
 *   <li>No corePoolSize/maxPoolSize - virtual threads are cheap to create</li>
 *   <li>No queueCapacity - task submission doesn't block on pool availability</li>
 *   <li>Concurrency limit still needed - to control downstream service load</li>
 * </ul>
 * 
 * <p>Uses the builder pattern for flexible configuration.
 */
public class ExecutorConfig {

    private final int concurrency;
    private final Duration taskTimeout;
    private final String threadNamePrefix;

    private ExecutorConfig(Builder builder) {
        this.concurrency = builder.concurrency;
        this.taskTimeout = builder.taskTimeout;
        this.threadNamePrefix = builder.threadNamePrefix;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a default configuration suitable for most use cases.
     */
    public static ExecutorConfig defaultConfig() {
        return builder().build();
    }

    /**
     * Gets the maximum number of concurrent task executions.
     * This is enforced via a semaphore to limit load on downstream services.
     */
    public int getConcurrency() {
        return concurrency;
    }

    /**
     * Gets the maximum time a task can run before timing out.
     * Null means no timeout.
     */
    public Duration getTaskTimeout() {
        return taskTimeout;
    }

    /**
     * Gets the prefix for virtual thread names (for debugging).
     */
    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public static class Builder {
        private int concurrency = 10;
        private Duration taskTimeout = Duration.ofMinutes(5);
        private String threadNamePrefix = "virtual-executor";

        private Builder() {}

        /**
         * Sets the maximum number of concurrent task executions.
         * This is the primary way to limit load on downstream services.
         * Default: 10
         * 
         * <p>With virtual threads, this doesn't limit thread creation (threads are cheap),
         * but limits how many tasks actually run concurrently via a semaphore.
         */
        public Builder concurrency(int concurrency) {
            if (concurrency < 1) {
                throw new IllegalArgumentException("concurrency must be >= 1");
            }
            this.concurrency = concurrency;
            return this;
        }

        /**
         * Sets the maximum time a task can run before timing out.
         * Default: 5 minutes
         * 
         * @param taskTimeout the timeout duration, or null for no timeout
         */
        public Builder taskTimeout(Duration taskTimeout) {
            this.taskTimeout = taskTimeout;
            return this;
        }

        /**
         * Sets the prefix for virtual thread names.
         * Useful for debugging and monitoring.
         * Default: "virtual-executor"
         */
        public Builder threadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
            return this;
        }

        public ExecutorConfig build() {
            return new ExecutorConfig(this);
        }
    }
}
