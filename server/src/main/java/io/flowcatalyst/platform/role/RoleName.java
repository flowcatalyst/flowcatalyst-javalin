package io.flowcatalyst.platform.role;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;
import java.util.Objects;

/// A role name that has been through the write boundary (Go F18,
/// `docs/token-claims-followups.md` §4): a `RoleAssignment` holds one, so an
/// assignment cannot be built from a raw string. The ways to get one:
///
///   - [#resolve]: shape-checked AND looked up, the result being the stored
///     role's own canonical name. Every assignment write path uses this.
///   - [#of]: shape-checked only, for paths that deliberately accept a name no
///     role bears yet.
///   - [Role#canonicalName]: from a role already in hand.
///   - [#constant]: a platform-owned literal (a seeded role); throws on a
///     malformed one.
///   - [#stored]: persistence reads only; it validates nothing.
///
/// A type holds against code, not against `psql`; that trade is accepted
/// (owner ruling 2026-08-27: no foreign key, the invariant belongs to the
/// domain).
///
/// **Shape.** A role name is the canonical `{applicationCode}:{roleName}`: two
/// or more colon-separated, non-empty segments of ASCII letters, digits and
/// `_`, `.`, `-`: no whitespace of any kind (the ASCII or the Unicode kind), no
/// control or zero-width characters, nothing that can render as a look-alike
/// of a real name. A multi-colon name (`logistics_portal:dashboard:user`) is
/// tolerated so existing definitions stay valid; case is not constrained (the
/// SDK sync lowercases, the admin create does not, and both exist in stored
/// data). A permission has the same shape, plus `*`, with one or more segments.
/// A value that fails is **rejected, never trimmed**: silently repairing
/// `example: dispatcher` would hide the caller's bug.
public final class RoleName {

    /// The 400 code for a role name of the wrong shape.
    public static final String INVALID_NAME = "INVALID_ROLE_NAME";
    /// The 400 code for a permission string of the wrong shape.
    public static final String INVALID_PERMISSION = "INVALID_PERMISSION";
    /// The 400 code for a well-formed name no role bears.
    public static final String ROLE_NOT_FOUND = "ROLE_NOT_FOUND";

    private final String value;

    private RoleName(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoleName other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    // ── Constructors ───────────────────────────────────────────────────────

    /// Shape-checks `name` ([#validate]) and returns it.
    ///
    /// @throws UseCaseException validation `INVALID_ROLE_NAME`
    public static RoleName of(String name) {
        validate(name);
        return new RoleName(name);
    }

    /// Validates `name` and resolves it against the role definitions: 400
    /// `INVALID_ROLE_NAME` for a malformed value, 400 `ROLE_NOT_FOUND` for a
    /// well-formed name no role bears. The result is the stored role's own
    /// canonical name.
    public static RoleName resolve(RoleRepository roles, String name) {
        validate(name);
        return roles.findByName(name)
                .orElseThrow(() -> UseCaseException.validation(ROLE_NOT_FOUND, "Role not found: " + name))
                .canonicalName();
    }

    /// [#resolve] for every name, in order.
    public static List<RoleName> resolveAll(RoleRepository roles, List<String> names) {
        return names.stream().map(n -> resolve(roles, n)).toList();
    }

    /// A platform-owned literal (a seeded role): [#of] for a constant.
    public static RoleName constant(String name) {
        return of(name);
    }

    /// A name read back from storage. Persistence adapters only: it validates
    /// nothing, so a row written outside the domain stays visible rather than
    /// failing every read of the principal.
    public static RoleName stored(String name) {
        return new RoleName(Objects.requireNonNull(name, "name"));
    }

    // ── Shape ──────────────────────────────────────────────────────────────

    /// Whether `name` is a well-formed canonical role name.
    public static boolean isWellFormed(String name) {
        return validSegments(name, 2, false);
    }

    /// @throws UseCaseException validation `INVALID_ROLE_NAME`, the message quoting the value
    public static void validate(String name) {
        if (!isWellFormed(name)) {
            throw UseCaseException.validation(INVALID_NAME, "Invalid role name \"" + name
                    + "\": expected '{application}:{role}' with letters, digits, '_', '.' and '-' only, and no spaces");
        }
    }

    /// [#validate] every name; the first offender's error.
    public static void validateAll(List<String> names) {
        names.forEach(RoleName::validate);
    }

    /// @throws UseCaseException validation `INVALID_PERMISSION`, the message quoting the value
    public static void validatePermission(String permission) {
        if (!validSegments(permission, 1, true)) {
            throw UseCaseException.validation(INVALID_PERMISSION, "Invalid permission \"" + permission
                    + "\": colon-separated segments of letters, digits, '_', '.', '-' or '*', and no spaces");
        }
    }

    /// [#validatePermission] every permission; the first offender's error.
    public static void validatePermissions(List<String> permissions) {
        if (permissions != null) permissions.forEach(RoleName::validatePermission);
    }

    private static boolean validSegments(String s, int minSegments, boolean allowStar) {
        if (s == null) return false;
        String[] segs = s.split(":", -1);
        if (segs.length < minSegments) return false;
        for (String seg : segs) {
            if (seg.isEmpty()) return false;
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '_' || c == '.' || c == '-' || (allowStar && c == '*');
                if (!ok) return false; // includes every non-ASCII char
            }
        }
        return true;
    }
}
