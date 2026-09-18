# Plan: FlowCatalyst Function Runner (Vert.x host, platform-owned registry, wasm via Chicory)

Status: agreed design (owner decisions 2026-09-13 recorded in §10; amended 2026-09-18 — function
addresses, the HTTP gateway, class-loader isolation, fcdev hosting: §3.1, §4a, §5, §8, §10 items 11–16).

## 1. What it is

A "lambda for the platform": a function is a small, signed, versioned artifact (a jar, or a wasm
module) that the platform knows about, that a pool of long-lived Vert.x hosts loads on demand, and
that the platform's existing router invokes exactly as it invokes any webhook target. Publishing a
function is a pipeline step; making it live is an alias change; retiring it is a version state.

Three principles, each argued in the sections that follow:

1. **The platform owns the registry.** Functions are a platform aggregate next to applications,
   with the same auth, audit, tenancy and OpenAPI treatment. Hosts are stateless workers that
   reconcile from it. "Known and versioned" falls out of this rather than being bolted on.
2. **The router delivers.** No new invocation machinery. A function's trigger is a subscription;
   its host pool is a dispatch pool; delivery, retry, FIFO groups, circuit breaking, rate limits
   and backpressure are the ones already tested in `router/`.
3. **Only our CI publishes.** Every version is signed by the organisation's pipeline identity and
   the platform refuses anything else. JVM functions in a shared host are therefore the primary
   runtime: warm, cheap, no isolation needed. The wasm runtime (§6) is kept for language choice
   (Rust/JS/Go functions from the same CI) and for fault containment of buggy code, not for
   untrusted code; it is phase 3 and optional.

## 2. Components

```
                 ┌────────────────────────── platform (fc-server) ───────────────────────────┐
 pipeline ──►    │ Function aggregate: functions, versions (immutable, by digest), aliases,   │
 publish API     │ signer policy, triggers → subscriptions + dispatch pools, audit, events    │
                 └───────────────┬──────────────────────────────┬────────────────────────────┘
                                 │ desired state (poll + event)  │ deliveries (existing router)
                                 ▼                              ▼
                 ┌──────────── function host pool (N Vert.x processes, one pool per placement) ─┐
                 │ reconcile loop → artifact store (OCI/S3/file) → verify cosign → load          │
                 │ JVM runtime: URLClassLoader per version, filtered parent exposing the API only │
                 │ wasm runtime: Chicory + Extism ABI, host functions (log/config/secret/db/http)  │
                 │ invoke:  POST /invoke/{address}  (HMAC-verified router delivery, alias live)    │
                 │ gateway: public routes (domain+path → address) and private calls by address   │
                 │ control: heartbeat, loaded set, per-function metrics                           │
                 └───────────────────────────────────────────────────────────────────────────────┘
```

Artifacts live in an OCI registry — the same registry type that stores container images (ECR,
GHCR, Harbor, Docker Hub); the OCI artifact spec lets it store any blob (a jar, a wasm file) by
sha256 digest with a manifest and media type, pushed with `oras push`. It gives immutability by
digest, existing auth/replication/retention, and a place cosign attaches signatures. One artifact
per version, addressed by digest;
signed with cosign keyless from the pipeline's OIDC identity — the same mechanism
`release-fcdev.yml` already uses. `s3://` and `file://` stores are supported behind the same
interface; `file://` is what fcdev uses.

## 3. Registry (platform aggregate `function`)

Tables (jOOQ-generated as usual; names indicative):

