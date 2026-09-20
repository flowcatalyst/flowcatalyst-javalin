#!/usr/bin/env bash
# B1 — memory per loaded function (docs/spec/function-host-benchmark.md).
# One measured point per invocation of run_point(): starts the REAL image
# fresh (fnhost-bench-tools — see bench/function-host/Dockerfile.bench's own
# header for the one difference from function-host/Dockerfile: +jdk.jcmd,
# jdk.attach, bench tooling only), points it at N desired-state entries
# (built by fake-platform/FakePlatform.java from the pre-stamped, DISTINCT
# jars gen-artifacts.sh produced), invokes each once, forces a GC, and
# records RSS / heap / metaspace / class count / thread count.
#
# Usage: run-b1.sh <results-csv>
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)   # bench/function-host
ROOT=$(cd "$HERE/../.." && pwd)
ARTIFACTS=$HERE/artifacts
CSV=${1:-$HERE/results/b1.csv}
FP_PORT=8091
FP_URL="http://127.0.0.1:$FP_PORT"
HOST_PORT=18080
METRICS_PORT=19090
IMAGE=fnhost-bench-tools
CONTAINER=fnhost-b1

mkdir -p "$(dirname "$CSV")"
if [ ! -f "$CSV" ]; then
  echo "timestamp,fixture,mem_limit,cpus,n,warm,invoked,rss_samples,rss_bytes,mem_limit_bytes,heap_used_bytes,metaspace_used_bytes,compressed_class_used_bytes,nonheap_used_bytes,thread_count,loaded_class_count,jcmd_chunk_bytes,jcmd_block_bytes,fc_fn_loaded,fc_fn_warm,notes" > "$CSV"
fi

log() { echo "[run-b1] $*" >&2; }

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

mem_limit_bytes() {
  case "$1" in
    2g) echo 2147483648 ;;
    4g) echo 4294967296 ;;
    *) echo 0 ;;
  esac
}

