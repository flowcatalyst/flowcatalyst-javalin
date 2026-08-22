package io.flowcatalyst.platform.application.operations;

/// The input DTO for [UpdateApplication] (audit `operation` = `UpdateCommand`).
/// PATCH semantics: a `null` field is left unchanged (spec §3).
public record UpdateCommand(String id, String name, String description, String iconUrl, String website,
                            String logo, String logoMimeType, String defaultBaseUrl) {
}
