# A dispatch job carries its own queue priority (owner ruling 2026-09-18)

## Problem
Priority lives only on the subscription (`msg_subscriptions.queue`,
`DEFAULT` | `HIGH_PRIORITY`). The scheduler resolves it at publish time from
the job's `subscription_id` (`SubscriptionPriorityCache`, 60 s TTL). Two
consequences:

1. **A direct job cannot ask for priority.** `POST /api/dispatch-jobs` and
   `/api/dispatch-jobs/batch` (the SDK's `createDispatchJob`) create jobs with
   no subscription, so every one publishes at `DEFAULT`.
2. **A job's priority is not stable.** Editing or deleting the subscription
   changes where an already-created job publishes.

## Ruling
The job carries its own priority; the subscription seeds it.

### R1 — `queue` column on the job
`msg_dispatch_jobs` gains `queue` (nullable, same width/shape as
`msg_subscriptions.queue`). Nullable and absent is the legacy state, not an
error. Both repos add the same DDL as their next migration (Java
`V10__…`, Go `054_…`), written `ADD COLUMN IF NOT EXISTS` so whichever
deploys first wins and the other is a no-op — the two share one database.

### R2 — fan-out copies the subscription's value
When fan-out raises a job from a matching subscription it writes that
subscription's `queue` onto the job verbatim (including legacy text — the
read side already tolerates it, see R4). A subscription with no value writes
none.

### R3 — the create API accepts it
`POST /api/dispatch-jobs` and `/api/dispatch-jobs/batch` accept an optional
`queue` on each item. It is **validated on the way in**, the same way a
subscription's is: `DEFAULT` | `HIGH_PRIORITY` only, anything else is a 400
naming the field. Absent means absent — never silently `DEFAULT` in the
column, so "not asked for" stays distinguishable from "asked for DEFAULT".

### R4 — resolution order at publish
`DispatchDestinationResolver` (Go: its equivalent) resolves priority as:

1. the **job's** `queue` when it is one of the two recognised values;
2. else the subscription's, through today's cache, when the job has a
   `subscription_id`;
3. else `DEFAULT`.

Unrecognised text in either place reads as `DEFAULT` — today's rule (R6),
never an error, never a dropped job.

### R5 — the contract travels
The new field goes on the OpenAPI lockfile's dispatch-job create request
(single and batch). **Go regenerates the lockfile** (`make api-bump`) and
Java copies it byte-for-byte, as before. The TypeScript and Laravel SDK
clients regenerate from it; the Java SDK's `CreateDispatchJobDto` gains the
field by hand.

## Tests — each must fail under its mutant
| # | Assert | Mutant |
|---|---|---|
| T1 | a job created via `POST /api/dispatch-jobs` with `queue: HIGH_PRIORITY` stores it, and publishes to the `…-HIGH_PRIORITY` queue | ignore the field on create |
| T2 | the same with `queue` absent stores null and publishes to `…-DEFAULT` | default the column to `DEFAULT` on create |
| T3 | `queue: "workers-high"` on create → 400 naming the field; no job row is written | accept any string |
| T4 | batch create: per-item `queue` is honoured independently (one `HIGH_PRIORITY`, one absent) | read the first item's value for all |
| T5 | fan-out from a `HIGH_PRIORITY` subscription writes `HIGH_PRIORITY` on the job | leave the column null and rely on the lookup |
| T6 | a job whose own `queue` is `HIGH_PRIORITY` but whose subscription says `DEFAULT` publishes **HIGH_PRIORITY** (job wins) | consult the subscription first |
| T7 | a legacy job (null `queue`) with a `HIGH_PRIORITY` subscription still publishes `HIGH_PRIORITY` (fallback intact) | drop the subscription fallback |
| T8 | a legacy job with unrecognised text in either place publishes `DEFAULT`, no error | throw on unrecognised text |

## Docs
Update each repo's dispatch/scheduler docs where priority resolution is
described (Java `docs/spec/deployed-dispatch.md` §3 and the subscription
spec's priority note; Go `docs/`), and the dispatch-job field table.
