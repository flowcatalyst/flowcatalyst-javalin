package io.flowcatalyst.platform.eventtype;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The event-type aggregate root (spec: `docs/spec/eventtype.md`). An event
/// type is identified by its four-segment code; the segments are
/// denormalised onto the row. It owns its spec versions and every state
/// transition on them.
///
/// Immutable record: each transition returns a copy and throws
/// [UseCaseException] when an invariant is violated, so an operation is just
/// load → transition → event. The repository persists whatever copy it is
/// handed and stamps `updatedAt` itself.
///
/// `clientId` is carried by the aggregate, the create command and the
/// `created` event and is what per-resource authorization checks — but
/// `msg_event_types` has no such column, so it is never persisted and reads
/// back `null` (spec §1, open question 1).
///
/// @param id           `evt_…` TSID
/// @param code         `application:subdomain:aggregate:event`, unique
/// @param name         human-readable name
/// @param description  optional description
/// @param specVersions schema versions, in load order
/// @param status       `CURRENT` | `ARCHIVED`
/// @param source       `CODE` | `API` | `UI`
/// @param clientScoped whether events of this type are client-scoped (never set today)
/// @param application  first code segment
/// @param subdomain    second code segment
/// @param aggregate    third code segment
/// @param eventName    fourth code segment
/// @param clientId     optional client scope (not persisted, see above)
/// @param createdBy    creating principal, `null` for pre-audit rows and sync-created rows
/// @param createdAt    creation time
/// @param updatedAt    last change
public record EventType(
        String id,
        String code,
        String name,
        String description,
        List<SpecVersion> specVersions,
        EventTypeStatus status,
        EventTypeSource source,
        boolean clientScoped,
        String application,
        String subdomain,
        String aggregate,
        String eventName,
        String clientId,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public EventType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        specVersions = specVersions == null ? List.of() : List.copyOf(specVersions);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(subdomain, "subdomain");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(eventName, "eventName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `CURRENT`, `UI`-sourced event type with no spec versions.
    ///
    /// @throws UseCaseException validation `INVALID_CODE_FORMAT` (see [EventTypeCode#parse])
    public static EventType create(String code, String name) {
        EventTypeCode parsed = EventTypeCode.parse(code);
        Instant now = Instant.now();
        return new EventType(EntityType.EVENT_TYPE.generate(), code, name, null, List.of(),
                EventTypeStatus.CURRENT, EventTypeSource.UI, false,
                parsed.application(), parsed.subdomain(), parsed.aggregate(), parsed.event(), null, null, now, now);
    }

    public boolean isArchived() {
        return status == EventTypeStatus.ARCHIVED;
    }

    /// The spec version with the given version string, if present.
    public Optional<SpecVersion> specVersion(String version) {
        return specVersions.stream().filter(sv -> sv.version().equals(version)).findFirst();
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// `CURRENT` → `ARCHIVED`.
    ///
    /// @throws UseCaseException conflict `ALREADY_ARCHIVED`
    public EventType archive() {
        if (isArchived()) {
            throw UseCaseException.conflict("ALREADY_ARCHIVED", "Event type '" + code + "' is already archived");
        }
        return new EventType(id, code, name, description, specVersions, EventTypeStatus.ARCHIVED, source, clientScoped,
                application, subdomain, aggregate, eventName, clientId, createdBy, createdAt, Instant.now());
    }

    /// Appends a new `FINALISING` JSON-Schema version.
    ///
    /// @throws UseCaseException conflict `VERSION_EXISTS` when this event type already has `version`
    public EventType addSchemaVersion(String version, JsonNode schema) {
        if (specVersion(version).isPresent()) {
            throw UseCaseException.conflict("VERSION_EXISTS",
                    "Schema version '" + version + "' already exists for this event type");
        }
        var versions = new ArrayList<>(specVersions);
        versions.add(SpecVersion.initial(id, version, schema));
        return withSpecVersions(versions);
    }

    /// `FINALISING` → `CURRENT` for `version`. At most one sibling — the first
    /// `CURRENT` version with the same [SpecVersion#major] — is auto-deprecated
    /// in the same change; its version string is reported so the event can
    /// carry it.
    ///
    /// @throws UseCaseException not-found `SpecVersion_NOT_FOUND`, conflict `NOT_FINALISING`
    public SchemaFinalised finaliseSchema(String version) {
        SpecVersion target = requireSpecVersion(version);
        if (target.status() != SpecVersionStatus.FINALISING) {
            throw UseCaseException.conflict("NOT_FINALISING",
                    "Schema version '" + version + "' is not in FINALISING state");
        }
        String major = target.major();
        String deprecatedVersion = null;
        var versions = new ArrayList<SpecVersion>(specVersions.size());
        for (SpecVersion sv : specVersions) {
            if (sv.version().equals(version)) {
                versions.add(sv.withStatus(SpecVersionStatus.CURRENT));
            } else if (deprecatedVersion == null && sv.isCurrent() && sv.major().equals(major)) {
                versions.add(sv.withStatus(SpecVersionStatus.DEPRECATED));
                deprecatedVersion = sv.version();
            } else {
                versions.add(sv);
            }
        }
        return new SchemaFinalised(withSpecVersions(versions), deprecatedVersion);
    }

    /// The outcome of [#finaliseSchema]: the changed aggregate plus the
    /// version string of the sibling that was auto-deprecated, or `null` when
    /// there was none.
    public record SchemaFinalised(EventType eventType, String deprecatedVersion) {
        public SchemaFinalised {
            Objects.requireNonNull(eventType, "eventType");
        }
    }

    /// `CURRENT` → `DEPRECATED` for `version`. A `FINALISING` version cannot
    /// be deprecated directly (finalise it, or let a later finalise deprecate it).
    ///
    /// @throws UseCaseException not-found `SpecVersion_NOT_FOUND`, conflict
    ///                          `STILL_FINALISING` | `ALREADY_DEPRECATED`
    public EventType deprecateSchema(String version) {
        SpecVersion target = requireSpecVersion(version);
        switch (target.status()) {
            case FINALISING -> throw UseCaseException.conflict("STILL_FINALISING",
                    "Schema version '" + version + "' is still in FINALISING state and cannot be deprecated directly");
            case DEPRECATED -> throw UseCaseException.conflict("ALREADY_DEPRECATED",
                    "Schema version '" + version + "' is already deprecated");
            case CURRENT -> { }
        }
        return withSpecVersions(specVersions.stream()
                .map(sv -> sv.version().equals(version) ? sv.withStatus(SpecVersionStatus.DEPRECATED) : sv)
                .toList());
    }

    private SpecVersion requireSpecVersion(String version) {
        return specVersion(version).orElseThrow(() ->
                UseCaseException.notFound("SpecVersion_NOT_FOUND", "SpecVersion not found: " + version));
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    public EventType withName(String newName) {
        return new EventType(id, code, newName, description, specVersions, status, source, clientScoped,
                application, subdomain, aggregate, eventName, clientId, createdBy, createdAt, updatedAt);
    }

    public EventType withDescription(String newDescription) {
        return new EventType(id, code, name, newDescription, specVersions, status, source, clientScoped,
                application, subdomain, aggregate, eventName, clientId, createdBy, createdAt, updatedAt);
    }

    public EventType withSource(EventTypeSource newSource) {
        return new EventType(id, code, name, description, specVersions, status, newSource, clientScoped,
                application, subdomain, aggregate, eventName, clientId, createdBy, createdAt, updatedAt);
    }

    public EventType withClientId(String newClientId) {
        return new EventType(id, code, name, description, specVersions, status, source, clientScoped,
                application, subdomain, aggregate, eventName, newClientId, createdBy, createdAt, updatedAt);
    }

    /// Owner ruling 2026-09-06 #7: the create/update request's `clientScoped`
    /// is honoured (Go 3c22690 carries it the same way).
    public EventType withClientScoped(boolean newClientScoped) {
        return new EventType(id, code, name, description, specVersions, status, source, newClientScoped,
                application, subdomain, aggregate, eventName, clientId, createdBy, createdAt, updatedAt);
    }

    public EventType withCreatedBy(String principalId) {
        return new EventType(id, code, name, description, specVersions, status, source, clientScoped,
                application, subdomain, aggregate, eventName, clientId, principalId, createdAt, updatedAt);
    }

    private EventType withSpecVersions(List<SpecVersion> versions) {
        return new EventType(id, code, name, description, versions, status, source, clientScoped,
                application, subdomain, aggregate, eventName, clientId, createdBy, createdAt, Instant.now());
    }
}
