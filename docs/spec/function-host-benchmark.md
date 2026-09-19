# Plan — function host benchmark: what one host holds and serves

Workplan §3 "Performance". Owner, 2026-09-20: run it as soon as the context slice (D4) lands; the
**per-loaded-function memory** figure decides instance sizing (low-CPU / high-memory EC2 capacity
provider) and is the number to set against "a Rust task idles at ~10 MB (+ a Service Connect
sidecar)". Nothing here is a tuning exercise (`feedback: no tuning`) — it measures the defaults.

## What is measured

| # | Question | How |
|---|---|---|
| B1 | **Memory per loaded function** | the host image, `-Xmx` from the fence at a 2 GiB and a 4 GiB container limit; load N = 0, 25, 50, 100, 200 functions; after a forced GC record RSS, heap used, **metaspace used**, class count, thread count. Report the slope (MB per function) for two fixtures: *lean* (API jar only, a few classes) and *typical* (shades Jackson + a JSON schema lib + pgjdbc-free DB use through the context — the realistic worst case for metaspace). 100 lazy + 20 warm is the workplan's headline row |
| B2 | **First-call latency of a lazy function** | cold (artifact cached, class loader not built) p50/p99 over 200 functions, lean and typical; and after idle-unload |
| B3 | **Steady-state throughput and p99** | one warm function doing 5 ms of simulated I/O (parked, not spinning) and one doing ~1 ms of CPU; closed-loop load at c = 64, 256, 1000 from a separate container; host pinned to **1 and 2 CPUs** (`--cpus`), as the platform benchmarks were (`docs/spec/admission.md`, `../test-size/RESULTS.md` protocol). Report req/s, p50/p99, `busy` refusals, CPU %, GC pause total |
| B4 | **Noisy neighbour** | B3's I/O function at a fixed rate while a second function burns CPU at its `maxConcurrency`; does the first one's p99 hold? (It is the honest cost of a shared JVM.) |
| B5 | **Cost line** | from B3: invocations per host-month at 50 % utilisation → $ per million on `r7g.large` / Fargate ARM, next to classic Lambda (GB-s + $0.20/M) and Lambda Managed Instances (EC2 × 1.15 + $0.20/M) — prices checked on the day, not from memory |

## Rules

- Docker on this machine, the real `function-host` image, a fake platform serving desired state
  (the smoke test's), `caffeinate -dims`, `JAVA_TOOL_OPTIONS` untouched (the fence decides).
- One heavy thing at a time; no Maven builds during a run.
- Every number in the report names its container limit, CPU count, fixture and N. No extrapolation
  past what was run; where a run was skipped, say so.
- Output: `docs/function-runner-report.md` §"Performance" (the workplan's report file), plus the raw
  CSVs under `bench/function-host/`.
