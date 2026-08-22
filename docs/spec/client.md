# Client — behavioural spec

The contract for `io.flowcatalyst.platform.client`. Derived from the lockfile
(`/api/clients*` — sixteen operations) plus the validation rules,
authorization placement, status rules, error codes and domain events the
aggregate embodies. The Java is written *from* this; tests assert it.
Questions marked **load-bearing or accident?** need an owner ruling — until
ruled on, the behaviour is kept.

## 1. Aggregate

A client is the tenant / organisation root of the multi-tenant model:
subscriptions, dispatch pools, applications, principals… all hang off a
client via `client_id`. Clients are **anchor-only** resources: there is no
per-resource dimension (a client *is* the scope), so every gate on this
surface is the handler's `requireAnchor` and every use case is
`publicAccess`.

| Field | Type | Notes |
|---|---|---|
| `id` | `clt_` + 13-char TSID | generated on create |
| `name` | string, required | trimmed on create and on rename |
| `identifier` | string, unique | URL-safe slug: `^[a-z0-9][a-z0-9-]*[a-z0-9]$` or a single `[a-z0-9]`; **trimmed and lower-cased** before validation and storage, so uniqueness is on the normalised form; immutable after create |
| `status` | `ACTIVE` \| `INACTIVE` \| `SUSPENDED` | default `ACTIVE`. Nothing in this aggregate ever sets `INACTIVE` (deactivate is a hard delete, §3) — **load-bearing or accident?** |
| `statusReason` | string, optional | set by suspend, cleared by activate |
| `statusChangedAt` | timestamp, optional | stamped by suspend and activate; `null` until the first status change |
| `notes` | list of note | stored as a JSONB array on the row, append-only, in insertion order |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Note:

| Field | JSON | Notes |
|---|---|---|
| `category` | `category` | free text, not trimmed |
| `text` | `text` | free text, not trimmed |
| `addedBy` | `addedBy` (omitted when absent) | the acting principal id; absent when the execution context has no principal |
| `addedAt` | `addedAt` | RFC 3339; written as UTC with 6 fractional digits, read leniently (any offset, fraction truncated to microseconds — the column may hold rows another writer produced) |

Lenient enum read: unknown `status` → `ACTIVE` (**accident?** — same
masking as event types; kept).

## 2. State machine

Status transitions have **no preconditions**:

| Transition | Effect | Precondition |
|---|---|---|
| `suspend(reason)` | `status=SUSPENDED`, `statusReason=reason`, `statusChangedAt=now`, `updatedAt=now` | none — suspending an already-suspended client re-stamps the reason and time (**accident?**) |
| `activate()` | `status=ACTIVE`, `statusReason=null`, `statusChangedAt=now`, `updatedAt=now` | none — activating an `ACTIVE` client re-stamps `statusChangedAt` (**accident?**) |
| `addNote(note)` | appends, `updatedAt=now` | none |
| `rename(name)` | `name=trim(name)` | none (blank is rejected at validation) |

