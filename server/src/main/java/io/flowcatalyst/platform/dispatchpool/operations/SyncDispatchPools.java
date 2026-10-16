package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolCode;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolArchived;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolCreated;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolUpdated;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolsSynced;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

/// Bulk-upserts an application SDK's dispatch pools in one transaction
/// (spec §7). Pools are matched by code over the **client-less** pools:
/// existing codes get name, description, rate limit and concurrency
/// replaced (status and client untouched), new codes are created
/// platform-wide, and with `removeUnlisted` every non-archived pool absent
/// from the batch is **archived** (never hard-deleted). One per-row event
/// per row touched plus one [DispatchPoolsSynced] rollup.
///
/// Ownership (Go F19). Pools are global rows matched by code, and the built-in
/// application-service role holds the sync permission, so each pool records the
/// application that owns it (`applicationId`; `null` for admin-created and
/// pre-ownership pools). An all-applications caller (a platform admin) may sync
/// any pool: new pools are created owned by the target application, unowned
/// pools it touches are handed to that application, an owned pool is never
/// re-assigned. Any other caller (an application service account, even at
/// anchor tier) may only create pools and change pools the target application
/// owns; a code that belongs to another application, to a client or to nobody
/// is refused `DISPATCH_POOL_NOT_OWNED`, all-or-nothing. `removeUnlisted`: a
/// non-anchor caller is refused `ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP`; an
/// all-applications anchor sweeps platform-wide; an anchor-tier
/// application-scoped caller archives only the pools its application owns.
///
/// A pool named in `cmd.protectedIds()` (a function's own pool,
/// `function-invocation.md` §4.2) is skipped entirely: not updated even if
/// this batch declares its code, and not archived by `removeUnlisted` —
/// neither counted. This operation never learns *why* a pool is protected;
/// it only ever sees the id set the sdksync handler hands it.
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
                .authorize(cmd -> {
                    Access.checkApplicationAccess(cmd.applicationId(), cmd.applicationCode());
                    // A removeUnlisted sweep is anchor-tier only (Go X-02(d)); what it may archive is
                    // narrowed in execute by pool ownership.
                    AuthContext caller = Auth.current();
                    if (cmd.removeUnlisted() && !caller.isAnchor() && !caller.isSuperAdmin()) {
                        throw UseCaseException.authorization("ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP",
                                "Only anchor users may sweep (removeUnlisted) dispatch pools — they are platform-global");
                    }
                })
                .execute((cmd, ec) -> {
                    List<DispatchPool> all = repo.findAll();
                    // The sync's pools are the client-less ones: (code, client_id) is unique, so a
                    // client's pool may share a code with a platform one and must never be mistaken for it.
                    Map<String, DispatchPool> existingByCode = all.stream()
                            .filter(p -> p.clientId() == null)
                            .collect(Collectors.toMap(DispatchPool::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));
                    Set<String> clientCodes = all.stream().filter(p -> p.clientId() != null)
                            .map(DispatchPool::code).collect(Collectors.toSet());
                    // An all-applications caller (a platform admin) may change any pool; every other
                    // caller only the pools the target application owns.
                    boolean allApps = Auth.current().allApplications();
                    Predicate<DispatchPool> owns = p -> cmd.applicationId() != null && cmd.applicationId().equals(p.applicationId());
                    Set<String> incomingCodes = cmd.pools().stream()
                            .map(SyncDispatchPoolInput::code).collect(Collectors.toSet());

                    var saves = new ArrayList<SyncSave<DispatchPool>>(cmd.pools().size());
                    int created = 0;
                    int updated = 0;
                    int archived = 0;
                    for (SyncDispatchPoolInput in : cmd.pools()) {
                        DispatchPool existing = existingByCode.get(in.code());
                        if (existing != null) {
                            if (cmd.protectedIds().contains(existing.id())) continue; // a function's own pool — untouched
                            if (!allApps && !owns.test(existing)) {
                                throw UseCaseException.authorization("DISPATCH_POOL_NOT_OWNED", "Dispatch pool '" + in.code()
                                        + "' is not owned by application '" + cmd.applicationCode()
                                        + "' (it belongs to another application or the platform); only a platform administrator may sync it");
                            }
                            DispatchPool p = applySettings(existing, in);
                            if (p.applicationId() == null) p = p.withApplicationId(cmd.applicationId());
                            saves.add(new SyncSave<>(p, DispatchPoolUpdated.of(ec, p)));
                            updated++;
                        } else {
                            if (!allApps && clientCodes.contains(in.code())) {
                                throw UseCaseException.authorization("DISPATCH_POOL_NOT_OWNED", "Dispatch pool code '" + in.code()
                                        + "' is not available to application '" + cmd.applicationCode() + "'");
                            }
                            DispatchPool p = applySettings(DispatchPool.create(in.code(), in.name())
                                    .withApplicationId(cmd.applicationId()), in);
                            saves.add(new SyncSave<>(p, DispatchPoolCreated.of(ec, p)));
                            created++;
                        }
                    }
                    if (cmd.removeUnlisted()) {
                        for (DispatchPool existing : existingByCode.values()) {
                            if (incomingCodes.contains(existing.code()) || existing.isArchived()) continue;
                            if (cmd.protectedIds().contains(existing.id())) continue; // a function's own pool — never archived by an SDK sync
                            if (!allApps && !owns.test(existing)) continue; // an application sweeps only its own pools
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
        if (DispatchPoolCode.problem(in.code()).isPresent()) {
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
