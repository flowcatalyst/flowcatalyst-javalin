package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.connection.operations.SyncConnectionInput;
import io.flowcatalyst.platform.connection.operations.SyncConnectionsCommand;

import java.util.List;

/// `SyncConnectionsRequest` (lockfile, `code-first-connections.md` §3):
/// `{clientId, connections[]: {code, name, description, externalId}}`.
/// `clientId` is the client's id OR its identifier slug — [SdkSyncApi]
/// resolves it to an id before this DTO builds its command.
public record SyncConnectionsRequest(String clientId, List<Input> connections) {

    public record Input(String code, String name, String description, String externalId) {
        SyncConnectionInput toInput() {
            return new SyncConnectionInput(code, name, description, externalId);
        }
    }

    SyncConnectionsCommand toCommand(String applicationId, String applicationCode, String resolvedClientId, boolean removeUnlisted) {
        return new SyncConnectionsCommand(applicationId, applicationCode, resolvedClientId,
                connections == null ? List.of() : connections.stream().map(Input::toInput).toList(), removeUnlisted);
    }
}
