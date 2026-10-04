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
#         D  20,000 groups x N/20000 jobs (mode NEXT_ON_ERROR)
#         or NGROUPS=<g> with any shape name for a custom group count (0 = ungrouped)
#
# Knobs (environment of this script): N [100000]  TIMEOUT_S [300]  CPUS [2]  SAMPLE_S [0.25]
#   WARMUP_N [0] jobs of the SAME shape published before the measured seed, fed WARMUP_CHUNK
#   [7500] rows every WARMUP_INTERVAL_S [1] seconds (300,000 -> 40 s) so a JIT compiles under
#   a realistic load; the rig waits until every one is published, runs WARMUP_MAINT, and only
#   then seeds the measured N. Every counter subtracts the warm-up.
#   WARMUP_MAINT [analyze-active] what is done to the job table after the warm-up:
#     none            nothing (autovacuum may or may not have run: not reproducible)
#     vacuum          VACUUM, no ANALYZE: dead index entries gone, statistics as they were
#     analyze-active  VACUUM ANALYZE of each partition that holds rows - what autovacuum does
#                     to a live table: the statistics say "no row is PENDING"
#     analyze-all     VACUUM ANALYZE of the parent, i.e. every partition including the empty
#                     ones - what a database-wide ANALYZE (vacuumdb, a restore, an upgrade) does.
#   The claim's and the QUEUED update's plans depend on this; RESULTS has the comparison.
#   THREADS [0] 1 = per-thread CPU (utime/stime) and context switches of the server's PID 1 from
#   /proc at the start and end of the publishing window (results/sched-<label>.threads.*.txt)
#   JFR [0] 1 = (Java only) `jcmd 1 JFR.start` just before the seed, stopped when the queue has
#   every message; saved as results/sched-<label>.jfr.  JFR_SETTINGS [profile]
#   GATE [0] 1 = scrape the server's /metrics once a second for fc_db_gate_* (Java) into
#   results/sched-<label>.gate.jsonl
#   Scheduler knobs, passed to the server when set in this script's environment:
#   FC_SCHEDULER_BUFFER_CAPACITY FC_SCHEDULER_DISPATCHERS FC_SCHEDULER_BATCH_SIZE
#   FC_SCHEDULER_DB_MAX_CONNECTIONS (Go, Rust)  FC_DB_POOL_SIZE_SCHEDULER (Java)  JAVA_TOOL_OPTIONS
#   SAME_CREATED_AT [0] see seed()   PG_STAT [0] 1 = load pg_stat_statements and print the top
#   statements of the measured window (a diagnostic run: the extension itself costs a little)
#   PG_EXPLAIN [0] 1 = auto_explain every statement into results/sched-<label>.pg.log (implies PG_STAT)
#   PG_STAT_TOP [6] rows of pg_stat_statements printed
#   WARMUP [100] ungrouped jobs published first of all, used as the readiness check
#   KEEP [0] leave the containers up afterwards.  Extra ENV=v args after <shape> go to the server.
#
# What one run does:
#   1. fresh Postgres container (database `sched`) and fresh sqsfix;
#   2. the server image with ONLY the dispatch scheduler on (FC_SCHEDULER_ENABLED=true, platform
#      API / router / stream / outbox / scheduled jobs / MCP off, standby off so the one instance
#      is always leader), publishing to SQS at sqsfix. The server applies its own migrations;
#   3. readiness: WARMUP ungrouped jobs are inserted and must all reach sqsfix, which proves the
#      poller is running and publishing (same check for all three implementations, no health URL);
#   3b. the warm-up (WARMUP_N), see above;
#   4. the measured seed: ONE `INSERT ... SELECT generate_series` of N PENDING jobs, due now. The
#      server is running but sees none of them until that statement commits, so seeding time is
#      outside the measured window (t0 = the moment the INSERT returns);
#   5. a series (sched.py, every SAMPLE_S) of sqsfix sent / SendMessageBatch calls, read over
#      HTTP from the host. The RATE is messages arriving at the queue. The job table is NOT
#      counted while publishing (a count over a million rows every 250 ms would load the very
#      Postgres being measured); once the queue holds N messages the table is polled until no
#      job is PENDING (drain) or TIMEOUT_S;
#   5b. CPU: /sys/fs/cgroup/cpu.stat of the server, Postgres and sqsfix containers at the first
#      publish seen and when the queue holds N messages (exact CPU seconds, user and system,
#      over exactly the publishing window), plus the docker stats series as before;
#   6. correctness: sent == N, duplicates, statuses, log lines, and per-group ORDER at the
#      queue (sqsfix /order: within one message group ids must arrive ascending, which is the
#      claim order of the seed);
#   7. the summary (results/sched-<label>.log, .json), the series (.ts.jsonl), the server log.
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
N=${N:-100000}; TIMEOUT_S=${TIMEOUT_S:-300}; CPUS=${CPUS:-2}; SAMPLE_S=${SAMPLE_S:-0.25}; WARMUP=${WARMUP:-100}
WARMUP_N=${WARMUP_N:-0}; WARMUP_CHUNK=${WARMUP_CHUNK:-7500}; WARMUP_INTERVAL_S=${WARMUP_INTERVAL_S:-1}
WARMUP_TIMEOUT_S=${WARMUP_TIMEOUT_S:-300}; WARMUP_MAINT=${WARMUP_MAINT:-analyze-active}
# Host ports: sqsfix /stats and the server's metrics listener, so the sampler reads them without
# a `docker exec` per sample.
SQS_HOST_PORT=${SQS_HOST_PORT:-14566}; METRICS_HOST_PORT=${METRICS_HOST_PORT:-19090}
# Scheduler knobs forwarded to the server when set here (all three implementations ignore the
# ones that are not theirs).
PASS_ENV="FC_SCHEDULER_BUFFER_CAPACITY FC_SCHEDULER_DISPATCHERS FC_SCHEDULER_BATCH_SIZE FC_SCHEDULER_DB_MAX_CONNECTIONS FC_DB_POOL_SIZE_SCHEDULER JAVA_TOOL_OPTIONS"
# Any 32 bytes, base64: the scheduler derives its dispatch-token signing secret from it.
APP_KEY=${APP_KEY:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}

