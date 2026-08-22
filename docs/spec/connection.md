# Connection — behavioural spec

The contract for `io.flowcatalyst.platform.connection`. Derived from the
lockfile (`/api/connections*`) plus the validation rules, authorization
placement, state machine, error codes and domain events the aggregate
embodies. The Java is written *from* this; tests assert it. Questions marked
**load-bearing or accident?** need an owner ruling — until ruled on, the
behaviour is kept.

## 1. Aggregate

A connection is an outbound webhook delivery target: a named endpoint owner
(the service account that signs/authenticates deliveries) that
subscriptions attach to. It is identified by a **code**, unique per client
(`(code, client_id)` — a platform-wide connection has `client_id = NULL`,
and the unique index treats `NULL`s as distinct, so uniqueness of
platform-wide codes is enforced by the operation, not the database —
**load-bearing or accident?**).

| Field | Type | Notes |
|---|---|---|
| `id` | `con_` + 13-char TSID | generated on create |
| `code` | string, required | normalised to lower case + trimmed on create; `^[a-z][a-z0-9-]*$`; immutable after create |
| `name` | string, required | trimmed on create and update |
| `description` | string, optional | not trimmed |
| `externalId` | string, optional | free-form external reference |
| `status` | `ACTIVE` \| `PAUSED` | default `ACTIVE` |
| `serviceAccountId` | string, required | **not validated against `iam_service_accounts`** (Go `TODO(wave-3c)`) — **load-bearing or accident?** (an unknown id is accepted today) |
| `clientId` | string, optional | `null` = platform-wide; persisted; drives authorization and list visibility |
| `clientIdentifier` | string, optional | column exists and is read/written, but **no operation ever sets it** — **load-bearing or accident?** |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Lenient enum read: exactly `PAUSED` → `PAUSED`; anything else (other case,
surrounding whitespace, unknown) → `ACTIVE` (**accident?** — it also
applies to the *update command's* `status` field, see §4).

## 2. State machine

```
ACTIVE --pause--> PAUSED --activate--> ACTIVE
```

Both transitions are **unconditional**: pausing a `PAUSED` connection or
activating an `ACTIVE` one succeeds, stamps `updatedAt`, persists and emits
`updated` again. There is no `ALREADY_PAUSED` / `ALREADY_ACTIVE` conflict.
**load-bearing or accident?** (the SPA calls pause/activate from a toggle;
an idempotent flip is friendlier than a 409 — kept as idempotent.)

`update` may also set the status directly (§4).

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/connections` | `connection:view` | query `status`, `clientId` | 200 `ConnectionListResponse` `{connections: [ConnectionResponse], total}` | ordered by code; both query params are equality filters on real columns (empty string = absent); no default status; client-scoped rows the caller cannot access are filtered out; `total` = size of the *visible* list (post-filter) |
| `POST /api/connections` | `connection:create` | `CreateConnectionRequest` → `CreateCommand` | 201 `ConnectionResponse` (re-read after the write — **load-bearing**: the SPA pushes the created connection straight into a select) | |
| `GET /api/connections/{id}` | `connection:view` | — | 200 `ConnectionResponse` | 404 `Connection_NOT_FOUND`; 403 `FORBIDDEN` `No access to this connection` when client-scoped and not accessible |
| `PUT /api/connections/{id}` | `connection:update` | `UpdateConnectionRequest` → `UpdateCommand(id from path)` | 204, empty body | |
| `DELETE /api/connections/{id}` | `connection:delete` | `DeleteCommand` | 204 | hard delete |
| `POST /api/connections/{id}/pause` | `connection:update` | `PauseCommand` | 200 `ConnectionResponse` (re-read) | |
| `POST /api/connections/{id}/activate` | `connection:update` | `ActivateCommand` | 200 `ConnectionResponse` (re-read) | |

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `CreateConnectionRequest` | `code`*, `name`*, `serviceAccountId`*, `description`, `externalId`, `clientId` | |
| `UpdateConnectionRequest` | `name`*, `description`, `externalId`, `status` | **absent** `description`/`externalId` clear the stored value (full replace, not patch); absent `status` leaves the status alone |
| `ConnectionResponse` | `id, code, name, description?, externalId?, status, serviceAccountId, clientId?, clientIdentifier?, createdAt, updatedAt` | optional fields omitted when null; timestamps RFC 3339 with 6 fractional digits, `Z` |
| `ConnectionListResponse` | `connections[]`, `total` | no pagination |

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `code` non-blank (after trim) | `CODE_REQUIRED` | `Connection code is required` |
| Create | trimmed + lower-cased `code` matches `^[a-z][a-z0-9-]*$` | `INVALID_CODE_FORMAT` | `Code must start with lowercase letter, contain only lowercase alphanumeric and hyphens` |
| Create | `name` non-blank | `NAME_REQUIRED` | `Connection name is required` |
| Create | `serviceAccountId` non-blank | `SERVICE_ACCOUNT_REQUIRED` | `serviceAccountId is required` |
| Update | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `name` non-blank | `NAME_REQUIRED` | `Connection name is required` |
| Delete / Pause / Activate | `id` non-blank | `ID_REQUIRED` | `id is required` |

Update `status`: when present it is read **leniently** — exactly `PAUSED`
pauses, anything else (including typos, other case, whitespace) activates. **load-bearing or accident?**
(a strict reader would answer 400 `INVALID_STATUS`; kept lenient.)

Malformed JSON body → 400 `INVALID_JSON` (transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | coarse permission per route (table §3); unauthenticated → 403 `UNAUTHENTICATED` |
| Create — `authorize` phase | `checkScopeAccess(principal, cmd.clientId)`: client-bound create requires access to that client; `clientId` absent (platform-wide) requires anchor / super-admin → else 403 `SCOPE_FORBIDDEN` |
| Update / Delete / Pause / Activate — `execute`, right after load + 404 | `checkScopeAccess(principal, loaded.clientId)`; these operations declare `publicAccess` for that reason |
| Reads | handler only: list filters client-scoped rows; get-by-id → 403 `FORBIDDEN` `No access to this connection` |

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another connection has the same normalised code **and the same `clientId`** (`null` matches only `null`) | `CODE_EXISTS` | 409 — message `Connection with code '<code>' already exists` |
| Update / Delete / Pause / Activate | no connection with that id | `Connection_NOT_FOUND` | 404 — message `Connection not found: <id>` |

A client-bound create may reuse a code that exists platform-wide or under
another client. **load-bearing** (multi-tenant codes).

## 7. Domain events

Source is always `platform:admin`; spec version `1.0`; subject
`platform.connection.{id}`; **message group `platform:connection:{id}`** on
every event (unlike event types, which have none). `data` omits null fields.

| Type | Emitted by | `data` fields |
|---|---|---|
| `platform:admin:connection:created` | Create | `connectionId, code, name` |
| `platform:admin:connection:updated` | Update, Pause, Activate | `connectionId, name` |
| `platform:admin:connection:deleted` | Delete | `connectionId, code` |

Pause and activate emit `updated` with no status field — a consumer
cannot tell a pause from a rename without re-reading. **load-bearing or
accident?** (kept; adding `status` to the payload is additive.)

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Connection`, `entity_id = {id}`,
`operation` = command record simple name, `operation_json` = the command) in
the same transaction as the row change. Audit operation names:
`CreateCommand`, `UpdateCommand`, `DeleteCommand`, `PauseCommand`,
`ActivateCommand` — the Go binary wrote `statusCommand` for both pause and
activate (a type-alias accident); this port writes the distinct names.
**accident** (ruled: distinct names are strictly more useful; flagged for
anyone querying `aud_logs` by operation).

## 8. Persistence

Upsert `ON CONFLICT (id)`: every column is replaced except `created_at`
(insert-only); `updated_at` is stamped `now()` at persist time (not the
aggregate's `updatedAt` — **accident?**, harmless). Delete removes the row;
there are no child rows (subscriptions reference connections by id without
a foreign key — deleting a connection with live subscriptions is allowed
today, **load-bearing or accident?**).

## 9. Open questions for the owner (summary)

1. `serviceAccountId` is not checked against `iam_service_accounts` at create.
2. `clientIdentifier` is never set by any operation — dead column or pending feature?
3. Pause/activate are idempotent (no 409 on a no-op flip).
4. Update's `status` is read leniently (`garbage` → `ACTIVE`).
5. Platform-wide code uniqueness relies on the operation's pre-check (the DB index treats `NULL` client ids as distinct).
6. `updated` event carries no `status`; pause/activate are indistinguishable from a rename on the wire.
7. Audit `operation` for pause/activate: Go wrote `statusCommand`; Java writes `PauseCommand` / `ActivateCommand`.
8. Deleting a connection that subscriptions still reference is allowed.
