# Subscription — behavioural spec

The contract for `io.flowcatalyst.platform.subscription`. Derived from the
lockfile (`/api/subscriptions*`, `/api/applications/{appCode}/subscriptions/sync`)
plus the validation rules, authorization placement, state machine, error
codes, domain events and persistence rules the aggregate embodies. The Java
is written *from* this; tests assert it. Questions marked **load-bearing or
accident?** need an owner ruling — until ruled on, the behaviour is kept.

## 1. Aggregate

A subscription binds one or more **event-type patterns** to a delivery
target (an `endpoint` URL, optionally through a `connection`), with the
dispatch settings the router applies (mode, timeout, retries, delay, max
age, dispatch pool). It is identified by a **code**, unique per
`(applicationCode, clientId)` — **answered by owner ruling 2026-09-21**
(`docs/spec/code-first-connections.md`): the old "unique per client,
`(code, client_id)`, DB treats `NULL` as distinct" question is superseded.
V12 (`code-first-connections.md` §1, mirroring Go migration 056) replaces
the old `(code, client_id)` unique index with an expression index on
`(COALESCE(application_code,''), COALESCE(client_id,''), code)` —
uniqueness of the three-part key, **`NULL` a real value on every nullable
part**, is now enforced by the database itself. K1 migrated the admin
create/update lookup to the new key with identical *admin* behaviour.
**K3 (landed)** migrates `SyncSubscriptions`' own matching to the same key
— scoped to `(applicationCode, clientId)`, not `applicationCode` alone —
and adds the `connectionCode`/`sharedConnection`/`clientId` fields §3 and §7
below now describe; see `code-first-connections.md` §3 "Subscription sync"
for the full rule set (the behavioural authority — this file records where
it lands in Java).

| Field | Type | Notes |
|---|---|---|
| `id` | `sub_` + 13-char TSID | generated on create |
| `code` | string, required | admin create: trimmed + lower-cased, `^[a-z][a-z0-9-]*$`; sync: stored **as given**, only non-blank is checked (§7). Immutable after create |
| `applicationCode` | string, optional | set only by sync (the owning SDK application); admin creates leave it `null`. The first part of the three-part uniqueness key (above) |
| `name` | string, required | admin create/update: trimmed; sync: as given |
| `description` | string, optional | |
| `clientId` | string, optional | `null` = platform-wide; persisted; drives authorization, list visibility and `matchesClient`. **K3**: also the second part of sync's ownership key — a sync scopes its matching/creation/removal to `(applicationCode, clientId)`, `NULL` matching `NULL` only (`code-first-connections.md` §3) |
| `clientIdentifier` | string, optional | column read/written, **no operation ever sets it** — **load-bearing or accident?** |
| `clientScoped` | boolean | column read/written, always `false` — nothing sets it. **accident?** |
| `eventTypes` | list of binding | ≥ 1 on create; replaced wholesale on update/sync |
| `connectionId` | string, optional | not a foreign key; validated to exist **only by sync** (`CONNECTION_NOT_FOUND`), not by admin create/update — **load-bearing or accident?** |
| `endpoint` | string, required | column `target`; admin create/update require `^https?://.+`; sync requires non-blank only (**accident?**) |
| `queue` | string, optional | written by admin create/update (R1, `docs/spec/deployed-dispatch.md` §3) — this row predates that ruling. A dispatch job raised from this subscription copies `queue` onto its own `msg_dispatch_jobs.queue` verbatim at fan-out (R2) and, since owner ruling 2026-09-18, the job's own copy wins over this column at publish time (R4, `docs/spec/dispatch-job-priority.md`) — editing or deleting a subscription no longer moves an already-created job's priority. |
| `customConfig` | list of `{key, value}` | free-form key/values; replaced wholesale |
| `source` | `CODE` \| `API` \| `UI` | `UI` from the admin API, `API` from sync; `CODE` is read-only legacy |
| `status` | `ACTIVE` \| `PAUSED` | default `ACTIVE` |
| `maxAgeSeconds` | int | default `86400` |
| `dispatchPoolId`, `dispatchPoolCode` | string, optional | admin: `dispatchPoolId` set verbatim (not validated, `dispatchPoolCode` never set — **accident?**); sync: both resolved from `dispatchPoolCode` (§7) |
| `delaySeconds` | int | default `0` |
| `sequence` | int | default `99`; nothing changes it. **accident?** |
| `mode` | `IMMEDIATE` \| `NEXT_ON_ERROR` \| `BLOCK_ON_ERROR` | default `IMMEDIATE`; read **leniently** (unknown → `IMMEDIATE`) both from the row and from the create/update command — **load-bearing or accident?** (a typo in `mode` silently becomes `IMMEDIATE`). The enum is the router's per-subscription dispatch mode (`DispatchMode`, see "Design notes") |
| `timeoutSeconds` | int | default `30` |
| `maxRetries` | int | default `3` |
| `serviceAccountId` | string, optional | not validated |
| `dataOnly` | boolean | default `true` (admin); sync input is a plain boolean, so an **absent** `dataOnly` in a sync row writes `false` (§7) — **load-bearing or accident?** |
| `createdBy` | principal id, optional | set by create **and** sync-create (unlike event types) |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Event-type binding (`msg_subscription_event_types` row):

