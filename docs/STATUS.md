# Port status — living document

Updated whenever a unit lands. A fresh session (human or agent) should be
able to resume from this file + `CONVENTIONS.md` + `docs/backlog.md` +
`docs/process/agent-prompts.md` without re-deriving anything.

## Where we are (2026-08-24, evening)

Reactor green on a clean uncontended build, 2026-08-25: **2041 tests** —
usecase 30 · sdk 40 · **server 1931** · fcdev 40, 0 failures. Coverage
180/243 lockfile operations (74%), zero drift. Commits on `main`; one commit
per landed/audited unit.

**Orchestration model** — see `Claude.md` and `docs/process/agent-prompts.md`
§0: Opus 5 orchestrates (specs, scope, verification, debugging, commits),
`sonnet` at medium effort writes the code.

**DIRECTION CHANGE (owner, 2026-08-24): the message router comes next.**
Remaining platform work — the `principal` audit, `sdksync`, the remaining
CRUD aggregates and auth — is **deferred**, not cancelled. It is all still
listed under "Platform work, deferred" below and none of it is blocked.

### Router progress — data plane COMPLETE (2026-08-25)

Spec gate cleared (`docs/spec/router.md` §0, current against Go `eff2a29`).
**565 router tests**, all in the default `mvn test`, no profiles or tags.

| Package | What it holds |
|---|---|
| `router.wire` | `Message` (both Go `omitempty` semantics), `DispatchMode`, sealed `MediationType`, sealed `MediationOutcome`, response parse order, HMAC golden vector |
| `router.policy` | `RetryPolicy` (the **Q3 collapse**), `GroupFlushRegistry`, `CircuitBreaker` + registry, `RateLimiter` |
| `router.pool` | `OrderedGroups` (the **Q1 ruling**), `Pool`, `HttpMediator`, `QueuedMessage` |
| `router.queue` | `Consumer` contract + Postgres, SQS, NATS backends |
| `router.inflight` | `InFlightTracker` — new / redelivery / external-requeue |
| `router.manager` | routing, `ConsumerLoop`, reconfigure, `ConsumerSupervisor`, `RouterShutdown`, `RouterServer` |
| `router.config` | `RouterConfig`, `PoolSpec` (wire) vs `Pool.Config` (runtime), merge |
| `router.standby` | `LeaderElection` + `RedisLockStore` |
| `router.observability` / `.prometheus` / `.api` | metrics collector, warning store, exposition, §9.1 monitoring API |

**Rulings taken while building** (all recorded in `docs/spec/router.md`):

| Q | Ruling |
|---|---|
| Q3 | Two nested retry layers collapse to one flattened schedule; breaker accounting stays **per burst** |
| Q16 | Java scheduler propagates `dispatchMode` and `poolCode` — Go publishes neither, so its ordered path is dead code. Go: mode half done (`7414bc5`), pool half correctly reverted (`7ed2dba`) |
| — | Pool codes namespaced `{clientIdentifier}-{code}`, resolved at publish time; router synthesises `*-DEFAULT-POOL` on demand |
| Q17 | A malformed Postgres row **moves to `queue_messages_failed`** — one bad row used to stop the queue permanently |
| Q28 | **Every** restart attempt counts, not only successful ones — Go's version meant a consumer that could never be rebuilt never escalated |
| Q51 | Carry the real 2xx status (Java done; Go fix in `router-fixes.md`) |
| Q54 | Honour `flushGroup` from any target; revisit logged in `improvements.md` |
| — | 3xx is a **permanent** error: ACK with an error notice, never retried |
| — | Ordered-head failure split by kind: 502/503/504 and transport NACK the group back to the broker; 500 retries 3× then ACKs |

**Open, worth a ruling:** Q56 (`/monitoring/standby-status` reports the lock
key as `instance_id`, so every instance reports the same value — useless for
the one question it answers). Plus Q4–Q15, Q18–Q50 in `router.md` §13, where
"keep" is the standing default.

