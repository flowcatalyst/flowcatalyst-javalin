package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [SetClientAssociation] (audit `operation` =
/// `SetClientAssociationCommand`): `clientId` is a client id or the anchor
/// wildcard `*`; `mode` is required for a specific client (`null` = not given).
public record SetClientAssociationCommand(String userId, String clientId, ClientAssociationMode mode) {
}
