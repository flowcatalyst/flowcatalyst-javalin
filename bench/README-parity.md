# L7 bench — four-way run under the round-9 pinned-core protocol

`docs/java-parity-plan.md` §3 L7 / §7. Two rigs, reused as-is from the Go-vs-Java work with a
`rust` leg added: `bench/real` (platform API, `GET /api/event-types` behind a session cookie)
and `bench/router` (router-only drain bench). Pinned-core protocol per
`../test-size/bench/run-onecore.sh` and `../test-size/RESULTS.md` round 9: a `--cpus=N` CFS
quota is unfair to a single-threaded server sharing cores with 30+ unpinned Postgres backends
— pin the server to a core with `--cpuset-cpus`, and pin Postgres off that core with
`docker update`, before measuring.

**These are the commands to run once the code is final and the machine is idle.** Numbers
taken while other lanes are compiling are not results — see the smoke-run note at the bottom.

## One-time setup

```
# Postgres + all four images (java, go, native if present, rust if RUST_SRC is set), then
# Go fcdev init / Java fcdev init / Go fcdev init / Rust fc-dev init, in that order, against
# the one shared `fc` database (bench/real/run.sh prepare()'s header comment has the full
# rationale for the order).
RUST_SRC=/path/to/flowcatalyst-rust bench/real/run.sh prepare
```

`RUST_SRC` must point at a flowcatalyst-rust checkout/worktree (build context for
`bench/real/Dockerfile.rust`, which builds `fc-server` and `fc-dev` from source — no
prebuilt binary needed, unlike the Go/native legs).

## Platform leg — four-way, pinned

One core for the server (`CPUSET`), Postgres pinned off it (`PG_CPUSET`), wrk always on
`--cpuset-cpus=2-9`. `<n>` below is whatever core the machine's isolated for this (e.g. `1`);
`<pg-range>` is the rest minus wrk's `2-9` (e.g. `10-13`, matching round 9).

```
CPUSET=<n> PG_CPUSET=<pg-range> WARMUP=30 RUN_S=60 bench/real/run.sh run java  bench-real-java  --cpuset-cpus=<n>
CPUSET=<n> PG_CPUSET=<pg-range> WARMUP=30 RUN_S=60 bench/real/run.sh run go    bench-real-go    --cpuset-cpus=<n>
CPUSET=<n> PG_CPUSET=<pg-range> WARMUP=30 RUN_S=60 bench/real/run.sh run rust bench-real-rust   --cpuset-cpus=<n>
# native, if bench/real/run.sh prepare built it (GraalVM native image present at build time):
CPUSET=<n> PG_CPUSET=<pg-range> WARMUP=30 RUN_S=60 bench/real/run.sh run native bench-real-native --cpuset-cpus=<n>
```

`WARMUP`/`RUN_S` default to 10/10 (the existing quota-mode default, unchanged) — round 9 used
30 s warm-up / 60 s measured for the JVM to finish JIT-compiling on one core; use the same
here so the four legs are comparable. Results: `bench/real/results/<label>.log` — req/s, p99,
max, mem, and the per-thread context-switch snapshot (already part of this rig).

The positional `--cpuset-cpus=<n>` arg above is redundant with `CPUSET=<n>` (CPUSET
overrides it) — it's kept so the command still means something if you drop `CPUSET=` to fall
back to quota mode, e.g. swap it for `--cpus=1` and drop `PG_CPUSET`.

## Router leg — four-way, pinned

```
BROKER=postgres CPUSET=<n> PG_CPUSET=<pg-range> bench/router/run.sh run java  bench-real-java  --cpuset-cpus=<n>
BROKER=postgres CPUSET=<n> PG_CPUSET=<pg-range> bench/router/run.sh run go    bench-real-go    --cpuset-cpus=<n>
BROKER=postgres CPUSET=<n> PG_CPUSET=<pg-range> bench/router/run.sh run rust  bench-real-rust  --cpuset-cpus=<n>
```

`bench/router/Dockerfile.rust` builds the Rust image for this leg (`fc-router-bin`, a
different binary from the platform leg's `fc-server` — both images happen to share the tag
`bench-real-rust` by convention; rebuild before switching legs):

```
docker build -f bench/router/Dockerfile.rust -t bench-real-rust /path/to/flowcatalyst-rust
```

This rig measures a full-queue **drain**, not a sustained rate, so there's no in-process
warm-up window the way `bench/real`'s wrk warm-up is (the router container is started fresh
per `run` call — see the script's header comment). Get a warm-up pass by running once with a
small `TOTAL_MESSAGES` first and discarding its `.log`, then again with the real size:

```
TOTAL_MESSAGES=5000  CPUSET=<n> PG_CPUSET=<pg-range> bench/router/run.sh run rust-warm bench-real-rust --cpuset-cpus=<n>   # discard
TOTAL_MESSAGES=50000 CPUSET=<n> PG_CPUSET=<pg-range> bench/router/run.sh run rust      bench-real-rust --cpuset-cpus=<n>   # keep
```

Results: `bench/router/results/<label>.log` — deliveries/s, drain time, RSS, context switches
(already part of this rig).

## Incremental-build loop

```
RUST_SRC=/path/to/flowcatalyst-rust bench/real/incremental-build.sh
```

Touches `crates/fc-platform/src/lib.rs` and times `cargo build --release` on the Rust side;
touches one file under `server/src/main/java` and times `mvn -q -o -DskipTests package` (run
from `server/`) on the Java side. Run it twice and read the **second** number — the first run
after a clean clone/`cargo clean`/`mvn clean` times a full build, not an incremental one.

## Smoke run — PENDING (2026-09-15)

Not yet run. The `bench-real-rust` image build (`docker build -f bench/real/Dockerfile.rust`
against `flowcatalyst-rust` @ `16b985b9`, `parity/integration`) was started and then
deliberately killed partway through dependency compilation: the machine's load average was
541 on 14 cores (several other lanes compiling in parallel), and this lane is rig prep only —
the smoke run and the real pinned-protocol numbers are deferred to a later pass on an idle
machine, once the Rust code this bench measures is final. Nothing else here has been
exercised end-to-end yet either (`fc-dev init` seeding, login, `wrk`).

Before that pass: `RUST_SRC=/path/to/flowcatalyst-rust bench/real/run.sh prepare` (builds the
image and seeds via the new Rust `fc-dev init` step), then `bench/real/run.sh run rust
bench-real-rust --cpus=1` (quota mode, defaults `WARMUP=10 RUN_S=10`, is enough to prove boot
+ seed + login + wrk end-to-end before switching to the pinned commands above).
