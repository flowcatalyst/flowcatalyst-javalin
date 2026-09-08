# Dispatch seam — behavioural specification

Semantic extraction (CONVENTIONS.md §8 step 1) of the Go reference
implementation's **dispatch seam**: everything between the platform's
dispatch-job store and the message router, as router-specification.md
§3.4 and §9 define it. This is a specification, not a design — it states
what the Go code does and cites `file:line` for every fact. No Java is
proposed here.

Router-specification citations use the file
`/Users/andrewgraaff/Developer/flowcatalyst-rust/docs/router-specification.md`
(hereafter "router-spec") and the ledger
`/Users/andrewgraaff/Developer/flowcatalyst-rust/docs/owner-questions.md`
(hereafter "ledger"). Go citations are relative to
`/Users/andrewgraaff/Developer/flowcatalyst-go` unless stated otherwise.
Contract facts (part of a wire shape a caller depends on) are marked
**[C]**; internal mechanics (an implementation choice a caller cannot
observe) are marked **[I]**.

---

## 1. Purpose & boundaries

The dispatch seam is the platform's private implementation of "deliver an
event/task to a webhook subscriber," built entirely on top of the
general-purpose router rather than by having the router talk to
subscribers directly. Router-spec §3.4 states the reason: *"For dispatch
jobs specifically, the router never observes delivery failure directly —
its mediation target for a dispatch job is the platform's own processing
endpoint, which records the real outcome and answers `200 {ack:true}`
regardless."* Concretely:

- **`msg_dispatch_jobs`** (`internal/platform/dispatchjob/entity.go:175-217`)
  is the system of record — one row per delivery of one message to one
  target URL. The queue copy is a delivery attempt, never the data
  (`docs/router-architecture.md:127-129`).
- **The scheduler** (`internal/platform/scheduler/**`) polls `PENDING`
  rows, applies pause/hold-back filters, and publishes a queue message
  per claimed row whose `mediationTarget` is the platform's own
  **processing endpoint**, not the subscriber (`dispatcher.go:76-80`).
- **The router** treats that message like any other: it delivers a
  signed `POST {"messageId":"<id>"}` to the processing endpoint
  (router-spec §2.5) and interprets *that* endpoint's `{ack:true}`
  response — never the real subscriber's response, which the router
  never sees.
- **The processing endpoint** (`POST /api/dispatch/process`,
  `internal/platform/dispatchjob/processing/processing.go`) is the
  actual webhook client: it loads the job, delivers to `target_url`,
  records the attempt, and advances `msg_dispatch_jobs.status`. It always
  ACKs the router regardless of the real outcome — retries are entirely
  platform-owned via `scheduled_for`, never via the queue's own
  redelivery (`processing.go:15-18`).
- **The settled endpoint** (`POST /api/dispatch/settled`,
  `internal/platform/dispatchjob/settled/settled.go`) and **the reaper**
  (`internal/platform/dispatchjob/reaper.go`) are the two mechanisms — a
  fast hook and a slow backstop — that recover dispatch-job rows the
  router ACKed off the broker as untried `BLOCK_ON_ERROR` siblings
  (router-spec §3.2, §5.4; ledger A-01).
- **Cancel / Complete / Resend** (`internal/platform/dispatchjob/operations/*.go`)
  are the human-operator recovery verbs the router-spec's review flow
  (§3.2) names: *ignore*, *completed*, *resend*.

Because the router never sees real delivery failure for a dispatch job,
**mode semantics (`NEXT_ON_ERROR`/`BLOCK_ON_ERROR`) are enforced entirely
platform-side**, at two points that must agree with each other: the
scheduler's claim query (§3 below) and the processing endpoint's
delivery-time re-check (§5 below). This is the seam's central invariant
and the reason `GroupHoldingStatusSQL` (§9) exists as a single shared
predicate rather than two independently-written checks.

---

## 2. The published queue message **[C]**

The scheduler publishes `common.Message` (`internal/common/message.go:76-86`),
JSON-tagged camelCase. Every field, as populated for a dispatch job by
`MessageGroupDispatcher.buildMessage` (`internal/platform/scheduler/dispatcher.go:81-105`):

| Field | JSON name | Type | Optional? | Dispatch-job value |
|---|---|---|---|---|
| `ID` | `id` | string | required | the dispatch-job id (`dispatcher.go:84`) |
| `PoolCode` | `poolCode` | string | `omitempty` | resolved client-namespaced pool code — see below (`dispatcher.go:92`, `message.go:78`) |
| `AuthToken` | `authToken` | `*string` | `omitempty` | HMAC-SHA256(jobID) signed with the scheduler's dispatch-auth secret (`dispatcher.go:82,87`; `auth.go:20-25`) |
| `SigningSecret` | `signingSecret` | `*string` | `omitempty` | never set for a dispatch job — always nil (`dispatcher.go:81-99` sets no such field) |
| `MediationType` | `mediationType` | string | required | always `"HTTP"` (`dispatcher.go:85`) |
| `MediationTarget` | `mediationTarget` | string | required | the platform's `/api/dispatch/process` URL (`cfg.ProcessingEndpoint`), **never** `target_url` (`dispatcher.go:86`, `scheduler.go:48-53`) |
| `MessageGroupID` | `messageGroupId` | `*string` | `omitempty` | the job's `message_group`, only when non-empty (`dispatcher.go:100-103`) |
| `HighPriority` | `highPriority` | bool | `omitempty` | never set for a dispatch job (defaults `false`) |
| `DispatchMode` | `dispatchMode` | string | `omitempty` | `common.ParseDispatchMode(tok.Mode)` — see resolution below (`dispatcher.go:98`) |

**`AuthToken`'s double duty.** The same per-job HMAC token that
authenticates the router's `/api/dispatch/process` callback
(router-spec §9, "Per-job signing") is *also* the credential the router
forwards to `/api/dispatch/settled` for each ACKed sibling
(`internal/router/settled.go:43-53`; `dispatchJobFromMessage`,
`settled.go:196-211`, keys off `m.AuthToken != nil`). A message with no
`AuthToken` did not come from the scheduler (an operator submission or a
direct-to-subscriber producer) and is skipped by the settled reporter,
never sent as an unverifiable id.

### `poolCode` composition — router-spec §9 / ledger R-16

Resolved by `PoolCodeResolver.Resolve` at **publish time**
(`poolcode.go:86-110`), never at routing time — router-spec §9: *"the
platform scheduler resolves these from the owning subscription's
configuration at publish time, so the router never needs to know
anything about clients or subscriptions."*

| Job's dispatch pool | Job's client | Published `poolCode` | Evidence |
|---|---|---|---|
| set, pool has an owning client identifier | — | `{clientIdentifier}-{poolCode}` | `poolcode.go:96-101` |
| set, pool is platform-level (no client) | — | `{poolCode}`, no prefix | `poolcode.go:96-101` |
| unset (or unresolvable — deleted pool) | resolves | `{clientIdentifier}-DEFAULT-POOL` | `poolcode.go:104-107` |
| unset | unresolvable | `DEFAULT-POOL` | `poolcode.go:109`, `DefaultPoolCode` at `poolcode.go:16` |

Namespacing exists because `msg_dispatch_pools` is unique on
`(code, client_id)` while the router keys pools by code alone and treats
one code with differing settings as a conflict, not two pools
(`poolcode.go:73-77`; router-architecture.md:177-194). **The composed
code is opaque** — both halves may contain hyphens, so nothing may split
it back apart; the only permitted structural read is the `-DEFAULT-POOL`
suffix test, `IsDefaultPoolCode` (`poolcode.go:174-179`).

A resolution failure is never fatal: an unknown pool id falls through to
the client's default pool, an unknown client to the global default —
"a job always publishes a routable code rather than being dropped"
(`poolcode.go:83-85`). A cache-refresh failure serves the previous
resolution rather than blocking the claim (`poolcode.go:87-91`).

