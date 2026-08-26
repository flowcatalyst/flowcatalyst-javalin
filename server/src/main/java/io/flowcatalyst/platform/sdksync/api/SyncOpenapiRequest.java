package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.openapispecs.operations.SyncOpenApiSpecCommand;
import tools.jackson.databind.JsonNode;

/// `SyncOpenapiRequest` (lockfile): `{spec}`, any JSON.
///
/// Deliberately unvalidated here — the operation decides what a valid spec is
/// (`openapispecs.md` §3), and a transport-level shape check would either
/// duplicate that rule or disagree with it.
public record SyncOpenapiRequest(JsonNode spec) {

    SyncOpenApiSpecCommand toCommand(String applicationId, String applicationCode) {
        return new SyncOpenApiSpecCommand(applicationId, applicationCode, spec);
    }
}
