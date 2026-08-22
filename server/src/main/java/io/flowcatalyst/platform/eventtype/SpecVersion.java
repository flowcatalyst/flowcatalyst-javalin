package io.flowcatalyst.platform.eventtype;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.tsid.EntityType;

import java.time.Instant;
import java.util.Objects;

/// One schema version of an event type (a `msg_event_type_spec_versions`
/// row). Immutable; the state transitions live on [EventType], which owns
/// the list and the cross-version rules.
///
/// @param id            `sch_…` TSID
/// @param eventTypeId   owning event type
/// @param version       the schema version string, typically semver (`1.0`)
/// @param mimeType      `application/schema+json` for JSON Schema
/// @param schemaContent the schema document, or `null` when none was stored
/// @param schemaType    payload language
/// @param status        lifecycle state
/// @param createdAt     when minted
/// @param updatedAt     last status/content change
public record SpecVersion(
        String id,
        String eventTypeId,
        String version,
        String mimeType,
        JsonNode schemaContent,
        SchemaType schemaType,
        SpecVersionStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String JSON_SCHEMA_MIME_TYPE = "application/schema+json";

    public SpecVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventTypeId, "eventTypeId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(schemaType, "schemaType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// Mints a new JSON-Schema version in the `FINALISING` state.
    public static SpecVersion initial(String eventTypeId, String version, JsonNode schemaContent) {
        Instant now = Instant.now();
        return new SpecVersion(EntityType.SCHEMA.generate(), eventTypeId, version, JSON_SCHEMA_MIME_TYPE,
                schemaContent, SchemaType.JSON_SCHEMA, SpecVersionStatus.FINALISING, now, now);
    }

    public boolean isCurrent() {
        return status == SpecVersionStatus.CURRENT;
    }

    public boolean isDeprecated() {
        return status == SpecVersionStatus.DEPRECATED;
    }

    /// The leading major segment of the version string (`"1.0"` → `"1"`,
    /// `"2.3.4-alpha"` → `"2"`); the whole string when it has no separator.
    /// Finalising a version auto-deprecates the `CURRENT` sibling of the same
    /// major (spec §2).
    public String major() {
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.' || c == '-' || c == '+') return version.substring(0, i);
        }
        return version;
    }

    /// Same version in a new state, `updatedAt` bumped.
    SpecVersion withStatus(SpecVersionStatus newStatus) {
        return new SpecVersion(id, eventTypeId, version, mimeType, schemaContent, schemaType, newStatus, createdAt, Instant.now());
    }
}
