# SDK ingest — events, dispatch jobs, audit logs (batch + singular)

Extracted 2026-09-05 against Go HEAD (`9f7be62`) from
`internal/platform/event/api/api.go` (`create`, `batchIngest`),
`internal/platform/shared/sdk/{dispatch_jobs_batch,dispatch_job_create,audit_batch}.go`,
the repositories' `InsertBatch`, `internal/platform/shared/auth/auth.go`
(`requirePermission`), and the Go tests `create_event_pg_test.go`,
`dispatch_job_create_pg_test.go`. These are the write paths a consumer
application's SDK outbox processor POSTs to. Go's own comment: infrastructure
processing paths — **no unit of work, no domain event, no audit row**; direct
batch inserts. `[C]` contract, `[I]` implementation detail, `[D]` Go defect.

## 1. Purpose and boundaries

Six routes, one Java unit (`io.flowcatalyst.platform.ingest`):

| Route | Lockfile | Go home | Permission |
|---|---|---|---|
| `POST /api/events` | yes (`createEvent`, 201 `CreateEventResponse`) | event/api | `platform:messaging:batch:events-write` |
| `POST /api/events/batch` | yes (`batchIngestEvents`, 201 `BatchResponse`) | event/api | same |
| `POST /api/dispatch-jobs` | **no** | shared/sdk | see §5 D1 |
| `POST /api/dispatch-jobs/batch` | **no** | shared/sdk | see §5 D1 |
| `POST /api/audit-logs/batch` | **no** | shared/sdk | any authenticated principal |

`GET /api/events*`, `GET /api/dispatch-jobs*`, `GET /api/audit-logs*` are
the read surfaces already ported in their own units; this unit adds only
the writes. Routes marked **no** are outside the lockfile: the contract for
those is this document plus the Java SDK (`sdk/` module) that calls them —
the port must check what the SDK sends and keep both sides agreeing.
Anchor callers bypass every permission check (`requirePermission`: anchor
OR permission).

## 2. Common batch shape [C]

