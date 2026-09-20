#!/bin/sh
# Runtime entrypoint for the fc-fnhost image. Runs docker/jvm-opts.sh
# (docs/spec/jvm-memory.md §4, docs/spec/function-host-process.md §3) to
# derive a container-fenced -Xmx / -XX:MaxDirectMemorySize AND
# -XX:MaxMetaspaceSize, then execs java as PID 1 so it receives SIGTERM
# directly.
#
# FC_JVM_METASPACE_PERCENT=${FC_JVM_METASPACE_PERCENT:-50}: this image's own
# default (an operator may still override it, within jvm-opts.sh's validated
# 10-70 range), set nowhere else — jvm-opts.sh only derives
# -XX:MaxMetaspaceSize when this variable is set, so the fc-server image
# (which never sets it) computes byte-identical flags to before this
# existed. A function is its own class loader; an unbounded metaspace would
# turn one function's leak into a host OOM-kill instead of a catchable
# `OutOfMemoryError: Metaspace` that fails just that one load.
#
# --enable-preview: the build compiles with preview features on
# (CONVENTIONS §8). --enable-native-access: this module depends on the
# server module, whose HTTP/3 connector uses the FFM API — harmless to pass
# even on a boot path that never reaches it.
#
# No -XX:AOTCache here: this slice ships no AOT cache (docs/spec/function-host-process.md
# §3 — the class set a host loads depends on which functions land on it, so a
# fixed training-run cache would be misleading without real numbers to design
# one against).
set -eu

export FC_JVM_METASPACE_PERCENT=${FC_JVM_METASPACE_PERCENT:-50}

# jvm-opts.sh now validates FC_JVM_METASPACE_PERCENT and can exit non-zero
# (an out-of-[10,70]-range or non-integer value) -- that must fail the
# container start, not silently fall back to an unfenced metaspace. Capture
# its output explicitly and check the exit status rather than splicing
# $(sh jvm-opts.sh) straight into the `java` command line, where a failed
# command substitution's exit status would be discarded (set -e does not
# catch a failing command substitution embedded as part of a larger word).
JVM_OPTS=$(sh /usr/local/bin/jvm-opts.sh) || {
    echo "entrypoint: docker/jvm-opts.sh failed (see its own stderr above); refusing to start" >&2
    exit 1
}

# Word-splitting of $JVM_OPTS is intended: jvm-opts.sh prints one line of
# space-separated flags — see the root docker/entrypoint.sh's own comment on
# this for the full reasoning.
exec java $JVM_OPTS --enable-preview --enable-native-access=ALL-UNNAMED \
    -jar /usr/local/lib/fc-fnhost.jar "$@"
