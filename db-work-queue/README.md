# db-work-queue

A Db2 table used as a work queue. Run as many instances as you like. They coordinate only through the table, keep
working through crashes, and retry failed jobs.

The whole mechanism is [`WorkQueue.java`](src/main/java/hle/org/workqueue/WorkQueue.java) (three SQL statements and
a worker loop) and [`schema.sql`](src/main/resources/schema.sql).

## How it works

Each instance runs `workers` virtual threads. Each thread loops: **claim → handle → save the outcome.**

| Step | SQL | Why it's safe |
|---|---|---|
| Claim | Lease the oldest row with `AVAILABLE_AT <= now`: `STATUS='CLAIMED'`, `ATTEMPTS+1`, `AVAILABLE_AT = now + lease` | One statement with `SKIP LOCKED DATA`, so two workers never take the same row |
| Success | `DONE`, `AVAILABLE_AT = NULL` | Fenced: `WHERE ID = ? AND ATTEMPTS = <this claim's value> AND STATUS = 'CLAIMED'` |
| Failure | `PENDING` again after `retry-backoff`. `FAILED` at `max-attempts` | Fenced the same way |
| Crash or hang | Nothing: the lease runs out, so the row can be claimed again. A handler still running at that point is interrupted and the attempt fails | A late write from the old worker updates 0 rows |

`ATTEMPTS` rises with every claim and is never reset, so it also serves as the fencing token. If the last attempt
crashes, the next claim sets the row to `FAILED` without running the job again.

**Delivery is at-least-once.** A crash after the handler finishes runs the job again, and so does a handler that
outlives its lease. Make its effects idempotent on `operationId`.

**Handlers must bound their downstream calls with timeouts and stop when interrupted.** `workers` is a hard
concurrency limit, so a handler that ignores the interrupt keeps its worker until it returns. If every worker on an
instance is stuck, that instance stops claiming, and its rows wait for a free worker on another instance. More
workers only delay the stall.

## Use

1. Apply `schema.sql`. Db2 must run in UTC, because leases use the database clock.
2. Put your job in the handler in [`App.java`](src/main/java/hle/org/workqueue/App.java). To retry, throw.
3. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`, then run
   `./mvnw spring-boot:run` on as many machines as you like. Stopping with SIGTERM lets running jobs finish (up to
   30s).

Producers enqueue with `INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD) VALUES (?, ?)`. A duplicate `OPERATION_ID`
fails with SQLSTATE 23505, meaning the job is already enqueued.

| Property | Default | |
|---|---|---|
| `workqueue.workers` | 16 | concurrent jobs per instance |
| `workqueue.lease` | 60s | how long a claim lasts, and so the handler's timeout |
| `workqueue.max-attempts` | 5 | |
| `workqueue.retry-backoff` | 30s | |
| `workqueue.poll-interval` | 1s | how long an idle worker sleeps between claims |

## Check what's in the queue

```sql
SELECT STATUS, COUNT(*) FROM WORK_ITEM GROUP BY STATUS;
SELECT * FROM WORK_ITEM WHERE STATUS = 'FAILED';
```

## Test

```bash
./mvnw test
```

This needs Docker. The test starts Db2 itself, which takes a few minutes on Apple Silicon.
