# Work plan: Function Runner, phases 1–2

Companion to `function-runner-plan.md` (the design; decisions in its §10) and
`vertx-migration-brief.md` (in progress by another agent). This document is the build order.
Read the design first; nothing here re-argues it. Amended 2026-09-18 for the design's §10 items
11–17: function addresses, the HTTP gateway, the filtering parent loader, fcdev hosting,
shrinking, no moving functions between services.

## 0. Coordination with the Vert.x conversion

The conversion agent owns `server/.../server/*` (listeners, `Server.java`, `Metrics.java`,
`Health.java`) and the new HTTP layer. To avoid conflicts:

- **Work packages A, B, C and F below touch only** `server/.../platform/function/**`, new Flyway
  migrations, jOOQ regeneration, the OpenAPI spec, `parity/`, `sdk/`, and two new Maven modules.
  They can start now.
- **Work package D (the host) depends on** the conversion's P1 deliverables: the
  `HttpServerVerticle` pattern, the handler adapter (virtual thread + `runOnContext`), and
  `GatedDataSource`. Until those land, build D against a thin local copy of the same three classes
  in the host module, then delete the copies and import the server's when P1 merges. Do not modify
  the conversion agent's files.
- Route registration for the platform API: the conversion introduces a route-registration seam
  (its P1 "RequestContext shim"). Package B registers the function routes through whatever seam
  exists at the time — Javalin today, the shim once merged — and keeps handlers framework-neutral
  (take a small `FunctionApiRequest`, return a `FunctionApiResponse`) so the swap is mechanical.
- Merge order: A → B → C → (conversion P1) → D → E → F.

## 1. Repository layout (new)

```
function-api/            Maven module `flowcatalyst-function-api` — the API jar. JDK only, no deps.
function-host/           Maven module `flowcatalyst-function-host` — the host binary (fc-fnhost).
server/.../platform/function/
    entity/     Function (+ FunctionAddress), FunctionVersion, FunctionAlias, FunctionHost, SignerPolicy,
                FunctionDomain, FunctionRoute (records, sealed states)
    repository/ jOOQ repositories
    service/    Publish, Promote, Retire, DesiredState, Heartbeat, TriggerSync, SignatureVerifier,
                DomainClaim, DomainVerify, RouteSync
    api/        HTTP handlers (framework-neutral request/response types)
    events/     fc.function.* domain events
server/src/main/resources/db/migration/V<next>__functions.sql
sdk/.../function/  @AsFunction annotation + FunctionDefinition in DefinitionScanner/Synchronizer
fcdev/.../FnWatchCommand.java, FnPublishCommand.java, FnHostLauncher.java (in-process or child process)
tools/fc-fn/  (optional, package E) picocli CLI, or subcommands under fcdev — see E
```

Add both new modules to the reactor in the root `pom.xml`; `function-api` must build with
`maven.compiler.failOnWarning=true` and **no** `--enable-preview` (functions compiled against it
should not inherit the preview pin). `function-host` inherits the server's flags.

## 2. Work packages

### A. Schema, entities, repositories (platform) — start now

1. Flyway migration `V<next>__functions.sql` (next number after the current 9 scripts):
   `fn_functions`, `fn_versions`, `fn_aliases`, `fn_hosts`, `fn_signer_policies`, `fn_domains`,
   `fn_routes` as in the design §3.
   Constraints: `fn_functions (application_id, service_name, name)` unique, each name segment
   checked against the DNS-label rule (design §3.1); `fn_domains (hostname)` unique;
   `fn_routes (hostname, method, path_pattern)` unique; `fn_versions (function_id, version)` unique
   and `(function_id, digest)` unique; `fn_aliases (function_id, alias)` unique; `fn_versions.state`
   check in (`published`,`ready`,`retired`); `manifest jsonb not null`; `fn_hosts.loaded jsonb`.
   Indexes: `fn_versions (function_id, state)`, `fn_hosts (pool, last_heartbeat)`.
2. Regenerate jOOQ (`tools/jooq-verify.sh` must pass; commit generated code as the repo does).
3. Entities as records with sealed state types; `FunctionAddress` as a record that parses and
   renders `app.service.function` (exactly three DNS-label segments; two-part input is rejected, the
   `default` service is a tooling default, not a parser one) and matches prefix patterns
   (`billing.invoices.*`, `billing.*`). `Manifest` as a record parsed from jsonb with validation
   (entrypoint, runtime, triggers — including `http` routes: hostnames, methods, path patterns, auth
   mode, CORS, body cap, timeout — limits, dbRefs, warm, maxConcurrency, maxDurationMs).
4. Repositories following the existing aggregate pattern (look at `platform/application` and
   `platform/subscription` for the conventions; unit-of-work rules are enforced by the existing
   convention tests — run them).
