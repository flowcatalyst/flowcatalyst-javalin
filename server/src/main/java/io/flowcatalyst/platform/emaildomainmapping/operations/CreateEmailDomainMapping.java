package io.flowcatalyst.platform.emaildomainmapping.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.TwoFactorPolicy;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.List;

/// Creates a mapping (unique by normalised domain) and emits
/// [EmailDomainMappingCreated]. Neither the identity provider nor the client
/// ids are checked against their tables (spec §1, open question 2).
public final class CreateEmailDomainMapping {

    private CreateEmailDomainMapping() {
    }

    public static Operation<CreateCommand, EmailDomainMappingCreated> of(EmailDomainMappingRepository repo) {
        return Operation.<CreateCommand, EmailDomainMappingCreated>named("CreateEmailDomainMapping")
                .validate(cmd -> {
                    EmailDomain.parse(cmd.emailDomain());
                    UseCaseException.requireNonBlank(cmd.identityProviderId(), "IDP_REQUIRED", "identityProviderId is required");
                    ScopeType scope = ScopeType.parseStrict(cmd.scopeType());
                    if (scope.requiresPrimaryClient() && cmd.primaryClientId() == null) {
                        throw UseCaseException.validation("PRIMARY_CLIENT_REQUIRED",
                                "primaryClientId is required for PARTNER and CLIENT scope");
                    }
                    policyOf(cmd).checkConsistent();
                })
                // Mappings are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    EmailDomain domain = EmailDomain.parse(cmd.emailDomain());
                    if (repo.findByEmailDomain(domain.value()).isPresent()) {
                        throw UseCaseException.conflict("DOMAIN_ALREADY_MAPPED",
                                "Email domain '" + domain.value() + "' is already mapped");
                    }
                    EmailDomainMapping m = EmailDomainMapping.create(domain, cmd.identityProviderId(), ScopeType.parseStrict(cmd.scopeType()))
                            .withPrimaryClientId(cmd.primaryClientId())
                            .withRequiredOidcTenantId(cmd.requiredOidcTenantId())
                            .withAdditionalClientIds(orEmpty(cmd.additionalClientIds()))
                            .withGrantedClientIds(orEmpty(cmd.grantedClientIds()))
                            .withTwoFactor(policyOf(cmd));
                    Access.requireTenantPin(repo, m);
                    return Plan.save(m, repo, EmailDomainMappingCreated.of(ec, m));
                });
    }

    /// The command's 2FA fields as a policy; `rememberDeviceDays` absent or
    /// `<= 0` takes the domain default (spec §1).
    private static TwoFactorPolicy policyOf(CreateCommand cmd) {
        int days = cmd.rememberDeviceDays() == null || cmd.rememberDeviceDays() <= 0
                ? TwoFactorPolicy.DEFAULT_REMEMBER_DEVICE_DAYS : cmd.rememberDeviceDays();
        return new TwoFactorPolicy(cmd.require2fa(), MfaMethod.parseAllStrict(cmd.allowed2faMethods()), cmd.rememberDeviceEnabled(), days);
    }

    private static List<String> orEmpty(List<String> ids) {
        return ids == null ? List.of() : ids;
    }
}
