# Writing a FlowCatalyst function

A function is a small piece of code the platform hosts, versions and calls over HTTP on your
behalf. This guide is the practical path from nothing to a published, promoted, invoked function:
the worked example is `examples/function-hello` — read its four files
(`manifest.json`, `proguard.conf`, `src/main/java/io/flowcatalyst/example/hello/HelloFunction.java`,
`src/test/java/io/flowcatalyst/example/hello/ShrunkJarTest.java`) alongside this guide.

Every claim below is checked against the code or a spec, and the "Sources" section at the end
names which. Where the platform's behaviour is less convenient than you might expect, this guide
says so plainly rather than rounding it off — see [§8 Honest limits](#8-honest-limits) in
particular before you rely on `Result.fail`/`Result.retry` for anything precise.


> New to the function service? `docs/function-service-overview.md` explains what it is, how it fits
> the platform, and where everything lives — read that first, then come back here for the how-to.

## 1. What a function is

**A function is always invoked over HTTP** — there is no separate "event invocation" type. An
event reaches your function the same way any subscriber's endpoint would: a subscription whose
target is your function's URL, delivered by the platform as a signed webhook. A scheduled job
works the same way. A browser or an API client, minus the signature. What the manifest adds over
"a webhook receiver you host yourself" is that the platform **creates and removes** your
subscriptions/schedules for you, in step with what you deploy.

Your function implements one interface:

```java
public interface Function {
    default void init(FunctionContext ctx) throws Exception { }
    Result handle(Request in, FunctionContext ctx) throws Exception;
    default void stop() throws Exception { }
}
```

The host builds one instance per loaded version, calls `init` once, `handle` once per invocation,
and `stop` once when the version unloads. `FunctionContext` is everything your function may reach
outside the invocation itself: a logger, config, secrets, a database pool, host-mediated outbound
HTTP, event emission, a clock, and your own address/version.

> A second sample, `examples/function-subscription-test`, is the smallest useful function: it
> subscribes to a platform event and stores every delivery in its own database through a
> platform-held secret. Its README is a five-step walkthrough of the whole path on a dev machine.

## 2. Quickstart

```sh
mkdir -p src/main/java/com/example/hello
# write HelloFunction.java (§3 below) and manifest.json (§4 below)
fcdev start                                  # platform + function host, one process, embedded Postgres
fcdev fn watch . --manifest manifest.json    # builds, publishes, promotes on every save
fcdev fn invoke acme.default.hello --path /healthz
```

`fcdev start` runs a function host beside the platform by default (`--no-functions` /
`FC_DEV_FUNCTIONS=false` turns it off); its port is `--fn-port` / `FC_FN_PORT`, default **8090**
(the platform itself is on 8080). The ready banner prints a `functions` line
(`http://127.0.0.1:8090/functions/…`) once both are up. `fcdev` also writes `fn-cli.json` into its
state directory on first `start` — the credentials every `fcdev fn …` command below reads by
default, so local commands need no `--client-id`/`--client-secret` flags at all.

## 3. Writing the function

`examples/function-hello`'s `HelloFunction` is deliberately small but exercises every kind of
endpoint the manifest supports:

```java
public final class HelloFunction implements Function {
    @Override
    public Result handle(Request in, FunctionContext ctx) throws Exception {
        String path = in.path();
        if (path.equals("/healthz"))                      return handleHealth();
        if (path.equals("/events/greeting-requested"))    return handleGreetingRequested(in, ctx);
        if (path.startsWith("/api/hello/"))                return handleHello(in);
        return Result.fail("no route for path " + path);
    }
    // ...
}
```

A few things worth calling out:

- **`in.path()`** is the request path with the `/functions/{address}[:{version}]` prefix (or the
  public route's prefix) already stripped — your function never sees that part. Whatever pattern
  matched (from `manifest.json`'s `endpoints`) also binds `in.pathParams()`.
- **`in.caller()`** is a sealed type — `Caller.Platform` (a verified webhook delivery),
  `Caller.Principal` (a platform bearer token; carries `id`, `type`, `clientId`, `permissions`) or
  `Caller.Anonymous` (a `none` endpoint) — one case per `auth` mode (§6). Switch on it exhaustively;
  the compiler will tell you if the platform ever adds a fourth.
- **Parsing an inbound webhook delivery**: `Webhook.event(Request)` parses a subscription/direct
  dispatch job's body into an `Event` record; `Webhook.schedule(Request)` parses a scheduled job's
  firing into a `Schedule` record. Both throw `WebhookFormatException` on anything malformed — let
  it propagate (the host turns any throw into `500 FUNCTION_ERROR`) unless you have a better answer.
  `Event.dataJson`/`Schedule.payloadJson` are the *raw JSON text* of the `data`/`payload` member,
  never a parsed tree — `function-api` carries zero JSON library, so parse it yourself (the sample
  uses Jackson, shaded in — see §7).
- **Config and secrets**: `ctx.config().get("KEY")` / `ctx.secrets().get("KEY")` return
  `Optional<String>`; `.require("KEY")` throws `IllegalStateException` naming the key if it was
  never declared in `manifest.json` or never set on the platform. **Never log a secret's value** —
  its presence is fine to log, its value is not, and nothing checks this for you.
- **Emitting an event**: `ctx.events().emit(new OutboundEvent(...))` — see §7.
- **Logging**: `ctx.logger()` is a `System.Logger` already carrying your function's address,
  version, invocation id and correlation id as MDC — just log through it, don't build your own.

## 4. The manifest

`manifest.json` sits beside your jar (or, for the sample, beside the module's `pom.xml`) and is
read by the REAL `io.flowcatalyst.platform.function.Manifest.parseStrict` at publish time — reject
first, on the platform, not at runtime on a host. **Unknown keys at any level are rejected.**

```json
{
  "runtime": "jvm",
  "entrypoint": "com.example.HelloFunction",
  "pool": "default",
  "warm": false,
  "limits": { "maxDurationMs": 10000, "maxConcurrency": 8 },
  "endpoints": [
    { "path": "/events/greeting-requested", "auth": "webhook" },
    { "path": "/api/hello/{name}", "auth": "platform", "methods": ["GET"],
      "cors": { "origins": ["https://app.acme.com"], "methods": ["GET"] } },
    { "path": "/healthz", "auth": "none", "methods": ["GET"] }
  ],
  "subscriptions": [
    { "eventType": "hello:greeting:greeting:requested", "path": "/events/greeting-requested",
      "mode": "IMMEDIATE", "maxRetries": 3, "timeoutSeconds": 30, "dataOnly": false }
  ],
  "schedules": [ ],
  "public": [ { "hostname": "api.acme.com", "pathPrefix": "/hello" } ],
  "config": ["GREETING"],
  "secrets": ["API_KEY"],
  "db": [ ],
  "httpAllow": [ ]
}
```

| Key | Required | Notes |
|---|---|---|
| `runtime` | yes | `jvm` or `wasm` (wasm is a later phase — not built yet); must match the function's own runtime, set at creation |
| `entrypoint` | yes | a binary class name (JVM) — the class the host instantiates by reflection (a public no-arg constructor implementing `Function`) |
| `pool` | no | a DNS label; `default` when absent |
| `warm` | no | keep this version's live instance warm rather than lazily loaded; `false` when absent |
| `limits.maxDurationMs` / `.maxConcurrency` | no | resolved against the platform's defaults and your client's ceiling; a value over the ceiling is rejected at publish |
| `limits.wasmMemoryMb` | — | **not applicable to `jvm`** — including it is rejected |
| `endpoints[].path` | yes | a route pattern: literal segments, `{param}`, or a trailing `*` |
| `endpoints[].auth` | **yes, always** | `webhook`, `platform` or `none` — **no default**; see §6 |
| `endpoints[].methods` | no | absent/empty = every method; a `webhook` endpoint's methods, if given, must be exactly `["POST"]` |
| `endpoints[].maxBodyBytes` | no | default 1 MiB |
| `endpoints[].timeoutMs` | no | default is `limits.maxDurationMs` |
| `endpoints[].cors` | no | `{ origins, methods, headers, allowCredentials }` — CORS for this endpoint on BOTH listeners (§6a); absent means the host does nothing CORS-related for it at all |
| `subscriptions[].eventType` | yes | the event type to subscribe to |
| `subscriptions[].path` | yes | **must be a literal path** (no `{}`/`*`) matching a `webhook`-authed endpoint, or publish is rejected |
| `subscriptions[].mode` | no | a `DispatchMode`; **default `IMMEDIATE`** — not the router's own ordinary default, because a manifest author who wants ordering asks for it explicitly |
| `subscriptions[].dataOnly` | no | default **`false`** — your function sees the whole envelope by default |
| `schedules[].cron` / `.timezone` / `.path` / `.payload` | — | same literal-path/webhook-endpoint rule as subscriptions |
| `public[].hostname` / `.pathPrefix` | — | a public HTTP route on a verified domain you own (§6a); `pathPrefix` defaults to `/` |
| `config` / `secrets` | no | the keys your function needs; each must match `^[A-Za-z][A-Za-z0-9_./-]{0,99}$`; promote refuses (`SETTINGS_MISSING`) if any declared key has no value set |
| `db[].name` / `.secretRef` / `.poolSize` | no | a database connection; `secretRef` names a secret holding the DSN |
| `httpAllow` | no | outbound hosts `ctx.http()` may call (exact host, or `*.suffix` for subdomains). A response body over **16 MiB** fails the call with `RESPONSE_TOO_LARGE` (`HttpResponseTooLargeException` on the JVM; an error body for JS and Rust). The cap is fixed, so page or stream large exports instead |

There is **no `filter`** on a subscription entry — the platform accepts and silently drops it (a
known gap, tracked in `docs/backlog.md`); do not rely on server-side filtering.

## 5. The manifest wires itself up — and un-wires itself

**Publish validates, promote materialises.** Publishing a version checks every rule above but
creates nothing. When you *promote* that version to live, the platform reconciles the platform
objects (one dispatch pool, one subscription per entry, one scheduled job per entry) to exactly
what the new manifest lists — creating what's new, updating what changed, and **deleting what the
manifest no longer lists**. Roll back to an older version and its wiring comes back too; nothing
you deploy leaves orphaned subscriptions behind.

## 6. The three auth modes

Every endpoint names exactly one, and the host checks it *before your function ever sees the call*:

| `auth` | `Caller` your function sees | When to use it |
|---|---|---|
| `webhook` | `Caller.Platform` | subscription deliveries, direct dispatch jobs, scheduled jobs — the platform's own signature (`X-FlowCatalyst-Signature`/`-Timestamp`, HMAC-SHA256 under your application's signing secret) is verified for you |
| `platform` | `Caller.Principal` | an ordinary platform API endpoint — the host verifies the bearer JWT locally against the platform's JWKS; **your function still decides what the principal may do**, the host only proves who it is |
| `none` | `Caller.Anonymous` | the host checks nothing — your own inbound webhooks (from a third party), your own sessions |

`auth` has no default — an endpoint that forgets it is rejected at publish, not silently treated as
open or closed.

### Authorising the caller

On a `platform` endpoint, `Caller.Principal` carries every claim the verified token holds —
`id`, `type`, `tier`, `clients`, `roles`, `applications`, `allApplications` and `permissions` —
except `email`/`name`: a function has no business with them, and they are PII the token happens to
hold. Each method restates one of the platform's own authorisation rules exactly, so your check
answers precisely as the platform's own would:

| Method | Semantics |
|---|---|
| `hasPermission(String required)` | exact match, or a held code whose segments match `required`'s segment for segment with `*` as a wildcard, same segment count |
| `hasAnyPermission(String…)` / `hasAllPermissions(String…)` | over `hasPermission` |
| `hasRole(String code)` | `roles.contains(code)` |
| `isAnchor()` | `tier` is the anchor tier |
| `canAccessClient(String clientId)` | anchor, or `clientId` is in `clients` |
| `canAccessApplication(String applicationId)` | `allApplications`, or `applicationId` is in `applications` (by **id**, not name) |
| `clientId()` | `Optional<String>`: the one client, when `clients` has exactly one entry that is not the anchor wildcard `*`, else empty |

```java
if (!(in.caller() instanceof Caller.Principal principal) || !principal.hasPermission("hello:greeting:greet")) {
    return Result.json(403, "{\"error\":\"PERMISSION_REQUIRED\"}");
}
```

## 6a. Public routes, domains, and CORS

By default a function is reachable only by address (`/functions/{address}/...`, on the PRIVATE
listener — in-VPC / Service Connect / fcdev's own port) or as a webhook/schedule target the
platform itself calls. `public[]` in the manifest exposes it on the internet-facing **public
listener** instead, at a hostname covered by a zone you have claimed.

**Trust model**: tenants can't deploy their own functions — the operator deploys every function
that runs, including the ones that serve a particular client's solutions. Functions are the
operator's own trusted code, never a tenant's; a client-owned function is simply one that runs
*for* that tenant, not code the tenant supplied. That is why claiming a domain needs no further
proof of ownership (below): TXT verification exists to defend one tenant from claiming another's
domain, and with a single trusted operator claiming every domain, there is nobody to defend
against.

### Claiming a zone

A claim is a **zone**: claiming `acme.com` covers `acme.com` itself AND every hostname whose labels
end in `acme.com`'s (`myapp.acme.com`, `qa-myapp.acme.com`, …) — you never claim each hostname
under it separately. No two claims may nest (by any owner): claiming `api.acme.com` while
`acme.com` is already claimed (or vice versa) is refused, `DOMAIN_TAKEN`.

```
fcdev fn domain claim api.acme.com [--client <id>]     # --client omitted = platform-owned
fcdev fn domain list [--client <id>]
fcdev fn domain release api.acme.com
```

Claim it; that's all — then point the CNAME at the load balancer. A claim is verified by being
made: there is no TXT record to create, no `verify` call, and no pending state. Only a hostname
covered by a zone you own may appear in a `public[]` entry — publishing against an unclaimed
hostname, or one under another owner's zone, fails the same way for both
(`PUBLIC_HOSTNAME_NOT_CLAIMED`; the platform never tells you which of the two it was, so it can
never be used to discover who holds a zone).

`fcdev start` opens the public listener on `--fn-public-port` (default **8091**), so once you `fn
domain claim hello.localhost` and publish a function with `"public": [{"hostname":
"hello.localhost"}]`, `http://hello.localhost:8091/` reaches it immediately — the same claim/publish
flow as any other hostname, dev or production.

### Reaching the function

The public listener matches the request's `Host` header against your claimed hostnames, then the
LONGEST `pathPrefix` whose whole segments prefix the request path (`/billing` matches `/billing` and
`/billing/x`, never `/billingx`) — the matched prefix is stripped before your function sees the
path, so the same handler serves both entries: `https://api.acme.com/invoices/7` (prefix `/`) and
`/functions/billing.invoices.api/invoices/7` both arrive as `path = "/invoices/7"`. There is **no
by-address or versioned access on the public listener** — `/functions/...` is just an ordinary
(almost certainly unmatched) path there, never special-cased.

`request.remoteAddress()` is the right-most `X-Forwarded-For` entry when the TCP peer is a trusted
proxy (your load balancer; `FC_FN_TRUSTED_PROXIES`, default RFC 1918 + loopback), else the TCP peer
itself — a client-supplied `X-Forwarded-For` is never trusted directly, and `X-Forwarded-Host` is
never consulted for routing at all (only the load balancer picks the route, via the real `Host`).

### Alias prefixes

A `public[]` entry can opt into serving its **named aliases** (see "Aliases" below) at a
prefixed hostname — **opt-in per route**, `aliasPrefixes`, absent/empty by default (exact-hostname
match only, today's behaviour):

```json
{ "hostname": "myapp.acme.com", "pathPrefix": "/", "aliasPrefixes": ["qa", "staging"] }
```

With `qa` opted in, `qa-myapp.acme.com` reaches the version the `qa` alias currently points at —
without needing its own domain claim or its own `fn_routes` row; the derived hostname is never
stored, so there is nothing to conflict at publish time. Matching: the exact hostname always wins
first; only when there is no exact match, and the first label of the requested hostname contains a
`-`, is it split at the FIRST `-` into a prefix and the rest (`qa-myapp` → `qa`, `myapp`;
`qa-my-app` → `qa`, `my-app`) and looked up as a base hostname — one level of derivation only. Each
alias prefix is a DNS label, never `live` (that name is reserved for the exact-hostname match), no
duplicates within one route.

Aliases are **HTTP-only** — pointing a named alias at a version never touches subscriptions,
schedules or the pool (those always follow `live`). An alias-prefixed call runs the ALIASED
version's own manifest for endpoint matching, auth and body limits, exactly like the exact-hostname
entry runs the LIVE version's.

`*.localhost` works locally with no setup (`qa-hello.localhost:8091` reaches the `qa` alias of a
function published with `"public": [{"hostname": "hello.localhost", "aliasPrefixes": ["qa"]}]` once
`hello.localhost` is claimed and the `qa` alias is pointed at a `READY` version). In production the
load balancer needs a wildcard rule/certificate for the zone — see `docs/deployments.md`.

### CORS

Add `cors` to an endpoint (not the `public[]` entry — CORS is per-endpoint, and applies on BOTH
listeners) to let a browser call it cross-origin:

```json
{ "path": "/api/hello/{name}", "auth": "platform", "methods": ["GET"],
  "cors": { "origins": ["https://app.acme.com"], "methods": ["GET"], "headers": ["X-Request-Id"],
            "allowCredentials": false } }
```

- A preflight (`OPTIONS` + `Origin` + `Access-Control-Request-Method`) is answered by the **host**
  directly — your function is never invoked, no permit is taken, and **no auth runs** (a preflight
  carries no credentials, so this is true even for a `platform`-authed endpoint).
- On an actual request, the host sets `Access-Control-Allow-Origin`/`-Allow-Credentials`/`Vary` on
  whatever your function returns, **replacing** anything your function set for those headers itself
  — the host is the one authority for a `cors`-declaring endpoint.
- **CORS is not access control.** A disallowed `Origin` still reaches your function and runs
  normally — the browser is what refuses to hand the response to the calling page, because the
  response carries no CORS headers at all. If you need to reject a caller, do it with `auth` (or
  your own logic), never by relying on CORS to block anything.
- `origins` entries are exact origins (`scheme://host[:port]`, no path) or `*`; `*` together with
  `allowCredentials: true` is rejected at publish (`ENDPOINT_INVALID`) — browsers refuse that
  combination anyway, and it is the classic "reflect any origin with credentials" hole.

## 7. Events

Your function never holds an application credential. `ctx.events().emit(OutboundEvent)` emits
**through the host**, on your function's behalf, and **you may only emit event types your own
application owns** — an attempt to emit someone else's type is refused (`EVENT_TYPE_NOT_OWNED`),
not silently dropped.

```java
ctx.events().emit(new OutboundEvent(
        "hello:greeting:greeting:sent",                 // type — must be owned by your function's application
        "function:" + ctx.address().render(),  // source
        event.subject(),                       // subject, carried through from the inbound delivery
        "application/json",
        jsonBytes,
        event.correlationId(), event.id(), event.messageGroup(),
        UUID.randomUUID().toString()));         // dedupId — required
```

`emit` throws `EventEmitException` (unchecked) on any refusal — `.code()` is the platform's error
code, `.status()` its HTTP status (or 503 for a transport failure). **Branch on the status, not on
"catch and ignore"**: a 5xx means the platform itself had a problem — worth asking your caller to
retry (`Result.retry(...)`, see §8); anything else (most commonly `EVENT_TYPE_NOT_OWNED`) is a
genuine rejection retrying will never fix (`Result.fail(...)`). `examples/function-hello`'s
`HelloFunction#handleGreetingRequested` does exactly this.

## 8. Honest limits

Read this before you design around anything the API *looks* like it should let you control:

- **`Result.fail` does not stop retries.** The status/body it builds (`500`,
  `{"error": "<reason>"}`) is classified identically to *any other* non-2xx, non-429 status by both
  delivery paths — the platform's own attempt-count decides when to give up, never your response.
  There is **no way for a function to force an immediate, non-retryable failure.**
- **`Result.retry(Duration)` is honoured by subscription/direct-dispatch-job delivery only** — it
  maps to `429` + `Retry-After`, spends no retry budget there. **A scheduled-job delivery ignores
  the requested delay entirely**: `JobDispatcher.deliver` only branches on 2xx vs. not, so a `429`
  from a scheduled job is treated exactly like `Result.fail` and consumes one of its attempts.
  Quoting `Result`'s own class doc (the wire-level table, pinned against the actual processing code
  at `platform/dispatchjob/processing/{ProcessingApi,SubscriberDelivery}.java` and
  `platform/scheduler/jobs/JobDispatcher.java`):

  | Helper | Dispatch-job delivery | Scheduled-job delivery |
  |---|---|---|
  | `ack()` | any 2xx without `{"ack":false}` ⇒ delivered | any 2xx ⇒ delivered |
  | `retry(Duration)` | `429` ⇒ deferred to `now + Retry-After`s, **no budget spent** | **not honoured at all** — treated as `fail`, spends an attempt, requested delay discarded |
  | `fail(String)` | classified identically to any other non-2xx/non-429; the platform's own attempt count decides when to give up | same — no status-code distinction |

- **JVM functions share one process.** Every loaded function on a host is its own class loader
  inside the *same* JVM — that buys isolation of your classes and your dependencies (bundle your
  own, including your own copy of any library you need; the platform never shares one across
  functions except deliberately), but it means: **no native libraries** (a jar containing a
  `.so`/`.dll`/`.dylib`/`.jnilib` is refused outright, `NATIVE_LIBRARY`), **no security providers**
  (a `META-INF/services/java.security.Provider` registration is refused, `SECURITY_PROVIDER` — a
  second provider under one JVM-wide name would silently apply to every function on that host), and
  **JVM-wide defaults are shared** (default locale, default timezone, system properties, and so on)
  — don't mutate them from your function.
- **Capacity is a metaspace question, not a heap one, and it is a real, measured number, not a
  guess.** A function host fences `-XX:MaxMetaspaceSize` to a percentage of its container's memory
  limit (`FC_JVM_METASPACE_PERCENT`, default 50, `docs/spec/jvm-memory.md` §4) — because a loaded
  function IS a class loader, and many short-lived functions loading and unloading is exactly the
  workload that grows metaspace unbounded otherwise. Measured directly (`bench/function-host/`,
  `docs/function-runner-report.md`): a lean function (the API jar only, ~2 classes) costs almost
  nothing; a "typical" function (jackson-databind + a JSON-schema validator shaded in, ~700 classes)
  costs **≈4.4 MB of metaspace and ≈5.4–5.9 MB of RSS per loaded instance**, on top of a
  **≈120–135 MB one-time host baseline**. The rule of thumb this predicts (and the report confirms
  by direct measurement, within a few percent, at both 2 GiB and 4 GiB containers):
  `(metaspace_percent% × container_limit − ~34 MiB baseline − reserve) / 4.4 MB` ≈ how many
  "typical" functions one host can hold loaded at once, before a further load is refused
  (`LOAD:METASPACE_HEADROOM`) rather than crashing anything — the old version, if any, keeps
  serving.
- **Signatures are verified JDK-only.** The platform's `SignatureVerifier` deliberately does not
  depend on `sigstore-java` (a 2026-09-19 owner ruling — the alternative is a shaded gRPC-Netty,
  BouncyCastle, Guava and protobuf dependency chain, ~35 MB, for verification alone). Consequences
  that matter to you as a publisher: it verifies **Rekor v1 bundles only** (no signed entry
  timestamp ⇒ `UNSUPPORTED_BUNDLE` — pin your `cosign` version, see §9), and it performs **no SCT
  check** (the certificate's embedded Signed Certificate Timestamp against the CT log is not
  verified — the residual risk is a compromised Fulcio issuing an unlogged certificate; the signing
  event itself is still publicly logged in Rekor). Neither is a bug to work around in your own
  function — they're a property of how this platform verifies you, worth knowing before you assume
  parity with `cosign verify-blob`'s own, fuller checks.

## 8a. Wasm functions

A function may also be a WebAssembly module (`runtime: wasm`), run by the host on Endive (pure
Java) through the Extism ABI — write it with an Extism PDK (Rust, or JavaScript — §8b below).
Everything above about endpoints, auth modes, subscriptions, schedules, publishing and promoting
is the same; what differs is below. Spec: `docs/spec/function-wasm-runtime.md`.

**The export.** `entrypoint` names a function export of the module; it is called once per
invocation with the request as UTF-8 JSON —

```json
{"address": "app.svc.name", "version": 3, "invocationId": "…", "method": "POST", "path": "/orders/42",
 "originalHost": "…", "originalPath": "…", "pathParams": {"id": "42"},
 "query": {"k": ["v"]}, "headers": {"k": ["v"]}, "bodyBase64": "…", "remoteAddress": "…",
 "caller": {"kind": "principal", "id": "…", "type": "…", "tier": "…", "clients": [], "roles": [],
            "applications": [], "allApplications": false, "permissions": []}}
```

(`caller` is `{"kind":"platform"}` for a webhook delivery, `{"kind":"anonymous"}` for `auth: none`)
— and returns `{"status": 200, "headers": {"k": ["v"]}, "body": "text"}` (or `"bodyBase64"`
instead of `"body"`; both headers and body are optional). Returning Extism's error code, trapping,
or returning anything else is a `500` with the fixed body `{"error":"the function failed"}` — your
guest's own message goes to the host's log, not to the caller.

**What the guest can reach** — only what the module imports, and the host refuses to load a module
importing anything outside `extism:host/env`, `extism:host/user` and `wasi_snapshot_preview1`
(`LOAD:WASM_IMPORT_NOT_ALLOWED`): the PDK's own `log`, `config::get` and `http::request` (over the
host's allowlisted, deadline-capped caller — here the allowlist is enforced, not a convention; a
refused call answers status `0` with `{"error": …}`), the host functions in `extism:host/user` —
`fc_secret_get(key) → value | ""`, `fc_emit_event(json) → {"ok": …}` and the `fc_db_*` family
below — and WASI's clock, random and stdout/stderr (which land in your function's log at
INFO/WARN). No filesystem, no environment. Config and secrets answer only the keys your manifest
declares.

**Database access.** Each `manifest.db[]` entry is reachable by its `name` through five host
functions in `extism:host/user`, each taking one JSON string and returning one (spec
`docs/spec/function-wasm-db.md`). They use the same pools, sizes and limits a JVM function gets.

| Function | Input | Answer |
|---|---|---|
| `fc_db_query` | `{"db":"main","sql":"SELECT id, name FROM t WHERE id = ?","params":[42],"tx":"…"}` | `{"rows":[{"id":42,"name":"…"}],"truncated":false}` |
| `fc_db_execute` | same shape | `{"updated":1}` |
| `fc_db_begin` | `{"db":"main"}` | `{"tx":"<opaque id>"}` |
| `fc_db_commit` / `fc_db_rollback` | `{"tx":"<id>"}` | `{"ok":true}` |

- `params` are bound positionally to `?` — never pasted into the SQL — and must be strings,
  numbers, booleans or `null`. A string is sent untyped, so the server reads it as whatever the
  statement needs there (`'2026-09-24T10:15:30Z'` into a `timestamptz`, a UUID, JSON text into
  `jsonb`); integers go as `int8`, other numbers as exact `numeric`.
- Rows come back keyed by column label (alias duplicate labels apart — the last one wins):
  integers and floats as numbers (`NaN`/`Infinity` as strings), `numeric` as a string exactly as
  PostgreSQL prints it, `bool` as a boolean, timestamps and dates as ISO-8601 strings (`timestamptz`
  in UTC, `…Z`), `bytea` as base64, `json`/`jsonb` parsed, `NULL` as `null`, anything else
  (text, uuid, interval, arrays, …) as PostgreSQL's text form.
- Without `tx`, a statement borrows a connection in autocommit and returns it before answering.
  With `tx`, it runs in that transaction. A `tx` is valid only in the call that opened it and only
  with the `db` it was opened on; when the call ends — normally, by error, trap or deadline — every
  transaction still open is **rolled back** and its connection returned. Commit what you mean to keep.
- Every statement's timeout is the time left before the invocation deadline (to the millisecond);
  with none left it is not sent.
- A query answers at most 10 000 rows or 8 MiB of row JSON, whichever comes first, and says
  `"truncated":true` when it stopped early. `fc_db_execute` is for statements that return no rows —
  one that does (a `SELECT`, `… RETURNING`) runs and then answers `DB_ERROR`; use `fc_db_query`.
- Every failure is `{"error":{"code","message"}}`, never a trap: `DB_NOT_DECLARED`,
  `DB_BAD_REQUEST` (input not of this shape), `DB_TX_UNKNOWN`, and from the SQLSTATE class
  `DB_CONSTRAINT` (23), `DB_SYNTAX` (42), `DB_TIMEOUT` (57014, or no time left),
  `DB_UNAVAILABLE` (08, other 57, 53) and `DB_ERROR` (the rest). The message is the driver's; the
  host never logs your SQL or its parameters.

**Limits.** `limits.wasmMemoryMb` (default 64) caps each instance's linear memory: a module that
*declares* more than that as its minimum is refused at load (`LOAD:WASM_MEMORY_OVER_CAP`); an
allocation past it at run time is a clean `500`. The endpoint's `timeoutMs` stops a running guest
at once (`504`). Up to `limits.maxConcurrency` calls run in parallel, each on its own instance (an
instance is never shared between concurrent calls); instances are created on demand and one whose
call failed is thrown away, so a failure never leaks state into the next call — but a *successful*
call's globals and memory do persist into later calls on the same instance, so don't rely on either
fresh or shared state between calls. The module is compiled once per version when it loads (≈0.2 s
and ≈4–6 MB of metaspace — counted by the same metaspace guard as JVM functions).

## 8b. JavaScript functions

JavaScript runs **through Wasm** (`runtime: wasm`, same as §8a) — QuickJS via the Extism JS PDK,
compiled ahead of time by `extism-js`. There is no separate "JS runtime"; the host sees an ordinary
Wasm module and treats it exactly as §8a describes (the same invocation JSON, the same host
functions, the same limits). Spec: `docs/spec/function-js-guest.md`.

**Scaffold.** `fcdev fn init --lang js <dir>` writes a starter project: `package.json` (depending
on `@flowcatalyst/function` via `file:lib/flowcatalyst-function` — the library ships with the
scaffold, the same reasoning as the JVM function API; it is not on npm), `tsconfig.json`,
`src/index.ts`, `manifest.json` (`runtime: wasm`, `entrypoint: handle`) and a README with the build
steps. `examples/function-hello-js` is the worked example, the JS twin of `examples/function-hello`.

**Toolchain.** Node 18+ / npm; [`extism-js`](https://github.com/extism/js-pdk) 1.6.x on `PATH`; and
[Binaryen](https://github.com/WebAssembly/binaryen)'s `wasm-opt` and `wasm-merge` on `PATH`
(`brew install binaryen`). `fcdev` never drives npm — `fn build` is not a thing for JS; the
scaffold's own `npm run build` is the build:

```bash
npm install
npm run build   # esbuild src/index.ts --bundle --format=cjs --target=es2020 --outfile=dist/index.js
                 # extism-js dist/index.js -i node_modules/@flowcatalyst/function/interface.d.ts -o dist/function.wasm
fcdev fn publish dist/function.wasm --manifest manifest.json
```

**Writing the handler** — `@flowcatalyst/function` mirrors `function-api`'s shapes under the same
names: `FunctionRequest` (with `body()`/`text()`/`json<T>()` helpers over the wire's base64 body),
`Caller` (`{kind:"platform"|"anonymous"|"principal"}` — a `PrincipalCaller` has the same
`hasPermission`/`hasAnyPermission`/`hasAllPermissions`/`hasRole`/`isAnchor`/`canAccessClient`/
`canAccessApplication`/`clientId` helpers as Java's `Caller.Principal`, tested against the same
wildcard-match cases), `FunctionResult` built through `Result.ok()/.json()/.text()/.status()/
.fail()/.retry(seconds)`, and a `Context` (`config`, `secrets`, `http`, `events`, `logger`, `now()`)
your handler reads through:

```ts
import { handler, Result } from "@flowcatalyst/function";

export const handle = handler((req, ctx) => {
	if (req.path === "/healthz") {
		return Result.json(200, { status: "ok" });
	}
	const greeting = ctx.config.get("GREETING") ?? "Hello";
	return Result.json(200, { message: `${greeting}, ${req.pathParams.name ?? "world"}!` });
});
```

`ctx.http.request(...)` calls through Extism's own `Http.request` (§8a's allowlist and deadline
apply identically); a host outside `manifest.httpAllow`, or an unreachable one, throws `HttpDenied`
rather than handing back a reply you have to remember to check the status of. `handler(...)` turns
any uncaught exception into `Result.fail(message)` — a clean `500`, never a trapped instance (§8a:
a bug in your own code must never take down every future call on that instance).

**What works, what doesn't.** QuickJS is an interpreter inside the Wasm sandbox: no Node built-ins
(no `fs`, `path`, `net`, `child_process`; `Buffer` is polyfilled), no event loop (no `setTimeout` —
`async`/`await` works only over values Extism's own synchronous calls already resolved), and an npm
package works only once bundled into your single output file by `esbuild` — packages depending on
Node built-ins or the DOM will not bundle cleanly. Steady-state call overhead is on the order of
**~0.3 ms** — fine behind an HTTP endpoint for glue code, webhook handlers and thin API calls; not
for heavy compute, which belongs in a Rust guest (§8c) or a JVM function instead.

## 8c. Rust functions

Rust compiles straight to Wasm (`runtime: wasm`, same as §8a) with the Extism Rust PDK
(`extism-pdk`) — no interpreter in the loop, unlike §8b's JS-via-QuickJS. Spec:
`docs/spec/function-rust-guest.md`.

**Scaffold.** `fcdev fn init --lang rust <dir>` writes a starter project: `Cargo.toml` (depending
on `flowcatalyst-function` via `path = "lib/flowcatalyst-function"` — the crate ships with the
scaffold, the same reasoning as `@flowcatalyst/function` and the JVM function API; it is not on
crates.io), `src/lib.rs`, `manifest.json` (`runtime: wasm`, `entrypoint: handle`) and a README with
the build steps. `examples/function-hello-rust` is the worked example, the Rust twin of
`examples/function-hello`.

**Toolchain.** Rust (`rustup`) with the `wasm32-unknown-unknown` target
(`rustup target add wasm32-unknown-unknown`); `cargo`. No separate compiler step like `extism-js` —
`cargo build` produces the Wasm module directly:

```bash
rustup target add wasm32-unknown-unknown
cargo build --release --target wasm32-unknown-unknown
fcdev fn publish target/wasm32-unknown-unknown/release/<crate>.wasm --manifest manifest.json
```

**Writing the handler** — `flowcatalyst-function` mirrors `function-api`'s and
`@flowcatalyst/function`'s shapes under the same names, adapted to Rust idiom: `FunctionRequest`
(with `body()`/`text()`/`json::<T>()` helpers over the wire's base64 body), a `Caller` enum
(`Platform | Anonymous | Principal(PrincipalCaller)` — `PrincipalCaller` has the same
`has_permission`/`has_any_permission`/`has_all_permissions`/`has_role`/`is_anchor`/
`can_access_client`/`can_access_application`/`client_id` helpers as Java's `Caller.Principal`,
tested against the same wildcard-match cases), `FunctionResult` built through
`FunctionResult::ok()/::json()/::text()/::status()/::fail()/::retry(seconds)`, and a `Context`
(`config`, `secrets`, `http`, `events`, `db(name)`, `logger`, `now()`) your handler reads through:

```rust
use extism_pdk::plugin_fn;
use flowcatalyst_function::{handler, FunctionResult};

#[plugin_fn]
pub fn handle(_input: String) -> extism_pdk::FnResult<String> {
    handler(|req, ctx| -> Result<FunctionResult, String> {
        if req.path == "/healthz" {
            return FunctionResult::json(200, &serde_json::json!({"status": "ok"}))
                .map_err(|e| e.to_string());
        }
        let greeting = ctx.config.get("GREETING").unwrap_or_else(|| "Hello".to_string());
        FunctionResult::json(200, &serde_json::json!({"message": greeting}))
            .map_err(|e| e.to_string())
    })
}
```

**One deliberate difference from JS.** `@flowcatalyst/function`'s builders throw, caught by its
`handler()` — the instance survives. On `wasm32-unknown-unknown`, a Rust panic cannot be caught:
`catch_unwind` compiles but does not actually intercept a panic on this target (verified against
the toolchain), so panicking would trap the whole instance for what should be a one-call mistake —
worse than JS, not equivalent to it. So `flowcatalyst-function`'s handler closure returns
`Result<FunctionResult, E>` instead of returning `FunctionResult` directly and throwing on trouble:
an `Err` (or the `?` of a builder like `FunctionResult::fail(...)`, which itself returns `Result`
rather than panicking on bad input) becomes the fixed reason "the function failed" — a controlled
`500`, logged with the real detail, never leaking it into the response body — exactly section 8a's
rule, just reached through `Result` instead of a caught exception. A genuine Rust panic
(`unwrap()` on `None`, an index out of bounds, ...) still traps the instance —
`flowcatalyst-function` cannot protect against that the way the JS library can; write fallible
operations to return `Result` and propagate with `?` rather than `.unwrap()`.

`ctx.http.request(...)` calls through Extism's own `http::request` (section 8a's allowlist and
deadline apply identically); a host outside `manifest.httpAllow`, or an unreachable one, is
`Err(HttpDenied)` rather than a reply you have to remember to check the status of. `ctx.db(name)`
wraps the five `fc_db_*` host functions section 8a describes: `.query(sql, params)` /
`.execute(sql, params)` for an autocommit statement, `.transaction(|tx| { ... })` to borrow one
connection for a closure — commits on `Ok`, rolls back on `Err` (the invocation's own end-of-call
cleanup still force-releases it either way).

**What works, what doesn't.** No heap-allocating standard library gaps that matter for typical glue
code — this is real, compiled Rust, not an interpreter — but `wasm32-unknown-unknown`'s `std` has no
OS clock or filesystem of its own; `flowcatalyst-function` reads the host's WASI clock for `now()`
rather than `std::time::SystemTime`, and there is no `std::fs` to reach for regardless (the guest
has no filesystem, section 8a). Keep the crate's own dependency tree small: everything compiles into
the one Wasm module, and `[profile.release]` with `opt-level = "z"`, `lto = true`,
`panic = "abort"` (the example's and the scaffold's default) keeps it small. Good for compute the
interpreter-based JS guest is not (section 8b), with the panic caveat above the one thing to design
around.

## 9. Building, shrinking and testing (the pipeline)

A JVM function's jar is shaded (your dependencies bundled in, **never** `function-api` — it's
`provided`; the host refuses any jar that bundles it, `BUNDLES_API`) and then shrunk. **The default
shrinker is `maven-shade-plugin`'s own `minimizeJar`** (ORCHESTRATOR RULING 2026-09-20): the
alternative, ProGuard, can only read a JDK's own classes from `.jmod` files, and recent Temurin
builds (JEP 493, "Linking Run-Time Images without JMODs" — this includes Temurin 25, what this guide
assumes you build with) ship no `jmods/` directory at all, so ProGuard cannot run on the same JDK a
function author is expected to have. `minimizeJar` needs no JDK class files — it works from the
dependency jars' own bytecode — and runs anywhere. It only removes classes from your *dependency*
jars that nothing reachable from your own compiled classes references; it never touches your own
classes, so (unlike ProGuard) it needs no keep rule for your manifest entrypoint or any class the
host calls through the `Function` interface. It can still miss something Jackson (or another
library) only finds via `ServiceLoader`/`META-INF/services` or other pure reflection inside *that
dependency* — if your shrunk jar refuses to load or a class goes missing at runtime, add a
shade `<filter>`/`<includes>` for that dependency (`examples/function-hello/pom.xml`'s own
`shade-dependencies` execution is the worked example; work out the minimal set by running your test
against the shrunk jar, not by guessing wide).

ProGuard, shrink-only (`-dontobfuscate -dontoptimize` — Jackson and most JSON libraries read Java
member names directly, and stack traces should stay readable), stays available as the **opt-in**
`shrink-proguard` Maven profile (`examples/function-hello/pom.xml`), sharing `proguard.conf` — use it
only on a JDK that still ships `jmods/` (GraalVM 25, or a Temurin ≤ 23); it cannot be verified by
Maven on a JDK without one (that is the state of every JDK this guide was written against — it was
last verified directly via the ProGuard CLI on GraalVM 25: a clean shrink, but the entrypoint needed
a `-keep class … { *; }` rule because the host calls it through the `Function` interface, a LIBRARY
type from ProGuard's point of view once `function-api` is `-libraryjars` — ProGuard's reachability
analysis cannot see across a library-into-program call it does not itself drive; `minimizeJar` has no
such problem since it never touches your own classes at all).

**Either way, the gain is artifact size** — pull/digest/signature time on a lazy function's first
load, and less for the publish pipeline to scan — not metaspace (§8's numbers are unaffected by
shrinking). A missing keep rule/include filter fails at *runtime*, not at build time, which is
exactly why the tests must run against the **shrunk** jar, not the pre-shrink one —
`examples/function-hello/pom.xml`'s own build binds its one test class to the `verify` phase (after
`package`, so after shading/shrinking have run) for this reason.

```sh
make examples
#   = mvn -q -B -Pexamples -pl examples/function-hello -am verify
```

Publishing for real (spec `function-artifact-upload.md` §5, R14): the jar is **uploaded through the
platform** by default — no registry of your own required, one path for local dev and a deployed
platform alike. Signing is unaffected; it is still the jar's own bytes that get signed, before the
upload:

```
build → shade → ProGuard shrink → tests run against the SHRUNK jar
      → cosign sign-blob --new-bundle-format --bundle fn.sigstore.json   (keyless; pin cosign — §8)
      → fcdev fn deploy target/my-function-shrunk.jar --manifest manifest.json --bundle fn.sigstore.json --wait 120s
```

`fn deploy`/`fn publish` upload the jar (`PUT /api/functions/{address}/artifacts/{digest}`, your
service account's own credentials — nobody but the platform holds storage credentials) and publish
the `platform://…` ref the upload returns; the platform writes it to whichever backend
`FC_FN_ARTIFACT_STORE` names (`docs/deployments.md` §"Function artifacts" has the owner-facing
side of that variable). A team that wants to publish by reference against its own registry instead
opts in with `--artifact-ref oci://…` (`s3://` is a backend here, not a reference scheme a
developer points at) — `--bundle` still carries the same signature either way:

```
      → oras push (OCI registry)
      → fcdev fn publish --artifact-ref oci://… --bundle fn.sigstore.json
      → fcdev fn promote --wait 120s
```

`examples/function-hello/.github/workflows/publish.yml` is a worked, commented TEMPLATE of exactly
this — copy it into your own function's repository (it is *not* part of this repo's own CI).

### Smoke-testing a published-but-not-yet-live version

```sh
fcdev fn publish target/my-function-shrunk.jar acme.default.hello --manifest manifest.json
fcdev fn invoke acme.default.hello:7 --path /api/hello/ada
#                              ^^^^ the version — for people, never generated by the platform
fcdev fn promote acme.default.hello --version 7 --wait 60s
```

The versioned form (`:7`) is for people: it needs a platform bearer token holding
`platform:function:version:invoke`, serves only a version that is currently live or the newest
published candidate, and lets you exercise even a `webhook` endpoint without knowing the signing
secret (`fn invoke --webhook --signing-secret <secret>` signs the request as the platform would, for
when you *do* want to test the real signature path). The platform itself **never** writes a
versioned URL into a subscription, scheduled job or route — every delivery always targets the
`live` alias, so promoting or rolling back never has to rewrite anything downstream.

### Aliases

An alias is a named pointer from a function to one of its `READY` versions — `live` is the one every
subscription, scheduled job, pool and public route the manifest declares actually follows; any other
name (`qa`, `staging`, …) is **HTTP-only**: pointing it at a version, or moving it, changes nothing
downstream — no wiring is created, updated or removed. It exists so a caller who already knows how to
address a function by name (`fn invoke acme.default.hello`, a versioned webhook path, an
alias-prefixed hostname — see "Alias prefixes" under §6a) can reach a specific candidate without
touching `live`.

```sh
fcdev fn promote acme.default.hello --version 7 --alias qa --wait 60s
fcdev fn alias list acme.default.hello
fcdev fn alias delete acme.default.hello qa
```

Rules: any name matching `^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$` (1–63 characters, `a-z`/`0-9`/`-`,
never starting or ending with `-`) works, `--alias` defaults to `live` when omitted. The target must
be `READY` — the same rule `live` gets — not `RETIRED`, and the function must not be `DISABLED`.
Pointing an alias at the version it already names is a no-op error (`ALIAS_UNCHANGED`); two
*different* aliases may legitimately point at the same version at once. `live` cannot be deleted
(`fn alias delete … live` fails with `ALIAS_PROTECTED` — promote another version instead); retiring a
version a named alias still points at is refused too, naming the alias, until it is moved or removed.

### `fcdev fn` reference

Global options (every subcommand accepts these): `--platform-url`, `--client-id`/`--client-secret`
(env `FLOWCATALYST_CLIENT_ID`/`_SECRET`; default: `fn-cli.json`), `--output text|json`. An address is
either a full `app.service.name`, or `--app`/`--service` (default `default`)/`--name` — mixing the
two forms, or a two-part address, is a usage error (exit 2).

| Command | Does |
|---|---|
| `fn publish <jar> [<address>] --manifest <file>` | sha256's the jar; **uploads it through the platform** (`PUT .../artifacts/{digest}`) and publishes the returned `platform://…` ref — the default, local dev and a deployed platform alike; `--artifact-ref oci://…` + `--bundle` publishes by reference instead (opt-in, for a team running its own registry). Creates the function on first publish unless `--no-create` |
| `fn promote <address> --version <n> [--alias <name>] [--wait 60s]` | polls for `READY`, then promotes; `--alias` defaults to `live` (any other name is HTTP-only, no wiring change); `--wait 0` promotes immediately |
| `fn deploy <jar> [<address>] --manifest <file> [--wait 60s]` | publish + promote (always `live`) in one step — what `watch` runs each cycle |
| `fn status [<address>]` | versions, every alias, hosts (with per-host loaded state/error), wiring |
| `fn versions [<address>]`, `fn retire [<address>] --version <n>` | list / retire a version (refuses the live one, or one a named alias still points at) |
| `fn alias list [<address>]`, `fn alias delete [<address>] <alias>` | list every alias; delete a named one (`live` refuses with `ALIAS_PROTECTED`) |
| `fn config get\|set [<address>] [KEY=VALUE…] [--manifest <file>] [--client <id>] [--no-create]` | `set` is read-modify-write of the whole map. Config and secrets belong to a **function**, so `set` creates it first when its address 404s — the way `fn publish` does, from `--manifest` (default: `manifest.json` in the working directory, when present) — so `set` → `deploy` now works with no `fn publish` first; `--no-create` fails instead (exit 1), and a 404 with no manifest to create from fails naming `--manifest`. `get` never creates. Promote still refuses with `SETTINGS_MISSING` until every declared key has a value |
| `fn secret set [<address>] <KEY> [--from-file <file>] [--manifest <file>] [--client <id>] [--no-create]`, `fn secret list\|delete` | the value is **never** a CLI argument — stdin (no echo at a TTY) or `--from-file` only. The CLI treats the value as a secret-manager reference (`aws-sm://`, `aws-ps://`, `gcp-sm://`, `vault://`, `env://`) unless prefixed **`encrypt:`**, which stores the plaintext encrypted at rest (`INVALID_SECRET_REF` otherwise); the prefix is stripped and the function receives the plain value. The admin UI and the raw `PUT …/secrets/{key}` take the plain value with no prefix — `encrypt:` is the CLI's convention only. `set` creates the function on a 404 for its address, same as `fn config set`; `list` never creates |
| `fn invoke <address>[:<version>] [--path /x] [--method POST] [--body <file>\|-] [-H k:v…] [--host-url] [--webhook --signing-secret <secret>]` | calls the function **host** directly, never the platform |
| `fn watch <dir> [<address>] [--jar <glob>] [--manifest manifest.json]` | debounced (500 ms) file watch; every change runs a deploy cycle; a failing cycle prints its error and the watch keeps going |
| `fn domain claim <hostname> [--client <id>]` | claims a zone — covers every hostname under it (§6a); immediately usable, no DNS verification step |
| `fn domain list [--client <id>]` | lists claimed zones (`--client` omitted = platform-owned) |
| `fn domain release <hostname>` | releases a zone — refused while any `public[]` route on the zone or a hostname under it still uses it |

## 10. What the sample proves, end to end

`examples/function-hello`'s `ShrunkJarTest` loads the real, shrunk jar through the REAL
`io.flowcatalyst.fnhost.load.JvmFunctionLoader` — the same class the production host uses — and:

- parses `manifest.json` with the real `Manifest.parseStrict`;
- confirms `function-api`'s own classes are absent from the shrunk jar (the host would otherwise
  refuse it, `BUNDLES_API`) and that the shaded Jackson dependency is present;
- invokes all three endpoints and asserts their responses, including that the emitted event carries
  the inbound delivery's correlation id;
- asserts a secret's value is in **no** captured log line, only its presence;
- asserts `EventEmitException` with a 5xx status maps to `Result.retry` and anything else maps to
  `Result.fail` — two mutants, two conditions, not one test covering both by accident.

## 11. The platform API surface

Everything above talks about the function itself; the platform routes that create, publish, promote,
configure and observe it — `/api/functions…`, `/api/function-pools`, `/api/function-policies…`,
`/api/function-domains…`, `/api/function-routes`, and the function-host control plane under
`/control/functions/…` — are hand-documented separately, since there is no Go implementation to
generate a lockfile entry from: `GET /api/openapi-functions.json` serves that OpenAPI 3.1 document,
unauthenticated, alongside the platform's main `/api/openapi.json`.

## 12. From the admin UI

Everything above is the CLI/API path. The admin SPA (`docs/spec/function-ui.md`, packages H1–H4,
merged) covers most of the same ground with a browser instead:

- **List** — `/functions`: address, owner, runtime, live version, status; filters by application,
  client (anchor only) and status. A **Function Pools** card underneath (`GET /api/function-pools`)
  shows each pool's host count, read-only. A **New Function** button (gated on
  `platform:function:function:manage`, hidden rather than disabled otherwise) opens the create
  drawer at `/functions/new`.
- **Create drawer** — application code, service and name (the three address labels, with the
  resulting `app.service.name` address previewed live as they're typed), runtime (`jvm` or `wasm`,
  with a hint on what a Wasm function is — an Extism PDK module, JavaScript via `fcdev fn init
  --lang js`), description, and for an anchor an owner client (platform-owned by default, matching
  `CreateFunction.java`'s own rule: a client-scoped caller's function is always their own client's,
  sent automatically and never a choice in this form). Platform error codes (`APPLICATION_CODE_NOT_ADDRESSABLE`,
  `FUNCTION_EXISTS`, `Application_NOT_FOUND`, field `details`) surface verbatim in the form; on
  success the drawer closes and opens the new function's own detail drawer.
- **Publish drawer** — open a function's detail page (`/functions/{address}`) → **Versions** →
  **Publish Version**: pick the artifact (a `.jar` once `manifest.json`'s `runtime` is `jvm`, a
  `.wasm` module once it is `wasm` — the input's label and accepted extension switch with the
  loaded manifest) and `manifest.json` (a Sigstore bundle is optional), submit. The drawer sha256s
  the artifact in the browser, uploads it (`PUT …/artifacts/{digest}`), then publishes with
  the ref the upload returned — never a locally-built one. Errors from the platform (`DIGEST_MISMATCH`,
  `MANIFEST_INVALID` and its field detail, `SIGNATURE_*`, `ARTIFACT_STORE_NOT_CONFIGURED`) surface
  verbatim in the drawer.
- **Promote** — the Versions tab's row action, enabled once a version reaches `READY` (a host has
  proven it loadable); confirms before applying the manifest.
