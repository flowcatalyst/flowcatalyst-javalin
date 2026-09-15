#!/usr/bin/env bash
# Plan docs/java-parity-plan.md §3 L7 / §7 "Loop" input: touch one file on each side and time
# a release build from a warm target/build cache — the incremental-compile loop a developer
# actually feels, not a clean build. Run each side TWICE and read the second number: the first
# run after a fresh clone (or after `cargo clean`/`mvn clean`) times a full build, not an
# incremental one.
#
#   RUST_SRC=<path-to-flowcatalyst-rust checkout, already built at least once> \
#       bench/real/incremental-build.sh
#
# Rust: `touch crates/fc-platform/src/lib.rs && cargo build --release` (plan's own wording,
# §3 L7) — the whole workspace, matching the Java side building its whole reactor below.
# Java: touches one file under server/src/main/java and runs `mvn -q -o -DskipTests package`
# from server/ (the "closest documented command" the plan asks for when there's no single
# canonical incremental-build script in this repo yet; -o assumes deps are already resolved
# locally, which is what "incremental" means here — a from-scratch `mvn` run with an empty
# ~/.m2 times dependency resolution, not compilation).
set -u
here=$(cd "$(dirname "$0")" && pwd); root=$(cd "$here/../.." && pwd)
out=$here/results; mkdir -p "$out"

now() { python3 -c 'import time;print(time.time())'; }
elapsed() { python3 -c "print(round($2-$1,2))"; }

echo "== incremental build loop (docs/java-parity-plan.md §7 'Loop') =="

if [ -n "${RUST_SRC:-}" ] && [ -f "$RUST_SRC/crates/fc-platform/src/lib.rs" ]; then
  touch "$RUST_SRC/crates/fc-platform/src/lib.rs"
  t0=$(now)
  ( cd "$RUST_SRC" && cargo build --release ) >"$out/incremental-rust.log" 2>&1
  rc=$?
  t1=$(now)
  echo "rust: touch crates/fc-platform/src/lib.rs && cargo build --release -> $(elapsed $t0 $t1)s (rc=$rc, log=$out/incremental-rust.log, RUST_SRC=$RUST_SRC)"
else
  echo "rust: skipped (set RUST_SRC=<path-to-flowcatalyst-rust checkout, already built once>)"
fi

javafile="$root/server/src/main/java/io/flowcatalyst/outbox/OutboxItem.java"
if [ -f "$javafile" ]; then
  touch "$javafile"
  t0=$(now)
  ( cd "$root/server" && mvn -q -o -DskipTests package ) >"$out/incremental-java.log" 2>&1
  rc=$?
  t1=$(now)
  echo "java: touch ${javafile#$root/} && (cd server && mvn -q -o -DskipTests package) -> $(elapsed $t0 $t1)s (rc=$rc, log=$out/incremental-java.log)"
else
  echo "java: skipped ($javafile not found)"
fi
