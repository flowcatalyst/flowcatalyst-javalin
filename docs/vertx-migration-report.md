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
