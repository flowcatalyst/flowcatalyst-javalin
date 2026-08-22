# Event type — behavioural spec

The contract for `io.flowcatalyst.platform.eventtype`. Derived from the
lockfile (`/api/event-types*`, `/api/applications/{appCode}/event-types/sync`)
plus the validation rules, authorization placement, state machines, error
codes and domain events the aggregate embodies. The Java is written *from*
this; tests assert it. Questions marked **load-bearing or accident?** need an
owner ruling — until ruled on, the behaviour is kept.

## 1. Aggregate

An event type is a catalogue entry for a kind of business event, identified
by a four-segment **code** `application:subdomain:aggregate:event`. The
segments are denormalised onto the row (`application`, `subdomain`,
`aggregate`; `eventName` is derived from the code on read). It owns a list of
**spec versions** (schemas).

| Field | Type | Notes |
|---|---|---|
| `id` | `evt_` + 13-char TSID | generated on create |
| `code` | string, unique | `a:b:c:d`, each segment non-blank |
| `name` | string, required | |
| `description` | string, optional | |
| `status` | `CURRENT` \| `ARCHIVED` | default `CURRENT` |
| `source` | `CODE` \| `API` \| `UI` | `UI` from the admin API, `API` from sync; `CODE` is read-only legacy |
| `clientScoped` | boolean | always `false` today — **load-bearing or accident?** (column exists, nothing sets it) |
| `clientId` | string, optional | carried on the aggregate, the create command and the `created` event, and used for authorization — but `msg_event_types` has **no such column**, so it is never persisted and every read returns `null`. **load-bearing or accident?** Today it means: creates bound to a client are scope-checked at create time only; afterwards the row is platform-level. |
| `createdBy` | principal id, optional | set by create; **not** set by sync (**accident?**) |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |
| `specVersions` | list of spec version | ordered by `(event_type_id, version)` text order on read |

Spec version:

| Field | Type | Notes |
|---|---|---|
| `id` | `sch_` + TSID | |
| `version` | string, unique per event type | typically semver; `major()` = text before the first `.`/`-`/`+` |
| `mimeType` | string | always `application/schema+json` |
| `schemaContent` | JSON, optional | `null` when none stored |
| `schemaType` | `JSON_SCHEMA` \| `XSD` \| `PROTO` | always `JSON_SCHEMA` today; read aliases `XML_SCHEMA`→`XSD`, `PROTOBUF`→`PROTO` |
| `status` | `FINALISING` \| `CURRENT` \| `DEPRECATED` | new versions start `FINALISING` |

Lenient enum reads: unknown `status` → `CURRENT`; unknown `source` → `UI`;
unknown spec-version status → `FINALISING`; unknown schema type →
`JSON_SCHEMA`. (**accident?** — silently mapping bad rows to a default hides
corruption; kept because the Go reader did the same.)

## 2. State machines

Event type: `CURRENT --archive--> ARCHIVED`. Archiving an `ARCHIVED` event
type is a conflict (`ALREADY_ARCHIVED`). There is no un-archive.

Spec version: `FINALISING --finalise--> CURRENT --deprecate--> DEPRECATED`.

| Transition | Precondition | Side effect | Error |
|---|---|---|---|
| add version `v` | no existing version `v` on this event type | new row `FINALISING` | `VERSION_EXISTS` (409) |
| finalise `v` | `v` exists, status `FINALISING` | `v` → `CURRENT`; **the first** other version with the same `major()` that is `CURRENT` → `DEPRECATED` (at most one; its version string is reported on the event) | `SpecVersion_NOT_FOUND` (404), `NOT_FINALISING` (409) |
| deprecate `v` | `v` exists, status `CURRENT` | `v` → `DEPRECATED` | `SpecVersion_NOT_FOUND` (404), `STILL_FINALISING` (409), `ALREADY_DEPRECATED` (409) |

