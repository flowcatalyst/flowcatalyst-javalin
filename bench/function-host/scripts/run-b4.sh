#!/usr/bin/env bash
# B4 — noisy neighbour (docs/spec/function-host-benchmark.md): function A's
# /io held at a fixed 500 req/s while function B hammers /cpu flat out at
# its own maxConcurrency (8), both loaded on the SAME --cpus 2 host, one
# desired-state document with per-entry maxConcurrency (FakePlatform's
# `overrideIndex`/`overrideMaxConcurrency` admin params — see
# fake-platform/FakePlatform.java). Reports A's /io p50/p99 with and
# without B running.
#
# A's fixed-rate load uses RateClient.java (open-loop-ish: schedules one
# request per 1/rate seconds on a virtual thread each, not wrk's
# closed-loop-at-concurrency model) since wrk cannot hold an exact req/s
# rate independent of response latency.
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
ARTIFACTS=$HERE/artifacts
FP_PORT=8091
FP_URL="http://127.0.0.1:$FP_PORT"
HOST_PORT=18080
METRICS_PORT=19090
IMAGE=fnhost-bench-tools
CONTAINER=fnhost-b4
OUT=${1:-$HERE/results}
mkdir -p "$OUT"
JAVA_HOME=${JAVA_HOME:-$(mise where java)}

log() { echo "[run-b4] $*" >&2; }

ensure_fake_platform() {
  if ! curl -fs -o /dev/null "$FP_URL/admin/status" 2>/dev/null; then
    nohup "$JAVA_HOME/bin/java" "$HERE/fake-platform/FakePlatform.java" "$FP_PORT" "$ARTIFACTS" /artifacts \
      > "$HERE/results/fake-platform.log" 2>&1 &
    disown
    for i in $(seq 1 30); do curl -fs -o /dev/null "$FP_URL/admin/status" 2>/dev/null && break; sleep 0.5; done
  fi
}

wait_ready() {
  for i in $(seq 1 120); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$METRICS_PORT/ready" 2>/dev/null)
    [ "$code" = "200" ] && return 0
    sleep 0.5
  done
  return 1
}

start_container() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1
  docker run -d --name "$CONTAINER" --memory=2g --cpus=2 \
    -e FC_FN_PLATFORM_URL=http://host.docker.internal:$FP_PORT \
    -e FC_FN_CLIENT_ID=bench -e FC_FN_CLIENT_SECRET=bench \
    -e FC_FN_SIGNATURES=off -e FLOWCATALYST_DEV_MODE=true \
    -e FC_FN_MAX_LOADED=50 -e FC_FN_POOL=default \
    -e FC_FN_MAX_CONCURRENCY=4000 \
    -v "$ARTIFACTS:/artifacts:ro" \
    -p $HOST_PORT:8080 -p $METRICS_PORT:9090 \
    "$IMAGE" >/dev/null
  wait_ready
}

run_rate_client() {
  local url=$1 rate=$2 seconds=$3 out=$4
  "$JAVA_HOME/bin/java" "$HERE/scripts/RateClient.java" "$url" "$rate" "$seconds" > "$out" 2>&1
}

main() {
  ensure_fake_platform
  # A = f000 unconstrained (maxConcurrency=2000, the document default); B =
  # f001 overridden to maxConcurrency=8 (B4's own number).
  curl -fs -o /dev/null -X POST "$FP_URL/admin/config?fixture=lean&n=2&warm=2&maxConcurrency=2000&maxDurationMs=30000&overrideIndex=1&overrideMaxConcurrency=8"
  start_container || { log "container never became ready"; docker logs "$CONTAINER" 2>&1 | tail -40; docker rm -f "$CONTAINER" >/dev/null 2>&1; exit 1; }

  local urlA="http://127.0.0.1:$HOST_PORT/functions/bench.lean.f000/io"
  local urlB="http://127.0.0.1:$HOST_PORT/functions/bench.lean.f001/cpu"

  log "baseline: A alone at 500req/s for 20s"
  run_rate_client "$urlA" 500 20 "$OUT/b4-a-alone.log"
  cat "$OUT/b4-a-alone.log"

  log "starting B (virtual-thread hammer, 16 callers so ~8 stay admitted after 429s, flat out, 25s)"
  "$JAVA_HOME/bin/java" "$HERE/scripts/Hammer.java" "$urlB" 16 25 > "$OUT/b4-hammer.log" 2>&1 &
  sleep 2   # let B ramp up before measuring A

  log "measuring: A at 500req/s for 20s WHILE B runs"
  ( sleep 8; docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' "$CONTAINER" > "$OUT/b4-cpu-sample.log" ) &
  run_rate_client "$urlA" 500 20 "$OUT/b4-a-with-b.log"
  cat "$OUT/b4-a-with-b.log"

  wait
  log "container CPU% during B's flat-out phase: $(cat "$OUT/b4-cpu-sample.log" 2>/dev/null)"

  docker logs "$CONTAINER" > "$OUT/b4.server.log" 2>&1
  docker rm -f "$CONTAINER" >/dev/null 2>&1
}

main "$@"
