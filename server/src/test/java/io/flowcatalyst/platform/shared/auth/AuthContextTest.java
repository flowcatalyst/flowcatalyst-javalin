package io.flowcatalyst.platform.shared.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextTest {

    private static AuthContext ctx(Scope scope, List<String> clients) {
        return new AuthContext("p1", scope, "p@x.io", clients, List.of(), List.of(), true, List.of());
    }

    /// S2.1/S2.4: a context nobody labelled is never a session — the one
    /// value that opens the self-service sign-in routes comes only from the
    /// authenticator's cookie path ([Authenticator]).
    @Test
    void anUnlabelledContextIsNeverASessionCookie() {
        assertThat(ctx(Scope.ANCHOR, List.of()).credential()).isEqualTo(AuthContext.Credential.IN_PROCESS);
        assertThat(ctx(Scope.ANCHOR, List.of()).viaSessionCookie()).isFalse();
        var full = new AuthContext("p1", PrincipalType.USER, Scope.ANCHOR, null, null, List.of(), List.of(), List.of(), true,
                List.of(), null);
        assertThat(full.viaSessionCookie()).as("mutant: the 11-argument constructor defaults to a session").isFalse();
        var session = full.withCredential(AuthContext.Credential.SESSION_COOKIE);
        assertThat(session.viaSessionCookie()).isTrue();
        assertThat(session.withPrincipalType(PrincipalType.USER).viaSessionCookie()).as("the stamp survives a copy").isTrue();
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
