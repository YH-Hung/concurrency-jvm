# Work Queue Phase 2c: QueueRunner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `QueueRunner` (spec slice 2.5): the poll loop with its permit rule, the renewal loop, the DB-free supervisor, `stop()` and `crash()`, and `QueueRunnerLifecycleTest`. It also lands the items carried over from the Phase 2b review: spec revision 11 (B4 counts `G`; `claimedAt` is the origin of E3 and T2), a renewal round that tells a claim its own task ended from a lost one, a shared diagnostics helper for logging failures, and a thread-safe `ScriptedRepository`.

**Architecture:** `QueueRunner` is the only class that creates `ClaimHandle`s, starts their virtual threads, and holds the registry and the permit semaphore. Each loop's body is a package-private step method (`pollOnce`, `renewOnce`, `superviseOnce`), so the lifecycle tests drive races on the test thread with a fake clock and latches. Only the start, stop and crash tests run the real loops, on the real clock. The processor is injected as a functional interface: `ItemProcessor::process` in production, and latch-controlled fake tasks in the tests. `WorkItemRepository.renew` now returns a `RenewalResult` that splits the requested claims into renewed, ended and lost, all in one transaction, so a task that finished after the round's snapshot is not counted as a lost claim.

**Tech Stack:** JDK 25 (virtual threads), Spring `SmartLifecycle` (spring-context, already on the classpath through `spring-boot-starter-jdbc`), SLF4J, JUnit 5, AssertJ, Awaitility (from `spring-boot-starter-test`), Testcontainers Db2 for the renewal ITs. Build with the Maven wrapper in `db-work-queue/`.

**Spec:** `docs/superpowers/specs/2026-09-21-db-work-queue-design.md`. Task 1 raises it to revision 11. The relevant sections are §5.2 (task lifecycle), §5.3 (renewal round, B4), §5.4 (logging), §7 (E2, E3, T2), §11.1 (`QueueRunnerLifecycleTest`) and §11.2 (`WorkItemRepositoryIT`).

**Starting point:** `main` at `d632489` (Phase 2b), where `./mvnw -pl work-queue-engine test` runs 346 unit tests, all passing. Create the branch `db-work-queue/phase-2c` from `main` before Task 1.

## Global Constraints

- Package `hle.org.workqueue.engine`. Main code goes in `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/`, tests in `.../src/test/java/hle/org/workqueue/engine/`.
- Run every command from `db-work-queue/` with `./mvnw`, never `mvn`, which is not on PATH.
- Unit tests (`*Test`) must not start Db2: never reference `Db2TestSupport` from them. Use `ItConfig` for the IT column. ITs (`*IT`) need Docker running. The first Db2 start under Rosetta takes 5–10 minutes.
- No Mockito. Fakes are hand-written. `ScriptedRepository` subclasses `WorkItemRepository` over an unconnected `DriverManagerDataSource`.
- Times are `System.nanoTime()` readings held as `long` and compared overflow-safely (`a - b < 0`, never `a < b`). The lifecycle test's fake clock starts 5s before `Long.MAX_VALUE`, so every deadline in it wraps around.
- The engine never logs idempotency keys, `OPERATION_ID`s, payloads or results (spec §5.4). Logs carry the row id, claim token, owner and outcome only. A failure is logged as `Diagnostics.describe(t)`, never as its message and never by passing the throwable to the logger. `ClaimHandle.toString()` prints only the id and token.
- Match the existing style: records with compact-constructor validation, `IllegalArgumentException` for a bad argument, `Objects.requireNonNull(value, "name")`, Javadoc on public types, and comments only where the reason isn't obvious. Engine internals are package-private (`final class`); `RenewalResult` is public, like `PersistResult`, because the public `WorkItemRepository.renew` returns it.
- Tests wait with Awaitility (`await().until(...)`), never with fixed sleeps.
- Scope: Micrometer, `WorkQueueHealth`, the `Sweeper` and the `BacklogSampler` arrive with slice 2.6. `QueueRunner` keeps its counters (`invariantViolations`, `registrationsLate`, `claimsLost`) as `AtomicLong`s behind package-private accessors, and 2.6 binds them to meters. Auto-configuration, which creates the owner id and registers the runner as a bean, arrives in Phase 3. `RecordingDownstream` and ITs 6–12 arrive with slice 2.7.

## Design decisions the spec leaves open

Read these before implementing. The code below already follows them.

1. **Each loop body is a step method.** `pollOnce()`, `renewOnce()` and `superviseOnce()` are package-private, and each loop only repeats its step and sleeps. The lifecycle tests call the steps directly on the test thread with a fake clock, so a race is set up with latches instead of timing. Only the start, stop and crash tests run the loops, with `System::nanoTime`.
2. **The processor is a functional interface, `QueueRunner.Processor`,** with the signature of `ItemProcessor.process`. Production passes `itemProcessor::process`. The tests pass `Tasks`, whose tasks run until the test releases them, and which can ignore interrupts. One test wires the real `ItemProcessor` through, to show that it fits.
3. **The injected thread factory is `QueueRunner.TaskThreads`: `Thread newThread(ClaimHandle handle, Runnable body)`.** It returns an unstarted thread. Production uses `VIRTUAL_THREADS` (`Thread.ofVirtual().name("workqueue-task-<id>-<token>")`). The factory runs before the transfer, so a factory that throws leaves the permit with the poll loop. Tests use the factory to record every handle that receives a permit, registered or not, and the permit invariant counts those handles.
4. **The registry is injectable (a `ConcurrentMap`).** This is how the tests make a registration throw, and how they land a `crash()` between a handle's transfer and its registration.
5. **Pauses.** After an empty claim, the loop pauses for `idle-poll-interval` ± 50%. After a claim that returned rows, including a partial claim, it polls again at once: the next `acquire` blocks until a permit is free, which throttles it. After a failed claim, or an iteration that threw after the claim (a handle that could not be built), the pause is `idle-poll-interval · 2^f`, capped at `poll-backoff-max`, where `f` counts consecutive failures. Any claim that returns resets `f`.
6. **The hung-task limit is checked after the permits are acquired.** While `hungTasks() ≥ hung-task-limit`, the iteration returns its permits unused and pauses for `supervisor-interval`. The count is recomputed on every iteration, so claiming resumes if a hung thread finally ends. Liveness (slice 2.6) reports DOWN from the same count.
7. **Renewal.** Every claim the round reports lost is counted in `claimsLost` and cancelled with `LOST`. A claim reported ended is neither. `RenewalResult` makes the three sets disjoint, and the repository computes them in the same transaction.
8. **Cancel-on-register.** The cancel step of `stop()` and `crash()` first stores its reason in a volatile field, then cancels every registered handle. `registerAndStart` reads the field after `register()` and cancels the new handle before `thread.start()`. A handle that registers after the cancel pass read the registry is therefore cancelled too. Its body sees the cancel in `markRunning()`, skips processing, and finishes.
9. **Loop failures.** A `RuntimeException` inside a loop iteration is logged with its diagnostics, and the loop goes on: the poll loop backs off, and the renewal loop counts the round as failed. An `Error` ends that loop's thread, which slice 2.6's liveness reports as a dead loop.
10. **Stop timing.** `stop()` joins the poll loop within the grace period and drains until the grace deadline. After the cancel wait, it interrupts the renewal and supervisor loops and joins each for at most 1s. It returns within `shutdown-grace + shutdown-cancel-wait + 2s`, inside E2's 5s margin. A runner starts at most once: after `stop()` or `crash()`, `start()` throws.
11. **`QueueRunner` implements `SmartLifecycle` now,** with the default phase (`Integer.MAX_VALUE`: started last, stopped first). Phase 3's auto-configuration registers it as a bean.
12. **Renewal's "ended" read-back** (spec §5.3, revision 11) matches `STATUS <> 'CLAIMED' AND OWNER = :owner` with the same pairs. `complete` and `retryOrFail` keep the owner and token. Sweep and revoke change the token, and replay clears the owner, so none of those three reads back as ended.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `docs/superpowers/specs/2026-09-21-db-work-queue-design.md` (modify) | Revision 11 | 1 |
| `main/.../TimingBudget.java` (modify) | B4 counts `G` | 1 |
| `test/.../TimingBudgetTest.java` (modify) | B4 at the defaults (106s) and in the IT column (19.9s) | 1 |
| `main/.../Diagnostics.java` (create) | What may be logged about a failure: class names and SQL codes down its cause chain | 2 |
| `main/.../ItemProcessor.java` (modify) | Uses `Diagnostics` instead of its private copy | 2 |
| `test/.../DiagnosticsTest.java` (create) | The helper on its own | 2 |
| `main/.../RenewalResult.java` (create) | One round's renewed, ended and lost claims | 3 |
| `main/.../WorkItemRepository.java` (modify) | `renew` reads back the claims it did not renew, in the same transaction | 3 |
| `test/.../WorkItemRepositoryIT.java`, `StaleCompletionIT.java`, `RevokeRaceIT.java` (modify) | Assert on `RenewalResult`, including one new IT that separates ended from lost | 3 |
| `test/.../ScriptedRepository.java` (modify) | Thread-safe; scripted `claim` and `renew` | 4 |
| `main/.../QueueRunner.java` (create) | Settings, poll loop (Task 4), renewal round (5), supervisor (6), loops, stop and crash (7) | 4–7 |
| `test/.../QueueRunnerLifecycleTest.java` (create) | Spec §11.1 `QueueRunnerLifecycleTest`, grown section by section | 4–7 |
| `db-work-queue/README.md` (modify) | Status line | 7 |

---

### Task 1: Spec revision 11 and B4 with G

**Files:**
- Modify: `docs/superpowers/specs/2026-09-21-db-work-queue-design.md` (revision 11)
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/TimingBudget.java` (the B4 check)
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/TimingBudgetTest.java`

**Interfaces:**
- Consumes: `WorkQueueProperties.getRegistrationAllowance()` (existing).
- Produces: `TimingBudget.check` rejects `max-processing-time < G + external-call-timeout + (completion-retries + 1)·W + completion-retries·completion-retry-delay`, and its message names B4. Every later task implements against spec revision 11.

- [ ] **Step 1: Raise the spec to revision 11**

Apply this patch from the repository root with `git apply` (paste it into a heredoc: `git apply <<'PATCH' ... PATCH`). It changes §5.2 (nothing after `thread.start()`, `claimedAt`, the body catches `Throwable`, cancel-on-register), §5.3 (B4 and the renewal read-back), §5.4 (diagnostics), §6 (settings checked at startup), §7 (E3, T2), §9.6 (`claims.lost`), §11.1, §11.2 and the change history.

````diff
diff --git a/docs/superpowers/specs/2026-09-21-db-work-queue-design.md b/docs/superpowers/specs/2026-09-21-db-work-queue-design.md
index 1c73875..fe430fd 100644
--- a/docs/superpowers/specs/2026-09-21-db-work-queue-design.md
+++ b/docs/superpowers/specs/2026-09-21-db-work-queue-design.md
@@ -1,6 +1,6 @@
 # db-work-queue — Design
 
-Date: 2026-09-21 (revision 10: 2026-09-26)
+Date: 2026-09-21 (revision 11: 2026-09-26)
 Status: Architecture accepted. Phase 1 gate passed (2026-09-25); approved for Phase 2.
 Production approval pending review of this revision. Every time value in §5.3 and §7 is a conditional target
 pending validation by the Phase 1 spike, review of the §5.3 timing argument,
@@ -261,17 +261,21 @@ backs off exponentially (cap `poll-backoff-max`).
    3. `try { register; thread.start(); } catch (Throwable t) { handle.finish(); ... }` —
       any failure between transfer and a successful start, including a registration error,
       is cleaned up by `finish()`, which releases the permit exactly once and is a no-op
-      on the registry if the handle was never registered.
+      on the registry if the handle was never registered. Nothing follows `thread.start()`
+      inside that `try`: once the thread runs, only its own body may finish the handle.
    4. Registration is `registry.putIfAbsent(key, handle)`. A collision is impossible (tokens
       are unique per claim), so it is treated as an invariant violation: the new handle is
       finished without starting, `workqueue.invariant.violations` is incremented, an ERROR
       is logged, and liveness reports DOWN.
-   5. The handle records the time between the claim operation returning and its
-      registration; more than `registration-allowance` increments
+   5. The handle records `claimedAt`, the time the claim operation returned. More than
+      `registration-allowance` between `claimedAt` and its registration increments
       `workqueue.registration.late` (the B2 proof assumes it does not happen, §5.3).
-4. **Run.** Body: `try { if (!handle.markRunning()) return; processor.process(...) } finally { handle.finish(); }`.
+      `claimedAt` is also where the handle's deadline, E3 and T2 (§7) count from.
+4. **Run.** Body: `try { if (!handle.markRunning()) return; processor.process(...) } catch (Throwable t) { log } finally { handle.finish(); }`.
    `markRunning()` fails if the handle was already cancelled, so a cancel that arrives
-   before execution skips processing but still runs `finish()`.
+   before execution skips processing but still runs `finish()`. The body catches every
+   `Throwable` and logs only the row id, token and the failure's diagnostics (§5.4); an
+   uncaught exception would reach the thread's default handler, which prints its message.
 5. **Finish (exactly once).** `finish()` does work only if `ended.compareAndSet(false, true)`:
    `registry.remove(key, this)` (value-aware — a handle can only remove itself, never a
    newer claim of the same row), then `permits.release()`, then metrics.
@@ -308,11 +312,16 @@ held = concurrency`.
 returned is still started) → wait up to `shutdown-grace` for the registry to empty, with
 renewal running → cancel all remaining handles → wait up to `shutdown-cancel-wait` → stop
 renewal, supervisor and sweeper → return. Nothing is released in Db2; leftover claims
-expire with their attempt consumed.
+expire with their attempt consumed. A stopped runner is not started again.
 
 **`crash()`** (package-private, tests only): stop all loops and cancel all handles at once,
 no drain and no waiting.
 
+**Cancelling a handle not yet registered.** The cancel step of stop and `crash()` first
+records its reason, then cancels every registered handle. The poll loop reads that reason
+after each registration and cancels the new handle before starting its thread, so a handle
+transferred but not yet registered when the cancel pass read the registry is cancelled too.
+
 ### 5.3 Timing budget
 
 Each DB operation (claim, renew, complete, retryOrFail, sweep, backlog sample, admin op)
@@ -410,7 +419,7 @@ the constraint). Symbols: `I` = renew-interval, `d` = renew-retry-delay, `L` = l
 | B1 | `T_lock < T_tx` | a lock wait surfaces as a lock-timeout error | 3 < 5 | 1 < 2 |
 | B2 | `max(I, W) + 3W + d + G < L` | every claim, including a new one, survives one failed renewal round under the actual schedule | 74 < 100 | 22.4 < 30 |
 | B3 | `pool size ≥ concurrency + 4` | tasks, poll, renewal, sweeper and backlog sampler never wait for each other's connections | 20 ≥ 20 | 8 ≥ 8 |
-| B4 | `max-processing-time ≥ external-call-timeout + (completion-retries + 1)·W + completion-retries·completion-retry-delay` | a slow-but-healthy task is not cut off by its deadline | 120 ≥ 105 | 25 ≥ 19.7 |
+| B4 | `max-processing-time ≥ G + external-call-timeout + (completion-retries + 1)·W + completion-retries·completion-retry-delay` | a slow-but-healthy task is not cut off by its deadline, which counts from `claimedAt`, up to `G` before the task starts | 120 ≥ 106 | 25 ≥ 19.9 |
 | B5 | `db-staleness-limit > 1.5·idle-poll-interval + W` | a healthy idle instance never reports DB staleness | 90 > 19.5 | 10 > 5.65 |
 
 **Renewal round** is exactly one DB operation, however many claims are renewed:
@@ -424,7 +433,18 @@ SELECT ID, CLAIM_TOKEN FROM FINAL TABLE (
 )
 ```
 
