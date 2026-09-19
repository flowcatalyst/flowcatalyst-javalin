# Spec — the host's listener: `/functions/{address}[:{version}]/{path}` (package D, slice D3)

Contract it serves: `function-invocation.md` §2 (addresses), §3 (`endpoints`, the three `auth`
modes), §6 (the signing secret), §7 (`Request`/`Result`). Builds on D1 (`function-host-core.md`) and
D2 (`function-host-reconciler.md`). **Private entry only** — the public listener (hostnames, domain
verification, CORS) is package F and reuses everything here below the route match.

## 0. Departures

| Design / workplan | Here | Why |
|---|---|---|
| reuse the server's `HttpServerVerticle` / `VertxListener` | the host has its own small Vert.x `HttpServer` wrapper | the server's listener is built around admission groups sized from database pools; the host has no database and its admission is per-function permits. Same model-B shape: event loop accepts and buffers, the invocation runs on a virtual thread, the response is written back with `runOnContext` |
| permit exhaustion ⇒ `503` + `Retry-After` on gateway calls, "retry-with-backoff outcome" on deliveries | **`429` + `Retry-After: 1`** for every caller | `429` is the one status the dispatch processor treats as *deferred, no retry budget spent* (`Result`'s mapping table); a browser or API client understands it equally. One answer, not two |
| the host subscribes to `version:published` / `alias:changed` and reconciles on delivery | polling only (15 s, ETag) | a promote needs `READY` first, which already takes a reconcile; lazy functions load on demand. If 15 s after promote proves too slow, `ReconcileLoop.trigger()` is already there to wire — measure first |
| `FunctionContext` services | still the D1 stub (logger, clock, address, version) | D4 |

## 1. One platform-side addition

Desired-state entries gain `applicationId` and `clientId` (omitted for a platform function) — the
host needs the function's owner to decide *reach* for a versioned call (§4). New permission
`FUNCTION_VERSION_INVOKE` = `platform:function:version:invoke`, added to `platform:function-publisher`
and `platform:messaging-admin` (pinning tests updated).

## 2. Request path

`FnHttpServer` (package `io.flowcatalyst.fnhost.http`), one Vert.x instance, `FC_FN_PORT` (default
8080), HTTP/1.1 + h2c. Only `/functions/…` is routed; anything else is 404. Steps, in order — each
failure names its response; **error bodies are `{"error": CODE, "message": …}`, never a stack trace
or an exception message from function code**:

1. **Draining** ⇒ `503` + `Retry-After: 5` (`DRAINING`). In-flight requests finish.
2. **Parse** `/functions/{address}[:{version}]` + rest. Bad address, version not a positive int ⇒
   `400 ADDRESS_INVALID` / `VERSION_INVALID`. `function-path` = `/` + rest (rest may be empty); the
   raw path is matched undecoded, as `RoutePattern` requires.
3. **Resolve**: unversioned ⇒ the live entry of the last desired-state document (`lazyRoutes` or
   loaded); none ⇒ `404 FUNCTION_NOT_FOUND`. Versioned ⇒ §4.
4. **Endpoint**: `RoutePattern.firstMatch` over the version's manifest `endpoints`, then the method
   (`methods` absent ⇒ all; `webhook` ⇒ `POST`). No pattern ⇒ `404 ENDPOINT_NOT_FOUND`; pattern but
   not the method ⇒ `405` with `Allow`. **Nothing past this line runs for a path the manifest does
   not declare — the function never sees it.**
5. **Body**: `Content-Length` over the endpoint's `maxBodyBytes` ⇒ `413` before reading; a chunked
   body is cut off at the cap ⇒ `413`. The body is fully buffered (values, not streams — design §4a).
6. **Authenticate** by the endpoint's `auth` (§3) ⇒ `401` on failure, with `WWW-Authenticate: Bearer`
   for `platform`. The body must be read first: a webhook signature covers it.
7. **Permits**: host-global (`FC_FN_MAX_CONCURRENCY`, default 512) then per-function
   (`limits.maxConcurrency`), both `tryAcquire` — never a queue. Either exhausted ⇒ `429` +
   `Retry-After: 1` (`BUSY`), and the first is released if the second fails. Released in `finally`.
8. **Load**: `Reconciler.ensureLoaded` for a lazy function. Known but not loadable (prepare failed,
   refused) ⇒ `503` + `Retry-After: 15` (`FUNCTION_UNAVAILABLE`) — the next reconcile may fix it, so
   it is not a 404.
9. **Invoke** on a virtual thread through `LoadedFunction.invoke` (context class loader switched, D1),
   `retain()`/`release()` around it, MDC `function`, `version`, `invocation_id` (a fresh TSID-like id,
   also the `Request.invocationId`), `correlation_id` from `X-Correlation-Id` when present.
   Deadline = the endpoint's `timeoutMs`: at the deadline the thread is **interrupted** and the caller
   gets `504 FUNCTION_TIMEOUT`. A function that ignores the interrupt keeps its permits until it
   returns — deliberately: the permit gauge then shows the truth, and the per-function cap contains
   the damage. A throw ⇒ `500 FUNCTION_ERROR`, logged with the cause; a `null` result ⇒ the same.
10. **Respond**: `Result.status/headers/body`; hop-by-hop headers (`Connection`, `Transfer-Encoding`,
    `Keep-Alive`, `Upgrade`, `Proxy-*`, `TE`, `Trailer`) and `Content-Length` from the function are
    dropped — the host sets the length. Written on the request's context.

`Request` fields: `path` = function-path, `originalHost` = `Host`, `originalPath` = the full request
path, `pathParams` from the matched endpoint, `query` parsed (repeated keys kept in order, `+` is a
space here — this is a query, not a path), `headers` as received minus `Authorization` and the two
`X-FlowCatalyst-Signature`/`-Timestamp` headers on `webhook`/`platform` endpoints (the host consumed
them; on `none` endpoints everything passes — the function does its own auth).

## 3. The three auth modes

- **`webhook`** ⇒ `Caller.Platform`. `WebhookVerifier`: `X-FlowCatalyst-Signature` equals
  `hex(HMAC-SHA256(secret, timestamp ‖ body))` (constant-time compare; the server's `WebhookSigner`
  computes it), `X-FlowCatalyst-Timestamp` no older than 300 s and no more than 60 s in the future —
  the SDK's `WebhookSignature` rule, pinned against the SDK's committed test vectors so host and SDK
  can never drift. The secret is the entry's `webhookSigningSecret`; after a rotation the **previous**
  secret is also accepted until the second reconcile after the change (deliveries signed just before
  the rotation are still in flight), then dropped. No secret in desired state ⇒ every `webhook` call
  is `401` (fail closed) and the heartbeat reports the function `FAILED: NO_SIGNING_SECRET`.
- **`platform`** ⇒ `Caller.Principal`. Bearer JWT verified locally with the server's `JwtVerifier`
  over `JwksKeySource`: keys from `<platform>/.well-known/jwks.json`, cached; an unknown `kid`
  refetches **at most once per 30 s** (a flood of bad tokens must not become a flood of JWKS
  requests); issuer and expiry checked as the platform's own authenticator does. `Principal` =
  id, type, client scope and permissions **from the token's claims — read `AccessTokenClaims` /
  the authenticator to see what they are; do not invent claims**. The host does not decide what the
  principal may do; the function does (ruling R10).
- **`none`** ⇒ `Caller.Anonymous`; nothing checked, nothing stripped.

## 4. Versioned calls — `/functions/{address}:{version}/…`

For people smoke-testing a version, never for the platform (`function-invocation.md` §2). Whatever
the endpoint's own `auth`:

1. a platform bearer token (as `platform` above) ⇒ else `401`;
2. holding `platform:function:version:invoke` (held patterns may carry `*` segments —
   `Permission.matches`) ⇒ else `403`;
3. **reach**: anchor, or the token's client scope contains the entry's `clientId` (a platform-owned
   function needs anchor), and its application scope, if restricted, contains `applicationId` ⇒ else
   `404` (not 403 — same rule as the platform API);
4. the version must be an entry of the current desired-state document (live or candidate) and
   prepared ⇒ else `404 VERSION_NOT_AVAILABLE`.

The endpoint's own auth is then **not** applied (a `webhook` endpoint can be smoke-tested without the
signing secret); `Request.caller` is the `Principal`. A candidate is loaded into `pinnedVersions` — a
small LRU (8) beside the registry, never the registry's live slot, closed when its version leaves
desired state. **An unversioned call is never served by `pinnedVersions`.**

