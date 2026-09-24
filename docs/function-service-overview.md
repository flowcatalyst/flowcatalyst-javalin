# The function service — what it is and how it fits

*For a developer or an LLM session meeting the function service for the first time. It explains
the shape and the reasons; the specs under `docs/spec/function-*.md` are the contract, and
`docs/functions.md` is the developer's how-to. Written 2026-09-22 against `main` at `7d82ef25`.*

## 1. The one-paragraph version

FlowCatalyst is a message-routing platform: applications emit **events**, **subscriptions** deliver
them to HTTP endpoints as signed webhooks, **scheduled jobs** fire on a cron, and a **router**
does the delivery with retries, ordering and back-pressure. Until the function service, every
endpoint on the receiving end was somebody else's server. The function service lets a developer
write a small Java class, publish its jar to the platform, and have the platform **run it** —
receiving the platform's own webhooks and schedules, answering HTTP for authenticated callers, and
serving public routes on the developer's own domain — without provisioning a server. It is the
platform's own compute: what a managed "serverless" runtime would be, but self-hosted, running
hundreds of functions per JVM, with the platform's existing identity, events and delivery
machinery as the plumbing.

## 2. Where it sits

```
                       ┌──────────────────────────── platform (fc-server) ────────────────────────────┐
  developer            │  registry (fn_ tables)   control plane (/control/functions)   platform API    │
  fcdev fn publish ───▶│  functions, versions,    desired state · heartbeat ·          /api/functions  │
  fcdev fn promote     │  aliases, policies,      artifact download · events            OpenAPI doc     │
                       │  domains, routes,        ▲                                                    │
                       │  config, secrets         │ polls every 15 s, bearer token                     │
                       └──────────────────────────┼─────────────────────────────────────────────────────┘
                                                  │
        router ── signed webhook ──▶ ┌────────────┴──── function host (fc-fnhost) ──────────────────┐
        scheduler ── signed POST ──▶ │  one JVM, one class loader per function version              │
        API caller ── bearer ──────▶ │  :8080 private entry   /functions/{address}[:{version}]/path │
        the public internet ───────▶ │  :8081 public entry    Host header → claimed domain → route  │
                                     │  :9090 /health /ready /metrics                                │
                                     └───────────────────────────────────────────────────────────────┘
```

Three things talk to each other:

- **The platform** (module `server`) keeps the registry and the control plane and does everything
  a human or a CLI asks for. It never runs function code.
- **The function host** (module `function-host`, image `fc-fnhost`) runs function code. It is a
  separate deployable — its own ECS service, its own memory sizing — that discovers what to run by
  polling the platform. A host belongs to a **pool** (`FC_FN_POOL`); a function's manifest names
  the pool that should run it.
- **The developer's tools**: the `function-api` jar (the tiny interface a function implements),
  `fcdev` (which runs a platform *and* a host in one process for local work, and carries the
  `fn` CLI), and the sample `examples/function-hello`.

The router and the scheduler do not know functions exist. When a function's manifest declares a
subscription or a schedule, the platform creates an ordinary subscription or scheduled job whose
target is the function's URL on its host. Delivery is the same signed webhook every other
subscriber gets. That is the central design choice: **every invocation is HTTP**, and the function
service adds no new delivery path.

## 3. The lifecycle of a function

1. **Create** a function: `POST /api/functions`, or implicitly on first `fn publish`. Its
   **address** is `application.service.name` — three DNS labels, unique, and stable. The owner is
   either a client (tenant) or the platform itself.
2. **Publish** a version: the jar is uploaded to the platform (`PUT …/artifacts/{digest}`), which
   stores it in `FC_FN_ARTIFACT_STORE` (a directory or an S3 bucket), and the manifest is submitted
   with the jar's sha256 and, in production, a Sigstore signature bundle. Publish **validates
   everything and changes nothing else**: the manifest is parsed strictly (unknown keys rejected),
   limits are checked against the client's policy, the signer must be one the policy permits.
   A published version is `PUBLISHED`; the platform includes it in desired state as a
   **candidate** so a host can load and verify it before anyone promotes it.
3. **Ready**: a host that has fetched, verified and loaded the candidate reports it in its
   heartbeat; the platform marks the version `READY` exactly once. Promote refuses a version that
   no host has proven loadable.
4. **Promote**: `PUT …/aliases/live` points the `live` alias at the version. This is the moment
   the manifest **materialises**: the platform creates the function's dispatch pool, its
   subscriptions (source `FUNCTION`), its scheduled jobs and its public routes from the manifest,
   and deletes whatever the previous manifest declared and this one no longer does. Promote also
   refuses if a declared config or secret key has no value. `PUT …/aliases/{name}` for any OTHER
   name points a **named alias** at a `READY` version too, but is HTTP-only — no wiring change —
   and `live` is never touched (`fn promote --alias <name>`, `docs/functions.md` §"Aliases").
