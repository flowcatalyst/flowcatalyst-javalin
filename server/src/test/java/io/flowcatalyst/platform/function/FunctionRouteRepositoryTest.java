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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FunctionRouteRepository` against the embedded Postgres (spec
/// `function-registry.md` §6.6, §8 M13).
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
                fresh(), Runtime.JVM, null);
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
        RoutePattern pattern = RoutePattern.parse("/invoices/{id}");
        FunctionRoute publicRoute = FunctionRoute.of(f.id(), host, HttpMethod.GET, pattern, Instant.now());
        FunctionRoute privateRoute = FunctionRoute.of(f.id(), null, HttpMethod.POST,
                RoutePattern.parse("/invoices"), Instant.now());
        replace(f.id(), List.of(publicRoute, privateRoute));

        assertThat(REPO.listByFunction(f.id())).hasSize(2);
        assertThat(REPO.listByHostname(host)).extracting(FunctionRoute::functionId).containsExactly(f.id());
        assertThat(REPO.findPublic(host, HttpMethod.GET, pattern)).map(FunctionRoute::functionId).contains(f.id());
        assertThat(REPO.listByFunctions(List.of(f.id()))).containsOnlyKeys(f.id());
        assertThat(REPO.listByFunctions(List.of(f.id())).get(f.id())).hasSize(2);

        // wholesale replace: only the new list survives
        FunctionRoute onlyOne = FunctionRoute.of(f.id(), null, HttpMethod.DELETE, RoutePattern.parse("/invoices/{id}"), Instant.now());
        replace(f.id(), List.of(onlyOne));
        assertThat(REPO.listByFunction(f.id())).hasSize(1);
        assertThat(REPO.listByFunction(f.id()).get(0).method()).isEqualTo(HttpMethod.DELETE);
    }

    // ── §8 M13: public uniqueness is cross-function, private is per-function ──

    @Test
    void aSecondFunctionCannotClaimTheSamePublicRoute() {
        Function f1 = createFunction();
        Function f2 = createFunction();
        Hostname host = Hostname.parse("shared-" + RUN + "-" + fresh() + ".acme.com");
        RoutePattern pattern = RoutePattern.parse("/shared-path");

        replace(f1.id(), List.of(FunctionRoute.of(f1.id(), host, HttpMethod.GET, pattern, Instant.now())));

        assertThatThrownBy(() -> replace(f2.id(), List.of(FunctionRoute.of(f2.id(), host, HttpMethod.GET, pattern, Instant.now()))))
                .as("the same public (hostname, method, path) on a second function is a unique violation")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void twoFunctionsMayEachRegisterTheSamePrivateRoute() {
        Function f1 = createFunction();
        Function f2 = createFunction();
        RoutePattern pattern = RoutePattern.parse("/shared-private-" + fresh());

        assertThatCode(() -> replace(f1.id(), List.of(FunctionRoute.of(f1.id(), null, HttpMethod.GET, pattern, Instant.now()))))
                .doesNotThrowAnyException();
        assertThatCode(() -> replace(f2.id(), List.of(FunctionRoute.of(f2.id(), null, HttpMethod.GET, pattern, Instant.now()))))
                .as("private routes are scoped per function, not global")
                .doesNotThrowAnyException();
    }
}
