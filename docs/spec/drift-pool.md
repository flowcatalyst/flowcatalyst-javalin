# Drift re-extraction: `internal/router/pool.go` (and `manager.go`) after `eff2a29`

Behavioural extraction of three Go commits that landed after the router spec
(`docs/spec/router.md`) was pinned at `eff2a29`. Written from `git show` on
each commit plus a full read of the current `pool.go` (1026 lines) in
`../flowcatalyst-go`. No Go or Java code was changed to produce this
document.

Commits, in landing order:

1. `7bbef5d` — router: split unreachable-target from application-rejection
   on delivery failure
2. `88b4549` — queue: pass the broker message id to Ack so SQS can't forget
   a delete (`pool.go` / `manager.go` parts only)
3. `8804827` — router: make `BLOCK_ON_ERROR` and `NEXT_ON_ERROR` actually
   differ

(Re-ordered from the task list into landing order — `88b4549` landed
between the other two, per each commit's author date.)

**Framing reminder honoured throughout:** Go is evidence of what Go does,
not of what is correct. Divergences are stated as divergences, not silently
resolved toward Go.

---

## Commit 1 — `7bbef5d`: split unreachable-target from application-rejection

### What changed, behaviourally

Before this commit, every retryable mediation failure — a 5xx of any kind,
a transport error, an open circuit breaker — was retried **in-pipeline,
forever**, with no broker interaction. A message stuck against a dead
target sat in process memory indefinitely; the broker never saw it again,
so its visibility timeout, redelivery count and DLQ could never act on it.

After this commit, a failure is split into two kinds by what it says about
the *message* versus the *target*:

- **The target ran the request and rejected it** (a plain HTTP 500). The
  mediator has already exhausted its own bounded burst before this outcome
  reaches the pool. Surviving that burst means the fault is more likely the
  message than a transient blip, so the message is **ACKed away and
  discarded** — one decision, no further pool-level retry.
- **The target could not be reached at all** (502/503/504, any other
  non-500 5xx, status 0/"unexpected status", a transport error, or an open
  circuit breaker). Nothing is wrong with the message. It is **released to
  the broker** (NACKed) instead of being retried in-process, so the
  broker's own expiry/DLQ mechanics — and the per-endpoint circuit
  breaker — become the things that actually protect against a sustained
  outage. 429 and `{"ack":false}` are untouched by this commit: the target
  answered and is healthy, so those still retry in-pipeline.

For an **ordered** group, "released to the broker" means the **whole
group** — the head in hand plus everything still buffered behind it in
`groupQs` — not just the head. Releasing the head alone would leave its
successors buffered in-process, so the head would come back **behind** them
on redelivery and the group would deliver out of order — the one thing
ordering exists to prevent. Both brokers redeliver a group in original
order (Postgres claims only the earliest-visible message per group; SQS
FIFO orders by `MessageGroupId`), so releasing the whole group and letting
it come back intact preserves order across the round trip.

For an **IMMEDIATE** message there is no group buffer, so only the message
itself is released.

### Which spec sections are now wrong

- **§2.6, table row for `NEXT_ON_ERROR`/`BLOCK_ON_ERROR` "Router side"
  column** and the accompanying prose describing "blocks the group,
  identical to `BLOCK_ON_ERROR`" for the *unreachable* case is superseded —
  it now correctly matches what §2.6 already documents as "RULED", but that
  ruling text was written **before** this re-extraction confirmed Go had
  actually landed it. The ruling table and the "Failure kind decides the
  disposition" prose in §2.6 are, after this extraction, **descriptions of
  Go's actual current behaviour**, not just a Java ruling diverging from
  Go — the RE-EXTRACTION OWED banner at the top of the file already flags
  this ("Recorded here as a Java deviation; Go now does it").
- **§3.4, "IMMEDIATE worker" bullet**: "process once (§3.5); if the verdict
  is *retry*: `Attempts++`... then dispatch again" — this description has
  no room for a *release* verdict at all. It is stale: it describes the
  pre-`7bbef5d` two-verdict world (terminal / retry). There is now a third
  verdict (`processRelease`) that IMMEDIATE must also handle, and it acts
  immediately, not through the retry counter.
- **§3.5 step 7**: *"Retryable outcomes **never touch the broker**
  (`guardrail_test.go:103-165`)."* — **This sentence is now false.** A
  502/503/504, a transport error, or an open breaker are still "retryable"
  in the sense that the message is not being discarded, but they now touch
  the broker immediately (NACK). The cited test file itself was rewritten
  by this commit specifically to assert the opposite for these three
  outcome kinds (`TestGuardrail_ReleaseOnUnreachable`,
  `TestGuardrail_ReleaseOnConnectionError`,
  `TestGuardrail_ReleaseOnCircuitOpen` all assert `acks.Load() == 0` but a
  broker-visible release, not "no broker action").
- **§3.6 invariant 5**: *"Retryable outcomes never release the message to
  the broker: no Nack, no Defer, no visibility change; the retry lives
  in-process with the in-flight entry kept. Only terminal outcomes (2xx
  success, 4xx config error, process-time duplicate) ACK."* — same
  falsehood as above, and additionally now omits that a plain 500 also
  ACKs (that part was already true pre-commit for config errors only; now
  a rejected 500 joins it).
- **§2.6, "Scope" paragraph**: *"IMMEDIATE messages keep Go's in-pipeline
  retry and still never touch the broker on a retryable outcome (§3.6)."*
  This describes **pre-`7bbef5d`** Go. Current Go's `runImmediate` handles
  `processRelease` exactly like the ordered path (nack immediately, single
  message since there is no group) — it does **not** retry unreachable- or
  rejected-target outcomes in-pipeline any more. This sentence needs
  rewriting, not just re-pointing at a line number.

### Exact new behaviour (implement-from-this, no further Go reading needed)

Given a mediation outcome for the **head of processing** (ordered head, or
an IMMEDIATE message):

| Outcome | ACKed? | NACKed? | Retried in-pipeline? | Scope of the action |
|---|---|---|---|---|
| 2xx success | ACK | — | — | this message only |
| 4xx (not 429) / bad mediation type / bad URL / marshal error | ACK | — | — | this message only |
| **HTTP 500 exactly** | **ACK** | — | — | this message only (siblings unaffected — see commit 3 for what "unaffected" means per mode) |
| **502 / 503 / 504** | — | **NACK** | — | **whole group** (head + everything buffered behind it) for ordered; this message alone for IMMEDIATE |
| Any other 5xx, or status 0 ("unexpected status") | — | **NACK** | — | same as above |
| Transport error / timeout / connection refused | — | **NACK** | — | same as above |
| Circuit breaker open (no call attempted) | — | **NACK** | — | same as above |
| 429 | — | — | yes (in-pipeline, honours `Retry-After`) | this message only, holds its place |
| 2xx + `{"ack":false}` (deferred) | — | — | yes (in-pipeline, deferred curve) | this message only, holds its place |

Order of operations for a group release: NACK the head first, then NACK
each buffered sibling in FIFO order, clearing the group's buffer and
`working` flag as it goes (so a redelivery of any released message spawns
a fresh drainer rather than finding stale state). This is `releaseGroup` /
`releaseBuffered` in current `pool.go`.

The redelivery delay handed to NACK for a release is the delay the outcome
itself carried (e.g. 30s for a 5xx/connection error, 5s for breaker-open),
turned into whole seconds via `nackDelay`; **on SQS this delay is a
documented no-op** (SQS `Nack` does nothing; the message simply becomes
visible again at its own visibility timeout). Only Postgres and NATS honour
it.

### Question this commit leaves open

The commit message states the classification explicitly ("A plain 500...
502/503/504 and transport failures..."), so the boundary is deliberate, not
accidental. What is *not* stated anywhere in the commit or the surrounding
code: **why exactly 500, and not "any status the app can plausibly emit for
an unhandled exception"** (501, 505 also arguably fit "the app ran and
threw", and Java's own `MediationOutcome.ErrorProcess.disposition()` — see
Java comparison below — already generalises this to "not 502/503/504/0" as
REJECTED, i.e. Java has already made the call that 501/505 etc. are
rejections too). Go pins the split at exactly one status code; nothing in
the commit says whether that was a deliberate narrow choice or a
placeholder. Worth an owner ruling, but Java's broader classification is
arguably the safer default (fewer 5xx statuses fall through to the
"unavailable, hold at broker" bucket that never resolves without operator
action on a target that is in fact never coming back for *that* status).

---

## Commit 2 — `88b4549`: pass the broker message id to Ack

*(only the `pool.go` / `manager.go` call-site changes; the SQS backend
mechanics that motivate it are described for context but are `queue/sqs`
internals, not part of the pool/manager behavioural surface this document
covers.)*

### What changed, behaviourally

SQS standard queues are at-least-once: after a successful delete, the
broker can still hand back a copy that was already in flight (a redelivery
racing the delete). The SQS backend guards against reprocessing such a copy
with `pendingDelete`, a map of recently-deleted `MessageId`s consulted on
every poll. Before this commit, `Consumer.Ack` took only a receipt handle;
the SQS backend translated handle → `MessageId` via a second map
(`receiptToMessageID`) populated at poll time and pruned by age past 1000
entries. **On a lookup miss, `Ack` silently skipped recording the delete —
no error, no log — and deleted the message anyway.** A busy queue plus a
message held in a slow-target retry loop long enough to fall out of that
1000-entry window meant the delete guard never armed, the next redelivery
was not recognised as already-handled, and **the target received the
message a second time.**

The fix changes the `Consumer.Ack` / `Acknowledger.Ack` signature to take
the broker message id directly (`Ack(ctx, receipt, brokerMessageID string)`
in Go), sourced from `QueuedMessage.BrokerMessageID` (populated at poll
time, carried through the whole pipeline, and already sitting in the
caller's hand at every one of the three call sites this touches:
`pool.go` `ackTracked`, the process-time duplicate-ack path in
`processOne`, and `manager.go`'s `ForceAckInFlight` and the
external-requeue-duplicate ack in `route`). With no lookup, there is
nothing to miss. `receiptToMessageID` is deleted outright.

### Which spec sections are now wrong

None of §2/§3/§6 state the pre-fix `Ack(ctx, receipt)` signature as
behaviour to preserve — this is a queue-backend implementation detail, not
a documented contract. No spec section needs correction for this commit;
it is recorded here for completeness and because the task named it
explicitly. The only spec-adjacent claim worth checking is §1.4's "Ack /
Nack on the source queue... never Defer, never ExtendVisibility" — that
remains true; this commit changes what's passed to Ack, not which action
is taken.

### Exact new behaviour

Every ACK call in `pool.go` and `manager.go` now threads
`qm.BrokerMessageID` alongside the receipt handle to the resolved
consumer's `Ack`. Postgres and NATS backends ignore the new argument (a
Postgres receipt embeds the row id and the row is deleted outright; NATS
acks the message object itself, so neither has an SQS-style at-least-once
redelivery to suppress). Only the SQS backend uses it, to key
`pendingDelete`.

