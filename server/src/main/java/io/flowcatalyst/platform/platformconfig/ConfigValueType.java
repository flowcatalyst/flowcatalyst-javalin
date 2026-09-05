package io.flowcatalyst.platform.platformconfig;

/// Whether a platform-config value is plain text or a secret. A `SECRET`
/// value is masked on read for non-anchor principals (spec §4). The constant
/// name is the stored and wire string.
public enum ConfigValueType {
    PLAIN, SECRET;

    /// Strict reader for STORED values (spec §1.1, X-06): never a silent
    /// default. See [PlatformConfigRepository]'s row mapper, which wraps
    /// [UnrecognisedConfigValueTypeException] in
    /// [CorruptPlatformConfigException] carrying the row id.
    ///
    /// The set command's `valueType` field ([#parseWire]) stays the
    /// pre-X-06 lenient rule (spec §1.1, open question 6) — wire input,
    /// out of scope for X-06.
    ///
    /// @throws UnrecognisedConfigValueTypeException `s` is `null` or not
    ///                                              `PLAIN` / `SECRET`
    public static ConfigValueType parse(String s) {
        return switch (s) {
            case "PLAIN" -> PLAIN;
            case "SECRET" -> SECRET;
            case null, default -> throw new UnrecognisedConfigValueTypeException(s);
        };
    }

    /// The pre-X-06 lenient reader, kept ONLY for the set command's
    /// `valueType` field: unknown → `PLAIN` (spec §1.1, open question 6).
    /// [io.flowcatalyst.platform.platformconfig.operations.SetProperty] is
    /// the one caller.
    public static ConfigValueType parseWire(String s) {
        return switch (s == null ? "" : s) {
            case "SECRET" -> SECRET;
            default -> PLAIN;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedConfigValueTypeException extends RuntimeException {
        public UnrecognisedConfigValueTypeException(String raw) {
            super("unrecognised config value type: " + raw);
        }
    }
}
