package io.flowcatalyst.platform.process.operations;

import java.util.List;

/// The input DTO for [UpdateProcess] (audit `operation` = `UpdateCommand`).
/// Every field but `id` is optional; `null` = untouched (spec §3).
public record UpdateCommand(String id, String name, String description, String body, String diagramType,
                            List<String> tags) {

    public UpdateCommand {
        tags = tags == null ? null : List.copyOf(tags);
    }
}
