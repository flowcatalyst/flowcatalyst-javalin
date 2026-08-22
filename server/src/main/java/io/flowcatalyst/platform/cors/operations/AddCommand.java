package io.flowcatalyst.platform.cors.operations;

/// The input DTO for [AddOrigin]. The record's simple name is the audit
/// log's `operation` column, so it must stay `AddCommand`.
///
/// @param origin      the browser origin, trimmed and validated by the operation (spec §4)
/// @param description optional free text
public record AddCommand(String origin, String description) {
}
