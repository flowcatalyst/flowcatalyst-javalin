package io.flowcatalyst.platform.emaildomainmapping.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces a mapping's client grants and second-factor policy (spec §1
/// absent-value rules, one [EmailDomainMapping#update] transition) and emits
/// [EmailDomainMappingUpdated]. The merged policy must still be consistent
/// (`require2fa` ⇒ a method).
public final class UpdateEmailDomainMapping {

    private UpdateEmailDomainMapping() {
    }

    public static Operation<UpdateCommand, EmailDomainMappingUpdated> of(EmailDomainMappingRepository repo) {
        return Operation.<UpdateCommand, EmailDomainMappingUpdated>named("UpdateEmailDomainMapping")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    MfaMethod.parseAllStrict(cmd.allowed2faMethods());
                })
                // Mappings are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    EmailDomainMapping m = Access.requireTenantPin(repo, Access.byId(repo, cmd.id()).update(changesOf(cmd)));
                    return Plan.save(m, repo, EmailDomainMappingUpdated.of(ec, m));
                });
    }

    /// The command's fields as the aggregate's [EmailDomainMapping.Changes]
    /// (`null` = absent; the methods list parsed, `null` kept as `null`).
    private static EmailDomainMapping.Changes changesOf(UpdateCommand cmd) {
        return new EmailDomainMapping.Changes(cmd.primaryClientId(), cmd.additionalClientIds(), cmd.grantedClientIds(),
                cmd.requiredOidcTenantId(), cmd.require2fa(),
                cmd.allowed2faMethods() == null ? null : MfaMethod.parseAllStrict(cmd.allowed2faMethods()),
                cmd.rememberDeviceEnabled(), cmd.rememberDeviceDays());
    }
}
