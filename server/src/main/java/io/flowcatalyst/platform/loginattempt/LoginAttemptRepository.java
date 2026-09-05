package io.flowcatalyst.platform.loginattempt;

import io.flowcatalyst.db.generated.tables.IamLoginAttempts;
import io.flowcatalyst.platform.shared.apicommon.KeysetCursor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /// (`attempted_at DESC, id DESC`), strictly after `after` when given
    /// (`null` = first page). The caller over-fetches by one to learn
    /// whether a next page exists.
    public List<LoginAttempt> findPage(ListFilter f, KeysetCursor after, int limit) {
        requireLimit(limit);
        Condition where = DSL.noCondition();
        if (f.attemptType() != null) where = where.and(T.ATTEMPT_TYPE.eq(f.attemptType()));
        if (f.outcome() != null) where = where.and(T.OUTCOME.eq(f.outcome()));
        if (f.identifier() != null) where = where.and(T.IDENTIFIER.eq(f.identifier()));
        if (f.principalId() != null) where = where.and(T.PRINCIPAL_ID.eq(f.principalId()));
        if (f.from() != null) where = where.and(T.ATTEMPTED_AT.ge(utc(f.from())));
        if (f.to() != null) where = where.and(T.ATTEMPTED_AT.le(utc(f.to())));
        if (after != null) where = where.and(DSL.row(T.ATTEMPTED_AT, T.ID).lt(utc(after.at()), after.id()));
        return dsl.selectFrom(T)
                .where(where)
                .orderBy(T.ATTEMPTED_AT.desc(), T.ID.desc())
                .limit(limit)
                .fetch(LoginAttemptRepository::toEntity);
    }

    /// The newest `limit` attempts for an identifier, `attempted_at DESC`
    /// (the session-history panel reads 20).
    public List<LoginAttempt> findRecentByIdentifier(String identifier, int limit) {
        Objects.requireNonNull(identifier, "identifier");
        requireLimit(limit);
        return dsl.selectFrom(T)
                .where(T.IDENTIFIER.eq(identifier))
                .orderBy(T.ATTEMPTED_AT.desc(), T.ID.desc())
                .limit(limit)
                .fetch(LoginAttemptRepository::toEntity);
    }

    // ── Reads: backoff ─────────────────────────────────────────────────────
    // Every argument is required: a `null` identifier / ip / since is a
    // programming error, never "no filter" (the policy skips the per-pair
    // step when it has no IP — spec §5). Identifier equality is raw; the
    // callers normalise (open question 6).

    /// How far back [#lastSuccessAt] looks. `iam_login_attempts` is
    /// range-partitioned by `attempted_at` (Go migration 049), so an
    /// unbounded `MAX` would touch every partition on every login — and a
    /// never-succeeded identifier, which is what every enumeration probe
    /// is, would pay that on every attempt. Owner ruling (2026-09-03, Go
    /// `3b64775`): a success older than this bound reads as **never
    /// succeeded** — the standard 30-day window applies in full; dormancy
    /// never weakens the lockout — and there is deliberately no second,
    /// unbounded query to recover the true stale timestamp.
    public static final Duration LAST_SUCCESS_LOOKBACK = Duration.ofDays(400);

    /// When the identifier last logged in successfully within
    /// [#LAST_SUCCESS_LOOKBACK]; empty when never, or not within it.
    /// Bounds the failure-counting window of the backoff (spec §5).
    public Optional<Instant> lastSuccessAt(String identifier) {
        return lastSuccessAt(identifier, Instant.now());
    }

    Optional<Instant> lastSuccessAt(String identifier, Instant now) {
        Objects.requireNonNull(identifier, "identifier");
        OffsetDateTime since = now.minus(LAST_SUCCESS_LOOKBACK).atOffset(ZoneOffset.UTC);
        // One scan of the identifier's window: the latest SUCCESS, and — X-06 —
        // whether any row in scope carries an outcome that is neither SUCCESS
        // nor FAILURE. Such a row must fail this read loudly rather than be
        // silently left out of the aggregate as if it simply did not match:
        // the caller would otherwise keep treating the identifier as never
        // (or always) succeeded when the truth is unknown. This sits on the
        // login hot path, so it is one indexed query, not two.
        var last = DSL.max(T.ATTEMPTED_AT).filterWhere(T.OUTCOME.eq(AttemptOutcome.SUCCESS.name()));
        var corruptId = DSL.min(T.ID).filterWhere(T.OUTCOME.notIn(KNOWN_OUTCOMES));
        var corruptOutcome = DSL.min(T.OUTCOME).filterWhere(T.OUTCOME.notIn(KNOWN_OUTCOMES));
        var row = dsl.select(last, corruptId, corruptOutcome).from(T)
                .where(T.IDENTIFIER.eq(identifier).and(T.ATTEMPTED_AT.ge(since)))
                .fetchSingle();
        if (row.get(corruptId) != null) {
            outcome(row.get(corruptId), row.get(corruptOutcome));
        }
        return Optional.ofNullable(row.get(last)).map(OffsetDateTime::toInstant);
    }

    private static final List<String> KNOWN_OUTCOMES = List.of(AttemptOutcome.SUCCESS.name(), AttemptOutcome.FAILURE.name());

    /// Failures for the `(identifier, ip)` pair since `since` (inclusive):
    /// the count and the latest one. Drives the per-pair exponential backoff.
    public FailureStats failureStatsSince(String identifier, String ip, Instant since) {
        Objects.requireNonNull(ip, "ip");
        var count = DSL.count();
        var last = DSL.max(T.ATTEMPTED_AT);
        Record row = dsl.select(count, last).from(T)
                .where(failuresOf(identifier, since).and(T.IP_ADDRESS.eq(ip)))
                .fetchSingle(); // an ungrouped aggregate always yields exactly one row
        OffsetDateTime lastAt = row.get(last);
        return new FailureStats(row.get(count), lastAt == null ? null : lastAt.toInstant());
    }

    /// Failures for the identifier across every IP since `since` (inclusive).
    /// Drives the global ceiling.
    public int countFailuresSince(String identifier, Instant since) {
        return dsl.fetchCount(T, failuresOf(identifier, since));
    }

    private static Condition failuresOf(String identifier, Instant since) {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(since, "since");
        return T.OUTCOME.eq(AttemptOutcome.FAILURE.name()).and(T.IDENTIFIER.eq(identifier)).and(T.ATTEMPTED_AT.ge(utc(since)));
    }

    /// `limit` is bounded by the caller (the API, the panel) — no silent correction (spec §5).
    private static void requireLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit < 1: " + limit);
    }

    // ── Quarterly partition maintenance (purger spec §4, migration V5) ──────
    // `iam_login_attempts` is range-partitioned by quarter on `attempted_at`
    // (`V5__partition_login_attempts.sql`, adopted from Go migration 049), with
    // partitions named `iam_login_attempts_YYYY_qN` (N = 1-4, one-based, no
    // zero-pad — exactly `to_char(quarter_start, 'YYYY') || '_q' ||
    // to_char(quarter_start, 'Q')`) and an `iam_login_attempts_default` catch-all
    // the purger must never touch.

    private static final String PARTITION_PREFIX = "iam_login_attempts_";
    private static final Pattern QUARTER_SUFFIX = Pattern.compile("^(\\d{4})_q([1-4])$");

    /// The start (UTC) of the calendar quarter containing `at`.
    static Instant quarterStart(Instant at) {
        var z = at.atZone(ZoneOffset.UTC);
        int quarterStartMonth = ((z.getMonthValue() - 1) / 3) * 3 + 1;
        return YearMonth.of(z.getYear(), quarterStartMonth).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /// The migration's exact partition name for the quarter beginning at `quarterStart`.
    static String quarterlyPartitionName(Instant quarterStart) {
        var z = quarterStart.atZone(ZoneOffset.UTC);
        int quarter = (z.getMonthValue() - 1) / 3 + 1;
        return PARTITION_PREFIX + z.getYear() + "_q" + quarter;
    }

    /// `CREATE TABLE IF NOT EXISTS <name> PARTITION OF iam_login_attempts FOR
    /// VALUES FROM (…) TO (…)` for the quarter containing `at` — idempotent,
    /// safe to call every purger tick for "this quarter" and "next quarter"
    /// (purger spec §4). Bound expressions are Postgres constants, not bind
    /// parameters (`could not determine data type of parameter`), so the two
    /// instants — both computed here, never user input — are rendered as
    /// literals, matching [io.flowcatalyst.stream.PartitionManager#ensureForward].
    public void ensureQuarterlyPartition(Instant at) {
        Instant start = quarterStart(at);
        Instant end = start.atZone(ZoneOffset.UTC).plusMonths(3).toInstant();
        String name = quarterlyPartitionName(start);
        dsl.execute("CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF iam_login_attempts"
                + " FOR VALUES FROM ('" + start + "') TO ('" + end + "')");
    }

    /// Drops every quarterly child of `iam_login_attempts` whose range ends at
    /// or before `cutoff` (purger spec §4). Only names matching the
    /// `_YYYY_qN` shape are ever considered — the default partition (and
    /// anything else) is left alone by construction, not by an extra check.
    public void dropPartitionsOlderThan(Instant cutoff) {
        for (String child : quarterlyPartitionNames()) {
            Optional<Instant> end = parseQuarterlyPartitionEnd(child);
            if (end.isEmpty() || end.get().isAfter(cutoff)) {
                continue;
            }
            dsl.execute("DROP TABLE IF EXISTS " + child);
        }
    }

    /// The exclusive end of `childName`'s range, or empty when it is not
    /// exactly a `iam_login_attempts_YYYY_qN` name — the default partition,
    /// or anything else `pg_inherits` might list.
    static Optional<Instant> parseQuarterlyPartitionEnd(String childName) {
        if (!childName.startsWith(PARTITION_PREFIX)) {
            return Optional.empty();
        }
        Matcher m = QUARTER_SUFFIX.matcher(childName.substring(PARTITION_PREFIX.length()));
        if (!m.matches()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(m.group(1));
        int quarter = Integer.parseInt(m.group(2));
        int startMonth = (quarter - 1) * 3 + 1;
        Instant start = YearMonth.of(year, startMonth).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return Optional.of(start.atZone(ZoneOffset.UTC).plusMonths(3).toInstant());
    }

    private List<String> quarterlyPartitionNames() {
        return dsl.resultQuery("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'iam_login_attempts'
                """).fetch(0, String.class);
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static LoginAttempt toEntity(Record row) {
        String id = row.get(T.ID);
        return new LoginAttempt(
                id,
                attemptType(id, row.get(T.ATTEMPT_TYPE)),
                outcome(id, row.get(T.OUTCOME)),
                row.get(T.FAILURE_REASON),
                row.get(T.IDENTIFIER),
                row.get(T.PRINCIPAL_ID),
                row.get(T.IP_ADDRESS),
                row.get(T.USER_AGENT),
                row.get(T.ATTEMPTED_AT).toInstant());
    }

    /// [AttemptType#parse], wrapped so a corrupt stored value fails loudly
    /// with the offending row's id (X-06).
    private static AttemptType attemptType(String rowId, String stored) {
        try {
            return AttemptType.parse(stored);
        } catch (AttemptType.UnrecognisedAttemptTypeException e) {
            throw new CorruptLoginAttemptException(rowId, e);
        }
    }

    /// [AttemptOutcome#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06).
    private static AttemptOutcome outcome(String rowId, String stored) {
        try {
            return AttemptOutcome.parse(stored);
        } catch (AttemptOutcome.UnrecognisedOutcomeException e) {
            throw new CorruptLoginAttemptException(rowId, e);
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