clean() { docker rm -f $SRV $SQSFIX $PG >/dev/null 2>&1; }
psqlq() { docker exec $PG psql -U pg -d $DB -v ON_ERROR_STOP=1 -tAc "$1"; }
sqsstat() { curl -s -m 5 http://127.0.0.1:$SQS_HOST_PORT/stats 2>/dev/null \
    | python3 -c 'import sys,json; j=json.load(sys.stdin); print(j.get("sent",0), j.get("calls",{}).get("SendMessageBatch",0), j.get("calls",{}).get("SendMessage",0))'; }
now() { python3 -c 'import time;print(time.time())'; }

# seed <prefix> <first> <count> <groups> <mode>: one statement. ids are <prefix> ('B' measured,
# 'W' warm-up) + 12 digits (the column is
# varchar(13)). created_at is clock_timestamp(), so every row has its own, increasing with the
# id, as jobs created one after another do. SAME_CREATED_AT=1 gives every row the statement's
# now() instead: the claim query orders by (message_group, sequence, created_at, id) and the
# poll index stops at created_at, so Postgres then sorts the whole tied run of rows on every
# claim. That is a different (pathological) measurement; it is not the default.
seed() {
  local prefix=$1 first=$2 count=$3 groups=$4 mode=$5 grp="NULL" created="clock_timestamp()"
  [ "$groups" -gt 0 ] && grp="'g' || lpad((gs % $groups)::text, 5, '0')"
  [ "${SAME_CREATED_AT:-0}" = 1 ] && created="now()"
  psqlq "INSERT INTO msg_dispatch_jobs (id, kind, code, target_url, mode, message_group, status, payload, created_at)
         SELECT '$prefix' || lpad(gs::text, 12, '0'), 'EVENT', 'bench:sched:job:created',
                'http://172.30.0.11:9000/hook', '$mode', $grp, 'PENDING', '{\"n\":' || gs || '}', $created
           FROM generate_series($first, $first + $count - 1) AS gs;" >/dev/null
}

