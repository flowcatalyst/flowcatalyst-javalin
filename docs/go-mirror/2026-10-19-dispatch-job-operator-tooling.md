> Mirroring is paused (2026-10-16); what Java is still missing from Go is in `PENDING.md` in this directory.
> This file is a **hand-off**: nothing here is implemented in Java yet. Its work items are PENDING rows K1..K7.

# Go dispatch-job operator tooling: edit payload, time window, requeue guard and redrive, split write permissions, requeue by filter, event type / subscription / connection filters (2026-10-19)

Source: `../flowcatalyst-go` commits (all on `main`, up to HEAD `3f40c8d4`):

| Go commit | What |
|---|---|
| `29e8cb11` | `PUT /api/dispatch-jobs/{id}/payload` (edit the payload of a PENDING or FAILED job) |
| `ad37de21` | mandatory `created_at` window on list-shaped reads; status guard on requeue; redrive of COMPLETED; the five split write permissions; migration `085` |
| `bbbb4bb3` | filters by event type, subscription (id/code/name) and connection; read-table index swap; `connection_id` on the read projection; cached `filter-options`; migration `086` |
| `3f40c8d4` | console (Vue SPA) and server-rendered (`/ui`) screens for all of the above |
| `a7497448` | collapsible sidebar sections (SPA and `/ui`) |

