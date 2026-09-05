package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.AnchorDomain;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.AnchorDomainValue;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AnchorDomainUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces an anchor domain's domain and emits [AnchorDomainUpdated] (spec
/// §4.1). Unlike create, a blank domain here folds into `INVALID_DOMAIN` —
/// see [AnchorDomainValue#parse]. A collision with another row's domain is
/// pre-checked and answered as `DOMAIN_EXISTS` (spec §8 D2 — a deliberate
/// deviation from Go, which has no pre-check and answers 500).
public final class UpdateAnchorDomain {

    private UpdateAnchorDomain() {
    }

    public static Operation<UpdateAnchorDomainCommand, AnchorDomainUpdated> of(AnchorDomainRepository repo) {
        return Operation.<UpdateAnchorDomainCommand, AnchorDomainUpdated>named("UpdateAnchorDomain")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    AnchorDomainValue.parse(cmd.domain());
                })
                // Anchor domains are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    AnchorDomain existing = Access.anchorDomainById(repo, cmd.id());
                    AnchorDomainValue domain = AnchorDomainValue.parse(cmd.domain());
                    repo.findByDomain(domain.value())
                            .filter(other -> !other.id().equals(existing.id()))
                            .ifPresent(other -> {
                                throw UseCaseException.conflict("DOMAIN_EXISTS", "Anchor domain '" + domain.value() + "' already exists");
                            });
                    AnchorDomain updated = existing.changeDomain(domain);
                    return Plan.save(updated, repo, AnchorDomainUpdated.of(ec, updated));
                });
    }
}
