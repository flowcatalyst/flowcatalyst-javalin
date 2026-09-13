# Brief: move the HTTP layer from Javalin/Jetty to Vert.x 5

Audience: the agent doing the refactor in this repository. Everything below is either a
measurement from `../test-size/RESULTS.md` (rounds 1–9, 2026-09-05 → 2026-09-13) or a fact
about this codebase. Where you find the codebase differs from what is stated here, the code wins;
record the difference in your report.

## 0. Read first: this move was made once and reverted

The Vert.x listener was built (Phases 0–3 of `docs/vertx-plan.md`), merged, and
**reverted by the owner on 2026-09-08** (`docs/vertx-plan.md` §9; `docs/STATUS.md`
"Vert.x listener cutover — reverted"). The reasons were not performance: MCP's
servlet-based streamable-HTTP transport had been parked rather than ported, the
servlet ecosystem as a whole would need bespoke Vert.x integrations indefinitely,
and on the real server (`bench/real/RESULTS.md`, not the `/hello` rig this brief
cites) the listener choice was within 2% on throughput at the two-CPU-and-up scale
the product ships at. Everything listener-independent from that work already
survives in `main`: the pool gate (`GatedDataSource`, `docs/spec/admission.md`),
the tier-2 bulkheads, the h2c mediation client, `RequestWorkers`. The seam
(`io.flowcatalyst.http`) is unchanged and is still Javalin/Jetty's only caller.

**Owner ruling 2026-09-13: this is a deliberate second attempt.** The reversion was
about MCP and servlet-library compatibility, not performance; MCP now has a Vert.x
transport path (the MCP SDK's `McpStreamableServerTransportProvider` on Vert.x,
`docs/vertx-plan.md` §5 Phase 4), and the owner judges the Vert.x solution superior.
The agent still ports MCP onto that transport as part of this work (P2, not Phase 4)
and reports the two-CPU number against the 2026-09-08 baseline, so the decision rests
on measurements from the real server this time.

**What the owner actually wants from the move (2026-09-13):** that a flood of
requests never makes the server "go crazy" — requests are admitted in an orderly,
bounded way per kind of work. That is the admission design in
`docs/spec/admission.md` §9 and §11, and it is the reason the listener matters: the
request-level queue-plus-workers model plugs in at the accept boundary, which
Jetty's thread-per-request model does not expose (`RequestWorkers` has no production
caller today for exactly that reason). See P2a below.

## 1. Goal

Replace Javalin 7.2.3 / Jetty 12.1.12 as the inbound HTTP server with Vert.x 5.1.7 core +
vertx-web, keeping the wire contract byte-for-byte, so that the server:

- holds a **bounded working set** at 1,000+ concurrent connections. The capped-heap
  rows in §2 (`-Xmx64m..128m`, ≤ 150 MB RSS, no throughput loss) are a *demonstrated
  capability*, run to confirm the server behaves under constrained memory — they are
  **not** a production ceiling for the core domain services, which may be sized
  larger (owner, 2026-09-13). The requirement is that memory is bounded and
  proportional to load, and that a small heap degrades gracefully rather than
  collapsing; the deployment picks the actual heap;
- halves tail latency on the DB-bound path (target: p99 ≤ 40 ms at c=1000 on one pinned core,
  from ~70 ms today);
- keeps or improves throughput (today ≈ 28k req/s per core on the router test).

Non-goals: changing the OpenAPI surface, the JSON shapes, the MDC/logging contract, the env-var
contract, the persistence layer (jOOQ/Hikari/Flyway), the router's delivery semantics, or the
fcdev boot sequence. No SSE/WebSocket work: the codebase has none (verified by grep).

## 2. Why (the evidence)

All runs: `GET /hello` → one Postgres query → small JSON, 32-connection pool, `wrk -c1000`.

| finding | Javalin/Jetty | Vert.x 5 |
|---|---:|---:|
| 14 cores, uncapped, p99 / max | 71–79 ms / 280–318 ms | 24–44 ms / 37 ms (model A) |
| 14 cores, RSS after 1M responses (native) | 830–1,134 MB | 65–113 MB |
| native, 128 MB heap cap | 20k req/s, read errors | not needed |
| native, 64 MB heap cap | OutOfMemoryError | — |
| one pinned core, jar, `-Xmx64m` | not run (see §7 to run it) | 69k req/s, p99 28 ms, 113 MB |
| 10k SSE subscribers, `-Xmx256m` | direct-buffer OOM after 8,188 events | not run |

Diagnosis (RESULTS.md, "Verification of hunches"): Jetty's working set at 1,000 connections is
*need*, not GC laziness — per-connection buffer sets plus servlet-layer garbage per request. Under
a small heap the collector thrashes and throughput collapses. Vert.x/Netty's per-connection state
is small and its allocation per request is low, so a 64 MB heap holds full throughput.

