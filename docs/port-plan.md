# Java port — continuation plan (2026-09-05)

Owner decision 2026-09-04: **the Java/Javalin port is the plan; carry on from
Go.** This replaces the "Next wave" section of `docs/STATUS.md` (the router
half of it is done; the platform half is re-sequenced here). Standing process
is unchanged: spec → implement as if Go never existed → audit Java-vs-spec
(`CONVENTIONS.md` §8, `docs/process/agent-prompts.md`). Reference
implementation `../flowcatalyst-go` is read-only and still moving — check its
`git log` before every unit.

## Where the port stands

- Lockfile coverage **192 / 245 operations (78.4%)**, zero drift. 24 platform
  aggregates ported and audited. Router complete against the Rust-repo
  contract; 2,353 server tests.
- Production artefacts: executable jar, jlink Docker image, opt-in 89 MB
  native binary. fcdev jar (181 MB, six bundled Postgres archives).
- **Unported, by Go production lines:** auth 12,411 · serviceaccount 2,152 ·
  outbox 1,463 · webauthn 1,297 · stream 1,305 · mfa 1,137 · passwordreset
  1,051 · portalidentity 916 · mcp 785 · portalauth 562 · resetapproval 322 ·
  secrets 318 · branding 265 · docsapi 221 · appdocs 182 · notify 152 · idp
  131. Plus the SDK ingest batch endpoints, the BFF (`bff/*`, `me`,
  `clientselection`), purger, scheduled-job scheduler, fcdev stubs.
- Go drift since the router drive (`7ae5acd..HEAD`, 4 commits): one is
  behavioural — `3b64775` *auth: pin the dormant-identifier backoff default
  (owner ruling 2026-09-03)* — and belongs in `auth-core.md` before Phase 3.

## Division of labour

| Who | Does |
|---|---|
| **Orchestrator (Opus/Fable)** | Specs and spec re-extraction; surfacing and recording owner rulings; every test that pins load-bearing behaviour (with the break-it-on-purpose check); transaction boundaries, concurrency, crypto and auth code; audits; diagnosis of any red build; commits. |
| **Sonnet 5, medium effort, ≤3 in parallel, own git worktrees** | Aggregate ports against a finished spec + the `eventtype` template; DTOs, routes, repositories, CRUD operations; mechanical refactors; fcdev stubs; test scaffolding I then review. Never "port X, work it out" — always spec + scope + registration lines. Never allowed to loop on an error. |

## Phase 0 — housekeeping (orchestrator, one session)

1. ~~Fold `3b64775`; drift-check the unported specs~~ **Done 2026-09-05:**
   the ruling lives in `loginattempt.md` §5 and `login-backoff-lock.md` (it
   is a repository bound, not an auth-core rule) and is implemented. Drift
   found and queued below (items 4–6); the three serviceaccount commits of
   2026-08-27 were already in `serviceaccount.md` / `-fixes.md`.
2. ~~Fix the suite flake `PoolTest.rateLimitWarnsOnceForARun`~~ **Done
   2026-09-05 (`931d5a7`):** the test awaited the rate-limited counter, which
   the pool bumps before raising the warning on the same worker; the first
   unlimited delivery now completes before the burst and the await includes
   the warning.
3. ~~Drift-check `auth-identity.md` §6 (MFA)~~ **Done 2026-09-05:** `6cbe708`
   touches no file under `internal/platform/mfa`, `auth/twofa` or
   `auth/mfatoken`; its auth-side change is the strict `rowToOAuthClient`
   decode (X-06, noted in `auth-core.md`). `3b64775` is folded into
   `loginattempt.md` / `login-backoff-lock.md` and implemented (`931d5a7`).
   §6 stands as written.
4. ~~Schema drift — adopt Go migrations 046–052 as Flyway V2–V7~~ **Done
   2026-09-05 (`dd1874b`).** Found
   2026-09-05: a Go database at HEAD carries seven migrations past our V1
   baseline (oauth secret grace 046/047, dispatch-mode default 048,
   **`iam_login_attempts` range-partitioned by quarter 049**, X-06 CHECK
   constraints 051/052). Mirror them idempotently, regenerate
   `go-schema.sql` + fingerprint from a Go-HEAD database, jOOQ regen,
   `GoAdoptionTest` on goose 052. Sonnet translates; the tests are reviewed
   and mutation-checked here.
5. ~~X-06 strict stored-enum reads across the platform~~ **Done 2026-09-05
   (`22bbffc`)**, five residual readers in the `e6a33ba` unit. Java's `parse`
   methods default unknown stored values (`AttemptOutcome` → `SUCCESS`,
   `ScopeType` → `ANCHOR` — a corrupt row reads as a login success, or as
   the most privileged scope). Replicate the dispatchjob unit's
   `Corrupt…Exception(rowId)` pattern everywhere; one test per module that
   inserts an impossible value and expects the typed read error. Sonnet
   sweep; security assertions reviewed here.
6. **`e6a33ba` drift (PR-3/PR-4, X-02, X-08):** principal by-id and
   mutations answer **404, byte-identical to not-found**, never 403, for an
   out-of-scope id (`PrincipalApi.java:228` still throws forbidden);
   scheduled-job sync `archiveUnlisted` and role sync `removeUnlisted`
   contained to the syncing application, platform-scope sweep anchor-only
   (`ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP`); sync rollup message groups
   `platform:<aggregate>:<applicationCode>` (bare when no application).
   Sonnet, after the X-06 sweep lands (same modules).
