package io.flowcatalyst.platform.connection.operations;

/// The input DTO for [UpdateConnection] (audit `operation` = `UpdateCommand`).
/// `description` and `externalId` are full replacements (`null` clears);
/// `status`, when present, is read leniently by `ConnectionStatus.parse`
/// (spec §4, open question 4) and `null` leaves the status alone.
///
/// @param applicationCode set-if-provided, never cleared (spec
///                        `code-first-connections.md` §3): `null` leaves the
///                        current owner alone; a non-`null` value must name
///                        an existing application the caller can access, and
///                        re-runs the duplicate check only when it actually
///                        changes the row's key
public record UpdateCommand(String id, String name, String description, String externalId, String status,
                            String applicationCode) {
}
