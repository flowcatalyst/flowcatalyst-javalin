package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Provisions a portal identity (spec §1): a USER the platform can
/// authenticate but that is inert everywhere else — `CLIENT` scope with no
/// client, no roles, no application access, no password. A separate
/// operation from [CreateUser] on purpose: that one's "CLIENT needs a
/// clientId" invariant must not be weakened to admit this shape.
///
/// Authorization is [Operation.Authorize#publicAccess()]: the primary caller
/// is the unauthenticated provider-direct OIDC callback (JIT provisioning);
/// the ensure/invite API adds its own gate at the handler.
public final class CreatePortalUser {

    private CreatePortalUser() {
    }

    public static Operation<CreatePortalUserCommand, UserCreated> of(PrincipalRepository repo) {
        return Operation.<CreatePortalUserCommand, UserCreated>named("CreatePortalUser")
                .validate(cmd -> EmailAddress.parse(cmd.email()))
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    EmailAddress email = EmailAddress.parse(cmd.email());
                    if (repo.findByEmail(email.value()).isPresent()) {
                        throw UseCaseException.conflict("EMAIL_EXISTS", "User with email '" + email.value() + "' already exists");
                    }
                    Principal p = Principal.newPortalUser(email).withName(cmd.name()).withProvider(cmd.provider());
                    return Plan.save(p, repo, UserCreated.of(ec, p));
                });
    }
}
