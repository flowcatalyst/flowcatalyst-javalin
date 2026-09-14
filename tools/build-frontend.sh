#!/usr/bin/env bash
# Rebuild the Vue SPA from this repo's own source (`./frontend`), OUT of
# tree, and copy the result into the Java server's embedded copy
# (`server/src/main/resources/frontend`).
#
# The SPA source moved into this repo (git subtree from the Go repo) on
# 2026-09-14 — see docs/go-mirror/2026-09-14-frontend-source-shared.md. Both repos
# hold the source for now; `tools/frontend-drift.sh` checks they stay
# byte-identical. Vite's `--outDir` keeps the build artefacts out of
# `frontend/` itself. No vue-tsc gate here (same as the old sync script) —
# `pnpm build` in `frontend/` is the type-checked build, run in CI.
#
# Usage: tools/build-frontend.sh
set -euo pipefail
JAVA_REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)/dist"
SRC_COMMIT="$(git -C "$JAVA_REPO" log -1 --format=%h -- frontend/src frontend/index.html frontend/package.json)"

echo ">> building SPA from $JAVA_REPO/frontend (source commit $SRC_COMMIT) into $OUT"
(cd "$JAVA_REPO/frontend" && pnpm install --frozen-lockfile && pnpm exec vite build --outDir "$OUT" --emptyOutDir)

echo ">> syncing into $JAVA_REPO/server/src/main/resources/frontend"
rsync -a --delete "$OUT/" "$JAVA_REPO/server/src/main/resources/frontend/"
echo "$SRC_COMMIT" > "$JAVA_REPO/server/src/main/resources/frontend.source-commit"
rm -rf "$(dirname "$OUT")"
echo ">> done — embedded SPA now at frontend source commit $SRC_COMMIT"
