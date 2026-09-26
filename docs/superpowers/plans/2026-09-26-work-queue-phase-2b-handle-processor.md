# Work Queue Phase 2b: ClaimHandle and ItemProcessor Implementation Plan

> **Revised after execution:** the branch `db-work-queue/phase-2b` is authoritative. The final review's fixes (`cancel` and `markRunning` ignore ended handles, Javadoc corrections, `describe`, a `ClaimHandleTest` timeout, a redacted `ClaimedItem.toString()`, and the race-test assertion) are in commits `8f8ed3c` and `21f138f` and are not repeated in the task code below. The one exception is the `ItemProcessor.java` block in Task 3, which now matches the branch. The version first executed passed the last persist exception to `log.warn`, which rendered its messages and causes (possibly protected data) and let a throwing `getMessage()` escape `process()`. An abandoned outcome now logs only class names and SQL codes.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the two parts that `QueueRunner` (slice 2.5) puts together: `ClaimHandle`, the lifecycle of one claim (slice 2.3), and `ItemProcessor`, which processes one row, with its downstream SPI `ExternalService`, `CallResult` and `Outcome` (slice 2.4).

**Architecture:** `ClaimHandle` owns one permit and one registry entry. It gives the permit back exactly once, in `finish()`. `markRunning()` and `cancel()` share a lock, so a cancel can never slip past a body that is starting. `ItemProcessor` makes at most one `ExternalService` call per claim and persists the outcome through the fenced `WorkItemRepository.complete` or `retryOrFail`, retrying a failed persist `completion-retries` times. It returns an `Outcome` and never throws an exception. All of this is pure JVM code: the unit tests use hand-written fakes and never start Db2.

**Tech Stack:** JDK 25, JUnit 5, AssertJ, SLF4J (already on the classpath through `spring-boot-starter-jdbc`). Build with the Maven wrapper in `db-work-queue/`.

**Spec:** `docs/superpowers/specs/2026-09-21-db-work-queue-design.md`, revision 10: §5.1 fenced writes, §5.2 task lifecycle, §5.4 external side-effect contract, §6 `ItemProcessor`, §11.1 `ClaimHandleTest` and `ItemProcessorTest`.

**Starting point:** `main` at `73bd7d3` (Phase 2a: timing foundations), where `./mvnw -pl work-queue-engine test` runs 296 unit tests, all passing. Create the branch `db-work-queue/phase-2b` from `main` before Task 1.

## Global Constraints

- Package `hle.org.workqueue.engine`. Main code goes in `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/`, tests in `.../src/test/java/hle/org/workqueue/engine/`.
- Run every command from `db-work-queue/` with `./mvnw`, never `mvn`, which is not on PATH.
- Unit tests (`*Test`) must not start Db2: never reference `Db2TestSupport` from them. Use `ItConfig` for the IT column.
- No Mockito. Fakes are hand-written: `ScriptedRepository` (Task 3) subclasses `WorkItemRepository` over an unconnected `DriverManagerDataSource`, as `WorkItemRepositoryTest` already does.
- Times are `System.nanoTime()` readings held as `long` and compared overflow-safely (`a - b < 0`, never `a < b`), as `RenewalSchedule` does.
- The engine never logs idempotency keys, `OPERATION_ID`s, payloads or results (spec §5.4). Logs carry the row id, claim token, owner and outcome only. The `toString()` of every new type must not reveal a key, payload or result, and validation messages must not echo a rejected value.
- Match the existing style: records with compact-constructor validation, `IllegalArgumentException` for a bad argument, `Objects.requireNonNull(value, "name")`, Javadoc on public types, and comments only where the reason isn't obvious. Engine internals are package-private (`final class`, like `LeaseTiming`); the SPI types (`ExternalService`, `CallResult`, `Outcome`) are public, like `PersistResult`.
- Scope: the metrics (`registration.late`, `outcomes`, `call.duration`) arrive with slice 2.6 and need no hook here. The gauges read the registry and the semaphore, and `QueueRunner` records outcomes. The loops that call `isRenewable` and `markHungIfOverdue` arrive with `QueueRunner` in slice 2.5, and the ITs' `RecordingDownstream` with slice 2.7.

## Design decisions the spec leaves open

Read these before implementing. The code below already follows them.

1. **`claimedAt` is when the claim operation returned.** It is the `nanoTime` reading the poll loop takes right after `repository.claim` returns, the same reading its registration-late check uses. The deadline is `claimedAt + max-processing-time`. This matches E3 (eligible within `M + W + L` of being claimed): the last renewal round starts before the deadline and writes within `W`. It also matches B4, because the task starts after `claimedAt`.
2. **`markRunning` and `cancel` share one lock.** Either the cancel comes first and `markRunning` returns false, or `markRunning` comes first and the cancel interrupts that thread. The first cancel wins, and its reason and time are kept, so the hung grace counts from it.
3. **`ItemProcessor.process(item, cancelled)` takes a `BooleanSupplier`, not the handle.** `QueueRunner` passes `handle::isCancelled`, so the processor can be tested without a registry or permits.
4. **Interruption during the call.** If the call throws `InterruptedException`, or the thread's interrupt status is set when the call returns or throws, the outcome is `INTERRUPTED` and nothing is written. The claim then expires.
5. **Persist failures.** A persist attempt is retried on any `RuntimeException`, of which `DataAccessException` is the expected kind, after `completion-retry-delay`, at most `completion-retries` times. If it still fails, or the thread is interrupted, the outcome is `ABANDONED`, logged at WARN with the row id, token, owner, and the class names and SQL codes down the last exception's cause chain. The exception itself is never passed to the logger: its messages may carry keys, payloads or results, and a message or cause that throws would escape `process()`.
6. **Bad inputs fail the attempt, not the task.** A call that returns `null`, or a row whose `OPERATION_ID` fails `IdempotencyKey` validation (impossible while `CK_WORK_ITEM_OPERATION_ID` holds), becomes a failed attempt through `retryOrFail`. Nothing throws. The error texts are constants that never echo the row's values.
7. **`CallResult` checks the `RESULT_VALUE` size (1000 UTF-8 bytes) when it is constructed.** An oversized result therefore fails inside the downstream's call and becomes a failed attempt. Without the check it would reach `complete`, fail there with a Db2 length error on every retry, and end as `ABANDONED` on every re-claim.
8. **The owner and namespace are checked when `ItemProcessor` is constructed**, through `WorkItemRepository.requireOwner` (made package-private) and a new `IdempotencyKey.requireNamespace`, so `process` never meets an invalid one.

## File Structure

