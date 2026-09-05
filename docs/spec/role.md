# Role — behavioural spec

The contract for `io.flowcatalyst.platform.role`. Derived from the lockfile
(`/api/roles*` — sixteen operations, including the permission catalogue
under `/api/roles/permissions*`) plus the validation rules, authorization
placement, source rules, sync semantics, error codes and domain events the
aggregate embodies. The Java is written *from* this; tests assert it.
Questions marked **load-bearing or accident?** need an owner ruling — until
ruled on, the behaviour is kept.

## 1. Aggregate

A role is a named set of permission codes that principals are assigned
(`iam_principal_roles.role_name` references `iam_roles.name` by value — there
is **no** foreign key). Roles are **global**: there is no client dimension,
so per-resource authorization does not exist; every gate is the handler's
coarse permission.

| Field | Type | Notes |
|---|---|---|
| `id` | `rol_` + 13-char TSID | generated on create |
| `name` | string, unique | canonical `{applicationCode}:{shortName}`, e.g. `platform:admin`; immutable after create |
| `displayName` | string, required | |
| `description` | string, optional | |
| `applicationCode` | string, optional in the DB | always set by every creation path; `null` on legacy rows only. On the wire it is required and renders `""` when absent |
| `applicationId` | `app_…`, optional | stamped only by the SDK sync (`SyncRoles`); admin-created and catalogue roles have `null`. **load-bearing or accident?** (see §7 — the SDK sync scopes its "existing roles" lookup by this column) |
| `permissions` | set of permission codes | de-duplicated; read back **sorted** (text order) |
| `source` | `CODE` \| `DATABASE` \| `SDK` | `DATABASE` from the admin API, `SDK` from the application sync, `CODE` from the built-in catalogue (seeder / `SyncPlatformRoles`) |
| `clientManaged` | boolean | informational flag ("managed at client scope"); nothing in this aggregate switches on it |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Derived: `shortName()` = `name` with the `{applicationCode}:` prefix
removed — exactly that prefix, once (a short name may itself contain colons);
falls back to the full name when the prefix does not match.

`hasPermission(p)` = any held code equals `p` or matches it with `*`
segment wildcards (same segment count; `*` matches any segment). The
matcher is the shared one (`platform.shared.auth.Permission#grants`).

Lenient enum read: unknown `source` → `DATABASE` (**accident?** — same
masking as event types; kept).

Permission catalogue entry (`iam_permissions`, a separate, role-independent
table; the seeder does **not** populate it — it is fed by the BFF
"define permission" endpoint and SDK permission syncs):

| Field | Type | Notes |
|---|---|---|
| `id` | `prm_` + TSID | |
| `code` | string, unique | four segments `application:context:aggregate:action` |
| `subdomain`, `context`, `aggregate`, `action` | strings | the four segments, denormalised (the first segment is stored in a column named `subdomain`) |
| `description` | optional | |
| `name` (wire only) | = `code` | the catalogue has no display name |
| `category` (wire only) | `subdomain:context:aggregate` | how the UI groups permissions |

## 2. Source rules (the state machine)

There are no status transitions. The only invariant is **source**:

| Operation | `CODE` | `DATABASE` | `SDK` |
|---|---|---|---|
| Update (admin) | refused, `CODE_ROLE_IMMUTABLE` (409) "Roles with source=CODE cannot be modified" | ok | ok |
| Delete (admin) | refused, `CODE_ROLE_IMMUTABLE` (409) "Roles with source=CODE cannot be deleted" | ok | ok |
| Grant / revoke permission (admin) | **allowed** | ok | ok |
| `SyncRoles` (SDK) | never touched | never touched | created / updated / removed |
| `SyncPlatformRoles` (catalogue) | created / updated / removed | skipped (warn) when a catalogue name collides | skipped (warn) |

Grant/revoke on a `CODE` role is **not** blocked although update/delete are —
**load-bearing or accident?** (The seeder "preserves local edits to
permissions", which suggests per-permission edits on catalogue roles are
intended; the next `SyncPlatformRoles` overwrites them anyway.)

