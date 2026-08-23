package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.operations.IdentityProviderEvents.IdentityProviderUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.util.ArrayList;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/// Updates a provider's settings, emits [IdentityProviderUpdated], and —
/// when the command carries a domain set — reconciles the mapping table
/// with it: additions are mapped (created or claimed, like create),
/// removals fall back to the internal provider, converting the domain's
/// OIDC-provisioned users back to internal auth (spec §4). One transaction;
/// a [TxOperation] because it spans two aggregates and reports the domain
/// deltas.
public final class UpdateIdentityProvider {

    private UpdateIdentityProvider() {
    }

    public static TxOperation<UpdateCommand, UpdateResult> of(IdentityProviderRepository repo,
                                                              EmailDomainMappingRepository mappings) {
        var routing = new DomainRouting(mappings);
        return TxOperation.<UpdateCommand, UpdateResult>named("UpdateIdentityProvider")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                    DomainRouting.normalise(cmd.allowedEmailDomains());
                })
                // Identity providers are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    IdentityProvider ip = Access.byId(repo, cmd.id()).update(new IdentityProvider.Changes(
                            cmd.name(), cmd.oidcIssuerUrl(), cmd.oidcClientId(), cmd.oidcClientSecretRef(),
                            cmd.oidcMultiTenant(), cmd.oidcIssuerPattern(), cmd.syncRolesFromIdp(), cmd.allowedRoleIds()));
                    scoped.commit(ip, repo, IdentityProviderUpdated.of(ec, ip), cmd);

                    var created = new ArrayList<String>();
                    var claimed = new ArrayList<String>();
                    var released = new ArrayList<String>();
                    int usersReset = 0;
                    if (cmd.allowedEmailDomains() == null) {
                        return new UpdateResult(ip.id(), ip.code(), created, claimed, released, usersReset);
                    }

                    var desired = DomainRouting.normalise(cmd.allowedEmailDomains());
                    Set<String> desiredSet = desired.stream().map(EmailDomain::value).collect(toSet());
                    var current = routing.routedTo(ip.id()); // read before any change

                    for (var domain : desired) {
                        switch (routing.mapDomain(scoped, ip, domain, cmd.primaryClientId(), ec, cmd)) {
                            case CREATED -> created.add(domain.value());
                            case CLAIMED -> claimed.add(domain.value());
                            case UNCHANGED -> { }
                        }
                    }

                    // Removals fall back to the internal provider — unless this *is* the internal provider.
                    if (!ip.isSeededInternal()) {
                        IdentityProvider internal = null;
                        for (var m : current) {
                            if (desiredSet.contains(m.emailDomain())) continue;
                            if (internal == null) {
                                internal = repo.findByCode(IdentityProvider.INTERNAL_CODE).orElseThrow(() -> UseCaseException.internal("SEED",
                                        "internal identity provider missing; cannot release domain '" + m.emailDomain() + "'", null));
                            }
                            usersReset += routing.moveTo(scoped, m, internal, ec, cmd);
                            released.add(m.emailDomain());
                        }
                    }
                    return new UpdateResult(ip.id(), ip.code(), created, claimed, released, usersReset);
                });
    }
}
