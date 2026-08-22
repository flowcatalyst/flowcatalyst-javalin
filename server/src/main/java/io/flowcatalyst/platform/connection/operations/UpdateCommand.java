package io.flowcatalyst.platform.connection.operations;

/// The input DTO for [UpdateConnection] (audit `operation` = `UpdateCommand`).
/// `description` and `externalId` are full replacements (`null` clears);
/// `status`, when present, is read leniently by `ConnectionStatus.parse`
/// (spec §4, open question 4) and `null` leaves the status alone.
public record UpdateCommand(String id, String name, String description, String externalId, String status) {
}
