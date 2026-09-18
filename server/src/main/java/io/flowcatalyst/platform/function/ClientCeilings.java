package io.flowcatalyst.platform.function;

/// The per-client ceilings a manifest's limits may not exceed (spec
/// `function-registry.md` §4.6). `ClientPolicy.ceilings(FunctionLimits)`
/// resolves each column against the platform default; a client with no
/// policy row gets [#of], every ceiling equal to the platform default — so
/// out of the box a function may lower a limit but never raise one. A
/// ceiling set below the default lowers the applied default with it: the
/// effective value of an absent limit is `min(default, ceiling)`.
///
/// @param maxDurationMs  ceiling for `limits.maxDurationMs` and a route's `timeoutMs`
/// @param maxConcurrency ceiling for `limits.maxConcurrency`
/// @param wasmMemoryMb   ceiling for `limits.wasmMemoryMb`
/// @param dbPoolSize     ceiling for `db[].poolSize`
public record ClientCeilings(int maxDurationMs, int maxConcurrency, int wasmMemoryMb, int dbPoolSize) {

    /// @throws IllegalArgumentException any component is `<= 0`, naming it
    public ClientCeilings {
        requirePositive(maxDurationMs, "maxDurationMs");
        requirePositive(maxConcurrency, "maxConcurrency");
        requirePositive(wasmMemoryMb, "wasmMemoryMb");
        requirePositive(dbPoolSize, "dbPoolSize");
    }

    /// A client with no policy row: every ceiling equal to the platform default.
    public static ClientCeilings of(FunctionLimits defaults) {
        return new ClientCeilings(defaults.maxDurationMs(), defaults.maxConcurrency(),
                defaults.wasmMemoryMb(), defaults.dbPoolSize());
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be > 0, got " + value);
    }
}
