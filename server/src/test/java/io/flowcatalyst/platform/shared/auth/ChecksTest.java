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

    /// Reach with no roles at all — pins that anchor scope alone grants no
    /// authority (docs/spec/permissions-from-roles.md §1).
    static final AuthContext ANCHOR = ctx(Scope.ANCHOR, List.of(), List.of());
    static final AuthContext ANCHOR_WITH_EVENT_TYPE_VIEW = ctx(Scope.ANCHOR, List.of(), List.of(EVENT_TYPE_VIEW.code()));
    static final AuthContext ANCHOR_WITH_WILDCARD = ctx(Scope.ANCHOR, List.of(), List.of(SUPER_ADMIN.code()));
    static final AuthContext ANCHOR_WITH_USER_WRITE = ctx(Scope.ANCHOR, List.of(), List.of(USER_UPDATE.code()));
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
        assertThat(errorOf(() -> Checks.requirePortalUserView(null, "cli_1")).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(errorOf(() -> Checks.checkApplicationAccess(null, "app_1", "app")).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(Checks.canAccessScope(null, "cli_1")).isFalse();
        assertThat(errorOf(() -> Checks.require(null, EVENT_TYPE_VIEW)).httpStatus()).isEqualTo(403);
    }

    /// Reach checks stay pure reach: an anchor with NO permissions still
    /// passes every one of these, because none of them is an authority
    /// question (docs/spec/permissions-from-roles.md §1, last table row).
    @Test
    void anchorReachChecksStayReachOnly() {
        assertThatCode(() -> {
            Checks.requireAnchor(ANCHOR);
            Checks.checkScopeAccess(ANCHOR, null);
            Checks.checkScopeAccess(ANCHOR, "cli_other");
            Checks.checkApplicationAccess(ANCHOR, "app_1", "app");
        }).doesNotThrowAnyException();
    }

    /// The withdrawn bypass (spec §1, `require`/`requireAny` row): an anchor
    /// with NO permissions is refused authority checks exactly like a
    /// CLIENT-scoped principal with none — reach is not authority. A mutant
    /// that reinstated `a.isAnchor() ||` in `require`/`requireAny` would turn
    /// every one of these into a pass.
    @Test
    void anchorWithoutThePermissionIsRefused() {
        assertThat(errorOf(() -> Checks.require(ANCHOR, EVENT_TYPE_VIEW)))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "permission required: platform:messaging:event-type:view"));
        assertThat(errorOf(() -> Checks.requireAny(ANCHOR, EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE)).code())
                .isEqualTo("PERMISSION_REQUIRED");
    }

    /// Same anchor, now holding the exact permission required: passes
    /// `require`/`requireAny` on its own merit, not on scope.
    @Test
    void anchorWithTheSpecificPermissionPasses() {
        assertThatCode(() -> {
            Checks.require(ANCHOR_WITH_EVENT_TYPE_VIEW, EVENT_TYPE_VIEW);
            Checks.requireAny(ANCHOR_WITH_EVENT_TYPE_VIEW, EVENT_TYPE_VIEW, ROLE_MANAGE);
        }).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.require(ANCHOR_WITH_EVENT_TYPE_VIEW, EVENT_TYPE_CREATE)).code())
                .isEqualTo("PERMISSION_REQUIRED");
    }

    /// Same anchor, now holding the super-admin wildcard: passes any
    /// permission, the same as any other principal holding it.
    @Test
    void anchorWithTheWildcardPasses() {
        assertThatCode(() -> {
            Checks.require(ANCHOR_WITH_WILDCARD, EVENT_TYPE_VIEW);
            Checks.requireAny(ANCHOR_WITH_WILDCARD, ROLE_MANAGE, APP_SVC_ROLE_CREATE);
        }).doesNotThrowAnyException();
    }

    /// A CLIENT context's `require`/`requireAny` behaviour is untouched by
    /// this unit — it never went through the anchor bypass.
    @Test
    void clientContextIsUnchanged() {
        assertThatCode(() -> Checks.require(CLIENT_ADMIN, EVENT_TYPE_VIEW)).doesNotThrowAnyException();
        assertThat(errorOf(() -> Checks.require(CLIENT_ADMIN, EVENT_TYPE_CREATE)).code()).isEqualTo("PERMISSION_REQUIRED");
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
    void anchorRequired() {
        assertThat(errorOf(() -> Checks.requireAnchor(CLIENT_ADMIN)))
                .isEqualTo(UseCaseError.authorization("ANCHOR_REQUIRED", "anchor scope required"));
        assertThat(errorOf(() -> Checks.requireAnchor(SUPER)).code()).isEqualTo("ANCHOR_REQUIRED");
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

    /// spec §3.1's anchor half of `requireUserAdmin`: an anchor reaches ANY
    /// target (client or platform) but is refused without a user-write
    /// permission — the old `if (a.isAnchor()) return;` bypass previously
    /// let a permission-less anchor manage any user; a mutant that restored
    /// it would turn both of these `PERMISSION_REQUIRED` cases back into a pass.
    @Test
    void anchorUserAdminNeedsTheUserWritePermissionForAnyTarget() {
        assertThat(errorOf(() -> Checks.requireUserAdmin(ANCHOR, "cli_1")))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "one of: platform:iam:user:create, platform:iam:user:update, platform:iam:user:delete"));
        assertThat(errorOf(() -> Checks.requireUserAdmin(ANCHOR, null)))
                .isEqualTo(UseCaseError.authorization("PERMISSION_REQUIRED",
                        "one of: platform:iam:user:create, platform:iam:user:update, platform:iam:user:delete"));
        // With the permission, an anchor still reaches any target, unlike a non-anchor.
        assertThatCode(() -> {
            Checks.requireUserAdmin(ANCHOR_WITH_USER_WRITE, "cli_1");
            Checks.requireUserAdmin(ANCHOR_WITH_USER_WRITE, "cli_unrelated");
            Checks.requireUserAdmin(ANCHOR_WITH_USER_WRITE, null);
        }).doesNotThrowAnyException();
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
