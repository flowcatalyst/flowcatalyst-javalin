package io.flowcatalyst.platform.passwordreset;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.IAM_PASSWORD_RESET_TOKENS;

/// `iam_password_reset_tokens` (§3.6): one live token per subject — every
/// mint deletes the subject's tokens first; the wrong-TOTP counter is an
/// atomic `UPDATE … RETURNING`.
public final class ResetTokenRepository {

    private static final io.flowcatalyst.db.generated.tables.IamPasswordResetTokens T = IAM_PASSWORD_RESET_TOKENS;

    private final DSLContext dsl;

    public ResetTokenRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    public void insert(ResetToken t) {
        dsl.insertInto(T)
                .set(T.ID, t.id()).set(T.PRINCIPAL_ID, t.principalId()).set(T.TOKEN_HASH, t.tokenHash())
                .set(T.PURPOSE, t.purpose().stored).set(T.RESET_2FA, t.reset2fa()).set(T.REQUIRES_FACTOR, t.requiresFactor())
                .set(T.FACTOR_ATTEMPTS, t.factorAttempts()).set(T.REDIRECT_URI, t.redirectUri())
                .set(T.EXPIRES_AT, utc(t.expiresAt())).set(T.CREATED_AT, utc(t.createdAt()))
                .execute();
    }

    public Optional<ResetToken> findByHash(String tokenHash) {
        return dsl.selectFrom(T).where(T.TOKEN_HASH.eq(tokenHash))
                .fetchOptional(r -> new ResetToken(r.getId(), r.getPrincipalId(), r.getTokenHash(),
                        ResetToken.Purpose.parse(r.getPurpose()), Boolean.TRUE.equals(r.getReset_2fa()),
                        Boolean.TRUE.equals(r.getRequiresFactor()), r.getFactorAttempts() == null ? 0 : r.getFactorAttempts(),
                        r.getRedirectUri(), r.getExpiresAt().toInstant(), r.getCreatedAt().toInstant()));
    }

    /// Every token of the subject — the "one live token" rule and the burn.
    public int deleteByPrincipal(String principalId) {
        return dsl.deleteFrom(T).where(T.PRINCIPAL_ID.eq(principalId)).execute();
    }

    /// `factor_attempts + 1`, returning the new count (`-1` when the row is gone).
    public int countFactorAttempt(String id) {
        Integer n = dsl.update(T).set(T.FACTOR_ATTEMPTS, T.FACTOR_ATTEMPTS.plus(1)).where(T.ID.eq(id))
                .returningResult(T.FACTOR_ATTEMPTS).fetchOne(T.FACTOR_ATTEMPTS);
        return n == null ? -1 : n;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
