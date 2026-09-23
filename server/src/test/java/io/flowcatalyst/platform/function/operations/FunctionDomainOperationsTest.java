package io.flowcatalyst.platform.function.operations;

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
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainClaimed;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainReleased;
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

/// `ClaimFunctionDomain` / `ReleaseFunctionDomain` against embedded Postgres
/// (spec `function-public-routes.md` §1, §6, amended `function-domains-no-dns.md`):
/// F1 (never names the holder; a fresh claim is immediately usable).
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

    private static DomainClaimed claim(FunctionOwner owner, String hostname) {
        return Auth.runAs(ANCHOR, () -> ClaimFunctionDomain.of(domains)
                .run(uow, new ClaimCommand(owner, hostname), EC));
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

    /// N1: a fresh claim is immediately usable — the claim itself writes
    /// exactly one `domain:claimed` event, on the correct message group,
    /// with no verification state anywhere in it.
    @Test
    void claimWritesOneClaimedEvent() {
        String h = host("claim");
        DomainClaimed ev = claim(new FunctionOwner.Platform(), h);
        assertThat(ev.hostname()).isEqualTo(h);
        assertThat(ev.owner()).isEqualTo("platform");

        FunctionDomain d = domains.findByHostname(Hostname.parse(h)).orElseThrow();
        assertThat(d.hostname()).isEqualTo(Hostname.parse(h));

        var rows = eventsFor("platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_CLAIMED);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("message_group")).as("F1 mutant guard: wrong message group")
                .isEqualTo("platform:function-domain:" + ev.domainId());
    }

    /// F1: a hostname already claimed by ANYONE cannot be claimed again, and
    /// the error message never names the holder — including when the
    /// second attempt is by a DIFFERENT owner (the interesting case: there is
    /// no oracle telling a caller who holds a hostname it did not itself claim).
    @Test
    void aHostnameClaimedByOneOwnerCannotBeClaimedByAnotherAndTheErrorNeverNamesTheHolder() {
        String h = host("taken");
        String holderClientId = "clt_" + fresh();
        claim(FunctionOwner.ofClientId(holderClientId), h);

        assertUseCaseError(() -> claim(new FunctionOwner.Platform(), h), UseCaseError.Conflict.class, "DOMAIN_TAKEN");
        assertThatThrownBy(() -> claim(new FunctionOwner.Platform(), h))
                .hasMessageNotContaining(holderClientId)
                .as("mutant: name the holder");
    }

    // ── Release ──────────────────────────────────────────────────────────

    @Test
    void releaseRemovesAnUnusedDomainAndWritesOneReleasedEvent() {
        String h = host("release");
        DomainClaimed ev = claim(new FunctionOwner.Platform(), h);

        release(h);

        assertThat(domains.findByHostname(Hostname.parse(h))).isEmpty();
        assertThat(eventsFor("platform.function-domain." + ev.domainId(), FunctionEvents.DOMAIN_RELEASED)).hasSize(1);
    }

    @Test
    void releaseOfADomainStillCarryingRoutesConflictsNamingTheFunctions() {
        String h = host("inuse");
        claim(new FunctionOwner.Platform(), h).domainId();

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