| File | Responsibility |
|---|---|
| `main/.../ClaimHandle.java` (create) | One claim's lifecycle: registration, `markRunning`, `cancel`, renewal eligibility, hung marking, exactly-once `finish` |
| `main/.../ExternalService.java` (create) | The downstream SPI (spec §5.4) |
| `main/.../CallResult.java` (create) | A call's result, size-checked against `RESULT_VALUE`, redacted `toString()` |
| `main/.../Outcome.java` (create) | How one task ended |
| `main/.../ItemProcessor.java` (create) | One row: at most one call, then the fenced persist with retries |
| `main/.../IdempotencyKey.java` (modify) | Extract `requireNamespace` for `ItemProcessor` to reuse |
| `main/.../WorkItemRepository.java` (modify) | `requireOwner` becomes package-private |
| `test/.../ClaimHandleTest.java` (create) | Spec §11.1 `ClaimHandleTest`, plus the rest of the handle's contract |
| `test/.../CallResultTest.java` (create) | Size limit, null, redaction |
| `test/.../ScriptedRepository.java` (create) | Scripted, recording `complete` and `retryOrFail`; slice 2.5 extends it with `claim` and `renew` |
| `test/.../ItemProcessorTest.java` (create) | Spec §11.1 `ItemProcessorTest` |
| `db-work-queue/README.md` (modify) | Status line |

---

### Task 1: ClaimHandle

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java`

**Interfaces:**
- Consumes: `record ClaimedItem(long id, String operationId, String payload, long claimToken)` with `ClaimKey key()`; `record ClaimKey(long id, long token)`; `static void Durations.requirePositive(String name, Duration value)` (all existing).
- Produces, all package-private, for slice 2.5's `QueueRunner`:
  - `final class ClaimHandle` with `ClaimHandle(ClaimedItem item, long claimedAt, Duration maxProcessingTime, Map<ClaimKey, ClaimHandle> registry, Semaphore permits)`. The constructor has no side effects.
  - `enum ClaimHandle.CancelReason { DEADLINE, LOST, SHUTDOWN, CRASH }`
  - `ClaimKey key()`, `ClaimedItem item()`, `long claimedAt()`, `long deadline()`
  - `boolean register()`: `registry.putIfAbsent(key, this) == null`; false means a key collision.
  - `boolean markRunning()`: false if already cancelled.
  - `boolean cancel(CancelReason reason, long now)`: the first cancel wins. It interrupts the thread if the body is running and never touches the registry or the permit.
  - `boolean isCancelled()`, `CancelReason cancelReason()` (null if never cancelled), `boolean isEnded()`
  - `boolean isRenewable(long now)`: `!ended && !cancelled && now < deadline`. `boolean isPastDeadline(long now)`.
  - `boolean markHungIfOverdue(long now, Duration hungGrace)`: true exactly once, when the handle was cancelled at least `hungGrace` ago and has not ended. `boolean isHung()`: marked and not ended.
  - `StackTraceElement[] runnerStackTrace()`: empty before the body runs.
  - `boolean finish()`: true for the one call that removed the handle (value-aware) and released its permit.

- [ ] **Step 1: Write the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java`:

