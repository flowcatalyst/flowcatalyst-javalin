# Event — behavioural spec

The contract for `io.flowcatalyst.platform.event`. Derived from the lockfile
(`/api/events*`) plus the filter, ordering, scoping and pagination rules the
read API embodies. The Java is written *from* this; tests assert it.
Questions marked **load-bearing or accident?** need an owner ruling — until
ruled on, the behaviour is kept.

## 1. Aggregate

An event is the CloudEvents 1.0 envelope the platform stores for every
domain fact. Rows enter the **write-side** table `msg_events` only through
the unit-of-work sink (`PlatformSink.writeEvent`, `docs/usecase-envelope.md`)
and — later, as a separate unit — the SDK ingest endpoints
(`POST /api/events`, `POST /api/events/batch`; **not** part of this surface,
open question 1). The stream processor projects each row once into the
**read-side** table `msg_events_read` (§9). This package is **read-only**:
no operations, no events, no `Persist`.

| Field | Type | `msg_events` | `msg_events_read` | Notes |
|---|---|---|---|---|
| `id` | 13-char untyped TSID | `id` | `id` | no prefix (it is the event's own id, not an entity id) |
| `specVersion` | string, optional | `spec_version` (default `'1.0'`) | `spec_version` (nullable) | `null` when the column is `NULL` |
| `type` | string | `type` | `type` | `application:subdomain:aggregate:verb` |
| `source` | string | `source` | `source` | |
| `subject` | string, optional | `subject` | `subject` | `platform.<aggregate>.<entityId>` for platform events |
| `time` | timestamp | `time` | `time` | the CloudEvents event time |
| `data` | JSON document, optional | `data` (JSONB) | `data` (**text**) | absent when `NULL`, empty or the JSON literal `null`; the read table stores the JSONB's text |
| `context` | list of `{key, value}` | `context_data` (JSONB) | — (not projected) | **empty on every read from `msg_events_read`** — the projection drops it |
| `deduplicationId` | string, optional | `deduplication_id` | `deduplication_id` | sink writes `type + "-" + id` |
| `clientId` | string, optional | `client_id` | `client_id` | `NULL` = platform-scoped |
| `messageGroup` | string, optional | `message_group` | `message_group` | |
| `correlationId` | string, optional | `correlation_id` | `correlation_id` | |
| `causationId` | string, optional | `causation_id` | `causation_id` | |
| `createdAt` | timestamp | `created_at` | `created_at` | **partition key** of both tables; the projection preserves the source value |
| `projection` | nested, optional | — | `application`, `subdomain`, `aggregate`, `projected_at` | `null` when the row was read from `msg_events`; always present on a `msg_events_read` row |

`projection`: `application` = first `:` segment of `type` (never `NULL` for a
non-empty type), `subdomain` / `aggregate` = second / third segment or
`NULL` when the type has fewer segments, `projectedAt` = when the stream
processor projected the row.

No state machine; no transitions; the record only refuses `null` in its
required components (`id`, `type`, `source`, `time`, `createdAt`) and
copies `context` defensively. A `null` `context` list reads as empty.

## 2. HTTP surface (lockfile)

Every route is `GET`. Unauthenticated → 403 `UNAUTHENTICATED`; missing
permission → 403 `PERMISSION_REQUIRED` (`permission required: <code>`).
Every error is the `ErrorModel` envelope. The literal segments
(`filter-options`, `list-raw`, `raw`) are registered before `{id}` so they
win. **Every route reads `msg_events_read`** — the write-side table backs no
lockfile route (§7).

| Method / path | Gate | Query / path inputs | Success | Envelope |
|---|---|---|---|---|
| `GET /api/events` | `platform:messaging:event:view` | §3 | 200 | **bare JSON array** of `EventRead` (the SPA binds it directly) |
| `GET /api/events/list-raw` | `platform:messaging:event:view-raw` | §3 — same read as the list | 200 | bare array of `EventRead` |
| `GET /api/events/raw` | `platform:messaging:event:view-raw` | SDK alias of `/list-raw`, same handler | 200 | bare array of `EventRead` |
| `GET /api/events/filter-options` | `platform:messaging:event:view` | — | 200 | `EventFilterOptionsResponse` `{applications[], subdomains[], eventTypes[]}` |
| `GET /api/events/{id}` | `platform:messaging:event:view` + per-resource client scope (§8) | path | 200 | `EventResponse`; 404 `Event_NOT_FOUND` `Event not found: <id>`; 403 `FORBIDDEN` `No access to this event` |

Excluded from this unit (open question 1): `POST /api/events`
(`createEvent`) and `POST /api/events/batch` (`batchIngestEvents`) — the SDK
ingest writes, which bypass the unit of work and carry the
`clientCode → client_id` lookup. `/bff/events*` and `/bff/debug/events` are
outside the lockfile and not wired.

Wire shapes:

| Schema | Fields (in order) | Notes |
|---|---|---|
| `EventRead` | `id, type, source, subject?, time, application?, subdomain?, aggregate?, messageGroup?, correlationId?, clientId?, projectedAt` | the slim list row; optional fields omitted when absent (`subject` omitted when `NULL`); `projectedAt` always present (falls back to `createdAt` for a row with no projection — unreachable from the routes, which read the projected table) |
| `EventResponse` | `id, specVersion, type, source, subject, time, data?, contextData?, deduplicationId, clientId?, messageGroup?, correlationId?, causationId?, application?, subdomain?, aggregate?, projectedAt?, createdAt` | `specVersion`, `subject`, `deduplicationId` are **required on the wire** and emitted as `""` when the column is `NULL` (the wire/DB `""` ↔ `NULL` mapping lives in the DTO — open question 2); `data` omitted when absent; `contextData` omitted when empty — i.e. always omitted on this surface |
| `EventFilterOptionsResponse` | `applications[], subdomains[], eventTypes[]` | arrays never `null`; each element `EventFilterOption {value, label}` with `label == value` |

Timestamps: RFC 3339, 6 fractional digits, `Z`.

## 3. The list (`/api/events`, `/list-raw`, `/raw`)

| Input | Rule |
|---|---|
| `type`, `source`, `subject`, `clientId`, `correlationId` | equality filters on the same-named column; absent/empty → no filter |
| `principalId` | **accepted and ignored** — `msg_events_read` has no backing column (open question 3) |
| `types`, `clientIds`, `applications`, `subdomains`, `aggregates` | comma-separated; each part trimmed, blanks dropped; the remaining values form an `IN` filter on `type` / `client_id` / `application` / `subdomain` / `aggregate`; absent/empty/all-blank → no filter. `type` and `types` both apply (AND) when both are sent |
| `since`, `until` | RFC 3339 with any offset → inclusive bounds on **`created_at`** (the partition key, so Postgres prunes partitions — not on `time`); unparseable → **ignored** (open question 4) |
| `limit`, `size` | `size` wins when `> 0`, else `limit`; the resolved value is passed to the repository, whose guard (§7) turns `<= 0` or `> 1000` into **100** — so the effective default is 100 and `size=5000` yields 100, not 1000 (the lockfile text says "default 50, max 1000": open question 5) |
| `offset` | `> 0` → `OFFSET`; else none |
| any of `limit` / `size` / `offset` non-integer | 400 `VALIDATION` `validation failed` with `details.errors[{message: "invalid integer", location: "query.<name>", value}]` — one entry per bad parameter, in `limit, offset, size` order |

Visibility (§8) is intersected with the filters in SQL. The repository's
filter carries the caller's `Visibility` (the shared `shared/auth` type,
`AuthContext.visibility()`) as a required component — there is no default
view; every filtered read states whose it is.