| Field | Type | Notes |
|---|---|---|
| `eventTypeCode` | string, required | a pattern: colon-separated segments, `*` matches one whole segment |
| `eventTypeId` | string, optional | stored verbatim; never resolved from the code — **accident?** |
| `specVersion` | string, optional | stored verbatim |
| `filter` | string, optional | **no column** — accepted on create/update/sync, carried in memory, never persisted, reads back `null`. **load-bearing or accident?** |

Bindings have no order column; they are read in row-id (insertion) order.

Numeric settings are **not bounded** anywhere (negative `timeoutSeconds`,
`maxRetries`, `delaySeconds`, `maxAgeSeconds` are accepted). **load-bearing
or accident?**

Lenient enum reads: unknown `status` → `ACTIVE`; unknown `source` → `UI`;
unknown `mode` → `IMMEDIATE`. (**accident?** — kept because the Go reader
did the same.)

### Matching (used by the fan-out, which is a separate subsystem)

The fan-out contract; `EventTypeBinding.matches(code)` is its only
implementation and `SubscriptionTest.bindingMatchesWholeSegmentsOnly` is its
table. The exact rules:

- The pattern and the event-type code are both split on **every** `:`
  (empty segments count — `a::b` has three segments).
- They match when they have the **same number of segments** and, position
  by position, the pattern segment is exactly `*` or is **equal** to the
  event's segment. Equality is literal and case-sensitive (`ORDERS` ≠
  `orders`); there is no trimming.
- `*` matches exactly one whole segment (an empty one included) and never
  spans segments: `orders:*` does not match `orders:order:created`, and
  `orders:order:*` does not match `orders:order:created:v1`; a pattern with
  more segments than the code (`orders:order:created:*`) does not match
  either.
- Nothing else is special: a partial wildcard (`create*`) and `**` are
  literals, so `orders:**` matches only the code `orders:**`.
- A `null` event-type code matches no pattern.
- A subscription matches an event type when **any** binding matches.
- A subscription matches an event's client when it is platform-wide
  (`clientId` null — matches everything, including events with **no**
  client), or when the event has a client id equal to the subscription's.
  A client-bound subscription never matches a client-less event.
- Only `ACTIVE` subscriptions are considered by the fan-out.

## 2. State machine

```
ACTIVE --pause--> PAUSED --resume--> ACTIVE
```

Both transitions are **unconditional**: pausing a `PAUSED` subscription or
resuming an `ACTIVE` one succeeds, stamps `updatedAt`, persists and emits
the event again. No `ALREADY_PAUSED` / `ALREADY_ACTIVE` conflict.
**load-bearing or accident?** (kept idempotent, like connections.)

