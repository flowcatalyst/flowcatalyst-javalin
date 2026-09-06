package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.sdk.usecase.UseCaseException;

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

    /// The set command's `valueType` field: exactly `PLAIN` or `SECRET`,
    /// anything else is a validation error — owner ruling 2026-09-06 #19
    /// (X-06 at the wire, Go's `ParseValueType`). The caller decides what an
    /// absent value means; this reader never sees `null`.
    /// [io.flowcatalyst.platform.platformconfig.operations.SetProperty] is
    /// the one caller.
    ///
    /// @throws UseCaseException validation `INVALID_VALUE_TYPE`
    public static ConfigValueType parseWire(String s) {
        return switch (s) {
            case "PLAIN" -> PLAIN;
            case "SECRET" -> SECRET;
            case null, default -> throw UseCaseException.validation("INVALID_VALUE_TYPE", "valueType must be PLAIN or SECRET");
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
