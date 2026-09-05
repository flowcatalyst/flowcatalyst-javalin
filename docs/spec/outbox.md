# Outbox processor — the consumer-side dispatcher into the platform ingest routes

Extracted 2026-09-05 against Go HEAD (`9f7be62`) from `internal/outbox/`
(`processor.go`, `http_dispatcher.go`, `group_distributor.go`,
`group_state.go`, `repository.go`, `admin.go`, `postgres/postgres.go`),
`internal/common/outbox.go`, `internal/server/subsystems.go`
(`StartOutboxProcessor`, `buildOutboxRepo`), `envcfg.go`, and the Go tests
(`TestGroupStateManager`, `TestGroupDistributor*`, `TestProcessor*`,
`TestSend*`, `TestSendBatch*`). Behaviour tables, never code. `[C]`
contract, `[I]` implementation detail, `[D]` Go defect / question.
**Owner ruling 2026-09-05: Postgres backend only; Mongo (and SQLite) stay
on the backlog.**

## 1. Purpose and boundaries [C]

A consumer application writes events, dispatch jobs and audit logs into
its own `outbox_messages` table inside its business transaction (the Java
SDK's `OutboxManager` + `JdbcOutboxDriver`, and `usecase`'s `OutboxSink`,
already do this — same table, same SMALLINT status codes). The outbox
processor is the loop that drains that table and POSTs the items to the
platform's ingest routes (`docs/spec/sdk-ingest.md`), keeping per-group
ordering and blocking a group on a permanent failure. In `fc-server` it is
the `FC_OUTBOX_ENABLED` subsystem, running against `FC_DATABASE_URL`'s
pool (`buildOutboxRepo`, backend `FC_OUTBOX_BACKEND` = `postgres` | `postgresql` | `""`),
leader-gated on its own election (`newLeaderGate(…, "outbox")`), and it
**does not start without `FC_OUTBOX_PLATFORM_URL`** (aliases
`FC_OUTBOX_API_URL`, `FC_API_BASE_URL`, `FLOWCATALYST_URL`) — it logs and
returns. Java: `io.flowcatalyst.outbox`; `Server.java:200` TODO(port).

## 2. Table and statuses [C]

`outbox_messages` (created by `InitSchema` at start, `IF NOT EXISTS`):
`id VARCHAR(26) PK`, `type VARCHAR(20)` (`EVENT` | `DISPATCH_JOB` |
`AUDIT_LOG`), `message_group VARCHAR(255) NULL`, `payload TEXT` (the JSON
item exactly as the ingest route expects it), `status SMALLINT`,
`retry_count SMALLINT`, `created_at`, `updated_at`, `error_message TEXT`,
`client_id VARCHAR(26)`, `payload_size INTEGER`, `headers JSONB`. Partial
indexes `(status, message_group, created_at) WHERE status = 0` and
`(status, created_at) WHERE status = 9`.

| Code | Status | Retryable | Terminal |
|---|---|---|---|
| 0 | PENDING | — | — |
| 1 | SUCCESS | no | yes (row is **deleted**) |
| 2 | BAD_REQUEST | no | yes |
| 3 | INTERNAL_ERROR | yes | no |
| 4 | UNAUTHORIZED | yes | no |
| 5 | FORBIDDEN | no | yes |
| 6 | GATEWAY_ERROR | yes | no |
| 9 | IN_PROGRESS | yes (recovered) | no |

`SKIPPED` from the audit route parses as its own item status and is treated
as success for the row (deleted).

## 3. Repository contract [C]

| Call | Behaviour |
|---|---|
| `claimPending(n)` | one transaction: `SELECT id FROM outbox_messages WHERE status = 0 ORDER BY message_group, created_at LIMIT n FOR UPDATE SKIP LOCKED` → `UPDATE … SET status = 9, updated_at = NOW()` → returns the rows |
| `markSuccess(ids)` | `DELETE … WHERE id = ANY(ids)` |
| `markFailed(ids, status, message, requeue)` | `status = requeue ? 0 : status.code`, `error_message = message`, `retry_count = retry_count + 1`, `updated_at = NOW()`. **No `next_retry_at`**: a requeued row is eligible on the very next poll (§8 D1) |
| `release(ids)` | `status = 0` **only where `status = 9`** (an item claimed but not attempted — inactive group — goes back untouched, no retry counted) |
| `requeue(ids)` | `status = 0, retry_count = 0, error_message = NULL` (the admin "unblock") |
| `recoverStuck(olderThan)` | `status = 0 WHERE status = 9 AND updated_at < now − olderThan`, returns the count |
| `healthy()` | ping |

## 4. The loop [C]

