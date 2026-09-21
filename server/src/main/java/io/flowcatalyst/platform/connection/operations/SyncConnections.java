package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionCode;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.ConnectionSource;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionCreated;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionDeleted;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionUpdated;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionsSynced;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Bulk-upserts an application's connection catalogue, scoped to
/// `(applicationCode, clientId)`, in one transaction (hand-off "Connection
/// sync (new)", spec `code-first-connections.md` §3). Rows are matched by
/// the normalised code among the connections owned by
/// `(applicationCode, clientId)` — `NULL` client matches `NULL` only
/// ([ConnectionRepository#findByApplicationAndClient]): `API`/`CODE`-sourced
/// matches are updated, `UI`-authored matches are skipped (still counted in
/// `syncedCodes`, never touched, no row created beside them), new codes are
/// created `API`-sourced, and with `removeUnlisted` the owned `API`/`CODE`
/// rows absent from the batch are hard-deleted — refusing the WHOLE sync
/// (nothing created, updated or deleted) with `409 CONNECTION_REFERENCED` if
/// ANY removal candidate is still referenced by a subscription; every
/// candidate is checked before any delete happens. The synced connection's
/// service account always follows the OWNING APPLICATION's provisioned
/// service account — never the caller's, on create AND on update; an
/// application with none fails the whole sync
/// (`400 APPLICATION_SERVICE_ACCOUNT_REQUIRED`). One per-row event per row
/// touched plus one [ConnectionsSynced] rollup.
///
/// Authorization: the caller must be able to access the application, and —
/// when `clientId` is given — the client too
/// ([Access#checkSyncAccess]). The coarse sync permission and the
/// `appCode`/`clientId` resolution belong to the sdksync handler.
public final class SyncConnections {

    private SyncConnections() {
    }

    public static Operation<SyncConnectionsCommand, ConnectionsSynced> of(
            ConnectionRepository repo, ApplicationRepository apps, SubscriptionRepository subscriptions) {
        return Operation.<SyncConnectionsCommand, ConnectionsSynced>named("SyncConnections")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED",
                            "Application code is required");
                    var seen = new HashSet<String>();
                    for (SyncConnectionInput in : cmd.connections()) {
                        ConnectionCode code = ConnectionCode.parse(in.code());
                        UseCaseException.requireNonBlank(in.name(), "NAME_REQUIRED", "Connection name is required");
                        if (!seen.add(code.value())) {
                            throw UseCaseException.validation("DUPLICATE_CODE",
                                    "Duplicate connection code '" + code.value() + "' in sync request");
                        }
                    }
                })
                .authorize(cmd -> Access.checkSyncAccess(cmd.applicationId(), cmd.applicationCode(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    // Order pinned (spec §3): application found → service account
                    // present → load owned rows → upsert → removal candidates →
                    // ALL reference checks before ANY delete.
                    Application app = apps.findById(cmd.applicationId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("Application", cmd.applicationId()));
                    if (app.serviceAccountId() == null || app.serviceAccountId().isBlank()) {
                        throw UseCaseException.validation("APPLICATION_SERVICE_ACCOUNT_REQUIRED",
                                "Application '" + cmd.applicationCode()
                                        + "' has no provisioned service account; connections cannot be synced without one");
                    }
                    String appServiceAccountId = app.serviceAccountId();

                    Map<String, Connection> existingByCode = repo.findByApplicationAndClient(cmd.applicationCode(), cmd.clientId()).stream()
                            .collect(Collectors.toMap(Connection::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));

                    var saves = new ArrayList<SyncSave<Connection>>(cmd.connections().size());
                    List<String> syncedCodes = new ArrayList<>(cmd.connections().size());
                    Set<String> syncedSet = new HashSet<>();
                    int created = 0;
                    int updated = 0;
                    for (SyncConnectionInput in : cmd.connections()) {
                        String code = ConnectionCode.parse(in.code()).value();
                        syncedCodes.add(code);
                        syncedSet.add(code);

                        Connection existing = existingByCode.get(code);
                        if (existing != null) {
                            if (!existing.source().isSyncManaged()) continue; // UI-authored rows are never touched
                            Connection c = existing
                                    .withName(in.name())
                                    .withDescription(in.description())
                                    .withExternalId(in.externalId())
                                    .withServiceAccountId(appServiceAccountId);
                            saves.add(new SyncSave<>(c, ConnectionUpdated.of(ec, c)));
                            updated++;
                        } else {
                            Connection c = Connection.create(ConnectionCode.parse(code), in.name(), appServiceAccountId)
                                    .withDescription(in.description())
                                    .withExternalId(in.externalId())
                                    .withClientId(cmd.clientId())
                                    .withApplicationCode(cmd.applicationCode())
                                    .withSource(ConnectionSource.API);
                            saves.add(new SyncSave<>(c, ConnectionCreated.of(ec, c)));
                            created++;
                        }
                    }

                    var deletes = new ArrayList<SyncDelete<Connection>>();
                    if (cmd.removeUnlisted()) {
                        List<Connection> candidates = existingByCode.values().stream()
                                .filter(c -> c.source().isSyncManaged() && !syncedSet.contains(c.code()))
                                .toList();
                        // ALL reference checks before ANY delete — one referenced
                        // candidate refuses the whole sync, nothing partially removed.
                        for (Connection candidate : candidates) {
                            List<String> refs = subscriptions.findCodesByConnectionId(candidate.id());
                            if (!refs.isEmpty()) {
                                throw UseCaseException.conflict("CONNECTION_REFERENCED",
                                        "Connection '" + candidate.code() + "' cannot be removed: still referenced by subscription(s) "
                                                + String.join(", ", refs));
                            }
                        }
                        for (Connection candidate : candidates) {
                            deletes.add(new SyncDelete<>(candidate, ConnectionDeleted.of(ec, candidate)));
                        }
                    }

                    var rollup = ConnectionsSynced.of(ec, cmd.applicationCode(), cmd.clientId(),
                            created, updated, deletes.size(), syncedCodes);
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }
}
