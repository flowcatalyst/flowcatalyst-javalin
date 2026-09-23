package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// `FunctionDomainRepository` against the embedded Postgres (spec
/// `function-registry.md` §6.5).
class FunctionDomainRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionDomainRepository REPO = new FunctionDomainRepository(DS);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static FunctionDomain persist(FunctionDomain d) {
        UOW.inTransaction(tx -> {
            REPO.persist(d, tx.dbTx());
            return null;
        });
        return d;
    }

    private static final FunctionOwner CLIENT_1 = FunctionOwner.ofClientId("clt_1");

    @Test
    void claimFindAndVerifyRoundTrip() {
        Hostname host = Hostname.parse("d-" + RUN + "-" + fresh() + ".acme.com");
        FunctionDomain claimed = persist(FunctionDomain.claim(CLIENT_1, host, "token-" + fresh(), Instant.now()));

        FunctionDomain reloaded = REPO.findById(claimed.id()).orElseThrow();
        assertThat(reloaded.hostname()).isEqualTo(host);
        assertThat(reloaded.verification()).isInstanceOf(FunctionDomain.Verification.Pending.class);
        assertThat(REPO.findByHostname(host)).map(FunctionDomain::id).contains(claimed.id());
        assertThat(REPO.listByOwner(CLIENT_1)).extracting(FunctionDomain::id).contains(claimed.id());

        Instant at = Instant.now().plusSeconds(5);
        FunctionDomain verified = persist(reloaded.verified(at));
        FunctionDomain reloadedVerified = REPO.findById(claimed.id()).orElseThrow();
        assertThat(reloadedVerified.verification()).isEqualTo(
                new FunctionDomain.Verification.Verified(at.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        assertThat(reloadedVerified.usableBy(CLIENT_1)).isTrue();
        assertThat(verified.usableBy(CLIENT_1)).isTrue();
    }

    @Test
    void persistOnlyEverChangesVerifiedAt() {
        Hostname host = Hostname.parse("i-" + RUN + "-" + fresh() + ".acme.com");
        FunctionDomain claimed = persist(FunctionDomain.claim(CLIENT_1, host, "original-token", Instant.now()));

        FunctionDomain tampered = new FunctionDomain(claimed.id(), FunctionOwner.ofClientId("clt_other"), host,
                "different-token", claimed.verification(), claimed.createdAt());
        persist(tampered);

        FunctionDomain reloaded = REPO.findById(claimed.id()).orElseThrow();
        assertThat(reloaded.owner()).as("SET list is verified_at only").isEqualTo(CLIENT_1);
        assertThat(reloaded.verificationToken()).isEqualTo("original-token");
    }

    @Test
    void deleteRemovesTheRow() {
        Hostname host = Hostname.parse("del-" + RUN + "-" + fresh() + ".acme.com");
        FunctionDomain claimed = persist(FunctionDomain.claim(CLIENT_1, host, "token", Instant.now()));
        UOW.inTransaction(tx -> {
            REPO.delete(claimed, tx.dbTx());
            return null;
        });
        assertThat(REPO.findById(claimed.id())).isEmpty();
    }

    // ── §8 M19/R2: a platform domain reads back Platform and lists under it only ──

    @Test
    void platformDomainRoundTripsAndListsUnderThePlatformFilterOnly() {
        Hostname host = Hostname.parse("plat-" + RUN + "-" + fresh() + ".acme.com");
        FunctionDomain claimed = persist(FunctionDomain.claim(new FunctionOwner.Platform(), host, "token-" + fresh(), Instant.now()));

        FunctionDomain reloaded = REPO.findById(claimed.id()).orElseThrow();
        assertThat(reloaded.owner()).isEqualTo(new FunctionOwner.Platform());
        assertThat(REPO.listByOwner(new FunctionOwner.Platform())).extracting(FunctionDomain::id).contains(claimed.id());
        assertThat(REPO.listByOwner(CLIENT_1)).extracting(FunctionDomain::id).doesNotContain(claimed.id());
    }

    // ── #covering (spec `function-zones-and-aliases.md` §1, Z1/Z4/Z5) ────────

    /// A zone claim covers a deeper hostname under it, and does NOT get
    /// confused by an unrelated claim with a different apex — the exact
    /// scenario `covering` exists to resolve (a sub-hostname under a zone,
    /// with a decoy claim present under a DIFFERENT apex).
    @Test
    void coveringResolvesADeeperHostnameToItsZoneClaimIgnoringAnUnrelatedClaim() {
        String apex = "acme-" + RUN + "-" + fresh() + ".com";
        Hostname zone = Hostname.parse(apex);
        FunctionDomain zoneClaim = persist(FunctionDomain.claim(CLIENT_1, zone, "token-" + fresh(), Instant.now()));
        // Decoy: an unrelated claim under a totally different apex must never be picked.
        persist(FunctionDomain.claim(CLIENT_1, Hostname.parse("myapp.other-" + RUN + "-" + fresh() + ".com"),
                "token-" + fresh(), Instant.now()));

        Hostname deep = Hostname.parse("qa-myapp." + apex);
        assertThat(REPO.covering(deep)).map(FunctionDomain::id)
                .as("mutant: pick the decoy, or fail to walk up to the zone apex")
                .contains(zoneClaim.id());
    }

    /// No claim at all covering the hostname ⇒ empty — the negative control
    /// for the test above (mutant: return SOMETHING regardless).
    @Test
    void coveringIsEmptyWhenNoClaimCoversTheHostname() {
        Hostname deep = Hostname.parse("qa-nothing." + "unclaimed-" + RUN + "-" + fresh() + ".com");
        assertThat(REPO.covering(deep)).isEmpty();
    }

    /// A claim of `myapp.acme.com` does NOT cover `xmyapp.acme.com` — a
    /// LABEL-boundary suffix, never a raw string suffix. Mutant: `endsWith`
    /// without the leading dot (which would let `xmyapp` slip through as if
    /// it were `myapp`).
    @Test
    void coveringNeverMatchesALabelThatIsMerelyAStringSuffix() {
        String suffix = "myapp-" + RUN + "-" + fresh() + ".acme.com";
        Hostname narrowClaim = Hostname.parse(suffix);
        persist(FunctionDomain.claim(CLIENT_1, narrowClaim, "token-" + fresh(), Instant.now()));

        Hostname lookalike = Hostname.parse("x" + suffix);
        assertThat(REPO.covering(lookalike)).as("mutant: string-suffix match instead of a label boundary").isEmpty();
    }

    // ── #anyUnder (spec §1's "covered by d" nesting clause) ──────────────────

    @Test
    void anyUnderIsTrueForAProperDescendantAndFalseForTheApexItselfAndAnUnrelatedHostname() {
        String apex = "under-" + RUN + "-" + fresh() + ".com";
        Hostname zone = Hostname.parse(apex);

        assertThat(REPO.anyUnder(zone)).as("nothing claimed yet").isFalse();

        // The apex itself, claimed directly (bypassing the operation's nesting rule,
        // which this repository-level test is free to do): still not "under" itself.
        persist(FunctionDomain.claim(CLIENT_1, zone, "token-" + fresh(), Instant.now()));
        assertThat(REPO.anyUnder(zone)).as("the apex claiming itself is not a descendant of itself").isFalse();

        persist(FunctionDomain.claim(CLIENT_1, Hostname.parse("api." + apex), "token-" + fresh(), Instant.now()));
        assertThat(REPO.anyUnder(zone)).as("a claim strictly under the apex").isTrue();

        String unrelatedApex = "notunder-" + RUN + "-" + fresh() + ".com";
        assertThat(REPO.anyUnder(Hostname.parse(unrelatedApex)))
                .as("mutant: match unrelated hostnames too").isFalse();
    }
}
