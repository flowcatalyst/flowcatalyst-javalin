package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.PasswordResetEmailer;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Checks that a principal is eligible for an internal password reset (a
/// USER, not federated, with an email) and asks the configured emailer to
/// mint the token and send the link (spec §3, §6). Not an envelope
/// operation: nothing is persisted and no event is emitted (spec §8, open
/// question 8) — the token is the emailer's (password-reset aggregate's)
/// business. The handler gates it (`requireUserAdmin` + client-scope target).
public final class SendPasswordReset {

    private SendPasswordReset() {
    }

    /// @throws UseCaseException validation `ID_REQUIRED` | `NOT_USER` | `OIDC_USER` | `NO_EMAIL`,
    ///                          not-found `Principal_NOT_FOUND`, internal `EMAILER_NOT_CONFIGURED` | `EMAILER`
    public static void run(PrincipalRepository repo, PasswordResetEmailer emailer, SendPasswordResetCommand cmd) {
        UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
        Principal p = Access.loadPrincipal(repo, cmd.id());
        if (!p.isUser()) {
            throw UseCaseException.validation("NOT_USER", "Password reset only applies to user accounts");
        }
        if (p.externalIdentity() != null) {
            throw UseCaseException.validation("OIDC_USER",
                    "Cannot send password reset for OIDC-federated users — they manage credentials at their IDP");
        }
        if (p.email() == null || p.email().isBlank()) {
            throw UseCaseException.validation("NO_EMAIL", "User does not have an email address on file");
        }
        try {
            emailer.sendResetEmail(p, cmd.reset2fa());
        } catch (UseCaseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw UseCaseException.internal("EMAILER", "send_reset_email failed", e);
        }
    }
}
