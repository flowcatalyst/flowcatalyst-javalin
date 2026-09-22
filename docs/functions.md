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
| `httpAllow` | no | outbound hosts `ctx.http()` may call (exact host, or `*.suffix` for subdomains) |

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
listener** instead, at a hostname you have claimed and verified.

### Claiming and verifying a hostname

```
fcdev fn domain claim api.acme.com [--client <id>]     # --client omitted = platform-owned
fcdev fn domain verify api.acme.com
fcdev fn domain list [--client <id>]
fcdev fn domain release api.acme.com
```

`claim` prints a DNS record to create:

```
create this DNS record to verify ownership:
  type:  TXT
  name:  _flowcatalyst.api.acme.com
  value: fc-verify=<token>
```

Create it with your DNS provider, then `fn domain verify api.acme.com`. Only a domain you own AND
have verified may appear in a `public[]` entry — publishing against an unclaimed, still-pending, or
someone-else's-verified hostname fails the same way for all three (`PUBLIC_HOSTNAME_NOT_VERIFIED`;
the platform never tells you which of the three it was, so it can never be used to discover who
holds a hostname).

**Local development**: any hostname whose last label is exactly `localhost` (e.g.
`hello.localhost`) auto-verifies the instant you claim it — no DNS record, no `verify` call — because
`.localhost` always resolves to loopback (RFC 6761) and `fcdev` runs in dev mode. `fcdev start`
opens the public listener on `--fn-public-port` (default **8091**), so once you `fn domain claim
hello.localhost` and publish a function with `"public": [{"hostname": "hello.localhost"}]`,
`http://hello.localhost:8091/` reaches it immediately.

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

### `fcdev fn` reference

Global options (every subcommand accepts these): `--platform-url`, `--client-id`/`--client-secret`
(env `FLOWCATALYST_CLIENT_ID`/`_SECRET`; default: `fn-cli.json`), `--output text|json`. An address is
either a full `app.service.name`, or `--app`/`--service` (default `default`)/`--name` — mixing the
two forms, or a two-part address, is a usage error (exit 2).

| Command | Does |
|---|---|
| `fn publish <jar> [<address>] --manifest <file>` | sha256's the jar; **uploads it through the platform** (`PUT .../artifacts/{digest}`) and publishes the returned `platform://…` ref — the default, local dev and a deployed platform alike; `--artifact-ref oci://…` + `--bundle` publishes by reference instead (opt-in, for a team running its own registry). Creates the function on first publish unless `--no-create` |
| `fn promote <address> --version <n> [--wait 60s]` | polls for `READY`, then promotes; `--wait 0` promotes immediately |
| `fn deploy <jar> [<address>] --manifest <file> [--wait 60s]` | publish + promote in one step — what `watch` runs each cycle |
| `fn status [<address>]` | versions, live alias, hosts (with per-host loaded state/error), wiring |
| `fn versions [<address>]`, `fn retire [<address>] --version <n>` | list / retire a version (refuses the live one) |
| `fn config get\|set [<address>] [KEY=VALUE…]` | `set` is read-modify-write of the whole map. Config and secrets belong to a **function**, so it must exist: `fn publish` first (creates it, no promote), set the values, then `fn deploy` — promote refuses with `SETTINGS_MISSING` until every declared key has a value |
| `fn secret set [<address>] <KEY> [--from-file <file>]`, `fn secret list\|delete` | the value is **never** a CLI argument — stdin (no echo at a TTY) or `--from-file` only. The value is a secret-manager reference (`aws-sm://`, `aws-ps://`, `gcp-sm://`, `vault://`, `env://`) unless prefixed **`encrypt:`**, which stores the plaintext encrypted at rest (`INVALID_SECRET_REF` otherwise); the prefix is stripped and the function receives the plain value |
| `fn invoke <address>[:<version>] [--path /x] [--method POST] [--body <file>\|-] [-H k:v…] [--host-url] [--webhook --signing-secret <secret>]` | calls the function **host** directly, never the platform |
| `fn watch <dir> [<address>] [--jar <glob>] [--manifest manifest.json]` | debounced (500 ms) file watch; every change runs a deploy cycle; a failing cycle prints its error and the watch keeps going |
| `fn domain claim <hostname> [--client <id>]` | claims a hostname (§6a); prints the TXT record to create, or nothing further if it auto-verified (`.localhost` under dev mode) |
| `fn domain verify <hostname>` | resolves the TXT record and marks the domain `VERIFIED` |
| `fn domain list [--client <id>]` | lists claimed hostnames and their verification state (`--client` omitted = platform-owned) |
| `fn domain release <hostname>` | releases a hostname — refused while any `public[]` route still uses it |

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
- §9 — `docs/function-runner-plan.md` §8 ("Shrinking"); `docs/spec/function-developer-surface.md` §3;
  `examples/function-hello/pom.xml`
- §10 — `docs/spec/function-developer-surface.md` §4, row E9
- fcdev fn reference — `fcdev/src/main/java/io/flowcatalyst/fcdev/fn/*.java` (read directly, picocli
  annotations and class docs); `docs/spec/function-developer-surface.md` §2
