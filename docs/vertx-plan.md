# Vert.x listener — viability and migration plan (2026-09-06)

Status: **draft for owner review, nothing started.** Companion evidence in
`../test-size/RESULTS.md` (18 stacks, 1,000-connection DB endpoint, SSE fan-out at
10,000 subscribers, capped-heap and collector runs, Jetty-core-without-servlet).

## 1. Verdict

Viable, with one design decision that must be measured before any unit is ported
(§3, the dispatch model). Everything the platform takes from Javalin today has a
library-shaped equivalent in Vert.x 5 core + web; gRPC later lands on the **same
listener and router** through a library module with no second Netty; the SSE hub
gets h2 and the best per-subscriber and per-write memory of any Java stack
measured. The move is the same mechanical job sized for Helidon (~96 handler files,
25 context methods, 4 bootstrap sites), verified route by route by the lockfile gate
and the parity harness, and this time the other side is a library, not a framework.

## 2. Evidence

| Question | Finding | Source |
|---|---|---|
| Is Vert.x a library or a framework? | `vertx-core` + `vertx-web`: routing, handlers, no DI, no config model, no service registry. 30 runtime artifacts: Vert.x modules, Netty, `jackson-core`. Quarkus is the framework built on it (136 artifacts); we would use the library underneath. | Central poms; test-size dependency lists |
| gRPC on the same port? | `vertx-grpc-server` is a `Handler<HttpServerRequest>` mounted on the existing `HttpServer`/`Router`; `vertx-grpcio-server` hosts grpc-java `BindableService` implementations; `grpc-netty-shaded` is **test scope only** — compile deps are `grpc-stub` + Vert.x modules. gRPC-Web built in; `vertx-grpc-transcoding` for HTTP/JSON mapping. | vertx.io/docs/vertx-grpc; Central poms 5.1.7 |
| Memory and latency | Native under 1,000-connection load: **65 MB**, p99 26 ms, max 37 ms (Javalin native 485–906 MB / p99 75 ms / max 281 ms; Helidon native 182 MB / 93 ms). Jar: 683 MB after run (G1 growth, same as every JVM stack). | RESULTS.md consolidated table |
| Native image | Built with `native-maven-plugin` + reachability metadata, no extra config; startup 0.61 s (the 3.6 s seen once was a one-off, not reproduced). | test-size `vertx/` |
| Jackson | `vertx-core` hard-depends on `jackson-core` **2.x** (`com.fasterxml`); `jackson-databind` optional. Coexists with our Jackson 3 (`tools.jackson`) — different packages. We never touch `JsonObject`; our mapper writes `Buffer`s. | vertx-core 5.1.7 pom; `feedback_jackson` |
| SSE | Plain chunked response; hub not yet measured on Vert.x (Javalin and Helidon were) — Phase 0 item. | sse/ |
| Governance | Eclipse Foundation project, Red Hat-led (same team as Quarkus). Direction will bend toward Quarkus's needs; the core API has been stable across 4.x → 5.x with a documented migration. | — |
| **Blocking model** | A **virtual-thread verticle serialises its tasks**: one blocking call blocks every other request on that instance. Measured: 1 instance = 11.5k req/s at a flat 87 ms (pure queueing); 14 instances = 51.8k. Head-of-line blocking per instance. See §3. | test-size `vertx/` first attempt vs rerun |

## 3. The dispatch model (decide first, by measurement)

Our handlers block: jOOQ transactions, Argon2/bcrypt, outbound HTTP to dispatch
targets, the seam calls. Three ways to host them on Vert.x:

| Model | How | Cost | Risk |
|---|---|---|---|
| A. VT verticles × N instances | Handler runs on the verticle's virtual thread; N lanes. | None per request (the 26 ms p99 was this, N = cores). | A slow handler stalls its lane; a keep-alive connection is pinned to one instance, so a load balancer's few long-lived connections concentrate head-of-line blocking. Not acceptable for login, dispatch, seam calls. |
| **B. Per-request virtual thread + one hop back** | Verticle accepts; adapter starts a virtual thread per request for the handler; response writes are marshalled to the request's context (`runOnContext`). | One context hop per response. Quarkus does two (in and out) and measured p99 60 ms / max 274 ms vs Vert.x-direct 26 / 37 — but Quarkus also carries RESTEasy; the hop's own cost is unknown. | Must be measured (Phase 0). If the hop costs < 5 ms at p99 it is the model. |
| C. Event-loop verticle + `executeBlocking(ordered=false)` | Vert.x's own worker pool. | Platform-thread pool (default 20) or a VT-backed executor; same hop as B. | Same as B with less control. |

**Recommendation: B**, pending the Phase 0 measurement. It keeps ordering hazards
out of the platform entirely, gives one virtual thread per request like today, and
keeps Vert.x as the I/O layer only.

