package io.flowcatalyst.platform.connection.operations;

import java.util.List;

/// The input DTO for [SyncConnections] (audit `operation` =
/// `SyncConnectionsCommand`). `applicationId` is what the use case
/// authorizes against; `applicationCode` scopes the reconciliation (rows
/// stamped with it) and is carried for event provenance. `clientId` is
/// already resolved to an id by the handler — the wire accepts either the
/// client's id or its identifier slug (hand-off "Connection sync (new)") —
/// `null` scopes the sync to the application's shared, client-less
/// connections. `removeUnlisted` hard-deletes the owned `API`/`CODE`-sourced
/// rows of `(applicationCode, clientId)` that are not in `connections`;
/// `UI` rows are never touched by sync.
public record SyncConnectionsCommand(String applicationId, String applicationCode, String clientId,
                                     List<SyncConnectionInput> connections, boolean removeUnlisted) {

    public SyncConnectionsCommand {
        connections = connections == null ? List.of() : List.copyOf(connections);
    }
}
