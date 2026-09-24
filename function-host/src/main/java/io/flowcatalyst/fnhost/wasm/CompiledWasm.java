package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.fnhost.wasm.WasmModuleCheck.Capped;
import io.flowcatalyst.fnhost.wasm.WasmModuleCheck.InstanceMemory;
import io.flowcatalyst.fnhost.wasm.WasmModuleCheck.NoMemory;
import io.flowcatalyst.function.Config;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.HttpCaller;
import io.flowcatalyst.function.Secrets;
import org.extism.sdk.chicory.Kernel;
import org.extism.sdk.chicory.Manifest;
import org.extism.sdk.chicory.ManifestWasm;
import org.extism.sdk.chicory.Plugin;
import org.extism.sdk.chicory.http.HttpConfig;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.wasi.WasiOptions;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/// One Wasm version, parsed once and **compiled once**
/// (`docs/spec/function-wasm-runtime.md` §3): the module's own machine
/// factory (Endive compiler mode — wasm → JVM bytecode, the metaspace a load
/// costs) plus the process-wide compiled Extism kernel. Every
/// [PluginInstance] made from this shares that compiled code and owns only
/// its linear memory.
///
/// The runtime resource [io.flowcatalyst.fnhost.load.LoadedFunction] holds
/// for a Wasm version: [#close] drops the compiled code, so its classes can be
/// collected once the last instance is gone, and refuses any later
/// instantiation.
public final class CompiledWasm implements AutoCloseable {

    private final WasmModule module;
    private final InstanceMemory memory;
    private final int kernelMaxPages;
    private volatile Function<Instance, Machine> machines;

    private CompiledWasm(WasmModule module, InstanceMemory memory, int kernelMaxPages, Function<Instance, Machine> compiled) {
        this.module = module;
        this.memory = memory;
        this.kernelMaxPages = kernelMaxPages;
        Function<Instance, Machine> kernel = KernelMachine.get();
        WasmModule kernelModule = Kernel.module();
        // Identity, never equals(): WasmModule#equals compares content, and these two
        // are the exact objects every instance of this version is built from.
        this.machines = instance -> {
            if (instance.module() == module) {
                return compiled.apply(instance);
            }
            if (instance.module() == kernelModule) {
                return kernel.apply(instance);
            }
            throw new IllegalStateException("no compiled machine for this module");
        };
    }

    /// Compiles `module` (and, the first time in this process, the Extism
    /// kernel). Class definition happens here — the caller runs the metaspace
    /// guard before, and treats a metaspace `OutOfMemoryError` from here as a
    /// refused load.
    ///
    /// @param memory         the linear memory every instance gets ([WasmModuleCheck#check])
    /// @param kernelMaxPages the cap on each instance's Extism kernel memory — the same
    ///                       `wasmMemoryMb` as the guest's; required, never uncapped
    public static CompiledWasm compile(WasmModule module, InstanceMemory memory, int kernelMaxPages) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(memory, "memory");
        if (kernelMaxPages <= 0) throw new IllegalArgumentException("kernelMaxPages must be > 0, was " + kernelMaxPages);
        return new CompiledWasm(module, memory, kernelMaxPages, compileModule(module));
    }

    static Function<Instance, Machine> compileModule(WasmModule module) {
        // SILENT: the compiler's default fallback notice goes to System.err, and no
        // runtime output may reach the host's own streams (spec §1). A function too
        // large to compile still runs, interpreted.
        return MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .compile();
    }

    /// What one instance is wired to — the version's own context services,
    /// already narrowed to what the manifest declares.
    record Bindings(System.Logger logger, Config config, Set<String> declaredConfig, Secrets secrets,
                    Set<String> declaredSecrets, HttpCaller http, Events events, Clock clock) {
        Bindings {
            Objects.requireNonNull(logger, "logger");
            Objects.requireNonNull(config, "config");
            declaredConfig = Set.copyOf(declaredConfig);
            Objects.requireNonNull(secrets, "secrets");
            declaredSecrets = Set.copyOf(declaredSecrets);
            Objects.requireNonNull(http, "http");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(clock, "clock");
        }
    }

    /// A fresh, independent instance: its own linear memory under the cap,
    /// its own WASI (no preopened directories, no environment, no arguments;
    /// stdout/stderr to the version's logger), its own host functions.
    ///
    /// @throws IllegalStateException this version has been closed
    PluginInstance instantiate(Bindings bindings) {
        Function<Instance, Machine> compiled = machines;
        if (compiled == null) {
            throw new IllegalStateException("this Wasm version has been unloaded");
        }
        GuestOutputStream stdout = new GuestOutputStream(bindings.logger(), System.Logger.Level.INFO);
        GuestOutputStream stderr = new GuestOutputStream(bindings.logger(), System.Logger.Level.WARNING);
        WasiOptions wasi = WasiOptions.builder()
                .withStdout(stdout, false)
                .withStderr(stderr, false)
                .withClock(bindings.clock())
                .withRandom(new SecureRandom())
                .build();
        Manifest.Options options = new Manifest.Options()
                .withAoT(true)
                .withMachineFactory(compiled)
                .withWasi(wasi)
                .withConfigProvider(key -> bindings.declaredConfig().contains(key)
                        ? bindings.config().get(key).orElse(null)
                        : null)
                // The allowlist is the host's alone (AllowlistHttpAdapter over the
                // version's HttpCaller). Extism's own check denies every host when this
                // is empty — and a denial there is a trap — so it is opened to "*".
                .withAllowedHosts("*")
                .withHttpConfig(HttpConfig.builder()
                        .withJsonCodec(ExtismHttpJson::new)
                        .withClientAdapter(() -> new AllowlistHttpAdapter(bindings.http()))
                        .build())
                .withHttpResponseHeaders(true)
                .withKernelMemoryMaxPages(kernelMaxPages);
        switch (memory) {
            case Capped(MemoryLimits limits) ->
                    options = options.withMemoryLimits(limits.initialPages(), limits.maximumPages());
            case NoMemory ignored -> {
                // nothing to cap
            }
        }
        Manifest manifest = Manifest.ofWasms(ManifestWasm.fromModule(module).build()).withOptions(options).build();
        Plugin plugin = Plugin.ofManifest(manifest)
                .withHostFunctions(HostFunctions.forInstance(bindings.secrets(), bindings.declaredSecrets(),
                        bindings.events()))
                .withLogger(new EndiveLogger(bindings.logger()))
                .build();
        return new PluginInstance(plugin, stdout, stderr);
    }

    @Override
    public void close() {
        machines = null;
    }

    /// The Extism kernel compiled once per process and shared by every
    /// version — not a static initialiser, so a metaspace failure compiling it
    /// fails one load and is retried by the next, rather than poisoning the
    /// class for the life of the process.
    private static final class KernelMachine {
        private static volatile Function<Instance, Machine> compiled;

        static Function<Instance, Machine> get() {
            Function<Instance, Machine> c = compiled;
            if (c != null) {
                return c;
            }
            synchronized (KernelMachine.class) {
                if (compiled == null) {
                    compiled = compileModule(Kernel.module());
                }
                return compiled;
            }
        }
    }
}
