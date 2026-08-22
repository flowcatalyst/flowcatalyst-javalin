# Process — behavioural spec

The contract for `io.flowcatalyst.platform.process`. Derived from the
lockfile (`/api/processes*`, `/api/applications/{appCode}/processes/sync`,
`/api/processes/sync`) plus the validation rules, authorization placement,
state machine, sync semantics, error codes and domain events the aggregate
embodies. The Java is written *from* this; tests assert it. Questions marked
**load-bearing or accident?** need an owner ruling — until ruled on, the
behaviour is kept.

## 1. Aggregate

A process is free-form workflow documentation — typically a Mermaid diagram —
identified by a three-segment **code** `application:subdomain:process-name`.
The segments are denormalised onto the row. Processes are **global**: there
is no client dimension, so per-resource authorization does not exist; every
gate is the handler's coarse permission (same shape as roles).

| Field | Type | Notes |
|---|---|---|
| `id` | `prc_` + 13-char TSID | generated on create |
| `code` | string, unique | `a:b:c`, each segment non-blank; immutable after create |
| `name` | string, required | admin create / update store it **trimmed**; sync stores it verbatim (**accident?**) |
| `description` | string, optional | `null` when absent |
| `status` | `CURRENT` \| `ARCHIVED` | default `CURRENT` |
| `source` | `CODE` \| `API` \| `UI` | `UI` from the admin API, `API` from sync, `CODE` from the seeder (the example `platform:fulfilment:on-demand-flow`) |
| `application`, `subdomain`, `processName` | strings | the three code segments |
| `body` | string, required, default `""` | the diagram / documentation source; the column is `NOT NULL DEFAULT ''`, so `""` is a real value ("no body"), not an absence marker |
| `diagramType` | string, required, default `mermaid` | the diagram syntax; free text |
| `tags` | list of string, required, default `[]` | the column is `NOT NULL`; an absent list is always stored and read as `[]` |
| `createdBy` | principal id, optional | set on the aggregate by admin create but **`msg_processes` has no such column** — never persisted, every read returns `null`, the wire field is never populated. **load-bearing or accident?** (kept on the aggregate and the response for wire-shape compatibility) |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Lenient enum reads: unknown `status` → `CURRENT`; unknown `source` → `UI`
(**accident?** — masks bad rows; kept, same as event types).

## 2. State machine

`CURRENT --archive--> ARCHIVED`. **Archiving is unconditional**: archiving an
already-`ARCHIVED` process succeeds again (status unchanged, `updatedAt`
re-stamped, a second `archived` event and audit row written). There is no
un-archive and no `ALREADY_ARCHIVED` conflict — **load-bearing or
accident?** (event types conflict; processes do not; kept).

Update never changes `status`, `source` or `code`.

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/processes` | `process:view` | query `application`, `subdomain`, `status` | 200 `ProcessListResponse` `{items: [ProcessResponse]}` | ordered by code; every non-empty query parameter is an equality filter; **no implied default** (unfiltered lists `ARCHIVED` rows too — unlike event types) |
| `POST /api/processes` | any of `process:{create\|update\|delete}` | `CreateProcessRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | **accident?** create is gated by "any write permission", not `create` |
| `GET /api/processes/by-code/{code}` | `process:view` | — | 200 `ProcessResponse` | 404 `Process_NOT_FOUND`, message carries the code |
| `GET /api/processes/{id}` | `process:view` | — | 200 `ProcessResponse` | 404 `Process_NOT_FOUND` |
| `PUT /api/processes/{id}` | any write permission | `UpdateProcessRequest` → `UpdateCommand(id from path)` | 204, empty body | |
| `POST /api/processes/{id}/archive` | any write permission | `ArchiveCommand(id)` | 204, empty body | no request body; **accident?** `process:archive` exists as a permission but the route is gated by the write trio |
| `DELETE /api/processes/{id}` | `process:delete` | `DeleteCommand(id)` | 204 | hard delete |
| `POST /api/applications/{appCode}/processes/sync` | `process:sync` \| `application-service:process:sync` + application resolution | `SyncProcessesRequest` (+ `?removeUnlisted`) → `SyncProcessesCommand` | 200 `SyncResultResponse` | lives in the sdksync surface, not this package's API; the operation is here |
| `POST /api/processes/sync` | same | `SyncProcessesByBodyRequest` (`applicationCode` in the body) | 200 `SyncResultResponse` | Laravel-SDK alias of the route above — sdksync surface, **not wired by this package** although it sits under `/api/processes` |

