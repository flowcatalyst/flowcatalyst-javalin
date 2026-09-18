# Router: a requested deferral is honoured by the broker (owner ruling 2026-09-17)

Status: **ruled; landed in Java (`5420b516`, `7759059a`), and ported to Go on
the owner's instruction 2026-09-18 (§Go port).** Originally specced as a
deliberate Java/Go difference; that difference is now closed.

## Problem (as found 2026-09-17)

A target answering 2xx `{"ack": false, "delaySeconds": N}` resolves to
`MediationOutcome.Deferred(N)` (`wire/MediationResponse.java`). Today:

1. The pool retries it **in memory** up to `MAX_IN_PIPELINE_ATTEMPTS` (10)
   times, sleeping `RetryPolicy.DEFERRED.delayBefore(attempt, N)` — floor N,
   **cap 60 s** — so a target asking for 600 s is called ~10 times ~60 s apart.
2. Only then is the message nacked. Unordered path passes the backoff delay
   (`Pool.java` ~435); the ordered path (`HeadFailure.ReturnGroup`,
   `Pool.handleHeadFailure`) passes a fixed `REJECTED_NACK_DELAY` (10 s).
3. `SqsQueue.nack` is a no-op — the message returns when the queue's own
   visibility timeout lapses, whatever delay was asked for.
4. `PostgresQueue`'s claim only blocks a row behind an earlier **visible**
   row of its group, so a group head returned with a delay is overtaken by
   its successors.

## Rulings

### R1 — a deferral with a delay goes straight back to the broker
A `Deferred` outcome with `delaySeconds > 0` is **not** retried in memory. On
its first occurrence the message is nacked with a delay of exactly
`Duration.ofSeconds(delaySeconds)` — no `RetryPolicy` curve, no 60 s cap —
with reason `"deferred"`. This applies to both paths:

- **Unordered (IMMEDIATE)**: `broker.nack(message, delay, "deferred")`, then
  return. The worker slot is freed at once; nothing sleeps.
- **Ordered group head**: `OrderedGroups.onHeadFailure` yields
  `ReturnGroup` immediately (not `RetryHead`) for a `Deferred` with
  `delaySeconds > 0`. See R2 for the delays.

A `Deferred` with `delaySeconds == 0` (no delay requested) is **unchanged**:
the existing in-memory retry on the `DEFERRED` curve and its budget.

Other `RETRY_IN_PLACE` outcomes (`RateLimited`/429) are **unchanged** — out
of scope for this unit.

### R2 — a returned ordered group's head carries its real delay
When `handleHeadFailure` handles `ReturnGroup`, the **head** is nacked with:
- `Duration.ofSeconds(delaySeconds)` for a `Deferred` with `delaySeconds > 0` (R1);
- otherwise `backoffFor(head, outcome)` — the same delay the unordered path
  uses for that outcome (today it is a fixed 10 s).

Siblings keep `REJECTED_NACK_DELAY`. On SQS FIFO the delayed head blocks
them; on Postgres R4 does.

### R3 — SQS nack honours the delay
`SqsQueue.nack(message, delay)` calls
`ChangeMessageVisibility(queueUrl, message.receiptHandle(), seconds)`:
- `seconds` = `delay` in whole seconds, `null`/negative → 0;
- clamped to SQS's limit: at most `43200 − secondsSince(polledAt)` where
  `polledAt` comes from `receiptToMessageId` for that receipt (floor 0). If
  the receipt is not in the map (pruned), clamp to 43200;
- **best-effort, never throws** (the `Acknowledger` contract): a failure
  (e.g. stale receipt, `ReceiptHandleIsInvalid`) is logged at WARN with
  `message_id`/`queue` and swallowed; the message then returns at its
  natural visibility timeout, which is today's behaviour;
- `nacked` counter increments as today; nack does not touch `pendingDelete`.

Why this is now safe (replacing the old Javadoc rationale): every
`Broker.nack` call site is a hand-back — `QueueBroker.nack` removes the
tracker entry before calling the consumer — so the router no longer owns the
message and a redelivery after the delay is a fresh delivery, not a
concurrent duplicate. Rewrite `SqsQueue.nack`'s Javadoc to say this.

