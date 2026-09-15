#!/usr/bin/env bash
# The plan §8 runtime comparison, real server vs Go vs Rust, one container each under a CPU
# quota (default) or pinned to one core (round-9 protocol, see below).
#
#   RUST_SRC=<path-to-flowcatalyst-rust> bench/real/run.sh prepare
#       # postgres + seed (Go fcdev, then Java fcdev, then Go fcdev again, then Rust fc-dev
#       # if RUST_SRC is set — see prepare()'s comment for the exact order), builds every image
#   bench/real/run.sh run <label> <image> <cpu-args> [env=value ...]
#
# `run`: starts the server container with the given docker cpu args (e.g. "--cpus=1"),
# logs in once through POST /auth/login, then drives GET /api/event-types (cookie session:
# a DB round trip for the session plus the list query, the browser path) with wrk for a
# WARMUP-second warm-up and a RUN_S-second measured run (both default 10 s) at 1,000
# connections; snapshots every OS thread's context-switch counters before and after the
# measured run; records memory from `docker stats`. Results land in bench/real/results/<label>.log.
#
# Pinned-core mode (test-size RESULTS.md round 9: a CFS quota is not a core — pin instead):
#   CPUSET=1 pins the server to that core (--cpuset-cpus=1), overriding whatever <cpu-args>
#     was passed on the command line (the positional arg is still required; CPUSET wins).
#   PG_CPUSET=10-13 moves Postgres off the server's core(s) with `docker update` before the
#     server starts. wrk always runs on --cpuset-cpus=2-9 regardless of either variable.
#   Both are unset by default, so the existing --cpus=N quota mode (already documented
#     elsewhere) is unchanged unless you opt in. Example, one command per leg:
#       CPUSET=1 PG_CPUSET=10-13 bench/real/run.sh run rust bench-real-rust --cpuset-cpus=1
set -u
here=$(cd "$(dirname "$0")" && pwd); root=$(cd "$here/../.." && pwd)
out=$here/results; mkdir -p "$out"
PG=bench-real-pg; NET=bench-real; PG_IP=172.30.0.2; SERVER_IP=172.30.0.10; ADMIN=bench@example.com; PASS='Bench-Pass-1234!'
JAVA_HOME=${JAVA_HOME:-$(mise where java)}

pg_ip() { echo $PG_IP; }

