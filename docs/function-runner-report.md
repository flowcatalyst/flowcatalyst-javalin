# Function runner report

## What shipped (branch `function-service`, 2026-09-18 → 2026-09-21)

Each package has its own spec under `docs/spec/`; each spec opens with a table of where it departs
from `function-runner-workplan.md` / `function-runner-plan.md` and why. Owner rulings R1–R13 are
recorded in the specs that apply them; live state is `docs/STATUS.md`.

| Package | Spec | What it is |
|---|---|---|
| A | `function-registry.md` | `fn_` schema (V13, Java-only), addresses, route patterns, manifest (strict + stored readers, limits frozen at publish), entities, repositories |
| B | `function-api.md` | functions, signer policies, publish / promote / retire, control plane (desired state + ETag, heartbeat), status, permissions and roles, events; routes outside the Go lockfile |
| C | `function-artifacts.md` | artifact store by blob digest (`file://`, `oci://`), **JDK-only** Sigstore bundle verification (Rekor v1, no SCT check — both written down) |
| I | `function-invocation.md` | every invocation is HTTP at `/functions/{address}[:{version}]/{path}`; manifest `endpoints` / `subscriptions` / `schedules` / `public`; wiring created at promote with subscription source `FUNCTION` (deliberate divergence from Go) |
| D | `function-host-core.md`, `-reconciler.md`, `-listener.md`, `-process.md`, `function-context.md` | API jar (release 21, zero deps), filtering class loader, reconciler, listener (webhook / platform / none auth, permits, deadlines, versioned calls), config + secrets + DB pools + HTTP allowlist + emit-owned-events, process (`/health` `/ready` `/metrics`, JFR, image, metaspace share) |
| E | `function-developer-surface.md` | fcdev hosts functions, `fcdev fn` CLI, `examples/function-hello`, workflow templates, `docs/functions.md` |
| F | `function-public-routes.md` | domain claims (TXT), route sync at promote, public listener, CORS |
| — | `dispatch-delivery-credentials.md` | landed on `main`: dispatch-job webhooks are signed (they went out bare) |

**Not built, by decision:** `@AsFunction` SDK annotation (superseded by manifest-declared wiring);
`s3://` store and ECR credentials (no SDK module on the class path); wasm runtime (phase 3); weighted
aliases (P4). **Owed:** a registry for `fnhost-image.yml`;
the ops runbook beyond `docs/deployments.md`'s sizing rule; the unexplained intermittent test
failures and the random-order fragility listed in `docs/STATUS.md`.

**What the process found that tests alone had not** (orchestrator single-condition mutants and
end-to-end steps, after agents reported green): a Sigstore checkpoint not bound to its inclusion
proof; platform functions leaking into tenant lists; a candidate version routable as live; a DSN
password in a `toString`; `/health` wired to a constant; the host validating tokens against the
wrong issuer; `.localhost` auto-verification wired on for every deployment; one corrupt manifest
failing desired state for every pool; an uncaught metaspace `OutOfMemoryError` leaving a host
half-started with `/ready` 200.

## Performance

Benchmark plan: `docs/spec/function-host-benchmark.md`. Scripts, fixtures and raw CSVs:
`bench/function-host/` (see its `README.md` for how to re-run). Every number below names its
container memory limit, CPU quota, fixture and N — nothing here is extrapolated beyond what was
run, except where explicitly marked and bounded to within ±20% of a measured point.

**What ran**: B1 (memory per loaded function) — complete, both fixtures, both memory limits,
including a clean re-run after an initial methodology bug (see below). B2 (first-call latency,
p50/p99 over 100 distinct functions) — complete, both fixtures, re-run after the padding fix
below. The workplan's headline row (100 lazy + 20 warm, before/after invoking 30 of the lazy ones)
— complete, re-measured clean after the two defects below were fixed. B3 (throughput/p99) — the
time-boxed minimum (`--cpus 2`, `c=256`, both `/io` and `/cpu`) plus three extra points for a
fuller picture (`c=64`, `c=1000`, `--cpus 1`). B4 (noisy neighbour) — complete, one comparison.
B5 (cost line) — complete, computed from B3's measured numbers plus today's fetched prices.

**What was skipped or incomplete**: B3/B4 ran on Docker Desktop for Mac, where the load generator
(host process) is not cgroup-isolated from the container's `--cpus` quota (see
`bench/function-host/README.md`) — treat those numbers as directional, not isolated-core numbers.
B3 was not run against the typical fixture (out of the original time box).

**Two defects found and fixed** (full detail below): (1) an uncaught `OutOfMemoryError: Metaspace`
escaped the reconciler's eager warm-load loop and killed the rest of that cycle's document — fixed
in `Reconciler`/`JvmFunctionLoader` (function-host); (2) the "lazy 404" seen while probing the
headline row was traced to the AD HOC invocation commands (and `bench/function-host/scripts/run-b1.sh`'s
own invoke step) not zero-padding function indices to FakePlatform's fixed 3-digit address format —
a benchmark bug, not a host defect; fixed in `run-b1.sh`, and the headline row/B2 re-measured with
correct addressing.

