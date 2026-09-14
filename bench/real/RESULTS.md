# Real-server comparison — Java (Vert.x / Javalin) vs Go, `docs/vertx-plan.md` §8

Rig: `bench/real/run.sh` (this directory). One container per server under a Docker CPU quota,
Postgres 18 in its own container on the rig network, seeded by Go `fcdev init` + Java `fcdev init`
(Flyway v8). wrk in a container on host CPUs 2–9, 1,000 connections, 8 threads, driving
`GET /api/event-types` with the `fc_session` cookie from one `POST /auth/login` — the browser
path: a session lookup in the database, then the list query, then ~50 KB of JSON per response
(72 event types with their schemas). Go at `b422466`, Java at `4d4082a`. Host: 14-core Apple
Silicon, OrbStack. Java image: `eclipse-temurin:25-jre-alpine` + the exec jar; Go: static
linux/arm64 binary on Alpine. Per-thread kernel context switches from `/proc/1/task/*/status`.

## Round 1 — 10 s warm-up, no memory limit (2026-09-06)

| CPU quota | server | req/s | share of Go | p99 | mem after run | switches / req |
|---|---|---:|---:|---:|---:|---:|
| 1 | Go | 1,870 | 100% | 605 ms | 125 MB | 5.6 |
| 1 | Java, Vert.x | 1,236 | 66% | 1,050 ms | 1.70 GB | 9.7 |
| 1 | Java, Javalin | 1,203 | 64% | 1,020 ms | 1.78 GB | 6.2 |
| 2 | Go | 2,548 | 100% | 436 ms | 144 MB | 5.7 |
| 2 | Java, Vert.x | 2,415 | 95% | 1,050 ms | 938 MB | 10.6 |
| 2 | Java, Javalin | 2,387 | 94% | 843 ms | 827 MB | 8.4 |

Three things this round shows about the rig rather than the servers, and which round 2 fixes:

1. **The 1-CPU JVM rows are still warming up.** The 10 s warm-up rate was half the measured
   rate for both Java rows (651 → 1,236 req/s), and the C1/C2 compiler threads appear in the
   per-thread table: on one CPU the JIT competes with the request path for the whole window.
   Go was steady (1,880 → 1,870). Round 2 warms for 60 s. The native image (`-Pnative`) is the
   real answer for a one-CPU pod: no JIT at all.
2. **No container memory limit, so the JVM sized its heap from the host's 16 GB** and G1 grew
   into it (1.7 GB after the run). A pod has a limit and the JVM takes a quarter of it by
   default. Round 2 runs both servers under the same `--memory=1g`. This is a deployment fact,
   not a tuning knob.
3. **The switch count does not transfer to this workload.** At ~1–2.5k req/s the loop and the
   carriers idle between requests, so each request is a wake (the light-load churn seen in
   `../test-size` at 24 connections), and under a CFS quota with 15–35 runnable threads the
   kernel preempts constantly — Go shows 5.6 per request too. The plan §8 ceiling of 1.0 was
   calibrated on the saturated one-query hello endpoint; for the real server the evidence is
   throughput, p99 and memory.

What does transfer: the per-request cost here is JSON serialisation of ~50 KB, not the
listener. Vert.x and Javalin are within 2% of each other on throughput; Vert.x's p99 is not
better than Javalin's on this endpoint at either size (the tail at 1,000 connections against
~2.4k req/s is ≥ 400 ms of queueing for everyone — Go's 436 ms *is* that floor).

## Round 2 — 60 s warm-up, `--memory=1g` on both (2026-09-06)

| CPU quota | server | warm-up rate | req/s | share of Go | p99 | mem after run |
|---|---|---:|---:|---:|---:|---:|
| 1 | Go | 1,875 | 1,869 | 100% | 601 ms | 132 MB |
| 1 | Java, Vert.x | 1,169 | 1,282 | 69% | 949 ms | 437 MB |
| 2 | Go | 2,448 | 2,494 | 100% | 437 ms | 182 MB |
| 2 | Java, Vert.x | 2,110 | 2,219 | 89% | 959 ms | 421 MB |

