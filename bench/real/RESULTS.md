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
