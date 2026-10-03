# DB Work Queue First Run Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` for native execution, or `superpowers:subagent-driven-development` if selected by the user. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the user one command that processes a fresh batch in real Db2, an obvious handler to change, and a short explanation of the ordinary job flow.

**Architecture:** Add the intended Spring Boot integration to the existing engine, then a separate example application that uses only its supported application contracts. Keep database migration and seeding outside the worker. A local script orchestrates the example, checks its batch, and stops its own worker.

**Tech Stack:** Existing JDK 25, Spring Boot 4.1.1, JDBC/JCC, HikariCP, Db2 `icr.io/db2_community/db2:12.1.5.0`, Maven wrapper, JUnit/AssertJ and Testcontainers. Use Boot-managed Flyway dependencies in the demo and the Boot Maven plugin for its executable jar; no new runtime framework or web server.

**Spec:** [Approved first-run design](../specs/2026-10-03-db-work-queue-first-run-design.md). The [original production design](../specs/2026-09-21-db-work-queue-design.md) remains authoritative for existing queue guarantees and pending production gates.

**Status:** Implementation in progress on `codex/db-work-queue-first-run`.

## Global Constraints

- Preserve existing SQL, schema V1, claim/renewal algorithms, property defaults, timing validation B1–B5, redaction, capacity and graceful-shutdown behavior.
- Add `workqueue.expected-namespace`, required with no default when workers are enabled. Keep the application API centered on `ExternalService`, `IdempotencyKey`, `CallResult` and `WorkQueueProperties`.
- Use real Db2. Do not substitute H2 or an in-memory queue for end-to-end evidence. Unit tests must not start Docker.
- The example lives in `hle.org.workqueue.demo`, outside `hle.org.workqueue.engine`; it cannot assemble engine internals or bypass their visibility.
- Workers never migrate or seed. The demo never cleans the schema, deletes existing jobs, or reuses operation identities between batches.
- The example handler has no external side effects. Keep durable downstream idempotency an explicit requirement when users replace it with a handler that causes effects.
- Use production engine timing defaults in the example, with Hikari maximum pool size **20** for default concurrency **16**. Keep DB credentials out of command arguments, logs, source control and generated reports.
- Admin endpoints, management security, the durable fault-injecting downstream, chaos/process scenarios, restricted-role production validation and load gates remain pending. This slice does not establish production readiness.

## Review Focus

1. A pool is already running, wrapped, ambiguous or smaller than concurrency + 4: reject unsupported startup before polling; never silently retain different timeouts. Task 1 tests this.
2. A handler, migration, required table, namespace or UTC database clock is missing/wrong: context startup fails before any claim. Task 1 covers failures; Task 4 checks real Db2 behavior.
3. A second run encounters earlier rows, or the batch file has missing/invalid IDs: verify only the current batch and do not mistake absent rows for successful work. Tasks 2 and 4 cover this.
4. Build, DB startup, worker startup or verification fails, or the user interrupts the script: return nonzero and stop only child processes started by this invocation. Task 3 tests this.
5. Virtual threads are the only application work: the standalone worker must remain alive until stopped; migrate/seed/verify must exit. Tasks 2 and 4 exercise both lifetimes.

---

## Files and responsibilities

Paths beginning with `engine/` below mean `db-work-queue/work-queue-engine/src/`; `demo/` means `db-work-queue/work-queue-demo/`.

