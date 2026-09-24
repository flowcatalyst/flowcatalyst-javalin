# Application — behavioural spec

The contract for `io.flowcatalyst.platform.application`. Derived from the
lockfile (`/api/applications*` — sixteen operations once the nine
`/api/applications/{appCode}/*/sync` routes, which belong to the `sdksync`
unit, are set aside) plus the validation rules, authorization placement,
state machine, error codes and domain events the aggregate embodies. The
Java is written *from* this; tests assert it. Questions marked
**load-bearing or accident?** need an owner ruling — until ruled on, the
behaviour is kept.

## 1. Aggregates

### 1.1 Application

A registered application or integration. Applications are **platform-level**:
there is no client dimension on the row, so per-resource authorization does
not exist — every gate is the handler's coarse permission (or anchor).

| Field | Type | Notes |
|---|---|---|
| `id` | `app_` + 13-char TSID | generated on create |
| `type` | `APPLICATION` \| `INTEGRATION` | default `APPLICATION`; immutable after create |
| `code` | string, unique | normalised (trimmed + lower-cased) at create; `^[a-z][a-z0-9_-]*$`; immutable |
| `name` | string, required | trimmed at create and update |
| `description`, `iconUrl`, `website`, `logo`, `logoMimeType`, `defaultBaseUrl` | strings, optional | stored verbatim — an explicit `""` is stored as `""` (it is how the SPA clears a field on update). **load-bearing or accident?** |
| `serviceAccountId` | `sac_…` principal id, optional | the **principal** id of the application's service account (the FK points at `iam_principals`, not `iam_service_accounts`); set once by attach/provision, never cleared |
| `active` | boolean | default `true` |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Lenient enum read: unknown `type` → `APPLICATION` (**accident?** — masks a
bad row; kept, same as the other aggregates).

### 1.2 Application client config

The per-(application, client) enablement row (`app_client_configs`). It is a
separate aggregate: enable/disable write this row, not the application.

| Field | Type | Notes |
|---|---|---|
| `id` | `apc_` + TSID | |
| `applicationId`, `clientId` | ids | one row per pair (no unique constraint in the DB — **accident?**; the operations always look the pair up first) |
| `enabled` | boolean | new rows start `true` |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` stamped `now()` on persist |
| `baseUrlOverride`, `configJson` | wire only | present in the `ClientConfigResponse` schema, **never populated** (no column). **load-bearing or accident?** |

## 2. State machines

Application: `active` is a flag, not a lifecycle. `activate` / `deactivate`
are **idempotent** — activating an active application is allowed, writes the
row again (new `updatedAt`) and emits the event. No transition is refused.
**load-bearing or accident?** (dispatch pools and connections refuse
no-op transitions with a 409; applications do not).

Service account: `serviceAccountId` is write-once: `attach` on an
application that already has one → 409 `APPLICATION_HAS_SERVICE_ACCOUNT`
(business rule; the provisioning flow answers the same state with 409
`ALREADY_PROVISIONED` — two codes for one condition, **accident?**).

Client config: `enabled` flips both ways, idempotently; a disable on an
already-disabled row still writes and still emits (audit trail).

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`. Write permission =
`requireAny(application:create, application:update, application:delete)`.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/applications` | `application:view` | query `type`, `active` | 200 `ApplicationListResponse` `{applications: [ApplicationResponse], total}` | ordered by code; `active` is parsed as `"true"` → active only, any other non-empty value → inactive only, absent → all. **accident?** (`active=yes` lists the inactive ones) |
| `POST /api/applications` | write | `CreateApplicationRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | |
| `GET /api/applications/by-code/{code}` | `application:view` | — | 200 `ApplicationResponse` | 404 `Application_NOT_FOUND` (message carries the code) |
| `GET /api/applications/{id}` | `application:view` | — | 200 `ApplicationResponse` | 404 `Application_NOT_FOUND` |
| `PUT /api/applications/{id}` | write | `UpdateApplicationRequest` → `UpdateCommand` | 204 | PATCH semantics: absent field = unchanged |
| `POST /api/applications/{id}/activate` | write | `ActivateCommand{id}` | 200 `ApplicationResponse` (re-read) | |
| `POST /api/applications/{id}/deactivate` | write | `DeactivateCommand{id}` | 200 `ApplicationResponse` (re-read) | |
| `DELETE /api/applications/{id}` | `application:delete` | `DeleteCommand{id}` | 204 | hard delete; client configs are **not** cascaded (**accident?** — orphaned `app_client_configs` rows) |
| `POST /api/applications/{id}/service-account` | anchor + `APPLICATION_UPDATE` | `AttachServiceAccountRequest{serviceAccountId, serviceAccountCode}` → `AttachServiceAccountCommand` | 204 | |
| `GET /api/applications/{id}/clients` | `application:view` | — | 200 `ClientConfigListResponse` `{items}` | ordered by `createdAt`; an unknown application id lists `[]`, not 404 (**accident?**) |
| `GET /api/applications/{id}/clients/{clientId}` | `application:view` | — | 200 `ClientConfigResponse` | 404 `ClientConfig_NOT_FOUND`, id rendered `{appId}:{clientId}` |
| `POST /api/applications/{id}/clients/{clientId}/enable` | anchor + `APPLICATION_ENABLE_CLIENT` | `EnableForClientCommand` | 204 | |
| `POST /api/applications/{id}/clients/{clientId}/disable` | anchor + `APPLICATION_DISABLE_CLIENT` | `DisableForClientCommand` | 204 | |
| `GET /api/applications/by-id/{id}/roles` | `application:view` | — | 200 `ApplicationRolesResponse` `{roles: [name]}` | role **names** (canonical `app:short`), ordered by name; unknown id → `[]` |
| `POST /api/applications/{id}/provision-service-account` | anchor + `SERVICE_ACCOUNT_CREATE` + `APPLICATION_UPDATE` | `ProvisionServiceAccountCommand{applicationId}` | 201 `ApplicationProvisionServiceAccountResponse` | see §10 |
| `POST /api/applications/{id}/provision-login-client` | anchor + `OAUTH_CLIENT_CREATE` + `APPLICATION_UPDATE` | `ProvisionLoginClientRequest` | 201 `ApplicationProvisionLoginClientResponse` | see §10 |

