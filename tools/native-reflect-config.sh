#!/bin/sh
# Emits a GraalVM reflect-config.json that registers EVERY class under the
# given class directories for full reflection (Jackson reads records and DTOs
# reflectively; jOOQ builds generated records the same way). Blunt but exact
# for the native-image trial — the classes are our own, so the cost is image
# size, not correctness.
#
#   tools/native-reflect-config.sh <out.json> <classes-dir>...
set -eu
out=$1; shift
for dir in "$@"; do
  [ -d "$dir" ] || continue
  find "$dir" -name '*.class' ! -name 'module-info.class' ! -name 'package-info.class' \
    | sed -e "s|^$dir/||" -e 's|\.class$||' -e 's|/|.|g'
done | sort -u | awk '
  BEGIN { print "[" }
  { printf "%s  {\"name\":\"%s\",\"allDeclaredConstructors\":true,\"allPublicConstructors\":true,\"allDeclaredMethods\":true,\"allPublicMethods\":true,\"allDeclaredFields\":true,\"allPublicFields\":true,\"allRecordComponents\":true}", (NR > 1 ? ",\n" : ""), $0 }
  END { print "\n]" }' > "$out"
echo "wrote $(grep -c '"name"' "$out") reflection entries to $out"
