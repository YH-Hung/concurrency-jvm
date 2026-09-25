# Claim-SQL spike (Phase 1)

Spec: [§6 "Claim SQL"](../../docs/superpowers/specs/2026-09-21-db-work-queue-design.md).
Evidence: `ClaimSqlSpikeIT`, which regenerates the table below in `work-queue-engine/target/claim-sql-spike.md`.

## Run

- Date: 2026-09-25 (V1 of spec revision 7); first run 2026-09-24 gave the same results
- Db2: `icr.io/db2_community/db2:12.1.5.0`, linux/amd64 under Rosetta emulation
- JCC: 12.1.5.0 (managed by Spring Boot 4.1.1)
- Pool: IT timeouts (§5.3); `CURRENT LOCK TIMEOUT` 1s, so a claim that waits for a lock fails after 1s

## Candidates

`ClaimSqlSpikeIT` run alone on a freshly migrated schema:

| Id | Form | Accepted | Skips locked rows | Oldest first | Second claim took | Error |
|---|---|---|---|---|---|---|
| A1 | single statement; SKIP LOCKED DATA ends the UPDATE inside FINAL TABLE | false | false | false | 0 ms | SQLCODE=-104 SQLSTATE=42601 DB2 SQL Error: SQLCODE=-104, SQLSTATE=42601, SQLERRMC=SKIP;= CURRENT TIMESTAMP ;), DRIVER=4.38.7 |
| A2 | single statement; SKIP LOCKED DATA ends the outer SELECT | true | true | true | 3 ms |  |
| A3 | single statement; SKIP LOCKED DATA ends the inner fullselect | false | false | false | 0 ms | SQLCODE=-104 SQLSTATE=42601 DB2 SQL Error: SQLCODE=-104, SQLSTATE=42601, SQLERRMC=SKIP;CH FIRST 1 ROWS ONLY;), DRIVER=4.38.7 |
| B | SELECT ... WITH RS USE AND KEEP UPDATE LOCKS SKIP LOCKED DATA, then UPDATE by ID | true | false | false | 1040 ms | SQLCODE=-911 SQLSTATE=40001 DB2 SQL Error: SQLCODE=-911, SQLSTATE=40001, SQLERRMC=68, DRIVER=4.38.7 |
| C | as B, without ORDER BY | true | false | false | 1024 ms | SQLCODE=-911 SQLSTATE=40001 DB2 SQL Error: SQLCODE=-911, SQLSTATE=40001, SQLERRMC=68, DRIVER=4.38.7 |

## Decision

Chosen form: A2 — the first candidate that is accepted, skips locked rows, and takes the
oldest rows first. `WorkItemRepository.claimSelection` (claim) and `WorkItemRepository.sweep` use it.
`ClaimSqlSpikeIT` fails unless A2 keeps all three properties; `WorkItemRepositoryIT` checks lock skipping
and oldest-first order under contention for the repository's own statement.

B and C are not stable: in a full `./mvnw verify`, where earlier ITs have filled and emptied `WORK_ITEM`
before the spike runs, both skipped the locked row without waiting (and took the oldest rows). Run alone,
on the original and on the revision 7 V1 alike, their `UPDATE` by `ID` waited for the locked row until the
lock timeout. Their behaviour presumably follows the `UPDATE`'s access plan, which depends on table
statistics. A2 skipped locked rows and took the oldest rows first in every run.

## Claim latency under contention (ConcurrentClaimIT)

16 owners, 1000 rows, batch 20, IT timeouts (T_tx 2s, T_read 2s, T_lock 1s). Emulated Db2: a regression
baseline for the §5.3 timeout review, not a production capacity claim.

```
ConcurrentClaimIT: 1000 rows, 16 owners, batch 20, DbTimeouts[poolWait=PT0.5S, login=PT1S, transaction=PT2S, read=PT2S, lockWait=PT1S]
claim: n=66 p50=7ms p99=99ms max=99ms
complete: n=1000 p50=2ms p99=42ms max=65ms
```

## Phase 1 gate (spec §12: ITs 1–5)

Run: 2026-09-25, `./mvnw verify` (20 unit tests, 70 ITs; first passed 2026-09-24).

| IT | Covers | Result |
|---|---|---|
| 1 `ConcurrentClaimIT` | 16 owners, 1000 rows, no row claimed twice | pass |
| 2 `WorkItemRepositoryIT` | claim fields, predicate and order; reclaim; renew; fencing by token and owner; read-back; sweep; replay; revokeOwner | pass |
| 3 `StaleCompletionIT` | stale owner loses renewal and is fenced | pass |
| 4 `QueryTimeoutIT` | stuck statement within T_tx + 1s (close-socket); lock wait within T_lock + 1s | pass |
| 5 `RevokeRaceIT` | revoke versus complete, retryOrFail and renew; old owner fenced afterwards | pass |

Renewal form: `SELECT ID, CLAIM_TOKEN FROM FINAL TABLE (UPDATE … WHERE STATUS = 'CLAIMED' AND OWNER = ? AND (pairs))`
as in spec §5.3, accepted by Db2 (`WorkItemRepositoryIT.renewReturnsExactlyThisOwnersMatchingClaimedPairs`).

Findings:

- Spec §6 primary claim form (A1: `SKIP LOCKED DATA` ending the UPDATE inside `FINAL TABLE`) is rejected by Db2 12.1
  (SQLCODE -104). Claim and sweep use A2 (`SKIP LOCKED DATA` after `FINAL TABLE (…)`); spec §6 now shows A2 (revision 7).
- Close-socket query timeout works: a statement stuck past T_tx returned in about 2.1s with SQLSTATE 08001 (-4499).
  But its connection is already closed, so the rollback fails, and Spring's `TransactionTemplate` throws
  `TransactionSystemException` ("Application exception overridden by rollback exception", logged at ERROR). The JCC
  exception was reachable only through `getApplicationException()`. `WorkItemRepository.inTransaction` now maps it to
  the statement's own `DataAccessException` (and maps `CannotCreateTransactionException` /
  `TransactionTimedOutException` to `DataAccessResourceFailureException`), so every database failure surfaces as a
  `DataAccessException`. Spring still logs "Application exception overridden by rollback exception" at ERROR on this
  path, which Phase 2 should decide how to handle.
- Latency on emulated Db2 (ConcurrentClaimIT, above) is far below the IT timeouts; no timeout was exceeded in any IT.

Open questions for Phase 2:

- Sweep keeps `OWNER` and `CLAIM_TOKEN` on the rows it fails. A late `retryOrFail` from the swept owner then reads back
  `FAILED` as if it were its own write, and its error text is not stored. Decide in the Sweeper/ItemProcessor design
  whether sweep should clear `OWNER`. Deferred by the project owner.
- Db2 `CURRENT TIMESTAMP` is the server's local time, and every lease, backoff and expiry comparison uses it (spec
  §5.1). If the Db2 server's time zone observes DST, all live leases expire at once at spring-forward and expiry is
  delayed by up to an hour at fall-back. The spec does not state a time-zone precondition. Decide before V1 is
  deployed: require the Db2 server to run in UTC (`CURRENT TIMEZONE = 0` at startup is necessary but not sufficient,
  because zones such as Europe/London are at offset 0 in winter and still observe DST; the precondition must be
  that the server's time zone is UTC), or make the arithmetic UTC-based.
