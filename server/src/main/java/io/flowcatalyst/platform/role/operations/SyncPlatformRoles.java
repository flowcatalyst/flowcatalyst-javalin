package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.RoleSource;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleCreated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleDeleted;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleUpdated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RolesSynced;
import io.flowcatalyst.platform.seed.RoleDefinition;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// Upserts the built-in `CODE` catalogue (`seed.PlatformRoles.all()`, or any
/// list of [RoleDefinition]s) in one transaction (spec §7.2): catalogue
/// entries whose name exists as a `CODE` role are refreshed wholesale,
/// entries colliding with a non-`CODE` role are skipped with a warning, new
/// entries are created `CODE`-sourced, and `CODE` rows absent from the
/// catalogue are swept — unless a principal still holds them, in which case
/// they are kept with a warning (unlike the SDK sync, which refuses). One
/// per-row event per row touched plus one [RolesSynced] rollup.
///
/// Authorization is [Operation.Authorize#publicAccess()]: the catalogue is
/// static code with no client or application dimension; the sole entry
/// point (the anchor-only BFF sync) keeps its own gate. The boot-time seed
/// ([io.flowcatalyst.platform.seed.Seeder]) does not run this operation: it
/// writes the catalogue directly and acts for no caller.
///
/// **Confinement** (S1.5, [Role]): a catalogue role may hold only its own
/// application's permissions, 400 `PERMISSION_OUTSIDE_APPLICATION`, per role
/// as it is built, with no super-admin exception. **The permission ceiling**
/// ([RoleCeiling], Go F10 `c56a68c`): every `platform:` permission the sync
/// adds to or removes from a role ([RoleCeiling#changed] of the stored and
/// the catalogue's list for a refreshed `CODE` role, every permission of a
/// new role and of a swept stale one) is collected across the catalogue and
/// checked once, after confinement and the sweep: 403
/// `PERMISSION_ABOVE_CALLER`, the whole sync refused before anything is
/// written. A same-named non-`CODE` role and a stale role still assigned are
/// skipped, so not counted.
public final class SyncPlatformRoles {

    private static final Logger LOG = LoggerFactory.getLogger(SyncPlatformRoles.class);

    private SyncPlatformRoles() {
    }

    public static Operation<SyncPlatformRolesCommand, RolesSynced> of(RoleRepository repo, List<RoleDefinition> catalogue) {
        List<RoleDefinition> defs = List.copyOf(catalogue);
        return Operation.<SyncPlatformRolesCommand, RolesSynced>named("SyncPlatformRoles")
                .authorize(Operation.Authorize.publicAccess())
                .execute((_, ec) -> {
                    var saves = new ArrayList<SyncSave<Role>>(defs.size());
                    var deletes = new ArrayList<SyncDelete<Role>>();
                    // The ceiling's input, in collection order (per catalogue role:
                    // added then removed, or a new role's all; then the swept roles').
                    var changed = new ArrayList<String>();
                    int created = 0;
                    int updated = 0;
                    for (RoleDefinition def : defs) {
                        Optional<Role> existing = repo.findByName(def.name());
                        if (existing.isPresent()) {
                            Role current = existing.get();
                            if (current.source() != RoleSource.CODE) {
                                LOG.atWarn().setMessage("role exists with non-CODE source; skipping platform-role sync")
                                        .addKeyValue("role", def.name())
                                        .addKeyValue("source", current.source())
                                        .log();
                                continue;
                            }
                            Role role = current.syncedFromCatalogue(def.displayName(), def.description(), def.permissions());
                            // Go F18: heal a row written without its application's id.
                            if (role.applicationId() == null) {
                                role = role.withApplicationId(ownerApplicationId(repo, role.owningApplicationCode()));
                            }
                            changed.addAll(RoleCeiling.changed(current.permissions(), def.permissions()));
                            saves.add(new SyncSave<>(role, RoleUpdated.of(ec, role)));
                            updated++;
                        } else {
                            Role role = Role.create(def.applicationCode(), def.shortName(), def.displayName())
                                    .withApplicationId(ownerApplicationId(repo, def.applicationCode()))
                                    .withDescription(def.description())
                                    .withPermissions(def.permissions())
                                    .withSource(RoleSource.CODE);
                            changed.addAll(def.permissions());
                            saves.add(new SyncSave<>(role, RoleCreated.of(ec, role)));
                            created++;
                        }
                    }

                    // Stale CODE rows: in the database, absent from the catalogue.
                    Set<String> catalogueNames = defs.stream().map(RoleDefinition::name).collect(Collectors.toSet());
                    for (Role stale : repo.findBySource(RoleSource.CODE)) {
                        if (catalogueNames.contains(stale.name())) {
                            continue;
                        }
                        long held = repo.countAssignments(stale.name());
                        if (held > 0) {
                            LOG.atWarn().setMessage("stale CODE role still assigned to principals; refusing to remove")
                                    .addKeyValue("role", stale.name())
                                    .addKeyValue("assignments", held)
                                    .log();
                            continue;
                        }
                        changed.addAll(stale.permissions());
                        deletes.add(new SyncDelete<>(stale, RoleDeleted.of(ec, stale)));
                    }
                    RoleCeiling.requirePermissions(Auth.current(), changed);

                    var rollup = RolesSynced.ofCatalogue(ec, created, updated, deletes.size(), defs.size());
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }

    /// The id a role of application `code` carries (Go F18). An application that
    /// does not exist yields `null` with a warning rather than an error: the
    /// built-in catalogue must still be installable on a database whose platform
    /// application row has not been seeded yet.
    private static String ownerApplicationId(RoleRepository repo, String code) {
        return repo.applicationIdByCode(code).orElseGet(() -> {
            LOG.atWarn().setMessage("role written without an application id: no such application")
                    .addKeyValue("applicationCode", code)
                    .log();
            return null;
        });
    }
}