`ApplicationResponse` fields, in order: `id, type, code, name, description?,
iconUrl?, website?, logo?, logoMimeType?, defaultBaseUrl?, serviceAccountId?,
active, hasLoginClient, createdAt, updatedAt`. `hasLoginClient` is required
by the schema; it is computed (§10) — true when an active client linked to
the application allows the `authorization_code` grant (the SPA gates its
"provision login client" form on it).

`ClientConfigResponse`: `id, applicationId, clientId, enabled,
baseUrlOverride?, configJson?, createdAt, updatedAt` — the two optionals are
always omitted (§1.2).

Also owned by this aggregate but reached from the **client** surface:
`UpdateClientApplicationsCommand{clientId, enabledApplicationIds[]}` behind
`PUT /api/clients/{id}/applications` (registered by the client Api, which
holds the coarse gate). Ported here as an operation; the route is the
client unit's.

## 4. Validation (validate phase — pure)

| Command | Rule | Code |
|---|---|---|
| Create | `code` blank after trim | `CODE_REQUIRED` "code is required" |
| Create | normalised code does not match `^[a-z][a-z0-9_-]*$` | `INVALID_CODE_FORMAT` "code must start with a lowercase letter and contain only lowercase alphanumerics, hyphens, and underscores" |
| Create | `name` blank | `NAME_REQUIRED` "name is required" |
| Create | `type` | not validated: `INTEGRATION` → integration, anything else (incl. absent) → `APPLICATION` (**accident?** — `type=foo` silently creates an application) |
| Update | `id` blank | `ID_REQUIRED` |
| Update | `name` present and blank | `NAME_REQUIRED` "name cannot be empty" |
| Delete / Activate / Deactivate | `id` blank | `ID_REQUIRED` |
| AttachServiceAccount | `applicationId` blank | `APPLICATION_ID_REQUIRED` |
| AttachServiceAccount | `serviceAccountId` blank | `SERVICE_ACCOUNT_ID_REQUIRED` (`serviceAccountCode` is not validated — it is only copied onto the event) |
| EnableForClient / DisableForClient | `applicationId` blank | `APPLICATION_ID_REQUIRED` |
| EnableForClient / DisableForClient | `clientId` blank | `CLIENT_ID_REQUIRED` |
| UpdateClientApplications | `clientId` blank | `CLIENT_ID_REQUIRED` |
| UpdateClientApplications | an entry of `enabledApplicationIds` blank | `APPLICATION_ID_REQUIRED` (raised in execute, after the client lookup — **accident?**, it is a shape check) |

