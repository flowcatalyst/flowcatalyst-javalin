# Platform config — behavioural spec

The contract for `io.flowcatalyst.platform.platformconfig`. Derived from the
lockfile (`/api/platform-config/*`, `/api/config/{app}/{section}/{property}`)
plus the validation rules, authorization placement, error codes and domain
events the aggregate embodies. The Java is written *from* this; tests assert
it. Questions marked **load-bearing or accident?** need an owner ruling —
until ruled on, the behaviour is kept.

## 1. Aggregates

Two sibling aggregates share one table pair and one API surface:

- **Platform config** (`app_platform_configs`) — one system-wide setting per
  *coordinate* `(applicationCode, section, property, scope, clientId)`.
- **Config access** (`app_platform_config_access`) — a per-role grant: role
  `R` may read / write the configs of application `A`.

### 1.1 Platform config

| Field | Type | Notes |
|---|---|---|
| `id` | `pcf_` + 13-char TSID | generated on first set |
| `applicationCode` | string, required | the application the setting belongs to (a code, not an id; **not** validated against `applications`) |
| `section` | string, required | |
| `property` | string, required | |
| `scope` | `GLOBAL` \| `CLIENT` | **derived from `clientId`**: present ⇒ `CLIENT`, absent ⇒ `GLOBAL`. Never set independently. |
| `clientId` | string, optional | the client a `CLIENT`-scoped value belongs to |
| `valueType` | `PLAIN` \| `SECRET` | default `PLAIN`; `SECRET` values are masked (`***`) on read for non-anchors |
| `value` | string, required (may be empty) | the stored text; JSON blobs (login theme) are stored as text |
| `description` | string, optional | |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

The **coordinate** `(applicationCode, section, property, scope, clientId)` is
unique (`uq_app_platform_config_key`). The lookup key used everywhere is
`(applicationCode, section, property, clientId?)` with scope derived; a
`GLOBAL` lookup matches `client_id IS NULL`.

**Caveat (load-bearing or accident?):** the unique index treats `NULL`s as
distinct, so for `GLOBAL` rows (`client_id IS NULL`) the database does *not*
enforce the coordinate's uniqueness — only the set operation's
find-by-coordinate-then-upsert does. Two concurrent first sets of the same
`GLOBAL` coordinate can therefore both insert, after which a lookup at that
coordinate fails (more than one row). Owner question 10.

Lenient enum reads: unknown `scope` → `GLOBAL`; unknown `valueType` →
`PLAIN` — on stored rows **and** on the `valueType` a set command carries
(`"BANANA"` is silently stored as `PLAIN`). **accident?** Kept.

### 1.2 Config access

| Field | Type | Notes |
|---|---|---|
| `id` | `cfa_` + TSID | |
| `applicationCode` | string, required | |
| `roleCode` | string, required | **not** validated against `iam_roles` — a grant for an unknown role is accepted (**accident?**) |
| `canRead` | boolean | **always `true`** after a grant (a grant is a read grant at minimum); the column exists and is persisted but nothing ever writes `false` |
| `canWrite` | boolean | from the grant |
| `createdAt` | timestamp | insert-only |

`(applicationCode, roleCode)` is unique (`uq_app_config_access_role`); a
second grant for the same pair **updates the existing row in place** (same
id, `canWrite` replaced, including *de*-escalation `true → false`).

## 2. State machines

There are no lifecycle states. The only transitions are:

| Aggregate | Transition | Effect |
|---|---|---|
| config | `set(value, valueType?, description)` | `value` replaced; `description` replaced (absent ⇒ cleared); `valueType` replaced only when given, otherwise kept (`PLAIN` on first set) |
| access | `grant(canWrite)` | `canRead := true`, `canWrite := canWrite` |

## 3. Authorization model

Platform config has **no permission codes**. Access is either anchor or a
DB-backed grant:

| Rule | Who passes |
|---|---|
| **read access to app `A`** | anchor; or a non-anchor holding at least one role with a `canRead` grant on `A` |
| **write access to app `A`** | anchor; or a non-anchor holding at least one role with a `canWrite` grant on `A` |
| **anchor-only** | anchor |

The role set is the principal's `roles` (the JWT claim); a principal with no
roles never passes a grant check. Failures: unauthenticated → 403
`UNAUTHENTICATED` (Java convention — Go's nil receiver fell through to
`FORBIDDEN`; same status); no grant → 403 `FORBIDDEN`
`No read access to platform config for <app>` /
`No write access to platform config for <app>`; not anchor → 403
`ANCHOR_REQUIRED`.

