package io.flowcatalyst.platform.emaildomainmapping.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute
/// phase. Email-domain mappings are anchor-only platform configuration with
/// no per-resource scope (spec §5): there is nothing to check after the
/// load, which is why those operations declare `Authorize.publicAccess()`
/// and rely on the handler's `requireAnchor`.
final class Access {

    private Access() {
    }

    /// The mapping with TSID `id`.
    ///
    /// @throws UseCaseException not-found `EmailDomainMapping_NOT_FOUND`
    static EmailDomainMapping byId(EmailDomainMappingRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("EmailDomainMapping", id));
    }

    /// Owner ruling 2026-09-25 (backlog item 3): a mapping to a multi-tenant
    /// provider that pins no tenants itself must carry its own
    /// `requiredOidcTenantId`.
    ///
    /// @throws UseCaseException validation `TENANT_PIN_REQUIRED`
    static EmailDomainMapping requireTenantPin(EmailDomainMappingRepository repo, EmailDomainMapping m) {
        String pin = m.requiredOidcTenantId();
        if ((pin == null || pin.isBlank()) && repo.mappingNeedsOwnTenantPin(m.identityProviderId())) {
            throw UseCaseException.validation("TENANT_PIN_REQUIRED",
                    "Email domain '" + m.emailDomain() + "' routes to a multi-tenant identity provider that pins no "
                            + "tenant: set requiredOidcTenantId on the mapping, or allowedTenantIds on the provider");
        }
        return m;
    }
}
