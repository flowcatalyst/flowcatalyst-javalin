# Stream processor — fan-out, read-model projections, partition manager

Extracted 2026-09-05 against Go HEAD (`9f7be62`) from `internal/stream/`
(`fan_out.go`, `projector.go`, `events.go`, `dispatch_jobs.go`,
`partition_manager.go`, `health.go`), `internal/server/subsystems.go`
(`StartStreamProcessorWithHealth`), `envcfg.go`, and the Go tests
(`TestNextSleep_AdaptiveTiers`, `TestParsePartitionEnd*`,
`TestRetentionFor_*`, `TestHealth*`). Behaviour tables, never code.
`[C]` contract, `[I]` implementation detail, `[D]` Go defect / question.
Java: `io.flowcatalyst.stream`, toggled by `FC_STREAM_PROCESSOR_ENABLED`
(already in `Env`, wired to nothing — `Server.java:200` TODO).

## 1. Purpose and topology [C]

Four independent single-threaded loops ("projectors") over the platform
database, all gated on **one leader election** (`newLeaderGate(…, "stream")`
— a stream-suffixed lock, independent of the router's and the scheduler's),
started together when the subsystem is enabled and each individually
toggled:

| Projector | Toggle (default on) | Reads | Writes | Batch default |
|---|---|---|---|---|
| `event_fan_out` | `FC_STREAM_FAN_OUT_ENABLED` | `msg_events` where `fanned_out_at IS NULL` + active subscriptions | `msg_dispatch_jobs` (one per matching subscription) | 200 |
| `event_projection` | `FC_STREAM_EVENTS_ENABLED` | `msg_events` where `projected_at IS NULL` | `msg_events_read` | 100 |
| `dispatch_job_projection` | `FC_STREAM_DISPATCH_JOBS_ENABLED` | `msg_dispatch_jobs` where `projected_at IS NULL OR updated_at > projected_at` | `msg_dispatch_jobs_read` | 100 |
| `partition_manager` | `FC_STREAM_PARTITION_MANAGER_ENABLED` (alias `FC_STREAM_PARTITIONS_ENABLED`) | `pg_inherits` | monthly partitions of seven `msg_*` parents | tick, not batch |

A non-leader instance runs the loops but **never claims** (it sleeps
`IdleSleep` and re-checks). Shutdown = cancel; each loop exits at its next
check. The subsystem is "stopped" when all four have returned.

Java shape (CONVENTIONS §8): one virtual thread per projector under a
`StructuredTaskScope`; cancellation by interruption; the claim/insert
transaction boundary in §3–§5 is the load-bearing part and is written by
the orchestrator; the loops are plain `while (!interrupted)` with the §2
pacing — no `select` choreography, no `CompletableFuture`.

## 2. The projector loop [C]

`ProjectorConfig{Enabled, BatchSize, PollInterval=100 ms, IdleSleep=1 s, ErrorSleep=5 s}`.

```
loop:
  if cancelled → log "projector stopped", return
  if not leader → sleep IdleSleep, continue
  n, err := step(batchSize)
  err     → log WARN "projector step error", health.RecordError()
  n > 0   → health.AddProcessed(n)   (stamps lastPoll)
  sleep(nextSleep):   err → ErrorSleep | n == 0 → IdleSleep | n ≥ batchSize → 0 | else → PollInterval
```

Pinned by Go `TestNextSleep_AdaptiveTiers`: exactly those four tiers, in
that precedence. `Health.SetRunning(true)` on entry, `false` on return (a
disabled projector never sets it). Batch size resolution:
`FC_STREAM_<NAME>_BATCH_SIZE` > `FC_STREAM_BATCH_SIZE` > per-projector
default (200 / 100 / 100).

## 3. Fan-out — `event_fan_out` [C]

**Subscriptions** are cached for `SubscriptionTTL` (5 s; env
`FC_STREAM_FAN_OUT_SUBS_REFRESH_SECS`, 0 = default): every `ACTIVE`
`msg_subscriptions` row with its `msg_subscription_event_types` patterns
(`LEFT JOIN`, so a subscription with no patterns is loaded and matches
nothing), ordered by id. A reload failure **keeps the previous cache** if
there was one; only a first-load failure fails the step.

**Matching** (`docs/spec/subscription.md` §3 rule, restated): pattern and
event-type code split on every `:`; they match when the segment counts are
equal and each pattern segment is `*` or equal. **Client**: a subscription
with `client_id NULL` matches every event; one with a client matches only
events with that exact `client_id` (an event with no client never matches
a client-bound subscription).