```java
package hle.org.workqueue.engine;

import hle.org.workqueue.engine.ClaimHandle.CancelReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class ClaimHandleTest {

    private static final long SECOND = 1_000_000_000L;
    private static final Duration MAX_PROCESSING_TIME = ofSeconds(25);
    private static final int RACE_ITERATIONS = 10_000;

    // Each handle under test owns one permit that the semaphore gets back only through finish().
    private final Semaphore permits = new Semaphore(0);
    private final Map<ClaimKey, ClaimHandle> registry = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newFixedThreadPool(3);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void constructionHasNoSideEffects() {
        ClaimHandle handle = handle(1, 7, 10 * SECOND);

        assertThat(registry).isEmpty();
        assertThat(permits.availablePermits()).isZero();
        assertThat(handle.key()).isEqualTo(new ClaimKey(1, 7));
        assertThat(handle.claimedAt()).isEqualTo(10 * SECOND);
        assertThat(handle.deadline()).isEqualTo(35 * SECOND);
    }

    @Test
    void rejectsANonPositiveMaxProcessingTime() {
        assertThatThrownBy(() -> new ClaimHandle(item(1, 1), 0, Duration.ZERO, registry, permits))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxProcessingTime");
    }

    @Test
    void finishRemovesTheHandleAndReturnsItsPermitOnce() {
        ClaimHandle handle = handle(1, 1);
        assertThat(handle.register()).isTrue();

        assertThat(handle.finish()).isTrue();
        assertThat(handle.finish()).isFalse();

        assertThat(handle.isEnded()).isTrue();
        assertThat(registry).isEmpty();
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void finishOfAnUnregisteredHandleOnlyReturnsItsPermit() {
        ClaimHandle other = handle(2, 1);
        other.register();
        ClaimHandle handle = handle(1, 1);

        assertThat(handle.finish()).isTrue();

        assertThat(registry).containsExactly(entry(new ClaimKey(2, 1), other));
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void anOldClaimEndingLeavesTheNewerClaimOfTheSameRowRegistered() {
        ClaimHandle old = handle(1, 1);
        old.register();
        ClaimHandle newer = handle(1, 2);
        newer.register();

        old.finish();

        assertThat(registry).containsExactly(entry(new ClaimKey(1, 2), newer));
    }

    @Test
    void aCollidingHandleIsNotRegisteredAndItsFinishLeavesTheRegisteredOne() {
        ClaimHandle registered = handle(1, 1);
        registered.register();
        ClaimHandle colliding = handle(1, 1);

        assertThat(colliding.register()).isFalse();
        assertThat(colliding.finish()).isTrue();

        assertThat(registry).containsExactly(entry(new ClaimKey(1, 1), registered));
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void cancelNeverReleasesThePermitOrTouchesTheRegistry() {
        ClaimHandle handle = handle(1, 1);
        handle.register();

        assertThat(handle.cancel(CancelReason.DEADLINE, 30 * SECOND)).isTrue();

        assertThat(handle.isCancelled()).isTrue();
        assertThat(handle.isEnded()).isFalse();
        assertThat(registry).containsKey(new ClaimKey(1, 1));
        assertThat(permits.availablePermits()).isZero();
    }

    @Test
    void onlyTheFirstCancelCounts() {
        ClaimHandle handle = handle(1, 1);

        assertThat(handle.cancel(CancelReason.LOST, 10 * SECOND)).isTrue();
        assertThat(handle.cancel(CancelReason.SHUTDOWN, 20 * SECOND)).isFalse();

        assertThat(handle.cancelReason()).isEqualTo(CancelReason.LOST);
        // The hung grace counts from the first cancel.
        assertThat(handle.markHungIfOverdue(12 * SECOND, ofSeconds(2))).isTrue();
    }

    @Test
    void aHandleCancelledBeforeItsBodyRunsDoesNotRun() {
        ClaimHandle handle = handle(1, 1);
        handle.cancel(CancelReason.SHUTDOWN, 0);

        assertThat(handle.markRunning()).isFalse();
    }

    @Test
    void cancelInterruptsARunningBody() throws InterruptedException {
        ClaimHandle handle = handle(1, 1);
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch running = new CountDownLatch(1);
        Thread body = Thread.ofVirtual().start(() -> {
            ran.set(handle.markRunning());
            running.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        running.await();

        handle.cancel(CancelReason.DEADLINE, 0);

        assertThat(body.join(ofSeconds(5))).isTrue();
        assertThat(ran).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void markRunningAndCancelNeverMissEachOther() throws Exception {
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            ClaimHandle handle = handle(i, 1);
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicBoolean cancelDone = new AtomicBoolean();
            Future<Boolean> ranUninterrupted = threads.submit(() -> {
                start.await();
                boolean ran = handle.markRunning();
                while (!cancelDone.get()) {
                    Thread.onSpinWait();
                }
                return ran && !Thread.interrupted();   // also clears the flag for the pool thread's next task
            });
            Future<?> cancel = threads.submit(() -> {
                start.await();
                handle.cancel(CancelReason.SHUTDOWN, 0);
                cancelDone.set(true);
                return null;
            });

            cancel.get();
            assertThat(ranUninterrupted.get()).as("iteration %d: the body ran and was not interrupted", i).isFalse();
        }
    }

    @Test
    void finishRunsExactlyOnceUnderConcurrentFinishAndCancel() throws Exception {
        for (int i = 0; i < RACE_ITERATIONS; i++) {
            Semaphore racePermits = new Semaphore(0);
            Map<ClaimKey, ClaimHandle> raceRegistry = new ConcurrentHashMap<>();
            ClaimHandle handle = new ClaimHandle(item(i, 1), 0, MAX_PROCESSING_TIME, raceRegistry, racePermits);
            handle.register();
            CyclicBarrier start = new CyclicBarrier(3);

            Future<Boolean> first = threads.submit(() -> {
                start.await();
                return handle.finish();
            });
            Future<Boolean> second = threads.submit(() -> {
                start.await();
                return handle.finish();
            });
            Future<Boolean> cancel = threads.submit(() -> {
                start.await();
                return handle.cancel(CancelReason.SHUTDOWN, 0);
            });

            assertThat(first.get() ^ second.get()).as("iteration %d: exactly one finish did the work", i).isTrue();
            assertThat(cancel.get()).isTrue();
            assertThat(racePermits.availablePermits()).as("iteration %d", i).isEqualTo(1);
            assertThat(raceRegistry).as("iteration %d", i).isEmpty();
        }
    }

    @Test
    void isRenewableUntilTheDeadline() {
        ClaimHandle handle = handle(1, 1, 10 * SECOND);

        assertThat(handle.isRenewable(35 * SECOND - 1)).isTrue();
        assertThat(handle.isPastDeadline(35 * SECOND - 1)).isFalse();
        assertThat(handle.isRenewable(35 * SECOND)).isFalse();
        assertThat(handle.isPastDeadline(35 * SECOND)).isTrue();
    }

    @Test
    void theDeadlineIsComparedOverflowSafely() {
        long claimedAt = Long.MAX_VALUE - SECOND;
        ClaimHandle handle = handle(1, 1, claimedAt);

        assertThat(handle.isRenewable(Long.MAX_VALUE)).isTrue();
        assertThat(handle.isRenewable(claimedAt + 25 * SECOND - 1)).isTrue();
        assertThat(handle.isRenewable(claimedAt + 25 * SECOND)).isFalse();
    }

    @Test
    void aCancelledOrEndedHandleIsNotRenewable() {
        ClaimHandle cancelled = handle(1, 1);
        cancelled.cancel(CancelReason.LOST, 0);
        ClaimHandle ended = handle(2, 1);
        ended.finish();

        assertThat(cancelled.isRenewable(0)).isFalse();
        assertThat(ended.isRenewable(0)).isFalse();
    }

    @Test
    void aCancelledHandleThatHasNotEndedIsMarkedHungOnceAfterTheGrace() {
        ClaimHandle handle = handle(1, 1);
        handle.register();
        Duration grace = ofSeconds(2);

        assertThat(handle.markHungIfOverdue(100 * SECOND, grace)).as("not cancelled").isFalse();
        handle.cancel(CancelReason.DEADLINE, 10 * SECOND);
        assertThat(handle.markHungIfOverdue(12 * SECOND - 1, grace)).isFalse();
        assertThat(handle.markHungIfOverdue(12 * SECOND, grace)).isTrue();
        assertThat(handle.markHungIfOverdue(13 * SECOND, grace)).as("only once").isFalse();

        assertThat(handle.isHung()).isTrue();
        assertThat(permits.availablePermits()).as("a hung task keeps its permit").isZero();

        handle.finish();
        assertThat(handle.isHung()).isFalse();
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void anEndedHandleIsNeverMarkedHung() {
        ClaimHandle handle = handle(1, 1);
        handle.cancel(CancelReason.DEADLINE, 0);
        handle.finish();

        assertThat(handle.markHungIfOverdue(100 * SECOND, ofSeconds(2))).isFalse();
        assertThat(handle.isHung()).isFalse();
    }

    @Test
    void theRunnerStackTraceIsEmptyUntilTheBodyRuns() {
        ClaimHandle handle = handle(1, 1);
        assertThat(handle.runnerStackTrace()).isEmpty();

        handle.markRunning();

        assertThat(handle.runnerStackTrace()).isNotEmpty();
    }

    @Test
    void toStringShowsOnlyTheIdAndToken() {
        ClaimHandle handle = new ClaimHandle(new ClaimedItem(1, "op-secret", "payload-secret", 7), 0,
                MAX_PROCESSING_TIME, registry, permits);

        assertThat(handle).hasToString("ClaimHandle[id=1, token=7]");
    }

    private ClaimHandle handle(long id, long token) {
        return handle(id, token, 0);
    }

    private ClaimHandle handle(long id, long token, long claimedAt) {
        return new ClaimHandle(item(id, token), claimedAt, MAX_PROCESSING_TIME, registry, permits);
    }

    private static ClaimedItem item(long id, long token) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, token);
    }
}
```

