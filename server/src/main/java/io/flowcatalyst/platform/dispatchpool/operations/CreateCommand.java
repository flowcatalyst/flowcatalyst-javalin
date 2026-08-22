package io.flowcatalyst.platform.dispatchpool.operations;

/// The input DTO for [CreateDispatchPool]. The record's simple name is the
/// audit log's `operation` column, so it must stay `CreateCommand`.
///
/// @param code        raw code; trimmed and lowercased before validation (spec §4)
/// @param name        human-readable name; trimmed
/// @param description optional
/// @param rateLimit   messages per minute; `null` = no rate limiter
/// @param concurrency max concurrent dispatches; `null` = default 10
/// @param clientId    optional client scope; `null` means platform-wide
public record CreateCommand(String code, String name, String description, Integer rateLimit, Integer concurrency,
                            String clientId) {
}
