# Dispatch pool — behavioural spec

The contract for `io.flowcatalyst.platform.dispatchpool`. Derived from the
lockfile (`/api/dispatch-pools*`, `/api/applications/{appCode}/dispatch-pools/sync`)
plus the validation rules, authorization placement, states, error codes,
domain events and persistence rules the aggregate embodies. The Java is
written *from* this; tests assert it. Questions marked **load-bearing or
accident?** need an owner ruling — until ruled on, the behaviour is kept.

## 1. Aggregate

A dispatch pool is a named bucket of rate-limit + concurrency settings the
message router uses when dispatching. It is identified by a **code** that is
unique per `(code, clientId)` pair; a pool with no `clientId` is
platform-wide ("anchor-scope").

| Field | Type | Notes |
|---|---|---|
| `id` | `dpl_` + 13-char TSID | generated on create |
| `code` | string, `^[a-z][a-z0-9_-]*$` | unique per `(code, clientId)`; the admin create **trims and lowercases** before validating, sync does **not** normalise. **load-bearing or accident?** (the divergence is documented in the Go tests as deliberate: "sync does NOT lowercase") |
| `name` | string, required | trimmed on create/update; **not** trimmed by sync (**accident?**) |
| `description` | string, optional | |
| `rateLimit` | int, optional | messages per minute; `null` = no rate limiter, concurrency-only |
| `concurrency` | int, required | max concurrent dispatches; default `10` |
| `clientId` | string, optional | owning client; `null` = platform-wide. Used for authorization, filtering and code uniqueness |
| `clientIdentifier` | string, optional | denormalised client identifier; read-only — nothing in this aggregate sets it (**accident?** column exists for the router/BFF read side) |
| `status` | `ACTIVE` \| `SUSPENDED` \| `ARCHIVED` | default `ACTIVE` |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Lenient enum read: unknown stored `status` → `ACTIVE` (**accident?** —
silently maps bad rows to the default; kept because the Go reader does).

## 2. States and transitions

`ACTIVE`, `SUSPENDED`, `ARCHIVED`. The three transitions are **unconditional
flips**: `suspend()` → `SUSPENDED`, `activate()` → `ACTIVE`, `archive()` →
`ARCHIVED`, from *any* current state. There is no conflict error
(`ALREADY_ARCHIVED` etc.), so an `ARCHIVED` pool can be suspended or
re-activated, and archiving twice is a no-op write that still emits an event.
**load-bearing or accident?** (The event-type aggregate refuses a second
archive; the router treats `ACTIVE` as "eligible for routing" and everything
else as not.)

`updatedAt` is bumped by each transition on the aggregate, but the
repository overwrites it with `now()` at persist time (§9).

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/dispatch-pools` | `dispatch-pool:view` | query `status`, `clientId` | 200 `DispatchPoolListResponse` `{pools: [...], total}` | ordered by code; client-scoped rows the caller cannot access are filtered out **after** the query, and `total` is the count of the visible rows. No default status filter (archived pools are listed unless `status` is given). Empty query values mean "no filter" |
| `POST /api/dispatch-pools` | `dispatch-pool:{create\|update\|delete}` (any) | `CreateDispatchPoolRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | **accident?** create is gated by "any write permission", not `create` specifically (same as event types) |
| `GET /api/dispatch-pools/{id}` | `dispatch-pool:view` | — | 200 `DispatchPoolResponse` | 404 `DispatchPool_NOT_FOUND`; 403 `FORBIDDEN` `No access to this dispatch pool` when client-scoped and not accessible |
| `PUT /api/dispatch-pools/{id}` | any write permission | `UpdateDispatchPoolRequest` → `UpdateCommand(id from path)` | 204, empty body | partial update: every body field optional, `null`/absent = unchanged |
| `POST /api/dispatch-pools/{id}/archive` | any write permission | `ArchiveCommand` | 204 | |
| `POST /api/dispatch-pools/{id}/suspend` | any write permission | `SuspendCommand` | 204 | |
| `POST /api/dispatch-pools/{id}/activate` | any write permission | `ActivateCommand` | 204 | |
| `DELETE /api/dispatch-pools/{id}` | `dispatch-pool:delete` | `DeleteCommand` | 204 | hard delete |
| `POST /api/applications/{appCode}/dispatch-pools/sync` | `dispatch-pool:{sync\|manage}` + application resolution | `SyncDispatchPoolsRequest` (+ `?removeUnlisted`) → `SyncDispatchPoolsCommand` | 200 `SyncResultResponse` | lives in the sdksync surface, not this package's API; the operation is here (§7) |

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `CreateDispatchPoolRequest` | `code`*, `name`*, `description`, `rateLimit`, `concurrency`, `clientId` | `concurrency` absent ⇒ `10` |
| `UpdateDispatchPoolRequest` | `name`, `description`, `rateLimit`, `concurrency` | all optional |
| `DispatchPoolResponse` | `id, code, name, description?, rateLimit?, concurrency, clientId?, clientIdentifier?, status, createdAt, updatedAt` | optional fields omitted when null; timestamps RFC 3339, 6 fractional digits, `Z` |
| `DispatchPoolListResponse` | `pools[]`, `total` | `total` = `pools.length` (visible rows), not a DB count. SPA reads `response.pools` |
| `CreatedResponse` | `id` | |
| `SyncDispatchPoolInputRequest` | `code`*, `name`*, `description`, `rateLimit`, `concurrency` | `concurrency` absent ⇒ `10`, applied by the operation (the sdksync handler maps the wire shape verbatim into `SyncDispatchPoolInput`) |
| `SyncDispatchPoolsRequest` | `pools[]`* | |

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `code` non-blank after trim | `CODE_REQUIRED` | `code is required` |
| Create | trimmed, lowercased `code` matches `^[a-z][a-z0-9_-]*$` | `INVALID_CODE_FORMAT` | `code must start with a lowercase letter and contain only lowercase alphanumeric, hyphens, underscores` |
| Create | `name` non-blank | `NAME_REQUIRED` | `name is required` |
| Create | `concurrency`, when given, ≥ 1 | `INVALID_CONCURRENCY` | `concurrency must be >= 1` |
| Create | `rateLimit`, when given, ≥ 0 | `INVALID_RATE_LIMIT` | `rateLimit cannot be negative` |
| Update | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `name`, when given, non-blank | `NAME_REQUIRED` | `name cannot be empty` |
| Update | `concurrency`, when given, ≥ 1 | `INVALID_CONCURRENCY` | as create |
| Update | `rateLimit`, when given, ≥ 0 | `INVALID_RATE_LIMIT` | as create |
| Delete / Archive / Suspend / Activate | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Sync | `applicationCode` non-blank | `APPLICATION_CODE_REQUIRED` | `Application code is required` |
| Sync, per row (first failure aborts) | `code` non-blank and matches the pattern **as given** (no trim/lowercase) | `INVALID_POOL_CODE` | `Pool code '<code>' is invalid. Must start with lowercase letter, contain only lowercase alphanumeric, hyphens, underscores.` |
| Sync, per row | `name` non-blank | `NAME_REQUIRED` | `Pool name is required` |
| Sync, per row | `rateLimit`, when given, **≥ 1** | `INVALID_RATE_LIMIT` | `Rate limit, when set, must be at least 1` |
| Sync, per row | `concurrency`, when given, ≥ 1 (absent ⇒ `10`) | `INVALID_CONCURRENCY` | `Concurrency must be at least 1` |

