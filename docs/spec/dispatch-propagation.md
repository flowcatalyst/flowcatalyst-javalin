# Dispatch jobs must carry `dispatchMode` and `poolCode` (router Q16)

**Owner ruling 2026-08-24: propagate both fields. Fix Go and Java.** The
dispatch path is not meaningfully used in production yet, which is what
makes this cheap to change now and expensive to change later.

Go changes live in `../flowcatalyst-go/internal/platform/scheduler/`. Make
them there first, then Java implements the corrected behaviour rather than
reproducing the defect and deviating from it.

Related: `docs/spec/router.md` §2.6, §13 Q16; `docs/spec/dispatchjob.md`.

---

## 1. The defect

`buildMessage` (`scheduler/dispatcher.go:81-94`) publishes only `ID`,
`MediationType`, `MediationTarget`, `AuthToken` and — when non-empty —
`MessageGroupID`. It sets neither `DispatchMode` nor `PoolCode`.

Both fields exist in the data and are dropped on the way out:

| Field | Where it lives | Where it is lost |
|---|---|---|
| mode | `msg_dispatch_jobs.mode` (`VARCHAR(30) NOT NULL DEFAULT 'IMMEDIATE'`); selected by the claim query into `dispatchClaim.mode` and used for the poller's per-mode filter | `DispatchJobToken` (`poller.go:383-387`) narrows to `{JobID, MessageGroup, TargetURL}` before `buildMessage` sees it |
| pool | `msg_dispatch_jobs.dispatch_pool_id` (`VARCHAR(17)`, nullable) | never selected by the claim query at all |

Consequences in production today, all verified:

- `DispatchMode` is the zero value, so `RequiresOrdering()` is false and
  **every dispatch job dispatches as `IMMEDIATE`**. The router's per-group
  FIFO never engages for the platform's only production producer. (The only
  sites setting `DispatchMode` on an outgoing message are two router **API**
  handlers for operator-submitted messages.)
- `PoolCode` is empty, so every dispatch job routes to the synthesised
  **`DEFAULT-POOL`** at concurrency 20 (`manager.go:20,24,394-402,538-539`).
  A subscription's configured dispatch pool — its concurrency *and* its rate
  limit — has never been in force.

This is why `f1fc427` and `5bb46df` were both platform-side changes:
ordering for dispatch jobs is enforced entirely by the poller's per-mode
filter and the delivery-time hold-back, reading `job.Mode` from the
database. The router is not part of it.

**The two symptoms are independent and a partial fix is not useful.**
Propagating `poolCode` alone leaves everything unordered; propagating
`dispatchMode` alone leaves every subscription sharing one pool's
concurrency and rate limit.

## 2. The fix (Go)

**Do not add a join to the claim query.** The claim runs
`FOR UPDATE SKIP LOCKED` over `msg_dispatch_jobs` (`poller.go:158-166`);
joining `msg_dispatch_pools` would extend row locking to the joined table
unless carefully written as `FOR UPDATE OF msg_dispatch_jobs`, and it puts
a join in the hot claim path for data that changes almost never.

Instead:

1. **Claim query** — add `dispatch_pool_id` to the select list. Nothing else
   about the query changes: same `WHERE`, same `ORDER BY`, same `LIMIT`,
   same locking.
2. **`dispatchClaim`** — carry `poolID` alongside the existing `mode`.
3. **Pool id → code** — resolve through a small cached map, mirroring the
   existing `pausedCache` pattern the poller already uses for subscription
   ids (`poller.go:137`). Pools are few and change rarely; refresh on the
   same cadence as the paused cache. A job whose `dispatch_pool_id` is NULL,
   or whose pool has been deleted, yields an empty code — which keeps
   today's `DEFAULT-POOL` behaviour as the fallback rather than dropping the
   job.
4. **`DispatchJobToken`** — gains `Mode` and `PoolCode`.
5. **`buildMessage`** — sets `DispatchMode` from the job's mode (through
   `common.ParseDispatchMode`, so an unknown string leniently becomes
   `IMMEDIATE` exactly as the poller's filter already treats it) and
   `PoolCode` when non-empty.

Leave the poller's per-mode filter and the delivery-time hold-back exactly
as they are. They read `job.Mode` from the database and stay correct; this
change adds the router's participation, it does not replace theirs.

## 3. Owner question — pool codes are not globally unique

`msg_dispatch_pools` has a **unique index on `(code, client_id)`**, so two
clients may each own a pool coded `FAST` with different concurrency and rate
limits. The router's registry is keyed by **code alone**
(`manager.go:534-535`), and its config merge treats one code with differing
settings as a conflict to be rejected, not as two pools
(`config_sync.go:167-176`, `conflictingPool`).

Today this is invisible, because no dispatch job carries a pool code at all.
The moment codes are propagated it becomes reachable: two clients' traffic
with the same pool code lands in one router pool, governed by whichever
config source won.

**Question:** should the propagated code be namespaced (e.g.
`<clientIdentifier>:<code>`) so pools cannot collide across clients, or is
the deployment single-tenant enough in practice that a flat code space is
correct? This must be answered before the fix ships — retrofitting a
namespace later is a wire-visible change to every pool code in the router
config.

## 4. Adjacent finding — the configured `default` pool receives nothing

`defaultPostgresRouterConfig` (`server/run.go:361-370`) synthesises a pool
with code **`default`** at concurrency 4. The fallback for an empty pool
code is **`DEFAULT-POOL`** at concurrency 20, which `Reconfigure` adds
whenever it is absent (`manager.go:538-539`).

Since nothing sets `PoolCode`, in default-broker mode (fcdev, single-tenant
deployments) the configured `default` pool receives **no traffic at all**
and everything runs in the auto-added `DEFAULT-POOL`. An operator setting
concurrency 4 gets 20.

Fixing §2 does not fix this by itself: jobs will carry their real pool code,
and `default` still matches nothing unless a pool is actually coded
`default`. Either the synthesised config should use `DEFAULT-POOL` as its
code, or the fallback should prefer a configured `default`. **Owner
question**, tracked with §3.

## 5. Tests

Go:

- A claimed job with `mode = 'BLOCK_ON_ERROR'` publishes a message whose
  `dispatchMode` is `BLOCK_ON_ERROR`; with `mode = 'IMMEDIATE'`, the field
  is absent or `IMMEDIATE`.
- A job whose pool has code `FAST` publishes `poolCode: "FAST"`; a job with
  a NULL `dispatch_pool_id` publishes no `poolCode` and still routes to
  `DEFAULT-POOL`.
- An unknown mode string in the column publishes as `IMMEDIATE`, matching
  the poller filter's lenient parse.
- The claim query's locking behaviour is unchanged — the existing
  concurrent-claim test must still pass unmodified.

Java, additionally — these are the behaviours that only become reachable
once the fields are propagated, and they are the Q1 ruling:

- `BLOCK_ON_ERROR`: a failed head blocks its group, and the queued siblings
  are **ACKed off the broker** for the platform to re-send.
- `NEXT_ON_ERROR`: a failed head does **not** block; the group continues
  with the next message.
- Two subscriptions on different pools are governed by their own pool's
  concurrency and rate limit, not by a shared default.

## 6. Risk

Low, and lower now than it will ever be: the owner confirms the dispatch
path is not meaningfully used in production yet, so there is no live
traffic whose ordering or throughput characteristics change underneath it.

The cutover note in `docs/spec/router.md` §2.6 should be read in that light
— the "pool config goes live for the first time" warning is real but
currently theoretical, and it is far cheaper to absorb before the dispatch
path carries load than after.
