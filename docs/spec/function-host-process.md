# Spec — the host as a process: `fc-fnhost` main, health, metrics, image (package D, slice D5)

Design: `docs/function-runner-plan.md` §4 ("Reports"), workplan §2 D items 1, 6, 8. Builds on D1–D3.
`FunctionContext` services are D4 and independent of this slice.

## 1. `FnHostMain`

`io.flowcatalyst.fnhost.FnHostMain` — `main`: `Logging.configure` (the server's — same JSON lines,
same fields), `HostEnv.load(EnvReader.system())` (a startup error prints one line naming every
missing/invalid variable and exits 2), `FnHost.start()`, a shutdown hook calling `FnHost.close()`
(drain, heartbeat `DRAINING`, wait for in-flight up to `FC_DRAIN_TIMEOUT_SECONDS`, exit 0).
`FC_EXIT_AFTER_START=true` exits 0 after a successful start (the server's AOT-training convention).
No picocli, no sub-commands: it is a daemon.

`function-host/pom.xml` (authorised): `maven-shade-plugin` producing
`flowcatalyst-function-host-<v>-exec.jar` with `Main-Class`, configured the way `server/pom.xml`
configures its own exec jar (service-file merging, signature-file exclusion) — copy that block, do
not invent one.

The observability listener binds **before** the first reconcile attempt (so `/ready` can answer
`STARTING`); the function listener still binds after it (`function-host-listener.md` §5).

## 2. Observability listener — `FC_METRICS_PORT` (default 9090), HTTP/1.1, all interfaces

Independent of the function listener (its own Vert.x server, one event loop): a saturated function
port must not make the process look dead.

- `GET /health` — liveness, not readiness. Before start-up has completed (`FnHost#start` has not yet
  returned) it is unconditionally 200 `{"status":"UP"}` — a slow first reconcile/load must never fail
  a liveness probe and get the task killed mid-boot. Once start-up HAS completed it tells the truth
  about whether this process can still do its job (§3 item 3): 503 `{"status":"LISTENER_DOWN"}` if the
  FUNCTION listener somehow is not bound, 503 `{"status":"RECONCILER_DOWN"}` if the reconcile loop's
  thread has died (§1.3's amended `Error` handling — any Error OTHER than a metaspace-family one ends
  the loop), else 200. Deliberately NOT draining- or platform-outage-aware — those are the process
  still doing its job — only `/ready` reports those; the container `HEALTHCHECK` points at this
  endpoint (`docs/deployments.md`), so ECS replaces the task on either 503 reason.
- `GET /ready` — 200 once the first reconcile has **succeeded**, the host is not draining, the
  FUNCTION listener is bound and the reconcile loop's thread is alive; else 503 with
  `{"status":"STARTING"|"DRAINING"|"PLATFORM_UNREACHABLE"|"LISTENER_DOWN"|"RECONCILER_DOWN"}`.
  (`PLATFORM_UNREACHABLE` only before the first success: after that, an outage keeps serving what is
  loaded and stays ready — D2 R6.) `LISTENER_DOWN`/`RECONCILER_DOWN` are §3 item 3's own addition: a
  reconcile that failed catastrophically enough could previously leave `/ready` reporting `UP` forever
  while the FUNCTION port never opened at all (the original defect, `docs/function-runner-report.md`'s
  "Metaspace at 50%" dated finding) — precedence is `DRAINING` > `STARTING` > `PLATFORM_UNREACHABLE` >
  `LISTENER_DOWN` > `RECONCILER_DOWN` > `READY`.
- `GET /metrics` — Prometheus text from one `PrometheusRegistry` (the library already on the class
  path through `server`), plus `JvmMetricsRegistration` as the server does.

### 2.1 Never running into the wall (§3 item 1) and never leaving the host half-started (§3 item 2)

`JvmFunctionLoader#load` is never even attempted (warm, lazy `ensureLoaded`, or a pinned candidate)
when free metaspace is already below a reserve (`max(64 MiB, 5% of the Metaspace pool's own max)`,
`MetaspaceGuard`) — the refusal is an ordinary load failure, `LOAD:METASPACE_HEADROOM`, distinct from
the backstop `LOAD:OUT_OF_METASPACE` (an actual `OutOfMemoryError` caught around one load). The old
version (if any) keeps serving, a lazy/pinned caller gets `503 FUNCTION_UNAVAILABLE` + `Retry-After`,
and the next reconcile cycle re-checks (metaspace is only reclaimed after a collection, so a check
that is still below the reserve requests at most ONE `System.gc()` per reconcile cycle, never per
load, before re-reading). Separately, `FnHost#start` wraps the very FIRST `reconcileOnce` call so
that ANY `Throwable` escaping it (guarded logging: `GuardedLog`, falling back to a preallocated
`System.err.write` line if the ordinary log call itself throws) never stops start-up from going on to
bind the FUNCTION listener — a host that serves whatever it managed to load beats a zombie that never
binds at all. `docs/function-runner-report.md`'s "Metaspace at 50%" dated finding is this defect;
its own follow-up section has the fix and the re-measured numbers.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `fc_fn_invocations_total` | counter | `address`, `version`, `outcome` | `outcome` ∈ `ok` (any status below 400 — a redirect is not an error), `not_found` (the host refused without revealing whether the address or version exists — unknown address, and every versioned refusal before reach is established), `client_error` (4xx from the function), `retry` (429 from the function), `error` (5xx from the function or a throw), `timeout`, `busy` (permit refused), `unauthorized`, `unavailable` — host refusals count, with `version` = `-` when no version was resolved |
| `fc_fn_duration_seconds` | histogram | `address` | invocation time, only when the function was entered; buckets 5 ms … 60 s |
| `fc_fn_active` | gauge | `address` | invocations in flight |
| `fc_fn_permits_available` | gauge | `scope` = `host` \| `function`, `address` (empty for `host`) | |
| `fc_fn_loaded` | gauge | — | loaded functions; `fc_fn_warm` likewise for warm ones |
| `fc_fn_load_errors_total` | counter | `reason` | `LoadOutcome.Refused` reasons + prepare failures |
| `fc_fn_reconcile_total` | counter | `outcome` = `changed` \| `not_modified` \| `failed` | |
| `fc_fn_last_reconcile_success_timestamp_seconds` | gauge | — | the alert: "no successful reconcile for N minutes" |

**Label cardinality is bounded by what is loaded**: `address` series are removed when a function
leaves desired state (a host that has served 10 000 short-lived functions must not export 10 000
dead series). Unknown addresses (404s) are never a label value — they count under `address="-"`.

JFR: `io.flowcatalyst.fnhost.FunctionInvocationEvent` (`jdk.jfr.Event`: address, version,
invocationId, status, outcome; begin/commit around the invocation; `shouldCommit()` respected) —
CONVENTIONS §8 "JFR events at the component's own semantic points".

## 3. Image — `function-host/Dockerfile` (+ `docker/` helpers reused)

Same shape as the server's: build stage (`mvn -pl function-host -am -DskipTests package`), `jdeps` +
`jlink` (same extra modules, **plus `jdk.compiler`? — no**: the host never compiles; add only what
`jdeps` reports and the server's reflection list), bare Alpine, non-root user, the server's
`docker/jvm-opts.sh` memory fence (`jvm-memory.md`) and entrypoint pattern, `HEALTHCHECK` on
`/health` of `FC_METRICS_PORT`, `EXPOSE 8080 9090`, a writable `FC_FN_CACHE_DIR` volume path owned by
the user. No AOT cache in this slice (the class set depends on what functions load; revisit with
numbers). **Metaspace**: the fence script gets `-XX:MaxMetaspaceSize` for this image only, derived as
a fraction of the container limit (start: 25 %) — functions are class loaders, and an unbounded
metaspace turns a leak into a host OOM-kill instead of a catchable `OutOfMemoryError: Metaspace`
that fails one load. Say in the README section what each of heap / metaspace / direct gets.

`docs/deployments.md` gains a "function host" service section: env table (`HostEnv`), ports, the
Service Connect alias convention `fn-<pool>` that `FC_FN_POOL_URL`'s default assumes, the
`platform:function-host` service-account recipe (as the router's), sizing note.

## 4. Tests (one mutant per condition; absence as well as presence)

| # | Behaviour | Mutant |
|---|---|---|
| P1 | `/ready`: 503 `STARTING` before the first reconcile; `PLATFORM_UNREACHABLE` after a failed first one; 200 after a success; **stays 200** through a later outage; 503 `DRAINING` after `drain()`; `/health` 200 throughout | ready on first attempt rather than first success; unready on any failure |
| P1b | §3 item 3: `/health` stays 200 before start-up completes regardless of listener/loop state; once complete, 503 `LISTENER_DOWN` if the function listener is not bound, 503 `RECONCILER_DOWN` if the loop thread has died, else 200; `/ready` is `READY` only with reconciler-success AND listener-bound AND loop-alive, precedence `DRAINING` > `STARTING` > `PLATFORM_UNREACHABLE` > `LISTENER_DOWN` > `RECONCILER_DOWN` > `READY` | check listener/loop before start-up completes; ignore listenerBound/reconcileLoopAlive entirely; wrong precedence among the two new states |
| P1c | §3 item 1: a load is refused (`LOAD:METASPACE_HEADROOM`) WITHOUT being attempted when free metaspace is below the reserve, at the exact boundary (`>=`, not `>`); an unbounded pool max turns the guard off; at most one `System.gc()` is requested per reconcile cycle, never per load | attempt the load anyway; off-by-one at the boundary; treat -1 as a real max; request a GC per load |
| P1d | §3 item 2: an Error (metaspace-family or not) escaping the very first `reconcileOnce` inside `FnHost#start` never stops the FUNCTION listener from binding; inside `ReconcileLoop`, a metaspace-family `OutOfMemoryError` (including one wrapped in `InternalError`/`NoClassDefFoundError`) is caught and the loop continues, but any OTHER `Error` still ends it | let the Error escape `start()` uncaught; swallow every Error in the loop, not just metaspace ones; treat every Error as fatal in the loop |
| P2 | each `outcome` label is produced by exactly the situation that defines it (ok, client_error, retry, error via 5xx, error via throw, timeout, busy, unauthorized, unavailable), and **only** that one increments | collapse two outcomes; count refusals as `ok` |
| P3 | duration is observed only when the function was entered (a `busy` refusal adds no observation) | observe always |
| P4 | `fc_fn_active` rises while a call is parked and returns to 0 after success, throw **and** timeout-then-return | decrement only on success |
| P5 | series for an address disappear from `/metrics` after the function leaves desired state; a 404 for an unknown address appears only under `address="-"` | keep series; label by requested address |
| P6 | `fc_fn_last_reconcile_success_timestamp_seconds` moves on success and **not** on failure or not-modified? — decide: not-modified is a success (the platform answered); pin that | move on failure |
| P7 | the metrics listener answers while the function listener's single event loop is blocked (park a handler) | one shared Vert.x server |
| P8 | `FnHostMain`: missing variables ⇒ exit code 2 and one line naming all of them (run `main` through a seam that returns the exit code rather than calling `System.exit` in tests); `FC_EXIT_AFTER_START` ⇒ 0; shutdown path calls `close()` once | exit 1; name only the first |
| P9 | the exec jar starts: `java -jar target/*-exec.jar` with a fake platform (local HTTP server answering the token and control routes) reaches `/ready` 200 — an integration test under `failsafe`? **No new plugin**: a surefire test that skips itself when the exec jar is absent, plus a `make fnhost-smoke` target that builds then runs it; say in the report whether CI runs it | — |
| P10 | JFR event committed with the right fields (JFR `RecordingStream` in-test) | never commit |

The Dockerfile is verified by building it (`docker build -f function-host/Dockerfile .`) and running
the image against the same fake platform until `/ready` is 200 — done by the orchestrator or the
agent if Docker is available; report the image size and the jlink module list.
