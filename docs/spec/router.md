# Message router — behavioural spec

Semantic extraction (CONVENTIONS.md §8, step 1) of the Go message router in
`flowcatalyst-go`. This document describes **behaviour**, not code: every
state, transition, timing constant, ordering guarantee, wire shape and edge
case the Go embodies, so the Java can be written from it and a conformance
suite can be seeded from it. Nothing here proposes the Java design.

Conventions used throughout:

- File citations are relative to `flowcatalyst-go/internal/` unless they
  start with `pkg/`, `cmd/` or `docs/` (e.g. `router/pool.go:253`).
- **[C]** marks a *contract* — behaviour visible to a queue backend, a
  webhook receiver, the config service, the monitoring API / dashboard,
  Prometheus, the notifier webhook, Redis or the ALB. **[I]** marks an
  *internal mechanic* the Java may restructure freely as long as the
  contracts and invariants hold.
- **load-bearing or accident?** flags a constant or branch the owner must
  rule on. Until ruled on, the behaviour is kept.
- "Ordered" means `DispatchMode.RequiresOrdering()` is true
  (`NEXT_ON_ERROR` or `BLOCK_ON_ERROR`); "IMMEDIATE" means it is false.

Source set read for this spec: `router/**` (incl. `api/**`), `common/**`,
`queue/**` (`queue.go`, `sqs/`, `postgres/`, `nats/`), `standby/election.go`,
`server/run.go`, `server/subsystems.go`, `server/envcfg.go`, `server/http.go`,
`pkg/fcsdk/webhook/**`, `docs/architecture.md`, `docs/wire-contract.md`,
`docs/environment-variables.md`, and every `*_test.go` in those packages.

---

## 0. Drift re-extraction — CLEARED 2026-08-24

This spec was extracted at Go `1e9d465`. Three commits landed inside the
subsystem afterwards; **all three have now been re-extracted into the
sections below and this spec is current against Go `eff2a29`.**

> ### ⚠ RE-EXTRACTION OWED (2026-08-25) — the spec is NOT current
>
> Six router commits landed in Go after `eff2a29`. The spec was never
> re-extracted, so §2/§3/§6 describe a Go that no longer exists, and — worse —
> several rulings recorded here as **deliberate Java-vs-Go deviations have
> since been adopted by Go**, which makes the deviation notes actively wrong.
>
> | Go commit | Touches | Effect on this spec |
> |---|---|---|
> | `7bbef5d` | `pool.go` | Splits unreachable-target from application-rejection on delivery failure — the ordered-head failure-kind ruling. Recorded here as a Java deviation; Go now does it. |
> | `8804827` | `pool.go` | **Q1.** Makes `BLOCK_ON_ERROR` and `NEXT_ON_ERROR` actually differ. Recorded here as *the largest deliberate deviation* ("Go blocks the group for both modes"); that is no longer true. |
> | `88b4549` | `manager.go`, `pool.go` | Passes the broker message id to Ack so SQS cannot forget a delete. |
> | `31f22de` | `postgres.go` | **Q17.** Quarantines malformed rows — the ruling Java implemented. Go now does it too. |
> | `18f1460` | `manager.go` | **Q16.** Scheduler propagates the client-namespaced pool code. |
> | `4f2d52c` | `mediator.go` | **501** as a terminal ACK. Java matched this on 2026-08-25 via the conformance corpus. |
>
> **RULED 2026-08-25 (owner): keep Java's behaviour.** Under
> `BLOCK_ON_ERROR` with the head terminally failed, the untried siblings are
> **ACKed off the broker**, not handed back.
>
> The reasoning, which turns on what the mode is *for*: `BLOCK_ON_ERROR`
> exists to guarantee nothing behind a failure is applied before it. Go's
> nack does not deliver that. Nacked messages return on the **broker's own
> timer**, not "once the failure is resolved" as its comment claims — and by
> then the head has been ACKed away, so the first sibling becomes the new
> head and is delivered anyway. "Add item" lands on an order that was never
> created. Java's ACK honours the contract; Go's silently breaks it.
>
> The messages are not lost: they remain as dispatch-job rows in the
> platform's store, which is the system of record. And a terminal 500 on the
> platform's own dispatch endpoint is close to hypothetical in practice.
>
> **Residual gap, worth knowing rather than acting on now:** nothing marks
> those sibling jobs for review. Their rows stay `QUEUED`/`PROCESSING` rather
> than `FAILED`, so they do not appear in a failed-jobs list, and
> `RequeueDispatchJobs` re-sends only ids an operator names. The trace does
> exist — every one of those ACKs emits a `MessageSettled` JFR event with
> reason `rejected-group-blocked`, so the affected ids are recoverable from a
> recording. Closing the gap properly means the platform learning that the
> router dropped a group, which is platform work, not router work.
 The owner
confirms no router work is in flight, so this target is stable.

| Go commit | What changed | Where it now lives |
|---|---|---|
| `f1fc427` only `BLOCK_ON_ERROR` stops for a failed sibling | The poller's group-level skip is gone; the per-mode filter holds back `BLOCK_ON_ERROR` alone. | §2.6, rewritten as two columns of truth — see the warning below |
| `5bb46df` hold back blocked-group jobs at delivery time | `/api/dispatch/process` mirrors the poller: ACK the queue message and revert the job to `PENDING` with no attempt and no retry budget; a DB error on either the check or the revert NACKs instead. | new §6.6b |
| `eff2a29` `flushGroup` | New mediation-response field; `GroupFlushRegistry` as a per-group circuit breaker; the check runs **before** the rate limiter. Wire-contract change. | §2.4, new §2.11, §3.5 step 4, §6.5, new §6.6a |

**Read §2.6 before writing any ordered-group code.** `f1fc427` implemented
only the *platform* half of the Q1 ruling. The *router* half is untouched:
`pool.go:272` still branches on `RequiresOrdering()` alone, so
`NEXT_ON_ERROR` and `BLOCK_ON_ERROR` remain identical inside the pool. A
Java router that mirrors the Go here will pass every Go-derived test and
still be wrong.

Re-extraction surfaced five new questions — **Q51–Q55** in §13, all on
`flushGroup`. Q54 is the one that matters: `flushGroup` causes the router to
**ACK messages it never delivered**, and nothing enforces the safety
condition that makes that sound.

Nothing already ported to Java is invalidated by these commits — the
platform CRUD aggregates are untouched.

## 1. Purpose & boundaries

### 1.1 What the router is

The router is a **pull → push relay**: it polls one or more message queues,
decides (per message) whether it may deliver now, and delivers each message
as a signed HTTP POST to the URL the message names, honouring per-pool
concurrency and rate limits, per-endpoint circuit breakers, and FIFO
ordering inside a message group when the message asks for it. It never
inspects or forwards the message payload beyond the fields it needs: the
body it POSTs is `{"messageId":"<id>"}` and nothing else
(`router/mediator.go:192-194,286`).

It is one subsystem of the single `fc-server` binary (`cmd/fc-server`,
enabled with `FC_ROUTER_ENABLED` / legacy `MESSAGE_ROUTER_ENABLED`,
`server/envcfg.go:149`). There is no separate `fc-router` binary in the tree
any more (only `cmd/fc-server`, `cmd/fcdev`, `cmd/decrypt-check`); comments
that mention `cmd/fc-router` are historical.

### 1.2 What the router is not

- It is **not** the dispatch-job scheduler: the scheduler claims jobs from
  `msg_dispatch_jobs` and publishes `Message`s to the queue the router
  consumes (`server/subsystems.go:44-114`, `platform/scheduler/dispatcher.go:80-96`).
  The router does not touch that table.
- It is **not** the webhook *subscriber* endpoint: the message's
  `mediationTarget` in the platform flow is the platform's own *processing
  endpoint*, which loads the job and delivers to the subscriber's URL
  (`platform/scheduler/dispatcher.go:75-80`). The router does not know the
  subscriber URL.
- It is **not** a queue: it owns no durable state. All durability is the
  broker's. Everything in the router (in-flight tracker, group buffers,
  warnings, metrics, breakers) is process memory and is lost on restart.
- It does **not** verify inbound webhooks; the other half of the signing
  contract lives in `pkg/fcsdk/webhook` and is only relevant here because
  it pins the signature format (§6.4).

### 1.3 Inputs

| Input | Source | Citation |
|---|---|---|
| Queue messages | Consumers built per `QueueConfig` via the scheme registry (`sqs`, `postgres`, `nats`; `http(s)` SQS URLs routed to `sqs`) | `queue/queue.go:113-170`, `server/subsystems.go:38-41` |
| Router config (pools + queues) | `FLOWCATALYST_CONFIG_URL` (comma-separated list, fetched in parallel, merged first-wins), polled on an interval; or, when unset and `FC_DEFAULT_BROKER=postgres`, a synthesised single-pool Postgres config | `router/config_sync.go`, `server/run.go:276-370` |
| Runtime knobs | env (`server/envcfg.go:195-209`): `FLOWCATALYST_DEV_MODE`, `FC_NOTIFY_WEBHOOK_URL`, `FC_DRAIN_TIMEOUT_SECONDS` (60), `FC_STANDBY_*`, `FC_ALB_*`, `FC_ROUTER_HTTP_PREFIX` (`/router`), `FC_ROUTER_AUTH_USER/PASS` (+ `AUTH_BASIC_USERNAME/PASSWORD`, `AUTH_MODE=NONE`) | `server/run.go:228-243` |
| Operator mutations | monitoring API (pool hot-update, breaker reset, force-ack, config reload, warning ack/clear, publish/seed) | §9 |
| Leadership | Redis lock (optional) | `standby/election.go` |

### 1.4 Outputs

| Output | Citation |
|---|---|
| HTTP POST deliveries (signed) to `mediationTarget` | `router/mediator.go:281-392` |
| Ack / Nack on the source queue (never Defer, never ExtendVisibility) | `router/pool.go:164-204`, grep: no callers of `Defer(`/`ExtendVisibility(` in `router/` |
| Monitoring JSON API + embedded HTML dashboard under `<prefix>` (default `/router`) on the API port | `server/run.go:203-226`, `router/api/**` |
| Prometheus text exposition at `<prefix>/metrics` (the `FC_METRICS_PORT` listener serves a placeholder only) | `router/api/prometheus.go`, `server/http.go:39-68` |
| Warnings (in-memory store, `/warnings*`) and an optional batched webhook notifier (`FC_NOTIFY_WEBHOOK_URL`) | `router/warning.go`, `router/notification.go` |
| ALB target registration / deregistration (optional) | `router/traffic.go` |
| Structured logs (slog) — not specified here except where they are the *only* evidence of an event | — |

### 1.5 Processes it shares

- **Leader election** (`standby/election.go`) is shared with the scheduler /
  scheduled-job / outbox gates, but each of those elects on its *own*
  suffixed key (`<lockKey>:<subsystem>`, `server/subsystems.go:150-181`);
  the router itself elects on `StandbyLockKey` unsuffixed
  (`router/server.go:134-144`). So "router leader" and "scheduler leader"
  may be different instances.
- The **HTTP listener** and chi mux are shared with the platform API; the
  router mounts under a prefix with its own BasicAuth middleware
  (`server/run.go:207-226`).
- The **stream health** provider is bridged in so the router's
  `/monitoring/stream-health*` endpoints reflect the co-tenanted stream
  processor (`server/run.go:245-274`). That is a pass-through; its
  semantics belong to the stream spec, not this one.

---

## 2. Message model

### 2.1 `Message` [C] — `common/message.go:47-59`

The JSON shape that is published to and consumed from every queue backend
(SQS body, Postgres `payload` column, NATS message data). Byte-compatible
camelCase; unset optionals are **absent** (not `null`)
(`common/message_test.go:13-71`).

| Go field | JSON | Type | Optional? | Meaning / notes |
|---|---|---|---|---|
| `ID` | `id` | string | required (always emitted) | Application message id (a TSID / job id). Dedup key in the in-flight tracker. Becomes the POST body `{"messageId": …}`. |
| `PoolCode` | `poolCode` | string | omitempty | Pool to process in. Empty or unknown → `DEFAULT-POOL` (`router/manager.go:405-426`). The platform scheduler does **not** set it (`platform/scheduler/dispatcher.go:81-86`). |
| `AuthToken` | `authToken` | string pointer | omitempty | When present (even `""`), sent as `Authorization: Bearer <token>` (`router/mediator.go:303-305`). |
| `SigningSecret` | `signingSecret` | string pointer | omitempty | When present, the request is HMAC-signed (§6.3). |
| `MediationType` | `mediationType` | enum string | always emitted | Only `"HTTP"` exists (`common/message.go:15-17`). Any other value → `ErrorConfig` → ACK-drop (`router/mediator.go:282-284`). |
| `MediationTarget` | `mediationTarget` | string | always emitted | Absolute URL POSTed to. Also the circuit-breaker key (full URL string) and, via scheme/host/port, the host-pool key. |
| `MessageGroupID` | `messageGroupId` | string pointer | omitempty | FIFO group. Used for ordering only when `dispatchMode` requires ordering; carried to SQS as `MessageGroupId` on publish. |
| `HighPriority` | `highPriority` | bool | omitempty | Carried, never acted on anywhere in the router (`router/pool.go:80-86`, `router/pool_test.go:11-32`). |
| `DispatchMode` | `dispatchMode` | enum string | omitempty | `IMMEDIATE`, `NEXT_ON_ERROR`, `BLOCK_ON_ERROR`. **Absent → `NEXT_ON_ERROR`** (owner ruling 2026-08-25; Go defaults to `IMMEDIATE`, a **deliberate deviation**). **Unknown → `NEXT_ON_ERROR`, logged**, not silently folded into the default: an unrecognised mode is a producer bug, and Go's `default` arm is how the wrong one hid. See §2.6. |

Go's `encoding/json` escapes `<`, `>`, `&` as `\u003c`, `\u003e`, `\u0026` when marshalling
strings; the only router-constructed JSON is the POST body, so a message id
containing those characters would be escaped in the signed bytes
(verified by running `json.Marshal`; TSIDs never contain them).

### 2.2 `QueuedMessage` [I] — `common/message.go:61-76`

What a backend's `Poll` returns and what flows through route → pool.

| Field | Type | Meaning |
|---|---|---|
| `Message` | `Message` | the decoded payload |
| `ReceiptHandle` | string | backend-specific ack/nack handle for **this delivery** (SQS receipt handle; Postgres `<pollUUID>:<id>`; NATS `<stream>:<streamSeq>`) |
| `BrokerMessageID` | string | backend message identity; `""` if the backend has none. SQS `MessageId`; Postgres = the app `id`; NATS `<streamSeq>:<consumerSeq>` |
| `QueueIdentifier` | string | the consumer's `Identifier()` — used to resolve the source consumer at ack time (`router/pool.go:144-152`) |
| `BatchID` | string | router-assigned per poll batch (decimal counter), informational only (`router/manager.go:323-327`) |
| `Attempts` | uint | in-pipeline attempt count; 0 on first dispatch; incremented by the pool on each retry; never on the wire |

### 2.3 `InFlightMessage` [I, surfaced on the API] — `common/message.go:78-131`

One entry per app message currently owned by this process (claimed at route
time, released on ACK / nack / pool-stop / flush / reap / force-ack).

