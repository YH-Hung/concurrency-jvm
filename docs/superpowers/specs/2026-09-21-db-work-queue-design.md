# db-work-queue — Design

Date: 2026-09-21 (revision 10: 2026-09-26)
Status: Architecture accepted. Phase 1 gate passed (2026-09-25); approved for Phase 2.
Production approval pending review of this revision. Every time value in §5.3 and §7 is a conditional target
pending validation by the Phase 1 spike, review of the §5.3 timing argument,
`LeaseSimulationTest`, and the real-driver and load tests.

## 1. Goal and scope

A production-ready Db2-backed work-queue **engine** plus a lightweight **demo harness**.
Rows in a Db2 table are processed by any number of independent application instances,
such that:

- **Concurrent distribution** — instances claim and process rows in parallel without a coordinator.
- **Dynamic worker number** — instances (separate JVMs) can be started or stopped at any
  time; no membership registration or rebalancing.
- **No double effects** — a stale owner can never write a row's final state; external
  calls per row are bounded; with the required downstream idempotency (§5.4), each
  operation's side effect is applied at most once, and exactly once for every operation
  that reaches DONE.
- **Crash/restart survival** — kill -9, freezes, hung tasks, graceful stops, and Db2
  outages all converge to every row DONE or FAILED, with the time targets and assumptions
  stated in §7.

### Decisions

| Topic | Decision |
|---|---|
| Deliverable | Production-ready engine + lightweight demo harness |
| Input | Rows inserted into `WORK_ITEM` by an upstream producer with an immutable `OPERATION_ID`; the demo seeds a finite batch |
| Tracking state | Columns on the input table (no side table) |
| Scaling unit | Instances; per-JVM concurrency fixed by config |
| Side-effect contract | Durable downstream idempotency on `NAMESPACE:OPERATION_ID` is required (§5.4) |
| Deployment target | Generic container orchestrator: liveness/readiness probes, env/mounted-file secrets, migrations as a one-shot job. Nothing Kubernetes-specific. |
| Db2 runtime | Real Db2: docker-compose (demo), Testcontainers (tests) |
| Distribution approach | Competing consumers: lease + `SKIP LOCKED DATA` + fencing token |

### Rejected approaches

- **Bucket ownership with membership table** — needs rebalancing logic, suffers from
  skew, slows scale-out, and idles instances beyond the bucket count.
- **Spring Batch 6 remote partitioning** — partitions are fixed at step start (poor
  dynamic scaling), needs messaging middleware or DB polling, and hides the mechanism.

## 2. Technology

- JDK 25 (Corretto 25 locally). Virtual threads for task execution.
- Spring Boot **4.1.1**: `spring-boot-starter-jdbc` (`JdbcClient`, HikariCP,
  `TransactionTemplate`), `spring-boot-starter-actuator` + Micrometer Prometheus
  registry, `spring-boot-starter-security` (demo app), Flyway (`flyway-core`,
  `flyway-database-db2`).
- Db2 JDBC driver `com.ibm.db2:jcc` (version managed by Boot).
- Db2 image `icr.io/db2_community/db2:12.1.5.0` (amd64 only; Rosetta emulation on Apple
  Silicon). Running it sets `LICENSE=accept`, i.e. the user accepts the Db2 Community
  Edition license.
- Tests: JUnit 5, AssertJ, Awaitility, Testcontainers **2.0.5** (`testcontainers-db2`,
  `testcontainers-toxiproxy`, `@ServiceConnection`), JaCoCo.
- Maven wrapper included (`mvn` is not on PATH on the dev machine).

## 3. Module structure

```
db-work-queue/                     aggregator POM (spring-boot-starter-parent 4.1.1, Java 25, hle.org)
  mvnw, .mvn/
  work-queue-engine/               library jar with Spring Boot auto-configuration
    src/main/java/hle/org/workqueue/engine/...
    src/main/resources/db/migration/workqueue/V1__work_queue.sql
    src/main/resources/META-INF/spring/...AutoConfiguration.imports
  work-queue-demo/                 Spring Boot app: simulated downstream, seed, verify, scripts
    src/main/java/hle/org/workqueue/demo/...
    src/main/resources/db/migration/demo/V100__simulated_downstream.sql
    docker-compose.yml, .env.example, scripts/
  docs/runbook.md                  operations runbook (§9.7)
  docs/grants.sql                  least-privilege template (§9.2)
  docs/alerts.yml                  example Prometheus alert rules (§9.6)
```

A production deployment is any Spring Boot app that depends on `work-queue-engine` and
provides an `ExternalService` bean. Seeding, verification and the simulated downstream
live only in `work-queue-demo`, so they are not in a production artifact.

## 4. Data model

### Engine tables (`db/migration/workqueue`)

```
WORK_QUEUE_META                    -- exactly one row
  NAMESPACE     VARCHAR(32)   NOT NULL   -- set by V1 from Flyway placeholder ${workqueueNamespace}
                CHECK (LENGTH(NAMESPACE) >= 1 AND LOCATE(':', NAMESPACE) = 0)

WORK_ITEM
  ID            BIGINT        NOT NULL GENERATED ALWAYS AS IDENTITY, PRIMARY KEY
  OPERATION_ID  VARCHAR(65)   NOT NULL             -- upstream-assigned, immutable, canonical format (§5.4)
                CHECK (LENGTH(OPERATION_ID) BETWEEN 1 AND 64 AND NOT REGEXP_LIKE(OPERATION_ID, '[^!-~]'))
  PAYLOAD       VARCHAR(1000) NOT NULL
  STATUS        VARCHAR(10)   NOT NULL DEFAULT 'PENDING'
                              CHECK (STATUS IN ('PENDING','CLAIMED','DONE','FAILED'))
  AVAILABLE_AT  TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP
  OWNER         VARCHAR(64)                        -- current claim holder; NULL after revocation, sweep or replay
  CLAIM_TOKEN   BIGINT        NOT NULL DEFAULT 0   -- fencing token; +1 per claim, revocation or sweep; never reset
  ATTEMPTS      INT           NOT NULL DEFAULT 0   -- claims since last (re)queue; reset only by replay
  RESULT_VALUE  VARCHAR(1000)
  LAST_ERROR    VARCHAR(1000)
  CREATED_AT    TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP
  UPDATED_AT    TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP
  UNIQUE INDEX UX_WORK_ITEM_OPERATION_ID (OPERATION_ID, LENGTH(OPERATION_ID))   -- exact identity
  INDEX IX_WORK_ITEM_CLAIM (STATUS, AVAILABLE_AT)
```

**Upstream insert contract:** `INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (?, ?)`.
Defaults make the row immediately claimable; rows may be inserted at any time. Only an
identical `OPERATION_ID` fails with a unique-key violation (SQLSTATE 23505), which the
producer treats as "already enqueued". An `OPERATION_ID` outside the §5.4 format fails with a
check violation (23513), or with a length error (22001) when it is longer than 65 bytes
(UTF-8; fewer characters for non-ASCII input) and the excess is not all blanks: a producer
bug, never "already enqueued". Nothing is stored in either case. `OPERATION_ID` rules are
in §5.4.

**Why the column is `VARCHAR(65)`:** on assignment, Db2 silently cuts excess trailing blanks
off a value that is too long for the column, before the `CHECK` and the unique key see it.
In a `VARCHAR(64)` column, 64 characters plus a blank were stored as the 64 characters, or
failed as a duplicate of them. One byte wider, any value over 65 bytes that ends in blanks
arrives as 65 bytes: too long if ASCII, non-ASCII otherwise, so it fails the `CHECK`.

**Why the unique key includes the length:** Db2 compares strings blank-padded, so
`'order-1'` equals `'order-1 '`, and it checks uniqueness before the `CHECK`. With a unique
key on `OPERATION_ID` alone, a malformed id with trailing blanks fails as a duplicate of the
canonical one and is dropped as "already enqueued". With the length in the key, only
identical ids collide, and the malformed one fails the `CHECK`.

**`ID` is internal.** Identity values can repeat after a table is recreated or restored,
so `ID` is used only for row addressing inside the engine (always together with
`CLAIM_TOKEN` and `OWNER` in fenced writes) and in admin filters. It is never sent
downstream.

**Why two counters:** `CLAIM_TOKEN` must be monotonic forever — resetting it would let a
stale owner holding token *n* match a later claim that reuses *n*. `ATTEMPTS` is the retry
budget and must be resettable so an operator can replay FAILED rows (§9.7). Invariant:
`CLAIM_TOKEN ≥ ATTEMPTS`.

**Immutability is enforced by Db2:** the worker user has column-level `UPDATE` only on the
tracking columns, never on `OPERATION_ID` or `PAYLOAD` (§9.2).

### Demo tables (`db/migration/demo`, demo app only)

```
SIMULATED_EFFECT                   -- the simulated downstream's durable idempotency store
  IDEMPOTENCY_KEY VARCHAR(140)  NOT NULL PRIMARY KEY  -- NAMESPACE:OPERATION_ID
  RESULT_VALUE    VARCHAR(1000) NOT NULL
  CLAIM_TOKEN     BIGINT        NOT NULL              -- token of the call that applied it
  INSTANCE_ID     VARCHAR(64)   NOT NULL
  APPLIED_AT      TIMESTAMP     NOT NULL

EXECUTION_LOG                      -- one row per call received by the simulated downstream
  ID              BIGINT        NOT NULL GENERATED ALWAYS AS IDENTITY, PRIMARY KEY
  IDEMPOTENCY_KEY VARCHAR(140)  NOT NULL
  CLAIM_TOKEN     BIGINT        NOT NULL
  INSTANCE_ID     VARCHAR(64)   NOT NULL
  OUTCOME         VARCHAR(16)   NOT NULL  -- APPLIED | REPLAYED | FAILED | RESPONSE_LOST | TIMED_OUT | INTERRUPTED
  EXECUTED_AT     TIMESTAMP     NOT NULL
  INDEX IX_EXEC_KEY (IDEMPOTENCY_KEY, CLAIM_TOKEN)
```

## 5. Contracts

### 5.1 Row and claim contract

A row is claimable when:

```sql
STATUS IN ('PENDING','CLAIMED') AND AVAILABLE_AT <= CURRENT TIMESTAMP AND ATTEMPTS < :maxAttempts
```

`AVAILABLE_AT` is the lease: a claim sets it to `now + lease`; renewal pushes it forward;
when renewal stops, the row becomes claimable again. A failed attempt with retries left
sets it to `now + retry-backoff`. Expired rows with exhausted attempts are left for the
`Sweeper`.

**Claim order.** One claim operation first takes expired CLAIMED rows (recovery), ordered
by `AVAILABLE_AT`, then fills the rest of the batch with PENDING rows ordered by
`AVAILABLE_AT`. Recovered work is therefore never starved by new work, and the backlog is
served oldest-available first. Both selections use `IX_WORK_ITEM_CLAIM`.