`update` does **not** touch `status`.

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/subscriptions` | `subscription:view` | query `status`, `clientId` | 200 `SubscriptionListResponse` `{subscriptions: [...], total}` | ordered by code; both are equality filters on real columns (empty string = absent); no default status; client-scoped rows the caller cannot access are filtered out; `total` = size of the *visible* list |
| `POST /api/subscriptions` | `subscription:{create\|update\|delete}` (any) | `CreateSubscriptionRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | **accident?** create is gated by "any write permission" (same as event types / dispatch pools) |
| `GET /api/subscriptions/{id}` | `subscription:view` | — | 200 `SubscriptionResponse` | 404 `Subscription_NOT_FOUND`; 403 `FORBIDDEN` `No access to this subscription` when client-scoped and not accessible |
| `PUT /api/subscriptions/{id}` | any write permission | `UpdateSubscriptionRequest` → `UpdateCommand(id from path)` | 204, empty body | partial update: absent = unchanged |
| `DELETE /api/subscriptions/{id}` | `subscription:delete` | `DeleteCommand` | 204 | hard delete (bindings + config rows too) |
| `POST /api/subscriptions/{id}/pause` | any write permission | `PauseCommand` | 204 | |
| `POST /api/subscriptions/{id}/resume` | any write permission | `ResumeCommand` | 204 | |
| `POST /api/applications/{appCode}/subscriptions/sync` | `subscription:{sync\|manage}` or `application-service:subscription:{create\|update\|delete}` + application resolution | `SyncSubscriptionsRequest` (+ `?removeUnlisted`) → `SyncSubscriptionsCommand` | 200 `SyncResultResponse` | lives in the sdksync surface — **not wired by this package**; the operation is here (§7) |

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `CreateSubscriptionRequest` | `code`*, `name`*, `endpoint`*, `description`, `clientId`, `connectionId`, `dispatchPoolId`, `serviceAccountId`, `eventTypes[]`, `customConfig[]`, `mode`, `timeoutSeconds`, `maxRetries`, `delaySeconds`, `maxAgeSeconds`, `dataOnly` | absent numerics/`dataOnly`/`mode` ⇒ the aggregate defaults (§1) |
| `UpdateSubscriptionRequest` | `name`, `description`, `endpoint`, `connectionId`, `eventTypes[]`, `customConfig[]`, `mode`, `timeoutSeconds`, `maxRetries`, `delaySeconds`, `maxAgeSeconds`, `dispatchPoolId`, `serviceAccountId`, `dataOnly` | every field optional, absent = unchanged; an explicit `[]` for `eventTypes`/`customConfig` **replaces with empty** (so an update can leave a subscription with no bindings — **accident?**); nothing can be cleared to `null` (`connectionId` can be re-pointed but not cleared) |
| `EventTypeBindingDTO` | `eventTypeCode`*, `eventTypeId`, `specVersion`, `filter` | optional fields omitted when null |
| `ConfigEntryDTO` | `key`*, `value`* | |
| `SubscriptionResponse` | `id, code, applicationCode?, name, description?, clientId?, clientIdentifier?, clientScoped, eventTypes[], connectionId?, endpoint, queue?, customConfig[], source, status, maxAgeSeconds, dispatchPoolId?, dispatchPoolCode?, delaySeconds, sequence, mode, timeoutSeconds, maxRetries, serviceAccountId?, dataOnly, createdBy?, createdAt, updatedAt` | optional (`?`) fields omitted when null; the two arrays are always present (possibly empty); timestamps RFC 3339 with 6 fractional digits, `Z` |
| `SubscriptionListResponse` | `subscriptions[]`, `total` | no pagination |
| `SyncSubscriptionsRequest` | `subscriptions[]`* of `SyncSubscriptionInputRequest`, `clientId` | **K3**: `clientId` is the client's id OR its identifier slug — resolved to an id by `SdkSyncApi` before authorization, same as connection sync's; absent/blank = a client-less (global) sync (`code-first-connections.md` §3) |
| `SyncSubscriptionInputRequest` | `code`*, `name`*, `target`*, `eventTypes[]`* of `{eventTypeCode*, filter}`, `description`, `connectionId`, `connectionCode`, `sharedConnection`, `dispatchPoolCode`, `mode`, `maxRetries`, `timeoutSeconds`, `dataOnly` | `mode` accepted and **ignored** (§7). **K3**: `connectionCode` names a connection by code within an EXPLICIT namespace (this application's own by default, the shared/application-less one when `sharedConnection: true`), no fallback between namespaces; `connectionId` still works; both together must name the same connection (§7, §6) |
| `SyncResultResponse` | `applicationCode, created, updated, deleted, syncedCodes[]` | |

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | trimmed `code` non-blank | `CODE_REQUIRED` | `code is required` |
| Create | trimmed + lower-cased `code` matches `^[a-z][a-z0-9-]*$` | `INVALID_CODE_FORMAT` | `code must start with a lowercase letter and contain only lowercase alphanumeric and hyphens` |
| Create | `name` non-blank | `NAME_REQUIRED` | `name is required` |
| Create | `endpoint` matches `^https?://.+` (absent fails too) | `INVALID_ENDPOINT` | `endpoint must be a http(s) URL` |
| Create | `eventTypes` non-empty | `EVENT_TYPES_REQUIRED` | `at least one event type binding is required` |
| Update | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `name`, when given, non-blank | `NAME_REQUIRED` | `name cannot be empty` |
| Update | `endpoint`, when given, matches `^https?://.+` | `INVALID_ENDPOINT` | `endpoint must be a http(s) URL` |
| Delete / Pause / Resume | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Sync | `applicationCode` non-blank | `APPLICATION_CODE_REQUIRED` | `Application code is required` |
| Sync, per row (first failure aborts) | `code` non-blank | `CODE_REQUIRED` | `Subscription code is required` |
| Sync, per row | `name` non-blank | `NAME_REQUIRED` | `Subscription name is required` |
| Sync, per row | `target` non-blank | `TARGET_REQUIRED` | `Target endpoint URL is required` |
| Sync, per row | `eventTypes` non-empty | `EVENT_TYPES_REQUIRED` | `At least one event type is required` |
| Sync, per row (**K3**) | `sharedConnection: true` requires a non-blank `connectionCode` | `SHARED_CONNECTION_REQUIRES_CODE` | `Subscription '<code>': sharedConnection requires connectionCode` |

Validation is in this order; the first failure wins. A binding's
`eventTypeCode` and a config entry's `key` / `value` are **not** validated:
blank values are accepted, and an **absent** one is stored as `""` (the
junction columns are `NOT NULL`, `''` is allowed). **accident?**

That `null → ""` is a **wire/DB mapping, not a domain value**: it is applied
once, at the boundary where the wire shape becomes the aggregate — the admin
DTOs (`EventTypeBindingDto.toEntity`, `ConfigEntryDto.toEntity`) and the
sync row input (`SyncEventTypeBindingInput.toBinding`). The aggregate records
(`EventTypeBinding.eventTypeCode`, `ConfigEntry.key/value`) require a
non-null value and never coalesce; the repository writes and reads the
column verbatim. Nothing inside the JVM treats `""` as "absent".

Malformed JSON body → 400 `INVALID_JSON` (transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | coarse permission per route (table §3); unauthenticated → 403 `UNAUTHENTICATED` |
| Create — `authorize` phase | `checkScopeAccess(principal, cmd.clientId)`: client-bound create requires access to that client; `clientId` absent (platform-wide) requires anchor / super-admin → else 403 `SCOPE_FORBIDDEN` |
| Update / Delete / Pause / Resume — `execute`, right after load + 404 | `checkScopeAccess(principal, loaded.clientId)`; these operations declare `publicAccess` for that reason |
| Sync — `authorize` phase | `checkSyncAccess(principal, cmd.applicationId, cmd.applicationCode, cmd.clientId)`: the principal must be able to access the **application** always → else 403 `FORBIDDEN` `Not authorised for application '<code>'`; when `clientId` is given, client access too → else 403 `FORBIDDEN` `No access to client: <id>`; unauthenticated → `UNAUTHENTICATED`. **K3**: a client-less sync needs ONLY application access, deliberately not anchor — ownership (§7's client scoping) fences it in, mirroring connection sync (`code-first-connections.md` §3). The coarse sync permission and the `appCode`/`clientId` resolution belong to the sdksync handler |
| Reads | handler only: list filters client-scoped rows; get-by-id → 403 `FORBIDDEN` `No access to this subscription` |

Sync writes are **not** scope-checked per subscription (rows are matched by
`(applicationCode, clientId)`, which sync itself stamps; an admin-created row
only enters the application's scope if something else sets its
`application_code`).

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another subscription has the same normalised code under the same three-part key **`(applicationCode, clientId, code)`** (`null` matches only `null` on both nullable parts; admin create always uses `applicationCode = null`) | `CODE_EXISTS` | 409 — `Subscription with code '<code>' already exists` |
| Update / Delete / Pause / Resume | no subscription with that id | `Subscription_NOT_FOUND` | 404 — `Subscription not found: <id>` |
| Sync | a row names a `connectionId` that does not exist | `CONNECTION_NOT_FOUND` | 404 — `Connection '<id>' not found` (checked for **every** row, before any write) |
| Sync (**K3**) | a row names a `connectionCode` that does not resolve within its namespace (no fallback) | `CONNECTION_NOT_FOUND` | 404 — `Connection with code '<code>' not found` |
| Sync (**K3**) | a row names both `connectionId` and `connectionCode`, resolving to different connections | `CONNECTION_MISMATCH` | 400 — `Subscription '<code>': connectionId and connectionCode name different connections` |
| Sync (**K3**) | the `connectionId` path: the resolved connection is bound to a different client than the sync's (incl. a global sync naming a client-scoped connection), or belongs to a different application (a shared connection is usable by anyone) | `CONNECTION_SCOPE_MISMATCH` | 400 — `Subscription '<code>': connection '<id>' is scoped to a different client` / `… belongs to a different application` |

A client-bound create may reuse a code that exists platform-wide, under
another client, or under another application (**load-bearing** — multi-tenant,
multi-application codes). Sync never conflicts on code: an existing code is
updated — and it does **not** check `(applicationCode, clientId, code)` at
all: a new sync code that already exists under a *different* scope hits the
database's unique index and fails with a 500 `PERSIST`. **load-bearing or
accident?** (unchanged by K1: V12 reshapes the index sync can hit, from
`(code, clientId)` to the three-part key, but `SyncSubscriptions` itself is
not migrated to pre-check it until K3.)

## 7. Sync semantics

Input: `applicationId`, `applicationCode`, `clientId` (**K3**, optional —
already resolved to an id by the handler), `subscriptions[]`,
`removeUnlisted`. Existing rows = every subscription owned by
`(applicationCode, clientId)` — `NULL` client matches `NULL` only (**K3**;
previously every client's rows for the application were loaded and matched
by code alone), matched to input rows **by code alone within that scope**.
Should two rows in that scope share a code (only possible when something
other than sync stamps a client-bound admin row with the application code),
the first in code order is the match and the other is treated as unlisted.
**accident?** (sync never creates such a pair itself.)

**K3 — connection resolution, before any row is matched/written**: every
input row's connection (`connectionId` and/or `connectionCode`) is resolved
to an id first, so a bad reference on row 5 aborts before row 1 is touched
(mirrors the pre-K3 `connectionId`-only check, now covering `connectionCode`
too). Rules (`code-first-connections.md` §3):

- `connectionCode` set: the namespace is EXPLICIT, never guessed — a bare
  code names a connection owned by THIS application; `sharedConnection: true`
  switches to the shared (application-less) namespace. **No fallback**
  between the two. Within the chosen namespace, a client-scoped sync
  (`clientId` set) prefers its own client's connection, falling back to a
  global one; a client-less sync only ever resolves a global connection. Not
  found → `CONNECTION_NOT_FOUND`. When `connectionId` is ALSO given, it must
  name the same connection → `CONNECTION_MISMATCH`.
- `connectionId` only: an id can name ANY row, so its scope needs an
  explicit check — its client must be absent or equal to this sync's client
  (`CONNECTION_SCOPE_MISMATCH`: a global sync may never point at a
  client-owned connection), and its application must be absent (shared,
  usable by anyone) or equal to this sync's application
  (`CONNECTION_SCOPE_MISMATCH`).
- Neither given: the row's connection link is cleared (unchanged from pre-K3).

| Input row | Effect | Per-row event |
|---|---|---|
| code exists, `source` `API` or `CODE` | `name`, `description`, `endpoint`(=`target`), `connectionId` (the **resolved** id — **unconditionally** replaced; absent clears it), `eventTypes` (replaced; `filter` carried, not stored; `eventTypeId`/`specVersion` null), `dataOnly` (plain boolean, absent ⇒ `false`) replaced; `maxRetries` / `timeoutSeconds` replaced **only when present**; dispatch pool re-resolved when `dispatchPoolCode` present (see below); `mode`, `status`, `clientId`, `delaySeconds`, `maxAgeSeconds`, `serviceAccountId`, `customConfig` untouched | `updated` |
| code exists, `source` `UI` | **skipped entirely** (not updated, not counted) — but its code is in `syncedCodes` and counts as "listed" for `removeUnlisted` | — |
| code new | created: `source=API`, `applicationCode` stamped, `clientId` stamped from the sync's `clientId` (**K3**; previously always `null`), `createdBy` = principal, defaults for everything not in the input (`mode` always `IMMEDIATE` — the input's `mode` is **ignored**), `dataOnly` from the input (absent ⇒ `false`) | `created` |
| `removeUnlisted` and existing row with `source` `API`/`CODE` not in input | hard-deleted (still scoped to `(applicationCode, clientId)` — never a sibling client's or the global rows, **K3**) | `deleted` |
| `removeUnlisted` and existing row with `source` `UI` not in input | untouched | — |

`dispatchPoolCode` resolution: when present and non-blank, looked up among
**platform-wide** pools (`client_id IS NULL`) by code; found ⇒ `dispatchPoolId`
and `dispatchPoolCode` set from the pool; not found ⇒ **silently left as is**
(unset on create, previous value on update). Absent/blank ⇒ untouched.
**load-bearing or accident?** (a typo in the pool code is invisible).

`mode` on a sync row is accepted for wire compatibility and deliberately
**not applied** (the router's per-subscription dispatch mode must not be
driven by SDK sync — ruled in Go; kept).

The result/rollup carries `created/updated/deleted` counts and `syncedCodes`
(input codes, in order, UI-skipped ones included). All row writes, per-row
events and the rollup commit in one transaction; one audit row per per-row
event, all with `operation = SyncSubscriptionsCommand`.

## 8. Domain events

Source is always `platform:admin`; spec version `1.0`; per-aggregate subject
is `platform.subscription.{id}` with message group
`platform:subscription:{id}` (FIFO per subscription). `data` omits null fields.

| Type | Subject / group | `data` fields |
|---|---|---|
| `platform:admin:subscription:created` | `platform.subscription.{id}` / `platform:subscription:{id}` | `subscriptionId, code, name` |
| `platform:admin:subscription:updated` | same | `subscriptionId, name` |
| `platform:admin:subscription:deleted` | same | `subscriptionId, code` |
| `platform:admin:subscription:paused` | same | `subscriptionId` |
| `platform:admin:subscription:resumed` | same | `subscriptionId` |
| `platform:admin:subscription:synced` (**singular** — event types/pools use the plural `…s:synced`; **accident?**, kept) | `platform.subscriptions.{applicationCode}` / group `platform:subscriptions` (**no** application suffix; all syncs share one group — same as dispatch pools) | `applicationCode, clientId?, created, updated, deleted, syncedCodes[]` (**K3**: `clientId` added, omitted when null; the seeded JSON schema — `additionalProperties:false` — gained it too, mirroring `connection:synced`) |

The `updated` payload carries only `subscriptionId` + `name`: endpoint,
binding and settings changes are **not** on the event. **load-bearing or
accident?** (a fan-out cache that reloads on this event must re-read the row.)

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Subscription`, `entity_id = {id}`,
`operation` = command record simple name, `operation_json` = the command) in
the same transaction as the row change. The sync rollup's audit row has
`entity_type = Subscriptions`, `entity_id = {applicationCode}`.

## 9. Persistence

Tables `msg_subscriptions` + `msg_subscription_event_types` +
`msg_subscription_custom_configs`. Upsert `ON CONFLICT (id)`: every column
replaced except `created_at` (insert-only) and `created_by` (insert-only —
the aggregate carries it, but a later persist never rewrites it);
`updated_at` is stamped `now()` at persist time (not the aggregate's
`updatedAt` — **accident?**, harmless). The junction rows are **replaced
wholesale** on every persist (delete all, insert all — a rename of the
subscription rewrites its bindings), on the **same connection and
transaction** as the row upsert, the `msg_events` row and the `aud_logs` row
— a reader never observes a subscription with no bindings between the
delete and the insert, and a failed insert rolls the row change back too.
Delete removes junction rows then the row, in that transaction. Reads
hydrate both junctions in one `IN` query each. List reads order by `code`.

## 9a. Design notes (not owner questions)

- **`DispatchMode` needs a shared home.** `IMMEDIATE | NEXT_ON_ERROR |
  BLOCK_ON_ERROR` is the router's per-subscription ordering mode; it is
  declared in `io.flowcatalyst.platform.subscription` only because the
  subscription is the first aggregate to store it. The router / dispatch
  scheduler will need the identical enum (the SDK already carries its own
  copy in `CreateDispatchJobDto.DispatchMode`). When the data plane lands,
  move it to a shared package — `io.flowcatalyst.platform.shared.messaging`
  (or a `messaging` package) — and have this aggregate import it; the
  lenient `parse` and `requiresOrdering()` go with it. Not moved yet on
  purpose: no second consumer exists in `server`. (Tracked in
  `docs/backlog.md`, "From the subscription port".)

## 10. Open questions for the owner (summary)

1. ~~Platform-wide code uniqueness relies on the operation's pre-check (`NULL` client ids are distinct to the index)~~ **Answered, owner ruling 2026-09-21**: uniqueness is now `(applicationCode, clientId, code)`, enforced by a database expression index (V12) — see `code-first-connections.md`. Sync still does no pre-check of its own key and can hit that index (500) — **still open**: K3 landed sync's client scoping (§7) but did not add this pre-check, same gap as before, now against the three-part key instead of the two-part one.
2. `filter` on a binding is accepted everywhere and stored nowhere.
3. Sync does not normalise/validate the code (`My-Sub` stored verbatim) nor the target URL format; admin create does both.
4. Sync `dataOnly` is a plain boolean — an SDK that omits it flips an existing `true` to `false` on every sync.
5. Sync clears `connectionId` when the row omits it; admin update cannot clear it. **K3**: a row naming neither `connectionId` nor `connectionCode` still clears it — unchanged.
6. Unresolvable `dispatchPoolCode` is silently ignored; admin `dispatchPoolId` is stored without existence check and never sets `dispatchPoolCode`.
7. `mode` is parsed leniently on the wire (typo ⇒ `IMMEDIATE`) and ignored by sync.
8. `clientIdentifier`, `clientScoped`, `queue`, `sequence`, `eventTypeId` are never set by any operation.
9. Pause/resume are idempotent (no 409 on a no-op flip); `updated` carries no settings.
10. Numeric settings are unbounded; a binding's `eventTypeCode` may be blank; an update with `eventTypes: []` leaves zero bindings.
11. Create/update/pause/resume are gated by *any* write permission rather than the specific verb.
12. Sync rollup event type is singular (`subscription:synced`) and its message group is shared (`platform:subscriptions`) — **K3**: the message group itself is still keyed on `applicationCode` alone, not `clientId` (two clients syncing the same application still share one FIFO lane); only the rollup's `data.clientId` field is new.
13. Admin create/update do not check that `connectionId` exists; sync does. **K3**: sync now also resolves/checks `connectionCode`, with the namespace and scope rules in §6/§7.
