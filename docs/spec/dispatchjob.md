# Dispatch job — behavioural spec

The contract for `io.flowcatalyst.platform.dispatchjob`. Derived from the
lockfile (`/api/dispatch-jobs*` — nine read operations and one operator
write) plus the filter, scoping, state and persistence rules the aggregate
embodies. The Java is written *from* this; tests assert it. Questions marked
**load-bearing or accident?** need an owner ruling — until ruled on, the
behaviour is kept.

## 0. Boundaries — what this unit is and is not

A dispatch job is one delivery of one message (an event or a task) to one
target URL. Jobs are **infrastructure-written**: they are created by SDK
ingest (`POST /api/dispatch-jobs/batch` — a later unit) and by the stream
fan-out, moved through their lifecycle by the **scheduler** (poller / stale
recovery) and the **processing endpoint** (`POST /api/dispatch/process`, the
router callback — later unit `dispatchprocessing`), and mirrored into a
denormalised read projection by the **stream projector**. None of those
writers emit domain events or audit rows.

This unit owns:

- the aggregate, its enums and the read projection record (§1);
- the repository: detail reads on the write table, list/facet reads on the
  projection, attempt history, and `Persist` (upsert by `(id, created_at)`)
  (§9);
- the **human-initiated** operator action behind `POST /api/dispatch-jobs/requeue`,
  ported as an envelope operation (event + audit) — the only write (§6);
- the `/api/dispatch-jobs` read surface (§3).

It does **not** own (documented here so the later units implement against
the same row contract — §10): ingest/insert, the scheduler's
`PENDING → QUEUED → PENDING` flips, the processing endpoint's
`PROCESSING / COMPLETED / FAILED / retry` flips and attempt recording, the
projector, the `/bff/**` mirrors and `/bff/debug/dispatch-jobs`.

## 1. Aggregate

### 1.1 `DispatchJob` — the write-table row (`msg_dispatch_jobs`)

Table is `PARTITION BY RANGE (created_at)`, monthly partitions
`msg_dispatch_jobs_YYYY_MM`, **primary key `(id, created_at)`**. Every
by-key write must carry `created_at` alongside `id` so the planner prunes
to one partition.

