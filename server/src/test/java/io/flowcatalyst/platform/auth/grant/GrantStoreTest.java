package io.flowcatalyst.platform.auth.grant;

import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_PAYLOADS;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/// auth-core §3.7 / §8.1 / §11 on the embedded Postgres: the storage
/// shapes a Go instance shares, the atomic consume, and the family
/// revocations. Rows carry a run marker in their ids and are deleted in
/// [#cleanup].
class GrantStoreTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    // Whole seconds: the payload stores epoch seconds, so sub-second precision does not round-trip.
    private static final Instant NOW = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final GrantStore STORE = new GrantStore(DS, Clock.fixed(NOW, ZoneOffset.UTC));

    /// Postgres normalises JSONB text (spacing, key order), so the payload is compared as JSON, never as a string.
    private static JsonNode payload(String id) {
        return Json.MAPPER.readTree(DB.select(OAUTH_OIDC_PAYLOADS.PAYLOAD).from(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.ID.eq(id)).fetchOne(OAUTH_OIDC_PAYLOADS.PAYLOAD).data());
    }

    private static String principal(String suffix) {
        return "prn_" + RUN + suffix;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(OAUTH_OIDC_PAYLOADS)
                .where(DSL.field("payload ->> 'accountId'", String.class).like("prn_" + RUN + "%"))
                .or(OAUTH_OIDC_PAYLOADS.ID.like("PendingAuth:" + RUN + "%"))
                .execute();
    }

    // ── authorization codes ────────────────────────────────────────────────

    @Test
    void anAuthorizationCodeRoundTripsInTheSharedStorageShape() {
        Instant authTime = NOW.minusSeconds(120);
        var c = AuthorizationCode.issue("oac_rp", principal("a"), "https://app/cb", NOW)
                .withScope("openid offline_access").withPkce("chal", "S256").withNonce("n").withState("s")
                .withContextClientId("clt_1").withAuthTime(authTime);
        STORE.insert(c);

        var row = DB.selectFrom(OAUTH_OIDC_PAYLOADS).where(OAUTH_OIDC_PAYLOADS.ID.eq("AuthorizationCode:" + c.code())).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getType()).isEqualTo("AuthorizationCode");
        assertThat(row.getGrantId()).isNull();
        JsonNode payload = payload("AuthorizationCode:" + c.code());
        assertThat(payload.get("accountId").asString()).isEqualTo(principal("a"));
        assertThat(payload.get("kind").asString()).isEqualTo("AuthorizationCode");
        assertThat(payload.get("codeChallengeMethod").asString()).isEqualTo("S256");
        assertThat(payload.get("authTime").asLong()).isEqualTo(authTime.getEpochSecond());
        assertThat(payload.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(row.getExpiresAt().toInstant()).isEqualTo(NOW.plusSeconds(600));

        var read = STORE.findCode(c.code()).orElseThrow();
        assertThat(read.used()).isFalse();
        assertThat(read.authTime()).isEqualTo(authTime);
        assertThat(read.scope()).isEqualTo("openid offline_access");
        assertThat(read.contextClientId()).isEqualTo("clt_1");
    }

    @Test
    void nullableFieldsAreWrittenAsJsonNullNeverOmitted() {
        var c = AuthorizationCode.issue("oac_rp", principal("n"), "https://app/cb", NOW);
        STORE.insert(c);
        JsonNode payload = payload("AuthorizationCode:" + c.code());
        for (String key : List.of("scope", "codeChallenge", "codeChallengeMethod", "nonce", "state", "contextClientId")) {
            assertThat(payload.has(key)).as(key + " is present").isTrue();
            assertThat(payload.get(key).isNull()).as(key + " is an explicit null").isTrue();
        }
        assertThat(payload.has("authTime")).as("authTime is omitted when unknown, never 0").isFalse();
        assertThat(STORE.findCode(c.code()).orElseThrow().authTime()).isNull();
    }

    @Test
    void aCodeIsConsumedExactlyOnceAndNeverWhenExpired() {
        var c = AuthorizationCode.issue("oac_rp", principal("c"), "https://app/cb", NOW);
        STORE.insert(c);
        assertThat(STORE.findAndConsume(c.code())).isPresent();
        assertThat(STORE.findAndConsume(c.code())).as("second redemption").isEmpty();
        assertThat(STORE.findCode(c.code()).orElseThrow().used()).isTrue();

        var expired = AuthorizationCode.issue("oac_rp", principal("e"), "https://app/cb", NOW.minusSeconds(700));
        STORE.insert(expired);
        assertThat(STORE.findAndConsume(expired.code())).isEmpty();
        assertThat(STORE.findAndConsume("no-such-code")).isEmpty();
    }

    /// Two redeemers racing for one code: redeemer A consumes inside a
    /// transaction it holds open; B's consume blocks on the row lock and,
    /// once A commits, sees the code already consumed — exactly one wins.
    @Test
    void twoConcurrentRedeemersExactlyOneWins() throws Exception {
        var c = AuthorizationCode.issue("oac_rp", principal("r"), "https://app/cb", NOW);
        STORE.insert(c);

        var aConsumed = new AtomicReference<Boolean>();
        var bConsumed = new AtomicReference<Boolean>();
        var aHolding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread a = Thread.ofPlatform().start(() -> {
            try (Connection conn = DS.getConnection()) {
                conn.setAutoCommit(false);
                var inTx = new GrantStore(conn, Clock.fixed(NOW, ZoneOffset.UTC));
                aConsumed.set(inTx.findAndConsume(c.code()).isPresent());
                aHolding.countDown();
                release.await(10, TimeUnit.SECONDS);
                conn.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(aHolding.await(10, TimeUnit.SECONDS)).isTrue();
        Thread b = Thread.ofPlatform().start(() -> bConsumed.set(STORE.findAndConsume(c.code()).isPresent()));
        Thread.sleep(300); // B is blocked on A's row lock; give it a chance to prove it
        assertThat(bConsumed.get()).as("B cannot decide while A holds the row").isNull();
        release.countDown();
        a.join(10_000);
        b.join(10_000);
        assertThat(aConsumed.get()).isTrue();
        assertThat(bConsumed.get()).as("after A commits, B sees consumed_at set and loses").isFalse();
    }

    // ── refresh tokens ─────────────────────────────────────────────────────

    @Test
    void aRefreshTokenRoundTripsWithItsFamilyMirroredIntoGrantId() {
        var issued = RefreshToken.issue(principal("t"), NOW, RefreshToken.TTL_SECONDS);
        var t = issued.token().withBinding("oac_rp", List.of("openid", "offline_access"), List.of("clt_1:acme"), NOW.minusSeconds(60))
                .withFamily("fam-" + RUN).withOrigin("10.0.0.1", "ua");
        STORE.insert(t);

        var row = DB.selectFrom(OAUTH_OIDC_PAYLOADS).where(OAUTH_OIDC_PAYLOADS.ID.eq("RefreshToken:" + t.id())).fetchOne();
        assertThat(row.getGrantId()).isEqualTo("fam-" + RUN);
        JsonNode payload = payload("RefreshToken:" + t.id());
        assertThat(payload.get("tokenHash").asString()).isEqualTo(t.tokenHash());
        assertThat(payload.get("scope").asString()).isEqualTo("openid offline_access");
        assertThat(payload.get("accessibleClients").size()).isEqualTo(1);
        assertThat(payload.get("accessibleClients").get(0).asString()).isEqualTo("clt_1:acme");
        assertThat(payload.get("revoked").asBoolean()).isFalse();
        assertThat(payload.get("kind").asString()).isEqualTo("RefreshToken");
        assertThat(row.getExpiresAt().toInstant()).isEqualTo(NOW.plusSeconds(7 * 24 * 3600));

        var read = STORE.findValidByHash(t.tokenHash()).orElseThrow();
        assertThat(read.scopes()).containsExactly("openid", "offline_access");
        assertThat(read.accessibleClients()).containsExactly("clt_1:acme");
        assertThat(read.oauthClientId()).isEqualTo("oac_rp");
        assertThat(read.authTime()).isEqualTo(NOW.minusSeconds(60));
        assertThat(read.createdFromIp()).isEqualTo("10.0.0.1");
        assertThat(STORE.findByHash("nope")).isEmpty();
    }

    @Test
    void emptyAccessibleClientsIsAnArrayNeverNull() {
        var t = RefreshToken.issue(principal("ac"), NOW, RefreshToken.TTL_SECONDS).token();
        STORE.insert(t);
        JsonNode payload = payload("RefreshToken:" + t.id());
        assertThat(payload.get("accessibleClients").isArray()).isTrue();
        assertThat(payload.get("accessibleClients").size()).isZero();
        assertThat(payload.get("clientId").isNull()).isTrue();
        assertThat(payload.get("tokenFamily").isNull()).isTrue();
    }

    @Test
    void revokeByHashSetsRevokedRevokedAtAndConsumedAtSoValidLookupsMiss() {
        var t = RefreshToken.issue(principal("rv"), NOW, RefreshToken.TTL_SECONDS).token();
        STORE.insert(t);
        assertThat(STORE.revokeByHash(t.tokenHash())).isTrue();
        assertThat(STORE.findValidByHash(t.tokenHash())).isEmpty();
        var read = STORE.findByHash(t.tokenHash()).orElseThrow();
        assertThat(read.revoked()).isTrue();
        assertThat(read.revokedAt()).isEqualTo(NOW);
        assertThat(DB.select(OAUTH_OIDC_PAYLOADS.CONSUMED_AT).from(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.ID.eq("RefreshToken:" + t.id())).fetchOne(OAUTH_OIDC_PAYLOADS.CONSUMED_AT)).isNotNull();
        assertThat(STORE.revokeByHash("nope")).isFalse();
    }

    @Test
    void familyAndPrincipalRevocationsHitOnlyTheActiveTokens() {
        String fam = "fam2-" + RUN;
        var a = RefreshToken.issue(principal("f"), NOW, RefreshToken.TTL_SECONDS).token().withFamily(fam);
        var b = RefreshToken.issue(principal("f"), NOW, RefreshToken.TTL_SECONDS).token().withFamily(fam);
        var other = RefreshToken.issue(principal("f"), NOW, RefreshToken.TTL_SECONDS).token().withFamily("fam3-" + RUN);
        STORE.insert(a);
        STORE.insert(b);
        STORE.insert(other);
        STORE.revokeByHash(a.tokenHash());

        assertThat(STORE.revokeAllInFamily(fam)).as("a is already revoked; only b").isEqualTo(1);
        assertThat(STORE.findValidByHash(b.tokenHash())).isEmpty();
        assertThat(STORE.findValidByHash(other.tokenHash())).as("another family is untouched").isPresent();

        assertThat(STORE.revokeAllForPrincipal(principal("f"))).as("only the remaining active one").isEqualTo(1);
        assertThat(STORE.findValidByHash(other.tokenHash())).isEmpty();
    }

    @Test
    void markReplacedRecordsTheSuccessorHash() {
        var t = RefreshToken.issue(principal("mr"), NOW, RefreshToken.TTL_SECONDS).token();
        STORE.insert(t);
        assertThat(STORE.markReplaced(t.tokenHash(), "next-hash")).isTrue();
        assertThat(STORE.findByHash(t.tokenHash()).orElseThrow().replacedBy()).isEqualTo("next-hash");
    }

    /// A row from before `expires_at` was persisted (`expires_at IS NULL`):
    /// hydration must reconstruct what the row's expiry *was* — the historical
    /// `RefreshToken.TTL_SECONDS` (7d) — never today's configured
    /// `Env.refreshTokenTtlSeconds()`, or a deploy-time TTL change would
    /// retroactively extend an already-issued legacy token. Written as a raw
    /// insert (never through [GrantStore#insert(RefreshToken)], which always
    /// stamps `expires_at`) so this is a genuinely null column, not a token
    /// that merely looks old.
    @Test
    void aLegacyRowWithNullExpiresAtHydratesToCreatedPlusSevenDaysRegardlessOfConfiguredTtl() {
        String id = "legacy-" + RUN;
        String hash = RefreshToken.hash("raw-" + id);
        var payload = Json.MAPPER.createObjectNode();
        payload.put("accountId", principal("legacy"));
        payload.put("tokenHash", hash);
        payload.put("scope", "");
        payload.putArray("accessibleClients");
        payload.put("revoked", false);
        payload.put("kind", "RefreshToken");
        payload.put("iat", NOW.getEpochSecond());
        DB.insertInto(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.ID, "RefreshToken:" + id)
                .set(OAUTH_OIDC_PAYLOADS.TYPE, "RefreshToken")
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, JSONB.jsonb(Json.write(payload)))
                .set(OAUTH_OIDC_PAYLOADS.CREATED_AT, NOW.atOffset(ZoneOffset.UTC))
                // expires_at intentionally left unset -> NULL, simulating a pre-column row.
                .execute();

        var read = STORE.findByHash(hash).orElseThrow();
        assertThat(read.expiresAt())
                .as("reconstructs the 7d cap this row was written under, never a configured TTL")
                .isEqualTo(NOW.plusSeconds(7 * 24 * 3600));
    }

    // ── pending auth ───────────────────────────────────────────────────────

    @Test
    void pendingAuthIsWrittenForCompatibilityAndConsumedOnce() {
        String state = RUN + "-state";
        STORE.insertPendingAuth(state, new GrantStore.PendingAuth("oac_rp", "https://app/cb", "openid", "chal", "S256", "n", NOW));
        var row = DB.selectFrom(OAUTH_OIDC_PAYLOADS).where(OAUTH_OIDC_PAYLOADS.ID.eq("PendingAuth:" + state)).fetchOne();
        assertThat(row.getType()).isEqualTo("PendingAuth");
        assertThat(row.getExpiresAt().toInstant()).isEqualTo(NOW.plusSeconds(600));
        var pa = STORE.consumePendingAuth(state).orElseThrow();
        assertThat(pa.clientId()).isEqualTo("oac_rp");
        assertThat(pa.createdAt()).isEqualTo(NOW);
        assertThat(STORE.consumePendingAuth(state)).as("single use").isEmpty();
    }

    // ── housekeeping ───────────────────────────────────────────────────────

    @Test
    void deleteExpiredRemovesOnlyExpiredRows() {
        var live = AuthorizationCode.issue("oac_rp", principal("de"), "https://app/cb", NOW);
        var dead = AuthorizationCode.issue("oac_rp", principal("de"), "https://app/cb", NOW.minusSeconds(3600));
        STORE.insert(live);
        STORE.insert(dead);
        assertThat(STORE.deleteExpired()).isGreaterThanOrEqualTo(1);
        assertThat(STORE.findCode(dead.code())).isEmpty();
        assertThat(STORE.findCode(live.code())).isPresent();
    }
}
