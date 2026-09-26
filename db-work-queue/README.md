# db-work-queue

Db2-backed work-queue engine. Design: [spec](../docs/superpowers/specs/2026-09-21-db-work-queue-design.md).

Status: Phase 1 gate green (spec §12: ITs 1–5); see [docs/claim-sql-spike.md](docs/claim-sql-spike.md). Phase 2 — runtime contracts — in progress: timing foundations done (`TimingBudget`, `RenewalSchedule`, `LeaseSimulationTest`).

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