**Watch items recorded, not solved:** the unavailable path retries at the
*broker's* cadence (nack delay is advisory), so the breaker is what actually
protects a downed target; and ACK-on-500 assumes the target re-drives what
it rejected — true of the platform's dispatch endpoint, not of a third-party
ordered target.

### Hardening pass (2026-08-25)

Four commits after the data plane landed, prompted by an architecture
review. Three confirmed message-loss defects, all sharing a shape worth
remembering: **the loss was invisible from the broker.** The message left,
which is the normal thing to happen, so no counter, log or alert could
distinguish it from a delivery — and in two of the three, a test existed
that asserted the wrong thing and passed.

| Defect | Why it was invisible |
|---|---|
| `MediationOutcome.targetUnavailable()` was a *defaulted* boolean and only two of seven outcomes overrode it, so `CircuitOpen`/`RateLimited`/`Deferred` inherited "the message is at fault" — an ordered group whose head met an open breaker was ACK-deleted, siblings and all | An ACK is what success looks like |
| A worker interrupted mid-backoff returned without releasing in-flight ownership; the redelivery it relied on was then classified as a duplicate and dropped | No broker call at all, so nothing to observe |
| `NatsQueue` and `SqsQueue` each opened an expensive resource and then did more work that can throw before anything owned it — and `QueueFactory` turns the throw into an empty `Optional`, so the reconfigure loop re-leaked on **every config poll** | A failed queue build is logged; the strand is not |

Design consequences kept:

- **No defaulted answers on `MediationOutcome`.** `disposition()` and
  `statusCode()` are both abstract, so all seven records must answer and a
  new outcome cannot inherit a wrong one. `OrderedGroups` switches on the
  disposition rather than on a boolean.
- **`Broker.release(message)`** is a third verb beside ack and nack: give up
  ownership, say nothing to the broker. Nacking would race the broker's own
  redelivery.
- **JFR events at the choke points** (`observability.jfr`) — `MessageSettled`
  on every message that leaves, carrying *who decided* as well as what was
  done; `GroupDecision` with its blast radius; `Dispatch` as a duration
  event. Tests read them back out of a real dumped recording, because a
  `commit()` that runs proves nothing about what JFR persists.
- **`Concurrently`** moved to its own package and now bounds `Pool.handBack`,
  which fanned out with no deadline on the shutdown path.

`StructuredTaskScope` for `Pool` was assessed and **rejected**: unbounded
lifetime, work arriving from consumer poll threads, no join point, and
`fork()` must be called by the scope owner. It belongs where the fan-out is
bounded and joined, which is where it already is.

### Correctness over conformance (2026-08-25)

Owner: *"You have been helping with correctness. I don't just want blind
conformance."* Go is **evidence of what Go does, not of what is correct**.
The port is the one chance to fix what Go shipped, so a harness that freezes
Go's behaviour would make Java inherit those defects and then fail Java for
being right.

`conformance/mediation-outcomes.json` is the §6 outcome table made executable
— 28 cases, language-neutral, stated as HTTP responses so both
implementations run the same file. Where they differ the row asserts the
better behaviour: `correct` names the side, `basis` argues it from the
behaviour itself. **Enforced in code** — a divergence missing either field
fails the build, because a row that does not say which side is right has in
practice picked Go.

Standing divergences (verified against Go `819b390`, not assumed):

| Case | Correct | State |
|---|---|---|
| real 2xx status | java | open — `common.Success()` hard-codes 200 |
| 3xx as permanent | java | open — falls to Go's `default` arm, retried for ever at status 0 |
| 1xx | both | benign client-library difference; every field deciding the message's fate agrees |
| 501 | both | **agreed** — Go fixed it in `4f2d52c`, the corpus caught Java still wrong |

Two defects found while building it, both Java: 501 falling into the generic
`>= 500` branch, and `RateLimited.statusCode()` returning 0 when the outcome
is produced only from a 429.

### Build and tooling notes (2026-08-25)

