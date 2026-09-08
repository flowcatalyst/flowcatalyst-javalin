package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.DeveloperCredentialSet;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates or rotates a developer's self-service client-credentials secret
/// and emits [DeveloperCredentialSet] (spec §2). The plaintext is disclosed
/// exactly once through [DeveloperSecrets#pop]. Requires the seeded
/// `platform:developer` role — the same live re-verification the token
/// endpoint performs, so this fails fast instead of minting a secret nothing
/// can use. Self-or-user-admin post-load (`Access.requireSelfOrUserAdmin`).
public final class SetDeveloperCredential {

    /// The seeded role that gates the developer client-credentials flow.
    public static final String DEVELOPER_ROLE = "platform:developer";

    private SetDeveloperCredential() {
    }

    /// @param disclose receives the freshly minted plaintext, once, if and only
    ///                 if minting happened — which is after the self-or-user-admin
    ///                 rule has passed. The caller owns the sink (a local), so the
    ///                 plaintext cannot outlive the request; it used to go into a
    ///                 process-wide map instead, which held it for two minutes and
    ///                 kept holding it when the commit failed. See [DeveloperSecrets].
    public static Operation<SetDeveloperCredentialCommand, DeveloperCredentialSet> of(
            PrincipalRepository repo, DeveloperSecrets secrets, java.util.function.Consumer<String> disclose) {
        return Operation.<SetDeveloperCredentialCommand, DeveloperCredentialSet>named("SetDeveloperCredential")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.principalId(), "PRINCIPAL_ID_REQUIRED", "Principal ID is required"))
                .authorize(Operation.Authorize.publicAccess()) // self-or-admin runs post-load: Access.requireSelfOrUserAdmin
                .execute((cmd, ec) -> {
                    Principal p = Access.loadUser(repo, cmd.principalId());
                    Access.requireSelfOrUserAdmin(p);
                    if (!p.isUser()) {
                        throw UseCaseException.businessRule("NOT_A_USER", "Developer credentials can only be set on USER type principals");
                    }
                    if (!p.hasRole(DEVELOPER_ROLE)) {
                        throw UseCaseException.businessRule("NOT_A_DEVELOPER", "Principal does not hold the developer role");
                    }
                    // Minted here, not by the caller: everything above this line
                    // is authorisation, and an unauthorised request must not
                    // reach the minting path at all.
                    var issued = secrets.issue();
                    disclose.accept(issued.plaintext());
                    p = p.withDeveloperSecret(issued.hashedRef());
                    return Plan.save(p, repo, DeveloperCredentialSet.of(ec, p));
                });
    }
}