# run_point <fixture> <n> <warm> <mem> <cpus> <notes>
run_point() {
  local fixture=$1 n=$2 warm=$3 mem=$4 cpus=$5 notes=${6:-}
  log "== fixture=$fixture n=$n warm=$warm cpus=$cpus mem=$mem notes=$notes"

  curl -fs -o /dev/null -X POST "$FP_URL/admin/config?fixture=$fixture&n=$n&warm=$warm&maxConcurrency=2000&maxDurationMs=30000"

  docker rm -f "$CONTAINER" >/dev/null 2>&1
  docker run -d --name "$CONTAINER" --memory="$mem" --cpus="$cpus" \
    -e FC_FN_PLATFORM_URL=http://host.docker.internal:$FP_PORT \
    -e FC_FN_CLIENT_ID=bench -e FC_FN_CLIENT_SECRET=bench \
    -e FC_FN_SIGNATURES=off -e FLOWCATALYST_DEV_MODE=true \
    -e FC_FN_MAX_LOADED=$((n + 50)) -e FC_FN_POOL=default \
    -v "$ARTIFACTS:/artifacts:ro" \
    -p $HOST_PORT:8080 -p $METRICS_PORT:9090 \
    "$IMAGE" >/dev/null

  local ready=0
  for i in $(seq 1 120); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$METRICS_PORT/ready" 2>/dev/null)
    [ "$code" = "200" ] && { ready=1; break; }
    sleep 0.5
  done
  if [ "$ready" = 1 ] && [ "$warm" -gt 0 ]; then
    # /ready=200 only means the FIRST reconcile attempt was applied — for a
    # large all-warm desired-state document (esp. "typical", metaspace-heavy),
    # the warm-loading loop can still be running well after that (observed
    # directly: RSS samples 1.5s apart varying by hundreds of MB at n=200
    # typical — the container was still loading functions under our feet).
    # Poll fc_fn_loaded until it stops changing for 3 consecutive 2s checks,
    # capped at 90s, before treating the set as actually loaded.
    local prev=-1 stable=0
    for i in $(seq 1 45); do
      local cur
      cur=$(curl -s "http://127.0.0.1:$METRICS_PORT/metrics" | awk -F' ' '/^fc_fn_loaded /{print $NF}')
      if [ "$cur" = "$prev" ]; then
        stable=$((stable + 1))
        [ "$stable" -ge 3 ] && break
      else
        stable=0
      fi
      prev=$cur
      sleep 2
    done
    log "  fc_fn_loaded settled at $prev after warm-load wait"
  fi
  if [ "$ready" != 1 ]; then
    log "  NEVER READY — see docker logs $CONTAINER"
    docker logs "$CONTAINER" 2>&1 | tail -30 >&2
    echo "$(date -u +%FT%TZ),$fixture,$mem,$cpus,$n,$warm,0,,,,,,,,,,,,,,NEVER_READY" >> "$CSV"
    docker rm -f "$CONTAINER" >/dev/null 2>&1
    return 1
  fi

  # one invocation each — parallel batches of 20 to keep this fast without
  # pretending to be a throughput test (that's B3).
  local invoked=0
  if [ "$n" -gt 0 ]; then
    seq -w 0 $((n - 1)) | xargs -P 20 -I{} curl -s -o /dev/null "http://127.0.0.1:$HOST_PORT/functions/bench.$fixture.f{}/x"
    invoked=$n
  fi

  # forced GC (bench-tools image only — see Dockerfile.bench). G1 does not
  # reliably hand pages back to the OS after a single cycle, so this runs
  # twice with settle time, then RSS is sampled three times 1.5s apart and
  # the MEDIAN is recorded — docker stats on a freshly-settled container is
  # otherwise noisy by tens of MB run to run (observed directly: two
  # consecutive n=0 points varied by ~40MB on a single sample each).
  docker exec "$CONTAINER" jcmd 1 GC.run >/dev/null 2>&1
  sleep 1.5
  docker exec "$CONTAINER" jcmd 1 GC.run >/dev/null 2>&1
  sleep 2

  local s1 s2 s3
  s1=$(to_bytes "$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" | awk -F' / ' '{print $1}')")
  sleep 1.5
  s2=$(to_bytes "$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" | awk -F' / ' '{print $1}')")
  sleep 1.5
  s3=$(to_bytes "$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" | awk -F' / ' '{print $1}')")
  local rss_bytes rss_human
  rss_bytes=$(printf '%s\n%s\n%s\n' "$s1" "$s2" "$s3" | sort -n | sed -n '2p')
  # No commas: this is a CSV field, and the raw samples are their own columns.
  rss_human="median;samples=${s1}|${s2}|${s3}"

  local metrics heap meta ccs nonheap threads fc_loaded fc_warm
  metrics=$(curl -s "http://127.0.0.1:$METRICS_PORT/metrics")
  heap=$(echo "$metrics" | awk -F'[ }]' '/^jvm_memory_used_bytes\{area="heap"\}/{print $NF}')
  nonheap=$(echo "$metrics" | awk -F'[ }]' '/^jvm_memory_used_bytes\{area="nonheap"\}/{print $NF}')
  meta=$(echo "$metrics" | awk -F' ' '/^jvm_memory_pool_used_bytes\{pool="Metaspace"\}/{print $NF}')
  ccs=$(echo "$metrics" | awk -F' ' '/^jvm_memory_pool_used_bytes\{pool="Compressed Class Space"\}/{print $NF}')
  threads=$(echo "$metrics" | awk -F' ' '/^jvm_threads_current /{print $NF}')
  fc_loaded=$(echo "$metrics" | awk -F' ' '/^fc_fn_loaded /{print $NF}')
  fc_warm=$(echo "$metrics" | awk -F' ' '/^fc_fn_warm /{print $NF}')

  local cls_stats total_line loaded_classes chunk_bytes block_bytes
  cls_stats=$(docker exec "$CONTAINER" jcmd 1 VM.classloader_stats 2>/dev/null)
  # "Total = <loaderCount>   <classes> <chunkBytes> <blockBytes>" — $3 is the
  # loader count (not what we want), $4/$5/$6 are the classes/chunk/block totals.
  total_line=$(echo "$cls_stats" | awk '/^Total = /{print}')
  loaded_classes=$(echo "$total_line" | awk '{print $4}')
  chunk_bytes=$(echo "$total_line" | awk '{print $5}')
  block_bytes=$(echo "$total_line" | awk '{print $6}')

  local limit_bytes; limit_bytes=$(mem_limit_bytes "$mem")

  echo "$(date -u +%FT%TZ),$fixture,$mem,$cpus,$n,$warm,$invoked,$rss_human,$rss_bytes,$limit_bytes,$heap,$meta,$ccs,$nonheap,$threads,$loaded_classes,$chunk_bytes,$block_bytes,$fc_loaded,$fc_warm,$notes" >> "$CSV"
  log "  rss=$rss_human meta=${meta}B classes=$loaded_classes threads=$threads"

  docker logs "$CONTAINER" > "$HERE/results/${fixture}-n${n}-w${warm}-${mem}.server.log" 2>&1
  docker rm -f "$CONTAINER" >/dev/null 2>&1
}

# Converts docker stats' human MemUsage (e.g. "512MiB", "1.2GiB", "800KiB") to bytes.
to_bytes() {
  local v=$1
  python3 - "$v" <<'PY'
import sys
s = sys.argv[1].strip()
units = {"B":1, "KiB":1024, "MiB":1024**2, "GiB":1024**3}
for u in ("GiB","MiB","KiB","B"):
    if s.endswith(u):
        num = float(s[:-len(u)])
        print(int(num * units[u]))
        break
else:
    print(0)
PY
}

main() {
  ensure_fake_platform

  local points=${POINTS:-"0 25 50 100 200"}
  local fixtures=${FIXTURES:-"lean typical"}
  local mems=${MEMS:-"2g 4g"}
  local cpus=${CPUS:-2}

  for mem in $mems; do
    for fixture in $fixtures; do
      for n in $points; do
        run_point "$fixture" "$n" "$n" "$mem" "$cpus" "all-warm-sweep"
      done
    done
  done
}

main "$@"
