#!/usr/bin/env bash
# Compare this repo's clients/typescript-sdk, clients/laravel-sdk and sdk (the
# Java SDK) against the Go repo's copies and report drift. Both repos hold the SDK sources for
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

# Java SDK: this repo's sdk/ vs the Go repo's clients/java-sdk. The directory is
# standalone (no parent pom, no sibling-module dependency) precisely so it can
# be mirrored byte-for-byte. The one file allowed to differ is
# openapi/openapi.json: each repo carries its OWN server's lockfile there
# (`make sdk-spec`), and this repo's lags Go's until the server port catches up.
# The use-case framework (usecase/) is not part of the SDK and is not mirrored.
set +e
diff -rq "${EXCLUDES[@]}" --exclude=target --exclude=openapi.json \
	"$JAVA_REPO/sdk" "$GO_REPO/clients/java-sdk"
sdk_status=$?
set -e
if [ "$sdk_status" -eq 2 ]; then
	exit 2
elif [ "$sdk_status" -ne 0 ]; then
	status=1
fi
if ! cmp -s "$JAVA_REPO/sdk/openapi/openapi.json" "$GO_REPO/api/openapi.lock.json"; then
	echo "note: sdk/openapi/openapi.json is not Go's current api/openapi.lock.json (expected until the Java server's lockfile catches up)" >&2
fi
exit "$status"
