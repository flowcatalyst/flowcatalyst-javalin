package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.http.Exchange;

import java.util.Map;
import java.util.Optional;

/// The second-factor gate `POST /auth/login` consults after the password
/// verified (`docs/spec/auth-core.md` §7.1 step 10, `auth-identity.md` §6.2).
///
/// The MFA unit implements it; until it lands, [#none()] challenges nobody —
/// exactly Go with `MFA == nil`. When a challenge is returned the endpoint
/// answers it with 200 instead of a session; the `/auth/2fa/*` flow then
/// completes the login. An evaluation failure **throws** and the endpoint
/// answers 500 `MFA_EVAL_FAILED`: the gate fails closed, never silently
/// bypassed.
public interface MfaChallenge {

    /// A 200 response that stands in for the session: `mfa_required` or
    /// `enrollment_required` and its fields.
    record Challenge(Map<String, Object> body) {
    }

    /// Empty when the login may complete now.
    Optional<Challenge> evaluate(Principal principal, Exchange ctx);

    static MfaChallenge none() {
        return (_, _) -> Optional.empty();
    }
}
