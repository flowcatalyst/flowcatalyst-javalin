package io.flowcatalyst.platform.passwordreset;

import io.flowcatalyst.platform.shared.SecureTokens;
import io.flowcatalyst.platform.shared.tsid.EntityType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/// One row of `iam_password_reset_tokens` (`docs/spec/auth-identity.md`
/// §3.6): the subject is a `prn_` or a `ptu_` id (confirm branches on the
/// prefix); only the SHA-256 of the link token is stored; `purpose` is
/// `reset` (15 min) or `invite` (72 h); `reset2fa` clears every factor on
/// confirm; `requiresFactor` demands a TOTP code and `factorAttempts`
/// counts the wrong ones on this token.
public record ResetToken(String id, String principalId, String tokenHash, Purpose purpose, boolean reset2fa,
                         boolean requiresFactor, int factorAttempts, String redirectUri, Instant expiresAt, Instant createdAt) {

    public enum Purpose {
        RESET("reset", Duration.ofMinutes(15)), INVITE("invite", Duration.ofHours(72));

        public final String stored;
        public final Duration ttl;

        Purpose(String stored, Duration ttl) {
            this.stored = stored;
            this.ttl = ttl;
        }

        /// An unknown stored value reads as `reset`.
        public static Purpose parse(String s) {
            return "invite".equals(s) ? INVITE : RESET;
        }
    }

    public static final int MAX_FACTOR_ATTEMPTS = 5;

    public ResetToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// A freshly minted token and the raw link value that is never stored.
    public record Minted(String raw, ResetToken token) {
        @Override
        public String toString() {
            return "Minted[raw=***, token=" + token.id() + "]";
        }
    }

    public static Minted mint(String principalId, Purpose purpose, boolean reset2fa, boolean requiresFactor, String redirectUri, Instant now) {
        String raw = generateRaw();
        var t = new ResetToken(EntityType.PASSWORD_RESET_TOKEN.generate(), principalId, hash(raw), purpose, reset2fa,
                requiresFactor, 0, redirectUri, now.plus(purpose.ttl), now);
        return new Minted(raw, t);
    }

    /// 32 random bytes, base64url without padding: 43 characters of `[A-Za-z0-9_-]`.
    public static String generateRaw() {
        return SecureTokens.urlSafe(32);
    }

    /// Lower-hex SHA-256 of the raw token (`hash("")` = `e3b0c442…b855`).
    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean portalSubject() {
        return principalId.startsWith(EntityType.PORTAL_USER.prefix() + "_");
    }
}
