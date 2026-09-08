# Router-only bench rig — Java vs Go, `docs/spec/router.md` §1, §6.1

Rig: `bench/router/run.sh` (this directory), reusing `bench/real`'s Docker network
(`bench-real`, 172.30.0.0/24), Postgres container (`bench-real-pg` at 172.30.0.2, user/pass
`pg`/`pg`) and context-switch-snapshot convention. Unlike `bench/real` (which drives the
platform's `GET /api/event-types` behind a session cookie), this rig exercises the **router**
in isolation: `FC_PLATFORM_ENABLED=false FC_ROUTER_ENABLED=true FC_DEFAULT_BROKER=postgres`,
against a database (`rt`) that holds nothing but the router's own `queue_messages` table.

The server sits at 172.30.0.10, a small Go sink (`bench-router-sink`, `sink/main.go`) at
172.30.0.11, both pinned to `--cpuset-cpus=2-9` for the sink / `--cpus=N` quotas for the
server, same as `bench/real`. A run: recreate database `rt` (+ its `queue_messages` table);
fill the queue with `TOTAL_MESSAGES` rows **by SQL, before the router container even starts**
(see "Measuring the drain" below — this replaced seeding through the router's own API, which
was measuring the seeder, not the router); start the server; wait for `GET /router/health` =
200 (this is the drain window's t0); poll the sink's `/stats` every 500 ms until delivered ==
total or `TIMEOUT_S`; sample `docker stats` every second meanwhile; snapshot
`/proc/1/task/*/status` at health=200 and at completion for a context-switch count over the
drain; fetch `GET /router/metrics` and `GET /router/monitoring/pools` (confirms which pool
actually ran); verify `queue_messages` is empty; and tear the containers down. See the header
comment in `run.sh` for the exact protocol.

### Pool concurrency: explicit `BENCH` pool via `FLOWCATALYST_CONFIG_URL`, not synthesised `DEFAULT-POOL`

Both routers accept `FLOWCATALYST_CONFIG_URL` (a URL returning the pools+queues JSON, fetched
on start and polled every 5 min — §8.1). The sink now also serves `GET /config`:

```
{"processingPools":[{"code":"BENCH","concurrency":<POOL_CONCURRENCY>}],
 "queues":[{"queueUri":"<QUEUE_URI>","queueName":"BENCH-1","connections":1,"visibilityTimeout":120}, ... one entry per QUEUES]}
```

`run.sh`'s `POOL_CONCURRENCY` env (default **64**) is forwarded to the sink and also decides
whether `run.sh` wires `FLOWCATALYST_CONFIG_URL=http://172.30.0.11:9000/config` into the server
and seeds `pool_code=BENCH` (any `POOL_CONCURRENCY != 0`), or reverts to the old behaviour —
no config URL, `pool_code=DEFAULT-POOL`, whatever concurrency each image synthesises for its
default-broker bootstrap — when `POOL_CONCURRENCY=0`. `QUEUE_URI` is computed once in `run.sh`
by mirroring Go's own `postgresQueueURI` transform (`internal/server/run.go` ~416-430:
`postgresql://` → `postgres://`, same host/db as `FC_DATABASE_URL`) — confirmed against
`router/queue/QueueFactory.java`'s `backendKey`/`createPostgres`, which resolves the same
`postgres://` scheme on the Java side.

**`queue_messages` now has to be created up front, for both images, once a config URL is in
play.** Neither router calls `InitSchema` for a queue that comes from a fetched config — Go
wires `InitSchema` (via the `queue.Embedded` interface) *only* inside the default-broker
bootstrap branch, itself gated on `RouterConfigURL == "" && DefaultBroker == "postgres"`
(`internal/server/run.go:338,352-358`); with a non-empty config URL that whole branch — schema
init included — is skipped, and the Postgres consumer/publisher factories
(`internal/queue/postgres/postgres.go:51-63`) just `pgxpool.New(ctx, cfg.URI)` with no DDL at
all. Since `run.sh` drops and recreates `rt` empty on every run, `reset_db()` now runs the
`queue_messages` DDL from spec §7.3 directly (`CREATE TABLE IF NOT EXISTS …`) right after
`CREATE DATABASE` — a no-op when a router's own bootstrap also creates it (`POOL_CONCURRENCY=0`
path), required when one doesn't.

**Does `FC_DEFAULT_BROKER=postgres` still matter with a config URL set?** No, for Go — its
default-broker synthesis is gated on `RouterConfigURL == ""`, so a non-empty config URL skips
it regardless of `DefaultBroker`'s value; confirmed by every `drain-go-*` run further down
completing correctly with `FC_DEFAULT_BROKER=postgres` still present but functionally
irrelevant. `run.sh` keeps it set anyway, for symmetry with the eventual Java run: the Java
side's blocker (gap #1 below) needs it regardless of config URL, since `Main.java`'s `needsDb`
gate — which decides whether a `DataSource` exists at all — doesn't look at
`FLOWCATALYST_CONFIG_URL` either.

### Measuring the drain, not the seeder (2026-09-07, round 3 — supersedes the round-2 numbers below)

The round-2 numbers (`go-c1-c64`/`go-c1-c256` further down) were measuring the **seed API**,
not the router: seeding 20,000 messages through `POST /router/api/seed/messages` took 14–27 s,
and the router drained them as fast as they arrived — at concurrency 256 the drain was recorded
as finishing 0.74 s *after seeding ended*, at 8% CPU. That's a seeder benchmark wearing a
router benchmark's clothes.

Fixed by filling the queue **before the router container starts at all**: `reset_db()` creates
`queue_messages` as before, then (`SEED_VIA=sql`, the new default) `run.sh` runs one
`INSERT INTO queue_messages (...) SELECT ... FROM generate_series(1, $TOTAL_MESSAGES)`
statement — not a loop — producing `TOTAL_MESSAGES` (default 50,000) unclaimed, immediately-
visible rows, *then* starts the server. Everything from `GET /router/health` = 200 onward is
now the drain: `deliveries_per_s`, `drain_time_s`, the memory/CPU sampler, and the
context-switch snapshot (`snap_before` moved to right after health, `snap_after` at
completion) all measure the router pulling a full queue, nothing else. The old API-seeding
path is kept for debugging behind `SEED_VIA=api` (unchanged behaviour, `SEED_CALLS`×
`SEED_COUNT`, folds seeding into the measured window — not meant to be read as drain numbers).

**Column values and payload shape** (docs/spec/router.md §7.3 for the columns, §2.1 for the
message fields) were pinned to the real thing rather than guessed: one message was seeded
through a live Go router's `POST /router/api/seed/messages` with `SINK_DELAY_MS=30000` on the
sink (so the row stayed claimed-but-undelivered long enough to inspect), then
`SELECT * FROM queue_messages` read back:

```
payload = {"id":"df7ac2ff-...","poolCode":"BENCH","mediationType":"HTTP","mediationTarget":"http://172.30.0.11:9000/hook","dispatchMode":"IMMEDIATE"}
message_group_id = NULL   (confirmed via `IS NULL`, not empty string)
```

`seed_sql()` in `run.sh` reproduces this exactly for each row: `id = 'bench-' || n` (unique),
`queue_name = 'BENCH'`, `message_group_id = NULL`, `receipt_handle = NULL`,
`visible_at = created_at = extract(epoch from now())::bigint` (§7.3: this is what `Publish`
itself does), `receive_count = 0`, and a payload string built the same way with `id` swapped
per row — no other field needs to vary per row for an unordered, unauthenticated, unsigned
benchmark load. Verified at small scale (2,000 rows) before running the full matrix: sink
`count` reached 2,000 and `SELECT count(*) FROM queue_messages` read 0 afterwards — Go accepted
the hand-made payload without any adjustment needed. Every run below also re-verifies this
(`queue_remaining=0` in each summary line).

SQL pre-seeding needs to know the queue's `queue_name` in advance, which is only reliable for
the config-URL (`BENCH`) path — the synthesised-`DEFAULT-POOL` path's queue name is
image-specific (Go hardcodes `"default"`; Java's `QueueConfig.of` defaults the name to the full
`postgres://` URI) and isn't worth branching on, so `POOL_CONCURRENCY=0` still uses the API
seeder regardless of `SEED_VIA` (see the `effective_seed_via` logic in `run.sh`).

**`SINK_H2C`** (default on, `SINK_H2C=1`) makes the sink accept HTTP/2 by prior-knowledge
cleartext in addition to HTTP/1.1, for whenever the Java router (once its publish/seed gap is
fixed) opens h2c to cleartext targets. Go's mediator speaks HTTP/1.1 regardless — every run
below shows `proto_counts={'HTTP/1.1': 50000}` even with h2c available on the sink — so
`proto_counts` in the summary line is the tell for which protocol a given router actually used.

