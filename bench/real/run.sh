#!/usr/bin/env bash
# The plan §8 runtime comparison, real server vs Go, one container each under a CPU quota.
#
#   bench/real/run.sh prepare            # postgres + seed (Java fcdev init), builds both images
#   bench/real/run.sh run <label> <image> <cpu-args> [FC_HTTP=vertx ...]
#
# `run`: starts the server container with the given docker cpu args (e.g. "--cpus=1"),
# logs in once through POST /auth/login, then drives GET /api/event-types (cookie session:
# a DB round trip for the session plus the list query, the browser path) with wrk for a
# 10 s warm-up and a 10 s measured run at 1,000 connections; snapshots every OS thread's
# context-switch counters before and after the measured run; records memory from
# `docker stats`. Results land in bench/real/results/<label>.log.
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
      --admin-email "$ADMIN" --admin-password "$PASS" --code bench --name Bench --root "$rootdir" >"$out/seed-java.log" 2>&1 || { tail -20 "$out/seed-java.log"; exit 1; }
  echo "-- Go fcdev init (second pass)"; "${goinit[@]}" >"$out/seed-go2.log" 2>&1 || { tail -20 "$out/seed-go2.log"; exit 1; }
  echo "-- building images"
  cp "$root"/server/target/flowcatalyst-server-*-exec.jar "$here/flowcatalyst-server-exec.jar"
  cp /tmp/fc-server-linux "$here/fc-server-linux"
  docker build -q -t bench-real-java -f "$here/Dockerfile.java" "$here" >/dev/null
  docker build -q -t bench-real-go -f "$here/Dockerfile.go" "$here" >/dev/null
  echo "prepared: postgres at $(pg_ip), images bench-real-java / bench-real-go"
}

run() {
  local label=$1 image=$2 cpuargs=$3; shift 3
  local name="bench-real-$label"
  docker rm -f "$name" >/dev/null 2>&1
  local envs=(-e FC_DATABASE_URL="postgresql://pg:pg@$(pg_ip):5432/fc" -e FC_API_PORT=8080 -e FC_METRICS_PORT=9090
              -e FC_PLATFORM_ENABLED=true -e FC_ROUTER_ENABLED=false -e FC_SCHEDULER_ENABLED=false
              -e FC_SCHEDULED_JOB_ENABLED=false -e FC_STREAM_PROCESSOR_ENABLED=false -e FC_OUTBOX_ENABLED=false
              -e FC_MCP_ENABLED=false -e FC_STANDBY_ENABLED=false -e FC_RATE_LIMIT_DISABLE=1)
  for kv in "$@"; do envs+=(-e "$kv"); done
  # A fixed address on the rig's own network, so the issuer/base URL is known before start.
  local ip=$SERVER_IP t0; t0=$(python3 -c 'import time;print(time.time())')
  docker run -d --name "$name" --network $NET --ip $ip $cpuargs "${envs[@]}" \
      -e FC_JWT_ISSUER="http://$ip:8080" -e FC_EXTERNAL_BASE_URL="http://$ip:8080" -e FC_WEBAUTHN_ORIGINS="http://$ip:8080" "$image" >/dev/null
  local probe="docker run --rm --network $NET curlimages/curl:8.10.1 -s"
  for i in $(seq 1 300); do $probe -o /dev/null -w '%{http_code}' "http://$ip:8080/health" 2>/dev/null | grep -q 200 && break; sleep 0.2; done
  local startup; startup=$(python3 -c "import time;print(round(time.time()-$t0,2))")
  local cookie; cookie=$($probe -D - -o /dev/null -H 'Content-Type: application/json' \
      -d "{\"email\":\"$ADMIN\",\"password\":\"$PASS\",\"rememberMe\":false}" "http://$ip:8080/auth/login" \
      | tr -d '\r' | awk -F': ' 'tolower($1)=="set-cookie" && $2 ~ /^fc_session=/ {split($2,a,";"); print a[1]}' | head -1)
  [ -n "$cookie" ] || { echo "login failed on $label"; docker logs "$name" 2>&1 | tail -20; return 1; }
  local first; first=$($probe -o /dev/null -w '%{http_code}' -H "Cookie: $cookie" "http://$ip:8080/api/event-types")
  mem() { docker stats --no-stream --format '{{.MemUsage}}' "$name" | awk '{print $1}'; }
  snap() { docker exec "$name" sh -c 'for t in /proc/1/task/*; do printf "%s\t%s\t%s\t%s\n" "$(basename $t)" "$(cat $t/comm)" "$(awk "/^voluntary/{print \$2}" $t/status)" "$(awk "/^nonvoluntary/{print \$2}" $t/status)"; done'; }
  local WRK="docker run --rm --network $NET --cpuset-cpus=2-9 --ulimit nofile=65536:65536 bench-wrk wrk -H Cookie:$cookie"
  local warm; warm=$($WRK -t8 -c1000 -d10s "http://$ip:8080/api/event-types" | grep -E 'Requests/sec' | awk '{print $2}')
  sleep 1
  local before; before=$(snap)
  local res; res=$($WRK -t8 -c1000 -d10s --latency "http://$ip:8080/api/event-types")
  local after; after=$(snap)
  local requests; requests=$(echo "$res" | grep -E '^ +[0-9]+ requests in' | awk '{print $1}')
  {
    echo "== $label image=$image cpu='$cpuargs' env='$*' startup=${startup}s first=$first warmup=${warm} req/s mem_after=$(mem)"
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
  docker rm -f "$name" >/dev/null 2>&1
}

case ${1:-} in
  prepare) prepare ;;
  run) shift; run "$@" ;;
  *) echo "usage: $0 prepare | run <label> <image> <cpu-args> [ENV=value ...]"; exit 2 ;;
esac
