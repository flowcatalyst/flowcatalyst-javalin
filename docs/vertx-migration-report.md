# Vert.x migration report

## Phase 1: restore the Vert.x listener, reconciled with main

Branch `vertx-2`, worktree `flowcatalyst-javalin-vertx2`. Goal: bring back the
Vert.x 5 HTTP listener as the API/metrics listener, exactly as it existed at
`f892f38^` (the tree just before the 2026-09-08 revert), reconciled with the
81 commits `main` landed afterward. MCP stays on its own, independent
Javalin/Jetty listener — this phase does not touch it.

### Files restored (from `f892f38^`, main's semantics kept where they conflicted)

- `server/src/main/java/io/flowcatalyst/http/vertx/VertxExchange.java` — new, unchanged from the old tree.
- `server/src/main/java/io/flowcatalyst/http/vertx/VertxListener.java` — new, unchanged from the old tree except one fix (below).
- `server/src/main/java/io/flowcatalyst/http/vertx/VertxRoutes.java` — new, unchanged from the old tree.
- `server/src/main/java/io/flowcatalyst/server/transport/Listeners.java` — restored to the Vert.x version (`Listeners.resolve(Env)` returning `Optional<VertxListener.Tls>`), replacing the Javalin/Jetty connector-builder version.
- `server/src/main/java/io/flowcatalyst/server/Metrics.java` — restored to the Vert.x version: same `/health`/`/ready`/`/metrics` routes and bodies, listener now a second `VertxListener` (`RequestWorkers.of(1, Map.of())`, `Budgets.none()`, h2c off) instead of a second Javalin app.
- `server/src/main/java/io/flowcatalyst/outbox/OutboxAdminApi.java` — restored to the Vert.x version (`OutboxAdminApi.Running` wrapping a loopback `VertxListener`), same routes and semantics.
- `server/src/main/java/io/flowcatalyst/server/Server.java` — merged (see "Conflicts" below).
- Test-only harness/tests: `TestHttp.java` (Vert.x-backed, `Adapter` enum removed — only one adapter exists again), `SeamContract.java` (`start()` no longer takes an `Adapter`), `VertxSeamContractTest.java` (new, replaces `JavalinSeamContractTest.java`, renamed via `git mv`), `RequestWorkersTest.java` (restored, including the end-to-end `theListenerQueuesTheThirdRequestWhenTwoWorkersAreBusyAndAnswersItAfterwards` test that only makes sense once a listener drives `RequestWorkers`), `BudgetsTest.java` (restored — dropped the Javalin-only `theLoginGroupAdmitsItsBudgetAndQueuesTheNextRequestUntilAHandlerFinishes` test, which asserted the now-deleted Javalin adapter's semaphore-around-the-handler mechanism), `server/src/test/java/io/flowcatalyst/http/vertx/VertxListenerTest.java` (new, `docs/spec/vertx-listener.md` §2 rows 2–6), `Http2Test.java` (restored to the Vert.x-client version, see "Conflicts"), `HttpMediatorVersionTest.java` (restored to the Vert.x TLS/ALPN fixture version).

### Files removed

- `server/src/main/java/io/flowcatalyst/http/javalin/{JavalinAdapter,JavalinExchange,JavalinJsonMapper,JavalinRoutes,SkipRemainingHandlersSignal}.java` — the whole adapter package.
- `server/src/test/java/io/flowcatalyst/http/JavalinSeamContractTest.java` (renamed to `VertxSeamContractTest.java`).

### Files deliberately left alone (main already correct, or unrelated to the listener)

- `server/src/main/java/io/flowcatalyst/server/transport/TlsMaterial.java` — main's version is *tighter* than the old tree's: it dropped a redundant `FC_HTTP3_ENABLED`-with-no-material guard that was dead code even in the old tree (`Listeners.resolve` already rejects `http3Enabled` before ever calling `TlsMaterial.resolve`). Kept main's version as-is.
- `server/src/main/java/io/flowcatalyst/mcp/McpServer.java`, `McpServerTest.java`, `fcdev/.../McpCommand.java` — untouched. Main already fully wired MCP (not parked, unlike the old tree) on its own independent Javalin/Jetty listener; that's exactly what this phase was told to preserve.
- `parity/.../Normaliser.java` — main's diff from the old tree is an unrelated parity fix (masking `updated_at`), not a listener change. Left alone.
- `server/src/test/java/io/flowcatalyst/server/ServerTest.java`, `TransportTestSupport.java` — framework-agnostic already (boot `Server`, hit HTTP endpoints); needed no changes. `ServerTest`'s new `aWorkerWithAMisconfiguredSqsDispatchSetupRefusesToStart` test (added on main after the revert, unrelated to the listener) is untouched and still passes.
- `server/src/test/java/io/flowcatalyst/server/LockfileCoverageTest.java` — only a comment differed between the two trees; left main's wording (still accurate).
- `fcdev/native-config/reachability-metadata.json`, `server/native-config/reachability-metadata.json` — diffed against the old tree: every difference is *additive* Jetty/Javalin/websocket reachability entries that main gained after the revert (needed for MCP's still-live Javalin listener) and the old tree never had (`vertx` never appears in either file — Vert.x apparently needs no explicit reflect-config entries). Left unchanged; nothing to regenerate.

### Conflicts with main, and how they were resolved

1. **`Server.start()`'s ordering.** Main's order (internal/metrics listener binds → `Router.build(...)` → API app built → subsystems → API listener binds → `router.startElection()`, both closed on failure) was kept exactly. This mapped cleanly onto Vert.x's own build/bind split: `buildApiAndReaper` now calls `VertxListener.prepare(options, configure)` (routes built, socket not yet bound — the "API app built" step) and the returned `ApiStarter.start(port)` calls `prepared.listen()` (the actual bind — the "API listener binds" step, still happening after subsystems as on main). `RouterStartupOrderTest` passed unchanged.
2. **MCP.** The old tree's `Server.start()` threw `IllegalStateException` when `FC_MCP_ENABLED=true` (MCP was parked). Kept main's version instead: full `McpServer.start(...)` wiring via `TokenManager`/`PlatformClient`, on MCP's own listener, untouched by this phase.
3. **`OutboxAdminApi`/`Metrics` field types in `Server.Running`.** Changed `Javalin outboxAdminApi` back to `OutboxAdminApi.Running outboxAdminApi` (and the constructor/local-var types to match) since the restored `OutboxAdminApi.start(...)` returns that type again, matching the old tree.
4. **`RequestWorkers` wiring.** The restored `VertxListener.Options` requires a `RequestWorkers` and `buildApiAndReaper` now computes `mainWorkers` from the pool's `GatedDataSource#ordinaryPermits()` (falling back to `Database.DEFAULT_POOL_SIZE - 2`) and registers `workers.collector()`, exactly as the old tree did. This isn't new scope — `RequestWorkers` is unchanged on main and simply had no production caller until the listener that drives it came back; the brief (§0) already flags this as the whole point of the move.
5. **`Http2Test`/`HttpMediatorVersionTest`.** Restored the old tree's Vert.x-client-based versions (they test the *server's* h2c/ALPN behaviour with a Vert.x client instead of a Jetty one), but kept main's `http3EnabledIsARejectedStartupErrorNotASilentNoOp` test — added to `main` after the revert and required by this phase's brief — appended to the restored `Http2Test.java`.
6. **`NoFrameworkLeakTest`.** Main had *already* split this into two scans (a Javalin scan and a Vert.x scan) to accommodate the outbound `VertxMediationClient` coexisting with the Javalin listener. Kept that two-scan structure (rather than the old tree's Vert.x-only single scan) and just narrowed the Javalin allow-list to `io/flowcatalyst/mcp/McpServer.java` only (Server/Metrics/OutboxAdminApi/TestHttp no longer import Javalin; the `http/javalin/` package is gone) while keeping the Vert.x allow-list exactly as the old tree had it (`http/vertx/` package, this test file, `Http2Test`, `HttpMediatorVersionTest`).
7. **A real bug the restore surfaced**: `VertxListener.java:322`'s `LOG.error("unmapped failure on {} {}", x.method(), x.path(), t)` used SLF4J `{}` interpolation. `StructuredLoggingTest` (added to main after the revert) failed on this — the old tree predates that convention. Fixed to the fluent `LOG.atError().setMessage("unmapped failure").addKeyValue("method", ...).addKeyValue("path", ...).setCause(t).log()` form every other call site uses. This is the only functional line changed inside a restored file.

### Dependencies

- Root `pom.xml`: added `vertx-web` to `dependencyManagement` (needed for `io.vertx.ext.web.Router`/`RoutingContext`); removed the `jetty.version` property and the `jetty-bom` import — nothing pins Jetty versions explicitly any more (see below). `javalin.version` and the `io.javalin:javalin` management entry stay (MCP).
- `server/pom.xml`: added `io.vertx:vertx-web`. Removed `org.eclipse.jetty.http2:jetty-http2-server`, `org.eclipse.jetty:jetty-alpn-server`, `org.eclipse.jetty:jetty-alpn-java-server`, and the test-scope `org.eclipse.jetty.http2:jetty-http2-client-transport` — these existed only to give the *Javalin* API listener h2c/h2 support (`Listeners.install(cfg.jetty, env)` + the old `Http2Test`'s Jetty HTTP/2 client probe); Vert.x does h2c/h2 natively (`HttpServerOptions.setHttp2ClearTextEnabled`/ALPN) and the restored `Http2Test` probes with Vert.x's own client instead.
- **Jetty artifacts that remain, and why**: only what `io.javalin:javalin` pulls in transitively for its own embedded server (`jetty-server`, `jetty-util`, etc. — whatever Javalin 7.2.3's POM declares; not pinned to a specific version any more since nothing else needs to agree with it). `McpServer.java` uses `io.javalin.Javalin` directly to embed the MCP SDK's servlet-based streamable-HTTP transport (`HttpServletStreamableServerTransportProvider`) on its own `FC_MCP_PORT` listener — independent of the API/metrics listener and never routed through the `io.flowcatalyst.http` seam. No `jetty-http2-*`/`jetty-alpn-*` add-on artifacts are needed for that: MCP serves plain HTTP/1.1 only.
- `fcdev/pom.xml`: one comment updated (native-image reachability directory comment now says "jOOQ, Jackson, the Postgres driver, Vert.x, and Jetty for MCP's own listener" instead of just "Jetty"). No dependency changes — fcdev shades `server`.

### Suites (worktree `flowcatalyst-javalin-vertx2`, branch `vertx-2`)

```
JAVA_HOME=$(mise where java) mvn -q -pl server -am test -Dsurefire.timeout=900
```
Run 1 found one real failure (`StructuredLoggingTest`, see conflict #7 above): **4010 tests, 1 failure, 0 errors, 1 skipped.**
After the fix, run 2: **4010 tests, 0 failures, 0 errors, 1 skipped (pre-existing, unrelated).**

```
JAVA_HOME=$(mise where java) mvn -q -pl fcdev -am test -Dtest='io.flowcatalyst.fcdev.*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.timeout=900
```
**105 tests, 0 failures, 0 errors, 0 skipped**, across 18 test classes. `DevDispatchRouterConfigIntegrationTest` (2/2) and `StartIntegrationTest` (4/4) — the two real-boot tests fcdev shades the Vert.x-backed server for — both green.

Spot-checked individually (all green): `LockfileCoverageTest` (1/1 — every route still enumerated through the Vert.x `RouteRegistry`), `NoFrameworkLeakTest` (2/2 — both the Javalin and the Vert.x scans), `VertxSeamContractTest` (18/18), `VertxListenerTest` (6/6), `Http2Test` (5/5), `HttpMediatorVersionTest` (6/6), `McpServerTest` (3/3), `RequestWorkersTest` (4/4), `BudgetsTest` (2/2), `ServerTest` (7/7).

Native build was not run (out of scope for this phase); native reachability metadata was diffed against the old tree and needed no changes (see above).

### Where the brief (`docs/vertx-migration-brief.md`) and the old tree were wrong about today's code

- The brief's P2 says MCP should be ported onto a Vert.x transport *in this same unit* ("P2, not Phase 4"). This phase's actual instructions override that explicitly: MCP stays on Jetty/Javalin for now, ported later. Noted here so the next phase doesn't assume it's already done.
- `docs/vertx-plan.md`'s "Kept from the Vert.x work despite the reversion" list already correctly predicted that `RequestWorkers` would need a real caller again; that held exactly as described.
- The old tree's `NoFrameworkLeakTest` assumed Javalin was gone entirely (single Vert.x-only scan). That's no longer true with MCP staying on Javalin, and main had *already* anticipated a two-framework world (it added the Vert.x scan for the mediation client) before this phase started — the merge in conflict #6 above was smaller than expected as a result.
- `OutboxAdminApi`'s and `Metrics`'s restored Vert.x versions needed zero logic changes beyond the framework swap — their route bodies (`ready()`, `scrape()`, the `/outbox/groups/*` handlers) were already 100% framework-neutral on both sides of the revert.

## Phase 2a — groups and pools

Branch `vertx-2`, worktree `flowcatalyst-javalin-vertx2`. Goal: `docs/spec/admission.md`
§11.7's build plan, first half only — endpoint groups and the four physical connection
pools. The second half (request workers, bounded queues, per-statement reads, `Budgets`
deletion, the new metrics) is a separate unit; `RequestWorkers` and `Budgets` are kept
compiling and wired exactly as Phase 1 left them.

### Groups

`io.flowcatalyst.http.Group` gained `BFF`, `API_WRITE`, `API_READ` and dropped `INGEST`
(merged into `DISPATCH`, per §11.7: "every route the message router calls: processing,
settled, ingest"). `LOGIN`/`OIDC`/`NO_DB` are unchanged and are not wired at any new
registration in this unit (that is the workers unit's job — `LOGIN`/`OIDC` sizing is
processor-count-based, not pool-based).

**Classification method.** `/bff/**` is flat `Group.BFF` regardless of read/write (the
spec's own rule — a BFF write, if any, would declare `API_WRITE`, but none currently do).
`DISPATCH` is the three `/api/dispatch/*` routes the router calls (`process`, `settled`,
`router-config`) plus the whole `IngestApi` surface (`/api/events*`, `/api/dispatch-jobs`
create, `/api/audit-logs/batch` — infrastructure batch inserts the SDK's own outbox
processor POSTs, spec `sdk-ingest.md`). Every other `/api/**` route defaults to
`API_READ` (an ungrouped `/api/` registration's group is `null` in the registry; the
default is asserted by the test below, not enforced at runtime — nothing currently
relies on the distinction at request time since both share the `API` physical pool).
`API_WRITE` is explicit: a route is marked with it iff its handler ultimately calls
`Operation#run`/`TxOperation#run` (a `UnitOfWork` transaction) — determined by scanning
each `*Api.java`'s `register()` body, resolving one level of local `Handler var = ...;`
indirection and following private-method calls transitively to find a
`.run(uow, …)`/`uow.inTransaction(…)`/`uow.commit*(…)`/`uow.emitEvent(…)` call. A route
whose handler writes to the database *outside* the `UnitOfWork` envelope (three found:
`PrincipalApi#sendPasswordReset`/`#resetTwoFactor`, `ScheduledJobApi#writeInstanceLog`/
`#completeInstance`) is correctly `API_READ` by this rule — no transaction is opened, so
there is nothing for the pinned-connection mode to protect, which is exactly the
admission-control property the split exists for.

**Landed:** 238 `/api/**` routes scanned across 28 `*Api.java`/bff files, 131 declared
`API_WRITE` (only the write ones needed an edit — `routes.post(...)` → `write.post(...)`
against a local `Routes write = routes.in(Group.API_WRITE);`; reads are untouched and
take the default), 107 left as the `API_READ` default. Six further write routes
(`DispatchJobApi#registerAt`'s `requeue`/`{id}/cancel`, `ProcessApi#registerAt`'s
`create`/`update`/`{id}/archive`/`{id}/delete`) are mounted through a shared
`registerAt(routes, prefix, state)` helper reused for both the `/api/` and `/bff/`
mounts with a *variable* prefix, so the literal-path scan can't see them; both gained a
private 4-arg overload (`registerAt(routes, prefix, state, Group writeGroup)`) that only
marks the writes when `writeGroup != null` — the `/api/` caller passes `Group.API_WRITE`,
the `/bff/` caller (a separately-wrapped `Routes` already carrying `Group.BFF`) keeps the
public 3-arg overload, so the two mounts never fight over one route's group. 13 files
needed no change at all (every route already the `API_READ` default): `AuditLogApi`,
`DocsApi`, `EventApi`, `LoginAttemptApi`, `MeApi`, `PublicApi`, plus the seven pure-BFF
classes wrapped at their `Platform.java` call site instead of internally.

**Test:** `server/src/test/java/io/flowcatalyst/server/RouteGroupTest.java` — two tests.
`everyWriteRouteFoundBySourceScanIsDeclaredApiWrite` re-derives the write-route set from
the *current* source tree at test time (not a hand-written list — a later write route
added without `Group.API_WRITE` fails the same day) and asserts it against the live
registry from a real booted `Server`, both directions (every scanned write route is
`API_WRITE`; every route declared `API_WRITE` was found by the scan or is one of the six
dynamic-prefix routes named above). `explicitlyPinnedDynamicPrefixWriteRoutesAreApiWrite`
covers those six by name. Mutant: reverted `write.put("/api/clients/{id}", …)` to
`routes.put(...)` in `ClientApi#register` — `everyWriteRouteFoundBySourceScanIs...` failed
with `PUT /api/clients/{id}: expected API_WRITE, registry has null`; reverting the mutant
restored green (confirmed with a clean, non-incremental build both times — see the build
hygiene note below).

### Pools

`io.flowcatalyst.platform.shared.database.Pools` — a new record, four `GatedDataSource`s
(`api`, `bff`, `dispatch`, `background`) opened by `Pools#open(url, EnvReader)` from one
budget `B` (`FC_DB_POOL_SIZE`, default 32 — [`Pools#DEFAULT_BUDGET`]): `api = B/2`,
`bff = B/4`, `dispatch = B/4`, `background = 4` fixed outside `B`. Each is independently
overridable (`FC_DB_POOL_SIZE_API` / `_BFF` / `_DISPATCH` / `_BACKGROUND` — the only
knobs) and every pool is floored at `Pools#MIN_POOL_SIZE` (2), whether derived or
overridden. `Pools#forGroup(Group)` maps `API_READ`/`API_WRITE`/`LOGIN`/`OIDC` → `api`,
`BFF` → `bff`, `DISPATCH` → `dispatch`; `NO_DB` throws (those routes never touch a pool).
`GatedDataSource#collector()` gained a `collector(String poolName)` overload adding a
`pool` label alongside the existing `lane` one, so `fc_db_gate_waiting`/`fc_db_gate_held`
are distinguishable per physical pool; `Pools#registerCollectors(PrometheusRegistry)`
registers all four.

**Wiring.** `Main`/`StartCommand` open one `Pools` (`Pools#open`/dev's own `EnvReader`)
in place of `Database.newPool`; `Server.Mode.Platform`/`Worker` now carry `Pools` instead
of one `DataSource` (`RouterOnly` is unchanged — still a nullable single pool, since it
never goes through this split). Every background subsystem in `Server#start`
(scheduler, outbox, stream, scheduled-job scheduler, purger, mail sender, and — since it
is not really request-path work either — the router's own `dataSource` parameter) now
derives from `pools.background()` via the existing `dbPool` local, not a request-path
pool; readiness probes take `pools.api().forProbes()` (§1's reserved lane, now on the
`api` pool specifically). AWS Secrets Manager credential rotation now starts one
`DbSecretRefresher` per physical pool (four independent `HikariDataSource`s each need
their own credential push) rather than one.

`Platform`'s constructor now takes `Pools` and keeps `pool = pools.api()` as its
existing field (used, unchanged, by the large majority of `/api/**` and `/auth/`/`/oauth/`
repositories — every one of those groups shares the `api` physical pool by design, so
"hand each API class the pool for its group" is a no-op for them). Three call sites got a
genuinely different physical pool: the dispatch-job reaper (background-listed explicitly
in §11.7) gets its own `DispatchJobRepository(pools.background())`; `SettledApi`/
`ProcessingApi`/`IngestApi`/`RouterConfigApi` (the `DISPATCH` group) get fresh repository
instances over `pools.dispatch()` (`DispatchJobRepository`, `EventRepository`,
`ClientRepository`, `ApplicationRepository`, `AuditLogRepository` — all stateless jOOQ
wrappers over their `DataSource`, so a second instance over the same tables is safe, not
a second view of the data); `DashboardBff` (the one BFF repository nothing on the `/api`
side shares) gets `DashboardRepository(pools.bff())`.

**What is NOT physically separated in this unit, and why.** Every other `/bff/**` mount
(`FilterOptionsBff`, `DeveloperBff`, `EventTypesBff`, `RolesBff`, `ScheduledJobsBff`,
`DebugBff`, and the three `registerAt`-shared classes) still executes against `pools.api()`
— their repository/`State` instances are the exact same objects their `/api` sibling
registers with, *by design* (`Platform.java`'s own comment: "the aggregate mounts reuse
the SAME handlers/state as their `/api` registrations … under a second base path"). Their
routes carry `Group.BFF` correctly (admission/metrics/future-worker-sizing all see them
as `BFF`), but un-sharing their repositories to point at `pools.bff()` would mean
building a second `UnitOfWork`/repository set for each, contradicting that explicit
sharing decision. Left for a follow-up if BFF's own connection-hold profile turns out to
need it — the metrics ruled in §11.6 (queue depth/utilisation per pool) are what would
tell you.

**Tests** (`server/src/test/java/io/flowcatalyst/platform/shared/database/PoolsTest.java`,
6 tests): sizes from the default budget (mutant: `budget/4` instead of `budget/2` for
`api` — two size assertions fail, `expected: 16 but was: 8`); `FC_DB_POOL_SIZE` rescaling
every share together; per-group override wins; every pool floored at 2 from both a tiny
budget and a tiny override; `forGroup` mapping (including `NO_DB` throwing); the collector
exposes all four `pool` labels (`fc_db_gate_waiting`/`_held`, scraped from a real
`PrometheusRegistry` — `MetricSnapshots` does not merge same-named snapshots from
different collectors into one, so the assertion `flatMap`s data points across all
matching snapshots rather than taking the first).
`server/src/test/java/io/flowcatalyst/server/BackgroundPoolWiringTest.java` pins pool
*identity*, not merely that a subsystem started: four `CountingDataSource` wrappers (one
per physical pool, all delegating to the same migrated `TestPg` fixture) feed a real
`Server` booted in `Mode.Worker` with only the outbox enabled; `Mode.Worker` never builds
`Platform` (`Server#buildApiAndReaper`'s `Mode.Worker _, Mode.RouterOnly _ -> {}` branch),
so with the router off every connection is attributable to a background subsystem —
`PostgresOutboxRepository#initSchema` runs synchronously inside `Server#start`, so the
assertion needs no wait/retry. Mutant: `case Mode.Worker(var pools) -> pools.background();`
→ `pools.api();` — `backgroundConnections` stayed 0 and the test failed
(`Expecting actual: 0 to be greater than: 0`); reverted, confirmed green again.

**A build-hygiene incident worth recording**: the `RouteGroupTest` mutant above initially
appeared to still fail *after* reverting the source — an incremental `mvn test` had not
recompiled `ClientApi.class` (`server/target/classes` predated the revert, confirmed by
comparing the `.class` mtime against the `.java` mtime — they matched, but the *content*
did not, i.e. Maven's compiler plugin skipped it). `rm -rf server/target/{classes,test-classes}`
and a clean run reproduced the correct (passing) result. `CLAUDE.md`'s "prefer `mvn clean
test` after any interface change" is this exact failure mode, not a hypothetical one.

### Docs

- `docs/spec/cutover.md` §4b: the Postgres sizing guidance is now
  `pods × (B + 4) ≤ max_connections − superuser_reserved_connections` (was
  `pods × 32 ≤ …` against the single old pool), naming all four env-var knobs.
- `server/src/main/java/io/flowcatalyst/server/Env.java`'s class doc: added `Pools`'
  four env vars to the "not here, on purpose" list (they are read directly by
  `Pools#open`, not through the `Env` record, matching every other per-subsystem
  `FromEnv` knob already documented there).

### Suites

```
JAVA_HOME=$(mise where java) mvn -q -pl server -am test -Dsurefire.timeout=900
```
**4019 tests, 0 failures, 0 errors, 1 skipped** (pre-existing, unrelated to this unit).

```
JAVA_HOME=$(mise where java) mvn -q -pl fcdev -am test -Dtest='io.flowcatalyst.fcdev.*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.timeout=900
```
**108 tests, 0 failures, 0 errors, 0 skipped.** `StartIntegrationTest` (4/4) and `DevDispatchRouterConfigIntegrationTest` (2/2) — the two real-boot tests that exercise `StartCommand`'s new `Pools.open(...)` wiring end to end — both green; `ServerTest` (7/7), `MainTest` (9/9) and `RouterStartupOrderTest` (1/1) in the server suite likewise.

## Phase 2b — workers, queues, routed pools

Branch `vertx-2`, worktree `flowcatalyst-javalin-vertx2`. Goal: `docs/spec/admission.md`
§11.7's build plan, the second half Phase 2a deferred — `RequestWorkers` actually wired,
bounded queues with `503`/`Retry-After`, a queued-request deadline, per-statement reads,
`Budgets`' deletion, the new metrics — plus the same day's "part B" ruling: **the pool is
chosen by the request, not by the handler class**.

### Part B — `Pools.routed()` and per-request modes

`io.flowcatalyst.platform.shared.database.Pools` gained `#routed()`: a `DataSource` whose
`getConnection()` reads `Admission.CURRENT`'s `Group` and delegates to `#forGroup`. No
scope bound throws `IllegalStateException` (background code's mistake, not a fallback);
`NO_DB` throws the same `IllegalArgumentException` `#forGroup` already did. `Admission`
(`io.flowcatalyst.http`) gained a `Group` field (now required — the two-arg constructor
`new Admission(path, group)` replaces the one-arg form everywhere, including every test)
and a `Mode` enum: `PINNED` (`API_WRITE`, `DISPATCH`, `LOGIN`, `OIDC`, and `NO_DB` as a
safe default it should never actually need — one connection per request, a nested
checkout joins it, exactly today's behaviour) or `PER_STATEMENT` (`API_READ`, `BFF` —
every checkout independent, nothing pinned). `Group#mode()` is the one place the mapping
lives, an exhaustive `switch` with no `default` arm (CLAUDE.md's "a defaulted member on a
sealed [construct] is untested by construction" — a new `Group` value fails to compile
here until it picks a mode). `GatedDataSource#checkout` now branches on
`admission.mode() == PINNED` (was: unconditionally re-entrant) before deciding whether a
checkout while already holding one joins the outer connection or is just another
independent permit.

**`Platform` undoes Part A's per-pool repository copies.** The `pool` field is now
`pools.routed()` (was `pools.api()`); every `pools.dispatch()`/`pools.bff()`-bound
repository Part A built specially (`RouterConfigDocumentBuilder`, the `SettledApi`/
`ProcessingApi` job repository, `IngestApi`'s four repositories, `DashboardBff`'s
`DashboardRepository`) is now the SAME instance its `/api` sibling already uses, built
over `pool` — one repository, correct on whichever physical pool the calling mount's
group resolves to. `dispatchPoolJobRepo` (a second `DispatchJobRepository` instance) is
gone entirely, folded into `dispatchJobRepo`. Migration, seeding and probes are unchanged
(`Main`, explicit `pools.api()`); the dispatch-job reaper is unchanged (background,
explicit `pools.background()`).

**Two boot-time eager reads needed an explicit pool instead of the routed one**, found by
running the suite, not by inspection: `CorsAllowlist`'s constructor and
`PasskeyService.Config.fromEnv(..., mfaBranding.platformName())` both read the database
synchronously at `Platform.register()` time, before any request — and therefore before any
`Admission` scope — exists. Both `corsOriginRepo` and `mfaBranding`'s
`PlatformConfigRepository` now take `pools.api()` explicitly (a comment at each site says
why); both are otherwise `/api/`-only, so this loses no pool-selection correctness for
their ordinary, request-time (lazy) uses. Every test that boots a real `Platform`
(`LockfileCoverageTest`, `RouteGroupTest`, `ServerTest`, `RouterStartupOrderTest`,
`RouterConfigEndpointTest`) caught this immediately as an `IllegalStateException` at boot
— the fix is these two lines, not a broader pattern (nothing else in `Platform.register`
reads the database outside a lambda/method reference).

### `RequestWorkers`, actually wired

No more `MAIN` fallback bucket — every group is real. `RequestWorkers.derived(int)` is
gone; `RequestWorkers.derived(Pools)` sizes six real groups off the four physical pools
(§11.3a): `API_WRITE` = `pools.api().ordinaryPermits()`; `API_READ` = 2×that (reads
release between statements, §11.4/§10, so two workers keep one connection busy);
`BFF` = 2× `pools.bff().ordinaryPermits()`; `DISPATCH` = `pools.dispatch().ordinaryPermits()`;
`LOGIN`/`OIDC` = `availableProcessors()`. `ordinaryPermits()`, not the raw pool size, so a
worker never waits at the gate (§9's original invariant, carried forward — the spec text
says "pool size" but the existing `Server` code this unit inherited already used
`ordinaryPermits()` for exactly this reason, and nothing here had cause to relitigate it).
`NO_DB` is deliberately absent — [`RequestWorkers#submit`] special-cases it: a fresh,
unbounded virtual thread, never queued, as before. `#of(Map<Group,Integer>)` (dropped the
`mainWorkers` int parameter) stays for tests; a group with no configured pool throws from
`#submit` rather than silently falling back to a shared bucket.

A default boot (budget `B` = 32) ends up with: `api` = 16 → 15 ordinary (`reservedFor(16)`
= 1) → `API_WRITE` = 15, `API_READ` = 30; `bff` = 8 → 7 ordinary → `BFF` = 14; `dispatch` =
8 → 7 ordinary → `DISPATCH` = 7; `LOGIN`/`OIDC` = the host's core count each.

**`VertxListener`** no longer carries `Budgets`; `Options` dropped the field entirely.
Every route's *effective* group is resolved once, in `dispatch()`: a declared group wins;
an ungrouped registration under `/api/`, `/auth/`, `/oauth/`, `/bff/`, `/portal/` or
`/.well-known/` (`Platform#isPlatformPath`'s prefixes, duplicated rather than depended on
— `http` must not depend on `server`) defaults to `API_READ`; everything else (health,
metrics, the SPA, OpenAPI documents, the router's own API, test fixtures) defaults to
`NO_DB`. This is what makes `pools.routed()` safe to call from every ungrouped `/api/**`
read route Part A left ungrouped by design.

### Bounded queues, `503 OVERLOADED`, the queued-request deadline

Each group's queue holds at most `8×` its worker count (`RequestWorkers#QUEUE_MULTIPLIER`);
`#submit` returns `false` — task never run — once full, and `VertxListener` answers `503`,
`Retry-After: 1`, `{"error":"OVERLOADED",...}` from the event loop without ever starting a
worker. Every queued request also carries its own deadline, armed in a new
`submitOrReject` method **at enqueue time**, on the loop — before any worker exists for it
— not only once `runChain` starts running it (the gap Phase 1 left: a request stuck behind
a full queue had no protection until a worker finally reached it). An `AtomicBoolean
claimed` decides the race between "a worker took it" and "the deadline fired first"
exactly once, either way; `runChain`'s own existing running-phase deadline (query-cancel +
interrupt) is untouched.

### Metrics

`fc_request_workers_busy{group}`, `fc_request_queue_depth{group}` (both relabelled from
`pool` to `group`), new `fc_request_queue_wait_seconds{group}` (classic histogram,
enqueue → a worker taking the request — recorded in `RequestWorkers#loop`, not by the
submitted task itself, so it measures time-in-queue, not queue-plus-run), new
`fc_request_rejected_total{group}` (counts both a queue-full refusal from `#submit` and a
queued-deadline refusal via the new `#markRejected`). `fc_db_gate_*{pool}` unchanged
(Part A).

### `Budgets` deleted

`http/Budgets.java`, `BudgetsTest.java`, `VertxListener.Options`' `budgets` parameter,
`Server`'s `Budgets.derived()` call, `Metrics`'/`OutboxAdminApi`'s `Budgets.none()` calls —
all gone. It was already dead code before this unit (nothing in `VertxListener` ever
called `Budgets#acquire`); `TestHttp.routes(Budgets, Consumer)` is gone too (only
`SeamContract` used it, and only to pass `Budgets.derived()` — a no-op). `ClusterBudget`
(tier 3) is untouched.

### Tests (each with a killed mutant)

- `PoolsTest#routedResolvesThePhysicalPoolFromTheCurrentRequestsGroupThroughOneSharedSource`:
  one shared `routed()` `DataSource`, bound under `Group.BFF` then `Group.API_READ`
  (counting-wrapper pools, `BackgroundPoolWiringTest`'s own pattern) — asserts the `bff`
  counter, then the `api` counter, each moves by exactly one connection and the other
  three stay at zero. Mutant: `resolve()` hard-coded to `return api;` — the BFF assertion
  fails (`expected: 1 but was: 0`), confirmed and reverted.
- `RequestWorkersTest#derivedSizesFollowTheSpecMultipliers`: `API_WRITE` = `pools.api()`'s
  ordinary permits, `API_READ` = 2×, `BFF` = 2× `pools.bff()`'s, `DISPATCH` = 1×
  `pools.dispatch()`'s, `LOGIN`/`OIDC` = core count. Mutant: swapped the `API_WRITE`/
  `API_READ` multipliers — `expected: 15 but was: 30`, confirmed and reverted.
- `GatedDataSourceTest#aPerStatementScopeHoldsNoConnectionBetweenStatementsAndEachCheckoutIsIndependent`:
  under a `Group.API_READ` (`PER_STATEMENT`) scope, `gate.held()` returns to zero between
  three sequential statements, and two concurrently open checkouts hold TWO permits, not
  one (no re-entrant join). Mutant: drop the `admission.mode() == PINNED` guard (branch on
  `held() > 0` alone, Part A's original rule) — `expected: 2 but was: 1`, confirmed and
  reverted. `aNestedCheckoutInsideARequestScopeJoinsTheOuterTransactionAndReleasesNothing`
  (the write path, now built with `Group.API_WRITE`) stays green throughout — the write
  path still pins, byte-for-byte.
- `VertxListenerTest#aFullDispatchQueueIsRefusedAtOnceWhileApiReadOnTheSameServerStillAnswers`:
  fills `DISPATCH`'s one worker + 8-deep queue with blocked requests; the 10th gets `503`
  `OVERLOADED` + `Retry-After: 1` immediately, while `API_READ` on the same server still
  answers `200`. Mutant: `poolOrThrow` ignores `group` and always returns the first pool
  (one shared queue) — the `API_READ` request got `503` too (`expected: 200 but was:
  503`), confirmed and reverted.
- `VertxListenerTest#aQueuedRequestWhoseDeadlineFiresIsAnswered503AndItsHandlerNeverRuns`:
  the `DISPATCH` pool's one worker is occupied directly through `RequestWorkers` (bypassing
  `runChain`'s own deadline entirely, so nothing else races the timer under test); an HTTP
  request to the same group gets `503 OVERLOADED` within its 400 ms deadline and
  `handlerRan` stays `false`. Mutant: the queued-phase timer armed 365 days out instead of
  at `deadline` — the client's own request timeout fired first (`HttpTimeoutException`),
  confirmed and reverted.
- `RequestWorkersTest#aFullQueueIsRefusedWithoutRunningTheTaskAndCountsAsRejected` and
  `RequestWorkersTest#theCollectorExposesAllFourSeriesLabelledByGroup` pin the queue bound
  and the four metric series (names, and the `group` label) directly, without a real
  listener. Mutant for the bound: `if (false && now > p.queueBound)` — the 9th queued task
  that should have been refused was accepted instead (`expected: false but was: true`),
  confirmed and reverted.

### `Group.LOGIN`/`Group.OIDC` declared on their real routes (2026-09-13 follow-up)

The "Left as written" note below this section originally flagged `LOGIN`/`OIDC` as sized
but unwired. This follow-up wires them: every route whose handler verifies a password, a
second factor, a WebAuthn assertion, or an OAuth grant/client credential now declares the
group, decided per handler by "does it call `PasswordHash.verify`/`.matches`/`.hash`, an
`Mfa` verify/confirm method (`verifyTotp`, `verifyLoginEmailPin`, `verifyRecoveryCode`,
`confirmTotpEnrollment`, `confirmEmailEnrollment` — the last two verify a code against the
pending secret/PIN the same as their non-enrolment counterparts), `PasskeyService#finishAssertion`,
or authenticate/verify an OAuth client or grant" — not by file or by "is this generally an
auth route" (most 2FA `begin`/status/list/remove routes verify nothing and stay `API_READ`).

**`Group.LOGIN`** (9 routes, all `PINNED`):

| Route | Class | What it verifies |
|---|---|---|
| `POST /auth/login` | `LoginApi` | the password (`PasswordHash.verify`/`equalizeTiming`) |
| `POST /auth/2fa/verify` | `TwoFactorApi` | TOTP / email PIN / recovery code |
| `POST /auth/2fa/enroll/totp/confirm` | `TwoFactorApi` | the TOTP code against the pending secret |
| `POST /auth/2fa/enroll/email/confirm` | `TwoFactorApi` | the email PIN |
| `POST /auth/2fa/methods/totp/confirm` | `TwoFactorApi` (self-service) | same as enrol/totp/confirm |
| `POST /auth/2fa/methods/email/confirm` | `TwoFactorApi` (self-service) | same as enrol/email/confirm |
| `POST /auth/change-password` | `ChangePasswordApi` | the current password, then any confirmed 2FA code |
| `POST /auth/password-reset/confirm` | `PasswordResetApi` | a TOTP factor (when the token requires one) before setting the new password |
| `POST /auth/webauthn/authenticate/complete` | `PasskeyApi` | the WebAuthn assertion (`PasskeyService#finishAssertion`) |

**`Group.OIDC`** (5 routes, all `PINNED`, exactly the coordinator's list — the OAuth
*provider* surface; `OidcBridgeApi`/`PortalAuthApi`'s employee-SSO/portal-SSO routes are a
different concept — the platform as an OIDC *client* — and stay out of scope here):
`POST /oauth/token` (`OAuthTokenApi`), `GET /oauth/authorize` (`OAuthAuthorizeApi`),
`POST /oauth/introspect` and `POST /oauth/revoke` (`OAuthIntrospectionApi`, one class),
`POST /auth/refresh` (`AuthRefreshApi`).

**Test:** `RouteGroupTest#loginAndOidcAreDeclaredOnTheirVerifyRoutes` — a real booted
`Server`'s registry, `declared.get("POST /auth/login") == Group.LOGIN` and
`declared.get("POST /oauth/token") == Group.OIDC`. A spot-check by name (unlike the
exhaustive source-scan `API_WRITE` test above), because "verifies a credential" is a
per-handler judgment call across several auth classes, not one grep-able call shape.
Mutant: reverted `routes.in(Group.LOGIN).post("/auth/login", …)` to plain
`routes.post(…)` — `declared.get("POST /auth/login")` read `null` (an ungrouped route's
default only applies at Vert.x dispatch time, never in the registry itself), test failed
with `expected: LOGIN but was: null`; same mutant/result for `/oauth/token` and `OIDC`;
both confirmed and reverted.

**Effect on worker pools**: `LOGIN`/`OIDC` traffic — previously silently unbounded
(`NO_DB` by the `defaultGroupFor` fallback, since none of these paths are under `/api/`)
— now actually queues through the `LOGIN`/`OIDC` worker pools sized in the first half of
this unit (`availableProcessors()` each), the isolation §2's derivation was written for.

### Suites

```
JAVA_HOME=$(mise where java) mvn -q -pl server -am test -Dsurefire.timeout=900
```
**4030 tests, 0 failures, 0 errors** (4029 before this follow-up + 1 new test).

```
JAVA_HOME=$(mise where java) mvn -q -pl fcdev -am test -Dtest='io.flowcatalyst.fcdev.*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.timeout=900
```
**108 tests, 0 failures, 0 errors.**

### Left as written, not done here

- `bench/real`'s two-CPU throughput / one-core switches-per-request round against the
  2026-09-08 baseline — explicitly the orchestrator's, not this unit's.
- `OidcBridgeApi` (employee OIDC SSO bridge) and `PortalAuthApi`'s portal-SSO/portal-login
  routes are NOT declared `Group.OIDC`/`Group.LOGIN` by this follow-up — the coordinator's
  list was the five OAuth-provider routes plus `/auth/refresh`, not the platform's own
  OIDC-client surface; admission.md §2's original text ("OIDC: `/auth/oidc/**`, portal SSO
  callback") is broader than what landed here. Left as a further judgment call if wanted.

## Phase 3 — MCP on Vert.x, Javalin/Jetty removed

Goal (`docs/vertx-migration-brief.md` §1/P2, the reason the 2026-09-08 cutover was
reverted, §0): move `io.flowcatalyst.mcp.McpServer`'s streamable-HTTP transport off the
MCP SDK's servlet-based `HttpServletStreamableServerTransportProvider` and its private
Javalin/Jetty listener onto Vert.x, then delete Javalin and Jetty from the tree entirely.

### What was built

- `server/src/main/java/io/flowcatalyst/mcp/VertxStreamableServerTransportProvider.java`
  (new): this repo's own `io.modelcontextprotocol.spec.McpStreamableServerTransportProvider`
  implementation over vertx-web, modelled line by line on the SDK's
  `HttpServletStreamableServerTransportProvider` (mcp-core 2.0.1 sources, read from
  `~/.m2/.../mcp-core-2.0.1-sources.jar` before writing a line of this). `mount(Router)`
  registers `POST`/`GET`/`DELETE` on `/mcp`. Every JSON-RPC dispatch (which may call the
  platform over HTTP through `PlatformClient`/`McpTools`) runs on a virtual thread from a
  dedicated `Executors.newVirtualThreadPerTaskExecutor()`, never the event loop; every
  response write hops back with `Context#runOnContext`, the same dispatch pattern
  `io.flowcatalyst.http.vertx.VertxListener` uses for the API listener. A session's SSE
  transport (`VertxStreamableMcpSessionTransport`) blocks its calling (always virtual)
  thread on a `CountDownLatch` until its write has been handed to the loop, so
  `sendMessage`'s `Mono` keeps the SDK's "completes when sent" contract even though the
  actual `HttpServerResponse` write must happen on the loop.
- `server/src/main/java/io/flowcatalyst/mcp/McpServer.java` (rewritten): builds its own,
  single-event-loop `Vertx` instance and `HttpServer` on `FC_MCP_BIND:FC_MCP_PORT` (own
  listener, per `docs/spec/mcp.md` §1 — unchanged), mounts the new provider plus
  `GET /health` on a `Router`, wires the platform's Jackson 3 mapper
  (`platform/shared/json/Json#MAPPER`) into the SDK via `mcp-json-jackson3`'s
  `JacksonMcpJsonMapper`. `start(...)`/`Running#port()`/`Running#stop()` keep the exact
  same signatures every caller (`Server.java`) already used, so no composition-root change
  was needed there. `Running#stop()` keeps the original shutdown order: close the listener
  (drain), then `mcpServer.closeGracefully()` (closes sessions and — via the SDK's own
  `McpAsyncServer#closeGracefully → transportProvider.closeGracefully()` chain — shuts down
  the provider's virtual-thread executor), then this listener's own `Vertx`.
- `server/src/test/java/io/flowcatalyst/mcp/McpServerTest.java`: same three tests, same
  assertions, now exercised over the Vert.x transport (see "Protocol behaviours" below for
  the one test whose *implementation* had to change).
- `server/src/test/java/io/flowcatalyst/mcp/VertxStreamableServerTransportProviderTest.java`
  (new): drives the wire protocol directly with a raw `java.net.http.HttpClient` — see
  "New tests" below.
- `server/src/test/java/io/flowcatalyst/http/NoFrameworkLeakTest.java`: the Javalin scan
  lost its `McpServer.java` allow-list entry and is now a plain "zero references anywhere"
  assertion; it also gained a Jetty scan (there was none before — Jetty was only ever
  reachable transitively through Javalin, so nothing scanned for it directly). The Vert.x
  scan's allow-list gained `io/flowcatalyst/mcp/` alongside the existing
  `io/flowcatalyst/http/vertx/` prefix.
- `docs/spec/mcp.md` §1: a new paragraph on the transport; §5: the new test's coverage.
- `server/native-config/reachability-metadata.json`,
  `fcdev/native-config/reachability-metadata.json`: every entry naming
  `org.eclipse.jetty.*`, `io.javalin`, or `jakarta.servlet.*` removed (types, resource
  globs, and resource bundles) — 24/25 total entries out of ~2,000 lines each; everything
  else untouched. Verified with `grep -ic "jetty\|javalin\|servlet\|websocket"` → `0` on
  both files after the edit, and `python3 -c "import json; json.load(...)"` to confirm both
  are still well-formed JSON.

### Protocol behaviours reproduced (and the two deliberate differences)

Reproduced 1:1 with the servlet transport: `POST` (JSON-RPC in; a single JSON response for
`initialize`, an SSE response stream — `text/event-stream`, one `message` event — for every
other request, matching `session.responseStream(...)`'s own `.then(transport.closeGracefully())`
which ends the stream once the response completes); `GET` (the standalone SSE listening
stream, `Mcp-Session-Id` required, `Last-Event-ID` replay); `DELETE` (session end, `405` when
`disallowDelete`); the `Mcp-Session-Id` response header on `initialize`; the SDK's own
`ServerTransportSecurityValidator`/`DefaultServerTransportSecurityValidator`/
`ServerTransportSecurityException` types **reused directly** (they are public, mcp-core
classes with no servlet dependency — no reimplementation needed) for Origin/Host validation
and its exact `403`/`421` status codes; the same `McpError` JSON envelope and status codes
for every bad-request/not-found/internal-error path; `notifyClients`/`notifyClient`;
`closeGracefully`.

Two deliberate differences from the servlet transport, both explained in the new class's
Javadoc:

1. **The `requestURI.endsWith(mcpEndpoint)` check has no equivalent.** The servlet maps one
   instance under a configurable path, so it re-checks the suffix on every request;
   vertx-web's `Router#route(HttpMethod, String)` already only invokes the handler for an
   exact match on `mcpEndpoint`, so the check would be dead code here.
2. **The default security validator is stricter, not equal.** `McpServer.java` built the
   servlet transport with `.builder().build()` — no security validator set, which defaults
   to `ServerTransportSecurityValidator.NOOP` (accepts everything, no Origin/Host check at
   all). This provider's builder defaults to
   `DefaultServerTransportSecurityValidator.builder().build()` — empty allow-lists, but
   **not** equivalent to NOOP: an *absent* `Origin` header (every non-browser MCP client,
   including the SDK's own `HttpClientStreamableHttpTransport`) still passes unaffected,
   but a *present* `Origin` header is rejected (`403`) unless allow-listed. Closes a
   DNS-rebinding gap the old transport left open, for free, with nothing to configure.
   Recorded here per CLAUDE.md "correctness over conformance": Go/the old Java transport is
   evidence of what was done, not of what is right.

One real bug this surfaced and fixed: `setSseHeaders` originally carried a
`Connection: keep-alive` header (copied from the servlet reference, where it is
meaningless-but-harmless). Vert.x's `HttpServerOptions` accepts h2c **by default**
(`DEFAULT_HTTP2_CLEAR_TEXT_ENABLED = true`) and HTTP/2 forbids hop-by-hop headers like
`Connection` outright (RFC 7540 §8.1.2.2); the JDK `HttpClient` (default version `HTTP_2`)
upgraded every test connection to h2c and then rejected the whole response as malformed
(`java.net.ProtocolException: malformed response: Prohibited header name 'connection'`).
Found by `VertxStreamableServerTransportProviderTest`'s `tools/list` and GET tests, both of
which failed with that exact exception on the first run. Fixed by dropping the header
(HTTP/1.1 keep-alive is already the default; nothing depended on it).

### Discrepancy against the brief: no shared `Vertx` instance to reuse

The brief (P1 item 2) says to "reuse the one `Vertx` instance the API listener shares with
`VertxMediationClient`". The code disagrees with that premise, and the code wins
(brief's own rule): `VertxListener.prepare` and `VertxMediationClient.start` each call
`Vertx.vertx(...)` and construct their **own**, separate instance — `Router.java`'s own
comment on `VertxMediationClient.start()` explains why: "the listener may not even be
Vert.x — so it has something of its own to close on shutdown." Neither class exposes a
`Vertx` accessor, so there is nothing to thread through even if the premise held. `McpServer`
follows the same established convention: its own, single-event-loop `Vertx` instance,
exactly as `VertxMediationClient` builds its own — never the API listener's.

### New tests

`VertxStreamableServerTransportProviderTest` drives the provider directly with a raw
`HttpClient` (no MCP SDK client — the SDK client is what `McpServerTest` already exercises):

- `initializeHandshakeReturnsASessionId` — `POST /mcp` with an `initialize` request asserts
  `200` and a present `Mcp-Session-Id` response header. **Mutant**: comment out
  `response.putHeader(HttpHeaders.MCP_SESSION_ID, sessionId)` in `handleInitialize` →
  `Expecting Optional to contain a value but it was empty` (this test), plus three more
  tests that build on `initializeSession()` failing the same way, plus
  `McpServerTest.aRealStreamableHttpSessionListsExactlyTheTwelveToolsAndNineResourcesByName`
  erroring out entirely (`Client failed to initialize by explicit API call` — the real SDK
  client can't proceed without the header either). Confirmed, reverted.
- `toolsListOverTheSessionAnswersJson` — a `tools/list` request over the session asserts the
  SSE stream's `data:` line parses as JSON with a non-empty `result.tools` array — the
  observable effect (a real tool list came back), not "a response was sent."
- `getOpensSseStreamAndANotifyClientsBroadcastArrivesAsEvent` — opens the `GET` listening
  stream, then calls `provider.notifyClients(...)` directly and asserts the broadcast
  arrives as an SSE `data:` event on that stream, retrying the broadcast (safe: a
  `notifyClients` call ahead of the listening-stream registration is a caught, logged
  `MissingMcpTransportSession` error, never a delivery) until it lands rather than
  sleeping a fixed duration — deterministic without depending on Vert.x's header-flush
  timing (SSE headers are not flushed until the first write, so there is no
  "connection established" signal to block on). This test is also why
  `handleGetOnVirtualThread` registers `session.listeningStream(transport)` **before**
  scheduling the headers `runOnContext` task, not after: registration is synchronous on the
  virtual thread and must complete before anything client-visible happens, or a broadcast
  that raced the client's own connect could be silently dropped by a real client with no
  retry of its own.
- `deleteEndsTheSessionAndASubsequentPostWithThatIdIsRefused` — `DELETE` asserts `200`, then
  a subsequent `POST` with that same `Mcp-Session-Id` asserts `404` — the session is
  actually gone from the map, not just "the delete method was invoked."
- `aRequestWithABadOriginIsRefusedExactlyAsTheDefaultValidatorRefusesIt` — an `initialize`
  `POST` carrying `Origin: http://evil.example.com` asserts `403`. **Mutant**: comment out
  the `securityValidator.validateHeaders(headers)` try/catch in `handlePost` → `expected:
  403 but was: 200` (exactly and only this test fails — the other four still pass).
  Confirmed, reverted.

`McpServerTest.theListenerIsBoundToTheConfiguredHostOnly` needed a different
*implementation* (Vert.x's `HttpServer` exposes no bound-address accessor the way Jetty's
`ServerConnector#getHost()` did), not a different behaviour: it now asserts that binding a
**second** `HttpServer` to the exact same `127.0.0.1:port` fails — proof the configured host
is actually occupied, arguably stronger than reading a field back. **Mutant**: drop
`.setHost(host)` from `McpServer#start`'s `HttpServerOptions` → `Expecting code to raise a
throwable` (the second bind succeeds because the real listener bound to `0.0.0.0` instead).
Confirmed, reverted.

### Dependency diff

Removed (actual `<dependency>` declarations, not just version pins):

| Artifact | From | Why |
|---|---|---|
| `io.javalin:javalin` | root `pom.xml` (dependencyManagement + `javalin.version` property), `server/pom.xml` | MCP's own listener was its last user |
| `com.github.ben-manes.caffeine:caffeine` | root `pom.xml` (dependencyManagement + `caffeine.version` property), `server/pom.xml` | brief P5 candidate; grep for `Caffeine`/`com.github.benmanes.caffeine` across every `.java` file: **zero matches** |

Jetty was never a direct dependency — it rode in transitively via `io.javalin:javalin`'s own
`jetty-server`; removing Javalin removes it automatically. `jakarta.servlet-api` (from
mcp-core, `provided` scope in mcp-core's own POM) was never on this module's compile or
runtime classpath even before this phase — nothing to remove there.

Root `pom.xml` also dropped the now-fully-unused `dependencyManagement` pins and version
properties for four of the brief's other five P5 candidates — **not because this phase
adds new callers to grep, but because they were already dead**: `org.mongodb:mongodb-driver-sync`,
`org.eclipse.angus:angus-mail`, `com.networknt:json-schema-validator`,
`org.hdrhistogram:HdrHistogram` were pinned in `dependencyManagement` but **never had an
actual `<dependency>` declaration in any module's POM** — grepping `pom.xml`/`server/pom.xml`/
`fcdev/pom.xml` for their `<artifactId>` outside `dependencyManagement` confirms zero. They
were not on any classpath already; this just deletes the leftover pins. Source grep for each
(`MongoClient`, `com.mongodb`; `jakarta.mail`, `javax.mail`; `JsonSchemaFactory`,
`com.networknt`; `org.HdrHistogram`, bare `Histogram` — the latter's hits are all
`io.prometheus.metrics.model.snapshots.Histogram*`, unrelated) also confirmed zero.

**Kept**: `com.mysql:mysql-connector-j` (`fcdev/pom.xml`) — the sixth P5 candidate, but a
grep for `com.mysql.cj`/`mysql` in `.java` sources shows it genuinely used:
`fcdev/src/main/java/io/flowcatalyst/fcdev/OutboxCommand.java`'s `createMysql` connects via
`DriverManager.getConnection(jdbcUrl)` against a `jdbc:mysql://` URL built by
`MysqlJdbcUrl.java` — no import needed (JDBC driver discovery is `META-INF/services`-based),
but a real, tested (`CreateTableCommandTest`) caller.

### Follow-up — the security validator's allow-list is derived, not empty

The provider's own bare default (`DefaultServerTransportSecurityValidator` with empty
allow-lists) refuses *any* request carrying an `Origin` header, including a legitimate
browser-based MCP client on the same machine sending `Origin: http://localhost:<port>` — and
there was no way to allow it. Per `CLAUDE.md` "no tuning; defaults are the product," the fix
is derived from what the listener already knows, not a new env var:

- `McpServer.deriveSecurityValidator(bindHost, boundPort)` (package-private, new) builds the
  allow-list from nothing but the listener's own bound address: `localhost`, `127.0.0.1`,
  `[::1]`, and whatever `host` (`FC_MCP_BIND`) resolved to, each with the listener's *actual*
  bound port, `http://` and `https://` origins both, plus the same host set as allowed `Host`
  values (`DefaultServerTransportSecurityValidator` enforces a `Host` check once any are
  configured). `boundPort` is an `IntSupplier` reading an `AtomicInteger` set only after
  `httpServer.listen()` succeeds — the actual port is unknown at provider-construction time
  (this method is also called with port `0`, an ephemeral port, by every test) and no request
  can arrive before `listen()` completes, so the lazy read is never stale for a real one.
  `McpServer#start` wires it in; the provider's own bare default is otherwise unchanged (still
  what `VertxStreamableServerTransportProviderTest`'s bad-origin mutant exercises).
- A real bug this surfaced: enabling the `Host` check exposed that Vert.x's `HttpServer`
  accepts h2c **by default**, and the JDK `HttpClient`'s default `HTTP_2` version upgrades every
  cleartext connection to it — but HTTP/2 has no literal `Host` header (only the `:authority`
  pseudo-header, which Vert.x does not synthesize a `Host` entry from), so every request looked
  like it was missing `Host` and got refused `421`. Every MCP test failed this way on the first
  run after wiring the derived validator in. Fixed by `.setHttp2ClearTextEnabled(false)` on the
  MCP `HttpServerOptions` (in both `McpServer#start` and the test's own listener setup) — MCP
  was always documented as HTTP/1.1-only (`docs/spec/mcp.md` §1); this just makes the listener
  enforce it, which also makes `Host` validation reliable.

New/changed tests:

- `McpServerTest.anOriginMatchingTheListenersOwnAddressIsAccepted` — through the real
  `McpServer.start()` wiring, `Origin: http://localhost:<port>` on the initialize `POST`
  answers `200`. **Mutant**: comment out `.securityValidator(deriveSecurityValidator(...))` in
  `McpServer#start` → `expected: 200 but was: 403` (this test only; the sibling refusal test
  stays green). Confirmed, reverted.
- `McpServerTest.aRequestWithAnUnrelatedOriginIsRefused` — the same real wiring, `Origin:
  http://evil.example.com` still answers `403`.
- `VertxStreamableServerTransportProviderTest`'s `@BeforeEach` now wires the provider with
  `McpServer.deriveSecurityValidator("127.0.0.1", boundPort::get)` (same `AtomicInteger`-after-
  `listen()` pattern) instead of the provider's bare default, so every test in the class
  exercises the real rule; `aRequestWithABadOriginIsRefusedExactlyAsTheDefaultValidatorRefusesIt`
  needed no change (`evil.example.com` still isn't allow-listed). New:
  `anOriginMatchingTheListenersOwnAddressIsAccepted` — `Origin: http://localhost:<port>`
  answers `200`. **Mutant**: revert `@BeforeEach` to the provider's bare default →
  `expected: 200 but was: 403` (this test only, confirmed, reverted).

```
JAVA_TOOL_OPTIONS="-Xmx2g" JAVA_HOME=$(mise where java) mvn -q -pl server -am test -Dtest='McpServerTest,VertxStreamableServerTransportProviderTest,NoFrameworkLeakTest,StructuredLoggingTest' -Dsurefire.failIfNoSpecifiedTests=false
```
**McpServerTest 5/5, VertxStreamableServerTransportProviderTest 6/6, NoFrameworkLeakTest 2/2,
StructuredLoggingTest 2/2 — all green.**

### Suites

```
JAVA_TOOL_OPTIONS="-Xmx2g" JAVA_HOME=$(mise where java) mvn -q -pl server -am test -Dtest='McpServerTest,VertxStreamableServerTransportProviderTest,NoFrameworkLeakTest' -Dsurefire.failIfNoSpecifiedTests=false
```
**10/10 new+touched tests green** (3 `McpServerTest` + 5 `VertxStreamableServerTransportProviderTest`
+ 2 `NoFrameworkLeakTest`). First run caught two real issues before this passed clean: the
h2c/`Connection`-header bug above, and `StructuredLoggingTest` flagging twelve
`LOG.error("… {}", e.getMessage())`-style calls in the new provider — converted to the
repo's fluent `LOG.atError().setMessage(...).addKeyValue(...).setCause(e).log()` form.

```
JAVA_TOOL_OPTIONS="-Xmx2g" JAVA_HOME=$(mise where java) mvn -q -pl server -am test -Dsurefire.timeout=900
```
**4036 tests, 0 failures, 0 errors, 1 skipped** (4033 before the Origin/Host follow-up + 3 new tests).

```
JAVA_TOOL_OPTIONS="-Xmx2g" JAVA_HOME=$(mise where java) mvn -q -pl fcdev -am test -Dtest='io.flowcatalyst.fcdev.*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.timeout=900
```
**108 tests, 0 failures, 0 errors** (the `DefaultPostgresBinaryResolver`/zonky
"No postgres binaries found" ERROR-level log lines in this run are pre-existing noise from
fcdev's Maven-Central binary resolution path, not test failures — every test passed).

```
JAVA_TOOL_OPTIONS="-Xmx2g" JAVA_HOME=$(mise where java) mvn -q -pl fcdev -am package -DskipTests
```
Shaded jar builds clean; `jar tf … | grep -icE "^io/javalin|^org/eclipse/jetty"` → `0` —
confirmed no Javalin/Jetty classes reach the fcdev fat jar.

### Not done in this unit

- Native-image build (`-Pnative`) for `server`/`fcdev` was explicitly out of scope
  ("Do not run the native build") — the reachability-metadata edits are believed correct
  (every removed entry named `org.eclipse.jetty`/`io.javalin`/`jakarta.servlet`, nothing
  else touched) but not verified by an actual native build in this unit.
- `parity/`, `conformance/`, `e2e/` were explicitly out of scope ("Do NOT commit, do NOT run
  parity or e2e").
- `fcdev`'s `--mcp` default / `StartIntegrationTest` / `DevDispatchRouterConfigIntegrationTest`
  were covered by the full `io.flowcatalyst.fcdev.*Test` run above rather than run
  individually by name; confirmed passing individually too: `McpCommandTest` 4/4,
  `StartIntegrationTest` 4/4, `DevDispatchRouterConfigIntegrationTest` 2/2. Checked
  `--mcp`'s default per the brief: `StartOptions.java` — `@Option(names = "--mcp", …)`
  reads `FC_MCP_ENABLED` with default `false` (`docs/spec/mcp.md` §1), unchanged by this
  phase; `McpServer.start(...)`'s signature is identical to before, so `StartCommand`'s
  wiring of it needed no edit at all. `McpCommand`/`McpCommandTest` (fcdev's stdio
  `fcdev mcp` subcommand) are a separate transport from this phase's streamable-HTTP one
  and were not touched.

## Phase 4 — verification and measurement (orchestrator, 2026-09-14)

Branch `vertx-2`: `d02475f` (phase 1), `12b01ce` and `3565581` (P2a parts A and B),
`289f480` (phase 3). Every phase was verified by the orchestrator in this worktree,
uncontended, after the agent's own run: the server suite from `clean` (4,010 → 4,019 →
4,028 → 4,036 as the phases added tests; one router timing test failed once during the
Mac's overnight maintenance sleep and passes alone in 13 s), fcdev 108, parity against a
clean export of Go `e87b88d` (its working tree does not compile mid-edit) — **1,311 steps,
0 DIFF, 0 ERROR after every phase, byte-identical under the new listener** — and the Java
e2e 51/51 (two e2e runs of 17 and 9.5 minutes were the machine suspending; the signature
is a multi-minute gap in fcdev's own log plus Hikari's "clock leap" on every pool, and
the same suite runs in 2.2 minutes under `caffeinate -dims`). The OpenAPI lock is
unchanged against `main`; `tools/jooq-verify.sh` is green. **Not run:** the native
image (`-Pnative`; GraalVM needs more memory than this machine had free during the
session) and `conformance/` (the Go-runner mediation check; nothing in these phases
touched mediation). Both are owed before a merge.

### `bench/real`, the brief's §7 (2026-09-14, same rig, same seed, back to back)

Rig as in `RESULTS.md`: one container each on Docker, Postgres pinned away, wrk on cpus
2–9, 60 s warm-up, 10 s measured, `GET /api/event-types` under a cookie session. The rig
gained a `CONNS` knob (default 1,000, the historical shape). The branch's exec jar in the
same `Dockerfile.java` (no explicit `-Xmx`, as every earlier Java row; the JVM takes a
quarter of the container). Go image: the same `bench-real-go` the 2026-09-08 rows used.

**At 1,000 connections the branch refuses the flood, as ruled.** 758,277 of 780,033
responses at two CPUs were `503 OVERLOADED` with `Retry-After: 1`: the API-read queue
holds 8 × 30 workers, and wrk never backs off, so the number wrk prints (77,844 req/s) is
rejection throughput. Admitted work: 2,176 successful requests/s at two CPUs and 953 on
one pinned core, with memory bounded (507 MB at 2 GB, 402 MB at 1 GB) — the "does not go
crazy" property, measured. Go at 1,000 connections: 2,481 req/s, p99 471 ms; 1,392 and
p99 753 ms on one core. The two shapes are not comparable as capacity because the branch
spends CPU on the refusals; they are comparable as behaviour under a flood.

**At 200 connections (under the bound, every response a 200) the branch is at or above
Go:**

| shape | branch req/s | Go req/s | share | branch p50 / p99 | Go p50 / p99 | memory after | switches/request |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2 CPUs, 2 GB | 2,573 | 2,196 | **117%** | 67 / 539 ms | 90 / 112 ms | 453 vs 69 MB | 9.9 vs 7.5 |
| 1 pinned core, 1 GB | 1,311 | 1,325 | **99%** | 136 / 210 ms | 149 / 172 ms | 403 vs 42 MB | 13.1 vs 7.2 |

Read against the 2026-09-08 final table (Java 93–96% of Go at two CPUs, 64–69% at one
core, both at 1,000 connections): throughput is no longer the gap. Two things are:

- **The two-CPU p99 (539 ms against Go's 112 ms)** while p50 is better than Go's. The
  median says the admitted path is faster; the tail says something periodic stalls it —
  the JVM's collector on a quarter-of-container heap is the first suspect (memory after
  the run is 453 MB with nothing bounding it), the JIT the second. The brief's P5 item
  (an explicit `-Xmx`) was not applied to the bench image so the row stays comparable
  with the earlier rows; it is the next measurement to take.
- **Context switches per request: 9.9 and 13.1 against Go's 7.5 and 7.2**, where the
  first attempt's pinned reads measured 0.82 on the `/hello` rig. This is §11.4's
  question answered with data: per-statement reads cross the untimed gate once per
  statement (about eight per request here) instead of once per request, and each
  crossing is a park and an unpark. It costs nothing visible in throughput at either
  shape, and it is what lets two read workers share one connection; but if the
  one-core switch count matters more than the worker ratio, reads should pin again
  (`Group#mode()` is the one line). That is a ruling to take with these numbers, not a
  defect.

Memory: the branch holds 400–450 MB where Go holds 40–70 MB. The brief's §1 says the
capped-heap rows were confirmation runs, not the target; the bench image runs with the
JVM default, and the P5 `-Xmx` decision is still open.
