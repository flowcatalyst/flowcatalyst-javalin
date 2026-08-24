package io.flowcatalyst.platform.principal;

import java.time.Instant;
import java.util.Objects;

/// One row of `iam_principal_roles` (spec §1–2): a role name plus the
/// source that owns the assignment. The source is a free string column
/// written by several subsystems (`SEED`, `BOOTSTRAP`, `MANUAL`, `null` on
/// legacy rows) and the role set is rewritten wholesale from the entity, so
/// it is carried verbatim rather than as an enum — an enum's lenient read
/// would silently rewrite a stored value it does not know. The three values
/// this package writes are the constants below.
///
/// @param role             role name (`platform:developer`, `orders:admin`…)
/// @param assignmentSource who assigned it; `null` on rows written before the column existed
/// @param assignedAt       when
public record RoleAssignment(String role, String assignmentSource, Instant assignedAt) {

    /// An administrator's explicit assignment (`AssignRoles`).
    public static final String ADMIN_ASSIGNED = "ADMIN_ASSIGNED";
    /// Reconciled from an identity provider's group claim (`SyncIdpRoles`).
    public static final String IDP_SYNC = "IDP_SYNC";
    /// Declared by an application SDK (`SyncPrincipals`).
    public static final String SDK_SYNC = "SDK_SYNC";

    public RoleAssignment {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(assignedAt, "assignedAt");
    }

    public boolean hasSource(String source) {
        return source.equals(assignmentSource);
    }
}