| Files | Responsibility |
|---|---|
| `engine/main/java/hle/org/workqueue/engine/WorkQueueAutoConfiguration.java` | Bind properties, validate dependencies, assemble the existing engine, and register its Spring lifecycle |
| `engine/main/java/hle/org/workqueue/engine/WorkQueueDataSourcePostProcessor.java` | Apply engine DB bounds to the supported Hikari pool before its first use |
| `engine/main/java/hle/org/workqueue/engine/SchemaCheck.java` | Bounded, read-only checks of migration, tables, namespace and UTC |
| `engine/main/java/hle/org/workqueue/engine/WorkQueueProperties.java` | Add expected namespace; preserve existing properties |
| `engine/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Discover the auto-configuration without application imports |
| `engine/test/java/hle/org/workqueue/engine/{WorkQueueAutoConfigurationTest,WorkQueueDataSourcePostProcessorTest,SchemaCheckTest}.java` | Startup, timeout ordering, and preflight contracts |
| `demo/pom.xml`, `db-work-queue/pom.xml` | Executable demo module and build registration |
| `demo/src/main/java/hle/org/workqueue/demo/{DemoApplication,ExampleHandler,DemoCommands}.java` | Command routing, the replaceable job handler, and demo-only migration/seed/verification |
| `demo/src/main/resources/application.yml`, `application-worker.yml`, `application-maintenance.yml` | Shared DB configuration and explicit worker/maintenance separation |
| `demo/src/test/java/hle/org/workqueue/demo/{ExampleHandlerTest,DemoCommandsTest,DemoApplicationTest,FirstRunIT}.java` | Handler, modes, batch verification, and real public-path integration |
| `demo/compose.yml`, `demo/.env.example`, `demo/.gitignore` | Local Db2 and ignored local credentials |
| `db-work-queue/scripts/first-run.sh`, `db-work-queue/scripts/test-first-run.sh` | First-run orchestration and bounded process/failure checks using command stubs |
| `db-work-queue/README.md`, `db-work-queue/docs/architecture.md` | Usage first, then the ordinary processing path and recovery details |

## Task 1: Make the engine start through Spring Boot

**Files:** The engine files and three tests listed above; update `WorkQueuePropertiesTest.java` and `PublicApiTest.java` only where needed for the new property/framework discovery.

**Interfaces:**
- Add `String WorkQueueProperties.getExpectedNamespace()` and its setter.
- `WorkQueueDataSourcePostProcessor(ObjectProvider<WorkQueueProperties> properties)` implements `BeanPostProcessor, Ordered`; `postProcessBeforeInitialization(Object bean, String beanName)` configures the supported source. No database access from the processor.
- `SchemaCheck(WorkItemRepository repository)` exposes package-private `String verify(String expectedNamespace)`, returning the verified namespace.
- `WorkQueueAutoConfiguration` is discovered by the imports resource. It requires exactly one `ExternalService` and one supported `DataSource`, supplies the existing repository/processor/runner beans and settings, and gives each context a fresh UUID owner. Application code does not import it.

The first supported pool is the conventional single Hikari bean named `dataSource`, created lazily by Boot or supplied unstarted by the application. Reject other pool types, already-started/closed pools, multiple sources, and wrapped/lazy-proxy sources with an actionable error. This restriction is documented rather than hidden. Do not modify unrelated pools.

The processor runs after property binding and Boot JDBC connection-details binding but before bean initialization/use: implement `Ordered`, not `PriorityOrdered`, with `HIGHEST_PRECEDENCE`. Apply existing `DbTimeouts.applyTo`, then validate the actual bound maximum pool size with `TimingBudget.check`. Boot 4's datasource auto-configuration is `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`. An unspecified Hikari maximum is 10 and fails B3 with default concurrency; the quickstart explicitly sets 20.

- [x] Add startup tests with these assertions, using recording JDBC objects and Boot's context test support rather than a live DB:

  ```java
  // full Boot datasource binding, including a recording driver
  assertThat(firstConnectionProperties).containsEntry("loginTimeout", "3")
      .containsEntry("blockingReadConnectionTimeout", "8")
      .containsEntry("queryTimeoutInterruptProcessingMode", "2");
  assertThat(pool.getConnectionInitSql()).isEqualTo("SET CURRENT LOCK TIMEOUT 3");
  assertThat(pool.getMaximumPoolSize()).isEqualTo(20);
  // maximum-pool-size=19, or missing (effective 10), with concurrency=16
  assertThat(startupFailure).hasStackTraceContaining("B3");
  assertThat(connectionAttempts).hasValue(0);
  ```

  Cover missing/duplicate handler, missing/invalid expected namespace, all unsupported-pool cases, and invalid B1–B5. Excluding the auto-configuration must create no queue beans and require no handler/namespace.
- [x] Add `SchemaCheckTest`: missing/failed/wrong migration V1; missing runtime columns/table; zero/multiple/invalid/mismatched namespace rows; nonzero timezone; SQL failure. Each prevents runner creation/polling and produces a useful, redacted failure. A valid check returns the namespace.
- [x] Run the new unit tests and confirm failure because the integration does not exist yet: `./mvnw -pl work-queue-engine test -Dtest=WorkQueueAutoConfigurationTest,WorkQueueDataSourcePostProcessorTest,SchemaCheckTest`.
- [x] Implement the property, datasource processor, preflight and bean assembly. Use `repository.inTransaction(...)` for bounded preflight SQL. Require one successful SQL migration row with version `1` and script `V1__work_queue.sql` in the default quoted `"flyway_schema_history"` table; Flyway's Db2 success column is `SMALLINT` (`1`). Probe the required `WORK_ITEM` columns, validate exactly one namespace using the existing validation, and require numeric `CURRENT TIMEZONE = 0`. No runtime Flyway dependency in the engine.
- [x] Ensure all settings/handler checks and `SchemaCheck.verify` run through required bean dependencies **before** `QueueRunner` can start. Do not use `ApplicationRunner` or `CommandLineRunner` for worker preflight: Spring starts `SmartLifecycle` earlier. Preserve Spring shutdown ordering so the runner stops before its datasource is closed. Disable neither missing-handler failures nor namespace failures through permissive bean conditions.
- [x] Register the existing metrics/health adapters with the assembled runner/processor using their supplier contracts; use names `workQueueLiveness` and `workQueueReadiness` for health contributors. Bind metrics only when a registry is available; do not add HTTP endpoints or start a web server.
- [x] Run `./mvnw -pl work-queue-engine test`; require all tests to pass. Commit this independently testable Spring integration.

## Task 2: Add the example application and explicit commands

**Files:** Demo Maven, Java, YAML and unit-test files in the table; register the module in the parent POM. Add basic build/run/handler instructions to the README in the same task.

**Interfaces:**
- Executable jar: `db-work-queue/work-queue-demo/target/work-queue-demo.jar` (fixed Maven final name).
- `DemoApplication.main(String[] args)` accepts `--demo.mode=worker|migrate|seed|verify`, default `worker`; invalid modes exit nonzero before any DB operation. Select the appropriate Spring profile before refreshing the context.
- `ExampleHandler implements ExternalService` implements its unchanged `call(...)` signature, returning `new CallResult("processed:" + payload)` for the short ASCII sample payloads. Document result-size limits and durable idempotency when replacing this demonstration logic.
- `DemoCommands(DataSource dataSource)` exposes `void migrate(String namespace)`, `List<Long> seed(int count)`, and `Verification verify(List<Long> ids, Duration timeout)`. Its nested immutable `Verification(int total, int done, int failed, int pending, int claimed, int missing)` has `boolean succeeded()`.
- `seed` writes committed numeric row IDs, one per line, to `--demo.batch-file=<path>`; default count is 10. `verify` reads that file and uses a 120s default timeout. Reject an empty file, duplicates, nonpositive IDs, invalid numbers, and nonpositive counts/timeouts.

- [x] Add unit tests for deterministic handler output, mode/profile routing, file validation, and the verification decision. The critical decision is:

  ```java
  assertThat(new Verification(10, 10, 0, 0, 0, 0).succeeded()).isTrue();
  assertThat(new Verification(10, 9, 0, 0, 0, 1).succeeded()).isFalse();
  assertThat(new Verification(10, 9, 1, 0, 0, 0).succeeded()).isFalse();
  ```

  Assert unrelated rows never enter the query/results, a failed requested job fails verification immediately, and deadline expiration returns nonzero. Use an injected clock/sleeper internal seam for timeout tests if needed; do not sleep two minutes in unit tests.
- [x] Run `./mvnw -pl work-queue-demo -am test -Dtest=ExampleHandlerTest,DemoCommandsTest,DemoApplicationTest -Dsurefire.failIfNoSpecifiedTests=false`; confirm the missing implementation fails.
- [x] Implement the small application. `worker` uses `spring.main.keep-alive=true` with no web server. Maintenance modes exclude `hle.org.workqueue.engine.WorkQueueAutoConfiguration`, do their one-shot action, close the context/pool and exit. Both profiles set `spring.flyway.enabled=false` and `spring.sql.init.mode=never`; only explicit `migrate` invokes Flyway programmatically, with `workqueueNamespace=demo`, migration location `classpath:db/migration/workqueue`, and cleaning disabled. Shared demo datasource configuration explicitly supplies pool wait 2s, validation 1s, JCC login 3s, blocking read 8s and close-socket-on-query-timeout mode 2, so maintenance is bounded even with engine configuration excluded. Use a 5s query timeout for seed/verify SQL.
- [x] Seed fresh UUID operation IDs and short payloads (`job-1`, etc.) through the documented two-column insert contract in one transaction. Write the manifest only after commit. Never delete or reset rows. Before demo migration/seed/verify, require `CURRENT SERVER = WORKQ`; the script supplies the dedicated local connection. A standalone worker can use another correctly configured database and namespace.
- [x] Implement verification over exactly the manifest IDs using parameterized SQL, counting missing rows as failures. Every query has a finite timeout; use overflow-safe monotonic deadlines and cap sleeps at the remaining allowance. Print status counts, not payloads, results, operation identities or credentials. Exit nonzero on timeout/failed/missing jobs; keep the normal Spring graceful worker shutdown.
- [x] Run unit tests and `./mvnw -pl work-queue-demo -am package -DskipTests`. Verify the jar manifest/packaging and that the demo imports no package-private engine machinery. Commit the example and initial usage instructions.

## Task 3: Make first run a single local command

**Files:** `work-queue-demo/compose.yml`, `.env.example`, `.gitignore`; `scripts/first-run.sh`, `scripts/test-first-run.sh`; update `README.md` with the actual invocation and cleanup commands.

**Interfaces:** `./scripts/first-run.sh` works from any working directory and returns 0 only when the newly inserted batch is entirely DONE. Compose project name `workqueue-first-run`, service `db2`, database `WORKQ`, namespace `demo`, platform `linux/amd64`, timezone UTC, host binding `127.0.0.1:50000`. Image remains `icr.io/db2_community/db2:12.1.5.0`.

- [ ] Add a shell verification harness that copies the script into a temporary fixture project with a stub `mvnw`, puts stub `java` and `docker` in its `PATH`, and records invocations/process lifetimes. Cover normal order, a repeat with a fresh manifest, failed build/DB readiness/migrate/seed/worker/verify, SIGINT/SIGTERM, and an existing unrelated worker. Assert exit codes, cleanup of this invocation's children, no invocation of `clean`, `DELETE` or `down --volumes`, and no secrets in command arguments/output. Run with Bash available on macOS; do not require Bash 4-only process primitives.
- [ ] Run `bash scripts/test-first-run.sh`; confirm it fails before the first-run script exists.
- [ ] Implement orchestration: check JDK 25, Docker Compose and `openssl` availability; build via the wrapper; create/reuse a permission-restricted ignored `.env` with an `openssl rand -hex 24` password; start Db2; wait with a **15 minute** upper bound for an actual database query to succeed; then run migrate, seed, background worker, and verify. Build failure happens before creating a container. Preserve existing credentials/data and never `source`/`eval` an environment or manifest file.
- [ ] Pass credentials to Java through environment variables, not `--spring.datasource.password`. Construct the loopback JDBC URL explicitly; do not accept an arbitrary inherited datasource URL in this first-run script. Use a fresh directory under the demo's `target/first-run/` for the manifest and worker log; print their paths and status counts without business data. Keep the database running afterward for inspection.
- [ ] Use traps that preserve the original exit status, terminate the worker started by this script, and wait for graceful exit. Bound cleanup at **35 seconds**, then terminate the still-running child if necessary; never signal unrelated workers or use broad process-name matching. Detect early worker exit during verification and stop the verifier too. Cleanup must also run on an interrupted or failed step.
- [ ] Document `docker compose --project-name workqueue-first-run --env-file work-queue-demo/.env -f work-queue-demo/compose.yml down` as the non-destructive stop command. Document `down --volumes` separately and explicitly as removal of **demo data**; the first-run script never invokes it.
- [ ] Run `bash -n scripts/first-run.sh scripts/test-first-run.sh` and `bash scripts/test-first-run.sh`. Verify the Compose configuration without printing interpolated secrets. Commit the runnable local workflow and README changes.

## Task 4: Prove the complete path and finish the reading guide

**Files:** `demo/src/test/java/hle/org/workqueue/demo/FirstRunIT.java`, demo Failsafe/test dependencies, `README.md`, `docs/architecture.md`, this plan's verification record.

**Interfaces:** `FirstRunIT` uses a real Db2 container configured with database `WORKQ`, namespace `demo`, and the same external-package application/auto-discovery as the executable example; it never directly constructs or imports engine implementation types. It owns an isolated database/schema and never uses the engine IT helper that cleans shared test state.

- [ ] Add the real integration scenario: migrate through `DemoCommands`; seed A; start the worker context; verify A; close context; seed B; restart with a fresh owner; verify B and assert A's statuses/results/attempt counts are unchanged. Query the stored example result to assert the actual handler ran. Test worker startup against a missing migration and mismatched namespace, with no jobs claimed.
- [ ] Run `./mvnw test` and require all unit tests to pass. Obtain the specified Db2 image through the normal authorized Docker workflow; if unavailable, preserve the work and mark real-DB verification incomplete rather than replacing the gate with mocks.
- [ ] Run `./mvnw verify` for existing and new ITs. Require all available tests to pass; report test counts and failures accurately. This does not count deferred runtime/operations/process/load scenarios as implemented.
- [ ] Run `./scripts/first-run.sh` twice on the documented local setup. Confirm real output, fresh batches, retained prior work, nonzero failure behavior, and no leftover worker process after each script exits. Keep the database and generated files for the user's inspection.
- [ ] Rewrite the README's opening around prerequisites → one command → expected counts → `ExampleHandler.java` → enqueue SQL → result query → stopping. Move the implementation-phase status and detailed tests below the quickstart. State any integration verification limitation at the top until resolved.
- [ ] Rewrite the reading guide around `ItemProcessor.process()` and **insert → claim → handler → persist → release**. Explain persistent completion versus local task cleanup, and persistence retry versus another processing attempt. Put ownership tables, leases, fencing and shutdown after that path. Link to exact source files; do not create another inventory-first guide.
- [ ] Perform the usability check using only the quickstart: locate/change the example handler, rerun, inspect its stored result, and locate the five steps in source. Restore any temporary verification-only handler edit. Run `git diff --check`; obtain an independent final review and resolve actionable findings. Commit the verified guide and final cleanup.

## Handoff and completion

Recommend **native execution in this chat**, followed by one independent final review: these four tasks share startup and command interfaces, and sequential implementation avoids duplicate setup while keeping the first-run goal visible. Use an isolated worktree at execution time based on the reviewed deep-modules branch.

Completion means the actual documented command and repeat run succeed, not just that the classes compile. If image access or Db2 prevents that evidence, report the specific limitation and leave the real-DB acceptance items unchecked.
