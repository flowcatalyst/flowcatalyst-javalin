package io.flowcatalyst.fnhost.load;

/// Why [JvmFunctionLoader#load] refused a jar. A closed set — a refusal is
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
    UNREADABLE_JAR
}
