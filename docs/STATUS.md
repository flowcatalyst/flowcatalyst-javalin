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
**465 router tests**, all in the default `mvn test`, no profiles or tags.

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

### Build and tooling notes (2026-08-25)

- **`--enable-preview` is now genuinely enabled** — compiler args *and*
  surefire `argLine`. CONVENTIONS §8 had claimed it was when it was not.
  This **pins the runtime to the Java 25 feature release**: 25.0.1 → 25.0.2
  is fine, 25 → 26 needs a recompile.
- Docs now use `$(mise where graalvm)` instead of a hardcoded JDK path —
  `agent-prompts.md` is pasted into subagent prompts, so a point-release
  bump used to break every delegated agent.
- **Concurrent Maven runs share `server/target/` and clobber each other.**
  Several "flaky" failures today were this, not real. An agent's completion
  notification does **not** mean its build has stopped — check for live
  Maven processes before trusting a result, and prefer `mvn clean test`
  after any interface change.

### Router work remaining

ALB deregistration; mounting the router into the platform server so it
starts with the process; then drop-in verification — the side-by-side replay
harness against the Go binary and a cutover rehearsal.

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
