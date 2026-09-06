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
  store handed to Vert.x as PKCS12 bytes (JDK TLS, no native); **HTTP/3 is not in this
  unit** — Vert.x 5.1 does HTTP/3 only through Netty's QUIC native codec, which plan
  ruling Q3 excludes unless measured; the Jetty listener keeps it behind `FC_HTTP=javalin`
  and the gap is owner question Q6 (§5). `Alt-Svc` therefore is not emitted by the Vert.x
  listener.
- **Metrics and OutboxAdmin listeners** stay on Javalin in this unit (they are not on the
  request path being measured); they move to a second `HttpServer` on the same `Vertx`
  in Phase 3 when Javalin is removed.
- **Shutdown.** `HttpServer.shutdown(SHUTDOWN_GRACE)` (in-flight requests drain, new
  connections refused), then `vertx.close()`; the executor is shut down after the server.
- **Selection.** `FC_HTTP=vertx|javalin` in `Env` (default `javalin` until Phase 3).
  `Server.Running.apiPort()`/`stop()` work for both through a small `ApiListener`
  interface. `TestHttp` picks the adapter from the `FC_HTTP` system property or env, and
  `TestHttp.routes(Adapter, …)` forces one; `SeamContractTest` becomes abstract with a
  Javalin and a Vert.x subclass so every pinned behaviour runs against both.

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

- **Q6 — HTTP/3 on the Vert.x listener.** Vert.x 5.1 supports HTTP/3 only via Netty's
  incubator QUIC codec and its native library. Options: (a) keep HTTP/3 out of the Vert.x
  listener and drop it at Phase 3 (the ALB terminates TLS in production, so h3 never
  reaches the process there anyway); (b) measure the Netty QUIC native the way Jetty's
  quiche binding was measured and decide on evidence. Until ruled, `FC_HTTP=javalin`
  keeps today's HTTP/3.
