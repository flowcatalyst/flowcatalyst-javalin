package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.identityprovider.operations.IdentityProviderEvents.IdentityProviderCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.util.ArrayList;

/// Creates an identity provider (unique by code), emits
/// [IdentityProviderCreated], and routes each listed email domain to it —
/// created when unknown, claimed when mapped elsewhere — all in one
/// transaction (spec §4). A [TxOperation] because it spans two aggregates
/// and reports which domains were created versus claimed.
public final class CreateIdentityProvider {

    private CreateIdentityProvider() {
    }

    public static TxOperation<CreateCommand, CreateResult> of(IdentityProviderRepository repo,
                                                              EmailDomainMappingRepository mappings) {
        var routing = new DomainRouting(mappings);
        return TxOperation.<CreateCommand, CreateResult>named("CreateIdentityProvider")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.code(), "CODE_REQUIRED", "code is required");
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                    if (IdentityProviderType.parseWire(cmd.type()) == IdentityProviderType.OIDC) {
                        UseCaseException.requireNonBlank(cmd.oidcIssuerUrl(), "OIDC_ISSUER_REQUIRED", "OIDC IDPs require oidcIssuerUrl");
                        UseCaseException.requireNonBlank(cmd.oidcClientId(), "OIDC_CLIENT_ID_REQUIRED", "OIDC IDPs require oidcClientId");
                    }
                    DomainRouting.normalise(cmd.allowedEmailDomains());
                })
                // Identity providers are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    if (repo.findByCode(cmd.code()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Identity provider with code '" + cmd.code() + "' already exists");
                    }
                    IdentityProvider ip = IdentityProvider.create(cmd.code(), cmd.name(), IdentityProviderType.parseWire(cmd.type()))
                            .withOidc(cmd.oidcIssuerUrl(), cmd.oidcClientId(), cmd.oidcClientSecretRef(), cmd.oidcMultiTenant(), cmd.oidcIssuerPattern())
                            .withRoleSync(cmd.syncRolesFromIdp(), cmd.allowedRoleIds());
                    scoped.commit(ip, repo, IdentityProviderCreated.of(ec, ip), cmd);

                    var created = new ArrayList<String>();
                    var claimed = new ArrayList<String>();
                    for (var domain : DomainRouting.normalise(cmd.allowedEmailDomains())) {
                        switch (routing.mapDomain(scoped, ip, domain, cmd.primaryClientId(), ec, cmd)) {
                            case CREATED -> created.add(domain.value());
                            case CLAIMED -> claimed.add(domain.value());
                            case UNCHANGED -> { }
                        }
                    }
                    return new CreateResult(ip.id(), ip.code(), created, claimed);
                });
    }
}