Against the plan §8 line (throughput within 10% of Go, p99 within 15 ms, memory recorded):
**not met on this endpoint.** Throughput is at 69% on one CPU and 89% on two; the p99 is about
twice Go's at both sizes; memory is 3× at the same 1 GB limit (the JVM's default quarter-of-limit
heap plus metaspace and code cache). The hello benchmark in `../test-size`, where the pool gate
brought the listener to Go's throughput, measured the listener and the pool wait; this endpoint
measures a page load — a session lookup, a list query and ~50 KB of JSON per response — and on
that the cost is per-request CPU in the JVM, not the listener. The Javalin listener is within 2%
of Vert.x here (round 1), so this is the platform's cost, not the adapter's.

The queueing floor at 1,000 connections and ~2.2–2.5k req/s is ~400–450 ms; Go's p99 sits on it,
Java's carries another ~500 ms, which the `VM Thread` row (13.6k switches on one CPU, GC
safepoints) points at: G1 on a 256 MB heap under ~50 KB of allocation per response.

What round 3 isolates: the same run with GC logging, and a single-item endpoint
(`GET /api/event-types/{id}`, same session lookup, ~1 KB of JSON) to separate serialisation from
the session path.


## Round 3 — where the Java cost is (2026-09-06, 2 CPUs, `--memory=1g`, 60 s warm-up)

| measurement | Go | Java, Vert.x | note |
|---|---:|---:|---|
| list endpoint with `-Xlog:gc` | — | 2,190 req/s, p99 1.11 s | **90 full GCs** and 2,213 young pauses in the ~70 s window; young pauses avg 1.7 ms, max 6.8 ms; heap 247 MB with ~135 MB live after collection |
| single item `GET /api/event-types/{id}` (session lookup + ~1 KB JSON) | 8,453 req/s, p99 135 ms | 6,248 req/s (74%), p99 501 ms | queueing floor at 1,000 connections ≈ 160 ms; Go sits on it, Java carries +340 ms |

Reading:
- Under a 1 GB limit the JVM's default heap is a quarter of it, 256 MB. This server's live set
  after collection is ~135 MB, so ~110 MB is left for a workload that allocates ~50 KB per
  response: G1 fell into full collections (90 in 70 s). That is the extra ~500 ms of tail on the
  list endpoint. Round 4 tests the same run at a 2 GB limit (512 MB heap) — a pod-sizing fact,
  not a knob.
- The single-item run removes serialisation and still shows Java at 74% of Go with a 3.7× tail.
  The session path is symmetric — Go's `introspect` also resolves claims from the database on
  every cookie request (`shared/middleware/middleware.go:164`) — so what remains is per-request
  CPU in the JVM (JWT RS256 verify, three jOOQ queries, Jackson) and safepoints.
- `../test-size` measured the listener and the pool wait, where the gate brought Java to Go's
  throughput. The real page load measures the platform code, and there the JVM is at 69–89% of
  Go on throughput with a heavier tail. The listener choice (Vert.x vs Javalin) is within 2% on
  this workload; the plan §8 line was calibrated on the wrong workload and is not met here.

## Round 4 — the heap explanation tested: `--memory=2g`, 2 CPUs, list endpoint

| server | req/s | share of Go | p99 | full GCs | young GCs | heap |
|---|---:|---:|---:|---:|---:|---|
| Go | 2,550 | 100% | 440 ms | — | — | 156 MB RSS |
| Java, Vert.x | 2,373 | 93% | 960 ms | **0** | 793 | 370 MB committed, ~126 MB live |

