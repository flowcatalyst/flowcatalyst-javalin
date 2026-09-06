# Spec — the HTTP seam (`io.flowcatalyst.http`)

Status: owner-ruled 2026-09-06 (`docs/vertx-plan.md` §5 Phase 1, Q2 = the seam).
Purpose: every handler, filter and exception mapper in `server` is written against
this package and nothing else. Today it is implemented by a Javalin adapter; the
Vert.x adapter (Phase 2) implements the same types. Swapping the listener is then a
change to one package and four bootstrap sites, never to handlers.

## 1. Shape

Package `io.flowcatalyst.http` (module `server`), no dependency on any web framework.

| Type | What it is |
|---|---|
| `Exchange` | One request/response. The methods below, **named exactly as Javalin's `Context`** so the rewrite is `sed`-shaped (`Context ctx` → `Exchange ctx`, nothing else changes at the call site except the two cases in §3). |
| `Handler` | `@FunctionalInterface void handle(Exchange ctx) throws Exception` |
| `ExceptionHandler<E extends Exception>` | `void handle(E e, Exchange ctx)` |
| `Routes` | Registration surface, see §2 |
| `Group` | `enum { LOGIN, OIDC, DISPATCH, INGEST }` — the four tier-2 bulkhead groups (`docs/spec/admission.md`). A registration made through `Routes.in(Group)` carries the group; the adapter applies the budget. In Phase 1 the Javalin adapter records the group and applies nothing. |
| `HttpException` | `RuntimeException` with `int status()` and a message; replaces `io.javalin.http.HttpResponseException`. Mapped by the adapter as §4 row 4. |
| `HttpCookie` | `record HttpCookie(String name, String value, String path, int maxAge, boolean httpOnly, boolean secure, SameSite sameSite)` with `enum SameSite { STRICT, LAX, NONE }`; replaces `io.javalin.http.Cookie` + `SameSite`. `maxAge` −1 = session cookie, 0 = delete. |
| `ExceptionMappers` | Registration + resolution of `exception` mappers shared by every adapter: `register(Class, ExceptionHandler)`, `Optional<ExceptionHandler> resolve(Throwable)` returning the mapper for the **most specific** registered supertype (exact class, then superclasses in order). Adapters route every throwable through it; specificity is therefore the seam's logic and §4 row 3's mutant lives here. |
| `RouteRegistry` | Read side: `List<Registration> registrations()` where `record Registration(String method, String path, Group group)`; `before`/`after`/`exception` are not registrations. `LockfileCoverageTest` walks this instead of Javalin's `HandlerType`. Every `Routes` implementation is also a `RouteRegistry`. |

### `Exchange` — the 25 methods and their exact contracts

