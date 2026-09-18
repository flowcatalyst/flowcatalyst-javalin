package io.flowcatalyst.platform.function;

/// A function's lifecycle status (spec `function-registry.md` §6.1): `ACTIVE`
/// or `DISABLED`. The constant name is the stored string per
/// `CONVENTIONS.md` §2.
public enum FunctionStatus {
    ACTIVE, DISABLED;

    /// Stored reader — exact constant name.
    ///
    /// @throws IllegalArgumentException `raw` is not `ACTIVE` or `DISABLED`
    public static FunctionStatus parse(String raw) {
        return switch (raw) {
            case "ACTIVE" -> ACTIVE;
            case "DISABLED" -> DISABLED;
            case null, default -> throw new IllegalArgumentException("unrecognised function status: " + raw);
        };
    }
}