## 4. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope. Two
path families: the legacy `/api/platform-config/…` (list + grants) and the
SPA's `/api/config/{app}/{section}/{property}` (single property). The SPA
passes `clientId` as a **query** parameter on the single-property routes.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/platform-config/{app}` | read access to `app` | — | 200 `ConfigListResponse` `{items: [ConfigResponse]}` | ordered by `section, property`; `SECRET` values masked `***` for non-anchors |
| `GET /api/config/{app}/{section}/{property}?clientId=` | read access to `app` | — | 200 `ConfigResponse` | 404 `Config_NOT_FOUND` (`Config not found: app/section/property`); masked as above |
| `PUT /api/config/{app}/{section}/{property}?clientId=` | *(in the use case)* write access to `app` | `SetPropertyRequest` → `SetPropertyCommand` (path + query folded in) | 200 `ConfigResponse` (re-read after the write; **not** masked — **accident?**, the caller just supplied the value) | creates or updates the coordinate |
| `DELETE /api/config/{app}/{section}/{property}?clientId=` | write access to `app` | — | 204 | **idempotent**: absent coordinate ⇒ 204. A direct repository delete — **no domain event, no audit row** (**load-bearing or accident?**, see §9) |
| `GET /api/platform-config/{app}/access` | anchor-only | — | 200 `AccessListResponse` `{items: [AccessResponse]}` | ordered by `roleCode` |
| `POST /api/platform-config/{app}/access` | anchor-only | `GrantAccessRequest` → `GrantAccessCommand(app from path)` | 201 `CreatedResponse` `{id}` | upsert by `(app, role)` |
| `DELETE /api/platform-config/access/{id}` | anchor-only | `RevokeAccessCommand` | 204 | 404 `PlatformConfigAccess_NOT_FOUND` |

`clientId` query parameter: absent or empty ⇒ `GLOBAL` lookup; otherwise the
`CLIENT` coordinate for that client. On `PUT` a non-empty query `clientId`
**overrides** the body's `clientId`; the body's is used only when the query
is absent. The `PUT` response is re-read at the coordinate the **command**
addressed (query or body client) — **deviation**: Go re-read at the query
coordinate only, so a body-only `clientId` set returned 500
`config missing after set` after a successful write (**accident**, fixed).
A body `clientId` of `""` is treated as absent (Go would have stored `scope=CLIENT, client_id=''` — **accident**, Java maps `""` → absent
at the DTO boundary).

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `SetPropertyRequest` | `value`*, `valueType` (`PLAIN`\|`SECRET`), `description`, `clientId` | |
| `GrantAccessRequest` | `roleCode`*, `canWrite`* | a missing `canWrite` reads as `false` |
| `ConfigResponse` | `id, applicationCode, section, property, scope, clientId?, valueType, value, description?, createdAt, updatedAt` | optional fields omitted when null; timestamps RFC 3339 with 6 fractional digits, `Z` |
| `AccessResponse` | `id, applicationCode, roleCode, canRead, canWrite, createdAt` | |
| `ConfigListResponse` / `AccessListResponse` | `items[]` | no pagination |
| `CreatedResponse` | `id` | |

## 5. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| SetProperty | `applicationCode` non-blank | `FIELD_REQUIRED` | `applicationCode is required` |
| SetProperty | `section` non-blank | `FIELD_REQUIRED` | `section is required` |
| SetProperty | `property` non-blank | `FIELD_REQUIRED` | `property is required` |
| SetProperty | `value` present (empty string allowed) | `FIELD_REQUIRED` | `value is required` — **deviation**: Go left this to huma's schema validation (422); Java reports it through the same code as the other required fields |
| GrantAccess | `applicationCode` non-blank | `APPLICATION_REQUIRED` | `applicationCode is required` |
| GrantAccess | `roleCode` non-blank | `ROLE_REQUIRED` | `roleCode is required` |
| RevokeAccess | `id` non-blank | `ID_REQUIRED` | `id is required` |

The three SetProperty checks run in the order `applicationCode`, `section`,
`property` (Go iterated a map — unordered; only the first failure is
reported, so the order matters when several are blank. **accident**, fixed
order chosen). Malformed JSON body → 400 `INVALID_JSON` (transport).

## 6. Authorization placement

| Where | What |
|---|---|
| Handler | reads (`list`, `get`): read access to `app`; `delete`: write access to `app`; grants (`listAccess`, `grant`, `revoke`): `requireAnchor`. Unauthenticated → 403 `UNAUTHENTICATED`. |
| SetProperty — `authorize` phase | write access to `cmd.applicationCode` (anchor, or a role with a `canWrite` grant) — the rule lives in the use case because the target application is a command field and the DB-backed check must run before any persistence. |
| GrantAccess / RevokeAccess | `publicAccess` — anchor-only at the handler; there is no per-instance dimension. |

## 7. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| RevokeAccess | no grant with that id | `PlatformConfigAccess_NOT_FOUND` | 404 |
| read `get` | no config at the coordinate | `Config_NOT_FOUND` | 404 (handler) |

There are no conflicts: both writes are upserts keyed by their natural key.

## 8. Domain events

Source `platform:admin`; spec version `1.0`; subject
`platform.platformconfig.{id}` (the config's or the grant's id — both
aggregates share the subject namespace, so `aud_logs.entity_type` is
`Platformconfig` for both); **message group
`platform:platformconfig:{id}`** on every event (per-aggregate FIFO). `data`
omits null fields.

| Type | Subject | `data` fields |
|---|---|---|
| `platform:admin:platform-config:property-set` | `platform.platformconfig.{configId}` | `configId, applicationCode, section, property` (never the value — secrets) |
| `platform:admin:platform-config:access-granted` | `platform.platformconfig.{accessId}` | `accessId, applicationCode, roleCode, canWrite` |
| `platform:admin:platform-config:access-revoked` | same | `accessId, applicationCode, roleCode` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Platformconfig`, `entity_id = {id}`,
`operation` = command record simple name — `SetPropertyCommand` /
`GrantAccessCommand` / `RevokeAccessCommand`, `operation_json` = the command
**including a secret `value` in clear** — **load-bearing or accident?**) in
the same transaction as the row change.

