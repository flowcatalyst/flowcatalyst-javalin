package io.flowcatalyst.platform.role.operations;

/// The (empty) input DTO for [SyncPlatformRoles]: the catalogue is static
/// code, not user input. The record exists so the audit log records
/// `SyncPlatformRolesCommand` alongside each per-row event.
public record SyncPlatformRolesCommand() {
}
