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
