package io.flowcatalyst.platform.openapispecs.operations;

import tools.jackson.databind.JsonNode;

/// The input DTO for [SyncOpenApiSpec] (audit `operation` =
/// `SyncOpenApiSpecCommand`). `applicationId` is resolved by the caller from
/// `applicationCode` and is what the use case authorizes against; `spec` is
/// the raw document, validated by the operation (spec §2).
public record SyncOpenApiSpecCommand(String applicationId, String applicationCode, JsonNode spec) {
}
