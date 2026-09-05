package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.IAM_MFA_EMAIL_PINS;
import static io.flowcatalyst.db.generated.Tables.IAM_MFA_TRUSTED_DEVICES;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_METHODS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_RECOVERY_CODES;

/// The four two-factor tables (`docs/spec/auth-identity.md` §3.4), every
/// write a single autocommit statement, the two single-use guarantees
/// expressed as guarded UPDATEs so concurrent callers cannot both succeed:
/// a recovery code is consumed by `UPDATE … SET used_at WHERE used_at IS
/// NULL`, an e-mail PIN attempt is counted by `UPDATE … RETURNING`.
public final class MfaRepository {

    private static final io.flowcatalyst.db.generated.tables.IamUserMfaMethods M = IAM_USER_MFA_METHODS;
    private static final io.flowcatalyst.db.generated.tables.IamUserMfaRecoveryCodes R = IAM_USER_MFA_RECOVERY_CODES;
    private static final io.flowcatalyst.db.generated.tables.IamMfaEmailPins P = IAM_MFA_EMAIL_PINS;
    private static final io.flowcatalyst.db.generated.tables.IamMfaTrustedDevices T = IAM_MFA_TRUSTED_DEVICES;

    private final DSLContext dsl;
    private final Clock clock;

    public MfaRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public MfaRepository(DataSource dataSource, Clock clock) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// The same store over one connection (a test holding a transaction open).
    MfaRepository(Connection connection, Clock clock) {
        this.dsl = DSL.using(connection, SQLDialect.POSTGRES);
        this.clock = clock;
    }

    // ── methods ────────────────────────────────────────────────────────────

    public Optional<MfaMethodRow> findMethod(String principalId, MfaMethod method) {
        return dsl.selectFrom(M).where(M.PRINCIPAL_ID.eq(principalId)).and(M.METHOD.eq(method.name()))
                .fetchOptional(r -> new MfaMethodRow(r.getId(), r.getPrincipalId(), MfaMethod.parseStrict(r.getMethod()),
                        r.getSecretEncrypted(), at(r.getConfirmedAt()), at(r.getLastUsedAt()), at(r.getCreatedAt())));
    }

    public List<MfaMethodRow> methods(String principalId) {
        return dsl.selectFrom(M).where(M.PRINCIPAL_ID.eq(principalId)).orderBy(M.CREATED_AT, M.ID)
                .fetch(r -> new MfaMethodRow(r.getId(), r.getPrincipalId(), MfaMethod.parseStrict(r.getMethod()),
                        r.getSecretEncrypted(), at(r.getConfirmedAt()), at(r.getLastUsedAt()), at(r.getCreatedAt())));
    }

    public List<MfaMethod> confirmedMethods(String principalId) {
        return methods(principalId).stream().filter(MfaMethodRow::confirmed).map(MfaMethodRow::method).toList();
    }

    /// A pending row (unique per principal+method — the caller deletes any
    /// existing one first).
    public MfaMethodRow insertPending(String principalId, MfaMethod method, String secretEncrypted) {
        Instant now = clock.instant();
        String id = EntityType.MFA_METHOD.generate();
        dsl.insertInto(M).set(M.ID, id).set(M.PRINCIPAL_ID, principalId).set(M.METHOD, method.name())
                .set(M.SECRET_ENCRYPTED, secretEncrypted).set(M.CREATED_AT, utc(now)).execute();
        return new MfaMethodRow(id, principalId, method, secretEncrypted, null, null, now);
    }

    public boolean confirm(String principalId, MfaMethod method, Instant lastUsedAt) {
        Instant now = clock.instant();
        return dsl.update(M).set(M.CONFIRMED_AT, utc(now)).set(M.LAST_USED_AT, lastUsedAt == null ? null : utc(lastUsedAt))
                .where(M.PRINCIPAL_ID.eq(principalId)).and(M.METHOD.eq(method.name())).execute() == 1;
    }

    /// The TOTP replay guard's forward step: only ever moves `last_used_at`
    /// later, so two concurrent verifications of the same code cannot both
    /// stamp.
    public boolean advanceLastUsed(String principalId, MfaMethod method, Instant to) {
        return dsl.update(M).set(M.LAST_USED_AT, utc(to))
                .where(M.PRINCIPAL_ID.eq(principalId)).and(M.METHOD.eq(method.name()))
                .and(M.LAST_USED_AT.isNull().or(M.LAST_USED_AT.lt(utc(to))))
                .execute() == 1;
    }

    public boolean deleteMethod(String principalId, MfaMethod method) {
        return dsl.deleteFrom(M).where(M.PRINCIPAL_ID.eq(principalId)).and(M.METHOD.eq(method.name())).execute() > 0;
    }

    // ── recovery codes ─────────────────────────────────────────────────────

    /// Replaces the whole set.
    public void replaceRecoveryCodes(String principalId, List<String> codeHashes) {
        Instant now = clock.instant();
        dsl.deleteFrom(R).where(R.PRINCIPAL_ID.eq(principalId)).execute();
        var insert = dsl.insertInto(R, R.ID, R.PRINCIPAL_ID, R.CODE_HASH, R.CREATED_AT);
        for (String h : codeHashes) {
            insert = insert.values(EntityType.MFA_RECOVERY_CODE.generate(), principalId, h, utc(now));
        }
        if (!codeHashes.isEmpty()) {
            insert.execute();
        }
    }

