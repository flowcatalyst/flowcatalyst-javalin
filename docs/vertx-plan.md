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

**Measured 2026-09-06 (`../test-size/vertx/`, HikariCP 32, native, 1,000 connections):**

| model | req/s | p99 | max | RSS after run |
|---|---:|---:|---:|---:|
| A × 14 verticles (lanes < pool connections: no contention) | 49,888 | 30 ms | 44 ms | 71 MB |
| A × 64 | 59,475 | 157 ms | 245 ms | 96 MB |
| A × 256 | 59,027 | 100 ms | 323 ms | 101 MB |
| **B** (virtual thread per request + hop) | 58,286 | **44 ms** | 266 ms | 113 MB |
| C (= B, no hop) | 57,367 | 50 ms | 254 ms | 116 MB |

The hop is free (C ≈ B). A's tight tail exists only while lanes < pool connections;
with realistic lane counts A is worse than B. The variable at this concurrency is
pool contention, which B handles best on the JVM. The original go/no-go line ("within
5 ms of A×14") compared against an uncontended baseline and is withdrawn.

**Decision: B, with exactly ONE event loop** (owner ruling 2026-09-06). In B the loop
does two things — parse requests, write responses — and nothing of the platform runs
on it. A loop per core is Vert.x's reactive default, where the loop is where the work
happens; that is the wrong shape for a blocking application on virtual threads.
Measured: B with 1 loop = 55.7k req/s, p99 41.6 ms, **66 MB** native after the run —
equal or better than 14 loops (58.3k / 43.9 ms / 113 MB), and the tail did not move
between 1 and 14, so one loop had headroom at a rate an order of magnitude above any
platform instance. The per-loop buffers and allocators were the extra 47 MB. (The
benchmark host is asymmetric — 10 performance + 4 efficiency cores — one more reason
never to derive the count from a core count.)

The listener deploys **one** event-loop verticle. This is a design fact of the spec,
not a setting. The only two things that could ever load the loop are TLS terminated
in-process (the loop does the crypto) — the platform terminates upstream — and the
hub's fan-out writes, which run on the hub's own `HttpServer` and are measured there
(Phase 0 item 2). Neither changes the number without a measurement that says so.

The third way the loop could come under load is the one the plan forbids outright
(owner, 2026-09-06): adopting other Vert.x assets. The Vert.x SQL client, HTTP client,
event bus, Kafka/Redis/mail modules and the rest all do their work *on the event loop*
and hand results back through futures — that is the reactive model, and using any of
them would put application load on the loop and reopen the count. Our model is
virtual threads: jOOQ on HikariCP, the JDK `HttpClient`, our own outbox, all blocking
on the request's virtual thread. **Vert.x is the listener and nothing else.** Nothing
outside `io.flowcatalyst.http.vertx` imports `io.vertx.*` (Phase 1 item 3 enforces it),
and no `io.vertx` artifact other than `vertx-core`, `vertx-web`, and later the gRPC
server modules, enters the pom.

B keeps ordering hazards out of the platform, gives one virtual thread per request
like today, and keeps Vert.x as the I/O layer only.

**Helidon, re-measured on a real pool (owner asked, 2026-09-06):** conceptually the
better fit — virtual threads everywhere, no event loop, no hop, the JDK's own sockets —
and the smallest live heap of any JVM stack (~25–33 MB). But its tail is its own:
p99 88.8 ms on HikariCP vs 41.6 for Vert.x B, so the pool was not the cause. And on one
core in a container (the density case) it did 21.5k req/s to Vert.x B's 32.3k. Its
kernel context switches (4.94 vs 3.97 per request) were first blamed on socket parks
through the JDK poller; **that explanation was wrong** (per-thread counters, later the
same day, RESULTS.md §"Where the switches come from"): socket parks are free on Linux
JDK 25 (`VTHREAD_POLLERS`), and ~3.4 of the switches in *both* stacks were HikariCP's
contended `getConnection` — a timed park through the ForkJoinPool's delay-scheduler
thread plus a wake of Hikari's connection-adder thread. Helidon's deficit is CPU per
request in user space, not switches. The framework coupling (`helidon-direction`) is
unchanged. The seam
(Phase 1) is listener-agnostic; a Helidon adapter is a one-day experiment against the
real platform if that ever changes.

(Earlier runs in RESULTS.md used a hand-rolled, unfair connection queue — owner
correction 2026-09-06: a pool is a normal pool, a bulkhead is only a semaphore — which
inflated every thread-per-request JVM tail by ~10 ms. Relative order unchanged.)

**Bulkheads come free with B** (owner, 2026-09-06): because the adapter owns the
per-request dispatch, a route (or a group of routes) can declare a concurrency
budget — a `Semaphore` acquired before the handler runs. Login and anything that
hashes a password: a handful in flight; outbound dispatch: a budget per target;
bulk/batch ingest: a hard cap; everything else: unlimited. A saturated budget
**queues untimed**; the request's loop-owned deadline is the only bound (ruling, §3b).
A timed semaphore wait is exactly the cost §3b removes, so it never appears. This is
the natural throttling the verticle-lane model gives you by accident and this model
gives you on purpose, per endpoint.

## 3b. Admission control and time (owner rulings, 2026-09-06)

Measured the same day on one core (`../test-size/RESULTS.md` §"Where the switches
come from", §"The fix, measured", §"Sizing"): on a virtual thread an **untimed park**
(semaphore, lock, socket) is free, and a **timed park** (`parkNanos`, `tryAcquire(t)`,
`poll(t)`, `Thread.sleep`, a socket with `soTimeout`) is a kernel round trip through
the ForkJoinPool's delay-scheduler thread that, on a one-core pod, also preempts the
carrier. HikariCP's contended borrow is a timed park; a pool-sized `Semaphore` in
front of it took Vert.x B from 3.93 to 0.82 switches per request and to Go's
throughput at every size (1–4 CPUs, pinned and CFS quota). Rules that follow:

1. **No timed park on a request path.** Deadlines belong to the event loop's timer
   wheel (Netty's, on the loop thread), never to the waiting thread.
2. **Tier 1 — the pool gate.** One semaphore sized *exactly* to the DB pool, taken at
   every connection checkout inside the pool wrapper. Not a bulkhead and not a knob:
   the number is the pool's by identity ("nothing else can use the connection").
   Nothing bypasses it; routes that never check out a connection never see it.
   `/health` and `/ready` hold a small **reserved** slice (derived from pool size) so
   readiness stays truthful when the gate is saturated — a busy pod must look busy,
   not dead, or the scaler cannot do its job. A **nested-acquire guard** (a
   `ScopedValue` flag: acquiring while holding throws) turns held-while-acquiring into
   a test failure instead of a deadlock under load. Hikari's own `connectionTimeout`
   then never fires and stays at its default.
3. **Tier 2 — local group bulkheads**, a semaphore per group of endpoints that go
   together, acquired on the request's virtual thread before the handler. Four
   groups, budgets **derived** from the resource each protects, never configured:
   login + MFA + password reset (= password4j's hash-pool size), OIDC bridge (= the
   discovery/JWKS client's connection limit), dispatch `POST /api/dispatch/process`
   (= the per-target concurrency the router already tracks), bulk/batch ingest
   (= pool size). Everything else unlimited. Saturation queues untimed (rule 1).
4. **Tier 3 — cluster-wide group bulkhead**, the same group budget across nodes.
   **Opt-in, not built now.** An interface with three implementations: `noop`
   (always grants — the default), `postgres` (row leases, `SKIP LOCKED`, lease
   expiry for crashed nodes), `redis`. A node never holds a pool permit while waiting
   for a cluster permit, and the cluster tier answers `503` at once rather than
   waiting across the network.
5. **The request deadline.** One product default for every request, armed on the
   loop's timer when the request is dispatched. When it fires the loop **interrupts
   the request's virtual thread**: a park in a gate, a bulkhead or a socket wakes and
   unwinds, the transaction rolls back, the permits are released, the client gets
   `503`. "Let the handler finish" was rejected: a pgjdbc read has no socket timeout,
   so a database host that drops packets would hold a pool permit for the TCP
   keepalive interval (~2 h). **Measured 2026-09-06** (Phase 0 item 4, pgjdbc 42.7.13,
   HikariCP 7.1.0, JDK 25, `select pg_sleep(10)` on a virtual thread): `Thread.interrupt()`
   wakes the read in ~10 ms but closes the socket (pgjdbc "I/O error", Hikari evicts
   the connection); `Connection.abort` likewise. `PgConnection.cancelQuery()` via
   `connection.unwrap(PgConnection.class)` — no statement handle needed — wakes it in
   8–12 ms with SQLSTATE `57014`, the connection stays usable, and a following
   `rollback()` succeeds. **So the deadline does both:** the loop timer first calls
   `cancelQuery()` on every connection the request holds (the pool wrapper knows
   them), then interrupts the virtual thread so parks in gates, bulkheads and
   non-JDBC sockets unwind too. An untimed `Semaphore.acquire` wakes on interrupt in
   ~6 ms.
6. **Pool size: 32 per pod, fixed**, not derived from cores (owner: "definitely not 4
   — a couple of long-running queries would grind everything to a halt"). The
   existing env var overrides it. This is the design's **only number**, and it is
   Postgres's budget, not the app's, so it belongs to every implementation. Deployment
   rule: pods × 32 ≤ `max_connections` − reserved; past ~3 pods raise it or front
   Postgres with PgBouncer in transaction mode (mind pgjdbc server-side prepares).
7. **Mail leaves the request path.** Login MFA, notifications and password reset send
   SMTP inline today (30 s connect + `soTimeout` on a plain socket = timed parks per
   read, and the login request held by the mail server). They go through the
   transactional outbox with a background sender; login answers at once. Behaviour
   change vs Go — recorded in `docs/go-mirror/` as a Go defect.
8. **HTTP/2 for the message router's outbound webhook mediation** when deployed
   (without h2 the HTTP/1.1 connection pools would be massive): prefer h2, fall back
   to 1.1, record the negotiated version on the attempt. fcdev local uses 1.1. The
   `SubscriberDelivery` client also gains the connect timeout it is missing.
9. **Sizing.** 1 CPU for small, low-throughput deployments; 2 CPUs for serious work,
   then horizontal. With the gate the stack is at Go's throughput at every size; 2
   CPUs buys the tail (p99 57 → 37 ms under quota), 4 buys nothing more.
10. **No dials.** Everything above is derived or fixed. The one number is the pool
    size. (Go's inherited rate-limit env vars are pre-existing dials — separate ruling.)

Order of waiting inside a request is fixed — deadline armed, group permit, pool
permit per checkout — and released in reverse. Guardrail: the one-core context-switch
bench (`../test-size/bench/ctx-switches.sh`) run against the real server, ceiling
1.0 switches per request, in the CI matrix; a transitive dependency that reintroduces
a timed park fails it.

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

1. ~~Dispatch-hop benchmark~~ **Done 2026-09-06** (§3): model B adopted.
2. **SSE hub on Vert.x** in `../test-size/sse/vertx/`: per-subscriber idle cost and
   the two fan-out rates, alongside the Javalin/Helidon rows.
3. **Owner rulings** (add to `docs/backlog.md`):
   - ~~Q1 dispatch model~~ settled by measurement: B (§3).
   - Q2 in-house HTTP seam (§5.1) vs. handlers written directly against
     `RoutingContext`. Recommendation: the seam — it is the difference between a
     one-module swap and a whole-tree swap next time, it lets Javalin and Vert.x
     coexist during migration, and its surface is the 25 methods already measured.
   - Q3 Netty transports: JDK NIO only (no epoll/kqueue/io_uring natives) unless
     measured otherwise; no TLS in-process (terminated upstream) so no tcnative.
   - Q4 MCP parked; the `/mcp` route is removed with the Javalin listener and
     returns with a Vert.x transport when MCP is revisited.
   - ~~Q5 bulkhead semantics~~ settled 2026-09-06: §3b (four derived groups, queue
     untimed, loop-owned deadline, cluster tier opt-in/noop).
4. ~~Deadline experiment~~ **Done 2026-09-06** (§3b rule 5): `cancelQuery()` then
   interrupt.

### Phase 1 — the seam (orchestrator spec; Sonnet mechanical rewrite; keeps Javalin live)

1. Spec `docs/spec/http-seam.md`: package `io.flowcatalyst.http` —
   `Routes` (get/post/put/patch/delete/before/after/exception, each registration
   optionally naming a `Group` — one of the four §3b tier-2 bulkheads),
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

1. Spec `docs/spec/vertx-listener.md`: `Vertx` instance, **one event-loop verticle**
   (§3), `HttpServer` options (h2c on, ALPN off unless TLS in-process, idle/keep-alive
   limits as today), `Router`, dispatch model B with the virtual-thread-per-request
   executor, the §3b admission order (deadline armed on the loop timer → group permit
   → pool gate per checkout), after-filters on the request thread before the
   `runOnContext` write hop, graceful shutdown with `SHUTDOWN_GRACE`, error
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
   Vert.x types; blocking is allowed in handlers because of Q1; **no Vert.x client or
   event-bus module, ever** — Vert.x is the listener only, §3), `docs/STATUS.md`,
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
| A timed park creeps back in through a dependency | the one-core switch bench in CI, ceiling 1.0 per request (§3b) |
| Deadline interrupt does not wake a pgjdbc read | Phase 0 item 4; fallback is `Statement.cancel` from the loop timer, never a socket timeout |
| Two checkouts held on one virtual thread deadlock under a full gate | the nested-acquire guard (§3b rule 2) makes it throw in tests |

## 7. Sizing

| Phase | Owner | Effort |
|---|---|---|
| 0 | orchestrator | 1 session (two benchmarks + rulings) |
| 1 | orchestrator spec ½ day; Sonnet 96-file rewrite ≈ 1 day across 3 worktrees; audit ½ day | ~2 days |
| 2 | orchestrator reference unit + spec 1 day; Sonnet suite/parity fix cycle 1–2 days; native ½ day | ~3 days |
| 3 | orchestrator | ½ day |

Everything lands before cutover (`docs/spec/cutover.md`), while the parity harness
is the safety net and every route is still being exercised.

## 8. Branch, acceptance, approval (owner, 2026-09-06)

The work happens on branch `vertx-listener`, scoped as **listener + tiers together**:
seam, Vert.x listener, pool gate with reserved probe permits, four derived group
bulkheads, the noop cluster interface, the loop-owned deadline, pool default 32, h2
for router mediation, mail via the outbox. Orchestration as always: the orchestrator
writes specs, the reference unit, the gate and the deadline (the tricky parts) and
reviews Sonnet's work by reading it whenever the tests alone would not catch a wrong
implementation; Sonnet (medium effort, worktrees) does the mechanical rewrite.

**Acceptance line** (all must hold before the merge is *requested*): parity corpus and
e2e flows identical to today's Java and to Go; runtime on the same rig and day against
the real server at 1- and 2-CPU quotas — throughput within 10% of Go, p99 within
15 ms, under 1.0 kernel switches per request, memory recorded.

**Nothing merges without the owner's explicit approval.** Meeting the line earns the
request, not the merge.
