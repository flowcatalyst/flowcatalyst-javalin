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
#   PG_EXPLAIN [0] 1 = auto_explain every statement into results/sched-<label>.pg.log (implies PG_STAT),
#   with auto_explain.log_settings=on: each plan lists the planner settings that backend runs
#   with (that is how plan_cache_mode / enable_sort of the scheduler's connections are verified)
#   PG_STAT_TOP [6] rows of pg_stat_statements printed
#   WARMUP [100] ungrouped jobs published first of all, used as the readiness check
#   QUEUE_TABLE [auto] 1 = the image claims from msg_dispatch_queue: every seed (readiness,
#   warm-up, measured) inserts the job AND its queue row in one statement, and the run ends with
#   a check that the queue table is empty. 0 = the older images (no such table): jobs only.
#   auto = wait until the server's migrations have stopped creating relations, then look.
#   QUEUE_MAINT [none] what is done to msg_dispatch_queue after the warm-up drained it:
#     none | analyze (ANALYZE only: pages stay allocated, the planner is told it is empty) |
#     vacuum-analyze (VACUUM ANALYZE: an empty table is truncated to zero pages)
#   WARMUP_MAINT also takes analyze-only (ANALYZE, no VACUUM, of each job partition with rows).
#   QUEUE_AUTOVAC_OFF_WARMUP [0] 1 = autovacuum is disabled on msg_dispatch_queue from start-up
#   until the warm-up has drained (and re-enabled before QUEUE_MAINT), so the drained table
#   reliably still has its pages when QUEUE_MAINT=analyze runs; otherwise autovacuum may have
#   truncated it already. The tabstat lines show which state the seed met.
#   SEED_WAIT_S [0] seconds to wait after the maintenance before the measured seed.
#   LAT_RATE [0] jobs/s: instead of one bulk seed, feed N = LAT_RATE x LAT_SECONDS [60] jobs of
#   the shape at that rate (one INSERT of LAT_RATE/10 rows every 100 ms, each its own
#   transaction, one psql session) and report the distribution of (arrival at sqsfix - the
#   job's created_at). N is overridden. IDLE_SAMPLES [0] with it: afterwards, that many single
#   jobs inserted 2-3 s apart into the idle system, reported the same way (poll-interval cost).
#   PERSEC [30] seconds of the per-second rate series printed in the summary.
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
QUEUE_TABLE=${QUEUE_TABLE:-auto}; QUEUE_MAINT=${QUEUE_MAINT:-none}; SEED_WAIT_S=${SEED_WAIT_S:-0}
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
  local ins="INSERT INTO msg_dispatch_jobs (id, kind, code, target_url, mode, message_group, status, payload, created_at)
         SELECT '$prefix' || lpad(gs::text, 12, '0'), 'EVENT', 'bench:sched:job:created',
                'http://172.30.0.11:9000/hook', '$mode', $grp, 'PENDING', '{\"n\":' || gs || '}', $created
           FROM generate_series($first, $first + $count - 1) AS gs"
  if [ "$QUEUE_TABLE" = 1 ]; then
    # What every implementation's lifecycle create does (Go insertPending, Rust enter_pending /
    # insert, Java DispatchJobLifecycle): the job and its queue row in ONE statement, the queue
    # row built from the inserted job (version = the job's updated_at).
    psqlq "WITH ins AS ($ins
           RETURNING id, created_at, message_group, sequence, scheduled_for, subscription_id,
                     dispatch_pool_id, client_id, mode, queue, updated_at)
         INSERT INTO msg_dispatch_queue (job_id, job_created_at, message_group, sequence, scheduled_for,
                     subscription_id, dispatch_pool_id, client_id, mode, queue, version)
         SELECT id, created_at, message_group, sequence, scheduled_for, subscription_id,
                dispatch_pool_id, client_id, mode, queue, updated_at FROM ins;" >/dev/null
  else
    psqlq "$ins;" >/dev/null
  fi
}

