# Spec — the Vert.x listener (`io.flowcatalyst.http.vertx`)

Status: Phase 2 of `docs/vertx-plan.md` (§5), owner-ruled 2026-09-06. The second
implementation of the seam (`docs/spec/http-seam.md`); Javalin stays reachable by flag
until Phase 3. Admission and time are `docs/spec/admission.md`.

## 1. Shape

- **Dependencies:** `io.vertx:vertx-core` and `io.vertx:vertx-web` 5.1.7 only (measured
  in `../test-size`). No Vert.x client, event bus, SQL, or other module — ever (plan §3).
  Nothing outside `io.flowcatalyst.http.vertx` imports `io.vertx.*`
  (`NoFrameworkLeakTest` gains the same rule for `io.vertx`).
- **One event loop.** `Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1))`; the API
  `HttpServer` and the `Router` live on it. The loop parses requests and writes
  responses; nothing of the platform runs on it. A design fact, not a setting.
- **Dispatch model B.** For every request the loop hands the whole seam chain to a fresh
  virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`): tier-2 group permit
  (untimed) → `Admission` scope → deadline armed → `before` filters in registration
  order (path-scoped ones only when the path matches) → route handler → `after` filters
  → deadline cancelled → permits released. The `Exchange` **buffers** status, headers,
  cookies and body during the chain; the write is marshalled back to the request's
  context with `runOnContext` and is the only thing the loop does per response.
- **Matching is the adapter's.** One Vert.x route per distinct path pattern with
  no method (`router.route(pattern)`), dispatching by method to the seam registrations;
  a matched path with no handler for the method runs the chain with a handler that
  throws `HttpException(404)`, and so does the catch-all last route — the platform's
  `HttpException` mapper renders the `NOT_FOUND` envelope both times. That is how
  "404, never 405" (seam §2 rule 4) holds without Javalin's `prefer405over404`.
  Pattern translation: Javalin `{name}` → Vert.x `:name`; Javalin `<name>` or a
  trailing `*` → Vert.x `*` (`Frontend`'s `/<path>` becomes `/*`; `pathParam("path")` is
  not used anywhere). Vert.x routes are ordered by registration; the catch-all is
  `.last()`.
- **Body.** `BodyHandler` with uploads off and a 1,000,000-byte limit — Javalin's
  default `maxRequestSize`, which is what the platform runs under today; over the limit
  is 413 through the same envelope. `formParam` reads the parsed form attributes.
- **JSON.** `Json.MAPPER` (tools.jackson), the same mapper `JavalinJsonMapper` wraps:
  `json()` writes bytes + `application/json`; `bodyAsClass` maps a parse failure to
  `UseCaseException.validation("INVALID_JSON", message)` exactly as the Javalin mapper
  does, so the 400 envelope is unchanged.
- **Cookies.** `HttpCookie` → `io.vertx.core.http.Cookie` with path, max-age (−1 →
  session, 0 → delete), HttpOnly, Secure, SameSite; request cookies from the request.
- **Streams.** `result(InputStream)` is read fully on the request's virtual thread into
  a `Buffer` and closed; the response is written in one `end(buffer)`. The only callers
  are the SPA assets and index (`Frontend`), small and classpath-resident.
- **Bodiless rule** (seam §2 rule 5): no `Content-Type` when status is 204 or nothing was
  written. **Exception mappers**: every throwable from the chain goes through the seam's
  `ExceptionMappers`; if nothing resolves, `500` with the platform's `INTERNAL` envelope
  shape is impossible because the platform always registers `Exception.class` — the
  adapter logs and answers a bare 500 only in that never case. `after` filters still run
  after a mapped exception. `skipRemainingHandlers()` sets a flag the chain reads: no
  further `before`, no handler; `after` runs.
- **The deadline** (admission.md §4): 30 s by default, `DISPATCH` group 130 s. Armed with
  `vertx.setTimer` on the loop when the request is dispatched, cancelled with
  `vertx.cancelTimer` when the chain ends. On fire: `cancelQuery()` on every connection in
  the request's `Admission`, then `interrupt()` on its virtual thread; the chain's
  exception path answers `503` `{"error":"DEADLINE"}` if nothing was written yet, else
  the connection is closed. The `Admission` scope is bound by this adapter around the
  whole chain (so the nested-checkout guard sees the authenticator's checkout too,
  which is correct: two checkouts *held at once* is the hazard, sequential ones are not).
- **Listeners** (`docs/spec/http-transport.md` §1): the plain API listener with
  `setHttp2ClearTextEnabled(true)` (h2c by prior knowledge and upgrade); the TLS
  listener when `TlsMaterial` resolves, `setSsl(true).setUseAlpn(true)` with the key
  store handed to Vert.x as PKCS12 bytes (JDK TLS, no native) — **landed 2026-09-08**,
  a second `HttpServer` on the same `Vertx`/`Router` (`VertxListener.Options.Tls`).
  HTTP/3 is dropped for good (Q6, §3, closed 2026-09-08) — Vert.x 5.1 does HTTP/3 only
  through Netty's QUIC native codec, which plan ruling Q3 excludes unless measured, and
  nothing in this platform needs it now that Jetty (which used to carry it behind
  `FC_HTTP=javalin`) is gone. `Alt-Svc` is therefore never emitted.
- **Metrics and OutboxAdmin listeners** — **landed 2026-09-08**: both are their own
  `VertxListener` (their own `Vertx`, event loop and `RequestWorkers`, not a second
  `HttpServer` sharing the API's — simpler, and neither is on the request path the
  admission design measures). Metrics binds every interface, plain HTTP/1.1
  (`h2c=false` — `VertxListener.Options.local` defaults `h2c` on, which this listener
  was never meant to have); OutboxAdmin keeps binding loopback-only.
- **Shutdown.** `HttpServer.shutdown(SHUTDOWN_GRACE)` (in-flight requests drain, new
  connections refused), then `vertx.close()`; the executor is shut down after the server.
- **Selection — removed 2026-09-08.** `FC_HTTP` and the Javalin adapter are gone
  (`docs/vertx-plan.md` Phase 3); Vert.x is the only listener, unconditionally.
  `TestHttp` no longer takes an `Adapter`; `SeamContract` keeps its one concrete
  subclass, `VertxSeamContractTest`.

## 2. Tests (break-it-on-purpose each)

| # | Behaviour | Pin | Mutant |
|---|---|---|---|
| 1 | Seam §4 rows 1–9 on Vert.x | `VertxSeamContractTest` (the abstract class re-run) | the same mutants as the Javalin unit, applied in the Vert.x adapter |
| 2 | Filters, handler and after run on one virtual thread and the response is written on the loop | row 8 + a header carrying `Thread.currentThread().isVirtual()`; assert the write thread name starts with `vert.x-eventloop` (captured in the adapter for tests) | run the handler on the loop → the virtual-thread assertion fails |
| 3 | A `select pg_sleep(60)` handler answers `503 DEADLINE` within `deadline + 1 s`, its connection is back in the pool and reusable, the group permit is released | `VertxDeadlineTest` over `TestPg`, deadline set to 1 s by the test | skip `cancelQuery` → the pool evicts the connection (Hikari total drops) and the assertion on reuse fails; skip `interrupt` → a handler parked on a `Semaphore` never answers |
| 4 | h2c prior-knowledge and upgrade both reach a handler and the response version is HTTP/2 | JDK `HttpClient` with `Version.HTTP_2` against the plain listener; assert `response.version()` | `setHttp2ClearTextEnabled(false)` → HTTP/1.1 |
| 5 | Shutdown drains an in-flight request and refuses a new connection | start a slow handler, call stop, assert the slow one completes 200 and a new connect is refused within the grace | `close()` instead of `shutdown()` → the in-flight request fails |
| 6 | Body over 1,000,000 bytes is 413 with the envelope | post 1,000,001 bytes | raise the limit → 200 |
| 7 | No `io.vertx` import outside the adapter package | `NoFrameworkLeakTest` extension | add one → fails |

Exit for Phase 2 (plan §5): suite green under both `FC_HTTP` values, lockfile
246/246, parity corpus identical under both, native image boots and serves, and the
runtime comparison of plan §8 run and recorded.

## 3. Owner question

- **Q6 — HTTP/3 on the Vert.x listener. Closed 2026-09-08: dropped.** Vert.x 5.1
  supports HTTP/3 only via Netty's incubator QUIC codec and its native library, which
  nothing in this platform needs — the ALB terminates TLS in production and never
  speaks h3 to the target, so h3 was always a client-edge feature of the deployment,
  not the process. `FC_HTTP3_ENABLED=true` is a startup error
  (`server.transport.Listeners#resolve`); `Http3.java` and the `jetty-http3-*`/
  `jetty-quic-*` dependencies are removed.

## 4. Landed 2026-09-06 (branch `vertx-listener`)

`io.flowcatalyst.http.vertx` (`VertxExchange`, `VertxRoutes`, `VertxListener` with
`prepare()`/`listen()`), `FC_HTTP` in `Env`, `Server.ApiListener`, `TestHttp.Adapter`,
`SeamContract` abstract with `JavalinSeamContractTest` and `VertxSeamContractTest` (18 rows
each), `VertxListenerTest` (rows 2–6; mutants killed: interrupt-only deadline, no interrupt
fallback, h2c off, `close()` for `shutdown()`, body limit raised), `NoFrameworkLeakTest` for
`io.vertx`. Full server suite green under `FC_HTTP=vertx` (3594/0) and `javalin`.

Four differences the first full Vert.x run exposed and the adapter now matches: JSON bodies are
newline-terminated like `JavalinJsonMapper` (and Go); `Set-Cookie` is hand-encoded because
Netty's encoder writes `HTTPOnly`; the 1 MB body cap is enforced lazily on read so a handler's
own `Content-Length` check answers its 400 first (`SettledApi`); a method miss falls through to
later routes so the SPA's GET catch-all still wins.

Deadline mechanism as measured: `cancelQuery()` first, then `interrupt()` only if the chain is
still running 250 ms later — an immediate interrupt races the cancel and closes the socket.

**Not in this unit:** the TLS listener on Vert.x (§1; `FC_HTTP=javalin` keeps it), HTTP/3
(Q6), Metrics and OutboxAdmin listeners (still Javalin), the native image on Vert.x, and the
plan §8 runtime comparison against Go.

## 5. Landed 2026-09-08 — the cutover (`docs/vertx-plan.md` Phase 3)

Everything §4 listed as "not in this unit" except the native image and the runtime
comparison (unchanged from 2026-09-06, not re-measured in this unit) is done: the TLS
listener (§1), Metrics and OutboxAdmin as their own `VertxListener`s (§1), Q6 closed
(§3). Javalin, every `org.eclipse.jetty*` artifact and `io.javalin:javalin` are removed
from both poms; `io.flowcatalyst.http.javalin` (`JavalinAdapter`, `JavalinRoutes`,
`JavalinExchange`, `JavalinJsonMapper`, `SkipRemainingHandlersSignal`) and
`server/transport/Http3.java` are deleted. `Env.HttpListener`/`FC_HTTP` are gone —
`Server#buildApiAndReaper` builds the Vert.x listener unconditionally.
`NoFrameworkLeakTest`'s Javalin rule is deleted; its Vert.x rule keeps two narrow
test-only exceptions (`Http2Test`, `HttpMediatorVersionTest` — each stands up a
throwaway Vert.x client/server as an "arbitrary HTTP/2 target", not part of the seam).
`TestHttp` collapses to Vert.x only; `SeamContract` keeps one concrete subclass,
`VertxSeamContractTest`; `JavalinSeamContractTest` and `BudgetsTest`'s
Javalin-adapter-specific test are deleted.

MCP's HTTP transport (`/mcp` streamable HTTP, a servlet
`HttpServletStreamableServerTransportProvider` mounted on Javalin's embedded Jetty) has
no Vert.x-native replacement — the MCP SDK ships no framework-agnostic HTTP transport,
and its SSE responses outlive the request that opens them (written to from other
threads as the session's reactive stream produces events), which does not fit dispatch
model B's buffered-response-once model. `FC_MCP_ENABLED=true` now fails the server at
startup (`io.flowcatalyst.mcp.McpServer.UNAVAILABLE`) rather than silently starting
nothing; `fcdev mcp --http` fails the same way (`fcdev mcp`'s default stdio transport
is unaffected — no HTTP listener involved). A native Vert.x transport (a small
`jakarta.servlet.http.HttpServletRequest`/`Response`/`AsyncContext` bridge over a raw,
non-seam `HttpServer`) is future work, not this unit.

A genuine bug this cutover found and fixed: `VertxListener.Options.local(...)`
defaults `h2c` to `true`, which is right for `TestHttp`/the API listener but wrong for
`Metrics` (`docs/spec/http-transport.md` §1 always specified HTTP/1.1 only) — caught by
`Http2Test.metricsListenerNeverUpgradesToH2c` failing against the first draft; fixed by
building `Metrics`'s `VertxListener.Options` directly instead of through `.local(...)`,
with `h2c=false` and host `0.0.0.0` (the metrics port must be reachable from another
pod/host, unlike the loopback-only outbox admin API — `.local()` also defaults to
`127.0.0.1`, which would have been silently wrong for the exact same reason).
