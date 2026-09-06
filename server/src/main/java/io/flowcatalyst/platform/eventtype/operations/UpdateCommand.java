package io.flowcatalyst.platform.eventtype.operations;

/// The input DTO for [UpdateEventType] (audit `operation` = `UpdateCommand`).
///
/// `clientScoped`: `null` leaves the stored value unchanged (owner ruling
/// 2026-09-06 #7, the same rule as Go's update).
public record UpdateCommand(String id, String name, String description, Boolean clientScoped) {

    public UpdateCommand(String id, String name, String description) {
        this(id, name, description, null);
    }
}