Contract authority (read these if this file and the code ever disagree): Go `docs/wire-contract.md` section
"Dispatch jobs: time window, requeue guard, write permissions" (including the sub-block "Event type,
subscription and connection filters (migration 086)"), `docs/published/20-messaging-and-delivery.md`
(the "Time window", "Filtering", "What a requeue will and will not reset", "Permissions" and "Edit payload"
bullets), `docs/environment-variables.md` (`FC_DISPATCH_JOB_MAX_WINDOW_DAYS`), and the code under
`internal/platform/dispatchjob/` (`api/api.go`, `window.go`, `requeue_rules.go`, `refs.go`, `lifecycle.go`,
`operations/{edit_payload,resend,resend_by_filter,events}.go`, `repository.go`), `internal/migrate/sql/085_*.sql`,
`086_*.sql`, `scripts/ops/086-dispatch-read-indexes-online.sql`, `internal/platform/seed/{permissions,roles,event_schemas,event_types}.go`.
Rationale for the operator-facing design: Go `docs/integral-subscription-records-vs-dispatch-jobs-2026-10-19.md`
(Integral's subscription-record operator page is the model; the dispatch-job page is being brought up to it).

Written from the Go code and tests (read, not run) and from a read of the Java tree at `21b60f6e`. Where this file says
"Java today", it was checked in `server/src/main/java/io/flowcatalyst/platform/dispatchjob/`.

## 0. Read this first

1. **Batch order.** Do K1 (permissions) first, then K2 (window), K3 (requeue guard), K5 (edit payload), K6 (filters),
   K4 (requeue by filter, which uses K1, K2, K3 and K6's filter struct), K7 (SPA) last. One commit per item on `main`,
   not pushed; ledger per the conventions in `PENDING.md` "How to resume".
2. **PENDING item G (requeue by filter) is absorbed by K4.** Java has no `requeue-by-filter` at all today; the Go
   behaviour described in section 6 is the *final* shape (window, guard, redrive and the new filters included), so do
   not port Go `165feb6d` first and then patch it.
3. **Behaviour changes for existing API clients.** After K2, `GET /api/dispatch-jobs` with no `since`/`until` returns only
   the last 24 hours, and a window over 31 days is a 400. The SDKs (TypeScript, Laravel, Java) and any script that lists
   dispatch jobs without bounds are affected. Go shipped the same change; tell the owner.
4. **K3 reverses a documented Java ruling**: `docs/spec/dispatchjob.md` (line ~177 "intent-named and total", line ~511
   "`requeue()` is total: also resets `PROCESSING` / `QUEUED` / `COMPLETED`") and the test
   `DispatchJobOperationsTest.requeueIsTotalSoACompletedJobIsResetToo`. Go made the reversal deliberately (a requeue of an
   in-flight job risks a double delivery; a requeue of a COMPLETED job re-sends something the subscriber already
   processed). Amend the spec and the test; do not keep both behaviours.
5. **Tokens carry permissions from mint time.** After K1, every user must sign in again and every service account mint
   a new token to hold the new permissions. Say so in the ledger's deploy note.
6. **Which databases need a migration run:** every deployed Java database (two data/DDL migrations, K1 and K6).
   See section 8 for numbering.

## 1. Cross-cutting conventions (apply to every endpoint below)

- **Mounts.** Every route below exists under both `/api/dispatch-jobs` (SDK/automation) and `/bff/dispatch-jobs` (SPA,
  cookie auth). In Java that is `DispatchJobApi.registerAt` (called for `/api/dispatch-jobs` from `register` and for
  `/bff/dispatch-jobs` from `Platform.java` ~line 792). The SDK-only aliases `/api/dispatch-jobs/raw` and
  `/api/dispatch-jobs/by-event/{eventId}` stay `/api`-only; `/raw` takes the same filters as `list-raw`.
- **Error envelope.** Same as Java: `{"error": CODE, "message": ..., "details": {...}}`. Use `UseCaseException.validation`
  (400), `.conflict` (409), `.authorization` (403), `.resourceNotFound` (404). `details` appears only where stated.
- **Route groups.** Routes that run an `Operation` (requeue, requeue-by-filter run, edit payload, cancel, complete) are
  `Group.API_WRITE` under `/api` as `requeue` and `cancel` are today. The preview and the reads are `API_READ`.
- **Scope vs Go.** Go answers an out-of-scope single job on a *write* with 403 `SCOPE_FORBIDDEN`. Java's ruling PR-3
  (2026-09-01, `operations/Access.loadOwn`) answers 404 byte-identical to not-found. **Keep Java's.** Every Go test below
  that asserts `SCOPE_FORBIDDEN` becomes "404 `DispatchJob_NOT_FOUND`" in the port.
- **Jobs with no client** (`client_id IS NULL`, platform-scoped) are visible to every caller in list reads but may be
  written only by a caller for whom `Checks.canAccessScope(ac, null)` is true (anchors). This rule already exists in
  `RequeueDispatchJobs` and is reused by K4.
- **Application confinement (Go F20)** is *not* in this hand-off. Go's list/get also apply an `AccessibleApplications`
  confinement for callers admitted only by the application-service view permission; that is PENDING item A1 and not
  part of this batch. The code in section 3 is written without it.

## 2. K1. The five write permissions (Go `ad37de21`, migration 085)

### Codes

| Permission | Gates |
|---|---|
| `platform:messaging:dispatch-job:requeue` | `POST /requeue`, `POST /requeue-by-filter`, and (together with `view`, either one) `POST /requeue-by-filter/preview` |
| `platform:messaging:dispatch-job:redrive-completed` | **additionally** required by a requeue request that sets `redriveCompleted: true` (either requeue endpoint) |
| `platform:messaging:dispatch-job:cancel` | `POST /{id}/cancel` |
| `platform:messaging:dispatch-job:complete` | `POST /{id}/complete` |
| `platform:messaging:dispatch-job:edit-payload` | `PUT /{id}/payload` |

`view` becomes read-only. `view-raw` keeps gating the raw reads and `/{id}/sign`. Holding *only* `redrive-completed` does
not allow a redrive (the request also resets non-COMPLETED jobs, so `requeue` is required too). Holding `requeue`
without `redrive-completed` and asking for `redriveCompleted: true` is **403 `PERMISSION_REQUIRED`**, not a quiet skip.
Java's error is `Checks.require` -> `PERMISSION_REQUIRED` "permission required: <code>"; same code as Go.
Order of checks for a requeue request: `requeue` first, then (only if `redriveCompleted`) `redrive-completed`.

### Who holds what (seed and migration)

- Fresh databases (seed): `platform:messaging-admin` gains `requeue`, `cancel`, `complete`, `edit-payload` (not
  `redrive-completed`). `platform:viewer`, `platform:admin-readonly` and `platform:application-service` gain none.
  `platform:super-admin` holds `platform:*:*:*` and therefore everything including `redrive-completed`. **No built-in
  role holds `redrive-completed` by name** (Go test fails if one does).
- Existing databases: migration 085 (Go `internal/migrate/sql/085_dispatch_job_write_permissions.sql`), copy verbatim:

```sql
INSERT INTO iam_role_permissions (role_id, permission)
SELECT rp.role_id, p.permission
  FROM iam_role_permissions rp
  JOIN iam_roles r ON r.id = rp.role_id
 CROSS JOIN (VALUES
        ('platform:messaging:dispatch-job:requeue'),
        ('platform:messaging:dispatch-job:cancel'),
        ('platform:messaging:dispatch-job:complete'),
        ('platform:messaging:dispatch-job:edit-payload')
      ) AS p(permission)
 WHERE rp.permission = 'platform:messaging:dispatch-job:view'
   AND r.name <> 'platform:viewer'
ON CONFLICT DO NOTHING;
```

  Meaning: every role (built-in messaging-admin, custom roles, application roles such as `shop:support`) that holds
  `view` is granted the four ordinary writes, once, so nobody loses an ability on upgrade. `platform:viewer` (read-only
  by intent) is excluded; roles without `view` get nothing; `redrive-completed` is granted to nobody. Idempotent.
  Go's Down deletes all five codes (including `redrive-completed`); Flyway has no Down, so just note it.
  **Gotcha:** the seeder never rewrites an existing role, so the migration is the only thing that gives existing
  `messaging-admin` rows the new grants; the seed edit alone is enough only for fresh databases.
  **Gotcha 2:** `platform:admin-readonly` in Go's seed does not hold `view`, so the migration does not touch it. In
  Java check which roles hold `view` before assuming the same (Java `PlatformRoles`: `messaging-admin`, `viewer`).

### Java mapping

- `shared/auth/Permission.java`: add `DISPATCH_JOB_REQUEUE`, `DISPATCH_JOB_REDRIVE_COMPLETED`, `DISPATCH_JOB_CANCEL`,
  `DISPATCH_JOB_COMPLETE`, `DISPATCH_JOB_EDIT_PAYLOAD` after `DISPATCH_JOB_VIEW_RAW`. This enum is also what
  `bff/api/RolesBff.java` (~line 240) lists as the permission catalogue, so the five show up in the role editor with no
  further work. Go's descriptions, if wanted: "Requeue dispatch jobs (by ids or by filter); never in-flight or completed
  ones", "Re-deliver COMPLETED dispatch jobs (with a requeue request that confirms it)", "Cancel a FAILED dispatch
  job", "Mark a FAILED dispatch job COMPLETED (delivered out of band)", "Edit the payload of a PENDING or FAILED
  dispatch job".
- `seed/Permissions.java`: five string constants beside `ADMIN_DISPATCH_JOB_VIEW_RAW`; `seed/PlatformRoles.java`
  `messaging-admin` (line ~152): add the four (not redrive). `PlatformCatalogueTest.roleCatalogueShape` total
  214 -> 218 (before A1; A1 adds 6 more on `application-service`).
- Handlers in `DispatchJobApi`: `requeue` (line ~220), `cancel` (~232), `complete` (~241) currently call
  `Checks.require(Auth.current(), DISPATCH_JOB_VIEW)`; switch to the new codes. Update the Javadoc table on the class.
- Existing Java tests to change: `DispatchJobApiTest.requeueRequiresTheViewPermissionAndTheIdsField` (fixtures
  `VIEWER_A`, `NO_PERMISSION` use `view`; the viewer fixture now needs `requeue`/`cancel`/`complete`), and the cancel/
  complete API tests that use `VIEWER_A`.
- Go SPA mirror: `frontend/src/utils/dispatch-job-permissions.ts` (section 9).

### Tests to port (Go)

`internal/platform/dispatchjob/api/window_guard_pg_test.go`: `TestPermissionMatrix` (each permission held alone allows
exactly its own action; `view`+`view-raw` allow none; requeue+redrive allows a redrive; `redrive-completed` alone
allows nothing; the wildcard allows all), `TestPermissionMatrix_Preview` (requeue or view opens the preview; cancel,
complete, edit-payload, redrive-completed alone do not: 403 `PERMISSION_REQUIRED`);
`internal/migrate/dispatch_job_write_permissions_pg_test.go` `TestMigration085_GrantsDispatchJobWritePermissionsToViewHolders`
(six roles seeded, before/after per role, `platform:viewer` and a role without `view` unchanged, super-admin unchanged,
zero `redrive-completed` rows, run twice, Down restores); `internal/platform/seed/roles_test.go`
`TestDispatchJobWritePermissionsInBuiltInRoles`; `internal/platform/shared/bff/roles_test.go`
`TestBuiltinPermissionsListTheDispatchJobWriteActions` (Java: assert the five are in the `Permission` enum / role
catalogue). Java homes: `PlatformCatalogueTest`, `DispatchJobApiTest`, a new `DispatchJobWritePermissionsMigrationTest`
beside `RoleDataFixMigrationsTest`.

## 3. K2. Mandatory `created_at` window on list-shaped reads (Go `ad37de21`)

### Why

`msg_dispatch_jobs_read` is RANGE-partitioned by `created_at`; a read without a `created_at` bound touches every
partition (90 days of retention by default). Every list-shaped read is therefore held to a window.

### Which reads

Windowed: `GET /api/dispatch-jobs`, `GET /bff/dispatch-jobs`, `GET .../list-raw` (alias `/raw`),
`GET .../filter-options`, `POST .../requeue-by-filter/preview`, `POST .../requeue-by-filter`.
**Not** windowed (the key already bounds them): `GET /{id}`, `/{id}/raw`, `/{id}/attempts`,
`/event/{eventId}` (alias `/by-event/{eventId}`), `POST /{id}/sign`, and every write on one job. Go test pins that
`X-Window-From` is absent on these.

### Rules (`EnforceWindow`, Go `dispatchjob/window.go`)

Inputs are the existing RFC 3339 `since` / `until` (query on GETs, body fields on POSTs). `now` is read once per request.

| `since` | `until` | Window `[from, to]` | `defaulted` | SQL |
|---|---|---|---|---|
| none | none | `[now-24h, now]` | **true** | `created_at >= now-24h` (no upper bound in SQL) |
| given | none | `[since, now]` | false | `created_at >= since` |
| none | given | `[until-24h, until]` | **true** | `created_at >= until-24h AND created_at <= until` |
| given | given | as given | false | both; `until < since` is **400 `INVALID_WINDOW`** ("until must not be before since") |

- Width check: if `to - from` is strictly greater than `maxDays * 24h` -> **400 `WINDOW_TOO_LARGE`** (a validation
  error; equal is allowed). Message is free text ("the time window is ...; at most N days may be read at once (narrow
  since/until)"); tests pin the code only. `since` in the future with no `until` gives a negative width: allowed, empty
  result.
- Max: env var **`FC_DISPATCH_JOB_MAX_WINDOW_DAYS`**, default **31**; non-positive or unparseable = 31. Java:
  `server/Env.java` is a record read with `e.integer(NAME, 0)` ("0 = default"); add `dispatchJobMaxWindowDays` beside
  `streamPartitionRetentionDays` (line ~195/540), resolve `0/negative -> 31` where it is used, pass it into
  `DispatchJobApi.State` (extend the record; `Platform.java` line ~463 builds it). Add a row to Java's env docs
  (`docs/audit/2026-09-14-env-and-cli-parity.md` lists the variables) and a test beside the other `Env` tests. Go's note:
  keep it at or below the partition retention (`FC_STREAM_PARTITION_RETENTION_DAYS`, 90).
- Unparseable `since`/`until`:
  - on the **list** and **list-raw**: *ignored as today* (the default then applies). Java's `timestamp()` already returns
    null on a parse failure; keep it. Java parses with `OffsetDateTime.parse` (offset required); Go's RFC 3339 also
    requires an offset. Keep Java's parser.
  - on **filter-options** and the two **POSTs**: **400 `INVALID_TIMESTAMP`** ("since must be an RFC3339 timestamp"),
    because a bulk requeue that silently dropped a bound would reset more jobs than asked.
- Precedence of errors on a list: query parse (`TOO_MANY_EVENT_TYPES`, `TOO_MANY_SUBSCRIPTION_IDS`) -> window
  (`INVALID_WINDOW`, `WINDOW_TOO_LARGE`) -> reference resolution (`TOO_MANY_MATCHES`).

### Echo of the applied window

- The two list endpoints keep the **bare-array body** (the SPA and SDKs bind it) and add response headers:
  `X-Window-From` and `X-Window-To` (RFC 3339 UTC, whole seconds, `Z` suffix; `To` is *now* when no `until` was given)
  and `X-Window-Defaulted` (`true`/`false`). Both mounts.
- `filter-options`, the preview and the run carry `window: {from, to, defaulted}` in the body (`from`/`to` as ISO
  instants). OpenAPI component name `WindowResponse` (Go lock); reuse that name in Java's lock.
- Java: add a `Window` record and `EnforceWindow` (static, pure, unit-testable), `ListFilter` always carries `since`
  after enforcement. `findWithFilters` already applies `since`/`until` with `ge`/`le`.

### `filter-options` (also section 7 for the new facet and cache)

`GET .../filter-options` takes only `since`, `until`. Gate: `view`. Facets are `SELECT DISTINCT <col> FROM
msg_dispatch_jobs_read WHERE <col> IS NOT NULL [AND tenant scope] AND created_at >= since [AND created_at <= until]
ORDER BY 1 LIMIT 200` for `status, code, client_id, dispatch_pool_id, subscription_id, kind`. Java's
`DispatchJobRepository.distinctValues(Facet, limit, Visibility)` gets `since`/`until` parameters (always pass `since`).
Response: existing six lists plus `eventTypes` (same list as `codes`) and `window`.

### Tests to port (Go `window_guard_pg_test.go`, `window_test.go`)

`TestWindow_ListDefaultsHonoursAndLimits` (no bounds -> default window, header `X-Window-Defaulted: true`, span 24h
+-2s, `To` within a minute of now, on both mounts; explicit window honoured, `false`; `since` only; 60 days -> 400
`WINDOW_TOO_LARGE`; a configured max of 2 days refuses 3 days and accepts 47h; `until` before `since` -> 400
`INVALID_WINDOW`), `TestWindow_ListRawIsWindowedToo`, `TestWindow_ExemptLookupsAreNotWindowed` (a 72h-old job is still
returned by id, raw, attempts, event, by-event, and those responses carry no `X-Window-From`),
`TestWindow_FilterOptionsAreWindowed` (old-code facet absent by default, present with a 5-day `since`; `defaulted`
flags; `WINDOW_TOO_LARGE`; `since=last tuesday` -> `INVALID_TIMESTAMP`),
`TestWindow_PreviewAndRequeueByFilterAreWindowed` (default window counts and resets only the recent two of five; an
explicit window reaches the old three; too-large refused on both); `window_test.go`
`TestEnforceWindow_DefaultsToTheLast24Hours`, `TestEnforceWindow_HonoursWhatTheCallerGave`, `TestEnforceWindow_Limits`;
`internal/server/envcfg_test.go` `TestLoadEnv_DispatchJobMaxWindowDays`. Existing Java tests
(`DispatchJobApiTest` with `BASE` timestamps in the past, `DispatchJobRepositoryTest`, anything calling the list
without a window against old fixtures) will need explicit `since`.

## 4. K3. Requeue status guard and redrive (Go `ad37de21`)

### Statuses

| Class | Statuses | Requeue behaviour |
|---|---|---|
| requeueable | `PENDING`, `FAILED` (and legacy `ERROR`), `CANCELLED`, `EXPIRED` | reset to `PENDING` (a `PENDING` job is reset too: it makes a job sitting out a retry backoff due now) |
| in flight | `QUEUED`, `PROCESSING` (and legacy `IN_PROGRESS`) | **never reset**; skipped and reported |
| completed | `COMPLETED` | reset only by a confirmed redrive; otherwise skipped and reported |

Any status not named is treated as requeueable by the classifier (Go `ClassifyForRequeue` default).

### Request and response: `POST /requeue` (both mounts)

Request `{ids: [string], redriveCompleted?: bool, confirmRedriveCompleted?: string}`. A redrive happens only when
`redriveCompleted == true` **and** `confirmRedriveCompleted` equals the literal `REDRIVE` (case-sensitive). One field
alone, or `"redrive"`, is not a redrive: COMPLETED jobs are skipped and reported (200, not an error).
Gates: section 2. Empty `ids`: 200 with all counts 0 (after the permission check).

Response 200:

```json
{ "requeued": 5, "redriven": 1, "skippedInFlight": 2, "skippedCompleted": 0,
  "skipped": [ {"id": "...", "status": "QUEUED", "reason": "IN_FLIGHT"},
               {"id": "...", "status": "COMPLETED", "reason": "COMPLETED_REDRIVE_NOT_CONFIRMED"} ] }
```

- `requeued` counts every job reset, the redriven COMPLETED ones included; `redriven` is the COMPLETED subset (0 unless
  the request redrove). `skipped` is always an array (`[]`, never absent). `reason` is `IN_FLIGHT` or
  `COMPLETED_REDRIVE_NOT_CONFIRMED`; `status` is the job's status. `skippedInFlight`/`skippedCompleted` count `skipped`
  by reason. Human wording of the second reason: "completed jobs are redriven only with redriveCompleted and
  confirmRedriveCompleted".
- Unknown ids and ids outside the caller's scope are dropped silently (not listed in `skipped`), as Java does today.
- A skipped job is never an error for the batch. A batch of only in-flight jobs returns 200 with `requeued: 0`.
- Redriven jobs get the same reset as any requeue: `status = PENDING`, `attempt_count = 0`, `scheduled_for`,
  `completed_at`, `duration_millis`, `last_error` all NULL, `updated_at` = now (a fresh retry budget). A test reads
  `attempt_count = 0` after a redrive.

### SQL guard (defence in depth; the in-memory classification can be stale)

The status guard also lives in the UPDATE, as every other transition does. Go: `TRequeue` from
`RequeueableStatuses` (`PENDING, FAILED, ERROR, CANCELLED, EXPIRED`) -> `PENDING`; a **new** transition `TRedrive`
from `COMPLETED` -> `PENDING` (separate so that an ordinary requeue can never reach a COMPLETED row even by a race);
both with the "opaque" guard `j.status || '' IN (...)`. A refused UPDATE (0 rows) in a requeue is a **silent no-op**,
not an error (the persister is non-strict): the counts reflect the in-memory pass and the SQL guard stops the reset. A
unit test calls `lifecycle.Requeue` on QUEUED/PROCESSING/COMPLETED rows and expects `false` (no reset), `Redrive` on a
FAILED row `false`, on a COMPLETED row `true`.
Java: `DispatchJobLifecycle.Transition.REQUEUE(null, "PENDING")` becomes `REQUEUE(List.of("PENDING","FAILED","ERROR",
"CANCELLED","EXPIRED"), "PENDING")` and a new `REDRIVE(List.of("COMPLETED"), "PENDING")`; `requeue(Connection,...)`
(line ~676) gets a `redrive` twin; `requeueWriter()` (line ~622) takes the set of ids to redrive (Go
`RequeuePersister(redriveIDs)`). The refused-transition counter and JFR event already cover a 0-row `enterPending`.
`Transition.guardSql(true)` already gives the `status || ''` form. `LIFECYCLE_TABLE` in
`DispatchJobLifecycleTest` (line ~54, currently lists REQUEUE from every status) must be updated to the narrowed set
and gain REDRIVE; `DispatchJobLifecycleRandomTest` (line ~34: "only an operator requeue may leave COMPLETED") needs its
model updated.

### Event and audit

- Go emits one rollup `platform:messaging:dispatch-jobs:resent` per request with data
  `{ids, redrive, redrivenCount, redrivenIds?, skippedInFlight, skippedCompleted}` (schema in the seed:
  `ids` string[] required, `redrive` boolean required, `redrivenCount` integer>=0 required, `redrivenIds` string[]
  optional (omitted when empty), `skippedInFlight` integer>=0 required, `skippedCompleted` integer>=0 required;
  `additionalProperties: false`). **Java keeps its own event names** (`platform:admin:dispatchjob:requeued` per job and
  `platform:admin:dispatchjobs:requeued` rollup; the divergence is the old ledger DJ-5; do not rename). Port the
  *content*: add to Java's rollup `DispatchJobsRequeued.data()` the fields `redrive` (bool), `redrivenCount` (int),
  `redrivenIds` (omit when empty), `skippedInFlight`, `skippedCompleted`; the per-job `DispatchJobRequeued` already
  carries `previousStatus`, which is `COMPLETED` for a redriven job. Update `PlatformEventSchemas`
  (`platform:admin:dispatchjobs:requeued`, line ~293) and the data record; `EmittedEventSchemaTest` validates every
  emitted event against the seeded schema, so the two must agree.
- **Deployed databases keep the old stored schema.** The seeder attaches a schema only to a type that has no 1.0
  version. Java has the V27 pattern (`V27__platform_event_schema_fixes.sql`: update the stored 1.0 while it is still
  exactly the old seeded form). Either make the new rollup fields optional in the schema (still a schema change,
  still needs the V27-style update) or add the same kind of update in the K3 migration. Go did not add a migration for
  its changed `resent` schema (a Go gap: deployed Go databases still store `{ids}`); it is up to the Java owner whether
  Java's stored schema is validated on emit anywhere in production; check before deciding it can be skipped.
- Audit: the command is the audit row's operation JSON, so `RequeueCommand` must carry `ids`, `redriveCompleted`,
  `confirmRedriveCompleted` (Go test reads `operation_json->>'redriveCompleted' = 'true'` from `aud_logs` where
  `operation` = the command name; Go's name is `ResendCommand`, Java's is `RequeueCommand`).

### Java mapping

`operations/RequeueCommand.java` (add the two fields; keep `ids` null-check `IDS_REQUIRED`),
`operations/RequeueDispatchJobs.java` (classify per job after the scope check, build skipped list, pass the redrive id
set to the writer; the rollup event gets the counts), `operations/DispatchJobEvents.java`,
`api/DispatchJobApi.RequeueRequest/RequeueResponse` (new fields; `skipped` element record `{id, status, reason}`;
`reason` enum `IN_FLIGHT | COMPLETED_REDRIVE_NOT_CONFIRMED`), the OpenAPI lock (`RequeueResponse` gains `redriven`,
`skippedInFlight`, `skippedCompleted`, `skipped`; the `SkippedJobResponse` component). Java's request-schema
validation requires `ids` (`requeueRequiresTheViewPermissionAndTheIdsField`): keep.

### Tests to port (Go `window_guard_pg_test.go`)

`TestRequeueGuard_ByIDs` (seven statuses seeded: 4 reset, QUEUED and PROCESSING `IN_FLIGHT`, COMPLETED
`COMPLETED_REDRIVE_NOT_CONFIRMED`, response counts 4/0/2/1; an in-flight-only batch returns 0 and is not an error),
`TestRequeueGuard_RedriveNeedsBothFieldsAndThePermission` (flag alone, word alone, `"redrive"` lowercase: skipped;
no redrive permission: 403 `PERMISSION_REQUIRED`; redrive permission alone: 403; both fields + both permissions with an
unknown id in the list: 5 requeued, 1 redriven, 2 skipped in flight; `attempt_count` 0; the event has `redrive: true`
and `redrivenCount: 1`, an ordinary requeue's `redrive: false`; the audit row carries `redriveCompleted: true`),
`TestRequeueGuard_SQLRefusesInFlightAndCompleted` (lifecycle level), `operations/ops_pg_test.go`
`TestResendDispatchJobs_Validation`, `_HappyPath`, `_ScopeFiltering`, `TestOperatorPersisters_RefuseNonFailedInSQL`;
`lifecycletest/matrix_pg_test.go` `TestLifecycleMatrix`, `TestLifecycleMatrix_AgreesWithTransitionTable`;
`window_test.go` `TestClassifyForRequeue`, `TestRedriveConfirmed`. Java homes: `DispatchJobOperationsTest`,
`DispatchJobApiTest`, `DispatchJobLifecycleTest`, `DispatchJobLifecycleRandomTest`.

## 5. K5. Edit the payload of a PENDING or FAILED job (Go `29e8cb11`, gate changed by `ad37de21`)

### Endpoint

`PUT /api/dispatch-jobs/{id}/payload` and `PUT /bff/dispatch-jobs/{id}/payload`. Gate:
`platform:messaging:dispatch-job:edit-payload`. Request `{payload: string (required, min length 1),
payloadContentType?: string}`. Response **200 with the full job** (`DispatchJobResponse`, the same shape as
`GET /{id}`, reloaded after the write): `status` and `attemptCount` unchanged, `payload` the new text,
`payloadContentType` the effective one. OpenAPI: operation `editDispatchJobPayload`, body `EditPayloadRequest`.

### Rules (in this order: Go `operations/edit_payload.go`)

1. Validate the command:
   - `ID_REQUIRED` (blank id) 400.
   - `PAYLOAD_REQUIRED` (empty payload) 400. (At HTTP level Go's schema `minLength 1` rejects it first; any 4xx is
     accepted by Go's test; in Java the request-schema validation gives 400 `VALIDATION`.)
   - `PAYLOAD_TOO_LARGE` when the payload is more than **262144 bytes** (`256 << 10`; **UTF-8 bytes, not
     characters**; exactly 262144 is allowed) 400.
   - `PAYLOAD_NOT_UTF8` when not valid UTF-8 text 400 (relevant only if Java can receive invalid UTF-8; JSON bodies
     usually cannot, keep the check for parity if the type allows it).
   - `CONTENT_TYPE_INVALID` when `payloadContentType` is given and, trimmed, is empty, longer than 100 characters, or
     has no `/` 400.
2. Load the job from the write table by id; missing -> 404 `DispatchJob_NOT_FOUND`. Resource scope: Java's
   `Access.loadOwn` (404 for out-of-scope; Go answers 403 `SCOPE_FORBIDDEN`, see section 1).
3. Status must be `PENDING`, `FAILED` or legacy `ERROR` (Go `EditableStatuses`). Otherwise **409 `NOT_EDITABLE`**:
   "dispatch job is <STATUS>; a payload can be edited only while the job is PENDING or FAILED". `QUEUED` and
   `PROCESSING` are refused because the delivery handler reads the job once, before it claims it, so an edit made
   while a delivery is under way could neither take effect nor be told apart from one that did; `COMPLETED`,
   `CANCELLED`, `EXPIRED` are history.
4. Effective content type = the supplied one (trimmed) else the job's stored one. If it is JSON (media type before any
   `;`, lower-cased, equals `application/json` or ends with `+json`) the payload must be valid JSON, else **400
   `PAYLOAD_NOT_JSON`** ("the payload is not valid JSON for content type <ct>"). Use a strict parse that rejects
   trailing garbage (`{} x`); `{not json` must fail. Switching a job to `application/xml` makes a non-JSON payload
   valid and persists the new type; switching back to JSON re-validates.
5. Persist, in the use-case transaction with the event and audit row:

```sql
UPDATE msg_dispatch_jobs
   SET payload = $3, payload_content_type = COALESCE($4, payload_content_type), updated_at = NOW()
 WHERE id = $1 AND created_at = $2 AND status || '' IN ('PENDING','FAILED','ERROR')
```

   (Go always passes the effective content type as `$4`.) Exactly one row must change, else the persister fails and
   the transaction rolls back with no event or audit row (a race with the scheduler). Go lets that race surface as a
   plain error (500); Java should answer **409 `NOT_EDITABLE`** instead (strictly better; record it as a deviation).
   `updated_at` moves on purpose: the scheduler's mark-QUEUED is optimistic on `updated_at`, so a claim that read the
   old payload is refused and the job is claimed again; and the stream projector re-projects the row (the read table
   has no payload column, so the read row only changes `updated_at`/`projected_at`). Status, attempts, schedule,
   `last_error` are untouched: the operator requeues afterwards (K3).
   Java: add a `Persist<DispatchJob>` writer beside `cancelWriter()` in `DispatchJobLifecycle` (the
   `DispatchJobLifecycleEnforcementTest` fails the build if any other production file writes the table), plus a
   `DispatchJob.withPayload(...)` copy method, and `operations/EditDispatchJobPayload.java` +
   `EditPayloadCommand.java` beside `CancelDispatchJob`/`CancelCommand`.

### Event, audit masking

- Event type `platform:messaging:dispatch-job:payload-edited`, source `platform:messaging`
  (`DispatchJobEvents.SOURCE_MESSAGING`), subject `platform.dispatchjob.<id>`, message group
  `platform:dispatchjob:<id>`: identical construction to `DispatchJobCancelled`. Data fields (all required, schema
  `additionalProperties: false`):

| field | type | meaning |
|---|---|---|
| `dispatchJobId` | string | |
| `status` | string | status at edit time |
| `previousSize` | integer >= 0 | UTF-8 bytes of the old payload (0 if it had none) |
| `previousSha256` | string | lower-case hex SHA-256 of the old payload bytes (of the empty string if none) |
| `size` | integer >= 0 | bytes of the new payload |
| `sha256` | string | lower-case hex SHA-256 of the new payload |
| `previousContentType` | string | |
| `contentType` | string | effective content type after the edit |

  **The event carries sizes and digests, never the payload**: payloads can hold personal data and the audit log is
  read more widely than the job. Go test: the serialized event data does not contain `example.com` from the payload;
  `digest("abc")` is `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`.
- Audit: the command is recorded, so mask the field. Go: `EditPayloadCommand.AuditMaskedFields() = ["payload"]`. Java:
  implement `io.flowcatalyst.sdk.usecase.AuditMasked#auditMaskedFields()` returning `Set.of("payload")` (the same
  mechanism as `SetPropertyCommand`; redaction replaces the value with `***`). Go test reads
  `aud_logs.operation_json::text` for `operation = 'EditPayloadCommand'`, `entity_id = <job id>` and asserts the
  payload text is absent and the job id present.
- Catalogue: Go adds `payload-edited` to the group `platform:messaging:dispatch-job` (`event_types.go`) and the schema
  above (`event_schemas.go`). Java: `PlatformEventTypes` line ~163 `b.group("platform:messaging:dispatch-job",
  "cancelled", "completed")` -> add `"payload-edited"`; `PlatformEventSchemas` after line ~301. No migration needed
  for the *type*: `Seeder.seedEventTypes` inserts a missing code and attaches the 1.0 schema on the next boot
  (see `2026-10-11-event-catalogue.md`). `EmittedCatalogueTest`/`EmittedEventSchemaTest` will demand it anyway.

### Tests to port

`operations/edit_payload_pg_test.go`: `TestEditDispatchJobPayload_EditableStatuses` (edits PENDING, FAILED and `ERROR`
keeping status, content type when omitted, attempt count 2 and `last_error`; refuses QUEUED, PROCESSING, COMPLETED,
CANCELLED, EXPIRED with 409 `NOT_EDITABLE`, payload untouched), `_NotFound`, `_Validation` (non-JSON under the job's own
JSON type, over-size, non-UTF-8, XML content type switch persisted, XML job takes XML, switch back to JSON re-validates),
`_ResourceScope` (client user: other client's job, platform job refused; own job allowed; **Java: 404 not 403**),
`_AuditHoldsNoPayload`; `edit_payload_test.go` `TestEditPayloadValidate` (table: ok, no id, empty payload, too large, at
the cap, not UTF-8, blank content type, content type without slash, xml), `TestIsJSONType` (`application/json`,
`Application/JSON; charset=utf-8`, `application/vnd.api+json` true; `application/xml`, `text/plain` false),
`TestDigest`; `api/edit_payload_pg_test.go` `TestEditPayload_HTTP` (both mounts 200 with `status: FAILED` and
`attemptCount: 3`; content-type change; COMPLETED job 409 `NOT_EDITABLE`; empty payload >= 400; `{nope` + JSON type 400
`PAYLOAD_NOT_JSON`; unknown id 404; no permission 403; client-scoped caller on a platform job refused). Java homes:
`DispatchJobOperationsTest`, `DispatchJobApiTest`, `PlatformCatalogueTest`/`EmittedEventSchemaTest`.

## 6. K4. Requeue by filter (absorbs PENDING G; Go `165feb6d`/`5496c3ff`, final shape after `ad37de21`/`bbbb4bb3`)

Reset every job a list filter matches, not just ids in hand. Two endpoints on both mounts:

### 6.1 The filter (shared with the list, K6)

One filter shape serves the list's query string and, **under the same names as top-level JSON string fields**, the two
POST bodies (so "requeue what the list shows" cannot select different jobs). All values are strings; plural fields are
comma-separated strings, **not JSON arrays**:

`status, clientId, dispatchPoolId, subscriptionId, code, since, until, messageGroup, clientIds, statuses, applications,
subdomains, aggregates, codes, source, eventType, eventTypes, subscriptionIds, subscriptionCode, subscriptionName,
connectionId, connectionCode`.

(No paging fields on the POSTs.) Semantics of each field are the list's (Java `ListFilter` + K6). One shared conversion
(`toParams(strict)`) builds it for the GET and the POSTs; `strict=true` for the POSTs and `filter-options` makes an
unparseable `since`/`until` a 400 `INVALID_TIMESTAMP`. An empty body is the filter "every job in the (default 24h)
window" and works under the same `confirmCount` rule (Go test `TestRequeueByFilter_NoFilter`, own database).

### 6.2 `POST .../requeue-by-filter/preview` (200)

Gate: `requeue` **or** `view`. Order: strict parse -> tenant scope (`VisibleTo`) -> window (K2) -> reference resolution
(K6) -> count. Count SQL (projection, grouped so the per-client rule can be applied per bucket):

```sql
SELECT status, client_id, count(*) FROM msg_dispatch_jobs_read WHERE <filter> GROUP BY status, client_id
```

Per bucket: `count += n`; `byStatus[status] += n`; then **only if** the caller can requeue that client's jobs
(`Checks.canAccessScope(ac, clientId)`; platform jobs are counted in `count`/`byStatus` for a tenant caller but never
eligible) classify by status: requeueable -> `eligible`, COMPLETED -> `completed`, in flight -> `inFlight`.
Response:

```json
{ "count": 9, "byStatus": {"FAILED": 6, "COMPLETED": 2, "PENDING": 1},
  "eligibleCount": 7, "completedCount": 2, "inFlightCount": 0, "redriveEligibleCount": 9,
  "window": {"from": "...", "to": "...", "defaulted": true} }
```

`eligibleCount` excludes COMPLETED and in-flight (send it as `confirmCount`); `redriveEligibleCount = eligibleCount +
completedCount` (the `confirmCount` of a request that also redrives). `byStatus` is `{}` when nothing matches. A tenant
caller sees its own jobs plus platform jobs in `count`; asking for another tenant's `clientIds` finds none (SQL scoping
`client_id IS NULL OR client_id = ANY(own)` makes the caller's filters only narrow).

### 6.3 `POST .../requeue-by-filter` (200)

Gate: `requeue`, plus `redrive-completed` if `redriveCompleted` (K1). Body: the filter, plus
`confirmCount` (**required**, integer >= 0; a missing field is 400), `limit` (optional integer 0..10000; 0/omitted =
10000; above 10000 is 400), `redriveCompleted`, `confirmRedriveCompleted` (as in K3). Errors in the operation:
`INVALID_CONFIRM_COUNT` (negative), `INVALID_LIMIT` (outside 0..10000), both 400.

Algorithm (Go `operations/resend_by_filter.go`):

1. `redrive = redriveCompleted && confirmRedriveCompleted == "REDRIVE"`.
2. Count now (the preview computation). `toReset = eligible`, or `redriveEligible` if `redrive`.
3. If `toReset > confirmCount` -> **409 `FILTER_MATCH_CHANGED`**, nothing reset, with
   `details: {count, eligibleCount, completedCount, inFlightCount, redriveEligibleCount, byStatus}` ("N jobs now match this filter
   and can be requeued, not the M confirmed; review the new count and confirm again"). Fewer matches than confirmed is
   fine. It never resets more than `confirmCount` even if jobs start matching while it runs.
4. Walk: `budget = min(limit, confirmCount)`. Page through matching ids **in id order, keyset** (`WHERE <filter> AND id > :after
   AND status = ANY(:resettable) [AND client_id IS NOT NULL] ORDER BY id LIMIT :page`), `page = min(500, budget - handed)`.
   `resettable` = requeueable statuses (+ `COMPLETED` when `redrive`), so every walked id counts toward the cap;
   `client_id IS NOT NULL` is added when the caller may see but not requeue platform jobs
   (`!canAccessScope(ac, null)`). Walking by id means a job that stops matching mid-walk (it was just reset) cannot shift
   the next page.
5. Each page of ids is one run of the K3 operation (`ResendDispatchJobs`/`RequeueDispatchJobs`) **in its own transaction**
   with its own event and audit row (so a 1,250-match run with limit 1,000 makes two batches of 500 and has 250 left; the
   test counts the event rows). The command carries the redrive fields only when `redrive`. Tenant filtering is applied
   again inside the operation.
6. Result counters: `requeued` (sum of ids reset, redriven included), `redriven`, `skippedInFlight` (= the in-flight
   count from step 2, plus any job a batch skipped because it changed status after the walk read it),
   `skippedCompleted` (= the completed count from step 2 if not `redrive`, plus batch skips).
7. If the walk stopped at the budget with ids left (a page came back full and `handed == budget`), count what is left
   after the last id (same count with `AfterID` set): `remaining` = that eligible (or redrive-eligible) count;
   `truncated = remaining > 0`. If the walk ran out of ids (`page` short or zero), `remaining = 0`.
8. A failure after at least one batch committed is **500 `REQUEUE_INTERRUPTED`** ("requeue stopped after N jobs were reset;
   run it again to continue") with `details: {requeued: N}`; batches already committed stay committed. A failure before
   any batch is the plain error.

Response 200:

```json
{ "requeued": 3, "redriven": 0, "skippedInFlight": 0, "skippedCompleted": 0, "truncated": false,
  "remaining": 0, "window": {"from": "...", "to": "...", "defaulted": true} }
```

409 body shape from the Go wire test: `{"error": "FILTER_MATCH_CHANGED", ..., "details": {"count": 3, "eligibleCount": 3,
"completedCount": 0, "inFlightCount": 0, "redriveEligibleCount": 3, "byStatus": {"FAILED": 3}}}` (`UseCaseException.conflict`
with details).

### 6.4 Java mapping

`DispatchJobRepository`: add `countMatches(ListFilter)` (the grouped count above) and `matchingIds(ListFilter, afterId,
limit)`; both reuse the **same** `where` builder as `findWithFilters` (extract it; Go's `FilterParams.where()` is shared by
list, count and id walk so they cannot diverge). `ListFilter` gains `statusIn`, `excludePlatformScoped`, `afterId` (not
exposed on the wire). New `operations/RequeueByFilter.java` (not a use case itself: a walker over
`RequeueDispatchJobs`), `operations/MatchSummary`. API: `DispatchJobApi.registerAt` gets `write.post(prefix +
"/requeue-by-filter", ...)` and `routes.post(prefix + "/requeue-by-filter/preview", ...)`, **registered before `{id}`
routes** (literal wins; there is no `POST /{id}` so ordering is moot for POST, keep it tidy). OpenAPI components
(Go names): `RequeueByFilterPreviewRequest`, `RequeueByFilterPreviewResponse`, `RequeueByFilterRequest`,
`RequeueByFilterResponse`, `WindowResponse`, `SkippedJobResponse`.

### 6.5 Tests to port

`api/requeue_by_filter_pg_test.go`: `TestRequeueByFilterPreview_CountsAndBreakdown` (anchor and tenant caller counts,
platform jobs not eligible for the tenant; another tenant's `clientIds` finds none),
`TestRequeueByFilter_ConfirmCountMismatchIs409` (409 with `details.eligibleCount`; nothing reset; fewer matches than
confirmed is fine), `TestRequeueByFilter_BatchesAndTruncates` (1,250 matches, limit 1,000), `TestRequeueByFilter_OnlyMatchingRowsReset`,
`TestRequeueByFilter_TenantScoping`, `TestRequeueByFilter_PermissionDenied`, `TestRequeueByFilter_RejectsBadTimestampAndLimit`
(`INVALID_TIMESTAMP`, `INVALID_LIMIT`), `TestRequeueByFilter_Wire` (both prefixes; exact JSON of preview/run/409; missing
`confirmCount` -> 400; `limit` 10001 -> 400), `TestRequeueByFilter_NoFilter`, `TestRequeueByFilter_ConcurrentSchedulerClaim`
(1,500 PENDING jobs reset while the scheduler claims and marks QUEUED: every job ends PENDING or QUEUED with attempts 0,
nothing errors; relies on mark-QUEUED being optimistic on `updated_at`, which Java has); `window_guard_pg_test.go`
`TestRequeueGuard_ByFilter` (preview 7/4/1/2/5 for seven statuses; ordinary run 4 reset, 2 in flight, 1 completed skipped;
half-confirmed redrive resets no COMPLETED; confirmed redrive needs `confirmCount = redriveEligibleCount`, smaller is 409;
in flight never reset even by a redrive; redrive without the permission 403); `dispatchjob/requeue_filter_plan_pg_test.go`
`TestRequeueByFilterPlans` (EXPLAIN plans: no seq scan of a partition; optional, port if the Java plan-test harness
`DispatchJobPlanTest`/`PlanDatabases` makes it cheap).

## 7. K6. Filters: event type, subscription, connection; cached filter-options; migration 086 (Go `bbbb4bb3`)

### 7.1 New filter fields (list, list-raw, preview/run bodies; same names)

| field | meaning |
|---|---|
| `eventType` | exact event type. Filters the **`code`** column: a job's `code` is its event type for a job the event fan-out raised (fan-out sets `code` = the event's type) and the caller's own job code for an SDK or `fn.dispatch` job. No separate column. |
| `eventTypes` | CSV, **at most 10**, else **400 `TOO_MANY_EVENT_TYPES`** ("at most 10 event types may be filtered at once"). `eventType` is merged into the list without duplicating; with `code` and `codes` all must hold (they are ANDed predicates on `code`: `code = x`, `code IN codes`, `code IN eventTypes`). The cap applies on the lenient list path too. |
| `subscriptionIds` | CSV, **at most 50**, else **400 `TOO_MANY_SUBSCRIPTION_IDS`**; ANDed with `subscriptionId` (`subscription_id = x AND subscription_id IN (...)`). |
| `subscriptionCode`, `subscriptionName` | exact match; a **single trailing `*`** makes it a prefix; `subscriptionName` is case-insensitive (`lower(name) = lower(?)` / `lower(name) LIKE lower(?)`). Trimmed; blank = absent. Both given: the same subscription must satisfy both. |
| `connectionId`, `connectionCode` | jobs delivered through the connection: the jobs of the connection's subscriptions **plus** jobs whose own `connection_id` is the connection (function dispatch). `connectionCode` follows the same `*` rule. |

CSV parsing: split on `,`, trim, drop empties (Java `csv()` already does this).

### 7.2 Reference resolution (names to ids first, so the job query stays single-table)

Done after tenant scoping and the window, before the query (`Repository.ResolveRefs`). Lookups hit the control-plane
tables, which are small and indexed on `code`:

- Subscription code/name: `SELECT id FROM msg_subscriptions WHERE <code match> AND <name match> [AND (client_id IS NULL OR
  client_id = ANY(:ownClients))] ORDER BY id LIMIT 201`. More than **200** rows -> **400 `TOO_MANY_MATCHES`** ("the
  subscription filter matches more than 200 records; narrow it"); **never truncate**: a truncated id list would list,
  count or requeue a subset of what the caller named. Then the job predicate is `subscription_id = ANY(ids)`; no match
  = an empty list (200, not an error).
- Connection code: the same against `msg_connections.code`, cap **50**, message "the connection filter matches more
  than 50 records; narrow it". If `connectionId` is also given, intersect: the id must be among the code's matches
  (disagreeing code and id match nothing). With only `connectionId`, the id list is `[connectionId]`, not looked up.
  Then the connection's subscriptions: `SELECT id FROM msg_subscriptions WHERE connection_id = ANY(:connIds) [AND tenant
  scope] ORDER BY id LIMIT 201` (cap 200, same 400). Job predicate: `(connection_id = ANY(:connIds) OR
  subscription_id = ANY(:connSubs))`; empty arrays match nothing.
- The tenant scope clause is applied only for a non-anchor (anchor = no clause). A client user cannot find another
  tenant's subscription or connection by name, nor list its jobs by id (the job query is tenant scoped too).
- **Fail closed:** a filter that carries a code/name/connection reference which was not resolved selects *nothing*
  (`FALSE`), never everything.
- LIKE escaping for prefix matches: escape `\`, `%` and `_` with a backslash, append `%`, use `ESCAPE '\'`. So
  `subscriptionCode=%*` matches only codes starting with a literal `%` (none): empty list. `subscriptionCode=rfsub-`
  (no star) is exact. A bare `*` is a prefix of the empty string (matches everything in scope, so usually hits the 200
  cap).

### 7.3 Projection column and migration 086

Go `internal/migrate/sql/086_dispatch_job_read_filter_indexes.sql` (copy statement for statement, as V20 did for Go 065):

```sql
ALTER TABLE msg_dispatch_jobs_read ADD COLUMN IF NOT EXISTS connection_id VARCHAR(17);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_code_created
    ON msg_dispatch_jobs_read (code, created_at DESC);
DROP INDEX IF EXISTS idx_msg_dispatch_jobs_read_code;

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_subscription_created
    ON msg_dispatch_jobs_read (subscription_id, created_at DESC);
DROP INDEX IF EXISTS idx_msg_dispatch_jobs_read_subscription_id;

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_connection_created
    ON msg_dispatch_jobs_read (connection_id, created_at DESC)
    WHERE connection_id IS NOT NULL;
```

Down (Go, for the record; Flyway has none): recreate the two single-column indexes
(`idx_msg_dispatch_jobs_read_code (code)`, `idx_msg_dispatch_jobs_read_subscription_id (subscription_id)`), drop the three
new objects and the column. Design rules to keep in the header comment: **swap, never add** (the read table is rewritten
on every status change and `status` is indexed so the update is never HOT; every extra index costs on every transition
of every job); the composites keep the old leading column, so every query the single served is still served; the
`connection_id` index is **partial** so it holds entries only for jobs that name their own connection (function
dispatch) and costs fan-out jobs, the bulk of the table, nothing; the other read indexes (application, message_group,
event_id, dispatch_pool_id, created_at, client+created, status+created) are untouched. The names `idx_msg_dispatch_jobs_read_code`
and `..._subscription_id` are identical in Java's `V1__baseline.sql` (lines 1560, 1572), so the DROPs hit.

- **Partitioned table.** These are partitioned indexes (built on every partition, ACCESS EXCLUSIVE on the parent while
  they build). Fine for a small database. On a populated one, build them first, online, with Go's
  `scripts/ops/086-dispatch-read-indexes-online.sql` (parent index `ON ONLY`, each partition `CREATE INDEX CONCURRENTLY`,
  then `ATTACH`; re-runnable; it also adds the column and has an optional per-month backfill and a validity check
  query); afterwards the migration's `CREATE ... IF NOT EXISTS` find the parent indexes and only the two DROPs run.
  Java has `scripts/` (only `release.sh` today); copy the file to `scripts/ops/` and mention it in the ledger's deploy note
  ("run the script before deploying if the read table is large"). Go test
  `migrate/dispatch_read_filter_indexes_pg_test.go` runs the script through `psql` on a scratch DB; Java's equivalent can
  skip if `psql` is absent, as Go's does.
- **`connection_id` on the write table is a prerequisite that Java does not have.** Go migration 082 added
  `msg_dispatch_jobs.connection_id VARCHAR(17)` (nullable, no default, no index) for `fn.dispatch`; the projector copies
  it to the read row. Java's `V1__baseline.sql` has `connection_id` only on `msg_subscriptions`. **Recommendation:** the
  K6 migration also runs `ALTER TABLE msg_dispatch_jobs ADD COLUMN IF NOT EXISTS connection_id VARCHAR(17);` (Go 082's
  statement; harmless, idempotent, and the column is already there on any database Go has migrated), and the Java
  projector (`stream/DispatchJobProjection.java`, the `INSERT ... SELECT` at ~line 40 and its `ON CONFLICT ... DO UPDATE`
  at ~line 61) copies `j.connection_id` and refreshes it on conflict (`connection_id = EXCLUDED.connection_id`), exactly
  as Go does. Nothing in Java *writes* the column until PENDING item C (`fn.dispatch`) lands, so for Java-created jobs
  it stays NULL and the "own connection" branch of the connection filter matches nothing extra; but a database shared
  with Go keeps working. The scheduler's pause-by-connection claim filter that Go 082 introduced is **not** part of
  this hand-off (item C). Do **not** expose `connection_id` on the wire (`DispatchJobRead` is unchanged in Go).
  Rows projected before the migration keep `connection_id` NULL until their next projection (the upsert refreshes it)
  or the script's optional backfill. Regenerate jOOQ (`db/generated/tables/MsgDispatchJobs*.java`, `tools/jooq-verify.sh`).
- `SchemaFingerprintTest` and `GoAdoptionTest` pin the schema over the goose-57 fixture: the new column(s) and index
  names need the "exact allowances" treatment, as ledger 10 did for V30/V31 (see
  `2026-10-15-platform-bug-fixes-10.md`), and the migration counts in `MigratorTest`/`GoAdoptionTest` go up by two.

### 7.4 filter-options: `eventTypes` and the 60-second cache

- Response gains `eventTypes` (identical list to `codes`) and `window`. Event types come **from the jobs of the window,
  not from the event-type catalogue**: an event may be emitted unregistered and a direct job's code is not an event type,
  so a catalogue-backed list would miss values the list can filter on.
- Cache, per instance, in memory (Go `api/filter_options_cache.go`): key = tenant scope (`*` for an anchor, else
  `c:` + the sorted client ids joined by `,`) + `|` + window `from` and `to` each truncated to a **5-minute bucket** (RFC
  3339 UTC), so two opens a few seconds apart whose "last 24 hours" differ by seconds share an entry. TTL **1 minute**;
  at most **256** entries (on `put`, expired entries are dropped, then the oldest is evicted until under the cap);
  **only a fully successful answer is cached** (if any of the six facet queries failed, return what was read but do not
  keep it). The `window` echoed with a cached answer is the one the first request computed. A fresh process (or after
  the TTL) sees new values.
- Java: a small `FilterOptionsCache` (a `LinkedHashMap` under a lock or Caffeine if already a dependency; check
  `pom.xml`). Note the keys include the caller's client set, so there is no cross-tenant leak.

### 7.5 Tests to port

`api/filter_refs_pg_test.go`: `TestRefs_EventTypeFilters` (exact, CSV, with status, with a wider window, `eventType` +
`eventTypes` + `codes` combine, 11 types -> 400 `TOO_MANY_EVENT_TYPES` on the list and the preview, the preview counts what
the list shows), `TestRefs_SubscriptionFilters` (id, ids, code exact/prefix, `rfsub-` no star is exact-empty, name
case-insensitive and prefix, combos, unmatched name = empty not error, `%*` literal, code+name disagreeing = empty,
202 subscriptions -> 400 `TOO_MANY_MATCHES` on the list and the preview, 51 ids -> 400 `TOO_MANY_SUBSCRIPTION_IDS`),
`TestRefs_ConnectionFilters` (id, code, with status, prefix, with subscription code, unknown = empty, id and code that
disagree = empty, the own-connection job found), `TestRefs_ResolutionIsTenantScoped` (client user cannot resolve or list
another tenant's names/ids; anchor resolves all), `TestFilterOptions_CachedAndCarriesEventTypes` (second call inside the
TTL does not see a new code; another tenant scope does not share the entry; a fresh state sees it);
`api/filter_params_test.go` `TestToParams_EventTypesMergeAndCap`, `TestToParams_SubscriptionAndConnectionReferences`;
`dispatchjob/refs_test.go` `TestNameMatch`, `TestUnresolvedReferenceFailsClosed`, `TestFilterParamsRenderTheNewFilters`;
`dispatchjob/filter_index_plan_pg_test.go` `TestFilterIndexPlans` (EXPLAIN: rare event type uses `code_created_at`, rare
subscription uses `subscription_id_created_at`, connection reads both indexes via BitmapOr, no partition seq scan; Java's
plan tests are in `DispatchJobPlanTest`); `stream/dispatch_jobs_projection_pg_test.go`
`TestDispatchJobProjection_EventTypeAndConnection` (a subscription job projects with `code` = event type and no
connection; a job that names `connection_id` projects it and keeps it after a status change; Java: `DispatchJobProjectionTest`);
`migrate/dispatch_read_filter_indexes_pg_test.go` `TestMigration086_SwapsIndexesAndOnlineScriptPreparesThem`;
`api/filter_options_pg_test.go` `TestFilterOptions_ScopedToTheCallersClients`.

## 8. Migration numbering and Flyway notes

| Go | content | Java |
|---|---|---|
| 085 `dispatch_job_write_permissions` | data (role grants) | next free `V<n>__dispatch_job_write_permissions.sql` |
| 086 `dispatch_job_read_filter_indexes` | DDL (column, 3 indexes, 2 drops) | `V<n+1>__dispatch_job_read_filter_indexes.sql` (also `ALTER TABLE msg_dispatch_jobs ADD COLUMN IF NOT EXISTS connection_id`, section 7.3) |
| 082 `dispatch_job_connection` | write-table column | folded into the line above; the rest of 082 (scheduler pause) is item C |
| 081, 083, 084 | function-runner schema | not needed (section 10) |

Java is at `V33`; the next free is `V34` **unless PENDING A1 lands first** (A1 reserves `V34`; then these are V35 and V36).
Do not hard-code the numbers in this file's successors: take the next free at implementation time and add each file to
`db/migration.index`. Numbering has been "Go number minus a moving offset" (Go ran ahead on its function and event-type
migrations); there is no 1:1 rule. Both migrations are written idempotent (`ON CONFLICT DO NOTHING`, `IF NOT EXISTS`,
`DROP ... IF EXISTS`) because a database may already have Go's 085/086 applied (the Java and Go schemas are shared
over `GoAdoptionTest`'s goose fixture) in either order. Bump the counts in `MigratorTest`, `GoAdoptionTest`,
`SchemaFingerprintTest`; the 085 equivalent is data-only so `SchemaFingerprintTest` ignores it.

## 9. K7. The SPA: it is NOT free (read how Java serves it)

Short version: the Java server embeds a built copy of the Vue SPA, and the SPA **source lives in this repo too**
(git subtree since 2026-09-14, `2026-09-14-frontend-source-shared.md`). `server/.../server/Frontend.java` serves
`classpath:frontend/`; `tools/build-frontend.sh` (`make frontend`) builds `./frontend` (Java's own copy of the source) and
rsyncs the result into `server/src/main/resources/frontend`, writing `frontend.source-commit`. So the Go SPA changes do not
arrive on their own: the two source trees must be brought together and rebuilt.

Facts from `tools/frontend-drift.sh` today (Java HEAD `21b60f6e` vs Go HEAD):

- The Java `frontend/` is **behind Go in the whole dispatch-jobs area**, not only by this batch. Go-only files:
  `pages/dispatch-jobs/{PayloadEditDialog,RedriveConfirmStep,RequeueByFilterDialog,RequeueSelectedDialog}.vue`,
  `jobDetail.ts`, `jobFilters.ts`, `jobWindow.ts`, `payloadEdit.ts`, `redriveConfirm.ts`, `requeueByFilter.ts`,
  `utils/nav-sections.ts`, tests `dispatch-job-write-permissions`, `dispatch-jobs-{detail,filter-body,filters,payload-edit,
  redrive,requeue,window}`, `nav-sections`. Files that differ: `api/dispatch-jobs.ts`, `api/client.ts` (the `onResponse`
  hook that reads the `X-Window-*` headers; Java's copy also has a Java-only `details` addition, keep both),
  `pages/dispatch-jobs/DispatchJobDetailDrawer.vue` and `DispatchJobListPage.vue` (Java's drawer diverges: it has a
  Java-only test `dispatch-job-detail-drawer.test.ts`), `utils/dispatch-job-permissions.ts`,
  `components/layout/AppSidebar.vue` (Java's lacks Go's older `visibleItem` and uses `filterNavigation`, so the nav change
  needs a hand merge), `api/generated/{index.ts,types.gen.ts}`.
- **Do not run `tools/pull-frontend-from-go.sh` blindly**: it is `rsync --delete`; the tree also holds Java-only function
  pages (`pages/functions/*`, `function-domains`, `function-policies`, `api/generated-functions`) that it would delete or
  rewrite (PENDING I says "port by hand, not by rsync"). Copy the dispatch-jobs files (and `nav-sections.ts`, `AppSidebar.vue`
  merged by hand, the listed tests) individually, then run `tools/frontend-drift.sh` and check the remaining diff is the
  known one.
- **Order matters**: the new SPA sends `since`/`until`, reads `X-Window-*`, calls `requeue-by-filter`, `PUT .../payload`,
  uses the five permission codes to show/hide buttons and sends the new filter fields. Land the Java API (K1-K6) first,
  then sync the SPA, then `make frontend`. An old API with the new SPA misbehaves (no window headers, 404 on the new routes,
  buttons hidden because the token lacks the new permissions).
- **Generated types**: `frontend/src/api/generated/types.gen.ts` is generated from the OpenAPI lock
  (`frontend/openapi-ts.config.ts` picks `server/src/main/resources/openapi/openapi.lock.json` in this repo). Update the
  Java lock for all new/changed operations and components first (list below), then regenerate (`pnpm` script in
  `frontend/package.json`); do not copy Go's `types.gen.ts` (it contains Go-only components and Java's has its own
  function types).
- What the new SPA depends on, as the Java API must provide: `GET /bff/dispatch-jobs` with `X-Window-From/To/Defaulted`
  response headers (the SPA tolerates their absence: "null when the headers are absent (an older server)"), request
  fields in section 6.1, `POST /bff/dispatch-jobs/requeue` with the redrive fields and `skipped`/`redriven`/`skippedInFlight`/
  `skippedCompleted` in the response, `requeue-by-filter` and its preview with the `window`, the 409 `FILTER_MATCH_CHANGED`
  with `details`, `PUT .../payload` returning the job, `POST .../cancel` and `.../complete` returning the job (Java
  already does), `filter-options` with `eventTypes` and `window`, and permission codes in the user's permission set.
  Go SPA behaviours worth knowing: a redrive uses a typed `REDRIVE` word (`REDRIVE_CONFIRM_WORD`); list errors
  (`WINDOW_TOO_LARGE`, `TOO_MANY_*`) are shown inline, not toasted; a window picker defaults to the last 24h; quick lenses
  (Failed etc.) set the filter; actions are hidden, not disabled, when the permission or status says no
  (`jobActions(status, has)`: requeue only for PENDING/FAILED/ERROR/CANCELLED/EXPIRED, redrive only for COMPLETED with both
  permissions, cancel/complete only for FAILED).
- **`a7497448` (collapsible nav).** SPA-only (`AppSidebar.vue`, new `utils/nav-sections.ts`, test `nav-sections.test.ts`):
  section headers become `aria-expanded` buttons with a chevron; collapsed state persists per viewer in `localStorage`
  key `fc:nav-closed`; the section holding the current page always stays open and its header does not toggle; the icon-only
  sidebar shows every entry. No API. It rides along with the SPA sync (hand-merge `AppSidebar.vue`).
- **Go-only, no Java work:** `/ui` (the Datastar server-rendered console: `internal/ui/**`, the templ pages for dispatch
  jobs, `internal/ui/kit/navgroup.go`, `internal/ui/shell/nav.go`) is Go-only by design (see `PENDING.md`, "Go features with
  no Java equivalent").

OpenAPI lock changes the Java lock needs (names from Go `api/openapi.lock.json`): operations `editDispatchJobPayload`
(PUT `/api/dispatch-jobs/{id}/payload` and the `/bff` twin), `previewRequeueDispatchJobsByFilter`, `requeueDispatchJobsByFilter`;
changed `requeueDispatchJobs` request/response; query parameters on `listDispatchJobs`, `listDispatchJobsRaw`, the
`X-Window-*` response headers on both; `dispatchJobFilterOptions` response (`eventTypes`, `window`); components
`EditPayloadRequest`, `RequeueRequest` (`redriveCompleted`, `confirmRedriveCompleted`), `RequeueResponse`,
`SkippedJobResponse`, `RequeueByFilter*`, `WindowResponse`, `DispatchJobFilterOptionsResponse`. Re-run
`tools/sdk-drift.sh` afterwards: the Go SDK copies (`clients/*/openapi/*.json`) changed in these commits; whether the
Java repo's `sdk/` copies follow is PENDING H (owner stance needed).

## 10. Go functions work in the same range that Java does NOT need (unless it adopts the Go function runner)

One line each; none is in scope here:

- **F35** `f0e70ee4`/`c5261560`/migration 084: optional per-function service account as the webhook verifier. Go runner only.
- **Prune** `fe366e0c`/`7b170a75`: `FC_FUNCTIONS_KEEP_VERSIONS` (default 20) prunes old untagged function versions after
  publish and emits the shared event `platform:function:version:pruned`. Go wazero runner; Java's JVM function host has its own
  version lifecycle. (The commit message says "Java/Rust to add the same" for the *event code*: if Java ever prunes versions,
  use `platform:function:version:pruned`.)
- **clientCodes** `1a37f5d8`: exposes the caller's client codes and `canAccessClientCode` in the function SDKs. Function
  SDK feature, no platform-side Java work.
- Also in the Go range and not covered here: generic runner plumbing `67c721dc` (runtimes filter, `POOL_LACKS_RUNTIME`),
  migrations 081/083 (function version requeue revision, runtime tag), and F22..F34 of Go's `docs/platform-bug-fixes.md`
  (the `PENDING.md` audit stopped at F21, `bf81422f`; 79 Go commits since then have **not** been audited against Java).

## 11. Where Java differs materially today (checklist for the implementer)

| Topic | Go (target) | Java today |
|---|---|---|
| Requeue statuses | guarded; in-flight never, COMPLETED only by confirmed redrive | **total**, any status, by documented ruling (`docs/spec/dispatchjob.md` ~177, ~511; `requeueIsTotalSoACompletedJobIsResetToo`) |
| Write permissions | five separate codes | `requeue`, `cancel`, `complete` all gated on `DISPATCH_JOB_VIEW` |
| Time window | mandatory, default 24h, max 31 days, echoed | none; list unbounded; `filter-options` unbounded |
| Requeue by filter | preview + run | absent (PENDING G) |
| Edit payload | `PUT /{id}/payload` | absent |
| Filters | event type(s), subscription id(s)/code/name, connection id/code | none of these (`ListFilter` has code/codes/applications/subdomains/aggregates only) |
| `filter-options` | + `eventTypes`, `window`, 60s cache | six lists, no window, no cache |
| Read table | `connection_id` column, composite indexes | no column; single-column `code` and `subscription_id` indexes |
| Out-of-scope single-job write | 403 `SCOPE_FORBIDDEN` | **404** (ruling PR-3); keep Java's |
| Requeue events | one rollup `platform:messaging:dispatch-jobs:resent` | per-job + rollup under `platform:admin:dispatchjob(s):requeued` (DJ-5); keep names, add fields |
| Requeue audit operation name | `ResendCommand` | `RequeueCommand` |
| Payload edit race | 500 (plain error) | will be 409 `NOT_EDITABLE` (recommended deviation) |
| SPA | current | far behind in the dispatch-jobs area; sync by hand (section 9) |

## 12. Ambiguities I could not resolve from the code (for the owner or the implementer)

1. **Java's stored event schemas** for the changed requeue rollup (section 4): whether to add a V27-style update migration
   or make the new fields optional; depends on whether production validates emitted platform events against the stored
   schema, which I did not trace in Java. Go itself did not migrate its changed `resent` schema.
2. **`msg_dispatch_jobs.connection_id` in Java** (section 7.3): the recommendation (add the write column in the K6
   migration and project it) goes slightly beyond "mirror 086" because Go 082 is item C's prerequisite. If the owner would
   rather keep K6 independent of C, the alternative is to add the column to the read table and index only, and leave the
   projector alone until C; the connection filter's own-connection branch then matches nothing in Java-created data
   either way.
3. **Whether the requeue-by-filter preview should live in `Group.API_READ`** (it is a POST that only counts) or
   `API_WRITE`: Go does not distinguish; I recommend `API_READ` since it opens no transaction.
4. **Race-count quirk in requeue by ids**: Go counts a job as `requeued` from the in-memory pass even if the SQL guard
   (a status change between read and write) then refuses the UPDATE silently. Not fixed in Go; Java may count the
   `RETURNING` rows instead. Not pinned by any Go test.
5. **Which Java roles besides `messaging-admin` and `viewer` hold `view`** in a deployed database is data, not code; the
   85-equivalent migration handles it, but the owner may want a query before deploy:
   `SELECT r.name FROM iam_roles r JOIN iam_role_permissions p ON p.role_id = r.id WHERE p.permission =
   'platform:messaging:dispatch-job:view' ORDER BY 1;` to see who will gain write abilities.
6. I did not run any Go test or any Java build; all behaviour above is from reading the code and tests.
