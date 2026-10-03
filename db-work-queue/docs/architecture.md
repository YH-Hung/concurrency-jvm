# Follow one job through the engine

Start with the [runnable example](../README.md). Its handler receives a payload and returns a result.
To understand what the engine adds, read
[ItemProcessor.process()](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java)
first: validate the operation identity, call the handler once, then persist the outcome.

## The ordinary path

```text
INSERT → claim → handler → persist → release local capacity
```

1. **Insert.** Your producer inserts `OPERATION_ID` and `PAYLOAD` into `WORK_ITEM`. The database
   defaults the row to `PENDING`. The example's
   [DemoCommands.seed()](../work-queue-demo/src/main/java/hle/org/workqueue/demo/DemoCommands.java)
   demonstrates the two-column insert and commits a fresh batch before returning its IDs.
2. **Claim.** [ClaimExecution.pollOnce()](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimExecution.java)
   reserves available local capacity and calls
   [WorkItemRepository.claim()](../work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java).
   Db2 selects eligible rows without waiting on rows another worker has locked. Each claim records an owner,
   increments the attempt and fencing token, and sets the lease expiry. Only committed claims become tasks.
3. **Handler.** A virtual thread runs `ItemProcessor.process()`, which calls your `ExternalService` with the
   stable idempotency key, current claim token, payload and timeout. The
   [example handler](../work-queue-demo/src/main/java/hle/org/workqueue/demo/ExampleHandler.java)
   returns `processed:` plus the payload.
4. **Persist.** `ItemProcessor` calls the repository's `complete()` or `retryOrFail()`. The update is fenced by
   row ID, owner and claim token. Success stores `RESULT_VALUE` and `DONE`; a failed attempt becomes
   `PENDING` with a retry delay, or `FAILED` when attempts are exhausted. Read-back resolves cases where
   a write's acknowledgement was lost.
5. **Release.** The task's `finally` finishes its
   [ClaimHandle](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java).
   This removes the local registration and returns its capacity permit exactly once.

**Persistent completion and local cleanup are different.** `DONE` is durable database state.
Finishing a handle means this JVM's task has exited. The engine releases capacity even if persistence
failed; it does not falsely mark that row done. A later lease expiry allows recovery.
Cancellation interrupts a task and stops renewal, but capacity stays occupied until that task actually exits.

**Persistence retry and another processing attempt are different.** The processor may retry a fenced
write or read back its result without calling the handler again. A later claim after a failed attempt
or expired lease can call the handler again. That is why a real downstream must durably deduplicate
`IdempotencyKey.value()`. The fencing token changes between claims; the logical operation identity does not.

## How Spring starts it

Application code supplies one `ExternalService` bean, a datasource, and configuration. Spring discovers
[WorkQueueAutoConfiguration](../work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueAutoConfiguration.java).
It applies bounded JDBC settings to the unstarted Hikari datasource, validates the timing budget and
handler count, then checks the migration, required columns, namespace and UTC clock.
A failed check prevents polling. The demo selects a separate maintenance configuration for migrate,
seed and verify, so these commands never start workers.

After preflight, [QueueRunner](../work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java)
starts five virtual-thread loops: polling, renewal, supervision, sweeping and backlog sampling.
The standalone demo keeps its JVM alive until stopped. Spring stops the runner before closing its datasource.

## Leases, failures and shutdown

Renewal extends eligible claims while their work remains within its deadline. A separate DB-free supervisor
cancels overdue tasks and detects hung tasks even when a database call is blocked. Hung tasks retain capacity;
reaching the configured limit pauses claiming and makes liveness down.

`ClaimExecution` owns poll recovery and its shared backoff. A repository failure releases local capacity,
registers nothing and backs off. A task construction failure releases capacity still held locally while
earlier tasks keep running. A returned claim resets the failure counter before task construction.
If a claim commits but its acknowledgement is lost, its attempt is consumed and its unrenewed lease expires.

Graceful shutdown marks readiness down, stops polling, drains with renewal active, cancels remaining work,
waits for cancellation, and stops the background loops. It never releases a database claim early.
After process death, leases expire and another instance can reclaim work with a new fencing token.
The old owner cannot overwrite the new claim's outcome.

## Where to go for a particular question

| Question | Owner |
|---|---|
| How is one handler call turned into a persisted outcome? | [ItemProcessor](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ItemProcessor.java) |
| Why can another job start, or why are claims paused? | [ClaimExecution](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimExecution.java), [ClaimHandle](../work-queue-engine/src/main/java/hle/org/workqueue/engine/ClaimHandle.java) |
| When do the five loops run and stop? | [EngineLoops](../work-queue-engine/src/main/java/hle/org/workqueue/engine/EngineLoops.java), [QueueRunner](../work-queue-engine/src/main/java/hle/org/workqueue/engine/QueueRunner.java) |
| What can Db2 atomically claim or update? | [WorkItemRepository](../work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkItemRepository.java), [SQL evidence](claim-sql-spike.md) |
| Which timing combinations are safe? | [TimingBudget](../work-queue-engine/src/main/java/hle/org/workqueue/engine/TimingBudget.java), [design §5.3](../../docs/superpowers/specs/2026-09-21-db-work-queue-design.md#53-timing-budget) |
| What do health and metrics observe? | [EngineSnapshot](../work-queue-engine/src/main/java/hle/org/workqueue/engine/EngineSnapshot.java), [WorkQueueHealth](../work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueHealth.java), [WorkQueueMetrics](../work-queue-engine/src/main/java/hle/org/workqueue/engine/WorkQueueMetrics.java) |

Health and metrics read immutable snapshots; they perform no database operation, join or downstream call.
A snapshot does not change after publication, although concurrent observations are not a single atomic
transaction across all modules. `BacklogSampler` owns sampled counts and freshness; `DbActivity` owns the
last successful database-operation time; `OperationStats` owns call counts and durations.

The [production design](../../docs/superpowers/specs/2026-09-21-db-work-queue-design.md) remains the reference
for the lifecycle, capacity invariant and external side-effect contract. Admin/security, remaining
runtime/process/chaos cases and production load gates are still separate work.
