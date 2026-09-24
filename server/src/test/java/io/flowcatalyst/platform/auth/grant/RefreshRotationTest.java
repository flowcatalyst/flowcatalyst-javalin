package io.flowcatalyst.platform.auth.grant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_PAYLOADS;
import static org.assertj.core.api.Assertions.assertThat;

/// auth-core §7.4 / §8.2: rotation, lineage, the absolute expiry cap, and
/// reuse detection — each pinned by the row state afterwards.
class RefreshRotationTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    // Whole seconds: authTime is stored as epoch seconds.
    private static final Instant NOW = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final GrantStore STORE = new GrantStore(DS, CLOCK);
    private static final RefreshRotation ROTATION = new RefreshRotation(STORE, CLOCK, RefreshToken.TTL_SECONDS);

    private static String principal(String suffix) {
        return "prn_rot" + RUN + suffix;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(OAUTH_OIDC_PAYLOADS)
                .where(DSL.field("payload ->> 'accountId'", String.class).like("prn_rot" + RUN + "%")).execute();
    }

    @Test
    void aValidTokenIsRotatedWithItsLineageAndTheFamilysExpiryCapInherited() {
        Instant authTime = NOW.minusSeconds(500);
        Instant cap = NOW.plusSeconds(3600); // a family already six days old
        var issued = RefreshToken.issue(principal("a"), NOW, RefreshToken.TTL_SECONDS);
        var t = issued.token().withBinding("oac_rp", List.of("openid", "offline_access"), List.of("clt_1"), authTime)
                .withFamily("fam-" + RUN).withExpiresAt(cap);
        STORE.insert(t);

        var r = rotated(ROTATION.rotate(issued.raw(), "oac_rp"));
        var next = r.replacement();
        assertThat(next.principalId()).isEqualTo(principal("a"));
        assertThat(next.oauthClientId()).isEqualTo("oac_rp");
        assertThat(next.scopes()).containsExactly("openid", "offline_access");
        assertThat(next.accessibleClients()).containsExactly("clt_1");
        assertThat(next.authTime()).as("a rotation is not a re-authentication").isEqualTo(authTime);
        assertThat(next.tokenFamily()).isEqualTo("fam-" + RUN);
        assertThat(next.expiresAt()).as("the absolute cap is inherited, never extended").isEqualTo(cap);

        // The presented token is revoked and linked to its replacement.
        var old = STORE.findByHash(t.tokenHash()).orElseThrow();
        assertThat(old.revoked()).isTrue();
        assertThat(old.replacedBy()).isEqualTo(next.tokenHash());
        // The replacement is live and usable.
        assertThat(STORE.findValidByHash(RefreshToken.hash(r.newRaw()))).isPresent();
    }

    /// Rotation must not extend the family cap even when the rotator is
    /// configured with a **longer** TTL than the family was originally
    /// issued under (a deploy that raises `OIDC_REFRESH_TOKEN_TTL` from 7d to
    /// 30d must not retroactively extend refresh families that predate the
    /// change). `RefreshRotation.rotate` calls `RefreshToken.issue(..,
    /// refreshTtlSeconds)` and then overwrites the result with the stored
    /// token's own expiry — this pins that the overwrite actually happens: a
    /// mutant that drops `.withExpiresAt(stored.expiresAt())` would leak the
    /// rotator's own (here, much longer) configured TTL into the replacement.
    @Test
    void rotationNeverExtendsPastTheOriginalCapEvenWithALongerConfiguredTtl() {
        long thirtyDays = 30L * 24 * 3600; // far longer than the family's own cap below
        var longTtlRotation = new RefreshRotation(STORE, CLOCK, thirtyDays);
        Instant shortCap = NOW.plusSeconds(60); // the family's own, much nearer cap
        var issued = RefreshToken.issue(principal("longttl"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token().withFamily("famlongttl-" + RUN).withExpiresAt(shortCap));

        var r = rotated(longTtlRotation.rotate(issued.raw(), null));
        assertThat(r.replacement().expiresAt())
                .as("inherits the family's own nearer cap, never the rotator's longer configured TTL")
                .isEqualTo(shortCap);
    }

    @Test
    void aLegacyTokenWithoutAFamilyRootsOneAtItsReplacement() {
        var issued = RefreshToken.issue(principal("l"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token()); // tokenFamily null, grant_id null
        var next = rotated(ROTATION.rotate(issued.raw(), null)).replacement();
        assertThat(next.tokenFamily()).isEqualTo(next.id());
    }

    @Test
    void replayOfARotatedOutTokenRevokesTheWholeFamily() {
        var issued = RefreshToken.issue(principal("r"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token().withFamily("famr-" + RUN));
        String liveRaw = rotated(ROTATION.rotate(issued.raw(), null)).newRaw();
        assertThat(STORE.findValidByHash(RefreshToken.hash(liveRaw))).isPresent();

        assertThat(ROTATION.rotate(issued.raw(), null))
                .as("the replay names the family and how many live tokens it caught")
                .isEqualTo(Result.err(new RefreshRotation.Rejection.ReuseDetected("famr-" + RUN, 1)));
        assertThat(STORE.findValidByHash(RefreshToken.hash(liveRaw)))
                .as("the compromised family's live token is revoked too").isEmpty();
    }

    @Test
    void reuseIsLoggedAtWarnWithTheFamily() {
        var issued = RefreshToken.issue(principal("w"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token().withFamily("famw-" + RUN));
        rotated(ROTATION.rotate(issued.raw(), null));
        Logger logger = (Logger) LoggerFactory.getLogger(RefreshRotation.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            ROTATION.rotate(issued.raw(), null);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .as("mutant: reuse detection silent")
                .anySatisfy(e -> assertThat(e.getKeyValuePairs()).anySatisfy(kv -> {
                    assertThat(kv.key).isEqualTo("family");
                    assertThat(kv.value).isEqualTo("famw-" + RUN);
                }));
    }

    @Test
    void aBindingRefusalRotatesNothing() {
        var issued = RefreshToken.issue(principal("b"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token().withBinding("oac_other", List.of(), List.of(), null).withFamily("famb-" + RUN));
        assertThat(ROTATION.rotate(issued.raw(), "oac_mine"))
                .isEqualTo(Result.err(new RefreshRotation.Rejection.Refused("oac_other", "oac_mine")));
        assertThat(ROTATION.rotate(issued.raw(), null))
                .as("no client presenting a client-bound token")
                .isEqualTo(Result.err(new RefreshRotation.Rejection.Refused("oac_other", null)));
        assertThat(STORE.findValidByHash(issued.token().tokenHash())).as("still valid — nothing revoked").isPresent();
        assertThat(STORE.findByHash(issued.token().tokenHash()).orElseThrow().replacedBy()).isNull();
    }

    @Test
    void aTokenIssuedOutsideAnyClientRotatesForAnyPresenter() {
        var issued = RefreshToken.issue(principal("n"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token().withFamily("famn-" + RUN));
        assertThat(ROTATION.rotate(issued.raw(), "oac_any")).isInstanceOf(Result.Ok.class);
    }

    @Test
    void anUnknownOrExpiredTokenIsUnknownWithoutSideEffects() {
        assertThat(ROTATION.rotate("never-issued", null)).isEqualTo(Result.err(new RefreshRotation.Rejection.Unknown()));
        var issued = RefreshToken.issue(principal("x"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token().withExpiresAt(NOW.minusSeconds(1)));
        assertThat(ROTATION.rotate(issued.raw(), null)).isEqualTo(Result.err(new RefreshRotation.Rejection.Unknown()));
    }

    /// S2.5, the load-bearing one: two presentations of the same token race
    /// and exactly one rotates. The rotation's own clock is the rendezvous —
    /// it is read after the validity check and before the consume, so both
    /// threads have seen the token as valid before either consumes it (the
    /// window a find-then-revoke implementation loses). The loser then reads
    /// as a replay: it names the family, and the winner's fresh token is
    /// caught by the revocation too.
    @Test
    void twoConcurrentPresentationsRotateExactlyOnce() throws Exception {
        var issued = RefreshToken.issue(principal("c"), NOW, RefreshToken.TTL_SECONDS);
        STORE.insert(issued.token().withFamily("famc-" + RUN));
        var bothValidated = new CyclicBarrier(2);
        Clock rendezvous = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() {
                try {
                    bothValidated.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the two presentations never met", e);
                }
                return NOW;
            }
        };
        var racing = new RefreshRotation(STORE, rendezvous, RefreshToken.TTL_SECONDS);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return racing.rotate(issued.raw(), null); });
            var b = pool.submit(() -> { start.await(); return racing.rotate(issued.raw(), null); });
            start.countDown();
            var outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(o -> o instanceof Result.Ok).as("mutant: no consumed_at guard").hasSize(1);
            assertThat(outcomes).filteredOn(o -> o instanceof Result.Err)
                    .singleElement()
                    .isEqualTo(Result.err(new RefreshRotation.Rejection.ReuseDetected("famc-" + RUN, 1)));
        }
        assertThat(DB.fetchCount(OAUTH_OIDC_PAYLOADS, OAUTH_OIDC_PAYLOADS.GRANT_ID.eq("famc-" + RUN)))
                .as("one replacement row, never two").isEqualTo(2);
    }

    private static RefreshRotation.Rotated rotated(Result<RefreshRotation.Rotated, RefreshRotation.Rejection> r) {
        return switch (r) {
            case Result.Ok<RefreshRotation.Rotated, RefreshRotation.Rejection>(var v) -> v;
            case Result.Err<RefreshRotation.Rotated, RefreshRotation.Rejection>(var e) ->
                    throw new AssertionError("expected a rotation, got " + e);
        };
    }
}