**Historical note (R-16, resolved in current Go).** The ledger's R-16
entry (`owner-questions.md:106-107`) describes an *earlier* state where
the scheduler published with `messageGroupId` but no `dispatchMode` and
no `poolCode`, making every platform job `IMMEDIATE` in `DEFAULT-POOL`.
Commits `7414bc5` ("carry dispatch pool and mode onto the published
message") and `18f1460` ("propagate the client-namespaced dispatch pool
code") fixed this; the code read above is the fixed state, confirmed by
`git log -- internal/platform/scheduler/dispatcher.go`. **The owner's
R-16 ruling** — *"a message arriving with no `poolCode` or no
`dispatchMode` is malformed — ACK it and raise a notice"* — is a
**router-side** rule (what the router does on receipt), separate from
this already-fixed **scheduler-side** publish behaviour; both must hold
independently.

### `dispatchMode` resolution

`DispatchJobToken.Mode` carries the job's *raw* stored `mode` string
through from the claim query's `dispatchClaim.mode` field
(`poller.go:319-321`) onto the token at claim time (`poller.go:270-276`,
`Mode: c.mode`). `buildMessage` then applies
`common.ParseDispatchMode(tok.Mode)` (`dispatcher.go:98`):

| Stored `mode` | Published `dispatchMode` | Evidence |
|---|---|---|
| `"IMMEDIATE"` | `IMMEDIATE` | `message.go:54-55` |
| `"NEXT_ON_ERROR"` | `NEXT_ON_ERROR` | `message.go:56-57` |
| `"BLOCK_ON_ERROR"` | `BLOCK_ON_ERROR` | `message.go:58-59` |
| `""` (empty/unset) | `NEXT_ON_ERROR` (`DefaultDispatchMode`) | `message.go:60-61,41` |
| anything else (producer bug) | `NEXT_ON_ERROR`, with a `slog.Warn` | `message.go:62-66` |

`DefaultDispatchMode = DispatchNextOnError` deliberately, and the
doc comment explains why (`message.go:32-41`): the old default was
`IMMEDIATE`, "the only mode with no ordering at all," so an omitting
producer silently lost ordering with no observable symptom until load
made concurrent dispatch actually interleave. This is the platform-layer
half of ledger entries **A-09** / **X-01**: *"Unspecified/unknown
`dispatchMode` ⇒ `NEXT_ON_ERROR` at the router layer... The subscription
layer is still open."* — confirmed live in Go's scheduler-publish path.

**A stale/misleading comment worth flagging.**
`scheduler/poller.go:396-398`'s doc comment on `filterByDispatchMode`
says *"Unknown modes parse leniently to IMMEDIATE and therefore
dispatch"* — this is **wrong** against the current
`common.ParseDispatchMode` (which defaults unknown/empty to
`NEXT_ON_ERROR`, not `IMMEDIATE`). The code's actual behaviour is
correct (verified by `TestUnknownModePublishesAsTheDefault`,
`poller_poolcode_pg_test.go:140-149`, which asserts
`common.DefaultDispatchMode` and `RequiresOrdering()==true`); only the
comment is stale, left over from before `DefaultDispatchMode` moved from
`IMMEDIATE` to `NEXT_ON_ERROR`. *Load-bearing or accident?* — accident;
harmless for the Java port beyond not copying the wrong comment.
Similarly `dispatcher_routing_test.go:9-42` (`TestBuildMessageCarriesMode`)
carries a comment claiming "PoolCode is NOT carried yet," which is also
stale now that `poolcode.go` exists — the test still passes only because
its input token happens to carry no pool code either.

---

## 3. Scheduler poll / claim behaviour

`PendingJobPoller.pollOnce` (`poller.go:147-309`) runs once per tick
inside one transaction:

1. **Refresh the paused-connection cache** (`poller.go:148`) — see §11
   timing table.
2. **Claim** — `SELECT ... FOR UPDATE SKIP LOCKED` (`poller.go:178-187`):

   ```sql
   SELECT id, subscription_id, message_group, mode, dispatch_pool_id, client_id,
          attempt_count, target_url, created_at, sequence
     FROM msg_dispatch_jobs
    WHERE status = 'PENDING'
      AND (scheduled_for IS NULL OR scheduled_for <= NOW())
    ORDER BY message_group ASC NULLS LAST, sequence ASC, created_at ASC, id ASC
    LIMIT $1
    FOR UPDATE SKIP LOCKED
   ```

   - **Batch size** `$1` = `Config.BatchSize`, default 100 (§11).
   - **`FOR UPDATE SKIP LOCKED`** lets multiple scheduler instances run
     against the same DB without contending on the same rows
     (`poller.go:158-159`) — but see §12 HA: only the leader is actually
     allowed to tick.
   - **Ordering columns**, in order: `message_group`, `sequence`,
     `created_at`, `id`. The **id is a time-ordered TSID**, appended
     specifically to make the order **total** — ties on
     `(message_group, sequence, created_at)` are common (a subscription's
     `sequence` is per-subscription, shared by every job of a group bound
     for that subscriber) and an ordering with ties is left to the query
     plan, which can interleave arbitrarily across a partitioned table
     (`poller.go:170-177`). **The positional hold-back below depends on
     this total order existing.**
   - A `NULL scheduled_for` (every freshly-created job) is always
     eligible; retry timing is owned entirely by `scheduled_for`, not by
     a separate backoff-loop mechanism (`poller.go:160-169`).

3. **Filter, in order** (`poller.go:230-233`): paused-subscription filter
   → group by `message_group` (`""`/NULL bucketed as `"default"`,
   `messageGroupKey`, `poller.go:352-359`) → blocked-group hold-back →
   per-mode filter. **Skipped claims are simply left `PENDING`** — their
   row locks release at commit and the next poll retries them
   (`poller.go:233`).
4. **Mark the survivors `QUEUED`** in one `UPDATE ... WHERE id = ANY($1)`
   bounded by `created_at >= $2 AND created_at <= $3` so the
   `created_at`-partitioned table prunes to the spanned partitions
   (`poller.go:280-289`).
5. **Commit**, then **publish** — dispatch is deliberately deferred until
   after commit (`poller.go:222-229`): publishing inside the still-open
   transaction meant a commit failure re-claimed an already-published job
   (duplicate dispatch), and a publish failure's `QUEUED→PENDING` revert
   would no-op because the guard status hadn't committed yet.
6. **On publish failure**, revert the whole batch `QUEUED→PENDING` in one
   `UPDATE ... WHERE id = ANY($1) AND status = 'QUEUED'`
   (`dispatcher.go:62-73`) — the status guard leaves alone any row the
   processing endpoint has already advanced past `QUEUED`.

### The claim-time `GroupHolding` hold-back — `filterByDispatchMode`

`poller.go:415-426`, keyed by the shared `GroupHoldingStatusSQL`
predicate (§9). Only `BLOCK_ON_ERROR` jobs are ever held back:

```go
func filterByDispatchMode(claims []dispatchClaim, holders map[string]jobKey) []dispatchClaim {
    kept := make([]dispatchClaim, 0, len(claims))
    for _, c := range claims {
        if common.ParseDispatchMode(c.mode) == common.DispatchBlockOnError {
            if holder, ok := holders[messageGroupKey(c.group)]; ok && holder.before(c.key()) {
                continue
            }
        }
        kept = append(kept, c)
    }
    return kept
}
```

- **`IMMEDIATE` and `NEXT_ON_ERROR` are never held** — neither mode
  promises to stop for a failed/backed-off sibling (`poller.go:413-414`).
- **The comparison is positional**, via `jobKey.before`
  (`poller.go:341-350`), over the *same* `(sequence, created_at, id)`
  triple the claim query orders by — **not set membership**. Set
  membership would include the holder itself the instant its own
  backoff expired, and the group would never move again
  (`poller.go:409-411`).
- `holders` is populated by `blockedGroups` (`poller.go:434-464`): one
  `DISTINCT ON (message_group) ... WHERE message_group = ANY($1) AND (`
  + `GroupHoldingStatusSQL` + `) ORDER BY message_group, sequence,
  created_at, id` query per poll — the **earliest** holder per group,
  because "anything behind it is held by it too" (`poller.go:439-440`).
  `= ANY($1)` **never matches `NULL`**, so a failed *ungrouped* job never
  blocks other ungrouped jobs — only a row whose `message_group` is the
  literal string `'default'` blocks the ungrouped bucket
  (`poller.go:428-433`).

### Timing / sizing constants

| Constant | Value | File:line | Load-bearing or accident? |
|---|---|---|---|
| `Config.PollInterval` | 1s | `scheduler.go:62` | Load-bearing — sets claim latency; "fast, conventional... over the slower legacy values" (`scheduler.go:56-59`) |
| `Config.BatchSize` | 100 | `scheduler.go:63` | Load-bearing — bounds claim-tx row locks and publish-batch size; SQS chunks to 10/`SendMessageBatch` regardless (`dispatcher.go:14-16`) |
| `Config.PausedCacheTTL` | 60s | `scheduler.go:64` | Load-bearing but soft — bounds staleness of the paused-connection *and* pool-code caches (shared TTL, `poller.go:113-114`); a stale read costs at most one TTL of misrouting |
| `Config.StaleAfter` | 5 minutes | `scheduler.go:65` | Load-bearing — how long a row may sit `QUEUED` before `StaleQueuedJobPoller` reverts it to `PENDING` (`stale_recovery.go:56-66`) |
| `Config.StaleScanInterval` | 60s | `scheduler.go:66` | Load-bearing — cadence of the stale-recovery sweep |
| `DefaultReaperInterval` | 2 minutes | `reaper.go:38` | Load-bearing but deliberately loose — "a backstop for a rare failure... not a hot path"; chosen as double the purger's 1-minute cadence since this sweep self-joins across partitions (`reaper.go:32-38`) |
| `DefaultProcessingLiveAfter` | 45 minutes | `reaper.go:62` | Load-bearing — sized *above* the router's documented 15-min-per-attempt × up-to-3-attempts callback contract so the reaper never races a delivery legitimately still in flight (`reaper.go:40-62`) |
| Retry backoff ladder (processing endpoint) | 5s, 15s, 30s, 60s, 120s (attempt 1‑5, clamped) | `processing.go:66-72,284-293` | Load-bearing — the platform's own retry curve for a genuinely-failed delivery, independent of the router-spec's own retry-policy table (§9's endpoint answers the router `ack:true` regardless, so router-spec §4.3's curve never applies to a dispatch job) |
| `defaultTimeout` (no `timeout_seconds` on job) | 30s | `processing.go:61` | Load-bearing default |
| Outer HTTP client ceiling | 2 minutes | `processing.go:99-104` | Load-bearing — bounds a single delivery attempt regardless of the job's own `timeout_seconds` |
| `maxResponseBody` | 64 KiB | `processing.go:58` | Load-bearing — caps how much of a subscriber response is recorded |
| Deferral default delay (`ack:false`, no `delaySeconds`) | 30s | `processing.go:463` | Load-bearing default, matches router-spec §4.3's own 429/deferral floor family loosely (not identical curve — see §5 below) |
| 429 default `Retry-After` | 30s | `processing.go:476` | Load-bearing default when the header is absent/unparseable |
| `maxJobsPerRequest` (settled) | 10,000 | `settled/settled.go:39` | Stated as "a generous ceiling against a pathological request, not a real-traffic limit" — accident-adjacent (arbitrary round number) but harmless |
| `maxBodyBytes` (settled) | 1 MiB | `settled/settled.go:42` | Load-bearing request-size cap |
| `settledChunkSize` (router side) | 1,000 | `router/settled.go:104` | Load-bearing — chosen so 1,000 × (~13-char TSID + 64-hex HMAC) stays comfortably inside both the endpoint's 1 MiB body cap and its 10,000-item cap, which a full 10,000-item request would otherwise blow through (`router/settled.go:94-104`) |
| `defaultSettledTimeout` | 5s | `router/settled.go:108` | Load-bearing — bounds one settled-report chunk call; independent of delivery pipeline timeouts per router-spec §5.4 |
| `dispatcher` publish chunk | 10/`SendMessageBatch` | `dispatcher.go:14` | SQS API limit, not a platform choice — accident of the backend, not tunable |

**Note on `DefaultConfig`'s doc comment.** `scheduler.go:56-59` claims
*"poll 1s / batch 100 / in-flight 1000 / stale 5m... All are
env-overridable."* No `in-flight` field exists on `Config`
(`scheduler.go:27-54`), and grepping `internal/server/subsystems.go`
shows only `ProcessingEndpoint` is ever overridden from `EnvCfg`
(`subsystems.go:78`) — `PollInterval`/`BatchSize`/`PausedCacheTTL`/
`StaleAfter`/`StaleScanInterval` are **not** wired to any env var today.
*Load-bearing or accident?* — flagged for the owner in §14; either the
comment is stale (an `in-flight` knob that no longer exists, and
env-overridability that was never built) or the env wiring is a real gap.

---

## 4. Dispatch-job state machine

Statuses: `common.DispatchStatus` (`internal/common/dispatch_status.go:5-15`)
— `PENDING`, `QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED`,
`EXPIRED`, plus the **legacy** value `ERROR` (accepted only by the parser,
never written by current code — `dispatch_status.go:49`, "an accepted
legacy alias of FAILED... real values `msg_dispatch_jobs.status` has
held"). `IsTerminal()` = `COMPLETED, FAILED, CANCELLED, EXPIRED`
(`dispatch_status.go:18-24`); `IsSuccessful()` = `COMPLETED` only
(`dispatch_status.go:27`). No code path in this seam writes `EXPIRED` —
it exists in the enum and is treated as terminal, but its writer is
outside this seam's files.

| From | To | Trigger | Who | Evidence | Notes |
|---|---|---|---|---|---|
| `PENDING` | `QUEUED` | claimed by the poller | scheduler (`pollOnce`) | `poller.go:283-289` | Reverted to `PENDING` on publish failure (`dispatcher.go:68-72`) or staleness (`stale_recovery.go:58-65`) |
| `QUEUED` | `PENDING` | publish failed | scheduler (`SubmitBatch`) | `dispatcher.go:54-73` | Guarded `WHERE status='QUEUED'` — leaves alone a row the processing endpoint already advanced |
| `QUEUED` | `PENDING` | stuck >`StaleAfter` | `StaleQueuedJobPoller` | `stale_recovery.go:56-66` | Recovers a crash between mark-QUEUED and successful publish, or a broker drop |
| `QUEUED` | `PENDING` | `GroupHeldBefore` re-check fails at delivery | processing endpoint | `processing.go:196-217` | No retry-budget spend (§5) |
| `QUEUED` | `PENDING` | router ACKed as untried `BLOCK_ON_ERROR` sibling | settled hook / reaper | `settled.go:508-520`; `reaper.go:69-88` | See §6, §7 |
| `QUEUED` | `PROCESSING` | first delivery attempt begins | processing endpoint | `processing.go:219`, `repository.go:377-382` | Stamps `last_attempt_at` |
| `PROCESSING` | `COMPLETED` | delivery succeeded | processing endpoint | `processing.go:251-256`, `repository.go:386-391` | Stamps `completed_at`, `duration_millis` |
| `PROCESSING` | `PENDING` | cooperative deferral (`ack:false` or 429) | processing endpoint | `processing.go:258-264`, `repository.go:412-423` (`Reschedule`) | **No** `attempt_count` bump — back-pressure, not a failure |
| `PROCESSING` | `PENDING` | retryable failure, budget remains | processing endpoint | `processing.go:274-280`, `repository.go:403-410` (`ScheduleRetry`) | **Does** bump `attempt_count`; `scheduled_for = now + backoffFor(attemptNumber)` |
| `PROCESSING` | `FAILED` | retries exhausted (`attemptNumber >= job.MaxRetries`) | processing endpoint | `processing.go:266-272`, `repository.go:393-401` (`MarkFailed`) | Terminal for the router-spec's REJECTED review flow (§3.2) — this is the platform-side equivalent |
| `PROCESSING` | `PENDING` | `QUEUED`/`PROCESSING` sibling stranded behind a `FAILED`/`ERROR` `BLOCK_ON_ERROR` head, PROCESSING row stale >45m | reaper | `reaper.go:69-88` | Backstop only — see §7 |
| `FAILED` | `CANCELLED` | operator override, ignore | `CancelDispatchJob` | `operations/cancel.go:16-25`, `entity.go:239-244` | Only valid from `FAILED` (409 otherwise) |
| `FAILED` | `COMPLETED` | operator override, mark handled out-of-band | `CompleteDispatchJob` | `operations/complete.go:15-24`, `entity.go:246-254` | Only valid from `FAILED` (409 otherwise) |
| any status | `PENDING` | operator resend/requeue, **no precondition** | `ResendDispatchJobs` | `operations/resend.go:39-73`, `entity.go:256-272` (`ResetToPending`) | Total — DJ-2 (open, §14): should this be restricted to terminal/`PENDING`? |

`ResetToPending` (`entity.go:256-272`) is the one shared mutator behind
Resend, the settled hook, and the reaper — all three "reason" strings
land in `last_error` so an operator can tell which mechanism reset a row
(`reapReason`, `reaper.go:67`; `defaultReason`, `settled.go:45`; nil for
the operator Resend path, mirroring the pre-envelope behaviour).

---

## 5. The processing endpoint — `POST /api/dispatch/process` **[C]** (internal wire; not registered in either OpenAPI lockfile — see §10)

`internal/platform/dispatchjob/processing/processing.go`. Mounted
**outside** the platform's bearer-JWT middleware — it self-verifies the
scheduler's HMAC token instead (`wire_public.go:113-133`).

### Request / response shapes

| | Shape | Evidence |
|---|---|---|
| Request | `{"messageId": "<dispatch-job id>"}` | `processing.go:126-128` |
| Response | `{"ack": bool, "message"?: string}` | `processing.go:134-137` |
| Auth | `Authorization: Bearer <HMAC-SHA256(jobID)>` verified via `DispatchAuthService.Verify` (constant-time compare) | `processing.go:158-165`, `auth.go:27-31` |

**The endpoint always answers `ack:true` on every code path except two**
(bad/missing auth, and a transient DB error loading the job) — matching
router-spec §9's "answers the router with the outcomes of §4.2/§4.4 on
the router's behalf... [poller owns retries]":

| Condition | HTTP status | `ack` | Evidence |
|---|---|---|---|
| malformed/empty `messageId` | 400 | `true` | `processing.go:149-152` |
| bad/missing bearer token | 401 | `false` | `processing.go:158-165` — **the one deliberate NACK path**, so a forged callback cannot trigger a delivery |
| DB error loading the job | 500 | `false` | `processing.go:167-173` — NACK so the queue redelivers |
| job row gone (deleted) | 200 | `true` | `processing.go:174-178` |
| job already terminal (`IsTerminal()`) | 200 | `true`, no delivery attempted | `processing.go:179-184` |
| `GroupHeldBefore` DB error | 500 | `false` | `processing.go:196-203` |
| group held (see below) | 200 | `true`, `"message":"group blocked"` | `processing.go:204-217` |
| `Reschedule` (revert-to-PENDING) fails while held | 500 | `false` | `processing.go:205-211` — **NACK, not ack**, or the job would sit `QUEUED` with no queue message until stale recovery |
| job already claimed by a concurrent delivery | 200 | `true`, no delivery | Java-only, `ProcessingApi.deliver` — see caveat below (Go has no equivalent guard) |
| claim DB error | 500 | `false` | Java-only, `ProcessingApi.deliver` — see caveat below |
| any successful/failed/deferred delivery attempt | 200 | `true` | `processing.go:240` |

**Router-spec §9's two invariants**, checked against this code:

- *"A job MUST NOT be left in an in-progress state with no queue message
  behind it."* Held — every path that can strand a job (the blocked-group
  revert) NACKs on failure rather than ACKing (`processing.go:205-211`).
  **Caveat**: this held in Go only by accident of scope, and Java
  (`ProcessingApi.deliver`, fixed 2026-09-08) no longer matches it exactly.
  Go's `MarkInProgress` (`processing.go:219-221`) is an unconditional
  `UPDATE` with no status guard, logged-but-swallowed on error, and
  `RecordAttempt` (`processing.go:234-236`) is swallowed the same way — a
  `MarkInProgress` failure does not abort the request; the handler proceeds
  to `deliver()` and always ACKs afterward regardless. Two overlapping
  deliveries of the same job (a redelivered queue message racing the
  original, or two scheduler instances) both pass Go's unlocked
  `IsTerminal()` read and both flip the row, so the subscriber's webhook can
  be called twice for one job. Java replaces the unconditional flip with
  `DispatchJobRepository.claimForDelivery`: a single conditional `UPDATE`
  guarded on `status IN ('PENDING','QUEUED')`, whose row count is the
  claim's answer. A concurrent delivery that finds the row already
  `PROCESSING` (or terminal) updates no row, does not call
  `deliver()`/the subscriber, and ACKs with `"already claimed"` — no retry
  budget spent, no duplicate call. A DB error during the claim NACKs
  (`ack:false`) instead of proceeding, because a failed claim leaves
  ownership unknown and delivering anyway is exactly the duplicate the
  guard exists to prevent — no longer best-effort.
- *"A hold-back MUST cost no retry budget."* Held: the blocked-group
  branch calls `Reschedule` (`repository.go:412-423`), which sets
  `status='PENDING', scheduled_for=$2` **without** touching
  `attempt_count` — confirmed by
  `TestProcess_HeldByABackedOffSiblingInFront` and
  `TestProcess_BlockedGroupAcksAndRevertsToPending` (§13), both of which
  assert `attempts == 0` after a hold-back.

### The delivery-time `GroupHeldBefore` gate

`processing.go:186-217`, guarded by `Repository.GroupHeldBefore`
(`repository.go:425-446`) — runs **only** for `BLOCK_ON_ERROR` jobs with
a non-empty `message_group` (`processing.go:196`); `IMMEDIATE` and
`NEXT_ON_ERROR` skip the query entirely, matching the poller's own
`filterByDispatchMode` filter. This exists because *"messages already on
the queue when the sibling ahead of them stalled would otherwise arrive
and deliver past it"* (`processing.go:190-192`; router-spec §3.4). On a
hit: `Reschedule(jobID, createdAt, time.Now())` — status back to
`PENDING`, `scheduled_for = NOW()` (i.e. immediately re-eligible once the
group unblocks) — and the router is told `ack:true` so the queue message
is dropped (`processing.go:204-216`).

### Attempt recording

One `msg_dispatch_job_attempts` row per delivery attempt
(`repository.go:522-555`, `RecordAttempt`), keyed by an untyped TSID.
`Attempt.Success` derives the stored `status` column (`SUCCESS`/`FAILURE`)
(`repository.go:527-530`). `CompleteFailure`'s `errType` parameter is
**only** persisted when non-empty (`entity.go:162-173`) — the two
cooperative-deferral paths (`ack:false`, HTTP 429) call it with the zero
value on purpose, because a deferral is not an error; a fixed bug
(pinned by `TestCompleteFailure_EmptyErrorTypeLeavesItNil`, §13) used to
persist the empty string instead of `NULL`, which migration 052's CHECK
constraint on `error_type` now rejects outright.

### Delivery request construction

- Method `POST`, `Content-Type: application/json`; extra headers
  `X-Dispatch-Job-Id`, `X-Event-Type` (`processing.go:330-332`).
- Body: `buildPayload` (`processing.go:405-448`) — raw `payload` bytes
  when `data_only`, else a CloudEvents-ish envelope
  `{id, type, attemptNumber, source?, subject?, correlationId?,
  messageGroup?, clientId?, data?}`. A non-JSON `payload` string passes
  through as the literal string value of `data` rather than being
  dropped (`processing.go:433-441`).
- Credentials (`DeliveryCredsResolver`, optional): `Authorization: Bearer
  <SA bearer>` and `X-FlowCatalyst-Signature`/`X-FlowCatalyst-Timestamp`
  (HMAC-SHA256 over `timestamp + body`, millisecond ISO8601 UTC,
  identical byte format to the router's own outbound signing per
  router-spec §4.1) when the resolved credentials carry a signing secret
  (`processing.go:333-353`). Resolver failure or empty creds degrades to
  bare delivery with a warning, not a hard failure (`processing.go:334-337`).
- **No redirects followed**: `CheckRedirect` returns
  `http.ErrUseLastResponse` (`processing.go:101-103`) — matches
  router-spec §4.4's "3xx (any; redirects are never followed)."
- Per-attempt timeout: `job.TimeoutSeconds` if set, else 30s
  (`processing.go:318-321`), bounded by the outer 2-minute client
  ceiling.

### Response classification — **diverges from router-spec §4.2/§4.4**

This is the seam's most important divergence to note: the processing
endpoint's own delivery classification (`processing.go:365-400`) is
**simpler** than the router's outcome table and does **not** implement
the R-57 502/503/504-vs-other-5xx split, nor separate `ErrorConfig` /
`ErrorProcess` / `ErrorConnection` / `RateLimited` / `CircuitOpen` —
because the platform, not the router, owns retry/backoff/terminal-failure
decisions for a dispatch job (per router-spec §3.4). What it does:

| Real subscriber response | Classified as | Then | Evidence |
|---|---|---|---|
| 2xx, body `{"ack":false[,"delaySeconds":N]}` | deferral | `Reschedule` to `now + N` (default 30s), **no budget spent** | `processing.go:366-379`, `parseDeferral` `processing.go:452-468` |
| 2xx, no such body | success | `MarkCompleted` | `processing.go:380` |
| 429 | deferral | `Reschedule` to `now + Retry-After` (parsed as integer seconds, default 30s) | `processing.go:382-390`, `retryAfterOrDefault` `processing.go:470-477` |
| 3xx / 4xx / 5xx (anything else) | failure, `ErrorHTTPError` | `ScheduleRetry` (budget spent) unless `attemptNumber >= MaxRetries`, then `MarkFailed` | `processing.go:392-399`, `advance` `processing.go:266-280` |
| transport error, timeout classified | failure, `ErrorTimeout`/`ErrorConnection` | same retry/exhaust logic | `processing.go:355-358`, `classifyTransportErr` `processing.go:479-487` |

Notably: **every non-2xx, non-429 status — including every 5xx —
consumes retry budget identically** and is retried on the same fixed
backoff ladder (§3's timing table) until `MaxRetries` is hit, at which
point it becomes `FAILED` (the platform-side equivalent of router-spec
§3.2's "terminal failure, handed to review"). There is **no** platform-side
analogue of router-spec §4.4's "502/503/504 releases the whole group with
no warning vs. other 5xx rejects into review" distinction — a 503 from
the real subscriber and a 500 are both retried, then both fail
identically once the budget is spent. **Flagged as an open question for
the owner in §14**: should the processing endpoint's classification track
router-spec §4.2/§4.4's split (unavailable vs. rejected), given that it
stands in for the router's own mediator for this seam?

---

## 6. The settled endpoint — `POST /api/dispatch/settled` **[C]** (internal wire; not registered in either OpenAPI lockfile — see §10)

`internal/platform/dispatchjob/settled/settled.go`. Router-spec §5.4's
"fast path" half of the A-01 recovery: when a `BLOCK_ON_ERROR` group's
head fails terminally, the router's own `Pool.ackBuffered`
(`flowcatalyst-go/internal/router/pool.go:949-980`, outside this seam's
file list but referenced by `settled.go`'s package doc,
`settled.go:1-20`) ACKs the untried buffered siblings off the broker
immediately, then fire-and-forgets a report here so the platform can mark
those rows recoverable instead of leaving them stranded at
`QUEUED`/`PROCESSING` forever.

### Request / response

| | Shape | Evidence |
|---|---|---|
| Request | `{"reason": string, "jobs": [{"id": string, "token": string}]}` | `settled.go:78-90` |
| Response | `{"settled": int, "ids"?: [string]}` | `settled.go:97-100` |

- `reason` defaults to a fixed string when blank
  (`defaultReason`, `settled.go:45,119-122`).
- Each `(id, token)` pair is verified **independently** with the same
  `Verifier` as the processing endpoint — one bad token in a batch drops
  only that item, never the whole batch (`settled.go:124-139`,
  confirmed by `TestServe_PartialBatch`, §13).
- **Limits**: body ≤ 1 MiB (`maxBodyBytes`, `settled.go:42`), batch ≤
  10,000 jobs (`maxJobsPerRequest`, `settled.go:39`) — a 400 with no body
  detail on either violation (`settled.go:106-117`).
- Empty `jobs` is a legal no-op, `200 {"settled":0}`
  (`settled.go:110-112`).
- All verified ids that fail to verify at all (or empty batch after
  trimming) → `401 {"settled":0}` (`settled.go:140-146`) — distinct from
  the 400 malformed-body case.

### Idempotency guard

`Repository.SettleAcked` (`repository.go:508-520`) is a single
`UPDATE ... SET status='PENDING', scheduled_for=NULL, last_error=$reason
WHERE id = ANY($ids) AND status IN ('QUEUED','PROCESSING')`. The `status
IN (...)` guard is the whole idempotency contract: a row a concurrent
path (the reaper, a duplicate hook call, or the job somehow completing)
already advanced past `QUEUED`/`PROCESSING` is left untouched, and the
response's `settled` count / `ids` list reflects only what this call
actually changed (`TestServe_TerminalStatusIgnored`, §13). Note this
guard is **exactly** the same one the reaper's sweep uses
(`dispatchjob.sql:186-187`, both status lists identical) — "the hook and
the reaper share the same guard, so running both back to back must never
double-act on a row" (`reaper.go:15-18`).

### Router-side wire contract (`internal/router/settled.go`)

- Chunked at 1,000 jobs/request (`settledChunkSize`, `settled.go:104`),
  sequential, best-effort — `ReportSettled` joins per-chunk errors but
  keeps trying the rest (`settled.go:153-165`).
- Own dedicated `http.Client` with a 5s default timeout
  (`defaultSettledTimeout`, `settled.go:108`), **independent of the
  delivery pipeline's own timeouts** per router-spec §5.4 — "fire-and-
  forget with its own bounded timeout... never blocking `ackBuffered` or
  holding a pool worker/semaphore slot."
- Wiring is conditional on `RouterPlatformURL` being configured
  (`server/run.go:325-341`); a standalone router with nothing to report
  to is the correct degraded state, backstopped entirely by the reaper
  (§7). Confirmed **wired end-to-end** in current Go
  (`Manager.SetSettledReporter` is called from every pool the manager
  creates — `router/manager.go:793,1198` — despite `settled.go`'s own
  header comment at `router/settled.go:32-41` describing this as
  "Deferred wiring (another lane's files, not this one)," which reads as
  stale relative to the code that now exists).

---

## 7. The reaper — `dispatchjob.RunReaper` / `SweepStrandedGroupSiblings`

`internal/platform/dispatchjob/reaper.go`. The **backstop** half of A-01
— catches what the settled hook misses: a dropped `/api/dispatch/settled`
call, or a router that crashes between ACKing the siblings and reporting
them (`reaper.go:1-22`).

### Predicate

`SweepStrandedGroupSiblings` (`reaper.go:82-88`) drives the SQL query
`DispatchJobSweepStrandedSiblings` (`dispatchjob.sql:164-193`):

```sql
WITH stranded AS (
    SELECT s.id, s.created_at
      FROM msg_dispatch_jobs s
      JOIN msg_dispatch_jobs h
        ON h.message_group = s.message_group
       AND h.status IN ('FAILED', 'ERROR')
       AND (h.sequence, h.created_at, h.id) < (s.sequence, s.created_at, s.id)
     WHERE s.mode = 'BLOCK_ON_ERROR'
       AND s.message_group IS NOT NULL
       AND s.status IN ('QUEUED', 'PROCESSING')
       AND (s.status <> 'PROCESSING' OR s.updated_at < $live_before)
)
UPDATE msg_dispatch_jobs j
   SET status = 'PENDING', scheduled_for = NULL, last_error = $reason, updated_at = NOW()
  FROM stranded st WHERE j.id = st.id AND j.created_at = st.created_at
RETURNING j.id;
```

- Matches **both** terminal-failure statuses (`FAILED` **and** the
  legacy `ERROR`) — exactly `GroupHoldingStatusSQL` (§9) — "the two gates
  must agree on what holds a group" (`reaper.go:22-24`); a sweep that
  recognised only `FAILED` would permanently strand siblings behind a
  legacy `ERROR` head, since that head still blocks at claim time
  (pinned by `TestSweepStrandedGroupSiblings_LegacyErrorHead`, §13).
- `QUEUED` siblings are reset **regardless of age**; `PROCESSING`
  siblings only once `updated_at` is older than the liveness cutoff — a
  fresh `PROCESSING` row is presumed a genuine in-flight delivery
  (pinned by `TestSweepStrandedGroupSiblings_BlockOnError`, §13).
- Positional, matching `(sequence, created_at, id)` — a sibling
  positioned **before** the head is never touched regardless of status
  (same test).
- `NEXT_ON_ERROR` and `IMMEDIATE` jobs are never swept — the join only
  fires for `s.mode = 'BLOCK_ON_ERROR'` (same test, "NEXT_ON_ERROR never
  blocks on a failed sibling").
- Idempotent: a row already reset no longer matches `status IN
  ('QUEUED','PROCESSING')` and is skipped on the next sweep
  (`TestSweepStrandedGroupSiblings_Idempotent`, §13).
- `last_error` records `reapReason` (`reaper.go:67`, contains the literal
  substring `"reaper"`) so an operator can distinguish reaper resets from
  hook resets or human Resend.

### Cadence and leadership

Cadence `DefaultReaperInterval` = 2 minutes (§3 timing table); liveness
cutoff `DefaultProcessingLiveAfter` = 45 minutes. **Not leader-gated** —
wired unconditionally under `cfg.PlatformEnabled`, alongside the purger,
not the scheduler (`server/run.go:113-131`). The doc comment
(`reaper.go:90-98`) states why: "each sweep is a plain conditional UPDATE
guarded by `status IN (QUEUED, PROCESSING)` with no risk of
double-publishing or duplicate delivery if more than one platform
instance runs it concurrently, so no leadership plumbing is required" —
unlike the scheduler's claim/dispatch loops, which **are** leader-gated
because the per-group FIFO dispatcher is in-process (§12).

---

## 8. Review verbs — Cancel / Complete / Resend **[C]** (routes; internal mechanics for events)

Router-spec §3.2's three review-flow verbs, mapped:

| Router-spec verb | Go operation | Route | Precondition | Error on violation | Evidence |
|---|---|---|---|---|---|
| *ignore* | `CancelDispatchJob` | `POST /api/dispatch-jobs/{id}/cancel` | `status == FAILED` | 409 `NOT_FAILED` | `operations/cancel.go`, `operations/shared.go:60-63`, `api/api.go:361-376` |
| *completed* | `CompleteDispatchJob` | `POST /api/dispatch-jobs/{id}/complete` | `status == FAILED` | 409 `NOT_FAILED` | `operations/complete.go`, `api/api.go:378-390` |
| *resend* | `ResendDispatchJobs` | `POST /api/dispatch-jobs/requeue` | none (total) | — | `operations/resend.go`, `api/api.go:333-359` |

### Cancel / Complete — shared shape (`operations/shared.go:31-67`)

- Command: `{id string}` (`StatusFlipCommand`, aliased `CancelCommand`/
  `CompleteCommand`).
- Validation: `id` non-blank → `VALIDATION IS_REQUIRED`
  (`shared.go:39-44`).
- Authorization: `usecaseop.Public` at the operation layer — the API
  controller enforces the coarse `dispatch-job:view` permission
  (`api/api.go:368,382`); `statusFlip` enforces **per-resource** client
  scope post-load via `auth.CheckScopeAccess` (`shared.go:57-59`) — 403
  `SCOPE_FORBIDDEN` for a job outside the caller's tenant, **including**
  a platform-scoped (`clientId == nil`) job for a non-anchor caller
  (`TestCancelDispatchJob_ResourceScope`, §13).
- Not-found: 404 `httperror.NotFound("DispatchJob", id)`
  (`shared.go:54-56`).
- Precondition: only `status == common.DispatchFailed` may flip; every
  other status (including the other terminal ones) is 409 `NOT_FAILED`
  with the current status interpolated into the message
  (`shared.go:60-63`) — deliberately narrower than
  `DispatchStatus.IsTerminal()`, "without this guard an operator could
  'cancel' an already-COMPLETED job" (`shared.go:27-29`).
- On success: `Cancel()`/`Complete()` stamps `completed_at` and flips
  status (`entity.go:239-254`); **flipping the head off `FAILED` is what
  unblocks the rest of its `BLOCK_ON_ERROR` group** — `GroupHoldingStatusSQL`
  stops matching the instant the status changes, and the scheduler's
  next poll re-admits the siblings in claim order (`cancel.go:12-15`,
  `complete.go:13-14`).
- The API handler reloads and returns the updated `DispatchJobResponse`
  (`api/api.go:392-403`) so the SPA can refresh the row without a second
  GET.

### Events emitted (Cancel / Complete)

Both are single-resource operator overrides; each emits exactly one
event via the shared `commonEvent` shape (`operations/events.go:33-55`):

| | `EventType` | `Source` | `Subject` | `MessageGroup` | `data` | Audit `operation` |
|---|---|---|---|---|---|---|
| Cancel | `platform:messaging:dispatch-job:cancelled` | `platform:messaging` | `platform.dispatchjob.{id}` | `platform:dispatchjob:{id}` | `{"dispatchJobId": id}` | `CancelDispatchJob` |
| Complete | `platform:messaging:dispatch-job:completed` | `platform:messaging` | `platform.dispatchjob.{id}` | `platform:dispatchjob:{id}` | `{"dispatchJobId": id}` | `CompleteDispatchJob` |

(`events.go:23-31,57-67` for Cancel/Complete constants and shapes.)

### Resend — bulk, deliberately different security contract

- Command: `{ids: []string}` (`ResendCommand`, `resend.go:16-18`).
  Validation: non-empty → `VALIDATION IDS_REQUIRED`
  (`resend.go:42-47`).
- Authorization: `usecaseop.Public` at the operation layer; the API
  controller gates on the same `dispatch-job:view` permission as list
  (`api/api.go:346-349`). **Per-tenant scoping happens inside `Execute`,
  not as a fail-the-batch check**: ids outside the caller's scope, and
  unknown ids, are both **silently dropped** from the batch rather than
  causing a 403/404 for the whole request — "a view grant can't be used
  to requeue another tenant's jobs," preserved unchanged from the
  pre-envelope `Requeue` handler (`resend.go:28-38`, confirmed by
  `TestResendDispatchJobs_ScopeFiltering`, §13).
- No status precondition — **any** status may be reset
  (`ResetToPending`, `entity.go:256-272`; `resend.go:64`) — this is
  ledger open question **DJ-2** (§14).
- On success: one `SaveAll` transaction writes every accessible job's
  reset **plus** one rollup event (`resend.go:49-73`).

### Events emitted (Resend)

| Per-row event | Rollup event |
|---|---|
| *(none — only the rollup is emitted)* | `DispatchJobsResent` |

- `DispatchJobsResentType = "platform:messaging:dispatch-jobs:resent"`,
  `Source = "platform:messaging"` (`events.go:26-27`).
- `Subject()` is the **constant** `"platform.dispatchjobs.resent"`
  (`events.go:82`) — **not** a per-batch id. This is ledger open question
  **DJ-5** (§14), and matters because Java's already-built equivalent
  (§15) does the opposite.
- `MessageGroup()` is the constant `"platform:dispatchjobs:resent"`
  (`events.go:88`).
- `data()` = `{"ids": [...affected job ids...]}` (`events.go:89-93`) —
  **no count field**; the wire response's `requeued` count is derived by
  the API handler from `len(event.IDs)` (`api/api.go:354-358`), not
  carried on the event itself.
- Audit `operation` = `ResendDispatchJobs` (the `usecaseop.Operation.Name`
  string, `resend.go:41`).
- **DJ-6** (ledger, open): an **empty** accessible-ids result (every
  requested id unknown or out-of-scope) still writes the rollup event +
  audit row, since `Execute` always returns a `Plan` even when `saves` is
  empty (`resend.go:72`, `ids` may be `[]`).

---

## 9. `GroupHolding` — the exact predicate

Stated once, shared by both enforcement points (`group_hold.go:1-26`):

```go
const GroupHoldingStatusSQL = `status IN ('FAILED', 'ERROR') ` +
    `OR (status = 'PENDING' AND scheduled_for IS NOT NULL AND scheduled_for > NOW())`
```

- **Holding statuses**: `FAILED` or the legacy `ERROR` (matched
  identically, "so old rows keep blocking as they always did") — OR a
  row `PENDING` with a **future** `scheduled_for` (a job mid-retry-
  backoff). The second disjunct is the one the doc comment calls "easy
  to miss": such a row is excluded from the normal claim query by its
  own future `scheduled_for` and is *not* `FAILED`, so a naive check
  that only looks at terminal statuses misses it — and its successors
  would be delivered while it is still waiting out its own backoff.
- **Deliberately excludes `QUEUED` and `PROCESSING`** — that is the
  ordinary flow, where a group's whole eligible run is claimed in one
  batch and the router's own per-group FIFO sequences it; treating those
  as holding would collapse every ordered group to one job per poll
  cycle.
- **The comparison consuming this predicate is always positional**
  (`(sequence, created_at, id)` — never plain membership): both
  `blockedGroups`/`filterByDispatchMode` (scheduler, `poller.go:415-464`)
  and `GroupHeldBefore` (processing endpoint, `repository.go:425-446`)
  compare "does the earliest holder in this group come *before* me,"
  because "this group contains a held job" would include the held job
  itself the moment its own backoff expired, and the group would never
  move again.
- **Both consuming sites must agree** — the scheduler's claim-time filter
  and the processing endpoint's delivery-time gate are two independent
  call sites reading the *same* SQL fragment; a job held at one and waved
  through at the other would loop (`group_hold.go:22-24`).

---

## 10. Wire contracts in the lockfile

### Go's `api/openapi.lock.json` — every operation under `/api/dispatch-jobs*` and `/api/dispatch/*`

| Method | Path | operationId |
|---|---|---|
| GET | `/api/dispatch-jobs` | `listDispatchJobs` |
| GET | `/api/dispatch-jobs/by-event/{eventId}` | `dispatchJobsByEventAlias` |
| GET | `/api/dispatch-jobs/event/{eventId}` | `dispatchJobsByEvent` |
| GET | `/api/dispatch-jobs/filter-options` | `dispatchJobFilterOptions` |
| GET | `/api/dispatch-jobs/list-raw` | `listDispatchJobsRaw` |
| GET | `/api/dispatch-jobs/raw` | `listDispatchJobsRawAlias` |
| POST | `/api/dispatch-jobs/requeue` | `requeueDispatchJobs` |
| GET | `/api/dispatch-jobs/{id}` | `getDispatchJob` |
| GET | `/api/dispatch-jobs/{id}/attempts` | `listDispatchJobAttempts` |
| **POST** | **`/api/dispatch-jobs/{id}/cancel`** | **`cancelDispatchJob`** |
| **POST** | **`/api/dispatch-jobs/{id}/complete`** | **`completeDispatchJob`** |
| GET | `/api/dispatch-jobs/{id}/raw` | `getDispatchJobRaw` |

(12 operations total; `/bff/dispatch-jobs*` and `/bff/debug/dispatch-jobs`
mirrors exist in Go source (`api/api.go:59-68`) but are BFF-tier routes,
not part of the SDK-facing lockfile surface this task scopes to.)

**`/api/dispatch/process` and `/api/dispatch/settled` are absent from
Go's OpenAPI lockfile by design** — both are mounted directly on the raw
chi router (`dispatchprocessing.New(...).Mount(r)`,
`dispatchsettled.New(...).Mount(r)`, `wire_public.go:120-133`), never
registered through huma, because they are router-to-platform internal
callbacks authenticated by a per-job HMAC token, not SDK-facing
operations. This spec treats them as **[C]** contracts (a caller — the
router — depends on the exact shape) but they are correctly **not**
lockfile entries.

### Delta against Java's lockfile (`server/src/main/resources/openapi/openapi.lock.json`)

| Go operation | Present in Java lockfile? |
|---|---|
| `listDispatchJobs` | yes |
| `dispatchJobsByEventAlias` | yes |
| `dispatchJobsByEvent` | yes |
| `dispatchJobFilterOptions` | yes |
| `listDispatchJobsRaw` | yes |
| `listDispatchJobsRawAlias` | yes |
| `requeueDispatchJobs` | yes |
| `getDispatchJob` | yes |
| `listDispatchJobAttempts` | yes |
| **`cancelDispatchJob`** | **ABSENT** |
| **`completeDispatchJob`** | **ABSENT** |
| `getDispatchJobRaw` | yes |

**Lockfile delta: 2 of 12 Go operations are absent from Java** —
`cancelDispatchJob` (`POST /api/dispatch-jobs/{id}/cancel`) and
`completeDispatchJob` (`POST /api/dispatch-jobs/{id}/complete}`), the two
review verbs router-spec §3.2 names *ignore* and *completed*. Confirmed
independently by source inspection (§15): no cancel/complete handler,
route, or operation exists anywhere under
`server/src/main/java/io/flowcatalyst/platform/dispatchjob/**`.

Neither `/api/dispatch/process` nor `/api/dispatch/settled` appears in
Java's lockfile either — consistent with their absence from Go's (they
are not SDK-facing), but also because **neither endpoint exists in Java
at all** (§15) — there being no scheduler to publish a
`mediationTarget` pointing at them, this is expected at the current
stage of the port rather than a lockfile gap per se.

---

## 11. Environment variables and defaults

| Variable | Aliases | Default | Effect | Evidence |
|---|---|---|---|---|
| `FC_SCHEDULER_ENABLED` | `DISPATCH_SCHEDULER_ENABLED` | `false` | Starts `StartScheduler` (poller + stale-recovery loops) | `envcfg.go:176`, `run.go:132-136` |
| `FC_DISPATCH_PROCESSING_ENDPOINT` | — | `http://localhost:{APIPort}/api/dispatch/process` | The `mediationTarget` every published dispatch message carries | `envcfg.go:249,253-254` |
| `FLOWCATALYST_APP_KEY` | — | *(required)* | Source key for the HKDF-SHA256-derived dispatch-auth HMAC secret (`info="fc-dispatch-auth"`); **fail-closed** — both the scheduler (`StartScheduler`) and the `/api/dispatch/process`+`/api/dispatch/settled` mount refuse to start without it | `subsystems.go:119-131`, `wire_public.go:113-133` |
| `FC_ROUTER_PLATFORM_URL` | `FC_API_BASE_URL`, `FLOWCATALYST_URL` | `""` | When set, wires `HTTPSettledReporter` onto every router pool (§6); when unset, the router behaves as if the feature never existed and the reaper is the sole recovery path | `envcfg.go:228`, `run.go:325-341` |
| `FC_ROUTER_STRICT_ROUTING` | — | `false` | Router-spec §2.3's strict gate: absent `poolCode`/`dispatchMode` becomes a drop-and-notice instead of the lenient default-fallback (unrelated to the scheduler's own always-set publish behaviour, §2) | `envcfg.go:107-111,226` |
| *(none)* | — | — | `Config.PollInterval` / `BatchSize` / `PausedCacheTTL` / `StaleAfter` / `StaleScanInterval` are **not** env-driven despite `DefaultConfig`'s doc comment claiming otherwise — see §3's timing-table note | `subsystems.go:63-82` (only `ProcessingEndpoint` is overridden) |
| *(none)* | — | `DefaultReaperInterval`=2m, `DefaultProcessingLiveAfter`=45m | Reaper cadence/cutoff — hardcoded at the call site, not env-driven | `run.go:129-130` |

`schedulerPublisher` (`subsystems.go:87-116`) additionally falls back to
a **NOOP publisher with a loud warning** when no broker can be resolved
(`cfg.DefaultBroker != "postgres"` or no `DatabaseURL`) — claimed jobs
are then never delivered and recovered only by stale recovery; explicitly
called out as unsafe for production (`subsystems.go:114-115`).

---

## 12. HA / leadership

| Loop | Leader-gated? | Evidence |
|---|---|---|
| `PendingJobPoller` (claim) | **yes** | `poller.go:105-111,136-138`; `scheduler.go:126` |
| `StaleQueuedJobPoller` (stale recovery) | **yes** | `stale_recovery.go:20-23,43-45`; `scheduler.go:127` |
| Reaper (`RunReaper`) | **no** | `reaper.go:90-98`, `run.go:127-128` — safe because every sweep is one conditional `UPDATE` on a status guard, not a claim |
| Processing endpoint (`/api/dispatch/process`) | n/a — stateless HTTP handler, one row at a time; safe under concurrent instances ONLY once the PROCESSING flip is a status-guarded conditional UPDATE whose row count decides whether to deliver — true in Java since 2026-09-08 (`claimForDelivery`), NOT yet true in Go (go-mirror G14), where the unguarded flip lets a duplicate delivery call the subscriber twice | `processing.go` throughout |
| Settled endpoint (`/api/dispatch/settled`) | n/a — same reasoning, guarded by `SettleAcked`'s `status IN (...)` | `settled.go` |

The scheduler's rationale for leader-gating both its loops: *"the
per-message-group FIFO dispatcher is in-process only, so within-group
ordering requires a single active scheduler. Concurrent SKIP-LOCKED
claims across replicas would let two nodes dispatch the same group's
jobs out of order"* (`subsystems.go:48-52`). `Scheduler.Run` wires
`IsLeader` onto both loops identically (`scheduler.go:125-127`); `nil`
(standby disabled) means always-run.

The reaper and the two HTTP endpoints are deliberately **not**
leader-gated because none of them performs an ordering-sensitive claim —
each write is a single idempotent, status-guarded `UPDATE`/`INSERT` safe
under concurrent execution from multiple platform instances.

---

## 13. Edge cases mined from tests

| Test | File:line | Behaviour pinned |
|---|---|---|
| `TestPollOnce_BackedOffJobHoldsItsGroup` | `scheduler/poller_backoff_holdback_pg_test.go:39-55` | A `PENDING` job with future `scheduled_for` holds everything behind it in its group; nothing publishes past it |
| `TestPollOnce_HeldJobDispatchesOnceItsBackoffExpires` | `poller_backoff_holdback_pg_test.go:61-77` | Once backoff expires, the previously-held job dispatches first — the hold-back can't self-block |
| `TestPollOnce_JobsAheadOfABackedOffSiblingStillDispatch` | `poller_backoff_holdback_pg_test.go:81-95` | A job *before* a backed-off sibling is unaffected — proves the check is positional, not set-membership |
| `TestPollOnce_BatchPublishFailureRevertsToPending` | `scheduler/poller_pg_test.go:62-79` | Whole claimed batch reverts `QUEUED→PENDING` on a `PublishBatch` error |
| `TestPollOnce_BlockedGroupHoldback` | `poller_pg_test.go:135-163` | `BLOCK_ON_ERROR` sibling of a `FAILED` job stays `PENDING`; `IMMEDIATE` sibling in the same group still flows; resolving the failure (any status change) releases the held sibling on the next poll |
| `TestPollOnce_NullGroupFailureDoesNotBlock` | `poller_pg_test.go:173-190` | A `FAILED` ungrouped (`NULL message_group`) job never blocks other ungrouped jobs — `= ANY` never matches `NULL` |
| `TestPollOnce_PausedConnectionHoldsJob` | `poller_pg_test.go:193-230` | A job behind a `PAUSED` connection's subscription stays `PENDING`; reactivating releases it (with a fresh, unstaled cache) |
| `TestPublishedMessageCarriesResolvedPoolCode` | `scheduler/poller_poolcode_pg_test.go:79-94` | End-to-end: claim → resolve → publish carries the client-namespaced pool code **and** the mode together |
| `TestPublishedPoolCodeFallsBackToClientDefault` | `poller_poolcode_pg_test.go:98-108` | No pool, resolvable client → `{identifier}-DEFAULT-POOL` |
| `TestPublishedPoolCodeIsGlobalDefaultWithNeither` | `poller_poolcode_pg_test.go:112-121` | No pool, no client → bare `DEFAULT-POOL` |
| `TestPublishedPoolCodeForPlatformPoolHasNoPrefix` | `poller_poolcode_pg_test.go:125-135` | A platform-level pool (no owning client) publishes unprefixed |
| `TestUnknownModePublishesAsTheDefault` | `poller_poolcode_pg_test.go:140-150` | An unrecognised stored `mode` string publishes as `NEXT_ON_ERROR` (the ordering default), not `IMMEDIATE` |
| `TestSweepStrandedGroupSiblings_BlockOnError` | `dispatchjob/reaper_pg_test.go:82-145` | `QUEUED` siblings swept regardless of age; fresh `PROCESSING` left alone; stale `PROCESSING` swept; positioned-before sibling untouched; `NEXT_ON_ERROR` never swept |
| `TestSweepStrandedGroupSiblings_Idempotent` | `reaper_pg_test.go:147-169` | Second sweep is a no-op on an already-reset row |
| `TestSweepStrandedGroupSiblings_LegacyErrorHead` | `reaper_pg_test.go:196-219` | A legacy `ERROR` head holds its group for reaper purposes exactly as `FAILED` does |
| `TestProcess_HeldByABackedOffSiblingInFront` | `processing/processing_holdback_pg_test.go:42-85` | Delivery-time hold-back: ACK-and-revert-to-`PENDING`, zero budget spent, no HTTP call made; once the front job clears, the held one delivers |
| `TestProcess_NotHeldByABackedOffSiblingBehind` | `processing_holdback_pg_test.go:91-115` | A backed-off sibling *behind* the current job never holds it — proves positional, not set-membership, at the delivery-time gate too |
| `TestProcess_Success` | `processing/processing_pg_test.go:94-122` | Successful delivery → `COMPLETED`, one attempt row, CloudEvents envelope body (not the raw `{messageId}`) |
| `TestProcess_RetryableFailureSchedulesBackoff` | `processing_pg_test.go:125-146` | 500 with budget remaining → `PENDING`, `attempt_count` bumped, `scheduled_for` in the future, still `ack:true` |
| `TestProcess_ExhaustedRetriesFails` | `processing_pg_test.go:148-164` | `attemptNumber == MaxRetries` → `FAILED` |
| `TestProcess_Deferral429DoesNotSpendBudget` | `processing_pg_test.go:166-183` | 429 → `PENDING`, `attempt_count` stays 0, `scheduled_for` set |
| `TestProcess_BadTokenUnauthorized` | `processing_pg_test.go:185-197` | Bad bearer → 401, `ack:false`, job left `QUEUED` (never `MarkInProgress`d) |
| `TestProcess_AlreadyTerminalAcksWithoutRedelivery` | `processing_pg_test.go:199-223` | A job already `COMPLETED` acks with **zero** HTTP calls to the subscriber — no re-delivery |
| `TestProcess_SignsSubscriberDelivery` | `processing_pg_test.go:225-286` | Subscriber delivery carries the SA bearer + `X-FlowCatalyst-Signature`/`-Timestamp`, verifiable HMAC-SHA256(timestamp+body) |
| `TestProcess_BlockedGroupAcksAndRevertsToPending` | `processing_pg_test.go:288-329` | Full round-trip: blocked → ack/revert/no-budget-spend → operator resolves head → re-delivered → `COMPLETED` |
| `TestServe_HappyPath` | `settled/settled_pg_test.go:85-100` | A `QUEUED` job + valid token resets to `PENDING`, `last_error` = the given reason |
| `TestServe_BadToken` | `settled_pg_test.go:107-124` | Forged token → 401, job untouched |
| `TestServe_PartialBatch` | `settled_pg_test.go:126-151` | One bad token doesn't sink the rest of the batch |
| `TestServe_TerminalStatusIgnored` | `settled_pg_test.go:154-171` | A `COMPLETED` job is never resurrected to `PENDING` by a late settled call |
| `TestServe_EmptyBatch` | `settled_pg_test.go:173-181` | Empty `jobs` array is accepted as a no-op, not malformed |
| `TestCancelDispatchJob_NonFailedSource_Conflict` | `operations/ops_pg_test.go:117-135` | Every non-`FAILED` status (including every terminal one) is rejected 409, parameterised over all six alternatives |
| `TestCancelDispatchJob_ResourceScope` | `ops_pg_test.go:137-165` | Another tenant's job and a platform-scoped job are both denied to a non-anchor; the caller's own tenant's job succeeds |
| `TestResendDispatchJobs_HappyPath` | `ops_pg_test.go:211-242` | Reset clears `attempt_count`, `scheduled_for`, `last_error`, `completed_at`; unknown id silently dropped from the result |
| `TestResendDispatchJobs_ScopeFiltering` | `ops_pg_test.go:244-273` | Out-of-scope and platform-scoped ids are silently dropped, not error the whole batch; the whole request still succeeds |
| `TestFindWithFilters_TenantScoping` | `dispatchjob/repository_pg_test.go:24-77` | `AccessibleClientIDs` narrows to own-tenant + platform-scoped rows; an explicit cross-tenant filter yields nothing (not an error) |
| `TestFindByID_CorruptStatusFailsLoudly` (+ Kind, RetryStrategy variants) | `repository_pg_test.go:92-165` | A row with an unrecognised `status`/`kind`/`retry_strategy` fails the read loudly (X-06) rather than silently defaulting |
| `TestFindByID_LegacyErrorStatusReadable` | `repository_pg_test.go:167-190` | The legacy `ERROR` status round-trips as `FAILED`, not a corrupt-read error |
| `TestFindWithFilters_CorruptStatusFailsTheWholeList` | `repository_pg_test.go:192-231` | One corrupt row fails the **entire** list read, not just that row (X-06: "a list containing the row fails too") |
| `TestParseKindStrict` / `TestParseRetryStrategyStrict` / `TestParseErrorTypeStrict` | `dispatchjob/entity_test.go:17-76` | All three parsers reject empty string as well as unrecognised values (`ok=false`), never silently defaulting |
| `TestCompleteFailure_EmptyErrorTypeLeavesItNil` | `entity_test.go:78-100` | A deferral's zero-value error type persists as `NULL`, never the literal empty string (regression pin for a real migration-052 constraint violation) |
| `TestParseDeferral` | `processing/processing_test.go:62-79` | `{"ack":false}` with no `delaySeconds` defaults to 30s; `{"ack":true}` and `{}` are not deferrals |
| `TestBackoffFor` | `processing_test.go:81-86` | Backoff clamps to the last ladder rung for any attempt number beyond it; non-positive attempt numbers guard to rung 0 |
| `TestBuildMessageOrderedModesReachTheFIFOPath` | `scheduler/dispatcher_routing_test.go:44-73` | An **absent** mode still takes `DefaultDispatchMode` and therefore orders — the regression this guards is a job whose mode silently fell through to `IMMEDIATE` |

---

## 14. Open questions for the owner (yes/no decisions)

1. **Processing-endpoint 5xx classification (§5).** Should
   `/api/dispatch/process`'s delivery classifier track router-spec
   §4.2/§4.4's unavailable-vs-rejected split (502/503/504 vs. other 5xx)
   instead of treating every non-2xx/429 status identically against the
   fixed 5-rung backoff ladder? *Yes: adopt the split (a struggling
   real-503 target gets the "outage" treatment distinct from a genuine
   `500` bug) / No: current uniform retry-then-fail behaviour is
   intentional and should be preserved as spec.*
2. **`RecordAttempt` failures are swallowed, not NACKed (§5).** A DB error
   at exactly `RecordAttempt` logs and proceeds rather than triggering
   router-spec §9's "every failure path NACKs" invariant. *Load-bearing gap,
   or accepted best-effort scope?*
   The `MarkInProgress` half of this question is **answered** (2026-09-08): it
   was load-bearing, and worse than the swallowed error — the flip had no
   status guard at all, so a duplicate delivery called the subscriber twice.
   Java replaced it with the guarded `claimForDelivery` and NACKs on a claim
   error; Go still has the original and must follow (go-mirror G14).
3. **Scheduler `Config` env-overridability (§3, §11).** `DefaultConfig`'s
   doc comment claims all five timing knobs are env-overridable and
   mentions an `in-flight` cap that doesn't exist on the struct; only
   `ProcessingEndpoint` is actually wired from `EnvCfg`. *Should
   `PollInterval`/`BatchSize`/`PausedCacheTTL`/`StaleAfter`/
   `StaleScanInterval` become env-driven in the Java port, or is the Go
   comment simply stale and the hardcoded defaults are the real spec?*
4. **DJ-2 (ledger, still open).** `ResendDispatchJobs`/`requeue` is total
   — it resets `PROCESSING`/`QUEUED`/`COMPLETED` jobs too, not just
   `FAILED`/terminal ones. *Add a precondition, or keep it total?*
5. **DJ-3 (ledger, still open).** Requeue is gated by the coarse `view`
   permission and silently skips inaccessible/unknown ids, reporting only
   a count. *Keep silent-skip, or make it 403/404 per bad id?*
6. **DJ-4 (ledger, still open).** Requeue's per-row scope predicate is
   `auth.CanAccessScope` (an anchor/super-admin may requeue platform-
   scoped jobs). *Confirm this is the intended platform-scope model going
   forward (vs. Go's historical `client_id = ANY(clients)` reading, which
   this already differs from).*
7. **DJ-5 (ledger, still open — and Java already conflicts, §15).**
   Go's `DispatchJobsResent` rollup subject is the **constant**
   `"platform.dispatchjobs.resent"`. *Keep the constant subject (per the
   ledger's "current Go behaviour stands" default), or mint a per-batch
   id as Java's already-built `DispatchJobsRequeued` does?* This decision
   should also settle whether the Java port's already-diverged behaviour
   needs to be corrected.
8. **DJ-6 (ledger, still open).** An empty accessible-ids result (every
   requested id unknown/out-of-scope) still writes the rollup event +
   audit row. *Intended (uniform audit trail for every request) or should
   a fully-empty result skip the write?*
9. **DJ-10 (ledger, still open — confirmed still true, §5/§10).**
   `attempts` is never populated on `DispatchJobResponse` (the repo read
   path that backs it never hydrates `Attempts`), and `clientIdentifier`/
   `priority` are declared but never emitted on `DispatchJobRead`.
   *Fix these gaps in the Java port, or they're deliberately deferred
   (the wire fields stay declared-but-unpopulated placeholders)?*
10. **Reaper reason string as a machine-checkable marker (§7).**
    `last_error` currently carries a free-text reason distinguishing
    reaper/hook/human resets by prose substring match (`"reaper: ..."`).
    *Should the Java port promote this to a structured field/enum instead
    of a string convention an operator (or test) must grep?*
11. **Env var naming for the settled-reporter platform URL (§11).**
    Three aliases exist (`FC_ROUTER_PLATFORM_URL`, `FC_API_BASE_URL`,
    `FLOWCATALYST_URL`) for what is semantically the router's own
    outbound base URL. *Should the Java port collapse to one canonical
    name, or is alias compatibility itself part of the contract (e.g. for
    shared deployment tooling)?*

---

## 15. What Java already has

Read from `server/src/main/java/io/flowcatalyst/platform/dispatchjob/**`
and `server/src/main/java/io/flowcatalyst/platform/subscription/DispatchMode.java`.

**Entity / transitions.** `DispatchJob` (record, `dispatchjob/DispatchJob.java`)
mirrors the Go entity's field set closely, but implements only **one**
transition: `requeue()` (`DispatchJob.java:132-138`) — the Resend
equivalent, total/unconditional exactly like Go's `ResetToPending`. There
is **no** `cancel()`/`complete()` transition anywhere in the Java source.

**Status enum.** `DispatchJobStatus` (`DispatchJobStatus.java`) has the
same seven-member set as Go plus the legacy `ERROR` alias in its parser
— but **`parse` defaults *any* unrecognised value (including truly
unknown strings, not just empty) to `PENDING`**
(`DispatchJobStatus.java:12-22`), which is **more lenient** than Go's
strict `(T, bool)` `ParseDispatchStatus` (`dispatch_status.go:39-58`,
X-06: unrecognised → loud read failure, never silent default). This is a
direct divergence from the X-06 convention (`CONVENTIONS.md` "no silent
default on an unrecognised stored value") that the Go side enforces
specifically because "a corrupted terminal status silently reappearing
as PENDING could resurrect a job that already completed or failed"
(`dispatch_status.go:35-37`).

**`DispatchMode` default — directly conflicts with the X-01/A-09 ruling.**
`platform/subscription/DispatchMode.java:14-19` defaults **unknown and
`null`** to `IMMEDIATE`:

```java
public static DispatchMode parse(String s) {
    return switch (s == null ? "" : s) {
        case "NEXT_ON_ERROR" -> NEXT_ON_ERROR;
        case "BLOCK_ON_ERROR" -> BLOCK_ON_ERROR;
        default -> IMMEDIATE;
    };
}
```

Go's `common.ParseDispatchMode` (§2 above) defaults both unknown *and*
empty to `NEXT_ON_ERROR`, precisely because `IMMEDIATE` is "the only mode
with no ordering at all" and a default that quietly weakens the ordering
guarantee is the wrong way round (`message.go:32-41`). This is exactly
the state ledger entries **A-09**/**X-01** describe as still-open at the
*subscription* layer ("The subscription layer is still open — see X-01")
— confirmed here as the Java code's **current, live** behaviour, not a
hypothetical. Any dispatch-seam Java implementation that reuses this
`DispatchMode.parse` inherits the wrong default until X-01 is ruled and
applied here.

**Repository.** `DispatchJobRepository` (`DispatchJobRepository.java`)
has read methods (`findById`, `findByIds`, `findWithFilters`,
`findByEventId`, `distinctValues`, `attemptsByJob`) and `persist`/`delete`
for the use-case envelope's `Persist<DispatchJob>` contract — but **no**
equivalent of Go's `MarkInProgress`, `MarkCompleted`, `MarkFailed`,
`ScheduleRetry`, `Reschedule`, `GroupHeldBefore`, `SettleAcked`,
`SweepStrandedGroupSiblings`, or `Insert`/`InsertBatch`. None of the
scheduler-driven or processing-endpoint-driven infrastructure writes
exist.

**Operations.** Only `RequeueDispatchJobs` exists
(`operations/RequeueDispatchJobs.java`), matching Go's `ResendDispatchJobs`
shape closely: `Operation.Authorize.publicAccess()` with per-row
`Checks.canAccessScope` filtering in `execute`, unknown/out-of-scope ids
silently dropped, one rollup event per batch. **`CancelDispatchJob` and
`CompleteDispatchJob` do not exist** — confirmed by both the lockfile
delta (§10) and a direct directory listing (no `cancel`/`complete` files
under `operations/`).

**`DispatchJobsRequeued` rollup subject diverges from the DJ-5 ledger
default.** Java's rollup (`operations/DispatchJobEvents.java:70-97`)
mints a fresh TSID **per batch** and builds a per-batch subject
`platform.dispatchjobs.{batchId}` (`DispatchJobEvents.java:36-42`,
`batchSubjectFor`) — the *opposite* of Go's constant subject
`"platform.dispatchjobs.resent"` (`events.go:82`). Ledger entry **DJ-5**
is explicitly still open with "current (Go) behaviour stands" as the
standing default (`owner-questions.md:426-427`), which means **Java's
already-shipped behaviour currently conflicts with the standing
convention** pending an explicit ruling — flagged as open question 7
above; this is not this spec proposing a fix, only naming the conflict.

**Routes.** `DispatchJobApi.register` (`api/DispatchJobApi.java:69-83`)
wires exactly the 9 read/list routes plus `requeue` — no `/cancel`,
`/complete`, `/api/dispatch/process`, or `/api/dispatch/settled` routes
exist anywhere in the Java server module.

**Missing entirely (no Java files under any of these names):**
- The scheduler subsystem: poller, `MessageGroupDispatcher`,
  `PoolCodeResolver`, `PausedConnectionCache`, `DispatchAuthService`,
  `StaleQueuedJobPoller` — no equivalent of Go's
  `internal/platform/scheduler/**` package exists.
- The processing endpoint (`/api/dispatch/process`) and its delivery
  client, payload builder, retry/backoff ladder, and
  `DeliveryCredsResolver`.
- The settled endpoint (`/api/dispatch/settled`) and its idempotent
  batch-verify-and-reset logic.
- The reaper (`SweepStrandedGroupSiblings`/`RunReaper`) and the shared
  `GroupHoldingStatusSQL`/`GroupHeldBefore` predicate in any form.
- `Cancel`/`Complete` operations, routes, and events.
- Any leadership-gating hook analogous to `Scheduler.IsLeader`.
- Any `common.Message`/queue-publish equivalent carrying `poolCode`,
  `dispatchMode`, `messageGroupId`, or the per-job HMAC `authToken` for a
  dispatch job — the Java port has no router or queue-publish layer yet
  at all (per `docs/spec/router.md`'s own stated scope, referenced by
  this task's brief as "data plane unported").

In short: **Java currently has the read/list surface and the Resend/
requeue operation only.** Everything else this specification describes —
the scheduler, the processing endpoint, the settled endpoint, the reaper,
Cancel/Complete, and the shared `GroupHolding` predicate that ties claim-
time and delivery-time enforcement together — is unbuilt.
