#!/bin/sh
# Derives -Xmx / -XX:MaxDirectMemorySize from the container's cgroup memory
# limit. See docs/spec/jvm-memory.md for the rules this implements.
#
# Prints the derived flags on ONE line to stdout (or nothing, per the rules
# below); an explanation always goes to stderr, prefixed "jvm-opts:".
#
# Runs under busybox ash on Alpine: POSIX sh only, no bashisms, no `bc`.
# $(( )) is 64-bit there, which the arithmetic below relies on.
set -eu

warn() {
    printf 'jvm-opts: %s\n' "$1" >&2
}

# Rule 2: if the operator has already set a heap/direct-memory flag via
# JAVA_TOOL_OPTIONS, that is the only override — step aside entirely.
case "${JAVA_TOOL_OPTIONS:-}" in
    *-Xmx*|*-XX:MaxRAMPercentage*|*-XX:MaxDirectMemorySize*)
        warn "JAVA_TOOL_OPTIONS already sets -Xmx, -XX:MaxRAMPercentage or -XX:MaxDirectMemorySize; stepping aside"
        exit 0
        ;;
esac

# Rule 1: locate the limit source. The test seam wins when set; otherwise
# cgroup v2, then cgroup v1.
if [ -n "${FC_JVM_MEMORY_LIMIT_FILE:-}" ]; then
    limit_file=$FC_JVM_MEMORY_LIMIT_FILE
elif [ -f /sys/fs/cgroup/memory.max ]; then
    limit_file=/sys/fs/cgroup/memory.max
elif [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
    limit_file=/sys/fs/cgroup/memory/memory.limit_in_bytes
else
    limit_file=""
fi

if [ -z "$limit_file" ] || [ ! -f "$limit_file" ]; then
    warn "no cgroup memory limit file found (checked FC_JVM_MEMORY_LIMIT_FILE, cgroup v2, cgroup v1); no fence applied, JVM defaults apply"
    exit 0
fi

raw=$(cat "$limit_file" 2>/dev/null) || raw=""
raw=$(printf '%s' "$raw" | tr -d '[:space:]')

if [ -z "$raw" ] || [ "$raw" = "max" ]; then
    warn "limit file '$limit_file' holds no numeric limit (read '$raw'); no fence applied, JVM defaults apply"
    exit 0
fi

case "$raw" in
    *[!0-9]*)
        warn "limit file '$limit_file' holds a non-numeric value ('$raw'); no fence applied, JVM defaults apply"
        exit 0
        ;;
esac

L=$raw

# cgroup v1's "unlimited" sentinel is effectively LLONG_MAX (rounded to a
# page boundary); treat anything at or above 2^60 bytes as no real limit.
if [ "$L" -ge 1152921504606846976 ]; then
    warn "limit $L bytes is at/above the v1 unlimited sentinel (2^60); no fence applied, JVM defaults apply"
    exit 0
fi

one_mib=1048576
reserve_min=201326592   # 192 MiB, in bytes

reserve_pct=$(( L * 15 / 100 ))
if [ "$reserve_pct" -gt "$reserve_min" ]; then
    reserve=$reserve_pct
else
    reserve=$reserve_min
fi

xmx_bytes=$(( L - reserve ))
xmx_mib=$(( xmx_bytes / one_mib ))

direct_bytes=$(( reserve / 2 ))
direct_mib=$(( direct_bytes / one_mib ))

if [ "$xmx_mib" -lt 64 ]; then
    warn "limit $L bytes gives a computed -Xmx below the 64 MiB floor; flooring to 64m (container is mis-sized) -> -Xmx64m -XX:MaxDirectMemorySize=${direct_mib}m"
    xmx_mib=64
else
    warn "limit $L bytes -> -Xmx${xmx_mib}m -XX:MaxDirectMemorySize=${direct_mib}m"
fi

printf -- "-Xmx%sm -XX:MaxDirectMemorySize=%sm\n" "$xmx_mib" "$direct_mib"