5. Limits policy: `FunctionLimits` with platform defaults (30 s, 32, 64 MB wasm, db pool 4,
   warm cap 200/host) from `Env.java` (`FC_FN_*` vars, documented in the same style), per-client
   ceilings in `fn_signer_policies` (rename to `fn_client_policies` if cleaner — one row per client
   holding signer identities *and* ceilings).

Acceptance: migration applies on embedded Postgres in tests; jOOQ drift check green; repository
round-trip tests for every table; manifest validation tests (reject unknown runtime, negative
limits, over-ceiling values, ambiguous route patterns); address tests (two-part and four-part
input rejected, uppercase and underscore rejected, prefix match does not match `billing.invoices`
against `billing.invoices-v2.*`).

### B. Platform API and services — after A

Routes (OpenAPI first: add to `sdk/openapi/openapi.json`, regenerate the lock, then implement):

| method/path | service | notes |
|---|---|---|
| `POST /api/functions` | create | application code, service name (required; tooling defaults `default`), name, runtime, description; ADMIN or app scope |
| `GET /api/functions`, `GET /api/functions/{address}` | read | paginated like other lists; list filters by address prefix (`billing.invoices.*`) |
| `POST /api/functions/{address}/versions` | **Publish** | body: artifactRef, digest, signatureBundle (base64 or ref), manifest |
| `GET /api/functions/{address}/versions` | read | |
| `POST /api/functions/{address}/versions/{v}/retire` | Retire | blocks invoke; hosts unload on next reconcile |
| `PUT /api/functions/{address}/aliases/live` | Promote | body: version; only `live` in phase 1 |
| `GET /api/functions/{address}/status` | Status | versions, alias, hosts with loaded state/last error |
| `GET /api/function-pools` | read | pools and their host counts |
| `GET /control/functions/desired-state?pool=` | DesiredState | host service token (client-credentials with a `function-host` scope) |
| `POST /control/functions/heartbeat` | Heartbeat | host id, pool, loaded set, metrics summary |
| `POST /api/functions/{address}/invoke` | Invoke (test) | phase 2, proxies to one ready host |
| `GET /api/function-routes` | read | phase 2; the materialised route table, filter by hostname/address |
| `POST /api/function-domains`, `GET …`, `POST …/{hostname}/verify`, `DELETE …` | DomainClaim / DomainVerify | phase 2; claim returns the TXT token, verify resolves it |

Services:

- **Publish**: validate manifest against limits/ceilings → verify cosign bundle (package C) against
  the client's signer policy → insert version (`published`) → **TriggerSync** → emit
  `fc.function.version.published` → audit. All in one unit of work; on any failure nothing persists.
- **TriggerSync**: for each manifest trigger, upsert platform objects idempotently, reusing the
  `sdksync` services rather than re-implementing: an event-type subscription for the function's
  application, a dispatch pool named `fn-<pool>` targeting `https://<host-pool-url>/invoke/{address}`
  with the pool's concurrency, and a scheduled job for `schedule` triggers. Deleting a trigger from
  the manifest on a later publish removes the object. Warm-capacity check against the pool here;
  reject with a 409 and a message naming the cap.
- **Promote**: alias `live` → version; version must be `ready` on at least one host in the pool
  (else 409); emit `fc.function.alias.changed`; audit.
- **DesiredState**: for a pool, the set of `{address, version, digest, artifactRef, manifest, alias,
  mode(lazy|warm)}` for all non-retired versions referenced by an alias, plus retired versions to
  unload, plus (phase 2) the pool's route table. ETag/`If-None-Match` so polling is cheap.
- **RouteSync** (phase 2, called from Publish and Promote): materialise the `live` version's `http`
  trigger into `fn_routes`; reject a public hostname that is not a verified `fn_domains` row of the
  function's client, and a (hostname, method, path pattern) owned by another function (409 naming
  it). Private-only routes name no hostname and need no domain.
- **Heartbeat**: upsert `fn_hosts`; mark versions `ready` when any host reports loaded OK; surface
  last error per host/version for Status.

Acceptance: parity scenarios added to `parity/scenarios/` for every new route (the Go binary will
not have them — mark them Java-only in `surface.json` the way any Java-first route is marked, or
skip in the Go leg; do not weaken the harness); OpenAPI lock diff reviewed and committed; audit
entries asserted in tests; convention tests green. No route changes a function's application,
service or name (design §10 item 17); a test asserts the function's address is unchanged after
every mutating route.

### C. Signature verification and artifact store — parallel with B

- `ArtifactStore` interface: `fetch(ref, expectedDigest) → Path`, implementations `oci://` (pull
  by digest via the registry HTTP API; ORAS-compatible manifest; ECR auth via the AWS SDK already
  present), `s3://`, `file://`. Digest is always re-computed after download and compared.
