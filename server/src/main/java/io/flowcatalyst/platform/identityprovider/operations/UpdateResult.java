package io.flowcatalyst.platform.identityprovider.operations;

import java.util.List;

/// What [UpdateIdentityProvider] reports back (spec §4): the domains newly
/// mapped, claimed from another provider, linked to a primary client from
/// this request (see [CreateResult#domainsLinked]), released back to the
/// internal provider, and how many OIDC-provisioned users those releases
/// converted back to internal auth.
public record UpdateResult(String identityProviderId, String code, List<String> domainsCreated,
                           List<String> domainsClaimed, List<String> domainsLinked, List<String> domainsReleased,
                           int usersReset) {
    public UpdateResult {
        domainsCreated = domainsCreated == null ? List.of() : List.copyOf(domainsCreated);
        domainsClaimed = domainsClaimed == null ? List.of() : List.copyOf(domainsClaimed);
        domainsLinked = domainsLinked == null ? List.of() : List.copyOf(domainsLinked);
        domainsReleased = domainsReleased == null ? List.of() : List.copyOf(domainsReleased);
    }
}