### R4 — Postgres: a delayed group head blocks its successors (option A)
The claim eligibility gains one condition: a row is not claimable while an
**earlier** row (by `(created_at, id)`) with the same group key
`COALESCE(message_group_id, id)` is **returned with a delay** —
`receipt_handle IS NULL AND visible_at > now`.

- The existing condition (no earlier *visible* row in the group) stays.
- A **claimed** earlier row (`receipt_handle IS NOT NULL`) still does not
  block — today's throughput and cross-poll behaviour for in-flight heads
  are unchanged. Only a nacked-with-delay row blocks.
- Ungrouped rows are their own group key, so they are unaffected.

### R5 — NATS keeps the in-memory deferral (owner ruling 2026-09-17, second unit)
R1's hand-back applies only when the message's broker **honours a delayed
return**. NATS does not, so on NATS a `Deferred` with a delay keeps the
pre-R1 behaviour: in-memory retry on the `DEFERRED` curve and its
`MAX_IN_PIPELINE_ATTEMPTS` budget, both paths.

- `Consumer` gains an **abstract** member (no default — CLAUDE.md: every
  backend must have an opinion), e.g. `boolean honoursDelayedReturn()`:
  `SqsQueue` true, `PostgresQueue` true, `NatsQueue` false. Every other
  implementation (test fakes included) must answer explicitly.
- `pool/Broker` gains an **abstract** `boolean honoursDelayedReturn(QueuedMessage)`;
  `QueueBroker` answers from the message's consumer (by `queueId`); an
  unregistered queue answers `false` (keep it in memory rather than nack
  into a skipped hand-back).
