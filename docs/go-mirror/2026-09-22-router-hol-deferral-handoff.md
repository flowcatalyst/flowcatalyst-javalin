# Java hand-off: router head-of-line blocking — backpressure by deferral

Owner rulings (2026-09-22). Go implements them in the commit that adds this
file; Java must mirror the *behaviour* — there is no shared schema or wire
change beyond one new dashboard field. Reference: `docs/router-architecture.md`
§3a in the Go repo, and the Go files named below.

## The incident

One **dedicated** pool at concurrency 1 was handed 10,000 messages that take
~5 s each (~14 h to drain). Its client's SQS FIFO queue also feeds that
client's other pools. The consumer stopped polling the queue while the pool
was full, so every other pool on that queue starved for 14 h.

Java's `ConsumerLoop.hasRoom()` already pauses only when *every* pool the last
batch fed is full (`RouterManager.poolsHaveCapacity` is `anyMatch`) — so Java
had half of this before Go did. What Java lacks is everything below the first
row.

## Rulings

| # | Ruling | Consequence |
|---|---|---|
| 1 | A full pool is not a reason to stop polling the queue. | Consumer keeps polling while any last-batch pool has room **or** while its deferral budget lasts. |
| 2 | A message for a full pool is **deferred**, not nacked — it has not failed. | New `defer(message, delay)` on the acknowledger; counted separately from nacks. |
| 3 | The delay is a **reservation** derived from the pool's measured rate, not a flat 10 s. | Per-pool admission schedule (below). No "come back early" hedge. |
| 4 | (a) The router owns give-up; NATS `max-deliver` is **unlimited** by default. | And `max-ack-pending` unlimited too — see the NATS section for why. |
| 5 | A reservation is not recalled when concurrency changes. | Accepted; bounded by a 1 h horizon. |
| 6 | Buffer doubled: `max(concurrency × 40, 100)`. | Was `× 20, 50`. |
| 7 | **Intra-pool** head-of-line (one slow group hogging a *shared* pool) is a user problem — move it to its own pool. | No per-group buffer cap. Do not build one. |

## 1. Poll gate (`ConsumerLoop.hasRoom`, `RouterManager.poolsHaveCapacity`)

Go: `Manager.hasCapacityFor` / `awaitCapacity` in `internal/router/manager.go`.

```
hasRoom = anyLastBatchPoolHasRoom() || deferralsOutstanding(now) < deferralBudget
```

- **Why the budget clause exists.** "Last batch's pools" is a one-batch
  memory. A queue whose last ten messages all named the full pool would still
  park, with the other pool's traffic queued behind them; the only way to find
  that traffic is to poll and defer what is in the way. The budget bounds it.
- **Why it is bounded at all.** A deferred message is still in flight from
  the broker's side — invisible on SQS, unacknowledged on NATS. **SQS FIFO
  stops delivering anything from a queue at 20,000 in flight.** Default
  budget **5,000** per queue (`FC_ROUTER_DEFERRAL_BUDGET`).
- **Per-consumer deferral ledger** (Go `deferral_ledger.go`): a min-heap of
  return times. `outstanding(now)` prunes entries `<= now` and returns the
  count; `earliest()` is the next due time. The pool reports each deferral's
  `(queueIdentifier, returnAt)` to the manager, which books it on the ledger
  of the consumer that owns that queue identifier (`consumersByID`; a
  deregistered queue has no ledger and needs none).
- **Wake-up.** The parked loop must wake on the capacity gate *or* on the
  ledger's earliest due time — nothing else signals "budget is back". Go
  arms a timer to `earliest()` inside the same wait as the gate.
- The pause warning now reads "destination pools at capacity and N
  deferrals outstanding; pausing <queue>".

## 2. Defer on the acknowledger

Java's `Acknowledger`/`Broker` have `nack` only. Add:

```java
/// Makes a delivery visible again after `delay` WITHOUT counting it as a
/// failure — backpressure, not rejection. Same ownership contract as nack:
/// the caller has already dropped the in-flight tracker entry.
void defer(QueuedMessage message, Duration delay);
```

| Backend | Implementation | Counter |
|---|---|---|
| SQS | Same `ChangeMessageVisibility` as `nack`, same 12 h clamp measured from the original receive. (Go's SQS `Defer` was a no-op until now; Java's SQS has no defer at all.) | `totalDeferred`, not `totalNacked` |
| Postgres | Same `visible_at` update as `nack`. | `totalDeferred` |
| NATS | `nakWithDelay` — same wire effect as nack-with-delay. | `totalDeferred` |

