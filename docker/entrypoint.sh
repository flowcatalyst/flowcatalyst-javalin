#!/bin/sh
# Runtime entrypoint for the fc-server image. Runs docker/jvm-opts.sh
# (docs/spec/jvm-memory.md) to derive a container-fenced -Xmx and
# -XX:MaxDirectMemorySize, then execs java as PID 1 so it receives SIGTERM
# directly.
#
# --enable-preview: the build compiles with preview features on
# (CONVENTIONS §8).
# --enable-native-access: the HTTP/3 connector's quiche binding uses the FFM
# API (docs/spec/http-transport.md); without the flag the JDK warns that
# restricted methods "will be blocked in a future release".
set -eu

# Word-splitting of this substitution is intended: jvm-opts.sh prints either
# one line of space-separated flags or nothing, and $(...) may legitimately
# be empty (no cgroup limit, or the operator already set JAVA_TOOL_OPTIONS) —
# `set -u` doesn't trip on that because it's a command substitution, not an
# unset variable.
exec java $(sh /usr/local/bin/jvm-opts.sh) --enable-preview --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/fc-server.jar "$@"