Note the **divergent bounds**: admin create/update accept `rateLimit = 0`
(bound ≥ 0), sync rejects it (bound ≥ 1). **load-bearing or accident?** The
Go tests pin both explicitly, so it is kept — but a `rateLimit` of `0`
persisted via the admin API means "rate-limited to zero messages per minute"
or "unlimited", depending on the router's reading.

Create's code format message differs from sync's (`INVALID_CODE_FORMAT` vs
`INVALID_POOL_CODE`). **accident?** — kept; both are visible to SDKs.

Malformed JSON body → 400 `INVALID_JSON` (transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | coarse permission (table in §3); unauthenticated → 403 `UNAUTHENTICATED` |
| Create — `authorize` phase | `checkScopeAccess(principal, cmd.clientId)`: a client-bound create requires access to that client; `clientId` absent (platform-wide) requires anchor / super-admin → else 403 `SCOPE_FORBIDDEN` |
| Update / Delete / Archive / Suspend / Activate — `execute`, right after load + 404 | `checkScopeAccess(principal, loaded.clientId)`; declared `Authorize.publicAccess()` because the check needs the row |
| Sync — `authorize` phase | the principal must be able to access the **application** (`canAccessApplication(cmd.applicationId)`), else 403 `FORBIDDEN` `Not authorised for application '<code>'`. The coarse `dispatch-pool:sync` gate and the `appCode → applicationId` resolution belong to the sdksync handler. Unauthenticated → `UNAUTHENTICATED` |
| Reads | handler only: list filters client-scoped rows; get-by-id → 403 `FORBIDDEN` `No access to this dispatch pool` |

Sync writes are **not** scope-checked per pool: a sync with application access
updates/archives platform-wide *and* client-bound pools alike (pools are
matched by code globally, §7). **load-bearing or accident?**

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another pool has the same `(code, clientId)` — `clientId` `null` matched as `IS NULL` | `CODE_EXISTS` | 409, message `Dispatch pool with code '<code>' already exists` |
| Update / Delete / Archive / Suspend / Activate | no pool with that id | `DispatchPool_NOT_FOUND` | 404 |

The database unique index `(code, client_id)` does **not** catch two
platform-wide pools with the same code (SQL `NULL`s are distinct), so the
application-level check is the only guard for anchor-scope codes.
**load-bearing** — keep the pre-insert lookup.

Sync never conflicts: an existing code is updated.

## 7. Sync semantics

Input: `applicationId`, `applicationCode`, `pools[]{code, name, description,
rateLimit, concurrency}`, `removeUnlisted`. Pools are **global**: the existing
set is *every* pool in the table regardless of `clientId` or application; the
application is carried for authorization and event provenance only.

| Input row | Effect | Per-row event |
|---|---|---|
| code exists (any client, any status) | `name`, `description`, `rateLimit`, `concurrency` replaced (an absent `concurrency` resets to `10`, an absent `rateLimit` clears the limiter; status, clientId untouched — an `ARCHIVED` pool stays archived) | `updated` |
| code new | created platform-wide (`clientId` null), `ACTIVE`, with the given settings | `created` |
| `removeUnlisted` and existing pool not in input and not already `ARCHIVED` | **archived** (soft — never hard-deleted) | `archived` |
| `removeUnlisted` and existing pool already `ARCHIVED` | untouched | — |

"Deleted" in the result/rollup counts the **archived** rows. With
`removeUnlisted`, a sync from application A archives every pool application
B registered (and every admin-created pool) that A does not list.
**load-bearing or accident?** — the Go tests call it a HAZARD and keep it.

Matching is by the raw input code; since sync does not lowercase and the
admin create does, an SDK sending `My-Pool` is rejected (§4) rather than
matched to `my-pool`.

Two consequences of "global, by code" that the uniqueness rule (§1: per
`(code, clientId)`) makes possible — **load-bearing or accident?**:

- The same code may exist platform-wide *and* for one or more clients. Sync
  updates **one** of them — the first in the repository's code order, which
  has no tiebreaker among equal codes — and leaves the others untouched
  (`removeUnlisted` does not archive them either: their code is listed).
  No error is raised.
- A batch listing the same code twice creates **two** platform-wide pools
  with that code (the existing set is read once, before any write; the DB
  unique index does not catch `NULL` clients). Nothing rejects duplicates in
  the input.

The result/rollup carries `created/updated/deleted` counts and `syncedCodes`
(input codes, in order). All row writes, per-row events and the rollup commit
in one transaction; one audit row per per-row event, all with
`operation = SyncDispatchPoolsCommand`.

## 8. Domain events

Source is always `platform:admin`; spec version `1.0`; per-aggregate subject
is `platform.dispatchpool.{id}` with message group
`platform:dispatchpool:{id}` (every per-aggregate event is FIFO-ordered per
pool — unlike event types, which carry no group). `data` omits null fields.

| Type | Subject / group | `data` fields |
|---|---|---|
| `platform:admin:dispatch-pool:created` | `platform.dispatchpool.{id}` / `platform:dispatchpool:{id}` | `poolId, code, name` |
| `platform:admin:dispatch-pool:updated` | same | `poolId, name` |
| `platform:admin:dispatch-pool:archived` | same | `poolId, code` |
| `platform:admin:dispatch-pool:deleted` | same | `poolId, code` |
| `platform:admin:dispatch-pool:suspended` | same | `poolId, code` |
| `platform:admin:dispatch-pool:activated` | same | `poolId, code` |
| `platform:admin:dispatch-pools:synced` | `platform.dispatchpools.{applicationCode}` / group `platform:dispatchpools` (**no** application suffix — all syncs share one group; **accident?** event types use `platform:eventtypes:{app}`) | `applicationCode, created, updated, deleted, syncedCodes[]` |

The `updated` payload carries only `poolId` + `name` — rate-limit and
concurrency changes are **not** on the event. **load-bearing or accident?**
(A router that reloads pool settings on this event must re-read the row.)

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Dispatchpool`, `entity_id = {id}`,
`operation` = command record simple name, `operation_json` = the command) in
the same transaction as the row change. The sync rollup's audit row has
`entity_type = Dispatchpools`, `entity_id = {applicationCode}`.

## 9. Persistence

Table `msg_dispatch_pools`. Upsert `ON CONFLICT (id)`; `created_at` is
written once and never overwritten; `updated_at` is stamped `now()` at
persist time (not the aggregate's `updatedAt` — **accident?**, harmless);
`client_identifier` is written from the aggregate (always what was read, or
`null` for new rows). Delete removes the row. Reads order by `code`. Sync
uses the same `Persist`.

## 10. Open questions for the owner (summary)

1. Transitions are unconditional flips (archive twice, suspend an archived pool) — intended, or should they refuse like event types do?
2. `rateLimit` bound differs: admin ≥ 0, sync ≥ 1. What does `0` mean to the router?
3. Admin create lowercases the code, sync does not — so the same SDK code can be rejected by sync yet accepted by the UI.
4. Create/update/archive/suspend/activate gated by *any* write permission rather than the specific verb.
5. Sync is global with `removeUnlisted` archiving other applications' and admin-created pools; and sync is not per-pool scope-checked.
6. The `updated` event omits rate-limit/concurrency.
7. Sync rollup message group is `platform:dispatchpools` (shared), not per application.
8. `clientIdentifier` is never set by this aggregate; `description` cannot be cleared via update (`null` = unchanged).
9. Lenient status read masks bad rows.
10. Sync matching by code over all scopes: which row wins when a code exists platform-wide *and* per client; and a batch that repeats a code creates duplicate platform-wide pools (§7).
