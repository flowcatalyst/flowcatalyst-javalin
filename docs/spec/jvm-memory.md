# JVM memory in the container image

Status: spec + owner rulings, 2026-09-14 (§0–3); compact object headers and
the AOT cache ruled 2026-09-15 (§1a); the function host's metaspace addition,
2026-09-19 (§4), turned into a runtime percent setting 2026-09-21 (§4); §4.4's
own residual gap (an uncaught `OutOfMemoryError` past the fence could leave
the FUNCTION listener unbound while `/ready` reported `UP`) fixed 2026-09-20
by the headroom guard + guarded start-up + truthful `/ready`/`/health`
(`docs/spec/function-host-process.md` §3). §0–3 apply to the `fc-server` image (`Dockerfile`) in every
role — API tier, worker, router — and to the bench rig's image, which since
§1a is built from that same `Dockerfile` (not a separate
`bench/real/Dockerfile.java`), so what is measured is what is deployed.
§4 additionally applies to the function host's own image
(`function-host/Dockerfile`, `fc-fnhost`). `fcdev`'s native binary is not a
JVM and is untouched.

## 0. Rulings

- **The task definition's `memory` (hard limit) is the only number an
  operator sets.** Nothing about the JVM's heap is configured per task, and
  nothing in the IaC names a JVM flag. Raising the container limit raises the
  heap with it.
- **No collector is pinned.** The JVM's own ergonomics choose: Serial below
  2 CPUs or below ~1.8 GB, G1 above. Measured 2026-09-06
  (`../test-size/RESULTS.md`, collector comparison): ZGC lost a quarter of
  throughput and grew to 10 GB uncapped — rejected. G1 at a 256 MB cap kept
  93% of uncapped throughput at 424 MB; its memory use follows the cap.
- **The cap follows the container, minus a reserve.** The JVM default of a
  quarter of the container is the defect this fixes: at 512 MB it leaves a
  128 MB heap and 384 MB idle; at 4 GB it wastes 3 GB. A single percentage
  cannot be right at both ends because the non-heap parts are a floor, not
  a share.

## 1. The fence

At container start, before `java` runs, the entrypoint derives from the
cgroup memory limit `L` (bytes):

```
reserve = max(192 MiB, 15% of L)        the non-heap floor: runtime, metaspace,
                                        code cache, thread stacks, GC data,
                                        direct buffers
-Xmx                = L - reserve       rounded down to whole MiB
-XX:MaxDirectMemorySize = reserve / 2   Netty/Vert.x buffers bounded inside
                                        the reserve (the JDK default is "same
                                        as the heap", which would double-count)
```

| Container | reserve | `-Xmx` | direct |
|---|---:|---:|---:|
| 512 MiB | 192 MiB | 320 MiB (62%) | 96 MiB |
| 1 GiB | 192 MiB | 832 MiB (81%) | 96 MiB |
| 4 GiB | 614 MiB | 3,481 MiB (85%) | 307 MiB |
| 16 GiB | 2,457 MiB | 13,926 MiB (85%) | 1,228 MiB |
| 256 GiB | 39,321 MiB | 222,822 MiB (85%) | 19,660 MiB |

Rules:

1. The limit is read from cgroup v2 `/sys/fs/cgroup/memory.max`, else cgroup
   v1 `/sys/fs/cgroup/memory/memory.limit_in_bytes`. A value of `max`, a
   missing file, or a v1 "unlimited" sentinel (≥ 2^60) means **no fence**:
   the script emits nothing and the JVM's own defaults apply (a quarter of
   physical memory — the right answer on a shared host with no limit).
2. If `JAVA_TOOL_OPTIONS` already contains `-Xmx`, `-XX:MaxRAMPercentage` or
   `-XX:MaxDirectMemorySize`, the operator has taken over: the script emits
   nothing and says so on stderr. That is the only override, and it is not a
   knob of ours.