The full collections were the throughput gap: gone at 2 GB, and throughput is at 93% of Go.
**They were not the tail.** The p99 did not move (960 ms vs 959 ms at 1 GB). Young pauses total
~1.6 s in 70 s, 2% — not it either. What is left is ordering: the gate's semaphores were the JDK
default, unfair. At 1,000 concurrent requests each needing three or four sequential checkouts
(principal, roles, permissions, then the list), an unfair queue lets late arrivals barge and a
few waiters starve for hundreds of milliseconds — a p99 made of ordering, not of work. Go's
pgxpool queues waiters in arrival order. Round 5 makes the gate's lanes fair
(`new Semaphore(n, true)`, a property, not a knob) and reruns exactly this row.

## Round 5 — fair gate lanes (`new Semaphore(n, true)`), same row as round 4

| server | req/s | share of Go | mean | p50 | p90 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| Go | 2,550 | 100% | 385 ms | 393 ms | 411 ms | 440 ms | 506 ms |
| Java, Vert.x, unfair gate (round 4) | 2,373 | 93% | 410 ms | 334 ms | 788 ms | 960 ms | 1.87 s |
| Java, Vert.x, **fair** gate | 1,959 | 77% | 497 ms | 498 ms | 542 ms | 600 ms | 711 ms |

The ordering explanation holds: with in-order hand-off the spread collapses (σ 282 → 53 ms, max
1.87 s → 711 ms, p99 within 160 ms of Go). The price is throughput, −16%: a fair
`java.util.concurrent.Semaphore` hands every released permit to the queue head, which is a
thread hand-off per checkout, three or four per request. Go's pool queues in order *and* keeps
the throughput because its hand-off is a goroutine switch inside the runtime.