# snap <label> <start|end>: called by the sampler (sched.py) at the first publish seen and when
# the queue holds N messages. One `docker exec` per container, each printing /proc/uptime with
# the counters it read, so every delta has its own exact time base.
snap() {
  local label=$1 which=$2 c
  for c in $SRV $PG $SQSFIX; do
    docker exec $c sh -c 'cat /proc/uptime /sys/fs/cgroup/cpu.stat' > "$out/.sched-$label.cg.$c.$which" 2>/dev/null &
  done
  if [ "${THREADS:-0}" = 1 ]; then
    # comm | stat | voluntary and nonvoluntary switches, one line per thread of PID 1, plus the
    # process totals (/proc/1/stat counts threads that have since exited; the task list cannot).
    docker exec $SRV sh -c 'echo "UPTIME $(cat /proc/uptime)"; echo "PROC $(cat /proc/1/stat)"; cd /proc/1/task && for t in *; do
        read -r c < $t/comm; read -r st < $t/stat
        sw=$(grep ctxt_switches $t/status | tr "\n\t" "  ")
        echo "T|$t|$c|$st|$sw"; done; echo "UPTIME_END $(cat /proc/uptime)"' > "$out/sched-$label.threads.$which.txt" 2>/dev/null &
  fi
  wait
}

