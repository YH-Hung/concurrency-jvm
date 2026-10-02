# Reading the work-queue engine

Start with [QueueRunner](../work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java).
Its lifecycle methods show the instance's startup and shutdown sequence. Four modules explain the execution path:

| Module | Contract | State it owns |
|---|---|---|
| `QueueRunner` | `start`, `stop`, `snapshot`; test-only `crash` | Instance lifecycle and module assembly |
| [ClaimExecution](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimExecution.java) | `pollOnce`, `renewOnce`, `superviseOnce`, `awaitDrained`, `cancelForShutdown`, `abort`, `snapshot` | Capacity, active claims, registration/cancellation gate, poll backoff and execution counters |
| [EngineLoops](../work-queue-engine/src/main/java/hle/org/workqueue/engine/EngineLoops.java) | `start`, `stopPolling`, `stopBackground`, `abort`, `deadLoops` | Five loop threads, run flags and renewal scheduling |
| [ItemProcessor](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java) | `process`, `callStatistics` | One-call/persist protocol and call timings |

Application code implements `ExternalService` using `IdempotencyKey` and `CallResult`, and configures
`WorkQueueProperties`. `ReplayFilter` describes the planned admin selection contract. Persistence, claim and timing
types are package-private. Spring Boot auto-configuration and admin wiring remain Phase 3 work; the current engine
is assembled explicitly in tests. The production goals and future approval gates remain in the
[design spec](../../docs/superpowers/specs/2026-09-21-db-work-queue-design.md).

## Ordinary processing

1. `QueueRunner.start()` asks `EngineLoops` to start five independent virtual threads: polling, renewal,
   supervision, sweeping and backlog sampling.
2. The poll loop calls `ClaimExecution.pollOnce()`. It takes free capacity, asks the repository to claim up to that
   capacity, transfers one permit to each handle, registers it and starts one virtual thread per claim. It returns
   the next polling pause; the loop only schedules that pause.
3. Each task calls `ItemProcessor.process()`: one downstream call, followed by fenced persistence with bounded
   retries and read-back. Persistence retries never repeat the downstream call.
4. The task's `finally` finishes its handle. Only actual exit, or cleanup before a successful start, returns capacity.
   Cancellation interrupts a task and stops renewal but never releases its permit.
5. Renewal maintains eligible leases. The separate DB-free supervisor cancels overdue claims and identifies hung
   tasks, even while a database operation is blocked.

`ClaimHandle` is internal lifecycle machinery inside the active-claim boundary. To reason about capacity, stay in
`ClaimExecution` and `ClaimHandle`: `free permits + handles not ended + locally held permits = concurrency`.
The complete [lifecycle contract](../../docs/superpowers/specs/2026-09-21-db-work-queue-design.md#52-task-lifecycle-contract)
and [timing budget](../../docs/superpowers/specs/2026-09-21-db-work-queue-design.md#53-timing-budget) explain the races
and lease origins. [WorkItemRepository](../work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java)
is the SQL boundary; the [claim SQL spike](claim-sql-spike.md) records the Db2 evidence.

## Failure, shutdown and recovery

`ClaimExecution` owns the complete poll recovery policy. A repository failure releases local capacity, registers
nothing and backs off. A construction failure also releases capacity still held locally, while earlier tasks keep
running. Both use the same failure counter; a returned claim resets it before task construction. A claim whose
commit acknowledgement was lost expires unrenewed, with its attempt consumed.

Graceful stop makes readiness down, stops and joins polling against the grace deadline, drains with renewal active,
cancels remaining claims, waits the cancellation allowance, then stops the four background loops within one shared
second. It performs no database release. `EngineLoops` handles partial-start rollback and abort; `QueueRunner.crash()`
publishes abort before waiting on its lifecycle lock so a late registration also sees cancellation.

After a process dies, Db2 leases expire. Another instance reclaims eligible work with a new fencing token; an old
claim cannot persist over the new owner. Downstream effects rely on the stable idempotency key and fencing contract
in [spec §5.4](../../docs/superpowers/specs/2026-09-21-db-work-queue-design.md#54-external-side-effect-contract).
Uncaught loop failures are logged through redacted diagnostics and appear in liveness. Hung tasks retain capacity;
reaching the configured hung-task limit pauses claims and makes liveness down.

## Observation and supporting state

[EngineSnapshot](../work-queue-engine/src/main/java/hle/org/workqueue/engine/EngineSnapshot.java) contains immutable
execution, backlog and lifecycle summaries. The execution summary includes the configured hung-threshold decision.
`ItemProcessor.callStatistics()` returns immutable timing totals for each call classification. Health and metrics
receive suppliers of fresh summaries. A retained summary never changes, and monitoring performs no DB operation,
join or downstream call. Concurrent readings are not one globally atomic transaction.

| Supporting owner | State / responsibility |
|---|---|
| `ClaimHandle` | One claim's cancellation, deadline, renewal origin and exactly-once cleanup |
| `BacklogSampler` | Latest successful sample, its freshness and failure count |
| `DbActivity` | Most recent successful engine DB operation; ages use the snapshot's clock reading |
| `OperationStats` | Atomic operation count and total time, exposed as immutable totals |
| `EngineSettings` | Validated immutable runtime settings, converted from unchanged configuration properties |
| `Sweeper` | One bounded sequence of repository sweep batches; scheduling belongs to `EngineLoops` |

Tests follow those ownership boundaries: `ClaimExecutionTest` covers claim/permit/renewal races, `EngineLoopsTest`
covers scheduling and startup rollback, and `QueueRunnerLifecycleTest` covers composition and shutdown budgets.
`EngineSnapshotTest`, `WorkQueueMetricsTest` and `WorkQueueHealthTest` cover observation semantics. Existing Db2
integration tests and the timing simulation remain authoritative. Phase 2 ITs 6–12 and Phases 3–5 remain pending.