3. A computed `-Xmx` below 64 MiB is floored at 64 MiB with a warning; the
   container is mis-sized and the JVM should say so rather than fail to start
   obscurely.
4. The script prints one line to stderr naming the limit it read and the two
   flags it derived, so the choice is in the task's log.
5. The entrypoint `exec`s `java`, so the JVM is PID 1 and receives SIGTERM
   directly.

Test seam: `FC_JVM_MEMORY_LIMIT_FILE=<path>` makes the script read that file
instead of the cgroup files. `JvmOptsScriptTest` runs the script against
files holding 512 MiB, 1 GiB, 4 GiB, 256 GiB, `max`, the v1 sentinel, and a
missing path, and against each of the three opt-out `JAVA_TOOL_OPTIONS`
values, asserting the exact flag strings of the table above (or emptiness).

## 1a. Compact object headers and the AOT cache

Owner ruling 2026-09-15: turn on two JDK 25 features in the shipped image,
both opt-in.

**Compact object headers** (`-XX:+UseCompactObjectHeaders`, a JDK 25 product
feature): every object header shrinks from 12 to 8 bytes. Pure win on a
64-bit heap — smaller objects, less GC work per byte of live data — with no
counterpart flag needed at training time versus runtime *except* that it
must be the same everywhere the AOT cache below is read, because it changes
object layout. Passed unconditionally in `docker/entrypoint.sh`, ahead of
everything else the entrypoint adds.

**Leyden's AOT cache** (JEP 514): a `-XX:AOTCacheOutput=<path>` run records
loaded classes, linked bytecode and (with `-XX:+AOTClassLinking`, the
default under `AOTCacheOutput`) resolved constant-pool entries; a later
`-XX:AOTCache=<path>` run maps that file in at startup instead of doing the
class-loading and linking work again. This is JEP 514's *one-step* training
— the cache is written when the training JVM exits normally, no separate
"assemble" step — which is why the Dockerfile's training run is a single
`RUN` that starts the server, waits for it to finish booting, and exits.

- **The training run** (Dockerfile build stage, after jlink): runs
  `/jre/bin/java -XX:+UseCompactObjectHeaders -XX:AOTCacheOutput=/fc-server.aot
  --enable-preview --enable-native-access=ALL-UNNAMED -jar /fc-server.jar`
  with `FC_EXIT_AFTER_START=true FC_PLATFORM_ENABLED=false
  FC_ROUTER_ENABLED=true FC_API_PORT=0 FC_METRICS_PORT=0 FC_LOG_FORMAT=json`.
  `FC_EXIT_AFTER_START` (`Env.exitAfterStart`, read like every other bare
  `FC_*` boolean, no alias) makes `Main` start the server exactly as a real
  boot would, log `training run complete` naming the roles it started, then
  stop it and return — a clean exit 0 once JEP 514 has written the cache,
  rather than a server that runs forever inside a `RUN` step. The build then
  asserts `test -s /fc-server.aot` so a training run that silently produced
  nothing fails the image build instead of shipping a cache-less image that
  looks fine.
- **Why `/jre/bin/java` and this exact `/fc-server.jar`**: the AOT cache is
  tied to the precise JDK build (its class library, its internal layouts)
  and to the classpath/module path that produced it. `/jre` at this point in
  the build is the *same* jlink runtime the runtime stage copies verbatim,
  and `/fc-server.jar` is the exact jar that ships — training against
  anything else (a different JDK image, a jar built differently) would
  produce a cache the runtime JVM rejects.
- **Router-only coverage**: this build stage has no database, so only the
  classes a router-only boot touches make it into the cache — platform/API
  (identity, OIDC, the Vue SPA route) classes are not trained. The
  entrypoint still passes `-XX:AOTCache=...` unconditionally for every role
  (a partial cache still helps the classes it does cover), but a platform
  deployment's boot is only partly warmed by it today. A later training pass
  with `FC_PLATFORM_ENABLED=true` against a throwaway embedded Postgres
  could extend the cache to those classes too; not done here.
