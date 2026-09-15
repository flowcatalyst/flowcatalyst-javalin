# JVM memory in the container image

Status: spec + owner rulings, 2026-09-14 (§0–3); compact object headers and
the AOT cache ruled 2026-09-15 (§1a). Applies to the `fc-server` image
(`Dockerfile`) in every role — API tier, worker, router — and to the bench
rig's image, which since §1a is built from that same `Dockerfile` (not a
separate `bench/real/Dockerfile.java`), so what is measured is what is
deployed. `fcdev`'s native binary is not a JVM and is untouched.

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
