package hle.org.workqueue.engine;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;

/** The IT column of spec §5.3 and §6, for unit tests. Unlike Db2TestSupport, it never starts Db2. */
final class ItConfig {

    /** Hikari maximum pool size: concurrency + 4 (B3). */
    static final int POOL_SIZE = 8;

    private ItConfig() {
    }

    static WorkQueueProperties properties() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setConcurrency(4);
        properties.setClaimBatchSize(20);
        properties.setLeaseDuration(ofSeconds(30));
        properties.setRenewInterval(ofSeconds(1));
        properties.setRenewRetryDelay(ofMillis(200));
        properties.setRegistrationAllowance(ofMillis(200));
        properties.setIdlePollInterval(ofMillis(100));
        properties.setPollBackoffMax(ofSeconds(2));
        properties.setSweepInterval(ofSeconds(1));
        properties.setSweepBatchSize(100);
        properties.setSupervisorInterval(ofMillis(100));
        properties.setMaxAttempts(5);
        properties.setRetryBackoff(ofMillis(100));
        properties.setExternalCallTimeout(ofSeconds(3));
        properties.setCompletionRetries(2);
        properties.setCompletionRetryDelay(ofMillis(100));
        properties.setMaxProcessingTime(ofSeconds(25));
        properties.setHungGrace(ofSeconds(2));
        properties.setHungTaskLimit(1);
        properties.setShutdownGrace(ofSeconds(2));
        properties.setShutdownCancelWait(ofSeconds(1));
        properties.setBacklogSampleInterval(ofSeconds(1));
        properties.setDbStalenessLimit(ofSeconds(10));
        WorkQueueProperties.Db db = properties.getDb();
        db.setPoolWait(ofMillis(500));
        db.setLogin(ofSeconds(1));
        db.setTransaction(ofSeconds(2));
        db.setRead(ofSeconds(2));
        db.setLockWait(ofSeconds(1));
        return properties;
    }
}