- **No fence flags in the training run**: `docker/jvm-opts.sh` only derives
  `-Xmx`/`-XX:MaxDirectMemorySize`, neither of which affects what classes get
  loaded or how they're linked, so leaving them out of training changes
  nothing about the cache's content — simpler than deriving a fence for a
  container that may not even be memory-limited during the build.
- **The matching rule**: the runtime JVM only *uses* an `-XX:AOTCache=` file
  when it is running the same JDK build, the same classpath/module path, and
  the same `-XX:+UseCompactObjectHeaders` setting as the training run that
  produced it (`-Xlog:aot` confirms this per the JDK 25 docs: cache
  validation checks a recorded fingerprint of the JDK version, the class
  path/module path, and a handful of "must match" JVM flags including object
  header shape). Every one of those is held fixed here by construction: same
  `/jre`, same jar, same entrypoint-supplied compact-headers flag.
- **A mismatch degrades, it never fails the boot**: per JEP 514, if the
  runtime JVM cannot use the cache — wrong JDK build, wrong classpath, a
  flag mismatch — it logs a warning (visible with `-Xlog:aot`) and falls
  back to loading/linking classes the normal way, exactly as if
  `-XX:AOTCache` had not been passed at all. `docker/entrypoint.sh` relies on
  this: it passes `-XX:AOTCache=/usr/local/lib/fc-server.aot` whenever that
  file exists, with no verification step of its own, because a bad cache is
  self-correcting at the JVM level rather than a boot-time failure to guard
  against.

## 2. Visibility

`Server.start` logs one line at INFO before the listeners bind:
collector name(s) as the JVM reports them, `maxHeapMiB`
(`Runtime.maxMemory()`), `maxDirectMiB` (`MaxDirectMemorySize` VM option),
`processors` (`availableProcessors()`). Nobody should have to infer the
collector from the container size. `JvmInfoTest` pins that the summary names
at least one collector and a positive heap.

### 2.1 `/metrics` series (2026-09-14)

The one log line above answers "what did we fence"; it does not answer "are
we close to the fence" over time — that needs a scrape, not a boot-time log
line. `JvmMetricsRegistration.register` (`server/src/main/java/io/flowcatalyst/server/JvmMetricsRegistration.java`,
called from `Server.start` alongside the application collectors — see
`docs/audit/2026-09-14-observability-parity.md` §1 for the pre-existing 11)
adds five JVM collectors to the metrics-port `PrometheusRegistry`:
`jvm_memory_used_bytes`/`jvm_memory_max_bytes` (labeled `area="heap"` and
`area="nonheap"`), `jvm_memory_pool_collection_used_bytes` — used space
**after** the last collection, per pool; this is the alarm input, not
`jvm_memory_used_bytes`, which can read high mid-allocation on a pool that
would collect back down to nothing — `jvm_gc_collection_seconds` (a
count+sum pair per collector — the time-spent-collecting signal), `jvm_threads_current`,
and `jvm_buffer_pool_used_bytes{pool="direct"}` — the other half of §1's
fence (`-XX:MaxDirectMemorySize`). Deliberately five named collectors, not
`JvmMetrics.builder()`'s full bundle (class-loading/compilation/runtime-info
add scrape noise this isn't the place to take on). Registration is
defensive: an `IllegalArgumentException` (the same registry already carries
these — a shared/default registry started twice) or any other
`RuntimeException` (a platform missing the MXBean, e.g. native image) is
caught per-collector and logged at WARN rather than failing startup — see
`docs/deployments.md`'s Java-compatibility section for the sizing signal
this feeds.

## 3. Verification (done 2026-09-14, `bench/real/RESULTS.md` round 16)

