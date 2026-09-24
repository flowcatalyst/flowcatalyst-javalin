package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.PrincipalsSynced;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserCreated;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserUpdated;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Upserts user principals declared by an application SDK (or the
/// platform-level user sync) in one transaction (spec §2, §7): matched by
/// normalised email; an existing principal keeps its non-`SDK_SYNC` roles
/// and has its `SDK_SYNC` set replaced, name and active updated; a new
/// principal is a CLIENT-scoped USER with the `SDK_SYNC` roles.
/// `removeUnlisted` never deletes — it strips, from every USER absent from
/// the payload, only the `SDK_SYNC` roles belonging to **this sync's
/// application** (spec X-02(c), ruled 2026-09-01: role names are
/// app-prefixed `app:role`, so an app-scoped sync with `removeUnlisted` must
/// not strip a different application's SDK_SYNC roles from a principal that
/// simply lacks a role from THIS payload) — counted as "deactivated" in the
/// rollup.
///
/// Authorization. The coarse "may sync principals" permission and (on the
/// app-scoped route) the per-application access check are the two entry
/// points' own (`POST /api/principals/sync`,
/// `POST /api/applications/{appCode}/principals/sync`). The operation holds
/// the per-resource rules both routes share (security-fixes S1.3):
///
///   - **Existing principals are touched only within reach.** A payload entry
///     whose email matches a principal the caller could not administer
///     ([Access#administers]: a non-anchor only reaches CLIENT-scope
///     principals of a client it can access) refuses the whole batch with
///     `SYNC_TARGET_FORBIDDEN`; the `removeUnlisted` sweep skips such
///     principals instead (it ranges over every user, so refusing would make
///     any non-anchor sweep impossible).
///   - **Role names must exist** (`UNKNOWN_ROLE`). On the app-scoped route
///     each must belong to the sync's application (`ROLE_APP_FORBIDDEN`),
///     whoever the caller is. On the platform route (no application) a
///     non-anchor may only name roles it could assign through
///     `PUT /api/principals/{id}/roles` — application roles of an application
///     the target's home client is entitled to ([Access#assignableRolesProblem];
///     a new principal has no client, so no role at all); an anchor, as there,
///     any existing role.
///   - **`passwordHash`** is stored verbatim on a principal the sync
///     creates; on an existing principal it is applied only when the caller
///     is a super-admin and otherwise ignored (the rest of the entry still
///     applies) — a hash is a credential for that account, and a sync must
///     not be a way to take over an account the caller merely reaches.
///     **Owner question** (security-fixes S1.3): whether a non-super-admin's
///     hash on an existing principal should instead be refused outright.
///   - A `removeUnlisted` sweep with no `applicationCode` is a platform-wide
///     sweep (every application's `SDK_SYNC` roles are up for stripping), so
///     that combination is gated here, anchor-only (X-02(d)).
public final class SyncPrincipals {

    private SyncPrincipals() {
    }

    /// @param roles         resolves the payload's role names (existence + owning application)
    /// @param clientConfigs the target client's entitled applications, for a non-anchor's platform-route sync
    public static Operation<SyncPrincipalsCommand, PrincipalsSynced> of(PrincipalRepository repo, RoleRepository roles,
                                                                        ClientConfigRepository clientConfigs) {
        return Operation.<SyncPrincipalsCommand, PrincipalsSynced>named("SyncPrincipals")
                .validate(cmd -> {
                    if (cmd.principals().isEmpty()) {
                        throw UseCaseException.validation("PRINCIPALS_REQUIRED", "At least one principal must be provided");
                    }
                })
                .authorize(cmd -> {
                    if (!cmd.removeUnlisted() || appScoped(cmd)) return;
                    AuthContext ac = Auth.current();
                    if (ac == null || (!ac.isAnchor() && !ac.isSuperAdmin())) {
                        throw UseCaseException.authorization("ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP",
                                "Only anchor users may sweep (removeUnlisted) principal roles with no application scope");
                    }
                })
                .execute((cmd, ec) -> {
                    AuthContext ac = Auth.current();
                    boolean mayReplaceHashes = ac != null && ac.isSuperAdmin();
                    Instant now = Instant.now();
                    var saves = new ArrayList<SyncSave<Principal>>(cmd.principals().size());
                    var syncedEmails = new ArrayList<String>(cmd.principals().size());
                    int created = 0;
                    int updated = 0;
                    int deactivated = 0;

                    for (SyncPrincipalInput in : cmd.principals()) {
                        String email = EmailAddress.normalise(in.email());
                        syncedEmails.add(email);
                        List<String> roleNames = in.roles().stream().map(r -> r.toLowerCase(Locale.ROOT)).toList();

                        Principal existing = repo.findByEmail(email).orElse(null);
                        if (existing != null && !Access.administers(ac, existing)) {
                            throw UseCaseException.authorization("SYNC_TARGET_FORBIDDEN",
                                    "Not authorised to manage principal '" + email + "'");
                        }
                        requireSyncableRoles(roles, clientConfigs, ac, cmd, roleNames, existing);

                        if (existing != null) {
                            Principal p = existing.syncSourcedRoles(RoleAssignment.SDK_SYNC, roleNames).principal()
                                    .withName(in.name())
                                    .withActive(in.active());
                            if (in.hasPasswordHash() && mayReplaceHashes) p = p.withPasswordHash(in.passwordHash());
                            saves.add(new SyncSave<>(p, UserUpdated.of(ec, p)));
                            updated++;
                        } else {
                            Principal p = Principal.newUser(EmailAddress.parse(email), UserScope.CLIENT)
                                    .withName(in.name())
                                    .withActive(in.active())
                                    .withRoles(roleNames.stream().map(r -> new RoleAssignment(r, RoleAssignment.SDK_SYNC, now)).toList());
                            if (in.hasPasswordHash()) p = p.withPasswordHash(in.passwordHash());
                            saves.add(new SyncSave<>(p, UserCreated.of(ec, p)));
                            created++;
                        }
                    }

                    if (cmd.removeUnlisted()) {
                        Set<String> synced = new HashSet<>(syncedEmails);
                        for (Principal pr : repo.findAll()) {
                            if (!pr.isUser() || pr.email() == null) continue;
                            if (synced.contains(EmailAddress.normalise(pr.email()))) continue;
                            // S1.3: the sweep never reaches past the caller's reach.
                            if (!Access.administers(ac, pr)) continue;
                            // X-02(c): only THIS sync's own application's SDK_SYNC
                            // roles are in scope — another application's survive.
                            if (!pr.hasRolesFrom(RoleAssignment.SDK_SYNC, cmd.applicationCode())) continue;
                            Principal stripped = pr.stripSourcedRoles(RoleAssignment.SDK_SYNC, cmd.applicationCode());
                            saves.add(new SyncSave<>(stripped, UserUpdated.of(ec, stripped)));
                            deactivated++;
                        }
                    }

                    var rollup = PrincipalsSynced.of(ec, cmd.applicationCode(), created, updated, deactivated, syncedEmails);
                    return Plan.sync(repo.withRoles(), saves, List.<SyncDelete<Principal>>of(), rollup);
                });
    }

    private static boolean appScoped(SyncPrincipalsCommand cmd) {
        return cmd.applicationCode() != null && !cmd.applicationCode().isBlank();
    }

    /// The role rule of the class doc: every name exists; app-scoped → each
    /// belongs to the sync's application; platform route + non-anchor → each
    /// is assignable to `target` (`null` = a principal this sync creates).
    ///
    /// @throws UseCaseException validation `UNKNOWN_ROLE`; authorization
    ///                          `ROLE_APP_FORBIDDEN` | `PLATFORM_ROLE_FORBIDDEN`
    private static void requireSyncableRoles(RoleRepository roles, ClientConfigRepository clientConfigs, AuthContext ac,
                                             SyncPrincipalsCommand cmd, List<String> roleNames, Principal target) {
        for (String name : roleNames) {
            Role role = roles.findByName(name)
                    .orElseThrow(() -> UseCaseException.validation("UNKNOWN_ROLE", "role not found: " + name));
            if (appScoped(cmd) && !role.owningApplicationCode().equals(cmd.applicationCode())) {
                throw UseCaseException.authorization("ROLE_APP_FORBIDDEN",
                        "role '" + name + "' does not belong to application '" + cmd.applicationCode() + "'");
            }
        }
        if (!appScoped(cmd) && (ac == null || !ac.isAnchor())) {
            Set<String> allowed = Access.clientApplicationIds(clientConfigs, target == null ? null : target.clientId());
            Access.assignableRolesProblem(roles, roleNames, allowed).ifPresent(problem -> {
                throw new UseCaseException(problem);
            });
        }
    }
}