run() {
  local label=$1 image=$2 shape=$3; shift 3
  local groups mode
  case "$shape" in
    A) groups=0; mode=IMMEDIATE ;;
    B) groups=1000; mode=NEXT_ON_ERROR ;;
    C) groups=10; mode=NEXT_ON_ERROR ;;
    D) groups=20000; mode=NEXT_ON_ERROR ;;
    *) groups=${NGROUPS:?shape must be A, B, C or D, or set NGROUPS}; mode=NEXT_ON_ERROR ;;
  esac
  [ -n "${NGROUPS:-}" ] && groups=$NGROUPS
  [ "$groups" = 0 ] && mode=IMMEDIATE
  local extra=() kv v passed=""
  for kv in $PASS_ENV; do
    v=$(printenv "$kv") && { extra+=(-e "$kv=$v"); passed="$passed $kv=$v"; }
  done
  for kv in "$@"; do extra+=(-e "$kv"); passed="$passed $kv"; done

  local leftovers; leftovers=$(docker ps -a --format '{{.Names}}' | grep -E '^bench-(sched|router)-' | tr '\n' ' ')
  [ -n "$leftovers" ] && echo "-- leftover bench containers: $leftovers(bench-sched-* are removed now)"
  clean
  local sampler_pid="" stats_pid="" gate_pid=""
  rm -f "$out"/.sched-"$label".cg.* "$out/sched-$label".threads.*.txt "$out/sched-$label.gate.jsonl" "$out/sched-$label.order.tsv"
  fail() {
    echo "FAILED at: $1" >&2
    docker logs $SRV 2>&1 | tail -30 >&2
    [ -n "$sampler_pid" ] && kill "$sampler_pid" >/dev/null 2>&1
    [ -n "$stats_pid" ] && kill "$stats_pid" >/dev/null 2>&1
    [ -n "$gate_pid" ] && kill "$gate_pid" >/dev/null 2>&1
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
  docker run -d --name $SQSFIX --network $NET --ip $SQS_IP --cpuset-cpus=2-9 -p 127.0.0.1:$SQS_HOST_PORT:4566 bench-router-sqsfix >/dev/null || fail "start sqsfix"
  local i
  for i in $(seq 1 120); do docker exec $PG pg_isready -h $PG_IP -U pg -d $DB >/dev/null 2>&1 && break; sleep 0.5; done
  docker exec $PG pg_isready -h $PG_IP -U pg -d $DB >/dev/null 2>&1 || fail "postgres ready"

  # (2) the server: scheduler only, SQS at sqsfix. FC_DISPATCH_QUEUE_URL is read only for its
  # account id and region; the queues actually used are {prefix}-{tenant}-{priority}.fifo.
  docker run -d --name $SRV --network $NET --ip $SERVER_IP --cpus="$CPUS" -p 127.0.0.1:$METRICS_HOST_PORT:9090 \
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
  [ "$base_sent" -eq "$WARMUP" ] || echo "WARNING: readiness put $base_sent messages on the queue for $WARMUP jobs" >&2

  # (3b) the warm-up: WARMUP_N jobs of the measured shape, in chunks, then wait for every one.
  local warm_s=0 warm_dups=0
  if [ "$WARMUP_N" -gt 0 ]; then
    local fed=0 k=0 c tw0; tw0=$(now)
    while [ "$fed" -lt "$WARMUP_N" ]; do
      c=$WARMUP_CHUNK; [ $((WARMUP_N - fed)) -lt "$c" ] && c=$((WARMUP_N - fed))
      seed W $((1000 + fed + 1)) "$c" "$groups" "$mode" || fail "warm-up seed"
      fed=$((fed + c)); k=$((k + 1))
      sleep "$(python3 -c "import time;print(max(0.0, $tw0 + $k * $WARMUP_INTERVAL_S - time.time()))")"
    done
    local wpend=1
    for i in $(seq 1 $((WARMUP_TIMEOUT_S * 2))); do
      read -r sent calls single <<<"$(sqsstat)"
      if [ "${sent:-0}" -ge $((WARMUP + WARMUP_N)) ]; then
        wpend=$(psqlq "SELECT count(*) FROM msg_dispatch_jobs WHERE status = 'PENDING'")
        [ "$wpend" = 0 ] && break
      fi
      [ "$(docker inspect -f '{{.State.Running}}' $SRV 2>/dev/null)" = true ] || fail "server exited during the warm-up"
      sleep 0.5
    done
    [ "$wpend" = 0 ] || fail "warm-up: ${sent:-0}/$((WARMUP + WARMUP_N)) messages, $wpend rows still PENDING after ${WARMUP_TIMEOUT_S}s"
    warm_s=$(python3 -c "import time;print(round(time.time()-$tw0,1))")
    # Table maintenance between the warm-up and the measured seed, so every measured phase
    # starts from the same, stated, table / index / statistics state instead of wherever
    # autovacuum happened to be. See WARMUP_MAINT in the header.
    case "$WARMUP_MAINT" in
      none) ;;
      vacuum) psqlq "VACUUM msg_dispatch_jobs" >/dev/null || fail "vacuum after warm-up" ;;
      analyze-active)
        local part
        for part in $(psqlq "SELECT DISTINCT tableoid::regclass FROM msg_dispatch_jobs"); do
          psqlq "VACUUM (ANALYZE) $part" >/dev/null || fail "vacuum analyze $part"
        done ;;
      analyze-all) psqlq "VACUUM (ANALYZE) msg_dispatch_jobs" >/dev/null || fail "vacuum analyze after warm-up" ;;
      *) fail "WARMUP_MAINT must be none, vacuum, analyze-active or analyze-all" ;;
    esac
    sleep 2
    read -r sent calls single <<<"$(sqsstat)"
    base_sent=$sent; base_calls=$calls
    warm_dups=$((base_sent - WARMUP - WARMUP_N))
    echo "-- warm-up: $WARMUP_N jobs (chunks of $WARMUP_CHUNK every ${WARMUP_INTERVAL_S}s) published in ${warm_s}s, $warm_dups duplicate messages; maintenance=$WARMUP_MAINT"
  fi
  local base_single=$single

  [ "${PG_EXPLAIN:-0}" = 1 ] && PG_STAT=1
  [ "${PG_STAT:-0}" = 1 ] && { psqlq "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null; psqlq "SELECT pg_stat_statements_reset()" >/dev/null; }

  if [ "${JFR:-0}" = 1 ]; then
    docker exec $SRV jcmd 1 JFR.start name=bench settings="${JFR_SETTINGS:-profile}" filename=/tmp/bench.jfr > "$out/sched-$label.jfr.log" 2>&1 \
      || echo "WARNING: JFR.start failed, see $out/sched-$label.jfr.log" >&2
  fi

  # (5) the sampler starts BEFORE the seed so the first publish is never missed; it calls
  # `snap` (cgroup CPU, threads) at the first publish and at the N-th message. The docker stats
  # loop writes one line per container per round; only rounds inside the publishing window count.
  local cpufile="$out/.sched-$label.cpu.tmp"; : > "$cpufile"
  ( while :; do docker stats --no-stream --format "$(now) {{.Name}} {{.CPUPerc}} {{.MemUsage}}" $SRV $PG $SQSFIX 2>/dev/null >> "$cpufile"; done ) &
  stats_pid=$!
  if [ "${GATE:-0}" = 1 ]; then
    python3 "$here/sched.py" gate "$out/sched-$label.gate.jsonl" "http://127.0.0.1:$METRICS_HOST_PORT/metrics" 1 &
    gate_pid=$!
  fi
  local ts="$out/sched-$label.ts.jsonl"
  THREADS="${THREADS:-0}" python3 "$here/sched.py" sample "$ts" $PG $DB "http://127.0.0.1:$SQS_HOST_PORT/stats" "$N" "$TIMEOUT_S" "$SAMPLE_S" \
      "$base_sent" "$0" "$label" &
  sampler_pid=$!
  sleep 1

  # (4) the measured seed.
  local t_seed0; t_seed0=$(now)
  seed B 1 "$N" "$groups" "$mode" || fail "seed"
  local t_seed1; t_seed1=$(now)
  local seed_s; seed_s=$(python3 -c "print(round($t_seed1-$t_seed0,2))")
  echo "-- seeded $N PENDING jobs (shape $shape, groups=$groups, mode=$mode) in ${seed_s}s, one INSERT, server running; sampling"

  wait $sampler_pid; sampler_pid=""
  kill $stats_pid >/dev/null 2>&1; wait $stats_pid 2>/dev/null; stats_pid=""
  [ -n "$gate_pid" ] && { kill $gate_pid >/dev/null 2>&1; wait $gate_pid 2>/dev/null; gate_pid=""; }
  if [ "${JFR:-0}" = 1 ]; then
    docker exec $SRV jcmd 1 JFR.stop name=bench >> "$out/sched-$label.jfr.log" 2>&1
    docker cp $SRV:/tmp/bench.jfr "$out/sched-$label.jfr" >/dev/null 2>&1 || echo "WARNING: no JFR file" >&2
  fi

  # Settle (a late duplicate publish would show up here), then freeze the server and read the
  # queue counters and the job table as one pair. After a timeout the server is still
  # publishing, so the pair can differ by the one claim that was published but not yet committed
  # when it was frozen; after a full drain nothing is in flight and the two must be equal.
  sleep 3
  docker pause $SRV >/dev/null 2>&1
  sleep 0.5
  read -r sent calls single <<<"$(sqsstat)"
  local pending_final; pending_final=$(psqlq "SELECT count(*) FROM msg_dispatch_jobs WHERE id LIKE 'B%' AND status = 'PENDING'")
  local statuses; statuses=$(psqlq "SELECT coalesce(string_agg(status || '=' || n, ',' ORDER BY status), 'none') FROM (SELECT status, count(*) AS n FROM msg_dispatch_jobs WHERE id LIKE 'B%' GROUP BY status) s")
  curl -s -m 120 "http://127.0.0.1:$SQS_HOST_PORT/order" > "$out/sched-$label.order.tsv" || echo "WARNING: could not read sqsfix /order" >&2
  local pgstat=""
  if [ "${PG_STAT:-0}" = 1 ]; then
    docker exec $PG psql -U pg -d $DB -AtF '|' -c "SELECT calls, round(total_exec_time::numeric, 1), round(mean_exec_time::numeric, 3), round(min_exec_time::numeric, 3), round(max_exec_time::numeric, 3), round(stddev_exec_time::numeric, 3), rows, shared_blks_hit + shared_blks_read, left(regexp_replace(query, '\s+', ' ', 'g'), 600) FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 40" > "$out/sched-$label.pgstat.txt"
    pgstat=$(docker exec $PG psql -U pg -d $DB -c "SELECT calls, round(total_exec_time::numeric) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 150) AS query FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT ${PG_STAT_TOP:-6}")
  fi
  docker unpause $SRV >/dev/null 2>&1
  docker logs $SRV > "$out/sched-$label.server.log" 2>&1
  [ "${PG_EXPLAIN:-0}" = 1 ] && docker logs $PG > "$out/sched-$label.pg.log" 2>&1
  # Log lines at warn / error level, JSON or plain text, and panics.
  local warns errs
  warns=$(grep -ciE '"level":"warn|(^|[^a-z])warn(ing)?([^a-z]|$)' "$out/sched-$label.server.log")
  errs=$(grep -ciE '"level":"(error|fatal)|(^|[^a-z_])(error|fatal|panic|exception)([^a-z]|$)' "$out/sched-$label.server.log")
  {
    echo "== $label image=$image shape=$shape groups=$groups mode=$mode cpus=$CPUS n=$N warmup_n=$WARMUP_N warmup_s=$warm_s maint=$WARMUP_MAINT same_created_at=${SAME_CREATED_AT:-0} env='${passed# }'"
    python3 "$here/sched.py" report "$out" "$label" "$N" "$base_sent" "$base_calls" "$sent" "$calls" "$pending_final" \
        "$t_seed1" "$SRV" "$PG" "$SQSFIX" "$CPUS" "$statuses" "$warns" "$errs" "$((single - base_single))" "$warm_dups" \
        "image=$image shape=$shape groups=$groups cpus=$CPUS warmup_n=$WARMUP_N env=${passed# }"
    echo "   seed_insert_s=$seed_s single_SendMessage_calls=$((single - base_single))"
    if [ "$errs" != 0 ] || [ "$warns" != 0 ]; then
      echo "   first warn/error lines of the server log:"
      grep -iE '"level":"(warn|error|fatal)|(^|[^a-z_])(warn|warning|error|fatal|panic|exception)([^a-z]|$)' "$out/sched-$label.server.log" | cut -c1-300 | head -4 | sed 's/^/     /'
    fi
    [ -n "$pgstat" ] && { echo "   pg_stat_statements, top statements by total time (reset just before the seed):"; echo "$pgstat"; }
  } | tee "$out/sched-$label.log"
  rm -f "$cpufile" "$out"/.sched-"$label".cg.* "$out/sched-$label.order.tsv"

  [ "${KEEP:-0}" = 1 ] || clean
}

case ${1:-} in
  run) shift; [ $# -ge 3 ] || { echo "usage: $0 run <label> <image> <A|B|C|D> [ENV=v ...]"; exit 2; }; run "$@" ;;
  _snap) shift; snap "$@" ;;
  clean) clean ;;
  *) echo "usage: $0 run <label> <image> <A|B|C|D> [ENV=v ...] | clean"; exit 2 ;;
esac