- **`--enable-preview` is now genuinely enabled** — compiler args *and*
  surefire `argLine`. CONVENTIONS §8 had claimed it was when it was not.
  This **pins the runtime to the Java 25 feature release**: 25.0.1 → 25.0.2
  is fine, 25 → 26 needs a recompile.
- Docs now use `$(mise where graalvm)` instead of a hardcoded JDK path —
  `agent-prompts.md` is pasted into subagent prompts, so a point-release
  bump used to break every delegated agent.
- `timeout(1)` is **not** on macOS. Use surefire's `-Dsurefire.timeout=<s>`
  to bound a test that may hang; `timeout ... mvn` silently exits 127 and
  looks like a passing mutation check.
- **Concurrent Maven runs share `server/target/` and clobber each other.**
  Several "flaky" failures today were this, not real. An agent's completion
  notification does **not** mean its build has stopped — check for live
  Maven processes before trusting a result, and prefer `mvn clean test`
  after any interface change.

### Router work remaining (2026-08-25)

Mounting into the platform server is **done** — `Server.java` starts the
router before the listeners bind and drains it after they stop.

Ordered by what unblocks the most:

1. ~~**Java warning service.**~~ **DONE 2026-08-25**, in two halves. `HttpMediator` takes a
   `Warnings` collaborator and every permanent ACK-drop now raises one; the
   corpus asserts a `warning` column on all 28 cases. `Warnings` moved from
   `manager` to `observability` beside its implementation — the raisers are
   spread across three packages and the contract should not sit inside one
   caller's. **Still open:** the `WarningStore.AUTO_ACKNOWLEDGE_AGE` question
   (constant 45) — deliberately left, it needs an owner ruling, not a silent
   fix.
2. ~~**ELBv2 `TargetGroup`**~~ **DONE 2026-08-25.** `Elbv2TargetGroup` wires
   the three calls behind `AlbTraffic`'s already-tested policy, and `Traffic`
   is now `AutoCloseable` so the SDK client is released on shutdown. **The
   router has no `TODO(port)` left.**
3. ~~**Pool gaps found against Go.**~~ **DONE 2026-08-25.** `markRetrying`
   had no production caller, so two guards that read `attempts` were
   unreachable; `runImmediate` never read `disposition`, so nothing went back
   to the broker and no in-place retry was bounded; the live mediating view
   was missing. Bounding was then applied to the ordered path too. An
   unspecified `dispatchMode` now defaults to **`NEXT_ON_ERROR`**, matching
   Go — Java defaulted to `IMMEDIATE`, which silently gave no ordering to a
   producer that needed it.

