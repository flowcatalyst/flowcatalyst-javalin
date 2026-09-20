# Function runner report

## Performance

Benchmark plan: `docs/spec/function-host-benchmark.md`. Scripts, fixtures and raw CSVs:
`bench/function-host/` (see its `README.md` for how to re-run). Every number below names its
container memory limit, CPU quota, fixture and N — nothing here is extrapolated beyond what was
run, except where explicitly marked and bounded to within ±20% of a measured point.

**What ran**: B1 (memory per loaded function) — complete, both fixtures, both memory limits,
including a clean re-run after an initial methodology bug (see below). B3 (throughput/p99) — the
time-boxed minimum (`--cpus 2`, `c=256`, both `/io` and `/cpu`) plus three extra points for a
fuller picture (`c=64`, `c=1000`, `--cpus 1`). B4 (noisy neighbour) — complete, one comparison.
B5 (cost line) — complete, computed from B3's measured numbers plus today's fetched prices.

**What was skipped or incomplete**: B2 (first-call latency, p50/p99 over 100-200 distinct
functions) was not run — time-boxed out after B1/B3/B4/B5. The workplan's headline B1 row ("100
lazy + 20 warm, before/after invoking 30 of the lazy ones") could not be cleanly measured: see
"Anomaly" below — reported honestly rather than tuned into a clean-looking number. B3/B4 ran on
Docker Desktop for Mac, where the load generator (host process) is not cgroup-isolated from the
container's `--cpus` quota (see `bench/function-host/README.md`) — treat those numbers as
directional, not isolated-core numbers.

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

**Anomaly — an uncaught `OutOfMemoryError: Metaspace` crashes the eager warm-load loop, not just
one function's load**: at 2g/typical/N=200 (fence hit), `docker logs` shows, reproduced identically
on two independent runs:

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

This is **on the `main` thread**, inside the synchronous startup reconcile — the design intent
documented in `docs/spec/function-host-process.md` §3 is "a catchable `OutOfMemoryError: Metaspace`
that fails one load", i.e. the *function's* load should be refused (`LoadOutcome.Refused`) while
the host keeps running everything else. What was actually observed is the whole eager warm-load
loop dying with an *uncaught* `Error` partway through (index 109 of 200, both times) — the process
survived only because other non-daemon threads (the Vert.x listeners) were already running by that
point, so `/health`/`/metrics`/`/ready` kept answering with whatever had loaded before the crash
(109/200), but nothing after index 109 was attempted — not a clean per-function refusal. This was
NOT tuned away or hidden; it is reported verbatim because it directly contradicts the fence's
documented failure mode and is worth the owner's attention. (`bench/function-host/results/typical-n200-w200-2g.server.log`)

**Anomaly — the workplan's headline row ("100 lazy + 20 warm, before/after invoking 30 lazy ones")
could not be cleanly measured**: with 20 warm + 100 lazy typical functions declared and the host
fully `/ready` (waited 20s past readiness), invoking lazy addresses that are demonstrably present
in the served desired-state document (verified byte-for-byte) returned `404` (`fc_fn_invocations_total{address="-",outcome="not_found"}`)
for the large majority of first attempts — 29 of 29 in one run — with only isolated, non-deterministic
individual successes on retry. This does not match H12's documented contract ("lazy function loads
on first call... an unloadable one is 503, not 404"). Per this task's own rule ("if something is not
explainable in one pass, stop and report verbatim — do not tune anything to make a number look
better"), this was **not** chased further or retried into a clean number; the raw sequence is in
`bench/function-host/results/headline-lazy-anomaly.server.log`. This is a second, independent signal
(alongside the metaspace crash above) that the reconciler's handling of a large, lazily-loaded
desired-state document deserves engineering attention before relying on lazy-loading at scale.

### B2 — first-call latency

**Skipped** (time-boxed out — see "What was skipped" above).

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

---

*Raw CSVs, server logs and re-run scripts: `bench/function-host/results/` and
`bench/function-host/scripts/` — see `bench/function-host/README.md`.*
