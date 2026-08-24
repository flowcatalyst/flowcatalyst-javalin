package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.PasswordPolicy;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserIdentity;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserCreated;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a USER principal (unique by normalised email) and emits
/// [UserCreated] (spec §4, §6).
///
/// Authorization is deliberately [Operation.Authorize#publicAccess()]: the
/// operation is reached by the admin handlers (coarse permission +
/// `requireUserAdmin` against the resolved client + the "client-admins
/// create CLIENT-scope only" rule, all in the handler) **and** by the
/// unauthenticated login JIT-provisioning flow, which provisions a
/// just-authenticated federated user. Each entry point keeps its own gate.
public final class CreateUser {

    private CreateUser() {
    }

    public static Operation<CreateCommand, UserCreated> of(PrincipalRepository repo) {
        return Operation.<CreateCommand, UserCreated>named("CreateUser")
                .validate(cmd -> {
                    EmailAddress email = EmailAddress.parse(cmd.email());
                    UserScope scope = UserScope.parseStrict(cmd.scope());
                    if ((scope == UserScope.CLIENT || scope == UserScope.PARTNER) && cmd.clientId() == null) {
                        throw UseCaseException.validation("CLIENT_REQUIRED", "clientId is required for PARTNER or CLIENT scope");
                    }
                    if (cmd.hasPassword()) {
                        PasswordPolicy.check(cmd.password(), email.value(), cmd.name() == null ? "" : cmd.name()).require();
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    EmailAddress email = EmailAddress.parse(cmd.email());
                    if (repo.findByEmail(email.value()).isPresent()) {
                        throw UseCaseException.conflict("EMAIL_EXISTS", "User with email '" + email.value() + "' already exists");
                    }
                    Principal p = Principal.newUser(email, UserScope.parseStrict(cmd.scope()))
                            .withClientId(cmd.clientId())
                            .withName(cmd.name());
                    if (cmd.hasPassword()) {
                        p = p.withPasswordHash(PasswordHash.hash(cmd.password()));
                    }
                    if (UserIdentity.OIDC.equals(cmd.idpType())) {
                        p = p.asOidcUser();
                    }
                    return Plan.save(p, repo, UserCreated.of(ec, p));
                });
    }
}
