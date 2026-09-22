# Go fix: remove the stale-`QUEUED` sweep; reaper redrives `PROCESSING` at 15 minutes

**Owner ruling 2026-09-22 (evening), on the conflict with Go `bbd5488`/`b60d75c`:** Go raised the
sweep to 75 minutes the same day; the owner rules **Java's way** — no sweep at all, reaper at 15
minutes — for both platforms. Go's router-side dedup of a republished copy (`bbd5488`) is kept and
mirrored in Java; it remains useful because the reaper's redrive can still republish.

Owner rulings 2026-09-22, found while landing the router head-of-line deferral
(`java-handoff-2026-09-22-router-hol-deferral.md`). Java: commit "scheduler: no stale-QUEUED
recovery …" on `main`.

## Why

A dispatch job stays `QUEUED` until the router delivers it and the processing endpoint advances
it. The stale sweep (`internal/platform/scheduler/stale_recovery.go`, `StaleAfter` 5 min) cannot
tell "lost" from "not delivered yet": a message the router **deferred** for a full pool (up to the
1 h horizon) got reverted to `PENDING` and re-published every 5 minutes for the whole deferral —
a 10k backlog ⇒ ~120k in flight against SQS FIFO's 20k ceiling. The deferral fix would have wedged
the queue by another door.

## Rulings

1. **No automatic resend of a `QUEUED` job.** Remove the stale sweep (`stale_recovery.go`, the
   `StaleAfter`/`StaleScanInterval` config, the scheduler's second loop, `reclaimStaleQueued` or
   its equivalent query). A broker-held job is the broker's until delivered; an expired message is
   simply gone (SQS retention goes to 4 days, IaC). The old PHP mediator needed the resend; this
   one does not.
2. **The reaper redrives `PROCESSING` rows older than 15 minutes** — `reaper.go`
   `DefaultProcessingLiveAfter` 45 min → 15 min, the old system's value. Accepted: a delivery still
   hanging on its second 15-minute attempt is redriven — a duplicate to a target that is already
   broken.
3. The dedup-nonce reasoning on `MessageDeduplicationId` still holds; its example is now the
   reaper's redrive, not the stale sweep. Update the comments that name the sweep
   (`sqs.go`'s publisher, the `DispatchPublisher` contract, the repository).

## Tests to mirror

- A row `QUEUED` with `updated_at` an hour ago stays `QUEUED` across scheduler ticks and is never
  re-published (Java: `DispatchSchedulerTest.aRowQueuedForAnHourIsNeverRevertedOrRePublished`;
  mutant: let the claim re-take `QUEUED` rows ⇒ fails).
- The reaper's default cutoff is 15 minutes.
- The NoopPublisher's doc: a claimed row is now never recovered in degraded mode.
