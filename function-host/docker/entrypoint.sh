#!/bin/sh
# Runtime entrypoint for the fc-fnhost image. Runs docker/jvm-opts.sh
# (docs/spec/jvm-memory.md, docs/spec/function-host-process.md §3) to derive
# a container-fenced -Xmx / -XX:MaxDirectMemorySize AND -XX:MaxMetaspaceSize,
# then execs java as PID 1 so it receives SIGTERM directly.
#
# FC_JVM_METASPACE_FENCE=true: this image's own opt-in, set nowhere else —
# jvm-opts.sh only derives -XX:MaxMetaspaceSize when it sees this variable,
# so the fc-server image (which never sets it) computes byte-identical flags
# to before this existed. A function is a class loader; an unbounded
# metaspace would turn one function's leak into a host OOM-kill instead of a
# catchable `OutOfMemoryError: Metaspace` that fails just that one load.
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

export FC_JVM_METASPACE_FENCE=true

# Word-splitting of the jvm-opts.sh substitution is intended — see the root
# docker/entrypoint.sh's own comment on this for the full reasoning.
exec java $(sh /usr/local/bin/jvm-opts.sh) --enable-preview --enable-native-access=ALL-UNNAMED \
    -jar /usr/local/lib/fc-fnhost.jar "$@"
