package io.flowcatalyst.platform.auth.ratelimit;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import static io.flowcatalyst.db.generated.Tables.IAM_RATE_LIMIT_EVENTS;

/// The Postgres [RateLimit.Store]: one row per attempt in
/// `iam_rate_limit_events`, counted in the window in the same statement
/// (`docs/spec/auth-core.md` §11; Go `ratelimit.PostgresStore`).
///
/// **The count must include the row this call just inserted** (ruling
/// A-20, Go `a88164f`). A data-modifying CTE and the outer `SELECT` share
/// one snapshot, so a plain count cannot see the new row and admits one
/// request past every ceiling. The inserted row is added back explicitly
/// via `RETURNING` + `COUNT(*) FROM ins`; the test pins that the
/// `(limit + 1)`-th call is denied.
public final class PostgresRateLimitStore implements RateLimit.Store {

    private final DSLContext dsl;
    private final Clock clock;

    public PostgresRateLimitStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public PostgresRateLimitStore(DataSource dataSource, Clock clock) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RateLimit.Decision checkAndRecord(RateLimit.Bucket bucket, String key, RateLimit.Policy policy) {
        Instant now = clock.instant();
        OffsetDateTime windowStart = now.minus(policy.window()).atOffset(ZoneOffset.UTC);
        Long count = dsl.fetchOne("""
                        WITH ins AS (
                            INSERT INTO iam_rate_limit_events (bucket, key, occurred_at)
                            VALUES ({0}, {1}, {2})
                            RETURNING occurred_at
                        )
                        SELECT (SELECT COUNT(*) FROM iam_rate_limit_events
                                 WHERE bucket = {0} AND key = {1} AND occurred_at > {3})
                             + (SELECT COUNT(*) FROM ins)
                        """,
                        DSL.val(bucket.key()), DSL.val(key), DSL.val(now.atOffset(ZoneOffset.UTC)), DSL.val(windowStart))
                .get(0, Long.class);
        if (count == null) {
            throw new IllegalStateException("rate-limit count returned no row");
        }
        if (count <= policy.limit()) {
            return RateLimit.Decision.ALLOWED;
        }
        // Retry-After = time until the oldest in-window event ages out.
        OffsetDateTime oldest = dsl.select(DSL.min(IAM_RATE_LIMIT_EVENTS.OCCURRED_AT)).from(IAM_RATE_LIMIT_EVENTS)
                .where(IAM_RATE_LIMIT_EVENTS.BUCKET.eq(bucket.key()))
                .and(IAM_RATE_LIMIT_EVENTS.KEY.eq(key))
                .and(IAM_RATE_LIMIT_EVENTS.OCCURRED_AT.gt(windowStart))
                .fetchOne(0, OffsetDateTime.class);
        long retryAfter = policy.window().getSeconds();
        if (oldest != null) {
            long elapsed = Math.max(Duration.between(oldest.toInstant(), now).getSeconds(), 0);
            retryAfter = policy.window().getSeconds() - elapsed;
        }
        return RateLimit.Decision.denied(retryAfter);
    }

    @Override
    public int prune(Duration olderThan) {
        OffsetDateTime cutoff = clock.instant().minus(olderThan).atOffset(ZoneOffset.UTC);
        return dsl.deleteFrom(IAM_RATE_LIMIT_EVENTS).where(IAM_RATE_LIMIT_EVENTS.OCCURRED_AT.lt(cutoff)).execute();
    }
}