```
            claim (CLAIM_TOKEN+1, ATTEMPTS+1, OWNER = me, AVAILABLE_AT = now + lease)
 PENDING ─────────────────────────────▶ CLAIMED ──complete──▶ DONE
    ▲  ▲  ▲                              │  │  │
    │  │  └─ retryOrFail, ATTEMPTS < max ┘  │  ├─ retryOrFail, ATTEMPTS ≥ max ──▶ FAILED
    │  │     (AVAILABLE_AT = now + backoff) │  │                                    │
    │  │                                    │  └─ renewal stops, lease expires ─▶ claimable again
    │  │                                    │     (ATTEMPTS ≥ max ─▶ Sweeper ─▶ FAILED, CLAIM_TOKEN+1, OWNER = NULL)
    │  └─ admin revokeOwner, ATTEMPTS < max ┘     (ATTEMPTS ≥ max ─▶ FAILED)      │
    │     (CLAIM_TOKEN+1, OWNER = NULL, AVAILABLE_AT = now)                       │
    └──────────── admin replay (ATTEMPTS = 0, AVAILABLE_AT = now; token unchanged) ◀┘
```

Rules:

- **DB clock only.** Every timestamp written to or compared in Db2 is `CURRENT TIMESTAMP`.
  JVM clocks are used only for local deadlines (`System.nanoTime()`).
- **Db2 runs in UTC (deployment precondition).** `CURRENT TIMESTAMP` is the Db2 server's
  local time. In a time zone with daylight saving, every live lease would expire at once at
  spring-forward, and expiry, backoff and sweeping would be delayed by up to an hour at
  fall-back. `SchemaCheck` fails startup when `CURRENT TIMEZONE <> 0`; that check is
  necessary but not sufficient (Europe/London is at offset 0 in winter), so the runbook
  requires the Db2 instance's time zone to be UTC. UTC arithmetic in SQL was rejected: it
  would change every statement and the `DEFAULT CURRENT TIMESTAMP` the upstream insert
  contract relies on.
- **Fenced writes.** Every write by a claim holder (renew, complete, retryOrFail) matches
  `ID = ? AND CLAIM_TOKEN = ? AND OWNER = :me AND STATUS = 'CLAIMED'`. Zero rows means
  the claim is no longer this instance's; the write is dropped. `OWNER` is included so
  that a repeated `(ID, CLAIM_TOKEN)` pair after a restore (§9.7) cannot match another
  process's claim: instance ids are unique per process start.
- **Ending someone else's claim fences it.** The two statements that end a claim other
  than by its holder's own write, the `Sweeper` and `revokeOwner` (§9.7), both set
  `CLAIM_TOKEN = CLAIM_TOKEN + 1, OWNER = NULL`. The holder's late writes then update 0
  rows, and the persist read-back (§6 `ItemProcessor`) reports `FENCED` instead of
  mistaking the new state for its own committed write.
- **Attempts are never refunded** except by explicit operator replay.
- **One external call per claim.** A retry always goes through a new claim.
- **One connection per thread.** No thread holds more than one pooled connection at a
  time, and no connection is held during an external call.

### 5.2 Task lifecycle contract

Each claim is owned by exactly one `ClaimHandle`, keyed by `ClaimKey(id, token)`.
`QueueRunner` is the only class that creates handles, starts their threads, and holds the
registry `ConcurrentHashMap<ClaimKey, ClaimHandle>` and `Semaphore permits(concurrency)`.

