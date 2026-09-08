#!/usr/bin/env bash
# Starts the real flowcatalyst app (router-only, internal port) plus the tiny path-rewriting
# proxy (proxy.mjs) that exposes it on the port/paths bench/router/run.sh expects. See
# proxy.mjs's header comment for why the proxy exists at all.
set -u
INTERNAL_ROUTER_PORT=${INTERNAL_ROUTER_PORT:-18080}
export ROUTER_PORT=$INTERNAL_ROUTER_PORT
export HOST=0.0.0.0

node /app/dist/index.cjs &
APP_PID=$!

PROXY_PORT=8080 UPSTREAM_PORT=$INTERNAL_ROUTER_PORT node /app/proxy.mjs &
PROXY_PID=$!

term() {
  kill -TERM "$APP_PID" "$PROXY_PID" 2>/dev/null
}
trap term TERM INT

wait -n "$APP_PID" "$PROXY_PID"
code=$?
term
wait "$APP_PID" "$PROXY_PID" 2>/dev/null
exit $code
