# DB work queue: a working first example

Status: design approved by the user on 2026-10-03; implementation plan pending review.

## Outcome

The user wants both a usable queue and code they can understand, starting with a runnable end-to-end example. Success means running a documented command, seeing jobs reach `DONE` in real Db2, changing the example handler, and running it again without assembling engine internals.

The previous refactor preserved behavior and clarified ownership, but left application startup deferred. The current README describes building tests; there is no application to run. This slice closes that gap and makes the ordinary processing path the starting point for learning the engine.

## Approaches

1. **Spring Boot wiring plus a minimal example — recommended.** Follows the existing intended integration contract: an application supplies an `ExternalService` bean and configuration. Provides a real usage path without exposing claim machinery.
2. A public manual-start facade. Could shorten example setup, but introduces a second integration contract alongside the planned Spring lifecycle.
3. Documentation and a test walkthrough only. Helps explain internals, but leaves the missing application entry point unresolved.

## The first-run experience

From `db-work-queue/`, the proposed command is:

```sh
./scripts/first-run.sh
```

The script builds the example, starts a local demo Db2 container, applies migrations through a separate one-shot mode, inserts a small batch of sample jobs, starts the worker, and verifies that that batch finishes. It prints the inserted, completed, and failed counts and exits nonzero on failure or a bounded verification timeout.

The script uses explicit demo database configuration. It never deletes existing work or points at an arbitrary application database. Every invocation inserts a new batch with fresh operation IDs and verifies only that batch. The README explains startup time, the first image download, and separate stop and data-removal commands.

Db2 remains real Db2; the example does not substitute an in-memory database. The currently required image is absent locally, so a verified first run depends on obtaining it. Until that run succeeds, the example must be labeled unverified.

## What the application supplies

The new `work-queue-demo` module contains a small Spring Boot application, an `ExternalService` implementation, and example configuration. The handler is a deterministic transformation with no external side effects: it makes the queue mechanics easy to observe without requiring another service. Its source is the obvious place to substitute application work.

The guide distinguishes this demonstration handler from a handler that causes real external effects. Such handlers retain the existing requirement for durable downstream idempotency using `IdempotencyKey`; queue fencing protects database writes and does not by itself prevent repeated external effects.

The example application imports only the supported SPI/value/configuration types. It does not use the engine package to bypass visibility and does not construct a repository, processor, runner, semaphore, claim handle, or background loop.

## Engine startup

Implement the existing intended Spring Boot auto-configuration contract. Given the application handler and configured database, it assembles the engine and lets Spring manage worker start and graceful stop. Required handler/configuration, database timing and pool constraints, schema readiness, namespace identity, and database time-zone checks must succeed before polling starts.

Add the planned `workqueue.expected-namespace` property and retain all existing property names, defaults, and timing validation. Workers never migrate or seed the database. Demo migration and seeding run in separate, explicit modes before worker startup.

Keep the current claim, renewal, fencing, persistence, and cancellation algorithms. This slice does not introduce another public queue API or rewrite the execution model.

## Understanding the code

The README starts with prerequisites, the first-run command, expected output, where to change the handler, and how an application enqueues work. An upstream producer inserts `OPERATION_ID` and `PAYLOAD` into `WORK_ITEM`; the engine manages the processing columns.

The reading guide then follows one job:

```text
insert a row -> claim it -> call the handler -> store its outcome -> release local capacity
```

Begin the source walkthrough at `ItemProcessor.process()`. Explain two distinctions explicitly: storing a successful result is different from releasing a task's local capacity, and retrying a database write is different from scheduling another processing attempt. Introduce leases, fencing, renewal, supervision, and shutdown after the ordinary path.

The usability check is concrete: a reader should be able to find the handler, replace its behavior, locate the resulting database value, and explain that five-step path using the example and guide. Further internal simplification should address obstacles discovered in that exercise.

## Verification

- Spring context tests prove that application code outside the engine package can supply the handler and start the configured engine, and that invalid startup conditions prevent polling.
- Existing unit and lifecycle tests continue to pass.
- A real Db2 first run migrates, inserts, processes, and verifies its own batch through the public integration path.
- Running the example again succeeds without removing the earlier batch.
- Shutdown uses the existing graceful lifecycle; no worker thread is assembled by example code.
- Documentation commands and expected output are checked against the actual run.

## Scope boundary

This is the smallest usable application slice of the previously planned operations phase. Admin endpoints, replay/revoke UI, security roles for management endpoints, the durable simulated downstream with fault injection, chaos/process scenarios, and load validation remain separate work. This example does not establish production readiness or satisfy those pending gates.
