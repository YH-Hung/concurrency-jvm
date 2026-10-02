# Work Queue Phase 2d: Sweeper, Metrics and Health Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Executed on branch `db-work-queue/phase-2d`; five review decisions supersede this plan's text.** `sampleBacklog()` ends with `WITH UR` (an uncommitted read, so it never waits for row locks whatever the database's `cur_commit` setting), and its lock IT asserts the uncommitted state. `QueueRunner.deadLoops()` reads each loop thread's `isAlive()` before its run flag, so a loop that stop or `crash()` ended is never reported dead. From the code review (spec revision 13): `deadLoops()` also reports the sweeper and backlog sampler loops, so liveness watches all five; the `BacklogSampler` dates its sample (`backlog.sample_age`) and counts its failures (`backlog.sample.errors`); and `claim.rows` counts claimed rows, which the LostClaimsHigh alert divides `claims.lost` by. Design decisions 1, 8 and 9, the Task 1, 2, 4, 5 and 6 code, and the self-review lines on the default isolation, the LostClaimsHigh ratio and the dead sweeper predate them; the branch's code and spec are authoritative.

**Goal:** Build spec slice 2.6: the `Sweeper` and the `BacklogSampler` on two more `QueueRunner` loops; what health and the meters need from the runner (each claim's last lease write for `renewal.lag`, the last DB success, claim and renewal timings, task outcomes, dead loops); `WorkQueueMetrics`, which binds every spec §9.6 meter; and `WorkQueueHealth`, liveness and readiness, with `WorkQueueHealthTest`. It raises the spec to revision 12.

**Architecture:** The engine's logic stays free of Micrometer and of Spring Boot's health types. `QueueRunner` and `ItemProcessor` keep counts in `AtomicLong`s and timings in `OperationStats` (a count and a total); `WorkQueueMetrics` is a Micrometer `MeterBinder` that registers function counters, function timers and gauges over them, so no meter is on a task's path. `DbActivity` holds the time of the last DB success, which the claim, renewal, sweep and sample paths report. `Sweeper` and `BacklogSampler` are step classes that `QueueRunner` builds and runs on their own loops, so `stop()` and `crash()` end them too. `WorkQueueHealth` computes liveness and readiness from the runner whenever a probe asks, as Spring Boot 4 `Health` values; Phase 3 registers them as indicators.

**Tech Stack:** JDK 25 (virtual threads), Spring Boot 4.1.1 (`spring-boot-health` for `Health` and `Status`), Micrometer (`micrometer-core`, version from the Boot BOM; `SimpleMeterRegistry` in tests), SLF4J, JUnit 5, AssertJ, Awaitility, Testcontainers Db2 for the one new query. Build with the Maven wrapper in `db-work-queue/`.

**Spec:** `docs/superpowers/specs/2026-09-21-db-work-queue-design.md`. Task 1 raises it to revision 12. The relevant sections are §5.2 (stop sequence), §5.3 (a lease-setting write lands no earlier than its operation's start), §6 (components), §9.6 (metrics and health), §11.1 (`QueueRunnerLifecycleTest`, `SweeperTest`, `WorkQueueMetricsTest`, `WorkQueueHealthTest`) and §11.2 (`WorkItemRepositoryIT`).

**Starting point:** `main` at `93dca0b` (Phase 2c), where `./mvnw -pl work-queue-engine test` runs 405 unit tests and `./mvnw verify` 72 ITs, all passing. Create the branch `db-work-queue/phase-2d` from `main` before Task 1.

## Global Constraints

- Package `hle.org.workqueue.engine`. Main code goes in `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/`, tests in `.../src/test/java/hle/org/workqueue/engine/`.
- Run every build command from `db-work-queue/` with `./mvnw`, never `mvn`, which is not on PATH. Run `git` commands from the repository root.
- Unit tests (`*Test`) must not start Db2: never reference `Db2TestSupport` from them; use `ItConfig` for the IT column. ITs (`*IT`) need Docker running. The first Db2 start under Rosetta takes 5–10 minutes, and without `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` every IT run starts a new container.
- No Mockito. Fakes are hand-written: `ScriptedRepository` (a `WorkItemRepository` that answers from scripts) and `Tasks` (latch-controlled tasks).
- Times are `System.nanoTime()` readings held as `long` and compared overflow-safely (`a - b < 0`, never `a < b`). The fake clocks in the tests start 5s before `Long.MAX_VALUE`, so every age and deadline in them wraps around.
- The engine never logs idempotency keys, `OPERATION_ID`s, payloads or results (spec §5.4). A failure is logged as `Diagnostics.describe(t)`, never as its message and never by passing the throwable to the logger.
- Match the existing style: records with compact-constructor validation, `IllegalArgumentException` for a bad argument, `Objects.requireNonNull(value, "name")`, Javadoc on types, and comments only where the reason isn't obvious. Engine internals are package-private (`final class`); `BacklogSample` is public, like `RenewalResult`, because the public `WorkItemRepository.sampleBacklog` returns it.
- Tests wait with Awaitility (`await().until(...)`), never with fixed sleeps.
- Only `WorkQueueMetrics` imports Micrometer, and only `WorkQueueHealth` imports Spring Boot's health types.
- Scope: auto-configuration (the beans, the probe groups, binding `WorkQueueMetrics`, the `SchemaCheck` readiness gate) and `docs/alerts.yml` arrive in Phase 3. `RecordingDownstream` and ITs 6–12 arrive with slice 2.7.

## Design decisions the spec leaves open

Read these before implementing. The code below already follows them, and Task 1's spec revision 12 records the ones that change what the spec says.

1. **The sweeper and the sampler are step classes on `QueueRunner` loops.** `QueueRunner` builds a `Sweeper` and a `BacklogSampler` from its repository, owner and settings, and runs each on its own loop (`workqueue-sweeper`, `workqueue-backlog-sampler`): a pass, then the interval. The package-private `sweepOnce()` and `sampleOnce()` are those passes, for the tests, as `pollOnce()` and `renewOnce()` are. A pass logs its own failures, so only an `Error` ends one of these loops; it is logged, and liveness does not watch these two loops (spec §9.6 names the poll, renewal and supervisor loops).
2. **`stop()` waits at most 1s in all for the four loops that outlive the drain.** It interrupts renewal, the supervisor, the sweeper and the sampler together and joins them against one 1s deadline, so it returns within `shutdown-grace + shutdown-cancel-wait + 1s` (it was `+ 2s` with two sequential joins; four would have been `+ 4s`).
3. **A sweep pass** repeats while a batch comes back exactly full, and ends early when its thread is interrupted. A failed batch ends the pass; the batches before it stay swept. The rows it fails are logged at WARN by count, since they now need an operator.
4. **`renewal.lag` counts from the start of the operation that wrote the lease.** A `ClaimHandle` records `leaseWrittenAt`: its claim operation's start, then the start of each renewal round that renewed it. The write came no earlier (spec §5.3), so a lag of at most `L` means the lease has not run out; `claimedAt`, up to `W` later, would understate it. The constructor now takes `claimStartedAt` before `claimedAt` and rejects a claim that returned before it started.
5. **`DbActivity`** holds the last DB success, counted from the runner's creation until the first. Every claim that returns (an empty one included), every renewal round that returns, every sweep batch and every sample report it. Loops report concurrently, so a report that read the clock earlier but arrives later does not move it back.
6. **Timings are `OperationStats`**, a count and a total in nanoseconds that a Micrometer `FunctionTimer` reads: a rate and a mean, no maximum or percentiles. `claim.duration` records every claim operation, failed ones included; `renewal.duration` every round that ran (a skipped round is not one). `ItemProcessor` times each external call by how it ended (`ok`, `error`, `timeout`, `interrupted`) on an injectable clock.
7. **Outcomes are counted in the task body:** `CANCELLED` when the handle was cancelled before its body ran, nothing when the body threw. A handle that never started (a collision, or a registration or start failure) has no outcome; it is counted in `invariant.violations` or logged.
8. **The backlog sample is one query** over PENDING, CLAIMED and FAILED rows (the status predicate lets `IX_WORK_ITEM_CLAIM` answer it without DONE rows). Expired claims are CLAIMED rows with `AVAILABLE_AT` more than one lease ago; the oldest claimable PENDING row's age comes from `SECONDS_BETWEEN` on the Db2 clock, in whole seconds. Under the default isolation Db2 12.1 returns the committed row without waiting for another transaction's lock, and an IT pins that. The gauges report NaN until the first sample, and a failed sample keeps the previous one.
9. **Dead loops.** `deadLoops()` names the poll, renewal and supervisor loops whose threads ended while their run flag was still set. Stop and `crash()` clear the flags before interrupting, so the loops they end are not dead.
10. **Health is computed per probe.** Readiness is UP while not stopping, `renewal.lag ≤ L` and `db.last_success_age ≤ db-staleness-limit`; liveness is UP while the hung-task limit is not reached, there are no invariant violations and no loop is dead. Liveness and the poll loop's pause share `hungTaskLimitReached()`. The details are `hungTasks`, `invariantViolations` and `deadLoops`, and `stopping`, `renewalLag` and `dbLastSuccessAge` (as `"12.5s"`). Readiness does not check whether the runner has started: before that, Spring Boot's own readiness state still refuses traffic.
11. **`Tasks` moves out of `QueueRunnerLifecycleTest`** into its own test-support file, unchanged, for the metrics and health tests.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `docs/superpowers/specs/2026-09-21-db-work-queue-design.md` (modify) | Revision 12 | 1 |
| `main/.../BacklogSample.java` (create) | One backlog sample | 1 |
| `main/.../WorkItemRepository.java` (modify) | `sampleBacklog()` | 1 |
| `test/.../BacklogSampleTest.java` (create), `WorkItemRepositoryIT.java` (modify) | The record; the query on Db2 | 1 |
| `main/.../DbActivity.java` (create) | When a DB operation last succeeded | 2 |
| `main/.../Sweeper.java`, `BacklogSampler.java` (create) | One sweep pass; one sample, and the latest one | 2 |
| `test/.../ScriptedRepository.java` (modify) | Scripted `sweep` and `sampleBacklog` | 2 |
| `test/.../DbActivityTest.java`, `SweeperTest.java`, `BacklogSamplerTest.java` (create) | The three on their own | 2 |
| `main/.../OperationStats.java` (create) | A count and a total duration | 3 |
| `main/.../ClaimHandle.java` (modify) | `claimStartedAt`, `leaseWrittenAt`, `leaseRenewed` | 3 |
| `main/.../QueueRunner.java` (modify) | Claim and renewal timings and errors, outcomes, `renewalLag()`, `dbLastSuccessAge()` (Task 3); settings, the sweeper and sampler loops, `deadLoops()`, `hungTaskLimitReached()`, `backlog()` (Task 4) | 3, 4 |
| `test/.../ClaimHandleTest.java`, `QueueRunnerLifecycleTest.java` (modify) | The new constructor; the measurements (Task 3); the loops and dead loops (Task 4); `Tasks` moved out (Task 5) | 3, 4, 5 |
| `test/.../Tasks.java` (create) | Latch-controlled tasks, shared | 5 |
| `db-work-queue/work-queue-engine/pom.xml` (modify) | `micrometer-core` (Task 5), `spring-boot-health` (Task 6) | 5, 6 |
| `main/.../ItemProcessor.java` (modify) | `CallStatus`, call timings, an injectable clock | 5 |
| `main/.../WorkQueueMetrics.java` (create) | Every §9.6 meter | 5 |
| `test/.../ItemProcessorTest.java` (modify), `WorkQueueMetricsTest.java` (create) | Call timings; the meters | 5 |
| `main/.../WorkQueueHealth.java`, `test/.../WorkQueueHealthTest.java` (create) | Liveness and readiness | 6 |
| `db-work-queue/README.md` (modify) | Status line | 6 |

---

### Task 1: Spec revision 12 and the backlog sample

**Files:**
- Modify: `docs/superpowers/specs/2026-09-21-db-work-queue-design.md` (revision 12)
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/BacklogSample.java`
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/BacklogSampleTest.java` (create), `.../WorkItemRepositoryIT.java` (modify)

**Interfaces:**
- Consumes: `WorkItemRepository.inTransaction` and `leaseSeconds()` (existing).
- Produces: `public record BacklogSample(long pending, long claimed, long failed, long expiredClaims, Duration oldestPendingAge)`, which rejects negative counts and a null or negative age; `public BacklogSample WorkItemRepository.sampleBacklog()`. Task 2's `BacklogSampler` calls it, and Task 5's backlog gauges read the sample. Every later task implements against spec revision 12.

- [ ] **Step 1: Raise the spec to revision 12**

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/docs/superpowers/specs/2026-09-21-db-work-queue-design.md b/docs/superpowers/specs/2026-09-21-db-work-queue-design.md
index a193163..e5836a4 100644
--- a/docs/superpowers/specs/2026-09-21-db-work-queue-design.md
+++ b/docs/superpowers/specs/2026-09-21-db-work-queue-design.md
@@ -1,6 +1,6 @@
 # db-work-queue — Design
 
-Date: 2026-09-21 (revision 11: 2026-09-26)
+Date: 2026-09-21 (revision 12: 2026-09-30)
 Status: Architecture accepted. Phase 1 gate passed (2026-09-25); approved for Phase 2.
 Production approval pending review of this revision. Every time value in §5.3 and §7 is a conditional target
 pending validation by the Phase 1 spike, review of the §5.3 timing argument,
@@ -311,8 +311,9 @@ held = concurrency`.
 (interrupting it; its `finally` returns held permits; a claim already committed and
 returned is still started) → wait up to `shutdown-grace` for the registry to empty, with
 renewal running → cancel all remaining handles → wait up to `shutdown-cancel-wait` → stop
-renewal, supervisor and sweeper → return. Nothing is released in Db2; leftover claims
-expire with their attempt consumed. A stopped runner is not started again.
+renewal, supervisor, sweeper and backlog sampler, waiting at most 1s for them in all →
+return. Nothing is released in Db2; leftover claims expire with their attempt consumed. A
+stopped runner is not started again.
 
 **`crash()`** (package-private, tests only): stop all loops and cancel all handles at once,
 no drain and no waiting.
@@ -545,14 +546,15 @@ time, retention is permanent for the namespace.
 | `WorkQueueProperties` | `@ConfigurationProperties("workqueue")`, including `db.*` timeouts. |
 | `TimingBudget`, `RenewalSchedule` | B1–B5 at startup; the renewal next-start function. |
 | `WorkItemRepository` | All SQL via `JdbcClient`, each operation in a timed `TransactionTemplate`: claim, renew, complete, retryOrFail, sweep, backlog sample, replay, revokeOwner, readNamespace. Only class that knows Db2 syntax. |
-| `ClaimedItem`, `ClaimKey`, `IdempotencyKey`, `RenewalResult` | Records: `(id, operationId, payload, claimToken)`, `(id, token)`, `(namespace, operationId)` with validation (§5.4); `RenewalResult(renewed, ended, lost)`, the disjoint sets one renewal round reports (§5.3). |
+| `ClaimedItem`, `ClaimKey`, `IdempotencyKey`, `RenewalResult`, `BacklogSample` | Records: `(id, operationId, payload, claimToken)`, `(id, token)`, `(namespace, operationId)` with validation (§5.4); `RenewalResult(renewed, ended, lost)`, the disjoint sets one renewal round reports (§5.3); `BacklogSample(pending, claimed, failed, expiredClaims, oldestPendingAge)`, one backlog sample (§9.6). |
 | `Diagnostics` | What the engine may log about a failure: the class names down its cause chain, with SQL codes, never a message (§5.4). |
 | `ClaimHandle` | One claim's lifecycle state (§5.2): permit ownership, `markRunning`, `cancel`, exactly-once `finish`. |
-| `QueueRunner` | Poll loop, renewal loop, supervisor, registry, permits, stop/crash. |
+| `QueueRunner` | Poll loop, renewal loop, supervisor, registry, permits, stop/crash; runs the `Sweeper` and the `BacklogSampler` on two more loops, which stop and `crash()` also end. Records what health and the meters read (§9.6). |
 | `ItemProcessor`, `Outcome` | One row, one call; persist the result with retries; returns `COMPLETED`, `RETRY_SCHEDULED`, `FAILED`, `FENCED`, `ABANDONED`, `INTERRUPTED`, `CANCELLED`. Never throws. |
 | `ExternalService`, `CallResult` | SPI (§5.4). |
 | `Sweeper` | Every `sweep-interval`: expired CLAIMED rows with `ATTEMPTS ≥ max` → FAILED with `CLAIM_TOKEN + 1` and `OWNER = NULL` (§5.1), in batches of `sweep-batch-size` (`FETCH FIRST :s ROWS ONLY`, `SKIP LOCKED DATA`), repeating while a batch is full. Idempotent; runs on every instance; concurrent sweepers skip each other's rows instead of waiting. |
-| `BacklogSampler` | Every `backlog-sample-interval`: one query for DB-wide gauges (§9.6). |
+| `BacklogSampler` | Every `backlog-sample-interval`: one query for DB-wide gauges (§9.6). The gauges read the latest successful sample. |
+| `WorkQueueMetrics` | Binds the §9.6 meters to the application's Micrometer registry; each reads the engine's state when scraped. |
 | `WorkQueueHealth` | Liveness and readiness contributors (§9.6). |
 | `SchemaCheck` | At startup: engine migration applied; `WORK_QUEUE_META.NAMESPACE` matches the §5.4 format and equals `workqueue.expected-namespace`; `CURRENT TIMEZONE = 0` (§5.1). Fails fast otherwise. Workers never run DDL. |
 | `WorkQueueAdmin`, `WorkQueueEndpoint` | Replay and revokeOwner (§9.7); actuator endpoint `workqueue` with read (status) and write operations. Write operations are disabled unless `workqueue.admin.write-enabled=true`. |
@@ -807,22 +809,26 @@ changing replica count.
 
 | Metric | Type | Meaning |
 |---|---|---|
-| `claims`, `claim.duration`, `claim.errors` | counter, timer, counter | claim operations (an empty claim counts as a success) |
-| `outcomes{outcome}` | counter | one per task end |
-| `call.duration{result=ok\|error\|timeout}` | timer | external calls |
+| `claims`, `claim.duration`, `claim.errors` | counter, timer, counter | claim operations that returned (an empty claim counts), every claim operation, and those that failed |
+| `outcomes{outcome}` | counter | one per task end; a task cancelled before its body ran ends `CANCELLED`; a body that threw has no outcome |
+| `call.duration{result=ok\|error\|timeout\|interrupted}` | timer | external calls: `ok` returned a result; `error` threw or returned none; `timeout` threw `TimeoutException`; `interrupted` threw `InterruptedException`, or the interrupt status was set when it returned or threw |
 | `renewal.duration`, `renewal.errors` | timer, counter | renewal rounds that ran |
-| `renewal.lag` | gauge | max over renewal-eligible claims of the time since that claim's last successful lease write (claim or renewal); **0 when there are none** |
+| `renewal.lag` | gauge | max over renewal-eligible claims of the time since that claim's last successful lease write (claim or renewal), counted from the start of the operation that wrote it: the write came no earlier (§5.3), so a lag of at most `L` means the lease has not run out; **0 when there are none** |
 | `claims.lost` | counter | claims reported lost by renewal (§5.3); a claim its own task ended after the round's snapshot is not lost |
-| `db.last_success_age` | gauge | time since any engine DB operation succeeded; kept fresh on idle instances by the poll loop's empty claims, the sweeper and the backlog sampler |
+| `db.last_success_age` | gauge | time since any engine DB operation succeeded (a claim, renewal round, sweep or backlog sample that returned), counted from the runner's creation until the first; kept fresh on idle instances by the poll loop's empty claims, the sweeper and the backlog sampler |
 | `inflight`, `permits.available`, `tasks.hung` | gauges | local capacity |
 | `registration.late` | counter | handles registered more than `registration-allowance` after their claim returned (a process pause the B2 proof does not cover) |
 | `invariant.violations` | counter | engine invariant breaches, e.g. a registry key collision |
-| `backlog{status}`, `backlog.oldest_pending_age`, `claims.expired` | gauges (sampled) | DB-wide; `claims.expired` = CLAIMED rows expired for more than one lease (nobody is picking them up) |
+| `backlog{status}`, `backlog.oldest_pending_age`, `claims.expired` | gauges (sampled) | DB-wide, from one query that reads committed rows without waiting for locks; `status` is `pending`, `claimed` or `failed` (DONE rows only accumulate, and no alert reads them); `backlog.oldest_pending_age` = how long the oldest PENDING row that is claimable now has been claimable, 0 if none (rows waiting out `retry-backoff` are not waiting for capacity); `claims.expired` = CLAIMED rows expired for more than one lease (nobody is picking them up); no value (NaN) until the first sample, then the latest successful sample |
+
+The timers are Micrometer `FunctionTimer`s: they report a count and a total time, so a rate
+and a mean, but no maximum or percentiles.
 
 **Health:**
 
 - Liveness DOWN when `tasks.hung ≥ hung-task-limit`, when `invariant.violations > 0`, or
-  when the poll, renewal or supervisor thread has died.
+  when the poll, renewal or supervisor thread has died: ended while the runner still ran it,
+  not by stop or `crash()`. A dead sweeper or backlog sampler loop is logged only.
 - Readiness DOWN during stop; before `SchemaCheck` passes; when `renewal.lag > L` (the
   instance is losing claims — only possible while it holds claims; a single failed round,
   which B2 tolerates, does not trip it); or when
@@ -953,7 +959,16 @@ not.
   - renewal: a claim reported lost is counted and cancelled; one its own task ended after
     the snapshot is neither;
   - a task that throws, even an `Error` → logged by id, token and class name only,
-    `finish()` once.
+    `finish()` once;
+  - the sweeper and the backlog sampler run every interval until stop or `crash()`;
+    `stop()` waits at most 1s in all for the loops that outlive the drain; a poll, renewal
+    or supervisor loop that dies is reported dead until stop, and loops that stop ended
+    are not.
+- `SweeperTest`: a pass sweeps full batches until one comes back short; a failed batch
+  ends the pass, logged by class names only, and keeps the earlier batches; an interrupted
+  pass stops after its current batch.
+- `WorkQueueMetricsTest`: every §9.6 meter is registered with its tags and reads the
+  engine's state when scraped; the backlog gauges have no value until the first sample.
 - `RenewalScheduleTest`: `next(s, e, ok)` for success, overrun and failure.
 - `TimingBudgetTest`: each of B1–B5 rejects a violating config and names itself;
   `I = 15s, W = 5s, d = 1s, G = 1s, L = 26s` is rejected by B2; `W = 18s` is rejected by
@@ -991,10 +1006,13 @@ not.
   `("a", "b:c")` is accepted; namespaces outside `^[a-z0-9][a-z0-9-]{0,31}$` and empty or
   over-long operation ids are rejected; for generated valid pairs, distinct pairs always
   give distinct `value()`s, and splitting `value()` at the first `:` recovers the pair.
-- `WorkQueueHealthTest` (fake clock): an idle instance with no claims stays ready for
-  10 × lease and reports `renewal.lag = 0`; one failed renewal round keeps readiness UP;
-  an eligible claim unrenewed for more than `L` → readiness DOWN; `db.last_success_age > db-staleness-limit` with no claims →
-  readiness DOWN; both recover.
+- `WorkQueueHealthTest` (fake clock, defaults): an idle instance with no claims stays ready
+  for 10 × lease and reports `renewal.lag = 0`; one failed renewal round keeps readiness UP,
+  even in B2's worst case (a lag of 74s under the 100s lease); an eligible claim unrenewed
+  for more than `L` → readiness DOWN; `db.last_success_age > db-staleness-limit` with no
+  claims → readiness DOWN; both recover, and a sweep or backlog sample also keeps Db2
+  fresh; readiness DOWN from the start of stop; liveness DOWN at `hung-task-limit`, after
+  an invariant violation, and when a watched loop dies.
 - `ItemProcessorTest`: each `Outcome`; 0 rows with this owner's committed write →
   success outcome, not `FENCED`; exactly one call; timeout passed through; never throws.
 - `SimulatedDownstreamTest`: repeat key returns the stored result; `RESPONSE_LOST`
@@ -1020,7 +1038,10 @@ not.
    - sweep bumps the token and clears the owner; the swept owner's late renew, complete and
      retryOrFail are fenced;
    - replay resets `ATTEMPTS` and keeps `CLAIM_TOKEN` and `OPERATION_ID`;
-   - revokeOwner bumps the token, clears the owner, and sets PENDING or FAILED by `ATTEMPTS`.
+   - revokeOwner bumps the token, clears the owner, and sets PENDING or FAILED by `ATTEMPTS`;
+   - the backlog sample counts PENDING, CLAIMED and FAILED rows but not DONE ones, the claims
+     expired for more than one lease, and the age of the oldest claimable PENDING row; it
+     does not wait for a row another transaction has locked.
 3. `StaleCompletionIT` — A claims, expiry forced, B claims; A's renew reports lost, A's
    complete is fenced, B's result is stored.
 4. `QueryTimeoutIT` — a deliberately slow statement returns within `T_tx + 1s` with
@@ -1296,3 +1317,15 @@ and load validation.
 | Failures are logged by their diagnostics only (§5.4) | A message may carry business data, and a message or cause that throws would throw out of the log call. |
 | `QueueRunner` validates its intervals, graces and counts at startup | A zero `hung-grace` marked every cancelled task hung at once. |
 | §6 lists `RenewalResult` and `Diagnostics` | Both are engine components that revision 11 added (§5.3, §5.4), but the component table did not name them. |
+
+**Revision 12 (Phase 2d planning, `Sweeper`, metrics and health):**
+
+| Change | Reason |
+|---|---|
+| `renewal.lag` counts from the start of the operation that wrote the lease | The write lands no earlier (§5.3), so a lag of at most `L` means the lease has not run out. `claimedAt` is up to `W` later and would understate the lag. |
+| `db.last_success_age` counts from the runner's creation until the first success | "Time since the last success" had no value before one. |
+| `backlog{status}` covers `pending`, `claimed` and `failed`; `backlog.oldest_pending_age` measures claimable PENDING rows from `AVAILABLE_AT`; the backlog gauges have no value until the first sample and keep the latest successful one | DONE rows only accumulate and no alert reads them, and counting them on every sample grows with the table. A row waiting out `retry-backoff` is not waiting for capacity. |
+| The backlog sample reads committed rows without waiting for locks | A sample that waited behind a claim's row locks would run into `T_lock`. |
+| `call.duration` adds `result=interrupted`; `outcomes` counts a task cancelled before its body ran as `CANCELLED` | An interrupted call is neither an error nor a timeout, and a cancelled task still ends. |
+| The sweeper and the backlog sampler run on loops of `QueueRunner`; stop ends them with renewal and the supervisor, waiting at most 1s in all | They stop with the instance, `crash()` included. Waiting up to 1s for each of the four loops in turn could take 4s of E2's 5s margin. |
+| A dead loop is one that ended while the runner still ran it | The loops that stop ends must not turn liveness DOWN during a graceful stop. |
````

It changes §5.2 (the stop sequence ends the sampler too, within 1s in all), §6 (`BacklogSample`, `QueueRunner`'s two loops, `WorkQueueMetrics`), §9.6 (what each meter counts and from when, the backlog statuses, `result=interrupted`, `FunctionTimer`s, dead loops), §11.1 and §11.2, and adds the revision 12 change history.

- [ ] **Step 2: Write the failing tests**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/BacklogSampleTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacklogSampleTest {

    @Test
    void rejectsANegativeCount() {
        assertThatThrownBy(() -> new BacklogSample(-1, 0, 0, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pending");
        assertThatThrownBy(() -> new BacklogSample(0, -1, 0, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claimed");
        assertThatThrownBy(() -> new BacklogSample(0, 0, -1, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failed");
        assertThatThrownBy(() -> new BacklogSample(0, 0, 0, -1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expiredClaims");
    }

    @Test
    void rejectsAMissingOrNegativeAge() {
        assertThatThrownBy(() -> new BacklogSample(0, 0, 0, 0, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("oldestPendingAge");
        assertThatThrownBy(() -> new BacklogSample(0, 0, 0, 0, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oldestPendingAge");
    }
}
```

Add three tests to `WorkItemRepositoryIT`, after the sweep tests:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java
index 9976771..e1ce273 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java
@@ -468,6 +468,73 @@ class WorkItemRepositoryIT {
         assertThat(rows.row(locked).status()).isEqualTo("CLAIMED");
     }
 
+    @Test
+    void sampleBacklogCountsTheUnfinishedRowsTheExpiredClaimsAndTheOldestClaimablePendingRow() {
+        long oldest = rows.insert();
+        rows.setAvailableAt(oldest, -120);
+        long newer = rows.insert();
+        rows.setAvailableAt(newer, -30);
+        long backingOff = rows.insert();
+        rows.setAvailableAt(backingOff, 60);                 // PENDING in retry-backoff: not waiting on capacity
+        long live = rows.insert();
+        rows.setClaim(live, "owner-a", 1, 1, 20);
+        long recentlyExpired = rows.insert();
+        rows.setClaim(recentlyExpired, "owner-b", 1, 1, -10);   // expired less than one lease (30s) ago
+        long abandoned = rows.insert();
+        rows.setClaim(abandoned, "owner-c", 1, 1, -31);         // expired more than one lease ago
+        long failed = rows.insert();
+        rows.setStatus(failed, "FAILED");
+        long done = rows.insert();
+        rows.setStatus(done, "DONE");
+
+        BacklogSample sample = repository.sampleBacklog();
+
+        assertThat(sample.pending()).isEqualTo(3);
+        assertThat(sample.claimed()).isEqualTo(3);
+        assertThat(sample.failed()).isEqualTo(1);
+        assertThat(sample.expiredClaims()).isEqualTo(1);
+        assertThat(sample.oldestPendingAge()).isBetween(Duration.ofSeconds(120), Duration.ofSeconds(125));
+    }
+
+    @Test
+    void sampleBacklogOfAnEmptyQueueIsAllZero() {
+        long done = rows.insert();
+        rows.setStatus(done, "DONE");
+        long waiting = rows.insert();
+        rows.setAvailableAt(waiting, 60);
+
+        assertThat(repository.sampleBacklog()).isEqualTo(new BacklogSample(1, 0, 0, 0, Duration.ZERO));
+    }
+
+    @Test
+    void sampleBacklogDoesNotWaitForRowsLockedByAnotherTransaction() throws Exception {
+        long locked = rows.insert();
+        CountDownLatch held = new CountDownLatch(1);
+        CountDownLatch release = new CountDownLatch(1);
+
+        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
+            Future<Integer> holder = executor.submit(() -> repository.inTransaction(jdbc -> {
+                int updated = jdbc.sql("UPDATE WORK_ITEM SET STATUS = 'CLAIMED', OWNER = 'owner-a' WHERE ID = :id")
+                        .param("id", locked)
+                        .update();
+                held.countDown();
+                await(release);
+                return updated;
+            }));
+            await(held);
+
+            long start = System.nanoTime();
+            BacklogSample sample = repository.sampleBacklog();
+
+            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no lock wait")
+                    .isLessThan(Duration.ofSeconds(1));
+            assertThat(sample.pending()).as("the last committed state").isEqualTo(1);
+            assertThat(sample.claimed()).isZero();
+            release.countDown();
+            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(1);
+        }
+    }
+
     @Test
     void replayDryRunCountsAndExecuteRequeuesKeepingTokenAndOperationId() {
         long matching = failedRow("downstream 503");
````

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=BacklogSampleTest`
Expected: a compilation failure, `cannot find symbol` for `class BacklogSample` and `method sampleBacklog()`.

- [ ] **Step 4: Implement the sample**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/BacklogSample.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * One backlog sample (spec §9.6), taken by one query on the Db2 clock. DONE rows are not counted: they only
 * accumulate, and no alert reads them.
 *
 * @param pending          PENDING rows, claimable now or waiting out retry-backoff
 * @param claimed          CLAIMED rows, live or expired
 * @param failed           FAILED rows, waiting for an operator's replay
 * @param expiredClaims    CLAIMED rows whose lease ended more than one lease ago: nobody is picking them up
 * @param oldestPendingAge how long the oldest PENDING row that is claimable now has been claimable; zero if none
 */
public record BacklogSample(long pending, long claimed, long failed, long expiredClaims, Duration oldestPendingAge) {

    public BacklogSample {
        requireNotNegative("pending", pending);
        requireNotNegative("claimed", claimed);
        requireNotNegative("failed", failed);
        requireNotNegative("expiredClaims", expiredClaims);
        Objects.requireNonNull(oldestPendingAge, "oldestPendingAge");
        if (oldestPendingAge.isNegative()) {
            throw new IllegalArgumentException("oldestPendingAge must not be negative: " + oldestPendingAge);
        }
    }

    private static void requireNotNegative(String name, long count) {
        if (count < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + count);
        }
    }
}
```

Add `sampleBacklog()` to `WorkItemRepository`, before `replay`. `CURRENT TIMESTAMP` has one value per statement, so the expired-claims cut-off and the age use the same instant:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java
index 0aea587..454cdab 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java
@@ -220,6 +220,32 @@ public class WorkItemRepository {
                 .size());
     }
 
+    /**
+     * One backlog sample (spec §9.6) in one query. It reads only unfinished rows, so Db2 can answer from
+     * IX_WORK_ITEM_CLAIM without visiting DONE rows, and it reads committed data without waiting for row locks.
+     */
+    public BacklogSample sampleBacklog() {
+        return inTransaction(jdbc -> jdbc.sql("""
+                SELECT COUNT(CASE WHEN STATUS = 'PENDING' THEN 1 END) AS PENDING,
+                       COUNT(CASE WHEN STATUS = 'CLAIMED' THEN 1 END) AS CLAIMED,
+                       COUNT(CASE WHEN STATUS = 'FAILED' THEN 1 END) AS FAILED,
+                       COUNT(CASE WHEN STATUS = 'CLAIMED'
+                                   AND AVAILABLE_AT < CURRENT TIMESTAMP - (CAST(:leaseSeconds AS INTEGER)) SECONDS
+                                  THEN 1 END) AS EXPIRED_CLAIMS,
+                       SECONDS_BETWEEN(CURRENT TIMESTAMP,
+                                       MIN(CASE WHEN STATUS = 'PENDING' AND AVAILABLE_AT <= CURRENT TIMESTAMP
+                                                THEN AVAILABLE_AT END)) AS OLDEST_PENDING_SECONDS
+                  FROM WORK_ITEM
+                 WHERE STATUS IN ('PENDING', 'CLAIMED', 'FAILED')
+                """)
+                .param("leaseSeconds", leaseSeconds())
+                // OLDEST_PENDING_SECONDS is NULL without a claimable PENDING row, and getLong reads NULL as 0.
+                .query((rs, rowNum) -> new BacklogSample(rs.getLong("PENDING"), rs.getLong("CLAIMED"),
+                        rs.getLong("FAILED"), rs.getLong("EXPIRED_CLAIMS"),
+                        Duration.ofSeconds(rs.getLong("OLDEST_PENDING_SECONDS"))))
+                .single());
+    }
+
     /**
      * Operator replay of FAILED rows (spec §9.7). A dry run counts the matching rows; otherwise they become
      * PENDING with ATTEMPTS = 0 and no owner, keeping CLAIM_TOKEN and OPERATION_ID.
````

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl work-queue-engine verify -Dtest=BacklogSampleTest -Dit.test=WorkItemRepositoryIT`
Expected: BUILD SUCCESS, with `BacklogSampleTest` (2 tests) and `WorkItemRepositoryIT` (30 tests, 3 of them new) passing. Db2 accepts `SECONDS_BETWEEN`, and the sample returns in well under a second while another transaction holds a row lock.

- [ ] **Step 6: Commit** (from the repository root)

```bash
git add docs/superpowers/specs/2026-09-21-db-work-queue-design.md \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/BacklogSample.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/BacklogSampleTest.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java
git commit -m "feat: sample the work-queue backlog in one query (spec revision 12)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: DbActivity, the Sweeper and the BacklogSampler

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/DbActivity.java`, `Sweeper.java`, `BacklogSampler.java`
- Modify: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DbActivityTest.java`, `SweeperTest.java`, `BacklogSamplerTest.java` (create)

**Interfaces:**
- Consumes: `WorkItemRepository.sweep(int)` (existing) and `sampleBacklog()` (Task 1); `Diagnostics.describe`.
- Produces:
  - `final class DbActivity` with `DbActivity(LongSupplier clock)`, `void succeeded()` and `Duration lastSuccessAge()`. Tasks 3 and 4 give the runner one.
  - `final class Sweeper` with `Sweeper(WorkItemRepository repository, String owner, int batchSize, DbActivity db)` and `int sweepOnce()`, which returns the rows swept.
  - `final class BacklogSampler` with `BacklogSampler(WorkItemRepository repository, String owner, DbActivity db)`, `boolean sampleOnce()` and `BacklogSample latest()` (null before the first sample).
  - `ScriptedRepository`: `thenSweep(int...)`, `thenSweep(Supplier<Integer>)`, `thenSweepThrow(RuntimeException)`, `List<Integer> sweepSizes()`, `thenSample(BacklogSample)`, `thenSample(Supplier<BacklogSample>)`, `thenSampleThrow(RuntimeException)` and `int samples()`. An unscripted sweep sweeps nothing, and an unscripted sample finds an empty queue.

- [ ] **Step 1: Make ScriptedRepository scriptable for sweeps and samples**

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java
index 35f5dfc..1421bb0 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java
@@ -10,6 +10,7 @@ import java.util.List;
 import java.util.Set;
 import java.util.concurrent.ConcurrentLinkedDeque;
 import java.util.concurrent.CopyOnWriteArrayList;
+import java.util.concurrent.atomic.AtomicInteger;
 import java.util.function.Function;
 import java.util.function.Supplier;
 
@@ -18,9 +19,9 @@ import static java.util.stream.Collectors.toSet;
 /**
  * A WorkItemRepository for unit tests that never touches a database. Every operation answers from its own script,
  * in order, and is recorded. An unscripted persist fails the test with an AssertionError; an unscripted claim
- * finds nothing, and an unscripted renewal renews every claim. It is thread-safe: the poll loop, the renewal loop
- * and task threads call it concurrently, and a step runs outside any lock, so one that blocks holds up only its
- * own caller.
+ * finds nothing, an unscripted renewal renews every claim, an unscripted sweep sweeps nothing, and an unscripted
+ * sample finds an empty queue. It is thread-safe: the runner's loops and task threads call it concurrently, and a
+ * step runs outside any lock, so one that blocks holds up only its own caller.
  */
 class ScriptedRepository extends WorkItemRepository {
 
@@ -36,6 +37,10 @@ class ScriptedRepository extends WorkItemRepository {
     private final List<Integer> claimSizes = new CopyOnWriteArrayList<>();
     private final Deque<Function<Set<ClaimKey>, RenewalResult>> renewals = new ConcurrentLinkedDeque<>();
     private final List<Set<ClaimKey>> renewRequests = new CopyOnWriteArrayList<>();
+    private final Deque<Supplier<Integer>> sweeps = new ConcurrentLinkedDeque<>();
+    private final List<Integer> sweepSizes = new CopyOnWriteArrayList<>();
+    private final Deque<Supplier<BacklogSample>> samples = new ConcurrentLinkedDeque<>();
+    private final AtomicInteger sampleCount = new AtomicInteger();
 
     ScriptedRepository() {
         super(new DriverManagerDataSource(), DbTimeouts.defaults(),
@@ -100,6 +105,42 @@ class ScriptedRepository extends WorkItemRepository {
         return this;
     }
 
+    /** The next sweeps return these counts, one sweep per count. */
+    ScriptedRepository thenSweep(int... counts) {
+        for (int count : counts) {
+            thenSweep(() -> count);
+        }
+        return this;
+    }
+
+    ScriptedRepository thenSweepThrow(RuntimeException failure) {
+        return thenSweep(() -> {
+            throw failure;
+        });
+    }
+
+    /** The next sweep runs {@code step}. */
+    ScriptedRepository thenSweep(Supplier<Integer> step) {
+        sweeps.add(step);
+        return this;
+    }
+
+    ScriptedRepository thenSample(BacklogSample sample) {
+        return thenSample(() -> sample);
+    }
+
+    ScriptedRepository thenSampleThrow(RuntimeException failure) {
+        return thenSample(() -> {
+            throw failure;
+        });
+    }
+
+    /** The next backlog sample runs {@code step}. */
+    ScriptedRepository thenSample(Supplier<BacklogSample> step) {
+        samples.add(step);
+        return this;
+    }
+
     List<Write> writes() {
         return List.copyOf(writes);
     }
@@ -114,6 +155,16 @@ class ScriptedRepository extends WorkItemRepository {
         return List.copyOf(renewRequests);
     }
 
+    /** The batch size of every sweep, in order. */
+    List<Integer> sweepSizes() {
+        return List.copyOf(sweepSizes);
+    }
+
+    /** How many backlog samples were taken. */
+    int samples() {
+        return sampleCount.get();
+    }
+
     @Override
     public List<ClaimedItem> claim(String owner, int n) {
         claimSizes.add(n);
@@ -129,6 +180,20 @@ class ScriptedRepository extends WorkItemRepository {
         return step == null ? new RenewalResult(requested, Set.of(), Set.of()) : step.apply(requested);
     }
 
+    @Override
+    public int sweep(int batchSize) {
+        sweepSizes.add(batchSize);
+        Supplier<Integer> step = sweeps.poll();
+        return step == null ? 0 : step.get();
+    }
+
+    @Override
+    public BacklogSample sampleBacklog() {
+        sampleCount.incrementAndGet();
+        Supplier<BacklogSample> step = samples.poll();
+        return step == null ? new BacklogSample(0, 0, 0, 0, Duration.ZERO) : step.get();
+    }
+
     @Override
     public PersistResult complete(String owner, ClaimKey claim, String resultValue) {
         return next(new Write(Operation.COMPLETE, owner, claim, resultValue));
````

- [ ] **Step 2: Write the failing tests**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DbActivityTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

class DbActivityTest {

    private static final long SECOND = 1_000_000_000L;

    // Starts 5s before overflow, so the ages below wrap around.
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final DbActivity db = new DbActivity(now::get);

    @Test
    void beforeTheFirstSuccessTheAgeCountsFromCreation() {
        assertThat(db.lastSuccessAge()).isZero();

        now.addAndGet(7 * SECOND);

        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(7));
    }

    @Test
    void aSuccessRestartsTheAge() {
        now.addAndGet(7 * SECOND);
        db.succeeded();
        now.addAndGet(2 * SECOND);

        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(2));
    }

    @Test
    void aReportThatReadTheClockEarlierDoesNotMoveTheLastSuccessBack() {
        // The clock answers the constructor, a report at 10s, a slower report that read 4s, and the age at 12s.
        AtomicLong reading = new AtomicLong();
        List<Long> readings = List.of(0L, 10 * SECOND, 4 * SECOND, 12 * SECOND);
        DbActivity reordered = new DbActivity(() -> readings.get((int) reading.getAndIncrement()));

        reordered.succeeded();
        reordered.succeeded();

        assertThat(reordered.lastSuccessAge()).isEqualTo(ofSeconds(2));
    }

    @Test
    void theAgeIsNeverNegative() {
        now.addAndGet(-1);

        assertThat(db.lastSuccessAge()).isZero();
    }
}
```

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/SweeperTest.java`:

```java
package hle.org.workqueue.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.concurrent.atomic.AtomicLong;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §6 {@code Sweeper}: one pass sweeps batches until one comes back less than full. */
class SweeperTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String OWNER = "instance-a";
    private static final int BATCH_SIZE = 100;

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final DbActivity db = new DbActivity(now::get);
    private final Sweeper sweeper = new Sweeper(repository, OWNER, BATCH_SIZE, db);
    private final Logger sweeperLog = (Logger) LoggerFactory.getLogger(Sweeper.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logged.start();
        sweeperLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogsAndInterruptStatus() {
        sweeperLog.detachAppender(logged);
        Thread.interrupted();
    }

    @Test
    void aPassSweepsFullBatchesUntilOneComesBackShort() {
        repository.thenSweep(100, 100, 7);

        assertThat(sweeper.sweepOnce()).isEqualTo(207);

        assertThat(repository.sweepSizes()).containsExactly(100, 100, 100);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "Sweeper of owner instance-a marked 207 expired claims with exhausted attempts FAILED");
        });
    }

    @Test
    void aPassWithNothingToSweepMakesOneSweepAndLogsNothing() {
        assertThat(sweeper.sweepOnce()).isZero();

        assertThat(repository.sweepSizes()).containsExactly(100);
        assertThat(logged.list).isEmpty();
    }

    @Test
    void aFailedSweepEndsThePassLogsOnlyClassNamesAndKeepsTheEarlierBatches() {
        repository.thenSweep(100).thenSweepThrow(new DataAccessResourceFailureException("row of order-7:charge"));

        assertThat(sweeper.sweepOnce()).isEqualTo(100);

        assertThat(repository.sweepSizes()).containsExactly(100, 100);
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Sweep by owner instance-a failed after 100 rows: "
                        + "org.springframework.dao.DataAccessResourceFailureException",
                "Sweeper of owner instance-a marked 100 expired claims with exhausted attempts FAILED");
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void anInterruptedPassStopsAfterItsCurrentBatch() {
        repository.thenSweep(100, 100);
        Thread.currentThread().interrupt();

        assertThat(sweeper.sweepOnce()).isEqualTo(100);

        assertThat(repository.sweepSizes()).containsExactly(100);
    }

    @Test
    void everySweepThatReturnsIsADbSuccess() {
        now.addAndGet(5 * SECOND);
        repository.thenSweep(() -> {
            now.addAndGet(SECOND);   // the sweep takes a second
            return 0;
        });

        sweeper.sweepOnce();

        assertThat(db.lastSuccessAge()).isZero();
        now.addAndGet(3 * SECOND);
        repository.thenSweepThrow(new DataAccessResourceFailureException("Db2 unreachable"));
        sweeper.sweepOnce();
        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(3));
    }

    @Test
    void rejectsABatchSizeBelowOne() {
        assertThatThrownBy(() -> new Sweeper(repository, OWNER, 0, db))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchSize");
    }
}
```

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/BacklogSamplerTest.java`:

```java
package hle.org.workqueue.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.concurrent.atomic.AtomicLong;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec §6 {@code BacklogSampler}: the gauges read its latest successful sample. */
class BacklogSamplerTest {

    private static final long SECOND = 1_000_000_000L;
    private static final BacklogSample SAMPLE = new BacklogSample(12, 4, 1, 0, ofSeconds(30));

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final DbActivity db = new DbActivity(now::get);
    private final BacklogSampler sampler = new BacklogSampler(repository, "instance-a", db);
    private final Logger samplerLog = (Logger) LoggerFactory.getLogger(BacklogSampler.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logged.start();
        samplerLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        samplerLog.detachAppender(logged);
    }

    @Test
    void thereIsNoSampleBeforeTheFirst() {
        assertThat(sampler.latest()).isNull();
    }

    @Test
    void aSampleIsKeptAndIsADbSuccess() {
        now.addAndGet(5 * SECOND);
        repository.thenSample(SAMPLE);

        assertThat(sampler.sampleOnce()).isTrue();

        assertThat(sampler.latest()).isEqualTo(SAMPLE);
        assertThat(db.lastSuccessAge()).isZero();
        assertThat(logged.list).isEmpty();
    }

    @Test
    void aFailedSampleKeepsThePreviousOneAndLogsOnlyClassNames() {
        repository.thenSample(SAMPLE).thenSampleThrow(new DataAccessResourceFailureException("row of order-7:charge"));
        sampler.sampleOnce();
        now.addAndGet(5 * SECOND);

        assertThat(sampler.sampleOnce()).isFalse();

        assertThat(sampler.latest()).isEqualTo(SAMPLE);
        assertThat(db.lastSuccessAge()).isEqualTo(ofSeconds(5));
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Backlog sample by owner instance-a failed: "
                    + "org.springframework.dao.DataAccessResourceFailureException");
        });
    }

    @Test
    void aMissingSampleIsAFailure() {
        repository.thenSample(() -> null);

        assertThat(sampler.sampleOnce()).isFalse();

        assertThat(sampler.latest()).isNull();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest='DbActivityTest,SweeperTest,BacklogSamplerTest'`
Expected: a compilation failure, `cannot find symbol` for `class DbActivity`, `class Sweeper` and `class BacklogSampler`.

- [ ] **Step 4: Implement the three classes**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/DbActivity.java`:

```java
package hle.org.workqueue.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * When an engine DB operation last succeeded (spec §9.6 {@code db.last_success_age}). The claims, renewal rounds,
 * sweeps and backlog samples that return report here; readiness turns DOWN once the age passes db-staleness-limit.
 * Times are readings of the runner's clock, compared overflow-safely.
 */
final class DbActivity {

    private final LongSupplier clock;
    private final AtomicLong lastSuccess;

    /** Until the first success, the age counts from here: the runner's creation at startup. */
    DbActivity(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastSuccess = new AtomicLong(clock.getAsLong());
    }

    void succeeded() {
        long now = clock.getAsLong();
        // Loops report concurrently: a report that read the clock earlier but arrives later must not move it back.
        lastSuccess.accumulateAndGet(now, (last, reported) -> reported - last > 0 ? reported : last);
    }

    Duration lastSuccessAge() {
        return Duration.ofNanos(Math.max(0, clock.getAsLong() - lastSuccess.get()));
    }
}
```

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Sweeper.java`:

```java
package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The Sweeper (spec §6): fails the expired claims whose attempts are exhausted, which no claim can take again. One
 * pass sweeps batches of sweep-batch-size until one comes back less than full; QueueRunner's sweeper loop runs a pass
 * every sweep-interval. It is idempotent and runs on every instance: concurrent sweeps skip each other's rows.
 */
final class Sweeper {

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final WorkItemRepository repository;
    private final String owner;
    private final int batchSize;
    private final DbActivity db;

    Sweeper(WorkItemRepository repository, String owner, int batchSize, DbActivity db) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.owner = Objects.requireNonNull(owner, "owner");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        this.batchSize = batchSize;
        this.db = Objects.requireNonNull(db, "db");
    }

    /**
     * One pass. A failed sweep ends it, logged by its diagnostics, and the batches before it stay swept; so does an
     * interrupt, after the current batch. Returns the number of rows swept.
     */
    int sweepOnce() {
        int total = 0;
        try {
            int swept;
            do {
                swept = repository.sweep(batchSize);
                db.succeeded();
                total += swept;
            } while (swept == batchSize && !Thread.currentThread().isInterrupted());
        } catch (RuntimeException e) {
            log.warn("Sweep by owner {} failed after {} rows: {}", owner, total, Diagnostics.describe(e));
        }
        if (total > 0) {
            log.warn("Sweeper of owner {} marked {} expired claims with exhausted attempts FAILED", owner, total);
        }
        return total;
    }
}
```

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/BacklogSampler.java`:

```java
package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The BacklogSampler (spec §6): one query for the DB-wide gauges of spec §9.6, which QueueRunner's sampler loop runs
 * every backlog-sample-interval. The gauges read the latest successful sample: none before the first, and the last
 * one after a failure, whose staleness {@code db.last_success_age} shows.
 */
final class BacklogSampler {

    private static final Logger log = LoggerFactory.getLogger(BacklogSampler.class);

    private final WorkItemRepository repository;
    private final String owner;
    private final DbActivity db;
    private volatile BacklogSample latest;

    BacklogSampler(WorkItemRepository repository, String owner, DbActivity db) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.db = Objects.requireNonNull(db, "db");
    }

    /** Takes one sample. A failure is logged by its diagnostics and keeps the previous sample. */
    boolean sampleOnce() {
        try {
            latest = Objects.requireNonNull(repository.sampleBacklog(), "sample");
            db.succeeded();
            return true;
        } catch (RuntimeException e) {
            log.warn("Backlog sample by owner {} failed: {}", owner, Diagnostics.describe(e));
            return false;
        }
    }

    /** The latest successful sample, or null before the first. */
    BacklogSample latest() {
        return latest;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test`
Expected: exit code 0, with 421 unit tests passing: `DbActivityTest` 4, `SweeperTest` 6 and `BacklogSamplerTest` 4 of them new.

- [ ] **Step 6: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/DbActivity.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Sweeper.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/BacklogSampler.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DbActivityTest.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/SweeperTest.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/BacklogSamplerTest.java
git commit -m "feat: add the Sweeper, the BacklogSampler and DB activity tracking" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: What the runner records for health and the meters

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/OperationStats.java`
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java`, `QueueRunner.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java`, `QueueRunnerLifecycleTest.java` (modify)

**Interfaces:**
- Consumes: `DbActivity` (Task 2).
- Produces:
  - `final class OperationStats` with `void record(long nanos)`, `long count()` and `long totalNanos()`.
  - `ClaimHandle(ClaimedItem item, long claimStartedAt, long claimedAt, Duration maxProcessingTime, Map<ClaimKey, ClaimHandle> registry, Semaphore permits)`, `long leaseWrittenAt()` and `void leaseRenewed(long roundStart)`.
  - On `QueueRunner`: `long claims()`, `long claimErrors()`, `OperationStats claimTimes()`, `long renewalErrors()`, `OperationStats renewalTimes()`, `long outcomes(Outcome)`, `Duration renewalLag()` and `Duration dbLastSuccessAge()`. Task 5 binds them to meters, and Task 6's readiness reads the last two.

- [ ] **Step 1: Write the failing tests**

`ClaimHandleTest` passes the claim's start to the new constructor and adds two tests:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java
index 0910a36..9534e92 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java
@@ -52,10 +52,27 @@ class ClaimHandleTest {
 
     @Test
     void rejectsANonPositiveMaxProcessingTime() {
-        assertThatThrownBy(() -> new ClaimHandle(item(1, 1), 0, Duration.ZERO, registry, permits))
+        assertThatThrownBy(() -> new ClaimHandle(item(1, 1), 0, 0, Duration.ZERO, registry, permits))
                 .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxProcessingTime");
     }
 
+    @Test
+    void rejectsAClaimThatReturnedBeforeItStarted() {
+        assertThatThrownBy(() -> new ClaimHandle(item(1, 1), 2 * SECOND, SECOND, MAX_PROCESSING_TIME, registry,
+                permits)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claimStartedAt");
+    }
+
+    @Test
+    void theLeaseCountsFromTheClaimOperationsStartUntilARoundRenewsIt() {
+        ClaimHandle handle = new ClaimHandle(item(1, 1), Long.MAX_VALUE - SECOND, Long.MAX_VALUE + SECOND,
+                MAX_PROCESSING_TIME, registry, permits);   // the claim took 2s, across the overflow
+        assertThat(handle.leaseWrittenAt()).isEqualTo(Long.MAX_VALUE - SECOND);
+
+        handle.leaseRenewed(Long.MAX_VALUE + 15 * SECOND);
+
+        assertThat(handle.leaseWrittenAt()).isEqualTo(Long.MAX_VALUE + 15 * SECOND);
+    }
+
     @Test
     void finishRemovesTheHandleAndReturnsItsPermitOnce() {
         ClaimHandle handle = handle(1, 1);
@@ -194,7 +211,7 @@ class ClaimHandleTest {
         for (int i = 0; i < RACE_ITERATIONS; i++) {
             Semaphore racePermits = new Semaphore(0);
             Map<ClaimKey, ClaimHandle> raceRegistry = new ConcurrentHashMap<>();
-            ClaimHandle handle = new ClaimHandle(item(i, 1), 0, MAX_PROCESSING_TIME, raceRegistry, racePermits);
+            ClaimHandle handle = new ClaimHandle(item(i, 1), 0, 0, MAX_PROCESSING_TIME, raceRegistry, racePermits);
             handle.register();
             CyclicBarrier start = new CyclicBarrier(3);
 
@@ -317,7 +334,7 @@ class ClaimHandleTest {
 
     @Test
     void toStringShowsOnlyTheIdAndToken() {
-        ClaimHandle handle = new ClaimHandle(new ClaimedItem(1, "op-secret", "payload-secret", 7), 0,
+        ClaimHandle handle = new ClaimHandle(new ClaimedItem(1, "op-secret", "payload-secret", 7), 0, 0,
                 MAX_PROCESSING_TIME, registry, permits);
 
         assertThat(handle).hasToString("ClaimHandle[id=1, token=7]");
@@ -328,7 +345,7 @@ class ClaimHandleTest {
     }
 
     private ClaimHandle handle(long id, long token, long claimedAt) {
-        return new ClaimHandle(item(id, token), claimedAt, MAX_PROCESSING_TIME, registry, permits);
+        return new ClaimHandle(item(id, token), claimedAt, claimedAt, MAX_PROCESSING_TIME, registry, permits);
     }
 
     private static ClaimedItem item(long id, long token) {
````

Add a section to `QueueRunnerLifecycleTest`, before the start, stop and crash section, and extend two existing tests: a handle cancelled before its body runs ends `CANCELLED`, and a body that throws has no outcome.

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
index 245b376..01ab56c 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
@@ -345,6 +345,7 @@ class QueueRunnerLifecycleTest {
 
         await().until(() -> handle(key(1, 1)).isEnded());
         assertThat(tasks.started()).isEmpty();
+        assertThat(runner.outcomes(Outcome.CANCELLED)).isEqualTo(1);
         await().untilAsserted(this::assertPermitInvariant);
     }
 
@@ -379,6 +380,7 @@ class QueueRunnerLifecycleTest {
             assertThat(event.getFormattedMessage()).isEqualTo("Task for claim ClaimHandle[id=1, token=1] of owner"
                     + " instance-a failed: java.lang.StackOverflowError");
         });
+        assertThat(Arrays.stream(Outcome.values()).mapToLong(runner::outcomes).sum()).as("no outcome").isZero();
     }
 
     @Test
@@ -642,6 +644,145 @@ class QueueRunnerLifecycleTest {
         assertThat(tasks.highWater()).isEqualTo(CONCURRENCY);
     }
 
+    // ---- What health and the meters read (spec §9.6) ---------------------------------------------------------
+
+    @Test
+    void aClaimIsTimedAndCountedAndItsLeaseCountsFromTheClaimsStart() throws Exception {
+        long start = now.get();
+        repository.thenClaim(() -> {
+            now.addAndGet(3 * SECOND);   // the claim takes 3s
+            return List.of(item(1, 1));
+        });
+
+        runner.pollOnce();
+
+        assertThat(runner.claims()).isEqualTo(1);
+        assertThat(runner.claimErrors()).isZero();
+        assertThat(runner.claimTimes().count()).isEqualTo(1);
+        assertThat(runner.claimTimes().totalNanos()).isEqualTo(3 * SECOND);
+        assertThat(handle(key(1, 1)).leaseWrittenAt()).isEqualTo(start);
+        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(3));
+    }
+
+    @Test
+    void aFailedClaimIsTimedAndCountedAsAnError() throws Exception {
+        repository.thenClaim(() -> {
+            now.addAndGet(2 * SECOND);
+            throw UNREACHABLE;
+        });
+
+        runner.pollOnce();
+
+        assertThat(runner.claims()).isZero();
+        assertThat(runner.claimErrors()).isEqualTo(1);
+        assertThat(runner.claimTimes().count()).isEqualTo(1);
+        assertThat(runner.claimTimes().totalNanos()).isEqualTo(2 * SECOND);
+    }
+
+    @Test
+    void theRenewalLagIgnoresEndedCancelledAndPastDeadlineClaims() throws Exception {
+        assertThat(runner.renewalLag()).as("no claims").isZero();
+        tasks.ignoreInterrupts();
+        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
+        now.addAndGet(5 * SECOND);
+        tasks.release(key(1, 1));                    // claim 1 ends
+        await().until(() -> handle(key(1, 1)).isEnded());
+        repository.thenRenewLosing(key(2, 1));
+        runner.renewOnce();                          // claim 2 is lost: cancelled, but its task keeps running
+        now.addAndGet(20 * SECOND);                  // claim 3's deadline; no supervisor pass has cancelled it
+
+        assertThat(runner.renewalLag()).isZero();
+        assertThat(runner.inflight()).isEqualTo(2);
+    }
+
+    @Test
+    void aRoundThatRenewsAClaimRestartsItsLagFromTheRoundsStart() throws Exception {
+        claimAndStart(item(1, 1));
+        now.addAndGet(5 * SECOND);
+        repository.thenRenew(requested -> {
+            now.addAndGet(SECOND);   // the round takes a second
+            return new RenewalResult(requested, Set.of(), Set.of());
+        });
+
+        assertThat(runner.renewOnce()).isTrue();
+
+        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(1));
+        assertThat(runner.renewalTimes().count()).isEqualTo(1);
+        assertThat(runner.renewalTimes().totalNanos()).isEqualTo(SECOND);
+        assertThat(runner.renewalErrors()).isZero();
+    }
+
+    @Test
+    void aClaimTheRoundDidNotRenewKeepsItsLag() throws Exception {
+        claimAndStart(item(1, 1), item(2, 1));
+        now.addAndGet(5 * SECOND);
+        repository.thenRenewEnded(key(2, 1));   // claim 2's task persisted after the snapshot and is still ending
+
+        runner.renewOnce();
+
+        assertThat(handle(key(1, 1)).leaseWrittenAt()).isEqualTo(now.get());
+        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(5));
+    }
+
+    @Test
+    void aFailedRoundLeavesTheLagGrowingAndCountsAnError() throws Exception {
+        claimAndStart(item(1, 1));
+        now.addAndGet(5 * SECOND);
+        repository.thenRenew(requested -> {
+            now.addAndGet(SECOND);
+            throw UNREACHABLE;
+        });
+
+        assertThat(runner.renewOnce()).isFalse();
+
+        assertThat(runner.renewalLag()).isEqualTo(ofSeconds(6));
+        assertThat(runner.renewalErrors()).isEqualTo(1);
+        assertThat(runner.renewalTimes().count()).isEqualTo(1);
+        assertThat(runner.renewalTimes().totalNanos()).isEqualTo(SECOND);
+    }
+
+    @Test
+    void theDbAgeCountsFromCreationThenFromTheLastClaimOrRoundThatReturned() throws Exception {
+        now.addAndGet(5 * SECOND);
+        assertThat(runner.dbLastSuccessAge()).isEqualTo(ofSeconds(5));
+        runner.renewOnce();                          // skipped: nothing to renew
+        assertThat(runner.dbLastSuccessAge()).as("a skipped round is no DB success").isEqualTo(ofSeconds(5));
+        assertThat(runner.renewalTimes().count()).as("nor a round that ran").isZero();
+
+        runner.pollOnce();                           // an empty claim
+        assertThat(runner.dbLastSuccessAge()).isZero();
+
+        now.addAndGet(3 * SECOND);
+        repository.thenClaimThrow(UNREACHABLE);
+        runner.pollOnce();
+        assertThat(runner.dbLastSuccessAge()).isEqualTo(ofSeconds(3));
+
+        claimAndStart(item(1, 1));
+        now.addAndGet(2 * SECOND);
+        repository.thenRenewThrow(UNREACHABLE);
+        runner.renewOnce();
+        assertThat(runner.dbLastSuccessAge()).isEqualTo(ofSeconds(2));
+        runner.renewOnce();
+        assertThat(runner.dbLastSuccessAge()).isZero();
+    }
+
+    @Test
+    void everyTaskEndIsCountedByItsOutcome() throws Exception {
+        tasks.endWith(key(2, 1), Outcome.RETRY_SCHEDULED);
+        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
+        repository.thenRenewLosing(key(3, 1));
+        runner.renewOnce();                          // claim 3 is cancelled: its task is interrupted
+
+        tasks.release(key(1, 1));
+        tasks.release(key(2, 1));
+        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
+
+        assertThat(runner.outcomes(Outcome.COMPLETED)).isEqualTo(1);
+        assertThat(runner.outcomes(Outcome.RETRY_SCHEDULED)).isEqualTo(1);
+        assertThat(runner.outcomes(Outcome.INTERRUPTED)).isEqualTo(1);
+        assertThat(runner.outcomes(Outcome.FAILED)).isZero();
+    }
+
     // ---- start, stop and crash (spec §5.2) ------------------------------------------------------------------
 
     @Test
````

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest='QueueRunnerLifecycleTest,ClaimHandleTest'`
Expected: a compilation failure, `cannot find symbol` for `renewalLag()`, `dbLastSuccessAge()`, `renewalTimes()`, `outcomes(Outcome)`, `leaseWrittenAt()`, `claimTimes()`, `claims()`, `claimErrors()`, `renewalErrors()` and `leaseRenewed(long)`.

- [ ] **Step 3: Implement the measurements**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/OperationStats.java`:

```java
package hle.org.workqueue.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The count and total duration of one kind of operation (spec §9.6 timers), which a Micrometer FunctionTimer reads
 * when scraped. Durations are differences of {@code System.nanoTime()} readings, so never negative.
 */
final class OperationStats {

    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    void record(long nanos) {
        count.incrementAndGet();
        totalNanos.addAndGet(nanos);
    }

    long count() {
        return count.get();
    }

    long totalNanos() {
        return totalNanos.get();
    }
}
```

`ClaimHandle` records the start of the operation that last wrote its lease:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java
index 3257900..b92bee6 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java
@@ -42,17 +42,26 @@ final class ClaimHandle {
     private CancelReason cancelReason;
     private long cancelledAt;
 
+    // Only the renewal loop writes it after construction; the gauges read it.
+    private volatile long leaseWrittenAt;
+
     /**
      * Has no side effects: the poll loop still owns the permit until it transfers it after construction.
      *
-     * @param claimedAt when the claim operation returned; the deadline and the registration-late check count from it
+     * @param claimStartedAt when the claim operation started: its lease-setting write came no earlier (spec §5.3)
+     * @param claimedAt      when the claim operation returned; the deadline and the registration-late check count
+     *                       from it
      */
-    ClaimHandle(ClaimedItem item, long claimedAt, Duration maxProcessingTime, Map<ClaimKey, ClaimHandle> registry,
-                Semaphore permits) {
+    ClaimHandle(ClaimedItem item, long claimStartedAt, long claimedAt, Duration maxProcessingTime,
+                Map<ClaimKey, ClaimHandle> registry, Semaphore permits) {
         this.item = Objects.requireNonNull(item, "item");
         Durations.requirePositive("maxProcessingTime", maxProcessingTime);
+        if (claimedAt - claimStartedAt < 0) {
+            throw new IllegalArgumentException("claimedAt is before claimStartedAt");
+        }
         this.claimedAt = claimedAt;
         this.deadline = claimedAt + maxProcessingTime.toNanos();
+        this.leaseWrittenAt = claimStartedAt;
         this.registry = Objects.requireNonNull(registry, "registry");
         this.permits = Objects.requireNonNull(permits, "permits");
     }
@@ -74,6 +83,20 @@ final class ClaimHandle {
         return deadline;
     }
 
+    /**
+     * The start of the operation that last wrote this claim's lease: its claim operation, then the last renewal round
+     * that renewed it. The write itself came no earlier, so the lease lasts at least lease-duration from here (spec
+     * §5.3); {@code renewal.lag} counts from it (spec §9.6).
+     */
+    long leaseWrittenAt() {
+        return leaseWrittenAt;
+    }
+
+    /** A renewal round that started at {@code roundStart} renewed this claim's lease. */
+    void leaseRenewed(long roundStart) {
+        leaseWrittenAt = roundStart;
+    }
+
     /**
      * Adds this handle to the registry. False if another handle already holds its key, which is an invariant
      * violation: claim tokens are unique per claim.
````

`QueueRunner` times and counts every claim operation, including the failed ones, records the DB success of every claim and renewal round that returned, counts each task's outcome, passes the claim's start to the handle, and restarts the lag of each claim a round renews from that round's start:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
index ee4c92d..2d243d2 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
@@ -6,6 +6,7 @@ import org.slf4j.LoggerFactory;
 import org.springframework.context.SmartLifecycle;
 
 import java.time.Duration;
+import java.util.EnumMap;
 import java.util.HashMap;
 import java.util.List;
 import java.util.Map;
@@ -109,10 +110,18 @@ final class QueueRunner implements SmartLifecycle {
     private final ConcurrentMap<ClaimKey, ClaimHandle> registry;
     private final Semaphore permits;
     private final RenewalSchedule schedule;
+    private final DbActivity dbActivity;
 
+    // What health and the meters read (spec §9.6); WorkQueueMetrics binds them.
     private final AtomicLong invariantViolations = new AtomicLong();
     private final AtomicLong registrationsLate = new AtomicLong();
     private final AtomicLong claimsLost = new AtomicLong();
+    private final AtomicLong claims = new AtomicLong();
+    private final AtomicLong claimErrors = new AtomicLong();
+    private final OperationStats claimTimes = new OperationStats();
+    private final AtomicLong renewalErrors = new AtomicLong();
+    private final OperationStats renewalTimes = new OperationStats();
+    private final Map<Outcome, AtomicLong> outcomes = new EnumMap<>(Outcome.class);
 
     // cancelAll sets cancelOnRegister and walks the registry under this lock, and registerAndStart registers a
     // handle and reads cancelOnRegister under it, so a handle is either in the registry when a cancel pass walks it
@@ -163,6 +172,10 @@ final class QueueRunner implements SmartLifecycle {
         this.registry = Objects.requireNonNull(registry, "registry");
         this.permits = new Semaphore(settings.concurrency());
         this.schedule = new RenewalSchedule(settings.renewInterval(), settings.renewRetryDelay());
+        this.dbActivity = new DbActivity(clock);
+        for (Outcome outcome : Outcome.values()) {
+            outcomes.put(outcome, new AtomicLong());
+        }
     }
 
     // ---- Lifecycle -------------------------------------------------------------------------------------------
@@ -307,10 +320,13 @@ final class QueueRunner implements SmartLifecycle {
             if (claimingPaused()) {
                 return settings.supervisorInterval();
             }
+            long claimStartedAt = clock.getAsLong();
             List<ClaimedItem> claimed;
             try {
                 claimed = repository.claim(owner, held);
             } catch (RuntimeException e) {
+                claimTimes.record(clock.getAsLong() - claimStartedAt);
+                claimErrors.incrementAndGet();
                 // The outcome is uncertain: rows may have committed. They are never registered, so they expire
                 // unrenewed with their attempt consumed (spec §5.2).
                 Duration pause = backoff();
@@ -319,6 +335,9 @@ final class QueueRunner implements SmartLifecycle {
                 return pause;
             }
             long claimedAt = clock.getAsLong();
+            claimTimes.record(claimedAt - claimStartedAt);
+            claims.incrementAndGet();
+            dbActivity.succeeded();
             claimFailures = 0;
             if (claimed.size() > held) {
                 // Only the first held rows get a permit. The rest are CLAIMED but never registered, so, like the rows
@@ -329,7 +348,8 @@ final class QueueRunner implements SmartLifecycle {
                 claimed = claimed.subList(0, held);
             }
             for (ClaimedItem item : claimed) {
-                ClaimHandle handle = new ClaimHandle(item, claimedAt, settings.maxProcessingTime(), registry, permits);
+                ClaimHandle handle = new ClaimHandle(item, claimStartedAt, claimedAt, settings.maxProcessingTime(),
+                        registry, permits);
                 Thread thread = Objects.requireNonNull(taskThreads.newThread(handle, () -> runTask(handle)), "thread");
                 held--;   // the transfer: from here on the handle owns this permit
                 registerAndStart(handle, thread, claimedAt);
@@ -368,13 +388,15 @@ final class QueueRunner implements SmartLifecycle {
     }
 
     // Spec §5.2 step 4. Catches every Throwable: an uncaught one would reach the thread's default handler, which
-    // prints its message (spec §5.4).
+    // prints its message (spec §5.4). A task cancelled before its body ran ends CANCELLED; one that threw has no
+    // outcome.
     private void runTask(ClaimHandle handle) {
         try {
-            if (handle.markRunning()) {
-                Outcome outcome = processor.process(handle.item(), handle::isCancelled);
-                log.debug("Claim {} of owner {} ended {}", handle, owner, outcome);
-            }
+            Outcome outcome = handle.markRunning()
+                    ? processor.process(handle.item(), handle::isCancelled)
+                    : Outcome.CANCELLED;
+            outcomes.get(Objects.requireNonNull(outcome, "outcome")).incrementAndGet();
+            log.debug("Claim {} of owner {} ended {}", handle, owner, outcome);
         } catch (Throwable t) {
             log.error("Task for claim {} of owner {} failed: {}", handle, owner, Diagnostics.describe(t));
         } finally {
@@ -428,8 +450,9 @@ final class QueueRunner implements SmartLifecycle {
 
     /**
      * One renewal round (spec §5.3) over a snapshot, taken at its start, of the handles that are renewable then.
-     * Every claim the round reports lost is counted and cancelled; a claim its own task already ended is neither, and
-     * a lost claim the round did not request is an invariant violation.
+     * Every claim the round renews has its lease counted from the round's start. Every claim it reports lost is
+     * counted and cancelled; a claim its own task already ended is neither, and a lost claim the round did not
+     * request is an invariant violation.
      * Returns whether the round succeeded; a round with nothing to renew is skipped and succeeds.
      */
     boolean renewOnce() {
@@ -447,10 +470,20 @@ final class QueueRunner implements SmartLifecycle {
         try {
             result = repository.renew(owner, snapshot.keySet());
         } catch (RuntimeException e) {
+            renewalTimes.record(clock.getAsLong() - start);
+            renewalErrors.incrementAndGet();
             log.warn("Renewal of {} claims of owner {} failed: {}", snapshot.size(), owner, Diagnostics.describe(e));
             return false;
         }
         long now = clock.getAsLong();
+        renewalTimes.record(now - start);
+        dbActivity.succeeded();
+        for (ClaimKey key : result.renewed()) {
+            ClaimHandle handle = snapshot.get(key);
+            if (handle != null) {   // the repository renews only the pairs it was given
+                handle.leaseRenewed(start);
+            }
+        }
         for (ClaimKey key : result.lost()) {
             ClaimHandle handle = snapshot.get(key);
             if (handle == null) {
@@ -544,6 +577,55 @@ final class QueueRunner implements SmartLifecycle {
         return claimsLost.get();
     }
 
+    /** Claim operations that returned, with rows or without. */
+    long claims() {
+        return claims.get();
+    }
+
+    /** Claim operations that failed: their outcome is unknown. */
+    long claimErrors() {
+        return claimErrors.get();
+    }
+
+    /** The duration of every claim operation, returned or failed. */
+    OperationStats claimTimes() {
+        return claimTimes;
+    }
+
+    long renewalErrors() {
+        return renewalErrors.get();
+    }
+
+    /** The duration of every renewal round that ran; a skipped round is not one. */
+    OperationStats renewalTimes() {
+        return renewalTimes;
+    }
+
+    /** Tasks that ended with {@code outcome}. */
+    long outcomes(Outcome outcome) {
+        return outcomes.get(Objects.requireNonNull(outcome, "outcome")).get();
+    }
+
+    /**
+     * Spec §9.6 {@code renewal.lag}: the longest time since a renewal-eligible claim's lease was last written, counted
+     * from the start of the operation that wrote it; zero without renewal-eligible claims.
+     */
+    Duration renewalLag() {
+        long now = clock.getAsLong();
+        long lag = 0;
+        for (ClaimHandle handle : registry.values()) {
+            if (handle.isRenewable(now)) {
+                lag = Math.max(lag, now - handle.leaseWrittenAt());
+            }
+        }
+        return Duration.ofNanos(lag);
+    }
+
+    /** Spec §9.6 {@code db.last_success_age}. */
+    Duration dbLastSuccessAge() {
+        return dbActivity.lastSuccessAge();
+    }
+
     /** True from the start of {@link #stop()}: readiness reports DOWN. */
     boolean isStopping() {
         return stopping;
````

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test`
Expected: exit code 0, with 431 unit tests passing (`QueueRunnerLifecycleTest` 55, `ClaimHandleTest` 23).

- [ ] **Step 5: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/OperationStats.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ClaimHandleTest.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
git commit -m "feat: record lease writes, claim and renewal timings and task outcomes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The sweeper and backlog sampler loops, and dead loops

**Files:**
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java`

**Interfaces:**
- Consumes: `Sweeper`, `BacklogSampler` and `DbActivity` (Task 2); `ScriptedRepository.thenSweep`, `thenSample`, `sweepSizes()` and `samples()` (Task 2).
- Produces: `QueueRunner.Settings` gains `Duration sweepInterval, int sweepBatchSize, Duration backlogSampleInterval` at its end, from `WorkQueueProperties`. On `QueueRunner`: `int sweepOnce()`, `boolean sampleOnce()`, `BacklogSample backlog()`, `boolean hungTaskLimitReached()` (it replaces the private `claimingPaused()`) and `List<String> deadLoops()` (a subset of `poll`, `renewal`, `supervisor`, in that order). Task 5's backlog gauges read `backlog()`, and Task 6's liveness reads the last two.

- [ ] **Step 1: Write the failing tests**

The loop tests run a runner whose loop threads are recorded (`liveRunner()`). The crash and start-failure tests use sweep and sample intervals of a minute, so that a loop left uninterrupted would still be alive when they check:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
index 01ab56c..ec2e1f9 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
@@ -16,6 +16,7 @@ import org.springframework.dao.DataAccessResourceFailureException;
 import java.time.Duration;
 import java.util.ArrayList;
 import java.util.Arrays;
+import java.util.Collection;
 import java.util.LinkedHashMap;
 import java.util.List;
 import java.util.Map;
@@ -33,6 +34,7 @@ import java.util.function.LongSupplier;
 
 import static hle.org.workqueue.engine.ScriptedRepository.Operation.COMPLETE;
 import static java.time.Duration.ofMillis;
+import static java.time.Duration.ofMinutes;
 import static java.time.Duration.ofSeconds;
 import static java.util.concurrent.TimeUnit.SECONDS;
 import static org.assertj.core.api.Assertions.assertThat;
@@ -52,6 +54,8 @@ class QueueRunnerLifecycleTest {
      */
     private static final QueueRunner.Settings SETTINGS = QueueRunner.Settings.from(ItConfig.properties());
     private static final int CONCURRENCY = SETTINGS.concurrency();
+    /** The IT column with a minute between sweeps and between samples: those loops end soon only if interrupted. */
+    private static final QueueRunner.Settings MINUTE_PASSES = minutePasses();
     private static final DataAccessResourceFailureException UNREACHABLE =
             new DataAccessResourceFailureException("Db2 unreachable");
 
@@ -61,6 +65,8 @@ class QueueRunnerLifecycleTest {
     // Every handle that received a permit, registered or not, for the permit invariant.
     private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
     private final Tasks tasks = new Tasks();
+    // Every loop thread a runner built by liveRunner() started, for the tests of its loops.
+    private final List<Thread> loops = new CopyOnWriteArrayList<>();
     private final Logger runnerLog = (Logger) LoggerFactory.getLogger(QueueRunner.class);
     private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
     private QueueRunner runner = runner(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>());
@@ -616,6 +622,7 @@ class QueueRunnerLifecycleTest {
         now.addAndGet(2 * SECOND);
         runner.superviseOnce();
         assertThat(runner.hungTasks()).as("hung-task-limit is 1").isEqualTo(1);
+        assertThat(runner.hungTaskLimitReached()).isTrue();
 
         assertThat(runner.pollOnce()).as("the supervisor interval").isEqualTo(ofMillis(100));
         assertThat(repository.claimSizes()).containsExactly(4);
@@ -623,6 +630,7 @@ class QueueRunnerLifecycleTest {
 
         tasks.releaseAll();
         await().until(() -> handle(key(1, 1)).isEnded());
+        assertThat(runner.hungTaskLimitReached()).isFalse();
         runner.pollOnce();
         assertThat(repository.claimSizes()).containsExactly(4, 4);
     }
@@ -644,6 +652,32 @@ class QueueRunnerLifecycleTest {
         assertThat(tasks.highWater()).isEqualTo(CONCURRENCY);
     }
 
+    // ---- Sweeper and backlog sampler (spec §6) --------------------------------------------------------------
+
+    @Test
+    void aSweepPassSweepsBatchesOfTheSweepBatchSizeAndIsADbSuccess() {
+        now.addAndGet(5 * SECOND);
+        repository.thenSweep(100, 3);
+
+        assertThat(runner.sweepOnce()).isEqualTo(103);
+
+        assertThat(repository.sweepSizes()).containsExactly(100, 100);
+        assertThat(runner.dbLastSuccessAge()).isZero();
+    }
+
+    @Test
+    void theLatestBacklogSampleIsKeptAndIsADbSuccess() {
+        BacklogSample sample = new BacklogSample(12, 4, 1, 0, ofSeconds(30));
+        now.addAndGet(5 * SECOND);
+        assertThat(runner.backlog()).isNull();
+        repository.thenSample(sample);
+
+        assertThat(runner.sampleOnce()).isTrue();
+
+        assertThat(runner.backlog()).isEqualTo(sample);
+        assertThat(runner.dbLastSuccessAge()).isZero();
+    }
+
     // ---- What health and the meters read (spec §9.6) ---------------------------------------------------------
 
     @Test
@@ -850,7 +884,7 @@ class QueueRunnerLifecycleTest {
 
     @Test
     void crashCancelsEveryClaimAtOnceAndStopsTheLoops() throws Exception {
-        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
+        runner = liveRunner(MINUTE_PASSES);
         repository.thenClaim(item(1, 1), item(2, 1));
         runner.start();
         await().until(() -> tasks.started().size() == 2);
@@ -864,6 +898,49 @@ class QueueRunnerLifecycleTest {
         int claims = repository.claimSizes().size();
         await().during(ofMillis(300)).atMost(ofSeconds(2))
                 .until(() -> repository.claimSizes().size() == claims);
+        assertThat(loops).hasSize(5);
+        await().atMost(ofSeconds(5)).until(() -> loops.stream().noneMatch(Thread::isAlive));
+    }
+
+    @Test
+    void theSweeperAndTheBacklogSamplerRunEveryIntervalUntilStop() {
+        runner = liveRunner();
+
+        runner.start();
+
+        await().atMost(ofSeconds(5)).until(() -> repository.sweepSizes().size() >= 2 && repository.samples() >= 2);
+        runner.stop();
+        assertThat(loops).extracting(Thread::getName).containsExactly("workqueue-poll", "workqueue-renewal",
+                "workqueue-supervisor", "workqueue-sweeper", "workqueue-backlog-sampler");
+        assertThat(loops).noneMatch(Thread::isAlive);
+    }
+
+    @Test
+    void stopWaitsForAllFourLoopsAfterThePollLoopWithinOneSecondInAll() {
+        CountDownLatch release = new CountDownLatch(1);
+        CountDownLatch sweeping = new CountDownLatch(1);
+        CountDownLatch sampling = new CountDownLatch(1);
+        repository.thenSweep(() -> {
+            sweeping.countDown();
+            awaitIgnoringInterrupts(release);
+            return 0;
+        }).thenSample(() -> {
+            sampling.countDown();
+            awaitIgnoringInterrupts(release);
+            return new BacklogSample(0, 0, 0, 0, Duration.ZERO);
+        });
+        runner = liveRunner();
+        runner.start();
+        awaitIgnoringInterrupts(sweeping);
+        awaitIgnoringInterrupts(sampling);
+        long start = System.nanoTime();
+
+        runner.stop();
+
+        assertThat(Duration.ofNanos(System.nanoTime() - start))
+                .as("one shared second, not one per loop").isBetween(ofSeconds(1), ofMillis(1500));
+        release.countDown();
+        await().until(() -> loops.stream().noneMatch(Thread::isAlive));
     }
 
     @Test
@@ -904,6 +981,65 @@ class QueueRunnerLifecycleTest {
             assertThat(event.getFormattedMessage())
                     .isEqualTo("Poll loop of owner instance-a died: java.lang.StackOverflowError");
         });
+        await().until(() -> runner.deadLoops().equals(List.of("poll")));
+    }
+
+    @Test
+    void aLoopThatDiesIsReportedDeadUntilTheRunnerStops() {
+        Set<String> dying = Set.of("workqueue-poll", "workqueue-renewal", "workqueue-supervisor");
+        runner = new QueueRunner(repository, tasks, OWNER, SETTINGS, recordingThreads(), System::nanoTime,
+                new ConcurrentHashMap<>() {
+                    @Override
+                    public Collection<ClaimHandle> values() {   // each of the three loops reads the registry
+                        if (dying.contains(Thread.currentThread().getName())) {
+                            throw new StackOverflowError();
+                        }
+                        return super.values();
+                    }
+                }, recordingLoops());
+
+        runner.start();
+
+        await().until(() -> runner.deadLoops().equals(List.of("poll", "renewal", "supervisor")));
+        runner.stop();
+        assertThat(runner.deadLoops()).isEmpty();
+    }
+
+    @Test
+    void loopsThatStopEndsAreNotDead() throws Exception {
+        runner = liveRunner();
+        repository.thenClaim(item(1, 1));
+        runner.start();
+        await().until(() -> tasks.started().size() == 1);
+        FutureTask<Void> stop = new FutureTask<>(runner::stop, null);
+        Thread.ofVirtual().start(stop);
+        Thread poll = loops.getFirst();
+        await().until(() -> runner.isStopping() && !poll.isAlive());   // stop drains the task with the poll loop ended
+
+        assertThat(runner.deadLoops()).isEmpty();
+
+        tasks.releaseAll();
+        stop.get(10, SECONDS);
+    }
+
+    @Test
+    void aSweeperOrBacklogSamplerLoopThatDiesIsLoggedByClassNameOnlyButNotReportedDead() {
+        repository.thenSweep(() -> {
+            throw new StackOverflowError("row of order-7:charge");
+        }).thenSample(() -> {
+            throw new StackOverflowError("row of order-7:charge");
+        });
+        runner = liveRunner();
+
+        runner.start();
+
+        await().until(() -> loops.size() == 5 && !loops.get(3).isAlive() && !loops.get(4).isAlive());
+        assertThat(runner.deadLoops()).as("liveness watches the poll, renewal and supervisor loops").isEmpty();
+        synchronized (logged) {
+            assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactlyInAnyOrder(
+                    "Sweeper loop of owner instance-a died: java.lang.StackOverflowError",
+                    "Backlog sampler loop of owner instance-a died: java.lang.StackOverflowError");
+        }
     }
 
     @Test
@@ -915,16 +1051,13 @@ class QueueRunnerLifecycleTest {
             awaitIgnoringInterrupts(claimReturns);   // the claim commits and returns although start() interrupts it
             return List.of(item(1, 1));
         });
-        List<Thread> loops = new CopyOnWriteArrayList<>();
         runner = new QueueRunner(repository, tasks, OWNER, SETTINGS, recordingThreads(), System::nanoTime,
                 new ConcurrentHashMap<>(), (name, loop) -> {
                     if (name.equals("workqueue-renewal")) {
                         awaitIgnoringInterrupts(claiming);   // the poll loop is inside its claim
                         throw new OutOfMemoryError("unable to create thread");
                     }
-                    Thread thread = QueueRunner.VIRTUAL_LOOP_THREADS.start(name, loop);
-                    loops.add(thread);
-                    return thread;
+                    return recordingLoops().start(name, loop);
                 });
 
         assertThatThrownBy(runner::start).isInstanceOf(OutOfMemoryError.class);
@@ -939,6 +1072,30 @@ class QueueRunnerLifecycleTest {
         assertThatThrownBy(runner::start).isInstanceOf(IllegalStateException.class);
     }
 
+    @Test
+    void aLoopThreadThatFailsToStartLastEndsEveryLoopStartedBeforeIt() {
+        CountDownLatch sweeping = new CountDownLatch(1);
+        repository.thenSweep(() -> {
+            sweeping.countDown();
+            return 0;
+        });
+        runner = new QueueRunner(repository, tasks, OWNER, MINUTE_PASSES, recordingThreads(), System::nanoTime,
+                new ConcurrentHashMap<>(), (name, loop) -> {
+                    if (name.equals("workqueue-backlog-sampler")) {
+                        awaitIgnoringInterrupts(sweeping);   // the sweeper is in its first pass: only an interrupt ends it
+                        throw new OutOfMemoryError("unable to create thread");
+                    }
+                    return recordingLoops().start(name, loop);
+                });
+
+        assertThatThrownBy(runner::start).isInstanceOf(OutOfMemoryError.class);
+
+        assertThat(loops).extracting(Thread::getName)
+                .containsExactly("workqueue-poll", "workqueue-renewal", "workqueue-supervisor", "workqueue-sweeper");
+        await().atMost(ofSeconds(5)).until(() -> loops.stream().noneMatch(Thread::isAlive));
+        assertThat(runner.isRunning()).isFalse();
+    }
+
     @Test
     void aRunnerCannotBeRestarted() {
         runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
@@ -959,7 +1116,7 @@ class QueueRunnerLifecycleTest {
     void settingsComeFromTheProperties() {
         assertThat(QueueRunner.Settings.from(new WorkQueueProperties())).isEqualTo(new QueueRunner.Settings(16, 20,
                 ofSeconds(1), ofSeconds(30), ofSeconds(1), ofSeconds(15), ofSeconds(1), ofSeconds(120), ofSeconds(1),
-                ofSeconds(30), 4, ofSeconds(20), ofSeconds(5)));
+                ofSeconds(30), 4, ofSeconds(20), ofSeconds(5), ofSeconds(30), 100, ofSeconds(30)));
     }
 
     @Test
@@ -974,6 +1131,9 @@ class QueueRunnerLifecycleTest {
         invalid.put("hungGrace", properties -> properties.setHungGrace(ofSeconds(-1)));
         invalid.put("shutdownGrace", properties -> properties.setShutdownGrace(Duration.ZERO));
         invalid.put("shutdownCancelWait", properties -> properties.setShutdownCancelWait(Duration.ZERO));
+        invalid.put("sweepInterval", properties -> properties.setSweepInterval(Duration.ZERO));
+        invalid.put("sweepBatchSize", properties -> properties.setSweepBatchSize(0));
+        invalid.put("backlogSampleInterval", properties -> properties.setBacklogSampleInterval(Duration.ZERO));
 
         invalid.forEach((name, change) -> {
             WorkQueueProperties properties = ItConfig.properties();
@@ -1010,6 +1170,32 @@ class QueueRunnerLifecycleTest {
         return new QueueRunner(repository, processor, OWNER, SETTINGS, threads, clock, registry);
     }
 
+    // A runner on the real clock whose loop threads are recorded.
+    private QueueRunner liveRunner() {
+        return liveRunner(SETTINGS);
+    }
+
+    private QueueRunner liveRunner(QueueRunner.Settings settings) {
+        return new QueueRunner(repository, tasks, OWNER, settings, recordingThreads(), System::nanoTime,
+                new ConcurrentHashMap<>(), recordingLoops());
+    }
+
+    private static QueueRunner.Settings minutePasses() {
+        WorkQueueProperties properties = ItConfig.properties();
+        properties.setSweepInterval(ofMinutes(1));
+        properties.setBacklogSampleInterval(ofMinutes(1));
+        return QueueRunner.Settings.from(properties);
+    }
+
+    // Production's loop threads, recorded in the order they start.
+    private QueueRunner.LoopThreads recordingLoops() {
+        return (name, loop) -> {
+            Thread thread = QueueRunner.VIRTUAL_LOOP_THREADS.start(name, loop);
+            loops.add(thread);
+            return thread;
+        };
+    }
+
     // Production's virtual threads, recording every handle that receives a thread and with it a permit.
     private QueueRunner.TaskThreads recordingThreads() {
         return (handle, body) -> {
````

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: a compilation failure, `cannot find symbol` for `deadLoops()`, `hungTaskLimitReached()`, `backlog()`, `sweepOnce()` and `sampleOnce()`, and `constructor Settings in record hle.org.workqueue.engine.QueueRunner.Settings cannot be applied to given types`.

- [ ] **Step 3: Implement the loops**

The two loops share `passLoop`. `stop()` interrupts the four loops that outlive the drain together and joins them against one deadline, `crash()` and a failed `start()` end the new loops too, and `deadLoops()` reads each watched loop's run flag before its thread:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
index 2d243d2..d36a55b 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
@@ -6,6 +6,8 @@ import org.slf4j.LoggerFactory;
 import org.springframework.context.SmartLifecycle;
 
 import java.time.Duration;
+import java.util.ArrayList;
+import java.util.Arrays;
 import java.util.EnumMap;
 import java.util.HashMap;
 import java.util.List;
@@ -21,10 +23,11 @@ import java.util.function.LongSupplier;
 
 /**
  * Runs the queue on one instance (spec §5.2): the poll loop claims rows and starts one virtual thread per claim, the
- * renewal loop keeps the claims' leases, and the DB-free supervisor enforces deadlines and detects hung tasks. It
- * alone creates handles, starts their threads, and holds the registry and the permits; the permit invariant
- * {@code permits.available + handles not ended + held = concurrency} holds whenever the poll loop is between
- * iterations. Times are {@code System.nanoTime()} readings from the injected clock, compared overflow-safely.
+ * renewal loop keeps the claims' leases, and the DB-free supervisor enforces deadlines and detects hung tasks; two
+ * more loops run the {@link Sweeper} and the {@link BacklogSampler}. It alone creates handles, starts their threads,
+ * and holds the registry and the permits; the permit invariant {@code permits.available + handles not ended + held =
+ * concurrency} holds whenever the poll loop is between iterations. Times are {@code System.nanoTime()} readings from
+ * the injected clock, compared overflow-safely.
  */
 final class QueueRunner implements SmartLifecycle {
 
@@ -32,12 +35,14 @@ final class QueueRunner implements SmartLifecycle {
     record Settings(int concurrency, int claimBatchSize, Duration idlePollInterval, Duration pollBackoffMax,
                     Duration registrationAllowance, Duration renewInterval, Duration renewRetryDelay,
                     Duration maxProcessingTime, Duration supervisorInterval, Duration hungGrace, int hungTaskLimit,
-                    Duration shutdownGrace, Duration shutdownCancelWait) {
+                    Duration shutdownGrace, Duration shutdownCancelWait, Duration sweepInterval, int sweepBatchSize,
+                    Duration backlogSampleInterval) {
 
         Settings {
             requireAtLeastOne("concurrency", concurrency);
             requireAtLeastOne("claimBatchSize", claimBatchSize);
             requireAtLeastOne("hungTaskLimit", hungTaskLimit);
+            requireAtLeastOne("sweepBatchSize", sweepBatchSize);
             Durations.requirePositive("idlePollInterval", idlePollInterval);
             Durations.requirePositive("pollBackoffMax", pollBackoffMax);
             Durations.requirePositive("registrationAllowance", registrationAllowance);
@@ -48,6 +53,8 @@ final class QueueRunner implements SmartLifecycle {
             Durations.requirePositive("hungGrace", hungGrace);
             Durations.requirePositive("shutdownGrace", shutdownGrace);
             Durations.requirePositive("shutdownCancelWait", shutdownCancelWait);
+            Durations.requirePositive("sweepInterval", sweepInterval);
+            Durations.requirePositive("backlogSampleInterval", backlogSampleInterval);
         }
 
         static Settings from(WorkQueueProperties properties) {
@@ -56,7 +63,8 @@ final class QueueRunner implements SmartLifecycle {
                     properties.getRegistrationAllowance(), properties.getRenewInterval(),
                     properties.getRenewRetryDelay(), properties.getMaxProcessingTime(),
                     properties.getSupervisorInterval(), properties.getHungGrace(), properties.getHungTaskLimit(),
-                    properties.getShutdownGrace(), properties.getShutdownCancelWait());
+                    properties.getShutdownGrace(), properties.getShutdownCancelWait(), properties.getSweepInterval(),
+                    properties.getSweepBatchSize(), properties.getBacklogSampleInterval());
         }
 
         private static void requireAtLeastOne(String name, int value) {
@@ -83,7 +91,7 @@ final class QueueRunner implements SmartLifecycle {
             .name("workqueue-task-" + handle.key().id() + "-" + handle.key().token())
             .unstarted(body);
 
-    /** Starts the thread that runs one of the three loops. */
+    /** Starts the thread that runs one of the five loops. */
     @FunctionalInterface
     interface LoopThreads {
         Thread start(String name, Runnable loop);
@@ -92,7 +100,10 @@ final class QueueRunner implements SmartLifecycle {
     /** One named virtual thread per loop. */
     static final LoopThreads VIRTUAL_LOOP_THREADS = (name, loop) -> Thread.ofVirtual().name(name).start(loop);
 
-    /** How long {@link #stop()} waits for a loop thread after interrupting it; E2 leaves 5s for this and exit. */
+    /**
+     * How long {@link #stop()} waits, in all, for the loops that outlive the drain after interrupting them; E2 leaves
+     * 5s for this and exit.
+     */
     private static final Duration LOOP_JOIN_TIMEOUT = Duration.ofSeconds(1);
 
     /** How often {@link #stop()} checks whether the registry has emptied. */
@@ -111,6 +122,8 @@ final class QueueRunner implements SmartLifecycle {
     private final Semaphore permits;
     private final RenewalSchedule schedule;
     private final DbActivity dbActivity;
+    private final Sweeper sweeper;
+    private final BacklogSampler sampler;
 
     // What health and the meters read (spec §9.6); WorkQueueMetrics binds them.
     private final AtomicLong invariantViolations = new AtomicLong();
@@ -143,9 +156,13 @@ final class QueueRunner implements SmartLifecycle {
     private volatile boolean polling;
     private volatile boolean renewing;
     private volatile boolean supervising;
+    private volatile boolean sweeping;
+    private volatile boolean sampling;
     private Thread pollThread;
     private Thread renewalThread;
     private Thread supervisorThread;
+    private Thread sweeperThread;
+    private Thread samplerThread;
 
     QueueRunner(WorkItemRepository repository, Processor processor, String owner, Settings settings) {
         this(repository, processor, owner, settings, VIRTUAL_THREADS, System::nanoTime, new ConcurrentHashMap<>());
@@ -173,6 +190,8 @@ final class QueueRunner implements SmartLifecycle {
         this.permits = new Semaphore(settings.concurrency());
         this.schedule = new RenewalSchedule(settings.renewInterval(), settings.renewRetryDelay());
         this.dbActivity = new DbActivity(clock);
+        this.sweeper = new Sweeper(repository, owner, settings.sweepBatchSize(), dbActivity);
+        this.sampler = new BacklogSampler(repository, owner, dbActivity);
         for (Outcome outcome : Outcome.values()) {
             outcomes.put(outcome, new AtomicLong());
         }
@@ -180,7 +199,7 @@ final class QueueRunner implements SmartLifecycle {
 
     // ---- Lifecycle -------------------------------------------------------------------------------------------
 
-    /** Starts the three loops. A runner starts once: after stop() or crash() it cannot be started again. */
+    /** Starts the five loops. A runner starts once: after stop() or crash() it cannot be started again. */
     @Override
     public void start() {
         synchronized (lifecycle) {
@@ -194,22 +213,27 @@ final class QueueRunner implements SmartLifecycle {
             polling = true;
             renewing = true;
             supervising = true;
+            sweeping = true;
+            sampling = true;
             try {
                 pollThread = loopThreads.start("workqueue-poll", this::pollLoop);
                 renewalThread = loopThreads.start("workqueue-renewal", this::renewalLoop);
                 supervisorThread = loopThreads.start("workqueue-supervisor", this::supervisorLoop);
+                sweeperThread = loopThreads.start("workqueue-sweeper", this::sweeperLoop);
+                samplerThread = loopThreads.start("workqueue-backlog-sampler", this::samplerLoop);
             } catch (Throwable t) {
                 // running stays false, so stop() would do nothing: end the loops that did start here, and cancel
                 // whatever the poll loop registers from a claim that was already in flight.
                 polling = false;
                 renewing = false;
                 supervising = false;
+                sweeping = false;
+                sampling = false;
                 cancelAll(CancelReason.SHUTDOWN);
-                if (pollThread != null) {
-                    pollThread.interrupt();
-                }
-                if (renewalThread != null) {
-                    renewalThread.interrupt();
+                for (Thread loop : Arrays.asList(pollThread, renewalThread, supervisorThread, sweeperThread)) {
+                    if (loop != null) {
+                        loop.interrupt();
+                    }
                 }
                 throw t;
             }
@@ -226,8 +250,8 @@ final class QueueRunner implements SmartLifecycle {
     /**
      * The stop sequence of spec §5.2: stop claiming (a claim that already returned is still started), wait up to
      * shutdown-grace for the running tasks with renewal still running, cancel what is left, wait up to
-     * shutdown-cancel-wait, then stop renewal and the supervisor. Nothing is released in Db2: a claim still held
-     * expires with its attempt consumed.
+     * shutdown-cancel-wait, then stop renewal, the supervisor, the sweeper and the backlog sampler, waiting at most
+     * 1s for them in all. Nothing is released in Db2: a claim still held expires with its attempt consumed.
      */
     @Override
     public void stop() {
@@ -245,10 +269,14 @@ final class QueueRunner implements SmartLifecycle {
             awaitDrained(clock.getAsLong() + settings.shutdownCancelWait().toNanos());
             renewing = false;
             supervising = false;
-            renewalThread.interrupt();
-            supervisorThread.interrupt();
-            join(renewalThread, LOOP_JOIN_TIMEOUT);
-            join(supervisorThread, LOOP_JOIN_TIMEOUT);
+            sweeping = false;
+            sampling = false;
+            List<Thread> loops = List.of(renewalThread, supervisorThread, sweeperThread, samplerThread);
+            loops.forEach(Thread::interrupt);
+            long joinDeadline = clock.getAsLong() + LOOP_JOIN_TIMEOUT.toNanos();
+            for (Thread loop : loops) {
+                join(loop, remaining(joinDeadline));
+            }
             running = false;
         }
     }
@@ -263,12 +291,16 @@ final class QueueRunner implements SmartLifecycle {
         polling = false;
         renewing = false;
         supervising = false;
+        sweeping = false;
+        sampling = false;
         cancelAll(CancelReason.CRASH);
         synchronized (lifecycle) {
             if (running) {
                 pollThread.interrupt();
                 renewalThread.interrupt();
                 supervisorThread.interrupt();
+                sweeperThread.interrupt();
+                samplerThread.interrupt();
                 running = false;
             }
         }
@@ -276,9 +308,8 @@ final class QueueRunner implements SmartLifecycle {
 
     // ---- Poll loop -------------------------------------------------------------------------------------------
 
-    // Each loop survives a RuntimeException. An Error ends the loop, for slice 2.6's liveness to report as a dead
-    // loop; it is caught only to be logged by its diagnostics: the thread's default handler would print its message
-    // (spec §5.4).
+    // Each loop survives a RuntimeException. An Error ends the loop, which deadLoops() then reports to liveness; it is
+    // caught only to be logged by its diagnostics: the thread's default handler would print its message (spec §5.4).
     private void pollLoop() {
         try {
             while (polling) {
@@ -317,7 +348,7 @@ final class QueueRunner implements SmartLifecycle {
             while (held < settings.claimBatchSize() && permits.tryAcquire()) {
                 held++;
             }
-            if (claimingPaused()) {
+            if (hungTaskLimitReached()) {
                 return settings.supervisorInterval();
             }
             long claimStartedAt = clock.getAsLong();
@@ -404,10 +435,6 @@ final class QueueRunner implements SmartLifecycle {
         }
     }
 
-    private boolean claimingPaused() {
-        return hungTasks() >= settings.hungTaskLimit();
-    }
-
     // The idle interval ± 50%, so idle instances do not poll in step.
     private Duration idlePause() {
         long idle = settings.idlePollInterval().toNanos();
@@ -544,7 +571,42 @@ final class QueueRunner implements SmartLifecycle {
         return text.toString();
     }
 
-    // ---- State for health and metrics (slice 2.6) and tests --------------------------------------------------
+    // ---- Sweeper and backlog sampler -------------------------------------------------------------------------
+
+    private void sweeperLoop() {
+        passLoop("Sweeper", () -> sweeping, this::sweepOnce, settings.sweepInterval());
+    }
+
+    private void samplerLoop() {
+        passLoop("Backlog sampler", () -> sampling, this::sampleOnce, settings.backlogSampleInterval());
+    }
+
+    // A pass, then the interval, until stopped. A pass logs its own failures, so only an Error ends the loop; it is
+    // logged, and liveness does not watch these two loops (spec §9.6).
+    private void passLoop(String name, BooleanSupplier active, Runnable pass, Duration interval) {
+        try {
+            while (active.getAsBoolean()) {
+                pass.run();
+                if (!sleep(interval)) {
+                    return;
+                }
+            }
+        } catch (Throwable t) {
+            log.error("{} loop of owner {} died: {}", name, owner, Diagnostics.describe(t));
+        }
+    }
+
+    /** One sweeper pass (spec §6), which the sweeper loop repeats every sweep-interval. Returns the rows swept. */
+    int sweepOnce() {
+        return sweeper.sweepOnce();
+    }
+
+    /** One backlog sample (spec §9.6), which the sampler loop repeats every backlog-sample-interval. */
+    boolean sampleOnce() {
+        return sampler.sampleOnce();
+    }
+
+    // ---- State for health, metrics and tests -----------------------------------------------------------------
 
     int availablePermits() {
         return permits.availablePermits();
@@ -565,6 +627,37 @@ final class QueueRunner implements SmartLifecycle {
         return hung;
     }
 
+    /** Hung tasks have reached hung-task-limit: the poll loop stops claiming and liveness reports DOWN. */
+    boolean hungTaskLimitReached() {
+        return hungTasks() >= settings.hungTaskLimit();
+    }
+
+    /**
+     * The loops liveness watches (spec §9.6) that ended while the runner still wanted them: an Error ended them, or
+     * an interrupt that was not stop()'s or crash()'s. Loops that stop() or crash() ended are not dead.
+     */
+    List<String> deadLoops() {
+        if (!running) {
+            return List.of();
+        }
+        List<String> dead = new ArrayList<>(3);
+        if (polling && !pollThread.isAlive()) {
+            dead.add("poll");
+        }
+        if (renewing && !renewalThread.isAlive()) {
+            dead.add("renewal");
+        }
+        if (supervising && !supervisorThread.isAlive()) {
+            dead.add("supervisor");
+        }
+        return dead;
+    }
+
+    /** The latest backlog sample (spec §9.6 backlog gauges), or null before the first. */
+    BacklogSample backlog() {
+        return sampler.latest();
+    }
+
     long invariantViolations() {
         return invariantViolations.get();
     }
````

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test`
Expected: exit code 0, with 439 unit tests passing (`QueueRunnerLifecycleTest` 63, in about 30s).

- [ ] **Step 5: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
git commit -m "feat: run the sweeper and backlog sampler loops and report dead loops" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: WorkQueueMetrics and call durations

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueMetrics.java`, `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/Tasks.java`
- Modify: `db-work-queue/work-queue-engine/pom.xml`, `.../main/.../ItemProcessor.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueueMetricsTest.java` (create), `ItemProcessorTest.java`, `QueueRunnerLifecycleTest.java` (modify)

**Interfaces:**
- Consumes: Task 3's and Task 4's `QueueRunner` accessors, `OperationStats` (Task 3), `BacklogSample` (Task 1).
- Produces:
  - `ItemProcessor.CallStatus { OK, ERROR, TIMEOUT, INTERRUPTED }`, `OperationStats ItemProcessor.calls(CallStatus)`, and the constructor `ItemProcessor(WorkItemRepository, ExternalService, String owner, String namespace, Settings, Sleeper, LongSupplier clock)`; the two existing constructors use `System::nanoTime`.
  - `final class WorkQueueMetrics implements MeterBinder` with `WorkQueueMetrics(QueueRunner runner, ItemProcessor processor)`. Phase 3 registers it as a bean.
  - Test support: `final class Tasks implements QueueRunner.Processor`, moved out of `QueueRunnerLifecycleTest` unchanged. Task 6 uses it.

- [ ] **Step 1: Move Tasks into its own file**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/Tasks.java`:

```java
package hle.org.workqueue.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A QueueRunner processor for unit tests: tasks that run until released, then end with their scripted outcome
 * (COMPLETED by default). An interrupt ends a task INTERRUPTED at once, unless the tasks ignore interrupts.
 */
final class Tasks implements QueueRunner.Processor {

    private final Map<ClaimKey, CountDownLatch> releases = new ConcurrentHashMap<>();
    private final Map<ClaimKey, Outcome> outcomes = new ConcurrentHashMap<>();
    private final List<ClaimKey> started = new CopyOnWriteArrayList<>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger highWater = new AtomicInteger();
    private volatile boolean allReleased;
    private volatile boolean ignoreInterrupts;

    @Override
    public Outcome process(ClaimedItem item, BooleanSupplier cancelled) {
        started.add(item.key());
        highWater.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
            CountDownLatch release = latch(item.key());
            // releaseAll sets allReleased before it counts down the latches, so a task that misses the flag
            // has its latch counted down.
            while (!allReleased && release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    if (!ignoreInterrupts) {
                        return Outcome.INTERRUPTED;
                    }
                }
            }
            return outcomes.getOrDefault(item.key(), Outcome.COMPLETED);
        } finally {
            running.decrementAndGet();
        }
    }

    List<ClaimKey> started() {
        return List.copyOf(started);
    }

    int highWater() {
        return highWater.get();
    }

    void ignoreInterrupts() {
        ignoreInterrupts = true;
    }

    void endWith(ClaimKey key, Outcome outcome) {
        outcomes.put(key, outcome);
    }

    void release(ClaimKey key) {
        latch(key).countDown();
    }

    void releaseAll() {
        allReleased = true;
        releases.values().forEach(CountDownLatch::countDown);
    }

    private CountDownLatch latch(ClaimKey key) {
        return releases.computeIfAbsent(key, k -> new CountDownLatch(1));
    }
}
```

Then delete the nested class from `QueueRunnerLifecycleTest`, with the `BooleanSupplier` import only it used:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
index ec2e1f9..3c266a5 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
@@ -28,7 +28,6 @@ import java.util.concurrent.CountDownLatch;
 import java.util.concurrent.FutureTask;
 import java.util.concurrent.atomic.AtomicInteger;
 import java.util.concurrent.atomic.AtomicLong;
-import java.util.function.BooleanSupplier;
 import java.util.function.Consumer;
 import java.util.function.LongSupplier;
 
@@ -1246,71 +1245,4 @@ class QueueRunnerLifecycleTest {
     private static ClaimKey key(long id, long token) {
         return new ClaimKey(id, token);
     }
-
-    /**
-     * Tasks that run until released, then end with their scripted outcome (COMPLETED by default). An interrupt
-     * ends a task INTERRUPTED at once, unless the tasks ignore interrupts.
-     */
-    private static final class Tasks implements QueueRunner.Processor {
-
-        private final Map<ClaimKey, CountDownLatch> releases = new ConcurrentHashMap<>();
-        private final Map<ClaimKey, Outcome> outcomes = new ConcurrentHashMap<>();
-        private final List<ClaimKey> started = new CopyOnWriteArrayList<>();
-        private final AtomicInteger running = new AtomicInteger();
-        private final AtomicInteger highWater = new AtomicInteger();
-        private volatile boolean allReleased;
-        private volatile boolean ignoreInterrupts;
-
-        @Override
-        public Outcome process(ClaimedItem item, BooleanSupplier cancelled) {
-            started.add(item.key());
-            highWater.accumulateAndGet(running.incrementAndGet(), Math::max);
-            try {
-                CountDownLatch release = latch(item.key());
-                // releaseAll sets allReleased before it counts down the latches, so a task that misses the flag
-                // has its latch counted down.
-                while (!allReleased && release.getCount() > 0) {
-                    try {
-                        release.await();
-                    } catch (InterruptedException e) {
-                        if (!ignoreInterrupts) {
-                            return Outcome.INTERRUPTED;
-                        }
-                    }
-                }
-                return outcomes.getOrDefault(item.key(), Outcome.COMPLETED);
-            } finally {
-                running.decrementAndGet();
-            }
-        }
-
-        List<ClaimKey> started() {
-            return List.copyOf(started);
-        }
-
-        int highWater() {
-            return highWater.get();
-        }
-
-        void ignoreInterrupts() {
-            ignoreInterrupts = true;
-        }
-
-        void endWith(ClaimKey key, Outcome outcome) {
-            outcomes.put(key, outcome);
-        }
-
-        void release(ClaimKey key) {
-            latch(key).countDown();
-        }
-
-        void releaseAll() {
-            allReleased = true;
-            releases.values().forEach(CountDownLatch::countDown);
-        }
-
-        private CountDownLatch latch(ClaimKey key) {
-            return releases.computeIfAbsent(key, k -> new CountDownLatch(1));
-        }
-    }
 }
````

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: exit code 0; the same 63 tests pass.

- [ ] **Step 2: Add Micrometer**

Micrometer's version comes from the Spring Boot BOM:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/pom.xml b/db-work-queue/work-queue-engine/pom.xml
index 6c4e583..2abbca3 100644
--- a/db-work-queue/work-queue-engine/pom.xml
+++ b/db-work-queue/work-queue-engine/pom.xml
@@ -19,6 +19,10 @@
             <groupId>org.springframework.boot</groupId>
             <artifactId>spring-boot-starter-jdbc</artifactId>
         </dependency>
+        <dependency>
+            <groupId>io.micrometer</groupId>
+            <artifactId>micrometer-core</artifactId>
+        </dependency>
         <dependency>
             <groupId>com.ibm.db2</groupId>
             <artifactId>jcc</artifactId>
````

- [ ] **Step 3: Write the failing tests**

`ItemProcessorTest` gains a section on call durations:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java
index cdfc8c0..a3f894e 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java
@@ -4,6 +4,7 @@ import ch.qos.logback.classic.Level;
 import ch.qos.logback.classic.Logger;
 import ch.qos.logback.classic.spi.ILoggingEvent;
 import ch.qos.logback.core.read.ListAppender;
+import hle.org.workqueue.engine.ItemProcessor.CallStatus;
 import hle.org.workqueue.engine.ScriptedRepository.Write;
 import org.junit.jupiter.api.AfterEach;
 import org.junit.jupiter.api.BeforeEach;
@@ -15,8 +16,11 @@ import org.springframework.dao.DataAccessResourceFailureException;
 import java.sql.SQLTransientConnectionException;
 import java.time.Duration;
 import java.util.ArrayList;
+import java.util.EnumMap;
 import java.util.List;
+import java.util.Map;
 import java.util.concurrent.TimeoutException;
+import java.util.concurrent.atomic.AtomicLong;
 
 import static hle.org.workqueue.engine.ScriptedRepository.Operation.COMPLETE;
 import static hle.org.workqueue.engine.ScriptedRepository.Operation.RETRY_OR_FAIL;
@@ -24,6 +28,7 @@ import static java.time.Duration.ofMillis;
 import static java.time.Duration.ofSeconds;
 import static org.assertj.core.api.Assertions.assertThat;
 import static org.assertj.core.api.Assertions.assertThatThrownBy;
+import static org.assertj.core.api.Assertions.entry;
 
 class ItemProcessorTest {
 
@@ -325,6 +330,69 @@ class ItemProcessorTest {
         assertThat(repository.writes()).isEmpty();
     }
 
+    // ---- Call durations (spec §9.6 call.duration) -----------------------------------------------------------
+
+    @Test
+    void aCallIsTimedFromItsStartToItsReturn() {
+        repository.thenReturn(PersistResult.DONE);
+        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 1_000_000_000L);   // the call ends past the overflow
+        ItemProcessor processor = new ItemProcessor(repository, (key, token, payload, timeout) -> {
+            now.addAndGet(2_000_000_000L);
+            return new CallResult("receipt-7");
+        }, OWNER, NAMESPACE, SETTINGS, sleeps::add, now::get);
+
+        processor.process(ITEM, () -> false);
+
+        assertThat(processor.calls(CallStatus.OK).count()).isEqualTo(1);
+        assertThat(processor.calls(CallStatus.OK).totalNanos()).isEqualTo(2_000_000_000L);
+    }
+
+    @Test
+    void aCallIsTimedByHowItEnded() {
+        repository.thenReturn(PersistResult.DONE);
+        assertThat(timedCalls(returning("receipt-7"))).containsExactly(entry(CallStatus.OK, 1L));
+
+        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
+        assertThat(timedCalls(throwing(new IllegalStateException("downstream said no"))))
+                .containsExactly(entry(CallStatus.ERROR, 1L));
+
+        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
+        assertThat(timedCalls((key, token, payload, timeout) -> null)).containsExactly(entry(CallStatus.ERROR, 1L));
+
+        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
+        assertThat(timedCalls(throwing(new TimeoutException("3s passed"))))
+                .containsExactly(entry(CallStatus.TIMEOUT, 1L));
+    }
+
+    @Test
+    void anInterruptedCallIsTimedInterruptedHoweverItEnded() {
+        assertThat(timedCalls(throwing(new InterruptedException())))
+                .containsExactly(entry(CallStatus.INTERRUPTED, 1L));
+        Thread.interrupted();
+
+        assertThat(timedCalls((key, token, payload, timeout) -> {
+            Thread.currentThread().interrupt();
+            return new CallResult("receipt-7");
+        })).containsExactly(entry(CallStatus.INTERRUPTED, 1L));
+        Thread.interrupted();
+
+        assertThat(timedCalls((key, token, payload, timeout) -> {
+            Thread.currentThread().interrupt();
+            throw new TimeoutException("request aborted");
+        })).containsExactly(entry(CallStatus.INTERRUPTED, 1L));
+    }
+
+    @Test
+    void noCallIsNotTimed() {
+        ItemProcessor processor = processor(returning("receipt-7"));
+        repository.thenReturn(PersistResult.RETRY_SCHEDULED);
+
+        processor.process(ITEM, () -> true);                                          // cancelled
+        processor.process(new ClaimedItem(8, "has space", "payload-8", 1), () -> false);   // invalid OPERATION_ID
+
+        assertThat(timed(processor)).isEmpty();
+    }
+
     @Test
     void rejectsAnInvalidOwnerOrNamespace() {
         assertThatThrownBy(() -> new ItemProcessor(repository, returning("r"), " ", NAMESPACE, SETTINGS))
@@ -355,6 +423,24 @@ class ItemProcessorTest {
         return processor(service).process(ITEM, () -> false);
     }
 
+    // Processes ITEM once with a new processor and returns its call counts by status, leaving out the zeros.
+    private Map<CallStatus, Long> timedCalls(ExternalService service) {
+        ItemProcessor processor = processor(service);
+        processor.process(ITEM, () -> false);
+        return timed(processor);
+    }
+
+    private static Map<CallStatus, Long> timed(ItemProcessor processor) {
+        Map<CallStatus, Long> timed = new EnumMap<>(CallStatus.class);
+        for (CallStatus status : CallStatus.values()) {
+            long count = processor.calls(status).count();
+            if (count > 0) {
+                timed.put(status, count);
+            }
+        }
+        return timed;
+    }
+
     private ItemProcessor processor(ExternalService service) {
         return new ItemProcessor(repository, recording(service), OWNER, NAMESPACE, SETTINGS, sleeps::add);
     }
````

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueueMetricsTest.java`:

```java
package hle.org.workqueue.engine;

import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Spec §9.6 metrics: every meter exists under its name and reads the engine's state when scraped. */
@Timeout(30)
class WorkQueueMetricsTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String OWNER = "instance-a";
    private static final DataAccessResourceFailureException UNREACHABLE =
            new DataAccessResourceFailureException("Db2 unreachable");

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final Tasks tasks = new Tasks();
    private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
    private final AtomicBoolean registerLate = new AtomicBoolean();
    private final QueueRunner runner = new QueueRunner(repository, tasks, OWNER,
            QueueRunner.Settings.from(ItConfig.properties()), (handle, body) -> {
                handles.add(handle);
                if (registerLate.get()) {
                    now.addAndGet(SECOND);   // past registration-allowance (200ms)
                }
                return QueueRunner.VIRTUAL_THREADS.newThread(handle, body);
            }, now::get, new ConcurrentHashMap<>());
    private final ItemProcessor processor = new ItemProcessor(repository, (key, token, payload, timeout) -> {
        now.addAndGet(2 * SECOND);   // every call takes 2s
        return new CallResult("receipt");
    }, OWNER, "it", ItemProcessor.Settings.from(ItConfig.properties()), duration -> { }, now::get);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void bind() {
        new WorkQueueMetrics(runner, processor).bindTo(registry);
    }

    @AfterEach
    void endEveryTask() {
        runner.crash();
        tasks.releaseAll();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
    }

    @Test
    void everyMeterOfTheSpecIsRegistered() {
        Set<String> names = registry.getMeters().stream().map(meter -> meter.getId().getName()).collect(toSet());

        assertThat(names).containsExactlyInAnyOrder("workqueue.claims", "workqueue.claim.duration",
                "workqueue.claim.errors", "workqueue.outcomes", "workqueue.call.duration",
                "workqueue.renewal.duration", "workqueue.renewal.errors", "workqueue.renewal.lag",
                "workqueue.claims.lost", "workqueue.db.last_success_age", "workqueue.inflight",
                "workqueue.permits.available", "workqueue.tasks.hung", "workqueue.registration.late",
                "workqueue.invariant.violations", "workqueue.backlog", "workqueue.backlog.oldest_pending_age",
                "workqueue.claims.expired");
        assertThat(tagValues("workqueue.outcomes", "outcome")).containsExactlyInAnyOrder("completed",
                "retry_scheduled", "failed", "fenced", "abandoned", "interrupted", "cancelled");
        assertThat(tagValues("workqueue.call.duration", "result"))
                .containsExactlyInAnyOrder("ok", "error", "timeout", "interrupted");
        assertThat(tagValues("workqueue.backlog", "status")).containsExactlyInAnyOrder("pending", "claimed", "failed");
    }

    @Test
    void claimMetersCountAndTimeEveryClaimOperation() throws Exception {
        repository.thenClaim(() -> {
            now.addAndGet(2 * SECOND);
            return List.of();
        }).thenClaim(() -> {
            now.addAndGet(3 * SECOND);
            throw UNREACHABLE;
        });

        runner.pollOnce();
        runner.pollOnce();
        runner.pollOnce();   // unscripted: empty at once

        FunctionTimer claims = registry.get("workqueue.claim.duration").functionTimer();
        assertThat(claims.count()).isEqualTo(3);
        assertThat(claims.totalTime(SECONDS)).isEqualTo(5);
        assertThat(counter("workqueue.claims")).isEqualTo(2);
        assertThat(counter("workqueue.claim.errors")).isEqualTo(1);
    }

    @Test
    void renewalMetersTimeTheRoundsThatRanAndTheLagGrows() throws Exception {
        claimAndStart(item(1));
        now.addAndGet(4 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(SECOND);
            throw UNREACHABLE;
        });

        runner.renewOnce();

        FunctionTimer rounds = registry.get("workqueue.renewal.duration").functionTimer();
        assertThat(rounds.count()).isEqualTo(1);
        assertThat(rounds.totalTime(SECONDS)).isEqualTo(1);
        assertThat(counter("workqueue.renewal.errors")).isEqualTo(1);
        assertThat(registry.get("workqueue.renewal.lag").timeGauge().value(SECONDS)).isEqualTo(5);
    }

    @Test
    void outcomesAreCountedByOutcome() throws Exception {
        tasks.endWith(new ClaimKey(2, 1), Outcome.FAILED);
        claimAndStart(item(1), item(2));

        tasks.releaseAll();

        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
        assertThat(counter("workqueue.outcomes", "outcome", "completed")).isEqualTo(1);
        assertThat(counter("workqueue.outcomes", "outcome", "failed")).isEqualTo(1);
        assertThat(counter("workqueue.outcomes", "outcome", "abandoned")).isZero();
    }

    @Test
    void callsAreTimedByHowTheyEnded() {
        repository.thenReturn(PersistResult.DONE);

        processor.process(item(1), () -> false);

        FunctionTimer ok = registry.get("workqueue.call.duration").tag("result", "ok").functionTimer();
        assertThat(ok.count()).isEqualTo(1);
        assertThat(ok.totalTime(SECONDS)).isEqualTo(2);
        assertThat(registry.get("workqueue.call.duration").tag("result", "timeout").functionTimer().count()).isZero();
    }

    @Test
    void capacityGaugesReadTheRunner() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1), item(2), item(3));
        assertThat(gauge("workqueue.inflight")).isEqualTo(3);
        assertThat(gauge("workqueue.permits.available")).isEqualTo(1);
        assertThat(gauge("workqueue.tasks.hung")).isZero();

        now.addAndGet(25 * SECOND);
        runner.superviseOnce();   // all three cancelled at their deadline
        now.addAndGet(2 * SECOND);
        runner.superviseOnce();   // all three hung

        assertThat(gauge("workqueue.tasks.hung")).isEqualTo(3);
    }

    @Test
    void countersReadTheRunner() throws Exception {
        claimAndStart(item(1), item(2));
        repository.thenClaim(item(1));   // the same claim again while it runs: a key collision
        runner.pollOnce();
        repository.thenRenewLosing(new ClaimKey(1, 1), new ClaimKey(2, 1));
        runner.renewOnce();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));   // both interrupted
        registerLate.set(true);
        claimAndStart(item(3), item(4), item(5));

        assertThat(counter("workqueue.invariant.violations")).isEqualTo(1);
        assertThat(counter("workqueue.claims.lost")).isEqualTo(2);
        assertThat(counter("workqueue.registration.late")).isEqualTo(3);
    }

    @Test
    void theDbAgeIsTheTimeSinceTheLastDbSuccess() throws Exception {
        now.addAndGet(7 * SECOND);
        assertThat(registry.get("workqueue.db.last_success_age").timeGauge().value(SECONDS)).isEqualTo(7);

        runner.pollOnce();

        assertThat(registry.get("workqueue.db.last_success_age").timeGauge().value(SECONDS)).isZero();
    }

    @Test
    void backlogGaugesHaveNoValueBeforeTheFirstSampleThenReadTheLatest() {
        assertThat(gauge("workqueue.backlog", "status", "pending")).isNaN();
        assertThat(gauge("workqueue.claims.expired")).isNaN();
        assertThat(registry.get("workqueue.backlog.oldest_pending_age").timeGauge().value(SECONDS)).isNaN();

        repository.thenSample(new BacklogSample(12, 4, 1, 2, ofSeconds(30)));
        runner.sampleOnce();

        assertThat(gauge("workqueue.backlog", "status", "pending")).isEqualTo(12);
        assertThat(gauge("workqueue.backlog", "status", "claimed")).isEqualTo(4);
        assertThat(gauge("workqueue.backlog", "status", "failed")).isEqualTo(1);
        assertThat(gauge("workqueue.claims.expired")).isEqualTo(2);
        assertThat(registry.get("workqueue.backlog.oldest_pending_age").timeGauge().value(SECONDS)).isEqualTo(30);
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        runner.pollOnce();
        List<ClaimKey> keys = Arrays.stream(items).map(ClaimedItem::key).toList();
        await().until(() -> tasks.started().containsAll(keys));
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).functionCounter().count();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private Set<String> tagValues(String name, String tag) {
        return registry.find(name).meters().stream().map(Meter::getId).map(id -> id.getTag(tag)).collect(toSet());
    }

    private static ClaimedItem item(long id) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, 1);
    }
}
```

The values in each test differ from meter to meter (for instance 1 invariant violation, 2 lost claims and 3 late registrations), so a meter bound to the wrong accessor fails.

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest='WorkQueueMetricsTest,ItemProcessorTest'`
Expected: a compilation failure: `cannot find symbol` for `CallStatus`, and `no suitable constructor found for ItemProcessor(...)` with the clock argument.

- [ ] **Step 5: Time the calls and bind the meters**

`ItemProcessor` times each call from its start to its return, by how it ended:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java
index 43b5526..8567b19 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java
@@ -4,8 +4,12 @@ import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 
 import java.time.Duration;
+import java.util.EnumMap;
+import java.util.Map;
 import java.util.Objects;
+import java.util.concurrent.TimeoutException;
 import java.util.function.BooleanSupplier;
+import java.util.function.LongSupplier;
 import java.util.function.Supplier;
 
 /**
@@ -45,6 +49,18 @@ final class ItemProcessor {
         void sleep(Duration duration) throws InterruptedException;
     }
 
+    /** How an external call ended, for {@code call.duration{result}} (spec §9.6). */
+    enum CallStatus {
+        /** It returned a result. */
+        OK,
+        /** It threw, or returned no result. */
+        ERROR,
+        /** It threw a {@link TimeoutException}: external-call-timeout passed. */
+        TIMEOUT,
+        /** It threw InterruptedException, or the interrupt status was set when it returned or threw. */
+        INTERRUPTED
+    }
+
     static final String NO_RESULT_ERROR = "the external service returned no result";
     static final String INVALID_OPERATION_ID_ERROR = "OPERATION_ID is not a valid operation identity; not called";
 
@@ -56,6 +72,8 @@ final class ItemProcessor {
     private final String namespace;
     private final Settings settings;
     private final Sleeper sleeper;
+    private final LongSupplier clock;
+    private final Map<CallStatus, OperationStats> calls = new EnumMap<>(CallStatus.class);
 
     ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace,
                   Settings settings) {
@@ -64,6 +82,12 @@ final class ItemProcessor {
 
     ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace,
                   Settings settings, Sleeper sleeper) {
+        this(repository, service, owner, namespace, settings, sleeper, System::nanoTime);
+    }
+
+    /** For tests: the clock that times the calls is injectable. */
+    ItemProcessor(WorkItemRepository repository, ExternalService service, String owner, String namespace,
+                  Settings settings, Sleeper sleeper, LongSupplier clock) {
         WorkItemRepository.requireOwner(owner);
         IdempotencyKey.requireNamespace(namespace);
         this.repository = Objects.requireNonNull(repository, "repository");
@@ -72,6 +96,10 @@ final class ItemProcessor {
         this.namespace = namespace;
         this.settings = Objects.requireNonNull(settings, "settings");
         this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
+        this.clock = Objects.requireNonNull(clock, "clock");
+        for (CallStatus status : CallStatus.values()) {
+            calls.put(status, new OperationStats());
+        }
     }
 
     /** Processes {@code item}; {@code cancelled} reports whether its handle was cancelled. */
@@ -87,23 +115,43 @@ final class ItemProcessor {
             return failed(item, INVALID_OPERATION_ID_ERROR);
         }
         CallResult result;
+        long callStart = clock.getAsLong();
         try {
             result = service.call(key, item.claimToken(), item.payload(), settings.externalCallTimeout());
         } catch (InterruptedException e) {
             Thread.currentThread().interrupt();
-            return Outcome.INTERRUPTED;
+            return interrupted(callStart);
         } catch (Exception e) {
-            return Thread.currentThread().isInterrupted() ? Outcome.INTERRUPTED : failed(item, describe(e));
+            if (Thread.currentThread().isInterrupted()) {
+                return interrupted(callStart);
+            }
+            timeCall(e instanceof TimeoutException ? CallStatus.TIMEOUT : CallStatus.ERROR, callStart);
+            return failed(item, describe(e));
         }
         if (Thread.currentThread().isInterrupted()) {
-            return Outcome.INTERRUPTED;
+            return interrupted(callStart);
         }
+        timeCall(result == null ? CallStatus.ERROR : CallStatus.OK, callStart);
         if (result == null) {
             return failed(item, NO_RESULT_ERROR);
         }
         return persist(item, () -> repository.complete(owner, item.key(), result.value()));
     }
 
+    /** The calls that ended with {@code status}, and how long they took. */
+    OperationStats calls(CallStatus status) {
+        return calls.get(Objects.requireNonNull(status, "status"));
+    }
+
+    private Outcome interrupted(long callStart) {
+        timeCall(CallStatus.INTERRUPTED, callStart);
+        return Outcome.INTERRUPTED;
+    }
+
+    private void timeCall(CallStatus status, long callStart) {
+        calls.get(status).record(clock.getAsLong() - callStart);
+    }
+
     private Outcome failed(ClaimedItem item, String error) {
         return persist(item, () -> repository.retryOrFail(owner, item.key(), error));
     }
````

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueMetrics.java`:

```java
package hle.org.workqueue.engine;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * The engine's Micrometer meters (spec §9.6), all named {@code workqueue.*}. Each one reads the runner's or the
 * processor's state when the registry is scraped, so no meter is on a task's path. The timers are FunctionTimers:
 * they report a count and a total time, so a rate and a mean, but no maximum or percentiles. The backlog gauges
 * report NaN until the first sample. Phase 3's auto-configuration registers this as a bean, which Spring Boot binds
 * to the application's registry.
 */
final class WorkQueueMetrics implements MeterBinder {

    private final QueueRunner runner;
    private final ItemProcessor processor;

    WorkQueueMetrics(QueueRunner runner, ItemProcessor processor) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "workqueue.claims", "Claim operations that returned, an empty claim included",
                QueueRunner::claims);
        timer(registry, "workqueue.claim.duration", "Claim operations, returned or failed", Tags.empty(),
                runner.claimTimes());
        counter(registry, "workqueue.claim.errors", "Claim operations that failed", QueueRunner::claimErrors);
        for (Outcome outcome : Outcome.values()) {
            FunctionCounter.builder("workqueue.outcomes", runner, r -> r.outcomes(outcome))
                    .description("Task ends, by outcome")
                    .tag("outcome", tagValue(outcome))
                    .register(registry);
        }
        for (ItemProcessor.CallStatus status : ItemProcessor.CallStatus.values()) {
            timer(registry, "workqueue.call.duration", "External calls, by how they ended",
                    Tags.of("result", tagValue(status)), processor.calls(status));
        }
        timer(registry, "workqueue.renewal.duration", "Renewal rounds that ran", Tags.empty(), runner.renewalTimes());
        counter(registry, "workqueue.renewal.errors", "Renewal rounds that failed", QueueRunner::renewalErrors);
        timeGauge(registry, "workqueue.renewal.lag", "Longest time since a renewal-eligible claim's lease was"
                + " written; 0 without one", r -> r.renewalLag().toNanos());
        counter(registry, "workqueue.claims.lost", "Claims renewal reported lost", QueueRunner::claimsLost);
        timeGauge(registry, "workqueue.db.last_success_age", "Time since an engine DB operation last succeeded",
                r -> r.dbLastSuccessAge().toNanos());
        gauge(registry, "workqueue.inflight", "Registered claims: running, or cancelled and not yet ended",
                QueueRunner::inflight);
        gauge(registry, "workqueue.permits.available", "Free task permits", QueueRunner::availablePermits);
        gauge(registry, "workqueue.tasks.hung", "Cancelled tasks still running hung-grace after the cancel",
                QueueRunner::hungTasks);
        counter(registry, "workqueue.registration.late", "Claims registered later than registration-allowance",
                QueueRunner::registrationsLate);
        counter(registry, "workqueue.invariant.violations", "Engine invariant breaches",
                QueueRunner::invariantViolations);
        backlog(registry, "pending", BacklogSample::pending);
        backlog(registry, "claimed", BacklogSample::claimed);
        backlog(registry, "failed", BacklogSample::failed);
        timeGauge(registry, "workqueue.backlog.oldest_pending_age", "How long the oldest claimable PENDING row has"
                + " waited (sampled)", r -> sampled(r, sample -> sample.oldestPendingAge().toNanos()));
        gauge(registry, "workqueue.claims.expired", "CLAIMED rows expired for more than one lease (sampled)",
                r -> sampled(r, BacklogSample::expiredClaims));
    }

    private void counter(MeterRegistry registry, String name, String description,
                         ToDoubleFunction<QueueRunner> count) {
        FunctionCounter.builder(name, runner, count).description(description).register(registry);
    }

    private static void timer(MeterRegistry registry, String name, String description, Tags tags,
                              OperationStats stats) {
        FunctionTimer.builder(name, stats, OperationStats::count, OperationStats::totalNanos, TimeUnit.NANOSECONDS)
                .description(description)
                .tags(tags)
                .register(registry);
    }

    private void gauge(MeterRegistry registry, String name, String description, ToDoubleFunction<QueueRunner> value) {
        Gauge.builder(name, runner, value).description(description).register(registry);
    }

    private void timeGauge(MeterRegistry registry, String name, String description,
                           ToDoubleFunction<QueueRunner> nanos) {
        TimeGauge.builder(name, runner, TimeUnit.NANOSECONDS, nanos).description(description).register(registry);
    }

    private void backlog(MeterRegistry registry, String status, ToDoubleFunction<BacklogSample> count) {
        Gauge.builder("workqueue.backlog", runner, r -> sampled(r, count))
                .description("Rows by unfinished status (sampled)")
                .tag("status", status)
                .register(registry);
    }

    private static double sampled(QueueRunner runner, ToDoubleFunction<BacklogSample> value) {
        BacklogSample sample = runner.backlog();
        return sample == null ? Double.NaN : value.applyAsDouble(sample);
    }

    private static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test`
Expected: exit code 0, with 452 unit tests passing (`ItemProcessorTest` 29, `WorkQueueMetricsTest` 9).

- [ ] **Step 7: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/pom.xml \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueMetrics.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/Tasks.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ItemProcessorTest.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueueMetricsTest.java
git commit -m "feat: bind the engine's Micrometer meters" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Liveness and readiness

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueHealth.java`
- Modify: `db-work-queue/work-queue-engine/pom.xml`, `db-work-queue/README.md`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueueHealthTest.java` (create)

**Interfaces:**
- Consumes: `QueueRunner.isStopping()`, `renewalLag()`, `dbLastSuccessAge()` (Task 3), `hungTasks()`, `hungTaskLimitReached()`, `deadLoops()` (Task 4), `invariantViolations()`, `sweepOnce()` and `sampleOnce()`; `Tasks` (Task 5).
- Produces: `final class WorkQueueHealth` with `record Settings(Duration lease, Duration dbStalenessLimit)` (`Settings.from(WorkQueueProperties)`), `WorkQueueHealth(QueueRunner, Settings)`, `Health liveness()` and `Health readiness()` (`org.springframework.boot.health.contributor.Health`). Phase 3 registers the two as health indicators, for example `HealthIndicator liveness = health::liveness`.

- [ ] **Step 1: Add Spring Boot's health API**

In Spring Boot 4 `Health`, `Status` and `HealthIndicator` live in `org.springframework.boot.health.contributor`, in `spring-boot-health`:

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/work-queue-engine/pom.xml b/db-work-queue/work-queue-engine/pom.xml
index 2abbca3..d78d5fa 100644
--- a/db-work-queue/work-queue-engine/pom.xml
+++ b/db-work-queue/work-queue-engine/pom.xml
@@ -23,6 +23,10 @@
             <groupId>io.micrometer</groupId>
             <artifactId>micrometer-core</artifactId>
         </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-health</artifactId>
+        </dependency>
         <dependency>
             <groupId>com.ibm.db2</groupId>
             <artifactId>jcc</artifactId>
````

- [ ] **Step 2: Write the failing test**

It runs on a fake clock with the production defaults, as spec §11.1 asks:

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueueHealthTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Spec §11.1 {@code WorkQueueHealthTest}, on a fake clock and the production defaults: lease 100s, renew-interval 15s,
 * renew-retry-delay 1s, registration-allowance 1s, W 18s, idle-poll-interval 1s, db-staleness-limit 90s,
 * max-processing-time 120s, hung-grace 30s, hung-task-limit 4.
 */
@Timeout(30)
class WorkQueueHealthTest {

    private static final long SECOND = 1_000_000_000L;
    private static final WorkQueueProperties DEFAULTS = new WorkQueueProperties();
    private static final DataAccessResourceFailureException UNREACHABLE =
            new DataAccessResourceFailureException("Db2 unreachable");

    private final ScriptedRepository repository = new ScriptedRepository();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    private final Tasks tasks = new Tasks();
    private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
    private final QueueRunner runner = new QueueRunner(repository, tasks, "instance-a",
            QueueRunner.Settings.from(DEFAULTS), (handle, body) -> {
                handles.add(handle);
                return QueueRunner.VIRTUAL_THREADS.newThread(handle, body);
            }, now::get, new ConcurrentHashMap<>());
    private final WorkQueueHealth health = new WorkQueueHealth(runner, WorkQueueHealth.Settings.from(DEFAULTS));

    @AfterEach
    void endEveryTask() {
        runner.crash();
        tasks.releaseAll();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
    }

    // ---- Readiness ------------------------------------------------------------------------------------------

    @Test
    void anIdleInstanceWithNoClaimsStaysReadyForTenLeases() throws Exception {
        long end = now.get() + 10 * 100 * SECOND;
        while (end - now.get() > 0) {
            Duration pause = runner.pollOnce();   // an empty claim
            runner.renewOnce();                   // skipped: nothing to renew
            now.addAndGet(pause.toNanos());

            assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
            assertThat(runner.renewalLag()).isZero();
        }
    }

    @Test
    void oneFailedRenewalRoundKeepsReadinessUpEvenInTheWorstCaseOfB2() throws Exception {
        // Spec §5.3: the claim operation takes W, registration G, the first round that includes the claim starts
        // max(I, W) later and fails after W, and its retry starts d later and writes after W: 74s after the claim
        // operation started, under the 100s lease.
        repository.thenClaim(() -> {
            now.addAndGet(18 * SECOND);
            return List.of(item(1));
        });
        runner.pollOnce();
        await().until(() -> tasks.started().size() == 1);
        now.addAndGet(SECOND + 18 * SECOND);
        repository.thenRenew(requested -> {
            now.addAndGet(18 * SECOND);
            throw UNREACHABLE;
        });
        assertThat(runner.renewOnce()).isFalse();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
        now.addAndGet(SECOND);
        AtomicReference<Health> beforeTheRetryWrites = new AtomicReference<>();
        repository.thenRenew(requested -> {
            now.addAndGet(18 * SECOND);
            beforeTheRetryWrites.set(health.readiness());
            return new RenewalResult(requested, Set.of(), Set.of());
        });

        assertThat(runner.renewOnce()).isTrue();

        assertThat(beforeTheRetryWrites.get().getStatus()).isEqualTo(Status.UP);
        assertThat(beforeTheRetryWrites.get().getDetails()).containsEntry("renewalLag", "74s");
        assertThat(health.readiness().getDetails()).containsEntry("renewalLag", "18s");
    }

    @Test
    void aClaimUnrenewedForMoreThanALeaseTurnsReadinessDownUntilARoundRenewsIt() throws Exception {
        claimAndStart(item(1));
        now.addAndGet(50 * SECOND);
        runner.pollOnce();                            // claims keep Db2 fresh while renewal fails
        repository.thenRenewThrow(UNREACHABLE);
        runner.renewOnce();
        now.addAndGet(50 * SECOND);
        runner.pollOnce();
        assertThat(health.readiness().getStatus()).as("exactly one lease").isEqualTo(Status.UP);

        now.addAndGet(1);

        Health unready = health.readiness();
        assertThat(unready.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unready.getDetails()).containsEntry("renewalLag", "100s").containsEntry("dbLastSuccessAge", "0s");
        runner.renewOnce();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aStaleDbTurnsReadinessDownWithNoClaimsUntilAClaimReturns() throws Exception {
        repository.thenClaimThrow(UNREACHABLE).thenClaimThrow(UNREACHABLE);
        now.addAndGet(45 * SECOND);
        runner.pollOnce();
        now.addAndGet(45 * SECOND);
        runner.pollOnce();
        assertThat(health.readiness().getStatus()).as("exactly db-staleness-limit").isEqualTo(Status.UP);

        now.addAndGet(1);

        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        runner.pollOnce();                            // an empty claim
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aSweepOrABacklogSampleAlsoKeepsTheDbFresh() {
        now.addAndGet(91 * SECOND);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        runner.sweepOnce();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);

        now.addAndGet(91 * SECOND);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        runner.sampleOnce();
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void readinessIsDownFromTheStartOfStop() throws Exception {
        repository.thenClaim(item(1));
        runner.start();
        await().until(() -> tasks.started().size() == 1);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.UP);
        FutureTask<Void> stop = new FutureTask<>(runner::stop, null);
        Thread.ofVirtual().start(stop);
        await().until(runner::isStopping);

        Health draining = health.readiness();

        assertThat(draining.getStatus()).isEqualTo(Status.DOWN);
        assertThat(draining.getDetails()).containsEntry("stopping", true);
        tasks.releaseAll();
        stop.get(10, SECONDS);
        assertThat(health.readiness().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.liveness().getStatus()).as("the loops stop ended are not dead").isEqualTo(Status.UP);
    }

    // ---- Liveness -------------------------------------------------------------------------------------------

    @Test
    void aHealthyInstanceIsLive() {
        Health live = health.liveness();

        assertThat(live.getStatus()).isEqualTo(Status.UP);
        assertThat(live.getDetails()).containsEntry("hungTasks", 0).containsEntry("invariantViolations", 0L)
                .containsEntry("deadLoops", List.of());
    }

    @Test
    void anInvariantViolationTurnsLivenessDownForGood() throws Exception {
        claimAndStart(item(1));
        repository.thenClaim(item(1));   // the same claim again while it runs: a key collision

        runner.pollOnce();

        assertThat(health.liveness().getStatus()).isEqualTo(Status.DOWN);
        tasks.releaseAll();
        await().until(() -> handles.stream().allMatch(ClaimHandle::isEnded));
        assertThat(health.liveness().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.liveness().getDetails()).containsEntry("invariantViolations", 1L);
    }

    @Test
    void reachingTheHungTaskLimitTurnsLivenessDownUntilAHungTaskEnds() throws Exception {
        tasks.ignoreInterrupts();
        claimAndStart(item(1), item(2), item(3), item(4));
        now.addAndGet(120 * SECOND);
        runner.superviseOnce();          // all four cancelled at their deadline
        now.addAndGet(30 * SECOND);

        runner.superviseOnce();          // all four hung: the limit

        assertThat(health.liveness().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.liveness().getDetails()).containsEntry("hungTasks", 4);
        tasks.release(new ClaimKey(1, 1));
        await().until(() -> runner.hungTasks() == 3);
        assertThat(health.liveness().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aDeadLoopTurnsLivenessDown() {
        repository.thenClaim(() -> {
            throw new StackOverflowError();
        });

        runner.start();

        await().until(() -> health.liveness().getStatus().equals(Status.DOWN));
        assertThat(health.liveness().getDetails()).containsEntry("deadLoops", List.of("poll"));
    }

    // ---- Settings -------------------------------------------------------------------------------------------

    @Test
    void settingsComeFromTheProperties() {
        assertThat(WorkQueueHealth.Settings.from(DEFAULTS))
                .isEqualTo(new WorkQueueHealth.Settings(ofSeconds(100), ofSeconds(90)));
    }

    @Test
    void settingsRejectANonPositiveDuration() {
        assertThatThrownBy(() -> new WorkQueueHealth.Settings(Duration.ZERO, ofSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        assertThatThrownBy(() -> new WorkQueueHealth.Settings(ofSeconds(100), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dbStalenessLimit");
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        runner.pollOnce();
        List<ClaimKey> keys = Arrays.stream(items).map(ClaimedItem::key).toList();
        await().until(() -> tasks.started().containsAll(keys));
    }

    private static ClaimedItem item(long id) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, 1);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=WorkQueueHealthTest`
Expected: a compilation failure, `cannot find symbol` for `class WorkQueueHealth`.

- [ ] **Step 4: Implement liveness and readiness**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueHealth.java`:

```java
package hle.org.workqueue.engine;

import org.springframework.boot.health.contributor.Health;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Liveness and readiness of one instance (spec §9.6), read from its runner whenever a probe asks. Phase 3's
 * auto-configuration registers {@link #liveness()} and {@link #readiness()} as health indicators in the probe groups,
 * and adds SchemaCheck to readiness. An idle, healthy instance stays ready indefinitely.
 */
final class WorkQueueHealth {

    /**
     * @param lease            readiness turns DOWN once renewal.lag passes it: the instance may be losing claims
     * @param dbStalenessLimit readiness turns DOWN once db.last_success_age passes it
     */
    record Settings(Duration lease, Duration dbStalenessLimit) {

        Settings {
            Durations.requirePositive("lease", lease);
            Durations.requirePositive("dbStalenessLimit", dbStalenessLimit);
        }

        static Settings from(WorkQueueProperties properties) {
            return new Settings(properties.getLeaseDuration(), properties.getDbStalenessLimit());
        }
    }

    private final QueueRunner runner;
    private final Settings settings;

    WorkQueueHealth(QueueRunner runner, Settings settings) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * DOWN when hung tasks reach hung-task-limit, after any invariant violation, or when the poll, renewal or
     * supervisor loop has died: the orchestrator's restart is the remedy for each.
     */
    Health liveness() {
        int hungTasks = runner.hungTasks();
        long invariantViolations = runner.invariantViolations();
        List<String> deadLoops = runner.deadLoops();
        boolean live = !runner.hungTaskLimitReached() && invariantViolations == 0 && deadLoops.isEmpty();
        return (live ? Health.up() : Health.down())
                .withDetail("hungTasks", hungTasks)
                .withDetail("invariantViolations", invariantViolations)
                .withDetail("deadLoops", deadLoops)
                .build();
    }

    /**
     * DOWN from the start of stop, when renewal.lag passes the lease (only while claims are held; the one failed
     * round B2 tolerates stays below it), and when db.last_success_age passes db-staleness-limit.
     */
    Health readiness() {
        boolean stopping = runner.isStopping();
        Duration renewalLag = runner.renewalLag();
        Duration dbLastSuccessAge = runner.dbLastSuccessAge();
        boolean ready = !stopping && renewalLag.compareTo(settings.lease()) <= 0
                && dbLastSuccessAge.compareTo(settings.dbStalenessLimit()) <= 0;
        return (ready ? Health.up() : Health.down())
                .withDetail("stopping", stopping)
                .withDetail("renewalLag", Durations.seconds(renewalLag))
                .withDetail("dbLastSuccessAge", Durations.seconds(dbLastSuccessAge))
                .build();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test`
Expected: exit code 0, with 464 unit tests passing (`WorkQueueHealthTest` 12).

- [ ] **Step 6: Update the README status**

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`).

````diff
diff --git a/db-work-queue/README.md b/db-work-queue/README.md
index 22bbd47..f9b921b 100644
--- a/db-work-queue/README.md
+++ b/db-work-queue/README.md
@@ -2,7 +2,7 @@
 
 Db2-backed work-queue engine. Design: [spec](../docs/superpowers/specs/2026-09-21-db-work-queue-design.md).
 
-Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`), `ClaimHandle`, `ItemProcessor` and `QueueRunner` done; the `Sweeper`, metrics and health next.
+Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`), `ClaimHandle`, `ItemProcessor`, `QueueRunner`, the `Sweeper`, the `BacklogSampler`, metrics and health done; the Db2 ITs 6–12 next.
 
 ## Prerequisites
 
````

- [ ] **Step 7: Run the whole build, ITs included**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, with 464 unit tests and 75 ITs passing. JaCoCo reports 97% of instructions and 94% of branches covered.

- [ ] **Step 8: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/pom.xml \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueHealth.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkQueueHealthTest.java \
        db-work-queue/README.md
git commit -m "feat: add work-queue liveness and readiness" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage (revision 12):**
  - §6 `Sweeper` (batches of `sweep-batch-size`, repeating while full, on every instance): `Sweeper` and `SweeperTest` (Task 2), its loop (Task 4). The SQL, with `SKIP LOCKED DATA`, is Phase 1's `sweep`.
  - §6 `BacklogSampler` and the §9.6 backlog gauges: `sampleBacklog()` and its three ITs (Task 1), `BacklogSampler` (Task 2), its loop (Task 4), the gauges (Task 5).
  - §5.2 stop sequence (sweeper and sampler stopped last, within 1s in all) and `crash()`: Task 4.
  - §9.6 every metric: `WorkQueueMetricsTest.everyMeterOfTheSpecIsRegistered` names all 18 and their tags; each other test reads a group through the registry. The inputs: Task 3 (claims, renewals, outcomes, lag, DB age), Task 4 (backlog), Task 5 (calls).
  - §9.6 health: `WorkQueueHealth` (Task 6). Liveness DOWN at `hung-task-limit`, after an invariant violation and on a dead loop; readiness DOWN during stop, past `L` and past `db-staleness-limit`. The `SchemaCheck` gate is Phase 3.
  - §11.1 `WorkQueueHealthTest`: every bullet, B2's worst case included. The two §11.1 `QueueRunnerLifecycleTest` bullets that 2c left to this slice ("a collision … turns liveness DOWN", "`hung-task-limit` → liveness DOWN") are `anInvariantViolationTurnsLivenessDownForGood` and `reachingTheHungTaskLimitTurnsLivenessDownUntilAHungTaskEnds`.
  - §11.2 `WorkItemRepositoryIT`, the backlog bullet: Task 1.
- **Verified before writing.** Every code block and patch here comes from a scratch worktree where the tasks were replayed in order from `93dca0b`, and every command was run there. The failing states are measured: Tasks 1–6 each fail to compile on exactly the symbols named. Unit-test totals: 405 → 407 → 421 → 431 → 439 → 452 → 464. `QueueRunnerLifecycleTest` has 47 → 55 → 63 tests, and `WorkItemRepositoryIT` 27 → 30. The full `./mvnw verify` passes with 464 unit tests and 75 ITs.
- **The tests catch what they claim to.** These mutations were each killed by the intended test:
  - joining each loop for 1s in turn instead of against one deadline;
  - `deadLoops()` ignoring the run flags;
  - `crash()` or a failed `start()` leaving the sweeper uninterrupted;
  - `stop()` leaving the sampler uninterrupted;
  - `deadLoops()` skipping the renewal loop;
  - in readiness, `<` instead of `≤` for the lag and for the DB age;
  - readiness ignoring `stopping`;
  - liveness ignoring dead loops or invariant violations;
  - liveness using `>` for the hung-task limit.
- **Found while prototyping.**
  - The first versions of the crash and start-failure tests passed without the sweeper being interrupted: a loop whose flag was cleared still ended after its 1s interval, inside Awaitility's default wait, and a sweeper thread not yet past its flag check ended by itself. The tests now use one-minute intervals, and the start-failure test waits until the sweeper is inside its first pass.
  - Equal values in the metrics tests (one lost claim, one violation, one late registration) would have hidden a meter bound to the wrong accessor, so each value is now distinct.
  - Db2 12.1 accepts `SECONDS_BETWEEN`, and under the default isolation the backlog sample returns the committed state of a locked row at once.
- **Noticed, not changed.**
  - The LostClaimsHigh alert (§9.6) divides `claims.lost`, a count of rows, by `claims`, a count of claim operations. With up to 20 rows per operation, its 1% threshold can mean as little as 0.05% of claimed rows. When Phase 3 writes `docs/alerts.yml`, it should divide by claimed rows, which needs a counter the engine does not have yet.
  - A dead sweeper or backlog sampler loop is only logged, as the spec says. On a single instance, rows whose attempts are exhausted then stay expired until a restart; the ExpiredClaims alert fires, but liveness does not.
  - The timers have no maximum or percentiles. If the load tests (§11.4) need a claim or renewal p99, `QueueRunner` would record into Micrometer `Timer`s instead.

## After this plan

- **2e — Db2 ITs 6–12 (slice 2.7).** These are `RecordingDownstream`, the Toxiproxy host-port spike, and one IT-column definition in place of the current four (`Db2TestSupport`, `ItConfig`, `DbTimeoutsTest.IT`, `LeaseTimingTest.IT`).
  - `PoisonRowIT` uses `crash()`.
  - `GracefulShutdownIT` asserts E2 against `stop()`'s bound, now `shutdown-grace + shutdown-cancel-wait + 1s`.
  - `HungTaskIT` and `DbOutageIT` read liveness and readiness through `WorkQueueHealth`.
  - Passing them closes the Phase 2 gate: §11.1 and ITs 6–12.
- **Phase 3.** Auto-configuration wires `WorkItemRepository`, `ItemProcessor`, `QueueRunner`, `WorkQueueMetrics` and `WorkQueueHealth` as beans, puts the two health indicators in the liveness and readiness groups, and adds `SchemaCheck` to readiness.
