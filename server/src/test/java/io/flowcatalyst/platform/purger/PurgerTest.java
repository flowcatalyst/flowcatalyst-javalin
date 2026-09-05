package io.flowcatalyst.platform.purger;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.auth.mfa.MfaRepository;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// [Purger] against a real embedded Postgres (`docs/spec/scheduled-job-scheduler.md`
/// §4): the login-attempt quarterly-partition maintenance (the one step this
/// port implements against real DDL) and the lifecycle. The rate-limit-event
/// step is pinned as a genuine no-op — see [#rateLimitStepIsANamedNoOp] — not
/// tested as "prunes expired rows" because it deliberately does not: spec §4,
/// "leave a named no-op step ... if not [written by Java]".
class PurgerTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final LoginAttemptRepository LOGIN_ATTEMPTS = new LoginAttemptRepository(DS);

    private static boolean tableExists(String name) {
        try (Connection conn = DS.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT to_regclass(?)")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getString(1) != null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void dropIfExists(String name) {
        try (Connection conn = DS.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + name);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Quarterly partitions: created idempotently ──────────────────────────

    @Test
    void ensureQuarterlyPartitionCreatesThisQuarterAndNextIdempotently() {
        // A run-unique future year so this never collides with the quarters
        // the V5 migration pre-creates around "real now", or with another
        // run of this same test.
        int year = 2200 + (int) (UUID.randomUUID().getMostSignificantBits() % 50 + 50);
        Instant now = Instant.parse(year + "-05-15T00:00:00Z"); // Q2
        Instant nextQuarter = now.atZone(ZoneOffset.UTC).plusMonths(3).toInstant(); // Q3
        String thisQuarterName = "iam_login_attempts_" + year + "_q2";
        String nextQuarterName = "iam_login_attempts_" + year + "_q3";
        try {
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(now);
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(nextQuarter);
            assertThat(tableExists(thisQuarterName)).as(thisQuarterName).isTrue();
            assertThat(tableExists(nextQuarterName)).as(nextQuarterName).isTrue();

            // Idempotent: a second pass over the same two quarters must not throw.
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(now);
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(nextQuarter);
            assertThat(tableExists(thisQuarterName)).isTrue();
            assertThat(tableExists(nextQuarterName)).isTrue();
        } finally {
            dropIfExists(thisQuarterName);
            dropIfExists(nextQuarterName);
        }
    }

    // ── Readiness (ruling C-Q23): the partitions the backoff store needs ────

    @Test
    void missingQuarterlyPartitionsNamesThisQuarterAndNextUntilTheyExist() {
        int year = 2300 + (int) (UUID.randomUUID().getMostSignificantBits() % 50 + 50);
        Instant now = Instant.parse(year + "-08-15T00:00:00Z"); // Q3
        String q3 = "iam_login_attempts_" + year + "_q3";
        String q4 = "iam_login_attempts_" + year + "_q4";
        try {
            assertThat(LOGIN_ATTEMPTS.missingQuarterlyPartitions(now)).as("nothing exists yet").containsExactly(q3, q4);
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(now);
            assertThat(LOGIN_ATTEMPTS.missingQuarterlyPartitions(now)).as("next quarter is still missing").containsExactly(q4);
            LOGIN_ATTEMPTS.ensureQuarterlyPartition(now.atZone(ZoneOffset.UTC).plusMonths(3).toInstant());
            assertThat(LOGIN_ATTEMPTS.missingQuarterlyPartitions(now)).as("ready").isEmpty();
        } finally {
            dropIfExists(q3);
            dropIfExists(q4);
        }
    }

    // ── dropPartitionsOlderThan: an old quarter dropped, the default kept ───

    @Test
    void dropPartitionsOlderThanDropsAnOldQuarterButNeverTheDefaultPartition() {
        // Our own old quarter (spec-mandated: never drop a partition that
        // might hold another test's rows) — 1900 predates every other
        // fixture in this suite by construction.
        String oldQuarterName = "iam_login_attempts_1900_q1";
        LOGIN_ATTEMPTS.ensureQuarterlyPartition(Instant.parse("1900-02-01T00:00:00Z"));
        assertThat(tableExists(oldQuarterName)).as("the fixture created it").isTrue();
        assertThat(tableExists("iam_login_attempts_default")).as("the default partition already exists").isTrue();

        LOGIN_ATTEMPTS.dropPartitionsOlderThan(Instant.parse("1950-01-01T00:00:00Z"));

        assertThat(tableExists(oldQuarterName)).as("older than the cutoff -> dropped").isFalse();
        assertThat(tableExists("iam_login_attempts_default")).as("the default partition is never a match").isTrue();
    }

    // ── The sweeps: one expired row and one live row per table ─────────────

    private static final Purger.Sweeps SWEEPS = Purger.Sweeps.over(DS,
            RateLimit.Policies.fromEnv(new io.flowcatalyst.server.EnvReader(java.util.Map.of())));
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Test
    void rateLimitEventsOlderThanTheLongestWindowPlusMarginAreGoneAndNewerOnesStay() {
        String bucket = "purger-" + RUN;
        Instant retention = NOW.minus(SWEEPS.rateLimitRetention());
        insertRateLimitEvent(bucket, "old", Instant.now().minus(SWEEPS.rateLimitRetention()).minusSeconds(60));
        insertRateLimitEvent(bucket, "fresh", Instant.now().minusSeconds(30));
        assertThat(SWEEPS.rateLimitRetention()).as("auth-retention §3: MaxWindow + 10 min, never a hardcoded hour")
                .isEqualTo(Duration.ofHours(1).plusMinutes(10));

        Purger.tick(SWEEPS, Instant.now());

        assertThat(DB.fetch("SELECT key FROM iam_rate_limit_events WHERE bucket = ?", bucket).getValues("key", String.class))
                .containsExactly("fresh");
        Purger.tick(SWEEPS, Instant.now());
        assertThat(DB.fetchCount(DSL.table("iam_rate_limit_events"), DSL.field("bucket").eq(bucket))).as("idempotent").isEqualTo(1);
        assertThat(retention).isBefore(NOW);
    }

    @Test
    void expiredLoginStatesFlowsAndTokensAreSweptAndPendingApprovalsPastExpiryAreMarkedExpired() {
        String pid = principal();
        String liveState = "st-live-" + RUN;
        String deadState = "st-dead-" + RUN;
        DB.execute("INSERT INTO oauth_oidc_login_states (state, email_domain, identity_provider_id, email_domain_mapping_id, nonce, code_verifier, created_at, expires_at)"
                + " VALUES (?, 'x.example', 'idp_x', 'edm_x', 'n', 'v', now(), now() + interval '1 hour'), (?, 'x.example', 'idp_x', 'edm_x', 'n', 'v', now(), now() - interval '1 minute')", liveState, deadState);
        DB.execute("INSERT INTO portal_login_flows (id, oauth_client_id, portal_client_id, redirect_uri, state, created_at, expires_at)"
                + " VALUES (?, 'oac_x', 'cli_x', 'https://p.example/cb', 's', now(), now() + interval '1 hour'), (?, 'oac_x', 'cli_x', 'https://p.example/cb', 's', now(), now() - interval '1 minute')",
                "plf-live-" + RUN, "plf-dead-" + RUN);
        // Reset tokens keep a day of grace after expiry (ruling I-Q17: expiry + grace).
        DB.execute("INSERT INTO iam_password_reset_tokens (id, principal_id, token_hash, expires_at, created_at, purpose, requires_factor, factor_attempts)"
                + " VALUES (?, ?, 'h1', now() - interval '2 hours', now(), 'reset', false, 0),"
                + " (?, ?, 'h2', now() - interval '2 days', now(), 'reset', false, 0)",
                "prt-graced-" + RUN, pid, "prt-dead-" + RUN, pid);
        DB.execute("INSERT INTO iam_reset_approval_requests (id, principal_id, status, expires_at, created_at)"
                + " VALUES (?, ?, 'PENDING', now() - interval '1 minute', now()), (?, ?, 'PENDING', now() + interval '1 hour', now()), (?, ?, 'APPROVED', now() - interval '1 minute', now())",
                "ra-exp-" + RUN, pid, "ra-live-" + RUN, pid, "ra-appr-" + RUN, pid);

        Purger.tick(SWEEPS, Instant.now());

        assertThat(DB.fetch("SELECT state FROM oauth_oidc_login_states WHERE state IN (?, ?)", liveState, deadState).getValues("state", String.class))
                .containsExactly(liveState);
        assertThat(DB.fetch("SELECT id FROM portal_login_flows WHERE id IN (?, ?)", "plf-live-" + RUN, "plf-dead-" + RUN).getValues("id", String.class))
                .containsExactly("plf-live-" + RUN);
        assertThat(DB.fetch("SELECT id FROM iam_password_reset_tokens WHERE principal_id = ?", pid).getValues("id", String.class))
                .as("two hours past expiry is inside the grace; two days is not").containsExactly("prt-graced-" + RUN);
        assertThat(DB.fetch("SELECT id, status FROM iam_reset_approval_requests WHERE principal_id = ? ORDER BY id", pid).intoMaps())
                .extracting(m -> m.get("id") + "=" + m.get("status"))
                .containsExactly("ra-appr-" + RUN + "=APPROVED", "ra-exp-" + RUN + "=EXPIRED", "ra-live-" + RUN + "=PENDING");
        assertThat(DB.fetchCount(DSL.table("iam_reset_approval_requests"), DSL.field("principal_id").eq(pid))).as("defect 11: marked, never deleted").isEqualTo(3);

        DB.execute("DELETE FROM oauth_oidc_login_states WHERE state = ?", liveState);
        DB.execute("DELETE FROM portal_login_flows WHERE id = ?", "plf-live-" + RUN);
        DB.execute("DELETE FROM iam_principals WHERE id = ?", pid); // cascades the tokens and approvals
    }

    @Test
    void aLapsedPreviousSecretIsClearedAndOneStillInsideItsOverlapIsKept() {
        var repo = new OAuthClientRepository(DS, new ApplicationRepository(DS));
        var uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
        OAuthClient lapsed = OAuthClient.create("purge-lapsed-" + RUN, "L", ClientType.CONFIDENTIAL).withSecretRef("encrypted:old")
                .rotateSecret("encrypted:new", Duration.ofHours(1), Instant.now().minus(Duration.ofHours(2))).client();
        OAuthClient live = OAuthClient.create("purge-live-" + RUN, "V", ClientType.CONFIDENTIAL).withSecretRef("encrypted:old")
                .rotateSecret("encrypted:new", Duration.ofHours(6), Instant.now()).client();
        uow.inTransaction(tx -> { repo.persist(lapsed, tx.dbTx()); repo.persist(live, tx.dbTx()); return null; });
        try {
            Purger.tick(SWEEPS, Instant.now());
            OAuthClient l = repo.findById(lapsed.id()).orElseThrow();
            assertThat(l.previousSecretRef()).as("cleared at rest once it can no longer authenticate").isNull();
            assertThat(l.previousSecretExpiresAt()).isNull();
            assertThat(l.secretRef()).as("the current secret is untouched").isEqualTo("encrypted:new");
            OAuthClient v = repo.findById(live.id()).orElseThrow();
            assertThat(v.previousSecretRef()).isEqualTo("encrypted:old");
        } finally {
            uow.inTransaction(tx -> { repo.delete(lapsed, tx.dbTx()); repo.delete(live, tx.dbTx()); return null; });
        }
    }

    @Test
    void mfaPinsAndTrustedDevicesGetADayOfGraceAfterExpiry() {
        String pid = principal();
        var mfa = new MfaRepository(DS);
        mfa.issuePin(pid, "login", "h", Instant.now().minus(Duration.ofHours(2)));           // expired, inside grace
        String graced = mfa.insertTrustedDevice(pid, "td-graced-" + RUN, "g", Instant.now().minus(Duration.ofHours(2))).id();
        String dead = mfa.insertTrustedDevice(pid, "td-dead-" + RUN, "d", Instant.now().minus(Duration.ofDays(2))).id();
        String live = mfa.insertTrustedDevice(pid, "td-live-" + RUN, "l", Instant.now().plus(Duration.ofDays(2))).id();

        Purger.tick(SWEEPS, Instant.now());

        assertThat(mfa.latestPin(pid, "login")).as("inside the grace").isPresent();
        assertThat(mfa.trustedDevices(pid)).extracting(d -> d.id()).containsExactlyInAnyOrder(graced, live);
        assertThat(dead).isNotNull();
        DB.execute("DELETE FROM iam_principals WHERE id = ?", pid);
    }

    private static void insertRateLimitEvent(String bucket, String key, Instant at) {
        DB.execute("INSERT INTO iam_rate_limit_events (bucket, key, occurred_at) VALUES (?, ?, ?::timestamptz)", bucket, key, at.toString());
    }

    private static String principal() {
        String id = EntityType.PRINCIPAL.generate();
        DB.execute("INSERT INTO iam_principals (id, type, scope, name, active, all_applications, email, email_domain, created_at, updated_at)"
                + " VALUES (?, 'USER', 'ANCHOR', 'Purger', true, false, ?, 'example.com', now(), now())", id, "purger-" + RUN + "-" + id + "@example.com");
        return id;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    void startAndCloseCompletePromptly() throws InterruptedException {
        var purger = Purger.start(SWEEPS, Duration.ofMillis(50), Clock.systemUTC());
        Thread.sleep(120); // let it actually enter its sleep at least once

        long startNanos = System.nanoTime();
        purger.close();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).as("close() must interrupt, not wait out the tick interval").isLessThan(5_000);
    }
}
