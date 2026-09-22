# Router: head-of-line blocking — backpressure by deferral (owner rulings 2026-09-22)

**The contract is `docs/go-mirror/2026-09-22-router-hol-deferral-handoff.md`** — the owner's
hand-off, copied verbatim; its seven rulings and §1–§7 bind Java, and this file does not repeat
them. Go reference: `1c37a33` (`internal/router/{pool_admission,deferral_ledger,manager,pool}.go`,
`internal/queue/{sqs,nats}`). Go is evidence of what Go does, not authority (CONVENTIONS §8);
where the hand-off and Go's code disagree, the hand-off wins and the disagreement is reported.

This amends `router.md` and `router-deferral-handback.md`. The 2026-09-17 deferral (a *target's*
`{"ack": false, "delaySeconds"}`) and this one (a *full pool's* backpressure) are different
hand-backs and stay distinct in the code: the first is a nack with reason `deferred`, the second is
`defer` — a new verb, counted separately, never a failure.

## The incident, in Java terms

`ConsumerLoop.hasRoom()` already pauses only when **every** pool the last batch fed is full —
Java had half of the fix. What it lacks: the deferral budget clause on the gate, `defer` on the
brokers, the per-pool admission schedule, the per-consumer ledger with its wake-up, the NATS
defaults, the doubled buffer, and the dashboard field.

## What lands where

| Hand-off § | Java |
|---|---|
| §1 poll gate | `ConsumerLoop.hasRoom` gains `|| ledger.outstanding(now) < budget`; `DeferralLedger` (min-heap of return times, `outstanding(now)` prunes `<= now`, `earliest()`), one per consumer, owned by `RouterManager` keyed by queue identifier; the parked wait wakes on the capacity gate **or** a timer armed to `earliest()` — find the existing park/wake in `ConsumerLoop`/`RouterManager` (`awaitCapacity` or its equivalent) and extend that one wait, do not add a second loop; the pause warning text as the hand-off gives it |
| §2 defer | `Acknowledger.defer(QueuedMessage, Duration)` — **abstract, no default** (CLAUDE.md: every backend has an opinion); `SqsQueue` (same `ChangeMessageVisibility` and 12 h clamp as its nack), `PostgresQueue` (same `visible_at` update), `NatsQueue` (`nakWithDelay`); each counts `totalDeferred`, not `totalNacked`. `Pool.submit`'s at-capacity branch (`Pool.java:318`/`:322` — check which is the buffer-full one) becomes `broker.defer(message, admission.delay(...))`; every other `REJECTED_NACK_DELAY` site stays a nack. The pool reports `(queueIdentifier, returnAt)` to the manager for the ledger |
| §3 admission | `PoolAdmission` beside `Pool`: rate from a **timestamped completion ring** added to `PoolMetricsCollector` (it already keeps `Deque<Instant>` windows per outcome — reuse that shape: completions in the last 5 min ÷ span since the oldest, floored 1 s); the cursor under its own lock; the formula and constants exactly as §3, including the `honoursDelayedReturn()` ≥ 1 s spacing and backward jitter over the last quarter of the horizon |
| §4 NATS | `NatsQueueUri.DEFAULT_MAX_DELIVER` and `DEFAULT_MAX_ACK_PENDING` → `-1`; `Acknowledger.honoursDelayedReturn`'s Javadoc loses the `max-deliver` half of its rationale and gains the sentence the hand-off gives |
| §5 buffer | `Pool.QUEUE_CAPACITY_MULTIPLIER` 20 → 40, `MIN_QUEUE_CAPACITY` 50 → 100 |
| §6 dashboard | `totalDeferred` on the pool stats (camelCase dashboard DTO, `total_deferred` on `/monitoring/pools`); the **Deferred** column after Rate Limited in `dashboard.html` — row, totals, sort key, tooltip text as given; Prometheus counter beside the nack counter in `RouterPrometheusCollector` |
| §7 config | `FC_ROUTER_DEFERRAL_MAX_DELAY_SECONDS` (3600), `FC_ROUTER_DEFERRAL_BUDGET` (15000, raised from 5000 — see the Addendum) in `Env`, documented in `docs/spec/router-env.md` beside the other `FC_ROUTER_*` |

Logs are Go-shaped by ruling: mirror the field names of Go's log lines for a deferral and for the
pause warning.

## Tests — port Go's `pool_admission_test.go` first (mutation-checked there); one mutant per condition

| # | Behaviour | Mutant |
|---|---|---|
| D1 | consecutive slots: pool at 0.2/s with 100 buffered ⇒ first reservation ≈ 505 s, each next ≈ +5 s | drop `nextReturn` from the `max` |
| D2 | floor 5 s; fallback 30 s + 1 s spacing with no completion in the window | each constant |
| D3 | horizon clamp: never above the horizon, never below 75 % of it (many samples) | jitter forward; jitter over the whole horizon |
| D4 | NATS spacing: at 100/s an ordered broker's reservations all sit on the 5 s floor; an unordered (`honoursDelayedReturn=false`) broker's are ≥ 1 s apart | drop the `max(slot, 1 s)` |
| D5 | rate = completions ÷ span since the oldest, not ÷ window: a pool with 10 completions in the last 30 s reports 0.33/s, not 0.033/s | divide by the window |
| D6 | ledger: `outstanding(now)` prunes what is due; `earliest()`; a deregistered queue has no ledger and a deferral for it is dropped without error | never prune |
| D7 | wake on due: budget 1, one deferral due in 150 ms, every last-batch pool full ⇒ the parked consumer resumes within 1 s — **a timing assertion** | no timer (the test must time out, bounded) |
| D8 | each broker's `defer`: SQS changes visibility by the delay (clamped at 12 h from the original receive, same as nack) and counts `totalDeferred` with `totalNacked` unchanged; Postgres sets `visible_at`; NATS `nakWithDelay` | count as a nack; ignore the delay |
| D9 | `Pool.submit` at capacity **defers** with the admission delay (≥ 5 s) and does not nack; pool closed / stopped / shutdown-before-dispatch still **nack** | defer everywhere; nack at capacity |
| D10 | **end to end, two batches** — exactly as the hand-off's trap: first a batch of only the slow pool's messages (the consumer's remembered set becomes `{SLOW}`, full), then the fast pool's; assert fast messages acked, slow ones deferred (not nacked) with delay ≥ 5 s in arrival order, no tracker entries left, the consumer still has room. A single mixed batch must be shown NOT to catch the old gate (leave that as a comment, or a second test that passes against both) | restore the old gate (drop the budget clause) |
| D11 | the tests that pinned "parks on a full pool" now spend the budget first (budget 1 + one ledger entry due in an hour) — the only state that parks | — |
| D12 | NATS URI defaults `-1`/`-1`; still settable per URI | — |
| D13 | buffer `max(concurrency × 40, 100)` | — |
| D14 | `/monitoring/pools` carries `total_deferred`; the dashboard DTO `totalDeferred`; the Prometheus counter | — |

The router conformance suite (`MediationConformanceTest`, the `router-completion.md` ledger) must
stay green; the 2026-09-17 deferral tests must stay green unmodified.

## Owner rulings 2026-09-22, on the questions the hand-off left open

- **SQS redrive:** the FC queues have no redrive policy and no DLQ, so a deferral's redeliveries
  can dead-letter nothing. Closed.
- **SQS retention:** an expired message is acceptable — set retention to **4 days** (IaC, owner's
  side); jobs longer than a day are not a real case.
- **Buffer:** the hand-off's doubling stands; a global buffer budget is **not** pursued now.
- **The platform's stale sweep fought the deferral** (found here, both platforms): a job stays
  `QUEUED` until the router delivers it, and `StaleQueuedJobPoller` reverted any `QUEUED` row older
  than 5 minutes to `PENDING` for re-publish — so a message deferred toward the 1 h horizon got a
  second copy every 5 minutes (a 10k backlog ⇒ ~120k in flight against SQS FIFO's 20k ceiling).
  **Ruling: no automatic resend of a `QUEUED` job at all** — `StaleQueuedJobPoller` is removed; a
  message the broker holds is the broker's until delivered, and an expired one is simply gone (the
  old PHP mediator needed the resend; this one does not). **The reaper redrives `PROCESSING` rows
  older than 15 minutes** (`DispatchJobReaper.DEFAULT_PROCESSING_LIVE_AFTER` 45 → 15 min, the old
  system's value). Accepted: a delivery still hanging on its second 15-minute attempt can be
  redriven — a duplicate to a target that is already broken. Same change in Go
  (`internal/platform/scheduler` stale recovery removed; `reaper.go` 45 → 15) — hand-off owed.
  Lands as its own unit after this one; `docs/spec/dispatch-seam.md` §3/§7 amended then.

## Addendum 2026-09-22 (catch-up slice C4): a deferred copy keeps its in-flight entry

**Contract:** `docs/go-mirror/2026-09-22-catch-up-handoff.md` item A, `docs/go-mirror/2026-09-22-router-hol-deferral-handoff.md`
§Addendum. Go: `bbd5488` (`internal/router/inflight.go`, `pool_admission.go`).

Production after the HOL fix above: SQS and the router dashboard showed hundreds in flight for a
fraction as many pending jobs. Cause: `QueueBroker.defer` **removed** the tracker entry for a
deferred copy, so a duplicate published while the copy sat parked — from any source, not only the
withdrawn `StaleQueuedJobPoller` above — was seen as brand new and deferred again alongside the
original, forever.

- `InFlightTracker.markDeferred(messageId, until)` stamps the entry's `deferredUntil` instead of
  removing it; `QueueBroker.defer` calls it **before** the broker call (never after), so the mark
  stands even when that call fails — the message then simply returns at its natural visibility
  lapse, still as itself. Unregistered-queue defer (no consumer to hand back to) still removes the
  entry, exactly as before.
- `register()`: a deferred owner changes what "the same delivery again" means. A **different**,
  non-blank broker id is still `ExternalRequeue` — a duplicate to ACK away, never delivered
  alongside the parked original. The **same or a blank** broker id is the parked copy itself
  returning: the mark is cleared, the fresh receipt adopted, and the outcome is `New` (not
  `Redelivery`) — nothing else is holding the pipeline for it, so the caller must submit it.
- `size()` excludes deferred entries (a drain must not wait for the broker's own redelivery
  schedule); `deferredSize()` reports them. Go names these `Count()`/`DeferredCount()` — Java keeps
  its existing `size()` name for the non-deferred count rather than renaming it, and adds
  `deferredSize()` alongside for the same reason CONVENTIONS §8 prefers idiom over transliteration.
- `reapIdle`'s idle rule skips a deferred entry until `deferredUntil + 5 min`
  (`InFlightTracker.DEFERRED_REAP_GRACE`) — the broker's own redelivery is not to the second — but
  never past `InFlightTracker.DEFERRED_ABSOLUTE_CEILING` (2 h, measured from `startedAt`): a copy
  the broker never returns still ages out, so a later republish is admitted rather than deduped
  against a phantom forever. This ceiling is new to Java — the pre-existing `reapIdle` had no
  absolute bound at all (a genuinely retrying entry, `attempts > 0`, is still exempt without limit,
  unchanged, a divergence from Go's `Reap` that predates this slice and is out of scope here).
- Budget default **5000 → 15000** (hand-off, same day): the incident above showed the modest
  headroom the old default gave against SQS FIFO's 20k in-flight ceiling was too easily spent by
  ordinary backlog, not just a bug. The pause warning now states the budget and the remedy
  verbatim from Go's wording (`ConsumerLoop.awaitCapacity`).

Tests to mirror: Go `inflight_deferred_test.go` (tracker: mark/return/dedup/count/reap), Go
`TestDeferredMessageRepublishedCopyIsDeletedNotDeferred` (end to end: first copy deferred, a second
copy under a new broker id is ACKed not deferred, deferred count stays 1).
