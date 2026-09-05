# Port status — living document

Updated whenever a unit lands. A fresh session (human or agent) should be
able to resume from this file + `CONVENTIONS.md` + `docs/backlog.md` +
`docs/process/agent-prompts.md` without re-deriving anything.

## Overnight run 2026-09-05 — Phases 0, 1 and 2 done; Phase 3 gated on rulings (handover)

Owner asleep; orchestrator ran `docs/port-plan.md` Phases 0, 1 and 2 to
completion (eleven Sonnet ports merged, every Phase 2 spec written first),
wrote the auth rulings batches that gate Phase 3, and stopped there — no
auth Java without Batch A. Every unit below was reviewed by the orchestrator (code
read, not the report), mutation-checked on its security-bearing assertion,
and merged only after the unit's tests plus the lockfile check were green
in its worktree; main's full suite is re-run after each merge.

**Landed on `main`, in order:**

| Commit | Unit | Notes |
|---|---|---|
| `931d5a7` | Backoff ruling `3b64775` (`lastSuccessAt` bounded to 400 d) + PoolTest flake fix | orchestrator |
| `c6d7e36` | fcdev downloads its Postgres archive on first run — jar 181 → 47 MB | Sonnet; orchestrator added HTTP timeouts. Found zonky's own POM drags four archives in transitively |
| `dd1874b` | Flyway V2–V7 = Go 046–052 (secret grace, dispatch-mode default, **login-attempts partitioned**, X-06 CHECK constraints); Go schema re-captured at HEAD | Sonnet; two Go HEAD defects found → `backlog.md` (seeder writes `'JSON'` against its own CHECK; the login-attempt partition note was later corrected — Go's purger maintains them) |
| `22bbffc` | **X-06 strict stored-enum reads across twelve modules** | Sonnet sweep + orchestrator merge fix-up (CHECK constraints landed underneath it). `AttemptOutcome` no longer reads a corrupt row as a login SUCCESS; `ScopeType` no longer defaults to ANCHOR. Shared `CorruptRowException` → 500 `CORRUPT_ROW` |
| `9a4e6fc` | **authadmin**: anchor domains, client auth configs, IdP role mappings (11 ops) | spec `auth-admin-config.md` by orchestrator; Sonnet port; coverage **203/245** |
| `(merge)` | **serviceaccount** (14 ops), RS256 mint under the platform key, CAS plaintext upgrade, OAuth marker | Sonnet port; orchestrator replaced an HS256-under-app-key minter and a fake OAuth pair in review; coverage **206/245** |
| `(merge)` | **SDK ingest** (5 routes: events, dispatch jobs, audit logs) | Sonnet port; found Go's cross-request event dedup never fires (index includes `created_at`); coverage **208/245** |
| `(merge)` | **principal 404 oracle (PR-3/PR-4), X-02 sync containment, X-08 per-app rollup groups, five residual X-06 readers** | Sonnet; orchestrator mutation-checked the 404 (seven assertions) |
| `(merge)` | **stream processor** (Phase 2 unit 1): fan-out, projections, partition manager, health | spec `stream.md` by orchestrator; Sonnet port; orchestrator mutation-checked the claim transaction |
| `(merge)` | **outbox processor** (Phase 2 unit 2) | spec `outbox.md` by orchestrator; Sonnet port; orchestrator replaced a racy exclusivity test with a held-transaction one that kills the no-lock mutant |
| `7391dbe` | **scheduled-job scheduler + purger** (Phase 2 unit 3): poller, HMAC-signed dispatcher with credential cache, login-attempt partition maintenance | spec `scheduled-job-scheduler.md` by orchestrator; Sonnet port (five mutants killed); orchestrator killed a sixth (signing with the token instead of the secret) |
| `5b88313` | **BFF + `/api/me`** (Phase 1 last unit): dashboard, filter options, developer, event types, roles, scheduled jobs, `/bff/*` aggregate mounts, `/api/me*` | spec `bff.md` by orchestrator; Sonnet port (eight mutants); orchestrator admin-gated the dashboard (spec draft was wrong, agent flagged it) and killed a ninth mutant |
| `(merge)` | **MCP server** (Phase 2 unit 4): 12 tools, 9 resources, streamable HTTP on its own listener, client-credentials token manager + interim static bearer | spec `mcp.md` by orchestrator; Sonnet port (the agent stalled twice waiting on its own suite and never wrote a report — the orchestrator read the code, added the bearer assertion, killed two mutants); coverage unchanged |
| `c55d6dd` | **AWS Secrets Manager DB mode + rotation** (Phase 2 unit 5, last) | spec `db-secret.md` by orchestrator; Sonnet port — the cleanest of the night: ten mutants incl. the disabled timer, a clear report; orchestrator killed an eleventh (credentials clobbered on a failed refresh) |
| `(merge)` | **CORS filter** (Phase 4): allowlist-driven headers, preflight answered ahead of the authenticator, 30 s cache + invalidation | contract `cors.md` §9 by orchestrator; Sonnet port (five mutants); orchestrator added the preflight headers its worktree's spec lacked (specs must be committed before worktrees are cut) and a scope test, two more mutants |
| `(merge)` | **JFR events** for the five Phase 2 loops (Phase 4) | spec `jfr-events.md` by orchestrator; Sonnet (seven mutants, a strong report — it found the spec in the main checkout when its worktree lacked it); orchestrator added the outbox `persisted=false` case and its mutant |
| `(merge)` | **fcdev `mcp`, `outbox` + `create-table`, `upgrade`** (Phase 4) | spec `fcdev-commands.md` §2–§4 by orchestrator; Sonnet (five mutants; found that worktree agents' `mvn install` into the shared `~/.m2` clobber each other — now in `CLAUDE.md`); orchestrator added two tests + mutants |
| `(merge)` | **fcdev `init`** (Phase 4, last stub) | spec `fcdev-commands.md` §1; Sonnet (four mutants; two judgement calls reported, both sound); orchestrator resolved the stub-file conflict, wrote the §4 docs, killed the skipped-admin mutant |
| `a9e583f` | **Phase 3 A1 — token issuance core + `DbClaimsResolver`** | orchestrator; six mutants |
| `181630a` | **Phase 3 A2 — session surface** (`/auth/check-domain` ×2, `/auth/login`, `/auth/logout`, `/auth/me`, `/auth/login-history`; backoff as a real lock, fail-closed store) | orchestrator; five mutants; Go lock-anchor deviation recorded |
| `6b26f29` | **Phase 3 A3 — OAuth-client aggregate + admin API** (12 routes; `acceptsSecret` both-compares; empty grant list ⇒ none, C-Q20) | Sonnet in a worktree, strong; six mutants (five agent, one orchestrator on the null-vs-empty junction split); coverage 229/245 |
| `4643de0` | **Phase 3 A4a — grant store, refresh rotation, rate-limit store + governor** | orchestrator; mutants; JSONB/precision test pitfalls documented in the tests |
| `(next)` | **Phase 3 A4b — the OAuth / OIDC provider** (`/oauth/authorize`, `/oauth/token` ×3 grants + developer branch, `/oauth/introspect`, `/oauth/revoke`, `/oauth/userinfo`, discovery, JWKS, `/auth/refresh`; per-IP and per-client throttles; A-22 previous-secret signal) | orchestrator; 42 HTTP tests over embedded Postgres, six mutants killed (state ≤116, code↔client binding, previous-secret stamp, apiAccess ceiling narrowing, refresh client binding, introspection `client_id` = azp); wired in `Platform` |
| `69dbf9e`, `3b924ea` | Specs written: `auth-admin-config.md`, `sdk-ingest.md` | orchestrator. `sdk-ingest.md` §5 D1: Go's dispatch-job ingest checks a permission no role grants |

**Phase 4 (2026-09-05, on the owner's go-ahead): CORS filter, JFR events
for the Phase 2 loops, and all four fcdev stubs landed** — rows above.
Left in Phase 4: the pagination envelope (owner decision, wire change) and
the optional native fcdev build.

**Phase 3 (auth) started 2026-09-05 after every ruling was given.** Landed:
A1 token issuance + store-backed claims resolver (`a9e583f`), A2 the
session surface with the enforced backoff lock (`181630a`), A3 the
OAuth-client aggregate (`6b26f29`, Sonnet), A4a the grant store, refresh
rotation and rate limiting (`4643de0`), A4b the whole OAuth / OIDC provider
(row below). Found while porting A2: Go anchors the enforced lock to the
oldest failure of the ceiling set, not the last failure the spec names —
`docs/backlog.md`. **Next:** A5 MFA (orchestrator core + Sonnet routes),
then the purger sweeps for `iam_rate_limit_events` + the four auth tables,
the login-attempt partition readiness check (C-Q23), then Batch B (OIDC
bridge, portal) and Batch C (WebAuthn, password reset, approvals, mail).
Until the portal unit lands, `/oauth/token` refuses a `ptu_` code with
`invalid_grant "Portal subjects are not supported"`.

Final whole-reactor `mvn clean test` on main at the end of the overnight run:
**usecase 30 · sdk 44 · server 2983 · fcdev 47, zero failures** (server was
2772 when the night started).

**Where to resume:** Phase 3 needs Batch A of `docs/auth-rulings.md`
ruled. Until then the unblocked work is Phase 4's CORS filter and JFR
events (Sonnet, small) and the fcdev stubs `mcp` / `outbox` / `upgrade`,
which Phase 2 now gives something to drive. The `TODO(port)` markers left
in `Server`/`Platform` are the router's `/metrics` alias and the Phase 3
auth registrations (incl. the DB-backed `ClaimsResolver`); `Main` has none.

**Sonnet, honestly, across eleven ports:** reliable when the brief names
the template class, the exact routes and the mutants to run; two agents
produced the best work of the night on exactly that shape (outbox
concurrency aside, db-secret). Unreliable on unbriefed judgment — it
invented an HS256 minter under the encryption key and a fake OAuth pair
(serviceaccount), wrote a barrier-based "exclusivity" test that could not
fail (outbox), followed a wrong line in my own spec into an admin gate
that was open to every user (BFF, flagged by the agent, my error), and
one agent stalled twice waiting on its own test run and never wrote a
report (MCP). Nothing it wrote reached main unread.

**Phase 3 gate cleared (owner, 2026-09-05):** every question in
`docs/auth-rulings.md` was asked one by one and ruled; the rulings are
recorded there ("Rulings — Batch A/B/C"), in the Rust ledger, and as a Go
mirror list in `docs/backlog.md` with a verified patch for the mechanical
part in `docs/go-mirror/`. Auth (Phase 3) can start with the first cut:
JWT/RS256 issuance, PKCE, sessions, `/auth/login`, MFA.

**For the owner, collected in `docs/backlog.md`:** three Go HEAD defects
(seed `'JSON'`, unextended partitions, dispatch-ingest permission); the
wire-side leniency the X-06 sweep preserved under `parseWire` (role
`/by-source`, identity-provider create, platform-config value type,
connection update status) — Go's X-06 rejects unknown wire values too, so
this is a yes/no; and the questions in the two new specs' §8/§5.

## fc-server packaging and the default-broker gate (2026-09-04)

The owner restated the two deliverables, the same split as the Go repo:
**`fcdev`** is the developer monolith (fc-server + embedded Postgres + the
built-in Postgres broker + `start|stop|fresh|db upgrade`); **`fc-server`** is
the production server with every subsystem behind `FC_*_ENABLED` and **no
bundled broker** unless `FC_DEFAULT_BROKER=postgres`. Neither is a native
binary: both are executable jars, and JBang is optional for `fcdev` (the
owner does not want to register with JBang yet).

- `server/pom.xml` now shades an attached `flowcatalyst-server-<v>-exec.jar`
  (main class `io.flowcatalyst.server.Main`, `Implementation-Version` stamped
  so `/health` reports the build). The thin jar stays the main artifact, so
  `fcdev`'s own shade is unchanged. `Dockerfile` + `.dockerignore` mirror the
  Go image: Temurin 25 JRE on Alpine, uid 10001, ports 8080/9090, the same
  `wget /health` check. README has a "Run" section.
  **2026-09-05: the image ships a jlink runtime, not a stock JRE** — jdeps
  picks the 13 modules the jar needs, jlink adds the reflection-only ones
  (EC crypto, Unsafe users, JNDI, JMX, zipfs), stripped and compressed, on
  bare Alpine. Image **121 MB** (the `eclipse-temurin:25-jre-alpine` base
  alone is 225 MB); verified router-only in the container: health, router
  health, healthcheck, uid 10001. Still HotSpot with the full JIT.
- **Defect fixed (Java-only):** `Router.configSource` synthesised the Postgres
  broker queue whenever `FLOWCATALYST_CONFIG_URL` was blank, ignoring
  `FC_DEFAULT_BROKER`. Go (`server/run.go:346`) and spec §8.4 only do so when
  the broker is `postgres`; otherwise "no pools will start". A production
  router with neither would have built a queue from the database URL. Now
  gated; the default path also runs `PostgresQueue.initSchema` when a data
  source exists (Go does this in the router bootstrap, Java only did it in the
  scheduler publisher), and a blank database URL falls back to Go's
  `postgresql://postgres@localhost:5432/flowcatalyst`.
  `RouterConfigSourceTest` pins all five branches; removing the gate fails
  two of them (mutation-checked).
- Smoke-verified from the jar: `FC_PLATFORM_ENABLED=false FC_ROUTER_ENABLED=true`
  starts with no database, `/ready` shows only the router, `/router/health`
  is HEALTHY, log says "no pools will start".
- Still open for a router-only default-broker instance: `QueueFactory` needs
  a `DataSource`, but `Main` only opens one for database-backed subsystems,
  so `FC_DEFAULT_BROKER=postgres` on a router-only fc-server logs "needs
  postgres but no database is configured" and starts nothing. Go opens its
  own pgxpool from the URL. Owner question, not fixed here.

## GraalVM native-image trial (2026-09-04/05) — it works, 89 MB

Owner asked "just to see if it would work". It does: `server/target/fc-server`
is an **89 MB** arm64 Mach-O built in ~56 s by `mvn -DskipTests -pl server -am
-Pnative package` under `mise install java@oracle-graalvm-25.0.4.1` (the
project default JDK stays Temurin; the profile is opt-in). Verified with the
binary, not the jar: router-only with no database; platform + router +
default broker against a migrated database; and against an **empty**
database, which Flyway migrated and the seeder populated. Every surface
answered — `/health` with the stamped version, `/ready`, `/router/health`,
the `/api/*` list routes, an event-type create (201), the embedded SPA,
`/openapi.json`, `/docs`, `/metrics`. RSS: 46 MB router-only, ~105 MB full
platform; the same jar is 181 MB router-only.

**The first working image was 183 MB.** The build report (`--emit
build-report`) and analysis call tree found three causes, all self-inflicted:

1. `-H:IncludeResources=(…|io)/.*` swept **every `.class` file under `io/`**
   (netty, prometheus, nats, javalin, ours) into the image heap as bytes —
   24 MB of bytecode the image already contained as code. The pattern now
   names exactly our runtime resources (9 MB, mostly the SPA).
2. The blanket reflection config made **15,000 of our methods entry points**
   (every jOOQ generated method included), defeating dead-code elimination
   and, via `org.jooq.tools.reflect.Compile`, pulling `jdk.compiler` in.
   `io.flowcatalyst.tools.NativeReflectConfig` (test scope, uses the JDK
   class-file API) now registers only records, enums, Jackson-annotated
   classes and jOOQ record types: 906 entries instead of 1,399, and far
   narrower ones.
3. `netty-nio-client` came with the AWS SQS/ELBv2 artifacts as a runtime
   dependency though only the sync clients (apache5) are used. Excluded in
   `server/pom.xml`; the jar lost ~3 MB too. `-Os` added.

Residual: `jdk.compiler` is still reachable (3.5 MB) because jOOQ's SQL
parser (`DefaultParseContext.parseDataTypeEnum` → `Reflect.compile` →
`ToolProvider.getSystemJavaCompiler`) is reachable from jOOQ-internal paths
(`Convert$ConvertAll.from`, `TableImpl.accept`, `MetaImpl`), not from our
code. Cutting it needs a GraalVM substitution and an SDK dependency in the
production module; not worth 3.5 MB.

Four earlier build iterations each fixed one thing: Jackson could not see
record components (reflection config); jOOQ `SQLDataType.<clinit>` NPE on
unregistered array classes (agent-captured metadata in
`server/native-config/`, README there says how to re-capture); `/health`
said `dev` (`-Dfc.version` + `--initialize-at-build-time` for `Version`);
and **Flyway found no migrations** in an image ("unsupported protocol:
resource") — fixed in production code, gated to images: `IndexedMigrations`
is a Flyway `ResourceProvider` over the committed `db/migration.index`,
installed by `Migrator` only when `org.graalvm.nativeimage.imagecode` is
set. `IndexedMigrationsTest` pins index == directory listing **and** that an
indexed Flyway migrates a fresh database; an emptied index fails all three.
**Adding a migration now needs an index line** — the test tells you.

Not done, deliberately: no native tests run in the image (the JVM suite is
the authority), no Linux/Docker native stage (the Dockerfile ships the jar),
no PGO, and the agent metadata covers only the routes exercised above — an
unexercised reflective path (MCP, SQS, NATS, outbox, the auth surface once
ported) surfaces as a runtime "not registered" error, not at build time.

**Suite flake to fix:** `PoolTest.rateLimitWarnsOnceForARun` failed in two
of three full `mvn clean test` runs on 2026-09-04/05 (the DIAG line shows the
RATE_LIMIT warning *was* raised but the counter read 0) and passed alone
every time. Order- or load-dependent, not caused by today's changes; commit
`9e172f3` added the diagnostics for exactly this.

**fcdev size (owner, 2026-09-05):** the 181 MB fcdev jar is 129.5 MB of six
per-platform Postgres archives, of which any machine uses one. The Go fcdev
(fergusstrange/embedded-postgres) downloads the matching zonky archive from
Maven Central on first run and caches it. Zonky's `PgBinaryResolver` hook
makes the same change ~100 lines here and would put fcdev near 50 MB.
Not done; owner's call.

## Router completion drive (2026-09-02)

The owner asked for the full router port to be completed against the
implementation-neutral contract `../flowcatalyst-rust/docs/router-specification.md`
and its ruling ledger `../flowcatalyst-rust/docs/owner-questions.md` (the
authority order is in the spec's §0; `docs/spec/router.md` is pre-ruling).
Go absorbed every ruling in `2e2e466..7ae5acd`, so it is once again an
accurate reference for shape, never for correctness.

The gap audit and unit plan are `docs/spec/router-completion.md`; the platform
half was spec-extracted first into `docs/spec/dispatch-seam.md` (1105 lines,
11 owner questions in §14). Orchestration: Fable 5.1 planned, specced,
reviewed and merged; Sonnet 5 agents at medium effort wrote the code in
isolated git worktrees (builds must not share `target/`), one squash commit
per unit on `main`, full suite re-run on `main` after each merge.

| Unit | Commit | What landed |
|---|---|---|
| 5 observability | `4d2afce` | X-04 notifier floor env-tunable, INFO 1h TTL, `cleanup()` scheduled (A-08), R-52 group-flush list + clear, R-53 series, R-56 instance id |
| 1 delivery contract | `f2402b0` | R-57 5xx boundary (REJECTED is an explicit component, terminal on first attempt), R-12 origin+path breaker key, corpus `metric` column asserted, CIRCUIT_BREAKER/RATE_LIMIT warnings once per transition |
| 4a config lifecycle | `8a41635` | A-10 five-minute re-poll, R-30 last-known-good per source, R-33 gated real reload, R-36 consumer liveness → readiness, CONNECTION + QUEUE_HEALTH warnings |
| 2 A-01 gate | `dffe9b6` | BLOCK_ON_ERROR siblings released unless `FC_ROUTER_PLATFORM_URL` is set; settled reporter (ACK first, fire-and-forget, 1000/chunk, 5s) |
| 6a platform half | `2ea284f` | Cancel/Complete verbs (+ lockfile), one GroupHolding SQL fragment, `/api/dispatch/settled`, reaper, X-06 strict status parse, X-01 subscription default |
| 3 routing | `1dffd24` | `FC_ROUTER_STRICT_ROUTING` gate (off), R-59 synthesised-pool eviction, layer-2 dedup wired, drainer resurrection, per-consumer backpressure |
| 6c processing endpoint | `95929a3` | `/api/dispatch/process` with the §5 outcome table, delivery-time hold-back spending no budget, 5/15/30/60/120s ladder |
| 6b scheduler | `23d4ce1` | claim → mark QUEUED → commit → publish; poolCode composed at publish time; per-job HMAC bearer; stale recovery; scheduler-suffixed election; Postgres publisher |
| 4b lifecycle | `8b632da` | removed pools drain, replaced consumers linger until unreferenced, stall supervisor wired without aborting deliveries, `/monitoring/blocked-groups` (R-04) |

**Three step-3 audits** (fresh Sonnet readers, read-only) found five real
defects the coders' own mutation checks had not: `RouterServer` held the
leadership monitor across a config fetch of up to minutes (a leadership loss
mid-fetch kept the old leader polling); `Pool.submit` racing `close()`
swallowed the rejected task so an IMMEDIATE message was neither acked nor
nacked; the reaper was started and never stopped, sweeping the shared test
database; the dispatch-job GET routes still answered 403 for an out-of-scope
id while PR-3 requires a byte-identical 404; the subscriber response was read
unbounded before the 64 KiB cap. All are fixed or in the pending branches.
The audits also asked for a cross-implementation HMAC vector and pins for the
endpoint's three 500 paths.

The drive closed with the X-01 enum merge (one
`platform.shared.dispatch.DispatchMode`, `Subscription.DEFAULT_MODE` no
longer `IMMEDIATE`), the audit-fix commits, and two full-run flakes fixed at
the root (the throttle metric is one act; the test HTTP readiness probe has
a per-attempt timeout). Final uncontended run: **server 2356 tests green**,
lockfile 192/245 covered, zero drift.

**Open after this drive:** SQS/NATS dispatch publishers, signed subscriber
deliveries (needs `serviceaccount`), the owner questions in
`dispatch-seam.md` §14, the ledger's deferred R-items, and the two backlog
notes on the load-sensitive rate-limit test and leaked test threads.

## Where we are (2026-08-24, evening)

Reactor green on a clean uncontended build, 2026-08-27: **2270 tests** —
usecase 30 · sdk 44 · **server 2156** · fcdev 40, 0 failures. Coverage
**190/243 lockfile operations (78%)**, zero drift. Commits on `main`; one commit
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

Smaller, tracked in place: `Q41` metrics contract
(`RouterPrometheusCollector.java:54`). **`Q19` is closed** — see below.

### Go drift check, 2026-08-27

Ran against Go `426ac85`. Two live Java defects found and fixed, three auth
changes recorded for the unported side, and several places where Java was
already right.

**Fixed — `Q19`, NATS at-least-once was silently at-most-once.** The broker
id was `<streamSeq>:<consumerSeq>`, and the **consumer** sequence counts
deliveries, so it changes on every redelivery. The tracker read a changed
broker id under a known app id as "a rival copy exists — an external process
requeued work we still own" and ACK-deleted the arrival, so JetStream
destroyed its own copy on every ack-wait lapse; a later release or pool flush
then lost the message with only a "no pending message for receipt" warning.
Both identities now derive from the **stream** sequence, which every delivery
shares. `consumerSeq` was removed from `classify` entirely rather than left
unused, so the mistake is unavailable. Go ruled by fixing it the same way
(`20e9fe7`) with the loss demonstrated, which turns a parked question into a
defect. **The existing test asserted the bug** (`isNotEqualTo` on a
redelivery's broker id) and passed; it now asserts the same id.

**Fixed — TSID string order was noise below the millisecond.** Layout was
`ms | random | seq`; ids sort as strings and Crockford Base32 is
order-preserving, so the bit order **is** the sort order, and the counter that
guarantees uniqueness contributed nothing to ordering. Two ids minted in one
millisecond came out backwards about half the time — and these ids are the
keyset-pagination cursor. Now `ms | seq | random`, matching Go `17e737a`.
**The test documented the defect instead of catching it**: it decoded
`(ms, seq)` and compared that, with a comment explaining that the random bits
sat between them. It now compares the raw strings, which is what actually
sorts in an index.

**Already correct in Java, confirmed against Go's fixes:** redirects are not
followed (`Redirect.NEVER`, with the same reasoning Go reached in `2468140` —
301/302/303 downgrade POST to GET and drop the body, delivering nothing and
reporting success); `Pool.mediating` is keyed by worker `Thread`, not message
id, so `activeWorkers()` cannot under-report or let a loser's exit delete a
winner's entry; there is no `ExtendVisibility`; the Postgres quarantine is
`queue_messages_failed` keeping the latest failure. `89b195e` shows Go
adopting our `NEXT_ON_ERROR` ruling including logging an unrecognised mode —
full convergence on the router's enum.

**Recorded, not fixed:**

- `backlog.md`: **two `DispatchMode` enums with opposite defaults**. The
  router's takes `NEXT_ON_ERROR` per the ruling; `platform.subscription`'s
  still silently takes `IMMEDIATE`. Go applied the ruling at every layer.
  Needs one line from the owner because it changes how existing rows read.
- `dispatchjob.md`: Go `5762aa1` — `BLOCK_ON_ERROR` must hold a group while an
  **earlier** job is `FAILED`/`ERROR` **or backed-off**, compared
  **positionally**. A backed-off job is `PENDING` with a future
  `scheduled_for`, so nothing treated it as holding anything and its
  successors overtook it. The Java scheduler is unported: a note to
  implement, not a defect to fix.
- `auth-core.md` §0 (new): `de868dd` (`FindByServiceAccount` hydrates roles
  only, so an app-scoped SA's token carried an empty `applications` claim),
  `304338a` (the `scope` claim must be bounded by the role ceiling at every
  tier; an explicit request intersecting to nothing is now `invalid_scope`),
  `8d7ddbc` (per-client narrowing must emit canonical `{app}:{role}` names,
  not short names). Auth is unported, so these are corrections the port must
  reproduce rather than defects to fix — `de868dd` also lands on the
  `serviceaccount` unit still on the platform queue.

### Two cross-cutting changes, 2026-08-26

**Jackson 3 (`tools.jackson`)** — owner ruling, reversing the earlier "stay on
2.x": the migration cost is paid once either way, and paying it now avoids
paying it on someone else's schedule. `jackson-annotations` keeps its
`com.fasterxml.jackson.core` coordinate, which Jackson 3 never renamed.

The trap, because it will be hit again: **do not add `jackson-datatype-jsr310`
or `jackson-datatype-jdk8`.** Jackson 3 folded both into databind —
`java.time` lives in `tools.jackson.databind.ext.javatime`. The
`tools.jackson.datatype` jsr310 artifact is *retired*, which is why the BOM
looks like it points at an unpublished version. That is not a broken pointer
to work around, and pinning `3.0.0-rc2` to satisfy it puts a pre-GA
dependency in the build to get behaviour databind already has. Verified: with
no module registered, databind renders an `Instant` as
`2024-03-05T07:08:09.123456789Z` by itself. The fixed six-digit RFC 3339
layout comes from the `micro` `SimpleModule` in `platform/shared/json/Json.java`,
which must stay registered — `JsonTest` pins it, and removing `micro` fails 6
of its 10 assertions. The one remaining jsr310 dependency, in `sdk`, is real:
openapi-generator's templates hardcode Jackson 2.

**`RouterApi` split** — 1337 lines into 11 files, none over 241, `RouterApi`
itself 169. Grouped by resource, not by verb, and each group registers its own
routes so a path and its handler stay adjacent (what Go gets from
`huma.Register`). `Wire` is the `dto.go` counterpart; `Http` holds the shared
query reading and the two §9.1 error shapes. A pure move — no handler body
changed. Checked as one: 55 routes before and after, identical set, each still
bound to the **same** handler, compared as path→handler pairs rather than
paths alone.

### The `DashboardHandlerTest` "flake" was not a flake (2026-08-26)

It failed 3 of 4 in one full-suite run and passed standalone and on three
clean re-runs. First written up here as the Jetty first-connection race
`RouterApiTest.warmUp` absorbs. **That was wrong**, and the giveaway was in
the line already captured: `Failures: 3, Errors: 0` in 0.044s. A dropped
connection surfaces as an `UncheckedIOException` — an **Error**. Three
*assertion* failures, with no time spent, means the requests all succeeded
and returned the wrong body.

The reconstruction that fits every detail: `DashboardHandler` reads
`dashboard.html` from `target/classes` **once, at construction**, and
`readAllBytes` returns whatever has been copied so far without complaint. A
second Maven run over the same `target/` re-copies that resource; catch it
mid-copy and the handler renders an empty page and serves it as `200
text/html`. The three tests sharing the `@BeforeAll` instance then fail their
body assertions, while `rootMountSubstitutesEmptyPrefix` — the only one that
constructs its own handler, later, once the copy has finished — passes. Three
of four, and exactly those three.

So this is the **concurrent-Maven hazard `Claude.md` already documents**,
caught in the act, not a test to stabilise. A `warmUp` retry would not have
prevented it and would have been a fix for a failure mode that was not
occurring.

What was worth changing:

- **`DashboardHandler` now refuses an incomplete template** — it must contain
  the `__FC_API_BASE__` token *and* end in `</html>`. Both are needed: the
  token sits at byte 845 of ~91 KB, so it survives almost any truncation. The
  rules live in a package-private `validated(String)` so a test can state a
  partial document rather than contrive one on a classpath. This matters
  beyond the test — a bad build would otherwise ship a blank dashboard that
  answers 200.
- **`TestHttp.close()` now closes its `HttpClient`.** It never did: 1200
  create/close cycles left 1401 live threads, and closing it brings that to
  1203. The residual ~1 per instance is Javalin's own non-daemon helper
  (`JettyServer.kt:41`), not reclaimed by `app.stop()` and not fixable from
  the harness — recorded in `TestHttp` so nobody re-hunts it there.

Neither is the cause; the cause was running two builds over one `target/`.

### SDK drift picked up from Go (2026-08-26)

Go `7db14b7` / `c7dcb4a` / `2d10924` touched the SDKs' handling of the
application code. Ported and recorded in `docs/spec/sdksync.md` §6:

- `DefinitionSet.defineFromEnv()` + `APP_CODE_ENV` added to the Java SDK, an
  explicit factory rather than a fallback inside `define`. Blank/unset throws
  **at the call site** instead of surfacing later as
  `POST /api/applications/null/…`.
  Java's version splits out `defineFrom(String)` so both branches are
  assertable: Go's test can only exercise whichever branch the machine's
  environment happens to give it, which leaves the rejection path — the one
  that matters — untested wherever the variable is set.
- **No per-definition application override** in the Java SDK, deliberately;
  the Laravel one exists only because its definitions are found by scanning
  the filesystem. Recorded so nobody "fixes" the absence.
- `CreateDispatchJobDto` documented `IMMEDIATE` as the platform default. It
  is `NEXT_ON_ERROR` (owner ruling, below). Corrected here; Go fixed the
  Laravel and TypeScript SDKs in `2d10924` and **missed its own java-sdk** —
  `router-fixes.md` Fix 9.
- `docs/spec/router.md` §2's `dispatchMode` wire row still said "`IMMEDIATE`
  (default when absent/unknown)". Now records the ruling, the deviation from
  Go, and that an *unknown* mode is logged rather than folded into the
  default.

## Next wave

**Superseded 2026-09-05 by [`docs/port-plan.md`](port-plan.md)** — the router
half of the old plan is done (drive closed 2026-09-02); the platform half is
re-sequenced there with the Sonnet/orchestrator split. Short form: Phase 0
housekeeping (auth ruling `3b64775`, the PoolTest flake, fcdev download-on-
first-run) → Phase 1 remaining aggregates + SDK batch + BFF → Phase 2 stream,
outbox, scheduled-job scheduler, purger, MCP, Secrets Manager → Phase 3 auth
(gated on rulings, surfaced in three batches) → Phase 4 cross-cutting →
Phase 5 drop-in verification and CI.

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
