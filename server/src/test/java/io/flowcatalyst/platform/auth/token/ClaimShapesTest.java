package io.flowcatalyst.platform.auth.token;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserIdentity;
import io.flowcatalyst.platform.principal.UserScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The list-claim shapes of auth-core §3.1 (Go `buildClients`, `appAccessOf`,
/// `intersectApps`, `confineToClient`) — pinned as tables, because these are
/// exactly the entries a token minted by the other implementation must match.
class ClaimShapesTest {

    private static Principal principal(UserScope scope, String clientId, List<String> assignedClients,
                                       List<String> apps, boolean allApps) {
        Instant now = Instant.now();
        return new Principal("prn_1", PrincipalType.USER, scope, clientId, null, "Ann", true,
                UserIdentity.of("ann@example.com"), null,
                List.of(new RoleAssignment("hr:manager", RoleAssignment.ADMIN_ASSIGNED, now)),
                assignedClients, apps, allApps, null, now, now);
    }

    @Test
    void anchorClientsIsTheWildcardWhateverItIsAssigned() {
        var p = principal(UserScope.ANCHOR, "clt_home", List.of("clt_a"), List.of(), false);
        assertThat(ClaimShapes.clients(p, Map.of("clt_a", "acme"))).containsExactly("*");
    }

    @Test
    void partnerClientsAreTheAssignedClientsAsPairs() {
        var p = principal(UserScope.PARTNER, null, List.of("clt_a", "clt_b"), List.of(), false);
        assertThat(ClaimShapes.clients(p, Map.of("clt_a", "acme")))
                .as("known label paired, unknown label degrades to the bare id")
                .containsExactly("clt_a:acme", "clt_b");
    }

    @Test
    void clientScopedClientsIsTheHomeClientOrNothing() {
        var home = principal(UserScope.CLIENT, "clt_home", List.of("clt_ignored"), List.of(), false);
        assertThat(ClaimShapes.clients(home, Map.of("clt_home", "home"))).containsExactly("clt_home:home");
        var none = principal(UserScope.CLIENT, null, List.of("clt_ignored"), List.of(), false);
        assertThat(ClaimShapes.clients(none, Map.of())).isEmpty();
    }

    @Test
    void applicationsIsTheWildcardForAllApplicationsElsePairs() {
        var all = principal(UserScope.CLIENT, null, List.of(), List.of("app_1"), true);
        assertThat(ClaimShapes.applications(all, Map.of("app_1", "orders"))).containsExactly("*");
        var some = principal(UserScope.CLIENT, null, List.of(), List.of("app_1", "app_2"), false);
        assertThat(ClaimShapes.applications(some, Map.of("app_1", "orders", "app_2", "")))
                .as("a blank code degrades to the bare id too")
                .containsExactly("app_1:orders", "app_2");
        var nothing = principal(UserScope.CLIENT, null, List.of(), List.of(), false);
        assertThat(ClaimShapes.applications(nothing, Map.of())).isEmpty();
    }

    @Test
    void intersectAppsIsAllOfTheClientsWhenTheUserHoldsEverythingElseTheIntersection() {
        var all = principal(UserScope.CLIENT, null, List.of(), List.of(), true);
        assertThat(ClaimShapes.intersectApps(all, List.of("app_1", "app_2"))).containsExactly("app_1", "app_2");
        var some = principal(UserScope.CLIENT, null, List.of(), List.of("app_2", "app_3"), false);
        assertThat(ClaimShapes.intersectApps(some, List.of("app_1", "app_2")))
                .as("client order, only what the user can reach")
                .containsExactly("app_2");
    }

    @Test
    void confinementForcesAllApplicationsOffAndKeepsTheNarrowedRoles() {
        var all = principal(UserScope.ANCHOR, null, List.of(), List.of(), true);
        var c = ClaimShapes.confineToClient(all, List.of("app_1"), List.of("orders:admin"));
        assertThat(c.applicationIds()).containsExactly("app_1");
        assertThat(c.allApplications()).as("a confined view never says all_applications").isFalse();
        assertThat(c.roles()).containsExactly("orders:admin");
        assertThat(ClaimShapes.applications(c.applicationIds(), c.allApplications(), Map.of("app_1", "orders")))
                .containsExactly("app_1:orders");
    }
}
