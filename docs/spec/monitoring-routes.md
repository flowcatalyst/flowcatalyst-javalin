# Four monitoring routes — plan

The last gap that makes the Java router less operable than the Go one. All
read-only over data the router already holds; none is blocked.

Started 2026-08-26. `RouterApi`'s class doc used to list these as blocked on
dependencies that were built on 2026-08-25 — that note was stale and has been
corrected. Read it there for what each route is; this is the how.

## Why these are not "just wiring"

Everything they expose was written on 2026-08-25 and has never run in anger:
`BrokerStatsCache`, `AlbTraffic`/`Elbv2TargetGroup`, and `Pool.mediating()`.
An endpoint that reports a number is the first thing that will be trusted, so
each assertion should pin the number's *meaning*, not just its presence.

The specific trap, already written into `BrokerStatsCache`: a queue whose
metrics could not be fetched is **skipped, not zeroed**. "We could not ask" and
"there is nothing there" must not render the same, or a total outage looks like
an idle queue.

## The routes

Wire shapes are Go's (`internal/router/api/dto.go`), and are a contract —
field names and casing included. Note `/monitoring/queues` is snake_case while
its neighbours are camelCase; that is Go's shape and must be reproduced, not
tidied.

### 1. `GET /monitoring/traffic-status`

Smallest; do it first to establish the `State` plumbing.

```
{ enabled, mode, targetGroupArn?, registered, lastChangedAt?, lastError? }
```

`Traffic.status()` already returns `enabled/registered/lastChange/lastError`.
`mode` is `"disabled"` when no traffic management is configured. **`lastError`
is the field that matters**: `Traffic.Status` deliberately keeps it separate
from `registered`, because a failed deregister leaves the router *believing* it
is out while the balancer still sends traffic — the two disagree and an
operator needs both.

Needs: `Traffic` on `RouterApi.State`.

### 2. `POST /monitoring/broker-stats/refresh`

```
{ refreshed, ageSeconds }
```

`BrokerStatsCache` already has `refresh(...)` and `ageSeconds()`. The point of
the endpoint is an operator forcing a sample rather than waiting for the
housekeeping tick.

Needs: `BrokerStatsCache` on `State`, plus the same queue-metric supplier map
`Router.start` builds for the housekeeping loop — extract it rather than
writing it twice.

### 3. `GET /monitoring/queues`

```
[ { queue_identifier, pending_messages, in_flight_messages } ]   // snake_case
```

Straight from the cache's latest sample.

### 4. `GET /monitoring/queue-stats`

```
{ "<queue>": { name, totalMessages, totalConsumed, totalFailed, totalDeferred,
               successRate, currentSize, throughput, pendingMessages,
               messagesNotVisible } }
```

A map keyed by queue, not a list. Derivations Go uses:

- `currentSize = pendingMessages + inFlightMessages`
- `successRate = totalConsumed / (totalConsumed + totalFailed)`, and **1.0 when
  nothing has been processed** — not 0.0, or a fresh queue reads as total
  failure on every dashboard
- `throughput` is hard-coded 0.0 in Go
- `messagesNotVisible = inFlightMessages`

**Two things the cache does not keep yet**, and the only real work here:
`totalDeferred`, and the 30-minute windowed history behind `?timeWindow=`.
Decide explicitly: extend `BrokerStatsCache`, or ship without the window and
say so in the class doc. Do not emit a zero for a window that is not kept.

Also accepts `?refresh=true` to sample before rendering.

### 5. `MEDIATING` on `GET /monitoring/in-flight-messages/detail`

Deliberately last. Needs a join between `Pool.mediating()` and the tracker
entry, and the status vocabulary is where the value is: `MEDIATING` (inside a
worker), `RETRY_BACKOFF` (attempts > 0, not in a worker), `TRACKED_IDLE`
(neither — buffered, waiting for a slot, or a phantom whose broker has stopped
redelivering). That last case is the one an operator is hunting; the signature
is `lastSeenElapsedMs` growing without bound.

## Order

1 → 2 → 3 → 4 → 5. Each lands green; 1 and 2 unblock the rest.

## Definition of done

- Wire shapes byte-compatible with Go, snake/camel included.
- Every route returns an empty payload (`[]`, `{}`) rather than an error when
  its dependency is absent — spec §9.1.
- A test per route that reads the rendered JSON, not the collaborator.
- One mutation check per derived field — `successRate`'s empty case especially,
  since 0.0 and 1.0 are both plausible-looking and only one is right.
