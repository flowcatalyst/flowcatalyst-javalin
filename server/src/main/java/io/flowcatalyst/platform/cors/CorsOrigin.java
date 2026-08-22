package io.flowcatalyst.platform.cors;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// One entry of the platform-wide CORS allowlist (spec: `docs/spec/cors.md`).
/// Platform-owned, anchor-only configuration with no per-client dimension
/// and no state machine: an origin is added or deleted, never updated.
///
/// Immutable record. The origin arrives already trimmed and validated
/// ([Origin#parse]) — the type makes "no unvalidated origin reaches the
/// aggregate" a fact. The repository persists whatever it is handed and
/// stamps `updatedAt` itself.
///
/// @param id          `cor_…` TSID
/// @param origin      the browser origin, trimmed, unique
/// @param description optional free text; `null` when absent
/// @param createdBy   the acting principal on add; `null` for rows another writer produced
/// @param createdAt   creation time
/// @param updatedAt   last change
public record CorsOrigin(
        String id,
        String origin,
        String description,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public CorsOrigin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh allowlist entry for `origin`, recorded as added by `createdBy`
    /// (spec §1). `description` and `createdBy` may be `null`.
    public static CorsOrigin create(Origin origin, String description, String createdBy) {
        Instant now = Instant.now();
        return new CorsOrigin(EntityType.CORS_ORIGIN.generate(), origin.value(), description, createdBy, now, now);
    }
}