7. ~~fcdev: download the Postgres archive on first run~~ **Done 2026-09-05
   (`c6d7e36`, 181 → 47 MB).** (zonky
   `PgBinaryResolver`, Go's pattern) — spec + test mine, code Sonnet. Drops
   the jar to ~50 MB and is a prerequisite for a native fcdev.

## Phase 1 — finish the platform aggregates (coverage 192 → ~215)

Sonnet ports, three at a time, in this order; I audit each before its commit.

1. ~~`serviceaccount`~~ **Done 2026-09-05 (coverage 206).** The OAuth client
   and the mint's audit row wait for the auth aggregate (`backlog.md`).
2. ~~`anchor-domains`, `auth-configs`, `idp-role-mappings`~~ **Done
   2026-09-05 (`9a4e6fc`, spec `auth-admin-config.md`; coverage 203).**
3. **SDK ingest batch endpoints** — `/api/events`, `/api/events/batch`,
   `/api/dispatch-jobs/batch`, `/api/audit-logs/batch`. *I write this spec*:
   partial-failure semantics, idempotency on TSIDs, per-item error shape,
   the outbox seam. Sonnet implements; the batch-atomicity tests are mine.
4. **BFF**: dashboards, `me`, `clientselection` (outside the lockfile;
   `frontend-api-types-adoption.md` on the Go side is the contract).
   Sonnet, with the frontend as the acceptance test.

## Phase 2 — the remaining data-plane loops

Each is a poll loop with one transaction boundary that matters. Spec by me
(behaviour tables, never code), loop skeleton + claim/commit/publish
transaction by me, everything around it by Sonnet.

1. **Stream processor** (1,305 Go lines) — `FC_STREAM_PROCESSOR_ENABLED` is
   already an `Env` toggle wired to nothing.
2. **Outbox processor** (1,463) — Postgres backend only. **Owner ruling
   2026-09-05: the Mongo backend is out of the port, on the backlog.**
3. **Scheduled-job scheduler** + **purger** — `scheduledjob.md` exists;
   `Server.java:200` lists both as TODO(port).
4. **MCP** (785) — small; Sonnet end to end once the platform HTTP contract
   it proxies is stable.
5. **AWS Secrets Manager DB mode + rotation** (318) — `Main.java:38`. Sonnet.

Router follow-ups that ride along: SQS/NATS dispatch publishers (deferred in
Go too); the `/metrics` alias under the router prefix; a router-only
default-broker instance opening its own pool (owner question in STATUS).

## Phase 3 — auth and identity (the largest block, ~19k Go lines)

**Gate: owner rulings.** `auth-core.md` Q16–Q29 and all 25 of
`auth-identity.md` are open, and 15 observed Go defects are listed there.
Rule stands: no ruling → keep Go's behaviour, but the defects need a yes/no
each before code. I surface them in three batches so no session has to rule
on forty questions: (a) token issuance + sessions, (b) OIDC bridge + portal
auth, (c) WebAuthn + MFA + password reset + reset approvals.

Split: **mine** — JWT/RS256 issuance and validation, PKCE, session cookies,
password hashing and the dormant-identifier backoff, WebAuthn ceremonies,
MFA secrets, the DB-backed `ClaimsResolver` and role → permission
flattening, and every test that pins a security property (each one
mutation-checked). **Sonnet** — `oauth-clients` (10 ops), `portal-users`
(5), `reset-approvals` (3), `webauthn` route/DTO layer, `passwordreset`
flows around the hashing I provide, `branding`/`notify`/`appdocs`/`docsapi`.

**MFA is in the first cut** (owner ruling 2026-09-05: feature parity, it is
in use). It is specified in `auth-identity.md` §6 and §11.4–11.7 — TOTP
(RFC 6238, ±1 step, replay guard), e-mail PIN, recovery codes, trusted
devices, the login-decision gate and the change-password interplay. Its
owner questions join batch (c). The TOTP/PIN/recovery crypto and the
replay and single-use guarantees are orchestrator code with mutation-checked
tests; the 14 routes and the DTOs are Sonnet's. Note the `/auth/2fa/*`
routes sit outside the lockfile (only `/api/principals/{id}/reset-2fa` is
in it), so the frontend is their acceptance test.

## Phase 4 — cross-cutting

- **CORS filter from the allowlist** (ruled: implement) — Sonnet.
- **Pagination envelope** — wire change; owner decides; lockfile bump +
  SDK/frontend regen.
- **JFR events** at the semantic points the router spec names — Sonnet.
- **fcdev stubs** `init`, `mcp`, `outbox`, `upgrade` — Sonnet, after Phase 2
  gives them something to drive.
- **Native fcdev** (optional): per-platform GraalVM builds, picocli codegen;
  removes JBang and the JDK from the developer install.

## Phase 5 — drop-in verification and release

- Side-by-side replay harness against the Go binary on a Go-created database
  (design mine, harness Sonnet); frontend end-to-end through every BFF/auth
  route; cutover + rollback rehearsal.
- CI: Linux native build in a matrix (only macOS arm64 is proven), the jlink
  image as the default deployable, the native binary for the router tier.

## Rules that stay in force

- One commit per landed **and audited** unit; `mvn clean test` after any
  interface change; never two Maven runs on one `target/`.
- A test asserts behaviour a caller depends on; after it passes, break the
  code and watch it fail (CLAUDE.md). The orchestrator does this for every
  load-bearing claim and says which assertion pins which behaviour.
- Go is evidence of what Go does, not of what is right; deviations are
  recorded as rulings, never smuggled in.
