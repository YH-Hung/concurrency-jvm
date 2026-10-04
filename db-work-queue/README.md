# db-work-queue

A work queue stored in a shared Db2 table. Multiple app instances can process it together.

**You provide the jobs and the business logic. `WorkQueue` manages their execution.**

| Part | Responsibility |
|---|---|
| You: setup | Configure the Db2 connection and create the table. |
| You: producer | Insert jobs with an operation ID and payload. |
| You: handler | Process the payload safely, even if called again. |
| `WorkQueue` | Claim jobs, limit concurrency, manage leases and retries, and record success or failure. |

Each worker repeats: **claim one row → call your handler → save the result**.

## Your handler

Replace the logging lambda in [`App.java`](src/main/java/hle/org/workqueue/App.java) with your business logic.
It receives `(operationId, payload)`: a stable job ID and the payload string you inserted.

- **Return normally:** the queue marks the job `DONE`.
- **Throw:** the queue retries after `retry-backoff`, or marks it `FAILED` if attempts are exhausted.
- **Make repeated calls safe:** use `operationId` to prevent duplicate effects. For example, retrying
  `order-123` must not create a second order. This is called *idempotency*.
- **Set timeouts on downstream calls and stop when interrupted:** propagate `InterruptedException` or check
  interruption in long loops. The queue interrupts a handler when its lease ends; it cannot force it to stop.

**A job can run more than once**, even after the business operation succeeds: the app might crash before saving
`DONE`. This is *at-least-once delivery*. The table's unique operation ID prevents duplicate inserts;
your handler must prevent duplicate business effects.

## Run

Requires Java 25 and Db2.

1. Apply [`schema.sql`](src/main/resources/schema.sql) to Db2. Run Db2 in UTC so daylight-saving changes cannot
   shift leases and retries.
2. Implement the handler above.
3. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.
4. From `db-work-queue`, run `./mvnw spring-boot:run`. Spring starts the queue automatically. Run more instances
   against the same table to add workers.

Your producer enqueues a job with:

```sql
INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (?, ?);
```

A duplicate `OPERATION_ID` fails with SQLSTATE `23505`: that job is already in the table.

## Settings

**Queue settings are optional.** [`App.java`](src/main/java/hle/org/workqueue/App.java) registers
`WorkQueue.Settings` through `@EnableConfigurationProperties`. Spring reads `workqueue.*` properties,
uses each field's `@DefaultValue` in [`WorkQueue.java`](src/main/java/hle/org/workqueue/WorkQueue.java) when
that property is missing, and injects the settings into the queue bean. Invalid values fail startup.

| Property | Default | Meaning |
|---|---|---|
| `workqueue.workers` | `16` | Maximum concurrent handlers per instance. |
| `workqueue.lease` | `60s` | How long a claim lasts; also the handler's time budget, including time spent claiming. |
| `workqueue.max-attempts` | `5` | Attempt budget per job, including the first claim. Crashes also consume attempts. |
| `workqueue.retry-backoff` | `30s` | Fixed delay after a failed handler attempt. |
| `workqueue.poll-interval` | `1s` | Wait between claims when the queue is empty or a database call fails. |

Override only what you need in [`application.properties`](src/main/resources/application.properties):

```properties
workqueue.workers=8
workqueue.lease=120s
```

Choose a lease longer than a legitimate job plus its claim time. A shorter lease interrupts slow jobs;
a longer lease delays recovery after a crash.

## Failure and recovery

| Event | What the queue does |
|---|---|
| Handler exceeds its lease | Interrupts it and treats the timeout as a failed attempt once it stops. The expired row can be claimed again. |
| Handler ignores interruption | Waits for it, keeping that worker occupied. Another worker may run the expired job again. |
| App crashes, or saving `DONE` fails | Reclaims the row after its lease expires. If the attempt budget is exhausted, marks it `FAILED` without calling the handler again. |
| An old worker saves after a newer claim | Rejects the old outcome by checking the claim's `ATTEMPTS` value. This protects queue state; the handler protects business effects. |
| Database call fails | Logs the error, waits `poll-interval`, and tries again. |
| App receives SIGTERM | Stops claiming, allows up to 30s for running jobs, then interrupts remaining handlers. |

Claims use `SKIP LOCKED DATA` to skip rows locked by other workers. Each claim commits before the handler runs.
`AVAILABLE_AT` determines when a row can be claimed: enqueue time, retry time, or lease expiry;
`DONE` and `FAILED` rows have no next claim time. No cleanup job is needed to recover expired claims.

## Limits and checks

- Jobs are claimed roughly oldest available first; they can finish out of order.
- All jobs share the same lease duration.
- `PAYLOAD` and `LAST_ERROR` allow 1000 bytes; `OPERATION_ID` allows 64 bytes.
- `DONE` and `FAILED` rows remain in the table. Deleting one allows its operation ID to be enqueued again.

```sql
-- Counts by state
SELECT STATUS, COUNT(*) FROM WORK_ITEM GROUP BY STATUS;

-- Jobs that exhausted their attempts
SELECT OPERATION_ID, ATTEMPTS, LAST_ERROR FROM WORK_ITEM WHERE STATUS = 'FAILED';
```

From `db-work-queue`, run `./mvnw test`. Docker is required: the tests start Db2 and check concurrent processing,
retries, interruption, crash recovery, rejection of stale outcomes, and lifecycle concurrency limits.

For diagrams and implementation details, see the [visual walkthrough](../docs/db-work-queue-design.html).
The [earlier engine design](../docs/superpowers/specs/2026-09-21-db-work-queue-design.md) is deprecated.