There is no un-delete; delete is hard.

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`. Unless stated, the gate is
**anchor-only**: unauthenticated → 403 `UNAUTHENTICATED`, non-anchor → 403
`ANCHOR_REQUIRED` (`anchor scope required`). Holding `platform:admin:client:*`
permissions does not help a CLIENT-scoped principal — the permission
catalogue has them but no route checks them (**load-bearing or accident?**).

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/clients` | anchor | — | 200 `ClientListResponse` `{clients[], total}` | every client, ordered by `identifier`; `total = clients.length` (no pagination) |
| `POST /api/clients` | anchor | `CreateClientRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | |
| `POST /api/clients/search` | anchor | `SearchClientRequest{term}` | 200 `ClientListResponse` | `name ILIKE %term% OR identifier ILIKE %term%` (contains, not prefix — the Go comment says prefix; the query says contains: **load-bearing** for the SPA autocomplete), ordered by `identifier`, **at most 50**; absent/empty term → first 50 of all |
| `GET /api/clients/search?q=` | anchor | query `q` | 200 `ClientListResponse` | SDK alias of the POST; same semantics |
| `GET /api/clients/by-identifier/{identifier}` | anchor | — | 200 `ClientResponse` | 404 `Client_NOT_FOUND` with the identifier in the message; the path value is matched **as given** (not lower-cased) — **accident?** |
| `GET /api/clients/{id}` | anchor | — | 200 `ClientResponse` | 404 `Client_NOT_FOUND` |
| `PUT /api/clients/{id}` | anchor | `UpdateClientRequest{name?}` → `UpdateCommand(id from path)` | 204, empty body | `name` absent → nothing changes but the row is re-persisted and an `updated` event still fires (**accident?**) |
| `POST /api/clients/{id}/activate` | anchor | `ActivateCommand` | 200 `StatusChangeResponse` `{"message":"Client activated"}` | |
| `POST /api/clients/{id}/suspend` | anchor | `SuspendClientRequest{reason}` → `SuspendCommand` | 200 `{"message":"Client suspended"}` | |
| `POST /api/clients/{id}/notes` | anchor | `AddNoteRequest{category,text}` → `AddNoteCommand` | 200 `{"message":"Note added"}` | |
| `DELETE /api/clients/{id}` | anchor | `DeleteCommand` | 204 | hard delete of the row only — children (`client_id` references elsewhere) are **not** touched (**load-bearing or accident?**) |
| `POST /api/clients/{id}/deactivate` | anchor | `StatusChangeRequest{reason}` → `DeleteCommand` | 200 `{"message":"Client deactivated"}` | alias of delete: a **hard delete** — the row is gone afterwards, `GET /{id}` is 404, the status never becomes `INACTIVE`; the body is parsed (malformed JSON → 400) but the `reason` is **discarded** — it reaches neither the command, the audit row nor the event (the Go comment claims it is "for the audit log") — **accident** |
| `GET /api/clients/{id}/applications` | anchor **or** access to client `{id}` → else 403 `FORBIDDEN` `No access to this client` | — | 200 `ClientApplicationsResponse` `{applications[], total}` | 404 `Client_NOT_FOUND`; every application (ordered by `code`) with `enabledForClient` = the client's `app_client_configs.enabled` for that application, `false` when no config row. The only non-anchor route on this surface; see §10 |
| `PUT /api/clients/{id}/applications` | anchor | `UpdateClientApplicationsRequest{enabledApplicationIds[]}` → application's `UpdateClientApplicationsCommand` | 204 | **application aggregate's operation** (`platform:admin:application:…` events, `app_client_configs` rows) — see §10 |
| `POST /api/clients/{id}/applications/{applicationId}/enable` | anchor | → application's `EnableForClientCommand` | 204 | application aggregate's operation — §10 |
| `POST /api/clients/{id}/applications/{applicationId}/disable` | anchor | → application's `DisableForClientCommand` | 204 | application aggregate's operation — §10 |

Route precedence: `/api/clients/search` and `/api/clients/by-identifier/…`
are literal paths and must win over `/api/clients/{id}`.

Wire shapes (field order as listed; optional fields omitted when `null`):

| Schema | Fields |
|---|---|
| `CreateClientRequest` | `name`*, `identifier`* |
| `UpdateClientRequest` | `name` (optional; `null`/absent = unchanged) |
| `SuspendClientRequest` | `reason`* |
| `AddNoteRequest` | `category`*, `text`* |
| `SearchClientRequest` | `term`* (absent tolerated → no term, i.e. match everything) |
| `StatusChangeRequest` | `reason`* (ignored, see above) |
| `ClientResponse` | `id, name, identifier, status, statusReason?, statusChangedAt?, notes[], createdAt, updatedAt` |
| `NoteResponse` | `category, text, addedBy?, addedAt` |
| `ClientListResponse` | `clients[], total` |
| `ClientApplicationResponse` | `id, code, name, description?, iconUrl?, active, enabledForClient` |
| `ClientApplicationsResponse` | `applications[], total` |
| `StatusChangeResponse` | `message` |
| `CreatedResponse` | `id` |

Timestamps RFC 3339 with 6 fractional digits, `Z`. `notes` is always
present (`[]` when empty).

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `name` non-blank | `NAME_REQUIRED` | `name is required` |
| Create | `identifier` non-blank after trim | `IDENTIFIER_REQUIRED` | `identifier is required` |
| Create | normalised identifier matches the slug pattern | `INVALID_IDENTIFIER` | `identifier must be lowercase alphanumeric with optional hyphens (URL-safe)` |
| Update | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `name`, **when present**, non-blank | `NAME_REQUIRED` | `name cannot be empty` |
| Delete / Activate | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Suspend | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Suspend | `reason` non-blank | `REASON_REQUIRED` | `reason is required` |
| AddNote | `clientId` non-blank | `ID_REQUIRED` | `clientId is required` |
| AddNote | `category` non-blank | `CATEGORY_REQUIRED` | `category is required` |
| AddNote | `text` non-blank | `TEXT_REQUIRED` | `text is required` |

Normalisation: `name` is trimmed (create, update); `identifier` is trimmed
and lower-cased (create). `reason`, `category`, `text` are stored verbatim
(only blank-checked) — **accident?**

Malformed JSON body → 400 `INVALID_JSON` (transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | `requireAnchor` on every route except `GET /{id}/applications` (anchor or `canAccessClient(id)`); unauthenticated → 403 `UNAUTHENTICATED` |
| Use cases (all six) | `publicAccess` — clients have no per-resource dimension; the handler's coarse gate is the whole check |
| Reads | handler only |

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another client has the same (normalised) identifier | `IDENTIFIER_EXISTS` `Client with identifier '<id>' already exists` | 409 |
| Update / Delete / Suspend / Activate / AddNote | no client with that id | `Client_NOT_FOUND` `Client not found: <id>` | 404 |

The DB also has `UNIQUE (identifier)`; the operation checks first so the
error is the 409 envelope, not a constraint failure.

## 7. Domain events

Source is always `platform:admin`; spec version `1.0`; subject
`platform.client.{id}`; **every** event carries message group
`platform:client:{id}` (one client's events are delivered in order). `data`
omits nothing (all fields are non-null by construction).

| Type | `data` fields |
|---|---|
| `platform:admin:client:created` | `clientId, name, identifier` |
| `platform:admin:client:updated` | `clientId, name` (the name after the update — unchanged when the command carried none) |
| `platform:admin:client:activated` | `clientId` |
| `platform:admin:client:suspended` | `clientId, reason` |
| `platform:admin:client:note-added` | `clientId, category, text` |
| `platform:admin:client:deleted` | `clientId, identifier` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Client`, `entity_id = {id}`,
`operation` = command record simple name — `CreateCommand`, `UpdateCommand`,
`DeleteCommand`, `SuspendCommand`, `ActivateCommand`, `AddNoteCommand` —
`operation_json` = the command) in the same transaction as the row change.
Deactivate audits as `DeleteCommand` (the reason is lost, §3).