"At most one sibling is auto-deprecated" — **load-bearing or accident?** If
two `CURRENT` versions of one major could ever exist (they cannot via these
transitions), only the first in list order is deprecated.

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/event-types` | `event-type:view` | query `application`, `clientId`, `status`, `subdomain`, `aggregate` | 200 `EventTypeListResponse` `{items: [EventTypeResponse]}` | ordered by code; client-scoped rows the caller cannot access are filtered out. When **no** query parameter is given, `status=CURRENT` is implied. `clientId` is accepted but filters nothing (not a column) — yet its presence suppresses the `CURRENT` default. **load-bearing or accident?** |
| `POST /api/event-types` | `event-type:{create\|update\|delete}` (any) | `CreateEventTypeRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | **accident?** create is gated by "any write permission", not `create` specifically |
| `GET /api/event-types/{id}` | `event-type:view` | — | 200 `EventTypeResponse` | 404 `EventType_NOT_FOUND`; 403 `FORBIDDEN` when client-scoped and not accessible |
| `GET /api/event-types/by-code/{code}` | `event-type:view` | — | 200 `EventTypeResponse` | same as by id; the 404 message carries the code |
| `PUT /api/event-types/{id}` | any write permission | `UpdateEventTypeRequest` → `UpdateCommand(id from path)` | 204, empty body | body `id` ignored |
| `DELETE /api/event-types/{id}` | `event-type:delete` | `DeleteCommand` | 204 | hard delete incl. spec versions; the lockfile summary says "Archive" — **accident** (it deletes) |
| `POST /api/event-types/{id}/schemas` | any write permission | `AddSchemaRequest` → `AddSchemaCommand` | 200 `EventTypeResponse` (re-read after the write) | historical alias of `/versions` — same handler. **load-bearing** (SPA clients) |
| `POST /api/event-types/{id}/versions` | any write permission | same | same | canonical path |
| `POST /api/applications/{appCode}/event-types/sync` | `event-type:sync` et al. + application access | `SyncEventTypesRequest` (+ `?removeUnlisted`) → `SyncEventTypesCommand` | 200 `SyncResultResponse` | lives in the sdksync surface, not this package's API; the operation is here |

Archive / finalise / deprecate have **no** route in this surface; they are
reached from the BFF (anchor-gated). The operations exist here and are
authorization-`publicAccess` because each entry point keeps its own gate.

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `CreateEventTypeRequest` | `code`*, `name`*, `description`, `clientId`, `schema` (JSON) | `schema` present ⇒ spec version `1.0` minted `FINALISING` |
| `UpdateEventTypeRequest` | `name`*, `description` | |
| `AddSchemaRequest` | `version`*, `schema`* | |
| `EventTypeResponse` | `id, code, name, application, subdomain, aggregate, eventName, description?, status, source, clientId?, createdBy?, createdAt, updatedAt, specVersions[]` | optional fields omitted when null; timestamps RFC 3339 with 6 fractional digits, `Z` |
| `SpecVersionResponse` | `version, schema (always present, may be null), status, createdAt` | `id`, `mimeType`, `schemaType`, `updatedAt` are **not** exposed |
| `EventTypeListResponse` | `items[]` | no pagination |
| `CreatedResponse` | `id` | |

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `code` non-blank | `CODE_REQUIRED` | `Event type code is required` |
| Create | `name` non-blank | `NAME_REQUIRED` | `Event type name is required` |
| Create | exactly four `:`-separated segments | `INVALID_CODE_FORMAT` | `Event type code must follow format: application:subdomain:aggregate:event` |
| Create | every segment non-blank | `INVALID_CODE_FORMAT` | `Event type code part '<application\|subdomain\|aggregate\|event>' cannot be empty` |
| Update | `id` non-blank | `ID_REQUIRED` | |
| Update | `name` non-blank | `NAME_REQUIRED` | |
| Delete / Archive | `id` non-blank | `ID_REQUIRED` | |
| AddSchema / Finalise / Deprecate | `eventTypeId` non-blank | `ID_REQUIRED` | |
| AddSchema / Finalise / Deprecate | `version` non-blank | `VERSION_REQUIRED` | |
| AddSchema | `schema` present and not JSON `null` | `SCHEMA_REQUIRED` | |
| Sync | `applicationCode` non-blank | `APPLICATION_CODE_REQUIRED` | |
| Sync | each input code well-formed (checked in execute, per row) | `INVALID_CODE` | `<format message> (offending code: "<code>")`; the first bad row aborts the whole sync |