### The drain is poll-bound, not CPU-bound

All three runs below (`drain-go-c1-c64`, `drain-go-c1-c256`, `drain-go-c2-c256`) land within a
narrow band — 365–410 deliveries/s, `mean_cpu_pct` 3.1–3.3% — regardless of pool concurrency
(64 → 256, a 4× change) or CPU quota (1 → 2 cores, a 2× change). Concurrency 256 was not faster
than 64 (365.2 vs 410.3 deliveries/s — noise, if anything slightly *slower*), and doubling CPU
at concurrency 256 barely moved it (365.2 → 380.4). That is the signature of a bottleneck
upstream of the worker pool entirely: the **Postgres consumer poll loop**, not the pool's
concurrency, caps throughput here, for reasons in the spec itself (`docs/spec/router.md`):

- §5 #5 `maxPoll` = **10 messages per `Poll` call** — `Poll(ctx, 10)`, `router/manager.go:434`.
- §3.2 (`router/manager.go:436-507`): after a poll, **only a full batch (==10) re-polls
  immediately**; a partial batch (`<10`, `>0`) sleeps **500 ms**; an empty batch sleeps **1 s**.
- §7.3 / spec line 283: `QueueConfig.connections` — "**nothing** — no backend reads it; it only
  participates in change detection" — i.e. there is exactly **one** consumer goroutine per
  queue, full stop; raising `connections` in the sink's `/config` would not spin up parallel
  pollers on either side, because neither router implements that.

So for a single Postgres-backed queue, the router can never pull more than 10 messages per
poll round-trip, and with 50,000 rows queued it should be doing back-to-back full-batch polls
(no 500 ms/1 s pause) for nearly the whole run — the ceiling is `10 / (poll round-trip
latency)`, not pool concurrency, which is exactly consistent with concurrency 64 and 256
performing the same and CPU staying flat near-idle while the sink (also idle-fast, no
`SINK_DELAY_MS`) waits on the next trickle of 10. Raising pool concurrency past whatever the
poll loop can feed it buys nothing; the orchestrator would need either a config knob this
codebase doesn't have (`maxPoll` and the batch-size-10 pause rule are Go constants, not
env-configurable) or a change to the poll loop itself to test whether a bigger `Poll` batch
size changes this number.

### Queue parallelism (`QUEUES`) — round 4: a legitimate way past the poll-loop ceiling, and what it actually hits instead

One consumer per queue is fixed (`connections` is dead, above) — but nothing stops there being
more than one *queue*. `QUEUES` (default 1) makes the sink's `/config` advertise `BENCH-1` ..
`BENCH-QUEUES`, all sharing one `queueUri` (same Postgres database) but distinct `queueName`s,
all routed to the single `BENCH` pool. This is real production shape, not a rig trick: the
Postgres backend's claim query filters `WHERE queue_name = $1` on `QueueConfig.Name`
(`internal/queue/postgres/postgres.go:176` — confirmed by reading the query text directly), not
on the URI, and `Identifier()` is also `cfg.Name` (§7.1/§7.3) — so N distinct `queueName`s
against the same database really are N independent consumer goroutines, each with its own
poll loop and (§7.3: "one pgxpool per consumer") its own connection pool, each capped at
`maxPoll=10` individually but running *concurrently*.

`seed_sql()` distributes `TOTAL_MESSAGES` round-robin: `queue_name = 'BENCH-' || (((n-1) %
QUEUES) + 1)`. Verified at small scale first (`QUEUES=4`, 2,000 rows): the per-queue
distribution came out exactly even (500/500/500/500 — `GROUP BY queue_name` printed by
`run.sh` before every SQL-seeded run, not just this check), all 2,000 were delivered, and
`queue_messages` was empty afterward.

**Pitfall: Postgres connection exhaustion.** `bench-real-pg`'s `max_connections` was still the
factory default (100) — `bench/real`'s own `ALTER SYSTEM SET max_connections = 200` never got
applied to this shared container in this session. At `QUEUES=8` this was enough to trip "sorry,
too many clients already" (N consumer pgxpools + the rig's own polling connections). Fixed in
`prepare()`: check `SHOW max_connections`, and if under 300, `ALTER SYSTEM` + `docker restart
$PG` (the same fix `bench/real/run.sh` already applies for its own reasons) before doing
anything else. Even at 300 this was hit again transiently at `QUEUES=32` under load — see below.

**Pitfall: "sink delivered everything" isn't "the broker is empty."** The sink counts a hit the
instant it answers 200; the router's own ACK (a `DELETE`) reaches Postgres afterwards,
asynchronously. Killing the server the moment the sink hits `total` can abandon ACKs already in
flight — the first `QUEUES=8` attempt left 2,077 rows permanently orphaned in `queue_messages`
this way (confirmed: after the container was gone, nothing was ever going to delete them). Fixed
by polling `queue_remaining` too, after the sink-count loop, with its own grace window
(`DRAIN_GRACE_S`, default 30s) before tearing the server down — `drain_time_s` now covers this
wait as well, and `queue_remaining` in the summary line is the actual proof, not the sink's word
for it.

**Results — `QUEUES=8` behaves exactly like more capacity; `QUEUES=32` does not:**

| label | cpu | queues | delivered/total | deliveries/s | drain time | router mean CPU% | **PG mean CPU%** |
|---|---|---|---|---|---|---|---|
| drain-go-q8-c1 | 1 CPU | 8 | 50000/50000 | 5961.9 | 8.4 s | 44.4% | **801.6%** |
| drain-go-q8-c2 | 2 CPU | 8 | 50000/50000 | 6683.3 | 7.8 s | 43.1% | **738.7%** |
| drain-go-q32-c2 | 2 CPU | 32 | see below — did not complete cleanly | | | | |

```
== drain-go-q8-c1 image=bench-real-go cpu='--cpus=1 --memory=1g' env='' authmode=noauth pool_code=BENCH pool_concurrency=256 queues=8 pool_seen=yes(concurrency=256) seed_via=sql
   total_messages=50000 delivered=50000 deliveries_per_s=5961.9 drain_time_s=8.4 queue_remaining=0
   max_rss_mb=87.6 end_rss_mb=87.6 mean_cpu_pct=44.4 pg_mean_cpu_pct=801.6 context_switches=74104 switches_per_delivery=1.482
   proto_counts={'HTTP/1.1': 50000}
```

```
== drain-go-q8-c2 image=bench-real-go cpu='--cpus=2 --memory=2g' env='' authmode=noauth pool_code=BENCH pool_concurrency=256 queues=8 pool_seen=yes(concurrency=256) seed_via=sql
   total_messages=50000 delivered=50000 deliveries_per_s=6683.3 drain_time_s=7.83 queue_remaining=0
   max_rss_mb=34.7 end_rss_mb=34.7 mean_cpu_pct=43.1 pg_mean_cpu_pct=738.7 context_switches=72534 switches_per_delivery=1.451
   proto_counts={'HTTP/1.1': 50000}
```

