# function-host benchmark

Implements `docs/spec/function-host-benchmark.md` (B1-B5). Results land in
`docs/function-runner-report.md` §"Performance"; raw CSVs and server logs are
under `results/`.

## Layout

- `fake-platform/FakePlatform.java` — standalone control plane (`java
  FakePlatform.java <port> <artifactsDir> <containerArtifactsMount>`), zero
  dependencies (JDK `HttpServer`). Serves `/oauth/token`,
  `/control/functions/desired-state` (ETag, reconfigurable via
  `POST /admin/config?fixture=lean|typical&n=N&warm=W[&maxConcurrency=M][&maxDurationMs=D]`),
  `/control/functions/heartbeat`, `/.well-known/jwks.json`. Admin routes:
  `/admin/status`, `/admin/heartbeats`.
- `fixtures/lean/LeanFn.java` — API-jar-only fixture: `/io` sleeps 5ms,
  `/cpu` does ~1ms of CPU (calibrated: 11,000 SHA-256 digests of a 256-byte
  buffer ≈ 1.0-1.1ms JIT-warmed on the dev host — see the class doc and
  `scripts/gen-artifacts.sh`). Compiled with plain `javac` against
  `function-api/target/classes` (no reactor build needed beyond that jar).
- `fixtures/typical/` — standalone Maven project (own `pom.xml`, **not**
  in the reactor). Shades classic `com.fasterxml.jackson:jackson-databind`
  + `com.networknt:json-schema-validator` against `function-api` as a
  `system`-scope (`provided`-equivalent) dependency. `init` builds an
  `ObjectMapper` and compiles a small JSON Schema; `handle` validates the
  request body against it.
- `scripts/gen-artifacts.sh <count>` — builds both fixtures, then stamps out
  `<count>` DISTINCT copies of each (a small marker file added to each
  copy's zip so every jar has its own sha256 digest — the host keys its
  class-loader cache by digest/`versionId`, so identical files would
  collapse to ONE loaded function, not N) into `artifacts/lean/*.jar` and
  `artifacts/typical/*.jar`.
- `Dockerfile.bench` — **bench-only** variant of `function-host/Dockerfile`.
  The production jlink runtime ships no `jcmd`/`jmap`/`jstat` (confirmed:
  `/opt/jre/bin` holds only `java`, `jfr`, `keytool`), so B1 cannot force a
  GC or read `jcmd VM.classloader_stats` against it. This Dockerfile is
  byte-identical except its jlink `--add-modules` line adds `jdk.jcmd` and
  `jdk.attach`. The production `function-host/Dockerfile` is untouched.
- `scripts/run-b1.sh` — the memory-per-loaded-function sweep.
- `scripts/run-b3.sh` — throughput/p99 at one (endpoint, concurrency, cpus)
  point.
- `scripts/LoadClient.java` — closed-loop load generator (fixed concurrency,
  wrk's own model) that ALSO posts a body and round-robins across N distinct
  addresses, which `wrk` cannot do without a Lua script: `java
  --enable-preview LoadClient.java <baseUrl> <addressPrefix> <addressCount>
  <concurrency> <seconds>`, e.g. `java --enable-preview LoadClient.java
  http://127.0.0.1:18080 bench.typical.f 100 256 60`. Forces `HTTP_1_1`
  explicitly — the JDK client's default h2c negotiation against this
  listener multiplexes many virtual threads onto ONE connection and hits its
  own max-concurrent-streams limit long before the server is under any real
  load (found running the "typical fixture under load" check,
  `docs/function-runner-report.md`'s "Metaspace at 50%" subsection).

## Re-running

```
# 1. one-time: build both fixtures and stamp N distinct copies of each
#    (JAVA_HOME must point at a JDK — never hardcode it, see CLAUDE.md).
#    500 (not 200) so a metaspace-fence sweep past ~226/~458 (2g/4g at the
#    function host's 50% default) has enough DISTINCT jars to request.
JAVA_HOME=$(mise where java) bash bench/function-host/scripts/gen-artifacts.sh 500

# 2. build the bench-tooling image (adds jcmd/jmap only — see Dockerfile.bench's header)
docker build -f bench/function-host/Dockerfile.bench -t fnhost-bench-tools .

# 3. B1 — memory sweep (edit POINTS/FIXTURES/MEMS/CPUS env vars to narrow it)
POINTS="0 25 50 100 200" FIXTURES="lean typical" MEMS="2g 4g" CPUS=2 \
  bash bench/function-host/scripts/run-b1.sh bench/function-host/results/b1.csv

# 4. B3 — one throughput point (endpoint, concurrency, host cpus)
bash bench/function-host/scripts/run-b3.sh io 256 2 bench/function-host/results
```

`scripts/run-b1.sh` and `run-b3.sh` each start `fake-platform/FakePlatform.java`
themselves (as a background `java` process on the host, port 8091) if it is
not already listening — kill it with `pkill -f FakePlatform.java` when done.

## Known departures from the plan (say-so, per the plan's own "no
extrapolation, say so when skipped" rule)

- The load generator (`wrk`) runs as a **host process**, not in a
  cpuset-pinned container: this machine is Docker Desktop for Mac, whose
  Linux VM shares the host's logical CPUs with any host process. There is
  no cgroup boundary between "wrk on the host" and "the container's `--cpus`
  quota" the way `bench/real/run.sh`'s Linux-native rig has — B3/B4 numbers
  are directional on this machine, not isolated-core numbers.
- The CPU-work calibration (11,000 SHA-256 digests ≈ 1ms) was measured on
  the bench host's own JVM outside any container, JIT-warmed. It is NOT
  re-calibrated per container run — the container may have a different
  effective clock (cgroup CPU quota throttling changes wall-clock-per-op,
  not the iteration count), so "~1ms of CPU" inside a `--cpus`-limited
  container is the iteration count fixed, not a guaranteed 1ms of *wall*
  time there.
