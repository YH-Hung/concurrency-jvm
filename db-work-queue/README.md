# Db2 work queue

Insert a job into Db2; the worker claims it, calls your handler, and stores the result.
Start by running the example below, then change
[ExampleHandler.java](work-queue-demo/src/main/java/hle/org/workqueue/demo/ExampleHandler.java).

## Run ten jobs

You need **JDK 25**, **Docker Desktop with Compose**, and **OpenSSL**. Give Docker at least 4 GB
of memory. On Apple Silicon enable Rosetta for amd64 emulation: this Db2 image is amd64 only.
The first image download is several GB and the first database start can take 5–10 minutes.
Running the demo accepts the Db2 Community Edition license (`LICENSE=accept`). Port 50000 must be free.

From this directory:

```bash
./scripts/first-run.sh
```

Or run `./db-work-queue/scripts/first-run.sh` from the repository root. No installed Maven is needed.
The script builds the application, starts a dedicated local `WORKQ` database, applies its schema,
inserts ten fresh jobs, starts a worker, and waits for this batch to finish. Successful output includes:

```text
total=10 done=10 failed=0 pending=0 claimed=0 missing=0
Batch complete. Stopping this worker; Db2 remains running for inspection.
```

Run the same command again to process a new batch. Earlier jobs and their results remain in Db2.
Each run prints its directory under `work-queue-demo/target/first-run/`, containing `batch.ids`
and the build, database, migration, seed, worker and verification logs. A failed step returns nonzero.
Ctrl-C stops this invocation's child processes. Database readiness is limited to 15 minutes;
`FIRST_RUN_DB_TIMEOUT_SECONDS` can shorten that wait.

The script generates a random password in ignored `work-queue-demo/.env` with permissions `600`.
Keep that file with the database volume; changing its password does not change an existing Db2 instance.
The database port is bound only to `127.0.0.1`.

## Change the job

The whole sample handler is:

```java
@Override
public CallResult call(IdempotencyKey key, long claimToken, String payload, Duration timeout) {
    return new CallResult("processed:" + payload);
}
```

Change `processed:` in [ExampleHandler.java](work-queue-demo/src/main/java/hle/org/workqueue/demo/ExampleHandler.java)
and rerun the script. It rebuilds the jar, and only newly inserted jobs use the new code.
A result must fit in **1000 UTF-8 bytes**.

The sample has no external side effects. If your handler sends a payment, message, or another external request,
the downstream must **durably deduplicate `key.value()`** across attempts and honor the supplied timeout.
Leases and database fencing protect queue writes; they cannot undo an external effect.
See the [external side-effect contract](../docs/superpowers/specs/2026-09-21-db-work-queue-design.md#54-external-side-effect-contract).

## Insert a job and read its result

A producer supplies two columns; the database defaults the status and timing:

```sql
INSERT INTO WORK_ITEM (OPERATION_ID, PAYLOAD)
VALUES ('my-unique-operation-001', 'hello');
```

Use a unique operation ID for each new logical operation (1–64 printable ASCII characters, no spaces).
Repeating the same ID does not create a second job. Payloads must fit in 1000 bytes.
The first-run script stops its worker after verification, so keep a standalone worker running to process manually inserted rows.

To inspect the latest demo rows with Db2's command-line client:

```bash
docker compose --project-name workqueue-first-run --env-file work-queue-demo/.env \
  -f work-queue-demo/compose.yml exec -T db2 su - db2inst1 -c \
  'db2 connect to WORKQ >/dev/null && db2 "SELECT ID, STATUS, ATTEMPTS, RESULT_VALUE FROM WORK_ITEM ORDER BY ID DESC FETCH FIRST 20 ROWS ONLY"'
```

Expect `DONE`, `ATTEMPTS = 1`, and values such as `processed:job-1`. To inspect exactly one run,
use the numeric IDs from that run's `batch.ids` in `WHERE ID IN (...)`.

## Run each step yourself

Build with `./mvnw -pl work-queue-demo -am package -DskipTests`.
Set `WORKQUEUE_DB_PASSWORD` to the password from `.env` in each terminal that starts Java.
Do not put it in command arguments or source control. The demo defaults to the local `WORKQ` URL,
user `db2inst1`, namespace `demo`, and a Hikari pool of 20 connections.

```bash
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=migrate
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=seed --demo.batch-file=/tmp/new-batch.ids
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=worker
# In another terminal with the password set:
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=verify --demo.batch-file=/tmp/new-batch.ids
```

Use a new batch filename each time. `seed` accepts `--demo.count=10`; `verify` accepts
`--demo.timeout-seconds=120`. Verification checks only that file's IDs and fails for missing or failed
jobs, invalid IDs, or a timeout. Maintenance commands require database `WORKQ`, close their connections,
and exit. The worker remains alive until Ctrl-C and uses the engine's graceful shutdown.

## Use the engine in your Spring Boot application

Depend on `hle.org:work-queue-engine`, supply one `ExternalService` bean, and configure a datasource:

```java
@Bean
ExternalService myHandler() {
    return (key, claimToken, payload, timeout) -> new CallResult("processed:" + payload);
}
```

```yaml
spring:
  datasource:
    url: ${WORKQUEUE_JDBC_URL}
    username: ${WORKQUEUE_DB_USER}
    password: ${WORKQUEUE_DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
  sql:
    init:
      mode: never
  flyway:
    enabled: false
workqueue:
  expected-namespace: my-queue
```

Apply `classpath:db/migration/workqueue` separately with Flyway and placeholder
`workqueueNamespace=my-queue`. Workers never migrate or seed. Startup checks the V1 migration,
required columns, exactly one matching namespace, UTC database time, and the engine's timing budget
before polling. No application code constructs internal engine classes.

The supported datasource is **one unstarted HikariDataSource named `dataSource`**, normally created
by Boot. Wrapped/lazy proxy pools, already running pools, and multiple datasources are rejected.
The default concurrency is 16, so the pool needs at least 20 connections; Boot's default pool size 10
is too small. The engine applies bounded JDBC timeouts before the first connection opens.

## Stop or remove the demo database

Stop Db2 while keeping the data:

```bash
docker compose --project-name workqueue-first-run --env-file work-queue-demo/.env \
  -f work-queue-demo/compose.yml down
```

**Delete all demo data** only when you deliberately want a fresh database:

```bash
docker compose --project-name workqueue-first-run --env-file work-queue-demo/.env \
  -f work-queue-demo/compose.yml down --volumes
```

The first-run script never removes data. If port 50000 is occupied, stop the conflicting service yourself
or use the individual commands with your own local setup; the script does not stop unrelated processes.

## Tests and reading further

```bash
./mvnw test                            # unit tests, no Docker
./mvnw verify                          # unit tests + real Db2 integration tests
bash scripts/test-first-run.sh         # orchestration/signal tests; requires Python 3
```

[Follow one job through the source](docs/architecture.md) ·
[Db2 SQL evidence](docs/claim-sql-spike.md) ·
[Production design](../docs/superpowers/specs/2026-09-21-db-work-queue-design.md)

This example establishes the first-run and Spring integration path. Admin endpoints, management security,
a durable downstream fault harness, the remaining runtime/process/chaos scenarios, restricted-role validation,
and production load gates remain pending. The example does not establish production readiness.