`Config{PollInterval=1 s, BatchSize=100, MaxInFlight=1000, HTTPTimeout=30 s,
MaxRetries=3, RecoveryInterval=60 s, RecoveryThreshold=5 min,
MaxConcurrentGroups=10, BlockOnError=true}`; env `FC_OUTBOX_BATCH_SIZE`,
`FC_OUTBOX_MAX_IN_FLIGHT`, `FC_OUTBOX_POLL_INTERVAL_MS`,
`FC_OUTBOX_MAX_CONCURRENT_GROUPS` (alias `FC_MAX_CONCURRENT_GROUPS`),
`FC_OUTBOX_BLOCK_ON_ERROR` (default **true**), `FC_OUTBOX_ADMIN_PORT`
(0 = no admin listener), `FC_OUTBOX_PLATFORM_AUTH_TOKEN` (aliases
`FC_OUTBOX_TOKEN`, `FC_API_TOKEN`).

Every `PollInterval`, leader only, and only while `inFlight < MaxInFlight`
(back-pressure: skip the tick, do not claim): `claimPending(BatchSize)`,
then per item:
- **grouped** (`message_group` non-empty): if the group is not active
  (paused or blocked) → `release` immediately; else `inFlight++` and hand
  it to the group distributor (§5) with `dispatch = send one item` and
  `onAbort = release`.
- **ungrouped**: collected **per item type** and each type's list is sent
  as **one batch** on its own thread (`inFlight += n`).

Every `RecoveryInterval`, leader only: `recoverStuck(RecoveryThreshold)`,
logged when > 0.

**Outcome handling** (batch and single alike): `SUCCESS` → `markSuccess`
(+ `totalSucceeded`); otherwise `requeue = status.retryable && attemptCount + 1 < MaxRetries`,
`markFailed(status, message, requeue)` (+ `totalFailed`); a batch item with
no per-item result → `INTERNAL_ERROR "no per-item result"`. For a grouped
item whose failure is **not** requeued (terminal, or retries exhausted),
with `BlockOnError`: **block the group** with that item id and message
(§5). `markSuccess`/`markFailed` failures are logged, never thrown.

## 5. Groups: distributor and state [C]

`GroupDistributor(maxConcurrentGroups, blockOnError)`: one FIFO queue per
group; the first submit starts a drainer for that group; drainers acquire
a semaphore of `maxConcurrentGroups` (0 = unbounded) so at most that many
groups deliver at once (`TestGroupDistributorBoundedConcurrency`). A
drainer runs items **strictly in order**; when `dispatch()` returns false
and `blockOnError` is on, the **rest of that group's queue is aborted**
(each `onAbort` → `release`, back to PENDING untouched) and the queue is
dropped (`TestGroupDistributorBlockOnError`); with `blockOnError` off the
drainer just continues (`…NoBlockWhenDisabled`).

`GroupStateManager`: `RUNNING` (absent) | `PAUSED` | `BLOCKED{itemId, error}`.
`isActive` = neither paused nor blocked. `block(group, itemId, err)`;
`pause` (only from running/absent); `resume` (only from paused);
`clearBlock` → the blocked item id. Processor verbs: `pauseGroup`,
`resumeGroup`, `unblockGroup` = clearBlock **and `requeue` the poison item**
(retry count reset), `skipGroup` = clearBlock **without** requeue (the poison
row keeps its terminal status), `groupStates`, `blockedGroups`, `inFlight`,
`totals`. Pinned by `TestProcessorBlocksGroupOnPermanentFailure`,
`TestProcessorRetryableDoesNotBlock`.

## 6. HTTP dispatch [C]

`POST <platformUrl><itemType.apiPath>` — `/api/events/batch`,
`/api/dispatch-jobs/batch`, `/api/audit-logs/batch` — body
`{"items": [<payload>, …]}` (payloads passed through verbatim),
`Content-Type: application/json`, `Authorization: Bearer <token>` from the
`TokenSource` when one is wired (client-credentials, cached; a **401
invalidates the cached token** so the next send re-fetches) else the static
auth token. Timeout `HTTPTimeout`.