Neither row meets the plan §8 line. The trade is the owner's (ruling Q5 named fairness as
theirs): 93% throughput with a 2.2× tail, or 77% with a 1.4× tail. A third shape worth
measuring before ruling: one checkout per request (the authenticator's session queries and the
handler's transaction on the same connection) crosses the gate once instead of three or four
times, which would cut the queueing multiplier for both variants. The tree is left at the
committed, unfair gate; the fair variant is this one-line change.

## Standing conclusions from the real-server rounds (2026-09-06)

1. The listener is not where the cost is. Vert.x and Javalin are within 2% on this workload;
   the Vert.x move buys memory and h2/gRPC on one port, as planned, not throughput here.
2. The pool gate's *waiting* is what `../test-size` measured; the real server's cost is
   per-request CPU in the platform code and the JVM (69% of Go on one CPU, 89–93% on two),
   plus GC sizing under a container limit (a 1 GB pod gives a 256 MB heap; this server's live set
   is ~130 MB; 90 full GCs in 70 s; 2 GB clears it) and the checkout ordering above.
3. On one CPU the JVM's JIT competes with the request path for the whole run. The native image
   is the one-CPU answer and has not been measured on the real server yet.
4. Memory at the same 1 GB limit: Go 132 MB, Java 437 MB.

## Round 6 — one gate permit per request (experiment, uncommitted), 60 s warm-up

The gate is crossed once per request: the first checkout takes the permit, later checkouts in
the same request skip the wait, the adapter releases at the end of the chain (`Admission#gatePermit`).

| CPU / memory | server | req/s | share of Go | mean | p50 | p90 | p99 | max | σ |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 CPU, 2 GB | Go | 2,550 | 100% | 385 ms | 393 | 411 | 440 ms | 506 ms | 33 ms |
| 2 CPU, 2 GB | Vert.x, unfair per checkout (round 4) | 2,373 | 93% | 410 ms | 334 | 788 | 960 ms | 1.87 s | 282 ms |
| 2 CPU, 2 GB | Vert.x, fair per checkout (round 5) | 1,959 | 77% | 497 ms | 498 | 542 | 600 ms | 711 ms | 53 ms |
| 2 CPU, 2 GB | **Vert.x, one permit per request** | **2,440** | **96%** | 397 ms | 498 | 700 | 824 ms | 1.22 s | 264 ms |
| 1 CPU, 1 GB | Vert.x, one permit per request | 1,201 | 64% | | | | 912 ms | | |

Throughput parity is essentially reached at two CPUs (96%); the per-request CPU cost is now
level with Go's. The tail is not: σ 264 ms against Go's 33. That spread is not the gate any
more (one crossing) and not the collector (round 4). What it looks like is CFS quota
throttling: under `--cpus=2` the JVM runs 28+ threads (carriers, the loop, GC workers, JIT, VM
thread) and bursts past the quota, so the kernel throttles it for the rest of the 100 ms period;
Go 1.25+ sets `GOMAXPROCS` from the cgroup quota and stays inside it. Round 7 pins both servers
to two cores (`--cpuset-cpus=0,1`, no quota) to separate throttling from work.

## Round 7 — two pinned cores (`--cpuset-cpus=0,1`, no quota), 2 GB, one permit per request

| server | req/s | share | mean | p50 | p90 | p99 | max | σ |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Go | 2,425 | 100% | 403 ms | 407 | 441 | 475 ms | 501 ms | 34 ms |
| Vert.x, one permit per request | 2,160 | 89% | 440 ms | 507 | 914 | 1.03 s | 2.00 s | 391 ms |

Throttling is not the tail: pinned cores changed nothing for Java's spread. The distribution is
bimodal (p50 507 ms, p90 914 ms), which is what an unfair queue produces — and the one permit per
request is still taken from an unfair semaphore by 1,000 competing requests. Round 5 showed
fairness collapses the spread (σ 53 ms) at four hand-offs per request; round 8 combines the two:
fair semaphore, one crossing per request.

## Round 8 — fair semaphore, one crossing per request (2 GB, 60 s warm-up)

| CPU | server | req/s | share | mean | p50 | p90 | p99 | max | σ |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 CPU quota | Go | 2,550 | 100% | 385 ms | 393 | 411 | 440 ms | 506 ms | 33 ms |
| 2 CPU quota | Vert.x, unfair, one permit (round 6) | 2,440 | 96% | 397 ms | 498 | 700 | 824 ms | 1.22 s | 264 ms |
| 2 CPU quota | **Vert.x, fair, one permit** | **2,252** | **88%** | 434 ms | 434 | 460 | **560 ms** | 683 ms | **51 ms** |
| 2 pinned | Go | 2,425 | 100% | 403 ms | 407 | 441 | 475 ms | 501 ms | 34 ms |
| 2 pinned | Vert.x, fair, one permit | 1,983 | 82% | 493 ms | 487 | 602 | 705 ms | 807 ms | 78 ms |

The tail is ordering, confirmed twice over: fairness at one crossing per request brings the
p99 to within 120 ms of Go (the queueing floor itself is ~400 ms at 1,000 connections) with
Go's spread, and costs ~8% throughput against the unfair one-permit row (one fair hand-off per
request instead of four). The four Java rows at 2 CPUs now span a clean trade:

| gate | throughput share | p99 vs Go |
|---|---:|---:|
| unfair, per checkout (committed) | 93% | +520 ms |
| fair, per checkout | 77% | +160 ms |
| unfair, one per request | 96% | +384 ms |
| fair, one per request | 88% | +120 ms |

