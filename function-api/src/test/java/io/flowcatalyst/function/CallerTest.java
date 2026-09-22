package io.flowcatalyst.function;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/function-invocation.md` §7: `Caller`'s three cases;
/// `docs/spec/function-caller-claims.md` §1: `Principal`'s own invariants
/// (required `id`/`type`, defensive copies) and its non-`hasPermission`
/// methods (`hasPermission` itself is pinned against the platform's own rule
/// by the agreement test in the `server` module — P1).
class CallerTest {

    @Test
    void platformAndAnonymousAreSharedInstances() {
        assertThat(Caller.Platform.INSTANCE).isInstanceOf(Caller.class);
        assertThat(Caller.Anonymous.INSTANCE).isInstanceOf(Caller.class);
        assertThat(new Caller.Platform()).isEqualTo(Caller.Platform.INSTANCE);
        assertThat(new Caller.Anonymous()).isEqualTo(Caller.Anonymous.INSTANCE);
    }

    @Test
    void principalRequiresIdAndType() {
        assertThatThrownBy(() -> new Caller.Principal(null, "user", null, List.of(), List.of(), List.of(), false, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Caller.Principal("id", null, null, List.of(), List.of(), List.of(), false, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void principalPermissionsAreIndependentOfTheSetPassedIn() {
        Set<String> permissions = new HashSet<>(Set.of("read"));
        Caller.Principal principal =
                new Caller.Principal("id", "user", "CLIENT", List.of("client-1"), List.of(), List.of(), false, permissions);
        permissions.add("write");
        assertThat(principal.permissions()).containsExactly("read");
    }

    @Test
    void principalPermissionsAreUnmodifiable() {
        Caller.Principal principal =
                new Caller.Principal("id", "user", null, List.of(), List.of(), List.of(), false, Set.of("read"));
        assertThatThrownBy(() -> principal.permissions().add("write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── P3: clientId() — the single non-`*` client, else empty ───────────────

    @Test
    void clientIdIsEmptyWhenNoClients() {
        Caller.Principal principal =
                new Caller.Principal("id", "service-account", null, List.of(), List.of(), List.of(), false, Set.of());
        assertThat(principal.clientId()).isEmpty();
    }

    @Test
    void clientIdIsTheOneClientWhenExactlyOne() {
        Caller.Principal principal =
                new Caller.Principal("id", "user", "CLIENT", List.of("clt_1"), List.of(), List.of(), false, Set.of());
        assertThat(principal.clientId()).contains("clt_1");
    }

    @Test
    void clientIdIsEmptyWhenTwoClients() {
        Caller.Principal principal = new Caller.Principal("id", "user", "PARTNER", List.of("clt_1", "clt_2"),
                List.of(), List.of(), false, Set.of());
        assertThat(principal.clientId()).isEmpty();
    }

    @Test
    void clientIdIsEmptyWhenTheOneEntryIsTheAnchorWildcard() {
        Caller.Principal principal =
                new Caller.Principal("id", "user", "ANCHOR", List.of("*"), List.of(), List.of(), false, Set.of());
        assertThat(principal.clientId()).isEmpty();
    }

    // ── isAnchor / canAccessClient / canAccessApplication / hasRole ──────────

    @Test
    void isAnchorOnlyWhenTierIsAnchor() {
        assertThat(principal("ANCHOR", List.of(), List.of(), List.of(), false, Set.of()).isAnchor()).isTrue();
        assertThat(principal("CLIENT", List.of(), List.of(), List.of(), false, Set.of()).isAnchor()).isFalse();
        assertThat(principal(null, List.of(), List.of(), List.of(), false, Set.of()).isAnchor()).isFalse();
    }

    @Test
    void canAccessClientIsTrueForAnchorRegardlessOfClients() {
        Caller.Principal anchor = principal("ANCHOR", List.of(), List.of(), List.of(), false, Set.of());
        assertThat(anchor.canAccessClient("anything")).isTrue();
    }

    @Test
    void canAccessClientChecksTheListForNonAnchors() {
        Caller.Principal p = principal("CLIENT", List.of("clt_1"), List.of(), List.of(), false, Set.of());
        assertThat(p.canAccessClient("clt_1")).isTrue();
        assertThat(p.canAccessClient("clt_2")).isFalse();
    }

    @Test
    void canAccessApplicationIsTrueWhenAllApplications() {
        Caller.Principal p = principal("CLIENT", List.of(), List.of(), List.of(), true, Set.of());
        assertThat(p.canAccessApplication("anything")).isTrue();
    }

    @Test
    void canAccessApplicationChecksTheListOtherwise() {
        Caller.Principal p = principal("CLIENT", List.of(), List.of(), List.of("app_1"), false, Set.of());
        assertThat(p.canAccessApplication("app_1")).isTrue();
        assertThat(p.canAccessApplication("app_2")).isFalse();
    }

    @Test
    void hasRoleChecksTheRoleList() {
        Caller.Principal p = principal("CLIENT", List.of(), List.of("admin"), List.of(), false, Set.of());
        assertThat(p.hasRole("admin")).isTrue();
        assertThat(p.hasRole("viewer")).isFalse();
    }

    // ── hasAnyPermission / hasAllPermissions ──────────────────────────────────

    @Test
    void hasAnyPermissionIsTrueWhenAtLeastOneMatches() {
        Caller.Principal p = principal("CLIENT", List.of(), List.of(), List.of(), false, Set.of("a:b:c:read"));
        assertThat(p.hasAnyPermission("a:b:c:write", "a:b:c:read")).isTrue();
        assertThat(p.hasAnyPermission("a:b:c:write", "a:b:c:delete")).isFalse();
    }

    @Test
    void hasAllPermissionsRequiresEveryOne() {
        Caller.Principal p = principal("CLIENT", List.of(), List.of(), List.of(), false, Set.of("a:b:c:read", "a:b:c:write"));
        assertThat(p.hasAllPermissions("a:b:c:read", "a:b:c:write")).isTrue();
        assertThat(p.hasAllPermissions("a:b:c:read", "a:b:c:delete")).isFalse();
    }

    // ── hasPermission — wildcard matching table (mirrored in the server's agreement test) ──

    @ParameterizedTest(name = "[{index}] held={0} required={1} -> {2}")
    @CsvSource({
            "a:b:c:d, a:b:c:d, true",
            "a:*:c:d, a:b:c:d, true",
            "a:b:*:d, a:b:c:d, true",
            "*:*:*:*, a:b:c:d, true",
            "a:b:c:d, a:b:c:e, false",
            "a:b:c, a:b:c:d, false",
            "a:b:c:d:e, a:b:c:d, false",
    })
    void hasPermissionMatchesSegmentwise(String held, String required, boolean expected) {
        Caller.Principal p = principal("CLIENT", List.of(), List.of(), List.of(), false, Set.of(held));
        assertThat(p.hasPermission(required)).isEqualTo(expected);
    }

    @Test
    void hasPermissionIsFalseForNullRequired() {
        Caller.Principal p = principal("CLIENT", List.of(), List.of(), List.of(), false, Set.of("a:b:c:d"));
        assertThat(p.hasPermission(null)).isFalse();
    }

    // ── P5: email/name never reach the function — a compile-time fact ────────

    @Test
    void principalCarriesNoEmailOrName() {
        Set<String> names = new HashSet<>();
        for (RecordComponent c : Caller.Principal.class.getRecordComponents()) {
            names.add(c.getName());
        }
        assertThat(names).containsExactlyInAnyOrder(
                "id", "type", "tier", "clients", "roles", "applications", "allApplications", "permissions");
        assertThat(names).doesNotContain("email", "name");
    }

    private static Caller.Principal principal(String tier, List<String> clients, List<String> roles,
            List<String> applications, boolean allApplications, Set<String> permissions) {
        return new Caller.Principal("id", "user", tier, clients, roles, applications, allApplications, permissions);
    }
}
