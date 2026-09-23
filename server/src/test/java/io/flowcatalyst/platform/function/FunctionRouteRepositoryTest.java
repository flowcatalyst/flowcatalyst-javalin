package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FunctionRouteRepository` against the embedded Postgres (spec
/// `function-invocation.md` §3, amending `function-registry.md` §6.6, §8 M13:
/// every row is now public, so the reshaped table keeps only the
/// cross-function uniqueness half of that behaviour).
class FunctionRouteRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionRepository FUNCTION_REPO = new FunctionRepository(DS);
    private static final FunctionRouteRepository REPO = new FunctionRouteRepository(DS);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static Function createFunction() {
        Function f = Function.create(fresh(),
                FunctionAddress.of(new DnsLabel("app-" + RUN + "-" + fresh()), new DnsLabel("svc"), new DnsLabel("fn")),
                FunctionOwner.ofClientId(fresh()), Runtime.JVM, null);
        UOW.inTransaction(tx -> {
            FUNCTION_REPO.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static void replace(String functionId, List<FunctionRoute> routes) {
        UOW.inTransaction(tx -> {
            REPO.replaceForFunction(functionId, routes, tx.dbTx());
            return null;
        });
    }

    // ── round-trip ─────────────────────────────────────────────────────────

    @Test
    void replaceForFunctionListAndFindPublicRoundTrip() {
        Function f = createFunction();
        Hostname host = Hostname.parse("r-" + RUN + "-" + fresh() + ".acme.com");
        RoutePattern prefix = RoutePattern.parse("/invoices");
        FunctionRoute route = FunctionRoute.of(f.id(), host, prefix, List.of(), Instant.now());
        Hostname host2 = Hostname.parse("r2-" + RUN + "-" + fresh() + ".acme.com");
        FunctionRoute route2 = FunctionRoute.of(f.id(), host2, RoutePattern.parse("/"), List.of(), Instant.now());
        replace(f.id(), List.of(route, route2));

        assertThat(REPO.listByFunction(f.id())).hasSize(2);
        assertThat(REPO.listByHostname(host)).extracting(FunctionRoute::functionId).containsExactly(f.id());
        assertThat(REPO.findPublic(host, prefix)).map(FunctionRoute::functionId).contains(f.id());
        assertThat(REPO.listByFunctions(List.of(f.id()))).containsOnlyKeys(f.id());
        assertThat(REPO.listByFunctions(List.of(f.id())).get(f.id())).hasSize(2);

        // wholesale replace: only the new list survives
        FunctionRoute onlyOne = FunctionRoute.of(f.id(), host, RoutePattern.parse("/only-one"), List.of(), Instant.now());
        replace(f.id(), List.of(onlyOne));
        assertThat(REPO.listByFunction(f.id())).hasSize(1);
        assertThat(REPO.listByFunction(f.id()).get(0).pathPrefix()).isEqualTo(RoutePattern.parse("/only-one"));
    }

    // ── §8 M13 (amended): public uniqueness is cross-function ────────────────

    @Test
    void aSecondFunctionCannotClaimTheSamePublicRoute() {
        Function f1 = createFunction();
        Function f2 = createFunction();
        Hostname host = Hostname.parse("shared-" + RUN + "-" + fresh() + ".acme.com");
        RoutePattern prefix = RoutePattern.parse("/shared-path");

        replace(f1.id(), List.of(FunctionRoute.of(f1.id(), host, prefix, List.of(), Instant.now())));

        assertThatThrownBy(() -> replace(f2.id(), List.of(FunctionRoute.of(f2.id(), host, prefix, List.of(), Instant.now()))))
                .as("the same public (hostname, pathPrefix) on a second function is a unique violation")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void twoFunctionsMayUseDifferentPrefixesOnTheSameHostname() {
        Function f1 = createFunction();
        Function f2 = createFunction();
        Hostname host = Hostname.parse("multi-" + RUN + "-" + fresh() + ".acme.com");

        replace(f1.id(), List.of(FunctionRoute.of(f1.id(), host, RoutePattern.parse("/a"), List.of(), Instant.now())));
        replace(f2.id(), List.of(FunctionRoute.of(f2.id(), host, RoutePattern.parse("/b"), List.of(), Instant.now())));

        assertThat(REPO.listByHostname(host)).hasSize(2);
    }

    // ── #listUnder (spec `function-zones-and-aliases.md` §1's `DOMAIN_IN_USE`) ──

    /// A route on the zone apex itself, AND one on a hostname under it, both
    /// count as "covered by the zone"; a route under an unrelated apex does
    /// not. Mutant: equality instead of covering — would miss the deeper route.
    @Test
    void listUnderFindsTheApexItselfAndAnythingUnderItButNotAnUnrelatedApex() {
        Function f = createFunction();
        String apex = "zoneunder-" + RUN + "-" + fresh() + ".com";
        Hostname zone = Hostname.parse(apex);
        Hostname deep = Hostname.parse("qa-myapp." + apex);
        Hostname unrelated = Hostname.parse("other-" + RUN + "-" + fresh() + ".com");

        replace(f.id(), List.of(
                FunctionRoute.of(f.id(), zone, RoutePattern.parse("/a"), List.of(), Instant.now()),
                FunctionRoute.of(f.id(), deep, RoutePattern.parse("/b"), List.of(), Instant.now()),
                FunctionRoute.of(f.id(), unrelated, RoutePattern.parse("/c"), List.of(), Instant.now())));

        assertThat(REPO.listUnder(zone)).extracting(r -> r.hostname().value())
                .as("mutant: equality instead of covering — must find BOTH the apex route and the deeper one")
                .containsExactlyInAnyOrder(apex, deep.value());
    }

    @Test
    void listUnderNeverMatchesALabelThatIsMerelyAStringSuffix() {
        Function f = createFunction();
        String apex = "labelboundary-" + RUN + "-" + fresh() + ".com";
        Hostname zone = Hostname.parse(apex);
        Hostname lookalike = Hostname.parse("x" + apex); // NOT under `zone` — no label boundary

        replace(f.id(), List.of(FunctionRoute.of(f.id(), lookalike, RoutePattern.parse("/"), List.of(), Instant.now())));

        assertThat(REPO.listUnder(zone)).as("mutant: string-suffix match instead of a label boundary").isEmpty();
    }
}
