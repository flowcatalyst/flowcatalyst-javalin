#!/usr/bin/env bash
# Router-only bench rig (docs/spec/router.md §1, §6.1). Reuses bench/real's Docker network,
# Postgres container, IP conventions and context-switch snapshot. See RESULTS.md for the
# smoke-test rows and pitfalls.
#
#   bench/router/run.sh prepare
#   bench/router/run.sh run <label> <image> "<cpu-args>" [ENV=v ...]
#
# ENV=v args starting with SINK_ are passed to the sink container; everything else is passed
# to the server container (in addition to the fixed router-only env below).
#
# Pinned-core mode (test-size RESULTS.md round 9), same variables as bench/real/run.sh:
#   CPUSET=<n or range> pins the router container (--cpuset-cpus=$CPUSET), overriding the
#     positional <cpu-args> (still required on the command line; CPUSET wins when set).
#   PG_CPUSET=<n or range> moves Postgres (and, if running, LocalStack/NATS — whichever
#     container is actually acting as the broker for this BROKER=) off the pinned core(s)
#     with `docker update`, before the router starts. The sink, LocalStack, NATS and the
#     probers already run on --cpuset-cpus=2-9 unconditionally (unaffected either way).
#   Both unset by default: the existing <cpu-args> quota mode is unchanged.
#   "Warm-up window": this rig measures a full-queue drain, not a sustained request rate, so
#     there is no separate warm-up phase inside one call the way bench/real's wrk warm-up is —
#     the router process itself is started fresh per `run` invocation (see the `docker rm -f
#     "$sname"` below). Get a warm-up window by invoking `run` twice: once with a small
#     TOTAL_MESSAGES as a throwaway pass (discard its .log), then again with the real
#     TOTAL_MESSAGES for the measured pass — TOTAL_MESSAGES and TIMEOUT_S are already the
#     window-size parameters (see below); see bench/README-parity.md for the exact commands.
set -u
here=$(cd "$(dirname "$0")" && pwd)
out=$here/results; mkdir -p "$out"

NET=bench-real; PG=bench-real-pg; PG_IP=172.30.0.2; SERVER_IP=172.30.0.10; SINK_IP=172.30.0.11
LOCALSTACK_IP=172.30.0.12
NATS_IP=172.30.0.13
PROBER=bench-router-prober
AWSPROBER=bench-router-awsprober
LOCALSTACK=bench-router-localstack
NATS=bench-router-nats
NATSBOX=bench-router-natsbox
LOCALSTACK_IMAGE=${LOCALSTACK_IMAGE:-localstack/localstack:4.0}
AWSCLI_IMAGE=${AWSCLI_IMAGE:-amazon/aws-cli:2.17.62}
NATS_IMAGE=${NATS_IMAGE:-nats:2.11-alpine}
NATSBOX_IMAGE=${NATSBOX_IMAGE:-natsio/nats-box:latest}
TIMEOUT_S=${TIMEOUT_S:-300}
# BROKER: postgres (default, reproduces every earlier round), sqs, or nats — the owner asked
# for SQS on LocalStack because the Postgres queue_messages table turned out to be the ceiling
# (RESULTS.md "Queue parallelism"), and then for NATS JetStream because the owner wants the
# router measured against a real, fast broker so the router itself is the bottleneck, not the
# broker (both the Postgres queue table and LocalStack were). See the "SQS on LocalStack" /
# "NATS JetStream" sections in RESULTS.md for the full account.
BROKER=${BROKER:-postgres}
# POOL_CONCURRENCY: explicit, equal-on-both-sides pool sizing via FLOWCATALYST_CONFIG_URL
# (the sink's own /config route — see sink/main.go) instead of the synthesised DEFAULT-POOL,
# whose default concurrency differs Go (4) vs Java. POOL_CONCURRENCY=0 keeps the old
# behaviour: no config URL, seed against the synthesised DEFAULT-POOL.
POOL_CONCURRENCY=${POOL_CONCURRENCY:-64}
# TOTAL_MESSAGES: rows inserted directly by SQL BEFORE the router container starts, so what
# gets measured is the DRAIN (router draining a full queue), not the seeder. SEED_VIA=api
# reverts to the old behaviour (seed via the router's own HTTP API, after health, timed
# separately as part of the measured window) — kept for debugging only; the SQL path is what
# proves the router itself, not the seed API, so it's the default.
TOTAL_MESSAGES=${TOTAL_MESSAGES:-50000}
SEED_VIA=${SEED_VIA:-sql}
SEED_CALLS=${SEED_CALLS:-2}
SEED_COUNT=${SEED_COUNT:-10000}
# SINK_H2C: default on. The sink accepts h2c (HTTP/2 prior-knowledge cleartext) when a client
# asks for it; Go's mediator speaks HTTP/1.1 regardless, so proto_counts in the summary line
# is how you tell which protocol a given router actually used.
SINK_H2C=${SINK_H2C:-1}
# QUEUES: how many independent queues (BENCH-1..BENCH-n, all routed to the single BENCH pool)
# the config-URL path advertises. One consumer polls maxPoll=10 at a time per queue
# (docs/spec/router.md §5 #5, §3.2) regardless of pool concurrency, so a single queue caps
# out well under 500/s; N queues against the same database give N independent poll loops
# feeding the same worker pool — a legitimate production shape (queues are independent
# consumers), not a rig trick. Only applies with POOL_CONCURRENCY != 0 (the config-URL path).
QUEUES=${QUEUES:-1}

# ---- prepare: network + pg reused from bench/real, database `rt`, sink image -------------

