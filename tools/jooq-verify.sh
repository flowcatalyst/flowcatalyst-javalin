#!/usr/bin/env bash
# CI check: the committed jOOQ code (server/src/main/java/io/flowcatalyst/db/generated)
# must match what the current Flyway migrations generate.
#
# Regenerates into a temp dir (embedded PostgreSQL + Migrator + jOOQ codegen)
# and diffs it against the committed tree. Exit 1 on drift; fix with
#   mvn -pl server -Pjooq-codegen process-test-classes
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG_DIR="io/flowcatalyst/db/generated"
COMMITTED="$ROOT/server/src/main/java/$PKG_DIR"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/jooq-verify.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

MVN_ARGS=(-q -B -pl server -Pjooq-codegen "-Djooq.target=$TMP" process-test-classes)
(cd "$ROOT" && mvn "${MVN_ARGS[@]}")

if diff -ru "$COMMITTED" "$TMP/$PKG_DIR"; then
  echo "jooq-verify: generated code is up to date."
else
  echo >&2
  echo "jooq-verify: committed jOOQ code differs from a fresh generation." >&2
  echo "  Regenerate with: mvn -pl server -Pjooq-codegen process-test-classes" >&2
  exit 1
fi