- `NoOrphansTest` stays green: anything added must be reachable.

---

## Outcome (2026-08-26)

All five landed. `RouterApi.State` gained `traffic` and `brokerStats`, both
nullable and degrading the §9.1 way; `Router` now holds the cache the
housekeeping loop samples into and hands the API **that** one, so the loop and
the endpoint cannot disagree on the same dashboard.

### Two things this plan got wrong

- **The 30-minute window was already kept.** `BrokerStatsCache.HISTORY` is 30
  minutes and `windowed(Duration)` was written and tested on 2026-08-25;
  `queue-stats` needed no cache change for it, only `parseTimeWindow` — which
  `pool-stats` already had. The plan (and the `RouterApi` class doc it was
  written from) carried the claim forward without re-reading the class.
- **`totalDeferred` is not missing data; it is a permanently-zero field on
  both sides.** Go carries a `Defer` verb on all three queue backends with the
  counter behind this field, and **no production caller** — `grep 'Defer('`
  over the Go tree finds only the interface and the three implementations. So
  Go reports 0 as well. Java never grew the verb: a deferral is a `nack` with
  a delay and is counted as a nack on both sides, so the column loses nothing
  the nack count does not already hold. Emitting 0 is exact parity, not a
  stand-in for a number we failed to keep, and the rule "do not emit a zero
  for a window that is not kept" does not bite. Recorded as a named constant
  (`DEFERRALS_ARE_NACKS`) so the next reader finds the reason.

### Decisions taken while building

| Decision | Why |
|---|---|
| `Traffic.Status` gains `mode` + `targetGroupArn`; `TargetGroup` gains an **abstract** `arn()` | The API cannot learn the ARN without reaching into the implementation. Abstract rather than defaulted so a new `TargetGroup` must say what it registers with — a `""` default would let one report nothing on the endpoint whose job is naming the group |
| `BrokerStatsCache.refresh` samples **outside** the lock | It has two callers now. Holding the monitor across the broker I/O would make one unreachable broker block every read of the cache — the endpoint built to diagnose an outage would be the thing that hangs the dashboard during one |
| A sample that finishes behind one already stored is dropped | Two refreshes can now overlap (tick + operator). Letting the slower one land last would rewind `latest` and then report it as fresh |
| `RouterManager.queueMetricSources()` | The endpoint must sample the same queues the loop does. Resolved lazily per queue, so a reconfigure that replaces a consumer does not leave either caller sampling the closed one |
| `/monitoring/queues` sorted by queue id | The cache returns an unordered map; rows that move between two polls of unchanged data are unreadable, and an unsorted implementation passes a single-queue test |
| `in-flight-messages/detail` emits `attempts` and the elapsed fields **even at zero** — a deliberate deviation from Go's `omitempty` | `attempts: 0` is the fact separating a message pinned on its first delivery from one legitimately retrying, and that distinction is the endpoint's whole point. Absence is still used where it means something: a message not in the pipeline carries `messageId` and `inPipeline` only |
| `MEDIATING` outranks `RETRY_BACKOFF` | A retrying message *is* in a worker while the retry runs. Reporting `RETRY_BACKOFF` would tell an operator it is waiting when it is wedged against the target |
| `lastChangedAt` is an `Instant`, not Go's hand-formatted millisecond string | Every other timestamp on this surface goes through the platform's one RFC 3339 layout; Go rolls its own in exactly this handler. Still RFC 3339, still parses |

### A defect found on the way

`ForceAckResponse.wasMediating` was hard-coded `false` — it was written before
`Pool.mediating()` existed and the class doc said so. It told every operator
force-acking a genuinely wedged message that no attempt was running, which is
the one thing the flag exists to deny. Now read from the live set, and read
**before** the ack so the worker finishing mid-request cannot flip it.

### Mutation checks

Twelve mutants, all killed: `successRate`'s empty case (1.0 → 0.0),
`currentSize` dropping `inFlight`, `messagesNotVisible` reading `pending`, the
`/queues` sort removed, snake_case → camelCase, `MEDIATING` losing to
`RETRY_BACKOFF`, `wasMediating` back to `false`, `lastSeenElapsedMs` reading
`startedAt`, `lastError` dropped from traffic-status, `refresh=true` ignored,
`time_window` ignored, and the `ageSeconds` clamp removed.

The clamp survived its first mutation: after a successful refresh the age is
never negative, so the guard was unreachable and the test was decorative. It
is now pinned by a clock that steps **backwards** (an NTP correction), which is
the only way it fires — and the way it will fire in production.