prepare() {
  docker network inspect $NET >/dev/null 2>&1 || docker network create --subnet 172.30.0.0/24 $NET >/dev/null
  if ! docker inspect $PG >/dev/null 2>&1; then
    echo "-- $PG not found; starting one (bench/real usually already has it)"
    docker run -d --name $PG --network $NET --ip $PG_IP -e POSTGRES_PASSWORD=pg -e POSTGRES_USER=pg -e POSTGRES_DB=fc postgres:18-alpine >/dev/null
  fi
  for i in $(seq 1 60); do docker exec $PG pg_isready -U pg >/dev/null 2>&1 && break; sleep 0.5; done
  # Each Postgres-backed queue opens its OWN pgxpool (docs/spec/router.md §7.3: "one pgxpool
  # per consumer"), so N queues (QUEUES=N) means N independent connection pools against this
  # one Postgres — at QUEUES=8 this rig hit the factory-default max_connections=100 (mixed
  # with the rig's own polling connections). bench/real's prepare() already raises this the
  # same way; do it here too rather than assume the shared container picked it up.
  local maxconn; maxconn=$(docker exec $PG psql -U pg -d postgres -tAc "SHOW max_connections;" 2>/dev/null | tr -d '[:space:]')
  if [ "${maxconn:-0}" -lt 300 ] 2>/dev/null; then
    echo "-- raising max_connections 100 -> 300 (was $maxconn) and restarting $PG"
    docker exec $PG psql -U pg -d postgres -c "ALTER SYSTEM SET max_connections = 300;" >/dev/null
    docker restart $PG >/dev/null
    for i in $(seq 1 60); do docker exec $PG pg_isready -U pg >/dev/null 2>&1 && break; sleep 0.5; done
  fi
  docker exec $PG psql -U pg -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='rt'" | grep -q 1 \
    || docker exec $PG psql -U pg -d postgres -c "CREATE DATABASE rt OWNER pg;" >/dev/null
  echo "-- building bench-router-sink"
  docker build -q -t bench-router-sink -f "$here/Dockerfile.sink" "$here" >/dev/null
  echo "-- building bench-router-seedsqs (BROKER=sqs bulk producer)"
  docker build -q -t bench-router-seedsqs -f "$here/Dockerfile.seedsqs" "$here" >/dev/null
  echo "-- building bench-router-seednats (BROKER=nats bulk producer)"
  docker build -q -t bench-router-seednats -f "$here/Dockerfile.seednats" "$here" >/dev/null
  echo "prepared: network=$NET pg=$PG($PG_IP) db=rt sink image=bench-router-sink seedsqs image=bench-router-seedsqs seednats image=bench-router-seednats"
}

# ---- helpers -------------------------------------------------------------------------------

reset_db() {
  docker exec $PG psql -U pg -d postgres -c "DROP DATABASE IF EXISTS rt WITH (FORCE);" >/dev/null
  docker exec $PG psql -U pg -d postgres -c "CREATE DATABASE rt OWNER pg;" >/dev/null
  # queue_messages DDL (docs/spec/router.md §7.3), created up front. The default-broker
  # bootstrap (POOL_CONCURRENCY=0, no config URL) has each router create this itself
  # (IF NOT EXISTS, so pre-creating it here is a harmless no-op there); with a config URL
  # neither Go's nor Java's postgres queue backend calls InitSchema at all — it's InitSchema
  # via the Embedded interface, wired only into the default-broker bootstrap path
  # (internal/server/run.go:352-358) — so the table must already exist before the router
  # ever polls or the seed API ever inserts into it.
  docker exec $PG psql -U pg -d rt -c "
    CREATE TABLE IF NOT EXISTS queue_messages (
        id               TEXT NOT NULL,
        queue_name       TEXT NOT NULL,
        message_group_id TEXT,
        receipt_handle   TEXT,
        visible_at       BIGINT NOT NULL,
        payload          TEXT NOT NULL,
        created_at       BIGINT NOT NULL,
        receive_count    INTEGER DEFAULT 0,
        PRIMARY KEY (queue_name, id)
    );
    CREATE INDEX IF NOT EXISTS idx_queue_visible ON queue_messages (queue_name, visible_at, message_group_id);
  " >/dev/null
}

# seed_sql(n, nqueues, poolCode, target): one INSERT ... SELECT generate_series statement —
# fills queue_messages with n unclaimed, immediately-visible rows so the queue is FULL before
# the router container is even started (measures the drain, not the seed API). Rows are
# distributed round-robin over BENCH-1..BENCH-nqueues (matching the sink's /config — each
# distinct queue_name gets its own consumer/poll loop on both routers, docs/spec/router.md
# §7.1/§7.3: the Postgres backend's Poll filters `WHERE queue_name = $1` on QueueConfig.Name,
# not on the URI, so N names sharing one queueUri really are N independent pollers). Column
# semantics from §7.3 (Publish: visible_at=created_at=now, receipt_handle NULL); payload shape
# from §2.1, confirmed against a real API-seeded row (see RESULTS.md —
# {"id":...,"poolCode":...,"mediationType":"HTTP","mediationTarget":...,"dispatchMode":"IMMEDIATE"},
# message_group_id NULL, unset optionals simply absent from the JSON). pool_code is the same
# "BENCH" on every row regardless of which queue it lands in — one shared worker pool.
seed_sql() {
  local n=$1 nqueues=$2 pcode=$3 target=$4
  docker exec $PG psql -U pg -d rt -c "
    INSERT INTO queue_messages (id, queue_name, message_group_id, receipt_handle, visible_at, payload, created_at, receive_count)
    SELECT
      'bench-' || gs,
      'BENCH-' || (((gs - 1) % $nqueues) + 1),
      NULL,
      NULL,
      extract(epoch from now())::bigint,
      '{\"id\":\"bench-' || gs || '\",\"poolCode\":\"$pcode\",\"mediationType\":\"HTTP\",\"mediationTarget\":\"$target\",\"dispatchMode\":\"IMMEDIATE\"}',
      extract(epoch from now())::bigint,
      0
    FROM generate_series(1, $n) AS gs;
  " >/dev/null
}

