package io.flowcatalyst.platform.dispatchpool.operations;

import java.util.List;

/// The input DTO for [SyncDispatchPools] (audit `operation` = `SyncDispatchPoolsCommand`).
/// Pools are global (matched by code, not per application); `applicationId`
/// is what the use case authorizes against and `applicationCode` is carried
/// for event/audit provenance. `removeUnlisted` **archives** every pool not
/// in `pools` that is not already archived (spec §7).
public record SyncDispatchPoolsCommand(String applicationId, String applicationCode, List<SyncDispatchPoolInput> pools,
                                       boolean removeUnlisted) {

    public SyncDispatchPoolsCommand {
        pools = pools == null ? List.of() : List.copyOf(pools);
    }
}