Malformed JSON body → 400 `INVALID_JSON` (transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | coarse permission (table above); unauthenticated → 403 `UNAUTHENTICATED` |
| Create — `authorize` phase | `checkScopeAccess(principal, cmd.clientId)`: client-bound create requires access to that client; `clientId` absent (platform-wide) requires anchor / super-admin → else 403 `SCOPE_FORBIDDEN` |
| Update / Delete / Archive / AddSchema / Finalise / Deprecate — `execute`, right after load + 404 | `checkScopeAccess(principal, loaded.clientId)` — with `clientId` never persisted this is always the platform-wide rule: anchor / super-admin only. **accident?** (a CLIENT-scoped principal holding `event-type:update` can never update anything) |
| Sync | `publicAccess` — gated by its two entry points |
| Reads | handler only: list filters client-scoped rows; get-by-id/code → 403 `FORBIDDEN` `No access to this event type` |

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another event type has the same code | `CODE_EXISTS` | 409 |
| Update / Delete / Archive / AddSchema / Finalise / Deprecate | no event type with that id | `EventType_NOT_FOUND` | 404 |
| Archive | already archived | `ALREADY_ARCHIVED` | 409 |
| AddSchema | version exists | `VERSION_EXISTS` | 409 |
| Finalise / Deprecate | no such version | `SpecVersion_NOT_FOUND` | 404 |
| Finalise | version not `FINALISING` | `NOT_FINALISING` | 409 |
| Deprecate | version `FINALISING` | `STILL_FINALISING` | 409 |
| Deprecate | version `DEPRECATED` | `ALREADY_DEPRECATED` | 409 |

## 7. Sync semantics

Input: `applicationCode`, `eventTypes[]{code, name, description, schema}`,
`removeUnlisted`. Existing rows = every event type whose `application`
segment equals `applicationCode`.

| Input row | Effect | Per-row event |
|---|---|---|
| code exists | `name`, `description` replaced (source, status, schema untouched) | `updated` |
| code new | created with `source=API`, `createdBy` unset, no spec version | `created` |
| `removeUnlisted` and existing row with `source=API` not in input | hard-deleted | `deleted` |
| `removeUnlisted` and existing row with `source` `UI`/`CODE` not in input | untouched | — |

`schema` on a sync input is accepted but **ignored** (no spec version is
minted). **load-bearing or accident?** The result/rollup carries
`created/updated/deleted` counts and `syncedCodes` (input codes, in order).
All row writes, per-row events and the rollup commit in one transaction; one
audit row per per-row event, all with `operation = SyncEventTypesCommand`.

## 8. Domain events

Source is always `platform:admin`; spec version `1.0`; per-aggregate subject
is `platform.eventtype.{id}`; no message group unless stated. `data` omits
null fields.

| Type | Subject | `data` fields |
|---|---|---|
| `platform:admin:eventtype:created` | `platform.eventtype.{id}` | `eventTypeId, code, name, description?, application, subdomain, aggregate, eventName, clientId?` |
| `platform:admin:eventtype:updated` | same | `eventTypeId, name, description?` |
| `platform:admin:eventtype:deleted` | same | `eventTypeId, code` |
| `platform:admin:eventtype:archived` | same | `eventTypeId, code` |
| `platform:admin:eventtype:schema-added` | same | `eventTypeId, specVersion` |
| `platform:admin:eventtype:schema-finalised` | same | `eventTypeId, specVersion, deprecatedVersion?` |
| `platform:admin:eventtype:schema-deprecated` | same | `eventTypeId, specVersion` |
| `platform:admin:eventtypes:synced` | `platform.eventtypes.{applicationCode}`, message group `platform:eventtypes:{applicationCode}` | `applicationCode, created, updated, deleted, syncedCodes[]` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Eventtype`, `entity_id = {id}`,
`operation` = command record simple name, `operation_json` = the command) in
the same transaction as the row change.

## 9. Persistence

Upsert `ON CONFLICT (id)`; `created_by` and `created_at` are never
overwritten; `updated_at` is stamped `now()` at persist time (not the
aggregate's `updatedAt` — **accident?**, harmless). Spec versions upsert
`ON CONFLICT (id)` updating `schema_content, schema_type, status, updated_at`.
Delete removes spec versions then the row. Sync uses the same `Persist`.

## 10. Open questions for the owner (summary)

1. `clientId` never persisted — keep the carried-but-not-stored field, or add the column (new behaviour), or drop it from the aggregate?
2. Post-create writes are effectively anchor-only (because `clientId` reads back null). Intended?
3. `clientId` query param suppresses the default `status=CURRENT` while filtering nothing.
4. Create/update/add-schema gated by *any* write permission rather than the specific verb.
5. Sync ignores `schema` and does not set `createdBy`.
6. `/schemas` alias of `/versions` — confirm still needed by the SPA.
7. Lenient enum reads mask bad rows.
8. `DELETE` summary says "Archive" in the lockfile; behaviour is a hard delete.
