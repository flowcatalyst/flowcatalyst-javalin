# Java catch-up: everything Go changed on 2026-09-22

One day, five owner rulings, all pushed to Go `main`. Read in this order; the
two detailed hand-offs carry the rationale and the tests to mirror, this page
is the index plus the two items that only exist here.

| Go commit | What | Detailed hand-off |
|---|---|---|
| `1c37a33` | Router: full pool **defers** (reservation schedule) instead of parking the queue; NATS `max-deliver`/`max-ack-pending` unlimited; buffer ×40/100; deferral budget | `docs/java-handoff-2026-09-22-router-hol-deferral.md` |
| `4ab28ba` | `/bff/debug/dispatch-jobs` 500 (hand-written SELECT missed `queue`) — Go-only bug | — |
| `e2faf9a` | **Connection's service account signs** dispatch deliveries (reverses 2026-09-19); unsigned deliveries record why | `docs/java-handoff-2026-09-22-delivery-credentials.md` |
| `4816b59` | Failed attempts keep the response body; `request_info` on attempts; **sign** action; 401/403 fail fast; `descriptor`; read projection gets `descriptor`+`metadata`; SPA job panel; deferral budget 5k→15k | same doc, "Addendum"; HOL doc, "Addendum" |
| `cbd05b2` | Laravel SDK `php artisan flowcatalyst:verify-signature` | same doc, "Addendum" |
| this commit | Deferred copies dedup by **message id**; stale-QUEUED threshold 5m→75m | below |

## Schema (shared database — apply whichever platform deploys first)

Go migration 057:

- `msg_dispatch_jobs.descriptor VARCHAR(255)` NULL
- `msg_dispatch_jobs_read.descriptor VARCHAR(255)` NULL, `.metadata JSONB NOT NULL DEFAULT '[]'`
- `msg_dispatch_job_attempts.request_info JSONB` NULL

All `ADD COLUMN IF NOT EXISTS`; Java's own migration for the same columns
should be the same. jOOQ regen. The read projector must copy
`descriptor` and `metadata` from the write row (Go `internal/stream/dispatch_jobs.go`).

## The two items only on this page

### A. Deferred copies are keyed by message id (router)

Production after `1c37a33`: SQS and the router dashboard showed 500 in flight
for 200 pending jobs. Cause: the platform's **stale-QUEUED recovery** (5 min)
reverts a job whose message the router has *deferred* (up to 1 h) to PENDING,
the poller republishes it with a deliberately fresh dedup id (spec: the broker
must never dedup), and the router — which had **removed** the deferred copy's
tracker entry — treated the new copy as new and deferred it too. Every deferred
job spawned another copy every 5 minutes.

Fix, both halves:

1. **Router** (`InFlightTracker`): a deferral now *keeps* the tracker entry and
   stamps `DeferredUntil` (`MarkDeferred(messageId, until)`), instead of
   removing it. Consequences, all in `Register`:
   - a copy under a **different broker id** for a deferred message →
     `RegisterExternalRequeue` → the caller ACKs (deletes) it. The parked copy
     comes back on its schedule.
   - the deferred copy **itself** returning (same broker id, or blank broker
     id) → `RegisterNew` after clearing `DeferredUntil` and adopting the fresh
     receipt — the caller submits it as usual.
   - `Count()` excludes deferred entries (drain must not wait for the
     broker's parked copies); `DeferredCount()` reports them.
   - the reaper's idle rule skips a deferred entry until
     `DeferredUntil + 5 min`; the absolute ceiling (2 h) still applies, so a
     copy the broker never returns ages out and a later republish is admitted.
   - `deferMsg` marks even when the broker `Defer` call fails (the message
     returns at its natural visibility, still as itself). A message with no
     consumer to defer to is removed as before.

   Java: `InFlightTracker` + `Pool`'s deferral path. Tests to mirror: Go
   `internal/router/inflight_deferred_test.go` (tracker) and
   `TestDeferredMessageRepublishedCopyIsDeletedNotDeferred` in
   `pool_admission_test.go` (end to end: first copy deferred, second copy with a
   new broker id ACKed, deferred count stays 1).

2. **Platform** (`scheduler.DefaultConfig().StaleAfter`): 5 m → **75 m**. It
   must exceed the router's deferral horizon (1 h): a job whose message is
   parked for capacity sits QUEUED legitimately for that long. A genuinely
   stranded QUEUED row (crash between commit and publish) now waits 75 min —
   rare, and cheap next to a duplicate storm on every backlog. Java's
   equivalent stale-recovery threshold must match. (Not env-configurable on
   either side today; if it becomes one, document that it must track
   `FC_ROUTER_DEFERRAL_MAX_DELAY_SECONDS`.)

### B. Signature format — confirmed, no change

Owner asked whether the Laravel check could be a timestamp-formatting issue.
No: the platform signs `HMAC-SHA256(secret, ts + body)` with `ts` in
`2006-01-02T15:04:05.000Z` and sends that exact string as
`X-FlowCatalyst-Timestamp`; the SDK HMACs the **raw header string** with the
raw body, and parses the timestamp only for the replay window. Pinned by the
SDK's `WebhookValidatorCompatTest` and Go's `TestProcess_SignsSubscriberDelivery`.
Java's signer and `WebhookSignature` must keep the same bytes.

## Owner principles restated (apply everywhere)

- **Never skip silently.** A fallback that fires must say so where an
  operator will see it (attempt error message, warning, log with the reason).
- **Record what was sent, not only what came back.** Diagnosis must not need
  database archaeology.
- A UI field that nothing reads is a defect (the connection's service-account
  field was one).
