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
/// `clientId` is a component of the aggregate and the `created` event, but
/// `msg_event_types` has no such column: event types are platform-wide, it is
/// never set (create ignores a wire `clientId` and needs anchor reach,
/// 2026-10-11 F2), never persisted and always reads back `null` (spec §1,
/// open question 1). The read paths' client filter on it is dead code.
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
/// @param clientId     always `null` (see above)
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

    /// Why archive is refused while a version is not deprecated (bug 5) — the
    /// message the UIs show beside the disabled action.
    public static final String CANNOT_ARCHIVE_REASON = "Deprecate every schema version first.";

    /// Why delete is refused for a published, current event type (bug 5).
    public static final String CANNOT_DELETE_REASON =
            "Only an archived event type, or one whose schema versions are all still finalising, can be deleted.";

    /// Whether the event type may be archived: it is `CURRENT` and every schema
    /// version (if any) is `DEPRECATED` (bug 5, the SPA drawer's rule).
    public boolean canArchive() {
        return status == EventTypeStatus.CURRENT
                && specVersions.stream().allMatch(SpecVersion::isDeprecated);
    }

    /// Whether the event type may be deleted: it is `ARCHIVED`, or `CURRENT`
    /// with every schema version (if any) still `FINALISING` — never published
    /// (bug 5).
    public boolean canDelete() {
        return switch (status) {
            case ARCHIVED -> true;
            case CURRENT -> specVersions.stream().allMatch(sv -> sv.status() == SpecVersionStatus.FINALISING);
        };
    }

    /// The delete guard (bug 5): this event type, or the refusal.
    ///
    /// @throws UseCaseException business rule `DELETE_NOT_ALLOWED`
    public EventType requireDeletable() {
        if (!canDelete()) throw UseCaseException.businessRule("DELETE_NOT_ALLOWED", CANNOT_DELETE_REASON);
        return this;
    }

    /// The spec version with the given version string, if present.
    public Optional<SpecVersion> specVersion(String version) {
        return specVersions.stream().filter(sv -> sv.version().equals(version)).findFirst();
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// `CURRENT` → `ARCHIVED`, once every schema version is deprecated (bug 5).
    ///
    /// @throws UseCaseException conflict `ALREADY_ARCHIVED`; business rule `SCHEMAS_NOT_DEPRECATED`
    public EventType archive() {
        if (isArchived()) {
            throw UseCaseException.conflict("ALREADY_ARCHIVED", "Event type '" + code + "' is already archived");
        }
        if (!canArchive()) {
            throw UseCaseException.businessRule("SCHEMAS_NOT_DEPRECATED", CANNOT_ARCHIVE_REASON);
        }
        return new EventType(id, code, name, description, specVersions, EventTypeStatus.ARCHIVED, source, clientScoped,
                application, subdomain, aggregate, eventName, clientId, createdBy, createdAt, Instant.now());
    }

    /// Appends a new `FINALISING` JSON-Schema version.
    ///
    /// @throws UseCaseException conflict `VERSION_EXISTS` when this event type already has `version`
    public EventType addSchemaVersion(String version, JsonNode schema) {
        return addSchemaVersion(version, schema, SchemaType.JSON_SCHEMA, SpecVersion.JSON_SCHEMA_MIME_TYPE);
    }

    /// Appends a new `FINALISING` version of `schemaType` with `mimeType`;
    /// `schema` is already in its stored form ([SchemaType#normaliseContent]).
    ///
    /// @throws UseCaseException conflict `VERSION_EXISTS` when this event type already has `version`
    public EventType addSchemaVersion(String version, JsonNode schema, SchemaType schemaType, String mimeType) {
        if (specVersion(version).isPresent()) {
            throw UseCaseException.conflict("VERSION_EXISTS",
                    "Schema version '" + version + "' already exists for this event type");
        }
        var versions = new ArrayList<>(specVersions);
        versions.add(SpecVersion.initial(id, version, schema, schemaType, mimeType));
        return withSpecVersions(versions);
    }

    /// The spec version a sync's schema is: the type's first version, as create mints it.
    public static final String SYNC_SCHEMA_VERSION = "1.0";

    /// What [#syncSchema] did to spec version [#SYNC_SCHEMA_VERSION].
    public enum SchemaSyncOutcome {
        CREATED, UPDATED, UNCHANGED
    }

    /// The outcome of [#syncSchema]: the (possibly) changed aggregate and what happened.
    public record SchemaSynced(EventType eventType, SchemaSyncOutcome outcome) {
        public SchemaSynced {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(outcome, "outcome");
        }

        public boolean changed() {
            return outcome != SchemaSyncOutcome.UNCHANGED;
        }
    }

    /// A sync's schema becomes spec version `1.0` (spec §7): minted
    /// `FINALISING` when absent (as create mints it), its content rewritten when
    /// it differs — compared by value, since the stored copy comes back from
    /// JSONB with keys reordered — and left alone when the same. A type synced
    /// without a schema (`null` or JSON `null`) is unchanged. The version's
    /// status is never touched.
    public SchemaSynced syncSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) return new SchemaSynced(this, SchemaSyncOutcome.UNCHANGED);
        Optional<SpecVersion> existing = specVersion(SYNC_SCHEMA_VERSION);
        if (existing.isEmpty()) {
            return new SchemaSynced(addSchemaVersion(SYNC_SCHEMA_VERSION, schema), SchemaSyncOutcome.CREATED);
        }
        if (sameJson(existing.get().schemaContent(), schema)) return new SchemaSynced(this, SchemaSyncOutcome.UNCHANGED);
        return new SchemaSynced(withSpecVersions(specVersions.stream()
                .map(sv -> sv.version().equals(SYNC_SCHEMA_VERSION) ? sv.withContent(schema) : sv)
                .toList()), SchemaSyncOutcome.UPDATED);
    }

    /// JSON equality by value: object key order is ignored (Jackson's object
    /// equality is a map equality) and numbers compare by value, so `1` and
    /// `1.0` from different readers are the same schema.
    private static boolean sameJson(JsonNode a, JsonNode b) {
        if (a == null) return false;
        return a.equals((x, y) -> x.isNumber() && y.isNumber()
                ? x.decimalValue().compareTo(y.decimalValue())
                : x.equals(y) ? 0 : 1, b);
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
