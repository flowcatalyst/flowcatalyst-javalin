package io.flowcatalyst.platform.function;

import java.time.Instant;
import java.util.Objects;

/// One `fn_trigger_objects` row: a platform-managed object (a dispatch pool,
/// a subscription or a scheduled job) that a function's live manifest
/// created at promote (spec `function-invocation.md` §4). Not an aggregate —
/// a materialisation `TriggerSync` writes wholesale per function, the same
/// pattern as [FunctionRoute]. Its purpose is twofold: promote reconciles a
/// new manifest against these rows (create / update-if-different / delete
/// what the manifest no longer lists), and the two generic SDK syncs that
/// have no `source` column of their own (`SyncDispatchPools`,
/// `SyncScheduledJobs`) read [TriggerObjectRepository#objectIds] to skip a
/// function-owned object in their remove/archive sweep (spec §4.2).
///
/// @param functionId the owning function
/// @param kind        which kind of object this row names
/// @param objectId    the id of the row in its own table (`msg_subscriptions.id`,
///                    a dispatch pool's id, a scheduled job's id) — unique per kind
/// @param triggerKey  the manifest-derived key the primary key is scoped by
///                    (`fn-<fid>` for the one pool; `fn-<fid>-<8 hex sha256(...)>`
///                    for a subscription/schedule entry, spec §4)
/// @param createdAt   creation time
public record TriggerObject(String functionId, TriggerObjectKind kind, String objectId, String triggerKey,
                             Instant createdAt) {

    public TriggerObject {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(triggerKey, "triggerKey");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static TriggerObject of(String functionId, TriggerObjectKind kind, String objectId, String triggerKey,
                                    Instant now) {
        return new TriggerObject(functionId, kind, objectId, triggerKey, now);
    }
}
