# BFF and `/api/me` — the SPA's own routes

Extracted 2026-09-05 against Go HEAD (`9f7be62`) from
`internal/platform/shared/bff/{dashboard,filter_options,developer,event_types,roles,scheduled_jobs}.go`,
`internal/platform/shared/me/me.go`, the aggregate-mounted BFF groups in
`event/api`, `dispatchjob/api`, `process/api`, and the SPA's own contract in
`frontend/src/api/{dashboard,developer,event-types,filter-options,permissions,processes,roles,scheduled-jobs,client}.ts`
(`docs/frontend-api-types-adoption.md`: the BFF set is deliberately
*outside* the OpenAPI lockfile and hand-rolled on `bffFetch`, base `/bff`).
**The frontend is the acceptance test**: every route below must serve the
existing SPA unchanged. Behaviour tables, never code. `[C]` contract, `[I]`
implementation detail, `[D]` defect / question.

## 1. Conventions shared by every BFF route [C]

- Session-cookie or bearer through the same `Authenticator` as `/api`;
  `Auth.scoped` handlers. Errors in the platform envelope (`HttpError`).
- List responses are **unpaginated `{items, total}`** (total = item count)
  except scheduled jobs (§7, page/size). Timestamps as everywhere else.
- Client scope: a non-anchor sees only rows for clients it can access
  (`ac.canAccessClient`); an anchor sees all. Where Go filters in memory
  after `findAll`, Java may filter in SQL — the observable set is what is
  pinned.
- These routes reuse the aggregates' **existing operations and
  repositories**; only the wire DTOs are BFF-specific. Nothing here adds a
  new event, audit row or state transition beyond what the aggregate spec
  already defines — cite it rather than restate it.
- Java package `io.flowcatalyst.platform.bff` (`api/DashboardBff`,
  `FilterOptionsBff`, `DeveloperBff`, `EventTypesBff`, `RolesBff`,
  `ScheduledJobsBff`, `MeApi`); the `/bff/events`, `/bff/dispatch-jobs`,
  `/bff/processes` mounts are registered **by the owning aggregate's Api**
  under a second prefix (Go `registerBFF`/`registerAt`).
  `LockfileCoverageTest` already excludes `/bff/*` and `/api/me*`.

## 2. Dashboard — `GET /bff/dashboard/stats` [C]

