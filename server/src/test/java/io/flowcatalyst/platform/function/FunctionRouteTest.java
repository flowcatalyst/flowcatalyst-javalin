package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [FunctionRoute] is a materialisation, not an aggregate with transitions
/// (spec `function-invocation.md` §3, amending `function-registry.md` §6.6)
/// — this pins only [#of]'s shape, including that a route is always public.
class FunctionRouteTest {

    @Test
    void ofBuildsAPublicRouteWithAGeneratedId() {
        RoutePattern prefix = RoutePattern.parse("/invoices");
        Instant now = Instant.now();
        FunctionRoute r = FunctionRoute.of("fnc_1", Hostname.parse("api.acme.com"), prefix, now);
        assertThat(r.id()).startsWith("fnr_");
        assertThat(r.functionId()).isEqualTo("fnc_1");
        assertThat(r.hostname()).isEqualTo(Hostname.parse("api.acme.com"));
        assertThat(r.pathPrefix()).isEqualTo(prefix);
        assertThat(r.createdAt()).isEqualTo(now);
    }

    @Test
    void ofRejectsANullHostname() {
        assertThatThrownBy(() -> FunctionRoute.of("fnc_1", null, RoutePattern.parse("/invoices"), Instant.now()))
                .as("every route row is public — a private call needs no fn_routes row at all (spec §2)")
                .isInstanceOf(NullPointerException.class);
    }
}