## 8. Persistence

`tnt_clients`: upsert `ON CONFLICT (id)` updating `name, identifier, status,
status_reason, status_changed_at, notes, updated_at`; `created_at` is
insert-only; `updated_at` is stamped `now()` at persist time (not the
aggregate's `updatedAt` — harmless). `notes` is the JSON array of §1 notes.
Delete removes the row only.

Reads: by id; by identifier (exact); search (`ILIKE '%term%'` on name or
identifier, by identifier, limit 50); all (by identifier). A `NULL` /
empty `notes` column reads as an empty list.

## 9. Open questions for the owner (summary)

1. `INACTIVE` exists in the status enum but nothing sets it; deactivate is a
   hard delete. Intended?
2. Deactivate's `reason` is discarded (not audited). Intended, or should the
   alias carry the reason into the audit row?
3. Suspend / activate have no preconditions (idempotent re-stamps). Keep?
4. `PUT` with no `name` re-persists and emits `updated`. Keep?
5. The `client:*` permissions exist but every route is anchor-only. Intended?
6. `by-identifier/{identifier}` does not normalise the path value while
   create lower-cases. Intended?
7. Hard delete leaves `client_id` references elsewhere dangling. Intended?
8. Search is "contains", the Go comment says "prefix". Contains is kept.

## 10. Client → application linking (shared with the `application` aggregate)

Four routes under `/api/clients/{id}/applications*` are the client-side
face of the **application** aggregate's client configs
(`app_client_configs`, `apc_` ids). Their operations, events
(`platform:admin:application:enabled-for-client` /
`…:disabled-for-client` / `…:client-applications-updated`) and repositories
belong to `io.flowcatalyst.platform.application`.

Status in this port:

- All four routes are mounted by `ClientApi` (this package holds the gate,
  the `application` package holds the operations, events and repositories).
  `ClientApi` (and the composition root, which hands it the two
  application repositories) is the **only** place this package depends on
  `platform.application`; the dependency is one-way — nothing in
  `platform.application` imports `platform.client`.
- `GET /api/clients/{id}/applications` reads `ApplicationRepository`
  (every application, by code) + `ClientConfigRepository.findByClient` and
  decorates each application with the client's `enabled` flag (`false`
  without a config row).
- `PUT /api/clients/{id}/applications` → `UpdateClientApplicationsCommand(clientId, enabledApplicationIds)`;
  `POST …/{applicationId}/enable` → `EnableForClientCommand(applicationId, clientId)`;
  `POST …/{applicationId}/disable` → `DisableForClientCommand(applicationId, clientId)`.
  Gate: handler `requireAnchor`; the operations' `authorize` is
  `checkScopeAccess(cmd.clientId)`. Not-found: `Client_NOT_FOUND`,
  `Application_NOT_FOUND`, and for disable without a config row
  `ClientConfig_NOT_FOUND` (`<applicationId>:<clientId>`). Success 204.
  Events and audit rows are the application aggregate's
  (`docs/spec/application.md`).