**Execution model.** Each claim runs on its own virtual thread started directly with
`Thread.ofVirtual().unstarted(body)` — not through an `ExecutorService`. This removes two
leak paths: executor rejection after shutdown, and `Future.cancel` before start (which
skips the task's `finally`).

**Poll-loop permit rule.** Every permit is owned by exactly one party at a time: the poll
loop or one handle. The poll loop tracks the permits it owns in a local counter `held`.
Every iteration runs inside `try { ... } finally { permits.release(held); }`, so any
permit still owned by the poll loop is returned on every path: an empty or partial claim,
a claim exception, an uncertain commit, an exception while constructing a handle, and
interruption during `acquire` or during the claim at shutdown. Once a permit is transferred
to a handle (step 3), the handle's `finish()` is responsible for it on every later path,
including failures before its thread starts.

**Uncertain claim outcome.** If the claim operation throws — including a failure to
receive the commit acknowledgement — the poll loop treats it as "nothing claimed": it
registers nothing and returns all held permits. Any rows that did commit are CLAIMED by
this owner but unregistered, so they are never renewed and never called; they become
eligible for recovery within one lease (§7) with that attempt consumed. The loop then
backs off exponentially (cap `poll-backoff-max`).

**Lifecycle, in order:**

1. **Acquire.** Block for 1 permit, then `tryAcquire` up to `claim-batch-size` in total;
   `held = n`. Only the poll loop acquires permits.
2. **Claim.** One claim operation for `n` rows returns `k ≤ n` rows.
3. **Transfer, register and start**, per row:
   1. `handle = new ClaimHandle(...)` — no side effects; if construction throws, the permit
      is still in `held`.
   2. `held -= 1` — the transfer; it cannot throw. From here on the handle owns the permit.
   3. `try { register; thread.start(); } catch (Throwable t) { handle.finish(); ... }` —
      any failure between transfer and a successful start, including a registration error,
      is cleaned up by `finish()`, which releases the permit exactly once and is a no-op
      on the registry if the handle was never registered.
   4. Registration is `registry.putIfAbsent(key, handle)`. A collision is impossible (tokens
      are unique per claim), so it is treated as an invariant violation: the new handle is
      finished without starting, `workqueue.invariant.violations` is incremented, an ERROR
      is logged, and liveness reports DOWN.
   5. The handle records the time between the claim operation returning and its
      registration; more than `registration-allowance` increments
      `workqueue.registration.late` (the B2 proof assumes it does not happen, §5.3).
4. **Run.** Body: `try { if (!handle.markRunning()) return; processor.process(...) } finally { handle.finish(); }`.
   `markRunning()` fails if the handle was already cancelled, so a cancel that arrives
   before execution skips processing but still runs `finish()`.
5. **Finish (exactly once).** `finish()` does work only if `ended.compareAndSet(false, true)`:
   `registry.remove(key, this)` (value-aware — a handle can only remove itself, never a
   newer claim of the same row), then `permits.release()`, then metrics.
6. **Cancel.** `cancel(reason)` sets `cancelled`, records `cancelledAt`, and interrupts the
   thread if started. It does **not** touch the registry or the permit: the permit is
   released only when the thread actually exits (step 5), so a task ignoring interrupts
   still counts against `concurrency`.

**Every permit's path back to the semaphore:**

| Situation | Released by |
|---|---|
| Still owned by the poll loop (empty/partial claim, claim exception, uncertain commit, handle construction error, interrupt) | Poll-loop `finally` |
| Transferred, then registration fails, a collision is detected, or the thread fails to start | Poll loop → `handle.finish()` |
| Handle ends normally, any outcome, including processor exception | Body `finally` → `finish()` |
| Handle cancelled before the body runs | Body `finally` → `finish()` |
| JVM exit (kill -9 / halt) | Nobody — the process is gone; its claims expire in Db2 |

**Renewal eligibility:** a handle is renewed only while `!ended && !cancelled && now < deadline`.

**Supervisor** (a DB-free virtual thread, every `supervisor-interval`): cancels handles
past their deadline (`claimedAt + max-processing-time`); marks a cancelled handle whose
thread has not ended `hung-grace` after `cancelledAt` as **hung** (gauge
`workqueue.tasks.hung`, logged once with its stack trace; it keeps its permit); updates
health. When hung tasks ≥ `hung-task-limit`, the poll loop stops claiming and liveness
reports DOWN, so the orchestrator restarts the process — the only way to reclaim a thread
that ignores interruption. Deadline enforcement never waits on Db2.

**Permit invariant** (asserted by tests): `permits.available + (handles not ended) +
held = concurrency`.

**Stop (`SmartLifecycle.stop`, on SIGTERM):** readiness DOWN → stop the poll loop
(interrupting it; its `finally` returns held permits; a claim already committed and
returned is still started) → wait up to `shutdown-grace` for the registry to empty, with
renewal running → cancel all remaining handles → wait up to `shutdown-cancel-wait` → stop
renewal, supervisor and sweeper → return. Nothing is released in Db2; leftover claims
expire with their attempt consumed.

**`crash()`** (package-private, tests only): stop all loops and cancel all handles at once,
no drain and no waiting.

### 5.3 Timing budget

Each DB operation (claim, renew, complete, retryOrFail, sweep, backlog sample, admin op)
runs in its own `TransactionTemplate` with a timeout. Spring applies the remaining
transaction time as the JDBC query timeout of every statement; JCC
`queryTimeoutInterruptProcessingMode=2` (close socket) makes a timed-out statement return
even if the server or network does not respond. The socket read timeout bounds the commit
or rollback round trip that the query timeout does not cover.

| Symbol | Setting | Default | IT value | Bounds |
|---|---|---|---|---|
| T_pool | Hikari `connection-timeout` | 2s | 500ms | waiting for a pooled connection |
| T_login | JCC `loginTimeout` | 3s | 1s | opening a physical connection |
| T_tx | transaction timeout per operation (whole seconds) | 5s | 2s | all statements of one operation |
| T_read | JCC `blockingReadConnectionTimeout` | 8s | 2s | any single round trip (incl. commit) |
| T_lock | `SET CURRENT LOCK TIMEOUT` (Hikari `connection-init-sql`) | 3s | 1s | a row-lock wait |

**Worst case for one DB operation:** `W = T_pool + T_login + T_tx + T_read` = 18s default,
5.5s in ITs. The defaults are deliberately tolerant, with a 100s lease to match: claim,
renewal and sweep operations touch batches of rows and can wait on contention, so unloaded
statement latency is no evidence for tighter values. They are tightened only after the
Phase 1 spike and the load tests measure these operations under contention, and any
tightening must keep B1–B5.

Driver properties are passed through Hikari `data-source-properties`, not the URL, so
they also apply to Testcontainers-provided URLs.

**Renewal schedule** (`RenewalSchedule.next(start, end, succeeded)`, a pure function used
by the runtime and by the simulation tests): after a successful round that started at `s`
and ended at `e`, the next round starts at `max(s + renew-interval, e)`; after a failed
round ending at `e`, the next starts at `e + renew-retry-delay`. A round with no eligible
claims is skipped and counts as successful for scheduling. **A round's set of claims to
renew is a snapshot taken at its start**; a handle registered after that start waits for
the next round.

**Why B2 has that form.** Symbols: `I` renew-interval, `d` renew-retry-delay, `L` lease,
`G` registration-allowance.

- A lease-setting write executes no earlier than the start of its operation, so the lease
  lasts until at least that start + `L`.
- *Maintained claim:* its last successful renewal round started at `s`. The next round
  starts by `s + max(I, W)`.
- *New claim* (the worst case): the claim operation starts at `c`. Its commit is
  acknowledged by `c + W`, and the handle is registered by `c + W + G`. A round whose
  snapshot was taken just before registration excludes it, so the first round that
  includes it starts by `c + W + G + max(I, W)`.
- If that round fails, it ends within another `W`; the retry starts `d` later and commits
  within a further `W`.

So every claim survives one completely failed renewal round iff
`max(I, W) + 3W + d + G < L`. Example: `I = 15s, W = 5s, d = 1s, G = 1s, L = 26s` gives
`15 + 15 + 1 + 1 = 32`, not `< 26`, and is rejected.

**Why E5 has that form.** An outage fails every round it overlaps, including a round it
begins in just before that round's commit (the acknowledgement is lost), and a failed
round takes up to `W`: a refused connection fails at once, a stalled one only after `W`.
A new claim's lease lasts until at least `c + L`; it is registered by `c + W + G`, and the
first round that includes it starts by `s1 = c + W + G + max(I, W)`. An outage of length
`D` that begins as that round ends fails it. Retries that fail at once keep the retry
chain going to the end of the outage, so the last round it fails can start just before
the outage ends, at `s1 + W + D`, and still take `W` to fail. The retry starts `d` later
and writes within `W`. So the write lands by `c + max(I, W) + 4W + G + D + d`, and no claim
is lost if that is before `c + L`. An outage no longer than `d` fails at most one round,
because the retry starts `d` after a failure that came after the outage began; B2 covers
that case.

`E5 = max(d, L − max(I, W) − 4W − d − G)` (0 when B2 does not hold)

The bound needs the retry chain to reach the outage's last step, which an outage shorter
than `2d` does not allow; so when `d < E5 < 2d` the target is conservative (no claim is
lost up to `2d`).

With the defaults, `E5 = 100 − 18 − 72 − 1 − 1 = 8s`, and an 8.02s outage loses a claim: the
claim writes its lease at 0 (it expires at 100s) and registers at 19s; round R0 takes
19–37s, and R1 starts at 37s. The outage begins at 54.99s, so R1 fails at 55s. The retries
at 56–62s fail at once, the retry at 63s fails at 81s, and the retry at 82s writes at 100s.
`W` dominates E5: it counts five times (four when `I > W`), so each second cut from `W`
(when `W ≥ I`) adds 5s to E5, while each second added to `L` adds one.

`G` covers only the in-memory work between the claim returning and `putIfAbsent`; a
process pause longer than `G` is a freeze (E4), detected by `workqueue.registration.late`.
This timing argument is the primary justification for B2 and E5. `LeaseSimulationTest`
(§11.1) supplements it: it exercises a finite grid of configurations and interleavings
(claim execution, commit acknowledgement, registration, snapshots, round outcomes)
against the production `RenewalSchedule`, which is evidence, not a proof of every
execution. The real-driver tests (`QueryTimeoutIT`, `StaleOwnerIT`, `DbOutageIT`,
`LoadWithFaultsIT`, and `claims.lost = 0` in `SustainedLoadIT`) check what the argument
assumes about Db2 and JCC. B2 and E5 remain conditional targets until all three agree.

**Constraints**, checked by `TimingBudget` at startup (a violation fails startup and names
the constraint). Symbols: `I` = renew-interval, `d` = renew-retry-delay, `L` = lease.

| ID | Constraint | Why | Default | IT |
|---|---|---|---|---|
| B1 | `T_lock < T_tx` | a lock wait surfaces as a lock-timeout error | 3 < 5 | 1 < 2 |
| B2 | `max(I, W) + 3W + d + G < L` | every claim, including a new one, survives one failed renewal round under the actual schedule | 74 < 100 | 22.4 < 30 |
| B3 | `pool size ≥ concurrency + 4` | tasks, poll, renewal, sweeper and backlog sampler never wait for each other's connections | 20 ≥ 20 | 8 ≥ 8 |
| B4 | `max-processing-time ≥ external-call-timeout + (completion-retries + 1)·W + completion-retries·completion-retry-delay` | a slow-but-healthy task is not cut off by its deadline | 120 ≥ 105 | 25 ≥ 19.7 |
| B5 | `db-staleness-limit > 1.5·idle-poll-interval + W` | a healthy idle instance never reports DB staleness | 90 > 19.5 | 10 > 5.65 |

**Renewal round** is exactly one DB operation, however many claims are renewed:

```sql
SELECT ID, CLAIM_TOKEN FROM FINAL TABLE (
  UPDATE WORK_ITEM
     SET AVAILABLE_AT = CURRENT TIMESTAMP + :lease SECONDS, UPDATED_AT = CURRENT TIMESTAMP
   WHERE STATUS = 'CLAIMED' AND OWNER = :me
     AND ((ID = ? AND CLAIM_TOKEN = ?) OR (ID = ? AND CLAIM_TOKEN = ?) ...)   -- ≤ concurrency pairs
)
```

Lost claims = requested pairs − returned pairs.

**Orchestrator setting (documented, not checkable):** termination grace period ≥
`shutdown-grace + shutdown-cancel-wait + 10s`.

### 5.4 External side-effect contract

Claim-token fencing protects **database state only**. It does not stop a stale owner, a
retry, or a replay from calling the external service again. The engine therefore requires
durable downstream idempotency on an **operation identity** that is independent of the
table's physical row ids.

```java
public record IdempotencyKey(String namespace, String operationId) {
    // namespace: ^[a-z0-9][a-z0-9-]{0,31}$ (no ':'); operationId: ^[!-~]{1,64}$ (printable ASCII, no spaces).
    // Both validated in the constructor.
    public String value() { return namespace + ":" + operationId; }
}

public interface ExternalService {
    /**
     * Must be idempotent on key.value(), compared exactly (case-sensitive): repeated calls
     * apply the effect at most once and return the result of the first application. Must
     * return or throw within timeout.
     * Should respond to interruption.
     */
    CallResult call(IdempotencyKey key, long claimToken, String payload, Duration timeout)
            throws Exception;
}
```

**Key encoding.** `value()` is unambiguous because the namespace can never contain `:`,
so the first `:` is always the separator and `OPERATION_ID` may contain further colons.
The namespace rule is enforced three times: the `CHECK` constraint on `WORK_QUEUE_META`,
the full pattern in `SchemaCheck` at startup, and the `IdempotencyKey` constructor.
Downstreams that store the key as one string use `value()`; downstreams that store the two
parts separately may use the record's fields directly.

**Operation identity (`OPERATION_ID`):**

- Format: 1–64 printable ASCII characters (U+0021–U+007E): letters, digits and
  punctuation; no spaces, control characters or non-ASCII. One character is one byte, so
  byte lengths (the default `STRING_UNITS=SYSTEM`) equal character lengths, and
  blank-padded comparison can never merge two valid ids, here or in a downstream store.
  A producer with other business keys hashes or encodes them. Enforced by
  `CK_WORK_ITEM_OPERATION_ID` on insert and by the `IdempotencyKey` constructor.
- Case-sensitive: `order-1` and `ORDER-1` are different operations. Precondition: the
  database uses the `IDENTITY` collation (the IT database does); a case-insensitive
  collation would merge them under the unique key.
- Assigned by the upstream producer and **persisted with the business event** before the
  row is inserted; recommended: a UUID generated when the business event is created, or a
  deterministic business key (e.g. `order-8812:charge`).
- The producer reuses the persisted `OPERATION_ID` whenever it retries the insert or
  reconstructs work after a restore.
- Globally unique within its namespace for all time: an `OPERATION_ID` is never reused for
  different work, including after the table is recreated, data is restored, or rows are
  deleted or archived.
- Immutable once inserted: no engine statement updates it, and the worker's Db2 grant
  cannot (§9.2). Claims, retries, sweeps, revocation and replay all preserve it.

**Namespace:**

- Stored once in `WORK_QUEUE_META.NAMESPACE`, written by the V1 migration from the Flyway
  placeholder `workqueueNamespace`; it travels with the data through backup and restore.
- Format: `^[a-z0-9][a-z0-9-]{0,31}$` — lower-case letters, digits and `-`, never `:`.
- Must be unique among all queues that share any downstream dedupe store, and must not
  change once any row has been processed.
- Workers must set `workqueue.expected-namespace`; startup fails if it differs from
  `WORK_QUEUE_META.NAMESPACE`. This stops a deployment from processing another queue's
  database.
- Copying queue data into another environment requires deliberately setting a new
  namespace there (runbook), so its calls never dedupe against the source environment.

**Dedupe record lifetime:** the downstream must retain a dedupe record at least as long as
the same key can be sent again, i.e. the longest of: the time a row can stay non-terminal,
the replay window for FAILED rows, the backup retention period, and the period during
which the producer may re-enqueue an operation. If the producer may re-enqueue at any
time, retention is permanent for the namespace.

**Consequences:**

- Calls per row: zero to `max-attempts` between two replays (§7). Effects: at most one
  per operation; exactly one for a DONE operation; zero or one for a FAILED operation.
- The engine enforces the timeout from outside only as a backstop (deadline cancel, hung
  detection, §5.2).
- Self-fencing before calls (`LeaseGuard`) is unnecessary: a stale call is absorbed by the
  dedupe.
- A downstream that cannot provide durable idempotency is **not supported**.
- The engine never logs `PAYLOAD`, `RESULT_VALUE` or idempotency keys (may carry business
  data); logs carry `ID`, `CLAIM_TOKEN`, owner and outcome only.

## 6. Engine components (`hle.org.workqueue.engine`)

| Component | Responsibility |
|---|---|
| `WorkQueueProperties` | `@ConfigurationProperties("workqueue")`, including `db.*` timeouts. |
| `TimingBudget`, `RenewalSchedule` | B1–B5 at startup; the renewal next-start function. |
| `WorkItemRepository` | All SQL via `JdbcClient`, each operation in a timed `TransactionTemplate`: claim, renew, complete, retryOrFail, sweep, backlog sample, replay, revokeOwner, readNamespace. Only class that knows Db2 syntax. |
| `ClaimedItem`, `ClaimKey`, `IdempotencyKey` | Records: `(id, operationId, payload, claimToken)`, `(id, token)`, `(namespace, operationId)` with validation (§5.4). |
| `ClaimHandle` | One claim's lifecycle state (§5.2): permit ownership, `markRunning`, `cancel`, exactly-once `finish`. |
| `QueueRunner` | Poll loop, renewal loop, supervisor, registry, permits, stop/crash. |
| `ItemProcessor`, `Outcome` | One row, one call; persist the result with retries; returns `COMPLETED`, `RETRY_SCHEDULED`, `FAILED`, `FENCED`, `ABANDONED`, `INTERRUPTED`, `CANCELLED`. Never throws. |
| `ExternalService`, `CallResult` | SPI (§5.4). |
| `Sweeper` | Every `sweep-interval`: expired CLAIMED rows with `ATTEMPTS ≥ max` → FAILED with `CLAIM_TOKEN + 1` and `OWNER = NULL` (§5.1), in batches of `sweep-batch-size` (`FETCH FIRST :s ROWS ONLY`, `SKIP LOCKED DATA`), repeating while a batch is full. Idempotent; runs on every instance; concurrent sweepers skip each other's rows instead of waiting. |
| `BacklogSampler` | Every `backlog-sample-interval`: one query for DB-wide gauges (§9.6). |
| `WorkQueueHealth` | Liveness and readiness contributors (§9.6). |
| `SchemaCheck` | At startup: engine migration applied; `WORK_QUEUE_META.NAMESPACE` matches the §5.4 format and equals `workqueue.expected-namespace`; `CURRENT TIMEZONE = 0` (§5.1). Fails fast otherwise. Workers never run DDL. |
| `WorkQueueAdmin`, `WorkQueueEndpoint` | Replay and revokeOwner (§9.7); actuator endpoint `workqueue` with read (status) and write operations. Write operations are disabled unless `workqueue.admin.write-enabled=true`. |
| `WorkQueueAutoConfiguration` | Wires the above; fails startup if no `ExternalService` bean exists. |

### `ItemProcessor`

1. If the handle was cancelled → `CANCELLED`.
2. Call `ExternalService.call(key, token, payload, external-call-timeout)` exactly once.
   Interrupted → `INTERRUPTED` (nothing written; the claim expires). Timeout or error →
   step 3 with a failure.
3. Persist with the claim token: `complete(resultValue)` on success, `retryOrFail(error)`
   on failure. Each persist attempt is one operation: the fenced UPDATE and, if it updates
   0 rows, a read-back of the row in the same transaction.
   - 1 row → `COMPLETED` / `RETRY_SCHEDULED` / `FAILED`.
   - 0 rows and the read-back shows this owner's token already in the target state → an
     earlier attempt whose acknowledgement was lost did commit → the corresponding
     success outcome, not `FENCED`. A sweep or revocation changes the token and owner, so
     it never reads back as this owner's write.
   - 0 rows otherwise → `FENCED`.
   - A SQL error is retried up to `completion-retries` times, `completion-retry-delay`
     apart. Still failing, or interrupted while retrying → `ABANDONED` (the handle ends,
     renewal stops, the row becomes eligible for recovery within one lease).

### Configuration (`workqueue.*`)

| Property | Default | IT value |
|---|---|---|
| `expected-namespace` | required, no default | `it` |
| `concurrency` | 16 | 4 |
| `claim-batch-size` | 20 | 20 |
| `lease-duration` | 100s | 30s |
| `renew-interval` / `renew-retry-delay` | 15s / 1s | 1s / 200ms |
| `registration-allowance` (G) | 1s | 200ms |
| `idle-poll-interval` | 1s (± 50% jitter) | 100ms |
| `poll-backoff-max` | 30s | 2s |
| `sweep-interval` / `sweep-batch-size` | 30s / 100 | 1s / 100 |
| `supervisor-interval` | 1s | 100ms |
| `max-attempts` | 5 | 5 |
| `retry-backoff` | 5s | 100ms |
| `external-call-timeout` | 30s | 3s |
| `completion-retries` / `completion-retry-delay` | 3 / 1s | 2 / 100ms |
| `max-processing-time` | 120s | 25s |
| `hung-grace` / `hung-task-limit` | 30s / 4 | 2s / 1 |
| `shutdown-grace` / `shutdown-cancel-wait` | 20s / 5s | 2s / 1s |
| `backlog-sample-interval` | 30s | 1s |
| `db-staleness-limit` | 90s | 10s |
| `db.*` and pool size | §5.3 (pool = concurrency + 4) | §5.3 |
| `admin.write-enabled` | false | true (AdminIT only) |

The demo uses the production defaults, so its scenarios exercise the real budget.

### Claim SQL (settled by the Phase 1 spike)

One claim operation, one transaction, two selections in claim order (§5.1):

```sql
-- A: expired claims first (recovery)
SELECT ID, OPERATION_ID, PAYLOAD, CLAIM_TOKEN FROM FINAL TABLE (
  UPDATE (SELECT ID, OPERATION_ID, PAYLOAD, STATUS, OWNER, CLAIM_TOKEN, ATTEMPTS, AVAILABLE_AT, UPDATED_AT
          FROM WORK_ITEM
          WHERE STATUS = 'CLAIMED' AND AVAILABLE_AT <= CURRENT TIMESTAMP AND ATTEMPTS < :max
          ORDER BY AVAILABLE_AT FETCH FIRST :n ROWS ONLY)
  SET STATUS = 'CLAIMED', OWNER = :me, CLAIM_TOKEN = CLAIM_TOKEN + 1, ATTEMPTS = ATTEMPTS + 1,
      AVAILABLE_AT = CURRENT TIMESTAMP + :lease SECONDS, UPDATED_AT = CURRENT TIMESTAMP)
SKIP LOCKED DATA
-- B: same with STATUS = 'PENDING' for the remaining n − a rows
```

This is form A2 of the Phase 1 spike (`docs/claim-sql-spike.md`): `SKIP LOCKED DATA` ends the
outer `SELECT`. Db2 12.1 rejects it at the end of the `UPDATE` inside `FINAL TABLE` or of
the inner fullselect (SQLCODE -104). The lock-then-update fallback
(`SELECT ... WITH RS USE AND KEEP UPDATE LOCKS SKIP LOCKED DATA`, then `UPDATE` by `ID`) is
not stable: on a fresh table its `UPDATE` waited for the locked row until the lock timeout;
after other ITs had used the table, it skipped it. A2 behaved correctly in every run.
`ClaimSqlSpikeIT` fails unless A2 is accepted, skips locked rows, and takes the oldest rows
first while another claim holds a lock; `WorkItemRepositoryIT` asserts the same for the
repository's statement. The `Sweeper` uses the same form without `ORDER BY`. Both
selections share the operation's `T_tx`.

## 7. Guarantees and time bounds

### Guarantees

1. **Fenced final writes (strict).** Only the current claim of a CLAIMED row — matching
   `ID`, `CLAIM_TOKEN` and `OWNER` — can renew, complete or fail it. Revocation (§9.7)
   ends a claim immediately; a sweep ends an expired, exhausted claim the same way
   (token + 1, no owner).
2. **Bounded attempts (strict).** Between two replays a row is claimed at most
   `max-attempts` times, with at most one external call per claim.
3. **Bounded claim duration (strict).** No claim is renewed past `max-processing-time`.
   A claim whose task ended, was cancelled, or was lost is not renewed by any later round.
4. **Effects** (under the §5.4 contract):
   - at most one effect per `OPERATION_ID`;
   - exactly one for a DONE operation: DONE requires a call that returned a result, and
     the dedupe applies it at most once;
   - zero or one for a FAILED operation: every attempt may have ended before its call
     (crash before invocation, uncertain claim commit, cancellation), or an effect may
     have been applied whose response was lost;
   - calls per row between two replays: zero to `max-attempts`; a DONE row had at least
     one. Repeats are visible as `REPLAYED` in the demo, and the verify report lists
     FAILED rows that have an applied effect as replay candidates.
5. **Liveness.** While at least one healthy instance runs and Db2 is reachable, every row
   reaches DONE or FAILED.
6. **Bounded resources (strict).** Per instance: ≤ `concurrency` task threads holding
   permits (hung ones included), registry size ≤ `concurrency`, DB connections ≤ pool size.

### Time bounds

Every time value here is a **conditional target pending validation** (Phase 1 spike, the
§5.3 timing argument, `LeaseSimulationTest`, real-driver and load tests), not a proven
guarantee. Values fall into two groups:

- **Configuration targets** (E1–E5, T1, T2) follow from the timing argument, the
  configuration, and the stated inputs. Tests assert them.
- **Measured recovery objectives** (C1, C2, T3) also depend on free capacity, claim
  throughput and competing work. Their target formulas come from the simplified model
  below; tests always report them and assert them only where they control that model's
  assumptions.

Recovery stages: **eligible (E)** — the row can be claimed or swept again; **claimed (C)**
— a live instance holds it; **terminal (T)** — DONE or FAILED.

Symbols: `L` lease, `I` renew-interval, `d` renew-retry-delay, `G`
registration-allowance, `W` (§5.3), `P` idle-poll-interval, `M` max-processing-time,
`b` claim-batch-size, `S` sweep-batch-size, `r` affected rows, `Q` older eligible expired
claims ahead of them, `N = Q + r`, `K` non-hung permits across live, healthy instances,
`M_end = M + supervisor-interval + hung-grace`. Tests evaluate every formula on the config
under test.

**Configuration targets**

| ID | Event | Target | Default | IT |
|---|---|---|---|---|
| E1 | owner killed, frozen, or stopped renewing | its claims eligible within `L + W` of the moment the owner stops starting renewal rounds (a write already in flight can still land up to `W` later) | 118s | 35.5s |
| E2 | SIGTERM | process exits within `shutdown-grace + shutdown-cancel-wait + 5s`; leftover claims eligible within `shutdown-grace + shutdown-cancel-wait + L + W` of SIGTERM | 30s / 143s | 8s / 38.5s |
| E3 | task ignores interruption | its claim eligible within `M + W + L` of being claimed; counted hung within `M + hung-grace + supervisor-interval`; liveness DOWN within `hung-grace + supervisor-interval` of the `hung-task-limit`-th task being cancelled | 238s / 151s / 31s | 60.5s / 27.1s / 2.1s |
| E4 | stale owner resumes | its lost claims cancelled within `max(I, W) + d + W` of resuming | 37s | 11.2s |
| E5 | Db2 unreachable for D | **lease-preservation target** (§5.3): no claim is lost if `D ≤ max(d, L − max(I, W) − 4W − d − G)`. Not the longest survivable outage: a longer outage may let claims expire, be re-claimed and be called again, and durable downstream idempotency keeps the effects correct (`DbOutageIT`). After restoration, first successful claim within `poll-backoff-max + W` if the instance has a free permit. | 8s / 48s | 2.1s / 7.5s |
| T1 | eligible and attempts exhausted | FAILED by the `Sweeper` within `E + sweep-interval + ⌈X / S⌉ · W`, where `X` is the number of rows eligible for sweeping; needs one live instance whose sweep transactions succeed | E + 30s + ⌈X/100⌉ · 18s | E + 1s + ⌈X/100⌉ · 5.5s |
| T2 | claimed recovered row | if that attempt completes or fails finally, it does so within `C + M` | C + 120s | C + 25s |

**Measured recovery objectives**

| ID | Situation | Target (not a guarantee) | Default | IT |
|---|---|---|---|---|
| C1 | eligible; at E the live instances have at least `N` free permits | claimed within `E + 1.5·P + ⌈N / b⌉ · W` | E + 1.5s + ⌈N/20⌉ · 18s | E + 0.15s + ⌈N/20⌉ · 5.5s |
| C2 | eligible; live capacity busy, `K ≥ 1` | claimed within `E + ⌈N / K⌉ · M_end + ⌈N / b⌉ · W` | E + ⌈N/K⌉ · 151s + ⌈N/20⌉ · 18s | E + ⌈N/K⌉ · 27.1s + ⌈N/20⌉ · 5.5s |
| T3 | a retry is scheduled | depends on the older claimable backlog and throughput (PENDING rows are served oldest-available first); no target, reported by the load tests (§11.4) | — | — |

**Claim-throughput model behind C1 and C2.**

- A poll loop claims at most `min(its free permits, b)` rows per claim transaction and runs
  its transactions one after another; loops on different instances run in parallel. The
  model assumes full batches with all free capacity on one instance, which gives
  `⌈N / b⌉` sequential claim transactions of at most `W` each. This is the full-batch
  model count, not a worst case: underfilled batches (below) need more transactions.
- C1 adds the idle poll loop's wake-up, at most `1.5·P`.
- C2 adds `⌈N / K⌉` capacity waves: with no hung tasks, every task releases its permit
  within `M_end` of starting, and the next claim on that instance takes recovered rows
  before any PENDING row.

Not modeled — which is why C1 and C2 are objectives:

- a claim that skips rows locked by another claimer and returns empty sends its loop to
  idle sleep (up to `1.5·P` each time);
- permits released one at a time can make each claim transaction take a single row;
- claim failures, capacity lost during recovery, hung tasks, and an unknown `Q`.

Tests assert C1/C2 only where they fix `K`, `N`, `b`, and a downstream that honours
timeouts (§11.3, §11.4).

## 8. Failure handling

| Scenario | Behaviour | Effect on the downstream |
|---|---|---|
| **kill -9 / power loss** | Renewal stops; claims eligible (E1), claimed (C1/C2); attempts stay consumed. | A repeat call is deduped (`REPLAYED`). |
| **Freeze > lease** | Others re-claim. On resume, lost claims are cancelled (E4); late writes are fenced. | Stale calls are deduped. |
| **Graceful stop** | §5.2 stop sequence (E2). | Calls cancelled at the deadline, or whose completion had not committed, are deduped when the row is re-claimed. |
| **Effect applied, completion not committed** | The row stays CLAIMED until eligible, then is re-claimed. | The repeat call returns the stored result; the row ends DONE with the original result. |
| **Completion acknowledgement lost** | The persist retry updates 0 rows; the read-back recognises this owner's committed write → correct outcome. | — |
| **Claim fails or its commit outcome is unknown** | Nothing registered, all permits returned, backoff; any committed rows expire unrenewed (E1) with one attempt consumed. | No call was made for those claims. |
| **Completion cannot be persisted** | `ABANDONED` after `completion-retries`; not renewed; eligible within E1. | Deduped on re-claim. |
| **Task ignores interruption / hangs** | Deadline cancel stops renewal (E3); the hung thread keeps its permit; liveness DOWN at the limit → restart. | Deduped. |
| **Transient processing failure** | `retryOrFail`: retry after `retry-backoff`, or FAILED at `max-attempts`. | Failure before the effect: nothing applied. Response lost after the effect: the retry returns the stored result. |
| **Poison row** | Each claim consumes an attempt; FAILED at `max-attempts` by `retryOrFail` or the `Sweeper` (T1). | ≤ `max-attempts` calls. |
| **Db2 unreachable / network stalled** | Every operation fails within W. Poll backs off. Claims are kept for outages below the E5 target; longer outages may let claims expire and be re-claimed after restoration. Idle instances report readiness DOWN after `db-staleness-limit`. A statement cut off by the close-socket query timeout also makes Spring log "Application exception overridden by rollback exception" at ERROR, because the rollback fails on the closed connection; this is expected and kept (a timed-out operation deserves an ERROR), and the runbook says so. | Repeated calls after a long outage are deduped. |
| **Simultaneous claims** | `SKIP LOCKED DATA`; no overlap (`ConcurrentClaimIT`). | — |
| **Operator revokes an owner** | Its CLAIMED rows get a new token and leave CLAIMED in one statement; the owner's next write is fenced and its next renewal reports them lost. | Its in-flight calls are deduped. |
| **Db2 restored from backup** | Procedure in §9.7: quiesce, restore, restart. Restored rows keep their `OPERATION_ID` and are processed again. | Deduped (retention precondition, §5.4). Rows lost by the restore are re-enqueued by the producer with their original `OPERATION_ID`. |

## 9. Operations and deployment

### 9.1 Modes

| Mode | Where | What | DB user |
|---|---|---|---|
| migrate | one-shot job (any Flyway runner; the demo app has a `migrate` profile) | Flyway migrate `db/migration/workqueue` (+ `db/migration/demo` in the demo), with placeholder `workqueueNamespace` | `WQ_MIGRATOR` (DDL) |
| worker | long-running replicas | engine; `spring.flyway.enabled=false`; `SchemaCheck` fails fast on a missing migration or a namespace mismatch | `WQ_APP` |
| seed / verify | demo app only | §10 | demo user |

Deploy order: migrate job → workers. Rolling deploys are safe (drain-then-expire). Scale by
changing replica count.

### 9.2 Least privilege (`docs/grants.sql`)

- `WQ_MIGRATOR`: DDL on the schema; used only by the migrate job.
- `WQ_APP`: `SELECT` on `WORK_ITEM` and `WORK_QUEUE_META`; column-level
  `UPDATE (STATUS, AVAILABLE_AT, OWNER, CLAIM_TOKEN, ATTEMPTS, RESULT_VALUE, LAST_ERROR, UPDATED_AT)`
  on `WORK_ITEM`; `SELECT` on the Flyway history table. No `INSERT`, `DELETE`, DDL, or
  `UPDATE` of `OPERATION_ID`/`PAYLOAD`.
- Upstream producer: `INSERT` on `WORK_ITEM`.
- Demo only: the demo worker user additionally needs `SELECT, INSERT` on
  `SIMULATED_EFFECT` and `EXECUTION_LOG`.

### 9.3 Credentials

- No secrets in the repository. The demo's `docker-compose.yml` reads the Db2 password
  from `.env` (git-ignored); `.env.example` has placeholders; `db-up.sh` generates a
  random password into `.env` if missing.
- Apps read DB and management credentials from environment variables or mounted secret
  files (`spring.config.import=optional:configtree:/run/secrets/`).
- Logs never contain credentials, `PAYLOAD`, `RESULT_VALUE` or idempotency keys.

### 9.4 Endpoints

- The worker has no application endpoints; its only HTTP surface is actuator, bound to
  `127.0.0.1` by default in the demo (`server.address`).
- Demo security config (production hosts provide equivalent):
  - `/actuator/health/liveness`, `/actuator/health/readiness`: anonymous, no details.
  - `/actuator/prometheus`, `GET /actuator/workqueue`: role `WQ_VIEWER`.
  - Write operations on `/actuator/workqueue`: role `WQ_OPERATOR`, and only when
    `workqueue.admin.write-enabled=true`.
- Credentials for these roles come from environment variables or secret files (§9.3).

### 9.5 Seeding isolation

- Seeding code exists only in `work-queue-demo` (§3).
- Defence in depth: the seeder refuses to run unless the connected database name
  (`CURRENT SERVER`) is in `demo.seed.allowed-databases` (default `WORKQ`; empty means
  refuse). Deleting existing rows additionally requires `demo.seed.reset=true`.
- Seeding never runs DDL; schema comes from the migrate mode. Seeded rows get random
  UUID `OPERATION_ID`s.

### 9.6 Monitoring

**Metrics** (Micrometer, `workqueue.*`):

| Metric | Type | Meaning |
|---|---|---|
| `claims`, `claim.duration`, `claim.errors` | counter, timer, counter | claim operations (an empty claim counts as a success) |
| `outcomes{outcome}` | counter | one per task end |
| `call.duration{result=ok\|error\|timeout}` | timer | external calls |
| `renewal.duration`, `renewal.errors` | timer, counter | renewal rounds that ran |
| `renewal.lag` | gauge | max over renewal-eligible claims of the time since that claim's last successful lease write (claim or renewal); **0 when there are none** |
| `claims.lost` | counter | claims reported lost by renewal |
| `db.last_success_age` | gauge | time since any engine DB operation succeeded; kept fresh on idle instances by the poll loop's empty claims, the sweeper and the backlog sampler |
| `inflight`, `permits.available`, `tasks.hung` | gauges | local capacity |
| `registration.late` | counter | handles registered more than `registration-allowance` after their claim returned (a process pause the B2 proof does not cover) |
| `invariant.violations` | counter | engine invariant breaches, e.g. a registry key collision |
| `backlog{status}`, `backlog.oldest_pending_age`, `claims.expired` | gauges (sampled) | DB-wide; `claims.expired` = CLAIMED rows expired for more than one lease (nobody is picking them up) |

**Health:**

- Liveness DOWN when `tasks.hung ≥ hung-task-limit`, when `invariant.violations > 0`, or
  when the poll, renewal or supervisor thread has died.
- Readiness DOWN during stop; before `SchemaCheck` passes; when `renewal.lag > L` (the
  instance is losing claims — only possible while it holds claims; a single failed round,
  which B2 tolerates, does not trip it); or when
  `db.last_success_age > db-staleness-limit` (Db2 unreachable, idle or not). An idle,
  healthy instance stays ready indefinitely.

**Alerts** (`docs/alerts.yml`, each linked to a runbook section):

| Alert | Condition | First action |
|---|---|---|
| BacklogStalled | pending > 0 and completed rate = 0 for 5m | check replicas, Db2, downstream |
| ExpiredClaims | `claims.expired > 0` for 2 × lease | no live workers, or all hung |
| FailedRowsRising | `backlog{status=failed}` increased in 15m | inspect `LAST_ERROR`, fix cause, replay |
| HungTasks | `tasks.hung > 0` | downstream ignoring timeouts; stack traces in logs |
| LostClaimsHigh | lost / claims > 1% over 15m | GC pauses, Db2 latency, or lease too short |
| RenewalLagging | `renewal.lag > L / 2` for 1m | Db2 latency or connectivity (fires only while claims are held) |
| DbUnreachable | `db.last_success_age > db-staleness-limit / 2` | Db2 connectivity (fires on idle instances too) |
| AbandonedClaims | abandoned rate > 0 for 10m | Db2 write failures |
| RegistrationLate | `registration.late` increased | process pauses (GC, CPU starvation); lease budget at risk |
| InvariantViolation | `invariant.violations > 0` | engine bug: capture logs; the instance restarts via liveness |
| OldestPendingAge | above the SLO | scale out |

### 9.7 Recovery and replay (`docs/runbook.md`)

- **Replay FAILED rows:** `POST /actuator/workqueue` operation `replay` with a filter
  (`ids`, `lastErrorContains`, `failedBefore`) and `dryRun` (default `true`). Dry run
  returns the matching count only. Execute sets `STATUS='PENDING', ATTEMPTS=0,
  AVAILABLE_AT=CURRENT TIMESTAMP, OWNER=NULL` for matching FAILED rows; `CLAIM_TOKEN` and
  `OPERATION_ID` are unchanged. Each execution is logged with the operator principal,
  filter and count. Safe under §5.4: a replayed operation whose effect already applied
  receives the stored result.
- **Revoke an owner:** operation `revokeOwner(owner, dryRun)`. One statement for that
  owner's CLAIMED rows: `CLAIM_TOKEN = CLAIM_TOKEN + 1, OWNER = NULL,
  AVAILABLE_AT = CURRENT TIMESTAMP`, and `STATUS = 'PENDING'` (or `'FAILED'` with
  `LAST_ERROR = 'revoked; attempts exhausted'` when `ATTEMPTS ≥ max`). Once it commits,
  every write by the old owner is fenced (token, owner and status all changed) and its
  next renewal reports the rows lost. Row locks serialise it with the owner's own writes:
  whichever commits first wins. Use it to recover a dead or wedged instance's rows
  without waiting for lease expiry.
- **Restore from backup:** scale workers to 0 → restore → verify `WORK_QUEUE_META` →
  start workers. Restored rows keep their `OPERATION_ID`s and are reprocessed with
  deduped effects. Rows inserted after the backup point are gone and must be re-enqueued
  by the producer with their original `OPERATION_ID`s. Never regenerate or reassign
  `OPERATION_ID`s during restore, migration or copy. Quiescing first, together with
  `OWNER` in fenced writes, keeps pre-restore processes from touching restored rows.
- **Db2 time zone:** the Db2 instance must run in UTC (§5.1). Check it before the first
  deployment and after any change to the Db2 host or instance configuration.
- **Copy data to another environment:** set that environment's own
  `WORK_QUEUE_META.NAMESPACE` before starting its workers; they refuse to start otherwise.
- Procedures for: backlog stalled, expired claims, hung tasks, Db2 outage, poison rows
  (inspect, fix, replay), changing lease/timeouts (budget validator runs at startup),
  rolling deploy and termination grace.

## 10. Demo harness (`work-queue-demo`)

- **Simulated downstream** (`ExternalService` implementation), keyed by `key.value()`:
  1. Sleep a random latency in `[50ms, 500ms]`, capped by the timeout; interruption →
     `INTERRUPTED`, exceeding the timeout → `TIMED_OUT` (both apply no effect).
  2. With probability `fail-before-effect` (default 2%) → `FAILED`, no effect.
  3. Decide `responseLost` with probability `fail-after-effect` (default 2%). In one
     transaction: insert into `SIMULATED_EFFECT`, or on duplicate key read the stored
     result; insert the `EXECUTION_LOG` entry with outcome `RESPONSE_LOST` if
     `responseLost`, else `REPLAYED` or `APPLIED`.
  4. After commit, if `responseLost`, throw: the effect is applied but the caller sees a
     failure. This is the case that shows why the idempotency contract is needed.
- **Profiles:** `migrate`, `worker` (default), `seed` (guarded, §9.5), `verify`.
- **Scripts:** `db-up.sh`, `seed.sh [N]`, `start.sh [k]`, `signal.sh SIG i|random`,
  `status.sh` (polls `GET /actuator/workqueue` with viewer credentials), `verify.sh`,
  `replay.sh` and `revoke.sh` (dry run unless `--execute`), `stop-all.sh` (SIGTERM).
- **Scenarios** (each seeds, runs, waits until PENDING + CLAIMED = 0, verifies, stops):
  `scenario-drain.sh` (scale 2 → 4), `scenario-crash.sh` (kill -9 one of 3),
  `scenario-stale.sh` (SIGSTOP one of 3 for lease + 15s, then SIGCONT),
  `scenario-chaos.sh` (all combined; Phase 5).
- **Verify report** — invariants (exit 1 on violation), joining on
  `IDEMPOTENCY_KEY = NAMESPACE || ':' || OPERATION_ID`:
  - no PENDING or CLAIMED rows remain;
  - no operation has more than one `SIMULATED_EFFECT` row (also structural: primary key);
  - every DONE row has exactly one `SIMULATED_EFFECT` row, with the same `RESULT_VALUE`;
  - every DONE row has an `EXECUTION_LOG` entry with its final `CLAIM_TOKEN` and outcome
    `APPLIED` or `REPLAYED`;
  - every row has `ATTEMPTS ≤ max-attempts` and `CLAIM_TOKEN ≥ ATTEMPTS`;
  - no two `EXECUTION_LOG` entries share `(IDEMPOTENCY_KEY, CLAIM_TOKEN)`.

  Reported: counts per status and outcome, repeated calls (`REPLAYED`), FAILED rows with
  an applied effect (replay candidates) and without one, rows DONE per instance,
  throughput.

## 11. Testing and validation

ITs use the IT column of §5.3/§6, wait with Awaitility, and never use fixed sleeps. Lease
expiry is forced with SQL where a test is not about expiry timing. Unit tests (`*Test`)
run in Surefire; ITs (`*IT`) in Failsafe with one shared, reusable Db2 container. Time
bounds are asserted with the formulas of §7 evaluated on the test's config.

### 11.1 Unit and lifecycle-race tests (no Db2)

`QueueRunner` takes an injectable repository, thread starter and clock, so races are
driven deterministically with latches; every scenario ends by asserting the permit
invariant.

- `ClaimHandleTest`: `finish()` exactly once under concurrent finish/cancel (10 000
  iterations released together by a barrier); value-aware removal; cancel never releases
  the permit.
- `QueueRunnerLifecycleTest`:
  - **claim throws** → every held permit returned, nothing registered, backoff applied;
  - **uncertain claim outcome** (repository returns rows, then throws as if the commit
    acknowledgement was lost) → every held permit returned, nothing registered or renewed;
  - **exception constructing the handle for row *j* of *k*** → rows before *j* run
    normally; the permits for *j..k* are returned by the poll loop;
  - **after transfer: registration throws, a key collision, or thread start fails** →
    `handle.finish()` releases exactly that permit; the other rows are unaffected; a
    collision also increments `invariant.violations` and turns liveness DOWN;
  - **interrupt during `acquire` and during the claim** (stop) → held permits returned;
  - cancel before the body runs → processor never invoked, `finish()` once;
  - old handle `(id, t1)` ends after the row was re-claimed as `(id, t2)` → `(id, t2)`
    stays registered and renewed;
  - a cancelled task that ignores interrupts keeps its permit until it exits; the
    running-task high-water mark never exceeds `concurrency`;
  - renewal excludes ended, cancelled and past-deadline handles; an abandoned claim is
    not renewed on the next round;
  - supervisor: deadline → cancel; `hung-grace` → hung gauge; `hung-task-limit` →
    liveness DOWN and the poll loop stops claiming;
  - `stop()`: no claims after stop begins, renewal continues while draining, cancel at
    the grace deadline, nothing released.
- `RenewalScheduleTest`: `next(s, e, ok)` for success, overrun and failure.
- `TimingBudgetTest`: each of B1–B5 rejects a violating config and names itself;
  `I = 15s, W = 5s, d = 1s, G = 1s, L = 26s` is rejected by B2; `W = 18s` is rejected by
  B2 with a 60s lease and accepted with the default 100s lease; the IT config passes;
  E5 is 1s (= d) with a 90s lease and 8s with the default lease.
- `LeaseSimulationTest` — **evidence for B2 and E5**, supplementing the §5.3 argument
  (a finite grid, not a proof of every execution). A discrete-event model (10ms steps) of
  one instance's lease timeline using the production `RenewalSchedule.next`:
  - claim operation start `c`; its lease-setting write anywhere in `[c, c + W]`; commit
    acknowledgement anywhere in `[write, c + W]`; registration anywhere in
    `[ack, ack + G]`;
  - renewal rounds with durations in `[0, W]`, snapshot at round start, lease-setting
    write anywhere within a successful round;
  - an outage fails every round it overlaps, including one it begins in just before the
    round ends, and a failed round takes any time from 0 to `W`;
  - the model reduces a failed outage round's duration to the earliest it can fail (0, or
    the outage's start for the round the outage begins in), `W`, or the duration that
    makes the next round start on the outage's last step, and a cross-check test compares
    this with every duration on small configurations;
  - every claim phase relative to the renewal schedule, with every combination of the
    extreme durations above.

  Properties, over a grid of `(I, W, d, G, L)` plus the default and IT configs:
  - every config accepted by B2 keeps every claim's lease unexpired when exactly one
    renewal round fails, at every position;
  - for every accepted config, an outage no longer than the E5 target, starting at every
    offset, loses no claim;
  - both targets are tight: a config B2 rejects loses a claim to one failed round, and an
    outage two steps (20ms) longer than E5 loses a claim on every tested configuration.
    At the defaults and IT the first loss is at E5 + 20ms (E5 is conservative by 10ms), and
    where E5 = d it can be at E5 + 10ms. E5 is not tight when `d < E5 < 2d` (§5.3), and
    none of the configurations these checks use lies in that band;
  - maintained claims (not just new ones) satisfy both properties.
- `IdempotencyKeyTest`: `("a:b", "c")` is rejected (namespace contains `:`);
  `("a", "b:c")` is accepted; namespaces outside `^[a-z0-9][a-z0-9-]{0,31}$` and empty or
  over-long operation ids are rejected; for generated valid pairs, distinct pairs always
  give distinct `value()`s, and splitting `value()` at the first `:` recovers the pair.
- `WorkQueueHealthTest` (fake clock): an idle instance with no claims stays ready for
  10 × lease and reports `renewal.lag = 0`; one failed renewal round keeps readiness UP;
  an eligible claim unrenewed for more than `L` → readiness DOWN; `db.last_success_age > db-staleness-limit` with no claims →
  readiness DOWN; both recover.
- `ItemProcessorTest`: each `Outcome`; 0 rows with this owner's committed write →
  success outcome, not `FENCED`; exactly one call; timeout passed through; never throws.
- `SimulatedDownstreamTest`: repeat key returns the stored result; `RESPONSE_LOST`
  leaves the effect applied; timeout and interrupt apply nothing.

### 11.2 Db2 integration tests

**Phase 1 — claim contract and spike**

1. `ConcurrentClaimIT` — lease 5 min; 16 threads with distinct owners run
   `claim → complete each row with its token` until a claim returns empty. Every
   complete updates exactly 1 row; the claimed IDs are exactly the 1000 seeded; none
   claimed twice; more than one thread received rows.
2. `WorkItemRepositoryIT` — per operation:
   - claim fields and predicate (`ATTEMPTS`, future `AVAILABLE_AT`);
   - claim order: expired CLAIMED rows before PENDING; PENDING by `AVAILABLE_AT`;
   - claim skips rows locked by a concurrent claim without waiting, still oldest first;
   - reclaim after forced expiry with the next token;
   - renew returns exactly the matching CLAIMED pairs of this owner;
   - fenced writes with a stale token **or a different owner** update 0 rows;
   - the persist read-back distinguishes "own write already committed" from "fenced";
   - sweep bumps the token and clears the owner; the swept owner's late renew, complete and
     retryOrFail are fenced;
   - replay resets `ATTEMPTS` and keeps `CLAIM_TOKEN` and `OPERATION_ID`;
   - revokeOwner bumps the token, clears the owner, and sets PENDING or FAILED by `ATTEMPTS`.
3. `StaleCompletionIT` — A claims, expiry forced, B claims; A's renew reports lost, A's
   complete is fenced, B's result is stored.
4. `QueryTimeoutIT` — a deliberately slow statement returns within `T_tx + 1s` with
   close-socket interrupt mode; a lock wait returns a lock-timeout error within
   `T_lock + 1s`.
5. `RevokeRaceIT` — on separate connections, asserting the valid serialized outcomes:
   - **revoke vs complete** (also vs retryOrFail): exactly one of the two updates the
     row. Either the owner's write committed first (row DONE with its result, revoke
     updated 0 rows), or revoke committed first (row revoked with token + 1, the owner's
     write updated 0 rows).
   - **revoke vs renew**: revoke always succeeds. Renew updated 1 row if it serialized
     first, or 0 rows if second; both are valid. The final row is always revoked
     (token + 1, `OWNER` NULL, PENDING or FAILED).
   - **after either race**, every further write by the old owner (renew, complete,
     retryOrFail) updates 0 rows.
   - Each race runs 200 times, released together by a barrier, plus the two fixed orders.

Also in Phase 1: `ClaimSqlSpikeIT` records the candidate claim forms (§6) and fails unless
A2 keeps its three properties; `WorkItemSchemaIT` covers the V1 constraints, including the
`OPERATION_ID` format (including overlong ids ending in blanks, with and without an existing
64-character id), exact-identity uniqueness and case sensitivity (§4, §5.4).

**Phase 2 — runtime contracts**

Phase 2 runs before the demo module exists (Phase 3). Its ITs use a test-scope
`RecordingDownstream` in `work-queue-engine`: an in-memory `ExternalService` keyed by
`key.value()` with the §10 semantics (the first result is stored and returned to every
repeat; injectable fail-before-effect, fail-after-effect, blocking and
interrupt-ignoring behaviour) and a call log with the `EXECUTION_LOG` outcomes of §4.
Where ITs 6–12 name `EXECUTION_LOG`, `REPLAYED` or `INTERRUPTED`, they assert on that call
log. The demo's durable simulated downstream (§10) arrives in Phase 3, and the
process-level tests use it.
Toxiproxy (ITs 6 and 12) proxies to the Db2 container's host-mapped port instead of
joining a Testcontainers `Network`, so that the reused Db2 container is not recreated; a
short spike at the start of the Phase 2 ITs confirms this works under Rosetta emulation.

6. `ClaimFailureIT`:
   - ordinary failure: the claim statement fails (Toxiproxy reset) → permits returned,
     nothing registered, backoff, the next claim succeeds;
   - **uncertain commit**: a connection wrapper lets the commit reach Db2, then throws a
     communication error → permits returned; the committed rows are CLAIMED by this owner
     but never renewed or called; eligible within E1; re-claimed by a second instance; no
     `EXECUTION_LOG` entry for the orphaned token; `ATTEMPTS` consumed.
7. `AbandonmentIT` — completion of one row always throws; `ABANDONED`; not renewed;
   re-claimed by a second idle instance (free permits, N = 1) within E1 plus the C1
   target; its call is `REPLAYED`; the row
   ends DONE with the original result.
8. `StaleOwnerIT` — one instance's renewal paused while its calls block past the lease;
   others re-claim; on resume its claims are cancelled within E4; writes fenced;
   invariants hold.
9. `PoisonRowIT` — each claimant of the poison row is `crash()`ed and replaced; the row
   ends FAILED with `ATTEMPTS = max-attempts` and ≤ `max-attempts` calls, within T1 of
   the last claim becoming eligible.
10. `GracefulShutdownIT`:
    - drain — calls shorter than the grace period: no claims after stop, nothing left
      CLAIMED by this owner;
    - deadline — a call longer than the grace period: row left CLAIMED, eligible within E2,
      `ATTEMPTS` kept, call logged `INTERRUPTED`;
    - **effect applied before completion commit** — `complete` is held by a latch in a
      repository wrapper; stop → grace passes → handle cancelled; the row stays CLAIMED,
      becomes eligible, is re-claimed by another instance whose call is `REPLAYED`; the
      row ends DONE with the original result; exactly one effect.
11. `HungTaskIT` — a downstream that ignores interrupts: renewal stops at the deadline;
    eligible within E3; a second instance completes the row (`REPLAYED`); hung gauge = 1;
    permit still held; liveness DOWN at the limit.
12. `DbOutageIT` (Toxiproxy):
    - stall: every operation fails within W + 1s;
    - short outage (1.5s, below the IT E5 target of 2.1s): no claims lost;
    - long outage (> lease): claims expire and are re-claimed after restoration; first
      claim within E5; repeated calls are `REPLAYED`; no operation has more than one
      effect, and each DONE operation has exactly one;
    - idle instance: ready while Db2 is reachable for 3 × lease with no claims; readiness
      DOWN after `db-staleness-limit` of outage; ready again after restoration;
    - invariants hold after each case.

**Phase 3 — operations**

13. `MigrationIT` — migrate on an empty database writes the namespace placeholder into
    `WORK_QUEUE_META`; `SchemaCheck` fails fast when the migration is missing or
    `expected-namespace` differs; a worker running as a restricted user with exactly the
    §9.2 grants (created in the container) processes a batch, and that user cannot update
    `OPERATION_ID` or `PAYLOAD`.
14. `AdminIT` — replay and revokeOwner through the endpoint: dry run changes nothing and
    reports the count; execute behaves as in §9.7; a replayed operation whose effect
    exists ends DONE with the stored result; write operations rejected when disabled.
15. `SecurityIT` — probes anonymous; read requires `WQ_VIEWER`; writes require
    `WQ_OPERATOR`; any non-actuator path is rejected.
16. `SeedGuardIT` — refuses a non-allowlisted database; refuses reset without the flag.
17. `VerifyReportIT` — crafted states violating each invariant → exit 1; clean → exit 0.

### 11.3 Process-level tests (real JVMs, Phase 4)

Run in Failsafe after `package`; they launch the demo jar with `ProcessBuilder` against
the Testcontainers Db2.

- `ProcessCrashIT` — 3 workers, 3000 rows; `destroyForcibly()` (SIGKILL) one at ~30%.
  From Db2 timestamps: every row it held is eligible within E1 and claimed within the C2
  target, whose model assumptions the test fixes (K = 2 survivors × concurrency 4,
  N = r ≤ 4, b = 20, a downstream that honours timeouts so no task hangs); drain; verify
  invariants. Actual times are reported.
- `ProcessGracefulIT` — SIGTERM (`destroy()`) mid-run; asserts E2 and verify invariants.
- `ProcessFreezeIT` — `kill -STOP` / `kill -CONT` on one worker's PID; asserts E4,
  fenced writes, and verify invariants.

### 11.4 Load validation (opt-in `-Pload`, Phase 5)

- `SustainedLoadIT` — 4 worker processes × concurrency 16, 300 000 rows (about 30 min),
  latency 50–500ms, 2% fail-before, 2% fail-after. Samples actuator metrics every 10s to
  `target/load-report.csv`. Pass criteria:
  - verify invariants hold;
  - registry size and running task threads ≤ `concurrency` in every sample;
  - Hikari active ≤ pool size; Hikari pending = 0 in ≥ 99% of samples;
  - renewal round p99 < `I / 2`; claim p99 < `T_tx`;
  - `claims.lost = 0`, `outcomes{abandoned} = 0`, `tasks.hung = 0` (no faults injected,
    so any non-zero value is a false lease loss or a leak);
  - old-gen heap after GC at the end ≤ 1.1 × its value at minute 5;
  - throughput ≥ 50% of theoretical (`instances × concurrency / mean latency`). The
    measured baseline is recorded in the README; a miss is investigated (Db2-bound or
    engine-bound) rather than the target lowered.
  - Reported, not asserted: time from first eligibility to terminal state (p50, p99, max)
    for rows that needed retries (T3); FAILED operations with and without an applied
    effect.
- `LoadWithFaultsIT` — the same load plus a kill -9 and restart of one worker every
  5 min and one 25s Toxiproxy outage. Kills are spaced so the previous recovery has
  finished (Q = 0, N = r ≤ 16), K = 3 × 16, b = 20, and the downstream honours timeouts,
  so each kill must meet E1 and the C2 target. The 25s outage exceeds the 8s E5 target:
  claims may be re-claimed, and the test asserts at most one effect per operation, exactly
  one per DONE operation, and first claim within E5's restoration bound. Time-to-claim and time-to-terminal are reported. Invariants hold at
  the end.

Load results on emulated Db2 are a regression baseline, not a production capacity claim.

## 12. Delivery phases and approval gates

| Phase | Scope | Gate |
|---|---|---|
| 1. Claim contract (gate passed 2026-09-25) | aggregator + engine skeleton, V1 migration, `WorkItemRepository` (incl. revokeOwner and read-back), timed transactions, claim/renew SQL spike | ITs 1–5 |
| 2. Runtime contracts (approved to start) | `ClaimHandle`, `QueueRunner` (poll, renewal, supervisor), `ItemProcessor`, `Sweeper`, `TimingBudget`, `RenewalSchedule`, metrics, health | §11.1 (incl. `LeaseSimulationTest`), ITs 6–12 |
| 3. Operations | auto-configuration, `SchemaCheck`, admin, demo app, security, credentials, seed guard, verify, runbook, alerts | ITs 13–17 |
| 4. Process evidence | process-level ITs, scripts, drain/crash/stale scenarios | §11.3 |
| 5. Load | load ITs, baseline, `scenario-chaos.sh` | §11.4 |

**Production approval** requires phases 1–5 green, the §5.3 timing argument reviewed,
`LeaseSimulationTest` passing, and the real-driver and load tests meeting the §7
configuration targets and the C1/C2 targets where asserted. Default timeouts are tightened
only on that evidence.

## 13. Risks

| Risk | Mitigation |
|---|---|
| `SKIP LOCKED DATA` placement, or ordering combined with it, differs from expectation | Settled by the Phase 1 spike: form A2 combines both on Db2 12.1 (§6). `ClaimSqlSpikeIT` and `WorkItemRepositoryIT` fail if a Db2 upgrade changes that. |
| `FINAL TABLE` over a searched UPDATE, or query-timeout close-socket mode, behaves unexpectedly | `WorkItemRepositoryIT` and `QueryTimeoutIT` in Phase 1. |
| Simulating a lost commit acknowledgement reliably | A connection wrapper (commit, then throw) rather than network timing. |
| The lease timing argument misses an interleaving | `LeaseSimulationTest` adds evidence over a finite grid of interleavings; `SustainedLoadIT` requires `claims.lost = 0`; `registration.late` exposes pauses the argument excludes. |
| Tolerant defaults slow recovery (E1 118s) | Accepted initially; tightened only on Phase 1 and load-test latency evidence under contention. |
| Lock escalation to a table lock | Claim batch ≤ 100, short transactions; `LOCKLIST`/`MAXLOCKS` guidance in the runbook. |
| Creating a restricted Db2 user in the container (OS-level users) | `MigrationIT` creates it with `execInContainer`; if impractical, the least-privilege check moves to a documented manual step. |
| Db2 server time zone observes daylight saving | UTC precondition (§5.1); `SchemaCheck` rejects a non-zero `CURRENT TIMEZONE`; runbook check (§9.7). |
| Emulated Db2 is slow or unrepresentative | Reusable container; load numbers treated as a baseline only. |
| Producer or downstream breaks the §5.4 contract (reused `OPERATION_ID`, short dedupe retention) | Documented deployment preconditions; unique constraint and column grants catch reuse and mutation inside this table only. |

## 14. Out of scope

- Downstreams without durable idempotency; producers that cannot supply a stable,
  never-reused `OPERATION_ID`.
- Reprocessing DONE rows (replay covers FAILED rows only).
- Retention and archival of DONE/FAILED rows — the table owner's responsibility (subject
  to §5.4: archived `OPERATION_ID`s must still never be reused).
- Runtime-adjustable per-instance concurrency.
- Multi-tenancy or multiple logical queues per table.
- Web dashboard.

## 15. Change history

**Revision 2 (first review):** renewal limited to registered `(id, token)` claims;
drain-then-expire shutdown instead of release + refund; bounded JDBC waits;
`ConcurrentClaimIT` made immune to expiry; components merged into `QueueRunner`;
`LeaseGuard` removed.

**Revision 3 (second review):** production-ready engine + demo harness in separate
modules; per-claim virtual threads with `ClaimHandle` exactly-once cleanup; per-operation
timeouts and startup budget checks; processing deadline and hung detection; durable
downstream idempotency required; `ATTEMPTS` restored; migrations, least privilege,
secrets, secured endpoints, monitoring, runbook; lifecycle-race, process-level, network
and load validation.

**Revision 4 (third review):**

| Change | Reason |
|---|---|
| Poll-loop permit rule (`held` counter, `finally` release) and "uncertain claim = nothing claimed" | Permits acquired before claiming leaked on claim exceptions, uncertain commits and interruption. |
| Upstream-assigned immutable `OPERATION_ID` + namespace in `WORK_QUEUE_META`; key = `NAMESPACE:OPERATION_ID`; lifetime, restore and copy rules; column-level grants | A numeric identity can identify different work after recreation, restore or in another deployment, returning an unrelated old result. |
| `OWNER` added to every fenced write | A restored table can repeat `(ID, CLAIM_TOKEN)` pairs. |
| `expireOwner` replaced by `revokeOwner` (token bump + status change in one statement) | Changing only `AVAILABLE_AT` left the owner's token valid. |
| `RenewalSchedule` defined; B2 = `max(I, W) + 2W + d < L`; `renew-retry-delay`; schedule-simulation test | The old B2 ignored the wait for the next tick after an early-finishing round. IT lease raised to 20s to satisfy it. |
| Time bounds split into eligible / claimed / terminal with stated assumptions; claim order gives recovered rows priority, PENDING oldest first | The old R1 overpromised: exhausted rows need the sweeper, and claiming needs capacity. |
| `renewal.lag` (0 without claims) and `db.last_success_age` replace renewal freshness; B5 | Skipped renewal rounds made idle, healthy instances unready and fired alerts. |
| Deadline and hung detection moved to a DB-free supervisor | Enforcement no longer waits behind a slow renewal round. |
| Persist read-back after 0 rows | A lost commit acknowledgement was misreported as `FENCED`. |

**Revision 5 (fourth review):**

| Change | Reason |
|---|---|
| Namespace restricted to `^[a-z0-9][a-z0-9-]{0,31}$` (never `:`), enforced by `CHECK`, `SchemaCheck` and the `IdempotencyKey` constructor | Unrestricted concatenation made `("a:b","c")` and `("a","b:c")` collide. |
| Producer persists `OPERATION_ID` with the business event and reuses it on insert retries and restore reconstruction | Confirmed contract. |
| Handle owns its permit from transfer onward; `finish()` covers registration errors, collisions and start failures; collision → invariant violation, liveness DOWN | Registration could throw after the permit left `held`, leaking it. |
| Renewal snapshot at round start; registration allowance `G`; B2 = `max(I, W) + 3W + d + G < L`; `LeaseSimulationTest` gates B2/E5 | The proof ignored claim acknowledgement and registration before the first covering round. |
| Defaults: `T_pool 1s, T_login 2s, T_tx 3s, T_read 5s, T_lock 2s` (W = 11s); IT lease 25s | The corrected B2 rejects the revision-4 defaults. |
| E5 described as a lease-preservation target (10s default), not the longest survivable outage | Longer outages are recoverable via re-claim and dedupe; now stated and tested. |
| C1 states its capacity condition; C2 is a conditional bound with capacity, cancellation-completion and backlog assumptions, otherwise a measured objective | The old C2 assumed permits are released by the deadline and ignored older expired work. |
| `RevokeRaceIT` asserts valid serialized outcomes | Renew-then-revoke legitimately lets both updates succeed. |

**Revision 6 (fifth review):**

| Change | Reason |
|---|---|
| Defaults back to `W = 18s` with a 90s lease (E1 108s, E5 16s); tightening only on spike and load evidence | The claim that 3s transactions are ample was unsupported; batches and contention matter more than unloaded latency. |
| C1/C2 are measured recovery objectives; targets count claim transactions (`⌈N / b⌉ · W`) and capacity waves; unmodelled effects listed | The old C1/C2 ignored claim batch size and sequential claim transactions. |
| Sweeper batched with `SKIP LOCKED DATA`; T1 counts sweep transactions | Same issue for T1: one unbounded sweep could overrun `T_tx`. |
| Effects: at most once per operation, exactly once when DONE, zero or one when FAILED; calls zero to `max-attempts` | Attempts can be exhausted without any call, so "at-least-once calls" and universal "exactly-once effects" were overstated. |
| Simulation described as evidence supplementing the timing argument and real-driver tests | A finite grid cannot prove every execution. |

**Revision 7 (Phase 1 code review):**

| Change | Reason |
|---|---|
| `OPERATION_ID` restricted to `^[!-~]{1,64}$` (printable ASCII, no spaces), enforced by `CK_WORK_ITEM_OPERATION_ID` and the `IdempotencyKey` constructor | "Any content" did not fit the column: Db2 compares strings blank-padded, so `order-1` and `order-1 ` collided under the unique key, and `VARCHAR(64)` holds 64 bytes, not 64 characters (22 × `漢` is 66 bytes). |
| Unique key on `(OPERATION_ID, LENGTH(OPERATION_ID))` | Db2 checks uniqueness before the `CHECK`, so a malformed id with trailing blanks still failed as a duplicate and would be dropped as "already enqueued". |
| `OPERATION_ID VARCHAR(65)` with `CHECK (LENGTH BETWEEN 1 AND 64)` | Db2 silently cuts excess trailing blanks off on assignment: in `VARCHAR(64)`, 64 characters plus a blank were stored as a different 64-character id, or failed as a duplicate of it. |
| Database collation precondition (`IDENTITY`); downstream compares keys exactly | Case-insensitive comparison would merge distinct ids. |
| §6 claim SQL shows the spike's form A2; fallback paragraph replaced by the spike result | Db2 12.1 rejects the old primary form (SQLCODE -104); whether the fallback skips locked rows varies between runs. |
| `ClaimSqlSpikeIT` asserts A2's acceptance, lock skipping and oldest-first order; the repository's lock-skip test also discriminates order | The spike stayed green as long as any form was accepted and skipped locks. |

**Revision 8 (Phase 1 gate follow-ups):**

| Change | Reason |
|---|---|
| The `Sweeper` sets `CLAIM_TOKEN + 1` and `OWNER = NULL` on the rows it fails, as `revokeOwner` does | Sweep kept the owner and token, so a late `retryOrFail` from the swept owner read back FAILED as its own write, reported `FAILED` instead of `FENCED`, and its error text was lost. |
| Db2 must run in UTC (deployment precondition); `SchemaCheck` requires `CURRENT TIMEZONE = 0` | `CURRENT TIMESTAMP` is server-local time: daylight-saving transitions would expire every lease at once or delay expiry by an hour. |
| The ERROR that Spring logs after a close-socket query timeout is expected and documented (§8) | The rollback fails on the closed connection; `WorkItemRepository` already surfaces the statement's own `DataAccessException`. |
| Phase 2 ITs use a test-scope `RecordingDownstream`; Toxiproxy reaches Db2 through its host-mapped port | ITs 6–12 assert on downstream call outcomes, but the demo's simulated downstream arrives only in Phase 3. |

**Revision 9 (Phase 2 planning, lease model):**

| Change | Reason |
|---|---|
| E5 = `(F* − 1)·W + F*·d`, where `F*` is the largest `F` with `max(I, W) + 2W + G + F·(W + d) < L` (§5.3) | The old E5, `L − max(I, W) − 3W − d − G`, assumed an outage starts at the beginning of the round it fails. One that begins at the end of a round also fails it, and each retry during the outage costs `W + d`: with the old defaults a 1.2s outage lost a claim under a 16s target. A prototype of the §11.1 model confirmed the corrected formula to one 10ms step on 270 configurations, and that B2 is exact. |
| Default lease 90s → 100s (E1 118s, E5 20s); IT lease 25s → 30s (E5 5.9s) | With the corrected formula, a 90s lease gives E5 = 1s and a 25s IT lease 0.2s, too short for `DbOutageIT`'s 1.5s short outage. |
| `LeaseSimulationTest` also asserts that B2 and E5 are tight | A target the model cannot violate one step past its bound would not show that the formula is right. |
| `LoadWithFaultsIT` outage 20s → 25s | It must still exceed the E5 target. |

**Revision 10 (final review of Phase 2a):**

| Change | Reason |
|---|---|
| E5 = `max(d, L − max(I, W) − 4W − d − G)` (§5.3, §7) | Revision 9 assumed failed outage rounds take exactly `W`, so a fast-failing retry chain whose last round hangs for `W` lost claims at the defaults after 8.02s against a claimed 20s. |
| Leases kept at 100s / 30s, so E5 is 8s by default and 2.1s for ITs | Owner's decision; E1 stays 118s. |
| `LeaseSimulation` lets a failed outage round fail as early as it can, at `W`, or aligned to the outage's last step, with a full-enumeration cross-check (§11.1) | A failed round takes up to `W`, and the model must cover the fast-failing retry chain. The round the outage begins in also needs the early failure: without it the reduced model found the first loss one step late near the B2 bound. |
| §4 `OWNER` comment: current claim holder, NULL after revocation, sweep or replay | Sweep now clears `OWNER` (revision 8). |