5. **Run**: hosts in the pool see the new live version in desired state, load it (**new before
   old**, so an address is never without a version), route to it, and drain the old one.
6. **Retire** a version, or **delete** the function (cascades to versions, aliases, routes,
   trigger objects and stored artifacts).

`fcdev fn deploy` does publish → wait for ready → promote in one command; `fn watch` does it on
every save.

## 4. What a function is

A class implementing `io.flowcatalyst.function.Function` from the `function-api` jar:

```java
public final class HelloFunction implements Function {
    public void init(FunctionContext ctx) {}                 // optional
    public Result handle(Request in, FunctionContext ctx) {  // every invocation
        return Result.ack();                                 // or retry(...) or fail(...)
    }
    public void stop() {}                                    // optional
}
```

`Request` carries the HTTP method, path, path parameters, headers, body and the **caller**: a
`Webhook` (the router or the scheduler, signature already verified), a `Principal` (a platform
bearer token, with its permissions and reach), or anonymous. `Result` maps to HTTP: `ack` → 200,
`retry` → 429 with `Retry-After` (the router defers the delivery without spending an attempt),
`fail` → 500. `FunctionContext` gives the function what it may not obtain for itself: its config
values and secrets, a logger (MDC-tagged with the invocation), a database pool per declared
connection, an outbound HTTP caller restricted to `httpAllow`, and `emit(...)` for events — which
the host sends on the function's behalf and only for event types the function's application owns.

The jar is shaded (dependencies bundled, `function-api` excluded — the host refuses a jar that
bundles it) and shrunk; a typical function costs about **5.5 MB** of host memory. Native libraries
are refused: the host's isolation is class-loader isolation, not process isolation.

## 5. The manifest — the whole contract in one file

`manifest.json` is published with the jar and is the only place a function's wiring is declared:

| Section | What it declares |
|---|---|
| `runtime`, `entrypoint`, `pool`, `warm`, `limits` | what to run, where, kept loaded or lazy, and its duration/concurrency caps |
| `endpoints[]` | the paths the function answers and **how each is authenticated** — `webhook` (signed delivery), `platform` (bearer token), `none`. No default: every endpoint says |
| `subscriptions[]` | event types to receive, each pointing at a `webhook` endpoint |
| `schedules[]` | cron entries, each pointing at a `webhook` endpoint |
| `public[]` | hostname + path prefix served on the public entry, on a hostname covered by a zone the owner has claimed — a claim is verified by being made, no DNS TXT step (a claim covers every hostname under it); each entry may opt into `aliasPrefixes` so a prefixed hostname (`qa-myapp.acme.com`) reaches that named alias's version too (`docs/functions.md` §6a "Alias prefixes") |
| `config`, `secrets` | the keys the function needs; values are stored on the platform per function and delivered in desired state, **declared keys only** |
| `db[]`, `httpAllow` | database connections (DSN from a secret) and the outbound hosts the function may call |

Promote creates and prunes to match this file. Nothing about a function's wiring lives anywhere
else, which is what makes cleanup a matter of removing a line.

## 6. How a call reaches a function

| Route | Listener | Who | Authentication |
|---|---|---|---|
| `/functions/{address}/{path}` | private entry, 8080 | the router, the scheduler, in-VPC callers | the endpoint's own `auth` |
| `/functions/{address}:{version}/{path}` | private entry | a developer or an operator | platform bearer token with `platform:function:version:invoke`, then reach; the endpoint's own `auth` is **not** applied — this is how you exercise a version nobody has promoted |
| `https://{hostname}{prefix}{path}` | public entry, 8081 | the public internet | the endpoint's own `auth`; CORS answered by the host; `X-Forwarded-*` honoured only from `FC_FN_TRUSTED_PROXIES` |

The private entry is not meant to be reachable from outside the VPC. In ECS it is addressed
through Service Connect as `fn-{pool}` — the template `FC_FN_POOL_URL` on the platform is how the
router's subscriptions learn the URL.

The public entry's hostname match is exact first (the `Host` header against a claimed, covered
hostname, resolving to `live`). When there is no exact match and the request's first DNS label
contains a `-`, the host splits it at the first `-` into a candidate alias name and the rest of the
hostname, looks that base hostname up the same way, and — only if the winning route opted that
prefix into `aliasPrefixes` — serves the version the named alias currently points at instead of
`live`, using that version's OWN manifest for endpoint matching, auth and limits. This is the one
way a named alias (otherwise HTTP-only and invisible to routing, `docs/functions.md` §"Aliases")
becomes reachable from the public internet, and it needs no domain claim or `fn_routes` row of its
own — the derived hostname is computed at request time, never stored.