## 9. Persistence

- Config upsert `ON CONFLICT (id)` updating only `value_type, value,
  description, updated_at`; coordinate columns and `created_at` are
  insert-only; `updated_at` is stamped `now()` at persist time.
- Access upsert `ON CONFLICT (id)` updating only `can_read, can_write`;
  `created_at` insert-only.
- Delete is by id.
- **Property delete bypasses the envelope**: the handler deletes the row in a
  plain transaction (no event, no audit). Precedent in this codebase:
  `RoleApi.deletePermission`. Owner question 3.

## 10. Reads needed by other subsystems (not ported here)

`internal/platform/publicapi` (`/api/public/platform`,
`/api/public/login-theme`, `/api/config/platform`) and `internal/platform/branding`
read **one coordinate**: `findByCoordinate(GLOBAL: platform / login / theme)`
and `findByCoordinate(GLOBAL: <branding App/Section/Property>)`, and treat a
missing row or an empty `value` as "no theme". The Java repository exposes
`PlatformConfigRepository.findByCoordinate(ConfigCoordinate.global(app, section, property))`
returning `Optional<PlatformConfig>` for exactly that; nothing else is needed.

## 11. Open questions for the owner (summary)

1. `canRead` is always `true` — drop the column from the model (keep
   persisting `true`) or expose a read-only grant?
2. `roleCode` / `applicationCode` are not validated against roles /
   applications — intended (bootstrap before the app exists)?
3. Property **delete** emits no event and writes no audit row (and the
   handler, not a use case, does it). Intended, or should it become
   `DeleteProperty` with a `property-deleted` event?
4. The audit row stores the command JSON, i.e. a `SECRET` value in clear in
   `aud_logs.operation_json`. Intended?
5. `PUT` answers with the unmasked value even for non-anchor writers.
6. Unknown `valueType` on a set command is silently stored as `PLAIN`
   rather than rejected.
7. `value` missing from the body: Java → 400 `FIELD_REQUIRED`; Go → huma 422.
   Keep the Java code?
8. Body `clientId: ""` → treated as absent (Go: `CLIENT` scope with empty
   client id).
9. `PUT` with a body-only `clientId` now answers 200 with the client value
   (Go: 500 after the write). Confirm the fix is wanted rather than the
   body field being dropped.
10. `uq_app_platform_config_key` does not enforce uniqueness for `GLOBAL`
    rows (`client_id IS NULL`, NULLs distinct): concurrent first sets of one
    coordinate can both insert. Accept the race, or add a `NULLS NOT DISTINCT`
    unique index (schema change)?
