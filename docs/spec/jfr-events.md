# Flight-recorder events for the Phase 2 loops

Written 2026-09-05 by the orchestrator. The router already has its three
events (`io.flowcatalyst.router.observability.jfr`: `MessageSettled`,
`Dispatch`, `GroupDecision`, landed `246e270`), the dispatch scheduler has
`ClaimedBatch`, and the dispatch-job seam has `DispatchProcessed`. Every
loop Phase 2 added is silent in the same way the router used to be: a row
leaves a queue state and no counter or log line can say afterwards which
row, why, and with what outcome. This spec adds one event per loop at the
point where the decision becomes irreversible. `[C]` contract.

Rationale and conventions are `RouterEvent`'s class doc: `@Category` with
`"FlowCatalyst"` first, `@StackTrace(false)`, `@Label` on every field,
`shouldCommit()` guarded so a disabled event costs nothing, and the event
is committed **after** the state change it describes has been persisted
(never before — an event for a write that then fails is a lie in the
recording). Each subsystem gets its own package-private abstract base
`<Subsystem>Event` with the category, mirroring `RouterEvent`.

## 1. Stream — `io.flowcatalyst.stream.FanOutBatch` [C]

Committed once per `FanOut` transaction that claimed at least one event,
after the transaction commits (in `FanOut`'s step, around the `StreamTx.run`
result — not inside the transaction).

| Field | Label | Meaning |
|---|---|---|
| `eventsClaimed` (int) | Events Claimed | rows the `FOR UPDATE SKIP LOCKED` claim took |
| `jobsInserted` (int) | Dispatch Jobs Inserted | rows written to `msg_dispatch_jobs` |
| `subscriptions` (int) | Active Subscriptions | size of the subscription cache used for matching |
| `noSubscriptions` (boolean) | No Subscriptions | the claim-only path (`claimNoSubscriptions`) was taken |

Category `{"FlowCatalyst", "Stream"}`.

## 2. Outbox — `io.flowcatalyst.outbox.OutboxItemSettled` [C]

Committed in `OutboxProcessor.applyOutcome` after `markSuccess` /
`markFailed` returned (or threw — then `persisted=false`, and the event is
still committed, because "the repository update failed and the item will be
re-claimed" is exactly the thing an operator needs to see).

| Field | Label | Meaning |
|---|---|---|
| `itemId` (String) | Item ID | |
| `type` (String) | Item Type | `OutboxItemType.name()` |
| `group` (String) | Message Group | nullable |
| `outcome` (String) | Outcome | `SUCCESS` or `FAILURE` |
| `status` (String) | Status | `OutboxStatus.name()` written on failure; `DELIVERED` on success |
| `requeued` (boolean) | Requeued | a failure that goes back to pending |
| `grouped` (boolean) | Ordered Group | delivered on the per-group path |
| `persisted` (boolean) | Repository Updated | the mark call succeeded |

Category `{"FlowCatalyst", "Outbox"}`.

## 3. Scheduled jobs — `io.flowcatalyst.platform.scheduler.jobs.JobFired` [C]

Committed in `JobDispatcher` after the instance's terminal mark for this
attempt (`markDelivered`, `markDeliveryFailed`), and for the orphan path.

| Field | Label | Meaning |
|---|---|---|
| `instanceId` (String) | Instance ID | |
| `jobCode` (String) | Job Code | |
| `attempt` (int) | Attempt | attempts after `markInFlight` (0 for an orphan) |
| `outcome` (String) | Outcome | `DELIVERED`, `FAILED`, `ORPHAN` |
| `terminal` (boolean) | Terminal | no further attempts |
| `statusCode` (int) | Status Code | HTTP status, 0 when no response |
| `signed` (boolean) | Signed | the firing carried `X-FlowCatalyst-Signature` |

Category `{"FlowCatalyst", "ScheduledJob"}`.

## 4. Purger — `io.flowcatalyst.platform.purger.PurgerStep` [C]

Committed once per `step(name, action)` in `Purger.tick`, after the action
returned or threw.

| Field | Label | Meaning |
|---|---|---|
| `step` (String) | Step | the step name passed to `step(...)` |
| `succeeded` (boolean) | Succeeded | |
| `error` (String) | Error | exception class + message on failure, else null |

Category `{"FlowCatalyst", "Purger"}`.

## 5. DB secret — `io.flowcatalyst.server.dbsecret.DbSecretRefresh` [C]

Committed in `DbSecretRefresher.refreshNow` after the apply or the caught
failure.

| Field | Label | Meaning |
|---|---|---|
| `succeeded` (boolean) | Succeeded | credentials swapped |
| `error` (String) | Error | on failure |

Category `{"FlowCatalyst", "Database"}`. Never any credential material.

## 6. Tests

`Recorded` (the router test helper) moves to a shared test package
`io.flowcatalyst.testjfr.Recorded` with a `from(Class<? extends Event>,
Runnable)` that disables **every** FlowCatalyst event class it knows
(register the new ones there) and enables only the one under test; the
router's `RouterEventsTest` keeps passing unchanged apart from the import.
One test class per subsystem (`StreamEventsTest`, `OutboxEventsTest`,
`JobFiredEventTest`, `PurgerEventsTest`, `DbSecretEventTest`), each
driving the real loop against the embedded Postgres the way the
subsystem's existing tests already do, and asserting **field values read
back from the dumped recording**, not that an event class exists:

- FanOut: one batch with two events and one subscription → one event,
  `eventsClaimed=2`, `jobsInserted=2`, `noSubscriptions=false`; no events
  when nothing was claimed.
- Outbox: a stub platform answering 200 then 500 → two events with
  `outcome=SUCCESS` / `FAILURE`, the failure's `status` and `requeued` as
  the processor decided them.
- JobFired: a delivered firing (`statusCode=200`, `signed=true` when the
  application has a service account) and an orphan (`outcome=ORPHAN`,
  `terminal=true`).
- Purger: a tick whose partition step throws (drop the table's parent
  privilege, or pass a repository stub that throws) → `succeeded=false`
  with the error text, and the other steps' events still present.
- DbSecret: a stub source that throws → `succeeded=false`.

Mutants to run and report: delete each `commit()` (the test must fail);
swap `outcome` strings in the outbox event; commit the JobFired event
**before** `markDelivered` and have the mark throw — the test that asserts
no event for a failed mark must fail.