Every invocation takes a **permit** (per-function concurrency), has a deadline, and is counted in
`/metrics`. A version that fails to load answers 503 with `Retry-After`, never 404 — "known but
unloadable" is distinguishable from "no such function".

## 7. The host

`fc-fnhost` is a plain JVM process (Vert.x for HTTP, virtual threads for invocations). Its loop:

- **Reconcile** every 15 s (or when triggered): fetch desired state for its pool with an ETag,
  **prepare** each version it does not have (download from the platform's control plane, recompute
  the sha256, verify the Sigstore bundle against the signer recorded at publish), **load** what is
  live (warm now, lazy on first call), **unload** what is gone, **heartbeat** the outcome per
  version — `LOADED`, `REGISTERED`, or `FAILED` with a reason.
- A platform outage never unloads anything; a version that fails to prepare or load leaves the
  old one serving.
- **Isolation**: one class loader per version, filtering: a function sees the JDK, the
  `function-api` jar and its own jar, nothing of the host. Static state is per version.
- **Memory**: metaspace is the binding limit. The image sets `FC_JVM_METASPACE_PERCENT` (default
  50) and the heap is the remainder; a load is refused when headroom is short rather than allowed
  to take the host down, and an `OutOfMemoryError: Metaspace` is one function's failure, not the
  process's. Measured: **212 typical functions in 2 GiB, 438 in 4 GiB**; 100 functions under load
  on 2 CPUs did 11.7k req/s at p99 82 ms.
- **Health**: `/ready` is true only when the listener is up and the reconcile loop is alive.

The host authenticates to the platform as an OAuth client with the role `platform:function-host`
(`FC_FN_CLIENT_ID` / `FC_FN_CLIENT_SECRET`), discovers the token issuer from the platform's
discovery document, and holds **no** storage credentials: artifacts come through the platform.

## 8. Security model in one place

- **Who may do what** — permissions under `platform:function:*` (`function:view`,
  `function:manage`, `version:publish`, `alias:promote`, `policy:manage`, `secret:manage`,
  `domain:manage`, `version:invoke`, `host:control`); roles `platform:function-publisher` and
  `platform:function-host`. Reach follows the platform's rule: a tenant caller sees its own
  client's functions; platform-owned functions are anchor-only.
- **What may run** — every published artifact is signed (`cosign sign-blob`) and the platform and
  every host verify the Sigstore bundle with the JDK alone; the **client policy** lists the exact
  signer identities permitted. `FC_FN_SIGNATURES=off` exists for development only and is refused
  outside dev mode.
- **What a running function may touch** — only what its manifest declares: named config and secret
  keys, named database connections, allow-listed outbound hosts, its own application's event
  types. The host, not the function, holds the credentials.
- **What a caller must prove** — per endpoint: a valid delivery signature, a platform token with
  reach, or nothing. Versioned calls additionally need `version:invoke`.

## 9. How it relates to the rest of the platform

| Platform concept | The function service's use of it |
|---|---|
| Application | owns functions (its code is the first label of the address) and the event types a function may emit; its service account signs deliveries to the function |
| Client | may own functions; its **policy** sets signer identities and limit ceilings; a client-owned function's subscriptions and routes are client-scoped |
| Subscription | created per manifest entry with source `FUNCTION`; the SDK sync never touches those rows |
| Scheduled job | created per manifest schedule entry, target = the function's URL |
| Dispatch pool | one per function (ruling R7), so a slow function throttles only itself |
| Event / outbox | `ctx.emit()` goes through the host to the platform's ingest with the function's identity |
| Router | delivers to functions exactly as to any subscriber; honours `retry` by deferring (Java only — Go does not) |
| Service accounts / OAuth | the host and the CLI are OAuth clients; `fcdev start` provisions both |
| Domains | `fn_domains` backs the `public[]` routes; a claim is a ZONE, covering every hostname under it, and is verified by being made — no DNS TXT step, no pending state, no dev-mode special case (`docs/spec/function-domains-no-dns.md`) |
| Migrations | `V13__functions.sql` (Java-only tables `fn_*`; the only Go-shared change is widening `chk_msg_subscriptions_source` to admit `FUNCTION`) |

**Divergence from Go.** The function service has no Go counterpart; it is Java-first and
post-cutover. While the two platforms share a database, Go will not understand `FUNCTION`-sourced
subscriptions — the cutover plan (`docs/spec/cutover.md` §4.1) says rollback means deleting them.

## 10. Local development

```
make jar
java --enable-preview -jar fcdev/target/flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar start
cd examples/function-hello && mvn package
fcdev fn deploy target/function-hello.jar acme.default.hello --manifest manifest.json
fcdev fn invoke acme.default.hello --path /healthz
```

