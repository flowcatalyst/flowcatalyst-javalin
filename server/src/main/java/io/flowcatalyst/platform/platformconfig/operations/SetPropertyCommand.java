package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;

/// The input DTO for [SetProperty] (audit `operation` = `SetPropertyCommand`).
///
/// @param applicationCode the application the setting belongs to
/// @param section         setting group
/// @param property        setting name
/// @param value           the text to store (empty allowed, absent is not)
/// @param valueType       `PLAIN` | `SECRET`; `null` keeps the current type (`PLAIN` on first set)
/// @param description     optional; `null` clears
/// @param clientId        the client for a `CLIENT`-scoped value; `null` means `GLOBAL`
public record SetPropertyCommand(String applicationCode, String section, String property, String value,
                                 String valueType, String description, String clientId) {

    /// The coordinate this command addresses (scope derived from `clientId`).
    public ConfigCoordinate coordinate() {
        return ConfigCoordinate.of(applicationCode, section, property, clientId);
    }
}