## 5. Authorization placement

| Where | Rule |
|---|---|
| Handler | coarse permission / anchor (table in §3); unauthenticated → 403 `UNAUTHENTICATED` |
| Create / Update / Delete / Activate / Deactivate / AttachServiceAccount | `publicAccess` — the application is platform-level, there is nothing per-instance to check |
| EnableForClient / DisableForClient / UpdateClientApplications — `authorize` phase | `checkScopeAccess(principal, cmd.clientId)`: the resource is the **client**; a non-anchor needs access to that client. (The HTTP entry points are anchor-only anyway, so this only bites for other callers.) |
| Reads | handler only |

## 6. Conflicts, not-found, business rules (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another application has the normalised code | `CODE_EXISTS` "Application with code '…' already exists" | 409 |
| Update / Delete / Activate / Deactivate / Attach / Enable | no application with that id | `Application_NOT_FOUND` | 404 |
| Attach | application already has a service account | `APPLICATION_HAS_SERVICE_ACCOUNT` | 409 (business rule) |
| Attach | no principal linked to that service account | `ServiceAccountPrincipal_NOT_FOUND` | 404 |
| Enable / UpdateClientApplications | no client with that id | `Client_NOT_FOUND` | 404 |
| UpdateClientApplications | an enabled application id does not exist | `Application_NOT_FOUND` | 404 (checked for every id **before** any row is touched) |
| Disable | no config row for the pair | `ClientConfig_NOT_FOUND`, id `{appId}:{clientId}` | 404 |

Enable does **not** require an existing config row: it re-enables an
existing one (same id) or creates a fresh enabled one. Disable requires the
row (a never-enabled pair cannot be disabled).

## 7. Update-client-applications semantics

Input: `clientId`, `enabledApplicationIds[]` = the desired final enabled set.

| Current row for (app, client) | In desired set | Effect |
|---|---|---|
| none | yes | fresh enabled row; `enabledAdded` |
| disabled | yes | same row → enabled; `enabledAdded` |
| enabled | yes | untouched |
| enabled | no | same row → disabled; `disabledRemoved` |
| disabled / none | no | untouched |

All row writes plus the single rollup event commit in one transaction; an
empty diff still emits the rollup (and its audit row) so the request is on
record. The rollup's `enabledApplicationIds` is the input list verbatim (in
order, duplicates kept — **accident?**).

## 8. Domain events

Source is always `platform:iam`; spec version `1.0`; per-application
subject `platform.application.{id}` **and message group
`platform:application:{id}`** (every per-application event is ordered per
application). `data` omits nothing — every field is always present.