    /// Single use, race-free: true for exactly one caller per code.
    public boolean consumeRecoveryCode(String principalId, String codeHash) {
        return dsl.update(R).set(R.USED_AT, utc(clock.instant()))
                .where(R.PRINCIPAL_ID.eq(principalId)).and(R.CODE_HASH.eq(codeHash)).and(R.USED_AT.isNull())
                .execute() == 1;
    }

    public int remainingRecoveryCodes(String principalId) {
        return dsl.fetchCount(R, R.PRINCIPAL_ID.eq(principalId).and(R.USED_AT.isNull()));
    }

    public void deleteRecoveryCodes(String principalId) {
        dsl.deleteFrom(R).where(R.PRINCIPAL_ID.eq(principalId)).execute();
    }

    // ── e-mail PINs ────────────────────────────────────────────────────────

    /// Issuing deletes every outstanding PIN of that purpose first.
    public EmailPin issuePin(String principalId, String purpose, String pinHash, Instant expiresAt) {
        Instant now = clock.instant();
        dsl.deleteFrom(P).where(P.PRINCIPAL_ID.eq(principalId)).and(P.PURPOSE.eq(purpose)).execute();
        String id = EntityType.MFA_EMAIL_PIN.generate();
        dsl.insertInto(P).set(P.ID, id).set(P.PRINCIPAL_ID, principalId).set(P.PURPOSE, purpose)
                .set(P.PIN_HASH, pinHash).set(P.ATTEMPTS, 0).set(P.EXPIRES_AT, utc(expiresAt)).set(P.CREATED_AT, utc(now))
                .execute();
        return new EmailPin(id, principalId, purpose, pinHash, 0, expiresAt, now);
    }

    public Optional<EmailPin> latestPin(String principalId, String purpose) {
        return dsl.selectFrom(P).where(P.PRINCIPAL_ID.eq(principalId)).and(P.PURPOSE.eq(purpose))
                .orderBy(P.CREATED_AT.desc(), P.ID.desc()).limit(1)
                .fetchOptional(r -> new EmailPin(r.getId(), r.getPrincipalId(), r.getPurpose(), r.getPinHash(),
                        r.getAttempts(), at(r.getExpiresAt()), at(r.getCreatedAt())));
    }

    /// `attempts + 1`, returning the new count (`-1` when the row is gone).
    public int countPinAttempt(String pinId) {
        Integer n = dsl.update(P).set(P.ATTEMPTS, P.ATTEMPTS.plus(1)).where(P.ID.eq(pinId))
                .returningResult(P.ATTEMPTS).fetchOne(P.ATTEMPTS);
        return n == null ? -1 : n;
    }

    public void deletePin(String pinId) {
        dsl.deleteFrom(P).where(P.ID.eq(pinId)).execute();
    }

    public void deletePins(String principalId) {
        dsl.deleteFrom(P).where(P.PRINCIPAL_ID.eq(principalId)).execute();
    }

    // ── trusted devices ────────────────────────────────────────────────────

    public TrustedDevice insertTrustedDevice(String principalId, String tokenHash, String label, Instant expiresAt) {
        Instant now = clock.instant();
        String id = EntityType.MFA_TRUSTED_DEVICE.generate();
        dsl.insertInto(T).set(T.ID, id).set(T.PRINCIPAL_ID, principalId).set(T.TOKEN_HASH, tokenHash)
                .set(T.LABEL, label).set(T.EXPIRES_AT, utc(expiresAt)).set(T.CREATED_AT, utc(now)).execute();
        return new TrustedDevice(id, principalId, label, expiresAt, now, null);
    }

    /// True when a live device with this hash belongs to the principal; the
    /// match stamps `last_used_at`.
    public boolean touchTrustedDevice(String principalId, String tokenHash) {
        Instant now = clock.instant();
        return dsl.update(T).set(T.LAST_USED_AT, utc(now))
                .where(T.PRINCIPAL_ID.eq(principalId)).and(T.TOKEN_HASH.eq(tokenHash)).and(T.EXPIRES_AT.gt(utc(now)))
                .execute() == 1;
    }

    public List<TrustedDevice> trustedDevices(String principalId) {
        return dsl.selectFrom(T).where(T.PRINCIPAL_ID.eq(principalId)).orderBy(T.CREATED_AT.desc(), T.ID.desc())
                .fetch(r -> new TrustedDevice(r.getId(), r.getPrincipalId(), r.getLabel(), at(r.getExpiresAt()),
                        at(r.getCreatedAt()), at(r.getLastUsedAt())));
    }

    public int countTrustedDevices(String principalId) {
        return dsl.fetchCount(T, T.PRINCIPAL_ID.eq(principalId));
    }

    /// Owner-scoped: another principal's id deletes nothing.
    public boolean revokeTrustedDevice(String principalId, String id) {
        return dsl.deleteFrom(T).where(T.PRINCIPAL_ID.eq(principalId)).and(T.ID.eq(id)).execute() == 1;
    }

    public int revokeAllTrustedDevices(String principalId) {
        return dsl.deleteFrom(T).where(T.PRINCIPAL_ID.eq(principalId)).execute();
    }

    // ── sweeps (the purger, ruling I-Q17) ──────────────────────────────────

    public int deleteExpiredPins(Instant before) {
        return dsl.deleteFrom(P).where(P.EXPIRES_AT.lt(utc(before))).execute();
    }

    public int deleteExpiredTrustedDevices(Instant before) {
        return dsl.deleteFrom(T).where(T.EXPIRES_AT.lt(utc(before))).execute();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private static Instant at(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