| Response | Outcome for every item |
|---|---|
| 2xx, body `{results:[{id,status,error?}]}` with `len == items` | per item, `status` parsed (`SUCCESS`, `SKIPPED`, `BAD_REQUEST`, `INTERNAL_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `GATEWAY_ERROR`); unknown → `INTERNAL_ERROR "unknown item status: …"`; `error` → message |
| 2xx, unparseable body | `INTERNAL_ERROR "parse results: <first 200 chars>"` |
| 2xx, count mismatch | `INTERNAL_ERROR "result count mismatch: got n for m items"` (retryable — `TestSendBatch_CountMismatchFailsAllRetryable`) |
| 401 | `UNAUTHORIZED "401"` (retryable) + token invalidation |
| 403 | `FORBIDDEN "403"` (terminal) |
| 400 | `BAD_REQUEST "400"` (terminal — `TestSend_400IsTerminalBadRequest`) |
| 502 / 503 / 504 | `GATEWAY_ERROR "<code>"` |
| any other status (404, 409, 422, **429**, other 5xx) | `INTERNAL_ERROR "<code>"` (retryable — a transient 429/404 must not block a group, `TestSend_Transient4xxIsRetryable`) |
| transport error | `GATEWAY_ERROR "request: …"`; token fetch error → `GATEWAY_ERROR "auth: …"` |
| marshal error | `BAD_REQUEST "marshal: …"` |

A single grouped item is sent as a batch of one; a missing outcome →
`INTERNAL_ERROR "no outcome for item"`.

## 7. Admin API [C]

Only when `FC_OUTBOX_ADMIN_PORT > 0`, bound to **127.0.0.1** on that port
(not the API listener): `GET /outbox/groups`, `GET /outbox/groups/blocked`,
`POST /outbox/groups/{group}/{pause|resume|unblock|skip}` (unblock/skip
answer 404 when the group is not blocked). JSON `GroupInfo{group, status,
blockedItemId?, error?}`. Java: mount the same paths on a loopback-only
Javalin, or under the router's `/monitoring` prefix — owner's call (§8 Q1);
the spec keeps Go's shape.

## 8. Defects and questions for the owner

- **D1** No backoff: a requeued item is re-claimed on the next 1-second
  poll, so a platform outage is hammered `MaxRetries` times per item within
  seconds, then every item goes terminal (`INTERNAL_ERROR`/`GATEWAY_ERROR`
  with `retry_count = 3`) and, for grouped items, **blocks its group**
  needing manual `unblock`. Go's comment says "there is no next_retry_at
  column upstream". Add a backoff (`next_retry_at`) and treat retry
  exhaustion on transient statuses as "wait", not "terminal"?
- **D2** `MaxInFlight` gates the *claim*, not the group queues: a single
  hot group can hold up to a whole batch in its queue while other groups
  starve behind `MaxConcurrentGroups`. Design choice to confirm.
- **D3** Ungrouped items of the same type go as one batch; one bad item
  (400 from the route's whole-batch validation) fails **all** of them with
  `BAD_REQUEST` — terminal for the innocent ones. The ingest route rejects
  whole batches on any item's validation (`sdk-ingest.md` §2), so this is
  the two halves agreeing; still a sharp edge for operators.
- **D4** A group blocked in memory is forgotten on restart or leader
  change: the poison row stays terminal in the table, the group runs again
  and delivers its later items out of order past the poison. Persist the
  block, or derive it from the table on start?
- **Q1** Where does the admin API live in Java (loopback port as Go, or
  the router prefix)?
- **Q2** Mongo/SQLite backends: on the backlog by ruling; the `Repository`
  contract in §3 is the seam.

## 9. Tests the port must have (load-bearing → mutation-checked)

- **Claim is exclusive and ordered**: two processors claiming concurrently
  over seeded rows never both get the same id (counter per id == 1); claim
  order is `(message_group, created_at)`.
- **Release vs failure**: an item released because its group is inactive
  keeps `retry_count` (a counter that must not change) and returns to
  PENDING; a failed item increments it.
- **Retry ladder**: retryable status with `attemptCount + 1 < MaxRetries`
  → PENDING; at the limit → the terminal code is written; a terminal status
  never requeues regardless of count.
- **Group block**: a permanent failure on a grouped item blocks the group
  and aborts the queued rest back to PENDING untouched; `unblock` requeues
  the poison with `retry_count = 0`; `skip` leaves it terminal; a paused
  group's items are released, not attempted (`dispatch` counter stays 0).
- **Bounded groups**: with `maxConcurrentGroups = 2` and 3 groups
  submitted, at most 2 deliver at once (observed concurrency, gated by a
  latch).
- **Dispatch mapping**: the §6 table, every row, against a stub HTTP server,
  including the count mismatch and the 401 → token invalidation.
- **Back-pressure**: at `MaxInFlight`, a tick claims nothing.
- **Recovery**: a `status = 9` row older than the threshold returns to
  PENDING on the recovery tick; a younger one does not.
- **Leader gate**: a non-leader never claims.
- **Start conditions**: no platform URL → the subsystem logs and does not
  run; `InitSchema` is idempotent.
- **Shutdown**: interrupting stops the loop within one poll interval.