Two further findings that shape the design:

- **Model B wins.** Event-loop verticles accept; each request's blocking work runs on its own
  virtual thread; the response write is marshalled back with `runOnContext`. Model A (virtual-
  thread verticles, one lane per instance) serialises requests when lanes < pool connections
  and has a worse tail once lanes > connections (p99 100–157 ms). The hop back is free (model C,
  writing from the virtual thread, was no faster).
- **A semaphore gate in front of HikariCP.** `Semaphore(poolSize)` acquired on the request's
  virtual thread before `getConnection()`. Hikari's contended borrow is a *timed* park through the
  JDK's delay-scheduler thread plus an adder-thread wake; on one core that cost 3.9 kernel context
  switches per request. The gate (an untimed park) took it to 0.82, +14% throughput, p99 50 → 43 ms.
  It also cut memory 30–45% on 2–3 pinned cores (fewer wakes → less allocation → smaller heap).

## 3. Current state of this codebase (what you will touch)

- Entry: `server/src/main/java/io/flowcatalyst/server/Main.java`; composition and ordered
  shutdown in `server/.../server/Server.java` (listeners first so Jetty drains, then router drain,
  reaper, mail, scheduler, leader resources, publishers, outbox, stream). Preserve that order.
- Web: Javalin 7.2.3 on Jetty 12.1.12, pinned in `pom.xml` (HTTP/2 add-ons `jetty-http2-server`,
  `jetty-alpn-*`, h2c + h2). Vert.x 5.1.7 is already a dependency, used only as the outbound
  mediation client: `server/.../http/vertx/VertxMediationClient.java`. Reuse one `Vertx` instance.
- JSON: Jackson 3.1.5 (`tools.jackson`) behind `platform/shared/json/Json.java`. Do **not** put
  Vert.x's `JsonObject`/`Json` (Jackson 2 based) on the wire path; encode with the platform mapper
  into a `Buffer`.
- Second listener: `server/.../server/Metrics.java` on `FC_METRICS_PORT` (9090) serving
  `/health`, `/ready`, `/metrics`; `Health.java` returns 503 with named failing checks.
- Config: `server/.../server/Env.java` (~100 `FC_*` vars with alias precedence) + `DotEnv.java`.
- Logging: programmatic Logback in `server/.../server/Logging.java`; `MdcKeys` are a cross-
  language contract. Every request must still populate the same MDC keys.
- Embedded SPA: `server/src/main/resources/frontend` (vendored build, served by the server).
- Contract checks that must stay green: `parity/` (43 scenarios over a 103-route surface, diffed
  against the Go binary), `conformance/` (mediation outcomes), `e2e/` (Playwright), the OpenAPI
  lock `server/src/main/resources/openapi/openapi.lock.json` vs `sdk/openapi/openapi.json`,
  `tools/jooq-verify.sh`.
- fcdev: `fcdev/.../StartCommand.java` shades the server with embedded Postgres; native-image is
  wired for `server` and `fcdev` (`-Pnative`, `reachability-metadata.json` generated partly by
  `io.flowcatalyst.tools.NativeReflectConfig`). Both must still build and boot.
- Build: `maven.compiler.failOnWarning=true`, `-Xlint:all`, Java 25 with `--enable-preview`.

## 4. Target architecture

```
Vertx (one instance, shared with VertxMediationClient)
 ├─ HttpServerVerticle × N (N = available processors), ThreadingModel.EVENT_LOOP
 │    Router (vertx-web): auth → body limit → route → handler adapter
 │    handler adapter: submit work to a virtual-thread-per-task executor;
 │                     on completion ctx.runOnContext(v -> write response)
 ├─ MetricsVerticle × 1 on FC_METRICS_PORT: /health /ready /metrics
 └─ existing subsystems unchanged (router, outbox, stream, scheduler, …)

DB access: Semaphore(hikari.maximumPoolSize) acquired on the virtual thread, released in finally,
           around every getConnection() on request paths (one gate per DataSource).
```

Key choices, each backed by §2:

1. **N event-loop server verticles sharing one port** (`setInstances(N)`); Vert.x round-robins
   accepted connections across instances. One instance was measured at 11.5k req/s (serialised);
   N = cores gave 52–60k.
2. **Virtual thread per request, not `executeBlocking`.** `executeBlocking` uses a bounded worker
   pool and ordered queues; a `Executors.newVirtualThreadPerTaskExecutor()` gives one lightweight
   thread per request and lets existing blocking jOOQ/Hikari code run unchanged. Keep
   `StructuredTaskScope` usage as is.
3. **Write from the request's context.** After the blocking work, `context.runOnContext(...)`
   then `response.end(buffer)`. Never call the response from the virtual thread directly.
