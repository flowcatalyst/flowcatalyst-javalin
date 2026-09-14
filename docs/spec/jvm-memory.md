# JVM memory in the container image

Status: spec + owner rulings, 2026-09-14. Applies to the `fc-server` image
(`Dockerfile`) in every role — API tier, worker, router — and to the bench
rig's image (`bench/real/Dockerfile.java`), so what is measured is what is
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