- `SignatureVerifier`: verify a cosign **bundle** (Sigstore bundle format: certificate, signature,
  Rekor entry) against the digest, checking certificate OIDC issuer and subject against the client
  policy, using the Sigstore Java library (`dev.sigstore:sigstore-java`); pin a version, record it
  in the pom with the same "why pinned" comment style. Verification runs at publish (platform) and
  at load (host). Clock skew and Rekor offline modes: fail closed.
- Tests: a fixture bundle generated once with cosign against a fixed test key/identity, committed
  under `server/src/test/resources/function/`; negative tests for wrong digest, wrong subject,
  tampered bundle, expired certificate.

### D. The host (`function-host`) — after conversion P1 merges (or on local copies, see §0)

1. **Process skeleton**: `FnHostMain` → Vert.x instance → `HttpServerVerticle × N` (from the
   conversion) with routes `/invoke/{address}` and `/health`; `MetricsVerticle` on
   `FC_METRICS_PORT`. Config via `Env.java` conventions (`FC_FN_POOL`, `FC_FN_PLATFORM_URL`,
   `FC_FN_CLIENT_ID/SECRET`, caps). Graceful shutdown: stop accepting, drain in-flight up to
   `FC_DRAIN_TIMEOUT`, heartbeat "draining", exit.
2. **Reconciler**: timer (default 15 s) + subscription to `fc.function.*` events via the platform
   (the host registers itself as an application at boot using `sdksync`, subscribing to the two
   event types; deliveries hit `/invoke/_reconcile` — `_` is not a legal address character, so it
   cannot collide with a function). Diff desired vs loaded → fetch/verify/load
   or unload. Warm versions load now; lazy versions register routes only.
3. **JVM runtime**: `JvmFunctionLoader` (URLClassLoader per version), `ApiOnlyParentLoader` (the
   filtering parent of design §5: delegates `java.*`, `javax.sql.*` and the API jar's package to the
   host's loader, throws `ClassNotFoundException` for everything else), `LoadedFunction` (verticle
   wrapper, init/stop hooks), `FunctionRegistry` (address+alias → loaded, LRU for lazy, caps
   `FC_FN_MAX_LOADED`, `FC_FN_MAX_WARM`). Load-time checks: reject a jar containing native
   libraries or a `META-INF/services/java.security.Provider` entry.
4. **Invoke path**: verify router HMAC (`sdk` `WebhookSignature`) → resolve function → acquire
   host semaphore then function semaphore (both non-blocking `tryAcquire`; on failure respond with
   the router's retry-with-backoff outcome) → run on virtual thread with timeout → map `Result` to
   the mediation outcome status codes (take them from `conformance/` corpus, not from memory) →
   `runOnContext` write → release permits in `finally`. MDC keys set/cleared per invocation. The
   thread's context class loader is set to the function's loader for the call and restored in the
   same `finally`.
5. **FunctionContext**: logger (`System.Logger` implemented by the host, carrying MDC), config
   (manifest keys resolved from env/secrets at load), secrets, `DataSource` per declared DSN
   (host-owned Hikari behind `GatedDataSource`, `FC_FN_MAX_DB_POOLS` LRU), `http` (an API-jar
   interface; the host implements it on its Vert.x client with the manifest allowlist — no Vert.x
   type crosses the API), `Events.emit` (platform events API with the function's service-account
   token, dedupId required), clock, address. Every type in the API is a JDK or API-jar type.
6. **Metrics**: `fc_fn_invocations_total{address,version,outcome}`, `fc_fn_duration_seconds`
   histogram, `fc_fn_active`, `fc_fn_permits_available{scope=host|function}`, `fc_fn_loaded`,
   `fc_fn_warm`, `fc_fn_load_errors_total`; JFR event `FunctionInvocation`.
7. **Guardrails**: leak test harness (deploy → invoke → undeploy → force GC → assert loader
   collected via `WeakReference`), thread-spawn detector (thread count delta per invocation),
   blocked-event-loop check on in CI. Isolation tests, each written to fail if the filter is
   replaced by a plain parent (break it on purpose and confirm, per CLAUDE.md):
   - a function bundling a different version of an SDK class gets *its* class: assert the
     `Class` object's loader is the function loader and that a method present only in the
     bundled version is callable;
   - a function cannot load a host-only class (`io.vertx.core.Vertx`, a server class): assert
     `ClassNotFoundException`;
   - two functions bundling one library at two versions each see their own static state;
   - `ServiceLoader.load(X.class)` inside a function finds the function's provider, not the host's
     (pins the context class loader).
8. **Dockerfile** for the host (same jlink base as the server; `-Xmx` set; non-root).

Acceptance: an end-to-end test on embedded Postgres + one host process: publish (file store,
test signer) → promote → emit event → router delivers → function acks → status shows `ready` and
one invocation; negative: over-concurrency returns backoff and the router redelivers; timeout
returns fail with reason; retired version unloads within one reconcile.

### E. Developer surface — after D

- **`function-api` sample project** under `usecase/examples/function-hello` (or a new
  `examples/`): one function, a `manifest.json`, a Maven build producing a shaded jar, shrunk with
  ProGuard in shrink-only mode (design §8: keep file beside the manifest, tests run against the
  shrunk jar). This is the template the pipeline uses.
- **fcdev hosting** (`FnHostLauncher`): on the JVM, deploy the host verticles in fcdev's Vert.x
  instance on their own port; when fcdev is native, start the host jar as a child process on the
  developer's JDK (`JAVA_HOME`, else `java` on `PATH`; a clear error naming both if neither
  exists) and stop it with fcdev. Detect native the way fcdev already does (`docs/fcdev.md`).
  Acceptance: the D end-to-end flow passes under both, and the JVM case asserts a function cannot
  load a server class from fcdev's classpath.
