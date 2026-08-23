package io.flowcatalyst.platform.shared.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextTest {

    private static AuthContext ctx(Scope scope, List<String> clients) {
        return new AuthContext("p1", scope, "p@x.io", clients, List.of(), List.of(), true, List.of());
    }

    @Test
    void anchorSeesEverything() {
        assertThat(ctx(Scope.ANCHOR, List.of("*")).visibility()).isSameAs(Visibility.Everything.INSTANCE);
    }

    @Test
    void everyoneElseSeesTheirOwnTenants() {
        assertThat(ctx(Scope.CLIENT, List.of("cli_1", "cli_2")).visibility())
                .isEqualTo(new Visibility.Tenants(List.of("cli_1", "cli_2")));
        // a super-admin partner with no clients: platform-scoped rows only, not everything
        assertThat(ctx(Scope.PARTNER, List.of()).visibility()).isEqualTo(new Visibility.Tenants(List.of()));
        assertThat(ctx(Scope.CLIENT, null).visibility()).isEqualTo(new Visibility.Tenants(List.of()));
    }

    @Test
    void tenantsIsAnImmutableCopy() {
        var ids = new ArrayList<>(List.of("cli_1"));
        var t = new Visibility.Tenants(ids);
        ids.add("cli_2");
        assertThat(t.clientIds()).containsExactly("cli_1");
        assertThat(new Visibility.Tenants(null).clientIds()).isEmpty();
    }
}