The two race tests (`markRunningAndCancelNeverMissEachOther`, `finishRunsExactlyOnceUnderConcurrentFinishAndCancel`) were checked against broken implementations while this plan was written. A `finish()` that tests `ended` and then sets it failed on iteration 0, and a `markRunning()` that checks for a cancel outside the lock failed within about 3,000 iterations. Keep them at 10,000 iterations.

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=ClaimHandleTest`
Expected: `BUILD FAILURE`, with a compilation error `cannot find symbol ... class ClaimHandle`.

- [ ] **Step 3: Implement ClaimHandle**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One claim's lifecycle (spec §5.2). From the moment the poll loop transfers a permit to it, the handle owns that
 * permit and returns it exactly once, in {@link #finish()}, on every path. Cancelling only stops renewal and
 * interrupts the handle's thread: the permit stays taken until that thread ends, so a task that ignores
 * interruption still counts against concurrency. Times are {@code System.nanoTime()} readings, compared
 * overflow-safely.
 */
final class ClaimHandle {

    /** Why a claim was cancelled. */
    enum CancelReason {
        /** max-processing-time has passed since the claim. */
        DEADLINE,
        /** Renewal reported the claim lost: another owner may hold the row. */
        LOST,
        /** The instance is stopping and shutdown-grace has passed. */
        SHUTDOWN,
        /** {@code QueueRunner.crash()}, tests only. */
        CRASH
    }

    private final ClaimedItem item;
    private final long claimedAt;
    private final long deadline;
    private final Map<ClaimKey, ClaimHandle> registry;
    private final Semaphore permits;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean hung = new AtomicBoolean();

    // markRunning and cancel decide under this lock whether the body runs and whether its thread is interrupted,
    // so a cancel can never slip between the check and the start of processing.
    private final Object lock = new Object();
    private Thread runner;
    private CancelReason cancelReason;
    private long cancelledAt;

    /**
     * Has no side effects: the poll loop still owns the permit until it transfers it after construction.
     *
     * @param claimedAt when the claim operation returned; the deadline and the registration-late check count from it
     */
    ClaimHandle(ClaimedItem item, long claimedAt, Duration maxProcessingTime, Map<ClaimKey, ClaimHandle> registry,
                Semaphore permits) {
        this.item = Objects.requireNonNull(item, "item");
        Durations.requirePositive("maxProcessingTime", maxProcessingTime);
        this.claimedAt = claimedAt;
        this.deadline = claimedAt + maxProcessingTime.toNanos();
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permits = Objects.requireNonNull(permits, "permits");
    }

    ClaimKey key() {
        return item.key();
    }

    ClaimedItem item() {
        return item;
    }

    long claimedAt() {
        return claimedAt;
    }

    /** claimedAt + max-processing-time: the supervisor cancels the claim here, and renewal stops. */
    long deadline() {
        return deadline;
    }

    /**
     * Adds this handle to the registry. False if another handle already holds its key, which is an invariant
     * violation: claim tokens are unique per claim.
     */
    boolean register() {
        return registry.putIfAbsent(key(), this) == null;
    }

    /**
     * Called first by the handle's own thread. False if the handle was already cancelled: the body then skips
     * processing and only finishes. Once this returns true, a later cancel interrupts the calling thread.
     */
    boolean markRunning() {
        synchronized (lock) {
            if (cancelReason != null) {
                return false;
            }
            runner = Thread.currentThread();
            return true;
        }
    }

    /**
     * Stops renewal of this claim and interrupts its thread if the body is running. Never touches the registry or
     * the permit. False, changing nothing, if the handle was already cancelled.
     */
    boolean cancel(CancelReason reason, long now) {
        Objects.requireNonNull(reason, "reason");
        synchronized (lock) {
            if (cancelReason != null) {
                return false;
            }
            cancelReason = reason;
            cancelledAt = now;
            if (runner != null) {
                runner.interrupt();
            }
            return true;
        }
    }

    boolean isCancelled() {
        return cancelReason() != null;
    }

    /** The reason given to the first cancel, or null if the handle was never cancelled. */
    CancelReason cancelReason() {
        synchronized (lock) {
            return cancelReason;
        }
    }

    boolean isEnded() {
        return ended.get();
    }

    /** Renewal eligibility (spec §5.2): not ended, not cancelled, and before the deadline. */
    boolean isRenewable(long now) {
        return !isEnded() && !isCancelled() && !isPastDeadline(now);
    }

    boolean isPastDeadline(long now) {
        return now - deadline >= 0;
    }

    /**
     * Marks this handle hung if it was cancelled at least {@code hungGrace} before {@code now} and has not ended.
     * True only the first time, so the supervisor logs each hung task once.
     */
    boolean markHungIfOverdue(long now, Duration hungGrace) {
        synchronized (lock) {
            if (cancelReason == null || now - cancelledAt < hungGrace.toNanos()) {
                return false;
            }
        }
        return !isEnded() && hung.compareAndSet(false, true);
    }

    /** Marked hung and still running. It keeps its permit until its thread ends. */
    boolean isHung() {
        return hung.get() && !isEnded();
    }

    /** The body's stack, for the hung-task log; empty if the body has not started. */
    StackTraceElement[] runnerStackTrace() {
        Thread thread;
        synchronized (lock) {
            thread = runner;
        }
        return thread == null ? new StackTraceElement[0] : thread.getStackTrace();
    }

    /**
     * Ends this handle exactly once, whoever calls it: removes it from the registry if it is registered there
     * (never another handle with the same key) and returns its permit. True for the call that did so.
     */
    boolean finish() {
        if (!ended.compareAndSet(false, true)) {
            return false;
        }
        registry.remove(key(), this);
        permits.release();
        return true;
    }

    /** Only the row id and token: the engine never logs operation ids or payloads. */
    @Override
    public String toString() {
        return "ClaimHandle[id=" + item.id() + ", token=" + item.claimToken() + "]";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=ClaimHandleTest`
Expected: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`, in about half a second, then `BUILD SUCCESS`.

- [ ] **Step 5: Run all unit tests**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test`
Expected: `Tests run: 315, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. Docker is not needed.

- [ ] **Step 6: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java
git commit -m "feat: add ClaimHandle, the lifecycle of one claim" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: ExternalService SPI and CallResult

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ExternalService.java`
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/CallResult.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/CallResultTest.java`

**Interfaces:**
- Consumes: `public record IdempotencyKey(String namespace, String operationId)` (existing).
- Produces:
  - `public record CallResult(String value)` with `public static final int MAX_VALUE_BYTES = 1000`. It throws `NullPointerException("value")` for null and `IllegalArgumentException("value must be at most 1000 UTF-8 bytes")` when too long. `toString()` returns `CallResult[redacted]`.
  - `@FunctionalInterface public interface ExternalService` with `CallResult call(IdempotencyKey key, long claimToken, String payload, Duration timeout) throws Exception`. Task 3's `ItemProcessor` calls it, and slice 2.7's `RecordingDownstream` implements it.

- [ ] **Step 1: Write the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/CallResultTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallResultTest {

    @Test
    void acceptsUpToAThousandUtf8Bytes() {
        assertThat(new CallResult("").value()).isEmpty();
        assertThat(new CallResult("a".repeat(1000)).value()).hasSize(1000);
        assertThat(new CallResult("é".repeat(500)).value()).hasSize(500);
    }

    @Test
    void rejectsALongerValueWithoutEchoingIt() {
        String tooLong = "x".repeat(1001);

        assertThatThrownBy(() -> new CallResult(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value must be at most 1000 UTF-8 bytes");
        assertThatThrownBy(() -> new CallResult("é".repeat(500) + "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new CallResult(null)).isInstanceOf(NullPointerException.class).hasMessage("value");
    }

    @Test
    void toStringDoesNotRevealTheValue() {
        assertThat(new CallResult("receipt-secret")).hasToString("CallResult[redacted]");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=CallResultTest`
