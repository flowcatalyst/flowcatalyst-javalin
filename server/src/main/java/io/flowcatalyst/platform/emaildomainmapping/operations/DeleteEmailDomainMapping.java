package io.flowcatalyst.platform.emaildomainmapping.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a mapping with its junction rows and emits [EmailDomainMappingDeleted].
public final class DeleteEmailDomainMapping {

    private DeleteEmailDomainMapping() {
    }

    public static Operation<DeleteCommand, EmailDomainMappingDeleted> of(EmailDomainMappingRepository repo) {
        return Operation.<DeleteCommand, EmailDomainMappingDeleted>named("DeleteEmailDomainMapping")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Mappings are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    EmailDomainMapping m = Access.byId(repo, cmd.id());
                    return Plan.delete(m, repo, EmailDomainMappingDeleted.of(ec, m));
                });
    }
}
