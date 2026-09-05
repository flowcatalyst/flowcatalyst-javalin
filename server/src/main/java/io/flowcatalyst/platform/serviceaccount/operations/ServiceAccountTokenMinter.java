package io.flowcatalyst.platform.serviceaccount.operations;

import java.util.List;

/// Mints the short-lived, authority-bearing bearer `POST
/// /api/service-accounts/{id}/token` hands out (spec §8) — "the same grant
/// computation as the `client_credentials` path": scope = the linked
/// principal's flattened permission ceiling, plus its application reach.
///
/// Mirrors Go's `State.Auth *authservice.AuthService`: **optional**, by
/// design. This platform has no wired token-signing service yet (no `auth`
/// aggregate — see `CreateServiceAccountWithCredentials`'s class doc), so the
/// composition root may leave this unset, in which case the mint route
/// answers 500 `TOKEN` "token minting is not wired" — spec §8 step 2, not an
/// improvisation.
public interface ServiceAccountTokenMinter {

    /// @param accessToken       the signed bearer
    /// @param expiresInSeconds  the token's lifetime, for the wire `expiresIn` field
    record Minted(String accessToken, long expiresInSeconds) {
        @Override
        public String toString() {
            return "Minted[accessToken=***, expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    Minted mint(io.flowcatalyst.platform.principal.Principal principal, java.util.List<String> permissions);
}