**Step**, one transaction:
1. If the cache holds **zero** subscriptions: stamp the oldest `batchSize`
   unfanned events `fanned_out_at = NOW()` and return the count — **no jobs,
   and those events will never be fanned out later** (§8 D1). No
   `SKIP LOCKED` on this path (§8 D2).
2. Otherwise `UPDATE … SET fanned_out_at = NOW()` the oldest `batchSize`
   unfanned events selected `ORDER BY created_at LIMIT n FOR UPDATE SKIP
   LOCKED` (keyed `(id, created_at)` — the table is partitioned on
   `created_at`), `RETURNING id, type, source, subject, data,
   correlation_id, message_group, client_id, created_at`.
3. For each claimed event × each matching subscription, one job:

| Job column | Value |
|---|---|
| `id` | fresh **untyped 13-char TSID** |
| `code` / `source` / `subject` / `event_id` / `correlation_id` / `client_id` / `message_group` | the event's |
| `payload` | the event's `data` as text, or the literal `null` when empty |
| `target_url`, `data_only`, `service_account_id`, `subscription_id`, `dispatch_pool_id`, `sequence`, `timeout_seconds`, `max_retries` | the subscription's |
| `mode` | the subscription's, parsed with the shared `DispatchMode` (unknown/blank → `NEXT_ON_ERROR`, ledger X-01) |
| `protocol` | `HTTP_WEBHOOK` |
| `status` | `PENDING` |
| `idempotency_key` | `<eventId>:<subscriptionId>` (no unique index backs it — §8 D3) |
| `created_at`, `updated_at` | **the event's `created_at`** (so the job lands in the same monthly partition as its event and ages out with it) |

   inserted as one statement batch, `ON CONFLICT (id, created_at) DO NOTHING`.
4. Commit; return the number of **events** claimed (not jobs). A failure
   anywhere rolls the claim back, so the events are retried next step.

## 4. Event projection — `event_projection` [C]

Claim `SELECT id, created_at FROM msg_events WHERE projected_at IS NULL
LIMIT n FOR UPDATE SKIP LOCKED` in a transaction, then
`INSERT INTO msg_events_read (…) SELECT … FROM msg_events WHERE id = ANY(ids)
ON CONFLICT (id, created_at) DO NOTHING`, then
`UPDATE msg_events SET projected_at = NOW() WHERE id = ANY(ids)`, commit.
Derived columns: `application = split_part(type, ':', 1)`,
`subdomain = NULLIF(split_part(type, ':', 2), '')`,
`aggregate = NULLIF(split_part(type, ':', 3), '')`; `data` copied as text;
`created_at` = the **source** row's (same partition); `projected_at = NOW()`.
Returns the number of ids claimed.

## 5. Dispatch-job projection — `dispatch_job_projection` [C]

Dirty rule: `projected_at IS NULL OR updated_at > projected_at`, so every
status change re-projects. Claim as §4 (`FOR UPDATE SKIP LOCKED`), track
the min/max `created_at` of the claimed ids and bound the projecting
`SELECT` with `created_at BETWEEN min AND max` (partition pruning on a
large parent). Upsert into `msg_dispatch_jobs_read` on `(id, created_at)`,
updating `status, attempt_count, last_attempt_at, completed_at,
duration_millis, last_error, is_completed, is_terminal, updated_at,
projected_at` on conflict. Derived: `is_completed = status = 'COMPLETED'`;
`is_terminal = status IN ('COMPLETED','FAILED','CANCELLED','EXPIRED')`;
`application/subdomain/aggregate` from `code` as in §4. Then stamp
`projected_at = NOW()` on the source rows.

## 6. Partition manager [C]

`PartitionedTables` = `msg_events`, `msg_events_read`, `msg_dispatch_jobs`,
`msg_dispatch_jobs_read`, `msg_dispatch_job_attempts`,
`msg_scheduled_job_instances`, `msg_scheduled_job_instance_logs` (**not**
`iam_login_attempts` — `backlog.md`, owner question). Config
`MonthsForward=3`, `RetentionDays=90`, `ScheduledJobRetentionDays=30` (for
the two `msg_scheduled_job_instance*` parents), `TickInterval=24 h`; env
`FC_STREAM_PARTITION_MONTHS_FORWARD`, `…_RETENTION_DAYS`,
`…_RETENTION_DAYS_SCHEDULED_JOBS`, `…_TICK_HOURS` (0 = default). One pass
at start, then every tick, leader only; each pass, per parent that is
partitioned: `ensureForward` creates `<parent>_YYYY_MM` for this month and
the next `MonthsForward` months (`CREATE TABLE IF NOT EXISTS … PARTITION OF
… FOR VALUES FROM (monthStart) TO (nextMonthStart)`); `dropOld` lists the
children via `pg_inherits`, parses `_YYYY_MM` (anything else — the default
partition, a quarterly name — is left alone) and `DROP TABLE IF EXISTS`
those whose **end** ≤ now − retention. A failing parent is logged and the
pass continues. Health counts created + dropped as "processed". Pinned by
`TestParsePartitionEnd`, `TestParsePartitionEndDriveRetention`,
`TestRetentionFor_ScheduledJobTables`.

