package io.flowcatalyst.platform.auth.claims;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The store-backed [ClaimsResolver] (`docs/spec/auth-core.md` §3.5; Go
/// `provider.BuildClaims`, `FlattenPermissions`, `FilterRolesForApplications`).
///
/// A session cookie carries identity only, so everything mutable — tier,
/// clients, roles, applications, permissions — is re-read here on every
/// request: a role change takes effect immediately and the cookie stays
/// tiny. A principal that is missing or deactivated resolves to
/// `Optional.empty()`, which the authenticator treats as logged out rather
/// than as a 401 (a stale cookie is a graceful logout).
///
/// The [AuthContext] this builds is **post-boundary**: bare ids, never the
/// `"{id}:{label}"` pairs a token carries — those are split exactly once when
/// a token is read, and nothing here is a token.
public final class DbClaimsResolver implements ClaimsResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DbClaimsResolver.class);

    private final PrincipalRepository principals;
    private final RoleRepository roles;

    public DbClaimsResolver(PrincipalRepository principals, RoleRepository roles) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    @Override
    public Optional<AuthContext> resolveSession(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            return Optional.empty();
        }
        Optional<Principal> found;
        try {
            found = principals.findById(principalId);
        } catch (RuntimeException e) {
            // A lookup failure is a logout, not a 500 (Go: the middleware
            // treats any BuildClaims error as unauthenticated).
            LOG.warn("session principal lookup failed principal={}", principalId, e);
            return Optional.empty();
        }
        if (found.isEmpty() || !found.get().active()) {
            return Optional.empty();
        }
        Principal p = found.get();
        List<String> roleNames = roleNames(p);
        var clients = new ArrayList<>(p.assignedClients());
        if (p.clientId() != null && !p.clientId().isBlank()) {
            clients.add(p.clientId());
        }
        return Optional.of(new AuthContext(
                p.id(),
                Scope.parse(p.scope().name()),
                p.email(),
                clients,
                roleNames,
                p.accessibleApplicationIds(),
                p.allApplications(),
                flattenPermissions(roleNames)));
    }

    /// Each role by name, its permissions concatenated and de-duplicated in
    /// first-seen order. A role the store no longer has is skipped — the
    /// principal keeps whatever its remaining roles grant.
    @Override
    public List<String> flattenPermissions(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return List.of();
        }
        var out = new LinkedHashSet<String>();
        for (String name : roleNames) {
            roles.findByName(name).ifPresent(r -> out.addAll(r.permissions()));
        }
        return List.copyOf(out);
    }

    /// The principal's permission ceiling: its assigned roles, flattened.
    public List<String> ceiling(Principal p) {
        return flattenPermissions(roleNames(p));
    }

    /// Go `filterRolesForApplications`: which of `roleNames` a relying party
    /// scoped to `applicationIds` may see. Each name is resolved exactly
    /// (`findByName`), falling back to the app-local short name within the
    /// client's applications (SDK-synced assignments store the bare short
    /// name); unknown roles and platform roles (no application) are dropped,
    /// as are roles of other applications. The result is the role's
    /// **canonical** `{applicationCode}:{role}` name — narrowing decides which
    /// roles a client sees, never what they are called (`8d7ddbc`).
    public List<String> filterRolesForApplications(List<String> roleNames, List<String> applicationIds) {
        if (roleNames == null || roleNames.isEmpty() || applicationIds == null || applicationIds.isEmpty()) {
            return List.of();
        }
        var allowed = new HashSet<>(applicationIds);
        var out = new ArrayList<String>(roleNames.size());
        for (String name : roleNames) {
            Optional<Role> r = roles.findByName(name);
            if (r.isEmpty()) {
                r = roles.findByShortNameInApps(name, applicationIds);
            }
            if (r.isEmpty() || r.get().applicationId() == null) {
                continue;
            }
            if (allowed.contains(r.get().applicationId())) {
                out.add(r.get().name());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> roleNames(Principal p) {
        return p.roles().stream().map(RoleAssignment::role).toList();
    }
}