`Pool.submit`'s at-capacity branch (`Pool.java:321`, `broker.nack(message,
REJECTED_NACK_DELAY)`) becomes `broker.defer(message, admissionDelay(...))`.
The other `REJECTED_NACK_DELAY` sites (pool closed/stopped, shutdown before
dispatch) stay nacks.

## 3. Admission schedule (Go `internal/router/pool_admission.go`)

Per pool:

- `rate` = completions in the last **5 min** ÷ the span **since the oldest of
  them** (floored at 1 s) — not ÷ the window, or a pool that woke up 30 s ago
  reports a tenth of its rate. Go adds `PoolMetricsCollector.CompletionRate`;
  Java's `PoolMetrics` records `recordSuccess/Failure/Transient` with a
  duration but no timestamp — it needs a timestamped ring (or a completions
  counter sampled per second) to answer this.
- Cursor `nextReturn` (a `long` nanos under the pool's own lock, **not**
  `Pool`'s buffer lock — this runs on the routing path).

```
wait = queued / rate            (fallback 30 s when no completion in window)
slot = 1 / rate                 (fallback 1 s)
if (!broker.honoursDelayedReturn()) slot = max(slot, 1 s)   // NATS: keep a deferred group in order
earliest = max(now + wait, nextReturn)
reserved = earliest + slot
nextReturn = reserved
delay = reserved - now
if (delay > horizon) delay = horizon - random(0 .. 0.25 × horizon)   // spread the tail backwards
delay = max(delay, 5 s)
```

- **No ÷2 hedge**, deliberately. An early return finds the pool still full
  and re-books at the *back* of the schedule, wasting the round-trip and
  pushing the cursor one slot further from reality every time.
- **No cursor reset.** A cursor in the past is simply overtaken by
  `max(...)`. Resetting on "crossed back under capacity" would fire on every
  completion of a pool that is being kept full.
- **Horizon** 1 h default (`FC_ROUTER_DEFERRAL_MAX_DELAY_SECONDS`). Beyond it
  the reservation is clamped with *backward* jitter over the last quarter, so
  a large backlog's tail trickles in rather than arriving as one wave. The
  cursor still advances past the horizon; that is fine.
- Constants: min delay 5 s, fallback wait 30 s, ordered spacing 1 s, rate
  window 5 min, jitter fraction 0.25.

## 4. NATS defaults (`NatsQueueUri.DEFAULT_MAX_DELIVER`, `DEFAULT_MAX_ACK_PENDING`)

Both **-1 (unlimited)**; both still settable per URI.

- `max-deliver`: every deferral is a redelivery and spends an attempt. A 10k
  backlog at 0.2 msg/s with a 1 h horizon bounces its tail ~14 times; at the
  old cap of 10 the message silently stops being redelivered — permanent loss
  of work never attempted. The router already owns give-up (terminal ACKs,
  released groups); the broker cap only ever converted a slow backlog into
  loss. Owner: "we can always resend from the front end" if something truly
  wedges.
- `max-ack-pending`: a NAK-delayed message stays **outstanding** on the server
  until redelivered and acked, so every deferred message holds a slot for its
  whole delay. At 1000, a 1000-message backlog would suspend delivery of
  *everything* on the stream — the head-of-line block back through a side
  door.
- `honoursDelayedReturn()` on NATS stays **false** (ordering reason
  unchanged). Update its Javadoc: the `max-deliver` half of the rationale is
  gone. The backpressure deferral is the one hand-back that reaches NATS
  regardless of that answer — there is nowhere in memory to keep a message
  for a full pool — and it reads the `false` to space reservations ≥ 1 s.

## 5. Buffer (`Pool.QUEUE_CAPACITY_MULTIPLIER`, `MIN_QUEUE_CAPACITY`)

`20 → 40`, `50 → 100`. Accepted cost: a buffered message waits twice as long,
so the tracker dedups twice as many visibility-lapse redeliveries for it.

## 6. Dashboard / wire

- `PoolStats` gains `totalDeferred` (lifetime, camelCase on the dashboard
  DTO, `total_deferred` on the snake_case `/monitoring/pools` wire DTO).
- Pools table: new **Deferred** column after Rate Limited, in the row, the
  totals row and the sort keys. Tooltip: "Messages handed back to the broker
  because this pool's buffer was full; they return on the pool's own
  schedule".

## 7. Config

| Env | Default | Meaning |
|---|---|---|
| `FC_ROUTER_DEFERRAL_MAX_DELAY_SECONDS` | 3600 | reservation horizon |
| `FC_ROUTER_DEFERRAL_BUDGET` | 5000 | outstanding deferrals per queue before the consumer stops polling into all-full pools |

## Tests worth mirroring (Go `pool_admission_test.go`; all mutation-checked)

- **Consecutive slots**: prime a pool at 0.2/s with 100 buffered; first
  reservation ≈ 505 s; each next ≈ +5 s.
- **Floor** at 5 s; **fallback** 30 s + 1 s spacing with no rate; **horizon
  clamp** never exceeds the horizon and never jitters below 75 % of it.
- **NATS spacing**: at 100/s an ordered broker's reservations all sit on the
  5 s floor; an unordered one's spread ≥ 1 s apart.
- **Ledger** prune/earliest; **wake on due** (budget 1, one deferral due in
  150 ms, full pool → `awaitCapacity` returns within a second).
- **End to end** — the trap: it must use **two batches**. First a batch of
  only the slow pool's messages (so the consumer's remembered set is `{SLOW}`,
  full), *then* the fast pool's. A single mixed batch passes against the old
  gate because the first poll admits everything before the set is learned.
  Assert: fast messages acked, slow ones deferred (not nacked) with delay
  ≥ 5 s in arrival order, no tracker entries left, consumer still has room.
- The tests that used to pin "parks on a full pool" now spend the budget
  first (budget 1 + one ledger entry due in an hour); that is the only state
  left that parks.

## Still open, owner's side

Every deferral is a redelivery: SQS bumps `ApproximateReceiveCount`, so a
redrive policy with a low `maxReceiveCount` would DLQ messages that were
never attempted. Already true of the visibility-lapse design; check the FC
queues' redrive policy.