Expected: `BUILD FAILURE`, with a compilation error `cannot find symbol ... class CallResult`.

- [ ] **Step 3: Implement CallResult and ExternalService**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/CallResult.java`:

```java
package hle.org.workqueue.engine;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * What an external call returned (spec §5.4), stored as RESULT_VALUE when its claim completes. {@link #toString()}
 * does not reveal the value, because the engine never logs results.
 *
 * @param value at most {@value #MAX_VALUE_BYTES} UTF-8 bytes
 */
public record CallResult(String value) {

    /** RESULT_VALUE is VARCHAR(1000), counted in bytes. */
    public static final int MAX_VALUE_BYTES = 1000;

    public CallResult {
        Objects.requireNonNull(value, "value");
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("value must be at most " + MAX_VALUE_BYTES + " UTF-8 bytes");
        }
    }

    @Override
    public String toString() {
        return "CallResult[redacted]";
    }
}
```

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ExternalService.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;

/**
 * The downstream the engine calls, at most once per claim (spec §5.4). Claim-token fencing protects only the
 * database: a stale owner, a retry or a replay can call again with the same key. An implementation must therefore
 * be durably idempotent on {@code key.value()}, compared exactly (case-sensitive): repeated calls apply the effect
 * at most once and return the result of the first application. A downstream that cannot do that is not supported.
 */
@FunctionalInterface
public interface ExternalService {

    /**
     * Applies this operation's effect, or returns the stored result if it was already applied.
     *
     * @param key        the operation identity; never log it
     * @param claimToken the claim making this call, for the downstream's own records
     * @param payload    the row's PAYLOAD; never log it
     * @param timeout    return or throw within this time, throwing {@link java.util.concurrent.TimeoutException}
     *                   when it expires; the engine enforces it only as a backstop (deadline cancel, hung detection)
     * @return the result to store: the same for every repeat of the key
     * @throws InterruptedException if interrupted; implementations should respond to interruption, and the engine
     *                              then writes nothing
     * @throws Exception            any other failure: the engine records a failed attempt
     */
    CallResult call(IdempotencyKey key, long claimToken, String payload, Duration timeout) throws Exception;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=CallResultTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`, then `BUILD SUCCESS`.

- [ ] **Step 5: Run all unit tests**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test`
Expected: `Tests run: 319, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ExternalService.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/CallResult.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/CallResultTest.java
git commit -m "feat: add the ExternalService SPI and CallResult" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: ItemProcessor

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Outcome.java`
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java`
- Create: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java`
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/IdempotencyKey.java` (extract `requireNamespace`)
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java:345` (`requireOwner` becomes package-private)
- Modify: `db-work-queue/README.md` (status line)
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java`

**Interfaces:**
- Consumes: `ExternalService`, `CallResult` (Task 2). `WorkItemRepository.complete(String owner, ClaimKey claim, String resultValue)` and `retryOrFail(String owner, ClaimKey claim, String error)`, both returning `PersistResult { DONE, RETRY_SCHEDULED, FAILED, FENCED }` and throwing `DataAccessException` when the outcome is unknown (existing). `ItConfig.properties()` (existing).
- Produces:
  - `public enum Outcome { COMPLETED, RETRY_SCHEDULED, FAILED, FENCED, ABANDONED, INTERRUPTED, CANCELLED }`
  - `final class ItemProcessor` with `ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace, ItemProcessor.Settings settings)`, a test constructor that adds `ItemProcessor.Sleeper sleeper`, and `Outcome process(ClaimedItem item, BooleanSupplier cancelled)`.
  - `record ItemProcessor.Settings(Duration externalCallTimeout, int completionRetries, Duration completionRetryDelay)` with `static Settings from(WorkQueueProperties)`.
  - `@FunctionalInterface interface ItemProcessor.Sleeper { void sleep(Duration) throws InterruptedException; }`
  - `static void IdempotencyKey.requireNamespace(String)` and `static void WorkItemRepository.requireOwner(String)`, both package-private.
  - Test support: `class ScriptedRepository extends WorkItemRepository` with `thenReturn(PersistResult)`, `thenThrow(RuntimeException)`, `then(Supplier<PersistResult>)` and `List<Write> writes()`, where `record Write(Operation operation, String owner, ClaimKey claim, String value)` and `enum Operation { COMPLETE, RETRY_OR_FAIL }`. Slice 2.5 adds scripted `claim` and `renew` to it.

- [ ] **Step 1: Write the test fake and the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java`:

```java
package hle.org.workqueue.engine;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * A WorkItemRepository for unit tests: complete and retryOrFail answer from a script, in order, and are recorded.
 * It never touches a database; an unscripted persist fails the test with an AssertionError.
 */
class ScriptedRepository extends WorkItemRepository {

    enum Operation { COMPLETE, RETRY_OR_FAIL }

    /** One persist call; {@code value} is the result value of a complete or the error of a retryOrFail. */
    record Write(Operation operation, String owner, ClaimKey claim, String value) {
    }

    private final Deque<Supplier<PersistResult>> script = new ArrayDeque<>();
    private final List<Write> writes = new ArrayList<>();

    ScriptedRepository() {
        super(new DriverManagerDataSource(), DbTimeouts.defaults(),
                new Settings(Duration.ofSeconds(100), 5, Duration.ofSeconds(5)));
    }

    ScriptedRepository thenReturn(PersistResult result) {
        return then(() -> result);
    }

    ScriptedRepository thenThrow(RuntimeException failure) {
        return then(() -> {
            throw failure;
        });
    }

    /** The next persist runs {@code step}. */
    ScriptedRepository then(Supplier<PersistResult> step) {
        script.add(step);
        return this;
    }

    List<Write> writes() {
        return writes;
    }

    @Override
    public PersistResult complete(String owner, ClaimKey claim, String resultValue) {
        return next(new Write(Operation.COMPLETE, owner, claim, resultValue));
    }

    @Override
    public PersistResult retryOrFail(String owner, ClaimKey claim, String error) {
        return next(new Write(Operation.RETRY_OR_FAIL, owner, claim, error));
    }

    private PersistResult next(Write write) {
        writes.add(write);
        Supplier<PersistResult> step = script.poll();
        if (step == null) {
            throw new AssertionError("unscripted " + write.operation());
        }
        return step.get();
    }
}
```

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java`:

```java
package hle.org.workqueue.engine;

import hle.org.workqueue.engine.ScriptedRepository.Write;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static hle.org.workqueue.engine.ScriptedRepository.Operation.COMPLETE;
import static hle.org.workqueue.engine.ScriptedRepository.Operation.RETRY_OR_FAIL;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemProcessorTest {

    private static final String OWNER = "instance-a";
    private static final String NAMESPACE = "it";
    private static final ClaimedItem ITEM = new ClaimedItem(7, "order-7:charge", "payload-7", 3);
    private static final ClaimKey CLAIM = new ClaimKey(7, 3);
    /** The IT column: external-call-timeout 3s, completion-retries 2, completion-retry-delay 100ms. */
    private static final ItemProcessor.Settings SETTINGS = ItemProcessor.Settings.from(ItConfig.properties());

    private final ScriptedRepository repository = new ScriptedRepository();
    private final List<Call> calls = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();

    private record Call(IdempotencyKey key, long claimToken, String payload, Duration timeout) {
    }

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();   // tests of interruption leave the test thread interrupted
    }

    @Test
    void aSuccessfulCallCompletesTheRowWithItsResult() {
        repository.thenReturn(PersistResult.DONE);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.COMPLETED);

        assertThat(calls).containsExactly(
                new Call(new IdempotencyKey("it", "order-7:charge"), 3, "payload-7", ofSeconds(3)));
        assertThat(repository.writes()).containsExactly(new Write(COMPLETE, OWNER, CLAIM, "receipt-7"));
    }

    @Test
    void aCancelledHandleIsNeitherCalledNorPersisted() {
        assertThat(processor(returning("receipt-7")).process(ITEM, () -> true)).isEqualTo(Outcome.CANCELLED);

        assertThat(calls).isEmpty();
        assertThat(repository.writes()).isEmpty();
    }

    @Test
    void aFailedCallSchedulesARetry() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(calls).hasSize(1);
        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, "java.lang.IllegalStateException: downstream said no"));
    }

    @Test
    void aFailedCallOnTheLastAttemptFailsTheRow() {
        repository.thenReturn(PersistResult.FAILED);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.FAILED);
    }

    @Test
    void aTimedOutCallIsAFailedAttempt() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);

        assertThat(process(throwing(new TimeoutException("3s passed")))).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, "java.util.concurrent.TimeoutException: 3s passed"));
    }

    @Test
    void aCallThatReturnsNoResultIsAFailedAttempt() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);

        assertThat(process((key, token, payload, timeout) -> null)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, CLAIM, ItemProcessor.NO_RESULT_ERROR));
    }

    @Test
    void aRowWithAnInvalidOperationIdFailsItsAttemptWithoutACall() {
        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
        ClaimedItem invalid = new ClaimedItem(8, "has space", "payload-8", 1);

        assertThat(processor(returning("receipt-8")).process(invalid, () -> false)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(calls).isEmpty();
        assertThat(repository.writes()).containsExactly(
                new Write(RETRY_OR_FAIL, OWNER, new ClaimKey(8, 1), ItemProcessor.INVALID_OPERATION_ID_ERROR));
    }

    @Test
    void aFencedCompletionIsFenced() {
        repository.thenReturn(PersistResult.FENCED);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.FENCED);
    }

    @Test
    void aFencedFailureIsFenced() {
        repository.thenReturn(PersistResult.FENCED);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.FENCED);
    }

    @Test
    void aCompletionWhoseAcknowledgementWasLostIsCompletedNotFenced() {
        // The first complete commits but its acknowledgement is lost; the retry updates 0 rows, and its read-back
        // finds this owner's token already DONE.
        repository.thenThrow(new DataAccessResourceFailureException("commit acknowledgement lost"))
                .thenReturn(PersistResult.DONE);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.COMPLETED);

        assertThat(calls).hasSize(1);
        assertThat(repository.writes()).extracting(Write::operation).containsExactly(COMPLETE, COMPLETE);
        assertThat(sleeps).containsExactly(ofMillis(100));
    }

    @Test
    void anOutcomeThatCannotBePersistedIsAbandonedAfterTheRetries() {
        DataAccessResourceFailureException unreachable = new DataAccessResourceFailureException("Db2 unreachable");
        repository.thenThrow(unreachable).thenThrow(unreachable).thenThrow(unreachable);

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.ABANDONED);

        assertThat(calls).hasSize(1);
        assertThat(repository.writes()).hasSize(3);
        assertThat(sleeps).containsExactly(ofMillis(100), ofMillis(100));
    }

    @Test
    void anUnexpectedRepositoryExceptionIsAbandonedNotThrown() {
        IllegalStateException bug = new IllegalStateException("not a persisted status: CLAIMED");
        repository.thenThrow(bug).thenThrow(bug).thenThrow(bug);

        assertThat(process(throwing(new IllegalStateException("downstream said no")))).isEqualTo(Outcome.ABANDONED);
    }

    @Test
    void anInterruptWhileWaitingToRetryAbandons() {
        repository.thenThrow(new DataAccessResourceFailureException("Db2 unreachable"));
        ItemProcessor processor = new ItemProcessor(repository, recording(returning("receipt-7")), OWNER, NAMESPACE,
                SETTINGS, duration -> {
                    throw new InterruptedException();
                });

        assertThat(processor.process(ITEM, () -> false)).isEqualTo(Outcome.ABANDONED);

        assertThat(repository.writes()).hasSize(1);
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt status restored").isTrue();
    }

    @Test
    void anInterruptDuringAPersistAttemptAbandonsWithoutRetrying() {
        repository.then(() -> {
            Thread.currentThread().interrupt();
            throw new DataAccessResourceFailureException("interrupted during connection acquisition");
        });

        assertThat(process(returning("receipt-7"))).isEqualTo(Outcome.ABANDONED);

        assertThat(repository.writes()).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void anInterruptedCallWritesNothing() {
        assertThat(process(throwing(new InterruptedException()))).isEqualTo(Outcome.INTERRUPTED);

        assertThat(repository.writes()).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt status restored").isTrue();
    }

    @Test
    void aCallThatReturnsAfterItsThreadWasInterruptedWritesNothing() {
        ExternalService returnsAnyway = (key, token, payload, timeout) -> {
            Thread.currentThread().interrupt();
            return new CallResult("receipt-7");
        };

        assertThat(process(returnsAnyway)).isEqualTo(Outcome.INTERRUPTED);

        assertThat(repository.writes()).isEmpty();
    }

    @Test
    void aCallThatFailsBecauseItsThreadWasInterruptedWritesNothing() {
        ExternalService wrapsTheInterrupt = (key, token, payload, timeout) -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request aborted");
        };

        assertThat(process(wrapsTheInterrupt)).isEqualTo(Outcome.INTERRUPTED);

        assertThat(repository.writes()).isEmpty();
    }

    @Test
    void rejectsAnInvalidOwnerOrNamespace() {
        assertThatThrownBy(() -> new ItemProcessor(repository, returning("r"), " ", NAMESPACE, SETTINGS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemProcessor(repository, returning("r"), OWNER, "Bad:Namespace", SETTINGS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Bad:Namespace");
    }

    @Test
    void settingsComeFromTheProperties() {
        assertThat(ItemProcessor.Settings.from(new WorkQueueProperties()))
                .isEqualTo(new ItemProcessor.Settings(ofSeconds(30), 3, ofSeconds(1)));
        assertThat(SETTINGS).isEqualTo(new ItemProcessor.Settings(ofSeconds(3), 2, ofMillis(100)));
    }

    @Test
    void settingsRejectANonPositiveTimeoutOrANegativeRetryCountOrDelay() {
        assertThatThrownBy(() -> new ItemProcessor.Settings(Duration.ZERO, 2, ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("externalCallTimeout");
        assertThatThrownBy(() -> new ItemProcessor.Settings(ofSeconds(3), -1, ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completionRetries");
        assertThatThrownBy(() -> new ItemProcessor.Settings(ofSeconds(3), 2, ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completionRetryDelay");
    }

    private Outcome process(ExternalService service) {
        return processor(service).process(ITEM, () -> false);
    }

    private ItemProcessor processor(ExternalService service) {
        return new ItemProcessor(repository, recording(service), OWNER, NAMESPACE, SETTINGS, sleeps::add);
    }

    private ExternalService recording(ExternalService service) {
        return (key, token, payload, timeout) -> {
            calls.add(new Call(key, token, payload, timeout));
            return service.call(key, token, payload, timeout);
        };
    }

    private static ExternalService returning(String value) {
        return (key, token, payload, timeout) -> new CallResult(value);
    }

    private static ExternalService throwing(Exception failure) {
        return (key, token, payload, timeout) -> {
            throw failure;
        };
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=ItemProcessorTest`
Expected: `BUILD FAILURE`, with compilation errors `cannot find symbol` for `ItemProcessor` and `Outcome`.

- [ ] **Step 3: Extract `IdempotencyKey.requireNamespace`**

Replace `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/IdempotencyKey.java` with the following. Only the constructor's first lines and the new static method change; the null and pattern checks are the same.

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
        requireNamespace(namespace);
        Objects.requireNonNull(operationId, "operationId");
        if (!OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId must be 1 to 64 printable ASCII characters without spaces");
        }
    }

    /** Throws unless {@code namespace} matches {@code ^[a-z0-9][a-z0-9-]{0,31}$}; the message never echoes it. */
    static void requireNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace must match ^[a-z0-9][a-z0-9-]{0,31}$");
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

