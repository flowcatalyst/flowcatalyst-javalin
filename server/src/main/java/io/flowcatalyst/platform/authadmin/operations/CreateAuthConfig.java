package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.AuthProvider;
import io.flowcatalyst.platform.authadmin.ClientAuthConfig;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigEmailDomain;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.ConfigType;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AuthConfigCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.List;

/// Creates a client auth config (unique by normalised e-mail domain) and
/// emits [AuthConfigCreated] (spec §4.2). Validation order matches the
/// spec exactly: domain, configType, authProvider, then (when `OIDC`)
/// issuer and client id.
public final class CreateAuthConfig {

    private CreateAuthConfig() {
    }

    public static Operation<CreateAuthConfigCommand, AuthConfigCreated> of(ClientAuthConfigRepository repo) {
        return Operation.<CreateAuthConfigCommand, AuthConfigCreated>named("CreateAuthConfig")
                .validate(cmd -> {
                    ClientAuthConfigEmailDomain.parse(cmd.emailDomain());
                    ConfigType.parseStrict(cmd.configType());
                    AuthProvider provider = AuthProvider.parseStrict(cmd.authProvider());
                    if (provider == AuthProvider.OIDC) {
                        UseCaseException.requireNonBlank(cmd.oidcIssuerUrl(), "OIDC_ISSUER_REQUIRED", "oidcIssuerUrl is required for OIDC");
                        UseCaseException.requireNonBlank(cmd.oidcClientId(), "OIDC_CLIENT_ID_REQUIRED", "oidcClientId is required for OIDC");
                    }
                })
                // Auth configs are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    ClientAuthConfigEmailDomain domain = ClientAuthConfigEmailDomain.parse(cmd.emailDomain());
                    if (repo.findByEmailDomain(domain.value()).isPresent()) {
                        throw UseCaseException.conflict("DOMAIN_ALREADY_CONFIGURED",
                                "Auth config for domain '" + domain.value() + "' already exists");
                    }
                    ClientAuthConfig c = ClientAuthConfig.create(domain, ConfigType.parseStrict(cmd.configType()), AuthProvider.parseStrict(cmd.authProvider()))
                            .withPrimaryClientId(cmd.primaryClientId())
                            .withAdditionalClientIds(orEmpty(cmd.additionalClientIds()))
                            .withGrantedClientIds(orEmpty(cmd.grantedClientIds()))
                            .withOidcIssuerUrl(cmd.oidcIssuerUrl())
                            .withOidcClientId(cmd.oidcClientId())
                            .withOidcMultiTenant(cmd.oidcMultiTenant())
                            .withOidcIssuerPattern(cmd.oidcIssuerPattern())
                            .withOidcClientSecretRef(cmd.oidcClientSecretRef());
                    return Plan.save(c, repo, AuthConfigCreated.of(ec, c));
                });
    }

    private static List<String> orEmpty(List<String> ids) {
        return ids == null ? List.of() : ids;
    }
}
