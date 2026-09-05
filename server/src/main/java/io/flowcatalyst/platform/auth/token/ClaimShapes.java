package io.flowcatalyst.platform.auth.token;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.shared.auth.ScopeClaim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The authority list-claims a token carries, built from a principal
/// (`docs/spec/auth-core.md` §3.1; Go `authservice.buildClients`,
/// `appAccessOf`, `oauthapi.intersectApps`, `confineToClient`).
///
/// Both `clients` and `applications` are **`"{id}:{label}"` pairs**, or the
/// single `"*"` sentinel, so a consumer can read the human-meaningful half
/// without a lookup. The label maps come from the store at mint time
/// ([ClaimLabels]); an id whose label is unknown degrades to the bare id
/// rather than being dropped, because dropping it would silently narrow the
/// principal's access. [ScopeClaim] splits the pair exactly once, where a
/// token becomes an `AuthContext` — nothing else in the platform reasons in
/// pairs.
public final class ClaimShapes {

    /// "every one" — an anchor's `clients`, an all-applications principal's `applications`.
    public static final String WILDCARD = ScopeClaim.WILDCARD;

    private ClaimShapes() {
    }

    /// Go `buildClients`: anchor → `["*"]`; partner → every assigned client;
    /// client-scoped → the home client (empty when it has none). Each id is
    /// paired with its identifier when `identifiers` knows it.
    public static List<String> clients(Principal p, Map<String, String> identifiers) {
        Objects.requireNonNull(p, "principal");
        return switch (p.scope()) {
            case ANCHOR -> List.of(WILDCARD);
            case PARTNER -> pairs(p.assignedClients(), identifiers);
            case CLIENT -> p.clientId() == null ? List.of() : pairs(List.of(p.clientId()), identifiers);
        };
    }

    /// Go `appAccessOf`: `["*"]` when the principal reaches every application,
    /// else `"{id}:{code}"` for each explicit binding.
    public static List<String> applications(Principal p, Map<String, String> codes) {
        Objects.requireNonNull(p, "principal");
        return applications(p.accessibleApplicationIds(), p.allApplications(), codes);
    }

    /// The `applications` claim for an explicit id set — the confined form a
    /// relying party sees ([#confineToClient]).
    public static List<String> applications(List<String> applicationIds, boolean allApplications, Map<String, String> codes) {
        if (allApplications) {
            return List.of(WILDCARD);
        }
        return pairs(applicationIds, codes);
    }

    /// The principal's assigned role names, in assignment order.
    public static List<String> roleNames(Principal p) {
        return p.roles().stream().map(RoleAssignment::role).toList();
    }

    /// Go `intersectApps`: the client's application ids the user can actually
    /// reach — all of them when the user holds every application, else the
    /// intersection with the user's explicit bindings, in the client's order.
    public static List<String> intersectApps(Principal p, List<String> clientApplicationIds) {
        Objects.requireNonNull(p, "principal");
        if (p.allApplications()) {
            return List.copyOf(clientApplicationIds);
        }
        var mine = new HashSet<>(p.accessibleApplicationIds());
        return clientApplicationIds.stream().filter(mine::contains).toList();
    }

    /// What a relying party is entitled to know about the principal: the
    /// application scope confined to the client's own applications, and the
    /// roles narrowed to those applications (Go `confineToClient`, `7bce06c`).
    ///
    /// @param applicationIds  the confined application ids (never the wildcard)
    /// @param roles           the narrowed role names — always canonical
    ///                        `{applicationCode}:{role}` spellings (`8d7ddbc`)
    public record Confinement(List<String> applicationIds, List<String> roles) {
        public Confinement {
            applicationIds = List.copyOf(applicationIds);
            roles = List.copyOf(roles);
        }

        /// `all_applications` is forced off on a confined view.
        public boolean allApplications() {
            return false;
        }
    }

    /// Confines `p` to `clientApplicationIds`; `filteredRoles` is the result of
    /// the store-backed role narrowing the caller already ran (the resolver's
    /// `filterRolesForApplications`), passed in so this stays pure.
    public static Confinement confineToClient(Principal p, List<String> clientApplicationIds, List<String> filteredRoles) {
        return new Confinement(intersectApps(p, clientApplicationIds), filteredRoles);
    }

    /// `"{id}:{label}"`, or the bare id when the label is unknown or blank.
    static List<String> pairs(List<String> ids, Map<String, String> labels) {
        var out = new ArrayList<String>(ids.size());
        for (String id : ids) {
            String label = labels == null ? null : labels.get(id);
            out.add(label == null || label.isBlank() ? id : id + ":" + label);
        }
        return List.copyOf(out);
    }
}
