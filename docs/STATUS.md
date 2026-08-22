# Port status — living document

Updated whenever a unit lands. A fresh session (human or agent) should be
able to resume from this file + `CONVENTIONS.md` + `docs/backlog.md` +
`docs/process/agent-prompts.md` without re-deriving anything.

## Where we are (2026-08-22, evening)

Reactor green: `usecase` 30 · `sdk` 40 · `server` ~560 · `fcdev` 40 tests.
Commits on `main` through `67d550f` (subscription audited); one commit per landed/audited unit.

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
| process, cors, platformconfig | specs being written | ☐ port running (3 agents) | ☐ | |
| `Permission` enum (Checks refactor), sealed `Server.Mode`/`Spa`/`SigningKeys.KeyRotation`, `Metrics.Running` | — | ✔ | — | owner-directed refactors, 2026-08-22 |
| router | `docs/spec/router.md` (50 questions) | ☐ gated on owner rulings | ☐ | artifact published |

Lockfile coverage (`LockfileCoverageTest`): 76 / 243 operations (31%), zero drift.

Router spec rulings so far: Q1 (NEXT_ON_ERROR continues past a failed head; BLOCK_ON_ERROR ACKs queued siblings, group pending platform-side; failed message → human review → ignore/completed/resend → group re-queued). 49 pending.

## Next wave (in order)

1. Port (spec → implement → audit), three at a time: process, cors,
   platformconfig (running); then emaildomain,
   identityprovider, loginattempt, audit, event, docs; then serviceaccount,
   scheduledjob, principal (security-critical — read carefully),
   portalusers, resetapproval, webauthn, sdksync (wires the `Sync*`
   operations already ported), BFF dashboards, me, clientselection, sdk
   batch endpoints, publicapi, appdocs/openapispecs.
3. Auth subsystem: write `docs/spec/auth.md` FIRST (authservice/JWKS,
   login + session cookie, OAuth provider, grant store, OIDC bridge, portal
   auth, 2FA, backoff, rate limiting, password reset, encryption, email,
   notifier) → owner review → implement → audit. Largest/riskiest corner.
4. Data plane, each spec → review → implement → audit: router (spec done),
   stream processor, outbox processor, dispatch scheduler, scheduled-job
   scheduler (cron port), queue backends (SQS/Postgres/NATS), standby
   (Redis), ALB, MCP, purger. `--enable-preview` on `server` when
   `StructuredTaskScope` is used (keep localised).
5. Cross-cutting: CORS filter from the allowlist, pagination standard (wire
   change — owner), DB-backed `ClaimsResolver`, Secrets Manager DB mode,
   JFR events, `Permission` enum refactor of `Checks`, fcdev stubs.
6. Drop-in verification: side-by-side replay harness vs the Go binary,
   frontend end-to-end through every BFF/auth route, cutover + rollback
   rehearsal on a Go-created DB.

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
