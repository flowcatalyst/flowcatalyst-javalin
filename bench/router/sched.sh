#!/usr/bin/env bash
# Dispatch job scheduler publish-throughput bench. Separate from run.sh (the router bench) but on
# the same Docker network, IPs and image conventions (bench-real network, server at 172.30.0.10,
# Postgres at 172.30.0.2, the sqsfix in-memory SQS fixture at 172.30.0.12).
#
#   bench/router/sched.sh run <label> <image> <shape>
#   bench/router/sched.sh clean          # remove every bench-sched-* container
#
# shape:  A  ungrouped (message_group NULL, mode IMMEDIATE)
#         B  1,000 groups x N/1000 jobs  (mode NEXT_ON_ERROR)
#         C  10 groups x N/10 jobs       (mode NEXT_ON_ERROR)
#         or NGROUPS=<g> with any shape name for a custom group count (0 = ungrouped)
#
# Knobs (environment of this script): N [100000]  TIMEOUT_S [300]  CPUS [2]  SAMPLE_S [1]
#   SAME_CREATED_AT [0] see seed()   PG_STAT [0] 1 = load pg_stat_statements and print the top
#   statements of the measured window (a diagnostic run: the extension itself costs a little)
#   PG_EXPLAIN [0] 1 = auto_explain every statement into results/sched-<label>.pg.log (implies PG_STAT)
#   PG_STAT_TOP [6] rows of pg_stat_statements printed
#   WARMUP [100] jobs published before the measured seed, used as the readiness check
#   KEEP [0] leave the containers up afterwards.  Extra ENV=v args after <shape> go to the server.
#
# What one run does:
#   1. fresh Postgres container (database `sched`) and fresh sqsfix;
#   2. the server image with ONLY the dispatch scheduler on (FC_SCHEDULER_ENABLED=true, platform
#      API / router / stream / outbox / scheduled jobs / MCP off, standby off so the one instance
#      is always leader), publishing to SQS at sqsfix. The server applies its own migrations;
#   3. readiness: WARMUP ungrouped jobs are inserted and must all reach sqsfix, which proves the
#      poller is running and publishing (same check for all three implementations, no health URL);
#   4. the measured seed: ONE `INSERT ... SELECT generate_series` of N PENDING jobs, due now. The
#      server is running but sees none of them until that statement commits, so seeding time is
#      outside the measured window (t0 = the moment the INSERT returns);
#   5. a 1-second series (sched.py) of sqsfix sent / SendMessageBatch calls and job status counts,
#      until no job is PENDING or TIMEOUT_S;
#   6. the summary (results/sched-<label>.log), the series (.ts.jsonl) and the server log.
#
# Jobs carry no client, subscription or pool id: a client-less job publishes under the platform
# tenant at DEFAULT priority, so every job goes to the one queue FC-bench-platform-DEFAULT.fifo
# and no other table needs a row (the job table has no foreign keys). Nothing here is real SQS:
# sqsfix answers in microseconds, so the numbers are the scheduler and Postgres, not AWS.
#
# Images (all three take the same env):
#   Go    GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o fc-server-linux ./cmd/fc-server, then
#         FROM alpine:3.22 / COPY fc-server-linux /app/fc-server / ENTRYPOINT ["/app/fc-server"]
#   Rust  docker build --target builder -t x-builder <rust repo>; copy /app/target/release/fc-server
#         out of it onto debian:bookworm-slim, ENTRYPOINT ["/app/fc-server"]
#   Java  docker build -t <tag> <this repo>
set -u
here=$(cd "$(dirname "$0")" && pwd)
out=$here/results; mkdir -p "$out"

NET=bench-real; PG=bench-sched-pg; PG_IP=172.30.0.2; SERVER_IP=172.30.0.10; SQS_IP=172.30.0.12
SQSFIX=bench-sched-sqsfix; SRV=bench-sched-srv; DB=sched
N=${N:-100000}; TIMEOUT_S=${TIMEOUT_S:-300}; CPUS=${CPUS:-2}; SAMPLE_S=${SAMPLE_S:-1}; WARMUP=${WARMUP:-100}
# Any 32 bytes, base64: the scheduler derives its dispatch-token signing secret from it.
APP_KEY=${APP_KEY:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}

clean() { docker rm -f $SRV $SQSFIX $PG >/dev/null 2>&1; }
psqlq() { docker exec $PG psql -U pg -d $DB -v ON_ERROR_STOP=1 -tAc "$1"; }
sqsstat() { docker exec $SQSFIX wget -qO- http://127.0.0.1:4566/stats 2>/dev/null \
    | python3 -c 'import sys,json; j=json.load(sys.stdin); print(j.get("sent",0), j.get("calls",{}).get("SendMessageBatch",0), j.get("calls",{}).get("SendMessage",0))'; }