## 7. Health and readiness [C]

Per projector `Health{name, running, batchSequence (processed total),
errorCount, lastPollMs}`; `IsHealthy == IsRunning`. `HealthService`:
`IsLive` = at least one registered health is running (**false when none
registered**); `IsReady` = **every** registered health is healthy (false
when none). `Aggregate{healthy: total>0 && healthy==total, totalStreams,
healthyStreams, unhealthyStreams, streams[]}` — the shape the router's
`/monitoring` stream bridge and `/ready` consume (`Server.java` already
lists the `stream` toggle in `/ready`). Snapshot JSON:
`{name, status, running, healthy, batchSequence, errorCount, lastPollTimeMs}`.

## 8. Defects and questions for the owner

- **D1** With zero active subscriptions, fan-out stamps events
  `fanned_out_at` and discards them; a subscription created a second later
  never sees them, and the stamp is irreversible. Intended ("no subscriber
  at the time" semantics) or should events wait?
- **D2** That no-subscription path claims without `FOR UPDATE SKIP LOCKED`;
  two leaders (a split-brain window) could stamp the same rows — harmless
  here since nothing is inserted, noted for completeness.
- **D3** `idempotency_key` (`eventId:subscriptionId`) has no unique index;
  the only idempotency is the transactional claim. A rolled-back commit
  after a partial batch cannot double-insert (the batch is one
  transaction), so this is a latent, not live, defect — but the key is
  advertised on the wire as if it meant something.
- **D4** `data::text` is copied into `msg_events_read`: every event payload
  is stored twice. Design choice to confirm.
- **D5** Health "healthy" is just "running": a projector erroring on every
  step (`ErrorSleep` forever) stays healthy and ready. Should `errorCount`
  growth or a stale `lastPollMs` degrade it?
- **D6** The partition manager runs its first pass at start on every
  leader change; `CREATE TABLE IF NOT EXISTS` makes that safe, but two
  leaders racing can both attempt the same create (one fails on the name,
  logged, continues). Fine; noted.

## 9. Tests the port must have (load-bearing → mutation-checked)

- **Claim is exclusive and transactional**: two fan-out steps run
  concurrently over the same unfanned events produce jobs for each event
  exactly once (count rows per `(event_id, subscription_id)`); a step whose
  insert fails leaves `fanned_out_at` NULL (the events are retried).
- **Matching table**: pattern × code × client cases from §3, including a
  client-bound subscription never matching a client-less event, and a
  subscription with no patterns matching nothing.
- **Job shape**: every column of the §3 table asserted on the stored row,
  including `created_at == event.created_at` and the untyped 13-char id.
- **Zero subscriptions**: events are stamped, no jobs, count returned.
- **Cache TTL**: a subscription activated after the first load is not seen
  until the TTL lapses (fake clock), and a reload failure keeps the old set.
- **Pacing tiers**: the §2 table, all four, with precedence (a fake step
  returning error / 0 / n < batch / n ≥ batch).
- **Leader gate**: a non-leader loop never calls `step` (a counter that
  must stay 0 while the leader's moves).
- **Projections**: derived columns; the dispatch-job re-projection on
  `updated_at > projected_at` with the upsert changing only the listed
  columns; partition-bounded select (the test observes the bounds through
  a spy or by a row outside the range not being touched).
- **Partition manager**: names created for month 0..3; a partition ending
  exactly at the cutoff is dropped and one ending a day later is kept;
  scheduled-job parents use the 30-day retention; the default partition
  is never dropped; `iam_login_attempts` is not touched.
- **Health**: `IsLive`/`IsReady` truth table including the empty registry;
  the aggregate counts.
- **Shutdown**: interrupting the scope stops all four loops within one
  sleep tier and `running` goes false on each.
