package io.flowcatalyst.platform.dispatchpool.operations;

import java.util.List;
import java.util.Set;

/// The input DTO for [SyncDispatchPools] (audit `operation` = `SyncDispatchPoolsCommand`).
/// Pools are global (matched by code, not per application); `applicationId`
/// is what the use case authorizes against and `applicationCode` is carried
/// for event/audit provenance. `removeUnlisted` **archives** every pool not
/// in `pools` that is not already archived (spec §7).
///
/// @param protectedIds pool ids a function owns (`function-invocation.md`
///                      §4.2) — read from `TriggerObjectRepository` by the
///                      sdksync handler, never resolved here: this operation
///                      stays free of `fn_` knowledge. A protected pool is
///                      skipped entirely — neither updated by this sync's
///                      row nor archived by `removeUnlisted` — and not
///                      counted in either total.
public record SyncDispatchPoolsCommand(String applicationId, String applicationCode, List<SyncDispatchPoolInput> pools,
                                       boolean removeUnlisted, Set<String> protectedIds) {

    public SyncDispatchPoolsCommand {
        pools = pools == null ? List.of() : List.copyOf(pools);
        protectedIds = protectedIds == null ? Set.of() : Set.copyOf(protectedIds);
    }

    /// No protected ids — every caller but the sdksync handler (tests, and
    /// any future caller with nothing to protect).
    public SyncDispatchPoolsCommand(String applicationId, String applicationCode, List<SyncDispatchPoolInput> pools,
                                     boolean removeUnlisted) {
        this(applicationId, applicationCode, pools, removeUnlisted, Set.of());
    }
}
