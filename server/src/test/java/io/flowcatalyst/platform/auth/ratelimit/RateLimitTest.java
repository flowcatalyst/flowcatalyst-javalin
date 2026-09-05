package io.flowcatalyst.platform.auth.ratelimit;

import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.IAM_RATE_LIMIT_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// The Postgres store (auth-core §11, ruling A-20), the governor, the
/// policies, and the fail-open enforcement.
class RateLimitTest {

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(IAM_RATE_LIMIT_EVENTS).where(IAM_RATE_LIMIT_EVENTS.KEY.like(RUN + "%")).execute();
    }

    // ── the Postgres store ────────────────────────────────────────────────

    /// Ruling A-20 / Go `a88164f`: the count must include the row this call
    /// just inserted. With Limit=2, the third call is denied — a plain count
    /// under the CTE's snapshot would admit it.
    @Test
    void theLimitPlusOnthCallIsDenied() {
        var store = new PostgresRateLimitStore(TestPg.dataSource());
        var policy = new RateLimit.Policy(Duration.ofMinutes(1), 2);
        String key = RUN + "-ip";
        assertThat(store.checkAndRecord(RateLimit.Bucket.OAUTH_TOKEN_IP, key, policy).allowed()).isTrue();
        assertThat(store.checkAndRecord(RateLimit.Bucket.OAUTH_TOKEN_IP, key, policy).allowed()).isTrue();
        var third = store.checkAndRecord(RateLimit.Bucket.OAUTH_TOKEN_IP, key, policy);
        assertThat(third.allowed()).as("the (limit + 1)-th call").isFalse();
        assertThat(third.retryAfterSecs()).isBetween(1L, 60L);
        assertThat(DB.fetchCount(IAM_RATE_LIMIT_EVENTS, IAM_RATE_LIMIT_EVENTS.KEY.eq(key)))
                .as("every attempt is recorded, denied ones included").isEqualTo(3);
    }

    @Test
    void bucketsAndKeysDoNotCollideAndEventsAgeOutOfTheWindow() {
        var clockRef = new AtomicReference<>(Instant.now().minusSeconds(1));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clockRef.get(); }
        };
        var store = new PostgresRateLimitStore(TestPg.dataSource(), clock);
        var policy = new RateLimit.Policy(Duration.ofSeconds(30), 1);
        String key = RUN + "-w";
        assertThat(store.checkAndRecord(RateLimit.Bucket.OAUTH_AUTHORIZE_IP, key, policy).allowed()).isTrue();
        assertThat(store.checkAndRecord(RateLimit.Bucket.OAUTH_AUTHORIZE_IP, key, policy).allowed()).isFalse();
        assertThat(store.checkAndRecord(RateLimit.Bucket.OAUTH_TOKEN_IP, key, policy).allowed()).as("another bucket").isTrue();
        assertThat(store.checkAndRecord(RateLimit.Bucket.OAUTH_AUTHORIZE_IP, key + "2", policy).allowed()).as("another key").isTrue();
        clockRef.set(clockRef.get().plusSeconds(31));
        assertThat(store.checkAndRecord(RateLimit.Bucket.OAUTH_AUTHORIZE_IP, key, policy).allowed()).as("window rolled").isTrue();
        assertThat(store.prune(Duration.ofSeconds(20))).as("the old events pruned").isGreaterThanOrEqualTo(2);
    }

    // ── enforcement fails open ────────────────────────────────────────────

    @Test
    void enforceFailsOpenOnABackendErrorAndOnANullStore() {
        RateLimit.Store broken = new RateLimit.Store() {
            @Override public RateLimit.Decision checkAndRecord(RateLimit.Bucket b, String k, RateLimit.Policy p) { throw new IllegalStateException("down"); }
            @Override public int prune(Duration olderThan) { return 0; }
        };
        var policy = new RateLimit.Policy(Duration.ofMinutes(1), 1);
        assertThat(RateLimit.enforce(broken, RateLimit.Bucket.OAUTH_TOKEN_IP, "k", policy)).isNull();
        assertThat(RateLimit.enforce(null, RateLimit.Bucket.OAUTH_TOKEN_IP, "k", policy)).isNull();
        RateLimit.Store denying = new RateLimit.Store() {
            @Override public RateLimit.Decision checkAndRecord(RateLimit.Bucket b, String k, RateLimit.Policy p) { return RateLimit.Decision.denied(7); }
            @Override public int prune(Duration olderThan) { return 0; }
        };
        assertThat(RateLimit.enforce(denying, RateLimit.Bucket.OAUTH_TOKEN_IP, "k", policy).retryAfterSecs()).isEqualTo(7);
    }

    // ── policies ──────────────────────────────────────────────────────────

    @Test
    void policiesReadTheEnvWithGoDefaultsAndMaxWindowCoversEveryPolicy() {
        var p = RateLimit.Policies.fromEnv(new EnvReader(Map.of("FC_RL_OAUTH_TOKEN_CLIENT_PER_MIN", "42")));
        assertThat(p.oauthTokenIp().limit()).isEqualTo(600);
        assertThat(p.oauthTokenClient().limit()).isEqualTo(42);
        assertThat(p.passwordResetEmail()).isEqualTo(new RateLimit.Policy(Duration.ofHours(1), 5));
        assertThat(p.portalLogin()).isEqualTo(new RateLimit.Policy(Duration.ofMinutes(15), 10));
        assertThat(p.maxWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(RateLimitStores.build(new EnvReader(Map.of("FC_RATE_LIMIT_DISABLE", "1")), TestPg.dataSource()))
                .isInstanceOf(RateLimit.NoopStore.class);
        assertThat(RateLimitStores.build(new EnvReader(Map.of()), TestPg.dataSource()))
                .isInstanceOf(PostgresRateLimitStore.class);
    }

    // ── governor ──────────────────────────────────────────────────────────

    @Test
    void theGovernorAdmitsABurstThenRefillsAtTheSustainedRate() {
        var clockRef = new AtomicReference<>(Instant.parse("2026-09-05T12:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clockRef.get(); }
        };
        var g = new Governor(new Governor.Config(60, 3), clock); // one token per second, burst 3
        assertThat(g.check("ip").ok()).isTrue();
        assertThat(g.check("ip").ok()).isTrue();
        assertThat(g.check("ip").ok()).isTrue();
        var fourth = g.check("ip");
        assertThat(fourth.ok()).as("the burst is spent").isFalse();
        assertThat(fourth.retryAfterSecs()).isEqualTo(1);
        assertThat(g.check("other").ok()).as("keys are independent").isTrue();
        clockRef.set(clockRef.get().plusSeconds(1));
        assertThat(g.check("ip").ok()).as("one second refilled one token").isTrue();
        assertThat(g.check("ip").ok()).isFalse();
        clockRef.set(clockRef.get().plusSeconds(600));
        assertThat(g.prune(Duration.ofMinutes(5))).as("both keys idle > 5 min").isEqualTo(2);
        assertThat(g.size()).isZero();
    }
}
