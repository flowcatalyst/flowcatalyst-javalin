package io.flowcatalyst.platform.platformconfig;

import java.util.Objects;

/// The natural key of a platform-config value (spec §1.1):
/// `(applicationCode, section, property, clientId?)` with the scope derived —
/// a client id present means `CLIENT`, absent means `GLOBAL`. One place
/// encodes that derivation; the repository lookup, the set operation and the
/// single-property routes all build a coordinate rather than passing a scope
/// and a client id separately.
///
/// @param applicationCode the owning application's code
/// @param section         setting group
/// @param property        setting name
/// @param clientId        the client for a `CLIENT`-scoped value; `null` for `GLOBAL`
public record ConfigCoordinate(String applicationCode, String section, String property, String clientId) {

    public ConfigCoordinate {
        Objects.requireNonNull(applicationCode, "applicationCode");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(property, "property");
    }

    /// The platform-wide value of `app/section/property`.
    public static ConfigCoordinate global(String applicationCode, String section, String property) {
        return new ConfigCoordinate(applicationCode, section, property, null);
    }

    /// `global(…)` when `clientId` is `null`, the client's value otherwise.
    public static ConfigCoordinate of(String applicationCode, String section, String property, String clientId) {
        return new ConfigCoordinate(applicationCode, section, property, clientId);
    }

    /// `CLIENT` when bound to a client, `GLOBAL` otherwise.
    public ConfigScope scope() {
        return clientId == null ? ConfigScope.GLOBAL : ConfigScope.CLIENT;
    }

    /// `app/section/property` — how a coordinate is named in messages.
    public String path() {
        return applicationCode + "/" + section + "/" + property;
    }
}
