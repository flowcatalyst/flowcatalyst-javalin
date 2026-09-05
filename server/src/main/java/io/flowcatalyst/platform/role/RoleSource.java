package io.flowcatalyst.platform.role;

/// Where a role was authored: `CODE` (the built-in catalogue — immutable
/// through the admin API, owned by `SyncPlatformRoles`), `DATABASE` (admin
/// API) or `SDK` (application role sync — the only rows that sync may
/// create, update or remove). The constant name is the stored and wire
/// string.
public enum RoleSource {
    CODE, DATABASE, SDK;

    /// Strict reader for STORED values (spec §1, X-06): never a silent
    /// default. See [RoleRepository]'s row mapper, which wraps
    /// [UnrecognisedRoleSourceException] in [CorruptRoleException] carrying
    /// the row id.
    ///
    /// The `/by-source/{source}` path segment ([#parseWire]) stays the
    /// pre-X-06 lenient rule (spec §1, open question 5) — wire input, out
    /// of scope for X-06.
    ///
    /// @throws UnrecognisedRoleSourceException `s` is `null` or not one of
    ///                                         `CODE` / `DATABASE` / `SDK`
    public static RoleSource parse(String s) {
        return switch (s) {
            case "CODE" -> CODE;
            case "DATABASE" -> DATABASE;
            case "SDK" -> SDK;
            case null, default -> throw new UnrecognisedRoleSourceException(s);
        };
    }

    /// The pre-X-06 lenient reader, kept ONLY for the `/by-source/{source}`
    /// path segment: unknown → `DATABASE` (spec §1, open question 5).
    /// [io.flowcatalyst.platform.role.api.RoleApi] is the one caller.
    public static RoleSource parseWire(String s) {
        return switch (s == null ? "" : s) {
            case "CODE" -> CODE;
            case "SDK" -> SDK;
            default -> DATABASE;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedRoleSourceException extends RuntimeException {
        public UnrecognisedRoleSourceException(String raw) {
            super("unrecognised role source: " + raw);
        }
    }
}
