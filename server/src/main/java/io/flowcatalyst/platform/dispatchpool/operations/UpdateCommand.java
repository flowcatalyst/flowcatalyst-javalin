package io.flowcatalyst.platform.dispatchpool.operations;

/// The input DTO for [UpdateDispatchPool] (audit `operation` = `UpdateCommand`).
/// Every setting is optional: `null` leaves the current value unchanged
/// (so a description cannot be cleared — spec open question 8).
public record UpdateCommand(String id, String name, String description, Integer rateLimit, Integer concurrency) {
}