- [ ] **Step 4: Make `WorkItemRepository.requireOwner` package-private**

In `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java`, replace

```java
    private static void requireOwner(String owner) {
```

with

```java
    static void requireOwner(String owner) {
```

- [ ] **Step 5: Implement Outcome**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Outcome.java`:

```java
package hle.org.workqueue.engine;

/** How one claim's task ended (spec §6 ItemProcessor). */
public enum Outcome {
    /** The call returned and the row is DONE with its result. */
    COMPLETED,
    /** The call failed with attempts left: the row is PENDING again after retry-backoff. */
    RETRY_SCHEDULED,
    /** The call failed on the last attempt: the row is FAILED. */
    FAILED,
    /** The claim was no longer this owner's when its outcome was persisted: nothing was written. */
    FENCED,
    /** The outcome could not be persisted within completion-retries: the row stays CLAIMED until its lease expires. */
    ABANDONED,
    /** The call was interrupted: nothing was written, and the row stays CLAIMED until its lease expires. */
    INTERRUPTED,
    /** The handle was cancelled before the call: no call was made and nothing was written. */
    CANCELLED
}
```

- [ ] **Step 6: Implement ItemProcessor**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java`:

```java
package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Processes one claimed row (spec §6): at most one external call, then its outcome persisted under the claim's
 * token. It returns an {@link Outcome} and never throws an exception; a JVM {@link Error} propagates to the task
 * body, whose {@code finally} still finishes the handle.
 */
final class ItemProcessor {

    /**
     * @param externalCallTimeout  passed to every call
     * @param completionRetries    persist attempts after the first one fails
     * @param completionRetryDelay the pause before each of those attempts
     */
    record Settings(Duration externalCallTimeout, int completionRetries, Duration completionRetryDelay) {

        Settings {
            Durations.requirePositive("externalCallTimeout", externalCallTimeout);
            if (completionRetries < 0) {
                throw new IllegalArgumentException("completionRetries must not be negative: " + completionRetries);
            }
            Objects.requireNonNull(completionRetryDelay, "completionRetryDelay");
            if (completionRetryDelay.isNegative()) {
                throw new IllegalArgumentException("completionRetryDelay must not be negative: " + completionRetryDelay);
            }
        }

        static Settings from(WorkQueueProperties properties) {
            return new Settings(properties.getExternalCallTimeout(), properties.getCompletionRetries(),
                    properties.getCompletionRetryDelay());
        }
    }

    /** The pause between persist attempts; tests record it instead of sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final String NO_RESULT_ERROR = "the external service returned no result";
    static final String INVALID_OPERATION_ID_ERROR = "OPERATION_ID is not a valid operation identity; not called";

    /** Bounds the logged cause chain, which may be cyclic. */
    private static final int MAX_LOGGED_CAUSES = 8;

    private static final Logger log = LoggerFactory.getLogger(ItemProcessor.class);

    private final WorkItemRepository repository;
    private final ExternalService service;
    private final String owner;
    private final String namespace;
    private final Settings settings;
    private final Sleeper sleeper;

    ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace,
                  Settings settings) {
        this(repository, service, owner, namespace, settings, Thread::sleep);
    }

    ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace,
                  Settings settings, Sleeper sleeper) {
        WorkItemRepository.requireOwner(owner);
        IdempotencyKey.requireNamespace(namespace);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.service = Objects.requireNonNull(service, "service");
        this.owner = owner;
        this.namespace = namespace;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** Processes {@code item}; {@code cancelled} reports whether its handle was cancelled. */
    Outcome process(ClaimedItem item, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return Outcome.CANCELLED;
        }
        IdempotencyKey key;
        try {
            key = new IdempotencyKey(namespace, item.operationId());
        } catch (IllegalArgumentException e) {
            // Unreachable while CK_WORK_ITEM_OPERATION_ID holds; the attempt fails instead of the task.
            return failed(item, INVALID_OPERATION_ID_ERROR);
        }
        CallResult result;
        try {
            result = service.call(key, item.claimToken(), item.payload(), settings.externalCallTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.INTERRUPTED;
        } catch (Exception e) {
            return Thread.currentThread().isInterrupted() ? Outcome.INTERRUPTED : failed(item, describe(e));
        }
        if (Thread.currentThread().isInterrupted()) {
            return Outcome.INTERRUPTED;
        }
        if (result == null) {
            return failed(item, NO_RESULT_ERROR);
        }
        return persist(item, () -> repository.complete(owner, item.key(), result.value()));
    }

    private Outcome failed(ClaimedItem item, String error) {
        return persist(item, () -> repository.retryOrFail(owner, item.key(), error));
    }

    // Throwable.toString() calls getLocalizedMessage(), which a downstream exception may override and break.
    private static String describe(Exception failure) {
        try {
            return failure.toString();
        } catch (RuntimeException broken) {
            return failure.getClass().getName();
        }
    }

    // One persist attempt is one DB operation; the repository's read-back turns a retry of a write that did commit
    // into that write's outcome instead of FENCED.
    private Outcome persist(ClaimedItem item, Supplier<PersistResult> write) {
        for (int attempt = 0; ; attempt++) {
            try {
                return outcomeOf(write.get());
            } catch (RuntimeException e) {
                if (attempt == settings.completionRetries() || Thread.currentThread().isInterrupted()) {
                    return abandoned(item, e);
                }
                try {
                    sleeper.sleep(settings.completionRetryDelay());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return abandoned(item, e);
                }
            }
        }
    }

    // Never the throwable itself: its messages may carry keys, payloads or results (spec §5.4), and a logger that
    // reads a message or cause that throws would throw out of process().
    private Outcome abandoned(ClaimedItem item, RuntimeException lastFailure) {
        log.warn("Abandoned row {} token {} of owner {}: its outcome could not be persisted: {}", item.id(),
                item.claimToken(), owner, diagnostics(lastFailure));
        return Outcome.ABANDONED;
    }

    /** The class names down the failure's cause chain, with SQL codes: no messages, and nothing that can throw. */
    private static String diagnostics(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_LOGGED_CAUSES; depth++) {
            text.append(depth == 0 ? "" : ", caused by ").append(current.getClass().getName());
            appendSqlCodes(text, current);
            current = causeOf(current);
        }
        return text.toString();
    }

    private static Throwable causeOf(Throwable failure) {
        try {
            return failure.getCause();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static void appendSqlCodes(StringBuilder text, Throwable failure) {
        if (!(failure instanceof SQLException sql)) {
            return;
        }
        try {
            String state = sql.getSQLState();
            int code = sql.getErrorCode();
            text.append(" (SQLState ").append(state).append(", error code ").append(code).append(')');
        } catch (RuntimeException unreadable) {
            // The class name alone is still a diagnostic.
        }
    }

    private static Outcome outcomeOf(PersistResult result) {
        return switch (result) {
            case DONE -> Outcome.COMPLETED;
            case RETRY_SCHEDULED -> Outcome.RETRY_SCHEDULED;
            case FAILED -> Outcome.FAILED;
            case FENCED -> Outcome.FENCED;
        };
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test -Dtest=ItemProcessorTest`
Expected: `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`, then `BUILD SUCCESS`. Four `WARN ... ItemProcessor - Abandoned row 7 token 3 of owner instance-a` lines, with class names and no stack traces, are expected: they come from the abandonment tests.