Ordering: `created_at DESC` — newest first; ties are in no defined order
(open question 6).

No pagination envelope: the body is the bare array, `limit`/`offset` only.
There is **no cursor** on this surface (the audit aggregate's keyset read
has no counterpart here because the lockfile carries no `after`).

## 4. Filter options (`/filter-options`)

Three facets, each the **distinct non-null** values of one `msg_events_read`
column, ascending, at most 200: `application`, `subdomain`, `type`. Each
value is wrapped as `{value, label}` with both equal. The facet set the
repository allows is closed — `type`, `source`, `subject`, `client_id`,
`correlation_id`, `application`, `subdomain`, `aggregate` — a typed
enumeration, never a column name from the wire; only the three above are
routed (open question 7). Not tenant-scoped: any holder of `event:view`
sees every value (open question 8).

## 5. Get by id (`/{id}`)

Reads `msg_events_read` by `id` (every partition — there is no `created_at`
bound to prune with: open question 9). Unknown → 404. A client-scoped row
(`client_id` not `NULL`) is visible only to a principal that can access that
client (anchors always); a platform-scoped row is visible to any holder of
`event:view`. A row outside the caller's scope is 403 `FORBIDDEN`
`No access to this event` — not 404.

## 6. The raw read (`findRecentRaw`, repository only)

The most recent `limit` rows of **`msg_events`** (write side), including
`context_data`, newest first by `created_at`. It backs the debug view
`GET /bff/debug/events` (outside the lockfile, not wired here) and is the
only read over the write-side table; it is pinned by the repository test
because it is what proves a sink row reads back whole. `context_data` is a
JSON array of `{key, value}`; `NULL`, empty, or non-array content reads as an
empty list.

