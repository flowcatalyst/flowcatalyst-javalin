package io.flowcatalyst.platform.principal;

/// The principal kind: a human `USER` (identified by email) or the `SERVICE`
/// identity of a service account. The constant name is the stored and wire
/// string (spec §1).
public enum PrincipalType {
    USER, SERVICE;

    /// Lenient reader for stored values: anything but `SERVICE` is `USER`.
    public static PrincipalType parse(String s) {
        return "SERVICE".equals(s) ? SERVICE : USER;
    }
}
