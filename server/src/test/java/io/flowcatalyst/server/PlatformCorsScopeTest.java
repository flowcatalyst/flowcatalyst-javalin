package io.flowcatalyst.server;

import io.flowcatalyst.platform.cors.filter.CorsAllowlist;
import io.flowcatalyst.platform.cors.filter.CorsFilter;
import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `Platform.cors` (cors spec §9.1): the filter covers the platform prefixes
/// and nothing else — a router-prefix or bare path with an `Origin` header is
/// left untouched even for an allowed origin.
class PlatformCorsScopeTest {

    private static final String ORIGIN = "https://scope.example.test";
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var allowlist = new CorsAllowlist(() -> List.of(ORIGIN), Duration.ofHours(1), Clock.systemUTC());
        http = TestHttp.routes(routes -> {
            routes.before(Platform.cors(new CorsFilter(allowlist)));
            routes.get("/api/scope-probe", ctx -> ctx.result("ok"));
            routes.get("/router/scope-probe", ctx -> ctx.result("ok"));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    @Test
    void platformPathGetsTheHeaders() {
        var r = http.get("/api/scope-probe", "Origin", ORIGIN);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
    }

    @Test
    void routerPrefixPathIsUntouched() {
        var r = http.get("/router/scope-probe", "Origin", ORIGIN);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(r.headers().firstValue("Vary")).isEmpty();
    }

    @Test
    void routerPrefixPreflightIsNotAnsweredByTheFilter() {
        var r = http.send("OPTIONS", "/router/scope-probe", null, "Origin", ORIGIN, "Access-Control-Request-Method", "GET");
        assertThat(r.statusCode()).as("Javalin's own answer, not the filter's 204").isNotEqualTo(204);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}