Still open: one CPU (JIT contention; the native image is round 9), and whether "one permit
per request" is acceptable as a design — a request that holds a permit across a long non-DB
wait (the dispatch endpoint's up-to-120 s customer wait) would hold a pool permit for that long;
the `DISPATCH` group would need to release at its last checkout, or be excluded.

## Round 9 — GraalVM native image of the server (`-Pnative` as committed: `-Os`), fair gate, one crossing

| CPU / memory | server | req/s | share of Go | p99 | startup |
|---|---|---:|---:|---:|---:|
| 1 CPU, 1 GB | Go | 1,869 | 100% | 601 ms | 0.4 s |
| 1 CPU, 1 GB | Java JIT jar (round 6 shape) | 1,201 | 64% | 912 ms | 3.3 s |
| 1 CPU, 1 GB | **Java native, `-Os`** | **254** | **14%** | 2.0 s | 0.9 s |
| 2 CPU, 2 GB | Go | 2,550 | 100% | 440 ms | 0.9 s |
| 2 CPU, 2 GB | Java native, `-Os` | 582 | 23% | 1.9 s | 0.9 s |

Five times slower than the JIT jar on the same code, CPU-bound on the carrier. The committed
native profile optimises for size (`-Os`, the fcdev binary-size ruling); the hello app in
`../test-size` was built without it and reached 88% of Rust. Round 10 rebuilds at the default
optimisation level to separate "native image" from "size-optimised native image".

## Round 10 — native image at the default optimisation level (`-O2`), same code

| CPU / memory | server | req/s | share of Go | p50 | p99 | startup |
|---|---|---:|---:|---:|---:|---:|
| 1 CPU, 1 GB | Go | 1,869 | 100% | | 601 ms | 0.4 s |
| 1 CPU, 1 GB | Java JIT jar | 1,201 | 64% | | 912 ms | 3.3 s |
| 1 CPU, 1 GB | Java native `-Os` | 254 | 14% | 1.89 s | 2.0 s | 0.9 s |
| 1 CPU, 1 GB | Java native `-O2` | 602 | 32% | 1.63 s | 1.81 s | 0.8 s |
| 2 CPU, 2 GB | Go | 2,550 | 100% | | 440 ms | 0.9 s |
| 2 CPU, 2 GB | Java JIT jar, fair one-permit | 2,252 | 88% | 434 ms | 560 ms | 2.5 s |
| 2 CPU, 2 GB | Java native `-O2` | 1,132 | 44% | 871 ms | 995 ms | 0.9 s |

The native image is **not** the one-CPU answer for this platform: at the default optimisation
level it is half the JIT jar's throughput (`-Os` a fifth), because GraalVM's ahead-of-time code
without profile guidance loses to C2 on the JSON, jOOQ and JWT work that dominates here. It wins
startup (0.8 s vs 2.5–3.3 s) and memory, which is what `../test-size` measured on a trivial
handler. Profile-guided optimisation (`--pgo`) is Oracle GraalVM only, not the community build in
use. The committed `-Os` stays for the deliverable's size; nothing here argues for shipping the
native server as the throughput path.

## Round 11 — the TypeScript platform on Node 24 (`../flowcatalyst`, Fastify + Drizzle + postgres.js)

Its own Drizzle-migrated database, bootstrapped admin, an application and 72 event types with one
JSON schema each seeded through its API. Its list response is **86 KB** (the schema content is
inlined per version) against the Go/Java 48 KB, so it does more work per request. One process,
no cluster mode, so the second CPU is idle.

| CPU / memory | server | req/s | share of Go | p50 | p99 |
|---|---|---:|---:|---:|---:|
| 1 CPU, 1 GB | Go | 1,869 | 100% | | 601 ms |
| 1 CPU, 1 GB | Java JIT jar | 1,201 | 64% | | 912 ms |
| 1 CPU, 1 GB | **Node** | **456** | **24%** | 1.31 s | 2.0 s (client timeout) |
| 2 CPU, 2 GB | Go | 2,550 | 100% | | 440 ms |
| 2 CPU, 2 GB | Java JIT jar, fair one-permit | 2,252 | 88% | 434 ms | 560 ms |
| 2 CPU, 2 GB | Node (single process) | 569 | 22% | 1.70 s | 1.9 s |

On the light endpoint in `../test-size` Deno sat within 2% of Go on one core; on this page load
Node is at a quarter, with the same shape as the native image's story: a fast runtime headline,
a per-request cost in the layers above (Fastify routing, TypeBox validation, Drizzle, a
pure-JavaScript Postgres driver, and twice the bytes) that dominates it. A fair two-CPU row
would need cluster mode and would land near 2 × 456.

## Final table (2 CPUs, 2 GB, 60 s warm-up, same rig and day)

| server | req/s | share of Go | p99 | memory after run |
|---|---:|---:|---:|---:|
| Go 1.27 | 2,550 | 100% | 440 ms | 156 MB |
| Java 25 JIT, Vert.x, unfair gate per checkout (committed) | 2,373 | 93% | 960 ms | 370 MB heap |
| Java 25 JIT, Vert.x, fair gate, one crossing per request | 2,252 | 88% | 560 ms | ~420 MB |
| Java 25 JIT, Vert.x, unfair, one crossing | 2,440 | 96% | 824 ms | |
| Java 25 JIT, Javalin (round 1 shape) | 2,387 | 94% | 843 ms | |
| Java native `-O2` | 1,132 | 44% | 995 ms | |
| Node 24, TypeScript platform (one process, 86 KB responses) | 569 | 22% | 1.9 s | |

At one CPU: Go 1,869; Java JIT 1,201–1,282 (64–69%); Java native 602; Node 456.

## Round 12 — request-level admission (owner design): FIFO queue + N worker virtual threads per group

The gate leaves the hot path. Per group, the loop pushes each parsed request onto a FIFO
queue and N long-lived virtual threads pop and run the whole chain; the main group's N is the
pool's ordinary permits (30), so each worker holds at most one connection and never waits;
`LOGIN`/`OIDC` = processor count, `DISPATCH` its own pool; routes that never touch the database
(`Group.NO_DB`: SPA, OpenAPI documents, router API, 404 path) run unbounded, one virtual thread
each, never behind a database-bound request. Mutants killed: one worker over the size (three
tests), `NO_DB` routed through the pool (two tests).

| CPU / memory | server | req/s | share of Go | mean | p50 | p90 | p99 | max | σ |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 CPU quota, 2 GB | Go | 2,550 | 100% | 385 ms | 393 | 411 | 440 ms | 506 ms | 33 ms |
| 2 CPU quota, 2 GB | Vert.x, unfair gate per checkout (committed before) | 2,373 | 93% | 410 ms | 334 | 788 | 960 ms | 1.87 s | 282 ms |
| 2 CPU quota, 2 GB | Vert.x, fair gate, one crossing | 2,252 | 88% | 434 ms | 434 | 460 | 560 ms | 683 ms | 51 ms |
| 2 CPU quota, 2 GB | **Vert.x, request workers** | **2,496** | **98%** | 392 ms | 385 | 438 | **556 ms** | 771 ms | 52 ms |
| 2 pinned, 2 GB | Go | 2,425 | 100% | 403 ms | 407 | 441 | 475 ms | 501 ms | 34 ms |
| 2 pinned, 2 GB | Vert.x, request workers | 2,403 | 99% | 407 ms | 405 | 453 | 565 ms | 1.44 s | 52 ms |
| 1 CPU, 1 GB | Go | 1,869 | 100% | | | | 601 ms | | |
| 1 CPU, 1 GB | Vert.x, request workers | 1,232 | 66% | 782 ms | 736 | 951 | 1.32 s | 1.64 s | 157 ms |

The best of both measured semaphore variants at once: the unfair gate's throughput (a worker
that finishes is already running when it takes the next request, so the connection never idles
behind a wake) and the fair gate's ordering (the queue is first-in first-out). The plan §8 line
is met on throughput at two CPUs (98%); the p99 sits 116 ms over Go, where the floor is 400 ms
of queueing at 1,000 connections and the "within 15 ms" line was written for the hello endpoint.
One CPU is unchanged: the JIT and the collector share the core with the requests.

