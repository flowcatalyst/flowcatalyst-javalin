# Scheduled-job scheduler and the purger

Extracted 2026-09-05 against Go HEAD (`9f7be62`) from
`internal/platform/scheduledjob/scheduler/{cron,poller,dispatcher}.go`,
`internal/platform/scheduledjob/instance_repository.go`,
`internal/platform/serviceaccount` (`FindFirstByApplicationID`,
`NewCachedOutboundCredsResolver`), `internal/server/subsystems.go`
(`StartScheduledJobScheduler`, `StartPurger`, `newLeaderGate`) and the Go
tests (`TestDispatcherTick_OrphanInstance_MarkedDeliveryFailed`,
`…Accepts2xxNotJust202`, `…SignsFiringWithApplicationSecret`,
`TestWebhookEnvelope_CamelCaseKeys`). The aggregate half is
`docs/spec/scheduledjob.md` (§6 instances, §7 the data-plane contract this
unit consumes). Behaviour tables, never code. `[C]` contract, `[D]` defect.

## 1. Topology [C]

`FC_SCHEDULED_JOB_ENABLED` (alias `SCHEDULED_JOB_SCHEDULER_ENABLED`, default
false; already in `Env`, wired to nothing — `Server.java:200`). Two loops on
one leader election (`newLeaderGate(…, "scheduled-job")`): the **poller**
(creates `CRON` instances) and the **dispatcher** (delivers `QUEUED`
instances). Both tick on a timer, skip the tick when not leader, log and
continue on a tick error, exit on cancel. Config `Config{PollInterval=30 s,
DispatchInterval=5 s, DispatchBatchSize=32, HTTPTimeout=10 s}`; env
`FC_SCHEDULED_JOB_POLL_SECONDS`, `FC_SCHEDULED_JOB_DISPATCH_SECONDS`,
`FC_SCHEDULED_JOB_DISPATCH_BATCH`, `FC_SCHEDULED_JOB_HTTP_TIMEOUT_SECONDS`
(unset = default). Leader gate (`newLeaderGate`): standby disabled → always
leader; enabled → a Redis election on `<lockKey>:scheduled-job`; **election
init or start failure → never leader (fail closed)**, logged as an error.
Java package `io.flowcatalyst.platform.scheduler.jobs` (beside the existing
dispatch scheduler), reusing `LeaderElection` through the same gate helper
`Server` already has for the dispatch scheduler.

## 2. Poller — every `PollInterval` [C]

For every job in `findActive()` (status `ACTIVE`): `after = lastFiredAt ??
createdAt`; `slot = latestSlotInWindow(crons, timezone, after, now)`
(`scheduledjob.md` §3.2 — the latest cron slot in `(after, now]`; none →
skip). Insert a `CRON` instance `{id: sji_…, scheduledJobId, clientId,
jobCode, triggerKind CRON, scheduledFor = slot, firedAt = now, status
QUEUED, deliveryAttempts 0, createdAt = now}`, then `markFired(jobId, slot)`
(`last_fired_at = GREATEST(last_fired_at, slot)`). An insert failure is
logged and the job is skipped this tick (no `markFired`, so the slot is
retried next tick); a `markFired` failure is logged and **not** retried
(§6 D1). **No `SKIP LOCKED` claim** — correctness rests on the leader gate.
Missed slots collapse: only the latest slot in the window fires
(`scheduledjob.md` §3.2); a job paused for a day fires once when resumed.

## 3. Dispatcher — every `DispatchInterval` [C]

`list({status: QUEUED, limit: DispatchBatchSize})` (oldest first, per the
instance repository's order), jobs loaded once per tick per id (memoised):

1. Job gone (`findById` empty) → **orphan**: `markDeliveryFailed(instance,
   "ScheduledJob no longer exists", terminal = true)`.
2. `markInFlight(instance)`: `status = IN_FLIGHT, delivery_attempts =
   delivery_attempts + 1`; `attemptsAfter = deliveryAttempts + 1`.
3. No `targetUrl` on the job → failure "No target URL configured for job".
4. Build the **webhook envelope** (camelCase keys, pinned by
   `TestWebhookEnvelope_CamelCaseKeys`):
   `{jobId, jobCode, instanceId, scheduledFor?, firedAt, triggerKind,
   correlationId?, payload? (the job's JSON payload, verbatim),
   tracksCompletion, timeoutSeconds?, concurrent}`.
5. Credentials: when the job has an `applicationId`, resolve the
   application's outbound credentials — the **oldest active service
   account of that application**, its webhook auth token (bearer) and
   signing secret decrypted, **cached one minute per application**. Then:
   bearer present → `Authorization: Bearer <token>`; signing secret present
   → `X-FlowCatalyst-Signature: hex(HMAC-SHA256(secret, timestamp || body))`
   and `X-FlowCatalyst-Timestamp: <UTC, yyyy-MM-dd'T'HH:mm:ss.SSS'Z'>`.
   Every degraded case delivers anyway and logs a WARN naming the job:
   no application linkage ("re-sync the job from its application"), lookup
   failure, no credentials at all, secret without token, token without
   secret.
6. `POST targetUrl`, `Content-Type: application/json`, timeout
   `HTTPTimeout`. **Any 2xx** → `markDelivered` (`status = DELIVERED,
   delivered_at = now`) — not only 202 (`TestDispatcherTick_Accepts2xxNotJust202`).
   Transport error → failure "Network/HTTP error: …"; non-2xx → failure
   "HTTP <code> (expected 2xx): <first 500 bytes of the body>".
7. **Failure**: `terminal = attemptsAfter >= job.deliveryMaxAttempts`;
   `markDeliveryFailed(instance, message, terminal)` = `status =
   terminal ? DELIVERY_FAILED : QUEUED`, `delivery_error = message`
   (a non-terminal failure simply becomes `QUEUED` again and is retried on
   the next dispatch tick — **no backoff**, §6 D2). Terminal is logged as
   "delivery exhausted retries".

`DELIVERED` is the end of the scheduler's job; completion (`/complete`,
`scheduledjob.md` §6.2) and `hasActiveInstance` are the aggregate's.
Repository-write failures inside a step are logged and the instance is
left for the next tick.

