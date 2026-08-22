package io.flowcatalyst.platform.application.operations;

import java.util.List;

/// The input DTO for [UpdateClientApplications] (audit `operation` =
/// `UpdateClientApplicationsCommand`): `enabledApplicationIds` is the
/// client's desired final enabled set (spec §7).
public record UpdateClientApplicationsCommand(String clientId, List<String> enabledApplicationIds) {

    public UpdateClientApplicationsCommand {
        enabledApplicationIds = enabledApplicationIds == null ? List.of() : List.copyOf(enabledApplicationIds);
    }
}
