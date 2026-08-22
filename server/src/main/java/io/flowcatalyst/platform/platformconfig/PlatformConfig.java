package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// One platform-config value (spec: `docs/spec/platformconfig.md` §1.1): a
/// system-wide setting addressed by its [ConfigCoordinate]. Immutable record;
/// the only transition is [#set], which returns a copy. The repository
/// persists whatever copy it is handed and stamps `updatedAt` itself.
///
/// @param id              `pcf_…` TSID
/// @param applicationCode owning application's code
/// @param section         setting group
/// @param property        setting name
/// @param scope           `GLOBAL` | `CLIENT`, always consistent with `clientId`
/// @param clientId        the client of a `CLIENT`-scoped value, `null` for `GLOBAL`
/// @param valueType       `PLAIN` | `SECRET`
/// @param value           the stored text (may be empty, never `null`)
/// @param description     optional description
/// @param createdAt       first set
/// @param updatedAt       last set
public record PlatformConfig(
        String id,
        String applicationCode,
        String section,
        String property,
        ConfigScope scope,
        String clientId,
        ConfigValueType valueType,
        String value,
        String description,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    /// The value type of a config that was never given one (spec §1.1).
    public static final ConfigValueType DEFAULT_VALUE_TYPE = ConfigValueType.PLAIN;

    /// What a non-anchor sees in place of a `SECRET` value (spec §1.1, §4).
    public static final String MASKED_VALUE = "***";

    public PlatformConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationCode, "applicationCode");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `PLAIN` value at `coordinate` (scope derived from it), no description.
    public static PlatformConfig create(ConfigCoordinate coordinate, String value) {
        Instant now = Instant.now();
        return new PlatformConfig(EntityType.PLATFORM_CONFIG.generate(), coordinate.applicationCode(),
                coordinate.section(), coordinate.property(), coordinate.scope(), coordinate.clientId(),
                DEFAULT_VALUE_TYPE, value, null, now, now);
    }

    /// The natural key this value lives at.
    public ConfigCoordinate coordinate() {
        return ConfigCoordinate.of(applicationCode, section, property, clientId);
    }

    public boolean isSecret() {
        return valueType == ConfigValueType.SECRET;
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// Replaces the value and the description (a `null` description clears
    /// it); replaces the value type only when one is given, otherwise keeps
    /// the current one.
    public PlatformConfig set(String newValue, ConfigValueType newValueType, String newDescription) {
        Objects.requireNonNull(newValue, "newValue");
        return new PlatformConfig(id, applicationCode, section, property, scope, clientId,
                newValueType == null ? valueType : newValueType, newValue, newDescription, createdAt, Instant.now());
    }

    /// A copy whose value is [#MASKED_VALUE] — the read side's view of a
    /// secret for non-anchors (spec §4). Not a transition: nothing else
    /// changes, and the copy is never persisted; *who* sees the masked copy
    /// is the Api's rule.
    public PlatformConfig masked() {
        return new PlatformConfig(id, applicationCode, section, property, scope, clientId,
                valueType, MASKED_VALUE, description, createdAt, updatedAt);
    }
}
