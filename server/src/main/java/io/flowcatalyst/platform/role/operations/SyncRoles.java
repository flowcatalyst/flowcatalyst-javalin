package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.RoleSource;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleCreated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleDeleted;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleUpdated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RolesSynced;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Bulk-upserts one application's SDK role catalogue in one transaction
/// (spec §7.1): canonical names are `{applicationCode}:{lowercase short
/// name}`; only `SDK`-sourced rows are created, updated or removed —
/// `CODE` / `DATABASE` rows with a matching name are left untouched and not
/// counted; `removeUnlisted` prunes the application's unlisted `SDK` rows
/// but refuses the whole batch (`ROLE_HAS_ASSIGNMENTS`) when one of them is
/// still held by a principal. One per-row event per row touched plus one
/// [RolesSynced] rollup.
///
/// Authorization is resource-level here: the caller must have access to
/// the target application (the handler's coarse "may sync roles" gate and
/// the `{appCode}` resolution are separate).
public final class SyncRoles {

    private SyncRoles() {
    }

    public static Operation<SyncRolesCommand, RolesSynced> of(RoleRepository repo) {
        return Operation.<SyncRolesCommand, RolesSynced>named("SyncRoles")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED", "Application code is required");
                    if (cmd.roles().isEmpty()) {
                        throw UseCaseException.validation("ROLES_REQUIRED", "At least one role must be provided");
                    }
                })
                .authorize(cmd -> {
                    AuthContext ac = Auth.current();
                    if (ac == null) {
                        throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
                    }
                    if (!ac.canAccessApplication(cmd.applicationId())) {
                        throw UseCaseException.authorization("FORBIDDEN",
                                "Not authorised for application '" + cmd.applicationCode() + "'");
                    }
                })
                .execute((cmd, ec) -> {
                    Map<String, Role> existingByName = repo.findByApplicationId(cmd.applicationId()).stream()
                            .collect(Collectors.toMap(Role::name, Function.identity(), (a, _) -> a, LinkedHashMap::new));

                    var saves = new ArrayList<SyncSave<Role>>(cmd.roles().size());
                    var deletes = new ArrayList<SyncDelete<Role>>();
                    var syncedNames = new ArrayList<String>(cmd.roles().size());
                    int created = 0;
                    int updated = 0;
                    for (SyncRoleInput in : cmd.roles()) {
                        String shortName = Role.localName(in.name().toLowerCase(Locale.ROOT), cmd.applicationCode());
                        String name = cmd.applicationCode() + ":" + shortName;
                        syncedNames.add(name);
                        String displayName = in.displayName() != null ? in.displayName() : in.name();

                        Role existing = existingByName.get(name);
                        if (existing != null) {
                            if (existing.source() != RoleSource.SDK) {
                                continue; // never touch CODE / DATABASE rows
                            }
                            Role role = existing.syncedFromSdk(displayName, in.description(), in.permissions(), in.clientManaged());
                            saves.add(new SyncSave<>(role, RoleUpdated.of(ec, role)));
                            updated++;
                        } else {
                            Role role = Role.create(cmd.applicationCode(), shortName, displayName)
                                    .withApplicationId(cmd.applicationId())
                                    .withSource(RoleSource.SDK)
                                    .withDescription(in.description())
                                    .withPermissions(in.permissions())
                                    .withClientManaged(in.clientManaged());
                            saves.add(new SyncSave<>(role, RoleCreated.of(ec, role)));
                            created++;
                        }
                    }

                    if (cmd.removeUnlisted()) {
                        for (Role stale : existingByName.values()) {
                            if (stale.source() != RoleSource.SDK || syncedNames.contains(stale.name())) {
                                continue;
                            }
                            long held = repo.countAssignments(stale.name());
                            if (held > 0) {
                                throw UseCaseException.businessRule("ROLE_HAS_ASSIGNMENTS",
                                        "Cannot remove role '" + stale.name() + "' — " + held
                                                + " principal(s) still hold it. Strip the assignments before syncing.");
                            }
                            deletes.add(new SyncDelete<>(stale, RoleDeleted.of(ec, stale)));
                        }
                    }

                    var rollup = RolesSynced.ofApplication(ec, cmd.applicationCode(), created, updated, deletes.size(),
                            cmd.roles().size(), List.copyOf(syncedNames));
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }
}
