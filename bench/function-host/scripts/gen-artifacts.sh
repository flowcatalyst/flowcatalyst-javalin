#!/usr/bin/env bash
# Builds the two base fixture jars (lean, typical) and stamps out N distinct
# copies of each — a distinct artifact file with a distinct sha256 digest per
# function (docs/spec/function-host-benchmark.md point 2: "each of the N
# functions must be a DISTINCT artifact file with a distinct digest ... so
# each gets its own class loader"). Sharing one jar file on disk would still
# let every function share the OS page cache for its bytes — realistic, and
# noted in the report — but the host keys `Reconciler#prepared` and the class
# loader cache by `versionId`/digest, so identical files would collapse to
# ONE loaded class set, not N.
#
# Usage: gen-artifacts.sh <count> [repo-root]
set -eu
COUNT=${1:-200}
ROOT=${2:-$(cd "$(dirname "$0")/../../.." && pwd)}
BENCH=$ROOT/bench/function-host
ART=$BENCH/artifacts
JAVA_HOME=${JAVA_HOME:-$(mise where java)}
JAR=$JAVA_HOME/bin/jar
JAVAC=$JAVA_HOME/bin/javac

echo "-- building function-api (package only, never installed)"
( cd "$ROOT" && mvn -q -pl function-api -DskipTests package )
API_JAR=$ROOT/function-api/target/flowcatalyst-function-api-0.0.1-SNAPSHOT.jar

echo "-- building lean fixture"
mkdir -p "$BENCH/fixtures/lean/classes"
"$JAVAC" -d "$BENCH/fixtures/lean/classes" -cp "$ROOT/function-api/target/classes" "$BENCH/fixtures/lean/LeanFn.java"
( cd "$BENCH/fixtures/lean/classes" && "$JAR" cf "$BENCH/fixtures/lean/lean.jar" . )

echo "-- building typical fixture (shaded: jackson-databind + networknt json-schema-validator)"
( cd "$ROOT" && mvn -q -f "$BENCH/fixtures/typical/pom.xml" -o -Dfunction-api.jar="$API_JAR" -DskipTests package )
cp "$BENCH/fixtures/typical/target/typical-fixture.jar" "$BENCH/fixtures/typical.jar"

stamp() {
  local base=$1 outdir=$2 prefix=$3 n=$4
  mkdir -p "$outdir"
  rm -f "$outdir"/*.jar
  local tmp; tmp=$(mktemp -d)
  for i in $(seq -w 0 $((n - 1))); do
    local out="$outdir/$prefix-$i.jar"
    cp "$base" "$out"
    local marker="$tmp/bench-marker-$prefix-$i.txt"
    printf 'bench-fixture-index=%s\n' "$i" > "$marker"
    (cd "$tmp" && zip -q "$out" "$(basename "$marker")")
  done
  rm -rf "$tmp"
}

echo "-- stamping $COUNT distinct lean copies"
stamp "$BENCH/fixtures/lean/lean.jar" "$ART/lean" "lean" "$COUNT"
echo "-- stamping $COUNT distinct typical copies"
stamp "$BENCH/fixtures/typical.jar" "$ART/typical" "typical" "$COUNT"

echo "-- done: $ART/lean (*.jar) and $ART/typical (*.jar), $COUNT each"
ls "$ART/lean" | wc -l
ls "$ART/typical" | wc -l
