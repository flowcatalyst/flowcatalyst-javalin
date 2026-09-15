package io.flowcatalyst.platform.identityprovider.operations;

import java.util.List;

/// What [CreateIdentityProvider] reports back (spec §4): the new provider,
/// which of the listed domains got a fresh mapping (`domainsCreated`) versus
/// were re-pointed from another provider (`domainsClaimed`), and which
/// domains' mapping gained a primary client from this request
/// (`domainsLinked` — a new CLIENT-scoped mapping, or an existing one,
/// claimed or already routed here, that had no client; a claimed-and-linked
/// domain is in both `domainsClaimed` and `domainsLinked`).
public record CreateResult(String identityProviderId, String code, List<String> domainsCreated,
                           List<String> domainsClaimed, List<String> domainsLinked) {
    public CreateResult {
        domainsCreated = domainsCreated == null ? List.of() : List.copyOf(domainsCreated);
        domainsClaimed = domainsClaimed == null ? List.of() : List.copyOf(domainsClaimed);
        domainsLinked = domainsLinked == null ? List.of() : List.copyOf(domainsLinked);
    }
}
