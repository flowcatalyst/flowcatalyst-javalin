package io.flowcatalyst.platform.auth.grant;

import io.flowcatalyst.sdk.tsid.Tsid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/// A refresh token as stored (`docs/spec/auth-core.md` §3.7, §7.4, §8.2;
/// Go `grantstore.RefreshToken`). Only the SHA-256 hash of the raw value is
/// kept; the raw value is returned to the client exactly once.
///
/// @param id                untyped 13-char TSID; the row is `RefreshToken:{id}`
/// @param tokenHash         base64url(sha256(raw)), no padding
/// @param principalId       the subject (`accountId`)
/// @param oauthClientId     the OAuth client the family is bound to; `null` for `/auth/refresh` tokens
/// @param scopes            the granted scopes, in order
/// @param accessibleClients always a list, never null (storage contract)
/// @param revoked           revoked, or rotated out
/// @param revokedAt         when; nullable
/// @param tokenFamily       the rotation family's root token id; `null` on legacy rows
/// @param replacedBy        the hash of the token that replaced this one; nullable
/// @param lastUsedAt        never stamped by the flows (Go parity); nullable
/// @param createdFromIp     nullable
/// @param userAgent         nullable
/// @param authTime          the family's sign-in time; `null` when unknown
/// @param createdAt         issue time
/// @param expiresAt         the family's absolute cap — never extended by rotation
public record RefreshToken(
        String id,
        String tokenHash,
        String principalId,
        String oauthClientId,
        List<String> scopes,
        List<String> accessibleClients,
        boolean revoked,
        Instant revokedAt,
        String tokenFamily,
        String replacedBy,
        Instant lastUsedAt,
        String createdFromIp,
        String userAgent,
        Instant authTime,
        Instant createdAt,
        Instant expiresAt) {

    /// `refreshTokenDefaultExpiry`: seven days — also the family's absolute cap (ruling C-Q16).
    public static final long TTL_SECONDS = 7 * 24 * 3600;

    private static final SecureRandom RANDOM = new SecureRandom();

    public RefreshToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(principalId, "principalId");
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        accessibleClients = accessibleClients == null ? List.of() : List.copyOf(accessibleClients);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /// A raw token and its stored entity, for a fresh issue (`GenerateTokenPair`).
    public record Issued(String raw, RefreshToken token) {
        @Override
        public String toString() {
            return "Issued[raw=***, token=" + token.id() + "]";
        }
    }

    public static Issued issue(String principalId, Instant now) {
        String raw = generateRaw();
        return new Issued(raw, new RefreshToken(Tsid.generate(), hash(raw), principalId, null, List.of(), List.of(),
                false, null, null, null, null, null, null, null, now, now.plusSeconds(TTL_SECONDS)));
    }

    /// 32 random bytes, unpadded base64url.
    public static String generateRaw() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /// base64url(sha256(raw)), unpadded — the only form ever stored or looked up.
    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean isValid(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }

    public boolean wasReplaced() {
        return replacedBy != null;
    }

    public RefreshToken withBinding(String clientId, List<String> newScopes, List<String> clients, Instant newAuthTime) {
        return new RefreshToken(id, tokenHash, principalId, clientId, newScopes, clients, revoked, revokedAt, tokenFamily,
                replacedBy, lastUsedAt, createdFromIp, userAgent, newAuthTime, createdAt, expiresAt);
    }

    public RefreshToken withFamily(String family) {
        return new RefreshToken(id, tokenHash, principalId, oauthClientId, scopes, accessibleClients, revoked, revokedAt,
                family, replacedBy, lastUsedAt, createdFromIp, userAgent, authTime, createdAt, expiresAt);
    }

    public RefreshToken withExpiresAt(Instant newExpiresAt) {
        return new RefreshToken(id, tokenHash, principalId, oauthClientId, scopes, accessibleClients, revoked, revokedAt,
                tokenFamily, replacedBy, lastUsedAt, createdFromIp, userAgent, authTime, createdAt, newExpiresAt);
    }

    public RefreshToken withOrigin(String ip, String agent) {
        return new RefreshToken(id, tokenHash, principalId, oauthClientId, scopes, accessibleClients, revoked, revokedAt,
                tokenFamily, replacedBy, lastUsedAt, ip, agent, authTime, createdAt, expiresAt);
    }
}
