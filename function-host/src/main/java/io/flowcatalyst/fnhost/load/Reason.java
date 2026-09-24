package io.flowcatalyst.fnhost.load;

/// Why a [FunctionLoader] refused an artifact. A closed set — a refusal is
/// routine (it becomes a `FAILED` heartbeat entry, `docs/spec/function-host-core.md`
/// §2.2), never a reason to invent a new case without updating every switch
/// over it.
public enum Reason {

    /// The jar contains a `.so`/`.dll`/`.dylib`/`.jnilib` entry: one class
    /// loader per JVM may load a given native library, so a second function
    /// bundling the same one would get `UnsatisfiedLinkError`.
    NATIVE_LIBRARY,

    /// The jar registers a `java.security.Provider` service: a second
    /// provider under one JVM-wide name is silently ignored, so one
    /// function would run another's provider.
    SECURITY_PROVIDER,

    /// The jar contains a class in package `io.flowcatalyst.function` — a
    /// shaded copy of the API. It would never be loaded (the parent loader
    /// wins that package unconditionally), but it signals a build that
    /// forgot to mark the API `provided`.
    BUNDLES_API,

    /// The manifest's entrypoint class was not found in the jar.
    ENTRYPOINT_NOT_FOUND,

    /// The entrypoint class does not implement `io.flowcatalyst.function.Function`
    /// as the host's own copy of that interface.
    ENTRYPOINT_NOT_A_FUNCTION,

    /// The entrypoint class has no public no-arg constructor, or
    /// constructing it threw.
    ENTRYPOINT_NOT_INSTANTIABLE,

    /// The jar file itself could not be opened or read.
    UNREADABLE_JAR,

    /// Class loading, entrypoint construction, or `init` hit the metaspace
    /// fence (`docs/spec/function-host-process.md` §3): a catchable
    /// `OutOfMemoryError` naming Metaspace or Compressed class space. This
    /// ONE version fails to load; the fence itself, and every other
    /// function, is untouched — a Java-heap `OutOfMemoryError` is never
    /// this reason (it is not caught at all: it is not one function's
    /// problem to swallow). This is the BACKSTOP: [MetaspaceGuard] is meant
    /// to refuse a load before it ever gets this close, so a healthy host
    /// should see [#METASPACE_HEADROOM] far more often than this.
    OUT_OF_METASPACE,

    /// [MetaspaceGuard] refused the load WITHOUT ATTEMPTING IT: free
    /// metaspace was already below the reserve before class loading even
    /// began (`docs/spec/function-host-process.md` §3 item 1) — an ordinary,
    /// routine load failure, distinct from [#OUT_OF_METASPACE] (which means
    /// an actual `OutOfMemoryError` was caught). The old version (if any)
    /// keeps serving; the next reconcile cycle re-checks.
    METASPACE_HEADROOM,

    /// A `runtime: wasm` artifact that is not a Wasm module Endive can parse
    /// and compile (`docs/spec/function-wasm-runtime.md` §2) — garbage bytes,
    /// a truncated module, an unreadable file.
    WASM_INVALID,

    /// The module has no function export named by the manifest's
    /// `entrypoint`.
    WASM_ENTRYPOINT_NOT_EXPORTED,

    /// The module imports something the host does not provide: any import
    /// outside `extism:host/env`, `extism:host/user` and
    /// `wasi_snapshot_preview1`, a non-function import, or an
    /// `extism:host/user` name that is not one of the host's own functions.
    /// The detail names the import (`module::name`). A Wasm function reaches
    /// only what it imports, so this is the containment check.
    WASM_IMPORT_NOT_ALLOWED,

    /// The module's declared minimum linear memory exceeds the manifest's
    /// `limits.wasmMemoryMb` — it could never be instantiated under the cap.
    WASM_MEMORY_OVER_CAP
}
