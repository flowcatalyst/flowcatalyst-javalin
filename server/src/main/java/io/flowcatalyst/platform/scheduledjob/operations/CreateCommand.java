package io.flowcatalyst.platform.scheduledjob.operations;

import tools.jackson.databind.JsonNode;

import java.util.List;

/// The input DTO for [CreateScheduledJob]. The record's simple name is the
/// audit log's `operation` column, so it must stay `CreateCommand`.
///
/// @param code                raw code (normalised by `ScheduledJobCode.parse`)
/// @param name                human-readable name
/// @param crons               ≥ 1 six-field cron expressions
/// @param timezone            IANA zone; `null`/blank ⇒ `UTC`
/// @param clientId            optional client scope; `null` = platform-scoped
/// @param applicationId       optional owning application
/// @param description         optional
/// @param payload             optional JSON delivered with each firing
/// @param concurrent          consumer-owned flag
/// @param tracksCompletion    whether firings wait for a completion callback
/// @param timeoutSeconds      optional
/// @param deliveryMaxAttempts `null` ⇒ 3
/// @param targetUrl           optional
public record CreateCommand(String code, String name, List<String> crons, String timezone, String clientId,
                            String applicationId, String description, JsonNode payload, boolean concurrent,
                            boolean tracksCompletion, Integer timeoutSeconds, Integer deliveryMaxAttempts,
                            String targetUrl) {
    public CreateCommand {
        crons = crons == null ? null : List.copyOf(crons);
    }
}
