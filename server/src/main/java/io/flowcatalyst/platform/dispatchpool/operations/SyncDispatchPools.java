package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolCode;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolArchived;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolCreated;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolUpdated;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolsSynced;
import io.flowcatalyst.sdk.usecase.UseCaseException;
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

/// Bulk-upserts an application SDK's dispatch pools in one transaction
/// (spec §7). Pools are matched by code over **every** pool in the table:
/// existing codes get name, description, rate limit and concurrency
/// replaced (status and client untouched), new codes are created
/// platform-wide, and with `removeUnlisted` every non-archived pool absent
/// from the batch is **archived** (never hard-deleted). One per-row event
/// per row touched plus one [DispatchPoolsSynced] rollup.
///
/// Authorization is resource-level against the application the sync is
/// scoped to ([Access#checkApplicationAccess]); the coarse sync permission
/// and the `appCode → id` resolution belong to the sdksync handler.
public final class SyncDispatchPools {

    private SyncDispatchPools() {
    }

    public static Operation<SyncDispatchPoolsCommand, DispatchPoolsSynced> of(DispatchPoolRepository repo) {
        return Operation.<SyncDispatchPoolsCommand, DispatchPoolsSynced>named("SyncDispatchPools")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED",
                            "Application code is required");
                    cmd.pools().forEach(SyncDispatchPools::validateInput);
                })
                .authorize(cmd -> Access.checkApplicationAccess(cmd.applicationId(), cmd.applicationCode()))
                .execute((cmd, ec) -> {
                    Map<String, DispatchPool> existingByCode = repo.findAll().stream()
                            .collect(Collectors.toMap(DispatchPool::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));
                    Set<String> incomingCodes = cmd.pools().stream()
                            .map(SyncDispatchPoolInput::code).collect(Collectors.toSet());

                    var saves = new ArrayList<SyncSave<DispatchPool>>(cmd.pools().size());
                    int created = 0;
                    int updated = 0;
                    int archived = 0;
                    for (SyncDispatchPoolInput in : cmd.pools()) {
                        DispatchPool existing = existingByCode.get(in.code());
                        if (existing != null) {
                            DispatchPool p = applySettings(existing, in);
                            saves.add(new SyncSave<>(p, DispatchPoolUpdated.of(ec, p)));
                            updated++;
                        } else {
                            DispatchPool p = applySettings(DispatchPool.create(in.code(), in.name()), in);
                            saves.add(new SyncSave<>(p, DispatchPoolCreated.of(ec, p)));
                            created++;
                        }
                    }
                    if (cmd.removeUnlisted()) {
                        for (DispatchPool existing : existingByCode.values()) {
                            if (incomingCodes.contains(existing.code()) || existing.isArchived()) continue;
                            DispatchPool p = existing.archive();
                            saves.add(new SyncSave<>(p, DispatchPoolArchived.of(ec, p)));
                            archived++;
                        }
                    }

                    List<String> syncedCodes = cmd.pools().stream().map(SyncDispatchPoolInput::code).toList();
                    var rollup = DispatchPoolsSynced.of(ec, cmd.applicationCode(), created, updated, archived, syncedCodes);
                    return Plan.sync(repo, saves, List.of(), rollup);
                });
    }

    /// The sync row rules (spec §4): code as given, name, and the sync bounds.
    /// A bad code names the offending row, since the batch aborts on the
    /// first one.
    private static void validateInput(SyncDispatchPoolInput in) {
        try {
            DispatchPoolCode.parse(in.code());
        } catch (UseCaseException _) {
            throw UseCaseException.validation("INVALID_POOL_CODE", "Pool code '" + in.code()
                    + "' is invalid. Must start with lowercase letter, contain only lowercase alphanumeric, hyphens, underscores.");
        }
        UseCaseException.requireNonBlank(in.name(), "NAME_REQUIRED", "Pool name is required");
        Bounds.checkSync(in.rateLimit(), in.concurrency());
    }

    /// Replaces every setting from the row; an absent `concurrency` means the
    /// default, not "keep the current value" (spec §3, §7).
    private static DispatchPool applySettings(DispatchPool p, SyncDispatchPoolInput in) {
        return p.withName(in.name())
                .withDescription(in.description())
                .withRateLimit(in.rateLimit())
                .withConcurrency(in.concurrency() == null ? DispatchPool.DEFAULT_CONCURRENCY : in.concurrency());
    }
}
