#!/bin/sh
# Runtime entrypoint for the fc-server image. Runs docker/jvm-opts.sh
# (docs/spec/jvm-memory.md) to derive a container-fenced -Xmx and
# -XX:MaxDirectMemorySize, then execs java as PID 1 so it receives SIGTERM
# directly.
#
# -XX:+UseCompactObjectHeaders (JDK 25 product feature, opt-in): shrinks
# every object header from 12 to 8 bytes (docs/spec/jvm-memory.md §1a). Must
# match the Dockerfile training run's flag exactly — the AOT cache below
# records object layout, and a flag mismatch is one of the ways the cache is
# rejected.
#
# -XX:AOTCache=...: only passed when the Dockerfile's training run (§1a)
# actually produced /usr/local/lib/fc-server.aot. Per JEP 514, a cache the
# running JVM cannot use (wrong JDK build, mismatched flags/classpath) is
# silently disabled with a warning visible via -Xlog:aot, never a boot
# failure — see docs/spec/jvm-memory.md §1a for how that was confirmed —
# so it is safe to pass unconditionally once the file exists.
#
# --enable-preview: the build compiles with preview features on
# (CONVENTIONS §8).
# --enable-native-access: the HTTP/3 connector's quiche binding uses the FFM
# API (docs/spec/http-transport.md); without the flag the JDK warns that
# restricted methods "will be blocked in a future release".
set -eu

aot_cache=/usr/local/lib/fc-server.aot
jdk25_flags="-XX:+UseCompactObjectHeaders"
if [ -f "$aot_cache" ]; then
    jdk25_flags="$jdk25_flags -XX:AOTCache=$aot_cache"
fi

# Word-splitting of these substitutions is intended: jvm-opts.sh prints either
# one line of space-separated flags or nothing, and $(...) may legitimately
# be empty (no cgroup limit, or the operator already set JAVA_TOOL_OPTIONS) —
# `set -u` doesn't trip on that because it's a command substitution, not an
# unset variable. $jdk25_flags is always at least one flag, so it never goes
# empty, but is unquoted for the same reason and for a consistent style.
exec java $(sh /usr/local/bin/jvm-opts.sh) $jdk25_flags --enable-preview --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/fc-server.jar "$@"