now() { python3 -c 'import time;print(time.time())'; }

# seed <first> <count> <groups> <mode>: one statement. ids are 'B' + 12 digits (the column is
# varchar(13)). created_at is clock_timestamp(), so every row has its own, increasing with the
# id, as jobs created one after another do. SAME_CREATED_AT=1 gives every row the statement's
# now() instead: the claim query orders by (message_group, sequence, created_at, id) and the
# poll index stops at created_at, so Postgres then sorts the whole tied run of rows on every
# claim. That is a different (pathological) measurement; it is not the default.
seed() {
  local first=$1 count=$2 groups=$3 mode=$4 grp="NULL" created="clock_timestamp()"
  [ "$groups" -gt 0 ] && grp="'g' || lpad((gs % $groups)::text, 5, '0')"
  [ "${SAME_CREATED_AT:-0}" = 1 ] && created="now()"
  psqlq "INSERT INTO msg_dispatch_jobs (id, kind, code, target_url, mode, message_group, status, payload, created_at)
         SELECT 'B' || lpad(gs::text, 12, '0'), 'EVENT', 'bench:sched:job:created',
                'http://172.30.0.11:9000/hook', '$mode', $grp, 'PENDING', '{\"n\":' || gs || '}', $created
           FROM generate_series($first, $first + $count - 1) AS gs;" >/dev/null
}

