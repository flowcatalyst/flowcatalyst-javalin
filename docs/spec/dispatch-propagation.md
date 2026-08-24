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

## 1a. Go status (2026-08-24)

| Half | State | Commit |
|---|---|---|
| `dispatchMode` | **DONE.** `dispatchClaim.mode` already existed and was already selected; the token now carries it and `buildMessage` sets `DispatchMode: ParseDispatchMode(tok.Mode)`. Ordering is restored at the router. | `7414bc5` |
| `poolCode` | **Outstanding, deliberately.** A first attempt selected `dispatch_pool_code` from `msg_dispatch_jobs`, where no such column exists (it is on `msg_subscriptions`); `pollOnce` failed every tick with SQLSTATE 42703 and the dispatch path stopped. Reverted. `PoolCode` stays unset, so every job routes to `DEFAULT-POOL` — the pre-existing behaviour, not a regression. | `7414bc5`, reverted in `7ed2dba` |

**Testing lesson to carry into Java.** The break survived a full
`go test ./internal/...` because `poller_pg_test.go` sits behind
`//go:build integration`, so the four `TestPollOnce_*` cases that exercise
the real migration schema reported "no tests to run". The DB-free
`buildMessage` tests passed, including the strip-the-fix check. **The Java
tests that guard the claim query must run in the default `mvn test`, not
behind a profile** — a query this load-bearing cannot be guarded by a suite
the normal run skips.

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

## 3. Pool code namespacing — RULED

**Owner ruling 2026-08-24: namespace by client.** The propagated code is
`{clientIdentifier}-{poolCode}`, and the empty-pool fallback is
`{clientIdentifier}-DEFAULT-POOL`.

Why it was needed: `msg_dispatch_pools` is unique on `(code, client_id)`, so
two clients may each own a pool coded `FAST` with different concurrency and
rate limits. The router's registry is keyed by **code alone**
(`manager.go:534-535`) and its config merge treats one code with differing
settings as a *conflict to reject*, not as two pools (`conflictingPool`).
Flat codes would therefore have merged two clients' traffic into one pool
governed by whichever config source won. Unreachable today only because no
job carries a code at all.

### Resolution chain (at publish time, in the scheduler)

Resolve the full code **when publishing**, never at routing time. The
router then routes by the code it is given and needs to know nothing about
clients — which is what keeps this change confined to the scheduler.

| Job state | Published `poolCode` |
|---|---|
| `dispatch_pool_id` set, pool has a `client_identifier` | `{pool.client_identifier}-{pool.code}` |
| `dispatch_pool_id` set, pool's `client_identifier` is NULL (platform-level pool) | `{pool.code}` — no prefix |
| `dispatch_pool_id` NULL, job's `client_id` resolves to an identifier | `{clientIdentifier}-DEFAULT-POOL` |
| neither (platform-level job) | `DEFAULT-POOL` — today's global fallback, unchanged |

Two cached maps serve this, both refreshed on the `pausedCache` cadence:
pool id → `(code, clientIdentifier)` and client id → identifier.
`msg_dispatch_jobs` carries `client_id` but **not** `client_identifier`
(only `msg_subscriptions` and `msg_dispatch_pools` carry the identifier),
which is why the second map is needed.

### The composed code is opaque — never split it

`-` is not a safe delimiter to parse back: client identifiers and pool codes
may both contain hyphens. Nothing may reconstruct the parts from the
composed string. The one permitted structural read is a **suffix** test for
`-DEFAULT-POOL`, which is unambiguous.

### The router must synthesise per-client fallback pools

`{clientIdentifier}-DEFAULT-POOL` codes will not exist in the router's
config: **nothing in either repository emits `processingPools`** — the
router polls an external service at `FLOWCATALYST_CONFIG_URL`, and the only
in-process config is `defaultPostgresRouterConfig` for default-broker mode.
So the port cannot assume those pools are configured.

Without handling, every such message would take the unknown-code path
(`manager.go:419-425`): routed to the global `DEFAULT-POOL` with a ROUTING
warning per message — losing the per-client isolation this ruling exists to
create, and spamming warnings.

**Requirement:** the router synthesises a pool on demand for any code ending
`-DEFAULT-POOL`, with the same defaults it already applies to the global
`DEFAULT-POOL`, exactly as `Reconfigure` already auto-adds that one
(`manager.go:538-539`). A config-supplied pool of the same code always
wins. This is bounded by the number of clients, and it delivers the
per-client concurrency isolation without a dependency on the external config
service being updated first.

## 4. Default-broker mode — RULED

**Owner ruling 2026-08-24: the fallback is `{client-identifier}-DEFAULT-POOL`.**

The finding: `defaultPostgresRouterConfig` (`server/run.go:361-370`)
synthesises a pool coded **`default`** at concurrency 4, while the
empty-code fallback is **`DEFAULT-POOL`** at concurrency 20, auto-added
whenever absent. Since nothing sets `PoolCode`, the configured `default`
pool receives **no traffic at all** in default-broker mode (fcdev,
single-tenant) — an operator setting concurrency 4 silently gets 20.

Under §3's chain this resolves itself for client-scoped jobs: they publish
`{clientIdentifier}-DEFAULT-POOL` and land in their own synthesised pool.

Still to change, because it is the no-client case: rename the pool in
`defaultPostgresRouterConfig` from `default` to **`DEFAULT-POOL`** so the
configured pool *is* the fallback pool and its concurrency takes effect.
Otherwise `default` remains a pool that nothing can route to.

## 5. Tests

Go:

- A claimed job with `mode = 'BLOCK_ON_ERROR'` publishes a message whose
  `dispatchMode` is `BLOCK_ON_ERROR`; with `mode = 'IMMEDIATE'`, the field
  is absent or `IMMEDIATE`.
- A job whose pool is coded `FAST` for client `acme` publishes
  `poolCode: "acme-FAST"`; the same code under client `globex` publishes
  `globex-FAST`, and the two are governed by separate router pools with
  their own concurrency — the collision this ruling exists to prevent.
- A job with a NULL `dispatch_pool_id` but a resolvable client publishes
  `{clientIdentifier}-DEFAULT-POOL`; a job with neither publishes
  `DEFAULT-POOL`.
- A pool whose `client_identifier` is NULL publishes its bare code, with no
  prefix.
- The router synthesises a pool for an unseen `*-DEFAULT-POOL` code rather
  than routing it to the global `DEFAULT-POOL` with a ROUTING warning; a
  config-supplied pool of that code wins over the synthesised one.
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
