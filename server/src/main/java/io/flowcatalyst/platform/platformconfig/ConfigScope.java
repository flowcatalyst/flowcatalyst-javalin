package io.flowcatalyst.platform.platformconfig;

/// The visibility scope of a platform-config value: `GLOBAL` (one value for
/// the platform) or `CLIENT` (one value per client). Derived from the
/// presence of a client id — see [ConfigCoordinate] — never chosen on its own
/// (spec §1.1). The constant name is the stored and wire string.
public enum ConfigScope {
    GLOBAL, CLIENT;

    /// Lenient reader for stored values: unknown → `GLOBAL` (spec §1.1).
    public static ConfigScope parse(String s) {
        return switch (s == null ? "" : s) {
            case "CLIENT" -> CLIENT;
            default -> GLOBAL;
        };
    }
}
