package io.flowcatalyst.platform.scheduledjob.operations;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/// The input DTO for [UpdateScheduledJob] (audit `operation` =
/// `UpdateCommand`). Every field but `id` is optional: `null` = untouched.
/// A non-null `crons` must be non-empty; a JSON `null` payload clears it.
public record UpdateCommand(String id, String name, String description, List<String> crons, String timezone,
                            JsonNode payload, Boolean concurrent, Boolean tracksCompletion, Integer timeoutSeconds,
                            Integer deliveryMaxAttempts, String targetUrl) {
    public UpdateCommand {
        crons = crons == null ? null : List.copyOf(crons);
    }
}