### Question this commit leaves open

`pendingDelete` is retention-bounded by `PendingDeleteTTL`, pruned at the
top of every poll — bounded by *delete rate*, not total volume. The commit
message states this is intentional ("its size tracks delete RATE, not
total volume... a redelivery arriving after the window is harmless — the
target is idempotent"). That last clause is an **assumption about every
target**, not a guarantee the router enforces — nothing in `pool.go`
verifies or requires idempotency at the mediation target. This was already
true before the commit in spirit (any TTL-bounded guard has this shape);
worth flagging because the commit message states it as fact rather than as
an assumption resting on the caller.

---

## Commit 3 — `8804827`: make `BLOCK_ON_ERROR` and `NEXT_ON_ERROR` actually differ

### What changed, behaviourally

Before this commit the router held **zero references** to the
`BLOCK_ON_ERROR` / `NEXT_ON_ERROR` constants: `submit` branched only on
`RequiresOrdering()`, true for both, so the two ordered modes took an
identical code path — both got the FIFO buffer, neither got mode-specific
error handling. This is exactly the gap §2.6 already documented as "the
router half of the Q1 ruling is untouched" and the RE-EXTRACTION OWED
banner names as "**Q1.** Makes `BLOCK_ON_ERROR` and `NEXT_ON_ERROR`
actually differ... Recorded here as *the largest deliberate deviation*
('Go blocks the group for both modes'); that is no longer true."

After this commit, the two modes differ in **exactly one place**: what a
**terminal failure of the head** (a plain 500, or a 4xx) does to the rest
of the group.

- **`NEXT_ON_ERROR`**: the head is ACKed away (discarded) and the drainer
  **continues** to the next buffered message in the group. The failed
  message is not retried by the router again; the platform surfaces it for
  review and re-queues on resolution.
- **`BLOCK_ON_ERROR`**: the head is ACKed away, and then **every message
  still buffered behind it in this process is also NACKed back to the
  broker** (not ACKed — see the divergence below) and the drainer exits.
  The group's `working` flag is cleared, so a redelivery of any of those
  released siblings — or a fresh submit — spawns a new drainer. The
  platform re-sends the whole group in order once the failure is resolved.

Mechanically, this required splitting what had been one terminal-ACK
verdict (`processDone`, covering both "delivered" and "failed and ACKed
away") into two: `processDone` (success) and a new `processDiscarded`
(failed but ACKed away) — because the drainer needs to distinguish them to
know whether to keep going. The ACK itself is identical in both cases; only
the signal to the group differs.

Everything else about the two ordered modes is unchanged and shared: the
same FIFO buffer, the same ordering guarantee, the same handling of an
unreachable target (release/NACK the **whole** group regardless of mode —
commit 1's behaviour, untouched by this commit), and the same handling of
429 / `{"ack":false}` (retry in place; the target is healthy).

The commit message also records a **scope caveat**: for the platform's own
dispatch jobs, this branch is largely unreachable in Go today, because
their mediation target (`/api/dispatch/process`) always returns
`200 {ack:true}` even when the subscriber itself failed — the failure is
recorded platform-side and the router only ever sees success. So this
commit's effect is live for operator-submitted messages via the router API
and any producer that targets a subscriber directly, not (yet, in Go) for
the platform's dispatch flow. Router.md §2.6 already separately records
that Java's scheduler (Q16 ruling) *does* propagate `dispatchMode`, making
this branch live for Java in a way it currently is not for Go's own
dispatch flow — that asymmetry is already documented and doesn't need new
spec text, but it does mean the material divergence below is **not
theoretical for Java**: Java's dispatch jobs can reach `BLOCK_ON_ERROR`
today, in production, where Go's cannot.

### Which spec sections are now wrong

- **§2.6 table**, "Router side — Go still" column, row `NEXT_ON_ERROR`:
  *"blocks the group, identical to `BLOCK_ON_ERROR` (`pool.go:272`)"* — no
  longer true; the line number is also stale (current `pool.go` is 1026
  lines and the branch this commit added is around line 618-632).
- **§2.6, "Consequence for the port" paragraph**: *"a Java router that
  merely mirrors `pool.go` will pass every Go-derived test and still be
  wrong"* — this framing (mirroring Go = wrong) is now **inverted** for the
  head-disposition and mode-differentiation behaviour: Go has adopted the
  ruling. It is **not** inverted for the BLOCK_ON_ERROR-siblings question —
  see "MATERIAL DIVERGENCE" below, where mirroring Go would now be the
  *safer* choice and Java's existing behaviour is the outlier.
- **§2.6 "Failure kind decides the disposition" table**, the row for
  "500 and other 5xx", right-hand cell: *"Still failing after that: ACK
  the head back to the broker, discarding it. Then per mode:
  `BLOCK_ON_ERROR` also ACKs the siblings and blocks the group;
  `NEXT_ON_ERROR` leaves the siblings and continues with the next
  message."* — **the "`BLOCK_ON_ERROR` also ACKs the siblings" clause is
  the ruling this spec recorded, and it is what Java implements — but it is
  now confirmed to be a deliberate deviation from what Go actually does**
  (Go NACKs the siblings, not ACKs). This is the pre-existing owner
  question the RE-EXTRACTION OWED banner already surfaces; this
  re-extraction confirms the Go side of it precisely (see below) and adds
  nothing new to the open question except a firmer citation.

### Exact new behaviour

Given a **terminal failure of an ordered head** (plain 500, or a 4xx —
i.e. `processDiscarded`):

| Dispatch mode | Head | Buffered siblings (never delivered) | Drainer |
|---|---|---|---|
| `NEXT_ON_ERROR` | ACK (discard) | **left untouched in the buffer** — not acted on at all by this failure; delivery continues with them in order | continues, does not exit |
| `BLOCK_ON_ERROR` | ACK (discard) | **NACKed back to the broker**, in FIFO order, buffer cleared, `working` flag cleared | exits; a later submit or a broker redelivery of a released sibling spawns a fresh drainer |

For `BLOCK_ON_ERROR`, note precisely what "NACKed" means here versus
commit 1's group release: this is `releaseBuffered`, which nacks with
`delay=nil` (no specific redelivery delay requested) and reason
`"head failed under BLOCK_ON_ERROR"` — mechanically identical to a
commit-1 release except it does **not** also touch the head (the head was
already ACKed by `processOne` before this branch runs) and always applies
regardless of the outcome's own delay.

### MATERIAL DIVERGENCE FOUND WHILE CHECKING — needs an owner ruling

This is the finding the RE-EXTRACTION OWED banner in `router.md` already
flags at the top of the file; this re-extraction confirms it precisely
against the current Go source, and confirms it is **still present in the
current Java implementation**, not fixed:

Under `BLOCK_ON_ERROR`, with the head terminally failed, Go and Java do
opposite things to the untried siblings:

- **Go** (`8804827`, `releaseBuffered` in `pool.go`) **NACKs** the buffered
  siblings back to the broker. They redeliver once the failure is resolved
  and the group is re-submitted or a redelivery arrives; the broker holds
  them meanwhile. Nothing is lost if the platform's re-queue mechanism is
  broken, slow, or never built — the messages are still sitting on the
  broker.
- **Java** (`Pool.handleHeadFailure`, `HeadFailure.BlockGroup` branch,
  `Pool.java:474-480`) **ACKs** the buffered siblings — `broker.ack(blocked.failed(), ...)`
  then `blocked.siblings().forEach(sibling -> broker.ack(sibling, ...))`.
  The messages are **deleted from the broker** and exist only wherever the
  platform's human-review-triggered re-queue re-sends them from.

Java's is the riskier of the two by a wide margin, for the reason the
banner already states: it destroys untried messages and depends entirely
on a re-queue path that must exist and must be correct — a bug or an
outage in that path is silent, permanent data loss for every blocked group
until someone notices messages never arrived. Go's needs nothing to exist
beyond the broker itself. **Do not port around this — it needs an owner
decision**, and per the framing rule for this document, Go being "wrong"
in the original Q1 write-up (which predates Go actually implementing this
split) is no longer the right lens: Go now has an implementation, and it
is the safer one.

### Question this commit leaves open

The commit message is explicit that this differentiation is
`processDiscarded`-only — a rejected head. It says nothing about whether a
**retry-exhausted** ordered head (if such a path existed) should behave the
same way; it doesn't need to, because in the current design a rejected
head is a one-shot decision (no pool-level retry budget for `processDiscarded`
outcomes in Go — see commit 1). Not a real open question, just worth
confirming for the Java comparison below: Java's `RetryHead` mechanism (a
pool-level 3-attempt burst before reaching `Continue`/`BlockGroup`) has no
Go equivalent to cross-check against, because Go never retries a rejected
head at the pool level at all.

---

## Java comparison

Java source read: `Pool.java`, `OrderedGroups.java`,
`wire/MediationOutcome.java`, `wire/DispatchMode.java` (implied),
`HttpMediator.java`, `manager/QueueBroker.java`, `pool/Broker.java`,
`queue/Acknowledger.java`, `queue/sqs/SqsQueue.java`,
`inflight/InFlightTracker.java`, `policy/RetryPolicy.java`.

### Commit 1 — unreachable vs rejection split

**Ordered heads: AGREE.**
`MediationOutcome.ErrorProcess.disposition()`
(`wire/MediationOutcome.java:139-144`) classifies `statusCode == 0 || 502 ||
503 || 504` as `RETURN_TO_BROKER` and everything else (including 500, and
also 501/505/etc. — a broader net than Go's exact-500 check) as `REJECTED`.
`ErrorConnection` and `CircuitOpen` are always `RETURN_TO_BROKER`
(`MediationOutcome.java:153-155`, `195-197`). `OrderedGroups.onHeadFailure`
(`OrderedGroups.java:152-164`) sends `RETURN_TO_BROKER` straight to
`HeadFailure.ReturnGroup`, which `Pool.handleHeadFailure`
(`Pool.java:462-469`) resolves by NACKing the head **and every buffered
sibling** — matches Go's whole-group release exactly, including the
"nothing is wrong with these messages" framing in both codebases'
comments.

**IMMEDIATE messages: DIVERGE — and this is the most serious finding in
this document.**

`Pool.runImmediate` (`Pool.java:294-362`) only special-cases one
disposition:

```java
if (outcome.disposition() == MediationOutcome.Disposition.RETURN_TO_BROKER) {
    broker.nack(message, delay, "target-unavailable");
    return;
}
if (message.attempts() + 1 >= MAX_IN_PIPELINE_ATTEMPTS) {
    ...
    broker.nack(message, delay, "retry-budget-exhausted");
    return;
}
```

(`Pool.java:321-338`, `MAX_IN_PIPELINE_ATTEMPTS = 10` at `Pool.java:84`.)

`RETURN_TO_BROKER` is handled correctly and matches Go's `7bbef5d` fix: an
unreachable target is NACKed immediately, one message, no pool-level
retry.

But **`REJECTED`** (a plain 500, or the broader Java 5xx-not-gateway set)
falls into neither branch — it is treated identically to
`RETRY_IN_PLACE` (429, deferred), retried **in the generic loop up to
`MAX_IN_PIPELINE_ATTEMPTS` (10) attempts** along `RetryPolicy.DELIVERY`'s
full curve (3 fast attempts at 1s/2s spacing, then an exponential curve to
a 5-minute ceiling — `policy/RetryPolicy.java:62-74`), and **only then**
handed back to the broker via NACK — never ACKed/discarded.

Compare to Go's `runImmediate`, which shares `processOne` with the ordered
path: a plain 500 there returns `processDiscarded` (ACK, one shot, no
retry loop at all — the "3 attempts" already happened inside the
mediator's own burst before `processOne` ever saw the outcome), and
`runImmediate`'s handling of that verdict is `if result != processRetry {
return }` — nothing more happens. Go **never** puts a rejected IMMEDIATE
message through a multi-attempt pool-level retry loop, and it never NACKs
one back to the broker either — it discards it once.

Java's own `HttpMediator` docstring
(`HttpMediator.java:26-30`) explains why the mechanism differs: *"Go
retries up to three times inside a single `Mediate` and then lets the pool
back off again; the Q3 ruling collapsed those two layers into one
schedule, which now lives in `RetryPolicy` and is applied by [Pool]."*
That collapse is implemented for **ordered heads** — `OrderedGroups.rejected()`
(`OrderedGroups.java:168-179`) retries a rejected head up to
`rejectionBudget` (= `RetryPolicy.DELIVERY.burstSize()` = 3) times via
`HeadFailure.RetryHead`, then gives up via `Continue`/`BlockGroup` (ACK).
**It was never carried over to `runImmediate`.** The result: an IMMEDIATE
message against a target that reliably 500s is never discarded by the
Java router. It cycles between a 10-attempt pool-level retry (which can
run for minutes, given the curve's 5-minute ceiling) and a broker NACK
that hands it straight back for another 10-attempt cycle — indefinitely.
This is exactly the "loops forever" failure mode `7bbef5d`'s commit message
describes fixing, still present in Java, but only for the IMMEDIATE path.
Ordered heads are correctly fixed.

Concretely: `Pool.runImmediate` needs a third branch — `REJECTED`,
analogous to `OrderedGroups.rejected()` — that discards
(`broker.ack(message, "rejected")`) once a rejection-specific budget (3,
via `backoffs.delivery().burstSize()`, the same constant already used for
ordered heads) is spent, rather than falling through to the generic
10-attempt retry-then-NACK path that currently only correctly serves
`RETRY_IN_PLACE` outcomes (429 / deferred).

### Commit 2 — broker message id on Ack

**AGREE, and Java never had the bug.**

Java's `Acknowledger.ack(QueuedMessage message)`
(`queue/Acknowledger.java:31`) and `Broker.ack(QueuedMessage message)`
(`pool/Broker.java:19`) were designed from the start to take the whole
`QueuedMessage` record, which carries `brokerMessageId` as a first-class
field (`pool/QueuedMessage.java:22`) alongside — not derived from —
`receiptHandle`. `SqsQueue.ack` (`queue/sqs/SqsQueue.java:280-294`) reads
`message.brokerMessageId()` directly off the argument to key
`pendingDelete` — there is no receipt-handle-to-message-id translation map
in the Java SQS backend at all, so there is no lookup that can silently
miss. `QueueBroker.withFreshestHandle`
(`manager/QueueBroker.java:146-151`) substitutes only the receipt handle
when the tracker has a fresher one, via `QueuedMessage.withReceiptHandle`
which preserves `brokerMessageId` unchanged — so even the freshest-handle
substitution path Go's bug lived in cannot lose the broker id in Java.
This is a case where the Java port's different data-carrying design
(message-object-in, not string-in) structurally avoided the class of bug
Go had to patch.

### Commit 3 — `BLOCK_ON_ERROR` vs `NEXT_ON_ERROR`

**Mode differentiation itself: AGREE.** Java has always had this
distinction — it is not new to this re-extraction, it's the pre-existing
Q1 ruling implementation (`OrderedGroups.HeadFailure` sealed interface,
`Pool.handleHeadFailure`). `NEXT_ON_ERROR` → `HeadFailure.Continue` →
`broker.ack(carryOn.failed(), ...)`, `yield true` (drainer continues) —
matches Go's post-`8804827` `NEXT_ON_ERROR` path (ACK head, fall through to
next iteration) exactly.

**Sibling disposition under `BLOCK_ON_ERROR`: DIVERGE — see "MATERIAL
DIVERGENCE" above.** `Pool.java:474-480`:

```java
case HeadFailure.BlockGroup blocked -> {
    broker.ack(blocked.failed(), "rejected-group-blocked");
    blocked.siblings().forEach(sibling -> broker.ack(sibling, "rejected-group-blocked"));
    yield false;
}
```

ACKs every sibling — permanently deleting them from the broker — where Go
NACKs them (`releaseBuffered` → `nackMsg` per sibling, no delay,
`pool.go:664-704`). This is the divergence the top-of-file banner in
`router.md` already names as needing an owner ruling; this document
confirms it against the current Go source precisely (Go: `Nack`, not
`Ack`) and against the current Java source precisely (Java: `ack`, not
`nack`) so the ruling can be made without re-reading either codebase.

Restated plainly since it's the highest-stakes finding here: **if the
platform's re-queue-on-resolution path is not built, or has a bug, or is
down, every `BLOCK_ON_ERROR` group that ever hits a rejected head loses
its untried siblings permanently in the Java router today.** Go's
behaviour degrades to "redelivered forever until someone looks," which is
inconvenient but not lossy. This should be treated as a candidate bug fix,
not merely a spec correction, pending the owner ruling.

---

## Caveat on this document's Java-side findings (added by the orchestrator)

**The Go-side extraction is sound; parts of the Java comparison are not.**

This extraction ran while a *different* agent had uncommitted changes to
`Pool.java`, `Broker.java`, `QueueBroker.java` and `RouterApi.java` in the
working tree. Those changes have since been reverted. So where this document
describes Java's `runImmediate` as capping retries at ten attempts and NACKing
on exhaustion, it is describing code that no longer exists — that was the other
agent's in-flight work, not the committed baseline.

Re-checked against the reverted `Pool.java`:

- `runImmediate` **does not read `outcome.disposition()` at all.** Every
  `Attempt.Failed` — `REJECTED`, `RETURN_TO_BROKER`, `RETRY_IN_PLACE` alike —
  takes the same path: back off, increment attempts, loop, for ever.
- So both gaps this document identifies are real, and *worse* than it states.
  There is no ten-attempt cap to fall through: an IMMEDIATE message against a
  persistently-500ing target retries in place indefinitely, and so does one
  against an unreachable target or an open circuit.
- The ordered path is unaffected — `handleHeadFailure` switches on the
  disposition correctly.

The Go findings, the spec-section citations, and the `BLOCK_ON_ERROR` sibling
divergence are all read from `git show` and are unaffected by this.

**Corollary worth keeping:** an extraction that reads the working tree reads
whatever is in it. Run these against a known commit, or against a clean tree.
