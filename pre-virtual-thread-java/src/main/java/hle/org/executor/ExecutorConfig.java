package hle.org.executor;

import java.time.Duration;

/**
 * Configuration for {@link BoundedConcurrencyExecutor}.
 * 
 * <p>Key considerations for production use:
 * <ul>
 *   <li>Set concurrency based on downstream service capacity, not CPU cores</li>
 *   <li>Queue capacity should account for burst traffic</li>
 *   <li>Timeouts should be slightly longer than expected operation time</li>
 * </ul>
 */
public class ExecutorConfig {

    private final int concurrency;
    private final int queueCapacity;
    private final Duration taskTimeout;
    private final boolean daemon;
    private final String threadNamePrefix;
    private final RejectionPolicy rejectionPolicy;

    private ExecutorConfig(Builder builder) {
        this.concurrency = builder.concurrency;
        this.queueCapacity = builder.queueCapacity;
        this.taskTimeout = builder.taskTimeout;
        this.daemon = builder.daemon;
        this.threadNamePrefix = builder.threadNamePrefix;
        this.rejectionPolicy = builder.rejectionPolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ExecutorConfig defaultConfig() {
        return builder().build();
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public Duration getTaskTimeout() {
        return taskTimeout;
    }

    public boolean isDaemon() {
        return daemon;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public RejectionPolicy getRejectionPolicy() {
        return rejectionPolicy;
    }

    /**
     * Policy for handling tasks when the queue is full.
     */
    public enum RejectionPolicy {
        /** Block the submitting thread until space is available */
        BLOCK,
        /** Reject the task immediately with an exception */
        REJECT,
        /** Run the task in the calling thread (caller-runs policy) */
        CALLER_RUNS
    }

    public static class Builder {
        private int concurrency = 10;
        private int queueCapacity = 1000;
        private Duration taskTimeout = Duration.ofMinutes(5);
        private boolean daemon = false;
        private String threadNamePrefix = "bounded-executor";
        private RejectionPolicy rejectionPolicy = RejectionPolicy.BLOCK;

        private Builder() {}

        /**
         * Sets the maximum number of concurrent task executions.
         * This is the key parameter for controlling parallelism.
         * 
         * <p>For I/O-bound tasks (like CORBA calls), this can be higher
         * than CPU cores. For CPU-bound tasks, use cores * 1-2.
         * 
         * Default: 10
         */
        public Builder concurrency(int concurrency) {
            if (concurrency < 1) {
                throw new IllegalArgumentException("concurrency must be >= 1");
            }
            this.concurrency = concurrency;
            return this;
        }

        /**
         * Sets the maximum number of pending tasks in the queue.
         * When exceeded, the rejection policy is applied.
         * 
         * Default: 1000
         */
        public Builder queueCapacity(int queueCapacity) {
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("queueCapacity must be >= 1");
            }
            this.queueCapacity = queueCapacity;
            return this;
        }

        /**
         * Sets the maximum time a single task can run before being cancelled.
         * 
         * Default: 5 minutes
         */
        public Builder taskTimeout(Duration taskTimeout) {
            this.taskTimeout = taskTimeout;
            return this;
        }

        /**
         * Whether executor threads should be daemon threads.
         * Daemon threads don't prevent JVM shutdown.
         * 
         * <p>For production use in Spring/JBoss: set to false and
         * ensure proper shutdown is called.
         * 
         * Default: false
         */
        public Builder daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * Prefix for thread names, useful for debugging and monitoring.
         * 
         * Default: "bounded-executor"
         */
        public Builder threadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
            return this;
        }

        /**
         * Policy for handling tasks when the queue is full.
         * 
         * <p>Recommendations:
         * <ul>
         *   <li>BLOCK: Best for batch processing, prevents overload</li>
         *   <li>REJECT: Best for services that need backpressure</li>
         *   <li>CALLER_RUNS: Degrades gracefully under load</li>
         * </ul>
         * 
         * Default: BLOCK
         */
        public Builder rejectionPolicy(RejectionPolicy rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
            return this;
        }

        public ExecutorConfig build() {
            return new ExecutorConfig(this);
        }
    }
}