## Round 13 — is native memory-bound? and the single-item endpoint at one CPU (2026-09-07)

Native image at 1 CPU / 1 GB with `-XX:+PrintGC`: 1,257 collections in the ~75 s run, 3.7 s of
pauses (≈5%), old generation flat at 130 MB, young 95 MB — **not** memory pressure. (The earlier
"doubled at 2 GB" reading compared the 1-CPU/1-GB row with the 2-CPU/2-GB row: that was the CPU
doubling.) Native is CPU-bound and about half the JIT per request on this platform.

The 1 KB single-item endpoint (`GET /api/event-types/{id}`: the same cookie-session path,
three queries and a JWT verify, almost no serialisation) at 1 CPU / 1 GB:

| server | req/s | share of Go | p99 |
|---|---:|---:|---:|
| Go | 6,538 | 100% | 189 ms |
| Java JIT | 3,115 | 48% | 582 ms |
| Java native `-O2` | 2,135 | 33% | 566 ms |
| Node (TypeScript platform, own schema) | 1,101 | 17% | 1.24 s |

The same ratios as the 48 KB page: the cost is the per-request session path, not the payload.
Where exactly is the next measurement (a JFR profile of the JIT jar on this endpoint).

## Round 14 — where the per-request CPU goes: JFR profiles of the JIT jar and the native `-O2` image (2026-09-07)