### B1 — memory per loaded function

Image: `bench/function-host/Dockerfile.bench` (the production `function-host/Dockerfile` plus
`jdk.jcmd`/`jdk.attach` only — production image ships neither, confirmed by listing
`/opt/jre/bin`). All-warm desired-state document per N (so entries are genuinely loaded, not just
declared), one invocation each, two `jcmd GC.run` cycles, RSS sampled 3× 1.5s apart (median taken
— a single `docker stats` sample varied by tens of MB run to run). `loaded` = `fc_fn_loaded`
reported by `/metrics` after a settle-wait (loading a 200-entry, metaspace-heavy "typical"
document is not instantaneous — see "Methodology bug" below).

| fixture | mem | N requested | N loaded | RSS (MB) | heap used (MB) | metaspace used (MB) | compressed class space (MB) | loaded classes | threads |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| lean | 2g | 0 | 0 | 115.1 | 11.4 | 33.8 | 4.0 | 6171 | 27 |
| lean | 2g | 25 | 25 | 106.8 | 11.7 | 34.4 | 4.0 | 6304 | 29 |
| lean | 2g | 50 | 50 | 112.7 | 12.0 | 34.6 | 4.1 | 6331 | 29 |
| lean | 2g | 100 | 100 | 113.3 | 12.5 | 34.9 | 4.1 | 6384 | 29 |
| lean | 2g | 200 | 200 | 121.2 | 13.8 | 35.8 | 4.2 | 6570 | 29 |
| lean | 4g | 0 | 0 | 109.9 | 11.3 | 33.8 | 4.0 | 6169 | 28 |
| lean | 4g | 25 | 25 | 113.5 | 11.8 | 34.4 | 4.0 | 6301 | 28 |
| lean | 4g | 50 | 50 | 113.7 | 12.1 | 34.6 | 4.1 | 6331 | 29 |
| lean | 4g | 100 | 100 | 114.7 | 12.4 | 35.0 | 4.1 | 6384 | 27 |
| lean | 4g | 200 | 200 | 129.3 | 14.2 | 36.0 | 4.2 | 6568 | 28 |
| typical | 2g | 0 | 0 | 152.3 | 11.3 | 33.8 | 4.0 | 6171 | 28 |
| typical | 2g | 25 | 25 | 252.6 | 29.3 | 143.9 | 17.1 | 23613 | 28 |
| typical | 2g | 50 | 50 | 399.9 | 45.8 | 253.2 | 30.2 | 40942 | 29 |
| typical | 2g | 100 | 100 | 687.4 | 78.8 | 471.8 | 56.4 | 75594 | 29 |
| typical | 2g | 200 | **109** (fence) | 763.7 | 82.5 | 510.3 | 61.1 | 81649 | 24 |
| typical | 4g | 0 | 0 | 128.5 | 11.4 | 33.8 | 4.0 | 6169 | 26 |
| typical | 4g | 25 | 25 | 262.3 | 28.2 | 143.9 | 17.1 | 23613 | 27 |
| typical | 4g | 50 | 50 | 412.4 | 44.7 | 253.2 | 30.3 | 40943 | 27 |
| typical | 4g | 100 | 100 | 701.7 | 79.8 | 471.8 | 56.5 | 75594 | 28 |
| typical | 4g | 200 | 200 | 1305.6 | 144.5 | 909.1 | 108.9 | 144985 | 29 |

**Slopes** (least-squares over the fully-loaded points only — i.e. excluding the 2g/N=200 fence
row, which is not a "loaded 200" point):

| fixture | metric | slope | intercept (N=0 baseline) |
|---|---|---:|---:|
| lean | RSS | ~0.05–0.09 MB/function (2g / 4g) | ~110 MB |
| lean | metaspace | ~0.01 MB/function | ~34 MB |
| typical | RSS | 5.4–5.9 MB/function (2g / 4g) | ~120–135 MB |
| typical | metaspace | **4.38 MB/function**, identical at 2g and 4g | ~34 MB |

A lean function (API jar only, ~2 classes) costs almost nothing per instance — the ~110 MB
baseline is the JVM + host framework itself, paid once. A typical function (jackson-databind +
json-schema-validator shaded in, ~700 classes) costs **~4.4 MB of metaspace and ~5.4–5.9 MB of
RSS per loaded instance**, on top of that same baseline.

**The metaspace fence, observed directly**: the image's `FC_JVM_METASPACE_FENCE` sets
`-XX:MaxMetaspaceSize` to 25% of the container limit (512 MiB at 2g, 1024 MiB at 4g —
`docker/jvm-opts.sh`). Predicting the fence from the typical-fixture slope: `(512 − 34) / 4.38 ≈
109` functions at 2g — **matches the observed 109 exactly**. At 4g: `(1024 − 34) / 4.38 ≈ 226`
functions, 13% beyond the largest point actually measured (200, where 909 MB of the 1024 MB cap
was already used) — within the plan's own ±20% extrapolation tolerance, so: **a 4 GiB host holds
roughly 220–230 "typical" functions before hitting the metaspace fence**, not before running out of
RSS/heap headroom (RSS at N=200/4g was 1.3 GB of the 4 GB limit — plenty of room left; metaspace is
the binding constraint at the current 25% fence). Lean's metaspace slope is too close to zero to
extrapolate a fence point within range (would need on the order of tens of thousands of functions);
not a practical concern for this fixture.