4. **Gate in front of Hikari — already in `main`.** `platform/shared/database/GatedDataSource`
   is exactly this (untimed semaphore sized to the pool by identity, a reserve for the health
   probes, a nested-acquire guard; `docs/spec/admission.md` §1) and is what the platform's
   pool is wrapped in today. Do not build a second one; verify it is on the request path
   under the new listener (§7.4's context-switch count is the check).
5. **Explicit heap.** Set `-Xmx` (or `-XX:MaxRAMPercentage`) in the Dockerfile and fcdev launcher;
   the JVM's default of ¼ RAM is what produced the 1 GB RSS numbers. The value is a per-deployment
   sizing decision — the 64–128 MB runs proved the floor works, they do not set the number.
   Under a 1-CPU quota the JVM chooses Serial GC ergonomically; that is fine and was the
   configuration measured.
6. **HTTP/2:** `HttpServerOptions.setHttp2ClearTextEnabled(true)` for h2c parity with Jetty; h2 over
   TLS via `setUseAlpn(true)` + `setSsl(true)` where the Jetty config enabled it. Verify against
   whatever tests exercise h2c today (there is an h2c mediation test on the outbound side).

## 5. Work plan (each phase ends green on `make ci` equivalents)

**P0 — Inventory and baseline (no code change).**
- List every Javalin API use: `app.get/post/...`, `before/after`, `exception(...)`, `ctx.*`
  accessors, access manager / roles, static files, `ctx.json`, cookies/sessions, multipart,
  request body limits, CORS, compression. Produce a table: Javalin feature → Vert.x equivalent →
  file(s). Anything with no equivalent (Jetty sessions, if used) is a decision point: stop and report.
- Record baseline numbers with the rigs in `bench/` and the protocol in §7 (jar and native).

**P1 — Server skeleton behind the existing composition root.**
- `HttpServerVerticle` + `MetricsVerticle`; wire into `Server.start()`; remove the Jetty listener.
- Handler adapter with the virtual-thread + `runOnContext` pattern; a `RequestContext` shim that
  exposes what handlers use today (path/query params, headers, body as bytes/JSON via the platform
  mapper, principal, response status/headers/body), so handler bodies change as little as possible.
- Error mapping: reproduce the exact problem/error JSON shapes and status codes; the parity
  scenarios will tell you where they drift.
- MDC: populate `MdcKeys` on the virtual thread at handler entry and clear on exit.

**P2 — Cross-cutting.** Auth middleware, CORS, body size limits, request timeouts, static SPA
(`StaticHandler` over the classpath directory, same cache headers as today), h2c, access logging,
and **MCP on the SDK's Vert.x streamable-HTTP transport** (the reversion's reason, closed here).

**P2a — Admission: orderly under a flood.** Reuse, do not redesign (`docs/spec/admission.md`):
- Every route already declares its group (`Routes.in(Group)`: LOGIN, OIDC, DISPATCH, INGEST,
  NO_DB, else MAIN). `RequestWorkers` (§9) is the request-level model: per group a FIFO queue and
  N long-lived virtual-thread workers; the listener pushes a parsed request, a worker runs the
  whole chain and hands the response back. FIFO by construction, no wake-to-use gap, and with
  MAIN's N equal to the pool's permits the DB gate never waits. This is what "orderly" means
  operationally, and it is what reached 98% of Go at two CPUs on the previous branch.
- **Sizes are derived, never configured** — MAIN = the pool's ordinary permits, LOGIN/OIDC = the
  processor count (Argon2), DISPATCH its own workers, NO_DB unbounded. The one accepted knob is
  the **DB pool size, per group** (owner, `feedback_no_tuning`). So "the size of the semaphore
  for a workload" is never a separate number: a route declares its group, the group names its
  pool, the pool's size is the group's concurrency. Nothing else to tune.
- The owner's 2026-09-08 direction (`docs/backlog.md` "Endpoint groups") extends the groups to
  **ingest/dispatch, BFF, API, SSE**, each with its own DB pool, all on one port, split by
  transaction span not URL shape, with per-group queue-depth and in-flight metrics exported.
  Three rulings are still open there and must be taken before this phase is built: one Hikari
  pool per group versus per-group lanes on one pool; whether a non-transactional read releases
  its connection between statements; whether the read group may point at a replica.
- Deliverable: `RequestWorkers` wired as the handler adapter's dispatch (replacing the
  virtual-thread-per-request executor in §4 for grouped routes), the per-group metrics, and the
  §7.4 context-switch check proving the gate is off the hot path.

**P3 — Health/metrics/shutdown.** `/health`/`/ready`/`/metrics` semantics unchanged (503 with named
checks). Shutdown order preserved: close HTTP servers first (`HttpServer.close()` drains), then the
existing sequence. Register the same Prometheus collectors; add per-route latency histogram with a
`route` tag if not present.