- [ ] **Step 8: Update the README status**

In `db-work-queue/README.md`, replace the status line with:

```markdown
Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`), `ClaimHandle` and `ItemProcessor` done; `QueueRunner` next.
```

- [ ] **Step 9: Run all unit tests**

Run (from `db-work-queue/`): `./mvnw -pl work-queue-engine test`
Expected: `Tests run: 339, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. The existing `IdempotencyKeyTest` (28) and `WorkItemRepositoryTest` still pass unchanged.

- [ ] **Step 10: Commit**

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Outcome.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/IdempotencyKey.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java \
        db-work-queue/README.md
git commit -m "feat: add ItemProcessor, one call and a fenced persist per claim" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage.**
  - §5.2 handle rules: permit ownership and exactly-once `finish` (steps 3 and 5), value-aware removal, collision handling (step 3.4), `markRunning` versus cancel (step 4), and a cancel that never releases the permit (step 6). Also renewal eligibility and hung marking.
  - §11.1 `ClaimHandleTest`: 10,000 iterations of concurrent finish and cancel released by a barrier, value-aware removal, and a cancel that never releases the permit.
  - §6 `ItemProcessor` steps 1–3 and every `Outcome`.
  - §11.1 `ItemProcessorTest`: each outcome, a lost acknowledgement reported as `COMPLETED` rather than `FENCED`, exactly one call, the timeout passed through, and nothing thrown.
  - §5.4: the `ExternalService` contract and the logging rule, through redacted `toString()`s and error texts that never echo a value.