## 5. Lifecycle

`FnHost` assembles `HostEnv` → `ArtifactStores` → `Reconciler` + `ReconcileLoop` → `FnHttpServer`;
`start()` binds only after the first reconcile attempt (success or failure) so a host never answers
404 for functions it simply has not heard of yet; `close()`: `drain()` (heartbeat `DRAINING`), stop
accepting, wait for in-flight up to `FC_DRAIN_TIMEOUT_SECONDS` (default 60), close the loop, close
every loaded function. `main`, metrics, `/health` and the Dockerfile are D5.

## 6. Tests

Real HTTP (`java.net.http`) against `FnHttpServer` on an ephemeral port, fixture functions from
`FixtureJars`, a `FakeControlPlane`-fed `Reconciler`; time through an injected `Clock` where a rule is
about time. One mutant per condition; assert absence as well as presence; a mutant counts only when
applied, run, seen to fail, and restored from a copy.

| # | Behaviour | Mutant |
|---|---|---|
| H1 | the function receives `path` without the prefix, `originalPath` with it, path params decoded, query multi-values in order, header lookup case-insensitive; an undeclared path is 404 and **the function's invocation counter stays 0**; wrong method 405 with `Allow` | skip the endpoint match; pass the full path |
| H2 | `webhook`: valid signature ⇒ 200 and `Caller.Platform`; wrong secret, altered body, altered timestamp, stale (301 s), future (61 s), missing header — each 401 and the function not invoked; the SDK's committed vectors verify | drop each check in turn; compare with `equals` is **not** a required mutant (timing) — say so |
| H3 | rotation: previous secret accepted through the next reconcile, rejected after the second; a function with no secret is 401 and heartbeats `NO_SIGNING_SECRET` | never drop the previous; accept when no secret |
| H4 | `platform`: valid token ⇒ `Principal` with the token's id/type/clients/permissions; expired, wrong issuer, wrong key, garbage ⇒ 401 + `WWW-Authenticate`; unknown `kid` refetches JWKS once, a second unknown `kid` inside 30 s does **not** (count requests to the fake JWKS endpoint) | refetch every time; skip expiry; skip issuer |
| H5 | `none`: `Authorization` and signature headers reach the function untouched; on `webhook`/`platform` they do not | strip always; never strip |
| H6 | permits: with `maxConcurrency: 1` and one call parked inside the function (latch), a second is `429` + `Retry-After` and **the function is not entered**; after release a third succeeds; the host-global cap likewise across two functions; a failure to get the function permit returns the host permit (assert available count) | queue instead of `tryAcquire`; leak the host permit |
| H7 | timeout ⇒ 504 and the function thread was interrupted; a function that swallows the interrupt keeps its permit until it returns (assert the gauge), then frees it | release the permit at the deadline |
| H8 | throw ⇒ 500 `FUNCTION_ERROR` with no exception text in the body; `null` ⇒ same; permits released on both | echo the message |
| H9 | body cap: `Content-Length` over ⇒ 413 without reading; chunked over ⇒ 413; at the cap ⇒ 200 | check after buffering |
| H10 | response: hop-by-hop and `Content-Length` from the function dropped; status, other headers (multi-valued) and body verbatim | pass everything |
| H11 | versioned: no token 401; token without the permission 403; without reach 404 (another client's; platform-owned vs non-anchor; another application's); unknown version 404; a candidate is served **only** under `:{v}`; the same path unversioned still gets live; a `webhook` endpoint is reachable versioned without a signature | drop each of the four checks; let unversioned fall through to `pinnedVersions` |
| H12 | lazy function loads on first call, once under two concurrent first calls; an unloadable one is 503 + `Retry-After`, not 404 | 404 |
| H13 | promote v1→v2 while a v1 call is parked: the v1 call completes on v1, new calls get v2, v1's loader is closed only after the parked call returns | close immediately |
| H14 | drain: new ⇒ 503, in-flight completes, `close()` returns within the drain timeout even if a function never returns | wait for ever |
| H15 | **the package D acceptance, in process**: platform `Server` on `TestPg` + this host. Signed publish of a function with a subscription → heartbeat → `READY` → promote (subscription appears, target `…/functions/<address>/events/…`) → a dispatch job for that subscription is processed through `/api/dispatch/process` exactly as the router would call it → the platform's `SubscriberDelivery` POSTs a **signed** webhook to this host → the function runs with `Caller.Platform` and `Webhook.event(request)` yields the event → `Result.ack()` → the job is `COMPLETED`. Then: the function answers `Result.retry(…)` ⇒ the job is deferred, no attempt spent; the platform pool URL template points at the host's ephemeral port | — (integration pin) |
