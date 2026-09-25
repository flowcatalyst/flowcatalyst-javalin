package io.flowcatalyst.platform.role;

import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Nobody hands out authority they do not hold (owner ruling 2026-09-25,
/// `docs/backlog.md` §"Overnight review" item 14).
///
/// - **Roles.** A caller may add or remove a role only when it holds every one
///   of that role's permissions. The rule applies to the change: roles the
///   target keeps are not checked, so an administrator can still edit the other
///   roles of someone who holds more than they do. Removal counts, so a lesser
///   administrator cannot strip a super-admin.
/// - **Permissions.** A caller may add a permission to a role, or remove one
///   from it, only when it holds that permission. Otherwise granting yourself
///   a role you could not be assigned would be one edit away: add
///   `platform:*:*:*` to a role you already hold.
///
/// **Platform permissions only.** The ceiling counts permissions whose first
/// segment is `platform`: that is the authority an escalation reaches for, and
/// what `platform:*:*:*` covers. An application's own permissions
/// (`orders:order:create`) are held by nobody outside that application's roles,
/// not even a super-admin, so counting them would stop anyone administering
/// application roles. Those stay bounded by the application rules the callers
/// already enforce: a non-anchor may only name roles of applications its client
/// is entitled to (`Access.assignableRolesProblem`), and an application's roles
/// hold only its own permissions (S1.5).
///
/// Matching is [Permission#grants]: a held wildcard covers what it names, and
/// a role's own wildcard (`platform:messaging:*:*`) is covered only by a held
/// wildcard at least as wide. A role name that does not exist is not this
/// rule's business; the callers already refuse it as not found.
public final class RoleCeiling {

    private RoleCeiling() {
    }

    /// A role's permissions by name; empty for a role that does not exist.
    @FunctionalInterface
    public interface RolePermissions {
        List<String> of(String roleName);

        static RolePermissions from(RoleRepository roles) {
            return name -> roles.findByName(name).map(Role::permissions).orElse(List.of());
        }
    }

    /// The roles in `changed` whose permissions `caller` does not all hold, in
    /// the order given.
    public static List<String> rolesAboveCaller(AuthContext caller, Collection<String> changed, RolePermissions permissionsOf) {
        Set<String> above = new LinkedHashSet<>();
        for (String name : changed) {
            if (!permissionsAboveCaller(caller, permissionsOf.of(name)).isEmpty()) {
                above.add(name);
            }
        }
        return List.copyOf(above);
    }

    /// The platform permissions in `permissions` that `caller` does not hold.
    public static List<String> permissionsAboveCaller(AuthContext caller, Collection<String> permissions) {
        List<String> held = caller == null ? List.of() : caller.permissions();
        return permissions.stream()
                .filter(RoleCeiling::isPlatformPermission)
                .filter(p -> !Permission.grants(held, p))
                .distinct()
                .toList();
    }

    /// First segment `platform` (see the class doc).
    static boolean isPlatformPermission(String permission) {
        return permission != null && permission.startsWith(PLATFORM_PREFIX);
    }

    private static final String PLATFORM_PREFIX = "platform:";

    /// Refuses when any changed role is above `caller`.
    ///
    /// @throws UseCaseException authorization `ROLE_ABOVE_CALLER`, naming the roles
    public static void requireRoles(AuthContext caller, Collection<String> changed, RoleRepository roles) {
        requireRoles(caller, changed, RolePermissions.from(roles));
    }

    /// [#requireRoles(AuthContext, Collection, RoleRepository)] over any source of role permissions.
    public static void requireRoles(AuthContext caller, Collection<String> changed, RolePermissions permissionsOf) {
        List<String> above = rolesAboveCaller(caller, changed, permissionsOf);
        if (!above.isEmpty()) {
            throw UseCaseException.authorization("ROLE_ABOVE_CALLER",
                    "You may only assign or remove roles whose permissions you hold yourself: " + String.join(", ", above));
        }
    }

    /// Refuses when any changed permission is one `caller` does not hold.
    ///
    /// @throws UseCaseException authorization `PERMISSION_ABOVE_CALLER`, naming the permissions
    public static void requirePermissions(AuthContext caller, Collection<String> changed) {
        List<String> above = permissionsAboveCaller(caller, changed);
        if (!above.isEmpty()) {
            throw UseCaseException.authorization("PERMISSION_ABOVE_CALLER",
                    "You may only add or remove permissions you hold yourself: " + String.join(", ", above));
        }
    }

    /// `after` minus `before` plus `before` minus `after`: what a change adds or removes.
    public static Set<String> changed(Collection<String> before, Collection<String> after) {
        Set<String> out = new LinkedHashSet<>();
        for (String a : after) if (!before.contains(a)) out.add(a);
        for (String b : before) if (!after.contains(b)) out.add(b);
        return out;
    }
}
