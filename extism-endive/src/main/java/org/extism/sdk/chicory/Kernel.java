package org.extism.sdk.chicory;

import run.endive.runtime.ExportFunction;
import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

import java.util.List;
import java.util.function.Function;

public class Kernel {

    static final String IMPORT_MODULE_NAME = "extism:host/env";
    final run.endive.runtime.Memory instanceMemory;
    final ExportFunction alloc;
    final ExportFunction free;
    final ExportFunction length;
    final ExportFunction lengthUnsafe;
    final ExportFunction loadU8;
    final ExportFunction loadU64;
    final ExportFunction inputLoadU8;
    final ExportFunction inputLoadU64;
    final ExportFunction storeU8;
    final ExportFunction storeU64;
    final ExportFunction inputSet;
    final ExportFunction inputLen;
    final ExportFunction inputOffset;
    final ExportFunction outputLen;
    final ExportFunction outputOffset;
    final ExportFunction outputSet;
    final ExportFunction reset;
    final ExportFunction errorSet;
    final ExportFunction errorGet;
    final ExportFunction memoryBytes;

    public Kernel() {
        this(null, null);
    }

    Kernel(Function<Instance, Machine> machineFactory) {
        this(machineFactory, null);
    }

    /// FlowCatalyst change (see NOTICE): `maxPages` caps the kernel's OWN linear memory — it holds
    /// the input, the output and every block a guest allocates through the kernel, so without a
    /// cap a guest looping `alloc` grows the host's heap to the 4 GiB Wasm limit. The initial size
    /// stays the kernel module's declared minimum. `null` = upstream behaviour (uncapped).
    Kernel(Function<Instance, Machine> machineFactory, Integer maxPages) {
        Instance kernel = instance(machineFactory, maxPages);
        instanceMemory = kernel.memory();
        alloc = kernel.export("alloc");
        free = kernel.export("free");
        length = kernel.export("length");
        lengthUnsafe = kernel.export("length_unsafe");
        loadU8 = kernel.export("load_u8");
        loadU64 = kernel.export("load_u64");
        inputLoadU8 = kernel.export("input_load_u8");
        inputLoadU64 = kernel.export("input_load_u64");
        storeU8 = kernel.export("store_u8");
        storeU64 = kernel.export("store_u64");
        inputSet = kernel.export("input_set");
        inputLen = kernel.export("input_length");
        inputOffset = kernel.export("input_offset");
        outputLen = kernel.export("output_length");
        outputOffset = kernel.export("output_offset");
        outputSet = kernel.export("output_set");
        reset = kernel.export("reset");
        errorSet = kernel.export("error_set");
        errorGet = kernel.export("error_get");
        memoryBytes = kernel.export("memory_bytes");
    }

    // FlowCatalyst change (see NOTICE): the kernel is parsed ONCE per process, not once per
    // plugin instance, so a machine factory keyed by module identity (CachedAotMachineFactory,
    // or a host's own) compiles it once instead of once per instance.
    private static final class ParsedKernel {
        static final WasmModule MODULE =
                Parser.parse(Kernel.class.getClassLoader().getResourceAsStream("extism-runtime.wasm"));
    }

    /// FlowCatalyst change (see NOTICE): the one parsed kernel module every plugin
    /// instance shares — what a host's machine factory must recognise and compile.
    public static WasmModule module() {
        return ParsedKernel.MODULE;
    }

    private static Instance instance(Function<Instance, Machine> machineFactory, Integer maxPages) {
        WasmModule module = ParsedKernel.MODULE;
        if (machineFactory != null && machineFactory instanceof CachedAotMachineFactory) {
            ((CachedAotMachineFactory) machineFactory).compile(module);
        }
        var builder = Instance.builder(module).withMachineFactory(machineFactory);
        if (maxPages != null) {
            int min = module.memorySection().map(ms -> ms.getMemory(0).limits().initialPages()).orElse(0);
            builder = builder.withMemoryLimits(new run.endive.wasm.types.MemoryLimits(min, Math.max(min, maxPages)));
        }
        return builder.build();
    }

