package io.flowcatalyst.platform.loginattempt;

import io.flowcatalyst.db.generated.tables.IamLoginAttempts;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;

/// `iam_login_attempts` via jOOQ (spec §5). The one write is a direct,
/// autocommit insert: recording an attempt is infrastructure processing,
/// not a use case — no unit of work, no event, no audit row (spec §6). The
/// reads serve the admin list and the brute-force backoff. Pure CRUD — no
/// domain decisions live here.
public final class LoginAttemptRepository {

    private static final IamLoginAttempts T = IAM_LOGIN_ATTEMPTS;

    /// One context for reads and the autocommit write: jOOQ acquires and
    /// releases a pooled connection per statement.
    private final DSLContext dsl;

    public LoginAttemptRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Filters for [#findPage] (spec §3); `null` = no filter on that column.
    /// `attemptType` / `outcome` are compared to the stored string as given
    /// (an unknown value matches no row — open question 5); `from` / `to`
    /// are inclusive bounds on `attempted_at`.
    public record ListFilter(String attemptType, String outcome, String identifier, String principalId,
                             Instant from, Instant to) {
    }

    /// What the per-pair backoff needs (spec §5): how many failures since
    /// the bound and when the latest was. `lastFailureAt` is `null` when
    /// `count` is 0.
    public record FailureStats(int count, Instant lastFailureAt) {
        public FailureStats {
            if (count < 0) throw new IllegalArgumentException("count < 0: " + count);
            if (count == 0 && lastFailureAt != null) throw new IllegalArgumentException("lastFailureAt without failures");
        }
    }

    // ── Write ──────────────────────────────────────────────────────────────

    /// Inserts the attempt as given, outside any unit of work (spec §6).
    /// Throws on failure — the caller decides whether that may fail a login
    /// (it never does; the "log and continue" lives in the auth flow).
    public void recordAttempt(LoginAttempt a) {
        dsl.insertInto(T)
                .set(T.ID, a.id())
                .set(T.ATTEMPT_TYPE, a.attemptType().name())
                .set(T.OUTCOME, a.outcome().name())
                .set(T.FAILURE_REASON, a.failureReason())
                .set(T.IDENTIFIER, a.identifier())
                .set(T.PRINCIPAL_ID, a.principalId())
                .set(T.IP_ADDRESS, a.ipAddress())
                .set(T.USER_AGENT, a.userAgent())
                .set(T.ATTEMPTED_AT, utc(a.attemptedAt()))
                .execute();
    }

    // ── Reads: admin list ──────────────────────────────────────────────────

    /// Up to `limit` attempts matching every non-null filter, newest first
    /// (`attempted_at DESC, id DESC`), strictly after `after` when given.
    /// The caller over-fetches by one to learn whether a next page exists.
    public List<LoginAttempt> findPage(ListFilter f, LoginAttemptCursor after, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit < 1: " + limit);
        Condition where = DSL.noCondition();
        if (f.attemptType() != null) where = where.and(T.ATTEMPT_TYPE.eq(f.attemptType()));
        if (f.outcome() != null) where = where.and(T.OUTCOME.eq(f.outcome()));
        if (f.identifier() != null) where = where.and(T.IDENTIFIER.eq(f.identifier()));
        if (f.principalId() != null) where = where.and(T.PRINCIPAL_ID.eq(f.principalId()));
        if (f.from() != null) where = where.and(T.ATTEMPTED_AT.ge(utc(f.from())));
        if (f.to() != null) where = where.and(T.ATTEMPTED_AT.le(utc(f.to())));
        if (after != null) where = where.and(DSL.row(T.ATTEMPTED_AT, T.ID).lt(utc(after.attemptedAt()), after.id()));
        return dsl.selectFrom(T)
                .where(where)
                .orderBy(T.ATTEMPTED_AT.desc(), T.ID.desc())
                .limit(limit)
                .fetch(LoginAttemptRepository::toEntity);
    }

    /// The newest `limit` attempts for an identifier, `attempted_at DESC`
    /// (the session-history panel reads 20).
    public List<LoginAttempt> findRecentByIdentifier(String identifier, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit < 1: " + limit);
        return dsl.selectFrom(T)
                .where(T.IDENTIFIER.eq(identifier))
                .orderBy(T.ATTEMPTED_AT.desc(), T.ID.desc())
                .limit(limit)
                .fetch(LoginAttemptRepository::toEntity);
    }

    // ── Reads: backoff ─────────────────────────────────────────────────────

    /// When the identifier last logged in successfully; empty when never.
    /// Bounds the failure-counting window of the backoff (spec §5).
    public Optional<Instant> lastSuccessAt(String identifier) {
        OffsetDateTime max = dsl.select(DSL.max(T.ATTEMPTED_AT)).from(T)
                .where(T.OUTCOME.eq(AttemptOutcome.SUCCESS.name()).and(T.IDENTIFIER.eq(identifier)))
                .fetchOne(DSL.max(T.ATTEMPTED_AT));
        return Optional.ofNullable(max).map(OffsetDateTime::toInstant);
    }

    /// Failures for the `(identifier, ip)` pair since `since` (inclusive):
    /// the count and the latest one. Drives the per-pair exponential backoff.
    public FailureStats failureStatsSince(String identifier, String ip, Instant since) {
        var count = DSL.count();
        var last = DSL.max(T.ATTEMPTED_AT);
        Record row = dsl.select(count, last).from(T)
                .where(failuresOf(identifier).and(T.IP_ADDRESS.eq(ip)).and(T.ATTEMPTED_AT.ge(utc(since))))
                .fetchOne();
        OffsetDateTime lastAt = row == null ? null : row.get(last);
        return new FailureStats(row == null ? 0 : row.get(count), lastAt == null ? null : lastAt.toInstant());
    }

    /// Failures for the identifier across every IP since `since` (inclusive).
    /// Drives the global ceiling.
    public int countFailuresSince(String identifier, Instant since) {
        return dsl.fetchCount(T, failuresOf(identifier).and(T.ATTEMPTED_AT.ge(utc(since))));
    }

    private static Condition failuresOf(String identifier) {
        return T.OUTCOME.eq(AttemptOutcome.FAILURE.name()).and(T.IDENTIFIER.eq(identifier));
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static LoginAttempt toEntity(Record row) {
        return new LoginAttempt(
                row.get(T.ID),
                AttemptType.parse(row.get(T.ATTEMPT_TYPE)),
                AttemptOutcome.parse(row.get(T.OUTCOME)),
                row.get(T.FAILURE_REASON),
                row.get(T.IDENTIFIER),
                row.get(T.PRINCIPAL_ID),
                row.get(T.IP_ADDRESS),
                row.get(T.USER_AGENT),
                row.get(T.ATTEMPTED_AT).toInstant());
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