## 4. The purger — always on, every minute, not leader-gated [C]

Runs whenever the platform database is open (no env toggle). Each tick,
each step independently logged on failure and the loop continues:

| Step | Statement |
|---|---|
| OAuth payloads (access/refresh tokens) | `DELETE FROM oauth_oidc_payloads WHERE expires_at < NOW()` |
| OIDC login states | `DELETE … oauth_oidc_login_states … expires_at < NOW()` |
| WebAuthn ceremonies | `DELETE … webauthn_ceremonies … expires_at < NOW()` |
| Portal login flows | `DELETE … portal_login_flows … expires_at < NOW()` (abandoned logins rely on this sweep) |
| Rate-limit events | `DELETE … iam_rate_limit_events WHERE occurred_at < NOW() − (longest configured window + margin)` — always against the Postgres store, even when Redis is the live limiter |
| OAuth previous secrets | clear `previous_secret_*` where the rotation window has lapsed |
| **Login-attempt partitions** | `EnsureQuarterlyPartition(now)` and `(now + 3 months)` — `iam_login_attempts_YYYY_qN` as migration 049 names them, `CREATE TABLE IF NOT EXISTS … PARTITION OF … FOR VALUES FROM (quarter start) TO (next quarter start)`; then `DropPartitionsOlderThan(now − 3 years)` — a schema-level `DROP TABLE` of quarterly partitions whose range ends before the cutoff; the default partition is never touched |

The Java purger (2026-09-05) runs every step above plus the four ruling
I-Q17 sweeps — expired e-mail PINs and trusted devices
(`iam_mfa_email_pins`, `iam_mfa_trusted_devices`), expired password-reset
tokens, and PENDING reset-approval requests past expiry marked `EXPIRED`
(defect 11, never deleted). Login-facing rows keep a **24 h grace** after
expiry before removal (`Purger.EXPIRED_ROW_GRACE`): every reader already
refuses an expired row, so the grace only preserves a day of evidence
for support questions. The rate-limit retention is `Policies.maxWindow()
+ 10 min`, mirroring Go's margin. The partition step is what keeps `lastSuccessAt`'s 400-day
window partition-pruned; without it every attempt after the pre-created
quarters lands in the default partition.

## 5. Tests the port must have (load-bearing → mutation-checked)

- **Poller**: an `ACTIVE` job whose latest slot is inside the window gets
  exactly one `CRON` instance with `scheduledFor == slot` and
  `last_fired_at == slot`; a second tick in the same window creates none
  (a counter that must not change); a `PAUSED` job never fires; `after`
  falls back to `createdAt` for a never-fired job; a non-leader poller
  inserts nothing.
- **Dispatcher, against a stub HTTP target**: envelope key set byte-for-byte
  (the camelCase test); 200 and 204 both → `DELIVERED`; 500 → `QUEUED`
  with `delivery_attempts = 1` and `delivery_error` starting `HTTP 500`;
  the `deliveryMaxAttempts`-th failure → `DELIVERY_FAILED`; orphan →
  `DELIVERY_FAILED` with the exact message; no target URL → the exact
  message; bearer and signature headers present when the application's
  service account has both, signature verifiable with the secret over
  `timestamp || body` (`…SignsFiringWithApplicationSecret`); absent
  application → delivered unsigned; the credentials cache (a counting
  repository: two dispatches for one application within a minute → one
  lookup).
- **Purger**: the partition step creates `iam_login_attempts_<this quarter>`
  and `<next quarter>` idempotently; a partition older than the cutoff is
  dropped, the default one is not; each other step deletes only expired
  rows (one expired + one live row per table, count the survivor).
- **Lifecycle**: interrupt stops both loops within one interval; the
  leader gate fails closed on an election error.

## 6. Defects and questions for the owner

- **D1** A failed `markFired` after a successful instance insert leaves
  `last_fired_at` behind, so the next tick fires the **same slot again**
  (a duplicate instance). Rare (a write failing right after another
  succeeded), but the two writes should be one transaction.
- **D2** No delivery backoff: a target returning 500 is retried every
  `DispatchInterval` (5 s) `deliveryMaxAttempts` times, then terminal.
  Same shape as the outbox's D1. Add a backoff?
- **D3** The poller has no claim (`SKIP LOCKED`); two leaders in a
  split-brain window both insert an instance for the same slot. The
  dispatcher's `list` has none either, so both would deliver it. Accept
  (election guarantees) or add `FOR UPDATE SKIP LOCKED` on the instance
  list?
- **Q1** Unsigned delivery on every credential problem is a deliberate
  availability-over-integrity choice in Go. Keep, or refuse to deliver
  unsigned when the job's application *has* a service account?