prepare() {
  docker rm -f $PG >/dev/null 2>&1
  docker network inspect $NET >/dev/null 2>&1 || docker network create --subnet 172.30.0.0/24 $NET >/dev/null
  docker run -d --name $PG --network $NET --ip $PG_IP -e POSTGRES_PASSWORD=pg -e POSTGRES_USER=pg -e POSTGRES_DB=fc -p 5440:5432 postgres:18-alpine >/dev/null
  for i in $(seq 1 60); do docker exec $PG pg_isready -U pg >/dev/null 2>&1 && break; sleep 0.5; done
  docker exec $PG psql -U pg -d fc -c "ALTER SYSTEM SET max_connections = 200;" >/dev/null && docker restart $PG >/dev/null
  for i in $(seq 1 60); do docker exec $PG pg_isready -U pg >/dev/null 2>&1 && break; sleep 0.5; done
  local url="postgresql://pg:pg@127.0.0.1:5440/fc"
  # The parity harness's seeding order (parity/.../Seed.java): Go `fcdev init` first — it
  # migrates with goose and, at Go HEAD, fails part-way on its own seeder defect — then
  # the Java side fills the catalogue, then Go `fcdev init` again completes. Here the Java
  # side is `fcdev init` itself (Flyway baseline of the Go schema + V2..V8, so the Java
  # server's mail_outbox exists), which is also the production cutover path.
  export FLOWCATALYST_APP_KEY=${FLOWCATALYST_APP_KEY:-$(head -c 32 /dev/urandom | base64)}
  local rootdir; rootdir=$(mktemp -d /tmp/bench-real-root.XXXX)
  local goinit=(/tmp/fcdev-go init --yes --database-url "$url" --admin-email "$ADMIN" --admin-password "$PASS" --code bench --name Bench --root "$rootdir")
  echo "-- Go fcdev init (first pass)"; "${goinit[@]}" >"$out/seed-go1.log" 2>&1 || echo "   (failed as expected at Go's seeder defect; continuing)"
  echo "-- Java fcdev init (Flyway baseline + V2..V8 + seeder)"
  "$JAVA_HOME/bin/java" -jar "$root"/fcdev/target/flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar init --yes --database-url "$url" \
      --admin-email "$ADMIN" --admin-password "$PASS" --code bench --name Bench --root "$rootdir" >"$out/seed-java.log" 2>&1 \
      || grep -q 'already exists' "$out/seed-java.log" || { tail -20 "$out/seed-java.log"; exit 1; }
  # Go init may already have completed on the first pass (Go HEAD b422466 does); the second
  # pass is then a no-op or an "already exists", either way the seed is complete.
  echo "-- Go fcdev init (second pass)"; "${goinit[@]}" >"$out/seed-go2.log" 2>&1 || grep -q -i 'already exist' "$out/seed-go2.log" || { tail -20 "$out/seed-go2.log"; exit 1; }
  # RUST_SRC (optional): a flowcatalyst-rust checkout/worktree, used as the build context
  # for bench-real-rust (Dockerfile.rust builds fc-server AND fc-dev). Mirrors the Go/Java
  # order above: run Rust's own `fc-dev init` against the SAME already-migrated-and-seeded
  # `fc` database as a fourth, idempotent pass — by the time L0-L6 land, Rust's schema and
  # seed rows are expected to line up with Go/Java's, so this should be a no-op that only
  # fills in whatever Rust-specific rows fc-dev's init adds (or errors "already exists",
  # tolerated the same way the Go/Java passes above do). `--no-oauth-client` matches the
  # parity harness's seed shape (Java's fcdev init doesn't mint one either — see fc-dev's
  # own flag doc in bin/fc-dev/src/init.rs). Runs over the Docker network, not the host
  # port, since there is no local Rust binary the way there is a local Go binary/Java jar.
  if [ -n "${RUST_SRC:-}" ]; then
    echo "-- building bench-real-rust (fc-server + fc-dev, for fc-dev init)"
    docker build -q -t bench-real-rust -f "$here/Dockerfile.rust" "$RUST_SRC" >/dev/null
    echo "-- Rust fc-dev init"
    docker run --rm --network $NET --entrypoint /app/fc-dev -e FLOWCATALYST_APP_KEY="$FLOWCATALYST_APP_KEY" \
        bench-real-rust init --yes --database-url "postgresql://pg:pg@$(pg_ip):5432/fc" \
        --admin-email "$ADMIN" --admin-password "$PASS" --code bench --name Bench --root /tmp \
        --no-oauth-client >"$out/seed-rust.log" 2>&1 \
        || grep -qi 'already exists\|duplicate key' "$out/seed-rust.log" || { tail -20 "$out/seed-rust.log"; exit 1; }
  fi
  images
}

images() {
  echo "-- building images"
  # bench-real-java is the product image itself (docs/spec/jvm-memory.md
  # §1a) — jlink runtime, the -Xmx/-direct fence, -XX:+UseCompactObjectHeaders,
  # the build stage's AOT training run — built straight from the root
  # Dockerfile so the bench measures exactly what ships, not a jar dropped
  # onto a stock JRE. This does its own `mvn package` inside the Docker
  # build (several minutes); no local exec-jar copy needed for it any more.
  cp /tmp/fc-server-linux "$here/fc-server-linux"
  docker build -q -t bench-real-java -f "$root/Dockerfile" "$root" >/dev/null
  docker build -q -t bench-real-go -f "$here/Dockerfile.go" "$here" >/dev/null
  if [ -f "$root/server/target/fc-server" ] && file "$root/server/target/fc-server" | grep -q ELF; then
    cp "$root/server/target/fc-server" "$here/fc-server-native"
    docker build -q -t bench-real-native -f "$here/Dockerfile.native" "$here" >/dev/null && echo "   + bench-real-native (GraalVM native image)"
  fi
  if [ -n "${RUST_SRC:-}" ]; then
    docker build -q -t bench-real-rust -f "$here/Dockerfile.rust" "$RUST_SRC" >/dev/null && echo "   + bench-real-rust (RUST_SRC=$RUST_SRC)"
  fi
  echo "prepared: postgres at $(pg_ip), images bench-real-java / bench-real-go${RUST_SRC:+ / bench-real-rust}"
}