Permission edits are set operations: granting an already-held permission is
a no-op on the set (no duplicate row) but **still emits the event and the
audit row**; revoking an absent permission likewise. Update with an explicit
`permissions` list replaces the set wholesale (de-duplicated — Go would fail
the insert with a primary-key violation on a duplicate in the list,
**accident**, Java de-duplicates).

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`. "Resolve" = load by TSID id,
then by name when that misses (the SPA addresses roles by id, the SDKs by
name on the same `{id}` routes; TSIDs never contain `:`, names always do, so
the fallback is unambiguous) → 404 `Role_NOT_FOUND` with the given key.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/roles` | `role:view` | — | 200 `RoleListResponse` `{roles[], total}` | all roles, ordered by name |
| `POST /api/roles` | any of `role:{create\|update\|delete}` | `CreateRoleRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | **accident?** create is gated by "any write permission", not `create` |
| `GET /api/roles/{id}` | `role:view` | — | 200 `RoleResponse` | resolve (id, then name) |
| `PUT /api/roles/{id}` | any write permission | `UpdateRoleRequest` → `UpdateCommand(id = resolved role's id)` | 204, empty | the handler resolves first (404 before validation), then runs the use case with the TSID |
| `DELETE /api/roles/{id}` | `role:delete` | `DeleteCommand(resolved id)` | 204 | |
| `GET /api/roles/by-code/{code}` | `role:view` | — | 200 `RoleResponse` | by name only, no id fallback |
| `GET /api/roles/by-source/{source}` | `role:view` | — | 200 bare JSON array `[RoleResponse]` | `source` parsed leniently: anything but `CODE`/`SDK` lists the `DATABASE` roles. **accident?** |
| `GET /api/roles/by-application/{applicationId}` | `role:view` | — | 200 bare array | roles whose `applicationId` matches (SDK-synced roles in practice) |
| `GET /api/roles/filters/applications` | `role:view` | — | 200 `{applicationCodes[]}` | distinct non-null codes, ordered |
| `GET /api/roles/{roleName}/permissions` | `role:view` | — | 200 `{permissions[]}` | by name only → 404 `Role_NOT_FOUND` |
| `POST /api/roles/{roleName}/permissions/{permission}` | any write permission | `GrantPermissionCommand(roleName, permission)` | 200 `RoleResponse` (re-read) | |
| `POST /api/roles/{roleName}/permissions` | any write permission | `GrantPermissionRequest{permission}` → same command | 200 `RoleResponse` | SDK-compatibility alias — **load-bearing** |
| `DELETE /api/roles/{roleName}/permissions/{permission}` | any write permission | `RevokePermissionCommand(roleName, permission)` | 200 `RoleResponse` (re-read) | |
| `GET /api/roles/permissions` | `role:view` | — | 200 `PermissionListResponse` `{permissions[], total}` | catalogue, ordered by code |
| `GET /api/roles/permissions/{permission}` | `role:view` | — | 200 `PermissionResponse` | 404 `Permission_NOT_FOUND` |
| `DELETE /api/roles/permissions/{permission}` | `role:delete` | — | 204 | idempotent (absent code → 204). **Bypasses the use-case envelope: no domain event, no audit row.** **load-bearing or accident?** |

Route precedence: the literal segments (`permissions`, `by-code`,
`by-source`, `by-application`, `filters`) win over `{id}` / `{roleName}`
(chi semantics) — so a role literally named `permissions` cannot be
addressed on `/api/roles/{roleName}/permissions`. Kept.

Sync entry points (the operations live here; the routes do not):

| Entry point | Gate | Operation |
|---|---|---|
| `POST /api/applications/{appCode}/roles/sync` (sdksync) | `canSyncRoles` + application resolution | `SyncRoles` |
| `POST /bff/roles/sync-platform` (BFF) | anchor | `SyncPlatformRoles` with the seed catalogue |

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `CreateRoleRequest` | `applicationCode`*, `roleName`*, `displayName`*, `description`, `permissions[]`, `clientManaged`* | `clientManaged` is marked required but a missing boolean reads as `false` |
| `UpdateRoleRequest` | `displayName`, `description`, `permissions[]`, `clientManaged` | **every field optional; absent = untouched.** `permissions: []` clears the set; absent leaves it. `description: ""` stores the empty string (it cannot be reset to absent) |
| `GrantPermissionRequest` | `permission`* | |
| `RoleResponse` | `id, applicationId?, name, displayName, description?, applicationCode, permissions[], source, clientManaged, createdAt, updatedAt` | optional fields omitted when null; `permissions` always present (may be `[]`); `applicationCode` always present (`""` when the row has none) |
| `RoleListResponse` | `roles[], total` | `total` = `roles.length` (no pagination) |
| `RolePermissionListResponse` | `permissions[]` | |
| `ApplicationFilterListResponse` | `applicationCodes[]` | |
| `PermissionResponse` | `permission, name, description?, category?` | `name` = the code; `category` = `subdomain:context:aggregate` (always present for a stored row) |
| `PermissionListResponse` | `permissions[], total` | |
| `CreatedResponse` | `id` | |

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `applicationCode` non-blank | `APPLICATION_REQUIRED` | `applicationCode is required` |
| Create | `roleName` non-blank | `ROLE_NAME_REQUIRED` | `roleName is required` |
| Create | `displayName` non-blank | `DISPLAY_NAME_REQUIRED` | `displayName is required` |
| Update | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `displayName`, when present, non-blank | `DISPLAY_NAME_REQUIRED` | `displayName cannot be empty` |
| Delete | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Grant / Revoke | `roleName` non-blank | `ROLE_NAME_REQUIRED` | `Role name is required` |
| Grant / Revoke | `permission` non-blank | `PERMISSION_REQUIRED` | `Permission is required` |
| SyncRoles | `applicationCode` non-blank | `APPLICATION_CODE_REQUIRED` | `Application code is required` |
| SyncRoles | at least one role | `ROLES_REQUIRED` | `At least one role must be provided` |
| SyncPlatformRoles | — (empty command) | | |

Trimming: update trims `displayName`; create stores `roleName` and
`displayName` **verbatim** (a role name with surrounding spaces is legal).
**accident?** — kept. Neither `roleName` nor a permission code has a format
rule on the write path (any non-blank string is accepted; wildcards and
segment counts are not checked). Malformed JSON body → 400 `INVALID_JSON`
(transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | coarse permission (table in §3); unauthenticated → 403 `UNAUTHENTICATED` |
| Create / Update / Delete / Grant / Revoke / SyncPlatformRoles | `publicAccess` — roles have no per-instance dimension; each entry point keeps its own gate |
| SyncRoles — `authorize` phase | `canAccessApplication(cmd.applicationId)` (all-applications, or the id is in the principal's application list) → else 403 `FORBIDDEN` `Not authorised for application '<applicationCode>'`; no principal → `UNAUTHENTICATED` |
| Reads | handler only |

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | a role with name `{applicationCode}:{roleName}` exists | `ROLE_EXISTS` | 409 — message `Role '<name>' already exists` |
| Update / Delete | no role with that id | `Role_NOT_FOUND` | 404 |
| Update / Delete | role is `CODE`-sourced | `CODE_ROLE_IMMUTABLE` | 409 |
| Grant / Revoke | no role with that **name** | `Role_NOT_FOUND` | 404 |
| SyncRoles (`removeUnlisted`) | an unlisted `SDK` role still has principal assignments | `ROLE_HAS_ASSIGNMENTS` (business rule) | 400 — `Cannot remove role '<name>' — <n> principal(s) still hold it. Strip the assignments before syncing.`; the **whole sync aborts** |

## 7. Sync semantics

### 7.1 `SyncRoles` — one application's SDK catalogue

Input: `applicationCode`, `applicationId` (resolved by the caller),
`roles[]{name, displayName?, description?, permissions[], clientManaged}`,
`removeUnlisted`. Existing rows = every role whose `applicationId` equals
`cmd.applicationId`.

For each input row, canonical name = `{applicationCode}:{short}` where
`short` = `lowercase(name)` with a leading `{applicationCode}:` stripped
(so a bare `hr-manager`, a qualified `hr:hr-manager` and a malformed
`hr:dashboard:user` all round-trip without double-prefixing).

| Input row | Effect | Per-row event |
|---|---|---|
| canonical name exists with source `SDK` | `displayName` = input `displayName` or, absent, the **raw** input `name` (not lowercased); `description` replaced; `permissions` replaced **only when the input list is non-empty** (apps declare role names, permissions are curated in the UI — an empty list must not wipe them); `clientManaged` replaced | `updated` |
| canonical name exists with source `CODE` / `DATABASE` | untouched, not counted | — |
| new | created `SDK`-sourced with `applicationId` stamped, description, permissions (may be empty), `clientManaged` | `created` |
| `removeUnlisted` and existing `SDK` row not in the input | refused with `ROLE_HAS_ASSIGNMENTS` if any principal holds it, else hard-deleted | `deleted` |
| `removeUnlisted` and existing `CODE`/`DATABASE` row | untouched | — |

Rollup `RolesSynced{created, updated, removed, total = input row count,
applicationCode, syncedCodes = canonical names in input order}`.

**load-bearing or accident?** The "existing" set is scoped by
`applicationId`, but role names are globally unique. A `DATABASE` role created
through the admin API for the same application has `applicationId = null`, so
a same-named SDK row is not "existing" and the insert fails on
`iam_roles_name_key` (500). The documented intent ("never touch CODE/DATABASE
rows") suggests the lookup should be by name; kept as-is pending a ruling.

### 7.2 `SyncPlatformRoles` — the built-in catalogue

Input: the static catalogue (`seed.PlatformRoles.all()`, 14 roles, all
`application platform`, source `CODE`), an empty command (exists so the
audit log names `SyncPlatformRolesCommand`).

| Catalogue entry | Effect | Per-row event |
|---|---|---|
| name exists with source `CODE` | `displayName`, `description`, `permissions` replaced from the catalogue (`clientManaged` untouched) | `updated` |
| name exists with another source | skipped with a warning (operator chose a non-CODE replacement) | — |
| new | created `CODE`-sourced, `applicationId` null | `created` |
| `CODE` row in the DB whose name is not in the catalogue | deleted if no principal holds it; otherwise skipped with a warning (**not** an error — contrast with §7.1) | `deleted` |

Rollup `RolesSynced{created, updated, removed, total = catalogue size}`;
`applicationCode` / `syncedCodes` absent. **load-bearing or accident?** —
stale-but-assigned `CODE` roles are warned past here but refused in the SDK
sync; plausibly deliberate (the platform sync must not fail a deployment).

Both syncs: every row write, per-row event and the rollup commit in one
transaction; one audit row per event, all with `operation` =
`SyncRolesCommand` / `SyncPlatformRolesCommand`.

## 8. Domain events

Source is always `platform:admin`; spec version `1.0`; per-role subject is
`platform.role.{id}` with message group `platform:role:{id}` (one role's
events are delivered in order). `data` omits null fields.

| Type | Subject / group | `data` fields |
|---|---|---|
| `platform:admin:role:created` | `platform.role.{id}` / `platform:role:{id}` | `roleId, name` |
| `platform:admin:role:updated` | same | `roleId, name` |
| `platform:admin:role:deleted` | same | `roleId, name` |
| `platform:admin:role:permission-granted` | same | `roleId, roleName, permission` |
| `platform:admin:role:permission-revoked` | same | `roleId, roleName, permission` |
| `platform:admin:roles:synced` | `platform.roles` / `platform:roles` | `created, updated, removed, total, applicationCode?, syncedCodes?` |

The rollup's subject is `platform.roles` for **both** syncs: the SDK sync
builds `platform.roles.{applicationCode}` into its metadata but the event's
subject accessor returns the constant, and the sink reads the accessor — so
the per-application subject never reaches the database. **load-bearing or
accident?** (the event-type sync uses `platform.eventtypes.{app}` + a
per-app group; roles could do the same). Kept: `platform.roles`, group
`platform:roles`, which also means the audit row for the rollup has
`entity_type = Roles` and an empty `entity_id`.

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Role`, `entity_id = {id}`,
`operation` = command record simple name, `operation_json` = the command) in
the same transaction as the row change.

## 9. Persistence

`iam_roles` upsert `ON CONFLICT (id)` updating every column except
`created_at`; `updated_at` is stamped `now()` at persist time (**accident?**,
harmless). `application_code` is written as `NULL` when absent. Permissions
are replaced wholesale: `DELETE FROM iam_role_permissions WHERE role_id` then
one insert per (de-duplicated) code. Delete removes the permission rows and
the role (`iam_role_permissions` also cascades on the FK). Reads hydrate
permissions for all roles in one `IN` query; lists are `ORDER BY name`.

Catalogue (`iam_permissions`): reads `ORDER BY code`; upsert `ON CONFLICT
(code)` refreshing the segments, description and `updated_at`; delete by code
(no error when absent).

Assignment count = `COUNT(*) FROM iam_principal_roles WHERE role_name = ?`.

Short-name resolution (for SDK-synced principal assignments, which carry the
bare short name): the role whose `name` = `{application_code}:{shortName}`
**and** whose `applicationId` is one of the caller's application ids; a
blank short name or an empty application list resolves to nothing. No route
on this surface uses it; admin-created rows (no `applicationId`) are never
matched.

## 10. Open questions for the owner (summary)

1. Grant/revoke are allowed on `CODE` roles while update/delete are refused.
2. The SDK sync's "existing" lookup is by `applicationId`, not name — a
   same-named admin (`DATABASE`) role makes the sync fail with a unique
   violation.
3. `SyncPlatformRoles` warns past stale-but-assigned `CODE` roles; `SyncRoles`
   refuses with `ROLE_HAS_ASSIGNMENTS`. Intended asymmetry?
4. The catalogue delete (`DELETE /api/roles/permissions/{permission}`) is a
   direct repository write — no event, no audit.
5. ~~`/by-source/{anything-else}` lists `DATABASE` roles (lenient parse).~~ **Ruled 2026-09-05 (parity S1-A): 400 `INVALID_SOURCE`, Go's rule.**
6. Create stores `roleName`/`displayName` untrimmed; update trims.
7. The roles-synced rollup subject is always `platform.roles` (the per-app
   metadata subject is dead); audit `entity_id` is empty for it.
8. Create / update / grant / revoke gated by *any* write permission rather
   than the specific verb.
9. `UpdateRoleRequest.description: ""` stores an empty string; absent leaves
   the old value — there is no way to clear a description.
