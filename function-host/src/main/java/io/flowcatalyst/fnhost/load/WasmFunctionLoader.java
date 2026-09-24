package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.fnhost.wasm.CompiledWasm;
import io.flowcatalyst.fnhost.wasm.WasmFunction;
import io.flowcatalyst.fnhost.wasm.WasmModuleCheck;
import io.flowcatalyst.fnhost.wasm.WasmModuleCheck.EntrypointNotExported;
import io.flowcatalyst.fnhost.wasm.WasmModuleCheck.ImportNotAllowed;
import io.flowcatalyst.fnhost.wasm.WasmModuleCheck.InstanceMemory;
import io.flowcatalyst.fnhost.wasm.WasmModuleCheck.MemoryOverCap;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.sdk.result.Result.Err;
import io.flowcatalyst.sdk.result.Result.Ok;
import run.endive.wasm.WasmModule;
import run.endive.wasm.Parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/// Loads one `runtime: wasm` artifact (`docs/spec/function-wasm-runtime.md`
/// §2-§3): parse, check (imports, entrypoint export, memory against
/// `wasmMemoryMb`), then compile ONCE for the version — Endive compiler mode,
/// which defines classes, which is why the reconciler runs [MetaspaceGuard]
/// before this exactly as before a JVM load. Every refusal is a [Refused]
/// value; a metaspace `OutOfMemoryError` while compiling is
/// [Reason#OUT_OF_METASPACE], as for the JVM.
///
/// No instance is created here: the returned [LoadedFunction] holds a
/// [WasmFunction] whose pool instantiates lazily, and the version's
/// [CompiledWasm] as the resource its `close()` releases.
public final class WasmFunctionLoader implements FunctionLoader {

    @Override
    public LoadOutcome load(Path artifact, Manifest manifest, FunctionAddress address, int version) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(artifact);
        } catch (IOException e) {
            return new Refused(Reason.WASM_INVALID, "unreadable: " + artifact
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        WasmModule module;
        try {
            module = Parser.parse(bytes);
        } catch (RuntimeException e) {
            return new Refused(Reason.WASM_INVALID, "not a Wasm module: " + message(e));
        }

        int capPages = WasmModuleCheck.capPages(wasmMemoryMb(manifest));
        InstanceMemory memory;
        switch (WasmModuleCheck.check(module, manifest.entrypoint(), capPages)) {
            case Ok<InstanceMemory, WasmModuleCheck.Refusal>(InstanceMemory accepted) -> memory = accepted;
            case Err<InstanceMemory, WasmModuleCheck.Refusal>(WasmModuleCheck.Refusal refusal) -> {
                return new Refused(reasonFor(refusal), refusal.detail());
            }
        }

        CompiledWasm compiled;
        try {
            compiled = CompiledWasm.compile(module, memory, capPages);
        } catch (RuntimeException e) {
            return new Refused(Reason.WASM_INVALID, "does not compile: " + message(e));
        } catch (Error e) {
            OutOfMemoryError metaspace = JvmFunctionLoader.findMetaspaceOom(e);
            if (metaspace != null) {
                return new Refused(Reason.OUT_OF_METASPACE,
                        metaspace.getMessage() != null ? metaspace.getMessage() : "OutOfMemoryError");
            }
            throw e; // a Java-heap OOM, or any other real Error, is not this load's to swallow
        }

        WasmFunction function = new WasmFunction(compiled, manifest.entrypoint(),
                manifest.limits().maxConcurrency(), Set.copyOf(manifest.config()), Set.copyOf(manifest.secrets()),
                address, version);
        return new Loaded(new LoadedFunction(function, compiled, address, version));
    }

    private static Reason reasonFor(WasmModuleCheck.Refusal refusal) {
        return switch (refusal) {
            case ImportNotAllowed ignored -> Reason.WASM_IMPORT_NOT_ALLOWED;
            case EntrypointNotExported ignored -> Reason.WASM_ENTRYPOINT_NOT_EXPORTED;
            case MemoryOverCap ignored -> Reason.WASM_MEMORY_OVER_CAP;
        };
    }

    /// Always set for a Wasm manifest (the platform resolves it, and so does
    /// [Manifest#readStored]); the platform default covers a manifest built
    /// some other way.
    private static int wasmMemoryMb(Manifest manifest) {
        Integer mb = manifest.limits().wasmMemoryMb();
        return mb != null ? mb : FunctionLimits.DEFAULT_WASM_MEMORY_MB;
    }

    private static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