**Answering the workplan's question directly**: at the current 25% metaspace fence, **a 4 GiB host
holds ~226 "typical" functions** (owner's realistic-worst-case fixture: shaded Jackson + JSON
Schema validator). The **marginal** cost of one more typical function is ~4.4 MB metaspace / ~5.9 MB
RSS — cheaper than "a Rust task idling at ~10 MB (+ sidecar)" on a per-function basis, once the
one-time ~120–135 MB host baseline is amortised across the functions sharing it. That baseline is
the real trade-off the owner is weighing: one JVM host pays ~125 MB once and ~5–6 MB per additional
typical function; N independent Rust tasks pay ~10 MB (+ sidecar overhead, not measured here) **per
function, with no shared baseline to amortise**. Where the two approaches cross over depends on N
and on the Rust sidecar's real footprint, which this benchmark did not measure.

**Methodology bug found and fixed while running this**: the first B1 pass measured immediately
after `/ready` returned 200. For "typical" at high N, `/ready` flips to 200 once the first reconcile
*attempt* completes, but warm-loading ~100+ metaspace-heavy jars was still visibly in progress
afterward (three `docker stats` samples 1.5s apart varied by **hundreds of MB** on the same
container). The script now polls `fc_fn_loaded` until it is unchanged for three consecutive checks
before measuring. The corrected run is what's tabulated above; the numbers below are why the fix
mattered.

**Defect 1 (found, fixed) — an uncaught `OutOfMemoryError: Metaspace` crashed the eager warm-load
loop, not just one function's load**: at 2g/typical/N=200 (fence hit), `docker logs` showed,
reproduced identically on two independent runs:

```
Exception in thread "main" java.lang.OutOfMemoryError: Metaspace
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	...
	at fn:bench.typical.f109@1//fixture.typical.TypicalFn.init(TypicalFn.java:43)
	at io.flowcatalyst.fnhost.load.LoadedFunction.lambda$init$0(LoadedFunction.java:136)
	at io.flowcatalyst.fnhost.reconcile.Reconciler.attachContextAndInit(Reconciler.java:556)
	at io.flowcatalyst.fnhost.reconcile.Reconciler.applyLoadOutcome(Reconciler.java:501)
	at io.flowcatalyst.fnhost.reconcile.Reconciler.loadWarm(Reconciler.java:451)
	at io.flowcatalyst.fnhost.reconcile.Reconciler.load(Reconciler.java:439)
	at io.flowcatalyst.fnhost.reconcile.Reconciler.reconcileOnce(Reconciler.java:312)
	at io.flowcatalyst.fnhost.FnHost.start(FnHost.java:106)
	at io.flowcatalyst.fnhost.FnHostMain.run(FnHostMain.java:44)
	at io.flowcatalyst.fnhost.FnHostMain.main(FnHostMain.java:24)
```

Root cause: `Reconciler#attachContextAndInit` caught `Exception` around `Function#init`, but
`OutOfMemoryError` is an `Error`, not an `Exception` — it fell straight through, uncaught, out of
the synchronous startup reconcile, killing the eager warm-load loop mid-document (index 109 of
200) and leaving every entry after it never even attempted. `JvmFunctionLoader#load` had the same
gap around class definition and the entrypoint's constructor.

Fixed in `function-host/src/main/java/io/flowcatalyst/fnhost/reconcile/Reconciler.java`
(`attachContextAndInit`, `applyLoadOutcome`, `ensureLoaded`) and
`function-host/src/main/java/io/flowcatalyst/fnhost/load/JvmFunctionLoader.java` (`load`): a
metaspace `OutOfMemoryError` — matched by message, `Reason.OUT_OF_METASPACE` /
`LOAD:OUT_OF_METASPACE` — is now caught around exactly one function's class loading/construction/
`init`, closes that one class loader (reclaiming the metaspace it consumed), leaves the old version
(if any) serving, logs at ERROR, and lets the reconcile continue to the rest of the document; a
Java-heap `OutOfMemoryError` is deliberately **not** caught anywhere. Two related findings surfaced
while proving this against a REAL metaspace exhaustion (a forked JVM at the SAME 512 MiB fence a
real `--memory 2g` container gets, `MetaspaceFenceForkTest`, gated behind
`-Dfc.fnhost.metaspaceTest=true`, run by hand — see test tables below): (a) the metaspace exhaustion
does not always arrive as a bare `OutOfMemoryError` — `invokedynamic` bootstrap (a library's own
lambda, under `com.networknt.schema.ValidatorTypeCode.<clinit>`) wrapped it in `InternalError`, and
a class that already failed to initialise once wrapped it in `NoClassDefFoundError` with the
original error embedded only as text, not a real nested `Throwable` — both are now unwrapped by
`JvmFunctionLoader#findMetaspaceOom`, which walks the cause chain AND falls back to a message-text
match for the latter case; (b) the diagnostic log line for the failure can itself need a
not-yet-loaded Logback class and throw a second `OutOfMemoryError` — `Reconciler` now closes the
loader BEFORE logging and falls back to a bare `System.err` line (built with `StringBuilder`, never
`+`, which is `invokedynamic` and can fail the identical way) if logging itself throws.

