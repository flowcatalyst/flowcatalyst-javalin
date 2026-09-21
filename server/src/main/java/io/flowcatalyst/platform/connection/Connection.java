package io.flowcatalyst.platform.connection;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// The connection aggregate root (spec: `docs/spec/connection.md`,
/// `docs/spec/code-first-connections.md` §2): an outbound webhook delivery
/// target owned by a service account, optionally bound to one client and
/// optionally owned by one application. Identified by its normalised code,
/// unique per `(applicationCode, clientId)` — not per client alone.
///
/// Immutable record: each transition returns a copy, so an operation is just
/// load → transition → event. The repository persists whatever copy it is
/// handed and stamps `updatedAt` itself.
///
/// @param id               `con_…` TSID
/// @param code             normalised code (see [ConnectionCode]); unique per
///                         `(applicationCode, clientId)` (spec
///                         `code-first-connections.md` §2), NOT per client alone
/// @param applicationCode  optional owning application; `null` = shared (no
///                         application), set-if-provided on update, never cleared
/// @param name             human-readable name, trimmed
/// @param description      optional description (`null` when absent)
/// @param externalId       optional external reference (`null` when absent)
/// @param status           `ACTIVE` | `PAUSED`
/// @param source           who authored this row — `CODE` | `API` | `UI`
///                         (spec `code-first-connections.md`); create/update
///                         always produce `UI`
/// @param serviceAccountId the owning service account (not validated today, spec §1)
/// @param clientId         optional client scope; `null` = platform-wide
/// @param clientIdentifier optional; read and written but never set by an operation (spec §1)
/// @param createdAt        creation time
/// @param updatedAt        last change
public record Connection(
        String id,
        String code,
        String applicationCode,
        String name,
        String description,
        String externalId,
        ConnectionStatus status,
        ConnectionSource source,
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
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(serviceAccountId, "serviceAccountId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `ACTIVE`, platform-wide, shared (no application), `UI`-sourced
    /// connection. The code is normalised and validated by
    /// [ConnectionCode#parse]; the name is trimmed. The create operation's
    /// `applicationCode` (spec §3) is applied afterwards via [#withApplicationCode].
    public static Connection create(ConnectionCode code, String name, String serviceAccountId) {
        Instant now = Instant.now();
        return new Connection(EntityType.CONNECTION.generate(), code.value(), null, name.strip(), null, null,
                ConnectionStatus.ACTIVE, ConnectionSource.UI, serviceAccountId, null, null, now, now);
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
        return new Connection(id, code, applicationCode, newName.strip(), description, externalId, status,
                source, serviceAccountId, clientId, clientIdentifier, createdAt, updatedAt);
    }

    /// `null` clears (spec §3: update is a full replace).
    public Connection withDescription(String newDescription) {
        return new Connection(id, code, applicationCode, name, newDescription, externalId, status,
                source, serviceAccountId, clientId, clientIdentifier, createdAt, updatedAt);
    }

    /// `null` clears (spec §3: update is a full replace).
    public Connection withExternalId(String newExternalId) {
        return new Connection(id, code, applicationCode, name, description, newExternalId, status,
                source, serviceAccountId, clientId, clientIdentifier, createdAt, updatedAt);
    }

    public Connection withClientId(String newClientId) {
        return new Connection(id, code, applicationCode, name, description, externalId, status,
                source, serviceAccountId, newClientId, clientIdentifier, createdAt, updatedAt);
    }

    /// Set-if-provided, never cleared (spec §3: create/update `applicationCode`
    /// — a `null` argument leaves the current value alone, unlike the other
    /// `with*` copies here which are full replaces).
    public Connection withApplicationCode(String newApplicationCode) {
        if (newApplicationCode == null) return this;
        return new Connection(id, code, newApplicationCode, name, description, externalId, status,
                source, serviceAccountId, clientId, clientIdentifier, createdAt, updatedAt);
    }

    private Connection withStatus(ConnectionStatus newStatus) {
        return new Connection(id, code, applicationCode, name, description, externalId, newStatus,
                source, serviceAccountId, clientId, clientIdentifier, createdAt, Instant.now());
    }
}
