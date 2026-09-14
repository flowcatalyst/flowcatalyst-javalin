#!/usr/bin/env bash
# Compare this repo's `frontend/` against the Go repo's `frontend/` and
# report drift. Both repos hold the SPA source for now (git subtree, since
# 2026-09-14 — docs/go-mirror/2026-09-14-frontend-source-shared.md); this is the
# check that they stay diffable.
#
# Usage: tools/frontend-drift.sh [path-to-flowcatalyst-go]   (default ../flowcatalyst-go)
# Exit 0 if identical (modulo the excludes below), 1 if any file differs.
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

set +e
diff -rq "${EXCLUDES[@]}" "$JAVA_REPO/frontend" "$GO_REPO/frontend"
status=$?
set -e

# diff exits 2 on trouble (e.g. the Go repo isn't there) — let that surface
# as-is rather than being reported as "1 file differs".
if [ "$status" -eq 2 ]; then
	exit 2
elif [ "$status" -ne 0 ]; then
	exit 1
fi
exit 0
