package io.flowcatalyst.platform.serviceaccount;

import java.time.Instant;
import java.util.Objects;

/// One role held by the service account's linked `SERVICE` principal, read
/// through the same `iam_principal_roles` rows the principal's own
/// `RoleAssignment` models (spec §2.2). `clientId` and `assignedBy` have no
/// backing column on that junction and are always `null` here — matching Go,
/// which never populates them either.
///
/// Named `roleName` (not `role`) because the wire contract is: Go's struct
/// field is `Role` but its JSON tag is `roleName`, and CONVENTIONS says the
/// wire name is the contract — naming the record component after it removes
/// a mapping step at the API boundary.
///
/// @param roleName         role name (`platform:developer`, `orders:admin`…)
/// @param clientId         always `null` (no column); carried for wire parity
/// @param assignmentSource who/what assigned it (`ADMIN_ASSIGNED`, `IDP_SYNC`, `SDK_SYNC`, `null` on legacy rows)
/// @param assignedAt       when
/// @param assignedBy       always `null` (no column); carried for wire parity
public record RoleAssignment(String roleName, String clientId, String assignmentSource, Instant assignedAt, String assignedBy) {

    public RoleAssignment {
        Objects.requireNonNull(roleName, "roleName");
        Objects.requireNonNull(assignedAt, "assignedAt");
    }
}
