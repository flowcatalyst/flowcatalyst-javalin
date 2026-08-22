package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.RoleSource;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleCreated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleDeleted;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleUpdated;
import io.flowcatalyst.platform.role.operations.RoleEvents.RolesSynced;
import io.flowcatalyst.platform.seed.RoleDefinition;
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
/// point (the anchor-only BFF sync) keeps its own gate.
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
                    int created = 0;
                    int updated = 0;
                    for (RoleDefinition def : defs) {
                        Optional<Role> existing = repo.findByName(def.name());
                        if (existing.isPresent()) {
                            Role current = existing.get();
                            if (current.source() != RoleSource.CODE) {
                                LOG.warn("role exists with non-CODE source; skipping platform-role sync role={} source={}",
                                        def.name(), current.source());
                                continue;
                            }
                            Role role = current.syncedFromCatalogue(def.displayName(), def.description(), def.permissions());
                            saves.add(new SyncSave<>(role, RoleUpdated.of(ec, role)));
                            updated++;
                        } else {
                            Role role = Role.create(def.applicationCode(), def.shortName(), def.displayName())
                                    .withDescription(def.description())
                                    .withPermissions(def.permissions())
                                    .withSource(RoleSource.CODE);
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
                            LOG.warn("stale CODE role still assigned to principals; refusing to remove role={} assignments={}",
                                    stale.name(), held);
                            continue;
                        }
                        deletes.add(new SyncDelete<>(stale, RoleDeleted.of(ec, stale)));
                    }

                    var rollup = RolesSynced.ofCatalogue(ec, created, updated, deletes.size(), defs.size());
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }
}
