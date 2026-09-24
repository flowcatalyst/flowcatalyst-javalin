package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.sdk.result.Result;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.Export;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.Import;
import run.endive.wasm.types.ImportSection;
import run.endive.wasm.types.MemoryLimits;

import java.util.Objects;
import java.util.Set;

/// The load-time checks on a parsed module (`docs/spec/function-wasm-runtime.md`
/// §2), in this order — imports, entrypoint, memory — each a routine
/// [Refusal], never an exception. A Wasm function reaches only what it
/// imports, so the import check IS the containment: everything the host does
/// not name here is denied by never being linkable.
public final class WasmModuleCheck {

    /// The Extism kernel and built-ins (log, config, var, http).
    static final String EXTISM_ENV = "extism:host/env";
    /// The host's own functions ([HostFunctions]).
    static final String EXTISM_USER = "extism:host/user";
    /// WASI preview 1 (clock, random, stdout/stderr — no filesystem is preopened).
    static final String WASI = "wasi_snapshot_preview1";

    private static final Set<String> ALLOWED_MODULES = Set.of(EXTISM_ENV, EXTISM_USER, WASI);

    /// One linear-memory page.
    static final int PAGE_BYTES = 64 * 1024;

    private WasmModuleCheck() {
    }

    /// Why a module is refused. Every case names what refused it.
    public sealed interface Refusal permits ImportNotAllowed, EntrypointNotExported, MemoryOverCap {
        /// The refusal's human-readable detail (the heartbeat's and the log's).
        String detail();
    }

    /// `module::name` is not something the host provides.
    public record ImportNotAllowed(String module, String name, ExternalType kind) implements Refusal {
        @Override
        public String detail() {
            return module + "::" + name + (kind == ExternalType.FUNCTION ? "" : " (" + kind + ")");
        }
    }

    /// No function export has the manifest's `entrypoint` name.
    public record EntrypointNotExported(String entrypoint) implements Refusal {
        @Override
        public String detail() {
            return entrypoint;
        }
    }

    /// The module's declared minimum memory is over `wasmMemoryMb`.
    public record MemoryOverCap(int declaredPages, int capPages) implements Refusal {
        @Override
        public String detail() {
            return "the module declares " + declaredPages + " pages (" + mib(declaredPages) + " MiB) of memory; "
                    + "the cap is " + capPages + " pages (" + mib(capPages) + " MiB)";
        }
    }

    /// The linear memory every instance of an accepted module is given.
    public sealed interface InstanceMemory permits Capped, NoMemory {
    }

    /// The module's own declared minimum (capping below it fails
    /// instantiation — W0) up to `min(cap, the module's own declared maximum)`.
    public record Capped(MemoryLimits limits) implements InstanceMemory {
        public Capped {
            Objects.requireNonNull(limits, "limits");
        }
    }

    /// The module declares no memory of its own; there is nothing to cap.
    public record NoMemory() implements InstanceMemory {
    }

    /// `wasmMemoryMb` in 64 KiB pages.
    public static int capPages(int wasmMemoryMb) {
        long pages = (long) wasmMemoryMb * 1024 * 1024 / PAGE_BYTES;
        return (int) Math.min(pages, MemoryLimits.MAX_PAGES);
    }

    public static Result<InstanceMemory, Refusal> check(WasmModule module, String entrypoint, int capPages) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(entrypoint, "entrypoint");

        ImportSection imports = module.importSection();
        for (int i = 0; i < imports.importCount(); i++) {
            Import imp = imports.getImport(i);
            if (!importAllowed(imp)) {
                return Result.err(new ImportNotAllowed(imp.module(), imp.name(), imp.importType()));
            }
        }

        if (!exportsFunction(module, entrypoint)) {
            return Result.err(new EntrypointNotExported(entrypoint));
        }

        if (module.memorySection().isEmpty() || module.memorySection().get().memoryCount() == 0) {
            return Result.ok(new NoMemory());
        }
        MemoryLimits declared = module.memorySection().get().getMemory(0).limits();
        if (declared.initialPages() > capPages) {
            return Result.err(new MemoryOverCap(declared.initialPages(), capPages));
        }
        int max = Math.min(capPages, declared.maximumPages());
        return Result.ok(new Capped(new MemoryLimits(declared.initialPages(), max)));
    }

    private static boolean importAllowed(Import imp) {
        if (!ALLOWED_MODULES.contains(imp.module()) || imp.importType() != ExternalType.FUNCTION) {
            return false;
        }
        // extism:host/user is the host's own namespace: a name it does not define could
        // never link, so it is refused here rather than failing every first call.
        return !EXTISM_USER.equals(imp.module()) || HostFunctions.USER_NAMESPACE_NAMES.contains(imp.name());
    }

    private static boolean exportsFunction(WasmModule module, String name) {
        var exports = module.exportSection();
        for (int i = 0; i < exports.exportCount(); i++) {
            Export export = exports.getExport(i);
            if (export.name().equals(name) && export.exportType() == ExternalType.FUNCTION) {
                return true;
            }
        }
        return false;
    }

    private static long mib(int pages) {
        return (long) pages * PAGE_BYTES / (1024 * 1024);
    }
}
