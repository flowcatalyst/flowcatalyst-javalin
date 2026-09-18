package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// [FunctionRoute] is a materialisation, not an aggregate with transitions
/// (spec `function-registry.md` §6.6) — this pins only [#of]'s shape.
class FunctionRouteTest {

    @Test
    void ofBuildsAPublicRouteWithAGeneratedId() {
        RoutePattern pattern = RoutePattern.parse("/invoices/{id}");
        Instant now = Instant.now();
        FunctionRoute r = FunctionRoute.of("fnc_1", Hostname.parse("api.acme.com"), HttpMethod.GET, pattern, now);
        assertThat(r.id()).startsWith("fnr_");
        assertThat(r.functionId()).isEqualTo("fnc_1");
        assertThat(r.hostname()).isEqualTo(Hostname.parse("api.acme.com"));
        assertThat(r.method()).isEqualTo(HttpMethod.GET);
        assertThat(r.pattern()).isEqualTo(pattern);
        assertThat(r.createdAt()).isEqualTo(now);
    }

    @Test
    void ofAllowsANullHostnameForAPrivateRoute() {
        FunctionRoute r = FunctionRoute.of("fnc_1", null, HttpMethod.POST, RoutePattern.parse("/invoices"), Instant.now());
        assertThat(r.hostname()).isNull();
    }
}
