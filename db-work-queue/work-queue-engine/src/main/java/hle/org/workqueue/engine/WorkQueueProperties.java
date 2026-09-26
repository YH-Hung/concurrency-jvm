package hle.org.workqueue.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Engine settings, bound from {@code workqueue.*} (spec §6). Every default is the spec's default column.
 * {@link TimingBudget#check} validates the timing constraints between them at startup. Settings used only by
 * later phases (expected-namespace, admin) arrive with the components that read them.
 */
@ConfigurationProperties("workqueue")
public class WorkQueueProperties {

    private int concurrency = 16;
    private int claimBatchSize = 20;
    /** L: a claim or renewal sets AVAILABLE_AT to now + lease. */
    private Duration leaseDuration = Duration.ofSeconds(100);
    /** I: the least time between the starts of two successful renewal rounds. */
    private Duration renewInterval = Duration.ofSeconds(15);
    /** d: the time between a failed renewal round's end and the next round's start. */
    private Duration renewRetryDelay = Duration.ofSeconds(1);
    /** G: the longest in-memory time between a claim operation returning and its handle's registration. */
    private Duration registrationAllowance = Duration.ofSeconds(1);
    /** P: the sleep after a claim that returned nothing, before ±50% jitter. */
    private Duration idlePollInterval = Duration.ofSeconds(1);
    private Duration pollBackoffMax = Duration.ofSeconds(30);
    private Duration sweepInterval = Duration.ofSeconds(30);
    private int sweepBatchSize = 100;
    private Duration supervisorInterval = Duration.ofSeconds(1);
    private int maxAttempts = 5;
    private Duration retryBackoff = Duration.ofSeconds(5);
    private Duration externalCallTimeout = Duration.ofSeconds(30);
    private int completionRetries = 3;
    private Duration completionRetryDelay = Duration.ofSeconds(1);
    /** M: a claim is cancelled this long after it was claimed. */
    private Duration maxProcessingTime = Duration.ofSeconds(120);
    private Duration hungGrace = Duration.ofSeconds(30);
    private int hungTaskLimit = 4;
    private Duration shutdownGrace = Duration.ofSeconds(20);
    private Duration shutdownCancelWait = Duration.ofSeconds(5);
    private Duration backlogSampleInterval = Duration.ofSeconds(30);
    private Duration dbStalenessLimit = Duration.ofSeconds(90);
    private final Db db = new Db();

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getRenewInterval() {
        return renewInterval;
    }

    public void setRenewInterval(Duration renewInterval) {
        this.renewInterval = renewInterval;
    }

    public Duration getRenewRetryDelay() {
        return renewRetryDelay;
    }

    public void setRenewRetryDelay(Duration renewRetryDelay) {
        this.renewRetryDelay = renewRetryDelay;
    }

    public Duration getRegistrationAllowance() {
        return registrationAllowance;
    }

    public void setRegistrationAllowance(Duration registrationAllowance) {
        this.registrationAllowance = registrationAllowance;
    }

    public Duration getIdlePollInterval() {
        return idlePollInterval;
    }

    public void setIdlePollInterval(Duration idlePollInterval) {
        this.idlePollInterval = idlePollInterval;
    }

    public Duration getPollBackoffMax() {
        return pollBackoffMax;
    }

    public void setPollBackoffMax(Duration pollBackoffMax) {
        this.pollBackoffMax = pollBackoffMax;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }

    public int getSweepBatchSize() {
        return sweepBatchSize;
    }

    public void setSweepBatchSize(int sweepBatchSize) {
        this.sweepBatchSize = sweepBatchSize;
    }

    public Duration getSupervisorInterval() {
        return supervisorInterval;
    }

    public void setSupervisorInterval(Duration supervisorInterval) {
        this.supervisorInterval = supervisorInterval;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public Duration getExternalCallTimeout() {
        return externalCallTimeout;
    }

    public void setExternalCallTimeout(Duration externalCallTimeout) {
        this.externalCallTimeout = externalCallTimeout;
    }

    public int getCompletionRetries() {
        return completionRetries;
    }

    public void setCompletionRetries(int completionRetries) {
        this.completionRetries = completionRetries;
    }

    public Duration getCompletionRetryDelay() {
        return completionRetryDelay;
    }

    public void setCompletionRetryDelay(Duration completionRetryDelay) {
        this.completionRetryDelay = completionRetryDelay;
    }

    public Duration getMaxProcessingTime() {
        return maxProcessingTime;
    }

    public void setMaxProcessingTime(Duration maxProcessingTime) {
        this.maxProcessingTime = maxProcessingTime;
    }

    public Duration getHungGrace() {
        return hungGrace;
    }

    public void setHungGrace(Duration hungGrace) {
        this.hungGrace = hungGrace;
    }

    public int getHungTaskLimit() {
        return hungTaskLimit;
    }

    public void setHungTaskLimit(int hungTaskLimit) {
        this.hungTaskLimit = hungTaskLimit;
    }

    public Duration getShutdownGrace() {
        return shutdownGrace;
    }

    public void setShutdownGrace(Duration shutdownGrace) {
        this.shutdownGrace = shutdownGrace;
    }

    public Duration getShutdownCancelWait() {
        return shutdownCancelWait;
    }

    public void setShutdownCancelWait(Duration shutdownCancelWait) {
        this.shutdownCancelWait = shutdownCancelWait;
    }

    public Duration getBacklogSampleInterval() {
        return backlogSampleInterval;
    }

    public void setBacklogSampleInterval(Duration backlogSampleInterval) {
        this.backlogSampleInterval = backlogSampleInterval;
    }

    public Duration getDbStalenessLimit() {
        return dbStalenessLimit;
    }

    public void setDbStalenessLimit(Duration dbStalenessLimit) {
        this.dbStalenessLimit = dbStalenessLimit;
    }

    public Db getDb() {
        return db;
    }

    /** {@code workqueue.db.*}: the JDBC time bounds of spec §5.3, defaulting to {@link DbTimeouts#defaults()}. */
    public static class Db {

        private Duration poolWait = Duration.ofSeconds(2);
        private Duration login = Duration.ofSeconds(3);
        private Duration transaction = Duration.ofSeconds(5);
        private Duration read = Duration.ofSeconds(8);
        private Duration lockWait = Duration.ofSeconds(3);

        public Duration getPoolWait() {
            return poolWait;
        }

        public void setPoolWait(Duration poolWait) {
            this.poolWait = poolWait;
        }

        public Duration getLogin() {
            return login;
        }

        public void setLogin(Duration login) {
            this.login = login;
        }

        public Duration getTransaction() {
            return transaction;
        }

        public void setTransaction(Duration transaction) {
            this.transaction = transaction;
        }

        public Duration getRead() {
            return read;
        }

        public void setRead(Duration read) {
            this.read = read;
        }

        public Duration getLockWait() {
            return lockWait;
        }

        public void setLockWait(Duration lockWait) {
            this.lockWait = lockWait;
        }

        /** These settings as {@link DbTimeouts}, which validates them. */
        public DbTimeouts toTimeouts() {
            return new DbTimeouts(poolWait, login, transaction, read, lockWait);
        }
    }
}