`fcdev start` runs the platform, an embedded Postgres and a function host in one process
(functions on 8090, public entry 8091, host health 9091), writes `fn-cli.json` with the CLI's
credentials, defaults the artifact store to its state directory, and runs with signatures off.
`fn watch` rebuilds and redeploys on save. The full walkthrough is `docs/functions.md`.

## 11. Deployment

- **Images**: `fc-server` (unchanged by this work) and `fc-fnhost` (`function-host/Dockerfile`),
  the latter built weekly and pushed to ECR by `.github/workflows/fnhost-image.yml` once the
  repository variables `FNHOST_AWS_ROLE_ARN`, `FNHOST_AWS_REGION`, `FNHOST_ECR_REPOSITORY` are set.
- **Platform env**: `FC_FN_ARTIFACT_STORE` (`file:///…` or `s3://bucket/prefix`; the task role
  needs S3 permissions on the prefix), `FC_FN_SIGNATURES`, `FC_FN_TRUST_ROOT` (a private Sigstore
  root, if any — the host needs the same), `FC_FN_POOL_URL`, `FC_FN_DEFAULT_*` limits.
- **Host env**: `FC_FN_POOL`, `FC_FN_PLATFORM_URL`, `FC_FN_CLIENT_ID/SECRET`, `FC_FN_SIGNATURES`,
  `FC_FN_TRUST_ROOT`, `FC_FN_MAX_LOADED`, `FC_FN_CACHE_DIR`, `FC_FN_TRUSTED_PROXIES`,
  `FC_JVM_METASPACE_PERCENT`. One ECS service per pool, Service Connect alias `fn-{pool}`.
- **Sizing**: memory-heavy, CPU-light. A 2 GiB task holds ~200 typical functions; scale by adding
  tasks to the pool, not by growing one.

Full detail: `docs/deployments.md` §4.

## 12. What is deliberately not there

- **A registry write backend for artifacts** — the platform stores jars in a directory or S3;
  pushing them to ECR/OCI gains nothing while the platform is the only reader.
- **Garbage collection of stored artifacts** for retired versions — only function delete removes
  blobs.
- **Subscription `filter`** — the platform has no column for it anywhere; a filter in a manifest is
  dropped (backlog).
- **Process isolation** — one JVM, class-loader isolation. A function that spins CPU or leaks
  memory is bounded by permits and metaspace headroom, not by a cgroup. Native libraries are refused
  for this reason.

## 13. Where to read next

| Want | Read |
|---|---|
| to write a function | `docs/functions.md`, `examples/function-hello` |
| the design and the owner's decisions | `docs/function-runner-plan.md` (§10 decisions), `docs/function-runner-report.md` (what shipped, performance) |
| the contracts, per piece | `docs/spec/function-registry.md` (schema, addresses, manifest), `function-api.md` (platform API, control plane), `function-artifacts.md` + `function-artifact-upload.md` (store, signing, upload), `function-invocation.md` (HTTP invocation, wiring at promote), `function-context.md` (config, secrets, DB, HTTP, emit), `function-public-routes.md` (domains, public entry, CORS), `function-host-core.md` / `-reconciler.md` / `-listener.md` / `-process.md` (the host), `function-developer-surface.md` (fcdev, CLI, sample), `function-openapi.md` |
| the API as a document | `GET /api/openapi-functions.json` on any platform, or `server/src/main/resources/openapi/functions.openapi.json` |
| memory and sizing | `docs/spec/jvm-memory.md`, `docs/spec/function-host-benchmark.md`, `bench/function-host/` |
| how to deploy | `docs/deployments.md` §4 |
| the live state and what is open | `docs/STATUS.md` (top), `docs/backlog.md` |

## Glossary

- **address** — `application.service.name`, the function's identity; three DNS labels.
- **alias** — a named pointer to a version; `live` is the one every host actually serves and wires up. Any other name is a valid alias too, but HTTP-only — no wiring, `live` untouched (`docs/functions.md` §"Aliases").
- **candidate** — a `PUBLISHED` version carried in desired state so hosts can prove it loadable before promote.
- **desired state** — the document a host fetches: which versions to run, with their config, secrets, signer and public routes.
- **pool** — a named group of hosts; a manifest picks one; one ECS service per pool.
- **private / public entry** — the host's two listeners: in-VPC by address, and the internet by claimed hostname.
- **permit** — one unit of a function's concurrency limit; taken per invocation, released when it finishes.
- **reach** — the platform's tenancy rule: which clients' rows a caller may see.
- **warm / lazy** — loaded at reconcile, or on first invocation and unloaded after an hour idle.
