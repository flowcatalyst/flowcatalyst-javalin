package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.operations.IdentityProviderEvents.IdentityProviderUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.util.ArrayList;
import java.util.List;
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
                    DomainRouting.validateScope(cmd.mappingScope(), cmd.primaryClientId());
                })
                // Identity providers are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    IdentityProvider existing = Access.byId(repo, cmd.id());
                    // Already restricted to legal values in Validate above.
                    var resolved = DomainRouting.validateScope(cmd.mappingScope(), cmd.primaryClientId());
                    List<EmailDomain> domains = cmd.allowedEmailDomains() == null ? null : DomainRouting.normalise(cmd.allowedEmailDomains());
                    if (domains != null) {
                        routing.requireScopeForNewDomains(domains, resolved.scope()); // before any row is written
                    }

                    IdentityProvider ip = existing.update(new IdentityProvider.Changes(
                            cmd.name(), cmd.oidcIssuerUrl(), cmd.oidcClientId(), cmd.oidcClientSecretRef(),
                            cmd.oidcMultiTenant(), cmd.oidcIssuerPattern(), cmd.syncRolesFromIdp(), cmd.allowedRoleIds(),
                            cmd.allowedTenantIds()));
                    // Backlog item 3: the mappings routed here after this update must all be pinned —
                    // switching to multi-tenant, or clearing the provider's tenants, included.
                    TenantPin.require(ip, domains == null
                            ? routing.routedTo(ip.id()).stream().map(TenantPin.Routed::of).toList()
                            : domains.stream()
                                    .map(d -> new TenantPin.Routed(d.value(),
                                            mappings.findByEmailDomain(d.value()).map(m -> m.requiredOidcTenantId()).orElse(null)))
                                    .toList());
                    scoped.commit(ip, repo, IdentityProviderUpdated.of(ec, ip), cmd);

                    var created = new ArrayList<String>();
                    var claimed = new ArrayList<String>();
                    var linked = new ArrayList<String>();
                    var released = new ArrayList<String>();
                    int usersReset = 0;
                    if (domains == null) {
                        return new UpdateResult(ip.id(), ip.code(), created, claimed, linked, released, usersReset);
                    }

                    Set<String> desiredSet = domains.stream().map(EmailDomain::value).collect(toSet());
                    var current = routing.routedTo(ip.id()); // read before any change

                    for (var domain : domains) {
                        var mr = routing.mapDomain(scoped, ip, domain, resolved.scope(), resolved.primaryClientId(), ec, cmd);
                        if (mr.created()) created.add(domain.value());
                        if (mr.claimed()) claimed.add(domain.value());
                        if (mr.linked()) linked.add(domain.value());
                    }

                    // Removals fall back to the internal provider — unless this *is* the internal provider.
                    var stale = ip.isSeededInternal() ? List.<EmailDomainMapping>of()
                            : current.stream().filter(m -> !desiredSet.contains(m.emailDomain())).toList();
                    if (!stale.isEmpty()) {
                        IdentityProvider internal = internalProvider(repo, stale.getFirst().emailDomain());
                        for (var m : stale) {
                            usersReset += routing.moveTo(scoped, m, internal, ec, cmd);
                            released.add(m.emailDomain());
                        }
                    }
                    return new UpdateResult(ip.id(), ip.code(), created, claimed, linked, released, usersReset);
                });
    }

    /// The seeded internal provider a released domain falls back to; its
    /// absence is a broken install, not a client error (spec §4, §6).
    private static IdentityProvider internalProvider(IdentityProviderRepository repo, String releasingDomain) {
        return repo.findByCode(IdentityProvider.INTERNAL_CODE).orElseThrow(() -> UseCaseException.internal("SEED",
                "internal identity provider missing; cannot release domain '" + releasingDomain + "'", null));
    }
}
