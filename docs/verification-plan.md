# Verification plan — is Java operationally and behaviourally identical to Go?

Written 2026-09-11 after the portal-apps sync and the router drop-in work.
**A fresh session starts here**, then `docs/STATUS.md`. This plan
consolidates and supersedes the ordering in `docs/spec/dropin-verification.md`
(router) and the gate list in `docs/spec/cutover.md` §1 (platform); both stay
as the detailed designs for the phases they own.

## The question, made precise

"Identical" means: **given the same deployment (image role, environment,
database, queues, config service) and the same inputs over time, the Java
build produces the same externally observable effects as the Go build.**

Observable effects, in order of how much they matter:

1. Messages: delivered / acked / nacked / dropped, in what order, with what
   retries and delays (the router, the dispatch scheduler, the outbox).
2. Data: rows written to the shared database (platform API, scheduler,
   stream processor, scheduled jobs).
3. HTTP: responses from every route (platform API, BFF, OAuth/OIDC, portal,
   router monitoring).
4. Operations: startup and shutdown behaviour, health/readiness answers,
   metric names, alerts (Teams), log fields operators search on, resource
   footprint.

Where Go is wrong, Java may be *better*, never silently *different*: every
difference is either fixed or recorded with a ruling
(`feedback: correctness over conformance`; the allow-list mechanism in
`parity/expected-diffs.json`).

## Lesson that shapes this plan

Everything that compared Go and Java **as programs** came out clean. What
broke on 2026-09-11 came from comparing them **as deployments**. Run with
the real ECS task definition, the Java router ignored four variables and
never sent Teams alerts. It also ran with no queues in silence when its
config URL was down. No test caught either, because every test configured
the process the way the tests expected. The live task definitions are the
spec for configuration, and `docs/spec/router-env.md` is the first artefact
of that.

## Where we stand (evidence, not claims)

| Area | Status | Evidence |
|---|---|---|
| Platform HTTP (every lockfile route + 102 others) | **done**, re-run per sync | parity corpus vs Go `466dc11`: 1,282 steps, 0 DIFF, 0 ERROR, 253/253 + 102/102 |
| SPA flows | **done** on Java; Go's column not re-run since 2026-09-06 | `e2e/`: 51/51 Java |
| Schema / adoption of a Go database | **done** | `SchemaFingerprintTest` vs a real Go dump (2026-09-11), `GoAdoptionTest` |
| Token compatibility (one key, both sides) | **done** at claim level | parity rule 4 (JWS structure compared) |
| Router, single attempt → outcome | **done** | `conformance/` corpus |
| Router, sequences over time (retry, breaker, ordered groups, redelivery, leadership) | **not started** | `dropin-verification.md` Phase A |
| Router against real traffic | **not started** | Phase B shadow run |
| Dispatch scheduler, scheduled jobs, stream processor, outbox — non-HTTP behaviour | **unit-tested on each side only; never compared** | — |
| Env parity for every deployed role | **router only** (`router-env.md`); platform role partially (`cutover.md` §4) | — |
| Operational behaviour (startup, drain, health under failure, metrics names, alerts, logs, footprint) | **ad hoc** (bench, one container run) | `bench/real/RESULTS.md`; 2026-09-11 container run |
| Cutover / rollback rehearsal | **not started** | `cutover.md` |

## Phases

Ordered so each one's findings feed the next. **One heavy run at a time on
this machine** (parity, e2e, a reactor build, or Docker): two at once gets the
OS killing processes.

### Phase 0 — deployment inventory (owner + orchestrator, ~1 hour)

The owner supplies, for every environment Java should replace Go in: each
ECS task definition (image, role flags, environment, CPU/memory, health
checks), the ALB target groups, and which queues and config service each
router reads. The orchestrator turns that into `docs/deployments.md`: one row
per role (API tier, router, workers) × environment. **Everything below tests
against these rows**, not against defaults.

### Phase 1 — env and startup parity, every role (Sonnet, one brief per role)

Extend `router-env.md`'s method to each role in the inventory:
- **Variables:** every variable in the task definition, plus every variable
  Go's `envcfg.go` reads (81 reads today), each marked read/aliased/ignored,
  with a reason for anything ignored.
- **Boot:** start the Java image and the Go image with each role's exact
  environment against a scratch database, and compare which subsystems start,
  which ports listen, and what `/health` answers.
