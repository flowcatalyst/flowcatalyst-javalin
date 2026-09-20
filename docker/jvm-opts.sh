#!/bin/sh
# Derives -Xmx / -XX:MaxDirectMemorySize (and, when FC_JVM_METASPACE_PERCENT
# is set, -XX:MaxMetaspaceSize) from the container's cgroup memory limit.
# See docs/spec/jvm-memory.md for the rules this implements.
#
# Prints the derived flags on ONE line to stdout (or nothing, per the rules
# below); an explanation always goes to stderr, prefixed "jvm-opts:".
#
# Exit codes: 0 in every case except an invalid FC_JVM_METASPACE_PERCENT (§4
# below), which exits non-zero with nothing on stdout — a config typo must
# fail the container start, never silently fall back to an unfenced
# metaspace. Every other "can't compute a fence" case (no cgroup limit,
# JAVA_TOOL_OPTIONS already set, an unlimited sentinel) is still a soft
# no-op: exit 0, JVM defaults apply.
#
# Runs under busybox ash on Alpine: POSIX sh only, no bashisms, no `bc`.
# $(( )) is 64-bit there, which the arithmetic below relies on.
#
# FC_JVM_METASPACE_PERCENT=<integer 10-70> (default: unset — the fc-server
# image never sets it, so its computed -Xmx/-XX:MaxDirectMemorySize are
# byte-identical to before this variable existed): also derives
# -XX:MaxMetaspaceSize as that percent of the container limit, and — unlike
# the fc-server-only flags, which are unaffected — SUBTRACTS metaspace (and
# the existing direct-memory carve-out) from -Xmx, so heap + direct +
# metaspace + reserve never exceeds the container limit (previously this was
# added on top of -Xmx, over-subscribing the container). Function-host-only
# (function-host/docker/entrypoint.sh sets
# FC_JVM_METASPACE_PERCENT=${FC_JVM_METASPACE_PERCENT:-50}): a function is
# its own class loader, so an unbounded metaspace turns one function's leak
# into a host-wide OOM-kill instead of a catchable `OutOfMemoryError:
# Metaspace` that fails just that one function's load
# (docs/spec/function-host-process.md §3).
set -eu

warn() {
    printf 'jvm-opts: %s\n' "$1" >&2
}

fail() {
    printf 'jvm-opts: %s\n' "$1" >&2
    exit 1
}

# Rule 0: validate FC_JVM_METASPACE_PERCENT before anything else, including
# the JAVA_TOOL_OPTIONS step-aside below — a bad value is a config defect
# that must fail the container regardless of what else is set.
# ${FC_JVM_METASPACE_PERCENT+set} is true whenever the variable is SET, even
# to an empty string, distinguishing "unset" (skip validation, no metaspace
# fence at all) from "set to ''" (invalid, must fail) under `set -u`.
metaspace_percent=""
if [ -n "${FC_JVM_METASPACE_PERCENT+set}" ]; then
    case "$FC_JVM_METASPACE_PERCENT" in
        ''|*[!0-9]*)
            fail "FC_JVM_METASPACE_PERCENT must be an integer in [10,70] (got '${FC_JVM_METASPACE_PERCENT}')"
            ;;
    esac
    if [ "$FC_JVM_METASPACE_PERCENT" -lt 10 ] || [ "$FC_JVM_METASPACE_PERCENT" -gt 70 ]; then
        fail "FC_JVM_METASPACE_PERCENT must be an integer in [10,70] (got '${FC_JVM_METASPACE_PERCENT}')"
    fi
    metaspace_percent=$FC_JVM_METASPACE_PERCENT
fi

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
floor_mib=64
floor_bytes=67108864    # 64 MiB, in bytes

reserve_pct=$(( L * 15 / 100 ))
if [ "$reserve_pct" -gt "$reserve_min" ]; then
    reserve=$reserve_pct
else
    reserve=$reserve_min
fi

direct_bytes=$(( reserve / 2 ))
direct_mib=$(( direct_bytes / one_mib ))

xmx_bytes=$(( L - reserve ))

# §4: when a metaspace percent is set, its share is carved out of -Xmx too
# (on top of direct, which -Xmx already implicitly made room for via
# `reserve`) — see the header comment. `metaspace_bytes` is the REQUESTED
# amount here; it may still shrink below if the heap floor binds.
metaspace_bytes=""
if [ -n "$metaspace_percent" ]; then
    metaspace_bytes=$(( L * metaspace_percent / 100 ))
    xmx_bytes=$(( xmx_bytes - direct_bytes - metaspace_bytes ))
fi

if [ "$xmx_bytes" -lt "$floor_bytes" ]; then
    # The heap floor wins: -Xmx is floored at 64 MiB no matter what, and if a
    # metaspace fence is active it gives way (shrinks) so the sum invariant
    # (heap + direct + metaspace + reserve <= limit) still holds — never the
    # other way around, since a function host with no headroom to load
    # anything is a worse failure mode than a smaller metaspace fence.
    xmx_mib=$floor_mib
    if [ -n "$metaspace_percent" ]; then
        requested_mib=$(( metaspace_bytes / one_mib ))
        shrunk_bytes=$(( L - reserve - direct_bytes - floor_bytes ))
        if [ "$shrunk_bytes" -lt 0 ]; then
            shrunk_bytes=0
        fi
        metaspace_bytes=$shrunk_bytes
        metaspace_mib=$(( metaspace_bytes / one_mib ))
        warn "limit $L bytes gives a computed -Xmx below the 64 MiB floor; flooring -Xmx to 64m and shrinking -XX:MaxMetaspaceSize from the requested ${requested_mib}m to ${metaspace_mib}m so heap + direct + metaspace + reserve never exceeds the limit (container is mis-sized) -> -Xmx64m -XX:MaxDirectMemorySize=${direct_mib}m -XX:MaxMetaspaceSize=${metaspace_mib}m"
    else
        warn "limit $L bytes gives a computed -Xmx below the 64 MiB floor; flooring to 64m (container is mis-sized) -> -Xmx64m -XX:MaxDirectMemorySize=${direct_mib}m"
    fi
else
    xmx_mib=$(( xmx_bytes / one_mib ))
    if [ -n "$metaspace_percent" ]; then
        metaspace_mib=$(( metaspace_bytes / one_mib ))
        warn "limit $L bytes -> -Xmx${xmx_mib}m -XX:MaxDirectMemorySize=${direct_mib}m -XX:MaxMetaspaceSize=${metaspace_mib}m"
    else
        warn "limit $L bytes -> -Xmx${xmx_mib}m -XX:MaxDirectMemorySize=${direct_mib}m"
    fi
fi

metaspace_flag=""
if [ -n "$metaspace_percent" ]; then
    metaspace_mib=$(( metaspace_bytes / one_mib ))
    metaspace_flag=" -XX:MaxMetaspaceSize=${metaspace_mib}m"
fi

printf -- "-Xmx%sm -XX:MaxDirectMemorySize=%sm%s\n" "$xmx_mib" "$direct_mib" "$metaspace_flag"