| Type | Subject / group | `data` fields |
|---|---|---|
| `platform:iam:application:created` | `platform.application.{id}` / `platform:application:{id}` | `applicationId, code, name` |
| `platform:iam:application:updated` | same | `applicationId, name` |
| `platform:iam:application:activated` | same | `applicationId` |
| `platform:iam:application:deactivated` | same | `applicationId` |
| `platform:iam:application:deleted` | same | `applicationId, code` |
| `platform:iam:application:service-account-provisioned` | same | `applicationId, applicationCode, serviceAccountId, serviceAccountCode` — `serviceAccountId` is the **service-account** id from the command, not the principal id stored on the row (**accident?**) |
| `platform:iam:application:enabled-for-client` | same | `applicationId, clientId, configId` |
| `platform:iam:application:disabled-for-client` | same | `applicationId, clientId, configId` |
| `platform:iam:client:applications-updated` | `platform.client.{clientId}` / `platform:client:{clientId}` | `clientId, enabledApplicationIds[], enabledAdded[], disabledRemoved[]` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type` / `entity_id` derived from the event's
**subject** — `Application` / `{id}` for every per-application event,
including the client-config ones, and `Client` / `{clientId}` for the
rollup; `operation` = command record simple name, `operation_json` = the
command) in the same transaction as the row change.

## 9. Persistence

Application: upsert `ON CONFLICT (id)`; `created_at` insert-only;
`updated_at` stamped `now()` at persist. Delete removes only the
`app_applications` row. Client config: upsert `ON CONFLICT (id)` updating
`enabled, updated_at` only (`application_id` / `client_id` are insert-only).

Cross-aggregate reads the operations need (read-only, by id):
`tnt_clients` existence (enable / update-client-applications),
`iam_principals.service_account_id → id` (attach), `iam_roles` by
`application_id` (roles listing). The first two are **parked** on
`ApplicationRepository` (`clientExists`, `servicePrincipalIdFor`) as
minimal read-only queries so this unit has no compile-time dependency on
the client / principal units landing concurrently; they move to those
repositories' reads once they exist (backlog). The roles listing already
uses `RoleRepository.findByApplicationId`.

## 10. Provisioning (ported 2026-09-05, application-provisioning brief)

**Superseded 2026-09-25** (`security-fixes-2026-09-24.md` S1.2, commit
`6068fe6b`; `ApplicationApi.java`): §3 marks `service-account` (attach),
`provision-service-account`, `provision-login-client`, and
`clients/{clientId}/enable`/`disable` as gate "anchor" — anchor reach alone
is no longer any of their whole gate (the anchor tier is reach, never
authority). Each now also requires a permission:

| Route | Gate now |
|---|---|
| `POST …/{id}/service-account` (attach) | `requireAnchor` + `APPLICATION_UPDATE` — the attached account becomes the application's own identity |
| `POST …/{id}/provision-service-account` | `requireAnchor` + `SERVICE_ACCOUNT_CREATE` (it creates a service account and issues its credential — the `/api/service-accounts` create permission) + `APPLICATION_UPDATE` (it rewrites the application's service-account link) |
| `POST …/{id}/provision-login-client` | `requireAnchor` + `OAUTH_CLIENT_CREATE` (the `/api/oauth-clients` create permission — this IS an OAuth client create) + `APPLICATION_UPDATE` |
| `POST …/{id}/clients/{clientId}/enable` | `requireAnchor` + `APPLICATION_ENABLE_CLIENT` — an enabled application's roles become assignable by that client's admins |
| `POST …/{id}/clients/{clientId}/disable` | `requireAnchor` + `APPLICATION_DISABLE_CLIENT` |

- `POST …/provision-service-account` (`ProvisionServiceAccount`
  `TxOperation`): creates a service account (with generated webhook
  credentials), its `SERVICE` principal (app-scoped, granted the seeded
  `platform:application-service` role with assignment source `PROVISIONED`,
  with role + application-access junction rows), attaches it, and creates a
  `CONFIDENTIAL` OAuth client (`client_credentials`+`refresh_token`, scope
  `openid`, secret encrypted with `FLOWCATALYST_APP_KEY`, returned in
  plaintext exactly once) — four aggregates in one transaction; no app key
  configured fails the whole transaction (internal `SECRET`).
- `POST …/provision-login-client`: a thin handler over the OAuth-client
  aggregate's `CreateOAuthClient` (`authorization_code`+`refresh_token`,
  scopes `openid profile email`, PKCE for `PUBLIC`); no application write.
  **Deliberate deviation from Go** (`docs/backlog.md`): the request's
  `allowedOrigins` field is declared on the lockfile schema but Go never
  reads it (a Go defect); Java stores it on the created client.
- `hasLoginClient` is now computed (`OAuthClientRepository#hasLoginClientFor`):
  true when an **active** client linked to the application allows the
  `authorization_code` grant (§11 q3, now implemented).

## 11. Open questions for the owner (summary)

1. Activate / deactivate are idempotent (no 409 on a no-op) — keep?
2. Attach answers `APPLICATION_HAS_SERVICE_ACCOUNT`, provision `ALREADY_PROVISIONED` (both 409) for the same state.
3. ~~`hasLoginClient` always `false`~~ **Done (2026-09-05): implemented**, see §10; `baseUrlOverride` / `configJson` never populated.
4. `active=<anything but "true">` lists inactive applications.
5. `type` is not validated (unknown → `APPLICATION`).
6. Delete leaves orphaned `app_client_configs` rows.
7. `GET …/{id}/clients` and `…/by-id/{id}/roles` answer `[]` for an unknown application instead of 404.
8. `service-account-provisioned.serviceAccountId` carries the SA id while the row stores the principal id.
9. `""` on an optional update field is stored verbatim (the clear-a-field idiom).