Both on the single-item endpoint at 1 CPU / 1 GB, Vert.x listener, `settings=profile` (10 ms
sampling), 40 s inside steady load. HotSpot's sampler only sees Java frames (976 samples); the
native-image sampler sees everything including its GC and socket calls (3,112 samples, 78% of the
core). Buckets are per sample, first matching frame in the stack wins.

| bucket | JIT (Java frames) | native `-O2` (all frames) |
|---|---:|---:|
| jOOQ: render SQL / send+wait on pgjdbc / fetch+map rows | 36% | 43% (20% / 15% / 9%) |
| RSA-2048 verify of the session JWT (`RSACore`, `BigInteger`) | 34% | 29% |
| Vert.x + netty (parse request, write response) | 13% | 11% |
| own code (`io.flowcatalyst`) | 8% | 6% |
| Jackson | 5% | 4% |
| Nimbus JWT parse | 4% | 2% |
| GC | (not sampled) | 4.5% |

The request does one RS256 verify (one call path, one key, no signing: confirmed with 40-deep
stacks). The verify itself, measured in the same 1-CPU container:

| runtime | RS256 verify, 2048-bit |
|---|---:|
| Go 1.25 | 22 µs |
| Java 25 JIT (C2) | 23 µs |
| Java 25 JIT, C1 only | 128 µs |
| GraalVM CE 25 native `-O2` | 69 µs |

GraalVM CE has `mulAdd`/`squareToLen` stubs but not HotSpot C2's Montgomery-multiply
intrinsics on arm64, so native pays 3× the JIT for the identical verify. Both runtimes spend a
quarter to a third of the request on a verify that Go also does (Go's ~22 µs is ~14% of its
153 µs request). The other half is jOOQ rendering the same three session-path queries from the
AST on every request; pgjdbc's wire cost is 15%.

| server | req/s | CPU per request |
|---|---:|---:|
| Go | 6,538 | 153 µs |
| Java JIT | 3,115 | 321 µs |
| Java native `-O2` | 2,135 | 468 µs |

Two follow-ups fall out, neither a tuning knob: (1) verify a session token once and remember the
verdict for the token's remaining lifetime (removes ~30% of both Java runtimes' request and ~14%
of Go's); (2) the three session-path queries rendered once, not per request (jOOQ static SQL or
plain JDBC on that path; ~20%). Together they are the gap between Java JIT and Go on this endpoint.

Size: the `-Os` binary is 170.5 MB, the `-O2` binary with JFR compiled in 181.5 MB (+6%) for 2.4×
the throughput. The committed profile is still `-Os` (fcdev size ruling); worth an owner ruling
for fc-server.

Rig: `KEEP=1` leaves the server container up after a run (to `docker cp` a profile out);
`SERVER_ARGS` passes binary arguments (`-XX:StartFlightRecording=...` on native). Linux native
build without a Linux Maven: run the reactor inside `ghcr.io/graalvm/native-image-community:25`
with mise's Maven and `~/.m2` mounted (`mvn -o -Pnative -pl server -am package -DskipTests`).

## Round 15 — one core, clean machine, matched pools (2026-09-08)

