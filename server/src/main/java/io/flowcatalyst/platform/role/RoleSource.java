package io.flowcatalyst.platform.role;

/// Where a role was authored: `CODE` (the built-in catalogue — immutable
/// through the admin API, owned by `SyncPlatformRoles`), `DATABASE` (admin
/// API) or `SDK` (application role sync — the only rows that sync may
/// create, update or remove). The constant name is the stored and wire
/// string.
public enum RoleSource {
    CODE, DATABASE, SDK;

    /// Lenient reader for stored values and the `/by-source/{source}` path
    /// segment: unknown → `DATABASE` (spec §1, open question 5).
    public static RoleSource parse(String s) {
        return switch (s == null ? "" : s) {
            case "CODE" -> CODE;
            case "SDK" -> SDK;
            default -> DATABASE;
        };
    }
}
