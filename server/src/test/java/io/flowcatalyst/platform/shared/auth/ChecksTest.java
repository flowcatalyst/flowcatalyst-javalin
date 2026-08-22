package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.flowcatalyst.platform.shared.auth.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChecksTest {

    static AuthContext ctx(Scope scope, List<String> clients, List<String> perms) {
        return new AuthContext("p1", scope, "p@x.io", clients, List.of(), List.of(), true, perms);
    }

    static final AuthContext ANCHOR = ctx(Scope.ANCHOR, List.of(), List.of());
    static final AuthContext CLIENT_ADMIN = ctx(Scope.CLIENT, List.of("cli_1"),
            List.of(USER_UPDATE.code(), EVENT_TYPE_VIEW.code()));
    static final AuthContext WILDCARD = ctx(Scope.CLIENT, List.of("cli_1"), List.of("platform:messaging:*:*"));
    static final AuthContext SUPER = ctx(Scope.PARTNER, List.of(), List.of(SUPER_ADMIN.code()));

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
        assertThat(errorOf(() -> Checks.require(null, EVENT_TYPE_VIEW)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.requireAny(null, EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.checkScopeAccess(null, "cli_1")).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.requireUserAdmin(null, null)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.requireAdmin(null)).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.requirePortalUserView(null, "cli_1")).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.checkApplicationAccess(null, "app_1", "app")).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(Checks.canAccessScope(null, "cli_1")).isFalse();
        assertThat(errorOf(() -> Checks.require(null, EVENT_TYPE_VIEW)).httpStatus()).isEqualTo(403);
    }

    @Test
    void anchorPassesEverything() {
        assertThatCode(() -> {
            Checks.requireAnchor(ANCHOR);
            Checks.require(ANCHOR, EVENT_TYPE_VIEW);
            Checks.requireAny(ANCHOR, EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
            Checks.requireAny(ANCHOR, ROLE_MANAGE, APP_SVC_ROLE_CREATE);
            Checks.checkScopeAccess(ANCHOR, null);
            Checks.checkScopeAccess(ANCHOR, "cli_other");
            Checks.requireUserAdmin(ANCHOR, null);
            Checks.requireAdmin(ANCHOR);
            Checks.requirePortalUserManage(ANCHOR, "cli_x");
            Checks.checkApplicationAccess(ANCHOR, "app_1", "app");
        }).doesNotThrowAnyException();
    }

    @Test
    void permissionRequiredMessages() {
        assertThat(errorOf(() -> Checks.require(CLIENT_ADMIN, EVENT_TYPE_CREATE)))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "permission required: platform:messaging:event-type:create"));
        assertThat(errorOf(() -> Checks.requireAny(CLIENT_ADMIN, EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE)))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "one of: platform:messaging:event-type:create, platform:messaging:event-type:update, platform:messaging:event-type:delete"));
        assertThatCode(() -> Checks.require(CLIENT_ADMIN, EVENT_TYPE_VIEW)).doesNotThrowAnyException();
        assertThatCode(() -> Checks.requireAny(CLIENT_ADMIN, USER_CREATE, USER_UPDATE)).doesNotThrowAnyException();
    }

    @Test
    void wildcardsMatchBySegment() {
        assertThatCode(() -> {
            Checks.require(WILDCARD, EVENT_TYPE_VIEW);
            Checks.requireAny(WILDCARD, SUBSCRIPTION_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE);
            Checks.require(WILDCARD, SCHEDULED_JOB_FIRE);
        }).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.require(WILDCARD, ROLE_VIEW)).code()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(WILDCARD.hasPermission(EVENT_TYPE_VIEW)).isTrue();
        assertThat(WILDCARD.hasPermission(ROLE_VIEW)).isFalse();
        assertThat(SUPER.isSuperAdmin()).isTrue();
        assertThat(WILDCARD.isSuperAdmin()).isFalse();
    }

    @Test
    void anchorRequiredAndAdmin() {
        assertThat(errorOf(() -> Checks.requireAnchor(CLIENT_ADMIN)))
                .isEqualTo(UseCaseError.authorization("ANCHOR_REQUIRED", "anchor scope required"));
        assertThat(errorOf(() -> Checks.requireAnchor(SUPER)).code()).isEqualTo("ANCHOR_REQUIRED");
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
        assertThat(errorOf(() -> Checks.requireUserAdmin(noPerm, "cli_1")))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "one of: platform:iam:user:create, platform:iam:user:update, platform:iam:user:delete"));
    }

    @Test
    void portalUsers() {
        var portal = ctx(Scope.CLIENT, List.of("cli_1"), List.of(PORTAL_USER_VIEW.code()));
        assertThatCode(() -> Checks.requirePortalUserView(portal, "cli_1")).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.requirePortalUserView(portal, "cli_2")))
                .isEqualTo(UseCaseError.authorization("SCOPE_FORBIDDEN", "no access to this client"));
        assertThat(errorOf(() -> Checks.requirePortalUserManage(portal, "cli_1")))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "permission required: platform:iam:portal-user:manage"));
        var manager = ctx(Scope.CLIENT, List.of("cli_1"), List.of(PORTAL_USER_MANAGE.code()));
        assertThatCode(() -> {
            Checks.requirePortalUserView(manager, "cli_1");
            Checks.requirePortalUserManage(manager, "cli_1");
        }).doesNotThrowAnyException();
    }

    @Test
    void applicationAxis() {
        var scoped = new AuthContext("svc", Scope.ANCHOR, null, List.of(), List.of(), List.of("app_1"), false, List.of());
        assertThat(scoped.isApplicationScoped()).isTrue();
        assertThat(scoped.canAccessApplication("app_1")).isTrue();
        assertThat(scoped.canAccessApplication("app_2")).isFalse();
        assertThat(ANCHOR.canAccessApplication("anything")).isTrue();
        assertThat(errorOf(() -> Checks.checkApplicationAccess(scoped, "app_2", "other")))
                .isEqualTo(UseCaseError.authorization("FORBIDDEN", "Not authorised for application 'other'"));
        assertThatThrownBy(() -> new AuthContext(null, null, null, null, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
    }
}
