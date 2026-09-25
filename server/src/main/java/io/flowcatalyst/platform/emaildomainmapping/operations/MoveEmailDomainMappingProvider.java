package io.flowcatalyst.platform.emaildomainmapping.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository.IdentityProviderRef;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingProviderChanged;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

/// "This domain now authenticates elsewhere" (spec §2): re-points the
/// mapping, emits [EmailDomainMappingProviderChanged], and — only when the
/// target is the internal provider — converts the domain's OIDC-provisioned
/// users back to internal auth in the same transaction (no per-user event;
/// the count is reported as `usersReset`). Moving toward an OIDC provider
/// touches no principal. A [TxOperation] because it returns a custom result
/// and spans two aggregates' tables.
public final class MoveEmailDomainMappingProvider {

    private MoveEmailDomainMappingProvider() {
    }

    public static TxOperation<MoveProviderCommand, MoveProviderResult> of(EmailDomainMappingRepository repo) {
        return TxOperation.<MoveProviderCommand, MoveProviderResult>named("MoveEmailDomainMappingProvider")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    UseCaseException.requireNonBlank(cmd.identityProviderId(), "IDP_REQUIRED", "identityProviderId is required");
                })
                // Mappings are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    EmailDomainMapping before = Access.byId(repo, cmd.id());
                    EmailDomainMapping moved = Access.requireTenantPin(repo, before.moveToProvider(cmd.identityProviderId()));
                    IdentityProviderRef target = repo.identityProvider(cmd.identityProviderId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("IdentityProvider", cmd.identityProviderId()));

                    scoped.commit(moved, repo, EmailDomainMappingProviderChanged.of(ec, moved, before.identityProviderId()), cmd);
                    int usersReset = target.isInternal() ? repo.resetOidcUsersToInternal(moved.emailDomain(), scoped.dbTx()) : 0;
                    return new MoveProviderResult(moved.id(), moved.emailDomain(), before.identityProviderId(),
                            moved.identityProviderId(), usersReset);
                });
    }
}
