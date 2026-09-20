#!/usr/bin/env bash
# B3 — steady-state throughput/p99 (docs/spec/function-host-benchmark.md).
# One warm lean function; closed-loop load with `wrk` (already on this
# machine — `which wrk`) run as a HOST process (not container-pinned: this
# machine is Docker Desktop for Mac, whose Linux VM shares the host's
# logical CPUs with any host process, including wrk — there is no cgroup
# boundary to pin wrk away from that is meaningfully different from "the
# same physical cores, different scheduling domain"; recorded here rather
# than pretended away). 30s warm-up + 60s measured, per docs/spec's own
# numbers.
#
# Usage: run-b3.sh <endpoint: io|cpu> <concurrency> <host-cpus> <results-dir>
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
ARTIFACTS=$HERE/artifacts
FP_PORT=8091
FP_URL="http://127.0.0.1:$FP_PORT"
HOST_PORT=18080
METRICS_PORT=19090
IMAGE=fnhost-bench-tools
CONTAINER=fnhost-b3

ENDPOINT=${1:?usage: run-b3.sh io|cpu concurrency host-cpus results-dir}
CONC=${2:?concurrency}
HCPUS=${3:?host cpus}
OUT=${4:-$HERE/results}
mkdir -p "$OUT"

log() { echo "[run-b3] $*" >&2; }

ensure_fake_platform() {
  if ! curl -fs -o /dev/null "$FP_URL/admin/status" 2>/dev/null; then
    log "starting FakePlatform on :$FP_PORT"
    JAVA_HOME=${JAVA_HOME:-$(mise where java)}
    nohup "$JAVA_HOME/bin/java" "$HERE/fake-platform/FakePlatform.java" "$FP_PORT" "$ARTIFACTS" /artifacts \
      > "$HERE/results/fake-platform.log" 2>&1 &
    disown
    for i in $(seq 1 30); do curl -fs -o /dev/null "$FP_URL/admin/status" 2>/dev/null && break; sleep 0.5; done
  fi
}

metric() {
  # metric <name> <metrics-text>
  echo "$2" | awk -v n="$1" '$0 ~ "^"n" "{print $NF}'
}

sum_busy() {
  # sums fc_fn_invocations_total{...,outcome="busy"} across every label combo
  echo "$1" | awk -F' ' '/^fc_fn_invocations_total\{.*outcome="busy"/{s+=$NF} END{print s+0}'
}

sum_gc_pause() {
  echo "$1" | awk -F' ' '/^jvm_gc_collection_seconds_sum/{s+=$NF} END{print s+0}'
}

main() {
  ensure_fake_platform
  curl -fs -o /dev/null -X POST "$FP_URL/admin/config?fixture=lean&n=1&warm=1&maxConcurrency=2000&maxDurationMs=30000"

  docker rm -f "$CONTAINER" >/dev/null 2>&1
  docker run -d --name "$CONTAINER" --memory=2g --cpus="$HCPUS" \
    -e FC_FN_PLATFORM_URL=http://host.docker.internal:$FP_PORT \
    -e FC_FN_CLIENT_ID=bench -e FC_FN_CLIENT_SECRET=bench \
    -e FC_FN_SIGNATURES=off -e FLOWCATALYST_DEV_MODE=true \
    -e FC_FN_MAX_LOADED=50 -e FC_FN_POOL=default \
    -e FC_FN_MAX_CONCURRENCY=4000 \
    -v "$ARTIFACTS:/artifacts:ro" \
    -p $HOST_PORT:8080 -p $METRICS_PORT:9090 \
    "$IMAGE" >/dev/null

  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$METRICS_PORT/ready" 2>/dev/null)
    [ "$code" = "200" ] && break
    sleep 0.5
  done

  local url="http://127.0.0.1:$HOST_PORT/functions/bench.lean.f000/$ENDPOINT"
  log "warmup 30s: $url c=$CONC"
  wrk -t8 -c"$CONC" -d30s "$url" > "$OUT/b3-${ENDPOINT}-c${CONC}-cpu${HCPUS}.warmup.log" 2>&1

  local before after
  before=$(curl -s "http://127.0.0.1:$METRICS_PORT/metrics")
  local before_busy before_gc
  before_busy=$(sum_busy "$before")
  before_gc=$(sum_gc_pause "$before")

  log "measured 60s: $url c=$CONC cpus=$HCPUS"
  local t0 t1 cpu_pct
  t0=$(date +%s)
  # sample docker stats mid-run in the background
  ( sleep 25; docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' "$CONTAINER" >> "$OUT/b3-${ENDPOINT}-c${CONC}-cpu${HCPUS}.stats.log" ) &
  wrk -t8 -c"$CONC" -d60s --latency "$url" > "$OUT/b3-${ENDPOINT}-c${CONC}-cpu${HCPUS}.log" 2>&1
  t1=$(date +%s)
  wait

  after=$(curl -s "http://127.0.0.1:$METRICS_PORT/metrics")
  local after_busy after_gc
  after_busy=$(sum_busy "$after")
  after_gc=$(sum_gc_pause "$after")

  local busy_count gc_pause_s
  busy_count=$(echo "$after_busy - $before_busy" | bc)
  gc_pause_s=$(echo "$after_gc - $before_gc" | bc)
  cpu_pct=$(cat "$OUT/b3-${ENDPOINT}-c${CONC}-cpu${HCPUS}.stats.log" 2>/dev/null | awk '{print $1}')

  {
    echo "== B3 endpoint=$ENDPOINT concurrency=$CONC host_cpus=$HCPUS elapsed=$((t1-t0))s"
    grep -E 'Requests/sec|requests in|50%|90%|99%|Socket errors|Non-2xx' "$OUT/b3-${ENDPOINT}-c${CONC}-cpu${HCPUS}.log"
    echo "busy_429_count=$busy_count gc_pause_total_delta_s=$gc_pause_s cpu_pct_sample=$cpu_pct"
  } | tee "$OUT/b3-${ENDPOINT}-c${CONC}-cpu${HCPUS}.summary.log"

  docker logs "$CONTAINER" > "$OUT/b3-${ENDPOINT}-c${CONC}-cpu${HCPUS}.server.log" 2>&1
  docker rm -f "$CONTAINER" >/dev/null 2>&1
}

main "$@"