# A persistent curl container on the bench network — avoids a container-spin-up cost on every
# health/seed/poll call (the poll loop alone fires every 500 ms).
start_prober() {
  docker rm -f $PROBER >/dev/null 2>&1
  docker run -d --name $PROBER --network $NET --entrypoint sleep curlimages/curl:8.10.1 infinity >/dev/null
}
probe() { docker exec $PROBER curl -s "$@"; }

# ---- BROKER=sqs helpers ---------------------------------------------------------------------

# Fresh LocalStack (SQS only) at $LOCALSTACK_IP, pinned like the sink, same idiom as the other
# containers this rig recreates per run.
start_localstack() {
  docker rm -f $LOCALSTACK >/dev/null 2>&1
  docker run -d --name $LOCALSTACK --network $NET --ip $LOCALSTACK_IP --cpuset-cpus=2-9 \
      -e SERVICES=sqs -e DEFAULT_REGION=us-east-1 "$LOCALSTACK_IMAGE" >/dev/null
  for i in $(seq 1 60); do
    docker exec $LOCALSTACK curl -s http://localhost:4566/_localstack/health 2>/dev/null | grep -q '"sqs": *"\(available\|running\)"' && return 0
    sleep 1
  done
  return 1
}

# A persistent aws-cli container (credentials baked in) — same rationale as $PROBER: aws-cli is
# a Python process with real startup cost, and the drain-completion check alone can poll this
# every second.
start_awsprober() {
  docker rm -f $AWSPROBER >/dev/null 2>&1
  docker run -d --name $AWSPROBER --network $NET \
      -e AWS_ACCESS_KEY_ID=test -e AWS_SECRET_ACCESS_KEY=test -e AWS_DEFAULT_REGION=us-east-1 \
      --entrypoint sleep "$AWSCLI_IMAGE" infinity >/dev/null
}
awsprobe() { docker exec $AWSPROBER aws --endpoint-url "http://$LOCALSTACK_IP:4566" "$@"; }

# create_sqs_queues(n): BENCH-1..BENCH-n, visibility timeout 120 to match the Postgres rows,
# standard (non-FIFO) queues.
create_sqs_queues() {
  local n=$1 i
  for i in $(seq 1 "$n"); do
    awsprobe sqs create-queue --queue-name "BENCH-$i" --attributes VisibilityTimeout=120 >/dev/null
  done
}

