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
| §7 config | `FC_ROUTER_DEFERRAL_MAX_DELAY_SECONDS` (3600), `FC_ROUTER_DEFERRAL_BUDGET` (5000) in `Env`, documented in `docs/spec/router-env.md` beside the other `FC_ROUTER_*` |

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
