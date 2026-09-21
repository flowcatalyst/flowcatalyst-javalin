package io.flowcatalyst.platform.connection.operations;

/// One connection definition in a [SyncConnections] payload (hand-off
/// "Connection sync (new)"). `code` is normalised (trimmed, lower-cased) and
/// format-validated by [SyncConnections] via
/// [io.flowcatalyst.platform.connection.ConnectionCode#parse] — this DTO
/// carries the raw wire value.
public record SyncConnectionInput(String code, String name, String description, String externalId) {
}
