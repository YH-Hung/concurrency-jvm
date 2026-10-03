# db-work-queue

Db2-backed work queue with a runnable Spring Boot example. Put your job logic in
[ExampleHandler.java](work-queue-demo/src/main/java/hle/org/workqueue/demo/ExampleHandler.java).
Spring discovers the engine, validates startup, and manages its worker lifecycle.

Build the example with `./mvnw -pl work-queue-demo -am package -DskipTests`.
The executable is `work-queue-demo/target/work-queue-demo.jar`.
Against a local `WORKQ` database, set `WORKQUEUE_DB_PASSWORD` in the environment, then run:

```bash
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=migrate
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=seed --demo.batch-file=/tmp/my-new-batch.ids
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=worker
# In another terminal:
java -jar work-queue-demo/target/work-queue-demo.jar --demo.mode=verify --demo.batch-file=/tmp/my-new-batch.ids
```

The seed command inserts 10 fresh jobs; use a new batch file on each run. Verification succeeds only
when all IDs in that file are DONE. Stop the worker with Ctrl-C. The one-command Docker workflow is being added.

[Code-reading guide](docs/architecture.md) · [Design](../docs/superpowers/specs/2026-09-21-db-work-queue-design.md).

Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`), `ClaimHandle`, `ItemProcessor`, `QueueRunner`, the `Sweeper`, the `BacklogSampler`, metrics and health done; the Db2 ITs 6–12 next.

## Prerequisites

- JDK 25.
- Docker Desktop running, with "Use Rosetta for x86_64/amd64 emulation on Apple Silicon" enabled and
  at least 4 GB of memory. The Db2 image (`icr.io/db2_community/db2:12.1.5.0`) is amd64 only; the first
  pull is several GB and the first start takes 5–10 minutes. Running the integration tests accepts the
  Db2 Community Edition license (`LICENSE=accept`).
- Recommended: keep the Db2 container between runs.

  ```bash
  echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
  ```

## Build

`mvn` is not needed; use the wrapper from this directory.

```bash
./mvnw verify                                                        # unit tests + Db2 ITs + coverage
./mvnw test                                                          # unit tests only
./mvnw -pl work-queue-engine verify -Dit.test=WorkItemRepositoryIT   # one IT class
```

Coverage report: `work-queue-engine/target/site/jacoco/index.html`.
Spike findings: [docs/claim-sql-spike.md](docs/claim-sql-spike.md).
