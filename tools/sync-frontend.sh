#!/usr/bin/env bash
# Rebuild the Vue SPA from the Go repo's source, OUT of tree, and copy the
# result into the Java server's embedded copy (`server/src/main/resources/frontend`).
#
# The Go repo is read-only for this port; Vite's `--outDir` keeps the build
# artefacts out of it. Go's own binary embeds `frontend/dist` from its tree
# (`//go:embed all:dist`), so a Go build the owner makes with `make frontend`
# and a Java build synced here serve the same SPA only if both were built
# from the same source commit — `docs/spec/frontend-e2e.md` §2 checks that.
#
# Usage: tools/sync-frontend.sh [path-to-flowcatalyst-go]   (default ../flowcatalyst-go)
set -euo pipefail
GO_REPO="${1:-$(cd "$(dirname "$0")/.." && pwd)/../flowcatalyst-go}"
JAVA_REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)/dist"
SRC_COMMIT="$(git -C "$GO_REPO" log -1 --format=%h -- frontend/src frontend/index.html frontend/package.json)"

echo ">> building SPA from $GO_REPO/frontend (source commit $SRC_COMMIT) into $OUT"
(cd "$GO_REPO/frontend" && pnpm exec vite build --outDir "$OUT" --emptyOutDir)

echo ">> syncing into $JAVA_REPO/server/src/main/resources/frontend"
rsync -a --delete "$OUT/" "$JAVA_REPO/server/src/main/resources/frontend/"
echo "$SRC_COMMIT" > "$JAVA_REPO/server/src/main/resources/frontend.source-commit"
rm -rf "$(dirname "$OUT")"
echo ">> done — embedded SPA now at frontend source commit $SRC_COMMIT"