**P4 — DB gate.** Already done in `main` (`GatedDataSource`, with its tests). The only P4
work is confirming the new listener's request path still obtains connections through it.

**P5 — Dependencies and images.** Remove `javalin`, `jetty-*` from `pom.xml` (keep the Vert.x pin
comment explaining why versions are pinned). Also drop the six declared-but-unused deps found in
the survey (`caffeine`, `mongodb-driver-sync`, `angus-mail`, `json-schema-validator`,
`HdrHistogram`, `mysql-connector-j`) *only if* a grep confirms zero imports; report each.
Dockerfile: add `-Xmx`/`MaxRAMPercentage`; keep jlink. Regenerate native-image reachability
metadata (`NativeReflectConfig`) and confirm `-Pnative` builds for server and fcdev and boots.

**P6 — Verification.** `parity/` 43/43, `conformance/` green, `e2e/` green, OpenAPI lock unchanged
(diff must be empty — the surface is the contract), `tools/jooq-verify.sh` green, native boot test
green. Then §7 benchmarks with before/after tables.

## 6. Pitfalls (each one cost a day somewhere in the measurements)

- **Blocked event loop.** Any JDBC or HTTP call on the event loop stalls every connection on that
  loop. Keep `BlockedThreadChecker` warnings enabled in dev/CI and treat one as a failing test.
- **Jackson 2 vs 3.** Vert.x core depends on Jackson 2; the platform uses Jackson 3 (`tools.jackson`).
  They coexist (different packages) but do not let a `JsonObject` reach a response body or a model.
- **Response after connection close.** Guard `runOnContext` writes with `response.closed()`; wrk at
  c=1000 does drop connections.
- **Hikari timed waits.** Without the gate, 1,000 waiters park with timeouts and wake the
  delay-scheduler thread; the gate must be acquired *before* `getConnection()`, not inside.
- **Virtual-thread pinning.** pgjdbc 42.7 is fine; check any `synchronized` in request paths that
  wraps I/O (JDK 25 has largely removed the pinning penalty, but measure).
- **Native image startup.** Vert.x native started in 3.6 s in one run (uninvestigated outlier);
  measure fcdev native start before/after and report it, do not assume.
- **Sessions.** If Javalin/Jetty sessions or servlet filters are in use anywhere, stop and report:
  vertx-web sessions are a different model and that is a scope decision, not a port.
- **MCP.** The MCP server's streamable-HTTP transport is servlet-based and is the concrete
  reason the 2026-09-08 cutover was reverted (§0). It is a decision point on day one, not a
  Phase 4 item.
- **Serial GC on one core is by design.** Do not add `-XX:+UseG1GC` for the one-core profile.

## 7. Measurement protocol (from `../test-size/bench/`)

Report before/after for each row; single runs carry ±5–10%, so run the pair back-to-back and,
where the delta is < 10%, run both twice.

1. **14-core host, uncapped**: `bench/run.sh <label> <port> <cmd>` — 10 s warm-up, ~1M responses,
   `wrk -t8 -c1000 --latency`. Report req/s, p99, max, RSS after start, RSS after run.
2. **Capped heap**: same with `-Xmx128m` and `-Xmx64m`. The pass condition is "no throughput loss
   at 128 MB, no OOM at 64 MB" — a confirmation that constrained memory is handled, not the
   heap the service will run with (§1).
3. **One pinned core** (the deployment shape): container `--cpuset-cpus=1`, Postgres pinned away
   (`docker update --cpuset-cpus=10-13 <pg>`), wrk on cpus 2–9, 30 s warm-up then 60 s measured,
   `bench/run-onecore.sh` in `../test-size`. A CFS quota (`--cpus=1`) is *not* equivalent: it let
   multi-threaded JVMs fill their budget from several cores while single-threaded servers could not
   (round 9). Report req/s, p99, max, RSS. The Vert.x reference on this shape is 69k / 28 ms / 113 MB.
4. **Context switches per request** on the pinned run: sum `voluntary+nonvoluntary_ctxt_switches`
   from `/proc/1/task/*/status` over a 10 s window ÷ requests. Expect ≈ 0.8 with the gate,
   ≈ 4 without. If you see ≈ 4 after the change, the gate is not on the path.

## 8. Report format

One markdown file, `docs/vertx-migration-report.md`:
- inventory table from P0 and every decision point hit;
- files added/removed/changed, with the dependency diff from `pom.xml`;
- verification results (parity/conformance/e2e/openapi-lock/jooq/native) with commands used;
- the §7 tables, before and after, with the exact commands and host state (load average) noted;
- anything in this brief that turned out to be wrong about the codebase.