- `fn_functions` — `application_id`, `service_name`, `name` (unique together — the three parts of
  the function's address, §3.1), `client_id`, `runtime` (`jvm` | `wasm`), description, default
  limits, status.
- `fn_versions` — immutable: `function_id`, `version` (monotonic int), `artifact_ref`, `digest`
  (sha256, the identity), `signature_bundle_ref`, `signer_identity` (issuer + subject as verified),
  `manifest` (jsonb: entrypoint, triggers, limits, config keys, secret refs), `state`
  (`published` | `ready` | `retired`), `published_by`, `published_at`.
- `fn_aliases` — `function_id`, `alias` (`live`, `canary`, …), `version_id`, optional weight.
- `fn_hosts` — host id, pool, last heartbeat, loaded `{address, version, state}` set (for status).
- `fn_signer_policies` — per client: allowed OIDC issuers/subjects (e.g. the org's GitHub repo
  workflow identity) and which runtimes each may publish. **JVM requires a first-party signer.**
- `fn_domains` — per client: public hostname, verification token, `verified_at` (§4a).
- `fn_routes` — materialised from each function's `http` trigger for the `live` version: hostname,
  method, path pattern, function. Unique on (hostname, method, path pattern); it exists so
  conflicts are rejected at publish and so desired state can hand hosts one route table.

### 3.1 Function addresses

A function is identified everywhere by its **address**: `{app-code}.{service-name}.{function-name}`,
e.g. `billing.invoices.create`.

- **app-code** is the application's code. Application codes are globally unique
  (`app_applications_code_key`), so the address is globally unique without naming the client.
  Some users think of an application as a *module*; that is a UI and documentation label for the
  same thing, not a separate entity.
- **service-name** is a naming partition between the application and its functions, nothing more:
  nothing is versioned, loaded or promoted per service. It is **required**; tooling that has no
  use for it defaults it (the SDK and CLI default to `default`), so every address has three parts
  and prefix matching is never ambiguous.
- **function-name** names the function within its service.
- Each segment is a DNS label (`[a-z0-9-]`, 1–63 characters, no leading or trailing hyphen), so an
  address can become a hostname later without renaming anything.
- The address is the function's identity in the router's delivery target, gateway routes,
  permissions, metrics labels and MDC. Prefix patterns fall out of the shape: a grant of
  `billing.invoices.*` covers every function in that service; status views and metrics filter the
  same way.
- The version and alias are not part of the address; `live` is implied (§3 Aliases).

Behaviour:

- **Publish** validates the manifest, verifies the cosign bundle against the client's signer policy
  *at the platform* (hosts re-verify on load, defence in depth), records the version, emits
  `fc.function.version.published`, writes an audit entry.
- **Triggers → platform objects.** A manifest trigger `{eventType, messageGroupKey?}` becomes a
  subscription for the function's application, delivered to a dispatch pool whose target is the
  host pool's `/invoke/{address}` URL. A `schedule` trigger becomes a scheduled job with the same
  target. An `http` trigger declares the function's gateway routes (§4a) and becomes `fn_routes`
  rows. Reuses `sdksync` semantics: idempotent create/update/delete on each publish.
- **Aliases** are the only mutable pointer. Router targets reference aliases, so promotion and
  rollback never touch subscriptions. Weighted aliases (canary) are a later phase.
- **Retire** blocks new invocations of a version; hosts unload it on the next reconcile.

## 4. Hosts (data plane)

One Vert.x process per host, N hosts per pool, pools selected by labels (tenant tier, region,
runtime). Each host:

- **Reconciles** desired state: `GET /control/desired-state?pool=X` on a timer, plus a subscription
  to `fc.function.version.published` / `fc.function.alias.changed` for low latency. Fetch by digest,
  verify signature, load, report `ready` in the heartbeat. Lazy mode (default): record routes on
  reconcile, load on first invocation, unload after `FC_FN_IDLE_UNLOAD` (default 1 h). Eager mode
  per function via manifest for latency-sensitive ones.
- **Invokes** on the model-B path from the Vert.x migration brief: event-loop verticles accept;
  the invocation runs on a virtual thread; the response is written back on the request context.
  Per-invocation: timeout (manifest `maxDurationMs`, default 30 s), circuit breaker keyed by
  function+version, MDC (`function`, `version`, the platform's correlation keys).
- **Concurrency caps at three levels**, acquired in this order: host-global
  (`FC_FN_MAX_CONCURRENCY`, default 512 — a `Semaphore`), per function (manifest `maxConcurrency`,
  default 32), and upstream the router's dispatch-pool concurrency for the host pool. Exhaustion
  at either host level returns the router's *retry-with-backoff* outcome (not a failure), so the
  router's capacity gate holds the message and nothing is dropped. Caps are visible as gauges
  (`fc_fn_active`, `fc_fn_permits_available`) so a saturated function is obvious.
- **Limits are defaults, expandable, ceilinged.** Every limit (duration, concurrency, warm, wasm
  memory, DB pool size) has a platform default, a per-function manifest override, and a per-client
  ceiling in policy that the platform enforces at publish; a function cannot raise itself past the
  ceiling, an operator can raise the ceiling.
- **Verifies** the router's HMAC on `/invoke` exactly as any webhook target does
  (`WebhookSignature` in the SDK), and the caller's bearer token and permission on gateway calls
  (§4a).
- **Returns** the mediation outcome contract the router already understands: 2xx ack; the
  existing retry/backoff/fail status codes for `Result.retry(after)` / `Result.fail(reason)`.
- **Reports** per-function metrics on the metrics listener: invocations, outcomes, duration
  histogram, active, loaded versions, wasm memory; JFR events per invocation.

Host memory is capped (`-Xmx`, see migration brief). A host that trips its cap is restarted by the
orchestrator; that is the isolation model for JVM functions and it is written down as such.

## 4a. HTTP gateway (synchronous invocation)

Functions can serve HTTP. The platform **registers** routes; the host pool **serves** them. fc-server
never proxies function traffic, and the router is not in the synchronous path.

**Why not in fc-server.** Public function traffic would share the platform API's admission groups
and pools, so a spike on one function degrades logins; function code would be served beside the
identity server's session cookies; and the control plane's scaling would be tied to user traffic.
The router is asynchronous delivery with retries; a synchronous call does not belong on a queue.

**Two entries, one address.**

- **Public:** a load balancer in front of the host pool terminates TLS and routes by hostname (ALB
  host rules or a wildcard certificate). The host matches (hostname, method, path) against its route
  table to find the function's address.
- **Private:** callers inside the VPC reach the pool through its ECS Service Connect alias. Service
  Connect names an ECS service — a host pool — not a function, so the target travels in the request:
  the header `X-FlowCatalyst-Function: {address}` (the platform's header naming, as
  `X-FlowCatalyst-Signature`). The private listener also accepts the path form `/fn/{address}/…`,
  because a header is invisible in access logs and in curl history.

**Header rules.**

1. **The public entry overwrites the header.** The gateway discards any inbound
   `X-FlowCatalyst-Function` on a public request and sets it from the route match. Otherwise anyone
   on the internet could reach a function that is not routed on that hostname.
2. **Being in the VPC is not authentication.** Private calls carry a bearer token like public ones,
   and the host checks that the caller holds the permission for that address (prefix grants per
   §3.1).

**Authentication** is the gateway's job, not the function's. The host verifies bearer tokens locally
against the platform's JWKS, with no per-request call to the platform, and passes the authenticated
principal into the invocation. A route may be declared `auth: none` for public endpoints such as
inbound webhooks; the function then verifies what it needs itself.

**Registration.** The function's manifest `http` trigger lists its routes: hostnames (public only),
methods, path patterns, auth mode, CORS, request body cap and timeout. Publish validates them:

- **Domain ownership.** A public hostname must be a verified `fn_domains` row of the function's
  client: the platform issues a token, the client publishes it as a DNS TXT record, the platform
  checks it. Without this, one tenant can register another tenant's hostname. Private calls name an
  address, not a domain, so they need no verification.
- **Conflicts.** The same (hostname, method, path pattern) routed to two functions is a 409 naming
  the other function, like the warm-capacity check.
- **Aliases.** Routes resolve to the address, and the address to its `live` version, so promote and
  rollback never touch the route table.

Routes reach the hosts in desired state and are reconciled like functions. Matching is exact path
segments first, then `{param}` segments, then a trailing `*`; the platform rejects patterns that the
same rule would match ambiguously.

**The request and response are values, not the listener's objects.** The function receives an
`Invocation` of kind `HTTP` and returns `Result.http(status, headers, body)`; it never sees Vert.x's
`HttpServerRequest` or `RoutingContext`. Each reason is sufficient alone:

1. A wasm function takes bytes in and returns bytes out; a value serialises to the same bytes for
   both runtimes, so there is one HTTP model and one route table.
2. A Vert.x type in the API would have to be shared through the parent loader, coupling every
   function to the host's Vert.x version (§5).
3. A Vert.x request belongs to its event loop; functions run on virtual threads and every access
   would have to hop back with `runOnContext`.
4. A function that keeps a live request after returning keeps its class loader alive and can write
   to a finished response. A value can do neither.
5. A function is unit-tested with a record literal, and `fcdev fn invoke` replays a captured
   request from a JSON file.

The request carries method, path, the matched route, path parameters, query and headers as
multi-maps, the body as `byte[]` under the route's cap, the remote address, and the authenticated
principal. The function's address is a field of the `Invocation`, identical whichever entry the
call used; the function never reads the header. Streaming (large uploads, SSE), if ever needed, adds
an `InputStream` in and a body-writer callback out — still JDK types, so nothing above changes.

**Limits.** Gateway calls take the same host-global and per-function permits as router deliveries
(§4). A call that cannot get a permit gets `503` with `Retry-After`, not a queue.

## 5. JVM runtime

- **API jar** `fc-function-api` (no dependencies beyond the JDK, like `usecase/`):
  ```java
  public interface Function {
      Result handle(Invocation in, FunctionContext ctx) throws Exception;
  }
  // Invocation: kind (EVENT|HTTP|SCHEDULE), the CloudEvents-shaped envelope the platform stores
  // (type, source, subject, time, data, correlationId, causationId, messageGroup, dedupId),
  // or the HTTP request value for gateway calls (§4a). Always carries the function's address.
  // Result: ack() | retry(Duration) | fail(String) | http(status, headers, body)
  // FunctionContext: logger (a System.Logger the host implements, carrying the MDC keys), config
  //   (manifest keys → env/secrets resolved by the host), secrets, DataSource (host-owned pool per
  //   DB config, see §7), http (an API-jar interface backed by the host's client, manifest
  //   allowlist), Events (emit via platform API with dedupId; the host holds the function's
  //   service-account token), Clock, address, version.
  ```
  **Every type the API exposes is a JDK type or defined in the API jar.** No Vert.x, SLF4J or
  Jackson type may appear in a signature: whatever appears there must be shared through the parent
  loader, which couples every function to the host's version of it (below). The HTTP allowlist is
  enforced for wasm; a JVM function can build its own client, so for the JVM the allowlist is a
  convention and egress control belongs to the network.
- **Loading:** one `URLClassLoader` per function version over its jar, entrypoint class from the
  manifest, wrapped in a verticle registered under the function's address. Deploy new → switch
  alias → undeploy old. Functions shade their own third-party dependencies, including the SDK.
- **Isolation is the parent loader's job, and it must be a filter.** The JVM identifies a class by
  its name *and* its defining loader, so the host's `io.flowcatalyst.sdk.WebhookSignature` and a
  function's copy are unrelated classes with separate statics. But `ClassLoader.loadClass` asks the
  parent first, so if the parent is simply the host's application loader — which sees the whole
  host classpath: SDK, Vert.x, Jackson, jOOQ, Hikari — a class the function bundled under the same
  name silently resolves to the *host's* copy, and a version difference surfaces as
  `NoSuchMethodError` in production. The function loader's parent is therefore a filtering loader
  that delegates only `java.*`, `javax.sql.*` and the API jar's package to the host's loader and
  refuses everything else. The API types must come from that one loader on both sides; the JVM's
  loader constraints turn any disagreement into a `LinkageError` at link time.
- **Two functions bundling the same library** each get their own copy, at different versions if
  need be: their loaders are siblings and neither sees the other. They never exchange those types —
  they talk through the API, events and HTTP. The cost is metaspace per loaded version (§4's warm
  cap × the bundled libraries). If the workplan's §3 performance run shows that matters, the remedy is a
  deliberate allowlist on the filter (e.g. `io.flowcatalyst.sdk.*`, with the SDK then `provided`, its
  version declared in the manifest and checked at publish, and jars bundling a shared package
  rejected) — not shrinking, since unused classes are never loaded and cost no metaspace.
- **Context class loader.** `ServiceLoader.load` without a loader, `DriverManager`, Jackson module
  discovery and many logging frameworks resolve through the thread's context class loader, which on
  a host virtual thread is the host's loader. The invoke path sets it to the function's loader for
  the call and restores it in `finally`.
- **Guardrails** (tests in CI). Class loaders isolate classes, not the JVM; anything a library puts
  in JVM-wide state is shared by every function, first writer wins. A function fails the checks if
  it:
  - spawns platform threads, registers a JDBC driver, or holds static state past `stop()` (leak
    test: undeploy, force GC, assert the loader is collected through a `WeakReference`);
  - bundles a native library — one class loader per JVM may load a given library, so the second
    function gets `UnsatisfiedLinkError` (zstd-jni, snappy, sqlite-jdbc, Netty natives);
  - registers a security provider (`Security.addProvider`: a second `"BC"` is ignored, so one
    function runs another's BouncyCastle and pins its loader);
  - sets JVM-wide defaults: `URL.setURLStreamHandlerFactory`, `ProxySelector` / `Authenticator` /
    `CookieHandler` defaults, default `TimeZone` / `Locale`, system properties, shutdown hooks,
    `java.util.logging` configuration, fixed-name JMX MBeans.

  Publish rejects jars containing native libraries or a `java.security.Provider` service entry;
  the rest is caught by the leak and thread checks. A function that needs a native library runs on
  wasm or in a dedicated pool. Hosts restart nightly regardless.

## 6. Wasm runtime (Chicory)

> **Correction 2026-09-17:** Chicory has been released under the Bytecode Alliance as **Endive 1.0** (a fork of
> Dylibso's Chicory with a Cranelift native-code backend at build time, called via FFI). Target Endive, not Chicory:
> it carries foundation governance equal to wasmtime's and appears to be moving towards component-model support.
> The Extism ABI guidance below still applies until Endive's component support is confirmed.


- **Why Chicory:** pure Java, no native library, runs inside the same host process, and it is the
  runtime under Extism's Java host SDK. Its interpreter is slow; its compiler mode (wasm → JVM
  bytecode at load) is what makes it usable — enable it, measure it.
- **ABI: Extism.** A stable host/guest calling convention (bytes in, bytes out, host functions,
  JSON conventions) with maintained guest PDKs for **Rust, JavaScript, Go, C/C++, Zig, C#,
  AssemblyScript, Python**. One host integration covers all of them. This is deliberately *not* the
  WASI preview-2 component model: Chicory's component support was incomplete as of this plan, and
  Extism gives working polyglot today. Spin/wasmCloud components would not load directly; revisit
  when Chicory ships components.
- **Host functions** (the function's whole world; everything else is denied):
  `fc.log(level, msg)`, `fc.config.get(key)`, `fc.secret.get(ref)` (manifest-declared only),
  `fc.http.request(req)` (allowlisted hosts from the manifest), `fc.db.query(sql, params) → rows`
  and `fc.db.execute`, `fc.db.tx.begin/commit/rollback` (a connection handle scoped to the
  invocation, force-released at the end), `fc.events.emit(event)`, `fc.now()`.
- **Isolation and limits:** per-instance linear-memory cap from the manifest, per-invocation
  timeout enforced by a watchdog (verify Chicory's interruption mechanism in P3 before relying on
  it; if it cannot interrupt, run wasm invocations on a dedicated thread pool that is abandoned
  and recreated on timeout), CPU cap by concurrency only.
- **Languages:** Rust and JavaScript (QuickJS-based PDK) first, Go via TinyGo next. **PHP is
  out of scope** (decided): no Extism PDK, and PHP-in-wasm builds are tens of MB, slow to start and
  weak on I/O. PHP developers use the Laravel SDK path (their own service, outbox, webhooks).
- **Why keep wasm at all, given only our CI publishes:** language choice for the same pipeline,
  and containment — a wasm function that leaks or loops hurts only its own instance, which a JVM
  function cannot promise. Priority is below P1/P2; do not start it before the JVM host is in
  production.

## 7. Data access

- Host-owned pools, one per distinct DB configuration referenced by loaded functions (LRU, bounded
  count), each behind the semaphore gate from the benchmarks. A JVM function receives a
  `DataSource`; a wasm function gets `fc.db.*`.
- **Decided:** a function has a database only when its manifest declares one (a secret ref to a
  DSN, plus `poolSize` with default 4 and a client ceiling). Undeclared functions are pure:
  events in, events/HTTP out. Pools are host-owned, shared by all functions declaring the same DSN,
  bounded per host (`FC_FN_MAX_DB_POOLS`), LRU-closed when idle.

## 8. Pipeline and CLI

```
fc fn build   ./my-fn            # jar or wasm, writes manifest; shaded then shrunk (below)
fc fn publish ./my-fn --address billing.invoices.create   # push to OCI (digest), cosign sign (keyless), POST version
fc fn promote billing.invoices.create --alias live --version 12
fc fn invoke  billing.invoices.create --event ./sample.json   # sync test invoke via platform proxy
fc fn status  billing.invoices.create   # versions, aliases, hosts that have it loaded
fc fn status  'billing.invoices.*'      # every function in a service
```
`fc fn publish` in GitHub Actions uses the workflow's OIDC identity; the platform's signer policy
for the client lists that identity. A two-part address is never accepted; the CLI takes either a
full three-part address or `--app`, `--service` (default `default`) and `--name`.

**Shrinking.** A JVM function's build shrinks the shaded jar before it is signed, so the shrunk jar
is the artifact and its digest is what is published and verified:
`build → shade → shrink → run the function's tests against the shrunk jar → oras push → cosign sign → publish`.
The shrinker is ProGuard in shrink-only mode (`-dontobfuscate -dontoptimize`: Jackson derives JSON
names from member names, and stack traces must stay readable), with keep rules for the manifest
entrypoint, the classes Jackson binds, `ServiceLoader`-registered modules, enum `values`/`valueOf`,
and the `*Annotation*,Signature,InnerClasses,EnclosingMethod,Record,MethodParameters` attributes.
`maven-shade-plugin`'s `minimizeJar` is the cheaper, class-granular alternative. The gain is
artifact size — pull, digest and signature time on a lazy function's first call, and less for
publish to scan — not metaspace (§5). A missing keep rule fails at runtime, not at build, so the
tests run against the shrunk jar; that step is not optional.

**fcdev hosts functions itself.** One host implementation, two ways to start it:

- **fcdev on the JVM** (JBang or the shaded jar) deploys the host's verticles in its own Vert.x
  instance, on their own port, as production does. fcdev's classpath holds the entire server, so the
  filtering parent loader (§5) is exercised in dev exactly as production depends on it.
- **Native fcdev** (what `release-fcdev.yml` ships) cannot: a native image is a closed world and
  cannot define bytecode from a jar at runtime (GraalVM's runtime class loading is experimental; do
  not plan on it). It starts the host as a child process on the developer's JDK — anyone building
  JVM functions has one for Maven — and stops it on exit. Wasm under native fcdev: Chicory's
  interpreter works in a native image, its runtime compiler does not; confirm Endive's behaviour in
  P3.

`fcdev fn watch ./build` hides the difference: it hot-loads from a directory with signature checks
off and a `file://` store, in whichever host it started. `fcdev fn publish` targets the local
platform. Domain verification is off in fcdev; routes on `localhost` and the private entry work as
in production.

## 9. Platform API (OpenAPI, same conventions as the rest)

`{address}` is the three-part address of §3.1.

- `POST /api/functions` · `GET /api/functions` (filterable by address prefix) ·
  `GET /api/functions/{address}` · `DELETE …`
- `POST /api/functions/{address}/versions` (publish) · `GET …/versions` · `POST …/versions/{v}/retire`
- `PUT /api/functions/{address}/aliases/{alias}` · `GET …/aliases`
- `POST /api/functions/{address}/invoke` (sync test invoke, proxied to a host)
- `GET /api/functions/{address}/status` (hosts, loaded versions, last error)
- `GET /api/function-routes` (the materialised route table, filterable by hostname and address)
- `POST /api/function-domains` (claim a hostname; returns the TXT token) · `GET /api/function-domains` ·
  `POST /api/function-domains/{hostname}/verify` · `DELETE …`
- `GET /api/function-pools` · host control (service token): `GET /control/desired-state`
  (functions and routes), `POST /control/heartbeat`
- Events: `fc.function.version.published`, `fc.function.alias.changed`, `fc.function.version.retired`.
- SDK: `@AsFunction` in the Java SDK for apps that ship functions alongside event types.

## 10. Decisions (owner, 2026-09-13)

1. **Publishers:** only our CI. Signer policy lists the pipeline's OIDC identity; nothing else is
   accepted for any runtime.
2. **Database access:** declared per function in the manifest; none otherwise.
3. **Load modes:** warm is an option per function, mixed with lazy, with per-host and per-pool
   maxima (§4). **Concurrency:** capped globally per host and per function, with client ceilings
   (§4). Sync invocation: later phase (now the gateway, item 12, in P2).
4. **Placement:** shared pools, labels in the manifest.
5. **Artifact store:** OCI registry (see §2 for what that is), `s3://` fallback, `file://` in fcdev.
6. **Aliases:** `live` only to start.
7. **Limits:** defaults 30 s / 32 concurrent / 64 MB wasm memory, overridable per function up to a
   per-client ceiling an operator can raise.
8. **PHP:** not required; out of scope.
9. **Registry:** platform aggregate.
10. **Emitting events:** platform API with the function's service account; outbox only when a DB
    is declared.

Amendments (owner, 2026-09-18):

11. **Addresses:** a function is `{app-code}.{service-name}.{function-name}` (§3.1). "Module" is a
    user-facing name for an application, not an entity. A service is a naming partition only —
    nothing is versioned or loaded per service. The service name is required; tooling defaults it.
12. **HTTP gateway:** functions serve HTTP through routes the platform registers and the host pool
    serves (§4a) — not through fc-server and not through the router. Public calls route by hostname
    and path; private calls inside the VPC reach the pool's Service Connect alias and name the
    function with `X-FlowCatalyst-Function`.
13. **Domain verification** applies to public hostnames only; private calls name an address and
    need none. The public entry overwrites the function header; private calls still authenticate.
14. **Request and response are values** (`Invocation` of kind `HTTP`, `Result.http`), never the
    listener's objects; the API jar exposes JDK and API-jar types only (§4a, §5).
15. **fcdev runs the function host in the same instance** — in-process on the JVM, as a child
    process on the developer's JDK when fcdev is native (§8).
16. **Isolation by default:** functions bundle their own dependencies, the SDK included, behind a
    filtering parent loader; sharing the SDK through the parent is an optimisation to add only if
    measured metaspace demands it (§5). JVM function builds shrink before signing (§8).

Open: can a function move to another service? Proposed: no — the address is its identity in router
targets, routes, permissions and metrics, so a different address is a new function, as application
codes behave. Not yet ruled.

## 11. Phases

- **P1 — JVM, event-triggered.** Function aggregate with addresses + publish/alias APIs + signer
  policy; OCI+cosign artifact store; one host pool; reconcile + lazy load with the filtering parent
  loader; router delivery to `/invoke/{address}`; MDC/metrics; fcdev hosting (in-process and child
  process) and `fn watch`. The API jar ships the HTTP request/response values now, so the gateway
  never changes the function API. Acceptance: publish → promote → event delivered → function ack,
  visible in status; parity scenarios for the new routes; per-function metrics on the scrape.
- **P2 — Operate it.** Retire, rollback, per-function circuit breaker and concurrency, leak tests,
  nightly host restart, `fc fn` CLI, sync test invoke, the HTTP gateway (§4a: routes, domain
  verification, the private entry), scheduled triggers.
- **P3 — Wasm.** Chicory in compiler mode + Extism ABI; host functions; Rust and JS sample
  functions; timeout/memory enforcement verified; tenant signer policy.
- **P4 — Scale.** Weighted aliases, multiple pools with placement, host autoscaling on dispatch-pool
  depth, Go/TinyGo and Python PDKs, PHP evaluation with numbers.

Rough size: aggregate + APIs 4–6k LOC, host 5–8k, API jar < 1k, CLI 1–2k, wasm runtime 3–5k.
