#!/usr/bin/env bash
# Compare this repo's clients/typescript-sdk and clients/laravel-sdk against
# the Go repo's copies and report drift. Both repos hold the SDK sources for
# now (git subtree, since 2026-09-14 —
# docs/go-mirror/2026-09-14-sdk-copies.md); this is the check that they stay
# diffable.
#
# Usage: tools/sdk-drift.sh [path-to-flowcatalyst-go]   (default ../flowcatalyst-go)
# Exit 0 if identical (modulo the excludes below), 1 if any file differs.
set -euo pipefail
JAVA_REPO="$(cd "$(dirname "$0")/.." && pwd)"
GO_REPO="${1:-$JAVA_REPO/../flowcatalyst-go}"

EXCLUDES=(
	--exclude=node_modules
	--exclude=dist
	--exclude=vendor
	--exclude=.DS_Store
	# openapi-processed.json is gitignored on the Go side
	# (clients/laravel-sdk/.gitignore there, and here) — a local build
	# artifact of the Laravel generator, not source.
	--exclude=openapi-processed.json
)

status=0
for sdk in typescript-sdk laravel-sdk; do
	set +e
	diff -rq "${EXCLUDES[@]}" "$JAVA_REPO/clients/$sdk" "$GO_REPO/clients/$sdk"
	sdk_status=$?
	set -e

	# diff exits 2 on trouble (e.g. the Go repo isn't there) — let that
	# surface as-is rather than being reported as "files differ".
	if [ "$sdk_status" -eq 2 ]; then
		exit 2
	elif [ "$sdk_status" -ne 0 ]; then
		status=1
	fi
done
exit "$status"