`{totalClients, activeUsers, rolesDefined, eventsApprox, dispatchJobsApprox, auditLogsApprox, loginAttemptsApprox}`.
Exact: `COUNT(*) FROM tnt_clients`; `iam_principals WHERE type='USER' AND active`;
`iam_roles`. Approximate (`GREATEST(reltuples,0)::bigint` from `pg_class`,
summed over a partitioned parent's children): `msg_events`,
`msg_dispatch_jobs`, `aud_logs`, `iam_login_attempts`. Any authenticated
principal (§9 D1).

## 3. Filter options [C]

- `GET /bff/filter-options/clients` → `[{value: clientId, label: name}]`,
  every client the caller can access (anchor: all), sorted by label.
- `GET /bff/event-types/filters/applications` → `[application]` distinct
  first segments of all event-type codes, sorted.

## 4. Developer — anchor-only, every route [C]

| Route | Backing | Response |
|---|---|---|
| `GET /bff/developer/applications` | `application.findActive` + per app `openapispecs.findCurrentByApplication` | `{items:[{id, code, name, description?, iconUrl?, currentVersion?, currentSpecId?, currentSyncedAt?}]}` |
| `GET …/applications/{appId}` | `findById` (404) + current spec | one summary |
| `GET …/applications/{appId}/openapi/current` | current spec (404 when none) | `{id, applicationId, version, status, spec, changeNotesText?, changeNotes?: {addedPaths, removedPaths, addedSchemas, removedSchemas, removedOperations, hasBreaking}, syncedAt}` |
| `GET …/applications/{appId}/openapi/versions` | all specs of the app, newest first | `{items:[{id, version, status, changeNotesText?, hasBreaking, syncedAt}]}` |
| `GET …/applications/{appId}/openapi/versions/{specId}` | one spec (404; must belong to the app) | full spec response |
| `GET …/applications/{appId}/event-types` | event types of the app with their spec versions | `{items:[{id, code, name, description?, status, application, subdomain, aggregate, eventName, specVersions:[{id, version, status, schema}]}]}` |
| `POST /bff/developer/sync-platform-openapi` | `openapispecs.SyncOpenApiSpec` for the platform's own document | `{applicationCode, specId, version, status, archivedPriorVersion, hasBreaking, unchanged}` |

## 5. Event types (SPA shape) [C]

`bffEventTypeResponse{id, code, application, subdomain, aggregate, event, name, description?, status, clientScoped, specVersions:[{id, version, status, schemaType, mimeType, schema, createdAt, updatedAt}], createdAt, updatedAt}`.

| Route | Backing (`docs/spec/eventtype.md`) | Notes |
|---|---|---|
| `GET /bff/event-types?status&application&subdomain&aggregate` | `findWithFilters` | `{items, total}` |
| `GET /bff/event-types/filters/subdomains?application` / `…/filters/aggregates?application&subdomain` | distinct segments | `[string]` sorted |
| `GET /bff/event-types/{id}` | `findById` | 404 |
| `POST /bff/event-types` `{code, name, description?, schema?, clientId?}` | `CreateEventType` (+ `AddSchema` when `schema` given) | 201, the full response |
| `PUT /bff/event-types/{id}` `{name, description?}` | `UpdateEventType` | 204 |
| `DELETE /bff/event-types/{id}` | `DeleteEventType` | 204 |
| `POST /bff/event-types/{id}/archive` | `ArchiveEventType` | 200, the full response |
| `POST /bff/event-types/{id}/schemas` `{schema, mimeType?, schemaType?, version?}` | `AddSchema` | 200, the full response |
| `POST /bff/event-types/{id}/schemas/{version}/finalise` / `…/deprecate` | `FinaliseEventTypeSchema` / `DeprecateEventTypeSchema` | 200, the full response |
| `POST /bff/event-types/sync-platform` `{applicationCode}` | anchor-only; `SyncEventTypes` with the platform catalogue | `{created, updated, deleted, total, schemas:{created, updated, unchanged}}` |

Permissions: the same coarse gates as `/api/event-types` (the aggregate
spec), except `sync-platform` which is anchor-only.

## 6. Roles and the permission catalogue [C]

`bffRoleResponse{id, name, shortName, displayName, description?, permissions[], applicationCode, source, clientManaged, createdAt, updatedAt}`;
`bffPermissionResponse{permission, application, context, aggregate, action, description?}`
(the four segments of `app:context:aggregate:action`).

| Route | Backing (`docs/spec/role.md`) | Notes |
|---|---|---|
| `GET /bff/roles?application&source` | `findAll`, filtered | `{items, total}` |
| `GET /bff/roles/filters/applications` | distinct application codes of the roles | `{options:[{id, code, name}]}` |
| `GET /bff/roles/permissions?application` | **catalogue = the seeded platform permissions ∪ `iam_permissions` rows** (`PermissionRepository.findAll`), filtered by application, deduplicated by code | `{items, total}` |
| `POST /bff/roles/permissions` `{application, context, aggregate, action, description?}` | anchor-only; `PermissionRepository.upsert` of `app:context:aggregate:action` | 201 |
| `GET /bff/roles/permissions/{permission}` | one catalogue entry | 404 |
| `GET /bff/roles/{roleName}` | `findByName` | 404 |
| `POST /bff/roles` `{applicationCode, roleName, displayName, description?, permissions[], clientManaged}` | anchor-only; `CreateRole` | 201 `{id}` |
| `PUT /bff/roles/{roleName}` `{displayName?, description?, clientManaged?, permissions?}` | anchor-only; `UpdateRole` | 204 |
| `DELETE /bff/roles/{roleName}` | anchor-only; `DeleteRole` | 204 |
| `POST /bff/roles/sync-platform` | anchor-only; `SyncPlatformRoles(seed platform roles)` | `{created, updated, removed, total}` |

## 7. Scheduled jobs (paginated) [C]

`bffScheduledJobResponse{id, clientId?, clientName?, applicationId?, applicationName?, code, name, description?, status, crons[], timezone, payload?, concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, lastFiredAt?, createdAt, updatedAt, version, hasActiveInstance}`
— `hasActiveInstance` from `ScheduledJobInstanceRepository.hasActiveInstance(jobId, tracksCompletion)`;
client/application names joined in.
`bffScheduledJobInstanceResponse{id, scheduledJobId, jobCode, clientId?, triggerKind, scheduledFor, firedAt?, deliveredAt?, completedAt?, status, deliveryAttempts, deliveryError?, completionStatus?, completionResult?, correlationId?, createdAt}`;
`bffInstanceLogResponse{id, instanceId, level, message, metadata?, createdAt}`;
pages `{data, page, size, total, totalPages}` with `page` **0-based**,
`size` default and cap per Go's `parsePagination`, `totalPages = ceil(total/size)`.

| Route | Params | Backing (`docs/spec/scheduledjob.md`) |
|---|---|---|
| `GET /bff/scheduled-jobs` | `clientIds` (CSV; the literal `platform` = platform-scoped, `client_id IS NULL`), `applicationIds`, `statuses`, `search`, `page`, `size` | repository filtered list + count; non-anchor restricted to accessible clients |
| `GET /bff/scheduled-jobs/filter-options` | — | `{clients:[{value,label}], applications:[…], statuses:[…]}` scoped as above |
| `GET /bff/scheduled-jobs/{id}` | — | 404; a job of an inaccessible client → 404 (not 403) |
| `GET /bff/scheduled-jobs/{id}/instances` | `status`, `triggerKind`, `from`, `to` (RFC 3339), `page`, `size` | instances list + count |
| `GET /bff/scheduled-jobs/instances/{instanceId}` | — | 404; scope via the instance's client |
| `GET /bff/scheduled-jobs/instances/{instanceId}/logs` | — | the instance's logs, oldest first |

## 8. Aggregate-mounted BFF prefixes and `/api/me` [C]

- `/bff/events` — the same handlers as `/api/events` (`list`, `list-raw`,
  `filter-options`, `{id}`) **plus** `POST /bff/events/batch` (the SPA's
  own fan-out ingest, same body/behaviour as `sdk-ingest.md` §3.1).
- `/bff/dispatch-jobs` — the same handlers as `/api/dispatch-jobs`
  (`list`, `list-raw`, `filter-options`, `event/{eventId}`, `{id}`,
  `{id}/raw`, `{id}/attempts`, `requeue`, `{id}/cancel`).
- `/bff/processes` — the same handlers as `/api/processes` (`list`,
  create, `by-code/{code}`, `{id}`, update, archive, delete).
- `GET /api/me` → `{principalId, principalType, scope, name, email?, active, roles[], permissions[], accessibleClientIds[], accessibleApplicationIds[], allApplications}`
  — straight from the auth context (roles, permissions, clients,
  applications as the token/claims resolver computed them); `name`,
  `email`, `active` from the principal row (404 if the principal no longer
  exists).
- `GET /api/me/applications` → `{applications:[{id, code, name, description?, iconUrl?, baseUrl?, website?, logoMimeType?}], total, clientId?}`
  — all applications (active and inactive) filtered to the caller's
  accessible set unless `allApplications`.
- `GET /api/me/clients` → `{clients:[{id, name, identifier, status, createdAt, updatedAt}], total}`
  (anchor: all); `GET /api/me/clients/{clientId}` (404 when inaccessible);
  `GET /api/me/clients/{clientId}/applications` — the client's enabled
  application configs joined to applications.

## 9. Defects and questions for the owner

- **D1** Dashboard stats are readable by any authenticated principal,
  including a client-scoped user, and count the whole platform. Intended?
- **D2** `eventsApprox` etc. come from `pg_class.reltuples`, which is
  zero until the first `ANALYZE` — a fresh install shows 0 until autovacuum
  runs. Cosmetic; noted.
- **D3** Roles and event types are listed with `findAll` + in-memory
  filtering (Go comment: "cheap given typical sizes"). Java may filter in
  SQL; the result must match.
- **D4** `POST /bff/roles/permissions` writes to `iam_permissions` but no
  role references are validated against the catalogue elsewhere
  (`role.md` open question). Kept.
- **D5** `GET /bff/scheduled-jobs/{id}` answers 404 for an inaccessible
  client's job while `/api/scheduled-jobs/{id}` (aggregate spec) answers
  per its own rule — align both to the PR-3 404 ruling.

## 10. Tests the port must have

`DashboardBffTest` (exact counts move when a row is added; approximate
fields are integers ≥ 0), `FilterOptionsBffTest` (scope + sort),
`DeveloperBffTest` (anchor gate on every route; current/versions/spec by
id including the belongs-to-app 404), `EventTypesBffTest` (every route's
status code, the `{items,total}` shape, `clientScoped`, sync anchor-only),
`RolesBffTest` (catalogue = seed ∪ table deduplicated; anchor gates; the
`{id}` create response), `ScheduledJobsBffTest` (pagination arithmetic
incl. `totalPages`, the `platform` client filter, `hasActiveInstance`
true/false, inaccessible → 404, instance filters, logs order), `MeApiTest`
(the whoami shape from a test-header context; applications filtered to the
accessible set; clients 404 when inaccessible). Each response asserted
against the field list above **byte-for-byte in key set** (the SPA's types
are the contract), not just status codes.
