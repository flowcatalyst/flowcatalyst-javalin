package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;

import java.nio.file.Path;

/// Turns one fetched, verified artifact into a [LoadedFunction] — or a
/// routine [Refused] (`docs/spec/function-wasm-runtime.md` §2). One
/// implementation per `manifest.runtime()`; the
/// [io.flowcatalyst.fnhost.reconcile.Reconciler] chooses by runtime with an
/// exhaustive switch, and runs [MetaspaceGuard#check] before calling either
/// (both define classes: a JVM function its own, a Wasm module the ones
/// Endive's compiler generates).
///
/// Sealed so a new runtime is a compile error at the one place that
/// dispatches, never a silently unhandled case.
public sealed interface FunctionLoader permits JvmFunctionLoader, WasmFunctionLoader {

    /// Loads `artifact` as `address`@`version` under `manifest`. Never
    /// throws for a routine refusal; an `Error` that is not this host's
    /// metaspace fence (a Java-heap `OutOfMemoryError`, say) propagates.
    LoadOutcome load(Path artifact, Manifest manifest, FunctionAddress address, int version);
}
