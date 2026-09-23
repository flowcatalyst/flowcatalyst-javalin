package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.DnsException;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.TxtResolver;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainClaimed;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainReleased;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainVerified;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `ClaimFunctionDomain` / `VerifyFunctionDomain` / `ReleaseFunctionDomain`
/// against embedded Postgres (spec `function-public-routes.md` §1, §6): F1
/// (never names the holder; exact-match verify; resolver failure is 503-
/// shaped, never "not verified") and F3 (dev-mode `.localhost` auto-verify,
/// label-exact, never outside dev mode).
@SuppressWarnings("deprecation")
class FunctionDomainOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final FunctionDomainRepository domains = new FunctionDomainRepository(DS);
    private static final FunctionRouteRepository routes = new FunctionRouteRepository(DS);
    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "usr_fdo_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final AuthContext ANCHOR =
            new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io", List.of("*"), List.of(), List.of(), true, List.of());
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static String host(String tag) {
        return "fdo-" + tag + "-" + fresh() + ".example.com";
    }

    /// A resolver that always answers `values` for any name — the test's own
    /// seam (spec §1 deliverable 1).
    private static TxtResolver fake(List<String> values) {
        return name -> values;
    }

    /// A resolver that always fails — pins §6 M1's "resolver failure is 503,
    /// not 'not verified'".
    private static TxtResolver failing() {
        return name -> {
            throw new DnsException("simulated resolver failure");
        };
    }

    private static DomainClaimed claim(FunctionOwner owner, String hostname, boolean devMode) {
        return Auth.runAs(ANCHOR, () -> ClaimFunctionDomain.of(domains, devMode)
                .run(uow, new ClaimCommand(owner, hostname), EC));
    }

    private static VerifyFunctionDomain.Result verify(String hostname, TxtResolver resolver) {
        return Auth.runAs(ANCHOR, () -> VerifyFunctionDomain.of(domains, resolver)
                .run(uow, new VerifyCommand(hostname), EC));
    }

    private static DomainReleased release(String hostname) {
        return Auth.runAs(ANCHOR, () -> ReleaseFunctionDomain.of(domains, routes, functions)
                .run(uow, new ReleaseCommand(hostname), EC));
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static Result<Record> eventsFor(String subject, String type) {
        return DB.fetch("SELECT type, message_group FROM msg_events WHERE subject = ? AND type = ?", subject, type);
    }

    // ── Claim ────────────────────────────────────────────────────────────

    @Test
    void claimStartsPendingAndWritesOneClaimedEventWithNoToken() {
        String h = host("claim");
        DomainClaimed ev = claim(new FunctionOwner.Platform(), h, false);
        assertThat(ev.hostname()).isEqualTo(h);
        assertThat(ev.owner()).isEqualTo("platform");

        FunctionDomain d = domains.findByHostname(Hostname.parse(h)).orElseThrow();
        assertThat(d.verification()).isInstanceOf(FunctionDomain.Verification.Pending.class);
        assertThat(d.verificationToken()).isNotBlank();

        var rows = eventsFor("platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_CLAIMED);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("message_group")).as("F1 mutant guard: wrong message group")
                .isEqualTo("platform:function-domain:" + ev.domainId());
        // spec §1: "no token" — the event's own JSON must never carry it.
        var data = DB.fetch("SELECT data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                "platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_CLAIMED);
        assertThat(data.getFirst().get("data", String.class)).doesNotContain(d.verificationToken());
    }

    /// F1: a hostname already claimed by ANYONE cannot be claimed again, and
    /// the error message never names the holder — including when the
    /// second attempt is by a DIFFERENT owner (the interesting case: there is
    /// no oracle telling a caller who holds a hostname it did not itself claim).
    @Test
    void aHostnameClaimedByOneOwnerCannotBeClaimedByAnotherAndTheErrorNeverNamesTheHolder() {
        String h = host("taken");
        String holderClientId = "clt_" + fresh();
        claim(FunctionOwner.ofClientId(holderClientId), h, false);

        assertUseCaseError(() -> claim(new FunctionOwner.Platform(), h, false), UseCaseError.Conflict.class, "DOMAIN_TAKEN");
        assertThatThrownBy(() -> claim(new FunctionOwner.Platform(), h, false))
                .hasMessageNotContaining(holderClientId)
                .as("mutant: name the holder");
    }

    // ── Verify: exact match only (F1, §6 M1) ────────────────────────────────

    @Test
    void verifySucceedsOnlyOnAnExactTokenMatch() {
        String h = host("exact");
        DomainClaimed ev = claim(new FunctionOwner.Platform(), h, false);
        FunctionDomain claimed = domains.findByHostname(Hostname.parse(h)).orElseThrow();
        String expected = "fc-verify=" + claimed.verificationToken();

        VerifyFunctionDomain.Result result = verify(h, fake(List.of(expected)));
        assertThat(result.changed()).isTrue();
        assertThat(result.domain().verification()).isInstanceOf(FunctionDomain.Verification.Verified.class);

        var rows = eventsFor("platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_VERIFIED);
        assertThat(rows).hasSize(1);
    }

    @Test
    void verifyRejectsAValuePrefixedByTheToken() {
        String h = host("prefix");
        claim(new FunctionOwner.Platform(), h, false);
        FunctionDomain claimed = domains.findByHostname(Hostname.parse(h)).orElseThrow();
        String prefixed = "fc-verify=" + claimed.verificationToken() + "-extra";

        assertUseCaseError(() -> verify(h, fake(List.of(prefixed))), UseCaseError.Conflict.class, "DOMAIN_NOT_VERIFIED");
    }

    @Test
    void verifyRejectsAValueWithAnExtraPrefix() {
        String h = host("suffix");
        claim(new FunctionOwner.Platform(), h, false);
        FunctionDomain claimed = domains.findByHostname(Hostname.parse(h)).orElseThrow();
        String suffixed = "extra-fc-verify=" + claimed.verificationToken();

        assertUseCaseError(() -> verify(h, fake(List.of(suffixed))), UseCaseError.Conflict.class, "DOMAIN_NOT_VERIFIED");
    }

    @Test
    void verifyRejectsAnUnrelatedRecordValue() {
        String h = host("other");
        claim(new FunctionOwner.Platform(), h, false);

        assertUseCaseError(() -> verify(h, fake(List.of("v=spf1 include:example.com ~all"))),
                UseCaseError.Conflict.class, "DOMAIN_NOT_VERIFIED");
    }

    @Test
    void verifyRejectsACaseChangedToken() {
        String h = host("case");
        claim(new FunctionOwner.Platform(), h, false);
        FunctionDomain claimed = domains.findByHostname(Hostname.parse(h)).orElseThrow();
        String changedCase = ("fc-verify=" + claimed.verificationToken()).toUpperCase(Locale.ROOT);

        assertUseCaseError(() -> verify(h, fake(List.of(changedCase))), UseCaseError.Conflict.class, "DOMAIN_NOT_VERIFIED");
    }

    /// F1, §6 M1: a resolver failure is 503-shaped ([DnsUnavailableException]),
    /// never silently treated as "no matching TXT value found".
    @Test
    void verifyResolverFailureIsDnsUnavailableNeverTreatedAsUnverified() {
        String h = host("dnsfail");
        claim(new FunctionOwner.Platform(), h, false);

        assertThatThrownBy(() -> verify(h, failing()))
                .as("mutant: treat failure as unverified")
                .isInstanceOf(DnsUnavailableException.class)
                .isNotInstanceOf(UseCaseException.class);
    }

    @Test
    void verifyingAnAlreadyVerifiedDomainIs200WithNoNewEvent() {
        String h = host("already");
        DomainClaimed ev = claim(new FunctionOwner.Platform(), h, false);
        FunctionDomain claimed = domains.findByHostname(Hostname.parse(h)).orElseThrow();
        String expected = "fc-verify=" + claimed.verificationToken();
        verify(h, fake(List.of(expected)));
        assertThat(eventsFor("platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_VERIFIED)).hasSize(1);

        VerifyFunctionDomain.Result second = verify(h, fake(List.of(expected)));
        assertThat(second.changed()).as("mutant: re-verify unconditionally").isFalse();
        assertThat(eventsFor("platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_VERIFIED))
                .as("no second event").hasSize(1);
    }

    @Test
    void verifyOfAnUnknownHostnameIs404() {
        assertUseCaseError(() -> verify(host("missing"), fake(List.of())), UseCaseError.NotFound.class,
                "FunctionDomain_NOT_FOUND");
    }

    // ── F3: dev-mode `.localhost` auto-verify at claim (§6 M3) ──────────────

    @Test
    void devModeAutoVerifiesAnXDotLocalhostHostnameAtClaim() {
        DomainClaimed ev = claim(new FunctionOwner.Platform(), fresh() + ".localhost", true);
        FunctionDomain d = domains.findById(ev.domainId()).orElseThrow();
        assertThat(d.verification()).as("verified with no DNS call").isInstanceOf(FunctionDomain.Verification.Verified.class);
    }

    /// §6 M3: `evil.<x>.localhost.example.com`'s LAST label is `com`, not
    /// `localhost` — never auto-verified, in OR out of dev mode.
    @Test
    void devModeDoesNotAutoVerifyAHostnameThatMerelyContainsTheLocalhostLabel() {
        DomainClaimed ev = claim(new FunctionOwner.Platform(), "evil." + fresh() + ".localhost.example.com", true);
        FunctionDomain d = domains.findById(ev.domainId()).orElseThrow();
        assertThat(d.verification()).as("mutant: suffix match without the dot").isInstanceOf(FunctionDomain.Verification.Pending.class);
    }

    /// §6 M3, the mutant table's own wording: "suffix match WITHOUT THE DOT".
    /// `x.fakelocalhost`'s raw characters end with the substring
    /// `"localhost"` (a naive `String#endsWith("localhost")` would match it),
    /// but its LAST LABEL is `fakelocalhost`, not `localhost` — must stay
    /// PENDING. This is the one case `evil...com` above cannot distinguish
    /// (that hostname's raw characters do not even end with "localhost").
    @Test
    void devModeDoesNotAutoVerifyAHostnameWhoseLastLabelMerelyEndsWithLocalhost() {
        DomainClaimed ev = claim(new FunctionOwner.Platform(), fresh() + ".fakelocalhost", true);
        FunctionDomain d = domains.findById(ev.domainId()).orElseThrow();
        assertThat(d.verification()).as("mutant: suffix match without the dot").isInstanceOf(FunctionDomain.Verification.Pending.class);
    }

    /// §6 M3: the SAME hostname that auto-verifies in dev mode stays PENDING
    /// outside it.
    @Test
    void nonDevModeNeverAutoVerifiesAnXDotLocalhostHostname() {
        DomainClaimed ev = claim(new FunctionOwner.Platform(), fresh() + ".localhost", false);
        FunctionDomain d = domains.findById(ev.domainId()).orElseThrow();
        assertThat(d.verification()).as("mutant: ignore dev mode").isInstanceOf(FunctionDomain.Verification.Pending.class);
    }

    // ── Release ──────────────────────────────────────────────────────────

    @Test
    void releaseRemovesAnUnusedDomainAndWritesOneReleasedEvent() {
        String h = host("release");
        DomainClaimed ev = claim(new FunctionOwner.Platform(), h, false);

        release(h);

        assertThat(domains.findByHostname(Hostname.parse(h))).isEmpty();
        assertThat(eventsFor("platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_RELEASED)).hasSize(1);
    }

    @Test
    void releaseOfADomainStillCarryingRoutesConflictsNamingTheFunctions() {
        String h = host("inuse");
        claim(new FunctionOwner.Platform(), h, false).domainId();

        String appId = persistApplication("relinuse");
        FunctionAddress address =
                FunctionAddress.of(new DnsLabel("fdo" + RUN), new DnsLabel("svc"), new DnsLabel(fresh()));
        Function f = Function.create(appId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        FunctionRoute route = FunctionRoute.of(f.id(), Hostname.parse(h),
                io.flowcatalyst.platform.function.RoutePattern.parse("/"), List.of(), Instant.now());
        uow.inTransaction(tx -> {
            routes.replaceForFunction(f.id(), List.of(route), tx.dbTx());
            return null;
        });

        assertUseCaseError(() -> release(h), UseCaseError.Conflict.class, "DOMAIN_IN_USE");
        assertThatThrownBy(() -> release(h)).hasMessageContaining(f.address().render());
        assertThat(domains.findByHostname(Hostname.parse(h))).as("refused release leaves the row").isPresent();
    }

    private static String persistApplication(String tag) {
        io.flowcatalyst.platform.application.Application app = io.flowcatalyst.platform.application.Application.create(
                io.flowcatalyst.platform.application.ApplicationType.APPLICATION, "fdo" + tag + RUN, "fdo app " + tag);
        var applications = new io.flowcatalyst.platform.application.ApplicationRepository(DS);
        uow.inTransaction(tx -> {
            applications.persist(app, tx.dbTx());
            return null;
        });
        return app.id();
    }
}