4. ~~**Four monitoring routes.**~~ **DONE 2026-08-26** — all five, including
   the whole of `GET /monitoring/in-flight-messages/detail`, which turned out
   to be unported rather than merely missing its `MEDIATING` branch. Outcome
   and every decision in `docs/spec/monitoring-routes.md` "Outcome". The
   router's §9.1 surface now has nothing left that a live data source exists
   for.

   Two claims the plan inherited from the class doc were wrong on re-reading
   the code: the **30-minute window was already kept** (`BrokerStatsCache`
   has `HISTORY` + `windowed`), and **`totalDeferred` is not missing data** —
   Go's `Defer` verb has no production caller, so the field is structurally
   zero on both sides (`router-fixes.md` Fix 10 asks the owner what to do
   about it).

   Found and fixed on the way: `ForceAckResponse.wasMediating` was hard-coded
   `false`, telling every operator force-acking a wedged message that no
   attempt was running. `BrokerStatsCache.refresh` now samples **outside**
   its lock — with an operator-triggered refresh added, holding the monitor
   across broker I/O would let one unreachable broker hang every read of the
   cache during exactly the outage someone is looking at it for.

   Twelve mutation checks, all killed. One (`ageSeconds`' clamp) survived
   first time because it was unreachable after a successful refresh; it is
   now pinned by a backwards clock step, which is the only way it fires.

5. **Go runner Phase 1** (`conformance/go-runner.md`) — Go repo, not this one.
   Needs no Go changes and asserts six of seven fields.
6. **Go runner Phase 2** — extract Go's inline `switch outcome.Result`
   (`pool.go:901`) into a pure function so `disposition` becomes assertable.
7. **Drop-in verification** — side-by-side replay against the Go binary, then
   a cutover rehearsal.

Smaller, tracked in place: `Q19` NATS redelivery handle (`NatsQueue.java:326`),
`Q41` metrics contract (`RouterPrometheusCollector.java:54`).

## Next wave (in order)

### 1. Router / data plane — the current focus

**Gate: `docs/spec/router.md` §0 must be cleared before any router Java.**
The spec was extracted at Go `1e9d465`; three commits landed after it, all
inside the subsystem, and §2/§3/§6/§7 are stale in the ways §0's table
lists. §6 is the **wire contract** (the `flushGroup` mediation-response
field), so implementing against the stale text would be actively wrong, not
merely incomplete. The target is now stable — no router work is in flight —
so this is a bounded, one-time re-extraction.

Order of work:

1. **Re-extract the drifted sections** (CONVENTIONS §8 step 1 — behaviour and
   tables, never code) against Go `eff2a29`:
   - §6.5 status → outcome table gains the `flushGroup` column; the
     mediation-response parser and the golden vectors move with it.
   - §2 gains `GroupFlushRegistry` and the group-flush state machine;
     `common.Message.GroupID` now backs all group derivation.
   - §3 gains the pre-rate-limit flush check (a flushed group spends neither
     token nor slot).
   - §2.6 / §13 Q1: per-mode blocking is now *implemented upstream*, so it is
     settled behaviour, not an open question.
   - §7 / `docs/spec/dispatchjob.md` §10: delivery-time blocked-group
     hold-back (ACK + revert to `PENDING`, no attempt recorded, no retry
     budget spent; DB error NACKs instead).
2. **Owner rulings on the router questions.** 47 of 50 are open. Most are
   "keep or change?" where *keep* is the safe default and the spec's standing
   rule already applies ("until ruled on, the behaviour is kept"), so they do
   **not** all gate code. The ones that do, because they change structure or
   allege a defect, are: **Q27** (suspected startup defect, default-broker
   `Reconfigure`), **Q43** (suspected auth defect, BasicAuth public-path
   bypass under a path prefix), **Q16** (scheduler publishes without
   `dispatchMode`/`poolCode`), **Q13** (ordered messages with no group id
   share one global group), **Q35** (half-applied reconfigure). Surface these
   before the units that touch them, not all at once.
3. **Implement**, as if Go never existed, against the §8 idiom checklist —
   sealed message taxonomies with exhaustive switch, topology restructured
   (multiplex at producers, one queue per stage) rather than translated,
   cancellation by interruption rather than a ported `ctx`,
   `StructuredTaskScope` with completion policies rather than
   `CompletableFuture` chains, panic-recovery blocks deleted but their retry
   policies preserved as **named policy objects**, expected outcomes as
   sealed result types vs exceptional as exceptions, JFR events at semantic
   points. `--enable-preview` on `server` when `StructuredTaskScope` lands,
   kept localised.

   Suggested unit order, lowest dependency first:
   - **wire contract**: `Message` model + `DispatchMode` + HMAC signing +
     the mediation-response parser (incl. `flushGroup`, `ack:false`
     precedence). Pure, golden-vector testable, and it is the contract
     everything else is written against.
   - **policy objects**: rate limiter, per-endpoint circuit breaker,
     `GroupFlushRegistry`, the single named retry policy from Q3.
   - **pool / worker topology** and the ordered-group machinery (Q1's two
     modes and the review → re-queue flow).
   - **queue backends**: SQS, Postgres, NATS behind one sealed contract.
   - **loops**: stream processor, outbox processor, dispatch scheduler,
     scheduled-job scheduler.
   - **surrounds**: standby/Redis leadership, ALB, config sync, observability
     (`/monitoring`, warnings, Prometheus), shutdown sequence, purger.
4. **Conformance**: the golden vectors and the edge cases mined in §12 are
   the seed suite. Q1's two modes and the ignore/completed/resend → re-queue
   flow must be pinned by tests — that is a deliberate deviation from Go.

Blocking dependency to note: the router Q1 ruling needs the dispatchjob
*ignore* / *completed* routes, which require a **lockfile addition** (owner).

### 2. Platform work — deferred, not cancelled

Nothing here is blocked; it was overtaken by the router. Resume in this
order:

1. **Audit `principal`** (`docs/process/agent-prompts.md` §2) and **add
   `PrincipalApiTest`** — the only aggregate that landed without either, and
   the security-critical one. Its §11 Q3 (existence oracle) wants a ruling
   first.
2. **Finish `sdksync`** — the `Api` + registration; every `Sync*` operation
   it wires already exists and is audited. Unlocks 11 lockfile operations.
   Register `openapispecs` with it.
3. Remaining aggregates, three at a time: `serviceaccount` (14 ops),
   `anchor-domains` (4), `auth-configs` (4), `idp-role-mappings` (3); then
   the SDK ingest batch endpoints (`/api/events`, `/api/events/batch`,
   `/api/dispatch-jobs/batch`, `/api/audit-logs/batch`) and the BFF
   dashboards / `me` / `clientselection` (outside the lockfile).
4. **Auth** — `auth-core.md` Q1–Q15 are ruled (table above); **Q16–Q29**
   and all of `auth-identity.md` are not. `oauth-clients` (10 ops),
   `portal-users` (5), `webauthn` (6), `reset-approvals` (3) belong here.
   The Go-side fixes in `oauthapi-fixes.md` should land before the port
   reproduces the corrected behaviour.
5. Cross-cutting: CORS filter from the allowlist (owner ruled: implement),
   pagination standard (wire change — owner), DB-backed `ClaimsResolver`,
   Secrets Manager DB mode, JFR events, fcdev stubs
   (`init`/`mcp`/`outbox`/`upgrade`).
6. Drop-in verification: side-by-side replay harness against the Go binary,
   frontend end-to-end through every BFF/auth route, cutover + rollback
   rehearsal on a Go-created database.

**Standing rule:** re-check `git log` in `../flowcatalyst-go` before starting
any *platform* unit — that side is still moving. The router is stable.

## Owner rulings taken 2026-08-25

| Question | Ruling |
|---|---|
| `BLOCK_ON_ERROR`, untried siblings | **Keep Java's**: ACK them. Go's nack returns them on the *broker's* timer, by which point the head is gone, so the first sibling becomes the new head and is delivered past the failure — breaking the guarantee the mode is named for. Residual gap recorded: nothing marks those jobs `FAILED` for review (platform work). |
| Postgres quarantine | **`queue_messages_failed`, keep the LATEST failure.** Java already matches; Go-side change is Fix 2. |
| `AUTO_ACKNOWLEDGE_AGE` | **1 hour.** |
| Unspecified `dispatchMode` | **`NEXT_ON_ERROR`.** The failure modes are asymmetric: wanting concurrency and getting ordering is visible and cheap to fix; needing ordering and silently getting none is invisible and lands in the target's data. |

**Still needing a ruling:** the webhook's minimum severity (set to `WARNING`,
so `INFO` is dropped — a channel-noise judgement, one line to change).

## Owner rulings outstanding

See `docs/backlog.md` "Owner questions". Rule = no ruling → behaviour kept
as Go has it; a ruling becomes a spec line + conformance test (+ a
"deliberate deviation" note if behaviour changes).

## How to resume

- Build: `JAVA_HOME=$(mise where graalvm) mvn -q test`
  (first run downloads embedded PG 18).
- Per-aggregate pipeline: `docs/process/agent-prompts.md`.
- Reference Go repo: `../flowcatalyst-go` (read-only; never modified).
- Commit per landed+audited unit.