| Field | Type | Column | Notes |
|---|---|---|---|
| `id` | 13-char **untyped** TSID (no prefix — column is `varchar(13)`) | `id` | ingest generates it (`Tsid.generate()`), or the SDK supplies one |
| `externalId` | string, optional | `external_id` | |
| `kind` | `EVENT` \| `TASK` | `kind` | lenient read: unknown → `EVENT` |
| `code` | string, required | `code` | `app:subdomain:aggregate:event` by convention, **not validated** — any string |
| `source` | string, optional | `source` | |
| `subject` | string, optional | `subject` | |
| `targetUrl` | string, required | `target_url` | |
| `protocol` | `HTTP_WEBHOOK` | `protocol` | the only protocol; the column is ignored on read (always `HTTP_WEBHOOK`) — **accident?** (a stored other value is masked) |
| `payload` | string, optional | `payload` (text) | the body bytes as text; not on the projection |
| `payloadContentType` | string | `payload_content_type` | `NULL` reads as `application/json` (default) |
| `dataOnly` | boolean | `data_only` | |
| `eventId` | string, optional | `event_id` | the event this job was fanned out from |
| `correlationId` | string, optional | `correlation_id` | |
| `clientId` | string, optional | `client_id` | tenant; `NULL` = platform-scoped |
| `subscriptionId` | string, optional | `subscription_id` | |
| `serviceAccountId` | string, optional | `service_account_id` | |
| `dispatchPoolId` | string, optional | `dispatch_pool_id` | |
| `messageGroup` | string, optional | `message_group` | FIFO key for ordered modes |
| `mode` | `IMMEDIATE` \| `NEXT_ON_ERROR` \| `BLOCK_ON_ERROR` | `mode` | `io.flowcatalyst.platform.subscription.DispatchMode` (the router's enum, destined for a shared `messaging` package); lenient read: unknown → `IMMEDIATE` |
| `sequence` | int | `sequence` | order within the group, default 99 |
| `timeoutSeconds` | int ≥ 0 | `timeout_seconds` | default 30 |
| `schemaId` | string, optional | `schema_id` | not on the projection |
| `maxRetries` | int ≥ 0 | `max_retries` | default 3 |
| `retryStrategy` | `immediate` \| `fixed` \| `exponential` | `retry_strategy` | **stored and wire strings are lowercase**; lenient read: `IMMEDIATE`→`immediate`, `FIXED_DELAY`→`fixed`, unknown/`NULL` → `exponential`. The Java enum is `RetryStrategy.IMMEDIATE/FIXED/EXPONENTIAL` with a `wire()` accessor because the constant cannot be lowercase — a documented deviation from "constant name = stored string" |
| `status` | see §2 | `status` | lenient read with aliases `IN_PROGRESS`→`PROCESSING`, `ERROR`→`FAILED`, unknown → `PENDING` (**accident?** masks bad rows) |
| `attemptCount` | int | `attempt_count` | |
| `lastError` | string, optional | `last_error` | |
| `attempts` | list of [Attempt](#12-attempt) | — | **never hydrated on the job** (Go never loads them into the entity; the detail response therefore never carries `attempts`); read via the attempts route |
| `metadata` | list of `{key, value}` | `metadata` (JSONB array) | SDK wire shape is an **array of pairs**, not an object; `NULL`/`[]`/absent → empty list; written back as `[]` when empty |
| `idempotencyKey` | string, optional | `idempotency_key` | |
| `createdAt`, `updatedAt` | timestamps | | `created_at` is the partition key and half the primary key — immutable |
| `scheduledFor` | timestamp, optional | `scheduled_for` | the poller only claims `PENDING` rows whose `scheduled_for` is `NULL` or due |
| `expiresAt` | timestamp, optional | `expires_at` | |
| `lastAttemptAt`, `completedAt` | timestamps, optional | | |
| `durationMillis` | long, optional | `duration_millis` | end-to-end |
| — | | `queued_at`, `projected_at` | scheduler / projector bookkeeping; **not on the aggregate**, never written by this unit (the upsert does not list them, so they survive a persist) |

### 1.2 `Attempt` — `msg_dispatch_job_attempts`

Partitioned by `created_at`, PK `(id, created_at)`, unique
`(dispatch_job_id, attempt_number, created_at)`. Read-only in this unit
(recorded by the processing endpoint).

| Field | Column | Notes |
|---|---|---|
| `attemptNumber` | `attempt_number` | 1-based; `NULL` reads as 0 |
| `attemptedAt` | `attempted_at` | `NULL` reads as the epoch (**accident** — the column is nullable but the wire field is required) |
| `completedAt` | `completed_at` | optional |
| `durationMillis` | `duration_millis` | optional |
| `responseCode` | `response_code` | optional |
| `responseBody` | `response_body` | optional |
| `success` | `status` | **derived**: `status = 'SUCCESS'` → true, anything else (incl. `NULL`) → false; the wire never shows the string |
| `errorMessage` | `error_message` | optional |
| `errorType` | `error_type` | `CONNECTION` \| `TIMEOUT` \| `HTTP_ERROR` \| `VALIDATION` \| `UNKNOWN`; optional; lenient read: unknown non-null → `UNKNOWN` |
| — | `error_stack_trace`, `id`, `created_at` | not exposed |

Ordered `attempt_number ASC` on read.

### 1.3 `DispatchJobProjection` — `msg_dispatch_jobs_read`

The slim, indexed copy the list / by-event / facet reads use (the write
table carries only transactional indexes). Maintained by the stream
projector from `msg_dispatch_job_projection_feed` — **not by this unit**.
Same columns as the job minus `payload`, `payload_content_type`,
`data_only`, `schema_id`, `metadata`, `queued_at`; plus `is_completed`,
`is_terminal`, `application`, `subdomain`, `aggregate` (split parts of
`code`), `projected_at`. The projection record carries what the
`DispatchJobRead` wire shape needs:

`id, externalId, source, kind, code, subject, eventId, correlationId,
targetUrl, protocol, serviceAccountId, clientId, subscriptionId,
dispatchPoolId, mode, messageGroup, sequence, timeoutSeconds, status,
maxRetries, retryStrategy, scheduledFor, expiresAt, attemptCount,
lastAttemptAt, completedAt, durationMillis, lastError, idempotencyKey,
createdAt, updatedAt`.

`application / subdomain / aggregate` on the **wire** are derived from
`code` (`CodeFacets.of(code)`: segments 1–3 split on `:`, an empty or
missing segment is absent), while the **filters** `applications /
subdomains / aggregates` match the projection's real columns. The two
agree whenever the projector wrote `split_part(code, ':', n)`; the
derivation is what the SPA sees. **load-bearing or accident?** (Go derives
on the wire and filters on the columns.)

`CodeFacets` table (pinned by test):

| `code` | application | subdomain | aggregate |
|---|---|---|---|
| `orders:fulfillment:shipment:shipped` | `orders` | `fulfillment` | `shipment` |
| `orders:fulfillment` | `orders` | `fulfillment` | — |
| `orders` | `orders` | — | — |
| `:fulfillment:shipment` | — | `fulfillment` | `shipment` |
| `` (empty) | — | — | — |

## 2. State machine

| Status | Meaning | Terminal |
|---|---|---|
| `PENDING` | eligible for the poller once `scheduled_for` is `NULL` or due | no |
| `QUEUED` | claimed by the poller and published to the broker | no |
| `PROCESSING` | the processing endpoint is delivering it | no |
| `COMPLETED` | delivered (2xx) | yes |
| `FAILED` | retries exhausted (or `ERROR` legacy alias) | yes |
| `CANCELLED` | operator-cancelled (no route today) | yes |
| `EXPIRED` | past `expires_at` (no writer today) | yes |

Transitions and their **owners**:

| Transition | Owner | Effect on the row |
|---|---|---|
| create → `PENDING` | ingest / fan-out (later unit) | insert `ON CONFLICT (id, created_at) DO NOTHING` |
| `PENDING` → `QUEUED` | scheduler poller | `status='QUEUED'`, `updated_at=now()` (+`queued_at`) |
| `QUEUED` → `PENDING` (stale / publish failed) | scheduler stale-recovery / dispatcher | `status='PENDING'`, `updated_at=now()` |
| `QUEUED` → `PROCESSING` | processing endpoint (`markInProgress`) | `status='PROCESSING'`, `last_attempt_at=updated_at=now()` |
| `PROCESSING` → `COMPLETED` | processing (`markCompleted`) | `completed_at`, `duration_millis`, `updated_at` |
| `PROCESSING` → `FAILED` | processing (`markFailed`, `attempt ≥ max_retries`) | `completed_at`, `duration_millis`, `last_error`, `updated_at` |
| `PROCESSING` → `PENDING` (retry) | processing (`scheduleRetry`) | `attempt_count+1`, `scheduled_for=now+backoff`, `last_error`, `last_attempt_at=now()` |
| `PROCESSING`/`QUEUED` → `PENDING` (defer, no budget) | processing (`reschedule`) | `scheduled_for`, `updated_at` only |
| **any → `PENDING` (requeue)** | **this unit — [RequeueDispatchJobs](#6-operations)** | `status='PENDING'`, `scheduled_for=NULL`, `attempt_count=0`, `completed_at=NULL`, `duration_millis=NULL`, `last_error=NULL`, `updated_at=now()` |

`requeue()` has **no precondition**: a `COMPLETED`, `PROCESSING` or even
`QUEUED` job is reset the same way (Go's `UPDATE … WHERE id = ANY($1)`).
**load-bearing or accident?** (Requeueing a `PROCESSING` job races the
in-flight delivery; requeueing `COMPLETED` re-delivers.) Kept; the
transition is intent-named and total.

`isTerminal()` = `COMPLETED | FAILED | CANCELLED | EXPIRED`.

### 2.1 Review → resolve → re-queue (router spec §13 Q1 ruling)

The router ruling says a failed head-of-group is **reviewed by a human**,
who resolves it as *ignore*, *completed* or *resend*, after which the
> **Go drift 2026-08-27 (`5762aa1`), for whoever ports the scheduler:**
> `BLOCK_ON_ERROR` stopped a group for a `FAILED` sibling **and nothing else**.
> A job that failed transiently and is sitting out a retry backoff is
> `PENDING` with a future `scheduled_for` — excluded from the claim query by
> its own `scheduled_for`, and not `FAILED` — so nothing treated it as holding
> anything. Its successors were claimed and delivered while it waited, then it
> rejoined afterwards: precisely the reordering the mode exists to prevent,
> in the exact circumstances it exists for. A job is now held while an
> **earlier** job in its group is holding it up, where holding means
> `FAILED`/`ERROR` **or backed-off**, and the comparison had to become
> **positional** rather than set membership ("this group contains a held job"
> would catch the held job itself). The Java scheduler is unported, so this is
> a note to implement rather than a defect to fix.

**group goes back onto the queue** (the scheduler's blocked-group hold-back
releases once no `FAILED`/`ERROR` sibling remains in the group; the
processing endpoint's delivery-time check uses the same predicate —
`GroupBlocked`). How today's surface maps onto those three verbs,
**without inventing routes**:

| Reviewer verb | Endpoint today | Effect on the failed job | Group released? |
|---|---|---|---|
| **resend** | `POST /api/dispatch-jobs/requeue` `{ids:[failedId]}` | `FAILED → PENDING`, full retry budget, delivered again in order before its siblings (same `message_group`, its `sequence`) | yes — no `FAILED` sibling remains |
| **ignore** | *no route* — the closest is `requeue` of the **siblings only** (the failed job stays `FAILED` and keeps blocking) → not expressible today | would need `FAILED → CANCELLED` | would release |
| **completed** | *no route* | would need `FAILED → COMPLETED` (manual completion) | would release |

**Owner decision needed (open question 1):** *ignore* and *completed*
require two new admin operations (`CancelDispatchJob`: `FAILED → CANCELLED`;
`CompleteDispatchJob`: `FAILED → COMPLETED`, manual) and lockfile routes
that do not exist yet. This unit ships only *resend* (= requeue), with the
aggregate's status model and `isTerminal` ready for the other two. Until
ruled, a `BLOCK_ON_ERROR` group whose head should be ignored can only be
unblocked by re-sending the head.

## 3. HTTP surface (lockfile)

All routes require a bearer; every error is the `ErrorModel` envelope.
Unauthenticated → 403 `UNAUTHENTICATED`; missing permission → 403
`PERMISSION_REQUIRED`. Gates: **view** = `platform:messaging:dispatch-job:view`,
**view-raw** = `platform:messaging:dispatch-job:view-raw` (anchors always
pass). The literal segments (`list-raw`, `raw`, `filter-options`, `event/…`,
`by-event/…`, `requeue`) are registered before `{id}`.

| Method / path | Gate | Inputs | Success (200) | Notes |
|---|---|---|---|---|
| `GET /api/dispatch-jobs` | view | §4 list query | **bare JSON array** of `DispatchJobRead` | the SPA binds the array directly — no `{items}` envelope, no pagination metadata |
| `GET /api/dispatch-jobs/list-raw` | view-raw | same | same | same rows, raw gate — **accident?** ("raw" returns the projection shape, not the raw row) |
| `GET /api/dispatch-jobs/raw` | view-raw | same | same | SDK alias of `list-raw` |
| `GET /api/dispatch-jobs/filter-options` | view | — | `DispatchJobFilterOptionsResponse` | six facets, ≤ 200 distinct values each, ascending, **not tenant-scoped** (**accident?** a client viewer sees other tenants' client ids / codes) |
| `GET /api/dispatch-jobs/event/{eventId}` | view | path | bare array of `DispatchJobRead` | newest first; rows filtered by `canAccessScope` (platform-scoped jobs visible to anchor / super-admin only) |
| `GET /api/dispatch-jobs/by-event/{eventId}` | view | path | same | SDK alias |
| `GET /api/dispatch-jobs/{id}` | view | path | `DispatchJobResponse` | 404 `DispatchJob_NOT_FOUND`; 403 `SCOPE_FORBIDDEN` when the caller cannot access the job's client (platform-scoped → anchor / super-admin) |
| `GET /api/dispatch-jobs/{id}/raw` | view-raw | path | `DispatchJobResponse` | identical body to `{id}` — **accident?** |
| `GET /api/dispatch-jobs/{id}/attempts` | view | path | bare array of `AttemptDTO` | the job is loaded first for 404 + scope; attempts oldest first |
| `POST /api/dispatch-jobs/requeue` | **view** | `RequeueRequest` `{ids[]}` | `RequeueResponse` `{requeued}` | a caller who can see a job may re-drive it — **load-bearing or accident?** (a write gated by a view permission) |

Wire shapes (field order as listed; optional fields omitted when absent;
timestamps RFC 3339, 6 fractional digits, `Z`):

| Schema | Fields | Notes |
|---|---|---|
| `DispatchJobRead` | `id, eventId?, subscriptionId?, clientId?, application?, subdomain?, aggregate?, code, source?, subject?, status, kind, targetUrl, mode, dispatchMode, correlationId?, scheduledFor?, createdAt, updatedAt, completedAt?, lastAttemptAt?, attemptCount` | `dispatchMode` always equals `mode` (legacy duplicate); `clientIdentifier` and `priority` exist in the schema but are **never emitted** |
| `DispatchJobResponse` | `id, externalId?, kind, code, source?, subject?, targetUrl, protocol, payload?, payloadContentType, dataOnly, eventId?, correlationId?, clientId?, subscriptionId?, serviceAccountId?, dispatchPoolId?, messageGroup?, mode, sequence, timeoutSeconds, schemaId?, maxRetries, retryStrategy, status, attemptCount, lastError?, attempts?, metadata?, idempotencyKey?, createdAt, updatedAt, scheduledFor?, expiresAt?, lastAttemptAt?, completedAt?, durationMillis?` | `attempts` and `metadata` are **omitted when empty** (never `[]`); `attempts` is therefore never present today (§1.1) |
| `AttemptDTO` | `attemptNumber, attemptedAt, completedAt?, durationMillis?, responseCode?, responseBody?, success, errorMessage?, errorType?` | |
| `MetadataDTO` | `key, value` | |
| `RequeueRequest` | `ids[]` | required |
| `RequeueResponse` | `requeued` (int64) | rows actually reset |
| `DispatchJobFilterOptionsResponse` | `statuses[], codes[], clientIds[], dispatchPoolIds[], subscriptionIds[], kinds[]` | always present, possibly empty |

## 4. List query → filter (the three list routes)

| Query | Filter | Notes |
|---|---|---|
| `status` | `status =` | |
| `statuses` (CSV) | `status IN` | CSV = split on `,`, trimmed, blanks dropped |
| `clientId` | `client_id =` | |
| `clientIds` (CSV) | `client_id IN` | |
| `dispatchPoolId` | `dispatch_pool_id =` | |
| `subscriptionId` | `subscription_id =` | |
| `code` | `code =` | |
| `codes` (CSV) | `code IN` | |
| `source` | `source =` | documented as "free-text" but is an equality — **accident?** |
| `applications` / `subdomains` / `aggregates` (CSV) | `application IN` / `subdomain IN` / `aggregate IN` | projection columns |
| `since` / `until` | `created_at >=` / `<=` | RFC 3339; an **unparseable value is ignored** (no filter, no error) — **accident?** |
| `sort` | `createdAt.asc` → ascending; anything else → `created_at DESC` | |
| `limit`, `size` | row cap; `size` wins when > 0 | `<= 0` or `> 1000` → **100**; non-integer → 400 `VALIDATION` |
| `offset` | `OFFSET` when > 0 | non-integer → 400 `VALIDATION` |

A non-integer `limit` / `offset` / `size` is the shared `QueryParams`
400 `VALIDATION` `validation failed` envelope with one
`details.errors[] = {message: "invalid integer", location: "query.<name>", value}`
entry per bad parameter, in `limit, offset, size` order (the events list's
shape).

Absent or empty query parameters filter nothing. Ordering is by
`created_at` only (ties unordered — **accident?**; the audit list orders by
`(performed_at, id)`).

**Tenant scoping is SQL-side** (spec'd by the Go repository test): for a
non-anchor caller the filter additionally requires
`client_id IS NULL OR client_id IN (<caller's clients>)`, so the caller's
own `clientId`/`clientIds` can only narrow within its tenants. Anchors are
unscoped. A non-anchor with no clients sees platform-scoped rows only.
The scope is the platform's one `Visibility` value (`shared/auth`, sealed:
`Everything | Tenants(ids)`), built by `AuthContext.visibility()` and rendered
by the one SQL predicate `VisibilitySql.toCondition` (`shared/database`) —
never a nullable list; the repository's `ListFilter` requires it (the same
type scopes the events list, `event.md` §8).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | coarse gate (§3) |
| Lists | SQL-side `Visibility` from the caller (`AuthContext.visibility()`, §4); by-event: in-memory `Checks.canAccessScope` per row |
| Detail / raw / attempts | handler: 404 then `Checks.checkScopeAccess(caller, job.clientId)` |
| Requeue — execute phase | `publicAccess` in `authorize`; per row, jobs the caller cannot access (`!Checks.canAccessScope`) are **silently skipped and not counted** (§6). Deviation from Go in one corner: Go scopes with `client_id = ANY(caller.clients)` which also drops platform-scoped (`NULL`) jobs for a **non-anchor super-admin**; `canAccessScope` lets a super-admin requeue them. **Owner: keep the platform's one scope predicate (this), or Go's?** |

No `Checks.require*` inside `operations/`.

## 6. Operations

One operation: **`RequeueDispatchJobs`**, command **`RequeueCommand(ids)`**
(Go has no use case here — the handler ran the SQL directly and wrote no
event or audit; this port lifts the action into the envelope, as the Go
package doc already intended for "human-initiated actions").

| Phase | Rule | Error |
|---|---|---|
| validate | `ids` present (may be empty) | 400 `IDS_REQUIRED` `ids is required` |
| authorize | `publicAccess` — per-row scope in execute | |
| execute | ids de-duplicated in order; load from the write table; for each row the caller can access (§5): `job.requeue()`; `Plan.sync(saves, no deletes, rollup)`; unknown ids and inaccessible rows are skipped silently; the rollup's `requeued` = rows written | |

Empty `ids` → no row touched, `requeued = 0`; the rollup event + audit row
are still written (Go short-circuited before the DB — **deviation**, a
no-op request is still an audited operator action).

### 6.1 Events (`operations/DispatchJobEvents`)

Source `platform:admin`, spec version `1.0`, `data` omits null fields.

| Type | Subject | `data` |
|---|---|---|
| `platform:admin:dispatchjob:requeued` (per row) | `platform.dispatchjob.{id}` | `dispatchJobId, code, previousStatus, messageGroup?, clientId?` |
| `platform:admin:dispatchjobs:requeued` (rollup) | `platform.dispatchjobs.{batchId}` — a 13-char TSID minted per request, since the batch has no natural key and `aud_logs.entity_id` is `varchar(17)` (**owner: or a constant `requeue` segment so one audit entity lists every batch?**) | `batchId, dispatchJobIds[], requeued` |

Each per-row event writes one `msg_events` row and one `aud_logs` row
(`entity_type = Dispatchjob`, `entity_id = {id}`, `operation = RequeueCommand`);
the rollup writes one of each (`entity_type = Dispatchjobs`,
`entity_id = {batchId}`), all in the transaction that resets the rows.

## 7. Conflicts and not-found

| Where | Condition | Code | Status |
|---|---|---|---|
| `GET {id}`, `{id}/raw`, `{id}/attempts` | no job with that id | `DispatchJob_NOT_FOUND` | 404 |
| same | job not in caller's scope | `SCOPE_FORBIDDEN` | 403 |
| requeue | unknown / inaccessible id | — (skipped) | 200 |

## 8. Constants

| Constant | Value | Where | Load-bearing or accident? |
|---|---|---|---|
| list row cap fallback | 100 (when `<= 0` or `> 1000`) | `findWithFilters` | guard family shared with events — keep |
| list row cap max | 1000 | same | keep |
| facet limit | 200 per facet (repo fallback 200 when `<= 0` or `> 1000`) | `filter-options` | keep |
| `payload_content_type` default | `application/json` | read | keep |
| `retry_strategy` default | `exponential` | read | keep |
| `sequence` default | 99 | DB default | scheduler's; not applied here |

## 9. Persistence (this unit)

- **Detail read** (`findById`): write table, `WHERE id = ?` — probes every
  partition (no `created_at` known); acceptable for a by-id GET. Does not
  hydrate attempts (§1.1).
- **Bulk load for requeue** (`findByIds`): write table, `WHERE id IN (…)`.
- **List / by-event / facets**: projection only.
- **Attempts** (`attemptsByJob`): `WHERE dispatch_job_id = ? ORDER BY attempt_number`.
- **`persist`**: upsert `INSERT … ON CONFLICT (id, created_at) DO UPDATE`
  listing every aggregate column once; `id`, `created_at` insert-only;
  `updated_at` stamped `now()` at persist time; `metadata` written as a JSON
  array (`[]` when empty); `queued_at` / `projected_at` untouched. Stamping
  `updated_at` marks the row dirty for the projector (`idx_msg_dispatch_jobs_dirty`:
  `projected_at IS NULL OR updated_at > projected_at`), so a requeue reaches
  the projection without this unit touching `msg_dispatch_jobs_read`.
- **`delete`**: `WHERE id = ? AND created_at = ?` (partition-pruned); no
  operation uses it today.
- **Row shape for tests (no writer exists yet):** a write row needs
  `id` (13 chars), `code`, `target_url`, `created_at` (inside an existing
  monthly partition — the baseline creates `now()-1 … now()+3` months) and
  `updated_at`; every other column has a default or is nullable. A projection
  row additionally needs `kind`, `protocol`, `mode`, `status`, `max_retries`,
  `updated_at` (fewer defaults). An attempt row needs `id`, `dispatch_job_id`,
  `created_at`. The test fixture inserts all three directly with jOOQ.
- **JSONB is a foreign shape**: the repository test pins `metadata` read
  from `NULL`, `[]`, a pair array, an object-keyed legacy shape (reads as
  empty rather than failing — **accident?**) and a pair missing its `value`
  (the pair is dropped; the SDK contract makes both strings required).

## 10. Repository methods owned by other units (do NOT implement here)

| Go method | Owner | Contract the later unit must honour |
|---|---|---|
| `Insert` / `InsertBatch` | ingest (`POST /api/dispatch-jobs/batch`, `dispatch_job_create`), stream fan-out | `ON CONFLICT (id, created_at) DO NOTHING`; `metadata` `[]` when empty; `created_at` defaults to now |
| `MarkInProgress(id, createdAt)` | processing | `status='PROCESSING'`, `last_attempt_at = updated_at = now()` |
| `MarkCompleted(id, createdAt, durationMillis)` | processing | `status='COMPLETED'`, `completed_at = updated_at = now()`, `duration_millis` |
| `MarkFailed(id, createdAt, lastError, durationMillis)` | processing | `status='FAILED'`, `completed_at`, `duration_millis`, `last_error`, `updated_at` (§2) |
| `ScheduleRetry(id, createdAt, scheduledFor, lastError)` | processing | `attempt_count+1`, `scheduled_for`, `last_error`, `last_attempt_at=now()`, `status='PENDING'` |
| `Reschedule(id, createdAt, scheduledFor)` | processing (ack=false / 429 / blocked group) | `status='PENDING'`, `scheduled_for`, `updated_at`, **no** attempt bump (§2) |
| `GroupBlocked(group)` | processing + scheduler poller | `EXISTS … WHERE message_group = ? AND status IN ('FAILED','ERROR')` |
| `RecordAttempt(jobId, attempt)` | processing | untyped TSID id; `status` `SUCCESS`/`FAILURE` from the boolean |
| `FindRecentRaw(limit)` | `/bff/debug/dispatch-jobs` | write table, newest first, cap 1000 → 100 |
| poller claim `PENDING → QUEUED` (`FOR UPDATE SKIP LOCKED`), stale `QUEUED → PENDING` | scheduler | plain-SQL text blocks are allowed for the claim |

All status flips carry `created_at` with `id` for partition pruning.

What this table deliberately leaves to the data-plane spec (it is a column
contract, not a behaviour spec): whether each flip is guarded by the current
status (`… WHERE status = 'QUEUED'`) or unconditional; the poller's claim
query (batch size, `ORDER BY`, the `scheduled_for IS NULL OR <= now()`
predicate, the blocked-group hold-back, `LIMIT`); the stale-`QUEUED`
threshold; `RecordAttempt`'s column list (incl. `error_stack_trace`); and
whether `MarkCompleted` / `MarkFailed` are idempotent on a re-delivered
callback. Each is a timing/guard constant the data-plane spec must table
with "load-bearing or accident?".

## 11. Open questions for the owner (summary)

1. **Ignore / completed verbs (§2.1):** add `CancelDispatchJob`
   (`FAILED → CANCELLED`) and `CompleteDispatchJob` (`FAILED → COMPLETED`)
   with new lockfile routes? This unit ships resend (= requeue) only.
2. `requeue()` is total — also resets `PROCESSING` / `QUEUED` / `COMPLETED`
   jobs. Add a precondition (terminal or `PENDING` only)?
3. Requeue is gated by the **view** permission and silently skips
   inaccessible / unknown ids (reports a count). Keep, or 403 / 404?
4. Requeue scope predicate: platform `canAccessScope` (super-admin may
   requeue platform-scoped jobs) vs Go's `client_id = ANY(clients)`.
5. Rollup subject segment: a minted `{batchId}` vs a constant.
6. Empty `ids` still writes the rollup event + audit.
7. `filter-options` facets are not tenant-scoped.
8. `since`/`until` parse failures are ignored; `source` is equality not
   free-text; list order has no tie-breaker.
9. `list-raw` / `{id}/raw` return the same shapes as their non-raw twins
   (only the gate differs).
10. `attempts` is never present on `DispatchJobResponse` (not hydrated);
    `clientIdentifier` / `priority` never emitted on `DispatchJobRead`.
11. Lenient enum reads (`status` → `PENDING`, `protocol` ignored, legacy
    object-shaped `metadata` → empty) mask bad rows.
12. `DispatchMode` is imported from `platform.subscription` until the
    shared `messaging` package exists.
