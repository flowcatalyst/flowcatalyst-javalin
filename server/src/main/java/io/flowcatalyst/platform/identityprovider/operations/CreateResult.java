package io.flowcatalyst.platform.identityprovider.operations;

import java.util.List;

/// What [CreateIdentityProvider] reports back (spec §4): the new provider
/// and which of the listed domains got a fresh mapping (`domainsCreated`)
/// versus were re-pointed from another provider (`domainsClaimed`).
public record CreateResult(String identityProviderId, String code, List<String> domainsCreated,
                           List<String> domainsClaimed) {
    public CreateResult {
        domainsCreated = domainsCreated == null ? List.of() : List.copyOf(domainsCreated);
        domainsClaimed = domainsClaimed == null ? List.of() : List.copyOf(domainsClaimed);
    }
}
