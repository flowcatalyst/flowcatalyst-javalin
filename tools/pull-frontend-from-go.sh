#!/usr/bin/env bash
# Pull `frontend/` changes from the Go repo into this repo, for when the Go
# copy moves first (both repos hold the SPA source for now — see
# docs/go-mirror/2026-09-14-frontend-source-shared.md). rsync's --delete is guarded
# by the same excludes as tools/frontend-drift.sh, so embed.go/handler.go
# (deleted here on purpose — Java's equivalent is
# server/src/main/java/io/flowcatalyst/server/Frontend.java) do not come
# back, and generated/build artefacts never round-trip either way.
#
# The reverse direction (Java → Go, for when the Java copy moves first) is
# the same rsync with source and destination swapped, run from the Go repo
# by its owner:
#   rsync -a --delete <excludes> ../flowcatalyst-javalin/frontend/ ./frontend/
#
# Usage: tools/pull-frontend-from-go.sh [path-to-flowcatalyst-go]   (default ../flowcatalyst-go)
set -euo pipefail
JAVA_REPO="$(cd "$(dirname "$0")/.." && pwd)"
GO_REPO="${1:-$JAVA_REPO/../flowcatalyst-go}"

EXCLUDES=(
	--exclude=node_modules
	--exclude=dist
	--exclude=embed.go
	--exclude=handler.go
	--exclude='*.tsbuildinfo'
	--exclude=components.d.ts
	--exclude=vite.config.d.ts
	--exclude=.vite
	--exclude=.DS_Store
)

echo ">> pulling $GO_REPO/frontend into $JAVA_REPO/frontend"
rsync -a --delete "${EXCLUDES[@]}" "$GO_REPO/frontend/" "$JAVA_REPO/frontend/"
echo ">> done — re-run tools/frontend-drift.sh to confirm"
