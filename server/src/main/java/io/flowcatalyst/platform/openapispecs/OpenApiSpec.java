package io.flowcatalyst.platform.openapispecs;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.Objects;

/// One stored OpenAPI document of an application (spec §1). Invariants and
/// the single transition live here; no I/O.
///
/// @param id              `oas_` + TSID
/// @param applicationId   the owning application
/// @param version         unique per application (spec §3 chooses it)
/// @param status          `CURRENT` (at most one per application) or `ARCHIVED`
/// @param spec            the document, verbatim
/// @param specHash        [OpenApiDocument#hash()] of `spec`
/// @param changeNotes     the diff to the successor; `null` until archived
/// @param changeNotesText the rendered summary of `changeNotes`; `null` until archived
/// @param syncedAt        the sync's instant
/// @param syncedBy        the syncing principal; `null` when none was known
/// @param createdAt       row creation
/// @param updatedAt       re-stamped on archive
public record OpenApiSpec(
        String id,
        String applicationId,
        String version,
        SpecStatus status,
        JsonNode spec,
        String specHash,
        ChangeNotes changeNotes,
        String changeNotesText,
        Instant syncedAt,
        String syncedBy,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public OpenApiSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(specHash, "specHash");
        Objects.requireNonNull(syncedAt, "syncedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `CURRENT` row for `document` under `version`, synced `now` by
    /// `syncedBy` (spec §3).
    public static OpenApiSpec create(String applicationId, String version, OpenApiDocument document, Instant now,
                                     String syncedBy) {
        return new OpenApiSpec(EntityType.APPLICATION_OPENAPI_SPEC.generate(), applicationId, version, SpecStatus.CURRENT,
                document.root(), document.hash(), null, null, now, syncedBy, now, now);
    }

    /// `CURRENT → ARCHIVED`, stamping the diff to the successor (spec §1).
    ///
    /// @throws UseCaseException conflict `ALREADY_ARCHIVED`
    public OpenApiSpec archive(ChangeNotes notes, String summary, Instant now) {
        return switch (status) {
            case ARCHIVED -> throw UseCaseException.conflict("ALREADY_ARCHIVED", "OpenAPI spec " + id + " is already archived");
            case CURRENT -> new OpenApiSpec(id, applicationId, version, SpecStatus.ARCHIVED, spec, specHash,
                    Objects.requireNonNull(notes, "notes"), Objects.requireNonNull(summary, "summary"),
                    syncedAt, syncedBy, createdAt, now);
        };
    }
}