- R1's condition on both paths becomes `delaySeconds > 0 &&
  broker.honoursDelayedReturn(message)`. How `OrderedGroups.onHeadFailure`
  learns it is the implementer's choice (parameter or pre-decided by `Pool`).
- R2 is unchanged: when a NATS head's budget is exhausted and the group is
  returned, a delay-bearing deferral's head still carries its exact delay.

| # | Assert | Mutant |
|---|---|---|
| T12 | unordered: broker answers `false`, target defers 600 s → more than one mediation call before any nack, and no nack on the first deferral | drop `honoursDelayedReturn` from the condition |
| T13 | ordered: broker answers `false`, head defers 600 s → `RetryHead` on attempt 0 (not `ReturnGroup`) | same |
| T14 | `NatsQueue` answers `false`; `SqsQueue` and `PostgresQueue` answer `true`; `QueueBroker` answers from the message's consumer and `false` for an unregistered queue | flip NATS to `true`; `QueueBroker` returning a constant |

#### Findings behind R5 (recorded 2026-09-17)
JetStream here is one
durable WorkQueue consumer with `max-ack-pending` 1000 and no per-group
subject, so the broker enforces **no** group ordering — a delayed head's
successors are delivered regardless (already true of every nack today). And
each hand-back consumes one of `max-deliver` (default 10) deliveries, after
which JetStream stops redelivering silently — R1 turns "10 deliveries × 10
in-memory attempts" into "10 deferrals". Do not change `NatsQueue`.

## Docs to update
- `docs/spec/router.md` §7.2 (SQS Nack row), §7.3 (Poll/claim + Ordering
  consequence rows), §4/§6 wherever the deferral retry is described — mark
  each as a deliberate Java/Go difference, owner ruling 2026-09-17, pointing
  here.

## Tests — each must fail under the named mutant (CLAUDE.md testing policy)

| # | Behaviour | Assert | Mutant that must fail it |
|---|---|---|---|
| T1 | Unordered deferral with delay is handed back once | target answers `{"ack":false,"delaySeconds":600}`: exactly **1** mediation call; broker nack with **600 s**, reason `deferred`; returns promptly (no sleep) | restore the in-memory retry for `Deferred` |
| T2 | Delay is not capped at 60 s | same as T1, nack delay == 600 s, not 60 s | route the delay through `backoffFor` |
| T3 | Deferral without delay unchanged | `{"ack":false}`: more than one mediation call before any nack | apply R1 to `delaySeconds == 0` |
| T4 | Ordered head deferral returns the group with the head's delay | ordered group m1,m2,m3; m1 answers ack:false delay 600: 1 call total; m1 nacked 600 s; m2,m3 nacked; no m2/m3 delivery | yield `RetryHead`; or nack head with `REJECTED_NACK_DELAY` |
| T5 | Ordered ReturnGroup for an unavailable target uses the backoff delay | head outcome e.g. 503 with its delay floor: head nack delay == `backoffFor` value, not 10 s | revert head nack to `REJECTED_NACK_DELAY` |
| T6 | SQS nack changes visibility | `FakeSqsClient` records `ChangeMessageVisibility(receipt, 600)` | make `nack` a no-op again |
| T7 | SQS clamp | delay 50,000 s → 43200 minus elapsed since poll (use the injected `Clock`) | drop the clamp |
| T8 | SQS nack never throws | fake throws on `ChangeMessageVisibility`: `nack` returns normally, counter incremented | remove the catch |
| T9 | Postgres delayed head blocks its group | group g: publish m1 then m2; claim m1; nack m1 600 s; poll → **m2 not returned**; a row in another group and an ungrouped row **are** returned | remove the new clause |
| T10 | Postgres claimed head still does not block | group g: m1,m2; claim m1 (not nacked); next poll → m2 **is** returned (today's behaviour) | block on any earlier row regardless of `receipt_handle` |
| T11 | Postgres delayed head comes back first | after T9, make m1 visible (move `visible_at` into the past): next poll returns **m1**, not m2 | order/eligibility regression |

Existing tests that pin the old behaviour (in-memory deferral with a delay,
ordered return at 10 s, SQS nack no-op) are to be **updated to the new
rulings**, and each one listed in the report with the reason.

---

## Go port (owner instruction 2026-09-18)

The Go router takes R1–R5 with the same observable behaviour; only the shapes
differ. Java is the reference implementation — read `git show 5420b516` and
`git show 7759059a` in `flowcatalyst-javalin` before writing Go.

| Ruling | Go home |
|---|---|
| R1 unordered | `internal/router/pool.go`'s dispatch path — the retryable-failure branch that today only nacks when the retry budget is spent (`nackMsg(..., nackDelay(d.RetryAfter), "released to broker")`). A `MediationDeferred` outcome whose `delaySeconds > 0` nacks on its **first** occurrence with exactly that delay, reason `deferred`, and does not enter the in-pipeline retry loop. `delaySeconds == 0` is unchanged. |
| R1 ordered | the ordered drainer's head-failure decision: a delay-bearing `MediationDeferred` releases the group immediately instead of re-fronting the head. |
| R2 | when the ordered drainer releases a group, the **head** carries the deferral's exact delay, or the same backoff the unordered path would use for that outcome — never the fixed 10 s. Siblings keep 10 s. |
| R3 | `internal/queue/sqs/sqs.go` `Nack`: `ChangeMessageVisibility(receipt, seconds)`, seconds floored at 0 and clamped to `43200 − secondsSinceReceived`. Go's SQS queue does not record a per-receipt poll time today (only `pendingDelete` by broker id) — add one, or clamp to the flat 43200 and say which you chose and why. Best-effort: log at WARN, never return an error that fails a hand-back; keep the `nacked` counter. Rewrite the comment block at `sqs.go:250-257`. |
| R4 | `internal/queue/postgres/postgres.go` claim SQL: a row is not claimable while an **earlier** row of its group (`COALESCE(message_group_id, id)`, by `(created_at, id)`) is `receipt_handle IS NULL AND visible_at > now`. A *claimed* earlier row still does not block. |
| R5 | `internal/queue/queue.go` `Consumer` gains `HonoursDelayedReturn() bool` (no default — every backend answers): SQS and Postgres `true`, NATS `false`. The pool resolves it through the same consumer lookup `nackMsg` uses; an unregistered queue answers `false`. R1's condition on both paths is `delaySeconds > 0 && honoursDelayedReturn`. |

Tests: port T1–T14 from the Java suite (`PoolTest`, `OrderedGroupsTest`,
`SqsQueueTest`, `PostgresQueueTest`, `QueueBrokerTest` in the Java repo) into
the Go router/queue test packages, keeping the same assertions and the same
mutants. Go's own guardrail test that "retryable outcomes never nack" will
need the same treatment Java's did: re-point it at a `RateLimited` outcome,
which R1 does not touch, and say so.
