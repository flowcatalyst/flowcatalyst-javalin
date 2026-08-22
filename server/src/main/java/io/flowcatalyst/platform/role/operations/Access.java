package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id / by-name write operation's
/// execute phase. Roles are global (spec §5): there is no per-resource scope
/// to check after the load, which is why those operations declare
/// `Authorize.publicAccess()` and rely on the handler's coarse gate.
final class Access {

    private Access() {
    }

    /// The role with TSID `id`.
    ///
    /// @throws UseCaseException not-found `Role_NOT_FOUND`
    static Role byId(RoleRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Role", id));
    }

    /// The role with canonical `name`.
    ///
    /// @throws UseCaseException not-found `Role_NOT_FOUND`
    static Role byName(RoleRepository repo, String name) {
        return repo.findByName(name).orElseThrow(() -> UseCaseException.resourceNotFound("Role", name));
    }
}