- **Dependency failures:** repeat with each dependency down in turn (DB, config
  service, Redis for standby, SQS), comparing health answers, exit codes and
  alerts raised. Include **a configured queue that does not exist**, a real
  staging case on 2026-09-04: Go's router logged `consumer poll error …
  NonExistentQueue` every second, indefinitely, for
  `FC-staging-ceramic-release-staging-workers-high.fifo`. Java raises one
  `CONNECTION` warning (which reaches Teams) but still retries and logs a
  WARN every second (`ConsumerLoop.POLL_ERROR_PAUSE`). The proposed fix is in
  `docs/backlog.md` and needs an owner ruling.

Exit: a table per role with no unexplained "ignored" rows, and a
`DeploymentEnvTest` that loads each inventory row's environment into `Env` and
asserts the resolved settings. That test is the cheap guard against a future
drift like `API_PORT`.

### Phase 2 — observability parity (Sonnet, one brief)

- **Metrics:** scrape `/metrics` on both sides after a parity run and diff
  metric names and label keys. Dashboards and alerts are built against those
  names (`cutover.md` §1 already lists this gate).
- **Logs:** the fields operators filter on (message id, queue, pool, outcome),
  side by side for the same scenario.
- **Alerts:** the same failure (dead target, config down, breaker open)
  produces the same Teams cards. Capture with a local webhook, as on
  2026-09-11.

Exit: a name diff with every difference ruled on, and a captured card per
alert type.

### Phase 3 — router sequence harness (Sonnet builds, orchestrator specs; the biggest phase)

`dropin-verification.md` Phase A as written, with its precondition honoured:
**re-extract the router spec against Go HEAD first**. It was written against
Rust and has drifted.
- **Contract:** the broker-action sequence per message (`ack` / `nack(delay)` /
  `release`, with times).
- **Mechanics:** a scripted clock and a scripted target, run through both
  routers, comparing sequences.
- **Seams:** Java's already exist (see that doc's seam table). Go's gaps are a
  Go change, handed off via `docs/go-mirror/`.

Exit: the retry curve, breaker transitions, ordered-group draining,
redelivery, rate limiting and leadership handover each have a scenario that
passes on both sides.

### Phase 4 — non-HTTP subsystems (Sonnet, one brief per subsystem)

The dispatch scheduler, the scheduled-job scheduler, the stream processor
(event projection, fan-out, partition management) and the outbox processor.
For each:
- **Setup:** seed one database state, run the Go subsystem for N ticks
  against one clone and the Java subsystem against another, with a fixed
  clock where the seams allow.
- **Compare:** the resulting rows, the same way the parity harness compares
  HTTP bodies, and the messages published.
- **Harness:** reuse `parity/`'s template-database and normalisation
  machinery rather than writing a new one.

Exit: per subsystem, a scenario set covering its state machine, 0 unexplained
diffs.

### Phase 5 — shadow run (owner's staging + Sonnet tooling, ~1 week)

`dropin-verification.md` Phase B, **extended beyond the router**. In
staging:
- **Router:** Java consumes mirrored queues while Go serves live traffic, and
  the action sequences are compared.
- **API:** Java runs on a copy of the database with replayed read traffic,
  comparing responses.

This is where real message shapes and real target behaviour test the
assumptions the scripted phases encoded.

Exit: a diff list, every entry explained or fixed, over at least a week of
real traffic.

### Phase 6 — cutover rehearsal (owner + orchestrator)

`cutover.md` §2–§5 unchanged: three timed rehearsals of switch and rollback on
a copy of production, then production on a non-critical client first. The
gates in `cutover.md` §1 are this plan's phases 1–5.

## Delegation

- **The orchestrator (Opus 5)** writes each phase's spec/brief, decides every
  diff (fix Java / Go defect to `docs/go-mirror/` / ruled deviation), reviews
  code, mutation-checks load-bearing assertions, and commits.
- **Sonnet agents** build harnesses and scenarios and write the Java fixes,
  one brief each, in a worktree when they touch `server/`. Never two heavy
  runs at once.
- **The owner:** Phase 0 inventory, rulings on differences, staging
  infrastructure for Phase 5, and the rehearsals.

Don't parallelise phases 3 and 4 with each other: both need the machine's
memory, and both surface router/scheduler findings that change the other's
scope. Phases 1 and 2 can share a session.

## Keeping it true while Go moves

Go is still changing (four syncs on 2026-09-11 alone). Every sync ends with
the parity corpus and e2e green against the new Go commit, as today. From
Phase 3 on, the sequence and subsystem harnesses join that routine. Record
the Go commit each run was against (`STATUS.md`).

## First steps for the next session

1. Read this file, then `docs/STATUS.md` (top section).
2. Check `../flowcatalyst-go` `git log` since `466dc11`; sync first if it
   moved (lockfile, SPA, spec, parity — the routine in `STATUS.md`).
3. Ask the owner for the Phase 0 inventory; while waiting, start Phase 2's
   metrics-name diff, which needs no inventory.
