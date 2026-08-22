package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.SubscriptionSource;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionCreated;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionDeleted;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionUpdated;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionsSynced;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Bulk-upserts an application SDK's subscription catalogue in one
/// transaction (spec §7). Rows are matched by code among the subscriptions
/// stamped with the application's code: `API`/`CODE`-sourced matches are
/// updated, `UI`-authored matches are skipped, new codes are created
/// `API`-sourced, and with `removeUnlisted` the `API`/`CODE` rows absent
/// from the batch are hard-deleted. One per-row event per row touched plus
/// one [SubscriptionsSynced] rollup.
///
/// Authorization is resource-level against the application the sync is
/// scoped to ([Access#checkApplicationAccess]); the coarse sync permission
/// and the `appCode → id` resolution belong to the sdksync handler.
public final class SyncSubscriptions {

    private SyncSubscriptions() {
    }

    public static Operation<SyncSubscriptionsCommand, SubscriptionsSynced> of(
            SubscriptionRepository repo, ConnectionRepository connections, DispatchPoolRepository pools) {
        return Operation.<SyncSubscriptionsCommand, SubscriptionsSynced>named("SyncSubscriptions")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED",
                            "Application code is required");
                    cmd.subscriptions().forEach(SyncSubscriptions::validateInput);
                })
                .authorize(cmd -> Access.checkApplicationAccess(cmd.applicationId(), cmd.applicationCode()))
                .execute((cmd, ec) -> {
                    // Every named connection must exist before anything is written (spec §6).
                    for (SyncSubscriptionInput in : cmd.subscriptions()) {
                        if (in.connectionId() != null && connections.findById(in.connectionId()).isEmpty()) {
                            throw UseCaseException.notFound("CONNECTION_NOT_FOUND",
                                    "Connection '" + in.connectionId() + "' not found");
                        }
                    }

                    Map<String, Subscription> existingByCode = repo.findByApplicationCode(cmd.applicationCode()).stream()
                            .collect(Collectors.toMap(Subscription::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));
                    Set<String> incomingCodes = cmd.subscriptions().stream()
                            .map(SyncSubscriptionInput::code).collect(Collectors.toSet());

                    var saves = new ArrayList<SyncSave<Subscription>>(cmd.subscriptions().size());
                    var deletes = new ArrayList<SyncDelete<Subscription>>();
                    int created = 0;
                    int updated = 0;
                    for (SyncSubscriptionInput in : cmd.subscriptions()) {
                        Subscription existing = existingByCode.get(in.code());
                        if (existing != null) {
                            if (!existing.source().isSyncManaged()) continue; // UI-authored rows are never touched
                            Subscription s = applyInput(existing, in, pools);
                            saves.add(new SyncSave<>(s, SubscriptionUpdated.of(ec, s)));
                            updated++;
                        } else {
                            Subscription s = applyInput(Subscription.create(in.code(), in.name(), in.target())
                                    .withApplicationCode(cmd.applicationCode())
                                    .withSource(SubscriptionSource.API)
                                    .withCreatedBy(ec.principalId()), in, pools);
                            saves.add(new SyncSave<>(s, SubscriptionCreated.of(ec, s)));
                            created++;
                        }
                    }
                    if (cmd.removeUnlisted()) {
                        existingByCode.values().stream()
                                .filter(s -> s.source().isSyncManaged() && !incomingCodes.contains(s.code()))
                                .forEach(s -> deletes.add(new SyncDelete<>(s, SubscriptionDeleted.of(ec, s))));
                    }

                    List<String> syncedCodes = cmd.subscriptions().stream().map(SyncSubscriptionInput::code).toList();
                    var rollup = SubscriptionsSynced.of(ec, cmd.applicationCode(), created, updated, deletes.size(), syncedCodes);
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }

    /// The sync row rules (spec §4): presence only — no code normalisation, no
    /// URL format check.
    private static void validateInput(SyncSubscriptionInput in) {
        UseCaseException.requireNonBlank(in.code(), "CODE_REQUIRED", "Subscription code is required");
        UseCaseException.requireNonBlank(in.name(), "NAME_REQUIRED", "Subscription name is required");
        UseCaseException.requireNonBlank(in.target(), "TARGET_REQUIRED", "Target endpoint URL is required");
        if (in.eventTypes().isEmpty()) {
            throw UseCaseException.validation("EVENT_TYPES_REQUIRED", "At least one event type is required");
        }
    }

    /// Writes a batch row onto a subscription (spec §7): name, description,
    /// endpoint, connection (cleared when absent), bindings and `dataOnly`
    /// are replaced; `maxRetries` / `timeoutSeconds` only when present; the
    /// dispatch pool is re-resolved only when a code is given; `mode` is
    /// deliberately ignored.
    private static Subscription applyInput(Subscription s, SyncSubscriptionInput in, DispatchPoolRepository pools) {
        Subscription out = s.withName(in.name())
                .withDescription(in.description())
                .withEndpoint(in.target())
                .withConnectionId(in.connectionId())
                .withEventTypes(in.eventTypes().stream().map(SyncEventTypeBindingInput::toBinding).toList())
                .withDataOnly(in.dataOnly());
        if (in.maxRetries() != null) out = out.withMaxRetries(in.maxRetries());
        if (in.timeoutSeconds() != null) out = out.withTimeoutSeconds(in.timeoutSeconds());
        if (in.dispatchPoolCode() != null && !in.dispatchPoolCode().isBlank()) {
            // An unknown code is silently left as is (spec open question 6).
            var pool = pools.findByCode(in.dispatchPoolCode(), null);
            if (pool.isPresent()) out = out.withDispatchPool(pool.get().id(), pool.get().code());
        }
        return out;
    }
}
