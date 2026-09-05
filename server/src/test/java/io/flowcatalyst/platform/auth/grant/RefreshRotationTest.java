package io.flowcatalyst.platform.auth.grant;

import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_PAYLOADS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final RefreshRotation ROTATION = new RefreshRotation(STORE, CLOCK);

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
        var issued = RefreshToken.issue(principal("a"), NOW);
        var t = issued.token().withBinding("oac_rp", List.of("openid", "offline_access"), List.of("clt_1"), authTime)
                .withFamily("fam-" + RUN).withExpiresAt(cap);
        STORE.insert(t);

        var r = ROTATION.rotate(issued.raw(), null);
        assertThat(r.rotated()).isTrue();
        var next = r.replacement().orElseThrow();
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
        assertThat(STORE.findValidByHash(RefreshToken.hash(r.newRaw().orElseThrow()))).isPresent();
    }

    @Test
    void aLegacyTokenWithoutAFamilyRootsOneAtItsReplacement() {
        var issued = RefreshToken.issue(principal("l"), NOW);
        STORE.insert(issued.token()); // tokenFamily null, grant_id null
        var r = ROTATION.rotate(issued.raw(), null);
        var next = r.replacement().orElseThrow();
        assertThat(next.tokenFamily()).isEqualTo(next.id());
    }

    @Test
    void replayOfARotatedOutTokenRevokesTheWholeFamily() {
        var issued = RefreshToken.issue(principal("r"), NOW);
        STORE.insert(issued.token().withFamily("famr-" + RUN));
        var first = ROTATION.rotate(issued.raw(), null);
        String liveRaw = first.newRaw().orElseThrow();
        assertThat(STORE.findValidByHash(RefreshToken.hash(liveRaw))).isPresent();

        var replay = ROTATION.rotate(issued.raw(), null);
        assertThat(replay.rotated()).isFalse();
        assertThat(replay.stored()).isEmpty();
        assertThat(STORE.findValidByHash(RefreshToken.hash(liveRaw)))
                .as("the compromised family's live token is revoked too").isEmpty();
    }

    @Test
    void aBindingRefusalRotatesNothing() {
        var issued = RefreshToken.issue(principal("b"), NOW);
        STORE.insert(issued.token().withBinding("oac_other", List.of(), List.of(), null).withFamily("famb-" + RUN));
        assertThatThrownBy(() -> ROTATION.rotate(issued.raw(), stored -> "Token was not issued to this client"))
                .isInstanceOf(RefreshRotation.NotAuthorized.class)
                .hasMessage("Token was not issued to this client");
        assertThat(STORE.findValidByHash(issued.token().tokenHash())).as("still valid — nothing revoked").isPresent();
        assertThat(STORE.findByHash(issued.token().tokenHash()).orElseThrow().replacedBy()).isNull();
    }

    @Test
    void anUnknownOrExpiredTokenIsInvalidWithoutSideEffects() {
        assertThat(ROTATION.rotate("never-issued", null)).isEqualTo(RefreshRotation.Result.INVALID);
        var issued = RefreshToken.issue(principal("x"), NOW);
        STORE.insert(issued.token().withExpiresAt(NOW.minusSeconds(1)));
        assertThat(ROTATION.rotate(issued.raw(), null).rotated()).isFalse();
    }
}
