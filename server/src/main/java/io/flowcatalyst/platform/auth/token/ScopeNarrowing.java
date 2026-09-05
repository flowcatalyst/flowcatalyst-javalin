package io.flowcatalyst.platform.auth.token;

import io.flowcatalyst.platform.shared.auth.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// The `scope` claim a mint advertises (`docs/spec/auth-core.md` §7.3.3; Go
/// `oauthapi.grantedScope`, bounded at every tier since `304338a`).
///
/// A requested scope is a space-delimited mix of OIDC flow scopes and
/// permission codes. The flow scopes ([#RESERVED]) drive separate behaviour
/// (`openid` → id_token, `offline_access` → refresh_token) and are not
/// permissions, so they are dropped before narrowing. What is left is
/// intersected with the principal's **ceiling** — the permissions its roles
/// grant, flattened — using the four-segment wildcard match
/// [Permission#grants]. An anchor is not exempt: the token may only ever
/// advertise what a role grants; the escape hatch for a genuine super-admin
/// is a real wildcard permission on a role.
public final class ScopeNarrowing {

    /// OAuth/OIDC flow scopes that are never permissions.
    public static final Set<String> RESERVED = Set.of("openid", "profile", "email", "address", "phone", "offline_access");

    private ScopeNarrowing() {
    }

    /// @param permissions the permissions the token advertises (its `scope` claim)
    /// @param explicit    whether the caller asked for specific permissions —
    ///                    `client_credentials` rejects `explicit && empty` with
    ///                    `invalid_scope`; interactive minting ignores it
    public record Granted(List<String> permissions, boolean explicit) {
        public Granted {
            permissions = List.copyOf(permissions);
        }

        /// The claim value: space-joined, or `null` when there is nothing to advertise.
        public String claim() {
            return permissions.isEmpty() ? null : String.join(" ", permissions);
        }
    }

    /// Narrows `requested` to what `ceiling` grants.
    ///
    /// No permission requested (only flow scopes, or nothing) ⇒ the full
    /// ceiling, `explicit = false`. Otherwise each requested permission the
    /// ceiling grants, in request order, `explicit = true` — possibly empty.
    public static Granted grant(List<String> ceiling, String requested) {
        List<String> ceil = ceiling == null ? List.of() : ceiling;
        var requestedPermissions = new ArrayList<String>();
        if (requested != null) {
            for (String token : requested.trim().split("\\s+")) {
                if (!token.isEmpty() && !RESERVED.contains(token)) {
                    requestedPermissions.add(token);
                }
            }
        }
        if (requestedPermissions.isEmpty()) {
            return new Granted(ceil, false);
        }
        var granted = new ArrayList<String>(requestedPermissions.size());
        for (String r : requestedPermissions) {
            if (Permission.grants(ceil, r)) {
                granted.add(r);
            }
        }
        return new Granted(granted, true);
    }
}
