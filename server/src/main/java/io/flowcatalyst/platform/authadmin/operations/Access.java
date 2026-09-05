package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.AnchorDomain;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.ClientAuthConfig;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute
/// phase. All three aggregates are anchor-only platform configuration with
/// no per-resource scope (spec §1): there is nothing to check after the
/// load, which is why those operations declare `Authorize.publicAccess()`
/// and rely on the handler's `requireAnchor`.
final class Access {

    private Access() {
    }

    /// @throws UseCaseException not-found `AnchorDomain_NOT_FOUND`
    static AnchorDomain anchorDomainById(AnchorDomainRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("AnchorDomain", id));
    }

    /// @throws UseCaseException not-found `AuthConfig_NOT_FOUND`
    static ClientAuthConfig authConfigById(ClientAuthConfigRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("AuthConfig", id));
    }

    /// @throws UseCaseException not-found `IdpRoleMapping_NOT_FOUND`
    static IdpRoleMapping idpRoleMappingById(IdpRoleMappingRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("IdpRoleMapping", id));
    }
}
