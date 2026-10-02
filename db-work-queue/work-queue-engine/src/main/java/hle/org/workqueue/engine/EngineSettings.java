package hle.org.workqueue.engine;

import java.time.Duration;

/** The settings the loops use; spec §6 describes each. */
record EngineSettings(int concurrency, int claimBatchSize, Duration idlePollInterval, Duration pollBackoffMax,
                Duration registrationAllowance, Duration renewInterval, Duration renewRetryDelay,
                Duration maxProcessingTime, Duration supervisorInterval, Duration hungGrace, int hungTaskLimit,
                Duration shutdownGrace, Duration shutdownCancelWait, Duration sweepInterval, int sweepBatchSize,
                Duration backlogSampleInterval) {

    EngineSettings {
        requireAtLeastOne("concurrency", concurrency);
        requireAtLeastOne("claimBatchSize", claimBatchSize);
        requireAtLeastOne("hungTaskLimit", hungTaskLimit);
        requireAtLeastOne("sweepBatchSize", sweepBatchSize);
        Durations.requirePositive("idlePollInterval", idlePollInterval);
        Durations.requirePositive("pollBackoffMax", pollBackoffMax);
        Durations.requirePositive("registrationAllowance", registrationAllowance);
        Durations.requirePositive("renewInterval", renewInterval);
        Durations.requirePositive("renewRetryDelay", renewRetryDelay);
        Durations.requirePositive("maxProcessingTime", maxProcessingTime);
        Durations.requirePositive("supervisorInterval", supervisorInterval);
        Durations.requirePositive("hungGrace", hungGrace);
        Durations.requirePositive("shutdownGrace", shutdownGrace);
        Durations.requirePositive("shutdownCancelWait", shutdownCancelWait);
        Durations.requirePositive("sweepInterval", sweepInterval);
        Durations.requirePositive("backlogSampleInterval", backlogSampleInterval);
    }

    static EngineSettings from(WorkQueueProperties properties) {
        return new EngineSettings(properties.getConcurrency(), properties.getClaimBatchSize(),
                properties.getIdlePollInterval(), properties.getPollBackoffMax(),
                properties.getRegistrationAllowance(), properties.getRenewInterval(),
                properties.getRenewRetryDelay(), properties.getMaxProcessingTime(),
                properties.getSupervisorInterval(), properties.getHungGrace(), properties.getHungTaskLimit(),
                properties.getShutdownGrace(), properties.getShutdownCancelWait(), properties.getSweepInterval(),
                properties.getSweepBatchSize(), properties.getBacklogSampleInterval());
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1: " + value);
        }
    }
}
