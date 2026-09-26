# Work Queue Phase 2a: Timing Foundations Implementation Plan

> **Revised after the final review (spec revision 10):** the code blocks and expected values below match the code on
> branch `db-work-queue/phase-2`. The version first executed derived E5 from F*, the number of failed rounds survived
> (`d80f804`); the review corrected E5 to `max(d, L − max(I, W) − 4W − d − G)`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the engine's configuration, renewal schedule, timing budget (B1–B5, E5) and idempotency key, plus `LeaseSimulationTest`, the model-based evidence for B2 and E5 (spec §11.1). These are slices 2.1 and 2.2 of Phase 2.

**Architecture:** These are pure-logic classes in `hle.org.workqueue.engine`, and none of them touches Db2. `WorkQueueProperties` is the `workqueue.*` settings bean. `RenewalSchedule` is the pure next-round function that `QueueRunner` will use in slice 2.5. `LeaseTiming` holds the inputs of the lease argument (I, W, d, G, L) and derives B2 and E5 from them. `TimingBudget` checks B1–B5 at startup. `LeaseSimulation` is a test-scope search over renewal-round interleavings, in 10ms steps, that uses the production `RenewalSchedule`. Its reduced choice of failed-round durations is cross-checked against every duration on small configurations.

**Tech Stack:** JDK 25, Spring Boot 4.1.1 (`@ConfigurationProperties`, `Binder`), JUnit 5 with `junit-jupiter-params`, AssertJ. Build with the Maven wrapper in `db-work-queue/`.

**Spec:** `docs/superpowers/specs/2026-09-21-db-work-queue-design.md`, revision 10: §5.3 timing budget, §5.4 `IdempotencyKey`, §6 configuration, §7 E5, §11.1 unit tests.

## Global Constraints

- Package `hle.org.workqueue.engine`. Main code goes in `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/`, tests in `.../src/test/java/hle/org/workqueue/engine/`.
- Run every command from `db-work-queue/` with `./mvnw`, never `mvn`, which is not on PATH.
- Unit tests (`*Test`) must not start Db2: never reference `Db2TestSupport` from them. Use `ItConfig` (Task 1) for the IT column.
- Default column (spec §6, revision 9): lease 100s, renew-interval 15s, renew-retry-delay 1s, registration-allowance 1s, W = 18s (T_pool 2s + T_login 3s + T_tx 5s + T_read 8s), pool size 20.
- IT column: lease 30s, renew-interval 1s, renew-retry-delay 200ms, registration-allowance 200ms, W = 5.5s (500ms + 1s + 2s + 2s), T_lock 1s, pool size 8.
- Expected derived values: default B2 74s < 100s, E5 = max(d, L − max(I, W) − 4W − d − G) = 8s. IT: B2 22.4s < 30s, E5 = 2.1s.
- The engine never logs idempotency keys, payloads or results (spec §5.4). `IdempotencyKey.toString()` must not reveal the key, and validation messages must not echo the rejected value.
- Match the existing style: records with compact-constructor validation, `IllegalArgumentException` for a bad argument, `Objects.requireNonNull(value, "name")`, Javadoc on public types, and comments only where the reason isn't obvious.
- Scope: `ExternalService`, `CallResult` and `Outcome` move to the `ItemProcessor` slice (2.4), which is their first user. `expected-namespace` and `admin.write-enabled` arrive with `SchemaCheck` and the admin endpoint in Phase 3.

## File Structure

| File | Responsibility |
|---|---|
| `main/.../WorkQueueProperties.java` (create) | `workqueue.*` settings with the spec's defaults, and nested `Db` for `workqueue.db.*` |
| `main/.../Durations.java` (modify) | adds `requirePositive` and `seconds` (formatting) |
| `main/.../RenewalSchedule.java` (create) | `next(start, end, succeeded)` on `nanoTime` readings |
| `main/.../LeaseTiming.java` (create) | package-private record (I, W, d, G, L) with `b2Holds`, `b2Bound` and `leasePreservationTarget` (E5) |
| `main/.../DbTimeouts.java` (modify) | adds `worstCaseOperation()` (W) |
| `main/.../TimingBudget.java` (create) | `check(properties, poolSize)`: B1–B5, reports W and E5 |
| `main/.../IdempotencyKey.java` (create) | validated `(namespace, operationId)`, `value()`, redacted `toString()` |
| `test/.../ItConfig.java` (create) | the IT column as `WorkQueueProperties` |
| `test/.../LeaseSimulation.java` (create) | the §11.1 model |
| `test/.../*Test.java` (create or modify) | one test class per production class, plus `LeaseSimulationTest` |

---

### Task 1: WorkQueueProperties

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueProperties.java`
- Create: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItConfig.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueuePropertiesTest.java`

**Interfaces:**
- Consumes: `DbTimeouts(Duration poolWait, Duration login, Duration transaction, Duration read, Duration lockWait)` and `DbTimeouts.defaults()` (existing).
- Produces: `public class WorkQueueProperties`, annotated `@ConfigurationProperties("workqueue")`, with a getter and setter for each setting: `int concurrency, claimBatchSize, sweepBatchSize, maxAttempts, completionRetries, hungTaskLimit`; `Duration leaseDuration, renewInterval, renewRetryDelay, registrationAllowance, idlePollInterval, pollBackoffMax, sweepInterval, supervisorInterval, retryBackoff, externalCallTimeout, completionRetryDelay, maxProcessingTime, hungGrace, shutdownGrace, shutdownCancelWait, backlogSampleInterval, dbStalenessLimit`. `getDb()` returns `WorkQueueProperties.Db`, which has getters and setters for `poolWait, login, transaction, read, lockWait` plus `DbTimeouts toTimeouts()`. Test support: `ItConfig.properties()` returns the IT column, and `ItConfig.POOL_SIZE = 8`.

- [ ] **Step 1: Write the failing test and the IT-column helper**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueuePropertiesTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkQueuePropertiesTest {

    @Test
    void defaultsAreTheSpecDefaultColumn() {
        WorkQueueProperties properties = new WorkQueueProperties();

        assertThat(properties.getConcurrency()).isEqualTo(16);
        assertThat(properties.getClaimBatchSize()).isEqualTo(20);
        assertThat(properties.getLeaseDuration()).isEqualTo(ofSeconds(100));
        assertThat(properties.getRenewInterval()).isEqualTo(ofSeconds(15));
        assertThat(properties.getRenewRetryDelay()).isEqualTo(ofSeconds(1));
        assertThat(properties.getRegistrationAllowance()).isEqualTo(ofSeconds(1));
        assertThat(properties.getIdlePollInterval()).isEqualTo(ofSeconds(1));
        assertThat(properties.getPollBackoffMax()).isEqualTo(ofSeconds(30));
        assertThat(properties.getSweepInterval()).isEqualTo(ofSeconds(30));
        assertThat(properties.getSweepBatchSize()).isEqualTo(100);
        assertThat(properties.getSupervisorInterval()).isEqualTo(ofSeconds(1));
        assertThat(properties.getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRetryBackoff()).isEqualTo(ofSeconds(5));
        assertThat(properties.getExternalCallTimeout()).isEqualTo(ofSeconds(30));
        assertThat(properties.getCompletionRetries()).isEqualTo(3);
        assertThat(properties.getCompletionRetryDelay()).isEqualTo(ofSeconds(1));
        assertThat(properties.getMaxProcessingTime()).isEqualTo(ofSeconds(120));
        assertThat(properties.getHungGrace()).isEqualTo(ofSeconds(30));
        assertThat(properties.getHungTaskLimit()).isEqualTo(4);
        assertThat(properties.getShutdownGrace()).isEqualTo(ofSeconds(20));
        assertThat(properties.getShutdownCancelWait()).isEqualTo(ofSeconds(5));
        assertThat(properties.getBacklogSampleInterval()).isEqualTo(ofSeconds(30));
        assertThat(properties.getDbStalenessLimit()).isEqualTo(ofSeconds(90));
    }

    @Test
    void dbDefaultsAreTheDefaultTimeouts() {
        assertThat(new WorkQueueProperties().getDb().toTimeouts()).isEqualTo(DbTimeouts.defaults());
    }

    @Test
    void dbSettingsAreValidatedAsDbTimeouts() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setLogin(ofMillis(1500));

        assertThatThrownBy(() -> properties.getDb().toTimeouts())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("login");
    }

    @Test
    void bindsTheSpecPropertyNames() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "workqueue.lease-duration", "30s",
                "workqueue.renew-retry-delay", "200ms",
                "workqueue.completion-retries", "2",
                "workqueue.db.pool-wait", "500ms",
                "workqueue.db.lock-wait", "1s"));

        WorkQueueProperties properties = new Binder(source)
                .bind("workqueue", Bindable.ofInstance(new WorkQueueProperties()))
                .get();

        assertThat(properties.getLeaseDuration()).isEqualTo(ofSeconds(30));
        assertThat(properties.getRenewRetryDelay()).isEqualTo(ofMillis(200));
        assertThat(properties.getCompletionRetries()).isEqualTo(2);
        assertThat(properties.getDb().getPoolWait()).isEqualTo(ofMillis(500));
        assertThat(properties.getDb().getLockWait()).isEqualTo(ofSeconds(1));
        assertThat(properties.getConcurrency()).isEqualTo(16);
    }
}
```

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItConfig.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=WorkQueuePropertiesTest`
Expected: `BUILD FAILURE`, with a compilation error `cannot find symbol ... class WorkQueueProperties`.