Go also mounts the same seven handlers under `/bff/processes` for the
cookie-session SPA; that alias belongs to the BFF surface, not this package.

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `CreateProcessRequest` | `code`*, `name`*, `description`, `body`, `diagramType`, `tags[]` | absent `body` ⇒ `""`; absent/blank `diagramType` ⇒ `mermaid`; absent `tags` ⇒ `[]` |
| `UpdateProcessRequest` | `name`, `description`, `body`, `diagramType`, `tags[]` | **every field optional; absent = untouched.** `description: ""` stores the empty string (it cannot be reset to absent); `tags: []` clears the list |
| `SyncProcessInputRequest` | `code`*, `name`*, `description`, `body`, `diagramType`, `tags[]` | |
| `ProcessResponse` | `id, code, name, description?, status, source, application, subdomain, processName, body, diagramType, tags[], createdBy?, createdAt, updatedAt` | optional fields omitted when null; `body` always present (may be `""`); `tags` always present (may be `[]`); timestamps RFC 3339 with 6 fractional digits, `Z` |
| `ProcessListResponse` | `items[]` | no pagination |
| `CreatedResponse` | `id` | |
| `SyncResultResponse` | `applicationCode, created, updated, deleted, syncedCodes[]` | the rollup event's payload |

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `code` non-blank | `CODE_REQUIRED` | `Process code is required` |
| Create | exactly three `:`-separated segments | `INVALID_CODE_FORMAT` | `Process code must follow format: application:subdomain:process-name` |
| Create | every segment non-blank | `INVALID_CODE_FORMAT` | `Process code segments cannot be empty` |
| Create | `name` non-blank (checked **after** the code format) | `NAME_REQUIRED` | `Process name is required` |
| Update | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `name`, when present, non-blank | `NAME_REQUIRED` | `name cannot be empty` |
| Archive / Delete | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Sync | `applicationCode` non-blank | `APPLICATION_CODE_REQUIRED` | `Application code is required` |
| Sync | each **new** input code well-formed (checked in execute, per row; an existing code is matched verbatim) | `INVALID_PROCESS_CODE` | `<format or segment message> (offending code: "<code>")`; the first bad row aborts the whole sync |