Request side:
- `String pathParam(String name)` — decoded; missing → `HttpException(400)`? **No**: Javalin throws `IllegalArgumentException` for an unknown name (a programming error) and the adapter must do the same. A present param is never null.
- `String queryParam(String name)` — first value or `null`.
- `String formParam(String name)` — first value or `null`.
- `String header(String name)` — request header or `null` (case-insensitive).
- `String body()` — UTF-8 body, `""` when empty.
- `byte[] bodyAsBytes()`.
- `<T> T bodyAsClass(Class<T> type)` — through the platform JSON mapper (`JavalinJsonMapper` today, the same `tools.jackson` mapper on Vert.x). A malformed body raises the mapper's exception, which the existing exception order maps to 400 exactly as today (`HttpError.install` row for the mapper's exception type — keep whatever class Javalin surfaces today; the spec's rule is *the wire result is unchanged*, pinned by the parity corpus).
- `String path()` — the request path without query string.
- `String method()` — upper-case, `"GET"`… (Javalin returned `HandlerType`; the one comparison site becomes a string comparison).
- `String ip()` — remote address as Javalin reports it (no XFF parsing here; `RateLimit.ClientIP` does that).
- `String scheme()`.
- `long contentLength()` — request `Content-Length`, −1 when absent (Javalin's `int`; widen).
- `String cookie(String name)` — request cookie value or `null`.
- `<T> T attribute(String key)` / `void attribute(String key, Object value)` — per-request map.

Response side (every mutator returns `Exchange` for chaining, as Javalin's do):
- `Exchange status(int code)`; `int statusCode()`.
- `Exchange header(String name, String value)` — set (replace). `Exchange addHeader(String name, String value)` — append (one caller).
- `Exchange contentType(String value)`.
- `Exchange json(Object body)` — serialise with the platform mapper, set `Content-Type: application/json` unless already set, status untouched.
- `Exchange html(String body)` — `text/html; charset=utf-8`.
- `Exchange result(String body)` / `Exchange result(byte[] body)` / `Exchange result(InputStream body)` — raw body; `InputStream` is streamed and closed by the adapter after the response ends.
- `InputStream resultInputStream()` — `null` when no body has been set (only `ResponseDefaults` needs it; see §4 row 1 — after Phase 1 it has **no callers** and is dropped from the interface; listed here so the rewrite knows to delete `ResponseDefaults` rather than port it).
- `Exchange redirect(String location, int status)`.
- `Exchange cookie(HttpCookie cookie)`; `Exchange removeCookie(String name, String path)`.
- `void skipRemainingHandlers()` — a filter that wrote a response stops the chain (CORS preflight, 401, 429). Semantics: no later `before`, no route handler; `after` filters still run.

Dropped, not ported: `res()` (raw servlet response; its only use was the bodiless rule, now §4 row 1).

## 2. `Routes`

```
Routes get(String path, Handler h);   post, put, patch, delete likewise
Routes before(Handler h);  Routes before(String path, Handler h);
Routes after(Handler h);
<E extends Exception> Routes exception(Class<E> type, ExceptionHandler<E> h);
Routes in(Group group);   // a view: registrations made through it carry the group
```

Path syntax is Javalin's and stays so on Vert.x: `{id}` one segment, `<path>` the rest
(`Frontend` uses `/<path>`). The adapter translates; handlers never see the difference.

Ordering rules (the adapter guarantees them, tests pin them):
1. `before` filters run in registration order, then the route handler, then `after`
   filters in registration order — **all on the request's thread** (on Vert.x, the
   request's virtual thread; only the response write hops to the loop).
2. `exception` mappers are matched **most specific first**, i.e. exact class before
   superclass, regardless of registration order (Javalin's semantics; `HttpError.install`
   registers `Exception.class` last and relies on it).
3. A thrown exception skips the remaining `before`/handler but `after` filters still run.
4. **404/405 envelope**: an unmatched path answers the platform's JSON `NOT_FOUND`
   envelope (`HttpError`), never a bare 404, and a matched path with the wrong method
   also answers 404 (today's `prefer405over404 = false`). The adapter owns this.
5. **Bodiless rule**: a response with status 204, or any response whose handler set
   no body, carries **no `Content-Type`** (Go's net/http sends none). Adapter-owned; it
   replaces `ResponseDefaults`.

## 3. The rewrite (Phase 1, Sonnet, worktrees)

Mechanical, across the 94 `server/src/main` files and the 4 test files that import
Javalin, **except** the four bootstrap sites (`server/Server.java`, `server/Metrics.java`,
`outbox/OutboxAdminApi.java`, `mcp/McpServer.java`) and the transport package
(`server/transport/**`), which keep Javalin/Jetty until Phase 2/3:

| Today | After |
|---|---|
| `import io.javalin.http.Context` / `Context ctx` | `io.flowcatalyst.http.Exchange` / `Exchange ctx` |
| `io.javalin.router.JavalinDefaultRoutingApi routes` | `io.flowcatalyst.http.Routes routes` |
| `io.javalin.http.Handler` | `io.flowcatalyst.http.Handler` |
| `io.javalin.http.Cookie`, `SameSite` | `HttpCookie`, `HttpCookie.SameSite` |
| `io.javalin.http.HttpResponseException` (thrown or mapped) | `HttpException` |
| `ctx.method() == HandlerType.X` | `"X".equals(ctx.method())` |
| `ctx.res().setContentType(null)` (`ResponseDefaults`) | delete the class; §2 rule 5 |
| `cfg.routes.after(...)` in `ResponseDefaults.register(JavalinConfig)` | gone |
| `new TestHttp(cfg -> X.register(cfg.routes, s))` | `TestHttp.routes(routes -> X.register(routes, s))` — a static factory, because a second lambda-taking constructor would be ambiguous; the old constructor is deleted in unit (e) |
| `LockfileCoverageTest` walking `HandlerType` | walks `RouteRegistry.registrations()` |

The Javalin adapter (`io.flowcatalyst.http.javalin`): `JavalinRoutes implements Routes,
RouteRegistry` over a `JavalinDefaultRoutingApi`; `JavalinExchange implements Exchange`
over a `Context`; the 404/405 envelope and the bodiless rule installed by the adapter's
`install(JavalinConfig)`, which the four bootstrap sites call. Handlers are wrapped so
an `HttpException` thrown anywhere reaches the mapper. The adapter registers **one**
Javalin mapper for `Exception.class` that resolves through `ExceptionMappers`; Javalin's
own `HttpResponseException` (its 404/405 for unmatched routes) is translated to
`HttpException(status, message)` before resolution, so the platform's `HttpException`
mapper produces the envelope and no handler code ever sees a Javalin type.

### Two things real Javalin 7.2.3 forced (found while building unit (a))

- Javalin pre-registers its own mapper for `HttpResponseException`, and its most-specific
  walk makes that a closer match than `Exception.class`, so the adapter registers the
  translate-then-resolve handler under **both** keys; otherwise unmatched-route 404s
  render Javalin's plain-text body and never reach the envelope.
- Javalin's `Context.skipRemainingHandlers()` skips `after` filters too. The seam's
  rule is that `after` still runs, so `JavalinExchange.skipRemainingHandlers()` throws
  a stackless `SkipRemainingHandlersSignal` mapped to a no-op — a thrown `before` is
  the one path on which Javalin does run `after`. Every caller writes its response
  *before* calling skip (verified: all six), so the throw loses nothing.

## 4. Behaviours that must not move (pinned by tests, break-it-on-purpose each)

| # | Behaviour | Test that pins it, and the mutant that must fail it |
|---|---|---|
| 1 | 204 and body-less 200 carry no `Content-Type`; a JSON 200 carries `application/json` | `SeamContractTest`: assert header absent / present. Mutant: drop the bodiless hook → the absent-assert fails. |
| 2 | Unknown path → JSON `NOT_FOUND` envelope with code `NOT_FOUND`; wrong method on a known path → the same 404, never 405 | assert body code and status. Mutant: set `prefer405over404 = true` → fails. |
| 3 | Exception mapper specificity: a `UseCaseException` thrown in a handler is mapped by its own mapper, not by `Exception.class` | assert the envelope code from `UseCaseError`. Mutant: register mappers in reverse order → still passes (Javalin) — that is the point; on Vert.x the adapter must implement specificity, and the mutant "match first registered" must fail. |
| 4 | `HttpException(status, msg)` → the `HttpError` envelope with the status code's name (`BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, else `INTERNAL`) | assert each. Mutant: drop the `409` arm → fails. |
| 5 | `skipRemainingHandlers()` from a `before` stops the route handler but `after` still runs | a probe `after` sets a header; assert the route handler's marker is absent and the header present. Mutant: run the handler anyway → fails. |
| 6 | Cookie attributes survive: `SameSite`, `HttpOnly`, `Secure`, `Path`, `Max-Age` | parse `Set-Cookie`. Mutant: drop `SameSite` → fails. |
| 7 | `result(InputStream)` streams the bytes and closes the stream | a counting stream; assert closed and byte-equal. Mutant: never close → fails. |
| 8 | Filters + handler + after run on **one** thread per request | record `Thread.currentThread()` in each; assert equal. (Javalin: trivially true; Vert.x: the load-bearing one.) |
| 9 | Registry lists every `get/post/…` with its `Group`, none of the filters | assert counts against a small fixture `Routes`. |
| 10 | No `io.javalin` import outside the allowed set | `NoFrameworkLeakTest`: scan `server/src/main` and `server/src/test` sources; allowed: `io/flowcatalyst/http/javalin/**`, the four bootstrap classes, `server/transport/**`, `TestHttp`, `LockfileCoverageTest` (until it is rewritten, then it leaves the list). Mutant: add an import to a handler → fails. |

`SeamContractTest` runs against the **adapter under test** through `TestHttp`, so the
same class pins the Vert.x adapter in Phase 2 with no change.

## 5. Exit for Phase 1

`mvn -q test` green across the reactor; `LockfileCoverageTest` 245/245 with zero
drift; the parity corpus (`parity/`) identical to the pre-seam run; `NoFrameworkLeakTest`
green with the allowed list as in §4 row 10. Javalin is still the only listener. One
commit per landed unit: (a) the seam package + adapter + harness + contract tests;
(b)–(d) the three package-group rewrites; (e) `ResponseDefaults` removal and the
registry-based coverage test.

## 6. Owner notes

- `contentLength()` widens from `int` to `long`; two call sites, both comparisons.
- `method()` becomes a `String`; one comparison site changes shape.
- `Group` is in the seam so that handlers declare *what they are*; the budgets live in
  `docs/spec/admission.md` and are derived, never configured.
