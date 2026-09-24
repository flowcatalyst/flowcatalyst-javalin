package io.flowcatalyst.platform.auth.oidc;

import io.flowcatalyst.platform.shared.SecureTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/// One in-flight OIDC handshake (`docs/spec/auth-identity.md` §3.1): the
/// CSRF `state` is also the row key; `nonce` must come back in the
/// id_token; `codeVerifier` is the PKCE secret. `emailDomainMappingId`
/// empty ⇒ provider-direct; `portalClientId` set ⇒ a portal-plane
/// handshake. Lives ten minutes and is consumed exactly once.
///
/// @param oauth the chained `/oauth/authorize` request, or `null`
public record LoginState(String state, String emailDomain, String identityProviderId, String emailDomainMappingId,
                         String nonce, String codeVerifier, String returnUrl, OAuthChain oauth, String portalClientId,
                         Instant createdAt, Instant expiresAt) {

    public static final Duration TTL = Duration.ofMinutes(10);

    /// The SPA-forwarded `/oauth/authorize` parameters carried through the
    /// IdP round-trip; each stored only when non-empty.
    public record OAuthChain(String clientId, String redirectUri, String scope, String state, String codeChallenge,
                             String codeChallengeMethod, String nonce) {
        public boolean present() {
            return clientId != null && !clientId.isEmpty();
        }
    }

    public LoginState {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(codeVerifier, "codeVerifier");
        Objects.requireNonNull(identityProviderId, "identityProviderId");
        emailDomain = emailDomain == null ? "" : emailDomain.toLowerCase(java.util.Locale.ROOT);
        emailDomainMappingId = emailDomainMappingId == null ? "" : emailDomainMappingId;
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /// A fresh state: 32-byte `state` and `nonce`, 64-byte `code_verifier`, all base64url unpadded.
    public static LoginState begin(String emailDomain, String identityProviderId, String emailDomainMappingId,
                                   String returnUrl, OAuthChain oauth, String portalClientId, Instant now) {
        return new LoginState(random(32), emailDomain, identityProviderId, emailDomainMappingId, random(32), random(64),
                returnUrl, oauth, portalClientId, now, now.plus(TTL));
    }

    public boolean providerDirect() {
        return emailDomainMappingId.isEmpty();
    }

    public boolean portal() {
        return portalClientId != null && !portalClientId.isEmpty();
    }

    static String random(int bytes) {
        return SecureTokens.urlSafe(bytes);
    }
}
