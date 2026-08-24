package io.flowcatalyst.platform.principal;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The side-effect dependency of `SendPasswordReset` (spec §10): mint a
/// single-use reset token for the user and deliver the link by email. The
/// use case is purely about eligibility, so it stays free of token and
/// transport concerns. The password-reset subsystem will provide the real
/// implementation; until then [#notConfigured()] answers as Go does without
/// a mailer.
public interface PasswordResetEmailer {

    /// Mints a reset token and emails the link; `reset2fa` flags the token to
    /// also clear the user's enrolled second factors on confirm.
    ///
    /// @throws UseCaseException internal `EMAILER_NOT_CONFIGURED` | `EMAILER`
    void sendResetEmail(Principal principal, boolean reset2fa);

    /// No mailer wired: every send fails with 500 `EMAILER_NOT_CONFIGURED`.
    static PasswordResetEmailer notConfigured() {
        return (_, _) -> {
            throw UseCaseException.internal("EMAILER_NOT_CONFIGURED", "Password reset emailer not configured", null);
        };
    }
}