# tabstat <tag>: size, planner statistics and autovacuum history of the queue table and of every
# job partition that holds rows, one line each, with the database clock.
tabstat() {
  psqlq "SELECT '   tabstat $1 db_now=' || round(extract(epoch FROM clock_timestamp())::numeric, 2) || ' ' || c.relname
           || ' relpages=' || c.relpages || ' reltuples=' || c.reltuples || ' size_kb=' || pg_relation_size(c.oid) / 1024
           || ' live=' || s.n_live_tup || ' dead=' || s.n_dead_tup || ' mod_since_analyze=' || s.n_mod_since_analyze
           || ' autovacuum=' || s.autovacuum_count || '@' || coalesce(round(extract(epoch FROM s.last_autovacuum)::numeric, 2)::text, '-')
           || ' autoanalyze=' || s.autoanalyze_count || '@' || coalesce(round(extract(epoch FROM s.last_autoanalyze)::numeric, 2)::text, '-')
           || ' vacuum=' || s.vacuum_count || ' analyze=' || s.analyze_count
      FROM pg_class c JOIN pg_stat_user_tables s ON s.relid = c.oid
     WHERE c.relname = 'msg_dispatch_queue' OR (c.relname LIKE 'msg_dispatch_jobs%' AND c.relkind = 'r' AND s.n_live_tup + s.n_dead_tup > 0)
     ORDER BY c.relname" 2>/dev/null
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

# seed_paced <rate> <seconds> <groups> <mode>: 10 INSERTs a second, each rate/10 rows and its
# own transaction, paced against the database clock inside ONE psql session.
seed_paced() {
  local rate=$1 secs=$2 groups=$3 mode=$4 grp="NULL" chunk=$(( $1 / 10 )) k
  [ "$groups" -gt 0 ] && grp="'g' || lpad((gs % $groups)::text, 5, '0')"
  { echo "SELECT extract(epoch FROM clock_timestamp()) AS t0 \\gset"
    for k in $(seq 0 $((secs * 10 - 1))); do
      echo "INSERT INTO msg_dispatch_jobs (id, kind, code, target_url, mode, message_group, status, payload, created_at)
            SELECT 'B' || lpad(gs::text, 12, '0'), 'EVENT', 'bench:sched:job:created', 'http://172.30.0.11:9000/hook', '$mode', $grp, 'PENDING', '{\"n\":' || gs || '}', clock_timestamp()
              FROM generate_series($((k * chunk + 1)), $(((k + 1) * chunk))) AS gs;"
      echo "SELECT pg_sleep(greatest(0, :t0 + $((k + 1)) * 0.1 - extract(epoch FROM clock_timestamp())));"
    done
  } | docker exec -i $PG psql -U pg -d $DB -q -v ON_ERROR_STOP=1 >/dev/null
}

run() {
  local label=$1 image=$2 shape=$3; shift 3
  local LAT_RATE=${LAT_RATE:-0} LAT_SECONDS=${LAT_SECONDS:-60} IDLE_SAMPLES=${IDLE_SAMPLES:-0}
  [ "$LAT_RATE" -gt 0 ] && N=$((LAT_RATE / 10 * 10 * LAT_SECONDS))
  local host_load; host_load=$(uptime | sed 's/.*load averages*: *//')
  echo "-- host load averages before the run: $host_load"
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
      -c auto_explain.log_timing=on -c auto_explain.log_nested_statements=on -c auto_explain.log_settings=on)
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
  # Does this image claim from msg_dispatch_queue? The job table exists many migrations before
  # the queue table does, so `auto` first waits until no relation has appeared for 3 s.
  if [ "$QUEUE_TABLE" = auto ]; then
    local nrel=-1 nrel2 stable=0
    for i in $(seq 1 240); do
      nrel2=$(psqlq "SELECT count(*) FROM pg_class" 2>/dev/null)
      if [ "$nrel2" = "$nrel" ]; then stable=$((stable + 1)); else stable=0; nrel=$nrel2; fi
      [ "$stable" -ge 6 ] && break
      sleep 0.5
    done
    QUEUE_TABLE=0; psqlq "SELECT 1 FROM pg_class WHERE relname = 'msg_dispatch_queue'" 2>/dev/null | grep -q 1 && QUEUE_TABLE=1
  elif [ "$QUEUE_TABLE" = 1 ]; then
    for i in $(seq 1 240); do
      psqlq "SELECT 1 FROM pg_class WHERE relname = 'msg_dispatch_queue'" 2>/dev/null | grep -q 1 && break; sleep 0.5
    done
    psqlq "SELECT 1 FROM pg_class WHERE relname = 'msg_dispatch_queue'" 2>/dev/null | grep -q 1 || fail "QUEUE_TABLE=1 but no msg_dispatch_queue after 120s"
  fi
  echo "-- queue table (msg_dispatch_queue): $([ "$QUEUE_TABLE" = 1 ] && echo "present, every seed inserts the job and its queue row" || echo "absent, seeding the job table only")"
  if [ "$QUEUE_TABLE" = 1 ] && [ "${QUEUE_AUTOVAC_OFF_WARMUP:-0}" = 1 ]; then
    psqlq "ALTER TABLE msg_dispatch_queue SET (autovacuum_enabled = false)" >/dev/null || fail "disable autovacuum on the queue table"
  fi
  seed W 1 "$WARMUP" 0 IMMEDIATE || fail "readiness insert"
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
      analyze-only)
        local part
        for part in $(psqlq "SELECT DISTINCT tableoid::regclass FROM msg_dispatch_jobs"); do
          psqlq "ANALYZE $part" >/dev/null || fail "analyze $part"
        done ;;
      analyze-all) psqlq "VACUUM (ANALYZE) msg_dispatch_jobs" >/dev/null || fail "vacuum analyze after warm-up" ;;
      *) fail "WARMUP_MAINT must be none, vacuum, analyze-active, analyze-only or analyze-all" ;;
    esac
    if [ "$QUEUE_TABLE" = 1 ]; then
      local qleft; qleft=$(psqlq "SELECT count(*) FROM msg_dispatch_queue")
      [ "$qleft" = 0 ] || echo "WARNING: $qleft queue rows left after the warm-up drained" >&2
      if [ "${QUEUE_AUTOVAC_OFF_WARMUP:-0}" = 1 ]; then
        psqlq "ALTER TABLE msg_dispatch_queue RESET (autovacuum_enabled)" >/dev/null || fail "re-enable autovacuum on the queue table"
      fi
      case "$QUEUE_MAINT" in
        none) ;;
        analyze) psqlq "ANALYZE msg_dispatch_queue" >/dev/null || fail "analyze queue" ;;
        vacuum-analyze) psqlq "VACUUM (ANALYZE) msg_dispatch_queue" >/dev/null || fail "vacuum analyze queue" ;;
        *) fail "QUEUE_MAINT must be none, analyze or vacuum-analyze" ;;
      esac
    fi
    sleep 2
    read -r sent calls single <<<"$(sqsstat)"
    base_sent=$sent; base_calls=$calls
    warm_dups=$((base_sent - WARMUP - WARMUP_N))
    echo "-- warm-up: $WARMUP_N jobs (chunks of $WARMUP_CHUNK every ${WARMUP_INTERVAL_S}s) published in ${warm_s}s, $warm_dups duplicate messages; maintenance=$WARMUP_MAINT queue_maint=$QUEUE_MAINT"
  fi
  if [ "$SEED_WAIT_S" != 0 ]; then
    sleep "$SEED_WAIT_S"
    read -r sent calls single <<<"$(sqsstat)"
    [ "$sent" = "$base_sent" ] || echo "WARNING: $((sent - base_sent)) messages arrived during the ${SEED_WAIT_S}s wait before the seed" >&2
    base_sent=$sent; base_calls=$calls
  fi
  local tab_before; tab_before=$(tabstat before-seed)
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
  if [ "$LAT_RATE" -gt 0 ]; then
    [ "$QUEUE_TABLE" = 1 ] && fail "LAT_RATE is not implemented for the queue-table seeding"
    seed_paced "$LAT_RATE" "$LAT_SECONDS" "$groups" "$mode" || fail "paced seed"
  else
    seed B 1 "$N" "$groups" "$mode" || fail "seed"
  fi
  local t_seed1; t_seed1=$(now)
  # With a paced feed the "seed" lasts LAT_SECONDS; rates in the summary are then relative to
  # the START of the feed.
  [ "$LAT_RATE" -gt 0 ] && t_seed1=$t_seed0
  local seed_s; seed_s=$(python3 -c "print(round($t_seed1-$t_seed0,2))")
  echo "-- seeded $N PENDING jobs (shape $shape, groups=$groups, mode=$mode) in ${seed_s}s, one INSERT, server running; sampling"

  wait $sampler_pid; sampler_pid=""
  kill $stats_pid >/dev/null 2>&1; wait $stats_pid 2>/dev/null; stats_pid=""
  [ -n "$gate_pid" ] && { kill $gate_pid >/dev/null 2>&1; wait $gate_pid 2>/dev/null; gate_pid=""; }
  if [ "${JFR:-0}" = 1 ]; then
    docker exec $SRV jcmd 1 JFR.stop name=bench >> "$out/sched-$label.jfr.log" 2>&1
    docker cp $SRV:/tmp/bench.jfr "$out/sched-$label.jfr" >/dev/null 2>&1 || echo "WARNING: no JFR file" >&2
  fi

  local idle_n=0
  if [ "$LAT_RATE" -gt 0 ] && [ "$IDLE_SAMPLES" -gt 0 ]; then
    for i in $(seq 1 "$IDLE_SAMPLES"); do
      sleep "$(python3 -c 'import random;print(round(2+random.random(),3))')"
      psqlq "INSERT INTO msg_dispatch_jobs (id, kind, code, target_url, mode, status, payload, created_at)
             VALUES ('S' || lpad('$i', 12, '0'), 'EVENT', 'bench:sched:job:created', 'http://172.30.0.11:9000/hook', 'IMMEDIATE', 'PENDING', '{}', clock_timestamp())" >/dev/null || fail "single insert"
    done
    idle_n=$IDLE_SAMPLES; sleep 3
  fi
  # Settle (a late duplicate publish would show up here), then freeze the server and read the
  # queue counters and the job table as one pair. After a timeout the server is still
  # publishing, so the pair can differ by the one claim that was published but not yet committed
  # when it was frozen; after a full drain nothing is in flight and the two must be equal.
  sleep 3
  local mem_peak; mem_peak=$(docker exec $SRV sh -c 'cat /sys/fs/cgroup/memory.peak' 2>/dev/null || echo 0)
  docker pause $SRV >/dev/null 2>&1
  sleep 0.5
  read -r sent calls single <<<"$(sqsstat)"
  sent=$((sent - idle_n))   # the IDLE_SAMPLES singles are not part of N
  local lat_file=""
  if [ "$LAT_RATE" -gt 0 ]; then
    lat_file="$out/.sched-$label.created"
    psqlq "SELECT id || '|' || round(extract(epoch FROM created_at) * 1000, 3) FROM msg_dispatch_jobs WHERE id LIKE 'B%' OR id LIKE 'S%'" > "$lat_file"
  fi
  local pending_final; pending_final=$(psqlq "SELECT count(*) FROM msg_dispatch_jobs WHERE id LIKE 'B%' AND status = 'PENDING'")
  local queue_left=na
  [ "$QUEUE_TABLE" = 1 ] && queue_left=$(psqlq "SELECT count(*) FROM msg_dispatch_queue")
  local tab_after; tab_after=$(tabstat end)
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
    echo "== $label image=$image shape=$shape groups=$groups mode=$mode cpus=$CPUS n=$N warmup_n=$WARMUP_N warmup_s=$warm_s maint=$WARMUP_MAINT queue_table=$QUEUE_TABLE queue_maint=$QUEUE_MAINT queue_autovac_off_warmup=${QUEUE_AUTOVAC_OFF_WARMUP:-0} seed_wait_s=$SEED_WAIT_S same_created_at=${SAME_CREATED_AT:-0} env='${passed# }'"
    LAT_FILE="$lat_file" QUEUE_LEFT="$queue_left" PERSEC="${PERSEC:-30}" python3 "$here/sched.py" report "$out" "$label" "$N" "$base_sent" "$base_calls" "$sent" "$calls" "$pending_final" \
        "$t_seed1" "$SRV" "$PG" "$SQSFIX" "$CPUS" "$statuses" "$warns" "$errs" "$((single - base_single))" "$warm_dups" \
        "image=$image shape=$shape groups=$groups cpus=$CPUS warmup_n=$WARMUP_N env=${passed# }"
    echo "   seed_insert_s=$seed_s single_SendMessage_calls=$((single - base_single)) seed_committed_epoch=$t_seed1 (host clock; tabstat times are the database clock)"
    echo "   server_cgroup_memory_peak_mb=$(( ${mem_peak:-0} / 1048576 )) (memory.peak of the server container: its whole life, warm-up included) host_load_before='$host_load' lat_rate=$LAT_RATE"
    echo "$tab_before"; echo "$tab_after"
    if [ "$errs" != 0 ] || [ "$warns" != 0 ]; then
      echo "   first warn/error lines of the server log:"
      grep -iE '"level":"(warn|error|fatal)|(^|[^a-z_])(warn|warning|error|fatal|panic|exception)([^a-z]|$)' "$out/sched-$label.server.log" | cut -c1-300 | head -4 | sed 's/^/     /'
    fi
    [ -n "$pgstat" ] && { echo "   pg_stat_statements, top statements by total time (reset just before the seed):"; echo "$pgstat"; }
  } | tee "$out/sched-$label.log"
  rm -f "$cpufile" "$out"/.sched-"$label".cg.* "$out/sched-$label.order.tsv" "$out/.sched-$label.created"

  [ "${KEEP:-0}" = 1 ] || clean
}

case ${1:-} in
  run) shift; [ $# -ge 3 ] || { echo "usage: $0 run <label> <image> <A|B|C|D> [ENV=v ...]"; exit 2; }; run "$@" ;;
  _snap) shift; snap "$@" ;;
  clean) clean ;;
  *) echo "usage: $0 run <label> <image> <A|B|C|D> [ENV=v ...] | clean"; exit 2 ;;
esac