- [ ] **Step 3: Write `WorkQueueProperties`**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueProperties.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=WorkQueuePropertiesTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueProperties.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItConfig.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueuePropertiesTest.java
git commit -m "feat: add workqueue.* configuration properties"
```

---

### Task 2: RenewalSchedule

**Files:**
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Durations.java` (add `requirePositive`)
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/RenewalSchedule.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RenewalScheduleTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `static void Durations.requirePositive(String name, Duration value)`, which throws `NullPointerException(name)` for null and `IllegalArgumentException` (the message contains `name`) for zero or negative values. `public record RenewalSchedule(Duration interval, Duration retryDelay)` with `public long next(long start, long end, boolean succeeded)`: after a success, `max(start + I, end)`, compared overflow-safely; after a failure, `end + d`. It throws `IllegalArgumentException` if `end - start < 0`. Task 6 and slice 2.5's `QueueRunner` rely on this exact signature.

- [ ] **Step 1: Write the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RenewalScheduleTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenewalScheduleTest {

    private static final long SECOND = ofSeconds(1).toNanos();
    private static final RenewalSchedule SCHEDULE = new RenewalSchedule(ofSeconds(15), ofSeconds(1));

    @Test
    void aRoundThatEndsEarlyIsFollowedOneIntervalAfterItsStart() {
        assertThat(SCHEDULE.next(0, 2 * SECOND, true)).isEqualTo(15 * SECOND);
    }

    @Test
    void aRoundThatEndsExactlyAtTheIntervalIsFollowedThen() {
        assertThat(SCHEDULE.next(0, 15 * SECOND, true)).isEqualTo(15 * SECOND);
    }

    @Test
    void aRoundThatOverrunsTheIntervalIsFollowedAsSoonAsItEnds() {
        assertThat(SCHEDULE.next(0, 18 * SECOND, true)).isEqualTo(18 * SECOND);
    }

    @Test
    void aFailedRoundIsRetriedTheRetryDelayAfterItEnds() {
        assertThat(SCHEDULE.next(0, 5 * SECOND, false)).isEqualTo(6 * SECOND);
        assertThat(SCHEDULE.next(0, 0, false)).isEqualTo(SECOND);
    }

    @Test
    void comparesNanoTimeReadingsAcrossOverflow() {
        long start = Long.MAX_VALUE - SECOND;

        assertThat(SCHEDULE.next(start, start + 2 * SECOND, true)).isEqualTo(start + 15 * SECOND);
        assertThat(SCHEDULE.next(start, start + 20 * SECOND, true)).isEqualTo(start + 20 * SECOND);
    }

    @Test
    void rejectsARoundThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> SCHEDULE.next(SECOND, 0, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveSettings() {
        assertThatThrownBy(() -> new RenewalSchedule(Duration.ZERO, ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interval");
        assertThatThrownBy(() -> new RenewalSchedule(ofSeconds(15), ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retryDelay");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=RenewalScheduleTest`
Expected: `BUILD FAILURE`, with `cannot find symbol ... class RenewalSchedule`.

- [ ] **Step 3: Add `Durations.requirePositive` and write `RenewalSchedule`**

In `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Durations.java`, add after `requirePositiveWholeSeconds`:

```java
    static void requirePositive(String name, Duration value) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
```

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/RenewalSchedule.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;

/**
 * When the next renewal round starts (spec §5.3): after a successful round that started at {@code s} and
 * ended at {@code e}, at {@code max(s + I, e)}; after a failed round, at {@code e + d}. Times are
 * {@code System.nanoTime()} readings, compared overflow-safely. QueueRunner and LeaseSimulationTest share it.
 *
 * @param interval   I, renew-interval
 * @param retryDelay d, renew-retry-delay
 */
public record RenewalSchedule(Duration interval, Duration retryDelay) {

    public RenewalSchedule {
        Durations.requirePositive("interval", interval);
        Durations.requirePositive("retryDelay", retryDelay);
    }

    public long next(long start, long end, boolean succeeded) {
        if (end - start < 0) {
            throw new IllegalArgumentException("a round cannot end before it starts: start " + start + ", end " + end);
        }
        if (!succeeded) {
            return end + retryDelay.toNanos();
        }
        long earliest = start + interval.toNanos();
        return earliest - end >= 0 ? earliest : end;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=RenewalScheduleTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Durations.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/RenewalSchedule.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RenewalScheduleTest.java
git commit -m "feat: add the renewal schedule"
```

---

### Task 3: LeaseTiming (B2, E5)

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/LeaseTiming.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseTimingTest.java`

**Interfaces:**
- Consumes: `Durations.requirePositive` (Task 2).
- Produces: a package-private `record LeaseTiming(Duration renewInterval, Duration worstCaseOperation, Duration renewRetryDelay, Duration registrationAllowance, Duration lease)` with these package-private methods: `boolean b2Holds()`; `Duration b2Bound()`, which is `max(I, W) + 3W + d + G`; and `Duration leasePreservationTarget()`, which is `max(d, L − max(I, W) − 4W − d − G)`, or `Duration.ZERO` when B2 does not hold (spec §5.3). Tasks 4 and 6 use all three.

- [ ] **Step 1: Write the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseTimingTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseTimingTest {

    /** IT column: I = 1s, W = 5.5s, d = 200ms, G = 200ms, L = 30s. */
    private static final LeaseTiming IT = new LeaseTiming(ofSeconds(1), ofMillis(5500), ofMillis(200), ofMillis(200), ofSeconds(30));

    /** Default column (I = 15s, W = 18s, d = 1s, G = 1s) with the given lease. */
    private static LeaseTiming defaultsWithLease(long leaseSeconds) {
        return new LeaseTiming(ofSeconds(15), ofSeconds(18), ofSeconds(1), ofSeconds(1), ofSeconds(leaseSeconds));
    }

    @Test
    void b2BoundIsMaxOfIAndWPlusThreeWPlusDPlusG() {
        assertThat(defaultsWithLease(100).b2Bound()).isEqualTo(ofSeconds(74));
        assertThat(IT.b2Bound()).isEqualTo(ofMillis(22_400));
    }

    @Test
    void b2HoldsOnlyBelowItsBound() {
        assertThat(defaultsWithLease(74).b2Holds()).isFalse();
        assertThat(defaultsWithLease(75).b2Holds()).isTrue();
    }

    @Test
    void leasePreservationTargetIsPositiveExactlyWhenB2Holds() {
        assertThat(defaultsWithLease(74).leasePreservationTarget()).isEqualTo(Duration.ZERO);
        for (long lease = 40; lease <= 160; lease++) {
            LeaseTiming timing = defaultsWithLease(lease);
            assertThat(timing.leasePreservationTarget().isPositive()).as("lease %ds", lease).isEqualTo(timing.b2Holds());
        }
    }

    @Test
    void leasePreservationTargetIsTheLeaseLeftAfterTheWorstRetryChainButAtLeastD() {
        // L − max(I, W) − 4W − d − G = L − 92s, and never below d = 1s
        assertThat(defaultsWithLease(75).leasePreservationTarget()).isEqualTo(ofSeconds(1));
        assertThat(defaultsWithLease(90).leasePreservationTarget()).isEqualTo(ofSeconds(1));
        assertThat(defaultsWithLease(93).leasePreservationTarget()).isEqualTo(ofSeconds(1));
        assertThat(defaultsWithLease(94).leasePreservationTarget()).isEqualTo(ofSeconds(2));
        assertThat(defaultsWithLease(100).leasePreservationTarget()).isEqualTo(ofSeconds(8));
        assertThat(defaultsWithLease(112).leasePreservationTarget()).isEqualTo(ofSeconds(20));
    }

    @Test
    void theItColumnSurvivesA2point1sOutage() {
        // 30s − 5.5s − 22s − 0.2s − 0.2s
        assertThat(IT.leasePreservationTarget()).isEqualTo(ofMillis(2100));
    }

    @Test
    void rejectsNonPositiveInputs() {
        assertThatThrownBy(() -> new LeaseTiming(ofSeconds(15), ofSeconds(18), ofSeconds(1), ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        assertThatThrownBy(() -> new LeaseTiming(ofSeconds(15), ofSeconds(18), Duration.ZERO, ofSeconds(1), ofSeconds(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("renewRetryDelay");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=LeaseTimingTest`
Expected: `BUILD FAILURE`, with `cannot find symbol ... class LeaseTiming`.

- [ ] **Step 3: Write `LeaseTiming`**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/LeaseTiming.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;

/**
 * The inputs of the lease timing argument (spec §5.3) and what follows from them: B2 and E5.
 *
 * @param renewInterval         I
 * @param worstCaseOperation    W, the worst case for one DB operation
 * @param renewRetryDelay       d
 * @param registrationAllowance G
 * @param lease                 L
 */
record LeaseTiming(Duration renewInterval, Duration worstCaseOperation, Duration renewRetryDelay,
                   Duration registrationAllowance, Duration lease) {

    LeaseTiming {
        Durations.requirePositive("renewInterval", renewInterval);
        Durations.requirePositive("worstCaseOperation", worstCaseOperation);
        Durations.requirePositive("renewRetryDelay", renewRetryDelay);
        Durations.requirePositive("registrationAllowance", registrationAllowance);
        Durations.requirePositive("lease", lease);
    }

    /** B2: every claim, including a new one, survives one failed renewal round. */
    boolean b2Holds() {
        return b2Bound().compareTo(lease) < 0;
    }

    /** {@code max(I, W) + 3W + d + G}, which B2 requires to be below L. */
    Duration b2Bound() {
        return untilFirstCoveringRoundEnds().plus(retry());
    }

    /**
     * E5: an outage up to this long loses no claim (the lease-preservation target, spec §7),
     * {@code max(d, L − max(I, W) − 4W − d − G)}, or zero when B2 does not hold. An outage of length D that
     * begins as the first round including a new claim ends (by {@code c + max(I, W) + 2W + G}) fails it, the
     * last round it fails can start just before it ends and still take W, and the retry writes by {@code d + W}
     * later, so the write lands by {@code c + max(I, W) + 4W + G + D + d}; an outage no longer than d fails at
     * most one round, which B2 covers.
     */
    Duration leasePreservationTarget() {
        if (!b2Holds()) {
            return Duration.ZERO;
        }
        Duration afterWorstRetryChain = lease.minus(longerOfIAndW()).minus(worstCaseOperation.multipliedBy(4))
                .minus(renewRetryDelay).minus(registrationAllowance);
        return afterWorstRetryChain.compareTo(renewRetryDelay) > 0 ? afterWorstRetryChain : renewRetryDelay;
    }

    // max(I, W) + 2W + G: a new claim's lease is written by c, it is registered by c + W + G, the first round
    // that includes it starts by c + W + G + max(I, W) and writes by W later.
    private Duration untilFirstCoveringRoundEnds() {
        return longerOfIAndW().plus(worstCaseOperation.multipliedBy(2)).plus(registrationAllowance);
    }

    private Duration longerOfIAndW() {
        return renewInterval.compareTo(worstCaseOperation) >= 0 ? renewInterval : worstCaseOperation;
    }

    // W + d: one failed round and the delay before its retry.
    private Duration retry() {
        return worstCaseOperation.plus(renewRetryDelay);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=LeaseTimingTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/LeaseTiming.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseTimingTest.java
git commit -m "feat: derive B2 and E5 from the lease timing inputs"
```

---

### Task 4: TimingBudget (B1–B5)

**Files:**
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/DbTimeouts.java` (add `worstCaseOperation()`)
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Durations.java` (add `seconds`)
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/TimingBudget.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DbTimeoutsTest.java` (add one test)
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/TimingBudgetTest.java`

**Interfaces:**
- Consumes: `WorkQueueProperties` and `ItConfig` (Task 1); `Durations.requirePositive` (Task 2); `LeaseTiming` (Task 3).
- Produces:
  - `public Duration DbTimeouts.worstCaseOperation()`, which is W.
  - `static String Durations.seconds(Duration)`, which formats `18s` or `22.4s`.
  - `public final class TimingBudget` with `public static TimingBudget check(WorkQueueProperties properties, int poolSize)`. It throws `IllegalArgumentException` for an out-of-range setting, whose message names the property in kebab case; `lease-duration` must be a whole number of seconds. It throws `IllegalStateException` listing every violated constraint, each in the form `"B<n>: <constraint>, but <actual>"`.
  - `TimingBudget` accessors: `public Duration worstCaseOperation()`, `public Duration leasePreservationTarget()`, and the package-private `LeaseTiming leaseTiming()`. Task 6 uses the last one.
  - B3 computes `concurrency + 4` in `long` arithmetic, so a huge concurrency cannot overflow past the check.

- [ ] **Step 1: Write the failing tests**

In `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DbTimeoutsTest.java`, add before `exposesTheTransactionTimeoutInWholeSeconds`:

```java
    @Test
    void worstCaseOperationIsPoolWaitPlusLoginPlusTransactionPlusRead() {
        assertThat(DbTimeouts.defaults().worstCaseOperation()).isEqualTo(ofSeconds(18));
        assertThat(IT.worstCaseOperation()).isEqualTo(ofMillis(5500));
    }
```

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/TimingBudgetTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimingBudgetTest {

    /** concurrency + 4 with the default concurrency of 16. */
    private static final int DEFAULT_POOL_SIZE = 20;

    @Test
    void theDefaultConfigPassesWithTheSpecTimingValues() {
        TimingBudget budget = TimingBudget.check(new WorkQueueProperties(), DEFAULT_POOL_SIZE);

        assertThat(budget.worstCaseOperation()).isEqualTo(ofSeconds(18));
        assertThat(budget.leasePreservationTarget()).isEqualTo(ofSeconds(8));
    }

    @Test
    void theItConfigPassesWithTheSpecTimingValues() {
        TimingBudget budget = TimingBudget.check(ItConfig.properties(), ItConfig.POOL_SIZE);

        assertThat(budget.worstCaseOperation()).isEqualTo(ofMillis(5500));
        assertThat(budget.leasePreservationTarget()).isEqualTo(ofMillis(2100));
    }

    @Test
    void b1RejectsALockWaitThatIsNotShorterThanTheTransaction() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setLockWait(ofSeconds(5));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B1")
                .hasMessageContaining("5s >= 5s")
                .hasMessageNotContaining("B2");
    }

    @Test
    void b1AcceptsALockWaitJustShorterThanTheTransaction() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setLockWait(ofSeconds(4));

        TimingBudget.check(properties, DEFAULT_POOL_SIZE);
    }

    @Test
    void b2RejectsTheSpecExample() {
        // I = 15s, W = 5s, d = 1s, G = 1s, L = 26s: 15 + 15 + 1 + 1 = 32, not < 26
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.getDb().setPoolWait(ofSeconds(1));
        properties.getDb().setLogin(ofSeconds(1));
        properties.getDb().setTransaction(ofSeconds(2));
        properties.getDb().setRead(ofSeconds(1));
        properties.getDb().setLockWait(ofSeconds(1));
        properties.setLeaseDuration(ofSeconds(26));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B2")
                .hasMessageContaining("32s >= 26s")
                .hasMessageNotContaining("B4");
    }

    @Test
    void b2RejectsTheDefaultTimeoutsWithA60sLease() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setLeaseDuration(ofSeconds(60));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B2")
                .hasMessageContaining("74s >= 60s");
    }

    @Test
    void b3RejectsAPoolWithoutFourSpareConnections() {
        assertThatThrownBy(() -> TimingBudget.check(new WorkQueueProperties(), 19))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B3")
                .hasMessageContaining("19 < 20");
    }

    @Test
    void b3DoesNotOverflowForAHugeConcurrency() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setConcurrency(Integer.MAX_VALUE);

        assertThatThrownBy(() -> TimingBudget.check(properties, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B3")
                .hasMessageContaining("1 < 2147483651");
    }

    @Test
    void b4RejectsAProcessingTimeThatCutsOffASlowHealthyTask() {
        // 30s + (3 + 1)·18s + 3·1s = 105s
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setMaxProcessingTime(ofSeconds(104));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B4")
                .hasMessageContaining("104s < 105s");

        properties.setMaxProcessingTime(ofSeconds(105));
        TimingBudget.check(properties, DEFAULT_POOL_SIZE);
    }

    @Test
    void b5RejectsAStalenessLimitAHealthyIdleInstanceCanReach() {
        // 1.5·1s + 18s = 19.5s
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setDbStalenessLimit(ofMillis(19_500));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B5")
                .hasMessageContaining("19.5s <= 19.5s");
    }

    @Test
    void namesEveryViolatedConstraint() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setLeaseDuration(ofSeconds(60));

        assertThatThrownBy(() -> TimingBudget.check(properties, 19))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B2")
                .hasMessageContaining("B3");
    }

    @Test
    void rejectsOutOfRangeSettingsBeforeCheckingTheConstraints() {
        WorkQueueProperties zeroInterval = new WorkQueueProperties();
        zeroInterval.setRenewInterval(Duration.ZERO);
        WorkQueueProperties negativeRetries = new WorkQueueProperties();
        negativeRetries.setCompletionRetries(-1);

        assertThatThrownBy(() -> TimingBudget.check(zeroInterval, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("renew-interval");
        assertThatThrownBy(() -> TimingBudget.check(negativeRetries, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completion-retries");
        assertThatThrownBy(() -> TimingBudget.check(new WorkQueueProperties(), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pool size");
    }

    @Test
    void rejectsALeaseThatIsNotAWholeNumberOfSeconds() {
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setLeaseDuration(ofMillis(100_500));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease-duration");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=DbTimeoutsTest,TimingBudgetTest`
Expected: `BUILD FAILURE`, with `cannot find symbol` for `worstCaseOperation()` and `class TimingBudget`.

- [ ] **Step 3: Add `DbTimeouts.worstCaseOperation()`, `Durations.seconds` and write `TimingBudget`**

In `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/DbTimeouts.java`, add before `transactionSeconds()`:

```java
    /** W: the worst case for one DB operation, T_pool + T_login + T_tx + T_read (spec §5.3). */
    public Duration worstCaseOperation() {
        return poolWait.plus(login).plus(transaction).plus(read);
    }
```

In `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Durations.java`, add `import java.math.BigDecimal;` and, at the end of the class:

```java
    /** Seconds as the spec writes them, e.g. "18s" or "22.4s" (millisecond precision). */
    static String seconds(Duration value) {
        return BigDecimal.valueOf(value.toMillis(), 3).stripTrailingZeros().toPlainString() + "s";
    }
```

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/TimingBudget.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static hle.org.workqueue.engine.Durations.seconds;

/**
 * The startup timing constraints B1–B5 of spec §5.3. {@link #check} fails startup with an
 * IllegalStateException that names every violated constraint; a budget exists only for a configuration
 * that passes, and reports the timing values the spec derives from it.
 */
public final class TimingBudget {

    private final LeaseTiming leaseTiming;

    private TimingBudget(LeaseTiming leaseTiming) {
        this.leaseTiming = leaseTiming;
    }

    /**
     * @param poolSize the maximum size of the engine's connection pool
     * @throws IllegalArgumentException if a setting the constraints use is out of range
     * @throws IllegalStateException    if any of B1–B5 is violated
     */
    public static TimingBudget check(WorkQueueProperties properties, int poolSize) {
        Objects.requireNonNull(properties, "properties");
        requireInRange(properties, poolSize);
        DbTimeouts db = properties.getDb().toTimeouts();
        Duration w = db.worstCaseOperation();
        LeaseTiming leaseTiming = new LeaseTiming(properties.getRenewInterval(), w, properties.getRenewRetryDelay(),
                properties.getRegistrationAllowance(), properties.getLeaseDuration());

        List<String> violations = new ArrayList<>();
        if (db.lockWait().compareTo(db.transaction()) >= 0) {
            violations.add("B1: T_lock < T_tx, but " + seconds(db.lockWait()) + " >= " + seconds(db.transaction()));
        }
        if (!leaseTiming.b2Holds()) {
            violations.add("B2: max(I, W) + 3W + d + G < L, but "
                    + seconds(leaseTiming.b2Bound()) + " >= " + seconds(properties.getLeaseDuration()));
        }
        long minPoolSize = properties.getConcurrency() + 4L;
        if (poolSize < minPoolSize) {
            violations.add("B3: pool size >= concurrency + 4, but " + poolSize + " < " + minPoolSize);
        }
        Duration slowHealthyTask = properties.getExternalCallTimeout()
                .plus(w.multipliedBy(properties.getCompletionRetries() + 1L))
                .plus(properties.getCompletionRetryDelay().multipliedBy(properties.getCompletionRetries()));
        if (properties.getMaxProcessingTime().compareTo(slowHealthyTask) < 0) {
            violations.add("B4: max-processing-time >= external-call-timeout + (completion-retries + 1)·W"
                    + " + completion-retries·completion-retry-delay, but "
                    + seconds(properties.getMaxProcessingTime()) + " < " + seconds(slowHealthyTask));
        }
        Duration idleStaleness = properties.getIdlePollInterval().multipliedBy(3).dividedBy(2).plus(w);
        if (properties.getDbStalenessLimit().compareTo(idleStaleness) <= 0) {
            violations.add("B5: db-staleness-limit > 1.5·idle-poll-interval + W, but "
                    + seconds(properties.getDbStalenessLimit()) + " <= " + seconds(idleStaleness));
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Timing budget violated (spec §5.3): " + String.join("; ", violations));
        }
        return new TimingBudget(leaseTiming);
    }

    /** W: the worst case for one DB operation. */
    public Duration worstCaseOperation() {
        return leaseTiming.worstCaseOperation();
    }

    /** E5: an outage up to this long loses no claim (the lease-preservation target, spec §7). */
    public Duration leasePreservationTarget() {
        return leaseTiming.leasePreservationTarget();
    }

    LeaseTiming leaseTiming() {
        return leaseTiming;
    }

    private static void requireInRange(WorkQueueProperties properties, int poolSize) {
        if (properties.getConcurrency() < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1: " + properties.getConcurrency());
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("pool size must be at least 1: " + poolSize);
        }
        if (properties.getCompletionRetries() < 0) {
            throw new IllegalArgumentException("completion-retries must not be negative: " + properties.getCompletionRetries());
        }
        Durations.requirePositiveWholeSeconds("lease-duration", properties.getLeaseDuration());
        Durations.requirePositive("renew-interval", properties.getRenewInterval());
        Durations.requirePositive("renew-retry-delay", properties.getRenewRetryDelay());
        Durations.requirePositive("registration-allowance", properties.getRegistrationAllowance());
        Durations.requirePositive("idle-poll-interval", properties.getIdlePollInterval());
        Durations.requirePositive("external-call-timeout", properties.getExternalCallTimeout());
        Durations.requirePositive("completion-retry-delay", properties.getCompletionRetryDelay());
        Durations.requirePositive("max-processing-time", properties.getMaxProcessingTime());
        Durations.requirePositive("db-staleness-limit", properties.getDbStalenessLimit());
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=DbTimeoutsTest,TimingBudgetTest`
Expected: `Tests run: 8` (DbTimeoutsTest) and `Tests run: 13` (TimingBudgetTest), `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/DbTimeouts.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Durations.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/TimingBudget.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DbTimeoutsTest.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/TimingBudgetTest.java
git commit -m "feat: check the B1-B5 timing budget at startup"
```

---

### Task 5: IdempotencyKey

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/IdempotencyKey.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/IdempotencyKeyTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `public record IdempotencyKey(String namespace, String operationId)`. The namespace must match `^[a-z0-9][a-z0-9-]{0,31}$` and the operation id `^[!-~]{1,64}$`; anything else throws `IllegalArgumentException`, and null throws `NullPointerException`. `public String value()` returns `namespace + ":" + operationId`, and `toString()` returns `IdempotencyKey[redacted]`. Slice 2.4's `ItemProcessor` builds these.

- [ ] **Step 1: Write the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/IdempotencyKeyTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void rejectsANamespaceContainingAColon() {
        assertThatThrownBy(() -> new IdempotencyKey("a:b", "c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnOperationIdContainingColons() {
        assertThat(new IdempotencyKey("a", "b:c").value()).isEqualTo("a:b:c");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "-a", "a_b", "a b", "a.b", "é", "abcdefghijklmnopqrstuvwxyz0123456"})
    void rejectsNamespacesOutsideTheFormat(String namespace) {
        assertThatThrownBy(() -> new IdempotencyKey(namespace, "op-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "a-", "prod-eu-1", "abcdefghijklmnopqrstuvwxyz012345"})
    void acceptsNamespacesInTheFormat(String namespace) {
        assertThat(new IdempotencyKey(namespace, "op-1").namespace()).isEqualTo(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "tab\there", "é", "\u007f",
            "0123456789012345678901234567890123456789012345678901234567890123x"})
    void rejectsOperationIdsOutsideTheFormat(String operationId) {
        assertThatThrownBy(() -> new IdempotencyKey("it", operationId)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "~", "order-8812:charge", "0123456789012345678901234567890123456789012345678901234567890123"})
    void acceptsOperationIdsInTheFormat(String operationId) {
        assertThat(new IdempotencyKey("it", operationId).operationId()).isEqualTo(operationId);
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new IdempotencyKey(null, "op-1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyKey("it", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void distinctPairsGiveDistinctValuesAndTheFirstColonSplitsThem() {
        Random random = new Random(20260925);
        Map<String, IdempotencyKey> byValue = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            // Small alphabets make equal values likely if the encoding were ambiguous.
            IdempotencyKey key = new IdempotencyKey(
                    randomString(random, "ab0", "ab0-", 4),
                    randomString(random, "ab:-~!", "ab:-~!", 6));
            IdempotencyKey previous = byValue.putIfAbsent(key.value(), key);
            assertThat(previous).as("value %s", key.value()).isIn(null, key);

            String value = key.value();
            int separator = value.indexOf(':');
            assertThat(new IdempotencyKey(value.substring(0, separator), value.substring(separator + 1))).isEqualTo(key);
        }
    }

    @Test
    void toStringDoesNotRevealTheKey() {
        assertThat(new IdempotencyKey("it", "order-8812:charge").toString())
                .doesNotContain("order-8812")
                .doesNotContain("it:");
    }

    private static String randomString(Random random, String firstCharacters, String characters, int maxLength) {
        int length = 1 + random.nextInt(maxLength);
        StringBuilder text = new StringBuilder(length);
        text.append(firstCharacters.charAt(random.nextInt(firstCharacters.length())));
        for (int i = 1; i < length; i++) {
            text.append(characters.charAt(random.nextInt(characters.length())));
        }
        return text.toString();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=IdempotencyKeyTest`
Expected: `BUILD FAILURE`, with `cannot find symbol ... class IdempotencyKey`.

- [ ] **Step 3: Write `IdempotencyKey`**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/IdempotencyKey.java`:

```java
package hle.org.workqueue.engine;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The operation identity sent downstream (spec §5.4). {@link #value()} is unambiguous: the namespace never
 * contains ':', so the first ':' always separates the two parts, and the operation id may contain more.
 * {@link #toString()} does not reveal the key, because the engine never logs idempotency keys.
 *
 * @param namespace   {@code ^[a-z0-9][a-z0-9-]{0,31}$}
 * @param operationId {@code ^[!-~]{1,64}$}: printable ASCII, no spaces
 */
public record IdempotencyKey(String namespace, String operationId) {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern OPERATION_ID = Pattern.compile("[!-~]{1,64}");

    public IdempotencyKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(operationId, "operationId");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace must match ^[a-z0-9][a-z0-9-]{0,31}$");
        }
        if (!OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId must be 1 to 64 printable ASCII characters without spaces");
        }
    }

    /** {@code NAMESPACE:OPERATION_ID}, compared exactly (case-sensitive) by the downstream. */
    public String value() {
        return namespace + ":" + operationId;
    }

    @Override
    public String toString() {
        return "IdempotencyKey[redacted]";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=IdempotencyKeyTest`
Expected: `Tests run: 28, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/IdempotencyKey.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/IdempotencyKeyTest.java
git commit -m "feat: add the downstream idempotency key"
```

---

### Task 6: LeaseSimulationTest (evidence for B2 and E5)

**Files:**
- Create: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseSimulation.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseSimulationTest.java`
- Modify: `db-work-queue/README.md` (status line)

**Interfaces:**
- Consumes: `RenewalSchedule` (Task 2); `LeaseTiming` (Task 3); `TimingBudget.check(...).leaseTiming()` and `Durations.seconds` (Task 4); `WorkQueueProperties` and `ItConfig` (Task 1).
- Produces (test scope): `final class LeaseSimulation` with `LeaseSimulation(LeaseTiming timing, Faults faults)`, `LeaseSimulation(LeaseTiming timing, Faults faults, FailedRounds failedRounds)` and `Optional<String> findLoss(Start start)`. `start` is `NEW_CLAIMS` or `MAINTAINED_CLAIMS`; `faults` is `new OneFailedRound()` or `new Outage(Duration length)` (non-null, not negative); `failedRounds` is `REDUCED` (the default) or `EVERY_STEP`. `STEP` is 10ms. The result is a readable description of an interleaving that loses a claim, or empty if there is none.

**How the model works (read before implementing).** Time runs in 10ms steps, and every next round start comes from the production `RenewalSchedule.next`.

- A successful renewal round lasts 0 or W and writes the lease at its start or its end.
- Under `OneFailedRound`, exactly one round anywhere fails, taking 0 or W.
- Under `Outage(D)`, one outage of length at most D may begin at any step. It fails every round it overlaps, including a round it begins in one step before that round ends, and a failed round takes any time up to W: a refused connection fails at once, a stalled one only after W. A zero-length outage fails no round.
- The rounds an outage fails write nothing, so they are played as one choice: all that matters is how many steps pass until the first round start after the outage.
- By default (`REDUCED`), a failed round takes the earliest time it can fail, W, or the aligned duration that makes the next round start on the outage's last step. `EVERY_STEP` tries every duration, and a cross-check test compares the two on small configurations.
- New claims are registered at every step within one round gap after a snapshot that missed them, with the lease written 0, G, W or W + G before registration.
- A claim is lost when a write lands at or after its lease expiry (`AVAILABLE_AT`), or when the lease expires before the next round starts.
- The search keeps only the earliest expiry per (registration offset, fault state). This is sound: a claim's future depends only on those values, and an earlier expiry is never better for it. The test class runs in about 2s.

If the test finds a loss, the failure message shows the interleaving step by step. Treat that as a finding about the spec: do not weaken the test.

- [ ] **Step 1: Write the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseSimulationTest.java`:

```java
package hle.org.workqueue.engine;

import hle.org.workqueue.engine.LeaseSimulation.FailedRounds;
import hle.org.workqueue.engine.LeaseSimulation.OneFailedRound;
import hle.org.workqueue.engine.LeaseSimulation.Outage;
import hle.org.workqueue.engine.LeaseSimulation.Start;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static hle.org.workqueue.engine.LeaseSimulation.STEP;
import static hle.org.workqueue.engine.LeaseSimulation.Start.MAINTAINED_CLAIMS;
import static hle.org.workqueue.engine.LeaseSimulation.Start.NEW_CLAIMS;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evidence for B2 and E5 (spec §11.1): a finite grid of configurations, not a proof. */
class LeaseSimulationTest {

    private static final LeaseTiming DEFAULTS = TimingBudget.check(new WorkQueueProperties(), 20).leaseTiming();
    private static final LeaseTiming IT = TimingBudget.check(ItConfig.properties(), ItConfig.POOL_SIZE).leaseTiming();

    @Test
    void theDefaultAndItConfigsSurviveOneFailedRound() {
        for (LeaseTiming timing : List.of(DEFAULTS, IT)) {
            LeaseSimulation simulation = new LeaseSimulation(timing, new OneFailedRound());
            assertThat(simulation.findLoss(NEW_CLAIMS)).as("%s", timing).isEmpty();
            assertThat(simulation.findLoss(MAINTAINED_CLAIMS)).as("%s", timing).isEmpty();
        }
    }

    @Test
    void theDefaultAndItConfigsSurviveAnOutageUpToE5AndNoLonger() {
        for (LeaseTiming timing : List.of(DEFAULTS, IT)) {
            assertOutageTargetHoldsAndIsTight(timing);
        }
    }

    @Test
    void theRevision8DefaultLeaseLosesAClaimToA1point2sOutage() {
        LeaseTiming lease90s = withLease(DEFAULTS, ofSeconds(90));

        assertThat(new LeaseSimulation(lease90s, new Outage(ofMillis(1200))).findLoss(NEW_CLAIMS)).isPresent();
        assertThat(new LeaseSimulation(lease90s, new Outage(ofSeconds(1))).findLoss(NEW_CLAIMS)).isEmpty();
    }

    @Test
    void theRevision9TargetLosesAClaimToAn8point02sOutage() {
        // Revision 9 claimed 20s: a fast-failing retry chain whose last round takes W loses a claim after 8.02s.
        assertThat(new LeaseSimulation(DEFAULTS, new Outage(ofMillis(8020))).findLoss(NEW_CLAIMS)).isPresent();
        assertThat(new LeaseSimulation(DEFAULTS, new Outage(ofSeconds(8))).findLoss(NEW_CLAIMS)).isEmpty();
    }

    @Test
    void theOneFailureGridIncludesConfigsB2Rejects() {
        List<LeaseTiming> grid = oneFailureGrid();

        assertThat(grid).hasSize(180);
        assertThat(grid).filteredOn(timing -> !timing.b2Holds()).hasSize(36);
    }

    @ParameterizedTest
    @MethodSource("oneFailureGrid")
    void oneFailedRoundLosesAClaimExactlyWhenB2Rejects(LeaseTiming timing) {
        LeaseSimulation simulation = new LeaseSimulation(timing, new OneFailedRound());

        if (timing.b2Holds()) {
            assertThat(simulation.findLoss(NEW_CLAIMS)).isEmpty();
            assertThat(simulation.findLoss(MAINTAINED_CLAIMS)).isEmpty();
        } else {
            assertThat(simulation.findLoss(NEW_CLAIMS)).isPresent();
        }
    }

    @ParameterizedTest
    @MethodSource("outageGrid")
    void noOutageUpToE5LosesAClaimAndOneTwoStepsLongerDoes(LeaseTiming timing) {
        assertOutageTargetHoldsAndIsTight(timing);
    }

    @ParameterizedTest
    @MethodSource("crossCheckConfigs")
    void theReducedFailedRoundsFindTheSameFirstLossAsEveryDuration(LeaseTiming timing) {
        Optional<Duration> newClaimLoss = firstLosingOutage(timing, NEW_CLAIMS, FailedRounds.REDUCED);

        assertThat(newClaimLoss).as("%s", timing).isPresent();
        assertThat(newClaimLoss).as("%s, new claims", timing)
                .isEqualTo(firstLosingOutage(timing, NEW_CLAIMS, FailedRounds.EVERY_STEP));
        assertThat(firstLosingOutage(timing, MAINTAINED_CLAIMS, FailedRounds.REDUCED)).as("%s, maintained claims", timing)
                .isEqualTo(firstLosingOutage(timing, MAINTAINED_CLAIMS, FailedRounds.EVERY_STEP));
    }

    @Test
    void aZeroLengthOutageFailsNoRound() {
        // Renewal without failures needs max(I, W) + 2W + G = 320ms < 400ms; B2 (440ms) rejects one failed round.
        LeaseTiming timing = new LeaseTiming(ofMillis(50), ofMillis(100), ofMillis(20), ofMillis(20), ofMillis(400));

        assertThat(new LeaseSimulation(timing, new OneFailedRound()).findLoss(NEW_CLAIMS)).isPresent();
        for (FailedRounds failedRounds : FailedRounds.values()) {
            LeaseSimulation noOutage = new LeaseSimulation(timing, new Outage(Duration.ZERO), failedRounds);
            assertThat(noOutage.findLoss(NEW_CLAIMS)).as("%s, new claims", failedRounds).isEmpty();
            assertThat(noOutage.findLoss(MAINTAINED_CLAIMS)).as("%s, maintained claims", failedRounds).isEmpty();
        }
    }

    @Test
    void anOutageLengthMustNotBeNullOrNegative() {
        assertThatThrownBy(() -> new Outage(null)).isInstanceOf(NullPointerException.class).hasMessageContaining("length");
        assertThatThrownBy(() -> new Outage(ofMillis(-10))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    /**
     * Small enough to try every failed-round duration: I < W, I > W + d, a larger d, and a lease one step above
     * the B2 bound, where the round an outage begins in must be able to fail early for the retry chain to reach
     * the outage's last step. A d close to W needs the in-outage 0-duration choice, and the last config has
     * d < E5 < 2d, where E5 is conservative.
     */
    static List<LeaseTiming> crossCheckConfigs() {
        LeaseTiming shortInterval = new LeaseTiming(ofMillis(50), ofMillis(100), ofMillis(20), ofMillis(20), ofMillis(730));
        LeaseTiming longInterval = new LeaseTiming(ofMillis(200), ofMillis(100), ofMillis(20), ofMillis(20), ofSeconds(1));
        LeaseTiming longRetryDelay = new LeaseTiming(ofMillis(50), ofMillis(100), ofMillis(50), ofMillis(20), ofSeconds(1));
        return List.of(shortInterval,
                withLease(longInterval, longInterval.b2Bound().plus(ofMillis(120))),
                withLease(longRetryDelay, longRetryDelay.b2Bound().plus(ofMillis(300))),
                withLease(shortInterval, shortInterval.b2Bound().plus(STEP)),
                new LeaseTiming(ofMillis(100), ofMillis(40), ofMillis(30), ofMillis(10), ofMillis(360)),
                withLease(longRetryDelay, ofMillis(640)));
    }

    /** I × W × d × G, each with leases at, just above and well above the B2 bound. */
    static List<LeaseTiming> oneFailureGrid() {
        return grid(List.of(ofSeconds(1), ofSeconds(5), ofSeconds(15)), List.of(ofMillis(3500), ofMillis(5500), ofSeconds(9)),
                List.of(ofMillis(200), ofSeconds(1)), List.of(ofMillis(200), ofSeconds(1)), true);
    }

    /**
     * A smaller grid for outages, which branch at every step; only configs B2 accepts. I = 5s with W = 3.5s
     * covers I > W + d, where a new claim can stay out of the snapshot across a failed round.
     */
    static List<LeaseTiming> outageGrid() {
        return grid(List.of(ofSeconds(1), ofSeconds(5)), List.of(ofMillis(3500), ofSeconds(9)),
                List.of(ofMillis(200), ofSeconds(1)), List.of(ofSeconds(1)), false);
    }

    private static List<LeaseTiming> grid(List<Duration> intervals, List<Duration> operations, List<Duration> retryDelays,
                                          List<Duration> allowances, boolean includeRejected) {
        List<LeaseTiming> grid = new ArrayList<>();
        for (Duration i : intervals) {
            for (Duration w : operations) {
                for (Duration d : retryDelays) {
                    for (Duration g : allowances) {
                        Duration bound = new LeaseTiming(i, w, d, g, ofSeconds(1)).b2Bound();
                        Duration retry = w.plus(d);
                        List<Duration> leases = new ArrayList<>(List.of(bound.plus(STEP), bound.plus(retry),
                                bound.plus(retry.multipliedBy(2)).plus(ofSeconds(1))));
                        if (includeRejected) {
                            leases.addFirst(bound);
                            leases.add(2, bound.plus(STEP.multipliedBy(retry.dividedBy(STEP) / 2)));
                        }
                        for (Duration lease : leases) {
                            grid.add(new LeaseTiming(i, w, d, g, lease));
                        }
                    }
                }
            }
        }
        return grid;
    }

    private static void assertOutageTargetHoldsAndIsTight(LeaseTiming timing) {
        Duration target = timing.leasePreservationTarget();

        LeaseSimulation atTarget = new LeaseSimulation(timing, new Outage(target));
        assertThat(atTarget.findLoss(NEW_CLAIMS)).as("%s, outage %s", timing, target).isEmpty();
        assertThat(atTarget.findLoss(MAINTAINED_CLAIMS)).as("%s, outage %s", timing, target).isEmpty();

        Duration longer = target.plus(STEP.multipliedBy(2));
        assertThat(new LeaseSimulation(timing, new Outage(longer)).findLoss(NEW_CLAIMS))
                .as("%s, outage %s", timing, longer).isPresent();
    }

    /** The shortest outage, in steps from zero up to the lease, that loses a claim. */
    private static Optional<Duration> firstLosingOutage(LeaseTiming timing, Start start, FailedRounds failedRounds) {
        for (Duration outage = Duration.ZERO; outage.compareTo(timing.lease()) <= 0; outage = outage.plus(STEP)) {
            if (new LeaseSimulation(timing, new Outage(outage), failedRounds).findLoss(start).isPresent()) {
                return Optional.of(outage);
            }
        }
        return Optional.empty();
    }

    private static LeaseTiming withLease(LeaseTiming timing, Duration lease) {
        return new LeaseTiming(timing.renewInterval(), timing.worstCaseOperation(), timing.renewRetryDelay(),
                timing.registrationAllowance(), lease);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=LeaseSimulationTest`
Expected: `BUILD FAILURE`, with `cannot find symbol ... class LeaseSimulation`.

- [ ] **Step 3: Write the model**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseSimulation.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Discrete-event model of one instance's lease timeline (spec §11.1), the evidence for B2 and E5. Time runs
 * in 10ms steps, and every next round start comes from the production {@link RenewalSchedule}.
 *
 * <p>The search is exhaustive over these choices of the adversary, not over every behaviour: a successful
 * renewal round lasts 0 or W and writes the lease at its start or its end; a round fails or not as the
 * {@link Faults} allow; the one failed round of {@link OneFailedRound} takes 0 or W; a failed outage round takes
 * the durations {@link FailedRounds} lists. A new claim is registered at every step of a round gap after the
 * start of a round whose snapshot missed it, with its lease written 0, G, W or W + G before registration: the
 * extremes of the claim operation (write at c or c + W, acknowledgement at the write or c + W, registration at
 * the acknowledgement or G later). A claim is lost when a renewal write lands at or after its lease expiry, the
 * moment another instance may claim it.
 *
 * <p>A failed outage round may take any time up to W, but by default ({@link FailedRounds#REDUCED}) the model
 * reduces it to the earliest it can fail, W, or the duration that makes the next round start on the outage's
 * last step. That reduction is not proven: LeaseSimulationTest backs it by comparing it with every duration
 * ({@link FailedRounds#EVERY_STEP}) on small configurations.
 *
 * <p>A claim's future depends only on its registration and lease expiry relative to the current round start
 * and on the fault state, and an earlier expiry is never better for it. So the search keeps, per
 * (registration, fault state), only the earliest expiry seen, which keeps it small without losing a case. For
 * the same reason the rounds an outage fails are played as one choice: they write nothing, and a claim joins
 * the snapshot of the first round that starts after its registration whether the rounds before it failed or
 * not, so all that matters is how many steps pass until the first round start after the outage.
 */
final class LeaseSimulation {

    static final Duration STEP = Duration.ofMillis(10);

    /** Which claims the search starts from. */
    enum Start { NEW_CLAIMS, MAINTAINED_CLAIMS }

    /** How long a round that an {@link Outage} fails may take. */
    enum FailedRounds {
        /**
         * A failed round fails as early as it can, at W, or at the time that makes the next round start on the
         * outage's last step (aligned). As early as it can is 0 for a round that starts while the outage is on,
         * and the later of its start and the outage's start for the round the outage begins in.
         */
        REDUCED,
        /**
         * Every duration: the round the outage begins in fails at every step from the later of its start and the
         * outage's start to W, and a round that starts while the outage is on takes every duration from 0 to W.
         * Only for small configurations.
         */
        EVERY_STEP
    }

    sealed interface Faults permits OneFailedRound, Outage {
    }

    /** Exactly one renewal round fails, whichever the adversary picks. */
    record OneFailedRound() implements Faults {
    }

    /**
     * One Db2 outage of at most this length, beginning at any step. It fails every round it overlaps,
     * including one it begins in a step before that round ends, and a failed round takes up to W. The first
     * round it fails may start before or after it begins, so every shorter outage is covered too.
     */
    record Outage(Duration length) implements Faults {

        Outage {
            Objects.requireNonNull(length, "length");
            if (length.isNegative()) {
                throw new IllegalArgumentException("length must not be negative: " + length);
            }
        }
    }

    // The fault state at a round start, an int whose meaning depends on the fault model.
    // OneFailedRound: FAILURE_LEFT until the failed round, then NO_FAILURE_LEFT.
    // Outage: OUTAGE_NOT_STARTED, then OUTAGE_OVER. No round of the search starts while the outage is on, because
    // the rounds it fails are one choice (outageChoices); building that choice identifies a round that starts
    // while the outage is on by its fault: the steps from the round's start to the outage's end, always positive.
    private static final int FAILURE_LEFT = 1;
    private static final int NO_FAILURE_LEFT = 0;
    private static final int OUTAGE_NOT_STARTED = -1;
    private static final int OUTAGE_OVER = 0;

    private static final long STEP_NANOS = STEP.toNanos();
    /** Registration value of a claim that is in this round's snapshot. */
    private static final int COVERED = -1;
    private static final int NO_WRITE = -1;
    private static final int[] NO_WRITES = new int[0];

    private final int w;
    private final int g;
    private final int lease;
    private final Faults faults;
    private final FailedRounds failedRounds;
    /** Steps from a round's start to the next round's start, by [succeeded][duration]. */
    private final int[][] nextStart;
    /** The choices while the fault is still to come (FAILURE_LEFT, OUTAGE_NOT_STARTED). */
    private final List<Choice> choicesBeforeFault;
    /** The choices once it is over (NO_FAILURE_LEFT, OUTAGE_OVER). */
    private final List<Choice> choicesAfterFault;

    LeaseSimulation(LeaseTiming timing, Faults faults) {
        this(timing, faults, FailedRounds.REDUCED);
    }

    LeaseSimulation(LeaseTiming timing, Faults faults, FailedRounds failedRounds) {
        this.faults = Objects.requireNonNull(faults, "faults");
        this.failedRounds = Objects.requireNonNull(failedRounds, "failedRounds");
        this.w = steps(timing.worstCaseOperation());
        this.g = steps(timing.registrationAllowance());
        this.lease = steps(timing.lease());
        RenewalSchedule schedule = new RenewalSchedule(timing.renewInterval(), timing.renewRetryDelay());
        this.nextStart = new int[2][w + 1];
        for (int duration = 0; duration <= w; duration++) {
            for (boolean succeeded : new boolean[] {false, true}) {
                nextStart[succeeded ? 1 : 0][duration] =
                        steps(Duration.ofNanos(schedule.next(0, duration * STEP_NANOS, succeeded)));
            }
        }
        switch (faults) {
            case OneFailedRound _ -> {
                this.choicesBeforeFault = oneFailureChoices(FAILURE_LEFT);
                this.choicesAfterFault = oneFailureChoices(NO_FAILURE_LEFT);
            }
            case Outage(Duration length) -> {
                List<Choice> before = successes(OUTAGE_NOT_STARTED);
                before.addAll(outageChoices(steps(length)));
                this.choicesBeforeFault = before;
                this.choicesAfterFault = successes(OUTAGE_OVER);
            }
        }
    }

    /** One interleaving that loses a claim, described step by step, or empty if none does. */
    Optional<String> findLoss(Start start) {
        Search search = new Search();
        int fault = switch (faults) {
            case OneFailedRound _ -> FAILURE_LEFT;
            case Outage _ -> OUTAGE_NOT_STARTED;
        };
        if (start == Start.NEW_CLAIMS) {
            int gap = Math.max(next(w, true), next(w, false));
            for (int registration = 0; registration < gap; registration++) {
                for (int writtenBefore : distinct(0, g, w, w + g)) {
                    search.offer(new State(registration, fault), registration - writtenBefore + lease,
                            Path.start("new claim registered " + time(registration) + " after a round start whose "
                                    + "snapshot missed it, lease written " + time(writtenBefore) + " before registration"));
                }
            }
        } else {
            for (int duration : distinct(0, w)) {
                for (int write : distinct(0, duration)) {
                    search.offer(new State(COVERED, fault), write + lease - next(duration, true),
                            Path.start("claim renewed by a " + time(duration) + " round writing at +" + time(write)));
                }
            }
        }
        return search.run();
    }

    private final class Search {

        private final Map<State, Visit> earliest = new HashMap<>();
        private final ArrayDeque<Queued> queue = new ArrayDeque<>();
        private String loss;

        void offer(State state, int expiry, Path path) {
            offer(state, expiry, path, null, NO_WRITE);
        }

        // Records the state if its expiry is the earliest yet; the path is extended only then.
        private void offer(State state, int expiry, Path previous, Choice choice, int write) {
            if (loss != null) {
                return;
            }
            if (expiry <= 0) {
                loss = extend(previous, choice, write).render()
                        + " -> the lease expired before the next round started: claim lost";
                return;
            }
            Visit known = earliest.get(state);
            if (known == null || expiry < known.expiry()) {
                earliest.put(state, new Visit(expiry, extend(previous, choice, write)));
                queue.add(new Queued(state, expiry));
            }
        }

        private static Path extend(Path previous, Choice choice, int write) {
            return choice == null ? previous : new Path(previous, choice, write, null);
        }

        Optional<String> run() {
            while (loss == null && !queue.isEmpty()) {
                Queued queued = queue.poll();
                State state = queued.state();
                Visit visit = earliest.get(state);
                if (visit.expiry() != queued.expiry()) {
                    continue; // improved since it was queued; the newer entry expands it
                }
                for (Choice choice : choices(state.fault())) {
                    play(state, visit, choice);
                    if (loss != null) {
                        break;
                    }
                }
            }
            return Optional.ofNullable(loss);
        }

        private void play(State state, Visit visit, Choice choice) {
            int next = choice.next();
            boolean covered = state.registration() == COVERED;
            int registration = covered || state.registration() - next < 0 ? COVERED : state.registration() - next;
            State following = new State(registration, choice.fault());
            if (!choice.succeeded() || !covered) {
                offer(following, visit.expiry() - next, visit.path(), choice, NO_WRITE);
                return;
            }
            for (int write : choice.writes()) {
                if (write >= visit.expiry()) {
                    loss = extend(visit.path(), choice, write).render()
                            + " -> the write is at or after the lease expiry at +" + time(visit.expiry()) + ": claim lost";
                    return;
                }
                offer(following, write + lease - next, visit.path(), choice, write);
            }
        }
    }

    /** Every choice for the next round, given the fault state at its start. */
    private List<Choice> choices(int fault) {
        return switch (faults) {
            case OneFailedRound _ -> fault == FAILURE_LEFT ? choicesBeforeFault : choicesAfterFault;
            case Outage _ -> fault == OUTAGE_NOT_STARTED ? choicesBeforeFault : choicesAfterFault;
        };
    }

    private List<Choice> successes(int fault) {
        List<Choice> choices = new ArrayList<>();
        for (int duration : distinct(0, w)) {
            choices.add(new Choice(true, next(duration, true), fault, distinct(0, duration),
                    "a " + time(duration) + " round succeeds"));
        }
        return choices;
    }

    private List<Choice> oneFailureChoices(int fault) {
        List<Choice> choices = successes(fault);
        if (fault == FAILURE_LEFT) {
            for (int duration : distinct(0, w)) {
                choices.add(new Choice(false, next(duration, false), NO_FAILURE_LEFT, NO_WRITES,
                        "a " + time(duration) + " round fails"));
            }
        }
        return choices;
    }

    /**
     * An outage that overlaps this round, played to the first round start after it: one choice per number of
     * steps to that start, described by the first way found to get there.
     */
    private List<Choice> outageChoices(int outage) {
        if (outage == 0) {
            return List.of(); // an empty outage overlaps no round
        }
        // The outage ends a step after this round starts at the earliest, and at the latest begins a step before
        // a round of W ends.
        int lastEnd = w - 1 + outage;
        List<Map<Integer, Integer>> inOutage = inOutageContinuations(lastEnd);
        Map<Integer, Choice> byNext = new LinkedHashMap<>();
        for (int end = 1; end <= lastEnd; end++) {
            int earliest = Math.max(0, end - outage);
            int[] failures = switch (failedRounds) {
                case REDUCED -> distinct(earliest, w, aligned(end, earliest));
                case EVERY_STEP -> IntStream.rangeClosed(earliest, w).toArray();
            };
            for (int failure : failures) {
                int retry = next(failure, false);
                String begins = "an outage ending " + time(end) + " after the round start fails it at +" + time(failure);
                if (retry >= end) {
                    byNext.putIfAbsent(retry, new Choice(false, retry, OUTAGE_OVER, NO_WRITES, begins));
                    continue;
                }
                for (int after : inOutage.get(end - retry).keySet()) {
                    int next = retry + after;
                    if (!byNext.containsKey(next)) {
                        byNext.put(next, new Choice(false, next, OUTAGE_OVER, NO_WRITES,
                                begins + describeInOutage(inOutage, end - retry, after)));
                    }
                }
            }
        }
        return List.copyOf(byNext.values());
    }

    /**
     * For a round that starts while the outage is on, indexed by its fault: every number of steps from its start
     * to the first round start after the outage, mapped to the duration this round takes on the first way found.
     */
    private List<Map<Integer, Integer>> inOutageContinuations(int maxFault) {
        List<Map<Integer, Integer>> continuations = new ArrayList<>(List.of(Map.of()));
        for (int fault = 1; fault <= maxFault; fault++) {
            int[] durations = switch (failedRounds) {
                case REDUCED -> distinct(0, w, aligned(fault, 0));
                case EVERY_STEP -> IntStream.rangeClosed(0, w).toArray();
            };
            Map<Integer, Integer> continuation = new LinkedHashMap<>();
            for (int duration : durations) {
                int retry = next(duration, false);
                if (retry >= fault) {
                    continuation.putIfAbsent(retry, duration);
                } else {
                    for (int after : continuations.get(fault - retry).keySet()) {
                        continuation.putIfAbsent(retry + after, duration);
                    }
                }
            }
            continuations.add(continuation);
        }
        return continuations;
    }

    private String describeInOutage(List<Map<Integer, Integer>> inOutage, int fault, int toNextRound) {
        StringBuilder description = new StringBuilder();
        for (int left = fault, steps = toNextRound; ; ) {
            int duration = inOutage.get(left).get(steps);
            description.append(" -> a ").append(time(duration)).append(" round fails in the outage");
            int retry = next(duration, false);
            if (retry >= left) {
                return description.toString();
            }
            left -= retry;
            steps -= retry;
        }
    }

    /**
     * The failed-round duration, at least earliest, that makes the next round start on the outage's last step,
     * the latest a round can start in it; end is the steps from the round's start to the outage's end.
     */
    private int aligned(int end, int earliest) {
        return Math.clamp(end - 1 - next(0, false), earliest, w);
    }

    private int next(int duration, boolean succeeded) {
        return nextStart[succeeded ? 1 : 0][duration];
    }

    private static int steps(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos % STEP_NANOS != 0) {
            throw new IllegalArgumentException(duration + " is not a whole number of " + STEP + " steps");
        }
        return Math.toIntExact(nanos / STEP_NANOS);
    }

    private static int[] distinct(int... values) {
        return IntStream.of(values).distinct().sorted().toArray();
    }

    private static String time(int steps) {
        return Durations.seconds(STEP.multipliedBy(steps));
    }

    /** registration: steps from this round's start until the claim is registered, or COVERED. */
    private record State(int registration, int fault) {
    }

    /** expiry: the claim's lease expiry, in steps after this round's start. */
    private record Visit(int expiry, Path path) {
    }

    private record Queued(State state, int expiry) {
    }

    /** How the search reached a state; described only when a loss is reported. */
    private record Path(Path previous, Choice choice, int write, String start) {

        static Path start(String description) {
            return new Path(null, null, NO_WRITE, description);
        }

        String render() {
            List<String> steps = new ArrayList<>();
            for (Path path = this; path != null; path = path.previous()) {
                if (path.start() != null) {
                    steps.add(path.start());
                } else {
                    String write = path.write() == NO_WRITE ? "" : ", writing at +" + time(path.write());
                    steps.add(path.choice().description() + write);
                }
            }
            Collections.reverse(steps);
            return String.join(" -> ", steps);
        }
    }

    /**
     * One choice for the next round, or for all the rounds an outage fails: next is the steps from this round's
     * start to the round start that follows it, fault the fault state there, and writes the steps at which a
     * successful round may write the lease.
     */
    private record Choice(boolean succeeded, int next, int fault, int[] writes, String description) {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=LeaseSimulationTest`
Expected: `Tests run: 217, Failures: 0, Errors: 0`, taking about 2s, then `BUILD SUCCESS`.

- [ ] **Step 5: Update the README status**

In `db-work-queue/README.md`, replace the status line with:

```markdown
Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations done (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`).
```

- [ ] **Step 6: Run all unit tests**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test`
Expected: `Tests run: 296, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. Docker is not needed.

- [ ] **Step 7: Commit**

```bash
git add db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseSimulation.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/LeaseSimulationTest.java \
        db-work-queue/README.md
git commit -m "test: add the lease simulation behind B2 and E5"
```

---

## Self-Review Notes

- Spec coverage for slices 2.1 and 2.2: `WorkQueueProperties` for the §6 settings Phase 2 uses; `TimingBudget` for §5.3 B1–B5 and the §11.1 `TimingBudgetTest` cases (the spec example rejected by B2, W = 18s rejected with a 60s lease and accepted with 100s, the IT config passing, and E5 of 1s at a 90s lease versus 8s at 100s); `RenewalSchedule` for §5.3 and `RenewalScheduleTest`; `IdempotencyKey` for §5.4 and `IdempotencyKeyTest`; `LeaseSimulationTest` for all §11.1 properties, both starts, the tightness checks, and the reduced-versus-every-duration cross-check.
- Deferred on purpose: `ExternalService`, `CallResult` and `Outcome` (slice 2.4); `expected-namespace` and `admin.write-enabled` (Phase 3); binding `WorkQueueProperties` in auto-configuration (Phase 3).
- Test counts assume the existing suite of 20 unit tests: 20 + 4 + 7 + 6 + 1 + 13 + 28 + 217 = 296.