-Lost claims = requested pairs − returned pairs.
+In the same transaction, the pairs the `UPDATE` did not return are read back:
+
+```sql
+SELECT ID, CLAIM_TOKEN FROM WORK_ITEM
+ WHERE STATUS <> 'CLAIMED' AND OWNER = :me
+   AND ((ID = ? AND CLAIM_TOKEN = ?) OR ...)   -- the pairs not renewed
+```
+
+A pair it returns is **ended**: this owner's own `complete` or `retryOrFail` ended it after
+the round took its snapshot. **Lost** claims = requested − renewed − ended: swept, revoked or
+re-claimed, each of which changes the row's token or owner (§5.1). Only lost claims are
+counted and cancelled.
 
 **Orchestrator setting (documented, not checkable):** termination grace period ≥
 `shutdown-grace + shutdown-cancel-wait + 10s`.
@@ -513,7 +533,10 @@ time, retention is permanent for the namespace.
   dedupe.
 - A downstream that cannot provide durable idempotency is **not supported**.
 - The engine never logs `PAYLOAD`, `RESULT_VALUE` or idempotency keys (may carry business
-  data); logs carry `ID`, `CLAIM_TOKEN`, owner and outcome only.
+  data); logs carry `ID`, `CLAIM_TOKEN`, owner and outcome only. A failure is logged by its
+  diagnostics — the class names down its cause chain, with SQL codes — never by its message
+  or the throwable itself: a message may carry business data, and a message or cause that
+  throws would throw out of the log call.
 
 ## 6. Engine components (`hle.org.workqueue.engine`)
 
@@ -581,6 +604,10 @@ time, retention is permanent for the namespace.
 
 The demo uses the production defaults, so its scenarios exercise the real budget.
 
+`QueueRunner` checks its own settings when it is constructed at startup: every interval,
+grace and allowance it uses is positive, and `concurrency`, `claim-batch-size` and
+`hung-task-limit` are at least 1.
+
 ### Claim SQL (settled by the Phase 1 spike)
 
 One claim operation, one transaction, two selections in claim order (§5.1):
@@ -665,11 +692,11 @@ under test.
 |---|---|---|---|---|
 | E1 | owner killed, frozen, or stopped renewing | its claims eligible within `L + W` of the moment the owner stops starting renewal rounds (a write already in flight can still land up to `W` later) | 118s | 35.5s |
 | E2 | SIGTERM | process exits within `shutdown-grace + shutdown-cancel-wait + 5s`; leftover claims eligible within `shutdown-grace + shutdown-cancel-wait + L + W` of SIGTERM | 30s / 143s | 8s / 38.5s |