    public void setInput(byte[] input) {
        reset.apply();
        var ptr = alloc.apply(input.length)[0];
        instanceMemory.write((int) ptr, input);
        inputSet.apply(ptr, input.length);
    }

    byte[] getOutput() {
        var ptr = outputOffset.apply()[0];
        var len = outputLen.apply()[0];
        return instanceMemory.readBytes((int) ptr, (int) len);
    }

    public String getError() {
        long ptr = errorGet.apply()[0];
        long len = length.apply(ptr)[0];
        return instanceMemory.readString((int) ptr, (int) len);
    }

    HostFunction[] toHostFunctions() {
        var hostFunctions = new HostFunction[20];
        int count = 0;

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "alloc",
                        FunctionType.of(List.of(ValType.I64), List.of(ValType.I64)),
                        (Instance instance, long... args) -> alloc.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "free",
                        FunctionType.of(List.of(ValType.I64), List.of()),
                        (Instance instance, long... args) -> free.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "length",
                        FunctionType.of(List.of(ValType.I64), List.of(ValType.I64)),
                        (Instance instance, long... args) -> length.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "length_unsafe",
                        FunctionType.of(List.of(ValType.I64), List.of(ValType.I64)),
                        (Instance instance, long... args) -> lengthUnsafe.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "load_u8",
                        FunctionType.of(List.of(ValType.I64), List.of(ValType.I32)),
                        (Instance instance, long... args) -> loadU8.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "load_u64",
                        FunctionType.of(List.of(ValType.I64), List.of(ValType.I64)),
                        (Instance instance, long... args) -> loadU64.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "input_load_u8",
                        FunctionType.of(List.of(ValType.I64), List.of(ValType.I32)),
                        (Instance instance, long... args) -> inputLoadU8.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "input_load_u64",
                        FunctionType.of(List.of(ValType.I64), List.of(ValType.I64)),
                        (Instance instance, long... args) -> inputLoadU64.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "store_u8",
                        FunctionType.of(List.of(ValType.I64, ValType.I32), List.of()),
                        (Instance instance, long... args) -> storeU8.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "store_u64",
                        FunctionType.of(List.of(ValType.I64, ValType.I64), List.of()),
                        (Instance instance, long... args) -> storeU64.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "input_set",
                        FunctionType.of(List.of(ValType.I64, ValType.I64), List.of()),
                        (Instance instance, long... args) -> inputSet.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "input_length",
                        FunctionType.of(List.of(), List.of(ValType.I64)),
                        (Instance instance, long... args) -> inputLen.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "input_offset",
                        FunctionType.of(List.of(), List.of(ValType.I64)),
                        (Instance instance, long... args) -> inputOffset.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "output_set",
                        FunctionType.of(List.of(ValType.I64, ValType.I64), List.of()),
                        (Instance instance, long... args) -> outputSet.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "output_length",
                        FunctionType.of(List.of(), List.of(ValType.I64)),
                        (Instance instance, long... args) -> outputLen.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "output_offset",
                        FunctionType.of(List.of(), List.of(ValType.I64)),
                        (Instance instance, long... args) -> outputOffset.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "reset",
                        FunctionType.of(List.of(), List.of()),
                        (Instance instance, long... args) -> reset.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "error_set",
                        FunctionType.of(List.of(ValType.I64), List.of()),
                        (Instance instance, long... args) -> errorSet.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "error_get",
                        FunctionType.of(List.of(), List.of(ValType.I64)),
                        (Instance instance, long... args) -> errorGet.apply(args)
                );

        hostFunctions[count++]
                = new HostFunction(
                        IMPORT_MODULE_NAME,
                        "memory_bytes",
                        FunctionType.of(List.of(), List.of(ValType.I64)),
                        (Instance instance, long... args) -> memoryBytes.apply(args)
                );
        return hostFunctions;
    }
}