- **CLI**: picocli subcommands in fcdev to avoid a new binary: `fcdev fn watch <dir>` (file store,
  signatures off, hot reload on change), `fcdev fn publish <jar> --address app.service.fn`
  (or `--app`/`--service`/`--name`, service defaulting to `default`; local platform),
  `fcdev fn promote`, `fcdev fn status`, `fcdev fn invoke` (phase 2). Production pipeline uses the
  same commands from the shaded fcdev jar with `--platform-url` and CI credentials; `oras` and
  `cosign` are invoked as external tools by `publish` (documented prerequisites), not reimplemented.
- **Java SDK**: `@AsFunction(service = "default", name, triggers…)` (application from the
  scanning app) scanned by `DefinitionScanner`, synced by
  `DefinitionSynchronizer` so an application can declare the functions it ships.
- **GitHub Actions example** `examples/function-hello/.github/workflows/publish.yml`: build →
  shrink → test the shrunk jar → `oras push` → `cosign sign` (keyless) → `fcdev fn publish`.

### F. Phase 2 items — after E, in this order

1. `POST /api/functions/{address}/invoke` test invoke (platform → one ready host, sync).
2. HTTP gateway (design §4a): RouteSync and the domain routes in B; on the host, a route matcher
   (exact segments, then `{param}`, then trailing `*`) over the desired-state route table; the
   public entry discards any inbound `X-FlowCatalyst-Function` and sets it from the match; the
   private entry resolves the address from the header or `/fn/{address}/…`; bearer verified against
   the platform JWKS locally and the caller's permission checked for the address (prefix grants);
   `503` + `Retry-After` when permits are exhausted. Tests: a public request carrying a forged
   header reaches only the routed function; a private call without a token is 401 and with a token
   lacking the permission 403; an unverified hostname is rejected at publish.
3. Per-function circuit breaker (reuse `router/policy/CircuitBreaker`), rollback = promote older.
4. Nightly host restart policy (orchestrator-side; document, add readiness handling).
5. Scheduled triggers wired through the existing scheduled-job dispatcher.
6. Ops runbook: capacity math (warm × metaspace, pools × Hikari), what each gauge means.

## 3. Test strategy

- Unit: entities, addresses and prefix matching, manifest validation, limits/ceilings, signature
  verifier (fixtures), loader and isolation (D7), registry LRU, permit accounting, route matching.
- Integration (embedded Postgres, as the server tests do): publish/promote/retire/desired-state/
  heartbeat; trigger sync creating and removing subscriptions and dispatch pools.
- End-to-end: the D acceptance flow, run in CI as a new job alongside `parity` and `e2e`.
- Parity: new scenarios for every route; conformance corpus reused for outcome codes.
- Performance: the migration brief's §7 protocol against a host with 100 lazy + 20 warm trivial
  functions: RSS after load, first-call latency for a lazy function, p99 for a warm one at c=1000
  on one pinned core. Record the numbers in the report; they are the economic case.

## 4. Sizing and order

| package | est. LOC | can start | blocks |
|---|---:|---|---|
| A schema/entities/repos | 2–3k | now | B, C |
| B API/services/trigger sync | 3–4k | after A | D, E |
| C signature + artifact store | 1.5–2k | after A (parallel with B) | D |
| D host | 5–7k | after conversion P1 (or on local copies) | E, F |
| E developer surface | 1.5–2k | after D | F |
| F phase 2 | 2–3k | after E | — |

## 5. Report

`docs/function-runner-report.md`: what shipped per package, deviations from the design with
reasons, the test matrix results, the performance table from §3, and open items for phase 3
(wasm) with anything learned that changes its plan.