-| E3 | task ignores interruption | its claim eligible within `M + W + L` of being claimed; counted hung within `M + hung-grace + supervisor-interval`; liveness DOWN within `hung-grace + supervisor-interval` of the `hung-task-limit`-th task being cancelled | 238s / 151s / 31s | 60.5s / 27.1s / 2.1s |
+| E3 | task ignores interruption | its claim eligible within `M + W + L` of its `claimedAt` (§5.2); counted hung within `M + hung-grace + 2·supervisor-interval` (the cancel and the hung mark each come at the supervisor's next pass); liveness DOWN within `hung-grace + supervisor-interval` of the `hung-task-limit`-th task being cancelled | 238s / 152s / 31s | 60.5s / 27.2s / 2.1s |
 | E4 | stale owner resumes | its lost claims cancelled within `max(I, W) + d + W` of resuming | 37s | 11.2s |
 | E5 | Db2 unreachable for D | **lease-preservation target** (§5.3): no claim is lost if `D ≤ max(d, L − max(I, W) − 4W − d − G)`. Not the longest survivable outage: a longer outage may let claims expire, be re-claimed and be called again, and durable downstream idempotency keeps the effects correct (`DbOutageIT`). After restoration, first successful claim within `poll-backoff-max + W` if the instance has a free permit. | 8s / 48s | 2.1s / 7.5s |
 | T1 | eligible and attempts exhausted | FAILED by the `Sweeper` within `E + sweep-interval + ⌈X / S⌉ · W`, where `X` is the number of rows eligible for sweeping; needs one live instance whose sweep transactions succeed | E + 30s + ⌈X/100⌉ · 18s | E + 1s + ⌈X/100⌉ · 5.5s |
-| T2 | claimed recovered row | if that attempt completes or fails finally, it does so within `C + M` | C + 120s | C + 25s |
+| T2 | claimed recovered row | if that attempt completes or fails finally, it does so within `C + M`, where `C` is that claim's `claimedAt` (§5.2) | C + 120s | C + 25s |
 
 **Measured recovery objectives**
 
@@ -784,7 +811,7 @@ changing replica count.
 | `call.duration{result=ok\|error\|timeout}` | timer | external calls |
 | `renewal.duration`, `renewal.errors` | timer, counter | renewal rounds that ran |
 | `renewal.lag` | gauge | max over renewal-eligible claims of the time since that claim's last successful lease write (claim or renewal); **0 when there are none** |
-| `claims.lost` | counter | claims reported lost by renewal |
+| `claims.lost` | counter | claims reported lost by renewal (§5.3); a claim its own task ended after the round's snapshot is not lost |
 | `db.last_success_age` | gauge | time since any engine DB operation succeeded; kept fresh on idle instances by the poll loop's empty claims, the sweeper and the backlog sampler |
 | `inflight`, `permits.available`, `tasks.hung` | gauges | local capacity |
 | `registration.late` | counter | handles registered more than `registration-allowance` after their claim returned (a process pause the B2 proof does not cover) |
@@ -891,9 +918,10 @@ bounds are asserted with the formulas of §7 evaluated on the test's config.
 
 ### 11.1 Unit and lifecycle-race tests (no Db2)
 
-`QueueRunner` takes an injectable repository, thread starter and clock, so races are
-driven deterministically with latches; every scenario ends by asserting the permit
-invariant.
+`QueueRunner` takes an injectable repository, processor, task-thread factory, clock and
+registry, so races are driven deterministically with latches; every scenario ends by
+asserting the permit invariant, counting every handle that received a permit, registered or
+not.
 
 - `ClaimHandleTest`: `finish()` exactly once under concurrent finish/cancel (10 000
   iterations released together by a barrier); value-aware removal; cancel never releases
@@ -918,7 +946,13 @@ invariant.
   - supervisor: deadline → cancel; `hung-grace` → hung gauge; `hung-task-limit` →
     liveness DOWN and the poll loop stops claiming;
   - `stop()`: no claims after stop begins, renewal continues while draining, cancel at
-    the grace deadline, nothing released.
+    the grace deadline, nothing released;
+  - `crash()` between a handle's transfer and its registration → that handle is cancelled
+    before its thread starts, and the processor is never invoked;
+  - renewal: a claim reported lost is counted and cancelled; one its own task ended after
+    the snapshot is neither;
+  - a task that throws, even an `Error` → logged by id, token and class name only,
+    `finish()` once.
 - `RenewalScheduleTest`: `next(s, e, ok)` for success, overrun and failure.
 - `TimingBudgetTest`: each of B1–B5 rejects a violating config and names itself;
   `I = 15s, W = 5s, d = 1s, G = 1s, L = 26s` is rejected by B2; `W = 18s` is rejected by
@@ -978,7 +1012,8 @@ invariant.
    - claim order: expired CLAIMED rows before PENDING; PENDING by `AVAILABLE_AT`;
    - claim skips rows locked by a concurrent claim without waiting, still oldest first;
    - reclaim after forced expiry with the next token;
-   - renew returns exactly the matching CLAIMED pairs of this owner;
+   - renew renews exactly the matching CLAIMED pairs of this owner, reports the pairs this
+     owner already completed or failed as ended, and the rest as lost;
    - fenced writes with a stale token **or a different owner** update 0 rows;
    - the persist read-back distinguishes "own write already committed" from "fenced";
    - sweep bumps the token and clears the owner; the swept owner's late renew, complete and
@@ -1246,3 +1281,16 @@ and load validation.
 | Leases kept at 100s / 30s, so E5 is 8s by default and 2.1s for ITs | Owner's decision; E1 stays 118s. |
 | `LeaseSimulation` lets a failed outage round fail as early as it can, at `W`, or aligned to the outage's last step, with a full-enumeration cross-check (§11.1) | A failed round takes up to `W`, and the model must cover the fast-failing retry chain. The round the outage begins in also needs the early failure: without it the reduced model found the first loss one step late near the B2 bound. |
 | §4 `OWNER` comment: current claim holder, NULL after revocation, sweep or replay | Sweep now clears `OWNER` (revision 8). |
+
+**Revision 11 (Phase 2c planning, `QueueRunner`):**
+
+| Change | Reason |
+|---|---|
+| B4 adds `G`: `max-processing-time ≥ G + external-call-timeout + (completion-retries + 1)·W + completion-retries·completion-retry-delay` (106 ≤ 120 by default, 19.9 ≤ 25 in ITs) | The deadline counts from `claimedAt`, but the task starts only after its registration, up to `G` later, so without `G` a slow-but-healthy task could be cut off. |
+| `claimedAt`, when the claim operation returned, is named as the origin of the deadline, E3 and T2 | "Being claimed" and `C` could mean the claim operation's start, its commit or its return. |
+| E3's hung target is `M + hung-grace + 2·supervisor-interval` (152s / 27.2s) | The supervisor cancels at its first pass after the deadline and marks the task hung at its first pass `hung-grace` after that; each pass can come up to one interval late. |
+| The renewal round reads back, in the same transaction, the pairs it did not renew; a pair this owner already ended is not lost | A task that persisted its outcome between a round's snapshot and its `UPDATE` was counted in `claims.lost` and cancelled, so `claims.lost = 0` in `SustainedLoadIT` could not hold. |
+| Nothing follows `thread.start()` in the `try` whose `catch` finishes the handle; the task body catches `Throwable` | A failure after the start would release the permit of a running task. An uncaught failure would reach the thread's default handler, which prints its message. |
+| The cancel step of stop and `crash()` also reaches a handle transferred but not yet registered | A handle registered just after the cancel pass read the registry would have run on uncancelled. |
+| Failures are logged by their diagnostics only (§5.4) | A message may carry business data, and a message or cause that throws would throw out of the log call. |
+| `QueueRunner` validates its intervals, graces and counts at startup | A zero `hung-grace` marked every cancelled task hung at once. |
````

- [ ] **Step 2: Write the failing tests**

In `TimingBudgetTest.java`, replace `b4RejectsAProcessingTimeThatCutsOffASlowHealthyTask` and add the IT-column case after it:

```java
    @Test
    void b4RejectsAProcessingTimeThatCutsOffASlowHealthyTask() {
        // 1s + 30s + (3 + 1)·18s + 3·1s = 106s
        WorkQueueProperties properties = new WorkQueueProperties();
        properties.setMaxProcessingTime(ofSeconds(105));

        assertThatThrownBy(() -> TimingBudget.check(properties, DEFAULT_POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B4")
                .hasMessageContaining("105s < 106s");

        properties.setMaxProcessingTime(ofSeconds(106));
        TimingBudget.check(properties, DEFAULT_POOL_SIZE);
    }

    @Test
    void b4CountsTheRegistrationAllowanceInTheItConfig() {
        // 200ms + 3s + (2 + 1)·5.5s + 2·100ms = 19.9s
        WorkQueueProperties properties = ItConfig.properties();
        properties.setMaxProcessingTime(ofMillis(19_899));

        assertThatThrownBy(() -> TimingBudget.check(properties, ItConfig.POOL_SIZE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B4")
                .hasMessageContaining("19.899s < 19.9s");

        properties.setMaxProcessingTime(ofMillis(19_900));
        TimingBudget.check(properties, ItConfig.POOL_SIZE);
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=TimingBudgetTest`
Expected: 2 failures. `b4RejectsAProcessingTimeThatCutsOffASlowHealthyTask` fails because 105s passes, and `b4CountsTheRegistrationAllowanceInTheItConfig` fails because 19.899s passes.

- [ ] **Step 4: Count G in B4**

In `TimingBudget.check`, replace the B4 block:

```java
        // The deadline counts from the claim's return, and the task starts up to G later.
        Duration slowHealthyTask = properties.getRegistrationAllowance()
                .plus(properties.getExternalCallTimeout())
                .plus(w.multipliedBy(properties.getCompletionRetries() + 1L))
                .plus(properties.getCompletionRetryDelay().multipliedBy(properties.getCompletionRetries()));
        if (properties.getMaxProcessingTime().compareTo(slowHealthyTask) < 0) {
            violations.add("B4: max-processing-time >= G + external-call-timeout + (completion-retries + 1)·W"
                    + " + completion-retries·completion-retry-delay, but "
                    + seconds(properties.getMaxProcessingTime()) + " < " + seconds(slowHealthyTask));
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=TimingBudgetTest`
Expected: PASS (14 tests). Then run `./mvnw -q -pl work-queue-engine test`. Expected: 347 tests, all passing.

- [ ] **Step 6: Commit** (from the repository root)

```bash
git add docs/superpowers/specs/2026-09-21-db-work-queue-design.md db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/TimingBudget.java db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/TimingBudgetTest.java
git commit -m "docs: raise the work queue spec to revision 11 and count G in B4" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Diagnostics, the shared failure description

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Diagnostics.java`
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java` (drop its private copy)
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DiagnosticsTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `final class Diagnostics` with `static String describe(Throwable failure)` and `static final int MAX_CAUSES = 8`. It returns the class names down the cause chain, joined by `", caused by "`, with `" (SQLState s, error code c)"` after each `SQLException`. It never reads a message and never throws. Tasks 4–7 log every claim, renewal, task and loop failure through it.

- [ ] **Step 1: Write the failing test**

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DiagnosticsTest.java`:

```java
package hle.org.workqueue.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticsTest {

    @Test
    void aFailureIsDescribedByItsClassNameWithoutItsMessage() {
        assertThat(Diagnostics.describe(new IllegalStateException("payload-7")))
                .isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void causesAreListedWithTheirSqlCodes() {
        RuntimeException failure = new DataAccessResourceFailureException("could not store receipt-7",
                new SQLTransientConnectionException("payload-7 of order-7:charge", "08001", -4499));

        assertThat(Diagnostics.describe(failure)).isEqualTo(
                "org.springframework.dao.DataAccessResourceFailureException, caused by "
                        + "java.sql.SQLTransientConnectionException (SQLState 08001, error code -4499)");
    }

    @Test
    @Timeout(10)   // an unbounded walk of the cycle would hang, not fail
    void aCyclicCauseChainIsDescribedToABoundedDepth() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(Diagnostics.describe(first).split(", caused by ")).hasSize(Diagnostics.MAX_CAUSES);
    }

    @Test
    void aCauseThatCannotBeReadEndsTheDescription() {
        RuntimeException unreadable = new IllegalStateException() {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("getCause is broken");
            }
        };

        assertThat(Diagnostics.describe(unreadable)).isEqualTo(unreadable.getClass().getName());
    }

    @Test
    void sqlCodesThatCannotBeReadAreLeftOut() {
        SQLException unreadable = new SQLException("payload-7") {
            @Override
            public String getSQLState() {
                throw new IllegalStateException("getSQLState is broken");
            }
        };

        assertThat(Diagnostics.describe(unreadable)).isEqualTo(unreadable.getClass().getName());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=DiagnosticsTest`
Expected: a compilation failure, `cannot find symbol` for `Diagnostics`.

- [ ] **Step 3: Move the helper out of ItemProcessor**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Diagnostics.java`:

```java
package hle.org.workqueue.engine;

import java.sql.SQLException;

/**
 * What the engine may log about a failure (spec §5.4): the class names down its cause chain, with SQL codes. Never a
 * message, which may carry keys, payloads or results, and never the throwable itself: a logger that reads a message
 * or cause that throws would throw out of the log call.
 */
final class Diagnostics {

    /** Bounds the cause chain, which may be cyclic. */
    static final int MAX_CAUSES = 8;

    private Diagnostics() {
    }

    /** The class names down {@code failure}'s cause chain, with SQL codes. Reads nothing that can throw. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
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
}
```

Then apply this change to `ItemProcessor.java`. It deletes `diagnostics`, `causeOf`, `appendSqlCodes`, `MAX_LOGGED_CAUSES` and the `java.sql.SQLException` import, and calls the helper instead:

```diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java
index 5f0df56..172523b 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java
@@ -3,7 +3,6 @@ package hle.org.workqueue.engine;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 
-import java.sql.SQLException;
 import java.time.Duration;
 import java.util.Objects;
 import java.util.function.BooleanSupplier;
@@ -49,9 +48,6 @@ final class ItemProcessor {
     static final String NO_RESULT_ERROR = "the external service returned no result";
     static final String INVALID_OPERATION_ID_ERROR = "OPERATION_ID is not a valid operation identity; not called";
 
-    /** Bounds the logged cause chain, which may be cyclic. */
-    private static final int MAX_LOGGED_CAUSES = 8;
-
     private static final Logger log = LoggerFactory.getLogger(ItemProcessor.class);
 
     private final WorkItemRepository repository;
@@ -145,43 +141,10 @@ final class ItemProcessor {
     // reads a message or cause that throws would throw out of process().
     private Outcome abandoned(ClaimedItem item, RuntimeException lastFailure) {
         log.warn("Abandoned row {} token {} of owner {}: its outcome could not be persisted: {}", item.id(),
-                item.claimToken(), owner, diagnostics(lastFailure));
+                item.claimToken(), owner, Diagnostics.describe(lastFailure));
         return Outcome.ABANDONED;
     }
 
-    /** The class names down the failure's cause chain, with SQL codes: no messages, and nothing that can throw. */
-    private static String diagnostics(Throwable failure) {
-        StringBuilder text = new StringBuilder();
-        Throwable current = failure;
-        for (int depth = 0; current != null && depth < MAX_LOGGED_CAUSES; depth++) {
-            text.append(depth == 0 ? "" : ", caused by ").append(current.getClass().getName());
-            appendSqlCodes(text, current);
-            current = causeOf(current);
-        }
-        return text.toString();
-    }
-
-    private static Throwable causeOf(Throwable failure) {
-        try {
-            return failure.getCause();
-        } catch (RuntimeException unreadable) {
-            return null;
-        }
-    }
-
-    private static void appendSqlCodes(StringBuilder text, Throwable failure) {
-        if (!(failure instanceof SQLException sql)) {
-            return;
-        }
-        try {
-            String state = sql.getSQLState();
-            int code = sql.getErrorCode();
-            text.append(" (SQLState ").append(state).append(", error code ").append(code).append(')');
-        } catch (RuntimeException unreadable) {
-            // The class name alone is still a diagnostic.
-        }
-    }
-
     private static Outcome outcomeOf(PersistResult result) {
         return switch (result) {
             case DONE -> Outcome.COMPLETED;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test -Dtest='DiagnosticsTest,ItemProcessorTest'`
Expected: PASS (5 + 24 tests). `ItemProcessorTest`'s log assertions are unchanged, so it proves the abandoned-outcome log line did not change. Then `./mvnw -q -pl work-queue-engine test`. Expected: 352 tests, all passing.

- [ ] **Step 5: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/Diagnostics.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/DiagnosticsTest.java
git commit -m "refactor: share the failure diagnostics that the engine logs" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Renewal tells an ended claim from a lost one

**Files:**
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/RenewalResult.java`
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java` (`renew`, plus a `claimKeys` helper)
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java`, `StaleCompletionIT.java`, `RevokeRaceIT.java`

**Interfaces:**
- Consumes: `record ClaimKey(long id, long token)` (existing).
- Produces:
  - `public record RenewalResult(Set<ClaimKey> renewed, Set<ClaimKey> ended, Set<ClaimKey> lost)`, whose sets are copied, plus `static final RenewalResult NOTHING`.
  - `public RenewalResult WorkItemRepository.renew(String owner, Collection<ClaimKey> claims)`, which replaces `Set<ClaimKey> renew(...)`. Every requested claim lands in exactly one set: `renewed` got a new lease; `ended` is no longer CLAIMED but still has this owner and token; `lost` is everything else. It is one transaction. An empty `claims` returns `NOTHING` without touching the database. Task 4's `ScriptedRepository` and Task 5's `renewOnce` use this signature.

These are ITs: they need Docker, and the first Db2 start takes 5–10 minutes.

- [ ] **Step 1: Write the failing tests**

Apply this change to `WorkItemRepositoryIT.java`. It rewrites the first renewal test around `RenewalResult`, adds `renewReportsAClaimItsOwnerEndedAsEndedAndOneTakenFromItAsLost`, and has every fenced-renewal assertion check that the claim is reported lost:

```diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java
index b6dedd7..9976771 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java
@@ -11,7 +11,6 @@ import java.util.ArrayList;
 import java.util.List;
 import java.util.Locale;
 import java.util.Map;
-import java.util.Set;
 import java.util.concurrent.CountDownLatch;
 import java.util.concurrent.ExecutorService;
 import java.util.concurrent.Executors;
@@ -155,7 +154,7 @@ class WorkItemRepositoryIT {
     }
 
     @Test
-    void renewReturnsExactlyThisOwnersMatchingClaimedPairs() {
+    void renewRenewsExactlyThisOwnersMatchingClaimedPairs() {
         long r1 = rows.insert();
         rows.setAvailableAt(r1, -20);
         long r2 = rows.insert();
@@ -167,17 +166,44 @@ class WorkItemRepositoryIT {
         ClaimKey finished = repository.claim("owner-a", 1).getFirst().key();  // r3
         repository.complete("owner-a", finished, "result");
         rows.setAvailableAt(r1, 2);
+        ClaimKey staleToken = new ClaimKey(r1, mine.token() + 1);
 
-        Set<ClaimKey> renewed = repository.renew("owner-a",
-                List.of(mine, others, new ClaimKey(r1, mine.token() + 1), finished));
+        RenewalResult result = repository.renew("owner-a", List.of(mine, others, staleToken, finished));
 
-        assertThat(renewed).containsExactly(mine);
+        assertThat(result.renewed()).containsExactly(mine);
+        assertThat(result.ended()).containsExactly(finished);
+        assertThat(result.lost()).containsExactlyInAnyOrder(others, staleToken);
         assertThat(rows.availableIn(r1)).isGreaterThan(Duration.ofSeconds(20));
     }
 
+    @Test
+    void renewReportsAClaimItsOwnerEndedAsEndedAndOneTakenFromItAsLost() {
+        long completed = rows.insert();
+        rows.setAvailableAt(completed, -40);
+        long retried = rows.insert();
+        rows.setAvailableAt(retried, -30);
+        long failed = rows.insert();
+        rows.setAvailableAt(failed, -20);
+        rows.setAttempts(failed, 4);
+        long revoked = rows.insert();
+        rows.setAvailableAt(revoked, -10);
+        Map<Long, ClaimKey> keys = repository.claim("owner-a", 4).stream()
+                .collect(toMap(ClaimedItem::id, ClaimedItem::key));
+        assertThat(repository.complete("owner-a", keys.get(completed), "result")).isEqualTo(DONE);
+        assertThat(repository.retryOrFail("owner-a", keys.get(retried), "boom")).isEqualTo(RETRY_SCHEDULED);
+        assertThat(repository.retryOrFail("owner-a", keys.get(failed), "boom")).isEqualTo(FAILED);
+        assertThat(repository.revokeOwner("owner-a", false)).isEqualTo(1);   // the only row still CLAIMED
+
+        RenewalResult result = repository.renew("owner-a", keys.values());
+
+        assertThat(result.renewed()).isEmpty();
+        assertThat(result.ended()).containsExactlyInAnyOrder(keys.get(completed), keys.get(retried), keys.get(failed));
+        assertThat(result.lost()).containsExactly(keys.get(revoked));
+    }
+
     @Test
     void renewWithNoClaimsReturnsNothing() {
-        assertThat(repository.renew("owner-a", List.of())).isEmpty();
+        assertThat(repository.renew("owner-a", List.of())).isEqualTo(RenewalResult.NOTHING);
     }
 
     @Test
@@ -192,7 +218,7 @@ class WorkItemRepositoryIT {
             assertThat(claimed).hasSize(1);
             ClaimKey key = claimed.getFirst().key();
 
-            assertThat(repository.renew("owner-a", List.of(key))).containsExactly(key);
+            assertThat(repository.renew("owner-a", List.of(key)).renewed()).containsExactly(key);
 
             long expired = rows.insert();
             rows.setClaim(expired, "owner-dead", 5, 5, -1);
@@ -226,7 +252,7 @@ class WorkItemRepositoryIT {
 
         assertThat(repository.complete("owner-a", stale, "late")).isEqualTo(FENCED);
         assertThat(repository.retryOrFail("owner-a", stale, "late")).isEqualTo(FENCED);
-        assertThat(repository.renew("owner-a", List.of(stale))).isEmpty();
+        assertThat(repository.renew("owner-a", List.of(stale)).lost()).containsExactly(stale);
         assertThat(rows.row(id)).isEqualTo(before);
     }
 
@@ -238,7 +264,7 @@ class WorkItemRepositoryIT {
 
         assertThat(repository.complete("owner-b", key, "late")).isEqualTo(FENCED);
         assertThat(repository.retryOrFail("owner-b", key, "late")).isEqualTo(FENCED);
-        assertThat(repository.renew("owner-b", List.of(key))).isEmpty();
+        assertThat(repository.renew("owner-b", List.of(key)).lost()).containsExactly(key);
         assertThat(rows.row(id)).isEqualTo(before);
     }
 
@@ -405,7 +431,7 @@ class WorkItemRepositoryIT {
 
         assertThat(repository.sweep(10)).isEqualTo(1);
 
-        assertThat(repository.renew("owner-a", List.of(key))).isEmpty();
+        assertThat(repository.renew("owner-a", List.of(key)).lost()).containsExactly(key);
         assertThat(repository.retryOrFail("owner-a", key, "late")).isEqualTo(FENCED);
         assertThat(repository.complete("owner-a", key, "late")).isEqualTo(FENCED);
         WorkItems.Row row = rows.row(id);
@@ -515,7 +541,8 @@ class WorkItemRepositoryIT {
         assertThat(failed.owner()).isNull();
         assertThat(rows.row(othersRow)).isEqualTo(othersBefore);
 
-        assertThat(repository.renew("owner-a", keys.values())).isEmpty();
+        assertThat(repository.renew("owner-a", keys.values()).lost())
+                .containsExactlyInAnyOrderElementsOf(keys.values());
         assertThat(repository.complete("owner-a", keys.get(retryable), "late")).isEqualTo(FENCED);
         assertThat(repository.retryOrFail("owner-a", keys.get(exhausted), "late")).isEqualTo(FENCED);
     }
```

In `StaleCompletionIT.java`:

```diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/StaleCompletionIT.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/StaleCompletionIT.java
index 1c040a0..96ac78b 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/StaleCompletionIT.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/StaleCompletionIT.java
@@ -39,7 +39,7 @@ class StaleCompletionIT {
         ClaimKey b = repository.claim("owner-b", 1).getFirst().key();
         assertThat(b.token()).isEqualTo(a.token() + 1);
 
-        assertThat(repository.renew("owner-a", List.of(a))).as("A's renewal reports the claim lost").isEmpty();
+        assertThat(repository.renew("owner-a", List.of(a)).lost()).as("A's renewal reports the claim lost").containsExactly(a);
         assertThat(repository.complete("owner-a", a, "result-a")).isEqualTo(FENCED);
         assertThat(repository.complete("owner-b", b, "result-b")).isEqualTo(DONE);
         assertThat(repository.complete("owner-a", a, "result-a")).as("still fenced after B completed").isEqualTo(FENCED);
```

In `RevokeRaceIT.java`, the renew race now yields a `RenewalResult`: revoke-first leaves the claim lost, and renew-first leaves it renewed. `assertOldOwnerIsFenced` runs after either order, so it checks only that nothing is renewed. When the owner's own `complete` or `retryOrFail` committed first, revoke found no CLAIMED row, and the row still has that owner and token, so a later renewal correctly reports the claim **ended**, not lost:

```diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RevokeRaceIT.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RevokeRaceIT.java
index 120b98d..b459a67 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RevokeRaceIT.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RevokeRaceIT.java
@@ -73,7 +73,7 @@ class RevokeRaceIT {
 
         assertValidOutcome(write, claim, new Outcome(revoked, written, rows.row(claim.id())));
         if (write == Write.RENEW) {
-            assertThat(written).isEqualTo(Set.of(claim.key()));
+            assertThat(written).isEqualTo(new RenewalResult(Set.of(claim.key()), Set.of(), Set.of()));
         } else {
             assertThat(revoked).isZero();
         }
@@ -90,7 +90,8 @@ class RevokeRaceIT {
 
         assertValidOutcome(write, claim, new Outcome(revoked, written, rows.row(claim.id())));
         assertThat(revoked).isEqualTo(1);
-        assertThat(written).isEqualTo(write == Write.RENEW ? Set.of() : PersistResult.FENCED);
+        assertThat(written).isEqualTo(write == Write.RENEW
+                ? new RenewalResult(Set.of(), Set.of(), Set.of(claim.key())) : PersistResult.FENCED);
         assertOldOwnerIsFenced(claim);
     }
 
@@ -165,7 +166,9 @@ class RevokeRaceIT {
             }
             case RENEW -> {
                 assertThat(outcome.revoked()).isEqualTo(1);
-                assertThat(outcome.written()).isIn(Set.of(), Set.of(claim.key()));
+                RenewalResult renewal = (RenewalResult) outcome.written();
+                assertThat(renewal).isIn(new RenewalResult(Set.of(), Set.of(), Set.of(claim.key())),
+                        new RenewalResult(Set.of(claim.key()), Set.of(), Set.of()));
                 assertRevoked(claim, row);
             }
         }
@@ -186,7 +189,7 @@ class RevokeRaceIT {
     private void assertOldOwnerIsFenced(Claim claim) {
         WorkItems.Row before = rows.row(claim.id());
 
-        assertThat(repository.renew(claim.owner(), List.of(claim.key()))).isEmpty();
+        assertThat(repository.renew(claim.owner(), List.of(claim.key())).renewed()).isEmpty();
         repository.complete(claim.owner(), claim.key(), "late");
         repository.retryOrFail(claim.owner(), claim.key(), "late");
 
@@ -195,7 +198,7 @@ class RevokeRaceIT {
 
     private static String order(Write write, Outcome outcome) {
         if (write == Write.RENEW) {
-            return outcome.written().equals(Set.of()) ? "revoke first" : "renew first";
+            return ((RenewalResult) outcome.written()).renewed().isEmpty() ? "revoke first" : "renew first";
         }
         return outcome.revoked() == 1 ? "revoke first" : "owner write first";
     }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test-compile`
Expected: compilation failures (`cannot find symbol`) in all three ITs: `RenewalResult` and its accessors do not exist yet.

- [ ] **Step 3: Implement RenewalResult and the read-back**

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/RenewalResult.java`:

```java
package hle.org.workqueue.engine;

import java.util.Set;

/**
 * What one renewal round found (spec §5.3). Every requested claim is in exactly one set: {@code renewed} has a new
 * lease; {@code ended} was already ended by this owner's own complete or retryOrFail, because its task finished after
 * the round took its snapshot; {@code lost} is no longer this owner's, because it was swept, revoked or re-claimed.
 */
public record RenewalResult(Set<ClaimKey> renewed, Set<ClaimKey> ended, Set<ClaimKey> lost) {

    static final RenewalResult NOTHING = new RenewalResult(Set.of(), Set.of(), Set.of());

    public RenewalResult {
        renewed = Set.copyOf(renewed);
        ended = Set.copyOf(ended);
        lost = Set.copyOf(lost);
    }
}
```

Apply this change to `WorkItemRepository.java`. `renew` now builds its pairs through `claimKeys`, which the read-back reuses, and it adds the `java.util.HashSet` import:

```diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java
index f5a70e9..0aea587 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java
@@ -16,6 +16,7 @@ import java.time.temporal.ChronoUnit;
 import java.util.ArrayList;
 import java.util.Collection;
 import java.util.HashMap;
+import java.util.HashSet;
 import java.util.List;
 import java.util.Locale;
 import java.util.Map;
@@ -105,34 +106,39 @@ public class WorkItemRepository {
 
     /**
      * One renewal round (spec §5.3): pushes the lease of every listed claim that is still CLAIMED by
-     * {@code owner} with the same token, in one statement. A requested claim missing from the result is lost.
+     * {@code owner} with the same token, in one statement. In the same transaction, a claim it did not renew is
+     * reported ended if this owner's own complete or retryOrFail already ended it (the row still has this owner and
+     * token but is no longer CLAIMED), and lost otherwise.
      */
-    public Set<ClaimKey> renew(String owner, Collection<ClaimKey> claims) {
+    public RenewalResult renew(String owner, Collection<ClaimKey> claims) {
         requireOwner(owner);
-        if (claims.isEmpty()) {
-            return Set.of();
+        Set<ClaimKey> requested = Set.copyOf(claims);
+        if (requested.isEmpty()) {
+            return RenewalResult.NOTHING;
         }
-        Map<String, Object> params = new HashMap<>();
-        params.put("owner", owner);
-        params.put("leaseSeconds", leaseSeconds());
-        StringJoiner pairs = new StringJoiner(" OR ");
-        int i = 0;
-        for (ClaimKey claim : claims) {
-            pairs.add("(ID = :id" + i + " AND CLAIM_TOKEN = :token" + i + ")");
-            params.put("id" + i, claim.id());
-            params.put("token" + i, claim.token());
-            i++;
-        }
-        return inTransaction(jdbc -> jdbc.sql("""
-                SELECT ID, CLAIM_TOKEN FROM FINAL TABLE (
-                  UPDATE WORK_ITEM
-                     SET AVAILABLE_AT = CURRENT TIMESTAMP + (CAST(:leaseSeconds AS INTEGER)) SECONDS,
-                         UPDATED_AT = CURRENT TIMESTAMP
-                   WHERE STATUS = 'CLAIMED' AND OWNER = :owner AND (%s))
-                """.formatted(pairs))
-                .params(params)
-                .query((rs, rowNum) -> new ClaimKey(rs.getLong("ID"), rs.getLong("CLAIM_TOKEN")))
-                .set());
+        return inTransaction(jdbc -> {
+            Map<String, Object> params = new HashMap<>();
+            params.put("owner", owner);
+            params.put("leaseSeconds", leaseSeconds());
+            Set<ClaimKey> renewed = claimKeys(jdbc, """
+                    SELECT ID, CLAIM_TOKEN FROM FINAL TABLE (
+                      UPDATE WORK_ITEM
+                         SET AVAILABLE_AT = CURRENT TIMESTAMP + (CAST(:leaseSeconds AS INTEGER)) SECONDS,
+                             UPDATED_AT = CURRENT TIMESTAMP
+                       WHERE STATUS = 'CLAIMED' AND OWNER = :owner AND (%s))
+                    """, params, requested);
+            Set<ClaimKey> missing = new HashSet<>(requested);
+            missing.removeAll(renewed);
+            if (missing.isEmpty()) {
+                return new RenewalResult(renewed, Set.of(), Set.of());
+            }
+            Set<ClaimKey> ended = claimKeys(jdbc, """
+                    SELECT ID, CLAIM_TOKEN FROM WORK_ITEM
+                     WHERE STATUS <> 'CLAIMED' AND OWNER = :owner AND (%s)
+                    """, Map.of("owner", owner), missing);
+            missing.removeAll(ended);
+            return new RenewalResult(renewed, ended, missing);
+        });
     }
 
     /** Stores the result of this claim's call and marks the row DONE (fenced, with read-back). */
@@ -342,6 +348,24 @@ public class WorkItemRepository {
         return Math.toIntExact(settings.lease().toSeconds());
     }
 
+    // Runs sql, whose %s is replaced by one (ID, CLAIM_TOKEN) match per claim, and returns the pairs it selects.
+    private static Set<ClaimKey> claimKeys(JdbcClient jdbc, String sql, Map<String, Object> params,
+                                           Collection<ClaimKey> claims) {
+        Map<String, Object> allParams = new HashMap<>(params);
+        StringJoiner pairs = new StringJoiner(" OR ");
+        int i = 0;
+        for (ClaimKey claim : claims) {
+            pairs.add("(ID = :id" + i + " AND CLAIM_TOKEN = :token" + i + ")");
+            allParams.put("id" + i, claim.id());
+            allParams.put("token" + i, claim.token());
+            i++;
+        }
+        return jdbc.sql(sql.formatted(pairs))
+                .params(allParams)
+                .query((rs, rowNum) -> new ClaimKey(rs.getLong("ID"), rs.getLong("CLAIM_TOKEN")))
+                .set();
+    }
+
     static void requireOwner(String owner) {
         Objects.requireNonNull(owner, "owner");
         if (owner.isBlank() || owner.getBytes(StandardCharsets.UTF_8).length > MAX_OWNER_BYTES) {
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl work-queue-engine verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='WorkItemRepositoryIT,StaleCompletionIT,RevokeRaceIT'`
Expected: `Tests run: 37, Failures: 0, Errors: 0` in the Failsafe summary. Then `./mvnw -q -pl work-queue-engine test`. Expected: 352 unit tests, all passing (unchanged: no unit test calls `renew`).

- [ ] **Step 5: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/RenewalResult.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/WorkItemRepositoryIT.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/StaleCompletionIT.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/RevokeRaceIT.java
git commit -m "feat: tell claims their owner ended from lost ones in the renewal round" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: QueueRunner settings and the poll loop

**Files:**
- Modify: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java` (thread-safe; scripted `claim` and `renew`)
- Create: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java`

**Interfaces:**
- Consumes: `ClaimHandle` (its constructor, `register`, `markRunning`, `cancel`, `finish`, `item`, `key`, `isCancelled`, `isEnded`, `toString`); `WorkItemRepository.claim(String, int)` and `requireOwner(String)`; `Diagnostics.describe` (Task 2); `RenewalResult` (Task 3); `ItConfig.properties()`; `ItemProcessor.process` and `ItemProcessor.Settings.from` (one test).
- Produces, all package-private:
  - `final class QueueRunner`, with `QueueRunner(WorkItemRepository, Processor, String owner, Settings)` and the test constructor `QueueRunner(WorkItemRepository, Processor, String owner, Settings, TaskThreads, LongSupplier clock, ConcurrentMap<ClaimKey, ClaimHandle> registry)`.
  - `record QueueRunner.Settings(int concurrency, int claimBatchSize, Duration idlePollInterval, Duration pollBackoffMax, Duration registrationAllowance, Duration renewInterval, Duration renewRetryDelay, Duration maxProcessingTime, Duration supervisorInterval, Duration hungGrace, int hungTaskLimit, Duration shutdownGrace, Duration shutdownCancelWait)`, with `static Settings from(WorkQueueProperties)`. Every duration must be positive and every count at least 1; the message names the component.
  - `interface QueueRunner.Processor { Outcome process(ClaimedItem item, BooleanSupplier cancelled); }`, `interface QueueRunner.TaskThreads { Thread newThread(ClaimHandle handle, Runnable body); }` and `static final TaskThreads VIRTUAL_THREADS`.
  - `Duration pollOnce() throws InterruptedException`, and the accessors `int availablePermits()`, `int inflight()`, `long invariantViolations()` and `long registrationsLate()`.
  - Test support: `ScriptedRepository.thenClaim(ClaimedItem...)`, `thenClaim(Supplier<List<ClaimedItem>>)`, `thenClaimThrow(RuntimeException)`, `thenRenew(Function<Set<ClaimKey>, RenewalResult>)`, `thenRenewLosing(ClaimKey...)`, `thenRenewEnded(ClaimKey...)`, `thenRenewThrow(RuntimeException)`, `List<Integer> claimSizes()` and `List<Set<ClaimKey>> renewRequests()`. An unscripted claim returns nothing, and an unscripted renewal renews everything.

- [ ] **Step 1: Make ScriptedRepository thread-safe and scriptable for claims and renewals**

The poll loop, the renewal loop and task threads call it concurrently. Its scripts and records become concurrent collections, and a step runs outside any lock, so a step that blocks holds up only its own caller. Replace the whole file.

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java`:

```java
package hle.org.workqueue.engine;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toSet;

/**
 * A WorkItemRepository for unit tests that never touches a database. Every operation answers from its own script,
 * in order, and is recorded. An unscripted persist fails the test with an AssertionError; an unscripted claim
 * finds nothing, and an unscripted renewal renews every claim. It is thread-safe: the poll loop, the renewal loop
 * and task threads call it concurrently, and a step runs outside any lock, so one that blocks holds up only its
 * own caller.
 */
class ScriptedRepository extends WorkItemRepository {

    enum Operation { COMPLETE, RETRY_OR_FAIL }

    /** One persist call; {@code value} is the result value of a complete or the error of a retryOrFail. */
    record Write(Operation operation, String owner, ClaimKey claim, String value) {
    }

    private final Deque<Supplier<PersistResult>> persists = new ConcurrentLinkedDeque<>();
    private final List<Write> writes = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<List<ClaimedItem>>> claims = new ConcurrentLinkedDeque<>();
    private final List<Integer> claimSizes = new CopyOnWriteArrayList<>();
    private final Deque<Function<Set<ClaimKey>, RenewalResult>> renewals = new ConcurrentLinkedDeque<>();
    private final List<Set<ClaimKey>> renewRequests = new CopyOnWriteArrayList<>();

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
        persists.add(step);
        return this;
    }

    /** The next claim returns {@code items}, whatever it asked for. */
    ScriptedRepository thenClaim(ClaimedItem... items) {
        List<ClaimedItem> claimed = Arrays.asList(items);   // may hold null, to fail handle construction
        return thenClaim(() -> claimed);
    }

    ScriptedRepository thenClaimThrow(RuntimeException failure) {
        return thenClaim(() -> {
            throw failure;
        });
    }

    /** The next claim runs {@code step}. */
    ScriptedRepository thenClaim(Supplier<List<ClaimedItem>> step) {
        claims.add(step);
        return this;
    }

    /** The next renewal reports these claims lost and renews the rest. */
    ScriptedRepository thenRenewLosing(ClaimKey... lost) {
        Set<ClaimKey> lostSet = Set.of(lost);
        return thenRenew(requested -> new RenewalResult(minus(requested, lostSet), Set.of(), lostSet));
    }

    /** The next renewal reports these claims ended by their own tasks and renews the rest. */
    ScriptedRepository thenRenewEnded(ClaimKey... ended) {
        Set<ClaimKey> endedSet = Set.of(ended);
        return thenRenew(requested -> new RenewalResult(minus(requested, endedSet), endedSet, Set.of()));
    }

    ScriptedRepository thenRenewThrow(RuntimeException failure) {
        return thenRenew(requested -> {
            throw failure;
        });
    }

    /** The next renewal answers with {@code step}, given the claims it was asked to renew. */
    ScriptedRepository thenRenew(Function<Set<ClaimKey>, RenewalResult> step) {
        renewals.add(step);
        return this;
    }

    List<Write> writes() {
        return List.copyOf(writes);
    }

    /** The {@code n} of every claim, in order. */
    List<Integer> claimSizes() {
        return List.copyOf(claimSizes);
    }

    /** The claims every renewal round asked to renew, in order. */
    List<Set<ClaimKey>> renewRequests() {
        return List.copyOf(renewRequests);
    }

    @Override
    public List<ClaimedItem> claim(String owner, int n) {
        claimSizes.add(n);
        Supplier<List<ClaimedItem>> step = claims.poll();
        return step == null ? List.of() : step.get();
    }

    @Override
    public RenewalResult renew(String owner, Collection<ClaimKey> claims) {
        Set<ClaimKey> requested = Set.copyOf(claims);
        renewRequests.add(requested);
        Function<Set<ClaimKey>, RenewalResult> step = renewals.poll();
        return step == null ? new RenewalResult(requested, Set.of(), Set.of()) : step.apply(requested);
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
        Supplier<PersistResult> step = persists.poll();
        if (step == null) {
            throw new AssertionError("unscripted " + write.operation());
        }
        return step.get();
    }

    private static Set<ClaimKey> minus(Set<ClaimKey> requested, Set<ClaimKey> removed) {
        return requested.stream().filter(key -> !removed.contains(key)).collect(toSet());
    }
}
```

Run: `./mvnw -q -pl work-queue-engine test -Dtest=ItemProcessorTest`
Expected: PASS (24 tests). Its persist script behaves as before.

- [ ] **Step 2: Write the failing test**

`Tasks` is the fake processor: each task runs until the test releases it. `recordingThreads()` records every handle that receives a permit, so `assertPermitInvariant()` counts handles that never made it into the registry too. Tasks 5–7 add sections to this file.

`db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java`:

```java
package hle.org.workqueue.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import hle.org.workqueue.engine.ClaimHandle.CancelReason;
import hle.org.workqueue.engine.ScriptedRepository.Write;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static hle.org.workqueue.engine.ScriptedRepository.Operation.COMPLETE;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Spec §11.1 {@code QueueRunnerLifecycleTest}: every scenario ends with the permit invariant (see endEveryTask). */
@Timeout(30)   // a deadlock fails the test instead of hanging the build
class QueueRunnerLifecycleTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String OWNER = "instance-a";
    /**
     * The IT column: concurrency 4, claim-batch-size 20, max-processing-time 25s, registration-allowance 200ms,
     * idle-poll-interval 100ms, poll-backoff-max 2s, renew-interval 1s, supervisor-interval 100ms, hung-grace 2s,
     * hung-task-limit 1, shutdown-grace 2s, shutdown-cancel-wait 1s.
     */
    private static final QueueRunner.Settings SETTINGS = QueueRunner.Settings.from(ItConfig.properties());
    private static final int CONCURRENCY = SETTINGS.concurrency();
    private static final DataAccessResourceFailureException UNREACHABLE =
            new DataAccessResourceFailureException("Db2 unreachable");

    private final ScriptedRepository repository = new ScriptedRepository();
    // The fake clock starts 5s before overflow, so every deadline in these tests wraps around.
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 5 * SECOND);
    // Every handle that received a permit, registered or not, for the permit invariant.
    private final List<ClaimHandle> handles = new CopyOnWriteArrayList<>();
    private final Tasks tasks = new Tasks();
    private final Logger runnerLog = (Logger) LoggerFactory.getLogger(QueueRunner.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private QueueRunner runner = runner(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>());

    @BeforeEach
    void captureLogs() {
        logged.start();
        runnerLog.addAppender(logged);
    }

    @AfterEach
    void endEveryTask() {
        runnerLog.detachAppender(logged);
        tasks.releaseAll();
        await().untilAsserted(() -> assertThat(handles).allMatch(ClaimHandle::isEnded));
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
    }

    // ---- Poll loop and permits (spec §5.2 steps 1–3) ----------------------------------------------------------

    @Test
    void aClaimAsksForEveryFreePermit() throws Exception {
        claimAndStart(item(1, 1), item(2, 1));

        runner.pollOnce();

        assertThat(repository.claimSizes()).containsExactly(4, 2);
        assertThat(runner.inflight()).isEqualTo(2);
        assertThat(runner.availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void aClaimAsksForNoMoreThanTheBatchSize() throws Exception {
        WorkQueueProperties properties = ItConfig.properties();
        properties.setClaimBatchSize(3);
        runner = new QueueRunner(repository, tasks, OWNER, QueueRunner.Settings.from(properties), recordingThreads(),
                now::get, new ConcurrentHashMap<>());

        runner.pollOnce();

        assertThat(repository.claimSizes()).containsExactly(3);
    }

    @Test
    void anEmptyClaimReturnsEveryPermitAndPausesForTheJitteredIdleInterval() throws Exception {
        Duration pause = runner.pollOnce();

        assertThat(pause).isBetween(ofMillis(50), ofMillis(150));
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aPartialClaimStartsItsRowsReturnsTheOtherPermitsAndPollsAgainAtOnce() throws Exception {
        repository.thenClaim(item(1, 1));

        assertThat(runner.pollOnce()).isZero();

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(runner.availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void aFailedClaimRegistersNothingReturnsEveryPermitAndBacksOffExponentially() throws Exception {
        List<Duration> pauses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            repository.thenClaimThrow(UNREACHABLE);
            pauses.add(runner.pollOnce());
            assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
        }

        assertThat(pauses).containsExactly(ofMillis(100), ofMillis(200), ofMillis(400), ofMillis(800),
                ofMillis(1600), ofSeconds(2), ofSeconds(2));
        assertThat(handles).isEmpty();
        assertThat(runner.inflight()).isZero();
    }

    @Test
    void aClaimThatSucceedsResetsTheBackoff() throws Exception {
        repository.thenClaimThrow(UNREACHABLE).thenClaimThrow(UNREACHABLE).thenClaim().thenClaimThrow(UNREACHABLE);
        runner.pollOnce();
        runner.pollOnce();
        runner.pollOnce();

        assertThat(runner.pollOnce()).isEqualTo(ofMillis(100));
    }

    @Test
    void aFailedClaimLogsOnlyClassNames() throws Exception {
        repository.thenClaimThrow(new DataAccessResourceFailureException("row of order-7:charge"));

        runner.pollOnce();

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Claim by owner instance-a failed; nothing claimed, next claim in PT0.1S: "
                            + "org.springframework.dao.DataAccessResourceFailureException");
        });
    }

    @Test
    void anExceptionConstructingOneRowsHandleLeavesTheEarlierRowsRunningAndReturnsTheOtherPermits() {
        repository.thenClaim(item(1, 1), null, item(3, 1), item(4, 1));   // a null row fails the handle's constructor

        assertThatThrownBy(runner::pollOnce).isInstanceOf(NullPointerException.class);

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(handles).hasSize(1);
        assertThat(runner.availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void anExceptionCreatingOneRowsThreadLeavesTheEarlierRowsRunningAndReturnsTheOtherPermits() {
        runner = runner(tasks, (handle, body) -> {
            if (handle.key().id() == 2) {
                throw new IllegalStateException("no thread");
            }
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThatThrownBy(runner::pollOnce).isInstanceOf(IllegalStateException.class);

        await().until(() -> tasks.started().equals(List.of(key(1, 1))));
        assertThat(runner.availablePermits()).isEqualTo(3);
        assertPermitInvariant();
    }

    @Test
    void aRegistrationThatThrowsFinishesOnlyThatHandle() throws Exception {
        runner = runner(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>() {
            @Override
            public ClaimHandle putIfAbsent(ClaimKey key, ClaimHandle value) {
                if (key.id() == 2) {
                    throw new IllegalStateException("registry broken");
                }
                return super.putIfAbsent(key, value);
            }
        });
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThat(runner.pollOnce()).isZero();

        await().until(() -> Set.copyOf(tasks.started()).equals(Set.of(key(1, 1), key(3, 1))));
        assertThat(handle(key(2, 1)).isEnded()).isTrue();
        assertThat(runner.inflight()).isEqualTo(2);
        assertThat(runner.availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void aKeyCollisionFinishesTheNewHandleAndCountsAnInvariantViolation() throws Exception {
        claimAndStart(item(1, 5));
        repository.thenClaim(item(1, 5));

        runner.pollOnce();

        assertThat(runner.invariantViolations()).isEqualTo(1);
        assertThat(handles).extracting(ClaimHandle::isEnded).containsExactly(false, true);
        assertThat(tasks.started()).containsExactly(key(1, 5));
        assertThat(runner.inflight()).isEqualTo(1);
        assertPermitInvariant();
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Invariant violation: claim ClaimHandle[id=1, token=5]"
                    + " of owner instance-a is already registered; not started");
        });
    }

    @Test
    void aThreadThatFailsToStartFinishesOnlyItsHandle() throws Exception {
        runner = runner(tasks, (handle, body) -> {
            if (handle.key().id() == 2) {
                handles.add(handle);
                return new Thread(body) {
                    @Override
                    public void start() {
                        throw new OutOfMemoryError("unable to create native thread");
                    }
                };
            }
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1), item(3, 1));

        assertThat(runner.pollOnce()).isZero();

        await().until(() -> Set.copyOf(tasks.started()).equals(Set.of(key(1, 1), key(3, 1))));
        assertThat(handle(key(2, 1)).isEnded()).isTrue();
        assertThat(runner.inflight()).isEqualTo(2);
        assertThat(runner.availablePermits()).isEqualTo(2);
        assertPermitInvariant();
    }

    @Test
    void anInterruptWhileWaitingForAPermitLeavesEveryPermitWithItsHandle() throws Exception {
        claimAndStart(item(1, 1), item(2, 1), item(3, 1), item(4, 1));
        FutureTask<Duration> poll = new FutureTask<>(runner::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        await().until(() -> poller.getState() == Thread.State.WAITING);

        poller.interrupt();

        assertThatThrownBy(() -> poll.get(10, SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        assertThat(repository.claimSizes()).containsExactly(4);
        assertThat(runner.availablePermits()).isZero();
        assertPermitInvariant();
    }

    @Test
    void anInterruptDuringTheClaimReturnsEveryHeldPermit() throws Exception {
        CountDownLatch claiming = new CountDownLatch(1);
        repository.thenClaim(() -> {
            claiming.countDown();
            try {
                new CountDownLatch(1).await();   // until interrupted
                throw new AssertionError("not interrupted");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataAccessResourceFailureException("interrupted during the claim", e);
            }
        });
        FutureTask<Duration> poll = new FutureTask<>(runner::pollOnce);
        Thread poller = Thread.ofVirtual().start(poll);
        claiming.await();

        poller.interrupt();

        assertThat(poll.get(10, SECONDS)).as("a failed claim backs off").isEqualTo(ofMillis(100));
        assertThat(handles).isEmpty();
        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    void aHandleCancelledBeforeItsBodyRunsIsNeverProcessedAndFinishesOnce() throws Exception {
        runner = runner(tasks, (handle, body) -> recordingThreads().newThread(handle, () -> {
            handle.cancel(CancelReason.SHUTDOWN, now.get());
            body.run();
        }), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        runner.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(tasks.started()).isEmpty();
        await().untilAsserted(this::assertPermitInvariant);
    }

    @Test
    void aRegistrationLaterThanTheAllowanceIsCounted() throws Exception {
        runner = runner(tasks, (handle, body) -> {
            // Row 1 registers exactly registration-allowance after the claim returned, row 2 a nanosecond later.
            now.addAndGet(handle.key().id() == 1 ? ofMillis(200).toNanos() : 1);
            return recordingThreads().newThread(handle, body);
        }, now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1), item(2, 1));

        runner.pollOnce();

        assertThat(runner.registrationsLate()).isEqualTo(1);
    }

    @Test
    void aTaskThatThrowsIsLoggedByClassNameOnlyAndStillFinishes() throws Exception {
        runner = runner((item, cancelled) -> {
            throw new StackOverflowError("payload-1");
        }, recordingThreads(), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1));

        runner.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        await().untilAsserted(this::assertPermitInvariant);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).as("the raw throwable is not logged").isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Task for claim ClaimHandle[id=1, token=1] of owner"
                    + " instance-a failed: java.lang.StackOverflowError");
        });
    }

    @Test
    void theItemProcessorRunsAClaimedRowToCompletion() throws Exception {
        ItemProcessor processor = new ItemProcessor(repository,
                (key, token, payload, timeout) -> new CallResult("receipt-" + payload), OWNER, "it",
                ItemProcessor.Settings.from(ItConfig.properties()));
        runner = runner(processor::process, recordingThreads(), now::get, new ConcurrentHashMap<>());
        repository.thenClaim(item(1, 1)).thenReturn(PersistResult.DONE);

        runner.pollOnce();

        await().until(() -> handle(key(1, 1)).isEnded());
        assertThat(repository.writes()).containsExactly(new Write(COMPLETE, OWNER, key(1, 1), "receipt-payload-1"));
    }

    // ---- Settings -------------------------------------------------------------------------------------------

    @Test
    void settingsComeFromTheProperties() {
        assertThat(QueueRunner.Settings.from(new WorkQueueProperties())).isEqualTo(new QueueRunner.Settings(16, 20,
                ofSeconds(1), ofSeconds(30), ofSeconds(1), ofSeconds(15), ofSeconds(1), ofSeconds(120), ofSeconds(1),
                ofSeconds(30), 4, ofSeconds(20), ofSeconds(5)));
    }

    @Test
    void settingsRejectANonPositiveDurationOrACountBelowOne() {
        Map<String, Consumer<WorkQueueProperties>> invalid = new LinkedHashMap<>();
        invalid.put("concurrency", properties -> properties.setConcurrency(0));
        invalid.put("claimBatchSize", properties -> properties.setClaimBatchSize(0));
        invalid.put("hungTaskLimit", properties -> properties.setHungTaskLimit(0));
        invalid.put("idlePollInterval", properties -> properties.setIdlePollInterval(Duration.ZERO));
        invalid.put("pollBackoffMax", properties -> properties.setPollBackoffMax(Duration.ZERO));
        invalid.put("supervisorInterval", properties -> properties.setSupervisorInterval(Duration.ZERO));
        invalid.put("hungGrace", properties -> properties.setHungGrace(ofSeconds(-1)));
        invalid.put("shutdownGrace", properties -> properties.setShutdownGrace(Duration.ZERO));
        invalid.put("shutdownCancelWait", properties -> properties.setShutdownCancelWait(Duration.ZERO));

        invalid.forEach((name, change) -> {
            WorkQueueProperties properties = ItConfig.properties();
            change.accept(properties);
            assertThatThrownBy(() -> QueueRunner.Settings.from(properties))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        });
    }

    @Test
    void rejectsAnInvalidOwner() {
        assertThatThrownBy(() -> new QueueRunner(repository, tasks, " ", SETTINGS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Helpers --------------------------------------------------------------------------------------------

    /** Spec §5.2 with held = 0: call it only while no pollOnce is running. */
    private void assertPermitInvariant() {
        long notEnded = handles.stream().filter(handle -> !handle.isEnded()).count();
        assertThat(runner.availablePermits() + notEnded).as("permits.available + handles not ended")
                .isEqualTo(CONCURRENCY);
    }

    private void claimAndStart(ClaimedItem... items) throws InterruptedException {
        repository.thenClaim(items);
        runner.pollOnce();
        List<ClaimKey> keys = Arrays.stream(items).map(ClaimedItem::key).toList();
        await().until(() -> tasks.started().containsAll(keys));
    }

    private QueueRunner runner(QueueRunner.Processor processor, QueueRunner.TaskThreads threads, LongSupplier clock,
                               ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        return new QueueRunner(repository, processor, OWNER, SETTINGS, threads, clock, registry);
    }

    // Production's virtual threads, recording every handle that receives a thread and with it a permit.
    private QueueRunner.TaskThreads recordingThreads() {
        return (handle, body) -> {
            handles.add(handle);
            return QueueRunner.VIRTUAL_THREADS.newThread(handle, body);
        };
    }

    private ClaimHandle handle(ClaimKey key) {
        return handles.stream().filter(handle -> handle.key().equals(key)).findFirst().orElseThrow();
    }

    private static ClaimedItem item(long id, long token) {
        return new ClaimedItem(id, "op-" + id, "payload-" + id, token);
    }

    private static ClaimKey key(long id, long token) {
        return new ClaimKey(id, token);
    }

    /**
     * Tasks that run until released, then end with their scripted outcome (COMPLETED by default). An interrupt
     * ends a task INTERRUPTED at once, unless the tasks ignore interrupts.
     */
    private static final class Tasks implements QueueRunner.Processor {

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
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: a compilation failure, `cannot find symbol` for `QueueRunner`.

- [ ] **Step 4: Implement the settings and the poll loop**

Read the comments on `registerAndStart` closely. The handle owns its permit from `held--` onward. Every failure before a successful start therefore ends it through `finish()`, and `thread.start()` is the last statement in that `try`.

`db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java`:

```java
package hle.org.workqueue.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Runs the queue on one instance (spec §5.2): the poll loop claims rows and starts one virtual thread per claim, the
 * renewal loop keeps the claims' leases, and the DB-free supervisor enforces deadlines and detects hung tasks. It
 * alone creates handles, starts their threads, and holds the registry and the permits; the permit invariant
 * {@code permits.available + handles not ended + held = concurrency} holds whenever the poll loop is between
 * iterations. Times are {@code System.nanoTime()} readings from the injected clock, compared overflow-safely.
 */
final class QueueRunner {

    /** The settings the loops use; spec §6 describes each. */
    record Settings(int concurrency, int claimBatchSize, Duration idlePollInterval, Duration pollBackoffMax,
                    Duration registrationAllowance, Duration renewInterval, Duration renewRetryDelay,
                    Duration maxProcessingTime, Duration supervisorInterval, Duration hungGrace, int hungTaskLimit,
                    Duration shutdownGrace, Duration shutdownCancelWait) {

        Settings {
            requireAtLeastOne("concurrency", concurrency);
            requireAtLeastOne("claimBatchSize", claimBatchSize);
            requireAtLeastOne("hungTaskLimit", hungTaskLimit);
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
        }

        static Settings from(WorkQueueProperties properties) {
            return new Settings(properties.getConcurrency(), properties.getClaimBatchSize(),
                    properties.getIdlePollInterval(), properties.getPollBackoffMax(),
                    properties.getRegistrationAllowance(), properties.getRenewInterval(),
                    properties.getRenewRetryDelay(), properties.getMaxProcessingTime(),
                    properties.getSupervisorInterval(), properties.getHungGrace(), properties.getHungTaskLimit(),
                    properties.getShutdownGrace(), properties.getShutdownCancelWait());
        }

        private static void requireAtLeastOne(String name, int value) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be at least 1: " + value);
            }
        }
    }

    /** Processes one claimed row: {@link ItemProcessor#process} in production. */
    @FunctionalInterface
    interface Processor {
        Outcome process(ClaimedItem item, BooleanSupplier cancelled);
    }

    /** Creates, without starting it, the thread that runs one claim's body. */
    @FunctionalInterface
    interface TaskThreads {
        Thread newThread(ClaimHandle handle, Runnable body);
    }

    /** One virtual thread per claim, named after its row and token (spec §5.2 execution model). */
    static final TaskThreads VIRTUAL_THREADS = (handle, body) -> Thread.ofVirtual()
            .name("workqueue-task-" + handle.key().id() + "-" + handle.key().token())
            .unstarted(body);

    private static final Logger log = LoggerFactory.getLogger(QueueRunner.class);

    private final WorkItemRepository repository;
    private final Processor processor;
    private final String owner;
    private final Settings settings;
    private final TaskThreads taskThreads;
    private final LongSupplier clock;
    private final ConcurrentMap<ClaimKey, ClaimHandle> registry;
    private final Semaphore permits;

    private final AtomicLong invariantViolations = new AtomicLong();
    private final AtomicLong registrationsLate = new AtomicLong();

    // Only the poll loop reads or writes this.
    private int claimFailures;

    QueueRunner(WorkItemRepository repository, Processor processor, String owner, Settings settings) {
        this(repository, processor, owner, settings, VIRTUAL_THREADS, System::nanoTime, new ConcurrentHashMap<>());
    }

    /** For tests: the task threads, the clock and the registry are injectable. */
    QueueRunner(WorkItemRepository repository, Processor processor, String owner, Settings settings,
                TaskThreads taskThreads, LongSupplier clock, ConcurrentMap<ClaimKey, ClaimHandle> registry) {
        WorkItemRepository.requireOwner(owner);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.owner = owner;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.taskThreads = Objects.requireNonNull(taskThreads, "taskThreads");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permits = new Semaphore(settings.concurrency());
    }

    // ---- Poll loop -------------------------------------------------------------------------------------------

    /**
     * One poll-loop iteration (spec §5.2 steps 1–3): acquire permits, claim that many rows, then transfer one permit
     * to each claimed row's handle, register it and start its thread. Every permit the loop still holds is returned
     * on every path. Returns the pause before the next iteration: none after a claim that found rows, the jittered
     * idle interval after an empty one, a growing backoff after a failed one.
     *
     * @throws InterruptedException if interrupted while waiting for a permit
     */
    Duration pollOnce() throws InterruptedException {
        int held = 0;
        try {
            permits.acquire();
            held = 1;
            while (held < settings.claimBatchSize() && permits.tryAcquire()) {
                held++;
            }
            List<ClaimedItem> claimed;
            try {
                claimed = repository.claim(owner, held);
            } catch (RuntimeException e) {
                // The outcome is uncertain: rows may have committed. They are never registered, so they expire
                // unrenewed with their attempt consumed (spec §5.2).
                Duration pause = backoff();
                log.warn("Claim by owner {} failed; nothing claimed, next claim in {}: {}", owner, pause,
                        Diagnostics.describe(e));
                return pause;
            }
            long claimedAt = clock.getAsLong();
            claimFailures = 0;
            for (ClaimedItem item : claimed) {
                ClaimHandle handle = new ClaimHandle(item, claimedAt, settings.maxProcessingTime(), registry, permits);
                Thread thread = Objects.requireNonNull(taskThreads.newThread(handle, () -> runTask(handle)), "thread");
                held--;   // the transfer: from here on the handle owns this permit
                registerAndStart(handle, thread, claimedAt);
            }
            return claimed.isEmpty() ? idlePause() : Duration.ZERO;
        } finally {
            permits.release(held);
        }
    }

    // Spec §5.2 step 3. Every failure between the transfer and a successful start ends the handle through finish().
    // Nothing may follow thread.start() in this try: once the thread runs, only its own body may finish the handle.
    private void registerAndStart(ClaimHandle handle, Thread thread, long claimedAt) {
        try {
            if (!handle.register()) {
                invariantViolations.incrementAndGet();
                log.error("Invariant violation: claim {} of owner {} is already registered; not started", handle,
                        owner);
                handle.finish();
                return;
            }
            if (clock.getAsLong() - claimedAt > settings.registrationAllowance().toNanos()) {
                registrationsLate.incrementAndGet();
            }
            thread.start();
        } catch (Throwable t) {
            handle.finish();
            log.error("Could not start claim {} of owner {}: {}", handle, owner, Diagnostics.describe(t));
        }
    }

    // Spec §5.2 step 4. Catches every Throwable: an uncaught one would reach the thread's default handler, which
    // prints its message (spec §5.4).
    private void runTask(ClaimHandle handle) {
        try {
            if (handle.markRunning()) {
                Outcome outcome = processor.process(handle.item(), handle::isCancelled);
                log.debug("Claim {} of owner {} ended {}", handle, owner, outcome);
            }
        } catch (Throwable t) {
            log.error("Task for claim {} of owner {} failed: {}", handle, owner, Diagnostics.describe(t));
        } finally {
            handle.finish();
        }
    }

    // The idle interval ± 50%, so idle instances do not poll in step.
    private Duration idlePause() {
        long idle = settings.idlePollInterval().toNanos();
        return Duration.ofNanos(idle / 2 + ThreadLocalRandom.current().nextLong(idle + 1));
    }

    // idle-poll-interval, doubled after every consecutive failure, capped at poll-backoff-max.
    private Duration backoff() {
        Duration pause = settings.idlePollInterval();
        for (int i = 0; i < claimFailures && pause.compareTo(settings.pollBackoffMax()) < 0; i++) {
            pause = pause.multipliedBy(2);
        }
        claimFailures++;
        return pause.compareTo(settings.pollBackoffMax()) < 0 ? pause : settings.pollBackoffMax();
    }

    // ---- State for health and metrics (slice 2.6) and tests --------------------------------------------------

    int availablePermits() {
        return permits.availablePermits();
    }

    /** Registered handles: running, or cancelled and not yet ended. */
    int inflight() {
        return registry.size();
    }

    long invariantViolations() {
        return invariantViolations.get();
    }

    long registrationsLate() {
        return registrationsLate.get();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: PASS (21 tests, about 6s). Then run `./mvnw -q -pl work-queue-engine test`. Expected: 373 tests, all passing.

- [ ] **Step 6: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/ScriptedRepository.java \
        db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
git commit -m "feat: add QueueRunner's poll loop with its permit rule" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The renewal round

**Files:**
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java`

**Interfaces:**
- Consumes: `WorkItemRepository.renew(String, Collection<ClaimKey>)` returning `RenewalResult` (Task 3); `ClaimHandle.isRenewable(long)` and `cancel(CancelReason.LOST, long)`; `ScriptedRepository.thenRenewLosing`, `thenRenewEnded`, `thenRenewThrow` and `renewRequests()` (Task 4).
- Produces: `boolean QueueRunner.renewOnce()`, which Task 7's renewal loop repeats on `RenewalSchedule`, and `long claimsLost()`.

- [ ] **Step 1: Write the failing tests**

Add the renewal section to `QueueRunnerLifecycleTest`, between the poll-loop section and the settings section:

```diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
index 403129c..3fce88e 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
@@ -361,6 +361,113 @@ class QueueRunnerLifecycleTest {
         assertThat(repository.writes()).containsExactly(new Write(COMPLETE, OWNER, key(1, 1), "receipt-payload-1"));
     }
 
+    // ---- Renewal (spec §5.3) --------------------------------------------------------------------------------
+
+    @Test
+    void aRoundWithNothingToRenewIsSkippedAndSucceeds() {
+        assertThat(runner.renewOnce()).isTrue();
+
+        assertThat(repository.renewRequests()).isEmpty();
+    }
+
+    @Test
+    void theRowsOfAnUncertainClaimAreNeverRenewedOrProcessed() throws Exception {
+        // The claim's rows committed, but its commit acknowledgement was lost: the repository throws.
+        repository.thenClaim(() -> {
+            throw new DataAccessResourceFailureException("commit acknowledgement lost");
+        });
+
+        runner.pollOnce();
+
+        assertThat(runner.renewOnce()).isTrue();
+        assertThat(repository.renewRequests()).as("nothing to renew").isEmpty();
+        assertThat(tasks.started()).as("nothing called").isEmpty();
+        assertThat(handles).isEmpty();
+        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY);
+    }
+
+    @Test
+    void aRoundRenewsEveryRenewableClaimInOneRequest() throws Exception {
+        claimAndStart(item(1, 1), item(2, 1));
+
+        assertThat(runner.renewOnce()).isTrue();
+
+        assertThat(repository.renewRequests()).containsExactly(Set.of(key(1, 1), key(2, 1)));
+    }
+
+    @Test
+    void aRoundSkipsEndedCancelledAndPastDeadlineClaims() throws Exception {
+        tasks.ignoreInterrupts();
+        tasks.endWith(key(1, 1), Outcome.ABANDONED);
+        claimAndStart(item(1, 1), item(2, 1), item(3, 1));
+        now.addAndGet(10 * SECOND);
+        claimAndStart(item(4, 1));
+        repository.thenRenewLosing(key(2, 1));
+        runner.renewOnce();                          // claim 2 is lost: cancelled, but its task keeps running
+        tasks.release(key(1, 1));                    // claim 1 is abandoned: ended
+        await().until(() -> handle(key(1, 1)).isEnded());
+        now.addAndGet(15 * SECOND);                  // claim 3's deadline; no supervisor pass has cancelled it
+
+        runner.renewOnce();
+
+        assertThat(handle(key(2, 1)).isEnded()).isFalse();
+        assertThat(repository.renewRequests()).containsExactly(
+                Set.of(key(1, 1), key(2, 1), key(3, 1), key(4, 1)), Set.of(key(4, 1)));
+    }
+
+    @Test
+    void aClaimReportedLostIsCountedAndCancelled() throws Exception {
+        claimAndStart(item(1, 1), item(2, 1));
+        repository.thenRenewLosing(key(1, 1));
+
+        assertThat(runner.renewOnce()).isTrue();
+
+        assertThat(runner.claimsLost()).isEqualTo(1);
+        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.LOST);
+        assertThat(handle(key(2, 1)).isCancelled()).isFalse();
+        await().until(() -> handle(key(1, 1)).isEnded());   // its task was interrupted
+        runner.renewOnce();
+        assertThat(repository.renewRequests().getLast()).containsExactly(key(2, 1));
+        await().untilAsserted(this::assertPermitInvariant);
+    }
+
+    @Test
+    void aClaimItsOwnTaskAlreadyEndedIsNeitherLostNorCancelled() throws Exception {
+        claimAndStart(item(1, 1));
+        repository.thenRenewEnded(key(1, 1));   // its task persisted after the round took its snapshot
+
+        assertThat(runner.renewOnce()).isTrue();
+
+        assertThat(runner.claimsLost()).isZero();
+        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
+    }
+
+    @Test
+    void aFailedRoundCancelsNothingAndTheNextRoundRenewsTheSameClaims() throws Exception {
+        claimAndStart(item(1, 1));
+        repository.thenRenewThrow(UNREACHABLE);
+
+        assertThat(runner.renewOnce()).isFalse();
+        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
+        assertThat(runner.renewOnce()).isTrue();
+
+        assertThat(repository.renewRequests()).containsExactly(Set.of(key(1, 1)), Set.of(key(1, 1)));
+    }
+
+    @Test
+    void anOldHandleEndingAfterItsRowWasReclaimedLeavesTheNewClaimRegisteredAndRenewed() throws Exception {
+        claimAndStart(item(7, 1));
+        claimAndStart(item(7, 2));   // the same row, re-claimed by this owner after its lease expired
+        tasks.release(key(7, 1));
+        await().until(() -> handle(key(7, 1)).isEnded());
+
+        runner.renewOnce();
+
+        assertThat(runner.inflight()).isEqualTo(1);
+        assertThat(repository.renewRequests()).containsExactly(Set.of(key(7, 2)));
+        await().untilAsserted(this::assertPermitInvariant);
+    }
+
     // ---- Settings -------------------------------------------------------------------------------------------
 
     @Test
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: a compilation failure, `cannot find symbol` for the methods `renewOnce` and `claimsLost`.

- [ ] **Step 3: Implement the renewal round**

The snapshot is taken at the round's start, and only handles renewable then are in it (spec §5.3). A claim reported lost is counted and cancelled. A claim reported ended is left alone: its own task already persisted its outcome.

```diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
index 9f0c9fd..c403082 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
@@ -1,10 +1,13 @@
 package hle.org.workqueue.engine;
 
+import hle.org.workqueue.engine.ClaimHandle.CancelReason;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 
 import java.time.Duration;
+import java.util.HashMap;
 import java.util.List;
+import java.util.Map;
 import java.util.Objects;
 import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.ConcurrentMap;
@@ -91,6 +94,7 @@ final class QueueRunner {
 
     private final AtomicLong invariantViolations = new AtomicLong();
     private final AtomicLong registrationsLate = new AtomicLong();
+    private final AtomicLong claimsLost = new AtomicLong();
 
     // Only the poll loop reads or writes this.
     private int claimFailures;
@@ -208,6 +212,40 @@ final class QueueRunner {
         return pause.compareTo(settings.pollBackoffMax()) < 0 ? pause : settings.pollBackoffMax();
     }
 
+    // ---- Renewal loop ----------------------------------------------------------------------------------------
+
+    /**
+     * One renewal round (spec §5.3) over a snapshot, taken at its start, of the handles that are renewable then.
+     * Every claim the round reports lost is counted and cancelled; a claim its own task already ended is neither.
+     * Returns whether the round succeeded; a round with nothing to renew is skipped and succeeds.
+     */
+    boolean renewOnce() {
+        long start = clock.getAsLong();
+        Map<ClaimKey, ClaimHandle> snapshot = new HashMap<>();
+        for (ClaimHandle handle : registry.values()) {
+            if (handle.isRenewable(start)) {
+                snapshot.put(handle.key(), handle);
+            }
+        }
+        if (snapshot.isEmpty()) {
+            return true;
+        }
+        RenewalResult result;
+        try {
+            result = repository.renew(owner, snapshot.keySet());
+        } catch (RuntimeException e) {
+            log.warn("Renewal of {} claims of owner {} failed: {}", snapshot.size(), owner, Diagnostics.describe(e));
+            return false;
+        }
+        long now = clock.getAsLong();
+        for (ClaimKey key : result.lost()) {
+            claimsLost.incrementAndGet();
+            log.warn("Claim {} of owner {} was lost; cancelling it", key, owner);
+            snapshot.get(key).cancel(CancelReason.LOST, now);
+        }
+        return true;
+    }
+
     // ---- State for health and metrics (slice 2.6) and tests --------------------------------------------------
 
     int availablePermits() {
@@ -226,4 +264,8 @@ final class QueueRunner {
     long registrationsLate() {
         return registrationsLate.get();
     }
+
+    long claimsLost() {
+        return claimsLost.get();
+    }
 }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: PASS (29 tests). Then `./mvnw -q -pl work-queue-engine test`. Expected: 381 tests, all passing.

- [ ] **Step 5: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
git commit -m "feat: add QueueRunner's renewal round" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The supervisor

**Files:**
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java`
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java`

**Interfaces:**
- Consumes: `ClaimHandle.isPastDeadline(long)`, `cancel(CancelReason.DEADLINE, long)`, `markHungIfOverdue(long, Duration)`, `isHung()`, `cancelReason()` and `runnerStackTrace()`.
- Produces: `void QueueRunner.superviseOnce()`, which Task 7's supervisor loop repeats every `supervisor-interval`, and `int hungTasks()`, which slice 2.6's `tasks.hung` gauge and liveness read. `pollOnce()` stops claiming while `hungTasks() ≥ hung-task-limit`.

- [ ] **Step 1: Write the failing tests**

Add the supervisor section to `QueueRunnerLifecycleTest`, after the renewal section:

```diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
index 3fce88e..ecf12a1 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
@@ -468,6 +468,86 @@ class QueueRunnerLifecycleTest {
         await().untilAsserted(this::assertPermitInvariant);
     }
 
+    // ---- Supervisor (spec §5.2) -----------------------------------------------------------------------------
+
+    @Test
+    void theSupervisorCancelsAClaimAtItsDeadlineAndNotBefore() throws Exception {
+        claimAndStart(item(1, 1));
+        now.addAndGet(25 * SECOND - 1);
+        runner.superviseOnce();
+        assertThat(handle(key(1, 1)).isCancelled()).isFalse();
+
+        now.addAndGet(1);
+        runner.superviseOnce();
+
+        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.DEADLINE);
+        await().until(() -> handle(key(1, 1)).isEnded());   // its task was interrupted
+        await().untilAsserted(this::assertPermitInvariant);
+    }
+
+    @Test
+    void aCancelledTaskThatIgnoresInterruptsIsReportedHungOnceAndKeepsItsPermit() throws Exception {
+        tasks.ignoreInterrupts();
+        claimAndStart(item(1, 1));
+        now.addAndGet(25 * SECOND);
+        runner.superviseOnce();                     // cancelled at its deadline
+        now.addAndGet(2 * SECOND - 1);
+        runner.superviseOnce();
+        assertThat(runner.hungTasks()).isZero();
+
+        now.addAndGet(1);                           // hung-grace after the cancel
+        runner.superviseOnce();
+        runner.superviseOnce();
+
+        assertThat(runner.hungTasks()).isEqualTo(1);
+        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY - 1);
+        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.ERROR).singleElement()
+                .extracting(ILoggingEvent::getFormattedMessage).asString()
+                .startsWith("Claim ClaimHandle[id=1, token=1] of owner instance-a is hung: still running PT2S after"
+                        + " it was cancelled (DEADLINE)")
+                .contains("\tat ");
+        tasks.releaseAll();
+        await().until(() -> handle(key(1, 1)).isEnded());
+        assertThat(runner.hungTasks()).isZero();
+    }
+
+    @Test
+    void reachingTheHungTaskLimitStopsClaimingUntilTheHungTaskEnds() throws Exception {
+        tasks.ignoreInterrupts();
+        claimAndStart(item(1, 1));
+        now.addAndGet(25 * SECOND);
+        runner.superviseOnce();
+        now.addAndGet(2 * SECOND);
+        runner.superviseOnce();
+        assertThat(runner.hungTasks()).as("hung-task-limit is 1").isEqualTo(1);
+
+        assertThat(runner.pollOnce()).as("the supervisor interval").isEqualTo(ofMillis(100));
+        assertThat(repository.claimSizes()).containsExactly(4);
+        assertThat(runner.availablePermits()).isEqualTo(CONCURRENCY - 1);
+
+        tasks.releaseAll();
+        await().until(() -> handle(key(1, 1)).isEnded());
+        runner.pollOnce();
+        assertThat(repository.claimSizes()).containsExactly(4, 4);
+    }
+
+    @Test
+    void cancelledTasksThatIgnoreInterruptsStillCountAgainstConcurrency() throws Exception {
+        tasks.ignoreInterrupts();
+        claimAndStart(item(1, 1), item(2, 1), item(3, 1), item(4, 1));
+        now.addAndGet(25 * SECOND);
+        runner.superviseOnce();                     // all four cancelled at their deadline; none ends
+        FutureTask<Duration> poll = new FutureTask<>(runner::pollOnce);
+        Thread poller = Thread.ofVirtual().start(poll);
+        await().until(() -> poller.getState() == Thread.State.WAITING);
+
+        tasks.release(key(1, 1));
+
+        poll.get(10, SECONDS);
+        assertThat(repository.claimSizes()).containsExactly(4, 1);
+        assertThat(tasks.highWater()).isEqualTo(CONCURRENCY);
+    }
+
     // ---- Settings -------------------------------------------------------------------------------------------
 
     @Test
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: a compilation failure, `cannot find symbol` for the methods `superviseOnce` and `hungTasks`.

- [ ] **Step 3: Implement the supervisor and the hung-task pause**

```diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
index c403082..54f72ff 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
@@ -135,6 +135,9 @@ final class QueueRunner {
             while (held < settings.claimBatchSize() && permits.tryAcquire()) {
                 held++;
             }
+            if (claimingPaused()) {
+                return settings.supervisorInterval();
+            }
             List<ClaimedItem> claimed;
             try {
                 claimed = repository.claim(owner, held);
@@ -196,6 +199,10 @@ final class QueueRunner {
         }
     }
 
+    private boolean claimingPaused() {
+        return hungTasks() >= settings.hungTaskLimit();
+    }
+
     // The idle interval ± 50%, so idle instances do not poll in step.
     private Duration idlePause() {
         long idle = settings.idlePollInterval().toNanos();
@@ -246,6 +253,34 @@ final class QueueRunner {
         return true;
     }
 
+    // ---- Supervisor ------------------------------------------------------------------------------------------
+
+    /**
+     * One supervisor pass (spec §5.2): cancels every claim past its deadline, and marks hung every cancelled claim
+     * whose thread is still running hung-grace after the cancel, logging it once with its stack. Never waits on Db2.
+     */
+    void superviseOnce() {
+        long now = clock.getAsLong();
+        for (ClaimHandle handle : registry.values()) {
+            if (handle.isPastDeadline(now) && handle.cancel(CancelReason.DEADLINE, now)) {
+                log.warn("Claim {} of owner {} reached max-processing-time; cancelling it", handle, owner);
+            }
+            if (handle.markHungIfOverdue(now, settings.hungGrace())) {
+                log.error("Claim {} of owner {} is hung: still running {} after it was cancelled ({}); it keeps its"
+                        + " permit until its thread ends{}", handle, owner, settings.hungGrace(),
+                        handle.cancelReason(), stack(handle.runnerStackTrace()));
+            }
+        }
+    }
+
+    private static String stack(StackTraceElement[] frames) {
+        StringBuilder text = new StringBuilder();
+        for (StackTraceElement frame : frames) {
+            text.append(System.lineSeparator()).append("\tat ").append(frame);
+        }
+        return text.toString();
+    }
+
     // ---- State for health and metrics (slice 2.6) and tests --------------------------------------------------
 
     int availablePermits() {
@@ -257,6 +292,16 @@ final class QueueRunner {
         return registry.size();
     }
 
+    int hungTasks() {
+        int hung = 0;
+        for (ClaimHandle handle : registry.values()) {
+            if (handle.isHung()) {
+                hung++;
+            }
+        }
+        return hung;
+    }
+
     long invariantViolations() {
         return invariantViolations.get();
     }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: PASS (33 tests). Then `./mvnw -q -pl work-queue-engine test`. Expected: 385 tests, all passing.

- [ ] **Step 5: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
git commit -m "feat: add QueueRunner's supervisor" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The loops, stop and crash

**Files:**
- Modify: `db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java`
- Modify: `db-work-queue/README.md` (status line)
- Test: `db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java`

**Interfaces:**
- Consumes: `RenewalSchedule(Duration interval, Duration retryDelay).next(long start, long end, boolean succeeded)` (Phase 2a); `pollOnce`, `renewOnce` and `superviseOnce` (Tasks 4–6).
- Produces: `QueueRunner implements SmartLifecycle`, with `start()`, `stop()` and `isRunning()`; the package-private `void crash()` (tests only; slice 2.7's `PoisonRowIT` uses it); and `boolean isStopping()`, which slice 2.6's readiness reads.

- [ ] **Step 1: Write the failing tests**

Add the lifecycle section after the supervisor section, and have `endEveryTask` crash the runner first, so no test leaves loops running:

```diff
diff --git a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
index ecf12a1..b7945db 100644
--- a/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
+++ b/db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java
@@ -74,6 +74,7 @@ class QueueRunnerLifecycleTest {
     @AfterEach
     void endEveryTask() {
         runnerLog.detachAppender(logged);
+        runner.crash();
         tasks.releaseAll();
         await().untilAsserted(() -> assertThat(handles).allMatch(ClaimHandle::isEnded));
         await().untilAsserted(this::assertPermitInvariant);
@@ -548,6 +549,109 @@ class QueueRunnerLifecycleTest {
         assertThat(tasks.highWater()).isEqualTo(CONCURRENCY);
     }
 
+    // ---- start, stop and crash (spec §5.2) ------------------------------------------------------------------
+
+    @Test
+    void stopDrainsTheRunningTasksWhileRenewingThemAndClaimsNothingMore() throws Exception {
+        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
+        repository.thenClaim(item(1, 1), item(2, 1));
+        runner.start();
+        await().until(() -> tasks.started().size() == 2);
+        FutureTask<Void> stop = new FutureTask<>(runner::stop, null);
+        Thread.ofVirtual().start(stop);
+        await().until(runner::isStopping);
+        int roundsBefore = repository.renewRequests().size();
+
+        await().until(() -> repository.renewRequests().size() > roundsBefore);   // renewal continues while draining
+        int claims = repository.claimSizes().size();
+        tasks.releaseAll();
+        stop.get(10, SECONDS);
+
+        assertThat(repository.claimSizes()).as("no claim after the poll loop stopped").hasSize(claims);
+        assertThat(handles).noneMatch(ClaimHandle::isCancelled);
+        assertThat(repository.writes()).as("nothing released in Db2").isEmpty();
+        assertThat(runner.isRunning()).isFalse();
+    }
+
+    @Test
+    void stopCancelsTheTasksStillRunningAtTheGraceDeadline() throws Exception {
+        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
+        repository.thenClaim(item(1, 1));
+        runner.start();
+        await().until(() -> tasks.started().size() == 1);
+        long start = System.nanoTime();
+
+        runner.stop();
+
+        assertThat(Duration.ofNanos(System.nanoTime() - start))
+                .as("shutdown-grace, then a prompt drain").isBetween(ofSeconds(2), ofSeconds(3));
+        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.SHUTDOWN);
+        assertThat(handle(key(1, 1)).isEnded()).isTrue();
+        assertThat(repository.writes()).isEmpty();
+    }
+
+    @Test
+    void stopReturnsAfterTheCancelWaitEvenIfATaskIgnoresInterrupts() throws Exception {
+        tasks.ignoreInterrupts();
+        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
+        repository.thenClaim(item(1, 1));
+        runner.start();
+        await().until(() -> tasks.started().size() == 1);
+        long start = System.nanoTime();
+
+        runner.stop();
+
+        assertThat(Duration.ofNanos(System.nanoTime() - start))
+                .as("shutdown-grace + shutdown-cancel-wait").isBetween(ofSeconds(3), ofSeconds(4));
+        assertThat(handle(key(1, 1)).isEnded()).as("still running").isFalse();
+        assertThat(runner.availablePermits()).as("it keeps its permit").isEqualTo(CONCURRENCY - 1);
+    }
+
+    @Test
+    void crashCancelsEveryClaimAtOnceAndStopsTheLoops() throws Exception {
+        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
+        repository.thenClaim(item(1, 1), item(2, 1));
+        runner.start();
+        await().until(() -> tasks.started().size() == 2);
+        long start = System.nanoTime();
+
+        runner.crash();
+
+        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no waiting").isLessThan(ofMillis(500));
+        assertThat(handles).extracting(ClaimHandle::cancelReason).containsOnly(CancelReason.CRASH);
+        assertThat(runner.isRunning()).isFalse();
+        int claims = repository.claimSizes().size();
+        await().during(ofMillis(300)).atMost(ofSeconds(2))
+                .until(() -> repository.claimSizes().size() == claims);
+    }
+
+    @Test
+    void crashAlsoCancelsAClaimTransferredButNotYetRegistered() throws Exception {
+        runner = runner(tasks, recordingThreads(), now::get, new ConcurrentHashMap<>() {
+            @Override
+            public ClaimHandle putIfAbsent(ClaimKey key, ClaimHandle value) {
+                runner.crash();   // the crash lands between the transfer and the registration
+                return super.putIfAbsent(key, value);
+            }
+        });
+        repository.thenClaim(item(1, 1));
+
+        runner.pollOnce();
+
+        await().until(() -> handle(key(1, 1)).isEnded());
+        assertThat(handle(key(1, 1)).cancelReason()).isEqualTo(CancelReason.CRASH);
+        assertThat(tasks.started()).isEmpty();
+    }
+
+    @Test
+    void aRunnerCannotBeRestarted() {
+        runner = runner(tasks, recordingThreads(), System::nanoTime, new ConcurrentHashMap<>());
+        runner.start();
+        runner.stop();
+
+        assertThatThrownBy(runner::start).isInstanceOf(IllegalStateException.class);
+    }
+
     // ---- Settings -------------------------------------------------------------------------------------------
 
     @Test
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: a compilation failure, `cannot find symbol` for the methods `start`, `stop`, `isRunning`, `crash` and `isStopping`.

- [ ] **Step 3: Implement the loops, stop and crash**

The cancel step of both `stop()` and `crash()` goes through `cancelAll`, which sets `cancelOnRegister` and then walks the registry, both under one lock (`registrationLock`, added by the final-review fixes). `registerAndStart` holds the same lock around `register()`, the collision handling, and the read of `cancelOnRegister` with its cancel; `thread.start()` stays outside the lock, as the last statement of the `try` whose `catch` finishes the handle. Keep that protocol: a volatile store followed by a registry walk is not ordered against a concurrent registration under the Java memory model, and the lock is what guarantees that a crash landing between a transfer and a registration still cancels that handle (`crashAlsoCancelsAClaimTransferredButNotYetRegistered`). The lock must be reentrant, because that test crashes the runner from inside `register()`; `stop()` takes it while holding `lifecycle`, `crash()` before it takes `lifecycle`.

```diff
diff --git a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
index 54f72ff..b01a40c 100644
--- a/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
+++ b/db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java
@@ -3,6 +3,7 @@ package hle.org.workqueue.engine;
 import hle.org.workqueue.engine.ClaimHandle.CancelReason;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
+import org.springframework.context.SmartLifecycle;
 
 import java.time.Duration;
 import java.util.HashMap;
@@ -24,7 +25,7 @@ import java.util.function.LongSupplier;
  * {@code permits.available + handles not ended + held = concurrency} holds whenever the poll loop is between
  * iterations. Times are {@code System.nanoTime()} readings from the injected clock, compared overflow-safely.
  */
-final class QueueRunner {
+final class QueueRunner implements SmartLifecycle {
 
     /** The settings the loops use; spec §6 describes each. */
     record Settings(int concurrency, int claimBatchSize, Duration idlePollInterval, Duration pollBackoffMax,
@@ -81,6 +82,12 @@ final class QueueRunner {
             .name("workqueue-task-" + handle.key().id() + "-" + handle.key().token())
             .unstarted(body);
 
+    /** How long {@link #stop()} waits for a loop thread after interrupting it; E2 leaves 5s for this and exit. */
+    private static final Duration LOOP_JOIN_TIMEOUT = Duration.ofSeconds(1);
+
+    /** How often {@link #stop()} checks whether the registry has emptied. */
+    private static final Duration DRAIN_CHECK_INTERVAL = Duration.ofMillis(10);
+
     private static final Logger log = LoggerFactory.getLogger(QueueRunner.class);
 
     private final WorkItemRepository repository;
@@ -91,14 +98,30 @@ final class QueueRunner {
     private final LongSupplier clock;
     private final ConcurrentMap<ClaimKey, ClaimHandle> registry;
     private final Semaphore permits;
+    private final RenewalSchedule schedule;
 
     private final AtomicLong invariantViolations = new AtomicLong();
     private final AtomicLong registrationsLate = new AtomicLong();
     private final AtomicLong claimsLost = new AtomicLong();
 
+    // Once set, every handle registered from then on is cancelled before its thread starts, so crash() and stop()
+    // also reach a handle the poll loop has transferred but not yet registered.
+    private volatile CancelReason cancelOnRegister;
+
     // Only the poll loop reads or writes this.
     private int claimFailures;
 
+    private final Object lifecycle = new Object();
+    private boolean started;
+    private volatile boolean running;
+    private volatile boolean stopping;
+    private volatile boolean polling;
+    private volatile boolean renewing;
+    private volatile boolean supervising;
+    private Thread pollThread;
+    private Thread renewalThread;
+    private Thread supervisorThread;
+
     QueueRunner(WorkItemRepository repository, Processor processor, String owner, Settings settings) {
         this(repository, processor, owner, settings, VIRTUAL_THREADS, System::nanoTime, new ConcurrentHashMap<>());
     }
@@ -115,10 +138,102 @@ final class QueueRunner {
         this.clock = Objects.requireNonNull(clock, "clock");
         this.registry = Objects.requireNonNull(registry, "registry");
         this.permits = new Semaphore(settings.concurrency());
+        this.schedule = new RenewalSchedule(settings.renewInterval(), settings.renewRetryDelay());
+    }
+
+    // ---- Lifecycle -------------------------------------------------------------------------------------------
+
+    /** Starts the three loops. A runner starts once: after stop() or crash() it cannot be started again. */
+    @Override
+    public void start() {
+        synchronized (lifecycle) {
+            if (running) {
+                return;
+            }
+            if (started || cancelOnRegister != null) {
+                throw new IllegalStateException("a QueueRunner cannot be restarted");
+            }
+            started = true;
+            polling = true;
+            renewing = true;
+            supervising = true;
+            pollThread = Thread.ofVirtual().name("workqueue-poll").start(this::pollLoop);
+            renewalThread = Thread.ofVirtual().name("workqueue-renewal").start(this::renewalLoop);
+            supervisorThread = Thread.ofVirtual().name("workqueue-supervisor").start(this::supervisorLoop);
+            running = true;
+        }
+    }
+
+    /**
+     * The stop sequence of spec §5.2: stop claiming (a claim that already returned is still started), wait up to
+     * shutdown-grace for the running tasks with renewal still running, cancel what is left, wait up to
+     * shutdown-cancel-wait, then stop renewal and the supervisor. Nothing is released in Db2: a claim still held
+     * expires with its attempt consumed.
+     */
+    @Override
+    public void stop() {
+        synchronized (lifecycle) {
+            if (!running) {
+                return;
+            }
+            stopping = true;
+            long graceEnd = clock.getAsLong() + settings.shutdownGrace().toNanos();
+            polling = false;
+            pollThread.interrupt();
+            join(pollThread, remaining(graceEnd));
+            awaitDrained(graceEnd);
+            cancelAll(CancelReason.SHUTDOWN);
+            awaitDrained(clock.getAsLong() + settings.shutdownCancelWait().toNanos());
+            renewing = false;
+            supervising = false;
+            renewalThread.interrupt();
+            supervisorThread.interrupt();
+            join(renewalThread, LOOP_JOIN_TIMEOUT);
+            join(supervisorThread, LOOP_JOIN_TIMEOUT);
+            running = false;
+        }
+    }
+
+    @Override
+    public boolean isRunning() {
+        return running;
+    }
+
+    /** Tests only (spec §5.2): stops every loop and cancels every claim at once, without draining or waiting. */
+    void crash() {
+        polling = false;
+        renewing = false;
+        supervising = false;
+        cancelAll(CancelReason.CRASH);
+        synchronized (lifecycle) {
+            if (running) {
+                pollThread.interrupt();
+                renewalThread.interrupt();
+                supervisorThread.interrupt();
+                running = false;
+            }
+        }
     }
 
     // ---- Poll loop -------------------------------------------------------------------------------------------
 
+    private void pollLoop() {
+        while (polling) {
+            Duration pause;
+            try {
+                pause = pollOnce();
+            } catch (InterruptedException e) {
+                return;
+            } catch (RuntimeException e) {
+                pause = backoff();
+                log.error("Poll of owner {} failed; next claim in {}: {}", owner, pause, Diagnostics.describe(e));
+            }
+            if (!sleep(pause)) {
+                return;
+            }
+        }
+    }
+
     /**
      * One poll-loop iteration (spec §5.2 steps 1–3): acquire permits, claim that many rows, then transfer one permit
      * to each claimed row's handle, register it and start its thread. Every permit the loop still holds is returned
@@ -177,6 +292,10 @@ final class QueueRunner {
             if (clock.getAsLong() - claimedAt > settings.registrationAllowance().toNanos()) {
                 registrationsLate.incrementAndGet();
             }
+            CancelReason reason = cancelOnRegister;
+            if (reason != null) {
+                handle.cancel(reason, clock.getAsLong());
+            }
             thread.start();
         } catch (Throwable t) {
             handle.finish();
@@ -221,6 +340,24 @@ final class QueueRunner {
 
     // ---- Renewal loop ----------------------------------------------------------------------------------------
 
+    private void renewalLoop() {
+        long next = clock.getAsLong();
+        while (renewing) {
+            if (!sleep(Duration.ofNanos(Math.max(0, next - clock.getAsLong())))) {
+                return;
+            }
+            long start = clock.getAsLong();
+            boolean succeeded;
+            try {
+                succeeded = renewOnce();
+            } catch (RuntimeException e) {
+                succeeded = false;
+                log.error("Renewal round of owner {} failed: {}", owner, Diagnostics.describe(e));
+            }
+            next = schedule.next(start, clock.getAsLong(), succeeded);
+        }
+    }
+
     /**
      * One renewal round (spec §5.3) over a snapshot, taken at its start, of the handles that are renewable then.
      * Every claim the round reports lost is counted and cancelled; a claim its own task already ended is neither.
@@ -255,6 +392,19 @@ final class QueueRunner {
 
     // ---- Supervisor ------------------------------------------------------------------------------------------
 
+    private void supervisorLoop() {
+        while (supervising) {
+            try {
+                superviseOnce();
+            } catch (RuntimeException e) {
+                log.error("Supervisor pass of owner {} failed: {}", owner, Diagnostics.describe(e));
+            }
+            if (!sleep(settings.supervisorInterval())) {
+                return;
+            }
+        }
+    }
+
     /**
      * One supervisor pass (spec §5.2): cancels every claim past its deadline, and marks hung every cancelled claim
      * whose thread is still running hung-grace after the cancel, logging it once with its stack. Never waits on Db2.
@@ -313,4 +463,57 @@ final class QueueRunner {
     long claimsLost() {
         return claimsLost.get();
     }
+
+    /** True from the start of {@link #stop()}: readiness reports DOWN. */
+    boolean isStopping() {
+        return stopping;
+    }
+
+    // ---- Helpers ---------------------------------------------------------------------------------------------
+
+    private void cancelAll(CancelReason reason) {
+        cancelOnRegister = reason;
+        long now = clock.getAsLong();
+        for (ClaimHandle handle : registry.values()) {
+            handle.cancel(reason, now);
+        }
+    }
+
+    private void awaitDrained(long deadline) {
+        while (!registry.isEmpty()) {
+            Duration left = remaining(deadline);
+            if (left.isZero() || !sleep(left.compareTo(DRAIN_CHECK_INTERVAL) < 0 ? left : DRAIN_CHECK_INTERVAL)) {
+                return;
+            }
+        }
+    }
+
+    private Duration remaining(long deadline) {
+        return Duration.ofNanos(Math.max(0, deadline - clock.getAsLong()));
+    }
+
+    // False if interrupted, with the interrupt status restored.
+    private static boolean sleep(Duration pause) {
+        try {
+            if (pause.isPositive()) {
+                Thread.sleep(pause);
+            } else if (Thread.currentThread().isInterrupted()) {
+                return false;
+            }
+            return true;
+        } catch (InterruptedException e) {
+            Thread.currentThread().interrupt();
+            return false;
+        }
+    }
+
+    private static void join(Thread thread, Duration timeout) {
+        try {
+            if (timeout.isPositive()) {
+                thread.join(timeout);
+            }
+        } catch (InterruptedException e) {
+            Thread.currentThread().interrupt();
+        }
+    }
 }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl work-queue-engine test -Dtest=QueueRunnerLifecycleTest`
Expected: PASS (39 tests, about 20s; the three stop tests take 2–4s each). Then `./mvnw -q -pl work-queue-engine test`. Expected: 391 tests, all passing.

- [ ] **Step 5: Update the README status**

In `db-work-queue/README.md`, replace the status line:

```diff
diff --git a/db-work-queue/README.md b/db-work-queue/README.md
index 30ab7b6..22bbd47 100644
--- a/db-work-queue/README.md
+++ b/db-work-queue/README.md
@@ -2,7 +2,7 @@
 
 Db2-backed work-queue engine. Design: [spec](../docs/superpowers/specs/2026-09-21-db-work-queue-design.md).
 
-Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`), `ClaimHandle` and `ItemProcessor` done; `QueueRunner` next.
+Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`), `ClaimHandle`, `ItemProcessor` and `QueueRunner` done; the `Sweeper`, metrics and health next.
 
 ## Prerequisites
 
```

- [ ] **Step 6: Run the whole build, ITs included**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, with 391 unit tests and 72 ITs passing.

- [ ] **Step 7: Commit** (from the repository root)

```bash
git add db-work-queue/work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java \
        db-work-queue/work-queue-engine/src/test/java/hle/org/workqueue/engine/QueueRunnerLifecycleTest.java \
        db-work-queue/README.md
git commit -m "feat: run QueueRunner's loops with drain-then-cancel stop and crash" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage (revision 11):**
  - §5.2 lifecycle steps 1–5: `pollOnce` and `registerAndStart` (Task 4), with the permit rule's every path (the table "Every permit's path back to the semaphore") tested. That covers claim failure, an uncertain outcome, a handle or thread that cannot be built, a registration that throws, a collision, a failed start, cancel-before-run, and a normal end.
  - §5.2 step 6 (cancel never releases the permit): `cancelledTasksThatIgnoreInterruptsStillCountAgainstConcurrency`, `stopReturnsAfterTheCancelWaitEvenIfATaskIgnoresInterrupts`.
  - §5.2 renewal eligibility and §5.3 snapshot, lost and ended: Task 5, and the DB side in Task 3.
  - §5.2 supervisor, hung detection and the hung-task limit: Task 6.
  - §5.2 stop, `crash()` and cancel-on-register: Task 7. The permit invariant: `endEveryTask` after every test, and `assertPermitInvariant` mid-scenario.
  - §5.3 B4 with `G`: Task 1. §5.4 logging: `Diagnostics` (Task 2), and log-content assertions in Tasks 4 and 6.
  - §11.1 `QueueRunnerLifecycleTest`: every bullet has a test. The "liveness DOWN" parts are left to slice 2.6, which reads `invariantViolations()` and `hungTasks()`.
  - §11.2 `WorkItemRepositoryIT` renew bullet: Task 3.
- **Verified before writing.** Every code block and diff was taken from a scratch worktree where the tasks were replayed in order from `d632489`, and every command was run there. The failing states are measured too: Task 1 fails 2 tests, and Tasks 2–7 fail to compile on exactly the symbols named. Unit-test totals: 346 → 347 (Task 1) → 352 (Task 2) → 352 (Task 3; ITs only) → 373 → 381 → 385 → 391. `QueueRunnerLifecycleTest` alone has 21 → 29 → 33 → 39 tests.
- **The lifecycle tests catch what they claim to.** Ten mutations of `QueueRunner` were each killed by the intended test: no `finally` release, no cancel-on-register, renewing every registered handle, counting ended claims as lost, no hung pause, `>=` in the registration-late check, catching `Exception` instead of `Throwable` in the body, no cancel in `stop()`, and a collision or failed start not finishing its handle. Six consecutive runs of the class passed.
- **Found while prototyping, now in the spec.**
  - E3's hung target was one supervisor interval short: the cancel and the hung mark each come at the supervisor's next pass (revision 11).
  - `RevokeRaceIT`'s shared fenced-owner check had to be relaxed from "lost" to "not renewed". An owner write that committed before the revoke leaves the row with that owner and token, so it reads back as ended. That is exactly the case the read-back exists for.
- **Deferred on purpose.**
  - Slice 2.6: Micrometer meters, `WorkQueueHealth`, the `Sweeper` and the `BacklogSampler`.
  - Phase 3: the owner id and bean wiring (auto-configuration).
  - Slice 2.7: the Db2 ITs 6–12 that drive `QueueRunner` end to end.

## After this plan

- **2d — `Sweeper`, metrics and health (slice 2.6).** Bind `invariantViolations`, `registrationsLate`, `claimsLost`, `hungTasks`, `inflight` and `availablePermits` to meters. It needs three things `QueueRunner` does not record yet:
  - For `renewal.lag`: each handle's last successful lease write (its claim operation's start, then the start of each round that renewed it).
  - For `db.last_success_age`: the time of the last successful claim, renewal, sweep or backlog sample.
  - For liveness: whether each loop thread is alive.

  Readiness reads `isStopping()`. Remember the §9.6 rule: `renewal.lag` is 0 without renewal-eligible claims.
- **2e — Db2 ITs 6–12 (slice 2.7).** `RecordingDownstream`, the Toxiproxy host-port spike, and one IT-column definition in place of the current four (`Db2TestSupport`, `ItConfig`, `DbTimeoutsTest.IT`, `LeaseTimingTest.IT`). `PoisonRowIT` uses `crash()`. `GracefulShutdownIT` asserts E2 against `stop()`'s measured bound, `shutdown-grace + shutdown-cancel-wait + 2s`.
