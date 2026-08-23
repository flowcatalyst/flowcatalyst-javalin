package io.flowcatalyst.platform.emaildomainmapping.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.TwoFactorPolicy;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces a mapping's client grants and second-factor policy (spec §1
/// absent-value rules) and emits [EmailDomainMappingUpdated]. The merged
/// policy must still be consistent (`require2fa` ⇒ a method).
public final class UpdateEmailDomainMapping {

    private UpdateEmailDomainMapping() {
    }

    public static Operation<UpdateCommand, EmailDomainMappingUpdated> of(EmailDomainMappingRepository repo) {
        return Operation.<UpdateCommand, EmailDomainMappingUpdated>named("UpdateEmailDomainMapping")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    MfaMethod.parseAll(cmd.allowed2faMethods());
                })
                // Mappings are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    EmailDomainMapping m = Access.byId(repo, cmd.id())
                            .withPrimaryClientId(cmd.primaryClientId())
                            .withRequiredOidcTenantId(cmd.requiredOidcTenantId());
                    if (cmd.additionalClientIds() != null) m = m.withAdditionalClientIds(cmd.additionalClientIds());
                    if (cmd.grantedClientIds() != null) m = m.withGrantedClientIds(cmd.grantedClientIds());
                    m = m.withTwoFactor(mergedPolicy(m.twoFactor(), cmd));
                    return Plan.save(m, repo, EmailDomainMappingUpdated.of(ec, m));
                });
    }

    /// The current policy with every supplied field replaced (spec §1).
    private static TwoFactorPolicy mergedPolicy(TwoFactorPolicy current, UpdateCommand cmd) {
        TwoFactorPolicy p = current;
        if (cmd.require2fa() != null) p = p.withRequired(cmd.require2fa());
        if (cmd.allowed2faMethods() != null) p = p.withAllowedMethods(MfaMethod.parseAll(cmd.allowed2faMethods()));
        if (cmd.rememberDeviceEnabled() != null) p = p.withRememberDeviceEnabled(cmd.rememberDeviceEnabled());
        if (cmd.rememberDeviceDays() != null) p = p.withRememberDeviceDays(cmd.rememberDeviceDays());
        return p;
    }
}