Earlier attempts this day were worthless: the host reached 0.1 GB free with 19 GB compressed and
7 GB of swap, and everything measured a third of its morning figure. After releasing memory and
restarting the VM, Go reproduced its morning number within 4%, and three Go controls spread across
the run agree within 6%, so the set below is trustworthy.

| endpoint, 1 CPU / 1 GB | Go (pgxpool default, 14 conns) | Java, 32 conns / 30 workers | Java, 14 conns / 13 workers, min=max |
|---|---:|---:|---:|
| `/api/event-types/{id}` (1 KB) | 6,280 · 6,578 · 6,662 | 3,778 (59%) | 3,660 (56%) |
| `/api/event-types` (48 KB page) | 1,869 | 1,361 (73%) | 1,323 (71%) |
| RSS | 114–140 MB | 412–427 MB | 406–409 MB |

**Matching the pools changes nothing.** Java runs the same within 3% at 14 connections as at 32, so
the one-core gap is not connection concurrency; both sides are bound by per-request CPU. Round 14's
profile already located it: ~30% of the request is the RS256 verify of the session cookie and ~20%
is jOOQ rendering the same session-path queries from the AST on every call. Those two backlog items
are the only things that move this row.

**Dropping Javalin and Jetty was worth about a fifth of the gap.** Same endpoints, same Go: the item
row went 3,115 → 3,778 and the list row 1,232 → 1,361 against the pre-cutover build. Fewer classes,
less metaspace and a smaller code cache to contend for on a single core; a side effect of the
cutover rather than its purpose.

Pool sizes as shipped: Java `Database.DEFAULT_POOL_SIZE = 32` (constant, no env override), Go never
sets `MaxConnections` so pgxpool defaults to `max(4, NumCPU)` = 14 in this container. Neither is
deployment-settable — see `docs/backlog.md`.

## Round 16 — the container memory fence (2026-09-14, `docs/spec/jvm-memory.md`)

Same rig as the final table (2 CPUs, 2 GB, 60 s warm-up), 200 connections, all three
runs the same day. The Java image now derives `-Xmx`/`-XX:MaxDirectMemorySize` from the
cgroup limit at start (`docker/jvm-opts.sh`: limit minus max(192 MiB, 15%)); the
"default" row opts out through `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=25`, i.e. the
JVM's own quarter-of-container ergonomics, same image.

| server | heap ceiling | req/s | share of Go | p50 | p99 | memory after run |
|---|---:|---:|---:|---:|---:|---:|
| Go (`bench-real-go`, 7-day-old image) | — | 2,344 | 100% | 84 ms | 102 ms | 37 MB |
| Java 25, JVM default (25%) | 512 MiB | 2,543 | 108% | 68 ms | 507 ms | 475 MB |
| Java 25, fence | 1,740 MiB (+153 MiB direct) | 2,623 | 112% | 66 ms | 584 ms | 542 MB |
| Java 25, fence + `-Xlog:gc` | 1,740 MiB | 2,597 | 111% | 66 ms | 565 ms | 488 MB |

- The fence does what the spec says in a real container: the task log reads
  `jvm-opts: limit 2147483648 bytes -> -Xmx1740m -XX:MaxDirectMemorySize=153m`, then
  `jvm memory collectors=[G1 …] max_heap_mib=1740 max_direct_mib=153 processors=2`.
- **The p99 tail is not the heap and not the collector.** The earlier suspicion (final
  table: "the default quarter-of-container heap is the first suspect") is falsified: a
  3.4× larger ceiling moved nothing, and G1 never used it — the committed heap sat at
  ~317 MB throughout, with 987 young pauses over the 75 s run, max 13.5 ms, 2% of wall
  time, remarks under 4 ms. A 500 ms p99 behind a 66 ms p50 at 200 connections on two
  cores is queueing, which points at the request-worker admission queues
  (`admission.md` §11.7) — the owner-ruled design, and a question for the verification
  plan, not for the memory fence.
- Throughput and median are ahead of Go in every Java row; the fence costs nothing and
  removes the per-task heap knob. Adopted as the image default.
