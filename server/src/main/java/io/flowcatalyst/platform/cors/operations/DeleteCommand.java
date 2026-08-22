package io.flowcatalyst.platform.cors.operations;

/// The input DTO for [DeleteOrigin] (audit `operation` = `DeleteCommand`).
///
/// @param originId the `cor_…` TSID to remove
public record DeleteCommand(String originId) {
}