**Bulkheads come free with B** (owner, 2026-09-06): because the adapter owns the
per-request dispatch, a route (or a group of routes) can declare a concurrency
budget — a `Semaphore` acquired before the handler runs. Login and anything that
hashes a password: a handful in flight; outbound dispatch: a budget per target;
bulk/batch ingest: a hard cap; everything else: unlimited. A saturated budget
either waits with a timeout or answers `429`/`503` at once — that is a ruling (Q5),
not a default. This is the natural throttling the verticle-lane model gives you by
accident and this model gives you on purpose, per endpoint.

## 4. What the platform takes from Javalin (inventory, 2026-09-06)

| Surface | Count | Vert.x equivalent |
|---|---:|---|
| `routes.get/post/put/delete/patch` registrations | 423 in 72 files | `Router.get/post/…` |
| `Context` methods used | 25 (json, pathParam, status, bodyAsClass, queryParam, header, body, redirect, contentType, path, cookie, skipRemainingHandlers, attribute, method, result, contentLength, bodyAsBytes, statusCode, scheme, resultInputStream, res, ip, html, formParam, addHeader) | `RoutingContext` + `HttpServerRequest/Response` |
| `routes.before` / `after` | 10 / 2 | `Router.route().handler(...)` ordering; response-end hooks via `addHeadersEndHandler` |
| `routes.exception` mappers | 5 (`UseCaseException`, `LoginSurfaceException`, `CorruptRowException`, Javalin's `HttpResponseException` for 404/405, `Exception`) | `Router.errorHandler(status, …)` + `route().failureHandler(...)` |
| JSON mapper hook (`cfg.jsonMapper`) | 3 | our mapper → `Buffer` in the adapter |
| Cookies + `SameSite` | session cookie, trusted-device cookie | `io.vertx.core.http.Cookie` (SameSite supported) |
| Static SPA (`Frontend`) | `ctx.result(InputStream)` from classpath, cache headers | `StaticHandler` or the same stream-to-response in the adapter |
| Response defaults (no Content-Type on bodiless) | `after` filter | `addHeadersEndHandler` |
| `prefer405over404 = false` | 1 | our own 404 envelope as the router's last route |
| Jetty stop timeout (`SHUTDOWN_GRACE`) | 1 | `HttpServer.shutdown(timeout)` (Vert.x 5) |
| Metrics listener, OutboxAdmin listener | 2 more `Javalin.create` sites | second `HttpServer` on the same `Vertx` |
| **MCP** servlet transport (`modifyServletContextHandler`) | 1 | **Parked** — owner 2026-09-06: MCP unused, to be revisited. Needs a Vert.x `McpStreamableServerTransportProvider` (SDK interface is transport-agnostic; the servlet provider is ~40 KB of classes to mirror) when MCP returns. |
| Tests: `TestHttp` harness (60 test classes, `Consumer<JavalinConfig>`), `LockfileCoverageTest` (walks Javalin `HandlerType`), `SpecRoutesTest`, `FrontendTest` | 4 files import Javalin; 60 use the harness | harness takes `Consumer<Routes>`; coverage test walks our own registry |
| Parity harness | in-process `Server`, black-box | unchanged |

## 5. Plan

Standing process unchanged: orchestrator writes the spec and the seam, Sonnet
(medium, worktrees, ≤3 parallel, never `mvn install`) does the mechanical work,
orchestrator audits Java-vs-spec, one commit per landed unit, specs committed before
worktrees.

### Phase 0 — measure, then rule (orchestrator, one session)

1. **Dispatch-hop benchmark** in `../test-size/vertx-hop/`: the DB endpoint under
   model B (per-request VT + `runOnContext` write) vs model A, same 1,000-connection
   run and native GC log. Go/no-go line: model B p99 within 5 ms of model A.
2. **SSE hub on Vert.x** in `../test-size/sse/vertx/`: per-subscriber idle cost and
   the two fan-out rates, alongside the Javalin/Helidon rows.
3. **Owner rulings** (add to `docs/backlog.md`):
   - Q1 dispatch model (§3) given the numbers.
   - Q2 in-house HTTP seam (§5.1) vs. handlers written directly against
     `RoutingContext`. Recommendation: the seam — it is the difference between a
     one-module swap and a whole-tree swap next time, it lets Javalin and Vert.x
     coexist during migration, and its surface is the 25 methods already measured.
   - Q3 Netty transports: JDK NIO only (no epoll/kqueue/io_uring natives) unless
     measured otherwise; no TLS in-process (terminated upstream) so no tcnative.
   - Q4 MCP parked; the `/mcp` route is removed with the Javalin listener and
     returns with a Vert.x transport when MCP is revisited.
   - Q5 bulkhead semantics: which route groups carry a budget, the numbers, and
     whether saturation queues (with what timeout) or rejects (`429` vs `503`,
     `Retry-After`). The seam carries the mechanism regardless.

### Phase 1 — the seam (orchestrator spec; Sonnet mechanical rewrite; keeps Javalin live)

1. Spec `docs/spec/http-seam.md`: package `io.flowcatalyst.http` —
   `Routes` (get/post/put/patch/delete/before/after/exception, each registration
   optionally naming a `Budget` — a shared semaphore + saturation policy from Q5),
   `Exchange` (the 25 methods, named as today so the rewrite is `sed`-shaped),
   `HttpException(status)`, and the response-default + 404/405 semantics as rules.
   Two adapters: `javalin/` (wraps `Context`/`JavalinDefaultRoutingApi`) and, in
   Phase 2, `vertx/`.
2. Sonnet, one worktree per package group: replace `io.javalin.http.Context` →
   `Exchange`, `JavalinDefaultRoutingApi` → `Routes` across the 96 main files;
   `TestHttp(Consumer<Routes>)`; `LockfileCoverageTest` enumerates `Routes`.
   Javalin remains the only listener. Suite green, lockfile 245/245, parity
   corpus clean — **before any Vert.x code exists.** One commit.
3. Orchestrator audit: no `io.javalin` import outside `io.flowcatalyst.http.javalin`
   and the four bootstrap sites (enforce with an ArchUnit-style test).

### Phase 2 — the Vert.x listener (orchestrator spec + reference unit; Sonnet the rest)

1. Spec `docs/spec/vertx-listener.md`: `Vertx` instance, `HttpServer` options (h2c
   on, ALPN off unless TLS in-process, idle/keep-alive limits as today), `Router`,
   the dispatch model from Q1, graceful shutdown with `SHUTDOWN_GRACE`, error
   mapping order, bodiless-response rule, cookie/SameSite, static SPA serving,
   Metrics and OutboxAdmin as second servers on the same `Vertx`.
2. Reference unit (orchestrator): the `vertx/` adapter + `Server` bootstrap behind a
   flag (`FC_HTTP=vertx|javalin`), `eventtype` routes through it, `TestHttp` switchable
   by the same flag. Load-bearing tests with the break-it-on-purpose check: 404/405
   envelope, bodiless Content-Type, exception-mapper order, cookie attributes,
   SPA cache headers, shutdown drains in-flight requests.
3. Sonnet: run the full suite and the parity corpus under `FC_HTTP=vertx`; fix list
   handed back centrally, never looped.
4. Native profile: reflect config generator unchanged; Netty reachability from the
   metadata repository (verified on the hello app; the real image will surface more).
5. Exit: suite green both ways, lockfile 245/245, parity corpus identical under both
   listeners, native binary boots and serves.

### Phase 3 — cutover and removal

1. Default `FC_HTTP=vertx`; one release with Javalin reachable by flag.
2. Remove the Javalin adapter, Jetty, Kotlin stdlib, `jetty-ee10-*`, websocket and
   CDI-API jars; `/mcp` route goes with them (Q4). Expect fat jar ≈ −5 MB, native
   ≈ −8 MB, 42 → ~31 runtime artifacts.
3. Update `CONVENTIONS.md` (handlers are written against the seam, never against
   Vert.x types; blocking is allowed in handlers because of Q1), `docs/STATUS.md`,
   `helidon-direction` memory (Vert.x chosen: library, same listener for gRPC).

### Phase 4 — what it unlocks (separate specs, not part of the move)

- **SSE hub** (`docs/spec/hub.md`): own `HttpServer` on the same `Vertx`, h2c,
  subscriber registry, Last-Event-ID replay from the outbox stream, per-subscriber
  bounded queue with drop policy (the sse test's shape), auth via the existing
  session/JWT path.
- **gRPC**: `vertx-grpc-server` mounted on the platform router (or the hub's) when
  the first service exists; grpc-java stubs via `vertx-grpcio-server`; gRPC-Web for
  the SPA is free.
- **MCP**: Vert.x `McpStreamableServerTransportProvider` when MCP is revisited.

## 6. Risks and how each is closed

| Risk | Closed by |
|---|---|
| The response hop (model B) costs tail latency | Phase 0 item 1; go/no-go line stated |
| Netty reachability gaps in the real native image | Phase 2 item 4 on the full server; the tracing agent workflow already exists (`native-config/`) |
| Two Jacksons on the classpath | Package-distinct; a test asserts no `com.fasterxml` type crosses the seam |
| Vert.x's per-connection pinning to an instance | Model B: connections are pinned, handlers are not |
| Framework gravity (Vert.x bending toward Quarkus) | The seam: nothing outside `io.flowcatalyst.http.vertx` imports Vert.x |
| Team unfamiliarity (Sonnet writes correct Javalin from memory; Vert.x 5 less so) | Reference unit first; the adapter is the only Vert.x code Sonnet touches |
| SSE hub memory assumptions | Phase 0 item 2 before the hub spec |

## 7. Sizing

| Phase | Owner | Effort |
|---|---|---|
| 0 | orchestrator | 1 session (two benchmarks + rulings) |
| 1 | orchestrator spec ½ day; Sonnet 96-file rewrite ≈ 1 day across 3 worktrees; audit ½ day | ~2 days |
| 2 | orchestrator reference unit + spec 1 day; Sonnet suite/parity fix cycle 1–2 days; native ½ day | ~3 days |
| 3 | orchestrator | ½ day |

Everything lands before cutover (`docs/spec/cutover.md`), while the parity harness
is the safety net and every route is still being exercised.