- **Deferred on purpose.** The poll loop, renewal loop, supervisor, stop and crash, the permit invariant and `QueueRunnerLifecycleTest` belong to slice 2.5. Metrics and health belong to 2.6. `RecordingDownstream` and ITs 6–12 belong to 2.7.
- **Verified before writing.** Every code block was compiled and run against `main` at `73bd7d3` in a scratch worktree. The test counts are measured, not estimated: 296 + 19 + 4 + 20 = 339.
- **Noticed, not changed.** `ClaimedItem` is a record whose default `toString()` includes `operationId` and `payload`. Nothing logs it today. Slice 2.5 must log handles, which print only id and token, or give `ClaimedItem` a redacted `toString()`.

## After this plan

- **2c — `QueueRunner` (slice 2.5).** The poll loop with its permit rule, the renewal loop on `RenewalSchedule`, the supervisor, `stop()` and `crash()`, an injectable repository, thread starter and clock, and `QueueRunnerLifecycleTest` (spec §11.1), which asserts the permit invariant after every scenario.
- **2d — `Sweeper`, metrics and health (slice 2.6).** Adds the Micrometer dependency, `WorkQueueHealth` and `WorkQueueHealthTest`.
- **2e — Db2 ITs 6–12 (slice 2.7).** `RecordingDownstream`, the Toxiproxy host-port spike, and one IT-column definition in place of the current four (`Db2TestSupport`, `ItConfig`, `DbTimeoutsTest.IT`, `LeaseTimingTest.IT`).
