package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.AnchorDomain;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.AnchorDomainValue;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AnchorDomainCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates an anchor domain (unique by normalised domain) and emits
/// [AnchorDomainCreated] (spec §4.1).
public final class CreateAnchorDomain {

    private CreateAnchorDomain() {
    }

    public static Operation<CreateAnchorDomainCommand, AnchorDomainCreated> of(AnchorDomainRepository repo) {
        return Operation.<CreateAnchorDomainCommand, AnchorDomainCreated>named("CreateAnchorDomain")
                .validate(cmd -> {
                    // Create's distinct required code (spec §4.1); AnchorDomainValue#parse
                    // covers the format rule, folding blank into INVALID_DOMAIN for callers
                    // (update) that don't pre-check.
                    UseCaseException.requireNonBlank(cmd.domain(), "DOMAIN_REQUIRED", "domain is required");
                    AnchorDomainValue.parseForCreate(cmd.domain());
                })
                // Anchor domains are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    AnchorDomainValue domain = AnchorDomainValue.parseForCreate(cmd.domain());
                    if (repo.findByDomain(domain.value()).isPresent()) {
                        throw UseCaseException.conflict("DOMAIN_EXISTS", "Anchor domain '" + domain.value() + "' already exists");
                    }
                    AnchorDomain a = AnchorDomain.create(domain);
                    return Plan.save(a, repo, AnchorDomainCreated.of(ec, a));
                });
    }
}
