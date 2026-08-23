# Scheduled job — behavioural spec

The contract for `io.flowcatalyst.platform.scheduledjob`. Derived from the
lockfile (`/api/scheduled-jobs*`, `/api/applications/{appCode}/scheduled-jobs/sync`)
plus the validation rules, authorization placement, state machines, error
codes, domain events and the **cron parser** the aggregate embodies. The Java
is written *from* this; tests assert it. Questions marked **load-bearing or
accident?** need an owner ruling — until ruled on, the behaviour is kept.
Lines marked **Ruling:** are owner decisions already taken.

The scheduler loops (poller + dispatcher, stale recovery, webhook delivery,
signing) are the **data plane** and are *not* this unit; §7 states only the
contract they consume (`latestSlotInWindow`, `markFired`, the instance
projection).

## 1. Aggregate

A scheduled job is a cron-driven firing definition, identified by a `code`
that is unique **within its client scope** (a client id, or the platform
scope when `clientId` is null). Each firing is recorded as a **scheduled job
instance** (§6) — a projection written outside the use-case envelope.

| Field | Type | Notes |
|---|---|---|
| `id` | `sjb_` + 13-char TSID | generated on create |
| `clientId` | string, optional | `null` = platform-scoped; immutable after create |
| `applicationId` | string, optional | set by create (body) or sync (resolved from `{appCode}`); sync backfills/corrects it on existing rows |
| `code` | string | `^[a-z][a-z0-9-]*$` after trim + lowercase (§4); unique per `(code, clientId)` |
| `name` | string, required | trimmed |
| `description` | string, optional | |
| `status` | `ACTIVE` \| `PAUSED` \| `ARCHIVED` | default `ACTIVE`; lenient stored read: unknown → `ACTIVE` |
| `crons` | list of string, ≥ 1 | 6-field expressions (§3); stored `text[]` verbatim |
| `timezone` | string | IANA zone name; default `UTC` (a blank value on create, update or sync ⇒ `UTC` — **deviation**: Go stored `""` on a blank update); **not validated** — an unknown zone is evaluated as UTC at fire time (**load-bearing or accident?** an admin typo silently shifts every firing) |
| `payload` | JSON, optional | opaque, delivered with the firing |
| `concurrent` | boolean | default `false`; **consumer-owned, not enforced** by the platform (**Ruling**) |
| `tracksCompletion` | boolean | default `false`; when true `DELIVERED` is not terminal (§6) |
| `timeoutSeconds` | int, optional | |
| `deliveryMaxAttempts` | int | default **3** (`ScheduledJob.DEFAULT_DELIVERY_MAX_ATTEMPTS`) |
| `targetUrl` | string, optional | |
| `lastFiredAt` | timestamp, optional | the last cron **slot** fired (not wall-clock) — advanced monotonically by the poller (§7) |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` at persist time |
| `createdBy`, `updatedBy` | principal id, optional | `updatedBy` set by update / pause / resume / archive / sync-update |
| `version` | int | starts at 1; +1 on update, pause, resume, archive and on a sync that changes the row; **not** bumped by `markFired` |

## 2. State machine

```
ACTIVE ──pause──▶ PAUSED ──resume──▶ ACTIVE
  │                  │
  └────archive───────┴────────▶ ARCHIVED