## 7. Repository guards (constants)

| Guard | Value | Load-bearing or accident? |
|---|---|---|
| filtered read: limit `<= 0` or `> 1000` → 100 | | same guard family as audit (§7 there); the API passes the raw `size`/`limit`, so this *is* the API default and the over-max behaviour — open question 5 |
| raw read: limit `<= 0` or `> 1000` → 100 | | same |
| facet read: limit `<= 0` or `> 1000` → 200 | | same; the API passes 200 |
| API facet limit | 200 | |

Invalid limits are *corrected* by the repository rather than rejected.

## 8. Authorization placement

| Where | What |
|---|---|
| Handler | `Checks.require(Auth.current(), EVENT_VIEW)` on list, filter-options, get; `EVENT_VIEW_RAW` on `/list-raw` and `/raw` |
| Use case | none — there are no use cases |
| Resource-level (list) | **in SQL**: an anchor sees every row; any other principal sees platform-scoped rows (`client_id IS NULL`) plus rows whose `client_id` is in its client list — intersected with the caller's own `clientId`/`clientIds` filters, so those can only narrow within the principal's tenants, never reach across them. A non-anchor with an empty client list sees platform-scoped rows only. Stated as the platform's one `Visibility` value (`shared/auth`: `Everything \| Tenants(ids)`, built by `AuthContext.visibility()`) and rendered by its one SQL predicate (`shared/database` `VisibilitySql.toCondition`); the same value scopes dispatch-job lists |
| Resource-level (get) | the same rule applied to the one row (§5) |
| Filter options | none (§4) |

## 9. Persistence and the projection

None in this package. `msg_events` rows are written by
`PlatformSink.writeEvent` (`created_at = now()`, `client_id = NULL`,
`deduplication_id = type + "-" + id`, `context_data =
[{principalId}, {aggregateType}]`). The projection into `msg_events_read`
will be owned by the stream processor port; its shape, which the repository
test reproduces to seed the read table from genuine sink output, is:

```
INSERT INTO msg_events_read
    (id, spec_version, type, source, subject, time, data,
     correlation_id, causation_id, deduplication_id, message_group,
     client_id, application, subdomain, aggregate, created_at, projected_at)
SELECT e.id, e.spec_version, e.type, e.source, e.subject, e.time, e.data::text,
       e.correlation_id, e.causation_id, e.deduplication_id, e.message_group,
       e.client_id,
       split_part(e.type, ':', 1),
       NULLIF(split_part(e.type, ':', 2), ''),
       NULLIF(split_part(e.type, ':', 3), ''),
       e.created_at,          -- the partition key is preserved
       NOW()
  FROM msg_events e WHERE e.id IN (…)
ON CONFLICT (id, created_at) DO NOTHING;
UPDATE msg_events SET projected_at = NOW() WHERE id IN (…);
```

Both tables are `PARTITION BY RANGE (created_at)` with monthly partitions
(`<parent>_YYYY_MM`, month−1 .. month+3 at baseline); a row whose
`created_at` falls outside an existing partition cannot be inserted — tests
therefore seed with `created_at` near now.

`msg_events_read.data` is a **foreign shape** (text written by the
projection, and by other writers before it): `NULL`, empty, the literal
`null`, loosely formatted documents and non-object documents must all read
(the repository test pins each).

## 10. Open questions for the owner (summary)

1. `POST /api/events` (singular SDK ingest) and `POST /api/events/batch` are
   excluded here with the ingest unit. Confirm the singular create belongs
   with the batch unit (same insert path, same `clientCode` lookup) and not
   with this read surface.
2. `EventResponse.specVersion` / `subject` / `deduplicationId` emit `""` for
   a `NULL` column (the lockfile marks them required). Keep, or make them
   optional on the wire?
3. `principalId` is accepted on the list routes and ignored (no column on
   `msg_events_read`). Keep accepting (SDK sends it) or reject?
4. An unparseable `since` / `until` is silently ignored rather than 400.
   Load-bearing or accident?
5. `size` / `limit`: effective default 100 and `> 1000` → 100 (repository
   guard), while the lockfile text says "default 50, max 1000". Keep the
   guard (pinned by test) or clamp / default 50?
6. The list orders by `created_at DESC` only — ties (same microsecond) are in
   undefined order. Add `id DESC`?
7. The repository allows eight facet columns; the API routes three. Keep the
   unused five (SDK/BFF may) or drop?
8. Filter options are not tenant-scoped: a client-scoped viewer sees every
   application/subdomain/type that has events. Intended or accident?
9. `GET /{id}` scans every partition of `msg_events_read` (no `created_at`
   bound). Accident — should the TSID's timestamp bound the partition?
