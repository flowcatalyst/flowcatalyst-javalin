package io.flowcatalyst.platform.auth.grant;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/// An OAuth 2.0 authorization code (`docs/spec/auth-core.md` §3.7, §8.1; Go
/// `grantstore.AuthorizationCode`): short-lived (10 min), single-use,
/// PKCE-bound, and carrying the real sign-in time so the id_token's
/// `auth_time` survives the code exchange (ruling C-Q1).
///
/// @param code                the 86-char base64url value the browser carries
/// @param clientId            the OAuth client `client_id` it was issued to
/// @param principalId         the signed-in principal (`sub`), or a `ptu_` portal identity
/// @param redirectUri         the registered redirect it must be redeemed with
/// @param scope               the requested scope, verbatim; nullable
/// @param codeChallenge       PKCE challenge; nullable
/// @param codeChallengeMethod `S256` (or nullable ⇒ S256)
/// @param nonce               OIDC nonce to echo; nullable
/// @param state               the authorize `state`; nullable
/// @param contextClientId     the tenant client selected at login; nullable
/// @param authTime            when the user actually authenticated; `null` when unknown
/// @param createdAt           issue time
/// @param expiresAt           `createdAt + 10 min`
/// @param used                `consumed_at IS NOT NULL`
public record AuthorizationCode(
        String code,
        String clientId,
        String principalId,
        String redirectUri,
        String scope,
        String codeChallenge,
        String codeChallengeMethod,
        String nonce,
        String state,
        String contextClientId,
        Instant authTime,
        Instant createdAt,
        Instant expiresAt,
        boolean used) {

    /// `authCodeDefaultExpiry`: ten minutes.
    public static final long TTL_SECONDS = 600;

    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthorizationCode {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(redirectUri, "redirectUri");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /// A fresh code with the default expiry and nothing optional set.
    public static AuthorizationCode issue(String clientId, String principalId, String redirectUri, Instant now) {
        return new AuthorizationCode(generateCode(), clientId, principalId, redirectUri, null, null, null, null, null, null,
                null, now, now.plusSeconds(TTL_SECONDS), false);
    }

    /// 64 random bytes as unpadded base64url — 86 characters (Go `generateCode`).
    public static String generateCode() {
        byte[] b = new byte[64];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public AuthorizationCode withScope(String newScope) {
        return new AuthorizationCode(code, clientId, principalId, redirectUri, newScope, codeChallenge, codeChallengeMethod,
                nonce, state, contextClientId, authTime, createdAt, expiresAt, used);
    }

    public AuthorizationCode withPkce(String challenge, String method) {
        return new AuthorizationCode(code, clientId, principalId, redirectUri, scope, challenge, method,
                nonce, state, contextClientId, authTime, createdAt, expiresAt, used);
    }

    public AuthorizationCode withNonce(String newNonce) {
        return new AuthorizationCode(code, clientId, principalId, redirectUri, scope, codeChallenge, codeChallengeMethod,
                newNonce, state, contextClientId, authTime, createdAt, expiresAt, used);
    }

    public AuthorizationCode withState(String newState) {
        return new AuthorizationCode(code, clientId, principalId, redirectUri, scope, codeChallenge, codeChallengeMethod,
                nonce, newState, contextClientId, authTime, createdAt, expiresAt, used);
    }

    public AuthorizationCode withContextClientId(String newContextClientId) {
        return new AuthorizationCode(code, clientId, principalId, redirectUri, scope, codeChallenge, codeChallengeMethod,
                nonce, state, newContextClientId, authTime, createdAt, expiresAt, used);
    }

    public AuthorizationCode withAuthTime(Instant newAuthTime) {
        return new AuthorizationCode(code, clientId, principalId, redirectUri, scope, codeChallenge, codeChallengeMethod,
                nonce, state, contextClientId, newAuthTime, createdAt, expiresAt, used);
    }
}
