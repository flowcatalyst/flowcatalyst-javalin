package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.PrincipalsSynced;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserCreated;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserUpdated;
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
/// and has its `SDK_SYNC` set replaced, name and active updated, a carried
/// password hash stored verbatim; a new principal is a CLIENT-scoped USER
/// with the `SDK_SYNC` roles. `removeUnlisted` never deletes — it strips,
/// from every USER absent from the payload, only the `SDK_SYNC` roles
/// belonging to **this sync's application** (spec X-02(c), ruled
/// 2026-09-01: role names are app-prefixed `app:role`, so an app-scoped sync
/// with `removeUnlisted` must not strip a different application's SDK_SYNC
/// roles from a principal that simply lacks a role from THIS payload) —
/// counted as "deactivated" in the rollup.
///
/// Authorization is mostly delegated to two entry points with different
/// gating — the app-scoped SDK sync (`CanSyncPrincipals` + per-application
/// access) and the platform-level `POST /api/principals/sync`
/// (`CanSyncPrincipals` only, no application); each keeps its own gate, and
/// users are global (matched by email) so there is no per-resource
/// dimension the operation itself could check — EXCEPT one (X-02(d)): a
/// `removeUnlisted` sweep with no `applicationCode` is a platform-wide sweep
/// (every application's `SDK_SYNC` roles are up for stripping), so that
/// combination alone is gated here, anchor-only, regardless of entry point.
public final class SyncPrincipals {

    private SyncPrincipals() {
    }

    public static Operation<SyncPrincipalsCommand, PrincipalsSynced> of(PrincipalRepository repo) {
        return Operation.<SyncPrincipalsCommand, PrincipalsSynced>named("SyncPrincipals")
                .validate(cmd -> {
                    if (cmd.principals().isEmpty()) {
                        throw UseCaseException.validation("PRINCIPALS_REQUIRED", "At least one principal must be provided");
                    }
                })
                .authorize(cmd -> {
                    if (!cmd.removeUnlisted() || (cmd.applicationCode() != null && !cmd.applicationCode().isBlank())) return;
                    AuthContext ac = Auth.current();
                    if (ac == null || (!ac.isAnchor() && !ac.isSuperAdmin())) {
                        throw UseCaseException.authorization("ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP",
                                "Only anchor users may sweep (removeUnlisted) principal roles with no application scope");
                    }
                })
                .execute((cmd, ec) -> {
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
                        if (existing != null) {
                            Principal p = existing.syncSourcedRoles(RoleAssignment.SDK_SYNC, roleNames).principal()
                                    .withName(in.name())
                                    .withActive(in.active());
                            if (in.hasPasswordHash()) p = p.withPasswordHash(in.passwordHash());
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
}
