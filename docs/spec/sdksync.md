# SDK sync surface — behavioural spec

The contract for `io.flowcatalyst.platform.sdksync`: the declarative
self-registration endpoints an application's SDK calls at boot to register
its resources in one idempotent batch, scoped under
`/api/applications/{appCode}` (plus one body-scoped alias). Derived from the
lockfile (tag `sdk-sync`) and the behaviour the Go `internal/platform/sdksync`
handlers embody. This unit owns **no aggregate and no operation**: every
route resolves the application, applies its coarse gate, assembles the
owning aggregate's `Sync*` command and maps that aggregate's rollup onto the
shared response. The sync operations themselves are specified in their
aggregates (`eventtype.md` §7, `role.md`, `subscription.md` §7,
`dispatchpool.md` §7, `process.md` §7, `scheduledjob.md` §8, `docs.md` §5,
`principal.md` §7, `openapispecs.md` §3). The Java is written *from* this;
tests assert it. Questions marked **load-bearing or accident?** need an
owner ruling — until ruled on, the behaviour is kept.

## 1. Purpose and boundaries

- Ten `POST` routes, all `200` on success, all authenticated (inside the
  bearer middleware), all in the lockfile.
- The handler is four steps: **coarse permission → resolve `{appCode}` →
  command → run → response**. No business rules: defaults (`concurrency ⇒
  10`, `timezone ⇒ UTC`, `deliveryMaxAttempts ⇒ 3`, `active ⇒ true`) are the
  aggregates' (CONVENTIONS "defaults are domain, not transport"); the DTOs
  carry the wire shape verbatim, `null` = absent.
- Application resolution is **by code** (`ApplicationRepository.findByCode`);
  unknown → 404 `Application_NOT_FOUND` "Application not found: <code>".
  The resolved `id` is injected into the command as `applicationId`
  (where the command carries one) and the stored `code` as
  `applicationCode` — commands never see the raw path segment.
- The per-application access rule ("may this caller sync *this*
  application?" = `AuthContext.canAccessApplication(app.id)`: all-applications
  access, or the id in the caller's application binding — client tier is
  irrelevant, an anchor-tier service account bound to one application may
  sync only that one) is applied **exactly once per route**: in the owning
  operation's `authorize` phase when the command carries `applicationId`
  (`Checks.checkApplicationAccess`), else in the handler right after
  resolution (event-types, principals — their commands are keyed by code
  only). Either way: 403 `FORBIDDEN` "Not authorised for application
  '<code>'"; unauthenticated → 403 `UNAUTHENTICATED` (raised by the gate
  first). **Load-bearing**: with `removeUnlisted` a sync can prune, so it
  must never reach an application the caller is not bound to.

## 2. Shared response — `SyncResultResponse`

`{applicationCode, created, updated, deleted, syncedCodes[]}` — `created` /
`updated` / `deleted` are non-negative counts, `syncedCodes` is always an
array (`[]` when empty). Each route maps its aggregate's rollup:

| Route | `created` | `updated` | `deleted` | `syncedCodes` | `applicationCode` |
|---|---|---|---|---|---|
| event-types | `EventTypesSynced.created` | `.updated` | `.deleted` | `.syncedCodes` | `.applicationCode` |
| roles | `RolesSynced.created` | `.updated` | **`.removed`** | `.syncedCodes` | `.applicationCode` |
| subscriptions | `SubscriptionsSynced.created` | `.updated` | `.deleted` | `.syncedCodes` | `.applicationCode` |
| dispatch-pools | `DispatchPoolsSynced.created` | `.updated` | `.deleted` (= archived) | `.syncedCodes` | `.applicationCode` |
| processes (both routes) | `ProcessesSynced.created` | `.updated` | `.deleted` | `.syncedCodes` | `.applicationCode` |
| principals | `PrincipalsSynced.created` | `.updated` | **`.deactivated`** | **`.syncedEmails`** | `.applicationCode` |
| docs | `ReplaceResult.created` | `.updated` | `.deleted` | `.slugs` | the resolved application's code |

Scheduled jobs and OpenAPI have their own response shapes (§3).

## 3. Routes (lockfile, tag `sdk-sync`)