run() {
  local label=$1 image=$2 cpuargs=$3; shift 3
  # CPUSET=<n or range>: pinned-core mode (round 9) — overrides whatever cpuargs was passed
  # positionally with --cpuset-cpus=$CPUSET. Unset (default): cpuargs is used as-is, i.e. the
  # existing --cpus=N quota mode, unchanged.
  [ -n "${CPUSET:-}" ] && cpuargs="--cpuset-cpus=$CPUSET"
  # PG_CPUSET=<n or range>: move Postgres off the server's core(s) before it starts, per
  # RESULTS.md round 9 ("pin the server, pin Postgres away"). No-op (default) leaves Postgres
  # on the quota-shared cores exactly as every already-documented run has it.
  [ -n "${PG_CPUSET:-}" ] && docker update --cpuset-cpus="$PG_CPUSET" "$PG" >/dev/null 2>&1
  local name="bench-real-$label"
  docker rm -f "$name" >/dev/null 2>&1
  # whatever still holds the rig's server address goes too (a failed earlier run)
  docker ps -aq --filter "network=$NET" | while read c; do [ "$(docker inspect -f '{{.Name}}' $c)" = "/$PG" ] || docker rm -f $c >/dev/null 2>&1; done
  local envs=()
  if [ "${RAW_ENV:-0}" = 1 ]; then
    # A non-FC server (the TypeScript platform on Node): every variable comes from the args.
    envs=()
  else
  envs=(-e FC_DATABASE_URL="postgresql://pg:pg@$(pg_ip):5432/fc" -e FC_API_PORT=8080 -e FC_METRICS_PORT=9090
              -e FC_PLATFORM_ENABLED=true -e FC_ROUTER_ENABLED=false -e FC_SCHEDULER_ENABLED=false
              -e FC_SCHEDULED_JOB_ENABLED=false -e FC_STREAM_PROCESSOR_ENABLED=false -e FC_OUTBOX_ENABLED=false
              -e FC_MCP_ENABLED=false -e FC_STANDBY_ENABLED=false -e FC_RATE_LIMIT_DISABLE=1
              -e FLOWCATALYST_APP_KEY="${FLOWCATALYST_APP_KEY:-}")
  fi
  for kv in "$@"; do envs+=(-e "$kv"); done
  # A fixed address on the rig's own network, so the issuer/base URL is known before start.
  local ip=$SERVER_IP t0; t0=$(python3 -c 'import time;print(time.time())')
  local issuer=(-e FC_JWT_ISSUER="http://$ip:8080" -e FC_EXTERNAL_BASE_URL="http://$ip:8080" -e FC_WEBAUTHN_ORIGINS="http://$ip:8080")
  [ "${RAW_ENV:-0}" = 1 ] && issuer=()
  # ${arr[@]+"${arr[@]}"}: an empty array is "unbound" under set -u on macOS's bash 3.2.
  # SERVER_ARGS: extra command-line arguments for the server binary (e.g. native-image -XX flags).
  docker run -d --name "$name" --network $NET --ip $ip $cpuargs ${envs[@]+"${envs[@]}"} ${issuer[@]+"${issuer[@]}"} "$image" ${SERVER_ARGS:-} >/dev/null
  local probe="docker run --rm --network $NET curlimages/curl:8.10.1 -s"
  for i in $(seq 1 300); do $probe -o /dev/null -w '%{http_code}' "http://$ip:8080/health" 2>/dev/null | grep -q 200 && break; sleep 0.2; done
  local startup; startup=$(python3 -c "import time;print(round(time.time()-$t0,2))")
  local cookie="" body="${LOGIN_BODY:-}"
  [ -n "$body" ] || body="{\"email\":\"$ADMIN\",\"password\":\"$PASS\",\"rememberMe\":false}"
  for attempt in 1 2 3 4 5; do
    cookie=$($probe -m 15 -D - -o /dev/null -H 'Content-Type: application/json' \
        -d "$body" "http://$ip:8080${LOGIN_PATH:-/auth/login}" \
        | tr -d '\r' | awk -F': ' 'tolower($1)=="set-cookie" && $2 ~ /^fc_session=/ {split($2,a,";"); print a[1]}' | head -1)
    [ -n "$cookie" ] && break
    sleep 1
  done
  [ -n "$cookie" ] || { echo "login failed on $label"; docker logs "$name" 2>&1 | tail -20; docker rm -f "$name" >/dev/null 2>&1; return 1; }
  local endpoint=${ENDPOINT:-/api/event-types}
  local first; first=$($probe -o /dev/null -w '%{http_code}' -H "Cookie: $cookie" "http://$ip:8080$endpoint")
  mem() { docker stats --no-stream --format '{{.MemUsage}}' "$name" | awk '{print $1}'; }
  snap() { docker exec "$name" sh -c 'for t in /proc/1/task/*; do printf "%s\t%s\t%s\t%s\n" "$(basename $t)" "$(cat $t/comm)" "$(awk "/^voluntary/{print \$2}" $t/status)" "$(awk "/^nonvoluntary/{print \$2}" $t/status)"; done'; }
  # wrk sends -H verbatim; the servers want "Cookie: value" with the space, hence the array.
  local WRK=(docker run --rm --network $NET --cpuset-cpus=2-9 --ulimit nofile=65536:65536 bench-wrk wrk -H "Cookie: $cookie")
  # WARMUP seconds (default 10). A JVM on one CPU is still JIT-compiling after 10 s; use 60.
  local warm; warm=$("${WRK[@]}" -t8 -c${CONNS:-1000} -d${WARMUP:-10}s "http://$ip:8080$endpoint" | grep -E 'Requests/sec' | awk '{print $2}')
  sleep 1
  local before; before=$(snap)
  local res; res=$("${WRK[@]}" -t8 -c${CONNS:-1000} -d${RUN_S:-10}s --latency "http://$ip:8080$endpoint")
  local after; after=$(snap)
  local requests; requests=$(echo "$res" | grep -E '^ +[0-9]+ requests in' | awk '{print $1}')
  {
    echo "== $label image=$image cpu='$cpuargs' env='$*' endpoint=$endpoint startup=${startup}s first=$first warmup(${WARMUP:-10}s)=${warm} req/s mem_after=$(mem)"
    echo "$res" | grep -E 'requests in|Requests/sec|50%|90%|99%|Socket errors|Non-2xx|Latency +[0-9]'
    echo "-- per OS thread over the 10 s measured run: tid comm voluntary nonvoluntary total"
    python3 - "$requests" "$before" "$after" <<'PY'
import sys
req=int(sys.argv[1] or 1); b={}; a={}
for line in sys.argv[2].splitlines():
    p=line.split('\t');  b[p[0]]=(p[1],int(p[2] or 0),int(p[3] or 0)) if len(p)==4 else None
for line in sys.argv[3].splitlines():
    p=line.split('\t');  a[p[0]]=(p[1],int(p[2] or 0),int(p[3] or 0)) if len(p)==4 else None
rows=[]; tv=tn=0
for t,v in a.items():
    if not v: continue
    c,vol,non=v; bv,bn=(b[t][1],b[t][2]) if b.get(t) else (0,0)
    dv,dn=vol-bv,non-bn; tv+=dv; tn+=dn; rows.append((dv+dn,t,c,dv,dn))
for tot,t,c,dv,dn in sorted(rows,reverse=True)[:12]:
    if tot: print(f"   {t:>6}  {c:<28} {dv:>9} {dn:>9} {tot:>9}")
print(f"-- threads={len(a)} requests={req} voluntary={tv} nonvoluntary={tn} total={tv+tn}  per request: {(tv+tn)/req:.2f}")
PY
  } | tee "$out/$label.log"
  docker logs "$name" > "$out/$label.server.log" 2>&1
  # KEEP=1 leaves the server container running (to copy a profile out of it).
  [ "${KEEP:-0}" = 1 ] || docker rm -f "$name" >/dev/null 2>&1
}

case ${1:-} in
  prepare) prepare ;;
  images) images ;;
  run) shift; run "$@" ;;
  *) echo "usage: $0 prepare | images | run <label> <image> <cpu-args> [ENV=value ...]"
     echo "       env: RUST_SRC=<path> (prepare/images), CPUSET=<n>, PG_CPUSET=<n-m>, WARMUP=<s>, RUN_S=<s> (run)"
     exit 2 ;;
esac
