package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.role.RoleName;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.RoleSource;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleCreated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleDeleted;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleUpdated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RolesSynced;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
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
///
/// **Confinement** (S1.5, [Role]): a synced role may hold only this
/// application's permissions, 400 `PERMISSION_OUTSIDE_APPLICATION`, per role
/// as it is built. **The permission ceiling** ([RoleCeiling], Go F15 ruling 1,
/// the role-edit routes' rule): every `platform:` permission a synced role
/// gains or loses ([RoleCeiling#changed] of the stored and the sent list,
/// only when the sent list is non-empty), every permission of a new role and
/// of a role `removeUnlisted` deletes, is collected across the request and
/// checked once, after confinement and `ROLE_HAS_ASSIGNMENTS`: 403
/// `PERMISSION_ABOVE_CALLER`, the whole sync refused before anything is
/// written. A same-named `CODE`/`DATABASE` role is skipped, so not counted.
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
                    // Go F18: every role name and permission is shape-checked up front, so one
                    // malformed entry refuses the whole sync before any write.
                    for (SyncRoleInput in : cmd.roles()) {
                        RoleName.validate(cmd.applicationCode() + ":"
                                + Role.localName(in.name().toLowerCase(Locale.ROOT), cmd.applicationCode()));
                        RoleName.validatePermissions(in.permissions());
                    }
                })
                .authorize(cmd -> Checks.checkApplicationAccess(Auth.current(), cmd.applicationId(), cmd.applicationCode()))
                .execute((cmd, ec) -> {
                    Map<String, Role> existingByName = repo.findByApplicationId(cmd.applicationId()).stream()
                            .collect(Collectors.toMap(Role::name, Function.identity(), (a, _) -> a, LinkedHashMap::new));

                    // Go F18: a row of this application written without its id (the admin create
                    // used to leave it null) is invisible to the lookup above, so the sync would
                    // try to create it again and collide on the unique name. Adopt it: it is this
                    // application's role, give it its id.
                    for (SyncRoleInput in : cmd.roles()) {
                        String full = cmd.applicationCode() + ":"
                                + Role.localName(in.name().toLowerCase(Locale.ROOT), cmd.applicationCode());
                        if (existingByName.containsKey(full)) continue;
                        repo.findByName(full)
                                .filter(o -> o.applicationId() == null && o.owningApplicationCode().equals(cmd.applicationCode()))
                                .ifPresent(o -> existingByName.put(full, o.withApplicationId(cmd.applicationId())));
                    }

                    var saves = new ArrayList<SyncSave<Role>>(cmd.roles().size());
                    var deletes = new ArrayList<SyncDelete<Role>>();
                    var syncedNames = new ArrayList<String>(cmd.roles().size());
                    // The ceiling's input, in collection order (per role in payload
                    // order: added then removed; then the deleted roles').
                    var changed = new ArrayList<String>();
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
                            if (!in.permissions().isEmpty()) {
                                changed.addAll(RoleCeiling.changed(existing.permissions(), in.permissions()));
                            }
                            saves.add(new SyncSave<>(role, RoleUpdated.of(ec, role)));
                            updated++;
                        } else {
                            Role role = Role.create(cmd.applicationCode(), shortName, displayName)
                                    .withApplicationId(cmd.applicationId())
                                    .withSource(RoleSource.SDK)
                                    .withDescription(in.description())
                                    .withPermissions(in.permissions())
                                    .withClientManaged(in.clientManaged());
                            changed.addAll(in.permissions());
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
                            changed.addAll(stale.permissions());
                            deletes.add(new SyncDelete<>(stale, RoleDeleted.of(ec, stale)));
                        }
                    }
                    RoleCeiling.requirePermissions(Auth.current(), changed);

                    var rollup = RolesSynced.ofApplication(ec, cmd.applicationCode(), created, updated, deletes.size(),
                            cmd.roles().size(), List.copyOf(syncedNames));
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }
}
