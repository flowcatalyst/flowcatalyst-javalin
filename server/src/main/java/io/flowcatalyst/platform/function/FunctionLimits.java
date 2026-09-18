package io.flowcatalyst.platform.function;

/// The platform's default function limits (spec `function-registry.md`
/// §4.6), read from `Env` as one component. A manifest without an explicit
/// limit gets these, clamped to the client's ceiling ([ClientCeilings]);
/// `maxWarmPerHost` is carried for package B's warm-capacity check and is
/// unused by package A.
///
/// @param maxDurationMs  default for `FC_FN_DEFAULT_MAX_DURATION_MS`
/// @param maxConcurrency default for `FC_FN_DEFAULT_MAX_CONCURRENCY`
/// @param wasmMemoryMb   default for `FC_FN_DEFAULT_WASM_MEMORY_MB`
/// @param dbPoolSize     default for `FC_FN_DEFAULT_DB_POOL_SIZE`
/// @param maxWarmPerHost default for `FC_FN_MAX_WARM_PER_HOST`
public record FunctionLimits(int maxDurationMs, int maxConcurrency, int wasmMemoryMb, int dbPoolSize,
                              int maxWarmPerHost) {

    public static final int DEFAULT_MAX_DURATION_MS = 30_000;
    public static final int DEFAULT_MAX_CONCURRENCY = 32;
    public static final int DEFAULT_WASM_MEMORY_MB = 64;
    public static final int DEFAULT_DB_POOL_SIZE = 4;
    public static final int DEFAULT_MAX_WARM_PER_HOST = 200;

    /// @throws IllegalArgumentException any component is `<= 0`, naming it
    public FunctionLimits {
        requirePositive(maxDurationMs, "maxDurationMs");
        requirePositive(maxConcurrency, "maxConcurrency");
        requirePositive(wasmMemoryMb, "wasmMemoryMb");
        requirePositive(dbPoolSize, "dbPoolSize");
        requirePositive(maxWarmPerHost, "maxWarmPerHost");
    }

    /// The platform defaults (spec §4.6 table) — what `Env` falls back to
    /// when the corresponding `FC_FN_*` variable is unset.
    public static FunctionLimits defaults() {
        return new FunctionLimits(DEFAULT_MAX_DURATION_MS, DEFAULT_MAX_CONCURRENCY, DEFAULT_WASM_MEMORY_MB,
                DEFAULT_DB_POOL_SIZE, DEFAULT_MAX_WARM_PER_HOST);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be > 0, got " + value);
    }
}