`QUEUES=8` is a clean, complete answer to "is the router CPU-bound?": **no, but the database
it's talking to is.** 8 independent poll loops raised throughput ~15× over one queue (410 →
5,962–6,683 deliveries/s), `queue_remaining=0` both times (verified, not assumed), and the
*router* process never got near saturated at either CPU quota (43–44%, barely moved by doubling
CPU) — but `bench-real-pg` sat at 740–800% CPU (7–8 cores' worth) for the whole drain. Past the
poll-loop ceiling, this specific workload (a `SELECT ... FOR UPDATE SKIP LOCKED` claim query
with a per-row anti-join subquery against message-group ordering, run by up to 8 independent
pools) is Postgres-CPU-bound, not router-CPU-bound. Doubling the router's CPU quota (1→2) barely
moved throughput (5,962 → 6,683/s, +12%) — consistent with Postgres, not the router, being the
shared constraint.

**`QUEUES=32` did not complete cleanly, twice**, and *that itself* is the finding for this
setting: it's not a bigger version of `QUEUES=8`, it's a different, unstable regime.

- First attempt (`DRAIN_GRACE_S=30`, the default): sink hit 50,000, but `queue_remaining` never
  reached 0 within the 30s grace window (kept erroring — Postgres connection pressure again,
  this time even after raising `max_connections` to 300) — teardown then abandoned **13,646**
  still-unacked rows (`results/drain-go-q32-c2.log`).
- Second attempt, same config, `DRAIN_GRACE_S=180` (`drain-go-q32-c2-b`): still never reached
  `queue_remaining=0` — 7,737 rows remained even after the extended grace window and the
  container was gone. More strikingly, **the sink recorded 62,876 hits against 50,000 seeded
  messages** — a 26% *over*-delivery, i.e. real redeliveries, not just a slow ACK tail.
  `results/drain-go-q32-c2-b.metrics.prom`'s per-consumer counters make this concrete: most of
  the 32 `BENCH-N` consumers received close to their fair share (~1,563 = 50,000/32), but six of
  them (`BENCH-5`, `-8`, `-9`, `-23`, `-27`, `-29`) received **1.5–2×** that — `BENCH-9` alone
  received 3,056. Once a queue's ACKs fall behind, its unacked messages' visibility windows
  (120s) lapse and they become reclaimable *again*, which is more work for that same
  already-behind queue — a self-reinforcing backlog on a subset of queues, not a uniform
  slowdown.

  ```
  == drain-go-q32-c2-b image=bench-real-go cpu='--cpus=2 --memory=2g' env='' authmode=noauth pool_code=BENCH pool_concurrency=256 queues=32 pool_seen=yes(concurrency=256) seed_via=sql
     total_messages=50000 delivered=62876 deliveries_per_s=402.7 drain_time_s=217.15 queue_remaining=
     max_rss_mb=123.3 end_rss_mb=100.5 mean_cpu_pct=10.4 pg_mean_cpu_pct=58.6 context_switches=220409 switches_per_delivery=3.505
     proto_counts={'HTTP/1.1': 62876}
  ```

  (`queue_remaining` prints blank, not `0`, because the grace loop's own `psql` kept failing
  against the same connection pressure it was trying to measure — see the WARNING line in
  `results/drain-go-q32-c2-b.log`; the true value, read separately once the container was gone
  and connections had freed up, was 7,737.)

**What bounds `QUEUES=32`, then, if not CPU?** Neither side's CPU: router `mean_cpu_pct=10.4%`
and Postgres `pg_mean_cpu_pct=58.6%` are both *lower* than at `QUEUES=8` (44%/800%), despite 4×
the queues and a wildly worse outcome. That combination — falling CPU on both sides, rising
instability — points at **contention, not compute**: 32 independent pgxpools opening/competing
for connections and lock-waiting on each other's `FOR UPDATE SKIP LOCKED` claims and per-row
anti-join subqueries against the same table, plus the "too many clients" pressure observed
directly during the run (even at `max_connections=300`). CPU sits idle while things wait in
line. This is a real, reportable ceiling on the "N independent pgxpools against one Postgres"
architecture (§7.3) — not a rig artifact — and the orchestrator should treat `QUEUES=32` /
`POOL_CONCURRENCY=256` on a single 2-CPU Postgres as **known-unstable at this rig's current
settings**, not as a valid throughput data point; `QUEUES=8` is the largest value tested here
that stayed clean.

## Files built

- `sink/main.go`, `sink/go.mod` (+ vendored `golang.org/x/net` for the `SINK_H2C=1` h2c path)
  — the mediation target: `POST /hook` → 200 `{"ok":true}`, counts total/per-status/per-proto
  hits and first/last timestamps; `GET /stats`; `POST /reset`; `SINK_DELAY_MS` sleeps before
  answering (the slow-subscriber shape); `GET /config` serves the `FLOWCATALYST_CONFIG_URL`
  document — `POOL_CONCURRENCY`/`QUEUE_URI` env-driven, `QUEUES` (default 1) controls how many
  `BENCH-1..BENCH-n` queue entries it lists (see "Queue parallelism" above); h2c
  prior-knowledge cleartext accepted whenever `SINK_H2C=1` (the default).
- `Dockerfile.sink` — multi-stage, vendored deps so the build needs no network.
- `run.sh` — `prepare` (also raises `bench-real-pg`'s `max_connections` if under 300 — see
  "Queue parallelism" above) and `run <label> <image> "<cpu-args>" [ENV=v ...]` (`SINK_*` args
  route to the sink container, everything else to the server; `POOL_CONCURRENCY` env, default
  64, wires the config-URL `BENCH` pool; `QUEUES`, default 1, how many independent queues feed
  it; `TOTAL_MESSAGES`, default 50,000, rows pre-seeded by SQL before the router starts, spread
  round-robin over the `QUEUES` queue names; `DRAIN_GRACE_S`, default 30, extra time to wait for
  `queue_remaining=0` after the sink reports completion, before tearing the server down;
  `SEED_VIA=api` reverts to the old API-seeding path for debugging).
- `results/` — per run: `<label>.log` (summary), `<label>.server.log`, `<label>.sink-stats.json`,
  `<label>.metrics.prom`, `<label>.pools.json` (`/router/monitoring/pools`, confirms pool pickup).

## Reproduce

```
bench/router/run.sh prepare
POOL_CONCURRENCY=64  bash run.sh run drain-go-c1-c64  bench-real-go "--cpus=1 --memory=1g"
POOL_CONCURRENCY=256 bash run.sh run drain-go-c1-c256 bench-real-go "--cpus=1 --memory=1g"
POOL_CONCURRENCY=256 bash run.sh run drain-go-c2-c256 bench-real-go "--cpus=2 --memory=2g"
QUEUES=8  POOL_CONCURRENCY=256 bash run.sh run drain-go-q8-c1  bench-real-go "--cpus=1 --memory=1g"
QUEUES=8  POOL_CONCURRENCY=256 bash run.sh run drain-go-q8-c2  bench-real-go "--cpus=2 --memory=2g"
QUEUES=32 POOL_CONCURRENCY=256 DRAIN_GRACE_S=180 bash run.sh run drain-go-q32-c2 bench-real-go "--cpus=2 --memory=2g"  # see below — unstable at this setting
                      bash run.sh run java-c1 bench-real-java "--cpus=1 --memory=1g" FC_HTTP=vertx  # still blocked, see below — not retried this round
```

(`TOTAL_MESSAGES` defaults to 50,000; every Go run above used the default.)

## Round 2 (2026-09-07) — superseded, measured the seeder not the router

The `go-c1`/`go-c1-c64`/`go-c1-c256` rows from the previous round seeded through
`POST /router/api/seed/messages` and only started timing the drain *after* that call
returned. At concurrency 256 essentially all 20,000 messages had already been delivered
*during* the 14–27 s seed call itself (Postgres makes a row visible the instant it's
inserted), so `deliveries_per_s`/`time_to_complete_s` there were reporting how fast the seed
API could insert rows, not how fast the router could drain a queue — `time_to_complete_s=0.74`
at concurrency 256 was the giveaway. **Those three rows are wrong as router throughput
numbers and are superseded below**; the mechanism (`--enable-preview`, auth handling,
`FLOWCATALYST_CONFIG_URL`/`POOL_CONCURRENCY` wiring) they validated is still correct and
unchanged. Full text of the original round-2 write-up is available in git history for this
file if needed; it's cut here rather than kept alongside the corrected numbers to avoid anyone
reading the stale ones by accident.

## Drain test — 2026-09-07 round 3

| label | image | cpu | pool concurrency | delivered / total | deliveries/s | drain time | mean CPU% |
|---|---|---|---|---|---|---|---|
| drain-go-c1-c64 | bench-real-go | --cpus=1 --memory=1g | 64 | 50000/50000 | 410.3 | 122.1 s | 3.1% |
| drain-go-c1-c256 | bench-real-go | --cpus=1 --memory=1g | 256 | 50000/50000 | 365.2 | 137.1 s | 3.2% |
| drain-go-c2-c256 | bench-real-go | --cpus=2 --memory=2g | 256 | 50000/50000 | 380.4 | 132.2 s | 3.3% |

All three: `pool_seen=yes(concurrency=N)` at the requested value, `queue_remaining=0`
(`SELECT count(*) FROM queue_messages` after completion — full drain, verified, not just
sink-side), `proto_counts={'HTTP/1.1': 50000}` (Go; `SINK_H2C=1` was on throughout, unused).

### Verbatim summary lines

```
== drain-go-c1-c64 image=bench-real-go cpu='--cpus=1 --memory=1g' env='' authmode=noauth pool_code=BENCH pool_concurrency=64 pool_seen=yes(concurrency=64) seed_via=sql
   total_messages=50000 delivered=50000 deliveries_per_s=410.3 drain_time_s=122.1 queue_remaining=0
   max_rss_mb=58.5 end_rss_mb=17.6 mean_cpu_pct=3.1 context_switches=145659 switches_per_delivery=2.913
   proto_counts={'HTTP/1.1': 50000}
```

```
== drain-go-c1-c256 image=bench-real-go cpu='--cpus=1 --memory=1g' env='' authmode=noauth pool_code=BENCH pool_concurrency=256 pool_seen=yes(concurrency=256) seed_via=sql
   total_messages=50000 delivered=50000 deliveries_per_s=365.2 drain_time_s=137.06 queue_remaining=0
   max_rss_mb=47.2 end_rss_mb=41.1 mean_cpu_pct=3.2 context_switches=154195 switches_per_delivery=3.084
   proto_counts={'HTTP/1.1': 50000}
```

```
== drain-go-c2-c256 image=bench-real-go cpu='--cpus=2 --memory=2g' env='' authmode=noauth pool_code=BENCH pool_concurrency=256 pool_seen=yes(concurrency=256) seed_via=sql
   total_messages=50000 delivered=50000 deliveries_per_s=380.4 drain_time_s=132.17 queue_remaining=0
   max_rss_mb=44.5 end_rss_mb=34.3 mean_cpu_pct=3.3 context_switches=154954 switches_per_delivery=3.099
   proto_counts={'HTTP/1.1': 50000}
```

Read together against "The drain is poll-bound, not CPU-bound" above: this is the actual
router throughput ceiling for a single Postgres-backed queue in this codebase today, and it
does not move with pool concurrency or CPU quota — see that section for why, and what would
need to change (in the router itself, not this rig) to test past it.

### java-c1 — blocked, two independent gaps (unchanged this round; not retried)

`GET /router/health` came up fine (`authmode=auth` — the monitoring API's BasicAuth applied to
`/router/health` too, unlike the spec's public-path list; not investigated further here). The
run then failed at the very first seed call:

```
FAILED at: seed call 1: {"error":"INTERNAL","message":"internal error"}
```

with the server log showing, first, at consumer start-up:

```
ERROR io.flowcatalyst.router.queue.QueueFactory — queue postgres://pg:pg@172.30.0.2:5432/rt needs postgres but no database is configured
```

and then, on the seed POST itself, an unmapped 404 (`HttpException: Not found`) from the Vert.x
listener.

**Root causes, both in `server/src/main/java`, neither a rig issue:**

1. **`Main.java:36`** — `needsDb = platformEnabled || streamEnabled || schedulerEnabled ||
   scheduledJobEnabled || outboxEnabled` omits the case this bench exercises: router enabled
   with `FC_DEFAULT_BROKER=postgres` and no other DB-backed subsystem. So `dbPool` stays `null`
   and is handed to `Router.start(env, null, ...)`; `QueueFactory.createPostgres`
   (`router/queue/QueueFactory.java:67-71`) checks `dataSource == null` and refuses to build a
   consumer for the default-broker queue, logging exactly the line above.

   This is **not** shared with Go: Go's `cmd/fc-server/main.go` has the textually identical
   `needsDB` formula (also excluding router+postgres) and the same top-level `pool == nil` in
   this configuration — but Go's Postgres queue backend dials its **own** connection straight
   from the queue URI (`internal/queue/postgres/postgres.go:53,61`: `pgxpool.New(ctx,
   cfg.URI)`), independent of the shared top-level pool. That's an architectural difference the
   spec documents (§7.3: "one pgxpool per consumer and another per publisher") but the Java
   `QueueFactory` doesn't yet implement — it requires the shared `DataSource` to be non-null. A
   fix would need either (a) `Main.java` growing `dbPool` whenever
   `routerEnabled && "postgres".equals(defaultBroker)`, or (b) `QueueFactory`/`PostgresQueue`
   opening their own pool from the queue URI the way Go does. Not attempted — out of scope
   (no Java source changes permitted for this task).

2. **`RouterApi.java:52-63`** (class Javadoc, "Routes deliberately absent") — `POST /messages`
   and `POST /api/seed/messages` are **not implemented at all** in the Java router API yet:
   *"need a publisher abstraction that does not exist yet."* This is independent of #1 and
   would block seeding even if #1 were fixed: Java's router currently has no way to accept a
   message from a publisher (HTTP or otherwise) in this default-broker configuration; it can
   only *consume* what's already in `queue_messages` (once #1 is fixed) via schema initialised
   at start-up, and it's the seed API — not row-existence — that's missing here.

**Not attempted:** inserting rows into `queue_messages` directly via SQL to route around gap #2
— this was considered and rejected, because gap #1 means Java would still never poll them
(`QueueFactory` builds no consumer for the queue at all in this configuration), so it would not
demonstrate anything about delivery. Enabling any of the `needsDb`-gating flags
(`FC_SCHEDULER_ENABLED`, `FC_OUTBOX_ENABLED`, etc.) as a workaround was also rejected: all of
them share one `if (needsDb)` block that unconditionally runs the **full platform Flyway
migration + seeder** (`Main.java:75-83`) — a heavy, unrelated side effect that contradicts the
task's own framing ("the router only needs its `queue_messages` table") and would start an
actual competing background subsystem, contaminating the very CPU/throughput numbers this rig
exists to measure. `FC_DATABASE_URL` pointing at an empty database (the fix the task
anticipated trying) does **not** clear this — the gap is structural, not a URL/config issue.

## Pitfalls hit while building this

- **N queues means N connection pools against one Postgres, and that adds up fast.** Round 4
  (`QUEUES`): `max_connections` had to be raised (100 → 300, `prepare()`) to survive `QUEUES=8`,
  and even that wasn't enough to keep `QUEUES=32` clean — "sorry, too many clients already"
  during the run itself. See "Queue parallelism" above for the full account, including how this
  ties into the `QUEUES=32` instability (redeliveries, an ack backlog that didn't clear even
  with a 180s grace window).
- **The sink recording a hit is not the same event as the row disappearing from the broker.**
  `run.sh` used to tear the server down the instant `sink.count == total`; under load the
  router's own ACK (`DELETE`) can still be in flight at that moment, and killing the container
  abandons it permanently. Fixed by polling `queue_remaining` (with its own `DRAIN_GRACE_S`
  grace window) after the sink-count loop, before teardown — see "Queue parallelism" above.
- **`--enable-preview` at runtime, not just build time** (2026-09-07, superseded). `run.sh`
  used to pass `-e JAVA_TOOL_OPTIONS=--enable-preview` because `bench-real-java`'s ENTRYPOINT
  had no `--enable-preview` and router mode loads a preview-feature class
  (`io.flowcatalyst.router.concurrent.Concurrently`) that the platform-only `bench/real` runs
  never touch. **This round:** the orchestrator rebuilt `bench-real-java` with the production
  entrypoint flags (`--enable-preview --enable-native-access=ALL-UNNAMED`) baked in, so the
  `run.sh` workaround was removed — the image now carries the flag itself.
- **A queue fetched from a config URL isn't schema-initialised by either router.** `InitSchema`
  only runs inside the default-broker bootstrap branch on both sides (gated on no config URL);
  with `FLOWCATALYST_CONFIG_URL` set, `run.sh` has to create `queue_messages` itself before
  the first run against a freshly-recreated `rt` — see the config-URL section above.
- **Auth is not uniform across endpoints.** `wait_health()` found `/router/health` passes with
  *or* needs auth depending on the image/config (§9.7 of the spec documents a suspected Go
  defect here: under a mounted prefix, the public-path check doesn't see the rewritten path, so
  probes may need BasicAuth despite being nominally public). The mutating/seed/metrics
  endpoints are **not** in the public-path list regardless, so `run.sh` always sends
  `-u bench:bench` there — an earlier version of the script reused whatever auth mode
  `/health` happened to accept, which 401'd on `/router/api/seed/messages` the first time.
- **A container name reused across two overlapping `run.sh` invocations races.** Backgrounding
  a run and then starting a second one under the same label before the first has actually
  exited left both processes fighting over `docker rm -f`/`docker run --name` for the same
  container; symptoms were nonsensical (health timeouts, `docker logs`: "No such container").
  Not a `run.sh` bug per se, but worth calling out for whoever runs the full matrix: don't
  parallelise runs that share a label, and confirm a prior background invocation has actually
  returned before starting the next one.
- **`DROP DATABASE ... WITH (FORCE)`** (PG13+, fine on `postgres:18-alpine`) is what makes
  `reset_db` reliable even when a previous run's server container didn't get torn down cleanly
  and is still holding a connection to `rt`.
- The sink's `/stats` `per_sec_last_1s` is a coarse "requests in the current wall-clock second"
  counter (CAS on a `(second, count)` pair), not a true sliding window — fine as a live
  indicator, not meant to be precise.
- **To read a row before the router acks it away, stall it.** Discovering the exact payload
  shape (see "Measuring the drain" above) meant seeding one message through a *live* router and
  inspecting the row before it got deleted — normally sub-second. Fix: start the sink with
  `SINK_DELAY_MS=30000` first, seed the one message, then `SELECT * FROM queue_messages` during
  the 30 s the sink is holding the response open (the row is claimed — `receipt_handle` set,
  `receive_count=1` — but still present until the delayed 200 lets the router ack it). A
  one-off discovery technique, not something `run.sh` needs at runtime.
- **A pre-seeded queue needs `queue_messages` to exist before the INSERT, obviously** — but the
  first attempt at wiring `SEED_VIA=sql` skipped straight to seeding and only found out the hard
  way that a config-URL run never creates the table (see "queue fetched from a config URL"
  above): `reset_db()` has to run the DDL unconditionally, before *any* seeding path, not just
  before the router starts.

## Reading `deliveries/s` and `drain_time_s`

`deliveries_per_s` is `count / ((last_ns − first_ns) / 1e9)` from the sink's own timestamps;
`drain_time_s` is `run.sh`'s own wall-clock from `GET /router/health` = 200 to the sink
reaching `total`. With `SEED_VIA=sql` (the default since round 3) these agree closely, because
nothing runs between health and the first delivery — the queue was already full. With the
debug-only `SEED_VIA=api` path they can diverge the way round 2's numbers did: messages become
visible to the router the instant they're inserted (`visible_at = created_at = now`), so
delivery starts *during* the seed calls, before `drain_time_s`'s own timer would suggest — see
"Measuring the drain, not the seeder" above for why that's no longer how the default path
works.

## NATS JetStream (2026-09-07)

The owner asked for the router measured against a real, fast broker (`docs/spec/router.md`
§7.4), on the theory that both the Postgres queue table and LocalStack/SQS were themselves the
ceiling in earlier rounds (see "The drain is poll-bound" and "SQS on LocalStack" above). **The
headline result is not a throughput number: every one of the six matrix runs below hit a
100%-reproducible defect in the NATS queue backend (present identically in Go and the Java
port) that makes it structurally impossible for the router to ever ACK or NACK a NATS-backed
message.** The throughput figures are real numbers from real runs, but they measure a router
stuck retrying against its own broken ack path, not "router vs NATS."

### The defect: `QueueIdentifier` never matches the consumer registry key, for NATS only

`Manager` (Go `internal/router/manager.go:1261`) registers each consumer keyed by
**`QueueConfig.Name`** (`m.consumers[qc.Name] = rc` — the config's plain queue name, e.g.
`BENCH-1`). Every ack/nack resolves the consumer for a message by looking that same map up with
**`QueuedMessage.QueueIdentifier`** (`pool.go:174` `consumerFor` → `manager.go:340
resolveConsumer`). Two of the three queue backends set `QueueIdentifier` to exactly
`QueueConfig.Name`, so the lookup always succeeds:

| Backend | `QueueIdentifier` set to | Matches registry key `qc.Name`? |
|---|---|---|
| Postgres | `q.cfg.Name` (`internal/queue/postgres/postgres.go:242,398,411`) | yes |
| SQS | `q.queueName` (`internal/queue/sqs/sqs.go:194,359,372`, itself `cfg.Name`) | yes |
| **NATS** | **`q.identifier`** = `cfg.StreamName + "/" + cfg.ConsumerName` (`internal/queue/nats/nats.go:163,286,374,387`) | **no — structurally never** |

`Identifier()` for NATS is `<stream>/<consumer>` (spec §7.4, "Identity" row) — a value derived
from the queue **URI**, unconditionally containing a `/`. `QueueConfig.Name` (`BENCH-1`,
`BENCH-2`, …) comes from the **config-URL document** (`docs/spec/router.md` §2.5) and is a
completely independent string with no `/` in it. There is no URI/config choice that makes these
equal — `<stream>/<consumer>` can never literally equal a plain queue name — so **every** NATS
queue, regardless of naming, hits this on **every** ack and every nack. Confirmed identically in
the Java port: `NatsQueue.identifier = config.identifier()` (`server/src/main/java/io/flowcatalyst/router/queue/nats/NatsQueue.java:82,204`) vs. `PostgresQueue.identifier()` returning the
config name (`.../queue/postgres/PostgresQueue.java:233`) — the same asymmetry, so Java fails
the same way.

**Effect, confirmed by grepping every server log against the sink's own delivered count — the
match is exact, not approximate, in all six runs:**

```
go-nats-q1-c1:   delivered=3000   "ack: no consumer for queue" count=3000
go-nats-q8-c1:   delivered=17897  "ack: no consumer for queue" count=17897
go-nats-q8-c2:   delivered=14998  "ack: no consumer for queue" count=14998
java-nats-q1-c1: delivered=3000   "...is no longer registered" count=3000
java-nats-q8-c1: delivered=14245  "...is no longer registered" count=14245
java-nats-q8-c2: delivered=10161  "...is no longer registered" count=10161
```

Sample log lines (Go `pool.go:198`, Java equivalent):

```
{"level":"WARN","msg":"ack: no consumer for queue","queue":"BENCH1/router","message_id":"bench-1"}
WARN ack skipped: queue BENCH1/router is no longer registered (message bench-1)
```

Note this fires from `message_id: bench-1` onward — not a startup race, not a config-reload
blip, the very first message ever claimed. `ackTracked` (`pool.go:190-201`) still removes the
in-flight tracker entry even when the broker ack fails (so the sink-hit count climbs normally),
but the broker copy of the message is **never deleted**. It sits in the stream until JetStream's
own `AckWait` (our URI: 120 s) lapses, then auto-redelivers — up to `MaxDeliver` (our URI: 10)
times — after which JetStream silently dead-letters it (spec §7.4 "Redelivery cap"). That
combination is what produced the smoke test's over-delivery (below) and every matrix run's
`queue_depth_end` staying near or above `total_messages` even after the 30 s grace window: the
router is burning cycles re-claiming and re-"delivering" the same messages instead of draining
the queue, and NATS/router CPU stay near-idle throughout because the bottleneck is a permanently
failing map lookup, not compute.

This is a genuine defect in `../flowcatalyst-go` (mirrored faithfully in the Java port), not a
rig misconfiguration — no source changes were made per this task's scope; reported here for the
owner to fix (likely: NATS's `QueueIdentifier` should be `cfg.Name` like the other two backends,
with `Identifier()` — used only for logging/metrics labels — left as `<stream>/<consumer>`).

### Small-scale proof run (`TOTAL_MESSAGES=2000 QUEUES=2`, Go) — required by the task, and it failed the requirement

The task asked to prove `delivered = seeded`, streams empty, no redeliveries, before running the
full matrix. It did not hold, and this is the reason why (found via the log grep above, after
the fact):

```
== smoke-go-nats image=bench-real-go cpu='--cpus=1 --memory=1g' env='' authmode=noauth broker=nats pool_code=BENCH pool_concurrency=64 queues=2 pool_seen=yes(concurrency=64) seed_via=nats
   total_messages=2000 delivered=3273 deliveries_per_s=27.2 drain_time_s=151.03 queue_depth_end=4000 nats_mean_cpu_pct=0.4
   max_rss_mb=67.0 end_rss_mb=34.9 mean_cpu_pct=0.0 context_switches=3126 switches_per_delivery=0.955
   proto_counts={'HTTP/1.1': 3273}
```

`delivered=3273` against `total=2000` (64% over-delivery — real JetStream redeliveries, not a
slow-ack tail) and `queue_depth_end=4000` never reached 0 within the 30 s grace window — both
directly explained by the defect above once it was found. Stream/consumer provisioning itself
was **not** the problem: `create_nats_streams()` created `BENCH1`/`BENCH2` cleanly, the router's
own `CreateOrUpdateStream`/`CreateOrUpdateConsumer` calls did not error or visibly alter them
(no such log lines), and the pre-seed verify (`nats stream info --json`, `state.messages`)
confirmed both streams held exactly 1000/1000 before the router ever started.

### A rig-side bug found and fixed before the matrix: `seednats`'s first version silently lost messages

The first version of `bench/router/seednats` (a new one-shot bulk producer, modelled on
`seedsqs`, needed because the `nats` CLI's own `pub --count` was not attempted once a faster Go
option was already in hand) used core NATS `nc.Publish` — fire-and-forget, no `PubAck`. A real
run reported `sent=50000, errors=0`, but `nats stream info BENCH1 --json` afterward showed
`state.messages=32999` — **34% of the messages vanished silently**, caught only by this rig's
own pre-seed verify step (`run.sh` `fail "pre-seed verify: nats streams hold N, expected total"`
— see item 5 in the task: "prove delivered = seeded" applies to seeding too, not just draining).
Root cause: fire-and-forget publish has no flow-control signal, so a fast, unbatched, unbounded
burst of publishes can outrun the server. **Fixed** by switching `seednats` to JetStream's
`PublishAsync` (`bench/router/seednats/main.go`), which returns a `PubAckFuture` per message —
still pipelined (`WithPublishAsyncMaxPending`, default 4096) for throughput, but every message is
now confirmed, and loss is a detected `failed` count, not silence. Reverified at 50,000/1 queue
in isolation: `acked=50000 failed=0`, `state.messages=50000`, in 0.42 s.

### The six-command matrix (`POOL_CONCURRENCY=256`, `TOTAL_MESSAGES=50000`)

All six runs completed (no `fail()` — health passed, pre-seed verify passed, seed-then-drain
loop ran to its `TIMEOUT_S=300` cap in every case since the defect above means the queue never
actually drains). Verbatim summary lines:

```
== go-nats-q1-c1 image=bench-real-go cpu='--cpus=1 --memory=1g' env='' authmode=noauth broker=nats pool_code=BENCH pool_concurrency=256 queues=1 pool_seen=yes(concurrency=256) seed_via=nats
   total_messages=50000 delivered=3000 deliveries_per_s=12.5 drain_time_s=331.35 queue_depth_end=51000 nats_mean_cpu_pct=0.3
   max_rss_mb=64.1 end_rss_mb=27.0 mean_cpu_pct=0.1 context_switches=4407 switches_per_delivery=1.469
   proto_counts={'HTTP/1.1': 3000}
```

```
== go-nats-q8-c1 image=bench-real-go cpu='--cpus=1 --memory=1g' env='' authmode=noauth broker=nats pool_code=BENCH pool_concurrency=256 queues=8 pool_seen=yes(concurrency=256) seed_via=nats
   total_messages=50000 delivered=17897 deliveries_per_s=74.5 drain_time_s=331.44 queue_depth_end=58000 nats_mean_cpu_pct=0.5
   max_rss_mb=59.5 end_rss_mb=34.8 mean_cpu_pct=0.8 context_switches=25622 switches_per_delivery=1.432
   proto_counts={'HTTP/1.1': 17897}
```

```
== go-nats-q8-c2 image=bench-real-go cpu='--cpus=2 --memory=2g' env='' authmode=noauth broker=nats pool_code=BENCH pool_concurrency=256 queues=8 pool_seen=yes(concurrency=256) seed_via=nats
   total_messages=50000 delivered=14998 deliveries_per_s=62.4 drain_time_s=330.82 queue_depth_end=58000 nats_mean_cpu_pct=0.5
   max_rss_mb=52.6 end_rss_mb=44.8 mean_cpu_pct=0.6 context_switches=21662 switches_per_delivery=1.444
   proto_counts={'HTTP/1.1': 14998}
```

```
== java-nats-q1-c1 image=bench-real-java cpu='--cpus=1 --memory=1g' env='FC_HTTP=vertx' authmode=noauth broker=nats pool_code=BENCH pool_concurrency=256 queues=1 pool_seen=yes(concurrency=256) seed_via=nats
   total_messages=50000 delivered=3000 deliveries_per_s=12.5 drain_time_s=330.95 queue_depth_end=51000 nats_mean_cpu_pct=0.3
   max_rss_mb=213.0 end_rss_mb=158.2 mean_cpu_pct=1.0 context_switches=12050 switches_per_delivery=4.017
   proto_counts={'HTTP/2.0': 3000}
```

```
== java-nats-q8-c1 image=bench-real-java cpu='--cpus=1 --memory=1g' env='FC_HTTP=vertx' authmode=noauth broker=nats pool_code=BENCH pool_concurrency=256 queues=8 pool_seen=yes(concurrency=256) seed_via=nats
   total_messages=50000 delivered=14245 deliveries_per_s=58.9 drain_time_s=330.5 queue_depth_end=58000 nats_mean_cpu_pct=0.5
   max_rss_mb=226.3 end_rss_mb=211.3 mean_cpu_pct=1.7 context_switches=35672 switches_per_delivery=2.504
   proto_counts={'HTTP/2.0': 14245}
```

```
== java-nats-q8-c2 image=bench-real-java cpu='--cpus=2 --memory=2g' env='FC_HTTP=vertx' authmode=noauth broker=nats pool_code=BENCH pool_concurrency=256 queues=8 pool_seen=yes(concurrency=256) seed_via=nats
   total_messages=50000 delivered=10161 deliveries_per_s=42.3 drain_time_s=331.39 queue_depth_end=58000 nats_mean_cpu_pct=0.4
   max_rss_mb=262.9 end_rss_mb=253.2 mean_cpu_pct=0.9 context_switches=39321 switches_per_delivery=3.87
   proto_counts={'HTTP/2.0': 10161}
```

| label | delivered/total | deliveries/s | router mean CPU% (of quota) | **NATS mean CPU%** |
|---|---|---|---|---|
| go-nats-q1-c1 | 3000/50000 | 12.5 | 0.1% | 0.3% |
| go-nats-q8-c1 | 17897/50000 | 74.5 | 0.8% | 0.5% |
| go-nats-q8-c2 | 14998/50000 | 62.4 | 0.6% | 0.5% |
| java-nats-q1-c1 | 3000/50000 | 12.5 | 1.0% | 0.3% |
| java-nats-q8-c1 | 14245/50000 | 58.9 | 1.7% | 0.5% |
| java-nats-q8-c2 | 10161/50000 | 42.3 | 0.9% | 0.4% |

**Not one run was router-CPU-bound or NATS-CPU-bound** — every single mean CPU% figure, on
either side, is under 2% of quota. That in isolation would normally read as "neither side is the
bottleneck, look at the poll loop" (the same shape as the original Postgres single-queue
finding), but here it is fully explained by the ack defect above: the router spends its (tiny)
time re-claiming messages that were never removed from the stream, not doing real work. The
`QUEUES=8` numbers being higher than `QUEUES=1` (12.5→74.5 Go, 12.5→58.9 Java) is consistent
with more independent poll loops fetching more doomed claims in parallel, not with the broker
handling more real throughput. **`QUEUES=8, --cpus=2` was slower than `QUEUES=8, --cpus=1` for
both images** (Go: 74.5→62.4/s; Java: 58.9→42.3/s) — the opposite of the Postgres result — most
likely extra lock/goroutine-scheduling contention from doubling CPU on a workload that is 100%
wasted-retry churn rather than useful work; not investigated further since the underlying defect
makes these numbers not meaningful to optimise around.

Java did pick up the config-URL `BENCH` pool correctly in every run (`pool_seen=yes(concurrency=256)`,
confirmed via `results/*.pools.json`, which lists both the harmless synthesised `DEFAULT-POOL`
and `BENCH` side by side) — the earlier `java-c1` blocker (`needsDb`/`QueueFactory` requiring a
shared `DataSource` for Postgres-backed queues, RESULTS.md above) does **not** apply to NATS: the
Java `QueueFactory` builds `NatsQueue` directly from the queue URI with no `DataSource`
dependency (`server/src/main/java/io/flowcatalyst/router/queue/QueueFactory.java:73`), so this is
the first time a Java router run in this rig has completed a full drain-window measurement
end-to-end.

**Secondary observation, unrelated to the ack defect:** every Java run's `GET /router/metrics`
came back `{"error":"INTERNAL","message":"internal error"}` (`results/java-nats-*.metrics.prom`,
47 bytes vs. Go's ~4.6 KB of real Prometheus text). The server log shows the actual cause is a
plain 404, not a 500: `"unmapped failure on GET /router/metrics" ... HttpException: Not found`
(`io.flowcatalyst.http.vertx.VertxListener`) — the Vert.x listener (`FC_HTTP=vertx`) does not
have `/router/metrics` mounted in this router-only configuration. Not investigated further (no
Java source changes in scope for this task) but worth a separate look — this is the first time
this rig has gotten a Java router run far enough to fetch that endpoint at all.

### Setup notes

- NATS: `nats:2.11-alpine -js -m 8222` at `172.30.0.13`, `--cpuset-cpus=2-9`, health-waited on
  `GET :8222/healthz`. Provisioning CLI: `natsio/nats-box` (`nats` binary), one persistent
  container (`bench-router-natsbox`) reused across calls like `$PROBER`.
- Per queue `n`: stream `BENCHn`, `--retention=work --storage=memory --subjects='bench.n.>'
  --replicas=1 --max-age=7d`; durable pull consumer `router`, `--pull --deliver=all
  --ack=explicit --wait=120s --max-deliver=10 --max-pending=1000 --filter='bench.n.>'` — every
  value explicit and equal to the Go/Java parser defaults (`docs/spec/router.md` §7.4,
  `NatsQueueUri.java`) except `storage=memory` (deliberate — disk is not the variable under
  test here, matching the existing Postgres/SQS convention in this file) — spelling every field
  out, rather than relying on both sides defaulting the same way, so the router's own
  create-or-update is a **verified** no-op, not an assumed one. Confirmed in practice: no stream
  or consumer alteration errors in any of the six runs.
- Queue URI template handed to the router via the sink's `/config`:
  `nats://172.30.0.13:4222?stream=BENCH%d&consumer=router&subject=bench.%d.>&max-messages=10&poll-timeout-ms=20000&ack-wait-secs=120&max-deliver=10&max-ack-pending=1000&storage=memory&replicas=1&max-age-days=7`.
  The sink's `/config` handler (`sink/main.go`) used to substitute a single `%d` via
  `fmt.Sprintf`; the NATS template needs the queue number substituted **twice** (`stream=BENCH%d`
  and `subject=bench.%d.>`), which `Sprintf` with one arg cannot do (`%!d(MISSING)` on the
  second). Fixed by switching to `strings.ReplaceAll(uri, "%d", ...)`, a strict superset of the
  single-`%d` SQS template's behaviour (verified: SQS runs untouched by this change).
- `nats stream info --json`'s `state.messages` and `consumer info --json`'s `num_ack_pending`
  are the fields used for `queue_depth_end` (both must be 0 for a genuinely empty queue — see the
  defect section above for why they never reach 0 in this round).

## Files built (NATS JetStream round)

- `bench/router/seednats/` (new) — one-shot bulk NATS JetStream producer, modelled on
  `seedsqs/`: `main.go` (JetStream `PublishAsync` + `PubAckFuture`, see "bug found and fixed"
  above), `go.mod`/`go.sum`/vendored `github.com/nats-io/nats.go` (needs go ≥1.25, hence
  `Dockerfile.seednats` uses `golang:1.25-alpine`, not the `1.24`/`1.23` the other two Dockerfiles
  use).
- `bench/router/Dockerfile.seednats` (new).
- `bench/router/sink/main.go` — `%d` substitution changed from `fmt.Sprintf` to
  `strings.ReplaceAll` (multi-placeholder NATS template); `fmt` import removed as now-unused.
- `bench/router/run.sh` — `BROKER=nats` throughout: `NATS_IP`/`NATS`/`NATSBOX`/`NATS_IMAGE`/
  `NATSBOX_IMAGE` constants; `prepare()` builds `bench-router-seednats`; new helpers
  `start_nats`, `start_natsbox`, `natsprobe`, `create_nats_streams`, `nats_stream_messages`,
  `nats_ack_pending`, `nats_queue_depth`; a new `elif [ "$BROKER" = nats ]` branch in `run()`
  (provisioning, seeding, pre-seed verify); `nats` added to the `brokerc` selection, the
  broker-depth poll loop, the final `broker_line` (`nats_mean_cpu_pct`), and both teardown paths
  (`fail()` and the normal `KEEP` branch).

## Reproduce

```
bench/router/run.sh prepare
BROKER=nats TOTAL_MESSAGES=2000 QUEUES=2 bash run.sh run smoke-go-nats bench-real-go "--cpus=1 --memory=1g"
BROKER=nats POOL_CONCURRENCY=256 TOTAL_MESSAGES=50000 QUEUES=1 bash run.sh run go-nats-q1-c1   bench-real-go   "--cpus=1 --memory=1g"
BROKER=nats POOL_CONCURRENCY=256 TOTAL_MESSAGES=50000 QUEUES=8 bash run.sh run go-nats-q8-c1   bench-real-go   "--cpus=1 --memory=1g"
BROKER=nats POOL_CONCURRENCY=256 TOTAL_MESSAGES=50000 QUEUES=8 bash run.sh run go-nats-q8-c2   bench-real-go   "--cpus=2 --memory=2g"
BROKER=nats POOL_CONCURRENCY=256 TOTAL_MESSAGES=50000 QUEUES=1 bash run.sh run java-nats-q1-c1 bench-real-java "--cpus=1 --memory=1g" FC_HTTP=vertx
BROKER=nats POOL_CONCURRENCY=256 TOTAL_MESSAGES=50000 QUEUES=8 bash run.sh run java-nats-q8-c1 bench-real-java "--cpus=1 --memory=1g" FC_HTTP=vertx
BROKER=nats POOL_CONCURRENCY=256 TOTAL_MESSAGES=50000 QUEUES=8 bash run.sh run java-nats-q8-c2 bench-real-java "--cpus=2 --memory=2g" FC_HTTP=vertx
```

**Recommendation to the owner:** re-run this matrix after the `QueueIdentifier` fix above lands
— these six runs measure the ack-defect's retry churn, not NATS's real throughput ceiling, and
should not be compared against the Postgres/SQS numbers elsewhere in this file until then.

## Java rows and the fixes they forced (orchestrator, 2026-09-07 night)

Every Java row below is on `vertx-listener` after the router fixes of the day (`5335a07` single-source
config merge, `97cd7b0` ack by consumer identifier + `/router/metrics` + per-queue Postgres pool
`max(4, nCPU)` ungated, `7468aaf` queue-scoped broker-id index, `cbcf792` event-driven capacity gate and
no partial-batch sleep) and the NATS standing-subscription change in the fetch worktree. Go rows marked
"fixed" are the Go branch `router-fixes-g10-g12` (same three defects fixed there). Pass criteria for every
row: all messages delivered, broker depth 0 at the end, sink count = seeded (no redeliveries).

### Postgres broker, 8 queues, pool 256, 50,000 messages
| server | deliveries/s | router CPU | RSS | Postgres CPU |
|---|---:|---:|---:|---:|
| Go, 1 CPU | 5,962 | 44% | 88 MB | ~8 cores |
| Go, 2 CPU | 6,683 | 43% | 35 MB | ~7.4 cores |
| Java, 1 CPU (2-connection gated pool) | 1,069 | 25% | 236 MB | 1.4 cores |
| Java, 1 CPU (pool max(4,nCPU) ungated) | 2,175 | 45% | 261 MB | 2.3 cores |
| Java, 2 CPU (same) | 2,365 | 70% | 193 MB | 2.9 cores |

Java is latency-bound in its poll cycle here, not CPU-bound; not investigated further because the Postgres
broker is the embedded/dev broker, not the production bus.

### SQS on LocalStack, 8 queues, pool 256 (LocalStack at ~100% of one core is the ceiling in every row)
| server | deliveries/s | router CPU | RSS |
|---|---:|---:|---:|
| Go, 1 CPU | 1,238 | 22% | 43 MB |
| Go, 2 CPU | 1,184 | 20% | 88 MB |
| Java, 1 CPU | 1,137 | 39% | 388 MB |
| Java, 2 CPU | 1,010 | 53% | 339 MB |

### NATS JetStream, single queue, pool 256 — the router is the bottleneck
| server | messages | deliveries/s | router CPU | user CPU/msg | RSS |
|---|---:|---:|---:|---:|---:|
| Go (fixed), 1 CPU | 50,000 | 24,404 | 97% | | 77 MB |
| Go (fixed), 1 CPU | 150,000 | 28,293 | 99% | 26 µs | 112 MB |
| Go (fixed), 2 CPU | 50,000 | 28,710 | 105% | | 27 MB |
| Java, 1 CPU | 50,000 | 7,916 | 99% | | 422 MB |
| Java, 1 CPU | 150,000 | 10,463 | 74% | 72 µs (incl. JIT) | 424 MB |
| Java, 1 CPU | **500,000** | **25,384** | 95% | **34 µs** | 428 MB |
| Java, 2 CPU | 50,000 | 15,546 | 199% | | 230 MB |

**The short rows measure JIT warm-up, not the router.** A 50,000-message drain is six seconds on one core
that the C2 compiler shares with the workers. Warm (500,000 messages), Java reaches 90% of Go's
throughput at 1.3× Go's user CPU per message; kernel CPU per message is the same (~9–10 µs) on both,
so the receiver and the network are not the variable. Memory is the real difference (4×). The JFR
execution sampler is useless on this workload (17 samples in 25 s on virtual threads at one core); the
process user/kernel split from `/proc/1/stat` is the reliable instrument. Java rows should default to
500,000 messages.

### NATS JetStream, 8 queues — the two Java-only shapes that were defects
| server | deliveries/s | note |
|---|---:|---|
| Go (fixed), 1 CPU | 9,154 | |
| Go (fixed), 2 CPU | 21,140 | |
| Java, 1 CPU, per-poll `ConsumerContext.fetch` | 1,361 | zero warnings; a straggler tail, see below |
| Java, 1 CPU, standing subscription (4 queues) | 6,930 | was 374 with the per-poll fetch |
| Java, 1 CPU, standing subscription (8 queues) | 1,385–1,495 | 49,954 of 50,000 delivered in seconds, the last 46 over ~20 s |

The residual 8-queue number is a tail, not throughput: the classic `fetch(batch, maxWait)` waits for a
full batch, so once fewer than ten messages remain per consumer every poll waits the 20 s poll-timeout.
Owner ruling: NATS is not a poller — the backend is being moved to a continuous subscription (the client
keeps a pull request open, the server pushes, the client's flow control is the back-pressure, `poll`
takes from a one-batch buffer with an untimed park). Rows for that shape follow when it lands.

### Defects found by these rows (all fixed in Java; G10–G12 also fixed on the Go branch)
1. Config merge collapsed same-URI queues in a single source (Java only).
2. Router-only mode with the Postgres broker had no pool; Postgres queues did not connect from their URI; the publish/seed API was unimplemented (Java only).
3. NATS acks never resolved — registry keyed by config name, messages stamped with `<stream>/<consumer>` (G10, both).
4. In-flight broker-id index unscoped by queue — NATS ids collide across streams, cross-wired acks (G11, both).
5. Capacity gate was a 2 s sleep; partial batch slept 500 ms (G12, both; owner ruling: no sleeps).
6. Per-queue Postgres pool of 2 gated connections serialised acks (Java only).
7. NATS fetch lifecycle: per-poll ephemeral fetch (tail of lost-until-ack-wait messages), then full-batch waits (20 s tail) (G13; Go's no-wait-first Fetch avoids the second, the continuous subscription is the ruled shape for all three routers).

### NATS JetStream, Java on the continuous subscription (`cbeaf60`), warm rows (500,000 messages, 1 CPU, pool 256)
| queues | deliveries/s | router CPU | RSS | depth end |
|---|---:|---:|---:|---:|
| 1 | 25,638 | 96% | 368 MB | 0 |
| 8 | 14,117 | 79% | 372 MB | 0 |

Single queue: unchanged from the standing-subscription build (25,384), i.e. the listener model costs
nothing and removes the poll timeout. Eight queues: no 20 s tail any more, but 79% CPU and 55% of the
single-queue rate — the remaining gap is the capacity-gate crossing thrash with eight producers
(pool buffer oscillating at its limit) and is the next thing to root-cause; it is a throughput
detail on a shape the owner has ruled out of scope (one router instance, receiver-bound).

## Rust router (owner's `flowcatalyst-rust`, branch `router-bench-wiring` at `39f2d8e5`) — 2026-09-08

The Rust router only consumed SQS until tonight: its NATS and Postgres consumers existed as library
types nobody constructed. Two Sonnet units wired a scheme-dispatching consumer factory, ported G10–G13
and the owner's no-sleep ruling, moved NATS to a continuous subscription, made deployed-mode mediation
HTTP/2 (prior-knowledge h2c), gated `/health` on consumers started, and fixed a Postgres "ack race"
whose root cause was **three poll tasks per queue** (config-sync hot-add, the binary's own start-up loop
and `start()` each spawned one). Image `bench-real-rust` from `Dockerfile.rust`; every row needs
`FC_ROUTER_HTTP_PREFIX=/router`. All rows: delivered = seeded, depth 0.

| broker | queues | messages | deliveries/s | router CPU | RSS | broker CPU |
|---|---:|---:|---:|---:|---:|---:|
| NATS | 1 | 500,000 | 34,871 | 71% | 15 MB | 21% |
| NATS | 8 | 500,000 | 31,770 | 76% | 59 MB | 20% |
| Postgres | 8 | 50,000 | 4,653 | 30% | 9 MB | 4 cores |
| SQS (LocalStack) | 8 | 50,000 | 1,329 | 18% | 21 MB | 98% (LocalStack) |

Like-for-like with the Java warm rows (NATS, 1 CPU, h2c): Rust 34,871/s at 15 MB, Java 25,638/s at
368 MB, Go 21,516/s at 32 MB (Go's continuous-subscription build; its single-queue 500k row is
still failing, see below). Rust is the only one whose eight-queue rate stays near its single-queue rate.

Residue seen in the Rust logs, for a third unit: the stall detector restarts healthy consumers during
a drain (3× on Postgres, 8× on SQS) and one restart looked the consumer up by the wrong key
("Consumer not found for restart consumer_id=BENCH1/router") — the same liveness/identity class
Java (`59c30ef`) and Go (`62e159f`) fixed today; and "Pool at capacity, deferring" is logged per message
under saturation (883 lines), which wants a once-per-transition warning as in the other routers.

## Go, continuous subscription + h2c (branch `router-fixes-g10-g12` at `c446a45`) — 2026-09-08
Eight commits over the owner's `b422466`: G10–G12, continuous NATS subscription, h2c mediator, liveness
while parked, resubscribe on iterator error (the single-queue freeze: nats.go's iterator returned
"no heartbeat received" and the forwarder exited silently), startup-panic logging, pull threshold at
half a batch. All rows: delivered = seeded, depth 0, zero stalls/restarts.

| queues | CPUs | messages | deliveries/s | router CPU | RSS | note |
|---:|---:|---:|---:|---:|---:|---|
| 1 | 1 | 150,000 | 21,516 | 85% | 32 MB | before the continuous-subscription rewrite settled |
| 1 | 1 | 500,000 | 2,940 | 11% | 63 MB | 5 heartbeat misses, each resubscribed in µs; router mostly idle |
| 1 | 2 | 500,000 | 33,295 | 131% | 53 MB | clean |
| 8 | 1 | 500,000 | 16,165 | 75% | 105 MB | clean |

| 1 | 1 | 500,000 | **25,612** | 99% | 83 MB | `c446a45`: Next() gated on a room permit — zero warnings, zero heartbeat notices |

**Resolved (Go, one core, one queue):** the forwarder fetched with `Next()` and THEN blocked on the
full channel holding the message; with `Messages()` the next pull request is only issued from inside
`Next()`, so a parked poll loop let the outstanding request expire, and the client then waited for
messages nobody had requested until its heartbeat monitor tripped (`Consume` moved the same block onto
the delivery goroutine). Fix: acquire a room permit before `Next()`, never hold a fetched message
while blocked. Earlier text kept for the record:

**Was open (Go, one core, one queue):** the continuous `Messages()` path is RTT/scheduling-bound on
GOMAXPROCS=1 — 11% CPU, heartbeat misses that never occur at 2 CPUs — while the pre-rewrite
`Fetch` build did 28,293/s on the same row and Java/Rust do 25–35k/s on it. Java/Rust were run on the
same host minutes apart, so host noise does not explain it. Candidates: the forwarder goroutine
starved behind 256 workers on one P (each message crosses two goroutine hand-offs), or the
iterator's heartbeat monitor timer starved. Not chased further tonight; the owner merges the Go branch.

### Rust, unit 3 (`38e7b0a0`: liveness seeded at consumer start + broker-activity signal, restart by identifier, capacity warnings once per transition, no second spawn at start) — final rows
| broker | queues | messages | deliveries/s | router CPU | RSS | log |
|---|---:|---:|---:|---:|---:|---|
| Postgres | 8 | 50,000 | 6,236 | 40% | 9 MB | clean |
| NATS | 1 | 500,000 | 35,038 | 73% | 14 MB | clean |
| NATS | 8 | 500,000 | 31,894 | 79% | 60 MB | capacity pause/resume per loop per transition only |
| SQS (LocalStack) | 8 | 50,000 | 1,330 | 21% | 23 MB | clean |

All delivered, depth 0, no stalls, no restarts. The Rust router is usable on all three brokers.

## Where the three routers ended up (2026-09-08, all fixes in, NATS, HTTP/2, 1 CPU, 500,000 messages)
| router | 1 queue | 8 queues | RSS (1 queue) | user CPU/msg | branch |
|---|---:|---:|---:|---:|---|
| Rust | 35,038/s | 31,894/s | 14 MB | — | `router-bench-wiring` @ `38e7b0a0` |
| Java | 25,638/s | 14,117/s | 368 MB | 34 µs | `vertx-listener` @ `10af68c` |
| Go | 25,612/s (33,295/s at 2 CPU) | 16,165/s | 53–83 MB | 26 µs (fetch build) | `router-fixes-g10-g12` @ `c446a45` |

Every router was corrected by these rows: Java 7 defects, Go 6 (4 shared with Java), Rust 8 (three
pollers per queue, false stalls, restart key, receipt handle, plus the four ported ones). The
receiver-bound production shape (one router, hot standby, slow targets) needs none of the peak
numbers; what it needed was the correctness the rows forced.

### Cross-check: the backend-agnostic fixes on the other brokers, final images (2026-09-08 06:12)
All rows: delivered = seeded, depth 0, no errors; Java `cbeaf60`+`59c30ef` image, Go `c446a45` image.
| router | broker | deliveries/s | router CPU | RSS | log |
|---|---|---:|---:|---:|---|
| Java | Postgres, 8 queues | 2,361 | 52% | 307 MB | capacity pause/resume per loop only |
| Java | SQS (LocalStack), 8 queues | 1,200 | 40% | 399 MB | clean |
| Go | Postgres, 8 queues | 5,456 | 41% | 91 MB | clean |
| Go | SQS (LocalStack), 8 queues | 1,291 | 18% | 46 MB | 8 informational backlog lines |
With this every router has been proven on every broker it supports on its final build.
