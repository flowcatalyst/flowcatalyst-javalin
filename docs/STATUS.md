# Port status — living document

Updated whenever a unit lands. A fresh session (human or agent) should be
able to resume from this file + `CONVENTIONS.md` + `docs/backlog.md` +
`docs/process/agent-prompts.md` without re-deriving anything.

## Where we are (2026-08-24, evening)

Reactor green, verified 2026-08-24: **server 1661**, 0 failures — plus
usecase 30 · sdk 40 · fcdev 40. Coverage
180/243 lockfile operations (74%), zero drift. Commits on `main`; one commit
per landed/audited unit.

**Orchestration model** — see `Claude.md` and `docs/process/agent-prompts.md`
§0: Opus 5 orchestrates (specs, scope, verification, debugging, commits),
`sonnet` at medium effort writes the code.

**DIRECTION CHANGE (owner, 2026-08-24): the message router comes next.**
Remaining platform work — the `principal` audit, `sdksync`, the remaining
CRUD aggregates and auth — is **deferred**, not cancelled. It is all still
listed under "Platform work, deferred" below and none of it is blocked.

### Router progress (2026-08-24)

Spec gate **cleared**: `docs/spec/router.md` is current against Go `eff2a29`
(§0). Five units landed, 133 tests, all in the default `mvn test` run.

| Unit | Package | Pins |
|---|---|---|
| Wire contract | `router.wire` | `Message` (both Go `omitempty` semantics), `DispatchMode`, `MediationType` (sealed, so an unsupported value reaches the ACK-drop instead of poisoning), `MediationOutcome`, response parse order, HMAC golden vector |
| Retry policy | `router.policy` | The **Q3 collapse** — Go's two nested retry layers as one flattened schedule; `startsBurst`/`endsBurst` keep breaker accounting per burst |
| Group flush | `router.policy` | Q54 TTL-bounded suppression, extend-only, per-pool |
| Breaker + registry | `router.policy` | Failure-**rate** breaking, per-endpoint, idle retirement |
| Rate limiter | `router.policy` | Token bucket; `await` reserves rather than polls |
| Ordered groups | `router.pool` | The **Q1 ruling**, plus the failure-kind split |
| Pool | `router.pool` | Workers, drainers, capacity, the delivery pipeline |
| HTTP mediator | `router.pool` | Status → outcome, signing, breaker rules |

**Rulings taken while building** (all recorded in `docs/spec/router.md`):

- **Q16** — the Java scheduler propagates `dispatchMode` and `poolCode`.
  Go publishes neither, so today every dispatch job is `IMMEDIATE` in
  `DEFAULT-POOL` and the ordered path is dead code. Go fix half-done
  (`7414bc5` mode; pool half correctly reverted in `7ed2dba` after a broken
  claim query). Java spec: `docs/spec/dispatch-propagation.md`.
- **Pool codes namespaced** `{clientIdentifier}-{code}`, fallback
  `{clientIdentifier}-DEFAULT-POOL`, resolved at publish time; the router
  synthesises `*-DEFAULT-POOL` pools on demand.
- **Q51** — carry the real 2xx status. Java done; Go fix specced in
  `docs/spec/router-fixes.md`.
- **Q54** — honour `flushGroup` from any target; revisit logged in
  `docs/improvements.md`.
- **Ordered-head failure split by kind** — 502/503/504 and transport NACK
  the whole group back to the broker (retry indefinitely, broker owns it);
  500 retries 3× then ACKs, blocking or advancing the group per mode.

**Watch items recorded, not solved:** the unavailable path retries at the
*broker's* cadence (nack delay is advisory), so the breaker is what actually
protects a downed target; and ACK-on-500 assumes the target re-drives what
it rejected, true of the platform's dispatch endpoint but not of a
third-party ordered target.

**Next router units:** consumer/queue-backend contract (then SQS, Postgres,
NATS — good delegation candidates), the in-flight tracker, `Manager`
(routing, consumer loops, pool registry, config sync), observability
(metrics, warnings, monitoring API), standby/leadership, ALB, shutdown.

**Go reference state.** `../flowcatalyst-go` is on branch
`fix/oauth-endpoint-compliance` at `f908c3b`, working tree clean. The owner
confirms **no router work is in flight** — `eff2a29` is the newest
router-touching commit, so `docs/spec/router.md` §0's drift table is complete
and its target is stable. The platform side is still moving (the auth fixes
below), so keep re-checking `git log` before platform units; the router no
longer needs that treatment.

### Auth question rulings landed 2026-08-24

Rulings are recorded in `docs/spec/auth-core.md`; the change specs carry the
design. No auth Java yet — these are decisions, not code.

| Q | Ruling | Where the design lives |
|---|---|---|
| Q4 | Move `/oauth/userinfo` (and discovery) to the public group | `oauthapi-fixes.md` Fix 1 |
| Q5/Q6 | Keep Basic auth; fix `client_credentials` to accept it, and close the rate-limit evasion | `oauthapi-fixes.md` Fix 2, 3 |
| Q7, Q8 | Already fixed in Go — verified, not duplicated | `oauthapi-fixes.md` |
| Q9 | Arrays everywhere — **done in Go** (`b5b471d`, `f908c3b`), nothing left to port | `oauthapi-fixes.md` Fix 5 |
| Q10 | Cookie-mint failure answers 500, cause not leaked | `oauthapi-fixes.md` Fix 4 |
| Q11 | `GlobalLockSecs` becomes a **real lock** (deny while over-ceiling AND inside the lock; advertise the later) | `login-backoff-lock.md` |
| Q12 | Wire `ratelimit.Prune` into the existing purger, retention `MaxWindow()` | `auth-retention.md` |
| Q13 | Keep no-revocation-on-code-replay — **deferred out of the port** | `improvements.md` |
| Q14 | Keep the hard secret cutover in the port; grace period + immediate revoke **deferred out of the port** | `improvements.md` |
| Q15 | Derive `expires_in` from `AccessTokenExpirySecs` (six hard-coded sites) | `oauthapi-fixes.md` Fix 6 |