Malformed JSON body → 400 `INVALID_JSON` (transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | coarse permission (table above); unauthenticated → 403 `UNAUTHENTICATED` |
| Create / Update / Archive / Delete | `publicAccess` — processes are global, there is no per-resource dimension; the handler's coarse gate is the whole check |
| Sync — `authorize` phase | `checkApplicationAccess(principal, cmd.applicationId, cmd.applicationCode)`: `null` principal → `UNAUTHENTICATED`; no access to the application → 403 `FORBIDDEN` `Not authorised for application '<code>'` |
| Reads | handler only |

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another process has the same code | `CODE_EXISTS` | 409 `Process with code '<code>' already exists` |
| Update / Archive / Delete | no process with that id | `Process_NOT_FOUND` | 404 `Process not found: <id>` |

## 7. Sync semantics

Input: `applicationCode`, `applicationId` (resolved by the caller),
`processes[]{code, name, description, body, diagramType, tags}`,
`removeUnlisted`. Existing rows = every process whose `application` segment
equals `applicationCode`. A row is **sync-managed** when its source is `API`
or `CODE`; `UI`-authored rows are never touched.

| Input row | Effect | Per-row event |
|---|---|---|
| code exists, sync-managed | `name` (verbatim), `description`, `body`, `tags` replaced (absent `tags` ⇒ `[]`); `diagramType` replaced **only when the input value is non-blank**; `source` and `status` untouched (a `CODE` row stays `CODE`) | `updated` |
| code exists, `UI` | skipped — not counted, still listed in `syncedCodes` | — |
| code new | created with `source=API`, `createdBy` unset, the create defaults (`body` `""`, blank `diagramType` ⇒ `mermaid`, absent `tags` ⇒ `[]`) | `created` |
| `removeUnlisted` and existing sync-managed row not in input | hard-deleted | `deleted` |
| `removeUnlisted` and existing `UI` row not in input | untouched | — |

**load-bearing or accident?** Sync manages `CODE` rows too (event types
manage `API` only) — so a sync for application `platform` with
`removeUnlisted` deletes the seeded example process
`platform:fulfilment:on-demand-flow`.

The result/rollup carries `created/updated/deleted` counts and `syncedCodes`
(every input code, in order, including skipped `UI` ones). All row writes,
per-row events and the rollup commit in one transaction; one audit row per
per-row event, all with `operation = SyncProcessesCommand`.

## 8. Domain events

Source is always `platform:admin`; spec version `1.0`; per-aggregate subject
is `platform.process.{id}` **with message group `platform:process.{id}`**
(unlike event types, which carry no group). `data` omits null fields.

| Type | Subject | Message group | `data` fields |
|---|---|---|---|
| `platform:admin:process:created` | `platform.process.{id}` | `platform:process:{id}` | `processId, code, name` |
| `platform:admin:process:updated` | same | same | `processId, name` |
| `platform:admin:process:archived` | same | same | `processId, code` |
| `platform:admin:process:deleted` | same | same | `processId, code` |
| `platform:admin:processes:synced` | `platform.processes.{applicationCode}` | `platform:processes` (**fixed**, not per application — **accident?** event types group per application) | `applicationCode, created, updated, deleted, syncedCodes[]` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Process`, `entity_id = {id}`,
`operation` = command record simple name, `operation_json` = the command) in
the same transaction as the row change.

## 9. Persistence

Upsert `ON CONFLICT (id)`; `created_at` is never overwritten; `updated_at`
is stamped `now()` at persist time (not the aggregate's `updatedAt` —
**accident?**, harmless). `tags` is always written as a (possibly empty)
array — the column is `NOT NULL`. Delete removes the row. Sync uses the same
`Persist`. The table also has a unique index on `code` (the seeder relies on
it).

## 10. Open questions for the owner (summary)

1. `createdBy` never persisted (no column) — keep the carried-but-not-stored field, add the column, or drop it from the aggregate and the wire?
2. Archive is unconditional (no `ALREADY_ARCHIVED`) — intended, or align with event types?
3. Sync manages `CODE`-sourced rows, so `removeUnlisted` can delete the seeded example — intended?
4. The sync rollup's message group is the constant `platform:processes`, not per application — intended?
5. Create / update / archive gated by *any* write permission rather than the specific verb; `process:archive` / `process:manage` exist but gate nothing here.
6. Admin create / update trim `name`; sync stores it verbatim.
7. Lenient enum reads mask bad rows.
8. `/api/processes/sync` (body-scoped Laravel alias) — confirm still needed.

## 11. Deviations from the Go reference (deliberate)

- The Go `ProcessDeleted.CorrelationID()` accessor returned the *causation*
  id (a copy-paste slip); the Java event derives every accessor from its
  metadata, so `deleted` events carry the real correlation id.
- A `diagramType` that is whitespace-only on create is treated as absent
  (⇒ `mermaid`), the same rule sync already applied; Go stored the blanks.
- The sync's bad-code error names the offending row in its message (the
  code `INVALID_PROCESS_CODE` is unchanged), as the event-type sync does.
