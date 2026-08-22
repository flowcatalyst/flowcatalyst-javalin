package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChecksTest {

    static AuthContext ctx(Scope scope, List<String> clients, List<String> perms) {
        return new AuthContext("p1", scope, "p@x.io", clients, List.of(), List.of(), true, perms);
    }

    static final AuthContext ANCHOR = ctx(Scope.ANCHOR, List.of(), List.of());
    static final AuthContext CLIENT_ADMIN = ctx(Scope.CLIENT, List.of("cli_1"),
            List.of(Permissions.USER_UPDATE, Permissions.EVENT_TYPE_VIEW));
    static final AuthContext WILDCARD = ctx(Scope.CLIENT, List.of("cli_1"), List.of("platform:messaging:*:*"));
    static final AuthContext SUPER = ctx(Scope.PARTNER, List.of(), List.of(Permissions.SUPER_ADMIN));

    static UseCaseError errorOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (UseCaseException e) {
            return e.error();
        }
    }

    @Test
    void nullIsUnauthenticatedEverywhere() {
        assertThat(errorOf(() -> Checks.requireAnchor(null)))
                .isEqualTo(UseCaseError.authorization("UNAUTHENTICATED", "authentication required"));
        assertThat(errorOf(() -> Checks.canReadEventTypes(null)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.canWriteEventTypes(null)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.checkScopeAccess(null, "cli_1")).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.requireUserAdmin(null, null)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.requireAdmin(null)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.canReadPortalUsers(null, "cli_1")).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(Checks.canAccessScope(null, "cli_1")).isFalse();
        assertThat(errorOf(() -> Checks.canReadEventTypes(null)).httpStatus()).isEqualTo(403);
    }

    @Test
    void anchorPassesEverything() {
        assertThatCode(() -> {
            Checks.requireAnchor(ANCHOR);
            Checks.canReadEventTypes(ANCHOR);
            Checks.canWriteEventTypes(ANCHOR);
            Checks.canSyncRoles(ANCHOR);
            Checks.canReadClients(ANCHOR);
            Checks.checkScopeAccess(ANCHOR, null);
            Checks.checkScopeAccess(ANCHOR, "cli_other");
            Checks.requireUserAdmin(ANCHOR, null);
            Checks.requireAdmin(ANCHOR);
            Checks.canManagePortalUsers(ANCHOR, "cli_x");
        }).doesNotThrowAnyException();
    }

    @Test
    void permissionRequiredMessages() {
        assertThat(errorOf(() -> Checks.canCreateEventTypes(CLIENT_ADMIN)))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "permission required: platform:messaging:event-type:create"));
        assertThat(errorOf(() -> Checks.canWriteEventTypes(CLIENT_ADMIN)))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "one of: platform:messaging:event-type:create, platform:messaging:event-type:update, platform:messaging:event-type:delete"));
        assertThatCode(() -> Checks.canReadEventTypes(CLIENT_ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void wildcardsMatchBySegment() {
        assertThatCode(() -> {
            Checks.canReadEventTypes(WILDCARD);
            Checks.canWriteSubscriptions(WILDCARD);
            Checks.canFireScheduledJobs(WILDCARD);
        }).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.canReadRoles(WILDCARD)).code()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(Permissions.matches("platform:*:*:*", "platform:iam:role:view")).isTrue();
        assertThat(Permissions.matches("platform:*:*", "platform:iam:role:view")).isFalse(); // segment count
        assertThat(Permissions.matches("platform:iam:role:view", "platform:iam:role:view")).isTrue();
        assertThat(Permissions.grants(List.of("platform:iam:*:view"), "platform:iam:role:view")).isTrue();
    }

    @Test
    void anchorRequiredAndAdmin() {
        assertThat(errorOf(() -> Checks.requireAnchor(CLIENT_ADMIN)))
                .isEqualTo(UseCaseError.authorization("ANCHOR_REQUIRED", "anchor scope required"));
        assertThat(errorOf(() -> Checks.canReadClients(SUPER)).code()).isEqualTo("ANCHOR_REQUIRED");
        assertThatCode(() -> Checks.requireAdmin(SUPER)).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.requireAdmin(CLIENT_ADMIN)))
                .isEqualTo(UseCaseError.authorization("ADMIN_REQUIRED", "admin permission required"));
    }

    @Test
    void scopeAccess() {
        assertThatCode(() -> Checks.checkScopeAccess(CLIENT_ADMIN, "cli_1")).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.checkScopeAccess(CLIENT_ADMIN, "cli_2")))
                .isEqualTo(UseCaseError.authorization("SCOPE_FORBIDDEN", "no access to this resource's client"));
        assertThat(errorOf(() -> Checks.checkScopeAccess(CLIENT_ADMIN, null)))
                .isEqualTo(UseCaseError.authorization("SCOPE_FORBIDDEN", "anchor scope required for this resource"));
        assertThatCode(() -> Checks.checkScopeAccess(SUPER, null)).doesNotThrowAnyException(); // super-admin passes platform-level
        assertThat(Checks.canAccessScope(SUPER, "cli_9")).isFalse(); // but not an unrelated client
        assertThat(Checks.filterClientScoped(CLIENT_ADMIN, List.of("cli_1", "cli_2", "none"),
                s -> s.equals("none") ? null : s)).containsExactly("cli_1", "none");
    }

    @Test
    void userAdmin() {
        assertThatCode(() -> Checks.requireUserAdmin(CLIENT_ADMIN, "cli_1")).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.requireUserAdmin(CLIENT_ADMIN, null)))
                .isEqualTo(UseCaseError.authorization("ANCHOR_REQUIRED", "anchor scope required for platform users"));
        assertThat(errorOf(() -> Checks.requireUserAdmin(CLIENT_ADMIN, "cli_2")))
                .isEqualTo(UseCaseError.authorization("SCOPE_FORBIDDEN", "no access to this user's client"));
        var noPerm = ctx(Scope.CLIENT, List.of("cli_1"), List.of());
        assertThat(errorOf(() -> Checks.requireUserAdmin(noPerm, "cli_1")).code()).isEqualTo("PERMISSION_REQUIRED");
    }

    @Test
    void portalUsers() {
        var portal = ctx(Scope.CLIENT, List.of("cli_1"), List.of(Permissions.PORTAL_USER_VIEW));
        assertThatCode(() -> Checks.canReadPortalUsers(portal, "cli_1")).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.canReadPortalUsers(portal, "cli_2")))
                .isEqualTo(UseCaseError.authorization("SCOPE_FORBIDDEN", "no access to this client"));
        assertThat(errorOf(() -> Checks.canManagePortalUsers(portal, "cli_1")).code()).isEqualTo("PERMISSION_REQUIRED");
    }

    @Test
    void applicationAxis() {
        var scoped = new AuthContext("svc", Scope.ANCHOR, null, List.of(), List.of(), List.of("app_1"), false, List.of());
        assertThat(scoped.isApplicationScoped()).isTrue();
        assertThat(scoped.canAccessApplication("app_1")).isTrue();
        assertThat(scoped.canAccessApplication("app_2")).isFalse();
        assertThat(ANCHOR.canAccessApplication("anything")).isTrue();
        assertThatThrownBy(() -> new AuthContext(null, null, null, null, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
    }
}
