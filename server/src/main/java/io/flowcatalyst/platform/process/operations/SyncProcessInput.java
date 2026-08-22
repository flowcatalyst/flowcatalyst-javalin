package io.flowcatalyst.platform.process.operations;

import java.util.List;

/// One process definition in a [SyncProcessesCommand] batch (the camelCase
/// wire shape lives in the sdksync API layer).
///
/// @param code        full `application:subdomain:process-name`
/// @param name        stored verbatim (spec §1, open question 6)
/// @param description optional; replaces the stored value (absent ⇒ none)
/// @param body        optional; absent ⇒ `""`
/// @param diagramType optional; blank ⇒ keep the stored / default value
/// @param tags        optional; absent ⇒ none (sync is declarative)
public record SyncProcessInput(String code, String name, String description, String body, String diagramType,
                               List<String> tags) {

    public SyncProcessInput {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