- **Config & secrets** — the detail page's **Config & Secrets** tab: config values are inline-editable;
  secrets are set/replaced through a one-time input and the tab never displays a stored value again,
  only "set"/"not set". Declared keys are the UNION of the live manifest's declared keys and every
  non-retired version's own manifest (fetched the same way the Versions tab does, on this tab's
  load rather than on row expand) — each key is tagged with where it comes from ("live", "v2
  (ready)", …), and a key with a stored value that no manifest declares reads "not declared". An
  **Add key** row on each table sets a key directly, by name, even before any manifest has been
  loaded. The SETTINGS_MISSING banner is computed from the union too — specifically from the
  candidate version promote will actually check (the newest non-retired `PUBLISHED`/`READY`
  version), not only the live one — so it warns correctly even before a function's first promote.
  This closes what used to be a real gap: before this, the tab's rows came only from
  `GET …/config`/`…/secrets`, whose `declared` list is the *live* manifest's — empty until the
  first promote — while promote's own missing-settings check looks at the version being promoted,
  not the live one, so the one time a key most needed setting (before a function's first promote)
  was the one time the tab offered no way to set it. The fix here is client-side only; the server
  could usefully expose the candidate's declared keys directly (`GET …/config`/`…/secrets` taking
  an explicit version, or a dedicated field) instead of the SPA reconstructing them from
  `listVersions` + per-version `getVersion` calls.
- **Domains** — `/function-domains`: **Claim Domain** takes a hostname (+ client, for an anchor) — a
  claim covers every hostname under it, verified once for the whole zone; a hostname ending in
  `.localhost` auto-verifies immediately, no DNS record needed (dev mode). The detail drawer shows
  the TXT record to create for anything else, a **Verify** button, and **Release**.

## Sources

- §1, §5, §6 — `docs/spec/function-invocation.md` §1–§4, §6
- §3 — `docs/spec/function-context.md` §2–§3; `function-api/src/main/java/io/flowcatalyst/function/`
  (`Function`, `FunctionContext`, `Request`, `Caller`, `Webhook`, `Event`, `Schedule` — read
  directly, not paraphrased from memory)
- §4 — `server/src/main/java/io/flowcatalyst/platform/function/Manifest.java`;
  `docs/spec/function-invocation.md` §3
- §6a — `docs/spec/function-public-routes.md` §1, §3, §4, §5;
  `function-host/src/main/java/io/flowcatalyst/fnhost/http/{FnHttpServer,CorsPolicy}.java`,
  `function-host/src/main/java/io/flowcatalyst/fnhost/route/{PublicRouteTable,TrustedProxies}.java`;
  `fcdev/src/main/java/io/flowcatalyst/fcdev/fn/DomainCommand.java`
- §7 — `function-api/src/main/java/io/flowcatalyst/function/{Events,OutboundEvent,EventEmitException}.java`;
  `docs/spec/function-context.md` §3
- §8 — `Result`'s own class doc (`function-api/src/main/java/io/flowcatalyst/function/Result.java`);
  `function-host/src/main/java/io/flowcatalyst/fnhost/load/{JvmFunctionLoader,Reason}.java`;
  `docs/spec/jvm-memory.md` §4; `docs/function-runner-report.md` ("Metaspace at 50%" section, the
  measured 4.4 MB/function and ≈34 MB baseline numbers); `docs/spec/function-artifacts.md` §4
- §8a — `docs/spec/function-wasm-runtime.md` §3–§4;
  `function-host/src/main/java/io/flowcatalyst/fnhost/wasm/{WasmAbi,HostFunctions,WasmFunction}.java`
- §8b — `docs/spec/function-js-guest.md`; `clients/function-js/` (read directly — `types.ts`,
  `context.ts`, `handler.ts`); `examples/function-hello-js/`; `fcdev/src/main/java/io/flowcatalyst/fcdev/fn/InitCommand.java`
  (`--lang`); `function-host/src/test/java/io/flowcatalyst/fnhost/http/WasmFunctionHelloJsTest.java`
  (the committed module through the real listener)
- §9 — `docs/function-runner-plan.md` §8 ("Shrinking"); `docs/spec/function-developer-surface.md` §3;
  `examples/function-hello/pom.xml`
- §10 — `docs/spec/function-developer-surface.md` §4, row E9
- fcdev fn reference — `fcdev/src/main/java/io/flowcatalyst/fcdev/fn/*.java` (read directly, picocli
  annotations and class docs); `docs/spec/function-developer-surface.md` §2