Still open in `auth-core.md`: **Q16, Q17, Q18–Q29**. Plus all 25
`auth-identity.md` questions and its 15 observed Go defects.

**New in this repo:** `docs/improvements.md` — platform improvements
deliberately **outside** the port. Java reproduces current Go behaviour for
everything in that file; nothing there blocks a port unit.

**Lockfile synced** to Go `f908c3b` — `server/src/main/resources/openapi/openapi.lock.json`
and `sdk/openapi/openapi.json` are byte-identical to `api/openapi.lock.json`.
178 paths / 243 operations / 231 schemas; `LockfileCoverageTest` passes.

| Unit | Spec | Port | Audit | Notes |
|---|---|---|---|---|
| usecase envelope | `docs/usecase-envelope.md` | ✔ | ✔ | |
| sdk (client SDK) | — | ✔ (copied, on usecase) | — | openapi.json = lockfile |
| server foundations (Env, logging, signing keys, JWT auth, HttpError, apicommon, Json, spec routes, SPA, listeners) | — | ✔ | ✔ | `JwtVerifier.Verification` sealed |
| Flyway baseline + fingerprint/adoption tests, jOOQ codegen | `docs/database.md` | ✔ | ✔ | |
| seeder + PasswordHash | `docs/spec/seeder.md`, `password-hash.md` | ✔ | ✔ (report pending at time of writing) | |
| fcdev module | `docs/spec/fcdev.md`, `docs/fcdev.md` | ✔ | ✔ | init/mcp/outbox/upgrade stubbed |
| eventtype | `docs/spec/eventtype.md` | ✔ | ✔ | TEMPLATE aggregate |
| connection | `docs/spec/connection.md` | ✔ | ✔ | |
| dispatchpool | `docs/spec/dispatchpool.md` | ✔ | ✔ | |
| role (+ permissions) | `docs/spec/role.md` | ✔ | ✔ | |
| subscription | `docs/spec/subscription.md` | ✔ | ✔ | `SyncSubscriptions` ported, sdksync route not wired |
| client | `docs/spec/client.md` | ✔ | ✔ | uses application's repos for `/clients/{id}/applications*` |
| application | `docs/spec/application.md` | ✔ | ✔ | provision-service-account / provision-login-client deferred |
| process | `docs/spec/process.md` | ✔ | ✔ | |
| cors | `docs/spec/cors.md` | ✔ | ✔ | CORS *filter* still to build (owner decision) |
| platformconfig | `docs/spec/platformconfig.md` | ✔ | ✔ | |
| audit (read) | `docs/spec/audit.md` | ✔ | ✔ | batch ingest route = sdk unit |
| shared encryption | `docs/spec/encryption.md` | ✔ | ✔ | Go-minted golden vector |
| emaildomainmapping | `docs/spec/emaildomainmapping.md` | ✔ | ✔ | principal reset write flagged |
| loginattempt | `docs/spec/loginattempt.md` | ✔ | ✔ | backoff repo contract for auth; shared `KeysetCursor` |
| identityprovider | `docs/spec/identityprovider.md` | ✔ | ✔ | app key now via `Env` |
| event (read) | `docs/spec/event.md` | ✔ | ✔ | ingest POSTs = sdk unit |
| dispatchjob | `docs/spec/dispatchjob.md` | ✔ | ✔ | ignore/completed routes need lockfile addition (owner) |
| docs (appdocs + docsapi) | `docs/spec/docs.md` | ✔ | ✔ | published pages copied |
| scheduledjob (+ cron) | `docs/spec/scheduledjob.md` | ✔ | ✔ | 6-field cron hand-ported, 75 pinned rows |
| **principal** | `docs/spec/principal.md` | ✔ | ☐ **audit not run** | 31 routes, 39 operations; **no `PrincipalApiTest` yet**; emailer/notifier/MFA are stubs (spec §10) |
| **openapispecs** | `docs/spec/openapispecs.md` | ✔ | ☐ | not registered — consumed by sdksync |
| **sdksync** | `docs/spec/sdksync.md` | ◐ **5 DTO files only** | ☐ | no Api, not registered; the `Sync*` operations it wires all exist |
| publicapi + branding | `docs/spec/publicapi.md` | ✔ | ✔ | incl. legacy `/api/config/platform` |
| shared `Visibility` (+`VisibilitySql`) | — | ✔ | — | replaces event/dispatchjob copies |
| auth core (spec) | `docs/spec/auth-core.md` (29 Qs, artifact) | ☐ gated on owner | ☐ | |
| scheduledjob (+cron), principal, auth-identity spec | running | ☐ | ☐ | |
| `Permission` enum (Checks refactor), sealed `Server.Mode`/`Spa`/`SigningKeys.KeyRotation`, `Metrics.Running` | — | ✔ | — | owner-directed refactors, 2026-08-22 |
| **router** | `docs/spec/router.md` (50 Qs; Q1–Q3 ruled) | ☐ **NEXT — see below** | ☐ | §0 drift must be re-extracted first |




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

- Build: `JAVA_HOME=~/.local/share/mise/installs/graalvm/25.0.1 mvn -q test`
  (first run downloads embedded PG 18).
- Per-aggregate pipeline: `docs/process/agent-prompts.md`.
- Reference Go repo: `../flowcatalyst-go` (read-only; never modified).
- Commit per landed+audited unit.
