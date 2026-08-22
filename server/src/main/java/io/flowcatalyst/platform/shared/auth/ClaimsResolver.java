package io.flowcatalyst.platform.shared.auth;

import java.util.List;
import java.util.Optional;

/// The authenticator's seam to the principal / role stores (Go
/// `provider.ResolveClaims` + `provider.FlattenPermissions`).
///
/// The DB-backed implementation (principal + role repositories: load the
/// principal, reject inactive, collect `client_id` + assigned clients,
/// application access, role names, flatten role → permission codes) lands with
/// the IAM aggregates. Until then [#none()] serves the bearer-only path and
/// tests stub this interface.
public interface ClaimsResolver {

    /// Re-resolves the mutable authorization data for a session-cookie
    /// principal on every request, so role/permission/scope changes take
    /// effect immediately and the cookie stays tiny. `Optional.empty()` means
    /// "not found / deactivated / lookup failed" → the request proceeds
    /// **unauthenticated** (a stale cookie is a graceful logout, not a 401).
    Optional<AuthContext> resolveSession(String principalId);

    /// Resolves role names into their de-duplicated permission set for a
    /// bearer that carries roles but no `scope` claim. An empty result leaves
    /// the context without permissions (Go ignores a lookup error the same way).
    default List<String> flattenPermissions(List<String> roles) {
        return List.of();
    }

    /// A resolver with no backing store: every session is unauthenticated,
    /// no permissions are derived. For bearer-only deployments and tests.
    static ClaimsResolver none() {
        return _ -> Optional.empty();
    }
}
