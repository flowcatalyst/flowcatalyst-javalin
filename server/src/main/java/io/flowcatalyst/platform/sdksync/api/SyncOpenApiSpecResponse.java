package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.openapispecs.operations.OpenApiSpecEvents.ApplicationOpenApiSpecSynced;

/// The openapi sync's result (lockfile).
///
/// `status` is derived rather than carried: `"UNCHANGED"` when the spec hash
/// matched the current version, `"CURRENT"` otherwise. `archivedPriorVersion`
/// is `null` — and so omitted — when nothing was archived, which is how a
/// caller tells a first upload from a supersede.
public record SyncOpenApiSpecResponse(String applicationCode, String specId, String version, String status,
                                      String archivedPriorVersion, boolean hasBreaking, boolean unchanged) {

    static SyncOpenApiSpecResponse from(ApplicationOpenApiSpecSynced e) {
        return new SyncOpenApiSpecResponse(e.applicationCode(), e.specId(), e.version(),
                e.unchanged() ? "UNCHANGED" : "CURRENT",
                e.archivedPriorVersion(), e.hasBreaking(), e.unchanged());
    }
}