**Defect 2 (found: benchmark, not the host) — the "lazy 404" seen probing the headline row**: with
20 warm + 100 lazy typical functions declared and the host fully `/ready`, invoking lazy addresses
that are demonstrably present in the served desired-state document (verified byte-for-byte)
returned `404` (`fc_fn_invocations_total{address="-",outcome="not_found"}`) for the large majority
of first attempts — 29 of 29 in one run, `bench/function-host/results/headline-lazy-anomaly.server.log`.
Root cause, found by reproducing with a JUnit `FakeControlPlane` harness at the same 20+100 scale
(100% success, no 404s — ruling out a Reconciler bug), then parsing FakePlatform's exact raw
desired-state JSON through `DesiredDocument.parse()` (all 120 entries present, none dropped — ruling
out the lenient parser), then reproducing directly against a real Docker container: FakePlatform
always names addresses with a fixed 3-digit zero pad (`String.format("%03d", i)`,
`bench.typical.f020`…`f119`), but the ad hoc invocation commands used to probe the headline row (and
`bench/function-host/scripts/run-b1.sh`'s own "one invocation each" step, `seq -w`) padded to the
width of the LARGEST number in THEIR OWN range — for any range under 100, that is 1 or 2 digits
("bench.typical.f30" instead of "f030"), so the request never matched any entry and the host
correctly answered `FUNCTION_NOT_FOUND`. Confirmed directly: `seq -w 30 59` → 404 for all 30;
the same 30 indices, zero-padded to 3 digits, → 200/500 (app-level; no body was posted) for all 30,
zero 404s. This also explains "isolated, non-deterministic successes on retry" — an index ≥ 100
needs no padding at all and happens to match by chance. Not a `function-host` defect;
`bench/function-host/scripts/run-b1.sh` fixed (`seq -f "%03.0f"`, always 3 digits regardless of
range) — B1 itself was unaffected (its own document is always all-warm, so this step's job is only
to force an access, and a 404'd access unloads nothing), but the bug was real and latent in that
script for any `n<100` point.

**Headline row, re-measured clean** (fixture `typical`, `--memory 4g --cpus 2`, both defects fixed,
image rebuilt): 20 warm + 100 lazy declared, `/ready`, `fc_fn_loaded` settled at 20, waited a further
20s, then 30 lazy addresses (`f020`–`f049`, correctly zero-padded) invoked concurrently
(`xargs -P 30`): **30/30 succeeded** (HTTP 500 `EMPTY_BODY` — an application-level outcome from the
`typical` fixture's own JSON-schema validation, since no body was posted; the host itself never
answered 404 or hung), `fc_fn_loaded` moved from 20 → 50 (20 warm + the 30 just-loaded lazy ones,
exactly), and `fc_fn_invocations_total{...,outcome="not_found"}` is absent from `/metrics` entirely
— zero not-found outcomes over the whole run.
(`bench/function-host/results/headline-fixed.server.log`)

**The `SLF4J(W): No SLF4J providers were found` noise in every container log** is the FUNCTION's own
stderr, not a host defect: the `typical` fixture shades its own copy of `slf4j-api` (a transitive
dependency of json-schema-validator) with no logging backend bound inside that isolated class
loader, so each loaded instance prints its own one-time "no provider" warning — three lines per
`typical` load, matching the loaded-function count exactly in every server log above. The HOST's own
logging is separately confirmed configured and working: `Logging.init(envReader)` runs at
`FnHostMain` startup (`function-host/src/main/java/io/flowcatalyst/fnhost/FnHostMain.java:34`),
Logback (`ch.qos.logback:logback-classic`) is a transitive dependency of `function-host` via
`server`'s `pom.xml` and is present in the exec jar, and every server log captured for this report
carries the host's own structured JSON lines (`"function host started"`,
`"function version failed to load: out of metaspace"`, etc.) alongside the fixture noise — the host
was never silently failing to log. Not suppressed, per instructions: it is the function's output.

### B2 — first-call latency

Fresh container per fixture, all-lazy document (`warm=0`), 100 distinct addresses, one sequential
`curl` per address (`%{time_total}`) — the FIRST call to each, so this is genuinely cold-start
(class load + construct + `init`) latency, not steady-state. `--memory 2g` (lean) / `--memory 4g`
(typical), `--cpus 2`.

| fixture | N | p50 | p90 | p99 | min | max | fc_fn_loaded after |
|---|---:|---:|---:|---:|---:|---:|---:|
| lean | 100 | 3.18ms | 7.07ms | 9.78ms | 1.70ms | 46.02ms | 100 |
| typical | 100 | 58.40ms | 66.86ms | 97.10ms | 53.23ms | 126.70ms | 100 |

Every one of the 200 calls across both fixtures succeeded (`fc_fn_loaded` reached the full 100 for
both; no `not_found`/`unavailable` outcomes) — the lazy-load path (H12) holds at this scale. Lean's
first call is dominated by the host's own dispatch/JVM overhead (a lean function is ~2 classes);
typical's ~55-60ms median is almost entirely `TypicalFn.init()` compiling its JSON schema plus
defining ~700 shaded jackson-databind/json-schema-validator classes into a fresh class loader — the
same per-instance cost B1 measured in metaspace (~4.4 MB) shows up here as time. The one 46ms lean
outlier (vs. a 1.7-9.8ms typical range) and the 126.7ms typical max are consistent with ordinary
JIT/GC warm-up noise on the first few calls of a run, not a distinct mode — not chased further.
(raw per-call latencies: `bench/function-host/results/b2-lean-latencies-ms-raw.txt`,
`bench/function-host/results/b2-typical-latencies-ms-raw.txt`)

### B3 — steady-state throughput / p99

Fixture: lean, one warm function, `--memory 2g`. Load: `wrk` as a **host process** (not
cgroup-isolated from the container's `--cpus` quota on this machine — see README). 30s warm-up +
60s measured per point.

| endpoint | concurrency | host `--cpus` | req/s | p50 | p90 | p99 | mean latency | 429 `busy` | GC pause (measured Δ) | container CPU% (sample) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `/io` (5ms sleep) | 64 | 2 | 4,081 | 10.15ms | — | 216.18ms | — | 0 | 0.227s | 44.99% |
| `/io` | 256 | 2 | 15,311 | 11.37ms | 167.21ms | 575.47ms | — | 0 | 0.298s | 66.57% |
| `/io` | 1000 | 2 | 50,675 | 19.36ms | 21.60ms | 195.49ms | 25.09ms | 0 | 0.611s | 123.71% |
| `/io` | 256 | 1 | 19,006 | 11.11ms | 113.46ms | 548.08ms | — | 0 | 1.756s | 67.99% |
| `/cpu` (~1ms CPU) | 256 | 2 | 1,760 | 171.34ms | 245.20ms | 273.96ms | — | 0 | 0.517s | **196.80%** (saturated) |

Zero `429 busy` refusals at every point — `maxConcurrency` was set to 2000 (host-global cap 4000),
deliberately far above what these points needed, per the plan's own instruction not to show the
permit limiter unless deliberately testing it. `/cpu` at c=256/cpus=2 fully saturates the 2-CPU
quota (196.8%) — that endpoint is genuinely CPU-bound as designed; `/io`'s throughput scales with
concurrency and CPU% never saturates (max observed 123.71% of the 200% quota) because it is
parked-not-spinning, as designed. The `--cpus 1` vs `--cpus 2` `/io` numbers are close (19.0k vs
15.3k req/s) and in the **opposite** direction of what CPU count alone would predict — expected,
since `/io` barely uses CPU; treat the difference as run-to-run noise (JIT warm-up, GC timing), not
a real CPU-count effect for this endpoint.

### B4 — noisy neighbour

`--cpus 2`, `--memory 2g`. Function A = lean `/io`, unconstrained `maxConcurrency`, held at a fixed
500 req/s (open-loop client, not wrk's closed-loop model — `bench/function-host/scripts/RateClient.java`).
Function B = lean `/cpu`, `maxConcurrency=8` (confirmed applied via the served manifest), hammered
by 16 concurrent virtual threads for 25s (`bench/function-host/scripts/Hammer.java`) — B's own
permit cap refused 66,916 of its own attempts (`429`), letting through 40,478; container CPU was
**196.7%** (fully saturated) throughout B's run.

| | A's p50 | A's p99 |
|---|---:|---:|
| A alone | 8.03ms | 11.90ms |
| A while B saturates the host | 10.80ms (**+34%**) | 29.49ms (**+167%**) |

A's p50 degrades moderately; p99 degrades sharply — the honest cost of a shared JVM under CPU
contention from a neighbour, even though A's own endpoint does almost no CPU work itself (it is
scheduling/GC/event-loop contention, not direct competition for the same work).

### B5 — cost line

Derived **only** from the B3 `/io` measurements above (the lean fixture) at 50% utilisation.
Prices fetched today (2026-09-20); one could not be confirmed from AWS's own JS-rendered pricing
page and is cited from a third-party aggregator instead (noted below) — none were guessed from
memory and presented as checked.

| price | value | source | fetched |
|---|---|---|---|
| r7g.large on-demand | $0.1071/hr (2 vCPU, 16 GiB, us-east-1) | instances.vantage.sh/aws/ec2/r7g.large (third-party aggregator of AWS's public pricing API — AWS's own `aws.amazon.com/ec2/pricing/on-demand/` page is JS-rendered and returned no numeric table to WebFetch) | 2026-09-20 |
| Fargate ARM (Linux/ARM, us-east-1) | $0.0000089944/vCPU-s, $0.0000009889/GB-s | aws.amazon.com/fargate/pricing/ | 2026-09-20 |
| Lambda (x86, on-demand) | $0.0000166667/GB-s, $0.20/M requests | aws.amazon.com/lambda/pricing/ | 2026-09-20 |
| Lambda Managed Instances | EC2 price × 1.15 + $0.20/M requests | aws.amazon.com/lambda/pricing/ | 2026-09-20 |
| Lambda Arm/Graviton per-GB-s price | **not fetched** — the page's Arm pricing is behind a tab WebFetch could not render | — | — (not used below) |

Invocations/month at 50% utilisation, from the matching-CPU-count B3 measurement (peak req/s ×
0.5 × 2,592,000s/month):

- 2 vCPU (`--cpus 2`, c=1000 io, 50,675 req/s peak): **65.7 billion/month**
- 1 vCPU (`--cpus 1`, c=256 io, 19,006 req/s peak): **24.6 billion/month**

| platform | monthly cost | $/million invocations |
|---|---:|---:|
| r7g.large (2 vCPU/16GiB, 2-vCPU measurement) | $77.11 | **$0.00117** |
| Fargate ARM 1 vCPU/2 GB (1-vCPU measurement) | $28.44 | **$0.00116** |
| Fargate ARM 2 vCPU/4 GB (2-vCPU measurement) | $56.88 | **$0.00087** |
| Lambda classic (x86, 512 MB, 25.09ms measured mean duration) | — (per-invocation) | **$0.4091** (= $0.2091 duration + $0.20 request) |
| Lambda Managed Instances (r7g.large × 1.15) | $88.68 | **$0.2014** (dominated by the flat $0.20/M request fee) |

At this fixture's throughput (a trivial 5ms-sleep I/O function on a shared host serving tens of
thousands of req/s), the shared-JVM host is **~350× cheaper per million invocations than classic
Lambda** and **~170× cheaper than Lambda Managed Instances** — because a shared host amortises its
fixed hourly cost across an enormous number of cheap requests, while Lambda bills duration (or a
flat request fee) per invocation regardless of how little work each one does. This comparison is
specific to the **lean** fixture's throughput; a heavier (e.g. "typical"-shaped) workload would
narrow the gap substantially — B3 was not run against the typical fixture (out of the time box), so
that comparison is not available here.

### Metaspace at 50% (2026-09-20)

Owner ruling: `FC_JVM_METASPACE_FENCE` (boolean, 25% fixed, added ON TOP of
`-Xmx`) replaced by `FC_JVM_METASPACE_PERCENT` (integer 10–70, function host
default 50, SUBTRACTED from `-Xmx` — `docs/spec/jvm-memory.md` §4). Re-ran B1
against the rebuilt production-equivalent bench image
(`bench/function-host/Dockerfile.bench`, which shares `docker/jvm-opts.sh`
and `function-host/docker/entrypoint.sh` verbatim with the real
`function-host/Dockerfile`) at the new default. `bench/function-host/artifacts`
regenerated at N=500 per fixture first (the existing 200 distinct jars were
not enough to request the higher N points below — `FakePlatform`'s
`desiredState` handler throws, uncaught, when asked to digest a jar index
that does not exist, which the JDK's built-in `HttpServer` turns into a bare
closed connection with no response; a benchmark-tooling gap, not a
`function-host` defect, worth noting for whoever runs this next).

**Typical fixture, `--memory 2g`, N = 100, 200, 300, all warm**, and
**`--memory 4g`, N = 200, 400, 500, all warm** (same methodology as the
original B1: settle-wait on `fc_fn_loaded`, two `jcmd GC.run` cycles, RSS
median of 3 samples 1.5s apart):

| fixture | mem | N requested | N loaded | RSS (MB) | heap used (MB) | metaspace used (MB) | compressed class space (MB) | loaded classes | threads |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| typical | 2g | 100 | 100 | 717.3 | 79.1 | 472.2 | 56.5 | 75705 | 28 |
| typical | 2g | 200 | 200 | 1227.8 | 146.1 | 909.2 | 108.7 | 145002 | 28 |
| typical | 2g | 300 | **226** (fence) | 1362.9 | 158.8 | 1021.4 | 122.4 | 162773 | 21 |
| typical | 4g | 200 | 200 | 1223.7 | 145.5 | 909.2 | 108.7 | 145001 | 28 |
| typical | 4g | 400 | 400 | 2399.2 | 278.8 | 1782.7 | 212.3 | 283609 | 28 |
| typical | 4g | 500 | **458** (fence) | 2654.2 | 310.0 | 2036.3 | 242.5 | 323763 | 21 |

(raw: `bench/function-host/results/b1-50pct.csv`, server logs
`bench/function-host/results/typical-n{100,200,300}-w{…}-2g.server.log` and
`typical-n{200,400,500}-w{…}-4g.server.log`)

**Where the fence binds**: the per-function metaspace slope is unchanged from
the original 25%-fence measurement — it is a property of the fixture, not the
fence percentage — **4.4 MB/function**, ~34 MB baseline. The prediction from
that slope was **≈225 at 2 GiB** (`(1024 − 34) / 4.4`) and **≈458 at 4 GiB**
(`(2048 − 34) / 4.4`); measured **226** and **458** — matches almost exactly,
the closest the prediction-vs-measurement gap has been in this whole report.
`docker inspect -f '{{.State.OOMKilled}}'` on a live container at both fence
points (a manually-held 2g/N=300 and 4g/N=500 run, checked before removal):
**`false`** — the container itself is never OOM-killed by the kernel; the
JVM fails loudly with a catchable `OutOfMemoryError: Metaspace` instead, as
designed. The fenced-out entries are logged `"function version failed to
load: out of metaspace"` (`Reason.LOAD:OUT_OF_METASPACE`), confirmed present
in both server logs above.

**A finding, root-caused and fixed (2026-09-20 follow-up, `docs/spec/function-host-process.md`
§3)**: pushing well PAST the fence in one all-warm document — 300 requested against a ~226
capacity (a 33% overshoot) at 2 GiB, 500 against ~458 (a 9% overshoot) at 4 GiB — reproducibly
crashed the function host's *synchronous startup* reconcile (`FnHost.start` →
`Reconciler.reconcileOnce`, on the `main` thread) with an uncaught `OutOfMemoryError` once
metaspace was driven deep enough into exhaustion that even the per-entry recovery path's OWN
logging started falling back to the emergency `System.err` line (every one of the 20 failures
logged at 4g/N=500 needed that fallback, not just the first one the original report found). At
that point roughly 20–50 further entries were never even attempted (neither loaded nor logged as
failed) before `main` died. Because `FnObservability` (the `/ready`/`/metrics` listener) binds
BEFORE the first reconcile runs, but `FnHttpServer` (the actual `/functions/...` listener) only
bound AFTER `reconcileOnce` returned, a crashed startup reconcile left `/ready` reporting `UP` with
real metrics while the FUNCTION port never opened at all — an invocation of an already-
successfully-loaded function (`f000`) got a connection failure, not a 200. Materially worse than
"the fenced-out entries fail cleanly and the rest keeps serving" (what the smaller-overshoot
points, and the original report's own N=109/200 case, showed).

**Root cause**: the per-load `OutOfMemoryError` catch (`Reconciler#attachContextAndInit`,
`JvmFunctionLoader#load`) only fires AFTER a load is attempted — class definition, reflection, and
the entrypoint's own constructor/`init` all still ran, right up against the wall, every time. Deep
enough into exhaustion, even the RECOVERY path for that failure (closing the class loader, then
logging it) needed metaspace that no longer existed, and that second failure was an `Error`
`Reconciler#reconcileOnce` never expected to see escape.

**Fix, three parts (`docs/spec/function-host-process.md` §3)**: (1) a `MetaspaceGuard` reads the
`Metaspace` `MemoryPoolMXBean` and refuses a load WITHOUT ATTEMPTING IT — no class definition, no
reflection, nothing that could itself throw — whenever free metaspace is below `max(64 MiB, 5% of
the pool's own max)`; the refusal is an ordinary `LOAD:METASPACE_HEADROOM` failure, not an
`OutOfMemoryError`, and the old version (if any) keeps serving. At most one `System.gc()` is
requested per reconcile CYCLE (never per load) when a check finds itself below the reserve, since
metaspace is only reclaimed after a collection. (2) `FnHost#start` wraps the very first
`reconcileOnce` call so that ANY escaping `Throwable` is logged (guarded: falls back to a
preallocated `System.err.write` line if the ordinary log call itself throws) and start-up
CONTINUES to bind the FUNCTION listener regardless — the ORIGINAL defect above is exactly this
gap. `ReconcileLoop`'s own run loop now also catches a metaspace-family `OutOfMemoryError` the same
guarded way and keeps going; any OTHER `Error` still ends the loop. (3) `/ready` and `/health` now
also depend on the FUNCTION listener actually being bound and the reconcile loop's thread actually
being alive (`LISTENER_DOWN`/`RECONCILER_DOWN`, 503) — so a host that DID end up in the crashed
state above would no longer silently report `UP` forever; `/health` stays 200 unconditionally
before start-up completes (so a slow first load never fails a liveness probe) and tells the truth
after.

**Re-measured** (rebuilt `fnhost-bench-tools` image, same methodology, `fc_fn_loaded` settled, all
warm, real containers, checked before removal):

| mem | N requested | N loaded | `LOAD:METASPACE_HEADROOM` events (cumulative counter — the reconcile loop retries the still-refused entries every 15 s, so this exceeds the distinct entry count) | `LOAD:OUT_OF_METASPACE` events | `/ready` | `/health` | function port | `OOMKilled` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 GiB | 300 | **212** | 176 | **0** | 200 | 200 | open, invocation of a loaded function → 200 | `false` |
| 4 GiB | 500 | **438** | 124 | **0** | 200 | 200 | open, invocation of a loaded function → 200 | `false` |

The guard is now the fence: at the 50% default, metaspace ceilings are 1,024 MiB (2 GiB) / 2,048 MiB
(4 GiB) (`docs/spec/jvm-memory.md` §4.2), and `(ceiling − 34 MiB baseline − reserve) / 4.4 MB` —
reserve = `max(64 MiB, 5%)` — predicts **≈210** at 2 GiB and **≈434** at 4 GiB; measured 212 and
438, matching closely. **Capacity is now measurably LOWER than the raw fence** (226/458 without the
guard vs. 212/438 with it) — the reserve trades a small amount of capacity for never running the
per-function recovery path itself out of room; `docs/deployments.md`'s rule of thumb is updated
with the reserve term. Zero `LOAD:OUT_OF_METASPACE` events at either point: the guard heads off the
wall before a real `OutOfMemoryError` is ever thrown at this fence, which is why `OUT_OF_METASPACE`
is described as a backstop in the spec, not the primary refusal path any more. The single-fork
proof at a raw 512 MiB metaspace pool (300 requested) shows the same shape: 98 loaded, 202
`LOAD:METASPACE_HEADROOM`, 0 `LOAD:OUT_OF_METASPACE` (`MetaspaceFenceForkTest`, run by hand,
`-Dfc.fnhost.metaspaceTest=true`).

**Load check** (the one the first benchmark lacked): 100 typical functions
warm, `--memory 2g --cpus 2` (well inside the ~226 fence — no crash risk),
closed-loop `c=256` for 60s spread round-robin across all 100 addresses, each
POST carrying a 1971-byte JSON body valid against the fixture's own schema
(`bench/function-host/scripts/LoadClient.java` — `wrk` itself cannot post a
body and round-robin across N addresses without a Lua script; the JDK
`HttpClient` had to be forced to `HTTP_1_1` explicitly, since its default h2c
negotiation against this listener multiplexes 256 virtual threads onto ONE
connection and trips its own max-concurrent-streams limit long before the
server is under any real load — found and fixed while first running this):

| req/s | p50 | p99 | mean | peak heap used | GC pause total (Δ over the run) | RSS (post-run) | `OutOfMemoryError` in log |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 11,708 | 1.68ms | 81.92ms | 11.65ms | 211.6 MB (of 591.4 MB max) | 1.611s (of 60s, ~2.7%) | 879 MB / 2048 MB (43%) | **0** |

702,492 requests, 0 client-side errors, 0 non-200 responses over the full
60s. `docker logs` over the whole run carries zero `OutOfMemoryError` lines
of any kind (checked `grep -i outofmemory`) and zero application-level
`ERROR` lines beyond the one `"function host started"` `INFO` line — the
fence at 50% costs nothing extra under real concurrent load well inside its
own capacity. (raw: `bench/function-host/results/` — this check's own
client/server logs were not saved as separate files, per the numbers above;
re-run with `scripts/LoadClient.java` per the README to reproduce.)

**Five lines of findings**:

1. The prediction from the 4.4 MB/function slope, `(percent% × limit − 34) /
   4.4`, matched the measured fence almost exactly at both 2 GiB (226 vs.
   ≈225 predicted) and 4 GiB (458 vs. ≈458 predicted) — the formula in
   `docs/deployments.md`'s function-host section is trustworthy for this
   fixture shape.
2. The container is never OOM-killed at the fence (`OOMKilled=false`
   confirmed directly); the JVM fails loudly with a catchable
   `OutOfMemoryError: Metaspace` per fenced-out entry instead, exactly as
   designed.
3. Pushed far enough past the fence in one all-warm document, the startup
   reconcile's `main` thread could previously die with an UNCAUGHT
   `OutOfMemoryError` once metaspace was driven into full exhaustion — worse
   than the per-entry catchable case, because it left the FUNCTION listener
   never bound at all while `/ready` reported `UP`. Fixed 2026-09-20
   (`docs/spec/function-host-process.md` §3): a headroom guard refuses a
   load before it is ever attempted, `FnHost#start`'s first reconcile is
   guarded against any escaping `Throwable`, and `/ready`/`/health` now also
   depend on the listener being bound and the reconcile loop being alive.
   Re-measured at 2 GiB/N=300 and 4 GiB/N=500: 212 and 438 loaded (vs. 226
   and 458 without the guard — capacity trades down slightly for the
   reserve), zero `LOAD:OUT_OF_METASPACE` at either point, function port
   open and serving, `/ready`/`/health` both 200 — see the dated subsection
   above for the full detail.
4. Well inside the fence, under real closed-loop load (c=256, 60s, 100
   distinct addresses), the fence costs nothing measurable: zero errors,
   zero `OutOfMemoryError`s, GC pause time ~2.7% of wall time, heap peaking
   at 36% of its own cap.
5. The bench tooling itself had two gaps worth fixing for whoever re-runs
   this: `gen-artifacts.sh` needs N ≥ the highest point tested (not just
   ≥ the largest *loaded* count), and a load generator against this
   listener needs `HTTP_1_1` forced or it silently self-limits on
   max-concurrent-streams rather than reflecting server capacity.

---

*Raw CSVs, server logs and re-run scripts: `bench/function-host/results/` and
`bench/function-host/scripts/` — see `bench/function-host/README.md`.*