Two CPUs, 2 GB, 200 connections, same day: Go 2,344 req/s / p99 102 ms;
Java at the JVM default (512 MiB heap) 2,543 / 507 ms; Java fenced (1,740
MiB heap, 153 MiB direct) 2,623 / 584 ms. The fence is correct in a real
container (the two log lines of §1.4 and §2 appear as specified) and costs
nothing. The p99 hypothesis was wrong: the tail is not heap size or
collector pauses (G1 committed ~317 MB throughout, young pauses max 13.5
ms, 2% of wall time) but queueing behind the request-worker admission
(`admission.md` §11.7) — recorded in `docs/backlog.md` for the
verification plan; the "explicit -Xmx" question is closed by this spec.

## 4. The function host's own addition: metaspace (2026-09-19, package D
slice D5; turned into a runtime percent setting by owner ruling 2026-09-21)

`function-host/Dockerfile` (`fc-fnhost`) shares `docker/jvm-opts.sh` verbatim
with `fc-server` but sets `FC_JVM_METASPACE_PERCENT=${FC_JVM_METASPACE_PERCENT:-50}`
in its own `function-host/docker/entrypoint.sh` — a variable the fence script
only derives `-XX:MaxMetaspaceSize` from when it is SET (to any value, even
an invalid one — see the validation rule below), so `fc-server`'s own
computed flags (never setting it) are byte-identical before and after this
addition. Why the host needs this and the server does not: **a loaded
function is its own class loader** (`docs/spec/function-host-core.md` §2) —
many short-lived functions loading and unloading over the host's life is
exactly the workload that grows metaspace, and an unbounded metaspace turns
one function's classloader leak into a host-wide OOM-kill instead of a
catchable `OutOfMemoryError: Metaspace` that fails just that one function's
load (spec `function-host-process.md` §3). `fc-server` has no such workload
— every class it ever loads is its own, fixed at build time — so it gets no
fence.

### 4.1 The percent is a runtime setting, not a build-time constant

Owner ruling 2026-09-21: the original 2026-09-19 slice hardcoded 25% and, more
importantly, **added the metaspace budget on top of `-Xmx` instead of
subtracting it** — at a 4 GiB limit the script emitted `-Xmx3481m
-XX:MaxDirectMemorySize=307m -XX:MaxMetaspaceSize=1024m`, which sums to
4.8 GiB of permission in a 4 GiB container. Fixed by making the percentage an
operator-set env var, `FC_JVM_METASPACE_PERCENT` (integer, valid range
**10–70**), and by changing the derivation so metaspace is carved OUT of
`-Xmx`, never added beside it:

```
metaspace = FC_JVM_METASPACE_PERCENT% of the container limit L
-Xmx       = L - reserve - direct - metaspace     (reserve, direct: same formulas as §1)
```

so the invariant **heap + direct + metaspace + reserve ≤ L, always** — the
bug this fixes. Unlike §1's reserve/direct split (tuned against measured
data), 10–70% remains a validated RANGE rather than a single tuned number,
because the right share depends on the workload (how metaspace-heavy the
functions landing on a given pool are) in a way `fc-server`'s uniform,
build-time-fixed class set never has to account for — see §4.3 below for what
one real fixture actually costs.