All `POST`, all `200`, request bodies `application/json` (unparseable → 400
`INVALID_JSON`). `removeUnlisted` is a **query** boolean (`true` only when
the literal `true`; absent/anything else → `false`); it is **not** in the
body. `?removeUnlisted` semantics are each aggregate's (column "Unlisted").

| Path | Gate (`Checks.requireAny`, anchors pass) | Body → command | Unlisted | Response |
|---|---|---|---|---|
| `/api/applications/{appCode}/event-types/sync` | `EVENT_TYPE_SYNC` \| `EVENT_TYPE_MANAGE` \| `APP_SVC_EVENT_TYPE_{CREATE,UPDATE,DELETE}`; then **handler** `checkApplicationAccess` (the operation is `publicAccess` — the BFF catalogue sync shares it) | `SyncEventTypesRequest{eventTypes[]: {code*, name*, description}}` → `SyncEventTypesCommand(app.code, inputs(schema=null), removeUnlisted)` | `API`-sourced event types of the application not listed are deleted | `SyncResultResponse` |
| `/api/applications/{appCode}/roles/sync` | `ROLE_MANAGE` \| `ROLE_{CREATE,UPDATE,DELETE}` \| `APP_SVC_ROLE_{CREATE,UPDATE,DELETE}` | `SyncRolesRequest{roles[]: {name*, displayName, description, permissions[], clientManaged}}` → `SyncRolesCommand(app.code, app.id, inputs, removeUnlisted)`; `clientManaged` absent ⇒ `false`, `permissions` absent ⇒ `[]` | the application's `SDK`-sourced roles not listed are removed | `SyncResultResponse` (`deleted` = `removed`) |
| `/api/applications/{appCode}/subscriptions/sync` | `SUBSCRIPTION_SYNC` \| `SUBSCRIPTION_MANAGE` \| `APP_SVC_SUBSCRIPTION_{CREATE,UPDATE,DELETE}` | `SyncSubscriptionsRequest{subscriptions[]: {code*, name*, description, target*, connectionId, eventTypes*[]: {eventTypeCode*, filter}, dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly}}` → `SyncSubscriptionsCommand(app.id, app.code, inputs, removeUnlisted)`; `dataOnly` absent ⇒ `false` | `API`/`CODE` subscriptions of the application not listed are hard-deleted | `SyncResultResponse` |
| `/api/applications/{appCode}/dispatch-pools/sync` | `DISPATCH_POOL_SYNC` \| `DISPATCH_POOL_MANAGE` | `SyncDispatchPoolsRequest{pools[]: {code*, name*, description, rateLimit, concurrency}}` → `SyncDispatchPoolsCommand(app.id, app.code, inputs, removeUnlisted)`; `concurrency` passed through as `null` when absent — the operation applies `10` | pools (global) not listed are archived | `SyncResultResponse` |
| `/api/applications/{appCode}/principals/sync` | `USER_MANAGE` \| `USER_{CREATE,UPDATE,DELETE}` \| `USER_ASSIGN_ROLES`; then **handler** `checkApplicationAccess` (the command has no `applicationId`) | `SyncPrincipalsRequest{principals[]: {email*, name*, roles[], active, passwordHash}}` → `SyncPrincipalsCommand(app.code, inputs, removeUnlisted)`; `active` absent ⇒ `true` (**the one wire default applied here** — the command field is a primitive `boolean`; the aggregate has no "absent" to see, so the DTO's `Boolean`→`true` mapping is the only place it can live; flag if the owner prefers a `Boolean` command field) | `SDK_SYNC` roles stripped from unlisted principals | `SyncResultResponse` (`deleted` = `deactivated`, `syncedCodes` = `syncedEmails`) |
| `/api/applications/{appCode}/docs/sync` | `APP_SVC_DOCS_SYNC` | `SyncDocsRequest{docs[]: {slug*, title, content*}}` → `SyncAppDocsCommand(app.id, app.code, inputs)` — **no** `removeUnlisted`: the payload IS the set | n/a (declarative replace) | `SyncResultResponse` (`syncedCodes` = slugs) |
| `/api/applications/{appCode}/processes/sync` | `PROCESS_SYNC` \| `APP_SVC_PROCESS_SYNC` | `SyncProcessesRequest{processes[]: {code*, name*, description, body, diagramType, tags[]}}` → `SyncProcessesCommand(app.code, app.id, inputs, removeUnlisted)` | `API`/`CODE` processes of the application not listed are removed | `SyncResultResponse` |
| `/api/processes/sync` | same as above | `SyncProcessesByBodyRequest{applicationCode*, processes[]}` — the application code travels **in the body** (Laravel SDK alias); resolved and handled identically; `applicationCode` absent/blank → 404 `Application_NOT_FOUND` (`findByCode("")` finds nothing; **accident?** Go/huma would 422 on the missing required field — kept as 404, the lookup is the first thing that sees it) | same | `SyncResultResponse` |
| `/api/applications/{appCode}/scheduled-jobs/sync` | `APP_SVC_SCHEDULED_JOB_SYNC` \| `SCHEDULED_JOB_SYNC` \| `SCHEDULED_JOB_MANAGE` | `SyncScheduledJobsRequest{clientId, jobs*[]: {code*, name*, description, crons*[], timezone, payload, concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl}, archiveUnlisted}` → `SyncScheduledJobsCommand(app.code, app.id, clientId, entries, archiveUnlisted)`; `archiveUnlisted` is in the **body**; `clientId` `null` = platform-scoped (anchor / super-admin — the operation's `checkScopeAccess`); `timezone`/`deliveryMaxAttempts` passed through `null` — the aggregate defaults them | `archiveUnlisted`: `ACTIVE` jobs in scope not listed are archived | `SyncScheduledJobsResultResponse{applicationCode = app.code, created[], updated[], archived[]}` — the affected **job ids**, always arrays |
| `/api/applications/{appCode}/openapi/sync` | `APPLICATION_OPENAPI_SYNC` \| `APPLICATION_OPENAPI_MANAGE` | `SyncOpenapiRequest{spec*}` (any JSON; the operation validates the shape) → `SyncOpenApiSpecCommand(app.id, app.code, spec)` | n/a | `SyncOpenApiSpecResponse{applicationCode, specId, version, status, archivedPriorVersion?, hasBreaking, unchanged}`; `status` = `"UNCHANGED"` when `unchanged`, else `"CURRENT"`; `archivedPriorVersion` omitted when null |

Order of failures on every route: 403 (`UNAUTHENTICATED` / `PERMISSION_REQUIRED`)
→ 404 `Application_NOT_FOUND` → 400 validation / 403 `FORBIDDEN`
(application access, raised by the operation's `authorize` after its
`validate`, or by the handler before `run` for event-types/principals) →
the operation's own 404/409.

**Deviation** (1): a body whose list field is absent (`{}`) is treated as
an **empty list** by every route (the command records coerce `null` →
`List.of()`), whereas huma rejected the missing required array with 422.
Kept: an empty sync with `removeUnlisted` is a legitimate "prune all"; the
lockfile's `required` is informational here.

## 4. Wiring

`SdkSyncApi.State(apps, eventTypes, roles, subscriptions, connections,
processes, dispatchPools, scheduledJobs, specs, appDocs, uow)` is built in
`Platform.register` after the aggregate registrations (the repositories are
the same instances the aggregates' own APIs use). The principals route is
wired when `principal.operations.SyncPrincipals` lands (ported concurrently);
until then it is absent from the router and `LockfileCoverageTest` lists it
as missing.

## 5. Tests — `SdkSyncApiTest` (`TestHttp`)

- Gate: anonymous → 403 `UNAUTHENTICATED`; a caller with an unrelated
  permission → 403 `PERMISSION_REQUIRED`; a caller holding the route's
  application-service permission but **bound to another application** →
  403 `FORBIDDEN` "Not authorised for application '<code>'" (pinned on a
  handler-checked route and on an operation-checked route).
- `Application_NOT_FOUND` on an unknown `{appCode}` (and on a blank
  `applicationCode` for the body-scoped alias).
- One happy path per route: the response shape from §2/§3 and the owning
  aggregate's rollup event row in `msg_events` (subject/type from that
  aggregate's events file) + its `aud_logs` row, or — for docs, which emits
  nothing — the replaced page set.
- `removeUnlisted` is read from the query string, not the body.
