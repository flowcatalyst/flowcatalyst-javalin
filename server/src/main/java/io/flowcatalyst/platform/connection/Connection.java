package io.flowcatalyst.platform.connection;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// The connection aggregate root (spec: `docs/spec/connection.md`): an
/// outbound webhook delivery target owned by a service account, optionally
/// bound to one client. Identified by its normalised code, unique per client.
///
/// Immutable record: each transition returns a copy, so an operation is just
/// load → transition → event. The repository persists whatever copy it is
/// handed and stamps `updatedAt` itself.
///
/// @param id               `con_…` TSID
/// @param code             normalised code (see [ConnectionCode]); immutable after create
/// @param name             human-readable name, trimmed
/// @param description      optional description (`null` when absent)
/// @param externalId       optional external reference (`null` when absent)
/// @param status           `ACTIVE` | `PAUSED`
/// @param serviceAccountId the owning service account (not validated today, spec §1)
/// @param clientId         optional client scope; `null` = platform-wide
/// @param clientIdentifier optional; read and written but never set by an operation (spec §1)
/// @param createdAt        creation time
/// @param updatedAt        last change
public record Connection(
        String id,
        String code,
        String name,
        String description,
        String externalId,
        ConnectionStatus status,
        String serviceAccountId,
        String clientId,
        String clientIdentifier,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public Connection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(serviceAccountId, "serviceAccountId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `ACTIVE`, platform-wide connection. The code is normalised and
    /// validated by [ConnectionCode#parse]; the name is trimmed.
    public static Connection create(ConnectionCode code, String name, String serviceAccountId) {
        Instant now = Instant.now();
        return new Connection(EntityType.CONNECTION.generate(), code.value(), name.strip(), null, null,
                ConnectionStatus.ACTIVE, serviceAccountId, null, null, now, now);
    }

    public boolean isActive() {
        return status == ConnectionStatus.ACTIVE;
    }

    public boolean isPaused() {
        return status == ConnectionStatus.PAUSED;
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// → `PAUSED`. Idempotent: pausing a paused connection is not an error
    /// (spec §2, open question 3).
    public Connection pause() {
        return withStatus(ConnectionStatus.PAUSED);
    }

    /// → `ACTIVE`. Idempotent (spec §2, open question 3).
    public Connection activate() {
        return withStatus(ConnectionStatus.ACTIVE);
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    /// The name is trimmed (spec §1: trimmed on create and update).
    public Connection withName(String newName) {
        return new Connection(id, code, newName.strip(), description, externalId, status,
                serviceAccountId, clientId, clientIdentifier, createdAt, updatedAt);
    }

    /// `null` clears (spec §3: update is a full replace).
    public Connection withDescription(String newDescription) {
        return new Connection(id, code, name, newDescription, externalId, status,
                serviceAccountId, clientId, clientIdentifier, createdAt, updatedAt);
    }

    /// `null` clears (spec §3: update is a full replace).
    public Connection withExternalId(String newExternalId) {
        return new Connection(id, code, name, description, newExternalId, status,
                serviceAccountId, clientId, clientIdentifier, createdAt, updatedAt);
    }

    public Connection withClientId(String newClientId) {
        return new Connection(id, code, name, description, externalId, status,
                serviceAccountId, newClientId, clientIdentifier, createdAt, updatedAt);
    }

    private Connection withStatus(ConnectionStatus newStatus) {
        return new Connection(id, code, name, description, externalId, newStatus,
                serviceAccountId, clientId, clientIdentifier, createdAt, Instant.now());
    }
}