**Validation** (`docker/jvm-opts.sh`): unset ⇒ no metaspace flag at all, and
every other computed number is byte-identical to the script's behaviour
before this variable existed (the `fc-server` image's own path, verified by
`JvmOptsScriptTest`'s UNSET golden tests). Set, but not an integer in
`[10,70]` (out of range, non-numeric, or the empty string) ⇒ the script
prints one line to stderr naming the variable and the range, and **exits
non-zero** — `function-host/docker/entrypoint.sh` captures that exit status
explicitly and refuses to start the container rather than falling back to an
unfenced (or default-percent) metaspace silently.

**Which one gives way at the 64 MiB heap floor**: §1's existing rule floors
`-Xmx` at 64 MiB on a mis-sized container ("the JVM should say so rather than
fail to start obscurely"). With metaspace now subtracted from the SAME
budget, a large-enough percent request on a small-enough container can drive
the naive `-Xmx` calculation negative. **The heap floor wins: `-Xmx` is
always floored at 64 MiB, and the metaspace budget shrinks** to whatever is
left after `reserve + direct + 64 MiB` is taken out of the limit (clamped at
0, never negative) — a smaller metaspace fence is a better failure mode than
a host that cannot allocate anything at all. Example: a 512 MiB container at
70% would naively want ~358 MiB of metaspace, which leaves less than 64 MiB
for heap; the script instead emits `-Xmx64m -XX:MaxDirectMemorySize=96m
-XX:MaxMetaspaceSize=160m` (metaspace shrunk to the 160 MiB that's actually
left), with a warning naming both the floor and the shrink.

### 4.2 The split, by limit and percent

What each region gets, from one container limit `L` and percent `p`
(`reserve`/`direct` computed exactly as in §1):

| Region | Formula |
|---|---|
| Heap (`-Xmx`) | `L - reserve - direct - metaspace`, floored at 64 MiB (metaspace shrinks first, see above) |
| Direct (`-XX:MaxDirectMemorySize`) | `reserve / 2` (§1, unaffected by `p`) |
| Metaspace (`-XX:MaxMetaspaceSize`) | `p% of L` (or the floor-forced shrunk value) |
| Everything else (thread stacks, code cache, GC bookkeeping) | `reserve / 2` (the half `direct` doesn't claim), plus the JVM's own fixed overhead |

Measured flags (`JvmOptsScriptTest`'s own table, run against the real
script):

| limit | unset | 10% | 25% | 50% (the function host's default) | 70% |
|---|---|---|---|---|---|
| 512 MiB | `-Xmx320m -XX:MaxDirectMemorySize=96m` | `-Xmx172m … -XX:MaxMetaspaceSize=51m` | `-Xmx96m … =128m` | `-Xmx64m … =160m` (heap-floored, metaspace shrunk from a requested 256m) | `-Xmx64m … =160m` (heap-floored, shrunk from 358m) |
| 1 GiB | `-Xmx832m -XX:MaxDirectMemorySize=96m` | `-Xmx633m … =102m` | `-Xmx480m … =256m` | `-Xmx224m … =512m` | `-Xmx64m … =672m` (heap-floored, shrunk from 716m) |
| 2 GiB | `-Xmx1740m -XX:MaxDirectMemorySize=153m` | `-Xmx1382m … =204m` | `-Xmx1075m … =512m` | `-Xmx563m … =1024m` | `-Xmx153m … =1433m` |
| 4 GiB | `-Xmx3481m -XX:MaxDirectMemorySize=307m` | `-Xmx2764m … =409m` | `-Xmx2150m … =1024m` | `-Xmx1126m … =2048m` | `-Xmx307m … =2867m` |
| 8 GiB | `-Xmx6963m -XX:MaxDirectMemorySize=614m` | `-Xmx5529m … =819m` | `-Xmx4300m … =2048m` | `-Xmx2252m … =4096m` | `-Xmx614m … =5734m` |

Every row above satisfies `heap + direct + metaspace + reserve ≤ limit`
exactly (`JvmOptsScriptTest#metaspacePercentMatrix` asserts this per row, not
just the flag strings) — the invariant the 2026-09-19 slice's on-top addition
violated.

### 4.3 `/ready` reports the live split

The function host's observability listener (`FnObservability`,
`docs/spec/function-host-process.md` §2) adds a `memory` object to `/ready`'s
JSON body, read live from the running JVM rather than re-derived from env
vars (`FnMemorySnapshot`): `limitBytes` (the same cgroup-limit file
`docker/jvm-opts.sh` itself reads, via the same `FC_JVM_MEMORY_LIMIT_FILE`
test seam and v2/v1 fallback), `heapMaxBytes` (`Runtime.maxMemory()`, always
present), `metaspaceMaxBytes` (the `Metaspace` `MemoryPoolMXBean`'s max),
`directMaxBytes` (`MaxDirectMemorySize` via `HotSpotDiagnosticMXBean`). A
ceiling that is unbounded or unknown is OMITTED from the JSON, never printed
as a literal `-1` — an operator can tell "no fence configured" from "fenced
to N bytes" without inferring it from absence-of-a-flag in a process listing.

### 4.4 Measured cost per function, and where the fence binds at 50%

`docs/function-runner-report.md` §Performance's "Metaspace at 50%"
subsection has the full re-measurement; headline numbers: the "typical"
fixture (jackson-databind + json-schema-validator shaded, ~700 classes) costs
**~4.4 MB of metaspace per loaded instance** (unchanged from the original
25%-fence measurement — the per-function cost is a property of the fixture,
not the fence percentage). At the new 50% default this predicts the fence at
`(512×0.5 − 34) / 4.4 ≈ 50` typical functions on a 512 MiB container†, `(1024
− 34) / 4.4 ≈ 225` on 2 GiB, and `(2048 − 34) / 4.4 ≈ 458` on 4 GiB — the 2
GiB and 4 GiB predictions matched what was actually measured (226 and 458)
almost exactly. († not separately re-measured; within the report's own ±20%
extrapolation rule of the 2 GiB/4 GiB points that were.)

**A residual gap found while re-measuring, fixed 2026-09-20**: pushing an
all-warm document meaningfully PAST the fence (2 GiB/300 requested vs. a
~226 capacity; 4 GiB/500 vs. a ~458 capacity) reproducibly crashed the
function host's synchronous startup reconcile with an uncaught
`OutOfMemoryError` on the `main` thread once metaspace was driven deep enough
into exhaustion that even the per-entry recovery/logging path started
failing. The observability listener (`/ready`, `/metrics`) stayed up
throughout (it binds before the first reconcile even runs), but the FUNCTION
listener never bound at all in that state, so the functions that HAD loaded
successfully were not actually reachable — a materially worse failure mode
than "the fenced-out entries fail cleanly and the rest keeps serving".

Fixed by `docs/spec/function-host-process.md` §3, three parts: (1) a
`MetaspaceGuard` refuses a load — warm, lazy `ensureLoaded`, or a pinned
candidate — WITHOUT ATTEMPTING IT whenever free metaspace (read live from
the `Metaspace` `MemoryPoolMXBean`) is below `max(64 MiB, 5% of the pool's
own max)`, so the per-function recovery path this gap came from is never
reached in the first place; (2) `FnHost#start`'s first `reconcileOnce` call
is wrapped so an escaping `Throwable` is logged (guarded against the log
call itself throwing) and start-up still goes on to bind the FUNCTION
listener; `ReconcileLoop` likewise catches a metaspace-family
`OutOfMemoryError` in any LATER run and keeps going, while any other `Error`
still ends the loop; (3) `/ready`/`/health` now also require the FUNCTION
listener to be bound and the reconcile loop's thread to be alive
(`LISTENER_DOWN`/`RECONCILER_DOWN`, 503) — before start-up completes
`/health` stays 200 unconditionally (a slow first load must not fail
liveness), and tells the truth after. Re-measured, real containers, rebuilt
image: 2 GiB/N=300 → **212** loaded (vs. 226 without the guard), 4 GiB/N=500
→ **438** loaded (vs. 458) — capacity is measurably LOWER than the raw fence
because of the reserve, and zero `LOAD:OUT_OF_METASPACE` events at either
point (the guard heads off the real wall before it is ever hit) — function
port open and serving, `/ready`/`/health` both 200, `OOMKilled=false`; full
detail and the exact counts in the report's own dated follow-up
subsection.