```

| Transition | Precondition | Effect |
|---|---|---|
| `pause` | none | `PAUSED`, `version+1` |
| `resume` | none | `ACTIVE`, `version+1` |
| `archive` | none | `ARCHIVED`, `version+1` |
| `update(Changes)` | none | non-null fields applied, `version+1` |
| `fireNow(correlationId)` | status ≠ `ARCHIVED` — else 409 `ARCHIVED` "Archived jobs cannot be fired" | a `MANUAL` `QUEUED` instance (§6); the job row is **not** changed |
| sync reconcile | — | see §8 |

**load-bearing or accident?** There are no status preconditions on
pause/resume/archive: `resume` on an `ARCHIVED` job un-archives it,
`pause` on an `ARCHIVED` job makes it `PAUSED`, `archive` twice bumps the
version twice. A `PAUSED` job **is** firable manually (the poller skips
`PAUSED`; a human can override) — pinned.

## 3. Cron expressions (the parser contract)

One parser, `CronExpression.parse(String)`, is the only reader of cron text;
the create/update/sync validate phases and `latestSlotInWindow` all go
through it. **Ruling:** exactly **6** whitespace-separated fields
`second minute hour day-of-month month day-of-week`; 5-field (POSIX) and
7-field (with year) expressions are **rejected** at create/update/sync (the
Go shape gate accepted 5–7 fields and silently never fired 5/7-field rows).

### 3.1 Grammar

| Field | Range | Names (case-insensitive) |
|---|---|---|
| second | 0–59 | — |
| minute | 0–59 | — |
| hour | 0–23 | — |
| day-of-month | 1–31 | — |
| month | 1–12 | `jan`…`dec` |
| day-of-week | 0–6 (0 = Sunday) | `sun`…`sat` |

Each field is a comma-separated list of *ranges*; a range is
`*` \| `?` \| `N` \| `N-M` \| `name` \| `name-name`, optionally followed by
`/step`. Rules, each pinned by a row in `ScheduledJobTest`:

| Rule | Accepted | Rejected (400 `INVALID_CRON`) |
|---|---|---|
| field count | 6 fields, any whitespace (spaces/tabs, leading/trailing) | 0–5 or ≥ 7 fields → **`CRON_INVALID_SHAPE`** "cron expression must have 6 whitespace-separated fields (sec min hour dom mon dow), got N: '<expr>'" |
| blank | — | `null`, `""`, `"   "` → `INVALID_CRON` "cron expressions cannot be empty" |
| wildcard | `*`, `?` (identical; both mean "any" and carry the *star* flag) | — |
| single value | `5`, `05`, `+5` (`Integer.parseInt` semantics, as Go `Atoi`) | `-1` (negative), `abc`, `L`, `W`, `#`, `1.5` |
| range | `1-5`, `mon-fri`, `jan-mar`, `MON-FRI` | `5-3` (start > end), `1-2-3` (too many hyphens), `0-7` dow (end above max) |
| step | `*/15`, `1-30/5`, `5/10` (= `5-max/10`), `*/1` | `*/0` (step 0), `1/2/3` (too many slashes), `*/x` |
| list | `1,15`, `mon,wed,fri` | `1,,2`, `1,`, `,` — an empty list item is a malformation (**deviation**: Go's `FieldsFunc` silently dropped empty items; `,` alone parsed to a field that never matches) |
| bounds | values within the field range | `60` sec/min, `24` hour, `0`/`32` dom, `0`/`13` month, `7` dow → "… above maximum / below minimum" |
| descriptors | — | `@daily`, `@every 1h`, … → `INVALID_CRON` "descriptors are not supported" (the Go parser was built without the descriptor option) |
| per-expression zone | — | `TZ=…`/`CRON_TZ=…` prefix → `INVALID_CRON` (the job's `timezone` is the only zone; Go's robfig would have honoured the prefix — 7 tokens, so already shape-rejected under the ruling) |

Semantics, as robfig/cron v3 (`SpecSchedule.Next`):

- **Day matching.** If *either* day-of-month or day-of-week carries the star
  flag (`*`/`?` with no step > 1), both must match (AND). If *both* are
  restricted, the day matches when either does (OR — classic cron).
  `*/2` in day-of-month clears the star flag (pinned: `0 0 0 */2 * 1` fires
  on the next odd day *or* Monday, whichever is first).
- **Next occurrence** is strictly after the given instant, at whole-second
  resolution, searched field by field (month → day → hour → minute →
  second) with carry; none within **5 years** → no occurrence (`0 0 0 30 2 *`
  never fires).
- **Time zone.** Evaluation happens in the job's `ZoneId` on the local
  wall clock; hours/minutes/seconds advance on the instant timeline, days
  and months on the local calendar. Consequences (pinned, `Europe/Amsterdam`):
  a slot in a spring-forward gap (`0 30 2 * * *` on 2026-03-29) is **skipped**
  to the next day; a slot in a fall-back overlap (2026-10-25 02:30) fires at
  the **first** occurrence (`00:30Z`); `0 0 3 * * *` on the spring-forward day
  fires at 03:00 CEST = `01:00Z`. Gap/overlap resolution follows `java.time`
  (Go's `time.Date` is unspecified in a gap) — **accident**, the two agree
  on every pinned row.

### 3.2 `latestSlotInWindow(crons, zone, after, upTo)`

The poller's question: the **latest** slot of any of the job's expressions in
the half-open window `(after, upTo]`, evaluated in `zone`, returned in UTC;
empty when no slot falls in the window or `after >= upTo`. **Skip-missed**
(AWS-style): when several slots elapsed, only the latest fires. Expressions
that fail to parse are **skipped**, not errors (legacy 5-field rows stored
before the ruling yield no slot — **load-bearing** for stored data). An
unknown zone name is evaluated as UTC (`ScheduledJob.zoneId()`).

Pinned rows (`ScheduledJobTest.latestSlotInWindow*`): skip-to-latest,
no-slot window, empty window, inverted window (`after > upTo`), latest
across two crons, lower bound exclusive, upper bound inclusive, zone
conversion, unparseable skipped, one unparseable + one good (the good one
fires).

**load-bearing or accident?** The walk is forward from `after`: an
every-second expression on a job whose window is months wide is O(slots)
— the poller (data plane) may want a bound.

## 4. HTTP surface (lockfile)

All routes require a bearer; errors are the `ErrorModel` envelope
`{"error": CODE, "message": …, "details"?: …}`. "write" = any of
`scheduled-job:{create|update|delete}`.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/scheduled-jobs` | `scheduled-job:view` | query `status`, `clientId` (`platform` = platform-scoped only), `search` (ILIKE `%s%` on code OR name), `page`/`size`(+aliases) | 200 `OffsetPageScheduledJobResponse` `{data, page, size, total, total_pages}` | ordered by code; non-anchors see platform-scoped rows + their clients' (in SQL, so `total` is consistent); `hasActiveInstance` per row |
| `POST /api/scheduled-jobs` | write | `CreateScheduledJobRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | |
| `GET /api/scheduled-jobs/by-code/{code}` | `scheduled-job:view` | query `clientId` (absent = platform scope) | 200 `ScheduledJobResponse` | 404 `ScheduledJob_NOT_FOUND` (message carries the code); 403 `FORBIDDEN` "No access to this scheduled job" |
| `GET /api/scheduled-jobs/{id}` | `scheduled-job:view` | — | 200 `ScheduledJobResponse` | same 404/403 |
| `PUT /api/scheduled-jobs/{id}` | write | `UpdateScheduledJobRequest` → `UpdateCommand(id)` | 204 | partial: absent = untouched |
| `DELETE /api/scheduled-jobs/{id}` | `scheduled-job:delete` | `DeleteCommand` | 204 | hard delete of the job row; instances/logs are **not** deleted (**accident?** orphans) |
| `POST /api/scheduled-jobs/{id}/pause` | write | `PauseCommand` | 204 | |
| `POST /api/scheduled-jobs/{id}/resume` | write | `ResumeCommand` | 204 | |
| `POST /api/scheduled-jobs/{id}/archive` | write | `ArchiveCommand` | 204 | |
| `POST /api/scheduled-jobs/{id}/fire` | `scheduled-job:fire` | optional `FireNowRequest{correlationId}` → `FireNowCommand` | 202 `FireNowResponse` `{id, scheduledJobId, instanceId}` (`id` = `instanceId`, SPA toast reads `id`) | |
| `GET /api/scheduled-jobs/{id}/instances` | `scheduled-job:view` | query `status`, paging | 200 `OffsetPageScheduledJobInstanceResponse` | newest first (`created_at DESC, id DESC`); `status` is read leniently — unknown → `QUEUED` filter (**accident?**); no scope check on `{id}` (rows carry `clientId`) (**accident?**) |
| `GET /api/scheduled-jobs/instances/{instanceId}` | `scheduled-job:view` (**not** `scheduled-job-instance:view` — **accident?**) | — | 200 `ScheduledJobInstanceResponse` | 404 `ScheduledJobInstance_NOT_FOUND`; 403 `FORBIDDEN` "No access to this instance" |
| `GET /api/scheduled-jobs/instances/{instanceId}/logs` | `scheduled-job:view` | — | 200 bare array of `ScheduledJobInstanceLogResponse` | oldest first, at most **500**; no 404 for an unknown instance (empty array); no scope check (**accident?**) |
| `POST /api/scheduled-jobs/instances/{instanceId}/log` | write | `WriteInstanceLogRequest{level, message, metadata}` | 204 | 404 instance; scope-checked on the instance's client (`SCOPE_FORBIDDEN`); direct row insert, no event (**accident?** no envelope) |
| `POST /api/scheduled-jobs/instances/{instanceId}/complete` | write | `CompleteInstanceRequest` (§6.2) | 204 | 404 instance; scope-checked; direct row update, idempotent, no event |
| `POST /api/applications/{appCode}/scheduled-jobs/sync` | `scheduled-job:sync` et al. (sdksync) | `SyncScheduledJobsRequest` → `SyncScheduledJobsCommand` | 200 `SyncScheduledJobsResultResponse` | the route lives in the sdksync surface (not wired here); the operation is here (§8) |

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `CreateScheduledJobRequest` | `code`*, `name`*, `crons`*, `concurrent`*, `tracksCompletion`*, `timezone`, `clientId`, `applicationId`, `description`, `payload`, `timeoutSeconds`, `deliveryMaxAttempts`, `targetUrl` | absent `timezone` ⇒ `UTC`; absent `deliveryMaxAttempts` ⇒ 3 (domain defaults) |
| `UpdateScheduledJobRequest` | all optional: `name`, `description`, `crons`, `timezone`, `payload`, `concurrent`, `tracksCompletion`, `timeoutSeconds`, `deliveryMaxAttempts`, `targetUrl` | absent = untouched; `crons: []` is an error; JSON `payload: null` clears the payload |
| `ScheduledJobResponse` | `id, clientId?, applicationId?, code, name, description?, status, crons, timezone, payload?, concurrent, tracksCompletion, timeoutSeconds?, deliveryMaxAttempts, targetUrl?, lastFiredAt?, createdAt, updatedAt, createdBy?, updatedBy?, version, hasActiveInstance` | optional fields omitted when null |
| `FireNowRequest` | `correlationId?` | body optional |
| `FireNowResponse` | `id, scheduledJobId, instanceId` | |
| `ScheduledJobInstanceResponse` | `id, scheduledJobId, clientId?, jobCode, triggerKind, scheduledFor?, firedAt, deliveredAt?, completedAt?, status, deliveryAttempts, deliveryError?, completionStatus?, completionResult?, correlationId?, createdAt` | |
| `ScheduledJobInstanceLogResponse` | `id, instanceId, scheduledJobId?, clientId?, level, message, metadata?, createdAt` | |
| `WriteInstanceLogRequest` | `level`*, `message`*, `metadata` | blank `level` / `message` → 400 `LEVEL_REQUIRED` / `MESSAGE_REQUIRED` (Go's framework rejected the missing required fields); `level` is otherwise free text (`DEBUG|INFO|WARN|ERROR` by doc, not enforced — **accident?**) |
| `CompleteInstanceRequest` | `status`, `completionStatus`, `completionResult`, `result` | two dialects, §6.2 |
| `SyncScheduledJobsRequest` | `jobs`*`[SyncScheduledJobInputRequest]`, `clientId`, `archiveUnlisted` | |
| `SyncScheduledJobInputRequest` | `code`*, `name`*, `crons`*, `description`, `timezone` (default UTC), `payload`, `concurrent`, `tracksCompletion`, `timeoutSeconds`, `deliveryMaxAttempts` (default 3), `targetUrl` | |
| `SyncScheduledJobsResultResponse` | `applicationCode, created[], updated[], archived[]` | job **ids** (not counts) |

## 5. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `code` non-blank after trim | `CODE_REQUIRED` | `code is required` |
| Create | trimmed+lowercased code matches `^[a-z][a-z0-9-]*$` | `INVALID_CODE_FORMAT` | `code must start with a lowercase letter and contain only lowercase alphanumeric and hyphens` |
| Create | `name` non-blank | `NAME_REQUIRED` | `name is required` |
| write log (handler) | `level`, `message` non-blank | `LEVEL_REQUIRED` / `MESSAGE_REQUIRED` | |
| Create / Update (when present) | `crons` non-empty | `CRONS_REQUIRED` | `at least one cron expression is required` |
| Create / Update / Sync | each cron non-blank | `INVALID_CRON` | `cron expressions cannot be empty` |
| Create / Update / Sync | each cron has 6 fields | `CRON_INVALID_SHAPE` | §3.1 |
| Create / Update / Sync | each cron parses (§3.1) | `INVALID_CRON` | `cron expression '<expr>': <detail>` |
| Update | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `name`, when present, non-blank | `NAME_REQUIRED` | `name cannot be empty` |
| Pause / Resume / Archive / Delete / FireNow | `id` non-blank | `ID_REQUIRED` | |
| Sync | `applicationCode` non-blank | `APPLICATION_CODE_REQUIRED` | (**deviation**: Go did not check; the handler always sets it) |
| Sync | each entry has code, name and ≥ 1 cron | `INVALID_SYNC_ENTRY` | `Sync entry '<code>' must have code, name, and at least one cron` |

Sync entry codes are taken **verbatim** (not trimmed/lowercased, not
pattern-checked) — **load-bearing or accident?** (a sync can create a code
the admin API could never create). Sync crons are parsed under the ruling
(**deviation**: Go accepted any string in sync).

## 6. Instances (projection) and logs

### 6.1 Instance

| Field | Notes |
|---|---|
| `id` | `sji_` TSID |
| `scheduledJobId`, `clientId`, `jobCode` | denormalised from the job at fire time |
| `triggerKind` | `CRON` \| `MANUAL` \| `BACKFILL`; lenient read unknown → `CRON` |
| `scheduledFor` | the cron slot (null for `MANUAL`) |
| `firedAt`, `createdAt` | when the row was created |
| `status` | `QUEUED` → `IN_FLIGHT` → `DELIVERED` → `COMPLETED` \| `FAILED`; `DELIVERY_FAILED` after `deliveryMaxAttempts`; lenient read unknown → `QUEUED` |
| `deliveryAttempts`, `deliveryError`, `deliveredAt` | dispatcher bookkeeping |
| `completionStatus`, `completionResult`, `completedAt` | set by `/complete` |
| `correlationId` | from `FireNowRequest` |

Terminal: `COMPLETED`, `FAILED`, `DELIVERY_FAILED`; `DELIVERED` is terminal
unless the job `tracksCompletion`. **`hasActiveInstance(job)`** = any
instance of the job with status `QUEUED`/`IN_FLIGHT`, or (`tracksCompletion`
and status `DELIVERED` and `completed_at IS NULL`). Computed per row of the
list (**accident?** N+1, bounded by the page size).

`FireNow` (two-phase): after load + scope + not-archived check, the
`MANUAL` `QUEUED` instance (`deliveryAttempts = 0`, `firedAt = now`) is
**inserted directly** (it is a projection, not an aggregate), then
`fired-manually` is emitted through the envelope (`Plan.emit`). A failed
insert yields no event; a failed event commit leaves the instance row
(**load-bearing or accident?** — the Go shape).

### 6.2 Completing an instance — two dialects

`resolveCompletion(status, completionStatus)`; an explicit `completionStatus`
always wins; `status` is matched case-insensitively:

| `status` | `completionStatus` | → instance status | → completion status |
|---|---|---|---|
| absent/empty | c | `COMPLETED` | c |
| `SUCCESS` / `FAILURE` (SDK dialect) | empty | `COMPLETED` | `SUCCESS` / `FAILURE` (upper-cased) |
| `SUCCESS` / `FAILURE` | c | `COMPLETED` | c |
| anything else (SPA dialect: an instance status) | c | lenient `InstanceStatus.parse` (unknown → `QUEUED` — **accident?**) | c |

Result payload: `completionResult` if present, else `result` (SDK alias).
The update sets `status`, `completion_status`, `completion_result`,
`completed_at = now()`; repeated calls overwrite (idempotent).

### 6.3 Log

`{id: sjl_…, instanceId, scheduledJobId, clientId, level, message, metadata?, createdAt}`;
`scheduledJobId`/`clientId` copied from the instance. Listed oldest first
(`created_at, id`), capped at 500.

## 7. Persistence and the data-plane contract

Job upsert `ON CONFLICT (id)` listing each column once; `created_by` /
`created_at` insert-only; `updated_at = now()` at persist (not the
aggregate's) — **accident**, harmless. `crons` is `text[]`; `payload`
JSONB (JSON `null` on the wire ⇒ column `NULL`). Delete removes the job row
only. `findByCode(code, clientId)`: `client_id = ?` or `client_id IS NULL`.

Data-plane contract (consumed by the scheduler unit, not implemented here
beyond the repository calls): `findActive()` (status `ACTIVE`);
`latestSlotInWindow(crons, zone, after = lastFiredAt ?? createdAt, now)`;
the poller inserts a `CRON` instance with `scheduledFor = slot` then
`markFired(id, slot)` = `last_fired_at = GREATEST(last_fired_at, slot)`
(monotonic; no version bump). The dispatcher's instance transitions
(`IN_FLIGHT` / `DELIVERED` / `DELIVERY_FAILED` / back to `QUEUED`) belong to
the data-plane unit.

## 8. Sync semantics

Input: `applicationCode`, `applicationId` (resolved by the handler, may be
null), `clientId` (null = platform scope), `jobs[]`, `archiveUnlisted`.
Scope = every job whose `clientId` equals the command's (or `IS NULL`).

| Input row | Effect | Per-row event |
|---|---|---|
| code exists in scope, **something differs** (name, description, crons, timezone, payload, concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, `applicationId` when the command carries one and it differs/is null, or status ≠ `ACTIVE`) | fields replaced, status → `ACTIVE` (a reappearing archived/paused job is **re-activated**), `version+1`, `updatedBy` | `updated` |
| code exists, nothing differs | **not persisted, not reported** (pure no-op; version unchanged) | — |
| code new | created `ACTIVE` with the entry's values, `clientId`/`applicationId` from the command, `createdBy` | `created` |
| `archiveUnlisted` and an `ACTIVE` job in scope is not in the input | archived | `archived` |
| `archiveUnlisted` and a `PAUSED`/`ARCHIVED` unlisted job | untouched | — |

Payload comparison is **structural** (`JsonNode.equals`) — **deviation**: Go
compared raw bytes, so whitespace/key-order differences counted as changes.
The rollup carries the **ids** `created[]`, `updated[]`, `archived[]`
(always arrays — Go serialised `null` for an empty one, **accident**). All
writes, per-row events and the rollup commit in one transaction; one audit
row per per-row event, `operation = SyncScheduledJobsCommand`.

## 9. Authorization placement

| Where | What |
|---|---|
| Handler | coarse permission (§4 table); unauthenticated → 403 `UNAUTHENTICATED`; list visibility is the shared `Visibility` (`AuthContext.visibility()`: anchor → everything, else platform-scoped rows + own clients) ANDed in SQL via `VisibilitySql`; the explicit `clientId` query scope (`platform` literal / one client) is the repository's own `ClientFilter`, a different concept (what the caller asks for, not what it may see) |
| Create — `authorize` | `checkScopeAccess(principal, cmd.clientId)`: client-bound needs access to that client; platform-scoped needs anchor / super-admin → else 403 `SCOPE_FORBIDDEN` (Go: anchor only, `FORBIDDEN` — **deviation**, the Java helper is shared) |
| Update / Pause / Resume / Archive / Delete / FireNow — `execute`, after load + 404 | `Access.loadScoped` = `checkScopeAccess(principal, job.clientId)`; hence `Authorize.publicAccess()` |
| Sync — `authorize` | `Checks.checkApplicationAccess(principal, applicationId, applicationCode)` (**deviation**: the Go scheduled-jobs sync handler resolved the app but did not call `requireAppAccess` as the other syncs do — **accident**; Java applies the promoted rule) **and** `checkScopeAccess(principal, cmd.clientId)` |
| Reads | handler only: list visibility in SQL; get by id/code → 403 `FORBIDDEN` "No access to this scheduled job"; instance get → "No access to this instance"; log/complete writes → `checkScopeAccess` on the instance's client |

## 10. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another job with the same `(code, clientId)` | `CODE_EXISTS` | 409 |
| by-id operations | no job with that id | `ScheduledJob_NOT_FOUND` | 404 |
| FireNow | job `ARCHIVED` | `ARCHIVED` | 409 |
| instance routes | no instance with that id | `ScheduledJobInstance_NOT_FOUND` | 404 |

## 11. Domain events

Source `platform:admin`; spec version `1.0`; subject `platform.scheduledjob.{id}`;
**message group `platform:scheduledjob:{id}`** on every per-aggregate event
(unlike most aggregates — **load-bearing**, firings must stay ordered per job).

| Type | `data` |
|---|---|
| `platform:admin:scheduled-job:created` | `scheduledJobId, code` |
| `platform:admin:scheduled-job:updated` | same |
| `platform:admin:scheduled-job:paused` | same |
| `platform:admin:scheduled-job:resumed` | same |
| `platform:admin:scheduled-job:archived` | same |
| `platform:admin:scheduled-job:deleted` | same |
| `platform:admin:scheduled-job:fired-manually` | `scheduledJobId, code, instanceId` |
| `platform:admin:scheduledjobs:synced` — subject `platform.scheduledjobs.synced.{applicationCode}`, message group `platform:scheduledjobs:synced` | `applicationCode, created[], updated[], archived[]` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Scheduledjob`, `entity_id = {id}`,
`operation` = command record simple name — `PauseCommand` / `ResumeCommand`
/ `ArchiveCommand` (**deviation**: Go aliased all three to one
`transitionCommand` type, so its audit rows read `transitionCommand`),
`operation_json` = the command).

## 12. Open questions for the owner (summary)

1. No preconditions on pause/resume/archive (`resume` un-archives). Intended?
2. `timezone` is never validated; unknown zones fire in UTC.
3. Sync entry codes bypass the code format rule; Java now parses sync crons (Go did not).
4. Delete orphans instances/logs.
5. Instance reads gated by `scheduled-job:view`, not `scheduled-job-instance:view`; log/complete writes gated by the job write permission, and written without the envelope (no event, no audit).
6. Lenient instance `status` query filter / complete dialect (unknown → `QUEUED`).
7. `hasActiveInstance` per row (N+1).
8. FireNow two-phase: instance insert outside the event transaction.
9. Audit `operation` name for pause/resume/archive (`transitionCommand` in Go).
10. Forward cron walk is unbounded for dense expressions over a long window.
11. BFF-only list filters (`clientIds` incl. the `platform` literal, `applicationIds`, `statuses`) are not ported until the BFF lands.
12. Empty cron list items are rejected (robfig ignored them).
