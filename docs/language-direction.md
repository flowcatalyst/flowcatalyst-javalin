# Language direction — parked 2026-08-26

Owner parked the TypeScript question on 2026-08-26 and resumed the Java port.
**This is a record to reopen from, not an open question.** Nothing here blocks
work; if the question is reopened, start at "The experiment" below rather than
re-deriving the arguments.

Written from a session that had read Go's API layer closely
(`handlers_dashboard.go`, `handlers_misc.go`, `handlers_mutations.go`,
`dto.go`), `queue.go`, the three backends' metrics paths and `traffic.go`,
plus most of the Java router. `pool.go` and `mediator.go` were known through
the spec rather than line by line — weigh the core-engine claims accordingly.

## What prompted it

Owner: *"Something is making me feel like both Java and Go are too heavy in
different ways. But some of this is just my unease at not working enough with
the code. And probably feeling a bit lost."*

That self-diagnosis is worth keeping attached to the technical argument,
because it changes what the right intervention is. See "The experiment".

## Java vs Go, as observed in this codebase

### Where Java is genuinely ahead

Not style — the compiler does work Go cannot.

`MediationOutcome` is seven records with `disposition()` and `statusCode()`
**abstract**: every implementation must answer, and an eighth outcome will not
compile without an opinion. Go models the same thing as a struct with a
`Result` enum and a `switch` with a `default:` arm. **Three defects in this
system's history came out of that arm** — 3xx falling through and retrying
for ever at status 0, 501 inheriting the generic `>= 500` branch, and the
`targetUnavailable()` default that ACK-deleted whole ordered groups. Java had
the third one too, as a *defaulted interface method*; the fix was making it
abstract so the compiler collected the cases. Go has no equivalent move.

Second: absence. `Traffic.Status` carries `Optional<Instant> lastChange`; Go
carries `time.Time` and the handler asks `!st.LastChangedAt.IsZero()`.
Zero-value-as-absent is a standing tax, and it is the same confusion that made
`totalDeferred` read as a fact when it is structurally zero.

### Where Go is ahead

Go's `dashboardQueueStats` is ~25 lines and immediately obvious; the Java
equivalent is a handler, an extracted row builder, and a DTO record with
`@JsonProperty` annotations.

More importantly `huma.Register(api, huma.Operation{Method, Path, Summary},
s.handler)` puts path, docs and handler in one place. **This was the strongest
point against the Java and it has since been addressed** — `RouterApi` was
1337 lines with a wall of route lambdas and the DTOs 900 lines below the code
that built them; it is now 11 files, each group registering its own routes
(2026-08-26). The gap that remains is per-endpoint ceremony, not navigation.

Per-endpoint, Go reads better. Per-*system*, Java does: the sealed taxonomies
mean "what are all the outcomes" has a compiler-checked answer.

### Fit for purpose

Both fit. Java 25's virtual threads make the pool/worker topology natural in a
way Java 17 would not have; Go's goroutines always were. Go wins on runtime
footprint and startup, Java on refactoring safety at this scale.

**The uncomfortable point:** the port's demonstrated value so far has been
finding real defects, and those came from spec-first-then-audit, not from
Java. Most were reachable by auditing the Go directly, for a fraction of a
port.

## TypeScript, assessed

The owner's proposal was strict settings, locked-down npm, no `any`, no
`unknown`.

### The one rule to change

Ban `any`. **Do not ban `unknown`** — it is the honest type for a queue
payload, a parsed body, a `catch` binding, a DB row. Banning it does not
remove the uncertainty; it pushes people to `as Message`, an unchecked cast
that lies to every reader downstream. The rule that works: `unknown` at every
boundary, narrowed immediately by a runtime validator (zod/valibot/typia),
never propagated inward; then ban bare `as` on external data.

Alongside `strict`: `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`,
`erasableSyntaxOnly`.

### TS is better at this than expected

The load-bearing design move here is the sealed taxonomy. A discriminated
union with `disposition`/`statusCode` required on each member gives exactly
that, plus `assertNever` for exhaustive switches. **The bug class that bit
three times is equally preventable in TS.** This is not the disqualifier it
might look like.

### The real worry: the event loop

Specific, not general. The router is mostly I/O, so Node handles it — but this
system has hard liveness coupling. **Leader-election heartbeats every 10s
against a 30s lock TTL.** Visibility timeouts. Drain deadlines. On Java 25 a
CPU-bound stretch parks a virtual thread and everything else keeps running; on
Node, HMAC-signing a burst or serialising a fat payload stalls *everything*,
including the heartbeat that owns leadership. `worker_threads` exist but
reintroduce a message-passing boundary. Cancellation becomes `AbortSignal`
threaded through every layer — Go's `context` tax without Go's cultural
pressure to actually pay it.

### The second worry: boundary count

Three queue backends, HTTP wire contracts where snake-vs-camel is a contract,
config polling, DB rows. Every one needs a validator or the types are fiction.
Java's record constructors give some of that for free.

### npm lockdown

The instinct is right and is the strongest argument for TS being survivable:
lockfile + integrity hashes, `--ignore-scripts`, a private registry mirror,
minimal dependency count, Node's permission model.

### The rewrite-count problem

TS would be the **third** implementation of this system. The specs now exist,
so a third pass finds fewer defects than the second did. And "heavy in
different ways" may be the *system*, not the languages: ordered at-least-once
delivery, leader election, three brokers, an OIDC server — that weight is
invariant. TS feels lighter for a few weeks, then holds the same complexity
with less compiler help at the boundaries.

## Go ramp, for a strong JVM engineer with no Go

- **Reading it fluently: ~1 week.** Go is deliberately tiny.
- **Writing it acceptably: 2–4 weeks.**
- **Writing it idiomatically — not Java-in-Go: 2–3 months.**

Syntax is not the cost. These are: errors as values (`if err != nil` at every
call site, wrap-vs-return each time); implicit interface satisfaction, so
"who implements this" is a grep rather than a compiler query; zero values with
no `Optional`; `context.Context` threading; goroutine lifetime with no
structured concurrency; slice aliasing on `append`.

The hard part is unlearning the assistance this codebase relies on. Go will
not collect the cases for you, so `default:` arms become suspicious by
default — here they have been wrong three times out of three.

## The experiment — start here if this is reopened

Do not decide from argument. `conformance/mediation-outcomes.json` is
deliberately language-neutral: 28 cases stated as HTTP responses, so any
implementation runs it unmodified.

**Write a strict-TS implementation of just the mediation outcome table and run
that corpus against it. By hand, by the owner, not by an agent.** One day's
work, on the exact part of the system where sealed types earned their keep. It
answers the question with evidence, and it addresses the "not working enough
with the code" half of the problem, which a rewrite would make worse rather
than better — another six months watching an agent produce a third version.
