# Plan: FlowCatalyst Function Runner (Vert.x host, platform-owned registry, wasm via Chicory)

Status: agreed design (owner decisions 2026-09-13 recorded in §10).

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
                 │ JVM runtime: URLClassLoader per version, Function API jar as parent            │
                 │ wasm runtime: Chicory + Extism ABI, host functions (log/config/secret/db/http)  │
                 │ invoke: POST /invoke/{code}[/{alias|version}] (HMAC-verified router delivery)   │
                 │ sync:   POST /fn/{code}      (OAuth bearer, optional)                          │
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

- `fn_functions` — `code` (unique per client), `client_id`, `application_id`, `runtime`
  (`jvm` | `wasm`), description, default limits, status.
- `fn_versions` — immutable: `function_id`, `version` (monotonic int), `artifact_ref`, `digest`
  (sha256, the identity), `signature_bundle_ref`, `signer_identity` (issuer + subject as verified),
  `manifest` (jsonb: entrypoint, triggers, limits, config keys, secret refs), `state`
  (`published` | `ready` | `retired`), `published_by`, `published_at`.
- `fn_aliases` — `function_id`, `alias` (`live`, `canary`, …), `version_id`, optional weight.
- `fn_hosts` — host id, pool, last heartbeat, loaded `{code, version, state}` set (for status).
- `fn_signer_policies` — per client: allowed OIDC issuers/subjects (e.g. the org's GitHub repo
  workflow identity) and which runtimes each may publish. **JVM requires a first-party signer.**

Behaviour:

- **Publish** validates the manifest, verifies the cosign bundle against the client's signer policy
  *at the platform* (hosts re-verify on load, defence in depth), records the version, emits
  `fc.function.version.published`, writes an audit entry.
- **Triggers → platform objects.** A manifest trigger `{eventType, messageGroupKey?}` becomes a
  subscription for the function's application, delivered to a dispatch pool whose target is the
  host pool's `/invoke/{code}/live` URL. A `schedule` trigger becomes a scheduled job with the same
  target. A `http` trigger enables the sync route. Reuses `sdksync` semantics: idempotent
  create/update/delete on each publish.
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
  (`WebhookSignature` in the SDK), and OAuth bearer + scope on `/fn`.
- **Returns** the mediation outcome contract the router already understands: 2xx ack; the
  existing retry/backoff/fail status codes for `Result.retry(after)` / `Result.fail(reason)`.
- **Reports** per-function metrics on the metrics listener: invocations, outcomes, duration
  histogram, active, loaded versions, wasm memory; JFR events per invocation.

Host memory is capped (`-Xmx`, see migration brief). A host that trips its cap is restarted by the
orchestrator; that is the isolation model for JVM functions and it is written down as such.

## 5. JVM runtime

- **API jar** `fc-function-api` (no dependencies beyond the JDK, like `usecase/`):
  ```java
  public interface Function {
      Result handle(Invocation in, FunctionContext ctx) throws Exception;
  }
  // Invocation: kind (EVENT|HTTP|SCHEDULE), the CloudEvents-shaped envelope the platform stores
  // (type, source, subject, time, data, correlationId, causationId, messageGroup, dedupId),
  // or the HTTP request for sync calls.
  // Result: ack() | retry(Duration) | fail(String) | http(status, headers, body)
  // FunctionContext: logger (MDC-aware), config (manifest keys → env/secrets resolved by the host),
  //   secrets, DataSource (host-owned pool per DB config, see §7), HttpClient (host-owned, allowlist),
  //   Events (emit via platform API with dedupId; the host holds the function's service-account token),
  //   Clock, functionCode, version.
  ```
- **Loading:** `URLClassLoader(jar, parent = apiLoader)`, entrypoint class from the manifest,
  wrapped in a verticle registered under the function's routes. Deploy new → switch alias → undeploy
  old. Functions shade their own third-party dependencies; the parent loader exposes only the API jar.
- **Guardrails** (tests in CI): a function that spawns platform threads, registers a JDBC driver,
  or holds static state past `stop()` fails a leak test; hosts restart nightly regardless.

## 6. Wasm runtime (Chicory)

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
fc fn build   ./my-fn            # jar or wasm, writes manifest
fc fn publish ./my-fn --code X   # push artifact to OCI (digest), cosign sign (keyless), POST version
fc fn promote X --alias live --version 12
fc fn invoke  X --alias live --event ./sample.json   # sync test invoke via platform proxy
fc fn status  X                  # versions, aliases, hosts that have it loaded
```
`fc fn publish` in GitHub Actions uses the workflow's OIDC identity; the platform's signer policy
for the client lists that identity. fcdev: `fcdev fn watch ./build` hot-loads from a directory
with signature checks off and a `file://` store; `fcdev fn publish` targets the local platform.

## 9. Platform API (OpenAPI, same conventions as the rest)

- `POST /api/functions` · `GET /api/functions/{code}` · `DELETE …`
- `POST /api/functions/{code}/versions` (publish) · `GET …/versions` · `POST …/versions/{v}/retire`
- `PUT /api/functions/{code}/aliases/{alias}` · `GET …/aliases`
- `POST /api/functions/{code}/invoke` (sync test invoke, proxied to a host)
- `GET /api/functions/{code}/status` (hosts, loaded versions, last error)
- `GET /api/function-pools` · host control (service token): `GET /control/desired-state`,
  `POST /control/heartbeat`
- Events: `fc.function.version.published`, `fc.function.alias.changed`, `fc.function.version.retired`.
- SDK: `@AsFunction` in the Java SDK for apps that ship functions alongside event types.

## 10. Decisions (owner, 2026-09-13)

1. **Publishers:** only our CI. Signer policy lists the pipeline's OIDC identity; nothing else is
   accepted for any runtime.
2. **Database access:** declared per function in the manifest; none otherwise.
3. **Load modes:** warm is an option per function, mixed with lazy, with per-host and per-pool
   maxima (§4). **Concurrency:** capped globally per host and per function, with client ceilings
   (§4). Sync invocation: later phase.
4. **Placement:** shared pools, labels in the manifest.
5. **Artifact store:** OCI registry (see §2 for what that is), `s3://` fallback, `file://` in fcdev.
6. **Aliases:** `live` only to start.
7. **Limits:** defaults 30 s / 32 concurrent / 64 MB wasm memory, overridable per function up to a
   per-client ceiling an operator can raise.
8. **PHP:** not required; out of scope.
9. **Registry:** platform aggregate.
10. **Emitting events:** platform API with the function's service account; outbox only when a DB
    is declared.

## 11. Phases

- **P1 — JVM, event-triggered.** Function aggregate + publish/alias APIs + signer policy; OCI+cosign
  artifact store; one host pool; reconcile + lazy load; router delivery to `/invoke`; MDC/metrics;
  fcdev `fn watch`. Acceptance: publish → promote → event delivered → function ack, visible in
  status; parity scenarios for the new routes; per-function metrics on the scrape.
- **P2 — Operate it.** Retire, rollback, per-function circuit breaker and concurrency, leak tests,
  nightly host restart, `fc fn` CLI, sync invoke, scheduled triggers.
- **P3 — Wasm.** Chicory in compiler mode + Extism ABI; host functions; Rust and JS sample
  functions; timeout/memory enforcement verified; tenant signer policy.
- **P4 — Scale.** Weighted aliases, multiple pools with placement, host autoscaling on dispatch-pool
  depth, Go/TinyGo and Python PDKs, PHP evaluation with numbers.

Rough size: aggregate + APIs 4–6k LOC, host 5–8k, API jar < 1k, CLI 1–2k, wasm runtime 3–5k.
