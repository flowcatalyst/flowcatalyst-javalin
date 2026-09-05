package io.flowcatalyst.platform.platformconfig;

/// The visibility scope of a platform-config value: `GLOBAL` (one value for
/// the platform) or `CLIENT` (one value per client). Derived from the
/// presence of a client id — see [ConfigCoordinate] — never chosen on its own
/// (spec §1.1). The constant name is the stored and wire string.
public enum ConfigScope {
    GLOBAL, CLIENT;

    /// Strict reader for stored values (spec §1.1, X-06): never a silent
    /// default. See [PlatformConfigRepository]'s row mapper, which wraps
    /// [UnrecognisedConfigScopeException] in [CorruptPlatformConfigException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedConfigScopeException `s` is `null` or not
    ///                                          `GLOBAL` / `CLIENT`
    public static ConfigScope parse(String s) {
        return switch (s) {
            case "GLOBAL" -> GLOBAL;
            case "CLIENT" -> CLIENT;
            case null, default -> throw new UnrecognisedConfigScopeException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedConfigScopeException extends RuntimeException {
        public UnrecognisedConfigScopeException(String raw) {
            super("unrecognised config scope: " + raw);
        }
    }
}
