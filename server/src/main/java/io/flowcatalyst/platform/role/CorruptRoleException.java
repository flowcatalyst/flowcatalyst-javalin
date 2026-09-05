package io.flowcatalyst.platform.role;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `iam_roles` whose `source` column holds a value
/// [RoleSource#parse] does not recognise (X-06: never a silent default).
/// Carries the offending row's id.
public final class CorruptRoleException extends CorruptRowException {

    public CorruptRoleException(String roleId, Throwable cause) {
        super("role", roleId, cause);
    }
}
