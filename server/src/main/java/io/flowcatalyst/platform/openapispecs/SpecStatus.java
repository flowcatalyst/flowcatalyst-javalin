package io.flowcatalyst.platform.openapispecs;

/// Lifecycle of one stored OpenAPI document (spec §1): an application has at
/// most one `CURRENT` row; every earlier sync is kept `ARCHIVED`. The
/// constant name is the stored string.
public enum SpecStatus {
    CURRENT,
    ARCHIVED;

    /// The lenient stored reader: unknown → `CURRENT` (spec §1).
    public static SpecStatus parse(String value) {
        return ARCHIVED.name().equals(value) ? ARCHIVED : CURRENT;
    }
}