run() {
  local label=$1 image=$2 shape=$3; shift 3
  local groups mode
  case "$shape" in
    A) groups=0; mode=IMMEDIATE ;;
    B) groups=1000; mode=NEXT_ON_ERROR ;;
    C) groups=10; mode=NEXT_ON_ERROR ;;
    *) groups=${NGROUPS:?shape must be A, B or C, or set NGROUPS}; mode=NEXT_ON_ERROR ;;
  esac
  [ -n "${NGROUPS:-}" ] && groups=$NGROUPS
  [ "$groups" = 0 ] && mode=IMMEDIATE
  local extra=(); for kv in "$@"; do extra+=(-e "$kv"); done

  local leftovers; leftovers=$(docker ps -a --format '{{.Names}}' | grep -E '^bench-(sched|router)-' | tr '\n' ' ')
  [ -n "$leftovers" ] && echo "-- leftover bench containers: $leftovers(bench-sched-* are removed now)"
  clean
  local sampler_pid="" stats_pid=""
  fail() {
    echo "FAILED at: $1" >&2
    docker logs $SRV 2>&1 | tail -30 >&2
    [ -n "$sampler_pid" ] && kill "$sampler_pid" >/dev/null 2>&1
    [ -n "$stats_pid" ] && kill "$stats_pid" >/dev/null 2>&1
    docker logs $SRV > "$out/sched-$label.server.log" 2>&1
    clean; exit 1
  }

  docker network inspect $NET >/dev/null 2>&1 || docker network create --subnet 172.30.0.0/24 $NET >/dev/null
  docker image inspect bench-router-sqsfix >/dev/null 2>&1 \
    || docker build -q -t bench-router-sqsfix -f "$here/sqsfix/Dockerfile" "$here" >/dev/null

  local pgargs=()
  [ "${PG_STAT:-0}" = 1 ] && pgargs=(-c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=all)
  # PG_EXPLAIN=1: auto_explain logs the plan Postgres actually ran for EVERY statement (with
  # ANALYZE timings and buffers) to the Postgres log, saved as results/sched-<label>.pg.log.
  # A plan-capture run, not a rate measurement: the instrumentation is expensive.
  [ "${PG_EXPLAIN:-0}" = 1 ] && pgargs=(-c shared_preload_libraries=pg_stat_statements,auto_explain -c pg_stat_statements.track=all
      -c auto_explain.log_min_duration=0 -c auto_explain.log_analyze=on -c auto_explain.log_buffers=on
      -c auto_explain.log_timing=on -c auto_explain.log_nested_statements=on)
  # (1) fresh Postgres + sqsfix. pg_isready over TCP: the image's init-time server listens on the
  # unix socket only, so this cannot pass before the real server is up.
  docker run -d --name $PG --network $NET --ip $PG_IP -e POSTGRES_PASSWORD=pg -e POSTGRES_USER=pg \
      -e POSTGRES_DB=$DB postgres:18-alpine -c max_connections=300 ${pgargs[@]+"${pgargs[@]}"} >/dev/null || fail "start postgres"
  docker run -d --name $SQSFIX --network $NET --ip $SQS_IP --cpuset-cpus=2-9 bench-router-sqsfix >/dev/null || fail "start sqsfix"
  local i
  for i in $(seq 1 120); do docker exec $PG pg_isready -h $PG_IP -U pg -d $DB >/dev/null 2>&1 && break; sleep 0.5; done
  docker exec $PG pg_isready -h $PG_IP -U pg -d $DB >/dev/null 2>&1 || fail "postgres ready"

  # (2) the server: scheduler only, SQS at sqsfix. FC_DISPATCH_QUEUE_URL is read only for its
  # account id and region; the queues actually used are {prefix}-{tenant}-{priority}.fifo.
  docker run -d --name $SRV --network $NET --ip $SERVER_IP --cpus="$CPUS" \
      -e FC_PLATFORM_ENABLED=false -e FC_ROUTER_ENABLED=false -e FC_SCHEDULER_ENABLED=true \
      -e FC_SCHEDULED_JOB_ENABLED=false -e FC_STREAM_PROCESSOR_ENABLED=false -e FC_OUTBOX_ENABLED=false \
      -e FC_MCP_ENABLED=false -e FC_STANDBY_ENABLED=false \
      -e FC_DATABASE_URL="postgresql://pg:pg@$PG_IP:5432/$DB" -e FC_API_PORT=8080 -e FC_METRICS_PORT=9090 \
      -e FLOWCATALYST_APP_KEY="$APP_KEY" \
      -e FC_DISPATCH_QUEUE_TYPE=SQS -e FC_DISPATCH_QUEUE_PREFIX=FC-bench -e FC_DISPATCH_QUEUE_REGION=us-east-1 \
      -e FC_DISPATCH_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/000000000000/anchor \
      -e AWS_ENDPOINT_URL_SQS="http://$SQS_IP:4566" -e AWS_ENDPOINT_URL="http://$SQS_IP:4566" \
      -e AWS_ACCESS_KEY_ID=test -e AWS_SECRET_ACCESS_KEY=test -e AWS_REGION=us-east-1 \
      ${extra[@]+"${extra[@]}"} "$image" >/dev/null || fail "start server"

  # (3) migrations applied (the job table exists), then the warm-up must reach sqsfix.
  for i in $(seq 1 240); do
    psqlq "SELECT 1 FROM pg_class WHERE relname = 'msg_dispatch_jobs'" 2>/dev/null | grep -q 1 && break
    [ "$(docker inspect -f '{{.State.Running}}' $SRV 2>/dev/null)" = true ] || fail "server exited during start-up"
    sleep 0.5
  done
  psqlq "SELECT 1 FROM pg_class WHERE relname = 'msg_dispatch_jobs'" 2>/dev/null | grep -q 1 || fail "migrations (no msg_dispatch_jobs after 120s)"
  psqlq "INSERT INTO msg_dispatch_jobs (id, kind, code, target_url, mode, status, payload)
         SELECT 'W' || lpad(gs::text, 12, '0'), 'EVENT', 'bench:sched:warmup', 'http://172.30.0.11:9000/hook',
                'IMMEDIATE', 'PENDING', '{}' FROM generate_series(1, $WARMUP) AS gs;" >/dev/null || fail "warm-up insert"
  local sent=0 calls=0 single=0
  for i in $(seq 1 240); do
    read -r sent calls single <<<"$(sqsstat)"
    [ "${sent:-0}" -ge "$WARMUP" ] && break
    [ "$(docker inspect -f '{{.State.Running}}' $SRV 2>/dev/null)" = true ] || fail "server exited before publishing the warm-up"
    sleep 0.5
  done
  [ "${sent:-0}" -ge "$WARMUP" ] || fail "warm-up: only ${sent:-0}/$WARMUP reached sqsfix in 120s"
  sleep 2   # let the poller go idle again
  read -r sent calls single <<<"$(sqsstat)"
  local base_sent=$sent base_calls=$calls
  [ "$base_sent" -eq "$WARMUP" ] || echo "WARNING: warm-up put $base_sent messages on the queue for $WARMUP jobs" >&2

  [ "${PG_EXPLAIN:-0}" = 1 ] && PG_STAT=1
  [ "${PG_STAT:-0}" = 1 ] && { psqlq "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null; psqlq "SELECT pg_stat_statements_reset()" >/dev/null; }

  # (4) the measured seed.
  local t_seed0; t_seed0=$(now)
  seed 1 "$N" "$groups" "$mode" || fail "seed"
  local seed_s; seed_s=$(python3 -c "import time;print(round(time.time()-$t_seed0,2))")
  echo "-- seeded $N PENDING jobs (shape $shape, groups=$groups, mode=$mode) in ${seed_s}s, one INSERT, server running; sampling"

  # (5) series + docker stats (server and Postgres CPU, one line each per second).
  local cpufile="$out/.sched-$label.cpu.tmp"; : > "$cpufile"
  ( while :; do docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' $SRV $PG 2>/dev/null >> "$cpufile"; sleep 1; done ) &
  stats_pid=$!
  local ts="$out/sched-$label.ts.jsonl"
  python3 "$here/sched.py" sample "$ts" $PG $DB $SQSFIX "$N" "$TIMEOUT_S" "$SAMPLE_S" &
  sampler_pid=$!
  wait $sampler_pid; sampler_pid=""
  kill $stats_pid >/dev/null 2>&1; wait $stats_pid 2>/dev/null; stats_pid=""

  # Settle (a late duplicate publish would show up here), then freeze the server and read the
  # queue counters and the job table as one pair. After a timeout the server is still
  # publishing, so the pair can differ by the one claim that was published but not yet committed
  # when it was frozen; after a full drain nothing is in flight and the two must be equal.
  sleep 3
  docker pause $SRV >/dev/null 2>&1
  sleep 0.5
  read -r sent calls single <<<"$(sqsstat)"
  local pending_final; pending_final=$(psqlq "SELECT count(*) FROM msg_dispatch_jobs WHERE id LIKE 'B%' AND status = 'PENDING'")
  local pgstat=""
  if [ "${PG_STAT:-0}" = 1 ]; then
    pgstat=$(docker exec $PG psql -U pg -d $DB -c "SELECT calls, round(total_exec_time::numeric) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 150) AS query FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT ${PG_STAT_TOP:-6}")
  fi
  docker unpause $SRV >/dev/null 2>&1
  local cpu; cpu=$(python3 - "$cpufile" $SRV $PG <<'PY'
import sys
srv, pg = sys.argv[2], sys.argv[3]
c = {srv: [], pg: []}; mem = 0.0
unit = {'kib': 1 / 1024, 'mib': 1, 'gib': 1024}
import re
for line in open(sys.argv[1]):
    p = line.split()
    if len(p) < 3 or p[0] not in c: continue
    try: c[p[0]].append(float(p[1].rstrip('%')))
    except ValueError: continue
    if p[0] == srv:
        m = re.match(r'([\d.]+)([A-Za-z]+)', p[2])
        if m: mem = max(mem, float(m.group(1)) * unit.get(m.group(2).lower(), 1))
avg = lambda x: sum(x) / len(x) if x else 0.0
print(f"server_mean_cpu_pct={avg(c[srv]):.0f} server_max_cpu_pct={max(c[srv] or [0]):.0f} "
      f"pg_mean_cpu_pct={avg(c[pg]):.0f} pg_max_cpu_pct={max(c[pg] or [0]):.0f} server_max_rss_mb={mem:.0f}")
PY
)
  rm -f "$cpufile"
  docker logs $SRV > "$out/sched-$label.server.log" 2>&1
  [ "${PG_EXPLAIN:-0}" = 1 ] && docker logs $PG > "$out/sched-$label.pg.log" 2>&1
  local warns errs
  warns=$(grep -ciE '"level":"warn' "$out/sched-$label.server.log"); errs=$(grep -ciE '"level":"error' "$out/sched-$label.server.log")
  {
    echo "== $label image=$image shape=$shape groups=$groups mode=$mode cpus=$CPUS same_created_at=${SAME_CREATED_AT:-0} env='$*'"
    python3 "$here/sched.py" report "$ts" "$N" "$base_sent" "$base_calls" "$sent" "$calls" "$pending_final"
    echo "   $cpu (docker stats, 100 = one core; server limited to $CPUS)"
    echo "   server log: warn_lines=$warns error_lines=$errs single_SendMessage_calls=$single seed_insert_s=$seed_s"
    [ -n "$pgstat" ] && { echo "   pg_stat_statements, top statements by total time (reset just before the seed):"; echo "$pgstat"; }
  } | tee "$out/sched-$label.log"

  [ "${KEEP:-0}" = 1 ] || clean
}

case ${1:-} in
  run) shift; [ $# -ge 3 ] || { echo "usage: $0 run <label> <image> <A|B|C> [ENV=v ...]"; exit 2; }; run "$@" ;;
  clean) clean ;;
  *) echo "usage: $0 run <label> <image> <A|B|C> [ENV=v ...] | clean"; exit 2 ;;
esac
