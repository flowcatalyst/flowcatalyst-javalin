package io.flowcatalyst.platform.auth.token;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// auth-core §7.3.3, bounded at every tier (`304338a`).
class ScopeNarrowingTest {

    private static final List<String> CEILING = List.of("orders:order:order:read", "orders:order:order:write", "hr:*:*:*");

    @Test
    void noPermissionRequestedMintsTheFullCeilingAndIsNotExplicit() {
        var g = ScopeNarrowing.grant(CEILING, "openid profile offline_access");
        assertThat(g.permissions()).containsExactlyElementsOf(CEILING);
        assertThat(g.explicit()).isFalse();
        assertThat(ScopeNarrowing.grant(CEILING, null).explicit()).isFalse();
        assertThat(ScopeNarrowing.grant(CEILING, "   ").permissions()).containsExactlyElementsOf(CEILING);
    }

    @Test
    void requestedPermissionsAreKeptOnlyWhereTheCeilingGrantsThem() {
        var g = ScopeNarrowing.grant(CEILING, "openid orders:order:order:read billing:invoice:invoice:read hr:leave:request:approve");
        assertThat(g.permissions())
                .as("exact grant kept, ungranted dropped, wildcard-granted kept, request order preserved")
                .containsExactly("orders:order:order:read", "hr:leave:request:approve");
        assertThat(g.explicit()).isTrue();
        assertThat(g.claim()).isEqualTo("orders:order:order:read hr:leave:request:approve");
    }

    @Test
    void anExplicitRequestTheCeilingGrantsNothingOfIsExplicitAndEmpty() {
        // client_credentials answers invalid_scope to this; it must never
        // degrade to the full ceiling.
        var g = ScopeNarrowing.grant(CEILING, "billing:invoice:invoice:read");
        assertThat(g.permissions()).isEmpty();
        assertThat(g.explicit()).isTrue();
        assertThat(g.claim()).isNull();
    }

    @Test
    void anEmptyCeilingAdvertisesNothingEvenWhenNothingIsRequested() {
        var g = ScopeNarrowing.grant(List.of(), "openid");
        assertThat(g.permissions()).isEmpty();
        assertThat(g.explicit()).isFalse();
        assertThat(g.claim()).isNull();
    }
}
