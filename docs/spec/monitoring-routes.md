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
