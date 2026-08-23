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
}