Request `{ "items": [ … ] }`. Response `{ "results": [ {id, status, error?} ] }`,
**one result per input item, in input order**; `status` is one of
`SUCCESS` | `SKIPPED` (audit only, §4.3) | `BAD_REQUEST` (events and
audit, per item, with `error` naming the reason). Empty `items` → 200 (events:
201 — Go's huma default) with `{"results": []}` and nothing touched.
Malformed JSON → 400 `INVALID_JSON`. Over the limit → 400 `BATCH_TOO_LARGE`
("max 1000 items per batch" for events and dispatch jobs, "Maximum 100
items per batch" for audit). **Owner ruling 2026-09-06 #10a (Go `ece54fe`,
Java the same night): partial success with honest per-item results** — an
event item missing `type`, `source` or `data` reports
`{"id": <its id or "">, "status": "BAD_REQUEST", "error": "<field> is required"}`
in its own slot and the valid items are still written (one insert, all of
them or none); an audit item without `principalId` reports
`{"id": "", "status": "BAD_REQUEST", "error": "principalId is required"}`
(#10b: never defaulted to the caller). A tenant failure on any event item
still rejects the whole batch (403) before anything is written; dispatch
jobs keep whole-batch rejection on validation too (Go unchanged there).
Senders (the outbox dispatcher, the SDK pollers) must read `results[]`
per item: a batch is no longer one outcome.

## 3. Events [C]

### 3.1 `POST /api/events/batch` — `BatchEventItem`
`{id?, type, source, subject?, specVersion?, data, deduplicationId?, correlationId?, causationId?, messageGroup?, clientId?, clientCode?, contextData?:[{key,value}]}`
(all optional on the wire; `type`, `source` and `data` are required per
item — a missing one is that item's `BAD_REQUEST` slot, §2).
- `id` absent → server mints an event TSID. Supplied id is stored as given
  (SDK-side idempotency).
- `clientId` wins; else `clientCode` is resolved through
  `client.FindByIdentifier` **once per distinct code per batch** (memoised);
  an unknown code → `clientId` null (the row is still written, unscoped —
  §5 D2). Tenant guard **per item**: a resolved client the caller cannot
  access → 403 `No access to client: <id>` for the whole batch.
- Non-anchor caller with no `clientId`/`clientCode` and ≥1 accessible
  client (singular route only): defaults to the caller's first client.
  Batch items do not default (§5 D3).
- Insert: `INSERT … ON CONFLICT DO NOTHING` — the conflict target is the
  unique index `(deduplication_id, created_at)`, so a repeated
  `deduplicationId` is silently dropped **and still reported `SUCCESS`
  with the id the caller sent or the freshly minted one** (§5 D4).
- Every persisted item → `{id, "SUCCESS"}`; 201.

### 3.2 `POST /api/events` — `CreateEventRequest`
`{eventType, source, data (required), subject?, specVersion?, deduplicationId?, correlationId?, causationId?, messageGroup?, clientId?, contextData?}`.
Same insert path as a batch of one (pinned in Go by
`TestCreateEvent_PersistsAndMatchesBatchOfOne`, `…ContextDataPersisted`,
`…ClientDefaultingAndTenantGuard`, `…BadPayloadEnvelopeMatchesBatch`).
Response 201 `CreateEventResponse{event: CreatedEvent, dispatchJobCount: 0, isDuplicate: false}`
— **both scalar fields are hard-coded in Go** (§5 D4): no fan-out is done
here (the stream processor creates dispatch jobs later) and duplicate
detection is not surfaced.

## 4. Dispatch jobs and audit logs

### 4.1 `POST /api/dispatch-jobs/batch` — `BatchItem`
`{id?, externalId?, kind?, code, source?, subject?, targetUrl, payload?, payloadContentType?, dataOnly, eventId?, correlationId?, clientId?, subscriptionId?, serviceAccountId?, dispatchPoolId?, messageGroup?, mode?, sequence?, timeoutSeconds?, maxRetries?, metadata?:[{key,value}]}`.
- Defaults applied in the domain: `kind` absent → `EVENT`; a non-empty
  unrecognised `kind` → 400 `INVALID_KIND` "unknown dispatch job kind %q;
  must be EVENT or TASK" (X-06 at the write boundary); `mode` via the
  shared `DispatchMode` parse (unspecified → `NEXT_ON_ERROR`, ledger X-01);
  `payloadContentType` empty → `application/json`; `sequence` 0 → 99;
  `timeoutSeconds` 0 → 30; `maxRetries` 0 → 3; `protocol` =
  `HTTP_WEBHOOK`; `retryStrategy` = `EXPONENTIAL_BACKOFF`; `status` =
  `PENDING`. `id` absent → **13-char untyped TSID** (`msg_dispatch_jobs.id`
  is `VARCHAR(13)`); supplied → stored as given.
- Tenant guard per item: `clientId` set and not accessible → 403 whole batch.
- Insert: one `INSERT … ON CONFLICT (id, created_at) DO NOTHING` per job in
  a single pgx batch (the table is partitioned on `created_at`). A repeated
  SDK-supplied id is dropped silently and still reported `SUCCESS` (§5 D4).
- 201 with one `{id, "SUCCESS"}` per item.

### 4.2 `POST /api/dispatch-jobs` (singular)
Same mapping as a batch of one (Go `TestCreateDispatchJob_PersistsAndMatchesBatchOfOne`),
plus singular-only fields the tests name (`TestCreateDispatchJob_SingularOnlyFields`,
`…InvalidRetryStrategyRejected`): the port reads `dispatch_job_create.go` for
the exact extra fields (`retryStrategy` is validated there and rejected
when unrecognised) and pins them the same way.

### 4.3 `POST /api/audit-logs/batch` — `AuditBatchItem`
`{entityType, entityId, operation, operationData?, principalId?, performedAt?, applicationCode?, clientCode?}`; limit **100**.
Authentication only (any principal). Per item, **`SKIPPED` instead of
rejection**: unknown `applicationCode` → `{id:"", status:"SKIPPED"}`;
unknown `clientCode` → SKIPPED; resolved client not accessible to the
caller → SKIPPED. Codes are memoised per batch. `performedAt` parsed as
RFC 3339, **any parse failure silently falls back to now** (§5 D5);
absent → now. `principalId` absent → the caller's principal. Rows go into
`aud_logs` via `InsertBatch`; each written item → `{id, "SUCCESS"}`; 200.

## 5. Defects and questions for the owner

- **D1 [D] Dispatch-job ingest is unusable by non-anchors in Go.** Both
  dispatch routes check `CanWritePermission(ac, "WRITE_DISPATCH_JOBS")`, a
  permission string that no role in the seeded catalogue grants (the seeded
  one is `platform:messaging:batch:dispatch-jobs-write`); `requirePermission`
  has no alias table. Every non-anchor SDK service account gets 403
  `PERMISSION_REQUIRED`. Java checks the seeded
  `platform:messaging:batch:dispatch-jobs-write` (`Permission.BATCH_DISPATCH_JOBS_WRITE`) —
  **deliberate deviation**; Go needs the one-line fix.
- **D2** An event item with an unknown `clientCode` is written unscoped
  (`client_id` null) and reported `SUCCESS`. Audit does the opposite
  (SKIPPED). Which rule?
- **D3** Singular event create defaults a non-anchor's client; batch items
  do not. Intended?
- **D4** Duplicates (`deduplicationId` for events, supplied `id` for
  dispatch jobs) are dropped by `ON CONFLICT DO NOTHING` but reported
  `SUCCESS`, and `CreateEventResponse.isDuplicate` is hard-coded `false`
  while the lockfile promises it. The SDK outbox therefore cannot tell a
  replayed item from a new one. Java keeps the wire values (the lockfile
  pins them) but pins the *persistence* fact: a repeated key writes no
  second row. Report the truth on the wire?
- **D5** A malformed `performedAt` is silently replaced by now.
  Reject with 400 instead?
- **D6 [D] Event deduplication never fires across requests.** The unique
  index behind `ON CONFLICT DO NOTHING` is `(deduplication_id, created_at)`
  (it must include the partition key), and `created_at` is stamped at
  insert, so a replayed item with the same `deduplicationId` and a fresh
  `created_at` inserts a second row. The "idempotent via deduplication_id"
  promise in Go's repository comment holds only within one `created_at`
  microsecond. Java matches Go (the test pins one row only when the two
  inserts share a `created_at`). Real fix: dedupe on `(deduplication_id)`
  within a bounded window, or carry the original `created_at` on replay.
- **Q1** `dispatchJobCount` is always 0 on the singular create. Remove
  from the lockfile, or compute?

## 6. Deliberate deviations

- D1: the seeded dispatch-jobs-write permission instead of the unknown string.

## 7. Tests the port must have (load-bearing → mutation-checked)

`IngestApiTest` via `TestHttp` with test headers — for each of the five
routes: happy path writes the rows and returns one result per item in
order; the size limit (1000 / 1000 / 100) answers `BATCH_TOO_LARGE` and
writes nothing; empty `items` writes nothing; a tenant violation on item
*k* of *n* writes **none of the n** (count the rows); the permission gate
(`events-write`, `dispatch-jobs-write`; audit: unauthenticated → 401, any
authenticated → allowed); the defaults table of §4.1 asserted on the
stored row; `INVALID_KIND`; a repeated `deduplicationId` / supplied job id
writes exactly one row (count) and still answers `SUCCESS`; audit's three
`SKIPPED` cases with `id:""` in the right positions; `clientCode`
memoisation (a fake/spy repository counts lookups: two items with one
code → one lookup); the singular event and dispatch-job creates persisting
identically to a batch of one (compare the stored rows column by column);
the Java SDK's outbox request/response records deserialising this exact
shape (`sdk/` module).
