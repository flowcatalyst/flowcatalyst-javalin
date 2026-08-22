package io.flowcatalyst.platform.process.operations;

import java.util.List;

/// The input DTO for [CreateProcess]. The record's simple name is the audit
/// log's `operation` column, so it must stay `CreateCommand`.
///
/// @param code        `application:subdomain:process-name`
/// @param name        human-readable name (stored trimmed)
/// @param description optional
/// @param body        optional diagram source; absent ⇒ `""`
/// @param diagramType optional; absent or blank ⇒ `mermaid`
/// @param tags        optional; absent ⇒ none
public record CreateCommand(String code, String name, String description, String body, String diagramType,
                            List<String> tags) {

    public CreateCommand {
        tags = tags == null ? null : List.copyOf(tags);
    }
}
