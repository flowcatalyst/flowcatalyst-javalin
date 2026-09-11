package io.flowcatalyst.platform.principal;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// The principal aggregate root (spec: `docs/spec/principal.md`): every
/// user and service account, with its tenancy scope, the two access axes
/// (client: home client + PARTNER grants; application: all-applications or
/// an explicit list) and, for users, the role assignments and credentials.
///
/// Immutable record: each transition returns a copy and throws
/// [UseCaseException] when an invariant is violated, so an operation is
/// just load → transition → event. Collections are defensively copied.
/// The repository persists whatever copy it is handed and stamps
/// `updatedAt` itself; junction tables are written only by the persister
/// the operation chose (spec §9).
///
/// @param id                       `prn_…` TSID
/// @param type                     `USER` | `SERVICE`
/// @param scope                    `ANCHOR` | `PARTNER` | `CLIENT`
/// @param clientId                 home client; `null` for anchors, partners, portal identities, service accounts
/// @param applicationId            owning application of a service principal (never set here)
/// @param name                     display name
/// @param active                   whether the principal may authenticate
/// @param userIdentity             the user half; `null` for service principals (spec §1)
/// @param serviceAccountId         the service-account row, `SERVICE` only
/// @param roles                    role assignments, in `assignedAt` order
/// @param assignedClients          PARTNER access-grant client ids
/// @param accessibleApplicationIds explicit application access
/// @param allApplications          access to every application, present and future
/// @param externalIdentity         federated identity, when provisioned by an IdP
/// @param createdAt                creation time
/// @param updatedAt                last change
public record Principal(
        String id,
        PrincipalType type,
        UserScope scope,
        String clientId,
        String applicationId,
        String name,
        boolean active,
        UserIdentity userIdentity,
        String serviceAccountId,
        List<RoleAssignment> roles,
        List<String> assignedClients,
        List<String> accessibleApplicationIds,
        boolean allApplications,
        ExternalIdentity externalIdentity,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    /// The client id sentinel meaning "every client" on the client-association route (spec §2).
    public static final String ANCHOR_CLIENT_WILDCARD = "*";

    public Principal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(name, "name");
        roles = roles == null ? List.of() : List.copyOf(roles);
        assignedClients = assignedClients == null ? List.of() : List.copyOf(assignedClients);
        accessibleApplicationIds = accessibleApplicationIds == null ? List.of() : List.copyOf(accessibleApplicationIds);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    // ── Factories (spec §1) ────────────────────────────────────────────────

    /// An active USER named after its email, no roles, no grants, all applications.
    public static Principal newUser(EmailAddress email, UserScope scope) {
        Instant now = Instant.now();
        return new Principal(EntityType.PRINCIPAL.generate(), PrincipalType.USER, scope, null, null, email.value(), true,
                UserIdentity.of(email.value()), null, List.of(), List.of(), List.of(), true, null, now, now);
    }

    /// An active, anchor-tier SERVICE principal for a service account, all applications.
    public static Principal newService(String serviceAccountId, String name) {
        Instant now = Instant.now();
        return new Principal(EntityType.PRINCIPAL.generate(), PrincipalType.SERVICE, UserScope.ANCHOR, null, null, name, true,
                null, Objects.requireNonNull(serviceAccountId, "serviceAccountId"), List.of(), List.of(), List.of(), true, null, now, now);
    }

    /// The token endpoint's view of a **portal identity** (`ptu_` subject,
    /// auth-identity §5.8): never persisted — a USER shape carrying the
    /// identity's id, e-mail and name so the identity token and id token
    /// mint the same way as for a principal, with nothing else attached.
    /// `updatedAt` is the identity's own, so the id_token's `updated_at`
    /// reports its real last modification rather than the mint time (the
    /// same rule as for principals: an RP watching it must not see a change
    /// on every login).
    public static Principal portalSubject(String identityId, String email, String name, Instant updatedAt) {
        Instant at = updatedAt == null ? Instant.now() : updatedAt;
        return new Principal(Objects.requireNonNull(identityId, "identityId"), PrincipalType.USER, UserScope.CLIENT, null, null,
                name == null ? "" : name, true, email == null ? null : UserIdentity.of(email), null, List.of(), List.of(), List.of(),
                false, null, at, at);
    }

    /// A portal identity: a USER the platform can authenticate but that is
    /// inert everywhere else — `CLIENT` scope with **no** client, no password,
    /// `allApplications = false`. What it may do is the portal app's business.
    public static Principal newPortalUser(EmailAddress email) {
        return newUser(email, UserScope.CLIENT).withAllApplications(false);
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public boolean isUser() {
        return type == PrincipalType.USER;
    }

    public boolean isService() {
        return type == PrincipalType.SERVICE;
    }

    /// The user's email, or `null` for a service principal.
    public String email() {
        return userIdentity == null ? null : userIdentity.email();
    }

    /// Provisioned by an IdP (has an external identity) or marked `OIDC`.
    public boolean isFederated() {
        return externalIdentity != null || (userIdentity != null && userIdentity.isOidc());
    }

    public boolean hasRole(String roleName) {
        return roles.stream().anyMatch(ra -> ra.role().equals(roleName));
    }

    /// Role names in assignment order (duplicates preserved as stored).
    public List<String> roleNames() {
        return roles.stream().map(RoleAssignment::role).toList();
    }

    public boolean hasDeveloperSecret() {
        return userIdentity != null && userIdentity.hasDeveloperSecret();
    }

    /// Home client or any granted client (the list filter's rule, spec §3).
    public boolean reachesClient(String id) {
        return id.equals(clientId) || assignedClients.contains(id);
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// `active = true` (idempotent — re-activating still produces a write + event, spec §2).
    public Principal activate() {
        return withActive(true);
    }

    /// `active = false` (idempotent).
    public Principal deactivate() {
        return withActive(false);
    }

    /// The fields the admin update may replace; `null` = leave untouched.
    /// `email` is not a change but an identity *assertion*: when present and
    /// non-blank it must equal the stored email.
    public record Changes(String name, Boolean active, String email) {
    }

    /// The admin update (spec §4): name (trimmed) and active are replaced when
    /// present; a differing email is refused.
    ///
    /// @throws UseCaseException validation `EMAIL_IMMUTABLE`
    public Principal update(Changes changes) {
        String asserted = EmailAddress.normalise(changes.email());
        if (!asserted.isEmpty() && !asserted.equals(EmailAddress.normalise(email()))) {
            throw UseCaseException.validation("EMAIL_IMMUTABLE",
                    "email cannot be changed here; it is the principal's identity");
        }
        return new Principal(id, type, scope, clientId, applicationId,
                changes.name() == null ? name : changes.name().trim(),
                changes.active() == null ? active : changes.active(),
                userIdentity, serviceAccountId, roles, assignedClients, accessibleApplicationIds, allApplications,
                externalIdentity, createdAt, Instant.now());
    }

    /// Stores an already-computed password hash on the user identity.
    ///
    /// @throws UseCaseException conflict `NOT_A_USER` for a principal without a user identity
    public Principal withPasswordHash(String hash) {
        return withIdentity(requireIdentity("Password reset only applies to USER principals").withPasswordHash(hash));
    }

    /// Marks the user as federated through `OIDC` and drops any password.
    public Principal asOidcUser() {
        return withIdentity(requireIdentity("Only USER principals can be federated").withPasswordHash(null).withProvider(UserIdentity.OIDC));
    }

    /// Records the provider that minted a portal identity (`null` keeps the default).
    public Principal withProvider(String provider) {
        if (provider == null || provider.isEmpty()) return this;
        return withIdentity(requireIdentity("Only USER principals have a provider").withProvider(provider));
    }

    /// Sets or rotates the encrypted developer client-secret, stamping its time.
    public Principal withDeveloperSecret(String encryptedRef) {
        Instant now = Instant.now();
        return withIdentity(requireIdentity("Developer credentials can only be set on USER type principals")
                .withDeveloperSecret(Objects.requireNonNull(encryptedRef, "encryptedRef"), now), now);
    }

    /// Clears the developer client-secret; a principal without one (or a service principal) is unchanged.
    public Principal clearDeveloperSecret() {
        if (userIdentity == null) return this;
        return withIdentity(userIdentity.withDeveloperSecret(null, null));
    }

    /// The outcome of a role-set change: the new aggregate, the resulting
    /// role names, and the names that entered / left the set.
    public record RolesChanged(Principal principal, List<String> roles, List<String> added, List<String> removed) {
        public RolesChanged {
            Objects.requireNonNull(principal, "principal");
            roles = List.copyOf(roles);
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }
    }

    /// The administrator's set: **every** assignment is replaced by `names`,
    /// all tagged `ADMIN_ASSIGNED` (a previously IdP/SDK-sourced row is
    /// adopted — spec §2, open question 1). `names` is reported verbatim.
    public RolesChanged assignRoles(List<String> names) {
        Instant now = Instant.now();
        List<RoleAssignment> assignments = names.stream()
                .map(n -> new RoleAssignment(n, RoleAssignment.ADMIN_ASSIGNED, now)).toList();
        List<String> previous = roleNames();
        return new RolesChanged(withRoles(assignments, now), names, difference(names, previous), difference(previous, names));
    }

    /// A sync's set: the assignments tagged `source` are replaced by `names`
    /// (tagged `source`, stamped now); every other assignment is kept, and a
    /// name already kept is not duplicated. Reports the resulting names.
    public RolesChanged syncSourcedRoles(String source, List<String> names) {
        Instant now = Instant.now();
        var kept = new ArrayList<RoleAssignment>(roles.size() + names.size());
        var keptNames = new LinkedHashSet<String>();
        for (RoleAssignment ra : roles) {
            if (ra.hasSource(source)) continue;
            kept.add(ra);
            keptNames.add(ra.role());
        }
        for (String n : names) {
            if (keptNames.add(n)) kept.add(new RoleAssignment(n, source, now));
        }
        List<String> previous = roleNames();
        List<String> current = kept.stream().map(RoleAssignment::role).toList();
        return new RolesChanged(withRoles(kept, now), current, difference(current, previous), difference(previous, current));
    }

    /// Whether any assignment carries `source`.
    public boolean hasRolesFrom(String source) {
        return roles.stream().anyMatch(ra -> ra.hasSource(source));
    }

    /// Drops every assignment tagged `source` (the sync's `removeUnlisted`).
    public Principal stripSourcedRoles(String source) {
        return withRoles(roles.stream().filter(ra -> !ra.hasSource(source)).toList(), Instant.now());
    }

    /// [#hasRolesFrom(String)] narrowed to one application's sync (spec
    /// X-02(c), ruled 2026-09-01): role names are app-prefixed `app:role`
    /// (see `RoleAssignment`/the role aggregate), so `applicationCode`
    /// selects only the assignments THAT sync could have produced. A blank
    /// or `null` `applicationCode` (the platform-level sync) matches every
    /// assignment tagged `source`, same as the single-argument form.
    public boolean hasRolesFrom(String source, String applicationCode) {
        return roles.stream().anyMatch(ra -> ra.hasSource(source) && belongsToApplicationSync(ra, applicationCode));
    }

    /// [#stripSourcedRoles(String)] narrowed to one application's sync (spec
    /// X-02(c)): a `removeUnlisted` sweep drops only THIS sync's own
    /// `source`-tagged, app-prefixed assignments — another application's
    /// `source`-tagged roles survive. A blank or `null` `applicationCode`
    /// strips every assignment tagged `source`, same as the single-argument form.
    public Principal stripSourcedRoles(String source, String applicationCode) {
        return withRoles(roles.stream().filter(ra -> !(ra.hasSource(source) && belongsToApplicationSync(ra, applicationCode))).toList(),
                Instant.now());
    }

    private static boolean belongsToApplicationSync(RoleAssignment ra, String applicationCode) {
        return applicationCode == null || applicationCode.isBlank() || ra.role().startsWith(applicationCode + ":");
    }

    /// The outcome of an application-access change.
    public record AccessChanged(Principal principal, List<String> added, List<String> removed) {
        public AccessChanged {
            Objects.requireNonNull(principal, "principal");
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }
    }

    /// Replaces the explicit application list; `allApplications` is set when
    /// non-null, otherwise left as is.
    public AccessChanged assignApplicationAccess(List<String> applicationIds, Boolean newAllApplications) {
        List<String> added = difference(applicationIds, accessibleApplicationIds);
        List<String> removed = difference(accessibleApplicationIds, applicationIds);
        var next = new Principal(id, type, scope, clientId, applicationId, name, active, userIdentity, serviceAccountId,
                roles, assignedClients, applicationIds, newAllApplications == null ? allApplications : newAllApplications,
                externalIdentity, createdAt, Instant.now());
        return new AccessChanged(next, added, removed);
    }

    /// The outcome of a client-association change: the new aggregate plus the
    /// client ids that must hold PARTNER grants afterwards (empty unless
    /// promoted to PARTNER).
    public record ClientAssociationChanged(Principal principal, List<String> grantClientIds) {
        public ClientAssociationChanged {
            Objects.requireNonNull(principal, "principal");
            grantClientIds = List.copyOf(grantClientIds);
        }
    }

    /// `ANCHOR`, no home client.
    public ClientAssociationChanged toAnchor() {
        return new ClientAssociationChanged(withScope(UserScope.ANCHOR, null), List.of());
    }

    /// `CLIENT` homed at `newClientId`.
    public ClientAssociationChanged changeClient(String newClientId) {
        return new ClientAssociationChanged(withScope(UserScope.CLIENT, Objects.requireNonNull(newClientId, "newClientId")), List.of());
    }

    /// `PARTNER`, no home client; the new client — and, when promoted from
    /// `CLIENT`, the old home client — become grants (spec §2).
    public ClientAssociationChanged toPartner(String newClientId) {
        Objects.requireNonNull(newClientId, "newClientId");
        var grants = new ArrayList<String>(2);
        if (scope == UserScope.CLIENT && clientId != null && !clientId.isEmpty() && !clientId.equals(newClientId)) {
            grants.add(clientId);
        }
        grants.add(newClientId);
        return new ClientAssociationChanged(withScope(UserScope.PARTNER, null), grants);
    }

    // ── Construction-time copies ───────────────────────────────────────────

    public Principal withClientId(String newClientId) {
        return new Principal(id, type, scope, newClientId, applicationId, name, active, userIdentity, serviceAccountId,
                roles, assignedClients, accessibleApplicationIds, allApplications, externalIdentity, createdAt, updatedAt);
    }

    /// Records the owning application of a service principal (spec:
    /// `docs/spec/fcdev-commands.md` §1 step 5 — a service account's
    /// principal is scoped to the application it was provisioned for).
    public Principal withApplicationId(String newApplicationId) {
        return new Principal(id, type, scope, clientId, newApplicationId, name, active, userIdentity, serviceAccountId,
                roles, assignedClients, accessibleApplicationIds, allApplications, externalIdentity, createdAt, updatedAt);
    }

    /// Replaces the display name; `null` or blank keeps the current one.
    public Principal withName(String newName) {
        if (newName == null || newName.isBlank()) return this;
        return new Principal(id, type, scope, clientId, applicationId, newName.trim(), active, userIdentity, serviceAccountId,
                roles, assignedClients, accessibleApplicationIds, allApplications, externalIdentity, createdAt, updatedAt);
    }

    public Principal withActive(boolean newActive) {
        return new Principal(id, type, scope, clientId, applicationId, name, newActive, userIdentity, serviceAccountId,
                roles, assignedClients, accessibleApplicationIds, allApplications, externalIdentity, createdAt, Instant.now());
    }

    public Principal withAllApplications(boolean newAllApplications) {
        return new Principal(id, type, scope, clientId, applicationId, name, active, userIdentity, serviceAccountId,
                roles, assignedClients, accessibleApplicationIds, newAllApplications, externalIdentity, createdAt, updatedAt);
    }

    /// A sync-built role set (all tagged by the caller), stamped now.
    public Principal withRoles(List<RoleAssignment> newRoles) {
        return withRoles(newRoles, Instant.now());
    }

    private Principal withRoles(List<RoleAssignment> newRoles, Instant now) {
        return new Principal(id, type, scope, clientId, applicationId, name, active, userIdentity, serviceAccountId,
                newRoles, assignedClients, accessibleApplicationIds, allApplications, externalIdentity, createdAt, now);
    }

    private Principal withScope(UserScope newScope, String newClientId) {
        return new Principal(id, type, newScope, newClientId, applicationId, name, active, userIdentity, serviceAccountId,
                roles, assignedClients, accessibleApplicationIds, allApplications, externalIdentity, createdAt, Instant.now());
    }

    private Principal withIdentity(UserIdentity identity) {
        return withIdentity(identity, Instant.now());
    }

    private Principal withIdentity(UserIdentity identity, Instant now) {
        return new Principal(id, type, scope, clientId, applicationId, name, active, identity, serviceAccountId,
                roles, assignedClients, accessibleApplicationIds, allApplications, externalIdentity, createdAt, now);
    }

    private UserIdentity requireIdentity(String message) {
        if (userIdentity == null) throw UseCaseException.conflict("NOT_A_USER", message);
        return userIdentity;
    }

    /// Members of `a` not in `b`, in `a`'s order (duplicates kept as in `a`).
    static List<String> difference(List<String> a, List<String> b) {
        Set<String> in = Set.copyOf(b);
        return a.stream().filter(x -> !in.contains(x)).toList();
    }
}