| Field | Type | Meaning |
|---|---|---|
| `MessageID` | string | app id (tracker key #1) |
| `BrokerMessageID` | string | broker id (tracker key #2; `""` → not indexed by broker id). Key #2 is scoped by `QueueIdentifier` — a broker id (e.g. NATS's `<streamSeq>:<consumerSeq>`) is unique only within the queue that issued it, not across queues (G11). |
| `PoolCode` | string | as on the message (raw, *not* resolved — an empty pool code stays `""`) |
| `QueueIdentifier` | string | source consumer id |
| `StartedAt` | time | when the entry was created (route time) |
| `LastSeenAt` | time | refreshed on every broker redelivery (receipt-handle swap). The reaper ages on this, not on `StartedAt` |
| `MessageGroupID` | string | `""` when none |
| `BatchID` | string | |
| `ReceiptHandle` | string | **freshest** handle (swapped on redelivery); the handle used for the final ACK |
| `Attempts` | uint | >0 once marked retrying; stall detector and reaper skip entries with `Attempts>0` |

`ElapsedSeconds()` = whole seconds since `StartedAt` (`common/message.go:122-124`).

### 2.4 `MediationOutcome` [I] — `common/mediation.go`

| Field | Type | Meaning |
|---|---|---|
| `Result` | enum | see below |
| `DelaySeconds` | int | server/breaker-requested delay; 0 when none |
| `StatusCode` | int | HTTP status when known, else 0 |
| `ErrorMessage` | string | diagnostic text |
| `FlushGroup` | bool | **(eff2a29)** set when a 2xx body carried `{"flushGroup": true}` — the target delivered this message but asks the router to stop delivering the rest of its group. `DelaySeconds` on the same outcome sizes the suppression window. |

Result kinds and the constructors that produce them:

| Result | Constructor | Status | Delay | Produced when |
|---|---|---|---|---|
| `MediationSuccess` | `Success()` | 200 (hard-coded, even for 201/204) | 0, **or `delaySeconds` when `FlushGroup`** | 2xx without `{"ack":false}` |
| `MediationSuccess` + `FlushGroup` | `Success()` then `out.FlushGroup = true` | the real 2xx status (**Q51 ruled**: Go hard-codes 200 and is being fixed; Java carries it) | `delaySeconds` from the body, else 0 | 2xx with `{"flushGroup": true}` and not `{"ack": false}` (`mediator.go:356-365`) |
| `MediationErrorConfig` | `ErrorConfig(status,msg)` | status or 0 | 0 | 4xx except 429; unsupported mediation type; invalid target URL; payload marshal error |
| `MediationErrorProcess` | `ErrorProcess(delay,msg)` | set to status for 5xx; 0 for the "unexpected status" branch | 30 | 5xx; any status <200 |
| `MediationErrorConnection` | `ErrorConnection(msg)` | 0 | **30** (`common/mediation.go:59-62`) | transport error, timeout, request-build error |
| `MediationRateLimited` | `RateLimited(retryAfter)` | 429 | Retry-After or 30 | 429 |
| `MediationCircuitOpen` | `CircuitOpen(delay)` | 0 | breaker reset timeout (5) | breaker open; no HTTP attempted |
| `MediationDeferred` | `Deferred(delay,msg)` | set to the 2xx status | `delaySeconds` from body or 0 | 2xx with `{"ack":false[,"delaySeconds":N]}` |

Resolution of each kind by the pool is in §6.5.

### 2.5 Router configuration [C] — `common/config.go`

`RouterConfig` (`config.go:79-83`): `{"processingPools":[PoolConfig…],"queues":[QueueConfig…]}`.

`PoolConfig` (`config.go:9-14`):

| JSON | Type | Notes |
|---|---|---|
| `code` | string | pool identity |
| `concurrency` | uint32 | 0 → derived: `max(rateLimitPerMinute/60, 1)` for a new pool (`router/pool.go:114-122`); 0 on an existing pool → left unchanged (`router/manager.go:565-567`) |
| `rateLimitPerMinute` | uint32, omitempty | absent/0 → unlimited |

`QueueConfig` (`config.go:16-77`) — canonical keys `queueName`, `queueUri`,
`connections`, `visibilityTimeout`; **legacy aliases** `name`, `uri` accepted on
input; canonical keys always emitted on output
(`common/config_test.go`):

| JSON | Alias | Default when absent | Used by |
|---|---|---|---|
| `queueUri` | `uri` | — (required in practice; scheme selects backend) | all |
| `queueName` | `name` | = `queueUri` | consumer identity; Postgres `queue_name`; SQS `Identifier()` (else last URL path segment, `queue/sqs/sqs.go:76-79`) |
| `connections` | — | `1` | **nothing** — no backend reads it; it only participates in change detection (a change restarts the consumer, `router/manager.go:575-583`) |
| `visibilityTimeout` | — | `120` | SQS `ReceiveMessage.VisibilityTimeout` (0 → 30, `sqs.go:80-83`); Postgres claim window (≤0 → 30s, `postgres.go:122-125`); **ignored by NATS** (ack-wait comes from the URI query, default 120s) |

Precedence when both canonical and alias are present: canonical wins
(`config.go:49-63`).

Other config carriers:

- `LeaderElectionConfig` (`config.go:85-106`): `Enabled`, `RedisURL`,
  `LockKey` (default `"fc:leader"`), `LockTTLSeconds` 30,
  `HeartbeatIntervalSeconds` 10, `InstanceID` = fresh UUID. The env default
  lock key is `fc:server:leader` (`server/envcfg.go:209`), which overrides
  `fc:leader` (`router/server.go:136-138`).
- `StallConfig` exists **twice**: `common.StallConfig` (JSON-tagged, no
  check interval, `config.go:108-126`) and `router.StallConfig` (adds
  `CheckInterval`, `router/inflight.go:222-242`). Only the router one is used.
  **load-bearing or accident?** — the `common` copy looks like a leftover.
- `MediatorConfig`, `HostPoolSizing`, `BreakerConfig`, `WarningServiceConfig`,
  `HealthServiceConfig`, `LifecycleConfig`, `QueueHealthConfig`,
  `MetricsConfig`, `TrafficConfig`, `ServerConfig` — all code defaults, none
  env-tunable except what `ServerConfig` exposes (§5).

### 2.6 `DispatchMode` [C] — `common/message.go:19-45`

Three values, parsed leniently (`ParseDispatchMode`: unknown → `IMMEDIATE`,
`common/message_test.go:73-78`). `RequiresOrdering()` is true for
`NEXT_ON_ERROR` and `BLOCK_ON_ERROR`.

**Q1 is ruled (owner, 2026-08-22) and half-implemented upstream. Read this
table as two columns of truth, because they disagree.**

| Value | Ordering | Platform side (scheduler + delivery) — Go **as of `f1fc427`/`5bb46df`** | Router side — Go **still** | Java must implement |
|---|---|---|---|---|
| `IMMEDIATE` (or absent/unknown) | none | dispatched; never held by a failed sibling | concurrent, one worker per message, bounded by the pool semaphore | same |
| `NEXT_ON_ERROR` | per-group FIFO | **keeps flowing** past a failed sibling (`poller.go:327-340`) | **blocks the group**, identical to `BLOCK_ON_ERROR` (`pool.go:272`) | **the ruling: the group continues past a failed head.** A deliberate deviation from the Go router |
| `BLOCK_ON_ERROR` | strict FIFO | **held back** while a sibling is FAILED/ERROR, at both queue time and delivery time | blocks the group | blocks — and **ACKs the queued siblings** off the broker (the ruling), because the platform re-sends the group on resolution |

So `f1fc427` implemented the *platform* half of the Q1 ruling — the poller's
group-level skip is gone and the per-mode filter holds back `BLOCK_ON_ERROR`
alone — while the *router* half is untouched: `pool.go:272` still branches
only on `RequiresOrdering()`, so the two ordered modes remain
indistinguishable inside the pool.

**Consequence for the port, and it is the important one:** the platform-side
behaviour can be ported straight across, but the router-side ordered-group
handling must be written to the ruling, **not** to the Go. This is the
spec's largest deliberate deviation and the conformance suite must pin both
modes explicitly — a Java router that merely mirrors `pool.go` will pass
every Go-derived test and still be wrong.

**Failure kind decides the disposition — RULED (owner, 2026-08-24).** A
retryable failure is not one thing, and Go treats every 5xx identically
(`ErrorProcess`, retried in-pipeline forever). Java splits it, because the
two cases mean opposite things about the target:

| Failure | Meaning | Ordered-head disposition |
|---|---|---|
| Transport error, timeout, unreachable host, **502 / 503 / 504**, unexpected status | The target is **down or not ready**. Nothing about this message is wrong. | **NACK the group** — head *and* buffered siblings go back to the broker, the group is released. The broker redelivers, indefinitely, until *it* expires them (Q2). Nothing is held in router memory across an outage of unknown length. |
| **500** and other 5xx | The target **received and processed** the message and failed on it. Most likely something is wrong with the pointer itself, not with the target. | **Retry the head up to 3 attempts** on the backoff curve — exactly one burst, at 0 s, 1 s and 2 s — which absorbs a transient application fault (a database blip, a deadlock, a dependency wobble) without becoming an infinite retry. Still failing after that: **ACK the head back to the broker**, discarding it. Then per mode: `BLOCK_ON_ERROR` also ACKs the siblings and blocks the group; `NEXT_ON_ERROR` leaves the siblings and continues with the next message. The platform surfaces the failure for review and re-queues on resolution. |

The 3-attempt budget is `RetryPolicy.DELIVERY.burstSize()` — the same burst
that structures every other delivery, not a second constant to keep in step.
Without it, a 30-second database wobble on the platform's dispatch endpoint
would convert every ordered message in flight into a FAILED job needing
human review: nothing lost, but a self-healing transient turned into toil at
exactly the moment the system is already unhappy.

This resolves the tension between Q1 and Q2 that the earlier wording left
open ("follows the retry policy … and is then marked failed", against Q2's
no-terminal-give-up). Unavailability retries forever via the broker;
rejection is handed to the platform immediately. Neither path holds a group
in memory waiting on something with no bound.

**The unavailable path retries at the broker's cadence, not ours.** Once the
group is NACKed, redelivery timing belongs to the queue — and the nack delay
is advisory at best: SQS's is effectively a no-op and NATS takes ack-wait
from the URI (§7). So against a target down for an hour the group is
re-attempted every visibility period rather than on the exponential curve,
and the **per-endpoint circuit breaker** is what actually protects the
target: it short-circuits the HTTP calls while the broker keeps redelivering.
Anyone reading "retry indefinitely with backoff" should read it as
"indefinitely, at the broker's period, with the breaker absorbing the load".
A real backoff there would need visibility *extension*, not a nack delay.

**Scope:** ordered heads only. IMMEDIATE messages keep Go's in-pipeline
retry and still never touch the broker on a retryable outcome (§3.6), which
`guardrail_test.go` pins there and the Java conformance suite pins here.

**Caveat to watch.** ACK-on-500 assumes the target re-drives what it
rejected — true of the platform's own dispatch endpoint, which records the
job FAILED for review. A third-party ordered webhook target that answers 500
transiently would lose the message. Today the only ordered producer is the
platform scheduler (Q16), so this is sound; it needs revisiting if ordered
messages are ever pointed at a target that does not own the records.

Error resolution (the other half of the ruling): a failed message is never
retried independently by the router. It follows the retry policy (Q3), is
marked failed, and waits for a **human review** that sets it to *ignore*,
*completed* or *resend*; on that action the **platform re-queues the
group**. For `NEXT_ON_ERROR` only the failed message waits while its
siblings proceed; for `BLOCK_ON_ERROR` the whole group waits, its queued
siblings already ACKed off the broker, and is re-sent in order.

**Q16 is ruled: the Java scheduler propagates `dispatchMode` and `poolCode`**
(owner, 2026-08-24). Go publishes neither, so in Go every dispatch job reaches
the router as `IMMEDIATE` in `DEFAULT-POOL` and the ordered path is never
taken for them. In Java it *is* taken — so the router half of the Q1 ruling
above is live, load-bearing code, and a subscription's configured dispatch
pool (its concurrency and rate limit) starts applying to dispatch jobs.
Both are deliberate deviations and both need conformance tests.

**Cutover warning — the pool config goes live for the first time.**
(Owner, 2026-08-24: the dispatch path is not meaningfully used in
production yet, so this is currently theoretical — and that is exactly why
it is being fixed now. Full change spec, Go and Java:
`docs/spec/dispatch-propagation.md`.) Today
*all* dispatch traffic shares `DEFAULT-POOL` at concurrency 20
(`manager.go:24,538-539`), because every message arrives with an empty pool
code. Once Java propagates `poolCode`, each subscription is governed by its
own pool's concurrency and rate limit. Any pool configured tighter than 20
will make those subscriptions **slower than they are in Go**, not faster —
the configuration has never actually been in force. Verify the configured
pool limits against observed dispatch throughput during the cutover
rehearsal; this is a behaviour change, not a bug fix, and it is the one that
will show up as "the Java is slower".

Note also that the two symptoms are independent, so a partial fix is not
useful: propagating `poolCode` alone leaves everything unordered, and
propagating `dispatchMode` alone leaves every subscription sharing one
pool's concurrency and rate limit.

Blocking dependency: this flow needs the dispatchjob *ignore* / *completed*
routes, which require a **lockfile addition** (owner).

### 2.7 Warnings [C on `/warnings`, notifier] — `router/notification.go:16-72`

Categories: `CONFIGURATION`, `CONNECTION`, `RATE_LIMIT`, `CIRCUIT_BREAKER`,
`STALL`, `RESOURCE`, `ROUTING`, `POOL_CAPACITY`, `QUEUE_HEALTH`,
`CONSUMER_HEALTH`. Emitted today: `CONFIGURATION` (mediator 4xx/501),
`ROUTING` (unknown pool code), `POOL_CAPACITY` (all pools full),
`CONSUMER_HEALTH` (stalled consumer restart), `RESOURCE` (tracker >10 000),
`STALL` and `QUEUE_HEALTH` (**to the notifier only**, see §9.5). `CONNECTION`,
`RATE_LIMIT`, `CIRCUIT_BREAKER` are declared but never emitted.

Severities (rank): `INFO`(0) < `WARNING`(1) < `ERROR`(2) < `CRITICAL`(3)
(`notification.go:92-103`).

`Warning` (internal JSON camelCase; wire shape on the API is snake_case
`WireWarning`, `router/api/dto.go:307-338`):

| Field | Wire JSON | Type | Notes |
|---|---|---|---|
| `ID` | `id` | UUID string | |
| `Category` | `category` | enum | |
| `Severity` | `severity` | enum | |
| `Message` | `message` | string | |
| `Source` | `source` | string | e.g. `"router"`, `"HttpMediator"`, `"StallDetector"`, `"QueueHealthMonitor"` |
| `CreatedAt` | `created_at` | RFC3339 UTC | |
| `Acknowledged` | `acknowledged` | bool | |
| `AcknowledgedAt` | `acknowledged_at` | time, omitempty | |

### 2.8 Circuit breaker states [C on API/metrics] — `router/circuit_breaker.go:10-22`

`Closed`(0), `Open`(1), `HalfOpen`(2). Wire strings on the monitoring API:
`"CLOSED"`, `"OPEN"`, `"HALFOPEN"` (`strings.ToUpper("halfOpen")`,
`router/api/handlers_dashboard.go:211,223-234`). `BreakerStats`: `State`,
`Successes`, `Failures` (cumulative, reset by `Reset`), `RecentFailures`
(failures currently in the sliding window).

### 2.9 `queue.Metrics` [I, surfaced] — `queue/queue.go:35-44`

`QueueIdentifier`, `PendingMessages`, `InFlightMessages` (broker-side),
`TotalPolled`, `TotalAcked`, `TotalNacked`, `TotalDeferred` (process-local
counters, reset on consumer rebuild).

### 2.10 Snapshots surfaced by the API

- `PoolStats` (`router/health_types.go:40-55`): `poolCode`, `concurrency`,
  `activeWorkers`, `queueSize`, `queueCapacity`, `messageGroupCount`,
  `rateLimitPerMinute` (omitempty), `isRateLimited`, `metrics`
  (`EnhancedPoolMetrics`), `Histogram` (not serialised; feeds Prometheus).
- `EnhancedPoolMetrics` / `WindowedMetrics` / `ProcessingTimeMetrics`
  (`common/metrics.go`): camelCase; `last5Min`, `last30Min`, `processingTime`
  {`avgMs`,`minMs`,`maxMs`,`p50Ms`,`p95Ms`,`p99Ms`,`sampleCount`}.
- `MediatingEntry` (`router/pool.go:67-78`): `MessageID`, `PoolCode`, `Group`,
  `Queue`, `Target`, `Attempts`, `MediatedAt`.
- `HealthReport` (`health_types.go:18-28`): `status` (`Healthy|Warning|Degraded`
  internally; `HEALTHY|WARNING|DEGRADED` on the wire via `statusString`),
  `poolsHealthy`, `poolsUnhealthy`, `consumersHealthy`, `consumersUnhealthy`,
  `activeWarnings`, `criticalWarnings`, `issues[]`.
- `ConsumerHealth` (`health_types.go:30-38`).
- `TrafficStatus` (`router/traffic.go:200-226`).

---


### 2.11 `GroupFlushRegistry` [I, contract-adjacent] — `router/group_flush.go` (eff2a29)

A **per-message-group circuit breaker**, the group-scoped sibling of the
per-endpoint `BreakerRegistry`. One registry **per pool**, created in
`NewPool` — so the same message-group id in two pools suppresses
independently, and a flush in one pool never silences the other.

Purpose: a target that cannot accept a message group right now (typically a
record blocked behind an earlier failure) previously had to absorb every
sibling one at a time, each costing a delivery, a rate-limit token and a
concurrency slot. It can now answer one 2xx with `{"ack": true,
"flushGroup": true}` and the router ACKs the group's remaining messages
**without delivering them**.

**Safety condition [C] — this must be documented for integrators.** Flushed
messages are *never delivered*. The router ACKs them, which for every
backend means they are gone from the broker. This is only sound because the
**target** asked for it: it is asserting that it already owns the records
being pointed at (the message-pointer pattern) and will re-drive them
itself. **A target whose messages carry the only copy of the payload must
never set `flushGroup`** — doing so is indistinguishable from data loss.

| State | Entry | Exit | Effect on a message of that group |
|---|---|---|---|
| *Open* (not suppressed) | initial; also on expiry eviction; also `Clear(group)` | a `flushGroup` response for the group | delivered normally |
| *Suppressed* | `Flush(group, ttl)` with `ttl` from the response | `now >= until[group]`, evicted lazily on the next `Suppressed()` read | **ACKed without any HTTP call**, no rate-limit token, no concurrency slot |

Rules, each load-bearing:

- **TTL, not an explicit resume.** Suppression is time-bounded and
  self-healing: there is no "resume" protocol for the target to get wrong.
  When the window lapses the next message of the group goes through as a
  **probe** — the target either flushes again or delivery resumes on its own.
- **TTL clamping.** `ttl <= 0` → `DefaultFlushTTL` **60 s**; `ttl >
  MaxFlushTTL` → **5 min**. So a target cannot silence a group indefinitely.
- **Extend-only.** `Flush` refuses to shorten a live window: if the stored
  expiry is already later than the new one it returns `false` and changes
  nothing. A probe landing mid-window can never pull the expiry in.
- **Ungrouped is a no-op.** `Flush("")` returns false and `Suppressed("")`
  returns false, so flushing can never create a bucket that swallows
  unrelated ungrouped traffic. A `flushGroup` response for an ungrouped
  message is logged at WARN and otherwise ignored (`pool.go:800-802`).
- **`ack:false` takes precedence.** A target that wants the message back
  cannot also discard its group — see §6.5 and the parse order in §6.6.

Counters: `flushes` (one per accepted `Flush`, so extensions that change
nothing are not counted) and `suppressed` (one per message ACKed without
delivery), both read via `Stats()`.

**Not wired to anything.** `SuppressedUntil`, `Clear` and `Stats` have
**zero callers** outside tests — verified by grep. So the operator question
the design explicitly anticipates ("why is this group quiet?") is currently
unanswerable, and there is no override to lift a suppression early. **Q52.**

## 3. Topology & concurrency (as behaviour)

### 3.1 Components

| Component | One per | Responsibility | Citation |
|---|---|---|---|
| **Consumer poll loop** | queue (by `queueName`) | polls ≤10, hands batches to routing, paces itself, heartbeats | `router/manager.go:428-508` |
| **Router (Manager.route)** | process | assigns batch id, dedups via the in-flight tracker, resolves the pool, submits | `router/manager.go:311-389` |
| **Pool** | pool code | capacity backpressure, concurrency semaphore, rate limiter, IMMEDIATE workers, per-group FIFO drainers, outcome resolution (ack/retry) | `router/pool.go` |
| **Mediator** | process (shared by all pools) | breaker gate, HTTP delivery with bounded in-call retries, signing, status→outcome mapping, breaker accounting | `router/mediator.go` |
| **Breaker registry** | process; one breaker per target URL | failure-rate state machine | `router/circuit_breaker.go` |
| **Host pool registry** | process; one pool per origin | HTTP/2 connection slot grow/shrink | `router/host_pool.go` |
| **In-flight tracker** | process | ownership + dedup + receipt-handle freshness; feeds stall detector, drain, dashboard | `router/inflight.go` |
| **Stall detector**, **queue-health monitor**, **reaper**, **broker-stats cache**, **lifecycle loops** (warning cleanup, consumer health/restart, health report), **notifier** | process | periodic housekeeping | §4, §5 |
| **Config watcher** | process (leader only in standby mode) | fetch/merge/reconfigure | `router/config_sync.go:218-247` |

Pools are **passive**: they never poll. A pool receives messages from *every*
queue, so ack/nack is addressed to the message's *source* consumer, resolved
by `QueueIdentifier` at ack time; if that queue was deregistered meanwhile the
action is logged and skipped (`router/pool.go:144-204`). **[I]**, but the
resulting invariant is a contract: *a message is only ever acked/nacked on the
queue it came from*.

### 3.2 Consumer poll loop — required behaviour

Per iteration (`router/manager.go:436-507`):

1. Stop if cancelled.
2. **Backpressure gate**: if *no* pool has spare buffer capacity, wait,
   **event-driven**, for capacity to return — a pool signals the moment its
   `queueSize` crosses back under the threshold, and the loop parks untimed
   on that signal rather than polling a fixed interval (**Java-first
   correction, 2026-09-07**: Go still pauses a fixed 2 s and retries; on a
   fast broker several pollers fill a shared pool buffer in well under a
   second, the workers drain it in a fraction of that, and every poller then
   sits out the rest of the fixed pause with the router mostly idle —
   measured 1,312 deliveries/s at 22% CPU across eight queues where one
   queue alone reaches 7,916/s at 99% CPU; `docs/go-mirror/2026-09-06-go-fix-list.md`
   G12). If there are no pools at all, no signal exists to wait on, so this
   still pauses a fixed 2 s and retries — a defensive fallback for a state
   production never actually reaches (`RouterManager#reconfigure` always
   creates `DEFAULT-POOL` before any loop starts), not a knob. On the
   *transition* into "all full" record one `POOL_CAPACITY`/`WARNING` warning
   `"all pools at capacity; pausing <queueId>"` (not repeated while it stays
   full). A pool "has capacity" iff `queueSize < max(concurrency×20, 50)`.
3. `Poll(ctx, 10)`.
   - Error and consumer reports `ErrStopped` → **exit the loop** (the restart
     watchdog will rebuild it); no heartbeat.
   - Other error → log, sleep **1 s**, continue; **no heartbeat**
     (`manager_stopped_consumer_test.go:58-112`).
   - Success → heartbeat `lastPoll = now` (empty or not).
4. Empty batch → sleep **1 s**. (For SQS the `Poll` itself already long-polled
   up to 20 s.)
5. Non-empty → `route(batch)`; re-polls immediately, whether the batch was
   **partial** (<10) or full (**Java-first correction, owner ruling
   2026-09-07**: Go still sleeps 500 ms after a partial batch on the theory
   that the queue is draining, but that pause is the same throughput bug as
   step 2's — it holds the loop back from work the broker may already have
   ready; G12 covers both in one fix).

### 3.3 Routing — required behaviour (`router/manager.go:319-389`)

For each message of a batch, in batch order:

1. Stamp `BatchID` (process-wide counter as decimal string).
2. If a tracker exists, **register** ownership (§4.2):
   - `Redelivery` → drop this copy (nothing is acked/nacked; the owner's
     receipt handle was swapped to this fresher one). If the message is
     ordered, *kick* the owning pool's group drainer (`tryDrainGroup`) so a
     group whose drainer died with a cancelled consumer resumes
     (`manager_route_test.go:233-269`).
   - `ExternalRequeue` → **ACK this copy on the source consumer** with its own
     receipt handle (delete the duplicate from the broker); do not submit
     (`manager_route_test.go:79-115,167-199`).
   - `New` → continue.
3. Resolve the pool: `poolCode` → that pool; empty → `DEFAULT-POOL` (no
   warning); non-empty but unknown → `DEFAULT-POOL` **plus one `ROUTING`
   warning per message** (`manager_route_test.go:36-60`). **load-bearing or
   accident?** — one warning per *message*, not per distinct pool code, can
   flood the 1 000-entry store under load.
4. No pool at all (not even `DEFAULT-POOL`) → release the tracker entry and
   **Nack with delay 5 s**.
5. Otherwise `pool.submit`.

`DEFAULT-POOL` is always ensured by `Reconfigure` (concurrency 20 when the
config does not define it, `router/manager.go:534-540`), so step 4 only
happens before the first reconfigure or after shutdown.

### 3.4 Pool — required behaviour (`router/pool.go`)

**Submit** (`pool.go:253-289`):

1. Pool stopped → `Nack(delay 10)` (reason "pool stopped"); the tracker entry
   is released first.
2. Capacity: `capacity = max(concurrency×20, 50)`; if `queueSize ≥ capacity`
   → `Nack(delay 10)` ("pool at capacity"). `queueSize` counts IMMEDIATE
   messages waiting for a semaphore slot **and** IMMEDIATE messages sitting in
   a retry backoff **and** every buffered ordered message (including a
   retrying group head during its backoff).
3. IMMEDIATE → `queueSize++`, start an independent worker.
4. Ordered → append to the back of the group's FIFO (group = `messageGroupId`
   or `""` — **all ordered messages without a group share one global group
   per pool** and are therefore serialised; **load-bearing or accident?**);
   if the pool stopped concurrently → `Nack(delay 10)`. Then start a drainer
   for the group if none is running.

**IMMEDIATE worker** (`pool.go:296-339`): acquire a semaphore slot (if
cancelled while waiting → `queueSize--`, `Nack(delay 10)` "shutdown before
dispatch"); `queueSize--`; process once (§3.5); if the verdict is *retry*:
`Attempts++`, `queueSize++`, wait the backoff (if cancelled while waiting →
`queueSize--`, **release the tracker entry, no broker action** — the message
re-enters via the broker's own redelivery), then dispatch again.

**Ordered drainer** (`pool.go:522-646`) — one per group while the group holds
work:

- Loop: pop the head (delete the group when empty and exit); `queueSize--`;
  acquire a slot (cancelled while waiting → **re-front** the popped message,
  clear the group's `working` flag so a later submit/redelivery respawns a
  drainer, exit; if the pool stopped meanwhile → `Nack(delay 10)` "pool
  stopped during drain"); process once; on *retry*: `Attempts++`, re-front
  the message (pool stopped → `Nack(delay 10)` "pool stopped during retry";
  exit), wait the backoff holding **no** slot (cancelled → clear `working`,
  exit — the message is already re-fronted); loop.
- A *duplicate* verdict (see below) simply continues with the next head.

**Stop** (`pool.go:348-368`): mark stopped; flush every buffered message
(`queueSize -= n`, release their tracker entries, **no broker action**); in-
flight workers finish on their own.

**Hot updates**: `UpdateConcurrency(n)` — `n==0` rejected (`false`);
unchanged → no-op; otherwise a new semaphore of size `n` is installed; workers
already holding a slot keep it on the old semaphore, so effective concurrency
during the transition is ≤ `old_in_flight + n`, settling to `n`
(`pool.go:222-239`). `SetRateLimit(rpm)` / `UpdateRateLimit(*rpm)` swap the
limiter atomically; `nil`/0 → unlimited (`pool.go:209-220`,
`router/ratelimit.go`).

### 3.5 Process-one (the per-attempt pipeline) — `router/pool.go:717-825`

1. `activeWorkers++`; record a *mediating* entry (never reaped; removed on
   exit).
2. **Panic isolation** [I]: any panic → verdict *retry* after **10 s**, entry
   marked retrying. (Java: per-thread failure isolation makes the recover
   scaffolding unnecessary; the *retry after 10 s on unexpected exception*
   policy is the behaviour to keep or rule on.)
3. First dispatch only (`Attempts==0`) and tracker present: `EnsureTracked` —
   restores a reaped entry; if a *different* broker copy of the same app id
   owns the pipeline → **ACK this copy with its own receipt handle** and
   return *duplicate* (`inflight_test.go:92-119`).
4. **Flushed-group gate** [eff2a29]: if the message has a non-empty group and
   that group is currently suppressed (§2.11), **ACK it and stop** — no HTTP
   call, no metrics recorded, verdict *done*. Ordering here is the whole
   point: this sits **before** the rate limiter, so a flushed group spends
   neither a token nor a concurrency slot. Reading the registry also evicts
   an expired entry, which is what turns the next message into a probe.
5. **Rate limit**: if the bucket is empty right now, count one rate-limited
   event (same counter as HTTP 429 — **load-bearing or accident?**); then
   wait for a token; cancelled while waiting → mark retrying, verdict *retry*
   with `retryDelay(attempts, floor 5 s)`.
6. **Mediate** (§6), timing the call.
7. Resolve (table in §6.5): terminal outcomes ACK with the **freshest**
   receipt handle from the tracker (falling back to the dispatch-time
   handle) and release the entry; retryable outcomes mark the entry retrying
   and return *retry* with a computed backoff. **Superseded (Go `7bbef5d`):**
   an unreachable target NACKs immediately rather than retrying in place, and
   the in-place retry is bounded at 10 attempts. Original text: retryable
   outcomes **never
   touch the broker** (`guardrail_test.go:103-165`).

A suppressed message (step 4) records **no pool metric at all** — not
success, not transient, not rate-limited. It is visible only in the
registry's own `suppressed` counter, which nothing reads (§2.11). So a pool
whose groups are being flushed heavily looks *idle* on `/monitoring` and in
Prometheus rather than busy-but-suppressed. **Q53.**

### 3.6 Invariants (each is a sentence the Java must keep true)

1. **A pool never polls.** Only consumer loops call `Poll`. (`pool.go:20-24`)
2. **A message is acked or nacked only on the queue it was polled from**, via
   the consumer resolved by `QueueIdentifier` at action time; if that consumer
   is gone the action is skipped and logged. (`pool.go:154-204`)
3. **Within a message group, at most one message is being delivered at a
   time**, and messages of a group are attempted in arrival order.
   (`pool.go:80-90,517-533`; `pool_cascade_test.go:105-150`)
4. **A retryable failure of an ordered message re-queues it at the FRONT of
   its group**, so it is the next attempted and is never overtaken; the group
   is head-of-line blocked until it succeeds, is ACK-dropped (4xx), or the
   pool stops. (`pool.go:496-515,609-631`)
5. ~~**Retryable outcomes never release the message to the broker**: no Nack,~~
   **NO LONGER TRUE (Go `7bbef5d`, Java 2026-08-25).** An unreachable target
   NACKs immediately, and an in-place retry is bounded — past
   `maxInPipelineAttempts` (10) the message is released to the broker, because
   an in-pipeline retry never returns it and while it loops the broker's
   expiry, redelivery count and dead-letter queue can never act on it. The
   original invariant, kept for the record:
   no Nack,
   no Defer, no visibility change; the retry lives in-process with the in-
   flight entry kept. Only terminal outcomes (2xx success, 4xx config error,
   process-time duplicate) ACK. (`guardrail_test.go:103-165`,
   `pool_cascade_test.go:146-149,194-196`)
6. **Every message that leaves the pipeline without an ACK releases its
   tracker entry first** (pool stopped, at capacity, shutdown before
   dispatch, cancelled mid-backoff, no pool, flush on stop, force-ack,
   stall force-nack), so the broker's redelivery is processed as a fresh
   copy rather than dropped as a duplicate. (`manager_route_test.go:271-322`)
7. **Ownership is claimed at route time, before buffering**, so redeliveries
   and external requeues of a message still sitting in a group buffer are
   deduplicated. (`inflight.go:56-84`, `manager_route_test.go:167-231`)
8. **A redelivery (same broker id, or same app id when broker ids are blank)
   swaps the owner's receipt handle to the fresher one and is dropped**; the
   eventual ACK uses the freshest handle. (`inflight_test.go:14-27`,
   `manager_route_test.go:339-369`)
9. **An external requeue (same app id, different broker id) is ACK-deleted
   and never adopts its handle onto the owner.**
   (`inflight_test.go:29-54`)
10. **`EnsureTracked` never regresses a receipt handle.** (`inflight_test.go:92-119`)
11. **Entries with `Attempts>0` are neither reaped nor reported stalled**; the
    reaper ages on `LastSeenAt`. (`inflight.go:188-220,281-293`,
    `inflight_test.go:71-90`)
12. **Concurrency is bounded per pool by the semaphore across both paths**
    (IMMEDIATE workers and ordered drainers compete for the same slots); a
    retry backoff holds no slot. (`pool.go:37-46,603-607,629`)
13. **Backpressure is by buffer size, not by blocking**: `submit` never
    blocks; at capacity it nacks (delay 10 s); consumers stop polling while
    *every* pool is full. (`pool.go:259-268`, `manager.go:443-457`)
14. **A cancelled ordered drainer always leaves its group resumable**
    (message re-fronted, `working` cleared); a redelivery of a buffered
    message or a later submit resumes it. (`pool_cascade_test.go:224-320`,
    `manager_route_test.go:233-269`)
15. **Breaker accounting happens exactly once per `Mediate` call, in the
    mediator**: success for 2xx and 4xx, failure for 5xx/transport, nothing
    for 429 / deferred / circuit-open. (`mediator.go:220-239`,
    `guardrail_test.go:204-257`)
16. **RULED (owner, 2026-08-24): propagate both fields.** The Java scheduler puts `dispatchMode` and `poolCode` on every published message. This is a **deliberate deviation from Go**: the router's ordered path and per-pool routing become live for dispatch jobs, which means a subscription's configured dispatch pool (concurrency + rate limit) starts applying, and the **router half of the Q1 ruling becomes load-bearing code rather than dormant**. Conformance tests must pin both. Evidence that led to the ruling follows.

    **VERIFIED 2026-08-24.** The scheduler publishes with `messageGroupId` but **no `dispatchMode` and no `poolCode`**. `buildMessage` (`scheduler/dispatcher.go:81-94`) sets only `ID`, `MediationType`, `MediationTarget`, `AuthToken` and — when non-empty — `MessageGroupID`. Both dropped fields exist in the data: `dispatchClaim` carries `mode` and uses it platform-side, but `DispatchJobToken` (`poller.go:383-387`) narrows to `{JobID, MessageGroup, TargetURL}` before `buildMessage` ever sees it; and `msg_subscriptions` carries `dispatch_pool_id` / `dispatch_pool_code`, which nothing propagates onto the message.

    Consequences, all verified rather than inferred:
    - `DispatchMode` is the zero value `""`, so `RequiresOrdering()` is false → **every dispatch job is dispatched as `IMMEDIATE`**. The router's per-group FIFO never engages for the platform's primary producer. The only sites that set `DispatchMode` on an outgoing message at all are two router **API** handlers (`handlers_misc.go:201`, `handlers_messages.go:107`) — operator-submitted messages, not production traffic.
    - `PoolCode` is empty, so every dispatch job routes to the synthesised **`DEFAULT-POOL`** (`manager.go:20,394-402,538-539`). A subscription's configured dispatch pool — and therefore its concurrency and rate limit — is inert for dispatch jobs.
    - This is *why* `f1fc427` and `5bb46df` were both platform-side changes: ordering for dispatch jobs is enforced entirely by the poller's per-mode filter and the delivery-time hold-back, reading `job.Mode` from the database. The router is not part of it.

    **Decision needed before the ordered-group code is written.** Either (a) the Java scheduler propagates `dispatchMode` and `poolCode` onto the published message, which activates the router's ordered path and per-pool routing and makes the router half of the Q1 ruling live and load-bearing; or (b) it does not, ordering stays a platform-side concern, and the router's ordered machinery applies only to other producers — in which case the Q1 router-side ruling (BLOCK_ON_ERROR ACKs its queued siblings) is unreachable for dispatch jobs and should say so. (a) appears to be the original intent, since both fields are modelled on both sides and only the publish step drops them; but it is a behaviour change and it is the owner's call. See §2.6.


17. **RULED (owner, 2026-08-25): mark the row as errored.** Go fails the *whole poll* on a malformed payload, and the row is already claimed at that point — so it re-claims and re-fails every time its visibility lapses, and every message behind it is never delivered. One bad row stops a Postgres queue permanently. Java instead **moves the row into a `queue_messages_failed` table** and carries on with the rest of the batch. Moving beats flagging in place: the live table stays purely live work, with no errored rows in its heap to vacuum around and no error predicate in the claim query at all, so the existing index remains exactly right. It also makes a rollback to Go **safe rather than merely tolerable** — Go's claim has no notion of an error flag, so a flagged row would be re-claimed and poison it again, whereas a moved row is simply not there. And moving beats deleting — which is what SQS and NATS effectively do — because the payload is the only evidence of *why* it was malformed. The move is one statement (`DELETE … RETURNING` feeding an `INSERT … ON CONFLICT DO UPDATE`), so it can never be half-applied, and the upsert means a message that fails, is re-queued and fails again records its latest failure rather than a row per attempt.


18. **`HighPriority` never reorders anything.** (`pool_test.go:11-32`)

### 3.7 Ordering guarantees (and non-guarantees)

- Guaranteed: FIFO *attempt* order within `(pool, group)` for ordered
  messages **as received by this router process**, across retries.
- Not guaranteed: order across pools, across processes (a redelivery after a
  restart is re-routed from scratch), between IMMEDIATE messages (even in the
  same group — IMMEDIATE ignores the group entirely), or by the Postgres
  broker across polls (§7.3: a claimed head does not block its successors on
  the next poll). The platform scheduler publishes with a group but **without
  a dispatch mode** (`platform/scheduler/dispatcher.go:81-94`), so platform
  dispatch jobs are IMMEDIATE in the router. **load-bearing or accident?**
  (see §13).
- Batch order within a poll is preserved for *submission*; delivery order of
  IMMEDIATE messages is then arbitrary.

### 3.8 What Go does because of Go

| Go mechanism | Behaviour it implements (keep) | Go-specific shape (drop) | Citation |
|---|---|---|---|
| Buffered channel as semaphore, swapped atomically on resize; workers snapshot the channel so release goes to the one they acquired from | per-pool concurrency cap with "old in-flight + new cap" transition semantics | channel-send-as-acquire, `atomic.Value` of channel | `pool.go:37-46,142,297-313,582-607` |
| Goroutine per IMMEDIATE message, recursive re-spawn after backoff | independent concurrent delivery + in-process retry | goroutine recursion | `pool.go:296-339` |
| Goroutine per group while the group has work; `working` flag + `tryDrainGroup` | one-at-a-time per group, drainer dies when group empties | goroutine lifecycle, flag choreography | `pool.go:517-646` |
| `context` threaded from consumer → route → pool → worker; cancellation of a consumer's ctx aborts its in-flight HTTP calls and backoffs | consumer restart / reconfigure / leadership loss stops work that came from that consumer | ctx params everywhere, `select` on `ctx.Done()` | `manager.go:592-598`, `pool.go:298-308,323-336,583-599,623-630` |
| `select { case <-ctx.Done(): case <-time.After(d): }` | interruptible sleep | — | many |
| `sync.WaitGroup` + done-channel bridge | bounded wait for poll loops on shutdown | — | `manager.go:625-633`, `lifecycle.go:179-197` |
| `defer recover()` in `processOne` | "unexpected failure → retry after 10 s" | panic recovery | `pool.go:728-738` |
| `atomic.Pointer[rate.Limiter]` hot swap | rate limit hot update | — | `ratelimit.go` |
| Per-scheme `init()` registration of backends | scheme → backend resolution | global registry | `queue/queue.go:92-136` |
| `huma`/`chi` adapters, provider interfaces with nil-degradation (503 / empty payload) | every optional provider absent → documented degraded response | — | `api/api.go:146-173` |
| `http.Transport` per slot, `CloseIdleConnections` on evict | per-origin connection slots | net/http specifics | `host_pool.go` |

---

## 4. State machines

### 4.1 Message lifecycle (one broker copy)

| # | State | Entered by | Exits to |
|---|---|---|---|
| 1 | **Polled** | consumer `Poll` returned it | 2 |
| 2 | **Registered** (tracker: `RegisterNew`) | route | 3a (IMMEDIATE queued), 3b (ordered buffered), Nacked-no-pool (delay 5, entry released) |
| 2′ | **Dropped as redelivery** (`RegisterRedelivery`) | route | — (owner's handle swapped; ordered → drainer kicked) |
| 2″ | **ACKed as external requeue** (`RegisterExternalRequeue`) | route | — |
| 3a | **Queued (IMMEDIATE)** `queueSize++` | submit | 4 when a slot is acquired; Nacked (delay 10) if pool stopped / at capacity / cancelled before slot |
| 3b | **Buffered (ordered)** `queueSize++` | submit | 4 when it is the head and the drainer holds a slot; Nacked (delay 10) if pool stopped at submit; Flushed (entry released, no broker action) on pool stop |
| 4 | **Delivering** (`activeWorkers++`, mediating entry present) | worker | 5 (done/acked), 6 (retrying), 7 (duplicate), back to 3b-front / 3a-backoff on retry |
| 5 | **Acked** (2xx success, or 4xx config error) — entry released | process-one | terminal |
| 6 | **Retrying / in backoff** (entry kept, `Attempts>0`, `queueSize++`; ordered: re-fronted) | process-one retry verdict | 4 after backoff; if cancelled mid-backoff: IMMEDIATE → entry released, leaves pipeline (broker redelivers); ordered → parked (re-fronted, drainer cleared) |
| 7 | **ACKed as duplicate** (process-time `EnsureTracked` false) | process-one | terminal (owner unaffected) |
| 8 | **Nacked** (pool stopped / at capacity / shutdown / no pool / stall force-nack) — entry released | various | broker redelivers after its visibility/delay rules |
| 9 | **Force-acked** (operator) — broker delete best-effort, entry released | API | terminal for the entry; a running delivery attempt still finishes and will log a warn on its stale handle |
| 10 | **Reaped** (entry aged >15 min on `LastSeenAt`, `Attempts==0`) | reaper | entry gone; message may still be buffered/delivering (restored by `EnsureTracked` on first dispatch) |

Mapping to the wire-visible queue actions: **Ack** on 5, 7, 2″, 9; **Nack**
on 8; nothing on 2′, 3b-flush, 6-cancel(IMMEDIATE), 10.

### 4.2 Tracker `Register` outcomes — `router/inflight.go:56-84`

| Incoming copy vs. tracker | Outcome | Side effect |
|---|---|---|
| broker id non-empty and already tracked by broker id | `Redelivery` | owner's handle ← this handle; `LastSeenAt` refreshed |
| app id tracked; both broker ids non-empty and different | `ExternalRequeue` | none |
| app id tracked; otherwise (blank broker id on either side, or equal) | `Redelivery` | handle swap |
| not tracked | `New` | inserted (by app id; also by broker id when non-empty) |

Note: on Postgres the broker id **is** the app id, so `ExternalRequeue` can
never occur there; on NATS the broker id includes the consumer sequence,
which **changes on every redelivery**, so a NATS redelivery is classified as
`ExternalRequeue` (§7.4, §13).

### 4.3 Circuit breaker — `router/circuit_breaker.go`

Config defaults: failure-rate threshold 0.5, min calls 10, success threshold
3, reset timeout 5 s, window 100 samples.

| From | Event | Condition | To | Notes |
|---|---|---|---|---|
| Closed | `RecordFailure` | window has ≥10 samples **and** failures/samples ≥ 0.5 | Open | `lastFailure=now`; check only on a failure |
| Closed | `RecordSuccess` | — | Closed | sample pushed |
| Open | `Allow` | `now − lastFailure ≥ 5 s` | HalfOpen | half-open success count reset; **every** concurrent `Allow` after the timeout passes (no single-probe gate) |
| Open | `Allow` | otherwise | Open | returns `ErrCircuitOpen` → outcome `CircuitOpen(5)` |
| Open | `RecordSuccess`/`RecordFailure` | — | Open | sample pushed; failure refreshes `lastFailure` |
| HalfOpen | `RecordSuccess` | 3rd consecutive success | Closed | window cleared |
| HalfOpen | `RecordSuccess` | <3 | HalfOpen | |
| HalfOpen | `RecordFailure` | — | Open | `lastFailure=now` |
| any | `Reset` (API) | — | Closed | window + cumulative counters + `lastFailure` cleared |
| any | idle > 1 h (no Allow/Record) | reaper tick (5 min) | evicted (next `Get` creates a fresh Closed breaker) |

`State()` never transitions; only `Allow` does (`circuit_breaker_test.go:52-65`).
Registry is keyed by the exact `mediationTarget` string.

### 4.4 Consumer health / restart — `router/manager.go:635-732`, `router/lifecycle.go:230-250`

| State | Detection | Action |
|---|---|---|
| Healthy | `lastPoll` within 60 s | — |
| Stalled | `lastPoll != 0 && now − lastPoll > 60 s` on the 30 s consumer-health tick | warning `CONSUMER_HEALTH` ("Consumer X is stalled, restart attempt N"), severity `WARNING` until the 11th attempt (`attempts ≥ 10` → `CRITICAL`); sleep 5 s; cancel the old loop's context (aborts its in-flight deliveries), `Stop()` the old consumer; build a new consumer; if the registry entry is still the stalled one, swap it in and start a new loop (`lastPoll=now`); `restartAttempts[name]++` **only on a successful rebuild** |
| Recovered | not in the stalled set on a later tick | `restartAttempts[name]` cleared |
| Exited on `ErrStopped` | poll loop returned | looks stalled once `lastPoll` ages past 60 s → restarted as above |

A rebuild failure (`NewConsumer` error) logs and `continue`s without
incrementing the attempt counter — so a consumer that can *never* be rebuilt
never escalates to `CRITICAL`. **load-bearing or accident?**

`HealthService`'s own consumer model (`SetConsumerRunning`, `RecordConsumerPoll`,
`RecordPoolResult`, `RemoveStaleEntries`) is **never fed by production code**
(grep: only tests call them). Consequences are in §9.4.

### 4.5 Leadership gate — `router/server.go:301-351`

| Event | Action |
|---|---|
| Gain (incl. initial `IsLeader()` true) | create a per-leadership context; start the config watcher (`Watch`) under it (or, with no config URL, log and do nothing); `Traffic.Register` |
| Loss | cancel the leadership context (stops the watcher; cancels every consumer context created under it → aborts in-flight HTTP calls, parks ordered groups, releases IMMEDIATE entries); `Traffic.Deregister` (≤30 s); `Manager.Shutdown` (≤30 s: cancel+stop consumers, stop pools, clear registries, wait for poll loops) |
| Gain again | same as gain; `Reconfigure` repopulates the emptied registries |
| Standby disabled | always leader: watcher started once, `Register` once |

Election internals (§10).

### 4.6 Stall detector — `router/inflight.go:244-324`

Every 60 s: for each tracker entry with `Attempts==0` and
`ElapsedSeconds ≥ 300`: emit a `STALL`/`WARNING` warning **to the notifier
only** ("Message <id> stalled for <n>s in pool <code>"); if `ForceNackStalled`
(default **off**) and a nack function is wired and `Elapsed ≥ 600`: Nack on the
source queue with delay 30 s and remove the entry (failure → keep the entry,
retry next tick).

### 4.7 Server lifecycle — `router/server.go:186-260`

| Phase | What runs |
|---|---|
| Constructed (`NewServer`) | registries, mediator (host-pool sweep goroutine started), tracker, manager, warning/health/lifecycle services, optional election client, traffic strategy; with default broker: queue schema init + `Reconfigure(default)` **before** `Run` (`server/run.go:305-334`) |
| Running (`Run`) | notifier loop, stall detector, queue-health monitor, reaper, broker-stats refresh, lifecycle loops; then leadership gate or (non-standby) watcher + ALB register; blocks on ctx |
| Draining | ctx cancelled → wait until tracker count is 0 or **DrainTimeout (60 s)**, polling every 500 ms |
| Stopping (30 s budget) | `Traffic.Deregister` → `Lifecycle.Shutdown` → `Manager.Shutdown` → `election.Stop` → `Notifier.Stop` |
| Stopped | returns nil; `server.Run` then shuts the HTTP listeners (30 s) and waits for subsystems |

Because consumer contexts descend from the `Run` context, **the same
cancellation that starts the drain also aborts every in-flight HTTP delivery**
(requests are built with the consumer ctx, `mediator.go:291`); the "drain"
therefore waits for workers to *unwind*, not to *finish*. See §11, §13.

### 4.8 Host-pool slot — `router/host_pool.go`

| Event | Rule |
|---|---|
| First request to an origin | pool created with 1 slot |
| `Acquire` | pick the least-loaded slot; if **every** slot has in-flight ≥ 100 → try to grow (re-checked under lock; capped at 8 slots/origin, 1 under HTTP/1.1) → new slot takes the request; at cap → warn (≤1 per origin per 60 s) and use the least-loaded slot (h2 queues internally) |
| Sweep (every 15 s) | evict slots with in-flight ≤ 20 **and** idle > 60 s; never below 1 slot (keep the freshest); evicted slots' idle connections closed |
| `Close` | stop sweep; close idle connections on all slots (never called in production — `HTTPMediator.Close` has no caller) |

---

## 5. Timing & sizing constants

One row per constant. "LB" = load-bearing (keep unless ruled otherwise);
"ACC?" = looks arbitrary / contradicts docs / unexercised — owner to rule.
Evidence column says why.

| # | Name | Value | Unit | Where used | LB / ACC? | Evidence |
|---|---|---|---|---|---|---|
| 1 | `defaultPoolCode` | `DEFAULT-POOL` | — | fallback pool, always ensured (`router/manager.go:20,538-540`) | **LB** | test `TestManagerPoolForMessage`; the platform scheduler never sets `poolCode`, so *every* platform job lands here |
| 2 | `defaultPoolConcurrency` | 20 | workers | synthesised `DEFAULT-POOL` when config omits it (`manager.go:24,539`) | ACC? | no test; comment only |
| 3 | `consumerRestartDelay` | 5 | s | pause before rebuilding a stalled consumer (`manager.go:28,700`) | ACC? | comment: thundering-herd; plausible |
| 4 | `consumerRestartCriticalAfter` | 10 | attempts | warning severity escalates to CRITICAL (`manager.go:32,683`) | ACC? | comment only; counter is not bumped on rebuild failure |
| 5 | `maxPoll` | 10 | msgs | `Poll(ctx, 10)` per iteration (`manager.go:434`) | **LB** | equals the SQS hard limit (`sqs.go:154-156`) and NATS default batch |
| 6 | all-pools-full pause | 2 | s | consumer loop when no pool has capacity (`manager.go:454`) | ACC? | untested |
| 7 | poll-error sleep | 1 | s | `manager.go:478` | ACC? | test pins "no heartbeat on error", not the delay |
| 8 | empty-poll sleep | 1 | s | `manager.go:491` | ACC? | stacks on SQS 20 s long-poll; on Postgres it *is* the idle poll period |
| 9 | partial-batch pause | 500 | ms | `manager.go:504` | ACC? | "queue draining" heuristic |
| 10 | nack delay, no pool | 5 | s | `manager.go:382` | ACC? | |
| 11 | nack delay, pool stopped / at capacity / shutdown-before-dispatch / stopped-during-drain/retry | 10 | s | `pool.go:256,266,285,305,593,620` | ACC? | ignored by SQS (Nack no-op) |
| 12 | `queueCapacityMultiplier` / `minQueueCapacity` | 20 / 50 | msgs | `capacity = max(concurrency×20, 50)` (`pool.go:470-473`, `manager.go:519-522`, `Stats()`) | **LB** | surfaced on API as `queueCapacity` / `maxQueueCapacity`; drives backpressure |
| 13 | `retryMinDelay` | 100 | ms | base of the error backoff curve (`pool.go:671`) | **LB** | `TestRetryDelayKeepsTheErrorCurve` |
| 14 | `retryMaxDelay` | 5 | min | cap of the error curve (`pool.go:672`) | **LB** | same test |
| 15 | backoff shift cap | 12 | attempts | `min(attempts,12)` (`pool.go:699`); 100 ms×2¹² = 409.6 s > cap, so the cap is reached at attempt 12 | **LB** | `retryDelay(12,0)==5m` pinned |
| 16 | `panicRetryDelay` | 10 | s | after a recovered panic (`pool.go:673`) | ACC? | `TestGuardrail_RetryOnPanic` pins *retry*, not the delay |
| 17 | `deferredMinDelay` / `deferredMaxDelay` | 5 s / 60 s | — | ack=false curve: 5,10,20,40,60… (`pool.go:678-679`) | **LB** | `TestDeferredDelayCurve` pins the whole curve incl. floor-but-not-cap-lift |
| 18 | rate-limit-wait-cancelled floor | 5 | s | `retryDelay(attempts, 5)` (`pool.go:774`) | ACC? | untested |
| 19 | `ErrorConnection` default delay | 30 | s | floor for transport errors (`common/mediation.go:61`) | ACC? | no test pins 30 for connection; 5xx floor is pinned |
| 20 | 5xx / unexpected-status delay | 30 | s | `mediator.go:384,390` | **LB** | `TestGuardrail_RetryOnProcessError` pins ≥30 s |
| 21 | 429 default Retry-After | 30 | s | when header absent/non-integer (`mediator.go:365`) | ACC? | test pins header parsing (120) only |
| 22 | Mediator prod `Timeout` | 15 | min | per HTTP request (`mediator.go:67`) | ACC? | ×3 attempts → one `Mediate` can pin a worker ~45 min; comment says "15min timeout" deliberately |
| 23 | Mediator prod `ConnectTimeout` / `TLSHandshakeTimeout` | 30 s / 10 s | — | dial / TLS (`mediator.go:68-69`) | **LB** | `TestMediatorConnectTimeoutHonoured` |
| 24 | Mediator `MaxRetries` | 3 | total attempts | in-call retries for 5xx/transport only (`mediator.go:71,244-266`) | **LB** | `TestMediatorServerErrorRetries` pins 3 attempts |
| 25 | Mediator `RetryDelays` | 1 s, 2 s, 3 s (fallback 3 s) | — | between in-call attempts (`mediator.go:72,269-272`) | ACC? | only the count is tested |
| 26 | Dev mediator | 30 s timeout, 10 s connect, HTTP/1.1 | — | `FLOWCATALYST_DEV_MODE` (`mediator.go:77-85`) | ACC? | |
| 27 | Transport `MaxIdleConnsPerHost` / `IdleConnTimeout` / dial `KeepAlive` | 10 / 90 s / 30 s | — | `mediator.go:168-175` | ACC? | Go net/http tuning; no cap on concurrent conns |
| 28 | Host pool `StreamsHighWatermark` / `Low` / `MaxSlotsPerHost` / `SlotIdleGrace` / `SweepInterval` / `MaxSlotsWarningInterval` | 100 / 20 / 8 / 60 s / 15 s / 60 s | — | `host_pool.go:108-117` | ACC? | comment: sized under ALB's 128 streams; tests use other numbers |
| 29 | HTTP/1.1 preset `MaxSlotsPerHost` | 1 | — | `host_pool.go:122-126` | **LB** | HTTP/1.1 doesn't multiplex; more slots would double the transport pool |
| 30 | Breaker `FailureRateThreshold` / `MinCalls` / `SuccessThreshold` / `ResetTimeout` / `BufferSize` | 0.5 / 10 / 3 / 5 s / 100 | — | `circuit_breaker.go:48-56` | **LB** (semantics) / ACC? (numbers) | tests pin the *rules* with a smaller cfg; `TestGuardrail_MediatorShortCircuitsWhenOpen` uses 10 failures against defaults |
| 31 | Breaker `ResetTimeout` as defer delay | 5 | s | `CircuitOpen(int(ResetTimeout.Seconds()))` (`mediator.go:223`) | **LB** | `TestGuardrail_RetryOnCircuitOpen` pins ≥5 s |
| 32 | Rate limiter burst | = rpm (min 1) | tokens | `ratelimit.go:56-60` | ACC? | comment says "small jitter" but burst equals a full minute's allowance |
| 33 | `InFlightReapMaxAge` / reaper tick | 15 min / 5 min | — | `server.go:94,270` | ACC? | comment: "bounds the tracker against backend bugs" |
| 34 | `BreakerIdleMaxAge` | 1 | h | `server.go:97,280` | ACC? | `TestBreakerRegistryEvictsIdle` pins eviction, not 1 h |
| 35 | `inFlightMemoryWarnThreshold` | 10 000 | entries | RESOURCE/ERROR warning (`server.go:267,286`) | ACC? | |
| 36 | `DrainTimeout` | 60 | s | `server.go:88`, env `FC_DRAIN_TIMEOUT_SECONDS` (60) | ACC? | see §11: the drain aborts rather than finishes |
| 37 | shutdown budget / leader-loss drain / drain tick | 30 s / 30 s / 500 ms | — | `server.go:238,329,355` | ACC? | |
| 38 | `ConfigPollInterval` | **300 s** in code (`server.go:91`); comment on the field says 30 s (`server.go:25-27`) | s | config watcher | **LB (decided: 5 min)** | contradiction noted; owner already chose 5 min |
| 39 | Notifier batch / interval / client timeout | 20 / 10 s / 10 s | — | `server.go:103`, `notification.go:111` | ACC? | CRITICAL flushes immediately |
| 40 | Stall `StallThresholdSeconds` / `ForceNackStalled` / `ForceNackAfterSeconds` / `NackDelaySeconds` / `CheckInterval` | 300 / false / 600 / 30 / 60 s | — | `inflight.go:233-242` | ACC? | duplicated in `common/config.go:118-126` without the interval |
| 41 | Queue-health `CheckInterval` / `BacklogThreshold` / `GrowthThreshold` / `GrowthPeriodsThreshold` | 30 s / 1000 / 100 / 3 | — | `queue_health.go:22-30` | ACC? | warnings go to notifier only |
| 42 | `counterHistoryWindow` / `brokerRefreshInterval` | 30 min / 60 s | — | `broker_stats.go:14,17` | ACC? | 60 s also drives GetQueueAttributes call rate |
| 43 | Metrics `MaxSamples` / `ShortWindow` / `LongWindow` | 10 000 / 5 min / 30 min | — | `metrics.go:22-28` | **LB** (windows are wire-visible as `last5Min`/`last30Min`) | tests |
| 44 | `mediationBucketsSeconds` | .005 .01 .025 .05 .1 .25 .5 1 2.5 5 10 | s | Prometheus histogram (`metrics.go:68`) | **LB** | Prometheus contract; test pins bucket emission |
| 45 | Warning `MaxWarningAge` / `MaxWarnings` / `AutoAcknowledgeAge` / evict fraction / cleanup fallback | 8 h / 1000 / 8 h / 10 % / 5 min | — | `warning.go:26-32,318,300` | ACC? | `TestWarningService_EvictOnCapacity`; auto-ack age == max age makes auto-ack moot |
| 46 | Health `HealthyThreshold` / `WarningThreshold` / `RollingWindow` / `WarningAgeMinutes` / `ConsumerStallThreshold` / `MaxWarningsHealthy` / `MaxWarningsWarning` | 0.90 / 0.70 / 30 min / 30 / 60 s / 5 / 20 | — | `health.go:39-49` | **LB** for 5/20/30 (drive readiness); rest dead (never fed) | `TestHealthService_HealthReport_WarnsOnCount` |
| 47 | Lifecycle `WarningCleanupInterval` / `HealthReportInterval` / `ConsumerHealthInterval` / `ConsumerStallThreshold` | 5 min / 1 min / 30 s / 60 s (constructor fallback **90 s**) | — | `lifecycle.go:38-45,91` | ACC? | 60 vs 90 inconsistency; 60 is effective |
| 48 | ConfigSource client timeout / `MaxAttempts` / `RetryDelay` | 10 s / 12 / 5 s | — | `config_sync.go:44-46` | **LB** | `TestNewConfigSourceParsesCommaSeparated` pins 12 |
| 49 | Election `LockTTLSeconds` / `HeartbeatIntervalSeconds` / loop fallbacks / `Subscribe` buffer | 30 / 10 / 10 s & 30 s / 1 | — | `common/config.go:100-104`, `election.go:106-134,66` | ACC? | |
| 50 | Election lock key | `fc:leader` (lib default) vs `fc:server:leader` (env default) | — | `config.go:102`, `envcfg.go:209` | ACC? | two defaults |
| 51 | ALB `DeregistrationDelaySeconds` / poll | 300 s (when ≤0) / 5 s | — | `traffic.go:161-165` | ACC? | env `FC_ALB_DEREGISTRATION_DELAY_SECONDS` default 0 → 300 |
| 52 | SQS `PendingDeleteTTL` / `DefaultWaitSeconds` / max / vt fallback / receipt-map prune threshold / batch | 15 min / 20 s / 10 / 30 / >1000 / 10 | — | `sqs.go:35,38,155,82,422,325` | **LB** for 20 s & 10 (AWS limits); ACC? for 15 min & 1000 | |
| 53 | Postgres visibility fallback / `Healthy` ping | 30 s / 2 s | — | `postgres.go:124,259` | ACC? | |
| 54 | NATS defaults | batch 10, poll 20 s, ack-wait 120 s, max-deliver 10, max-ack-pending 1000, file, replicas 1, max-age 7 d; connect 10 s, reconnect wait 2 s, max reconnects ∞ | — | `nats.go:62-77,120-124` | ACC? | all URI-overridable |
| 55 | `QueueConfig` defaults | connections 1, visibilityTimeout 120 | — | `common/config.go:68,74` | **LB** | `TestQueueConfigUnmarshal_Defaults` |
| 56 | Default Postgres broker | pool `default` concurrency 4; queue `default` visibility 30; boot ctx 10 s; fallback DB URL `postgresql://postgres@localhost:5432/flowcatalyst` | — | `server/run.go:309-313,361-370` | ACC? | fcdev only |
| 57 | API defaults | in-flight `limit` 100; mediating `limit` 200; `/warnings/old` hours 8; seed count ≤10 000; seed default target `https://localhost:8080/api/test/fast`; `/api/test/slow` 500 ms (cap 30 s); `/api/test/pending` 30 s; `/monitoring/warnings` age 30 min | — | `handlers_dashboard.go:277,339`, `handlers_warnings.go:183`, `handlers_misc.go:179-191`, `handlers_mocks.go:96,134`, `handlers_health.go:165` | ACC? | `TestInFlightMessages_OrderedByElapsedDesc` pins limit *semantics* |
| 58 | Dashboard auto-refresh / pool-detail refresh | 5 s / 5 s | — | `dashboard.html:1528,1371` | ACC? | |
| 59 | Webhook `Verifier.MaxClockSkew` / `Validator` tolerance / future grace | 5 min / 300 s / 60 s | — | `pkg/fcsdk/webhook/webhook.go:33`, `validator.go:29,33` | **LB** | SDK tests |
| 60 | HTTP listener `ReadHeaderTimeout` (api / metrics) / listener shutdown | 10 s / 5 s / 30 s | — | `server/run.go:158,163,194` | ACC? | |
| 61 | Publisher queue selection | queue named `key`, else first queue name in sorted order | — | `manager.go:275-290` | ACC? | `POST /messages` `pool_code` is really a *queue* key |
| 62 | Breaker `BufferSize` floor | 1 | — | `circuit_breaker.go:80-83` | LB (guard) | |
| 63 | `Reserve()` far-future fallback | 1 h | — | `ratelimit.go:76` — no caller | dead | |

Derived backoff tables (all exact; `backoffDelay`, `pool.go:697-710`):

| attempts | error curve (floor 0) | 5xx/transport (floor 30 s) | circuit-open (floor 5 s) | deferred curve (floor 0) |
|---|---|---|---|---|
| 0 | 100 ms | 30 s | 5 s | 5 s |
| 1 | 200 ms | 30 s | 5 s | 10 s |
| 2 | 400 ms | 30 s | 5 s | 20 s |
| 3 | 800 ms | 30 s | 5 s | 40 s |
| 4 | 1.6 s | 30 s | 5 s | 60 s (cap) |
| 5 | 3.2 s | 30 s | 5 s | 60 s |
| 6 | 6.4 s | 30 s | 6.4 s | 60 s |
| 7 | 12.8 s | 30 s | 12.8 s | 60 s |
| 8 | 25.6 s | 30 s | 25.6 s | 60 s |
| 9 | 51.2 s | 51.2 s | 51.2 s | 60 s |
| 10 | 102.4 s | 102.4 s | 102.4 s | 60 s |
| 11 | 204.8 s | 204.8 s | 204.8 s | 60 s |
| ≥12 | 300 s (cap) | 300 s | 300 s | 60 s |

A 429 uses the error curve with floor = `Retry-After` (e.g. 240 → 240 s at
attempt 0, pinned by `retryDelay(0,240)==4m`). A requested `delaySeconds` on
the deferred curve floors but **never lifts the 60 s cap**
(`deferredDelay(0,300)==60s`).

---

## 6. HTTP delivery (mediation) [C]

### 6.1 Request construction — `router/mediator.go:281-313`

| Element | Value |
|---|---|
| Method | `POST` |
| URL | `Message.MediationTarget` verbatim |
| Body | exactly `{"messageId":"<Message.ID>"}` — Go `json.Marshal` of a one-field struct: no whitespace, `"` and `\` escaped, `<>&` HTML-escaped (`TestMediatorPayloadAndSignatureFormat`) |
| `Content-Type` | `application/json` |
| `Accept` | `application/json` |
| `X-FLOWCATALYST-SIGNATURE` | lowercase hex HMAC-SHA256 (64 chars) — only when `signingSecret` present |
| `X-FLOWCATALYST-TIMESTAMP` | `YYYY-MM-DDTHH:MM:SS.mmmZ` (UTC, exactly 3 fractional digits, literal `Z`, 24 chars) — only when `signingSecret` present |
| `Authorization` | `Bearer <authToken>` — when `authToken` present (even empty → `Bearer `) |
| Other headers | Go defaults: `User-Agent: Go-http-client/…`, `Content-Length`, `Accept-Encoding: gzip` |

Header-name casing: the constants are upper-case, but Go's
`Header.Set` canonicalises to `X-Flowcatalyst-Signature` /
`X-Flowcatalyst-Timestamp` on the wire (and HTTP/2 lower-cases). The SDK
`Validator` documents the mixed-case `X-FlowCatalyst-Signature`
(`pkg/fcsdk/webhook/validator.go:24-25`). **Contract: receivers match
case-insensitively; the Java must not depend on any particular casing and
should emit the canonical `X-FlowCatalyst-*` spelling.**

### 6.2 Timeouts and protocol

- Per-request budget: `Client.Timeout` = 15 min (prod) / 30 s (dev) — the
  single source of truth; `ResponseHeaderTimeout` deliberately unset
  (`mediator.go:114-116`).
- Dial 30 s / 10 s; TLS handshake 10 s; keep-alive 30 s; idle conn 90 s;
  idle conns per host 10.
- HTTP/2 via ALPN with `StrictMaxConcurrentStreams=true` in prod; HTTP/1.1
  forced in dev (`mediator.go:178-186`).
- Redirects: Go's default client follows up to 10 redirects; 301/302/303
  turn the POST into a GET without body, 307/308 replay the POST (body is
  replayable). A 3xx that is *not* followed reaches the status switch and
  falls into the "unexpected status" branch (retry as 5xx). **Nothing
  configures or tests this** — see §13.
- The request carries the *consumer's* context: consumer restart,
  reconfigure, leadership loss and shutdown **abort in-flight requests**.

### 6.3 Signing — `router/mediator.go:202-213`

`ts = now().UTC().Format("2006-01-02T15:04:05.000Z")`;
`sig = hex(HMAC-SHA256(key = signingSecret bytes, data = ts ‖ body))`.
The body signed is the *exact* bytes sent. The verifier side
(`pkg/fcsdk/webhook/webhook.go:42-78`, `validator.go:105-135`) recomputes the
same; `Verifier` accepts the ms format or RFC3339Nano and enforces ±5 min
skew; `Validator` also accepts bare Unix seconds, 300 s past tolerance, 60 s
future grace.

`docs/architecture.md:189-190` and `docs/wire-contract.md:191-195` say the
router "signs the payload bytes it receives — it never re-serializes". The
code does **not** forward the queue payload at all; it constructs the
one-field body and signs that (the wire-contract doc's own parenthesis
acknowledges this). The "never re-serialize" sentence is stale. **[C]**: the
body is the constructed one-field object.

### 6.4 Golden vector

The comments reference `tests/golden/webhook/mediation-payload.json` and
`pkg/fcsdk/webhook/testdata/` (`mediator.go:204-205`, `webhook.go:5-6`,
`docs/wire-contract.md:197-201`). **Neither exists in the repository**
(`find` over the tree returns nothing); the Go tests compute the HMAC
dynamically with `time.Now()`. To seed the conformance suite, the following
vector was computed from the pinned formula (verified with both
`openssl dgst -sha256 -hmac` and a Go `hmac`+`json.Marshal` run):

| Input | Value |
|---|---|
| secret | `test-secret-do-not-use-in-prod` |
| timestamp | `2026-01-01T00:00:00.000Z` |
| message id | `msg_TEST123456` |
| body (30 bytes) | `{"messageId":"msg_TEST123456"}` |
| **signature** | `4c53b4f3224c8c870cd4f12e03a6903233153f26777708dd3afab915b2eb3819` |

The inputs mirror `TestMediatorPayloadAndSignatureFormat` and
`TestVerifyMatchesRouterSigner`; only the timestamp is fixed.

### 6.5 Status → outcome → resolution table

"Mediator retries" = in-call attempts (max 3 total, 1 s then 2 s between).
"Pool" = what `processOne` does with the final outcome. "Breaker" = what
`Mediate` records once per call. "Metrics" = pool collector
(`RecordSuccess`/`RecordFailure` add a latency sample and bump totals;
`RecordTransient` adds a non-success sample only; `RecordRateLimited` bumps
the rate-limited counter only).

| Condition | Outcome (status, delay) | Warning | Mediator retries | Breaker | Pool resolution | Metrics | Citation |
|---|---|---|---|---|---|---|---|
| 2xx, body empty / not JSON / no `ack` / `ack:true` | Success (200, 0) | — | no | success | **ACK**, release entry | success(dur) | `mediator.go:332-350`, `pool.go:782-785` |
| 2xx, JSON `{"ack":false[,"delaySeconds":N]}` | Deferred (status, N or 0) | — | no | neither | retry on **deferred curve** floored at N (cap 60 s); entry kept & marked retrying | transient(dur) | `mediator.go:337-348`, `pool.go:810-816`, `TestMediatorAckFalseIsDeferredWithoutInPipelineRetry` |
| 2xx, JSON `{"ack":true,"flushGroup":true[,"delaySeconds":N]}` **(eff2a29)** | Success (200, N or 0) + `FlushGroup` | — | no | success | **ACK this message**, then suppress the rest of its group for N s (clamped 60 s default / 5 min max). Ungrouped → WARN, flush ignored, plain ACK | success(dur) | `mediator.go:356-365`, `pool.go:793-806` |
| *(subsequent messages of a suppressed group)* **(eff2a29)** | **no outcome — no HTTP call made** | — | n/a | n/a | **ACK without delivery**, entry released; checked **before** the rate limiter so no token and no slot is spent | **none** (Q53) | `pool.go:760-771` |
| 400 | ErrorConfig (400) | CONFIGURATION / ERROR "HTTP 400: Bad request" | no | **success** | **ACK** (drop), release | failure(dur) | `mediator.go:352-354` |
| 401 / 403 | ErrorConfig | CONFIGURATION / ERROR "HTTP 40x: Auth error" | no | success | ACK | failure | `:356-358` |
| 404 | ErrorConfig | CONFIGURATION / ERROR "HTTP 404: Not found" | no | success | ACK | failure | `:360-362`, `TestGuardrail_BreakerRecordsSuccessOn4xx` |
| 429 | RateLimited (429, `Retry-After` integer seconds else 30) | — (log) | no | neither | retry on error curve floored at Retry-After; marked retrying | rateLimited | `:364-372`, `pool.go:805-808`, `TestMediatorRateLimitedReadsRetryAfter` |
| 501 | ErrorConfig (501) | CONFIGURATION / **CRITICAL** "HTTP 501: Not implemented" → health Degraded until acked | no | success | ACK | failure | `:374-376`, `TestGuardrail_ConfigErrorsSurfaceAsWarnings` |
| other 4xx (402, 405–428, 430–499) | ErrorConfig (status, "HTTP n: Client error") | — (log) | no | success | ACK | failure | `:378-380` |
| 5xx | ErrorProcess (status, 30) | — (log) | **yes** (3 attempts) | failure | retry, error curve floor 30 s; marked retrying | transient(dur) | `:382-386`, `pool.go:795-799`, `TestMediatorServerErrorRetries` |
| status < 200 (1xx) or un-followed 3xx | ErrorProcess (0, 30) "Unexpected status" | — | yes | failure | as 5xx | transient | `:388-390` |
| transport timeout (`net.Error.Timeout()`) | ErrorConnection (0, 30) "Request timeout" | — (log warn) | yes | failure | retry, floor 30 s | **failure(dur)** (bumps total_failure although retried) | `:314-326`, `pool.go:801-803` |
| other transport error (DNS, refused, TLS, ctx cancelled, redirect loop) | ErrorConnection "Request failed: …" | — (log warn) | yes | failure | retry, floor 30 s | failure(dur) | same |
| request build error (bad URL for `http.NewRequest`) | ErrorConnection | — | yes | failure | retry | failure | `:291-294` |
| target URL has no host / unknown scheme default port | ErrorConfig (0, "invalid mediation target URL") | **none** | no | success | **ACK** (silent drop) | failure | `:307-310`, `TestHostKeyRejectsMalformed` |
| `mediationType != HTTP` | ErrorConfig (0) | none | no | success | ACK (silent drop) | failure | `:282-284` |
| payload marshal error | ErrorConfig (0) | none | no | success | ACK | failure | `:286-289` (unreachable in practice) |
| breaker open | CircuitOpen (0, 5) | — | n/a (no HTTP) | neither | retry, error curve floor 5 s | **none** | `:221-224`, `pool.go:818-822` |
| rate-limiter wait cancelled | (no outcome) | — | — | — | retry floor 5 s | (rateLimited if bucket was empty) | `pool.go:764-775` |
| panic during mediation | (no outcome) | — | — | — | retry after 10 s | — | `pool.go:728-738` |

Effective per-attempt time budget for a dead 5xx target in prod:
3 × (≤15 min) + 1 s + 2 s inside one `Mediate`, then ≥30 s pool backoff,
repeated forever (no max attempts, no dead-letter) until the target answers
2xx/4xx, the message is force-acked, or the process stops. **load-bearing or
accident?** — there is **no terminal give-up** in the router.

### 6.6a Mediation response body — parse order [C] (eff2a29)

The response body is inspected **only** when the status is 2xx, the body is
non-empty, and it parses as JSON. Any other 2xx (empty body, non-JSON, JSON
without these fields) is a plain success.

```
{ "ack": bool?, "delaySeconds": uint32?, "flushGroup": bool? }
```

Evaluated in this order, first match wins — the order **is** the contract:

1. `ack` present and `false` → **Deferred**, `delaySeconds` (or 0) as the
   floor, real status copied onto the outcome.
2. `flushGroup` present and `true` → **Success with `FlushGroup`**,
   `delaySeconds` (when present) sizing the suppression window.
3. otherwise → plain **Success**.

So **`ack:false` beats `flushGroup`**: a target that wants the message back
cannot also discard its group. A body carrying both is treated purely as a
deferral, and the flush is silently dropped — not an error, and not logged.

`delaySeconds` is overloaded across the three cases: a *deferral floor* in
case 1, a *suppression TTL* in case 2, ignored in case 3. Same field, three
meanings, distinguished only by its siblings — worth pinning in the
conformance suite rather than left to a reader of the JSON.

### 6.6b The platform's own `/api/dispatch/process` as a target [C] (5bb46df, f1fc427)

Dispatch jobs are delivered by the router to the platform's own endpoint, so
the platform is a *client* of the §6.6a contract. Its response matrix is
part of the data plane's behaviour and the Java must implement both sides.

Order of checks in `processing.serve`, before any attempt is recorded:

| Condition | Response | Router sees | Job left as |
|---|---|---|---|
| mode is `BLOCK_ON_ERROR`, group non-empty, and `GroupBlocked(group)` is true | `200 {"ack": true, "message": "group blocked"}` | success → **ACK**, message dropped from the broker | reverted to **PENDING** via `Reschedule(id, createdAt, now)` — **no attempt recorded, no retry budget spent** |
| the `GroupBlocked` query errors | `500 {"ack": false, …}` | 5xx → NACK, queue redelivers | untouched (`QUEUED`) |
| the revert itself errors | `500 {"ack": false, …}` | 5xx → NACK, queue redelivers | untouched — **deliberate**: ACKing here would leave the job `QUEUED` with no queue message behind it, recoverable only by the stale-`QUEUED` sweep |
| otherwise | proceeds to `MarkInProgress` and normal delivery | — | `PROCESSING` |

`GroupBlocked` is `EXISTS (SELECT 1 FROM msg_dispatch_jobs WHERE
message_group = $1 AND status IN ('FAILED','ERROR'))`, served by
`idx_dispatch_jobs_blocked_groups` — the same predicate the scheduler
poller uses, which is what makes the two hold-backs agree.

Two invariants worth stating as sentences, because both are easy to lose in
a rewrite:

- **A job is never left `QUEUED` with no queue message behind it.** Every
  failure path on this route NACKs rather than ACKs, precisely to preserve
  this. It is the reason the two error rows above answer 500 rather than
  quietly succeeding.
- **The hold-back costs no retry budget.** `Reschedule` bumps no attempt
  count, so a group blocked for a long time does not exhaust its siblings'
  retries while waiting. This is what makes "re-queue the group on
  resolution" (§2.6) actually deliver them.

Why this exists: the poller stops *queueing* jobs whose group holds a
failed sibling, but messages already in the queue at the moment of failure
would otherwise arrive here and deliver straight past it. Since `f1fc427`
the gate is `BLOCK_ON_ERROR` only — `IMMEDIATE` and `NEXT_ON_ERROR` skip the
query entirely, matching the poller's per-mode filter and the Q1 ruling.

### 6.6 Per-host connection behaviour (effective semantics)

There is **no hard cap on concurrent requests to one origin**. Effective
behaviour: requests to an origin spread over up to 8 independent HTTP/2
connections (1 under HTTP/1.1), a new connection being opened only when every
existing one carries ≥100 in-flight requests; beyond 8×(server stream limit)
requests queue inside the transport (with strict-streams honouring the
server's `SETTINGS_MAX_CONCURRENT_STREAMS`); connections with ≤20 in-flight
that stay idle for 60 s are closed (min 1 kept). Concurrency is *bounded* only
by the pool semaphores. [I] — the Java may achieve the same with its own
client configuration as long as per-pool concurrency is the only cap.

---

## 7. Queue backend contracts

### 7.1 Interfaces as behavioural contracts — `queue/queue.go:46-84`

`Consumer`:

| Method | Contract (as the router relies on it) |
|---|---|
| `Identifier()` | stable string used as `QueueIdentifier` on every polled message and as the key for ack/nack resolution, metrics, Prometheus label (normalised after the last `/`). Java's `RouterManager` resolves ack/nack (and every other `QueueIdentifier` lookup) through an index keyed by `Identifier()` kept alongside its name-keyed config registry, precisely so this holds when `Identifier()` differs from the config queue name (§7.4) — see G10, `docs/go-mirror/2026-09-06-go-fix-list.md` |
| `Poll(ctx, max)` | return ≤`max` messages now owned by this consumer for one visibility window; may block (SQS 20 s, NATS 20 s); return `ErrStopped` (possibly wrapped) forever after `Stop()`; malformed payloads must not be returned (SQS: acked; NATS: termed; Postgres: **poll error**, §7.3) |
| `Ack(ctx, receipt)` | permanently remove the delivery identified by `receipt`; error if unknown (Postgres/NATS) |
| `Nack(ctx, receipt, *delay)` | make the delivery visible again after `delay` seconds (nil → 0); counts as a failure in counters. Router calls it only on the non-retryable control paths (§4.1 state 8) |
| `Defer(ctx, receipt, *delay)` | same as Nack but counted as deferred; **router never calls it** |
| `ExtendVisibility(ctx, receipt, secs)` | push out the visibility window; **router never calls it** — a delivery longer than the queue's visibility timeout *will* be redelivered (and deduped/handle-swapped by the tracker) |
| `Healthy()` | liveness; **router never calls it** |
| `Stop()` | terminal; subsequent `Poll` → `ErrStopped` |
| `Metrics(ctx)` | broker-side pending/in-flight + counters; may round-trip; nil/err tolerated |
| `Counters()` | process-local counters, no round-trip; nil tolerated |

`Publisher`: `Identifier()`, `Publish(ctx, Message) → brokerID`,
`PublishBatch(ctx, []Message) → ids` (partial results on error). `Embedded` =
Consumer + Publisher + `InitSchema(ctx)`; used by the server bootstrap to
create the Postgres table (`server/run.go:338-355`).

Scheme resolution (`queue.go:138-170`): backend key = text before `://`
(whole string if none); `http`/`https` whose host starts with `sqs.` or
`sqs-fips.` and contains `.amazonaws.` → `sqs` (`queue_test.go:32-51`);
unknown → error `no consumer registered for scheme "<x>"`. Registered schemes
in `fc-server`: `sqs`, `postgres`, `nats` (`server/subsystems.go:38-41`).
`docs/architecture.md:216-244` lists `sqlite` and `amqp` too — **not present
in the tree**.

### 7.2 SQS — `queue/sqs/sqs.go`

| Aspect | Behaviour |
|---|---|
| Build | AWS region taken from the queue URL host (`sqs.<region>.amazonaws.com`), else SDK default chain; `Identifier()` = `queueName` or last URL segment; visibility = cfg or 30 when 0; long-poll 20 s |
| Poll | `ReceiveMessage(Max=min(n,10), VisibilityTimeout, WaitTimeSeconds=20, all system & message attributes)`; empty → `nil,nil`. Per message: if `MessageId` is in the pending-delete map (acked within 15 min) → `DeleteMessage` immediately, skip; parse body JSON → `Message` (malformed/empty → `Ack` it, skip); remember `receipt → MessageId`; return `{ReceiptHandle, BrokerMessageID=MessageId, QueueIdentifier=name}`; `polled += n` |
| Ack | forget `receipt`, `pendingDelete[MessageId]=now`; `DeleteMessage(receipt)`; `acked++` only on success |
| Nack / Defer | **no-op** (counters only): the message stays invisible until its visibility timeout lapses, then is redelivered and deduped by the tracker (`sqs.go:256-276`). `delay` ignored by design |
| ExtendVisibility | `ChangeMessageVisibility(secs)` (never called) |
| Publish | `SendMessage(body=JSON(Message), MessageGroupId if set)`; **no `MessageDeduplicationId`** (a FIFO queue must have content-based dedup on, or publishes fail); returns `MessageId` |
| PublishBatch | chunks of 10, entry ids = index; returns successes; error on any failed entry |
| Maps | `pendingDelete` pruned of entries >15 min on every non-empty poll; `receiptToMessageID` pruned of entries >15 min only when it exceeds 1000 entries |
| Metrics | `GetQueueAttributes(ApproximateNumberOfMessages, ApproximateNumberOfMessagesNotVisible)` + counters |
| Stop | flag only (no client close) |

### 7.3 Postgres — `queue/postgres/postgres.go`

The Java backend now connects from the queue URI like Go's `pgxpool.New(ctx,
cfg.URI)` below (`QueueFactory#createPostgres`): a URI carrying its own
connection opens a dedicated pool, falling back to the platform's shared
pool only when the URI carries none or names that same database.

DDL (`InitSchema`, idempotent, matches the pre-existing layout):

```sql
CREATE TABLE IF NOT EXISTS queue_messages (
    id               TEXT NOT NULL,
    queue_name       TEXT NOT NULL,
    message_group_id TEXT,
    receipt_handle   TEXT,
    visible_at       BIGINT NOT NULL,   -- unix seconds
    payload          TEXT NOT NULL,     -- JSON common.Message
    created_at       BIGINT NOT NULL,   -- unix seconds
    receive_count    INTEGER DEFAULT 0,
    PRIMARY KEY (queue_name, id)
);
CREATE INDEX IF NOT EXISTS idx_queue_visible
    ON queue_messages (queue_name, visible_at, message_group_id);
```

| Aspect | Behaviour |
|---|---|
| Identity | `Identifier()` = `queueName`; one `pgxpool` per consumer and another per publisher |
| Poll (claim) | `now`, `newVisibleAt = now + visibility` (cfg seconds, ≤0 → 30 s), one `pollUUID` per call. Claim set = rows of this queue with `visible_at <= now` for which **no other row of the same group key `COALESCE(message_group_id, id)` is visible (`visible_at <= now`) and earlier by `(created_at, id)`**, ordered by `(created_at, id)`, `LIMIT n`, `FOR UPDATE SKIP LOCKED`; claimed rows get `receipt_handle = pollUUID||':'||id`, `visible_at = newVisibleAt`, `receive_count+1`; returns `(id, payload)` → `{ReceiptHandle=pollUUID:id, BrokerMessageID=id, QueueIdentifier=name}`. A malformed payload **fails the whole poll** (`postgres.go:171-173`) — the row stays claimed until visibility lapses, then fails again (poison). **load-bearing or accident?** |
| Ordering consequence | at most one message per group **per poll**; a claimed head (invisible) does **not** block its successors on the *next* poll, so cross-poll group ordering is not enforced by the broker |
| Ack | `DELETE … WHERE receipt_handle=$1 AND queue_name=$2`; 0 rows → error "receipt handle not found" |
| Nack / Defer | `receipt_handle=NULL, visible_at=now+delay` (nil → 0) |
| ExtendVisibility | `visible_at = now + secs` (never called) |
| Publish | `INSERT … ON CONFLICT (queue_name,id) DO NOTHING` with `visible_at=created_at=now` (seconds); returns `m.ID` (even when the row already existed) |
| PublishBatch | loop of `Publish`; error aborts and returns nil ids |
| Metrics | pending = `receipt_handle IS NULL AND visible_at <= now`; in-flight = `receipt_handle IS NOT NULL` (an expired claim counts as in-flight; a delayed row counts as neither) |
| Healthy | `Ping` with 2 s timeout (never called) |
| Stop | set stopped; close the pool |
| Dedup interplay | broker id == app id → redeliveries always classify as `Redelivery`; `ExternalRequeue` impossible |

Default-broker bootstrap (`server/run.go:305-334,357-386`): when
`FLOWCATALYST_CONFIG_URL` is empty and `FC_DEFAULT_BROKER=postgres`, the
server normalises the DB URL to `postgres://…`, runs `InitSchema` via a
throw-away consumer, and calls `Manager.Reconfigure({pool "default"
concurrency 4; queue "default" visibility 30})` **before** `Run`. The
scheduler publishes to the same synthesised queue (`server/subsystems.go:96-110`).

### 7.4 NATS JetStream — `queue/nats/nats.go`

| Aspect | Behaviour |
|---|---|
| URI | `nats://host:port[,host2…]?stream=&consumer=&subject=&max-messages=&poll-timeout-ms=&ack-wait-secs=&max-deliver=&max-ack-pending=&storage=file|memory&replicas=&max-age-days=`; defaults stream `FLOWCATALYST`, consumer `fc-router`, subject `flowcatalyst.>`, 10, 20 s, 120 s, 10, 1000, file, 1, 7 d |
| Provisioning | `CreateOrUpdateStream` (WorkQueue retention, subjects=[subject], storage, replicas, max age); `CreateOrUpdateConsumer` (durable=name, AckWait, MaxDeliver, MaxAckPending, FilterSubject) |
| Identity | `Identifier()` = `<stream>/<consumer>` |
| Poll | `Fetch(min(n, max-messages), MaxWait=poll-timeout)`; per msg: metadata error → `Term`; malformed JSON → `Term`; receipt `<stream>:<streamSeq>`; broker id `<streamSeq>:<consumerSeq>`; msg kept in a pending map by receipt |
| Poll (Java, listener model — owner ruling 2026-09-07, superseding two earlier revisions) | NATS is a genuine subscription, not a poller — polling (`Poll(ctx, max)`, a request-response shape) is an SQS/Postgres limitation, not the design NATS itself calls for. `NatsQueue` opens **one** standing `MessageConsumer` for the queue's whole life (`ConsumerContext#consume(ConsumeOptions, MessageHandler)`, `batchSize=max-messages`): the NATS client keeps a pull request continuously outstanding against the server and hands each message to a handler on its own delivery thread the moment it arrives. The handler's entire job is `buffer.put(msg)` — a `BlockingQueue` bounded at `max-messages` — so `put` blocking when the buffer is full **is** the back-pressure that stops the client asking for more. `poll(max)` is `buffer.take()` (untimed — free on a virtual thread) for the first message, then `drainTo` for up to `max-1` more already sitting in the buffer; there is no poll cycle to time and no `expiresIn` to race, so the two throughput defects the earlier revisions fixed (ephemeral-subscription churn; a no-wait-first fetch still capable of a multi-second tail — see G13's history) cannot recur, because the shape that caused them — this class issuing its own timed pull requests — no longer exists. `close()` interrupts a `poll` parked in `take()` directly (`waitingThread`), rather than relying on an external caller to interrupt the right thread. `poll-timeout-ms` on the URI is **parsed but unused** for NATS — documented on `NatsQueueUri`, never removed as a parameter. Confirmed by bench (`bench/router`, NATS JetStream, 50,000 messages, pool concurrency 256): QUEUES=1 7,671/s (6.4 s), QUEUES=4 6,401/s (6.6 s), QUEUES=8 at 2 CPU 3,959/s (13.3 s) — all pass; QUEUES=8 at 1 CPU 3,338/s (15.08 s, just over the 15 s bar) — a per-second timeline shows a multi-second stall late in the drain (~47.4k/50k, both 1 and 2 CPU) not yet root-caused (candidate: `ConsumeOptions`'s unset `expiresIn` defaulting to 30 s per internal refill cycle) — see G13, `docs/go-mirror/2026-09-06-go-fix-list.md`. |
| Ack / Nack / Defer | pop pending by receipt (unknown → error); `Ack()`; `NakWithDelay(delay)` if >0 else `Nak()` |
| ExtendVisibility | `InProgress()` (never called) |
| Publish | subject = filter with trailing `.>`/`.*` replaced by `.<poolCode>` (or `.default`); returns stream sequence as decimal |
| Stop | running=false, pending cleared, connection closed |
| Metrics | `NumPending`, `NumAckPending` + counters |
| Ignored config | `QueueConfig.VisibilityTimeout` (ack-wait from URI), `Connections` |
| Dedup interplay | redelivery has a new consumer sequence → different broker id → classified `ExternalRequeue` → the router **acks (consumes) the redelivered copy** while the original is still in flight; if the original later fails, the message is gone from the WorkQueue stream. Suspected defect — §13 |
| Redelivery cap | `MaxDeliver=10` → after 10 deliveries JetStream stops redelivering (message effectively dead-lettered by the broker, silently) |

---

## 8. Config sync [C]

### 8.1 Sources and fetch — `router/config_sync.go`

- `FLOWCATALYST_CONFIG_URL` is split on `,`, each part trimmed, empties
  dropped (`TestNewConfigSourceParsesCommaSeparated`).
- Every URL is fetched **in parallel**; each URL independently retries up to
  **12 attempts with 5 s between** (`fetchWithRetry`); a single attempt is a
  `GET` with a 10 s client timeout; any status ≥300 or JSON decode failure is
  an attempt failure. Worst case one `Fetch` takes ≈ 12×10 s + 11×5 s ≈ 3 min.
- Successes are collected **in URL order**; if **all** fail → error; a
  partial failure is logged per URL and tolerated.
- Merge (`mergeConfigs`): a single source passes through unchanged; with
  several, pools are keyed by `code`, queues by `queueUri`; the **first**
  source to define a key wins; a later duplicate with *different* values
  logs a warning ("duplicate pool/queue with conflicting values — keeping
  first") **to slog only**, not to the warning store; identical duplicates are
  silently dropped (`TestMergeConfigsUnionFirstWins`).
- Change detection: the merged config is JSON-marshalled and compared
  byte-wise with the previous merge; equal → `ErrUnchanged` (the watcher
  skips `Reconfigure`). The first fetch is never "unchanged". A source that
  drops out transiently changes the merge → its pools/queues are **removed**
  (consumers stopped, pools stopped/flushed) and re-added when it recovers.
  **load-bearing or accident?**

### 8.2 Applying — `Watch` + `Manager.Reconfigure` (`config_sync.go:218-247`, `manager.go:530-601`)

- `Watch` applies immediately on start and then on every tick of the poll
  interval (300 s; field comment says 30 s; owner decided 5 min).
- `Reconfigure`: build the wanted pool set (+ `DEFAULT-POOL` concurrency 20
  if absent) and wanted queue set (by `queueName`). Pools not wanted →
  `Stop()` (flush) and forget; existing → `SetRateLimit(rate or 0)` always,
  `UpdateConcurrency` only if the new concurrency is non-zero; new →
  created. Consumers: not wanted **or any field differs** (`QueueConfig`
  struct equality incl. `connections`) → cancel its context, `Stop()` the
  consumer, forget; new → build + start a poll loop. A consumer build error
  **aborts the reconfigure mid-way** (earlier changes stay applied, later
  queues are not started) and is logged by the watcher.
- Because stopping a consumer cancels its context, **a queue config change
  aborts that queue's in-flight deliveries** (§3.8) and parks its ordered
  groups (resumed by redelivery).
- Hot updates made via the API persist until the *remote config actually
  changes* (unchanged fetches do not reconfigure). When it changes, pool
  values are reapplied from the config.

### 8.3 `POST /config/reload` — `router/api/handlers_misc.go:150-161`, `router/server.go:160-172`

`Reload` = `Fetch` → unchanged → `200 {"success":true}`; changed →
`Reconfigure` → `200 {"success":true}`; fetch/reconfigure error →
`500 "reload: <err>"`. With **no config URL** (default-broker mode) the server
still wires a reloader, whose `Reload` returns `"router has no config source
configured"` → **500**; the handler's friendly `{"success":true,"note":"config
watcher polls automatically"}` branch is only reached when no reloader is
wired at all (never via `FromServer`). **load-bearing or accident?**

### 8.4 Default in-process broker

See §7.3 bootstrap. In this mode there is no watcher (`startPools` logs
"using bootstrapped pools (no hot reload)" when pools exist, else "no pools
will start"; `server.go:194-210`).

---

## 9. Observability

### 9.1 Monitoring API — every route [C]

Mounted under `<prefix>` (default `/router`) on the API port
(`server/run.go:207-226`); all JSON unless noted; BasicAuth applies except
for the public paths (§9.7). "Mutating" = changes router state.

| Method | Path | Mutating | Handler → response | Notes |
|---|---|---|---|---|
| GET | `/health`, `/q/health` | — | `SimpleHealthResponse {status, version, active_warnings, critical_warnings}` (snake) | status from `HealthReport` (§9.4) |
| GET | `/health/live` | — | 200 `{"status":"LIVE"}` always | |
| GET | `/health/ready`, `/health/startup` | — | 200 `{"status":"READY"}` or **503** `{"status":"NOT_READY"}` iff report is `Degraded` | |
| GET | `/monitoring` | — | `{status, version, health_report{…snake…}, pool_stats[{pool_code, concurrency, active_workers, queue_size, queue_capacity, message_group_count, rate_limit_per_minute?, is_rate_limited, metrics{camelCase}}], active_warnings, critical_warnings}` | `active_warnings` here = **all** unacknowledged (any age); inside `health_report` it is unacked ≤30 min |
| GET | `/monitoring/health` | — | `{status, timestamp (RFC3339Nano), uptimeMillis, details{totalQueues, healthyQueues, totalPools, healthyPools, activeWarnings, criticalWarnings, circuitBreakersOpen, degradationReason?}}` | `totalQueues/healthyQueues` derive from the never-fed consumer model → always 0 |
| GET | `/monitoring/pools` | — | `[WirePoolStats]` (snake outer) | |
| GET | `/monitoring/warnings` | — | `[WireWarning]` unacked ≤30 min, newest first | |
| GET | `/monitoring/consumer-health` | — | `{currentTimeMs, currentTime, consumers{ id → {mapKey, queueIdentifier, consumerQueueIdentifier, isHealthy, lastPollTimeMs, lastPollTime ("never"), timeSinceLastPollMs (-1), timeSinceLastPollSeconds (-1), isRunning} }}` | lists only **stalled** consumers of the (never-fed) HealthService → always `{}` |
| GET | `/monitoring/pool-stats?time_window=5min|5m|30min|30m|all` | — | `{ poolCode → {poolCode, totalProcessed, totalSucceeded, totalFailed, totalRateLimited, successRate, activeWorkers, availablePermits, maxConcurrency, queueSize, maxQueueCapacity, averageProcessingTimeMs} }` | window picks `last5Min`/`last30Min`/all-time; unknown → all-time |
| GET | `/monitoring/queue-stats?time_window=…&refresh=true` | refresh triggers broker attribute fetch | `{ queueId → {name, totalMessages(=polled), totalConsumed(=acked), totalFailed(=nacked), totalDeferred, successRate(acked/(acked+nacked), 1.0 if none), currentSize(=pending+inFlight), throughput (always 0.0), pendingMessages, messagesNotVisible(=inFlight)} }` | counters windowed as deltas vs the 30-min history (§9.3); no BrokerStats → `{}` |
| GET | `/monitoring/queues` | — | `[{queue_identifier, pending_messages, in_flight_messages}]` | |
| GET | `/monitoring/circuit-breakers` | — | `{ target → {name, state (CLOSED|OPEN|HALFOPEN), successfulCalls, failedCalls, rejectedCalls (always 0), failureRate (failures/(s+f)), bufferedCalls (=recent window failures), bufferSize (always 0)} }` | |
| GET | `/monitoring/circuit-breakers/{name}/state` | — | `{name, state, successes, failures, recentFailures}`; 404 if unknown; 503 if no registry | `{name}` is the raw URL key — URL-encode it |
| POST | `/monitoring/circuit-breakers/{name}/reset` | **yes** | `{reset:true, name}`; 404 unknown | `Reset()` clears state, window, cumulative counters |
| POST | `/monitoring/circuit-breakers/reset-all` | **yes** | `{reset: n}` | |
| GET | `/monitoring/in-flight-messages?limit=100&messageId=&poolCode=` | — | `[{messageId, brokerMessageId (null when blank), queueId, poolCode, elapsedTimeMs, addedToInPipelineAt, messageGroup, attempts}]` sorted elapsed **desc** then truncated | `messageId` substring match (case-insens.), `poolCode` exact (case-insens.); `limit≤0 → 100` (`TestInFlightMessages_OrderedByElapsedDesc`) |
| GET | `/monitoring/in-flight-messages/check?messageId=` | — | `{messageId, inPipeline, poolCode?, queueId?}` | |
| POST | `/monitoring/in-flight-messages/check-batch` `{messageIds:[…]}` | — | `{ id → bool }` | |
| GET | `/monitoring/in-flight-messages/detail?messageId=` | — | `{messageId, inPipeline, status (MEDIATING|RETRY_BACKOFF|TRACKED_IDLE), brokerMessageId?, queueId, poolCode, messageGroup, attempts, elapsedTimeMs, addedToInPipelineAt, lastSeenAt, lastSeenElapsedMs, mediationTarget?, mediatingElapsedMs?}`; miss → `{messageId, inPipeline:false}` (200, not 404) | status rule: in mediating set → MEDIATING; else attempts>0 → RETRY_BACKOFF; else TRACKED_IDLE (`TestInFlightDetail_*`) |
| POST | `/monitoring/in-flight-messages/{messageId}/ack` | **yes** (force-ack) | `{messageId, removed:true, brokerAcked, brokerAckError?, queueId, poolCode, elapsedTimeMs (whole seconds ×1000), wasMediating}`; 404 not tracked; 503 no acker | broker delete best-effort with the freshest handle; tracker entry always released; a running attempt is not aborted (`TestInFlightForceAck_*`, `TestManagerForceAckInFlight`) |
| GET | `/monitoring/mediating?limit=200&poolCode=` | — | `[{messageId, poolCode, group, queue, target, attempts, elapsedTimeMs}]` sorted elapsed desc | `TestMediating_OrderedByElapsedAndFiltered` |
| PUT | `/monitoring/pools/{poolCode}` `{concurrency?, rate_limit_per_minute?}` | **yes** | `{success:true, pool_code, new_config{concurrency?, rate_limit_per_minute?}}`; 404 "pool not found or update rejected"; 503 no updater | `concurrency` absent or 0 → unchanged; `rate_limit_per_minute` present → applied (0 → unlimited), absent → unchanged (`TestPoolUpdate*`) |
| POST | `/monitoring/broker-stats/refresh` | yes (cache) | `{refreshed:true, ageSeconds (≥0)}` | |
| GET | `/monitoring/warnings/unacknowledged` | — | `[WireWarning]` | alias |
| GET | `/monitoring/warnings/severity/{severity}` | — | `[WireWarning]` (`WARN` accepted as alias of `WARNING`; case-insens.) | alias |
| POST | `/monitoring/warnings/{id}/acknowledge` | yes | `{acknowledged:true}`; 404 | alias of `/warnings/{id}/acknowledge` |
| GET | `/monitoring/standby-status` | — | `{enabled, is_leader, instance_id}` | `instance_id` = lock key or `"default"` (not the UUID) |
| GET | `/monitoring/traffic-status` | — | `{enabled, mode ("alb-target-group"|"disabled"), targetGroupArn?, registered, lastChangedAt? (ms format), lastError?}` | |
| GET | `/monitoring/stream-health`, `…/live`, `…/ready` | — | pass-through to the stream processor; without provider: `{enabled:false,status:"NOT_CONFIGURED",detail}` / 200 `{status:"NOT_CONFIGURED"}` | stream spec |
| GET | `/warnings?severity=&category=&acknowledged=false` | — | `[WireWarning]` newest first (filters case-insens.; `acknowledged=false` → unacked only, anything else → all) | |
| DELETE | `/warnings` | yes | `{cleared:n}` | |
| POST | `/warnings/{id}/acknowledge` | yes | `{acknowledged:true}`; 404 | |
| POST | `/warnings/acknowledge-all` | yes | `{acknowledged:n}` | |
| GET | `/warnings/critical` | — | `[WireWarning]` (acked or not) | |
| GET | `/warnings/unacknowledged` | — | `[WireWarning]` | |
| GET | `/warnings/severity/{severity}` | — | `[WireWarning]` | |
| DELETE | `/warnings/old?hours=8` | yes | `{cleared:n}` (`hours≤0 → 8`) | |
| GET | `/api/config` | — | `{version, warnings_total, warnings_critical}` — never secrets | |
| POST | `/config/reload` | **yes** | §8.3 | |
| POST | `/messages` `{id?, pool_code, mediation_type?, mediation_target, message_group_id?, high_priority?, dispatch_mode?, auth_token?, signing_secret?}` | **yes** (publishes) | 201 `{message_id, broker_message_id, pool_code, queue_identifier}`; 422 on missing required (huma schema); 502 on publisher/publish error; 503 no publisher | `pool_code` selects the *queue* named like it, else the alphabetically-first queue (§5 #61); id auto-UUID; defaults HTTP / IMMEDIATE |
| POST | `/api/seed/messages` `{pool_code, mediation_target?, count 1..10000}` | yes | `{pool_code, queue_identifier, published}` | IMMEDIATE messages, default target `https://localhost:8080/api/test/fast` |
| POST | `/api/test/fast`, `/success` | counters | 200 `{ok:true, endpoint}` | mock targets, always on |
| POST | `/api/test/slow?delay_ms=` | counters | 200 after delay (default 500 ms, cap 30 s) | |
| POST | `/api/test/faulty` | counters | 50 % 200 / 50 % 500 | |
| POST | `/api/test/fail`, `/server-error` | counters | 500 | |
| POST | `/api/test/client-error` | counters | 400 | |
| POST | `/api/test/pending` | counters | 200 after 30 s | |
| GET | `/api/test/stats` | — | `{fast, slow, faulty, faulty_success, faulty_fail, fail, success, pending, client_error, server_error}` | |
| POST | `/api/test/stats/reset` | yes | `{reset:true}` | |
| * | `/api/benchmark/process`, `/process-slow`, `/stats`, `/reset` | — | aliases of the above | |
| GET | `/monitoring/dashboard`, `/dashboard.html` | — | `text/html` embedded dashboard with `window.__API_BASE__="<prefix>"` injected | not a huma op |
| GET | `/metrics` (under prefix) | — | Prometheus text | §9.3 |
| GET | `/openapi.json`, `/docs` | — | huma OpenAPI + docs UI (under prefix) | public |

Provider-absent degradation is uniform: 503 `"<name> not configured"` for
mutations/lookups, empty payload for lists (`api.go:146-173,385-392`).

### 9.2 Prometheus [C] — `router/api/prometheus.go`

Fresh snapshot per scrape; no process-level default metrics. Names, labels
and types:

| Series | Type | Labels | Value |
|---|---|---|---|
| `fc_pool_queue_size` | gauge | `pool` | buffered (pre-dispatch) messages |
| `fc_pool_active_workers` | gauge | `pool` | workers inside process-one |
| `fc_pool_message_groups` | gauge | `pool` | groups holding buffered work |
| `fc_messages_processed_total` | counter | `pool`, `success="true"|"false"` | `TotalSuccess` / `TotalFailure` |
| `fc_rate_limit_exceeded_total` | counter | `pool` | `TotalRateLimited` |
| `fc_mediation_duration_seconds` | histogram | `pool` | cumulative; buckets §5 #44 + `+Inf`; `_sum`, `_count` |
| `fc_in_pipeline_messages` | gauge | — | tracker size |
| `fc_queue_pending_messages`, `fc_queue_in_flight_messages` | gauge | `queue` (id after last `/`) | cached broker attrs |
| `fc_consumer_messages_received_total` | counter | `consumer` | `TotalPolled` |
| `fc_queue_messages_total` | counter | `queue`, `outcome="acked"|"nacked"|"deferred"` | counters |
| `fc_circuit_breaker_open` | gauge | `target` (full URL) | 1 iff Open |
| `fc_circuit_breaker_calls_total` | counter | `target`, `outcome="success"|"failure"` | cumulative |

Pinned by `TestPrometheusHandler_*`. The comment at `prometheus.go:38-44`
lists series the *established contract* defines but this collector does not
emit (`fc_messages_submitted_total`, `fc_messages_rejected_total{reason}`,
`fc_consumer_polls_total`, `fc_consumer_errors_total{type}`, a `result`
label, `flowcatalyst_broker_*`). **load-bearing or accident?** — gap
acknowledged in code.

### 9.3 Broker-stats cache — `router/broker_stats.go`

Background refresh at start and every 60 s (+ on demand): fetch
`Consumer.Metrics` for every consumer (broker round-trip), overwrite the
attribute cache, append a counter snapshot to a 30-min history (older
entries trimmed). `GetWindowed(w)`: live counters from `Consumer.Counters()`
with cached pending/in-flight overlaid; when `w>0` counters are replaced by
`live − baseline` where baseline is the newest snapshot at or before `now−w`
(oldest if history is shorter), saturating at 0; a queue with no baseline is
zeroed (`broker_stats_test.go`). `AgeSeconds()` = -1 before the first
refresh.

### 9.4 Health semantics — `router/health.go:184-259`, `router/api/handlers_health.go`

`HealthReport` inputs: pool success rates (from `RecordPoolResult` — **never
called in production**, so every pool counts as healthy "no data"), stalled
consumers (from `SetConsumerRunning`/`RecordConsumerPoll` — **never called**,
so 0 consumers, 0 stalled), `activeWarnings` = unacknowledged warnings ≤30
min old, `criticalWarnings` = unacknowledged CRITICAL (any age).

Effective status rule today:

| Status | Condition |
|---|---|
| `Degraded` | any unacked CRITICAL warning, **or** active warnings > 20 |
| `Warning` | active warnings > 5 |
| `Healthy` | otherwise |

(The pool/consumer clauses — "all pools unhealthy", "all consumers stalled",
"some pool/consumer unhealthy" — exist but can never fire.) `issues[]` lists
"N critical warnings" when applicable. Readiness = not Degraded. Only
`CRITICAL` warnings come from the mediator's 501 path today, so **an
unacknowledged 501 from any target makes the whole router NOT_READY** until
acknowledged or aged out (auto-ack/clear at 8 h). **load-bearing or
accident?**

### 9.5 Warnings store & notifier

Store (`router/warning.go`): bounded map (1000; at capacity the oldest 10 %
by `createdAt` are evicted before insert); `Add` forwards to the notifier if
attached; cleanup every 5 min auto-acks entries older than 8 h then deletes
entries older than 8 h; `Active(maxAgeMinutes)` = unacked ∧ age ≤ n.

Notifier (`router/notification.go`): no-op when `FC_NOTIFY_WEBHOOK_URL` is
empty. Queue warnings; flush on every 10 s tick, when the queue reaches 20,
or **immediately** when a CRITICAL is added; optional `minSeverity` filter
(never set in production). Flush = `POST {"warnings":[Warning…]}` (camelCase
`Warning` shape, §2.7) with `Content-Type: application/json`, 10 s timeout;
failures logged, batch dropped. On stop/ctx-cancel a final flush is
attempted — with the already-cancelled context on the ctx path, so it fails
(§11).

Routing of warning sources:

| Source | Goes to store (`/warnings`, health) | Goes to notifier |
|---|---|---|
| mediator 400/401/403/404/501 | yes | yes (via store) |
| unknown pool code, all pools full, consumer restart, tracker >10 000 | yes | yes (via store) |
| stall detector, queue-health monitor | **no** | yes (direct `Notifier.Add`, `inflight.go:300-307`, `queue_health.go:92-99,119-125`) |

**load-bearing or accident?** — STALL and QUEUE_HEALTH never appear on
`/warnings` and never affect health.

### 9.6 Dashboard — `router/api/dashboard.html`, `dashboard.go`

Single embedded HTML page served by the router with the mount prefix
injected (`window.__API_BASE__`) so nested mounting works
(`TestDashboardHTML_*`). Behaviour: BasicAuth login prompt storing
`Basic` credentials in `localStorage["flowcatalyst_auth"]` and sending them
as `Authorization` on every fetch; tabs **Queues**, **Pools**, **Warnings**,
**In-Flight**, **Mediating**; time-window selector (`time_window`); auto-
refresh every 5 s of `/monitoring/health`, `/monitoring/queue-stats`
(`refresh=true` only on the manual refresh button), `/monitoring/pool-stats`,
`/monitoring/warnings`, `/monitoring/circuit-breakers`; per-pool detail view
polling `/monitoring/in-flight-messages?limit=500&poolCode=` every 5 s;
in-flight detail and force-ack via `/monitoring/in-flight-messages/detail`
and `POST …/{id}/ack`; mediating list via `/monitoring/mediating`. It is a
*consumer* of the API above; it carries no behaviour of its own that the
Java must reproduce beyond serving the same page (or an equivalent) with the
prefix substitution.

### 9.7 BasicAuth — `router/api/auth.go`, `server/run.go:228-243`

- Credentials from `FC_ROUTER_AUTH_USER`/`FC_ROUTER_AUTH_PASS` (aliases
  `AUTH_BASIC_USERNAME`/`AUTH_BASIC_PASSWORD`); `AUTH_MODE=NONE` (case-insens.)
  forces off; empty username → middleware disabled.
- Realm `FlowCatalyst Router`; failure → 401 with
  `WWW-Authenticate: Basic realm="…", charset="UTF-8"`; constant-time compare.
- Public (no auth) paths: `/health`, `/q/health`, `/health/live`,
  `/health/ready`, `/health/startup`, `/q/health/live`, `/q/health/ready`,
  `/metrics`, `/q/metrics`, `/ready`, `/openapi*.{json,yaml}`, and anything
  under `/docs` (`TestBasicAuth_PublicPathsBypass`, `TestIsPublicPath`).
- **Suspected defect**: the middleware tests `r.URL.Path`, which chi does
  **not** rewrite for a mounted sub-router (`chi/v5 mux.go`: routing uses
  `rctx.RoutePath`; `r.URL.Path` keeps the full path). Under the default
  `/router` prefix the path seen is `/router/health/live`, which is not in
  the public set, so **probes and `/metrics` require BasicAuth when
  credentials are configured**. The tests mount at root and so do not catch
  it. §13.

---

## 10. Leadership / HA / ALB

### 10.1 Redis election [C on Redis] — `standby/election.go`

| Aspect | Behaviour |
|---|---|
| Key / value / TTL | `LockKey` (router: `FC_STANDBY_LOCK_KEY`, default `fc:server:leader`) / `InstanceID` (fresh UUID per process) / 30 s |
| Acquire | `SET key id NX EX 30` → success ⇒ leader |
| Refresh | if `SET NX` fails: Lua `if GET key == id then EXPIRE key 30` → `1` ⇒ still leader, `0` ⇒ follower |
| Cadence | immediately on start, then every 10 s |
| Any Redis error | demote to follower immediately (fail-safe toward "not leader") |
| Start | `PING` first; failure → `Run` returns the error (router does not start) |
| Stop | close loop; if leader, Lua `if GET key == id then DEL key`; close client |
| Notifications | `IsLeader()` atomic; `Subscribe()` channel of `{IsLeader, At}` (buffer 1, older events dropped if the receiver lags) |
| Disabled | `Enabled=false` → leader immediately, no Redis |

Worst-case failover: a dead leader's key expires after ≤30 s; a follower notices
on its next 10 s tick → ≤40 s without a leader. Worst-case split-brain window
on a network partition: the old leader stays "leader" until its next failed
refresh (≤10 s) while the new one may acquire after TTL expiry — by
construction at most one holds the key, but the old leader's in-flight
deliveries continue until it observes the loss.

### 10.2 Leader-gated behaviour — `router/server.go:212-224,301-351`

| Runs regardless of leadership | Runs only while leader |
|---|---|
| HTTP API, dashboard, Prometheus; notifier; stall detector; queue-health monitor; reaper; broker-stats refresh; lifecycle loops; election loop | config watcher (and therefore pools + consumers); ALB registration |

Follower: serves the API with empty pool/consumer sets (`/monitoring/standby-status`
→ `is_leader:false`); `POST /messages` works if queues are registered (they
are not on a follower → 502 "no queue registered"); `POST /config/reload`
still fetches and **would reconfigure** (the reloader is not leadership-gated)
— **suspected gap** (§13).

Default-broker mode + standby: the default pool and consumer are created in
`newRouterServer` **before** and independent of the election, so a follower
consumes too, and a leadership loss calls `Manager.Shutdown` which removes
them with nothing to recreate them on regain (`startPools` with no config
source only logs). §13.

### 10.3 ALB target group — `router/traffic.go`

| Event | Action |
|---|---|
| Construction | disabled unless `FC_ALB_ENABLED` **and** `FC_ALB_TARGET_GROUP_ARN` **and** `FC_ALB_TARGET_ID`/`FC_ALB_INSTANCE_IP` (else warns and disables); builds an ELBv2 client (`FC_ALB_REGION` or SDK default chain) |
| Register (leader gain / non-standby start) | `RegisterTargets(id=IP, port=FC_ALB_TARGET_PORT default 8080)`; idempotent; records `registered=true`, `lastChange`, clears `lastError`; failure stores `lastError` and is logged (router continues) |
| Deregister (leader loss / shutdown) | `DeregisterTargets`; then poll `DescribeTargetHealth` every **5 s** until the target is no longer `draining`, or `FC_ALB_DEREGISTRATION_DELAY_SECONDS` (≤0 → **300 s**) elapses, or the caller's context (30 s on both paths) expires; wait failure only logs |
| Status | `/monitoring/traffic-status` (§9.1) |

---

## 11. Shutdown — exact sequence and budgets

Trigger: the process context is cancelled (SIGINT/SIGTERM via
`cmd/fc-server/main.go`; `server.Run` derives and cancels its own ctx).

1. **Immediately on cancel** (side effects of the shared context, not an
   explicit step): every consumer poll loop exits; every in-flight HTTP
   request built on a consumer context is aborted (→ `ErrorConnection`,
   breaker **failure** recorded, pool retry verdict); IMMEDIATE retries
   waiting out a backoff release their tracker entries; ordered drainers
   re-front their message and clear `working`; stall detector, queue-health
   monitor, reaper, broker-stats refresh, notifier loop and lifecycle loops
   stop (`router/server.go:187-192`, `manager.go:437`, `pool.go:323-338,583-599,623-630`).
2. **Drain** (`server.go:228-236`): log `in_flight`; wait until the tracker
   is empty, polling every 500 ms, for at most `DrainTimeout` (60 s; env
   `FC_DRAIN_TIMEOUT_SECONDS`). Because of step 1 this completes as soon as
   aborted workers have unwound; a worker stuck in a non-interruptible
   section (none known) would hold it open.
3. **Shutdown budget 30 s** (`server.go:238-256`), in order:
   1. `Traffic.Deregister` (ALB; may spend the whole 30 s polling drain).
   2. `Lifecycle.Shutdown` (wait for the three loops).
   3. `Manager.Shutdown`: cancel + `Stop()` every consumer, `Stop()` every
      pool (flush buffers, release entries), empty the registries, wait for
      poll loops (bounded by the remaining budget).
   4. `election.Stop` (release the lock if held).
   5. `Notifier.Stop` (the notifier loop already exited in step 1 and its
      final flush used the cancelled context → **pending warnings are lost**).
4. `server.Run` then: `apiSrv.Shutdown` / `metricsSrv.Shutdown` (30 s),
   `wg.Wait()` for subsystems (`server/run.go:189-200`).

What a shutdown means for messages: nothing is acked on exit; every message
that was delivering, retrying or buffered returns to the broker by
**redelivery after the broker's own visibility / ack-wait rules** (SQS
visibility timeout; Postgres `visible_at`; NATS ack-wait 120 s) — except the
explicitly nacked paths (pool stopped/at capacity) which become visible after
10 s on brokers that honour Nack delay. A webhook that was mid-flight may
have been received by the target and will be **delivered again** (at-least-
once).

Not shut down: the mediator's host-pool sweep goroutine (`HTTPMediator.Close`
has no caller) — harmless at process exit.

---

## 12. Edge cases & invariants mined from tests

Each line: behaviour → pinning test (file in `router/` unless noted).

1. JSON tags of `Message` are camelCase; unset optionals are absent, not null → `common/message_test.go: TestMessageJSONRoundtrip, TestMessageOmitsEmptyOptionals`.
2. `ParseDispatchMode` maps `""`/unknown → `IMMEDIATE`; `RequiresOrdering` true only for `NEXT_ON_ERROR`/`BLOCK_ON_ERROR` → `TestDispatchModeParseLenient, TestDispatchModeRequiresOrdering`.
3. `QueueConfig` accepts `{queueName,queueUri}` and legacy `{name,uri}`; defaults name←uri, connections 1, visibilityTimeout 120; marshals camelCase; a full `RouterConfig` with a name-less queue decodes → `common/config_test.go: TestQueueConfigUnmarshal_*, TestRouterConfigUnmarshal_FullWire`.
4. POST body is exactly `{"messageId":"<id>"}`; `Authorization: Bearer <token>`; signature = hex HMAC-SHA256(secret, ts‖body); timestamp 24 chars ending in `Z` → `mediator_test.go: TestMediatorPayloadAndSignatureFormat`.
5. 400 → `ErrorConfig` with status 400 → `TestMediatorBadRequestIsConfigError`.
6. 429 with `Retry-After: 120` → `RateLimited`, delay 120 → `TestMediatorRateLimitedReadsRetryAfter`.
7. 5xx → `ErrorProcess` after exactly `MaxRetries` (3) total attempts → `TestMediatorServerErrorRetries`.
8. HTTP/2 transport configuration does not yield a config error → `TestMediatorHTTP2_DispatchSucceeds`.
9. `ConnectTimeout` is applied to the dialer (unroutable target fails in ≈ConnectTimeout, not `Timeout`) → `TestMediatorConnectTimeoutHonoured`.
10. 2xx `{"ack":false,"delaySeconds":45}` → `Deferred`, delay 45, **one** attempt → `TestMediatorAckFalseIsDeferredWithoutInPipelineRetry`.
11. Breaker trips only at ≥`MinCalls` samples and rate ≥ threshold; stays closed at 0.4; `State()` does not transition; `Allow()` after `ResetTimeout` → half-open; `SuccessThreshold` consecutive successes close; any half-open failure re-opens → `circuit_breaker_test.go: TestCircuitBreaker*`.
12. Registry dedups by URL; `Evict(maxIdle)` removes idle breakers and a re-`Get` yields a fresh instance; `Evict(≤0)` is a no-op → `TestBreakerRegistry*`.
13. Host key default ports 443/80; explicit port honoured; malformed / no default port (ftp) rejected → `host_pool_test.go: TestHostKey*`.
14. Pool starts with 1 slot; grows when every slot ≥ high watermark; never exceeds `MaxSlotsPerHost`; guard release decrements and double-release is a no-op; sweep removes idle slots below low watermark but keeps ≥1 and keeps busy slots; registry separates origins by scheme/host/port; `StartSweep`/`Close` idempotent → `TestHostPool*`, `TestSlotGuardReleaseDecrementsInFlight`, `TestHostPoolRegistry*`.
15. Redelivery swaps the owner's receipt handle and keeps count at 1 → `inflight_test.go: TestInFlightRedeliverySwapsReceiptHandle`.
16. External requeue does not adopt the handle and leaves no phantom broker-id entry after the owner completes → `TestInFlightExternalRequeueDoesNotContaminate`.
17. Reap removes entries older than max age on `LastSeenAt`; a recent redelivery protects an old entry → `TestInFlightRemoveAndReap, TestInFlightReapSkipsRecentlyRedelivered`.
18. `EnsureTracked` recognises the owner without regressing a fresher handle, rejects a foreign copy, restores a reaped entry → `TestInFlightEnsureTrackedBackstop`.
19. A group drains strictly FIFO ignoring `HighPriority`; `pop` reports emptiness → `pool_test.go: TestGroupQueueIsStrictFIFO, TestGroupQueueEmptyAfterAllPopped`.
20. `enqueue` appends to the back, `enqueueFront` prepends → `TestPoolEnqueueAppendsToBackEnqueueFrontPrepends`.
21. Deferred curve 5→10→20→40→60(cap); floor honoured; cap never lifted by a large request → `pool_backoff_test.go: TestDeferredDelayCurve`.
22. Error curve 100 ms start, 5 min cap at attempt 12, floor honoured incl. 240 s → `TestRetryDelayKeepsTheErrorCurve`.
23. Multi-source merge: first-wins by pool code and queue URI; single source passes through; comma-separated URL parsing trims and drops empties; `MaxAttempts` 12 → `config_sync_test.go: TestMergeConfigs*, TestNewConfigSourceParsesCommaSeparated`.
24. Disabled traffic strategy: register/deregister are successful no-ops, status `disabled`; enabled-but-incomplete config self-disables → `traffic_test.go`.
25. Warning store add/get, acknowledge (false for unknown id), filter by severity, eviction keeps ≤ max, `Active` filters acked and by age → `warning_test.go`.
26. Ordered head fails twice then succeeds: attempt order `m1,m1,m1,m2,m3`; no nacks; all acked → `pool_cascade_test.go: TestPoolOrderedRetryPreservesFIFO`.
27. IMMEDIATE retries independently: m1 attempted 3 times, others unaffected, no nacks → `TestPoolImmediateRetriesIndependently`.
28. **RULED (owner, 2026-08-25): count every restart attempt, successful or not.** Go increments the counter only on a *successful* rebuild, which inverts the escalation it exists for: a consumer that can never be rebuilt — bad credentials, a deleted queue, a wrong URI — warns at `WARNING` forever, once per tick, and never reaches `CRITICAL`, so the failure mode most needing a human stays the quietest while one that keeps rebuilding and re-stalling escalates properly. The counter answers "how many times has the platform tried and failed to fix this?", and a failed rebuild is more of that, not less. Java also distinguishes the two outcomes in the warning text ("has been rebuilt" vs "cannot be rebuilt") because they point at different causes — broker health versus configuration. Deliberate deviation; `ConsumerSupervisorTest` pins both.


29. Drainer cancelled during backoff likewise → `TestPoolOrderedGroupRecoversAfterCancelDuringBackoff`.
30. Success ACKs exactly once (`processDone`); 5xx → retry, no broker action, backoff ≥30 s; circuit-open → retry, no broker action, backoff ≥5 s; panic → retry, no broker action → `guardrail_test.go: TestGuardrail_Resolution*, _RetryOn*`.
31. 600 concurrent submits across IMMEDIATE and ordered paths all resolve exactly once under `-race` → `TestGuardrail_ConcurrentSubmitNoRaceAndResolvesEach`.
32. 4xx records a breaker **success**; an open breaker short-circuits with zero HTTP hits; 404 → ERROR and 501 → CRITICAL `CONFIGURATION` warnings → `TestGuardrail_BreakerRecordsSuccessOn4xx, _MediatorShortCircuitsWhenOpen, _ConfigErrorsSurfaceAsWarnings`.
33. Pool resolution: code → pool; empty → DEFAULT; unknown → DEFAULT + exactly one ROUTING warning; empty does not warn → `manager_route_test.go: TestManagerPoolForMessage, TestManagerUnknownPoolRecordsRoutingWarning`.
34. Register outcomes: new / external-requeue (different broker id) / redelivery (same id or blank id) → `TestInFlightTrackerRegisterOutcomes`.
35. Route ACK-drops an external requeue without mediating it → `TestManagerRouteExternalRequeueAcks`.
36. A requeue of a *buffered* ordered message is ACKed and the buffer keeps one copy; a redelivery of a buffered message is dropped (not acked), keeps one copy, and the owner adopts the fresher handle → `TestManagerRouteRequeueOfBufferedMessageAcked, TestManagerRouteRedeliveryOfBufferedMessageDropped`.
37. A redelivery resumes a parked group; the message is delivered once and acked with the redelivery's handle → `TestManagerRouteRedeliveryResumesParkedGroup`.
38. A stopped pool nacks and releases the tracker entry; a redelivery registers as new → `TestPoolStoppedNackReleasesTrackerEntry`.
39. Stopping a pool flushes buffered messages and releases their entries (the in-hand retrying head stays tracked) → `TestPoolStopFlushesBufferedTrackerEntries`.
40. `Lookup` returns a copy by exact id → `TestInFlightTrackerLookup`.
41. Force-ack uses the freshest handle, releases the entry, second call finds nothing, a deregistered queue still clears the entry and surfaces the ack error → `TestManagerForceAckInFlight`.
42. Poll loop exits on `ErrStopped` without advancing the heartbeat; transient poll errors do not advance it either → `manager_stopped_consumer_test.go`.
43. HealthService: success rate math, absent pool → no data, consumer health requires running + recent poll, stall detection by threshold, report Healthy / Degraded on critical / Warning→Degraded by counts (2/5 overrides), `RemoveStaleEntries` → `health_test.go` (note: production never feeds these).
44. Metrics collector: empty → success rate 1.0 and 0 samples; totals; transient not in totals but in window rate; nearest-rank percentiles; windowed counts/throughput; rate-limited windowed; eviction by window; reset; `MaxSamples` bound keeps newest → `metrics_test.go`.
45. Broker stats: attrs overlaid on live counters; windowed delta against baseline; `AgeSeconds` -1 before refresh; saturating subtraction → `broker_stats_test.go`.
46. API: `/health/live` 200 LIVE; `/health/ready` 200 when healthy; `/health` has a status; `/monitoring` pool_stats; `/monitoring/health` has details; pool-stats all-time & 5min windows and `availablePermits = concurrency − activeWorkers`; queue-stats `refresh=true` triggers refresh and `totalConsumed=acked`; circuit-breakers `CLOSED`; single breaker state; in-flight list + pool filter; in-flight check & batch; pool update passes concurrency/rate and 404 on rejection; broker-stats refresh; breaker reset single/all; publish 201 with auto id and 422 on missing required; seed publishes N; mocks counters/fail/client-error/reset; standby status; local config; warning ack; dashboard HTML serves and substitutes the prefix → `api/api_test.go`.
47. BasicAuth: disabled when empty; 401 + `WWW-Authenticate: Basic …` without creds; accepts valid; rejects wrong; public paths bypass (root-mounted); `IsPublicPath` table incl. `/docsy` false → `api/auth_test.go`.
48. In-flight detail statuses MEDIATING / RETRY_BACKOFF / TRACKED_IDLE, miss → `inPipeline:false`; force-ack success / broker-ack failure surfaced / 404 / 503 → `api/handlers_inflight_ops_test.go`.
49. In-flight list sorted elapsed-desc **before** truncation; additive `messageGroup`/`attempts` present → `api/in_flight_ordering_test.go`.
50. Mediating list sorted elapsed-desc with pool filter; fields carried → `api/mediating_test.go`.
51. Prometheus emits the named series/labels/histogram buckets → `api/prometheus_test.go`.
52. Stream-health pass-through: NOT_CONFIGURED without provider; live provider shape; ready 503 when not ready → `api/stream_health_test.go`.
53. Scheme registry routes by scheme; unknown scheme errors; SQS https URL → `sqs`; non-SQS https → error → `queue/queue_test.go`.
54. SDK verifier matches the router formula; rejects tampered body, stale timestamp (30 min), missing headers; validator accepts unix-seconds and ISO-ms timestamps, rejects expired / future / bad signature / missing, `ComputeSignature` stable → `pkg/fcsdk/webhook/*_test.go`.

---

## 13. Open questions for the owner

Each is a yes/no (or pick-one) decision. "Today" = what the Go does.

**Delivery semantics**

1. `NEXT_ON_ERROR` and `BLOCK_ON_ERROR` are implemented identically (head-of-line block the group). Keep them identical, or give `NEXT_ON_ERROR` "skip the failed head and continue" semantics? (`pool.go:270`, §2.6)
   **Ruling (Andrew, 2026-08-22): give them different semantics.**
   - `NEXT_ON_ERROR`: when the head of a message group fails, the group
     continues with the next message — no head-of-line blocking. The failed
     message is reported as failed (platform side) and is NOT retried in front
     of its siblings. *(Sub-question open: is the failed head retried
     independently — which breaks in-group order — or failed immediately and
     left for the platform to resend? Interacts with Q2 retry budget.)*
   - `BLOCK_ON_ERROR`: when the head fails, the rest of the group stays
     pending **on the platform**, not in router memory: the router ACKs the
     queued siblings of that group (removes them from the broker) instead of
     holding them, because the platform will re-send the whole group once the
     error is cleared (scheduler `blockedGroups` skips groups with a FAILED
     sibling; the stale-QUEUED poller returns the acked jobs to PENDING; a
     retry/cancel of the failed job releases the group). The failed head
     itself follows the retry policy (Q2) and is then marked failed.
   - Error resolution (Andrew, follow-up): a message that goes into error is
     **reviewed by a human**, who sets it to *ignore*, *completed* or
     *resend*; on that action the **group goes back onto the queue**. So the
     failed head is never retried independently by the router: it is failed
     (after the retry policy of Q2), surfaced for review, and the platform
     re-queues the group when the reviewer resolves it — for `NEXT_ON_ERROR`
     only the failed message waits for review while its siblings proceed; for
     `BLOCK_ON_ERROR` the whole group waits (siblings ACKed off the broker,
     pending platform-side) and is re-sent in order after resolution.
   → deliberate deviation from Go; conformance tests must pin both modes and
     the ignore/completed/resend → re-queue flow.
2. There is **no terminal give-up**: a message failing with 5xx/transport retries forever (≥30 s apart, 3 HTTP attempts each) until 2xx/4xx, force-ack, or process exit. Keep infinite retry, or add a max-attempts / max-age dead-letter path? (§6.5)
   **Ruling (Andrew, 2026-08-24): keep infinite retry — no dead-letter, no
   max-attempts.** A message stays on the queue until the *queue* expires it
   (broker retention/TTL is the terminal condition, not the router). Backoff
   and the per-endpoint circuit breaker are the protection against a failing
   target; the router never gives up on its own. The Java port must therefore
   not introduce a max-attempts counter or a dead-letter path.
3. Each `Mediate` makes up to **3 HTTP attempts** (1 s, 2 s between) *and then* the pool retries on its own curve. Keep the double-layer (in-call retries + pool backoff), or collapse to one retry policy?
   **Ruling (Andrew, 2026-08-24): collapse to ONE retry policy**, provided the
   observable behaviour is unchanged — same effective attempt spacing and the
   same outcome/breaker/metric accounting a caller would see today. The Java
   port expresses it as a single named retry-policy record (CONVENTIONS §8:
   "panic-recovery scaffolding is deleted; the retry policies it guarded are
   kept as explicit, named policy objects") rather than in-call attempts
   nested inside pool backoff. A conformance test must pin the resulting
   attempt schedule against the Go behaviour.
4. Prod request timeout is **15 min** (`mediator.go:67`); with 3 in-call attempts one message can hold a worker ~45 min while the queue visibility (default 120 s) lapses repeatedly (redeliveries deduped). Keep 15 min? Wire `ExtendVisibility` at ~50 % of visibility timeout for long deliveries (implemented on all backends, never called), or keep it dead?
5. Go's HTTP client **follows redirects** (301/302/303 downgrade POST→GET and drop the body; 307/308 replay). Java's default is *not* to follow. Should redirects be followed at all? If yes, which codes?
6. `mediationType ≠ HTTP` and targets without a host / default port are **silently ACK-dropped** (no warning; breaker *success*). Keep silent, or raise a CONFIGURATION warning like 400/404?
7. `ErrorConnection` bumps the pool's permanent `total_failure` on *every* retried attempt (`pool.go:801-803`) while `ErrorProcess` does not (`:795-799`). Intentional asymmetry, or accident?
8. `CircuitOpen` records **no** pool metric at all (neither transient nor rate-limited). Accident?
9. An internal rate-limiter stall and an HTTP 429 share the same `rateLimited` counter/Prometheus series (`pool.go:766-768,807`). Keep merged, or split?
10. Rate limiter burst = `rpm` (a full minute's allowance can fire instantly after idle), comment says "small jitter". Keep burst=rpm, or burst=max(rpm/60,1)?
11. Half-open admits **every** concurrent caller once `ResetTimeout` elapses (no single probe). Keep (Go behaviour), or single-probe half-open?
12. Breaker key = full URL string (so `…/hook?x=1` and `…/hook?x=2` are separate breakers). Keep, or key by origin+path / by origin?
13. Ordered messages **without** a group id share the global `""` group per pool and are fully serialised. Keep, or treat group-less ordered messages as IMMEDIATE (or as their own singleton group keyed by id)?
14. `HighPriority` is carried and inert. Keep as inert (document), or drop from the Java model?
15. The 2xx "ack=false" detection reads the whole body and requires valid JSON; any other 2xx body counts as success; `Success()` hard-codes status 200. Keep?

**Producer / ordering contract**

16. The platform scheduler publishes with `messageGroupId` but **no `dispatchMode`** and **no `poolCode`** → every platform job is IMMEDIATE in `DEFAULT-POOL`; group FIFO is therefore *not* enforced for platform jobs by the router, and the Postgres claim rule only prevents two same-group messages in one poll batch (§7.3). Is that the intended contract (ordering owned by the processing endpoint / `ack:false` deferral), or should the scheduler set `BLOCK_ON_ERROR` / should the Java router treat a present `messageGroupId` as ordered by default? (This may be a scheduler-spec question; flagged here because it decides whether the ordered path is load-bearing in production.)
17. A malformed payload on Postgres **fails the entire poll** and the poison row re-claims on every visibility lapse forever. Ack/park it instead (like SQS acks malformed)?
18. SQS publishes set **no `MessageDeduplicationId`** (FIFO queues need content-based dedup). Rely on content dedup (today), or set the job id?
19. On NATS a redelivery carries a new consumer sequence → classified as *external requeue* → the router **acks (consumes) the duplicate while the original is still in flight**; a later failure of the original loses the message. Is NATS in scope for the Java port at all? If yes, broker id should be the stream sequence only.
20. `Connections` in `QueueConfig` is parsed, compared for change detection (restarting the consumer), and **never used**. Drop it, or give it meaning (N poll loops per queue)?
21. `VisibilityTimeout` is ignored by NATS (ack-wait from the URI). Accept, or map it?
22. Default-broker Postgres queue uses visibility 30 s while the config default is 120 s. Intentional for dev speed?

**Dedup / tracker**

23. Tracker reap max age 15 min on `LastSeenAt`, skipping retrying entries; reaper tick 5 min; breaker eviction 1 h; tracker >10 000 → RESOURCE warning. Keep these numbers?
24. On `ForceAck`, a running delivery attempt is **not** aborted and may still ACK with a stale handle (logged). Acceptable?
25. Pool stop flushes buffered messages **without nacking** them (SQS: irrelevant; Postgres: they stay invisible until visibility lapses instead of becoming visible now). Nack-with-0 on flush for brokers that honour it?

**Consumer / config lifecycle**

26. A consumer restart (stall ≥60 s), a queue config change, a leadership loss and process shutdown all **abort in-flight HTTP deliveries** for that consumer's messages (breaker failure recorded, message re-delivered by the broker later). Should in-flight deliveries be allowed to finish (bounded) instead?
27. **Suspected startup defect (default-broker mode):** `newRouterServer` calls `Reconfigure(bootCtx)` with a 10 s timeout context that is `defer cancel()`-ed when the function returns (`server/run.go:309-310,329`); the consumer poll loop created under it therefore exits on its first iteration; the watchdog only rebuilds it once `lastPoll` is >60 s stale (30 s tick + 5 s delay) → ≈65–95 s of no polling after every fcdev start, plus a spurious `CONSUMER_HEALTH` "stalled, restart attempt 1" warning. Please confirm; the Java should start default-broker consumers under the run lifetime.
28. A consumer that **cannot be rebuilt** never increments `restartAttempts`, so it never escalates to CRITICAL. Accident?
29. One ROUTING warning **per message** with an unknown pool code. Dedupe per (pool code) with a TTL, or keep?
30. Partial multi-source config failure **removes** that source's pools/queues until it recovers (consumers stopped, buffers flushed). Keep first-wins-over-successes, or keep the last-known-good config for failed sources?
31. Config poll interval: code 300 s, field comment 30 s — owner decided **5 min**. Confirm 5 min is the Java default and whether it should be env-tunable.
32. `POST /config/reload` returns **500** in default-broker mode (no config source) although the handler has a friendly 200 branch that is unreachable. Return 200-with-note, or 400/409?
33. `POST /config/reload` is **not leadership-gated**: a follower would fetch and reconfigure (start consumers). Gate it?
34. Default-broker + standby: pools start before/without leadership and are not recreated after a loss→regain. Declare default-broker as single-instance only, or gate it too?
35. A consumer build error mid-`Reconfigure` leaves the reconfigure **half-applied**. Apply all-or-nothing, or keep?

**Health / warnings / observability**

36. `HealthService`'s pool-success-rate and consumer-liveness inputs are **never fed** in production (no callers of `RecordPoolResult` / `SetConsumerRunning` / `RecordConsumerPoll`). Health is warnings-only today (Degraded iff unacked CRITICAL or >20 active; Warning iff >5). Wire them in the Java (then a pool with <90 % success or a stalled consumer degrades readiness), or formalise warnings-only?
37. One unacknowledged **501** from any target → NOT_READY for the whole router (up to 8 h). Keep 501 = CRITICAL?
38. STALL and QUEUE_HEALTH warnings go to the **notifier only** and never to `/warnings`/health; CONNECTION, RATE_LIMIT, CIRCUIT_BREAKER categories are never emitted. Route everything through the store?
39. `/monitoring` reports `active_warnings` = all unacked, while `/health` and `health_report` use unacked ≤30 min. Unify?
40. `/monitoring/consumer-health` always returns `{}` (lists only stalled entries of the unfed model). Keep the shape and feed it, or drop the endpoint?
41. Prometheus: keep the acknowledged contract gap (`fc_messages_submitted_total`, `…rejected_total{reason}`, `fc_consumer_polls_total`, `fc_consumer_errors_total{type}`, `result` label, `flowcatalyst_broker_*` not emitted)? Should the Java emit the fuller set (it can, with JFR/Micrometer counters at event time)?
42. The notifier's final flush on shutdown runs with a cancelled context and is lost. Give it its own short budget?
43. **Suspected auth defect:** BasicAuth public-path bypass tests `r.URL.Path`, which under the `/router` mount still carries the prefix → `/router/health/live`, `/router/metrics`, `/router/openapi.json` require credentials when auth is on. Confirm, and should the Java match paths relative to the mount?
44. The documented golden vector (`tests/golden/webhook/…`) does not exist; §6.4 supplies a computed one. Adopt it as the committed vector for both sides?
45. Header name on the wire: Go canonicalises to `X-Flowcatalyst-*`; SDK docs say `X-FlowCatalyst-*`; constants say `X-FLOWCATALYST-*`. Confirm receivers are case-insensitive and pick one canonical spelling for the Java to emit.
46. `docs/architecture.md` claims the router "signs the payload bytes it receives, never re-serialises" and lists HdrHistogram / per-route HTTP histograms / sqlite & amqp backends; the code constructs a one-field body, uses a sorted-slice percentile and emits no HTTP-server histograms; sqlite/amqp are absent. Treat the docs as stale (spec wins)?
47. `common.StallConfig` (JSON-tagged) duplicates `router.StallConfig`; `LifecycleConfig.ConsumerStallThreshold` falls back to 90 s but defaults to 60 s; two lock-key defaults (`fc:leader` vs `fc:server:leader`). Collapse each to one value?
48. `Reserve()` on the rate limiter, `Consumer.Healthy()`, `Consumer.Defer()`, `ErrNotImplemented`, the `RouterError` kinds (`router/error.go`, no producer in the router) are dead code. Drop from the Java model?
49. Shutdown: drain waits only for aborted workers to unwind (≤60 s) — is the intended semantic "finish what's in flight, up to 60 s" (then the Java must *not* cancel workers at drain start), or "stop now and rely on redelivery" (then the 60 s drain is mostly moot)?
50. Timing constants flagged ACC? in §5 (consumer pacing 2 s/1 s/1 s/500 ms; nack delays 5 s/10 s; host-pool watermarks; warning 8 h/1000; notifier 20/10 s; SQS pending-delete 15 min; dashboard 5 s): keep as-is for parity, or treat as free to tune in Java?

51. **RULED (owner, 2026-08-24): carry the real status; fix Go as well.** `common.Success()` hard-codes `StatusCode: 200`, so a target answering 201/204 — or 201 with `{"flushGroup": true}` — is recorded as 200, while the `ack:false` branch beside it copies the real status. Verified diagnostic-only: nothing in the router branches on `MediationOutcome.StatusCode`. Go fix (constructor takes the status, so the compiler catches a future omission): `docs/spec/router-fixes.md` Fix 1. Java already carries it — `MediationResponseTest.carriesRealStatus`. (§2.4, `mediator.go:356-365`)
52. **(eff2a29)** `GroupFlushRegistry.SuppressedUntil`, `.Clear` and `.Stats` have **no callers** outside tests. So an operator cannot ask "why is this group quiet?" — the question the TTL design explicitly anticipates — and cannot lift a suppression early. Expose them on the monitoring API and add an operator clear, or drop them as dead code? (§2.11; same class as Q48)
53. **(eff2a29)** A message ACKed because its group is suppressed records **no pool metric at all** — not success, not transient, not rate-limited — and the registry's own `suppressed` counter is unread. A pool whose groups are being flushed heavily therefore looks *idle* rather than busy-but-suppressed, on `/monitoring` and in Prometheus alike. Add a suppressed counter to the pool metrics, or accept the blind spot? (§3.5, §6.5)
54. **RULED (owner, 2026-08-24): honour the Go behaviour — any target may flush.** Correct for this deployment's context, where targets own the records they are pointed at. **Logged to revisit** in `docs/improvements.md`: the router ACKs messages it never delivered on a target's say-so, and nothing enforces the safety condition that makes that sound, so a target holding the only copy of a payload loses data indistinguishably from a bug. No per-pool opt-in gate in the port. (§2.11)
55. **(eff2a29)** The registry is **per pool**, so the same message-group id in two pools suppresses independently. Correct as-is (a group is only meaningful within the pool that orders it), or should suppression be global to the router? (§2.11)
56. **`/monitoring/standby-status` reports the lock key as `instance_id`, not the instance.** `leaderAdapter.InstanceID()` (`router/api/api.go:374-379`) returns `Cfg.StandbyLockKey`, falling back to the literal `"default"` — never the election's own instance id, which *is* a per-process UUID and is what the field's name promises. Every instance in a cluster therefore reports the **same** `instance_id`, which makes the field useless for the one question it exists to answer: *which* instance is holding leadership. An operator investigating a suspected split-brain gets a value that cannot distinguish two routers. Reproduce (done, with a test pinning it), or return the election's instance id? The fix is one line and the field is already named for it. Found while porting the monitoring API, 2026-08-25.

---

## Appendix A — Environment variables the router reads (via `server/envcfg.go`)

| Var | Default | Effect |
|---|---|---|
| `FC_ROUTER_ENABLED` (`MESSAGE_ROUTER_ENABLED`) | false | start the router subsystem |
| `FC_ROUTER_HTTP_PREFIX` | `/router` | mount prefix for API/dashboard/metrics |
| `FLOWCATALYST_CONFIG_URL` | — | config source(s), comma-separated |
| `FC_DEFAULT_BROKER` | `""` | `postgres` → synthesised single-pool config when no URL |
| `FC_DATABASE_URL` (via `DatabaseURL`) | `postgresql://postgres@localhost:5432/flowcatalyst` fallback | default-broker queue URI |
| `FLOWCATALYST_DEV_MODE` | false | dev mediator (30 s, HTTP/1.1) |
| `FC_NOTIFY_WEBHOOK_URL` | — | notifier target |
| `FC_DRAIN_TIMEOUT_SECONDS` | 60 | drain budget |
| `FC_STANDBY_ENABLED` (`STANDBY_ENABLED`) | false | Redis election |
| `FC_STANDBY_REDIS_URL` (`REDIS_URL`) | `redis://127.0.0.1:6379` | |
| `FC_STANDBY_LOCK_KEY` | `fc:server:leader` | router lock key (subsystems add `:<name>`) |
| `FC_ALB_ENABLED`, `FC_ALB_TARGET_GROUP_ARN`, `FC_ALB_TARGET_ID` (`FC_ALB_INSTANCE_IP`), `FC_ALB_TARGET_PORT` (8080), `FC_ALB_REGION`, `FC_ALB_DEREGISTRATION_DELAY_SECONDS` (0 → 300) | | ALB registration |
| `FC_ROUTER_AUTH_USER` / `FC_ROUTER_AUTH_PASS` (`AUTH_BASIC_USERNAME` / `AUTH_BASIC_PASSWORD`), `AUTH_MODE=NONE` | — | BasicAuth |
| `FC_API_PORT`, `FC_METRICS_PORT` (9090) | | listeners (router metrics are on the API port under the prefix; the metrics port serves a placeholder) |

Not env-tunable today (code defaults only): config poll interval, in-flight
reap age, breaker idle age, breaker thresholds, host-pool sizing, mediator
timeouts/retries, stall / queue-health / warning / health / lifecycle /
notifier settings.