# sqs_queue_depth(n): sum of ApproximateNumberOfMessages + ApproximateNumberOfMessagesNotVisible
# across BENCH-1..BENCH-n — the SQS equivalent of `SELECT count(*) FROM queue_messages`. Queue
# URLs are the same "real AWS-shaped" ones the router's config uses (§7.1) — LocalStack resolves
# by path regardless of host, confirmed manually before wiring this in (RESULTS.md).
sqs_queue_depth() {
  local n=$1 i total=0 out visible notvisible
  for i in $(seq 1 "$n"); do
    out=$(awsprobe sqs get-queue-attributes \
        --queue-url "https://sqs.us-east-1.amazonaws.com/000000000000/BENCH-$i" \
        --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
        --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' \
        --output text 2>/dev/null)
    read -r visible notvisible <<<"$out"
    total=$((total + ${visible:-0} + ${notvisible:-0}))
  done
  echo "$total"
}

# ---- BROKER=nats helpers ---------------------------------------------------------------------

# Fresh NATS JetStream server at $NATS_IP, pinned like the sink/LocalStack. Storage is
# deliberately memory (not the default file) for every stream this rig creates — disk I/O is
# not the variable under test here (see RESULTS.md "NATS JetStream").
start_nats() {
  docker rm -f $NATS >/dev/null 2>&1
  docker run -d --name $NATS --network $NET --ip $NATS_IP --cpuset-cpus=2-9 \
      "$NATS_IMAGE" -js -m 8222 >/dev/null
  for i in $(seq 1 60); do
    docker exec $NATS wget -q -O /dev/null "http://127.0.0.1:8222/healthz" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

# A persistent nats-box (the `nats` CLI) container — same rationale as $PROBER/$AWSPROBER:
# avoids a container-spin-up cost on every stream/consumer/info call.
start_natsbox() {
  docker rm -f $NATSBOX >/dev/null 2>&1
  docker run -d --name $NATSBOX --network $NET --entrypoint sleep "$NATSBOX_IMAGE" infinity >/dev/null
}
natsprobe() { docker exec $NATSBOX nats -s "nats://$NATS_IP:4222" "$@"; }

# create_nats_streams(n): BENCH1..BENCHn, WorkQueue retention, subjects "bench.<i>.>",
# storage=memory, replicas=1, max-age=7d (docs/spec/router.md §7.4 defaults) — matching, field
# for field, the config the router's own queue URI (see run(), BROKER=nats branch) asks its
# CreateOrUpdateStream/CreateOrUpdateConsumer to provision, so the router's own provisioning
# call is a no-op against what's seeded here. Durable pull consumer "router" per stream:
# ack policy explicit, deliver all, ack-wait 120s, max-deliver 10, max-pending 1000, filtered to
# the same subject as the stream (matching cfg.Subject as FilterSubject in nats.go/NatsQueue).
create_nats_streams() {
  local n=$1 i
  for i in $(seq 1 "$n"); do
    natsprobe stream add "BENCH$i" \
        --retention=work --storage=memory --subjects="bench.$i.>" --replicas=1 \
        --max-age=7d --defaults >/dev/null \
      || { echo "FAILED: nats stream add BENCH$i" >&2; return 1; }
    natsprobe consumer add "BENCH$i" router \
        --pull --deliver=all --ack=explicit --wait=120s --max-deliver=10 --max-pending=1000 \
        --filter="bench.$i.>" --defaults >/dev/null \
      || { echo "FAILED: nats consumer add BENCH$i router" >&2; return 1; }
  done
}

# nats_stream_messages(n): sum of state.messages across BENCH1..BENCHn — the NATS equivalent of
# `SELECT count(*) FROM queue_messages` / sqs_queue_depth's ApproximateNumberOfMessages.
nats_stream_messages() {
  local n=$1 i total=0 m
  for i in $(seq 1 "$n"); do
    m=$(natsprobe stream info "BENCH$i" --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"]["messages"])' 2>/dev/null || echo 0)
    total=$((total + ${m:-0}))
  done
  echo "$total"
}

# nats_ack_pending(n): sum of num_ack_pending across the "router" consumer on BENCH1..BENCHn —
# claimed-but-unacked messages, the NATS equivalent of the Postgres backend's
# `receipt_handle IS NOT NULL` in-flight count.
nats_ack_pending() {
  local n=$1 i total=0 p
  for i in $(seq 1 "$n"); do
    p=$(natsprobe consumer info "BENCH$i" router --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["num_ack_pending"])' 2>/dev/null || echo 0)
    total=$((total + ${p:-0}))
  done
  echo "$total"
}

# nats_queue_depth(n): stream backlog + still-unacked in-flight — the drain isn't done until
# both are 0 (docs/spec/router.md §7.4: Ack is a JetStream Ack() on the pending map; a message
# claimed-but-not-yet-acked is neither "in the stream as pending" nor already gone).
nats_queue_depth() {
  local n=$1
  echo $(( $(nats_stream_messages "$n") + $(nats_ack_pending "$n") ))
}

# snap(): per-OS-thread kernel context-switch counters for PID 1 of a container.
snap() { docker exec "$1" sh -c 'for t in /proc/1/task/*; do printf "%s\t%s\t%s\n" "$(basename "$t")" "$(awk "/^voluntary/{print \$2}" "$t"/status)" "$(awk "/^nonvoluntary/{print \$2}" "$t"/status)"; done'; }

# switch_delta(before, after): total voluntary+nonvoluntary switches across all threads.
switch_delta() {
  python3 - "$1" "$2" <<'PY'
import sys
def parse(s):
    d={}
    for line in s.splitlines():
        p=line.split('\t')
        if len(p)==3:
            d[p[0]]=(int(p[1] or 0), int(p[2] or 0))
    return d
b=parse(sys.argv[1]); a=parse(sys.argv[2])
total=0
for t,(v,n) in a.items():
    bv,bn=b.get(t,(0,0))
    total += (v-bv) + (n-bn)
print(total)
PY
}

# wait_health(ip): tries no-auth then bench:bench basic auth against <ip>:8080/router/health.
# Echoes "noauth" or "auth" on success (stderr on failure) and returns non-zero on timeout.
wait_health() {
  local ip=$1
  for i in $(seq 1 150); do
    if probe -o /dev/null -w '%{http_code}' "http://$ip:8080/router/health" 2>/dev/null | grep -q 200; then
      echo noauth; return 0
    fi
    if probe -u bench:bench -o /dev/null -w '%{http_code}' "http://$ip:8080/router/health" 2>/dev/null | grep -q 200; then
      echo auth; return 0
    fi
    sleep 0.2
  done
  return 1
}

# ---- run -------------------------------------------------------------------------------

run() {
  # One run at a time on the shared network: a run's start-up removes every bench-router-*
  # container, so two concurrent runs destroy each other. mkdir is atomic; a lock whose owner
  # is gone is stale and reclaimed.
  local lock="$here/results/.run.lock"
  while ! mkdir "$lock" 2>/dev/null; do
    local owner; owner=$(cat "$lock/pid" 2>/dev/null || echo 0)
    if [ "$owner" -gt 0 ] 2>/dev/null && kill -0 "$owner" 2>/dev/null; then
      echo "another run (pid $owner) is in progress; waiting"; sleep 15
    else
      rm -rf "$lock"
    fi
  done
  echo $$ > "$lock/pid"
  trap 'rm -rf "$lock"' EXIT
  local label=$1 image=$2 cpuargs=$3; shift 3
  # CPUSET/PG_CPUSET: pinned-core mode (round 9) — see the header comment. cpuargs override
  # happens here, once, so every use of $cpuargs below (including the summary line) sees it.
  [ -n "${CPUSET:-}" ] && cpuargs="--cpuset-cpus=$CPUSET"
  local sname="bench-router-srv-$label" kname="bench-router-sink"

  fail() {
    echo "FAILED at: $1" >&2
    docker logs "$sname" 2>&1 | tail -30 >&2
    docker rm -f "$sname" "$kname" $PROBER $AWSPROBER $LOCALSTACK $NATS $NATSBOX >/dev/null 2>&1
    exit 1
  }

  # split extra ENV=v args into sink env (SINK_*) and server env (everything else)
  local sink_envs=() server_envs=()
  for kv in "$@"; do
    case "$kv" in
      SINK_*) sink_envs+=(-e "$kv") ;;
      *) server_envs+=(-e "$kv") ;;
    esac
  done

  docker rm -f "$sname" "$kname" >/dev/null 2>&1
  start_prober

  local pool_code=DEFAULT-POOL nqueues=1 queue_uri total effective_seed_via
  local config_url_env=()

  if [ "$BROKER" = sqs ]; then
    # BROKER=sqs always uses the config-URL/BENCH path — the synthesised-DEFAULT-POOL
    # bootstrap (POOL_CONCURRENCY=0) is a Postgres-only shape, nothing analogous for SQS.
    pool_code=BENCH; nqueues=$QUEUES; total=$TOTAL_MESSAGES; effective_seed_via=sqs
    # The "real AWS-shaped" URL docs/spec/router.md §7.1 requires for scheme resolution
    # (host starts with sqs., contains .amazonaws.) — LocalStack accepts any host in the
    # QueueUrl parameter and resolves by path (account/queue name), confirmed manually
    # before wiring this in: a message sent/received against exactly this URL shape
    # round-tripped through LocalStack with only AWS_ENDPOINT_URL_SQS redirecting the
    # connection. %d is templated per queue by the sink's /config (sink/main.go).
    queue_uri="https://sqs.us-east-1.amazonaws.com/000000000000/BENCH-%d"
    config_url_env=(-e FLOWCATALYST_CONFIG_URL="http://$SINK_IP:9000/config")

    start_localstack || fail "LocalStack health wait ($LOCALSTACK_IP:4566/_localstack/health)"
    start_awsprober
    create_sqs_queues "$nqueues"

    local t_seed0; t_seed0=$(python3 -c 'import time;print(time.time())')
    docker run --rm --network $NET \
        -e TOTAL="$total" -e QUEUES="$nqueues" -e ENDPOINT="http://$LOCALSTACK_IP:4566" \
        -e REGION=us-east-1 -e MEDIATION_TARGET="http://$SINK_IP:9000/hook" \
        bench-router-seedsqs || fail "seedsqs (TOTAL=$total QUEUES=$nqueues)"
    local sql_seed_wall; sql_seed_wall=$(python3 -c "import time;print(round(time.time()-$t_seed0,2))")
    echo "-- seeded $total messages across $nqueues SQS queue(s) in ${sql_seed_wall}s (queues full BEFORE the router starts)"
  elif [ "$BROKER" = nats ]; then
    # BROKER=nats, like sqs, always uses the config-URL/BENCH path.
    pool_code=BENCH; nqueues=$QUEUES; total=$TOTAL_MESSAGES; effective_seed_via=nats
    # %d is templated per queue by the sink's /config (sink/main.go, ReplaceAll now, not
    # Sprintf, because this template needs the queue number substituted twice). Every param is
    # explicit and equal to the Go/Java parser default (docs/spec/router.md §7.4,
    # NatsQueueUri.java) EXCEPT storage=memory (deliberately not file — disk isn't the variable
    # under test, see RESULTS.md) — spelling every default out here, rather than relying on both
    # sides defaulting the same way, is what makes the router's own create-or-update a verified
    # no-op against create_nats_streams() below, not an assumed one.
    queue_uri="nats://$NATS_IP:4222?stream=BENCH%d&consumer=router&subject=bench.%d.>&max-messages=10&poll-timeout-ms=20000&ack-wait-secs=120&max-deliver=10&max-ack-pending=1000&storage=memory&replicas=1&max-age-days=7"
    config_url_env=(-e FLOWCATALYST_CONFIG_URL="http://$SINK_IP:9000/config")

    start_nats || fail "NATS health wait ($NATS_IP:8222/healthz)"
    start_natsbox
    create_nats_streams "$nqueues" || fail "create_nats_streams(QUEUES=$nqueues)"

    local t_seed0; t_seed0=$(python3 -c 'import time;print(time.time())')
    docker run --rm --network $NET \
        -e NATS_URL="nats://$NATS_IP:4222" -e TOTAL="$total" -e QUEUES="$nqueues" \
        -e MEDIATION_TARGET="http://$SINK_IP:9000/hook" \
        bench-router-seednats || fail "seednats (TOTAL=$total QUEUES=$nqueues)"
    local sql_seed_wall; sql_seed_wall=$(python3 -c "import time;print(round(time.time()-$t_seed0,2))")
    echo "-- seeded $total messages across $nqueues NATS stream(s) in ${sql_seed_wall}s (streams full BEFORE the router starts)"

    # Verify per-stream counts BEFORE the router starts (task requirement): each BENCH<n>
    # stream must hold exactly its round-robin share of $total.
    local qi expect_lo expect_hi got all_ok=1
    for qi in $(seq 1 "$nqueues"); do
      got=$(natsprobe stream info "BENCH$qi" --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"]["messages"])' 2>/dev/null || echo -1)
      echo "   BENCH$qi messages=$got"
      [ "$got" -ge 0 ] 2>/dev/null || all_ok=0
    done
    local seeded_total; seeded_total=$(nats_stream_messages "$nqueues")
    [ "$seeded_total" = "$total" ] || fail "pre-seed verify: nats streams hold $seeded_total, expected $total"
  else
    reset_db

    # queue URI the postgres queue backend expects: mirror Go's postgresQueueURI
    # (internal/server/run.go ~416-430) / Java's Router#defaultQueueUri — postgresql:// -> postgres://,
    # same host/db as FC_DATABASE_URL below.
    queue_uri="postgres://pg:pg@$PG_IP:5432/rt"

    # pool_code: BENCH (from the sink's /config, explicit equal concurrency) unless
    # POOL_CONCURRENCY=0 asks for the old synthesised-DEFAULT-POOL behaviour. QUEUES only
    # applies to the BENCH path — the sink advertises BENCH-1..BENCH-$QUEUES, all feeding the
    # one BENCH pool (see the QUEUES comment near the top of this file).
    if [ "$POOL_CONCURRENCY" != 0 ]; then
      pool_code=BENCH; nqueues=$QUEUES
      config_url_env=(-e FLOWCATALYST_CONFIG_URL="http://$SINK_IP:9000/config")
    fi

    # SQL pre-seeding needs to know the queue_name(s) the router will poll for — for the
    # config-URL path that's ours (BENCH-1..BENCH-$QUEUES, set in the sink's /config); for the
    # synthesised DEFAULT-POOL path (POOL_CONCURRENCY=0) it's whatever each image's
    # default-broker bootstrap picks (Go: literal "default"; Java: the full postgres:// URI —
    # see RESULTS.md), which SQL-seeding doesn't know without per-image branching, so that path
    # stays on the API seeder regardless of SEED_VIA.
    effective_seed_via=$SEED_VIA
    [ "$POOL_CONCURRENCY" = 0 ] && effective_seed_via=api
    if [ "$effective_seed_via" = sql ]; then
      total=$TOTAL_MESSAGES
      local t_seed0; t_seed0=$(python3 -c 'import time;print(time.time())')
      seed_sql "$total" "$nqueues" "$pool_code" "http://$SINK_IP:9000/hook"
      local sql_seed_wall; sql_seed_wall=$(python3 -c "import time;print(round(time.time()-$t_seed0,2))")
      echo "-- seeded $total rows by SQL across $nqueues queue(s) in ${sql_seed_wall}s (queue full BEFORE the router starts)"
      # per-queue distribution, for the record (also proves the round-robin actually spread rows)
      docker exec $PG psql -U pg -d rt -c "SELECT queue_name, count(*) FROM queue_messages GROUP BY queue_name ORDER BY queue_name;"
    else
      total=$((SEED_CALLS * SEED_COUNT))
    fi
  fi

  # (b) fresh sink — always serves /config (POOL_CONCURRENCY/QUEUES/QUEUE_URI) and /hook (SINK_H2C).
  docker run -d --name "$kname" --network $NET --ip $SINK_IP --cpuset-cpus=2-9 \
      -e POOL_CONCURRENCY="$POOL_CONCURRENCY" -e QUEUES="$nqueues" -e QUEUE_URI="$queue_uri" -e SINK_H2C="$SINK_H2C" \
      ${sink_envs[@]+"${sink_envs[@]}"} bench-router-sink >/dev/null
  for i in $(seq 1 50); do probe -o /dev/null -w '%{http_code}' "http://$SINK_IP:9000/stats" 2>/dev/null | grep -q 200 && break; sleep 0.2; done

  # (c) server, router-only. FC_DEFAULT_BROKER=postgres is kept even with a config URL: the
  # Java port needs it to open the database at all (Main.java's needsDb gate — see RESULTS.md);
  # Go does NOT need it once FLOWCATALYST_CONFIG_URL is set (internal/server/run.go: the
  # default-broker synthesis is gated on `RouterConfigURL == "" && DefaultBroker == "postgres"`,
  # so a non-empty config URL skips it regardless of DefaultBroker) — kept anyway for symmetry.
  local fixed=(-e FC_PLATFORM_ENABLED=false -e FC_ROUTER_ENABLED=true -e FC_DEFAULT_BROKER=postgres
      -e FC_DATABASE_URL="postgresql://pg:pg@$PG_IP:5432/rt" -e FC_API_PORT=8080 -e FC_METRICS_PORT=9090
      -e FC_SCHEDULER_ENABLED=false -e FC_SCHEDULED_JOB_ENABLED=false -e FC_STREAM_PROCESSOR_ENABLED=false
      -e FC_OUTBOX_ENABLED=false -e FC_MCP_ENABLED=false -e FC_STANDBY_ENABLED=false
      -e FC_ROUTER_AUTH_USER=bench -e FC_ROUTER_AUTH_PASS=bench
      ${config_url_env[@]+"${config_url_env[@]}"})
  # BROKER=sqs: point the AWS SDK's default resolution chain at LocalStack. Both AWS_ENDPOINT_URL
  # and the service-specific AWS_ENDPOINT_URL_SQS are set (belt and braces — the coordinator's
  # instruction); FC_DATABASE_URL/FC_DEFAULT_BROKER above are inert here (no needsDb trigger —
  # see RESULTS.md — and the config URL is what actually drives the router either way), left in
  # place only because stripping them for one broker and not the other isn't worth the diff.
  if [ "$BROKER" = sqs ]; then
    fixed+=(-e AWS_ENDPOINT_URL_SQS="http://$LOCALSTACK_IP:4566" -e AWS_ENDPOINT_URL="http://$LOCALSTACK_IP:4566"
        -e AWS_ACCESS_KEY_ID=test -e AWS_SECRET_ACCESS_KEY=test -e AWS_REGION=us-east-1)
  fi
  # PG_CPUSET (pinned-core mode): move whichever container is acting as the broker off the
  # router's pinned core(s) before the router starts — $PG always (postgres is also the
  # bench/real Postgres, worth pinning away regardless of BROKER=), plus $LOCALSTACK/$NATS
  # when this run actually started them. docker update on a container that isn't running
  # errors; suppressed, since not every broker is present on every run.
  if [ -n "${PG_CPUSET:-}" ]; then
    for c in $PG $LOCALSTACK $NATS; do docker update --cpuset-cpus="$PG_CPUSET" "$c" >/dev/null 2>&1; done
  fi
  # bench-real-java's image now carries --enable-preview --enable-native-access=ALL-UNNAMED
  # on its own ENTRYPOINT (orchestrator rebuild); no JAVA_TOOL_OPTIONS workaround needed here.
  docker run -d --name "$sname" --network $NET --ip $SERVER_IP $cpuargs \
      ${fixed[@]+"${fixed[@]}"} ${server_envs[@]+"${server_envs[@]}"} "$image" >/dev/null

  # (d) health, either auth mode (diagnostic only: /health's public-path status is separate
  # from mutating endpoints below, which §9.7 does NOT list as public, so always send auth there).
  # This is the drain window's t0: the queue is already full (SQL mode) or about to be filled
  # (API debug mode) — either way, everything from here on is what gets measured.
  local authmode; authmode=$(wait_health $SERVER_IP) || fail "health wait ($SERVER_IP:8080/router/health)"
  local authflag=(-u bench:bench)
  local snap_before; snap_before=$(snap "$sname")

  if [ "$effective_seed_via" = api ]; then
    # (e) debug path: SEED_CALLS x POST /router/api/seed/messages, count=SEED_COUNT each.
    # Note this folds seeding into the measured window below — it's for debugging the rig,
    # not for reading drain numbers off of; SEED_VIA=sql (default) keeps them separate.
    local published=0 i seedbody resp
    seedbody="{\"pool_code\":\"$pool_code\",\"mediation_target\":\"http://$SINK_IP:9000/hook\",\"count\":$SEED_COUNT}"
    for i in $(seq 1 $SEED_CALLS); do
      resp=$(probe ${authflag[@]+"${authflag[@]}"} -X POST -H 'Content-Type: application/json' -d "$seedbody" "http://$SERVER_IP:8080/router/api/seed/messages")
      echo "$resp" | grep -q '"published"' || fail "seed call $i: $resp"
      local n; n=$(echo "$resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("published",0))' 2>/dev/null || echo 0)
      published=$((published + n))
    done
    [ "$published" -eq "$total" ] || echo "WARNING: seeded $published, expected $total" >&2
  fi

  # poll sink /stats every 500ms until count>=total or TIMEOUT_S; sample docker stats/1s meanwhile
  # (the server AND the broker container — $PG or $LOCALSTACK — one line each per second, so a
  # broker-bound drain shows up too — a poll-heavy queue can put more load on the broker than on
  # the router itself).
  local brokerc=$PG
  [ "$BROKER" = sqs ] && brokerc=$LOCALSTACK
  [ "$BROKER" = nats ] && brokerc=$NATS
  local statsfile="$out/.$label.mem.tmp" brokerstatsfile="$out/.$label.brokercpu.tmp"
  : > "$statsfile"; : > "$brokerstatsfile"
  ( while :; do
      docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' "$sname" 2>/dev/null >> "$statsfile"
      docker stats --no-stream --format '{{.CPUPerc}}' "$brokerc" 2>/dev/null >> "$brokerstatsfile"
      sleep 1
    done ) &
  local sampler_pid=$!
  local t_poll0; t_poll0=$(python3 -c 'import time;print(time.time())')
  local count=0 elapsed=0
  while :; do
    local stats; stats=$(probe "http://$SINK_IP:9000/stats" 2>/dev/null)
    count=$(echo "$stats" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count",0))' 2>/dev/null || echo 0)
    elapsed=$(python3 -c "import time;print(time.time()-$t_poll0)")
    [ "$count" -ge "$total" ] && break
    python3 -c "exit(0 if $elapsed < $TIMEOUT_S else 1)" || { echo "WARNING: timed out at count=$count/$total after ${TIMEOUT_S}s" >&2; break; }
    sleep 0.5
  done

  # The sink recording a hit and the router's own ACK (a Postgres DELETE, or an SQS
  # DeleteMessage) reaching the broker are two separate events — under load the ack can lag
  # noticeably behind the HTTP response the sink already counted. The drain isn't actually done
  # until the broker is empty, not just until the sink has seen everything, so keep polling the
  # broker's own depth too (its own short grace window: DRAIN_GRACE_S, default 30s) before
  # declaring completion and tearing the server down — killing the container abandons any ack
  # still in flight, permanently.
  local broker_remaining=unknown grace_elapsed=0
  local t_grace0; t_grace0=$(python3 -c 'import time;print(time.time())')
  while :; do
    if [ "$BROKER" = sqs ]; then
      broker_remaining=$(sqs_queue_depth "$nqueues")
    elif [ "$BROKER" = nats ]; then
      broker_remaining=$(nats_queue_depth "$nqueues")
    else
      broker_remaining=$(docker exec $PG psql -U pg -d rt -tAc "SELECT count(*) FROM queue_messages;" 2>/dev/null | tr -d '[:space:]')
    fi
    [ "$broker_remaining" = "0" ] && break
    grace_elapsed=$(python3 -c "import time;print(time.time()-$t_grace0)")
    python3 -c "exit(0 if $grace_elapsed < ${DRAIN_GRACE_S:-30} else 1)" \
      || { echo "WARNING: broker depth=$broker_remaining after sink completion + ${DRAIN_GRACE_S:-30}s grace (abandoned in-flight acks, not lost messages — the sink already counted them)" >&2; break; }
    sleep 0.5
  done

  kill $sampler_pid >/dev/null 2>&1; wait $sampler_pid 2>/dev/null
  local drain_time; drain_time=$(python3 -c "import time;print(round(time.time()-$t_poll0,2))")

  local snap_after; snap_after=$(snap "$sname")
  local switches; switches=$(switch_delta "$snap_before" "$snap_after")

  # final stats + per-proto counts
  local final; final=$(probe "http://$SINK_IP:9000/stats" 2>/dev/null)
  echo "$final" > "$out/$label.sink-stats.json"

  # (f) prometheus metrics
  probe ${authflag[@]+"${authflag[@]}"} "http://$SERVER_IP:8080/router/metrics" > "$out/$label.metrics.prom" 2>/dev/null

  # pools API — confirms the router actually picked up the config-URL pool (§9.1 /monitoring/pools)
  probe ${authflag[@]+"${authflag[@]}"} "http://$SERVER_IP:8080/router/monitoring/pools" > "$out/$label.pools.json" 2>/dev/null
  local pool_seen; pool_seen=$(python3 - "$out/$label.pools.json" "$pool_code" <<'PY'
import json, sys
try:
    pools = json.load(open(sys.argv[1]))
except Exception:
    print("unknown"); sys.exit()
code = sys.argv[2]
for p in pools if isinstance(pools, list) else []:
    if p.get("pool_code") == code or p.get("poolCode") == code or p.get("code") == code:
        print(f"yes(concurrency={p.get('concurrency', p.get('max_concurrency', '?'))})")
        sys.exit()
print("no")
PY
)

  # memory + cpu summary from the sampled docker stats lines "CPU% MEM / LIMIT"
  local memcpu; memcpu=$(python3 - "$statsfile" <<'PY'
import sys, re
maxmb = 0.0; endmb = 0.0; cpus = []
unit = {'b':1/1e6,'kib':1/1024,'mib':1,'gib':1024,'kb':1/1000,'mb':1,'gb':1000}
for line in open(sys.argv[1]):
    line = line.strip()
    if not line: continue
    parts = line.split()
    if len(parts) < 2: continue
    cpu = parts[0].rstrip('%')
    try: cpus.append(float(cpu))
    except ValueError: pass
    m = re.match(r'([\d.]+)([A-Za-z]+)', parts[1])
    if m:
        val, u = float(m.group(1)), m.group(2).lower()
        mb = val * unit.get(u, 1)
        endmb = mb
        maxmb = max(maxmb, mb)
mean_cpu = sum(cpus)/len(cpus) if cpus else 0.0
print(f"{maxmb:.1f} {endmb:.1f} {mean_cpu:.1f}")
PY
)
  rm -f "$statsfile"
  read -r max_rss end_rss mean_cpu <<<"$memcpu"

  # The broker container's mean CPU% over the same drain window — is the broker itself
  # (Postgres, or LocalStack emulating SQS) the bottleneck, rather than the router?
  local broker_mean_cpu; broker_mean_cpu=$(python3 - "$brokerstatsfile" <<'PY'
import sys
cpus = []
for line in open(sys.argv[1]):
    line = line.strip().rstrip('%')
    if not line: continue
    try: cpus.append(float(line))
    except ValueError: pass
print(f"{(sum(cpus)/len(cpus) if cpus else 0.0):.1f}")
PY
)
  rm -f "$brokerstatsfile"

  local first_ns last_ns scount protoc
  first_ns=$(echo "$final" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("first_ns",0))' 2>/dev/null || echo 0)
  last_ns=$(echo "$final" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("last_ns",0))' 2>/dev/null || echo 0)
  scount=$(echo "$final" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count",0))' 2>/dev/null || echo 0)
  protoc=$(echo "$final" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("proto_counts",{}))' 2>/dev/null || echo '{}')
  local rate; rate=$(python3 -c "
fn=$first_ns; ln=$last_ns
print(round($scount/((ln-fn)/1e9),1) if ln>fn else 0)
")
  local switches_per; switches_per=$(python3 -c "print(round($switches/max($scount,1),3))")

  local broker_line
  if [ "$BROKER" = sqs ]; then
    broker_line="queue_depth_end=$broker_remaining localstack_mean_cpu_pct=$broker_mean_cpu"
  elif [ "$BROKER" = nats ]; then
    broker_line="queue_depth_end=$broker_remaining nats_mean_cpu_pct=$broker_mean_cpu"
  else
    broker_line="queue_remaining=$broker_remaining pg_mean_cpu_pct=$broker_mean_cpu"
  fi

  {
    echo "== $label image=$image cpu='$cpuargs' env='$*' authmode=$authmode broker=$BROKER pool_code=$pool_code pool_concurrency=$POOL_CONCURRENCY queues=$nqueues pool_seen=$pool_seen seed_via=$effective_seed_via"
    echo "   total_messages=$total delivered=$scount deliveries_per_s=$rate drain_time_s=$drain_time $broker_line"
    echo "   max_rss_mb=$max_rss end_rss_mb=$end_rss mean_cpu_pct=$mean_cpu context_switches=$switches switches_per_delivery=$switches_per"
    echo "   proto_counts=$protoc"
  } | tee "$out/$label.log"

  docker logs "$sname" > "$out/$label.server.log" 2>&1

  if [ "${KEEP:-0}" != 1 ]; then
    docker rm -f "$sname" "$kname" $PROBER >/dev/null 2>&1
    [ "$BROKER" = sqs ] && docker rm -f $AWSPROBER $LOCALSTACK >/dev/null 2>&1
    [ "$BROKER" = nats ] && docker rm -f $NATSBOX $NATS >/dev/null 2>&1
  fi
}

case ${1:-} in
  prepare) prepare ;;
  run) shift; run "$@" ;;
  *) echo "usage: $0 prepare | run <label> <image> <cpu-args> [ENV=value ...]"; exit 2 ;;
esac
