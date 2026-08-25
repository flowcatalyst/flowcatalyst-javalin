# Drop-in verification — plan

How we get from "the Java router passes its own tests" to "we can turn Go off".

This is the largest remaining piece and the only one that answers the actual
question. It is **weeks, not days**, and it is worth being explicit about why
before anyone starts.

## What must be true, and what is not enough

Passing tests on both sides proves each is self-consistent. It does not prove
they agree. Two suites written separately against the same prose agree by
luck.

The conformance corpus (`conformance/`) fixed that **for one attempt in
isolation**: given this HTTP response, what outcome, what warning, what
breaker effect. That is the unit level and it is done.

What it cannot reach is everything that depends on **sequence, time and
state**: retry curves, breaker transitions, ordered-group draining, leadership
changes, redelivery. Those are where the defects found this month actually
lived — the ACK-deleted group, the interrupt leak, the semaphore resize — and
not one of them would have been caught by a single-attempt corpus.

## The observable contract

The thing to compare is **the sequence of broker actions per message**:

```
message-id → [ack(receipt, t), nack(receipt, delay, t), release(receipt, t), … ]
```

Not logs. Not metrics. Not internal state. Those differ between
implementations for uninteresting reasons and will generate noise forever.
Broker actions are exactly what decides whether a message is delivered,
duplicated or lost, and they are identical in vocabulary on both sides.

Java already emits this stream: `MessageSettled` JFR events carry message id,
queue, action, reason and requested delay. Go would need an equivalent hook —
one function on the broker path, not a redesign.

## Phase A — deterministic sequence harness (the bulk of the work)

A scripted scenario is a list of `(message, target response)` pairs plus a
controlled clock. Run it through both routers; compare the action sequences.

Java is already built for this, which is the reason to do it this way:

| Seam | Where |
|---|---|
| injectable `Clock` | `Pool`, `HttpMediator`, `CircuitBreaker`, `InFlightTracker` |
| injectable backoff curves | `Pool.Backoffs` |
| `Broker` interface | `Pool` talks to it, never to a queue |
| fixed config, no HTTP | `RouterServer.ConfigSource.fixed` |
| target is just an HTTP server | `HttpMediator` takes a URL |

So a scenario needs no AWS, no NATS, no Postgres and no wall-clock waiting.
Go's equivalents need checking; where a seam is missing, that is a Go change
and it should be specced the way `conformance/go-runner.md` specs the runner.

Scenarios worth writing, in rough priority — each one is a behaviour a defect
has already hidden in:

1. ordered group, head fails terminally, siblings untried — **the open
   `BLOCK_ON_ERROR` question; write this one first, it may settle it**
2. ordered group, target unreachable mid-drain
3. breaker opens mid-group, then recovers
4. redelivery arrives while the original is still in flight
5. shutdown mid-backoff (the interrupt leak)
6. leadership lost and regained (the failover bug)
7. rate limit with `Retry-After` across several attempts
8. flushed group suppressing its siblings
9. concurrency resized under load with a backlog queued
10. poison message on Postgres

**Sizing: 1–2 weeks.** The harness is a few days; the scenarios are the work,
and each one is a small specification argument before it is code.

## Phase B — shadow run

Both routers consuming a mirrored queue in staging, Java taking no live
traffic. Compare action sequences over real message shapes and real target
behaviour, which is where the assumptions a hand-written scenario encodes get
tested.

Needs: queue mirroring, a comparison job, and somewhere to put the diffs.
**Sizing: ~1 week**, mostly infrastructure rather than router work.

## Phase C — cutover rehearsal

Java live, Go in shadow, on a non-critical client first. Practise the rollback
until it is boring. **Sizing: days, spread over weeks of soak.**

## Order, and the honest dependency

Phase A depends on the router spec being **correct**, and as of 2026-08-25 it
is not — see the drift warning at the top of `docs/spec/router.md`. Six Go
commits landed after the extraction and two of them adopt rulings recorded
here as deliberate deviations. Writing scenarios against a stale spec would
encode the staleness into the harness, where it is much more expensive to find.

So: re-extract first, then Phase A.

## What would make this unnecessary

Nothing available. It is tempting to argue the conformance corpus plus a good
test suite is enough. It is not, and the evidence is local: every defect found
in the router this month passed a test that was already there and looked
right. Sequence and time are where they live.
