package io.flowcatalyst.platform.function;

import io.flowcatalyst.function.Caller;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.auth.Scope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// P1 (`docs/spec/function-caller-claims.md` §2): `function-api` has NO
/// dependency on `server` — it carries its own ~10-line copy of the
/// authorisation rules a function needs (`Caller.Principal#hasPermission` /
/// `#isAnchor` / `#canAccessClient` / `#canAccessApplication`). This test is
/// the one place that sees BOTH copies and pins them to agree: every case
/// run through `Permission.grants`/`AuthContext` and through
/// `Caller.Principal`'s own methods must answer identically. A held
/// permission pattern and an `AuthContext`/`Principal` pair are built from
/// the SAME claims for every case, so there is exactly one source of truth
/// per row.
///
/// Mutants this pins (spec §2): drop the segment-count check from the jar's
/// copy ⇒ `hasPermissionAgreesWithPermissionGrants` fails on the
/// different-segment-count rows; make `canAccessClient` ignore anchor ⇒
/// `canAccessClientAgreesWithAuthContext` fails on the anchor row.
class CallerClaimsAgreeWithPlatformTest {

    // ── hasPermission vs Permission.grants/matches ────────────────────────────

    @ParameterizedTest(name = "[{index}] held={0} required={1}")
    @CsvSource({
            // exact match
            "a:b:c:d, a:b:c:d",
            // wildcard segment (leading, middle, trailing)
            "'*:b:c:d', a:b:c:d",
            "'a:*:c:d', a:b:c:d",
            "'a:b:c:*', a:b:c:d",
            // wildcard in the middle of a real code
            "platform:*:event-type:view, platform:messaging:event-type:view",
            // all-wildcard (super-admin)
            "'*:*:*:*', platform:messaging:event-type:view",
            // a wildcard in the REQUIRED code grants nothing: only a held code may carry one
            // (a copy that honoured it on both sides would let a caller ask for a:*:*:* and pass)
            "a:b:c:d, 'a:*:c:d'",
            "a:b:c:d, '*:*:*:*'",
            // mismatch: a non-wildcard segment differs
            "a:b:c:d, a:b:c:e",
            // different segment counts (fewer / more)
            "a:b:c, a:b:c:d",
            "a:b:c:d:e, a:b:c:d",
            // empty strings
            "'', ''",
            "'', a:b:c:d",
    })
    void hasPermissionAgreesWithPermissionGrants(String held, String required) {
        boolean viaPlatform = Permission.grants(List.of(held), required);
        boolean viaJar = principalWith(held).hasPermission(required);
        assertThat(viaJar).as("held=%s required=%s", held, required).isEqualTo(viaPlatform);
    }

    @ParameterizedTest(name = "[{index}] held={0}")
    @CsvSource({"a:b:c:d", "'*:*:*:*'", "''"})
    void hasPermissionAgreesWithPermissionGrantsForNullRequired(String held) {
        boolean viaPlatform = Permission.grants(List.of(held), null);
        boolean viaJar = principalWith(held).hasPermission(null);
        assertThat(viaJar).isEqualTo(viaPlatform).isFalse();
    }

    // ── isAnchor ───────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] tier={0}")
    @CsvSource({"ANCHOR", "PARTNER", "CLIENT"})
    void isAnchorAgreesWithAuthContext(String tier) {
        AuthContext ac = authContext(tier, List.of(), List.of());
        Caller.Principal principal = principal(tier, List.of(), List.of(), false, Set.of());
        assertThat(principal.isAnchor()).as("tier=%s", tier).isEqualTo(ac.isAnchor());
    }

    @org.junit.jupiter.api.Test
    void isAnchorAgreesWithAuthContextForNullTier() {
        AuthContext ac = authContext(null, List.of(), List.of());
        Caller.Principal principal = principal(null, List.of(), List.of(), false, Set.of());
        assertThat(principal.isAnchor()).isEqualTo(ac.isAnchor()).isFalse();
    }

    // ── canAccessClient ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] tier={0} clients={1} asked={2}")
    @CsvSource({
            "ANCHOR, '', some-client",           // anchor: always, even with no clients listed
            "CLIENT, clt_1, clt_1",               // held, non-anchor
            "CLIENT, clt_1, clt_2",               // not held, non-anchor
            "CLIENT, 'clt_1;clt_2', clt_2",       // held, one of several
            "PARTNER, '', clt_1",                 // not held, empty list
    })
    void canAccessClientAgreesWithAuthContext(String tier, String clientsRaw, String asked) {
        List<String> clients = splitList(clientsRaw);
        AuthContext ac = authContext(tier, clients, List.of());
        Caller.Principal principal = principal(tier, clients, List.of(), false, Set.of());
        assertThat(principal.canAccessClient(asked)).as("tier=%s clients=%s asked=%s", tier, clients, asked)
                .isEqualTo(ac.canAccessClient(asked));
    }

    // ── canAccessApplication ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] allApplications={0} applications={1} asked={2}")
    @CsvSource({
            "true, '', app_1",                    // all-applications: always
            "false, app_1, app_1",                // held
            "false, app_1, app_2",                // not held
            "false, 'app_1;app_2', app_2",         // held, one of several
            "false, '', app_1",                    // not held, empty list
    })
    void canAccessApplicationAgreesWithAuthContext(boolean allApplications, String applicationsRaw, String asked) {
        List<String> applications = splitList(applicationsRaw);
        AuthContext ac = authContext("CLIENT", List.of(), applications, allApplications);
        Caller.Principal principal = principal("CLIENT", List.of(), applications, allApplications, Set.of());
        assertThat(principal.canAccessApplication(asked))
                .as("allApplications=%s applications=%s asked=%s", allApplications, applications, asked)
                .isEqualTo(ac.canAccessApplication(asked));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Caller.Principal principalWith(String heldPermission) {
        return principal("CLIENT", List.of(), List.of(), false, Set.of(heldPermission));
    }

    private static Caller.Principal principal(String tier, List<String> clients, List<String> applications,
            boolean allApplications, Set<String> permissions) {
        return new Caller.Principal("prn_1", "user", tier, clients, List.of(), applications, allApplications,
                permissions);
    }

    private static AuthContext authContext(String tier, List<String> clients, List<String> applications) {
        return authContext(tier, clients, applications, false);
    }

    private static AuthContext authContext(String tier, List<String> clients, List<String> applications,
            boolean allApplications) {
        return new AuthContext("prn_1", Scope.parse(tier), null, clients, List.of(), applications, allApplications,
                List.of());
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split(";"));
    }
}
