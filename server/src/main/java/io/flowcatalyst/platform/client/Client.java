package io.flowcatalyst.platform.client;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The client (tenant) aggregate root (spec: `docs/spec/client.md`). A
/// client is the organisation every other client-scoped aggregate hangs off;
/// it is identified by a unique URL-safe identifier and carries a lifecycle
/// status plus an append-only list of notes.
///
/// Immutable record: each transition returns a copy. Status transitions have
/// no preconditions (spec §2) — suspend and activate may be repeated and
/// simply re-stamp `statusChangedAt`. The repository persists whatever copy
/// it is handed and stamps `updatedAt` itself.
///
/// @param id              `clt_…` TSID
/// @param name            display name, trimmed
/// @param identifier      normalised slug, unique, immutable
/// @param status          `ACTIVE` | `INACTIVE` | `SUSPENDED`
/// @param statusReason    why the client is suspended; `null` otherwise
/// @param statusChangedAt last status change; `null` until the first one
/// @param notes           audit-trail notes, in insertion order
/// @param createdAt       creation time
/// @param updatedAt       last change
public record Client(
        String id,
        String name,
        String identifier,
        ClientStatus status,
        String statusReason,
        Instant statusChangedAt,
        List<ClientNote> notes,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public Client {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(status, "status");
        notes = notes == null ? List.of() : List.copyOf(notes);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `ACTIVE` client with no notes. The name is trimmed; the
    /// identifier arrives already normalised and validated ([ClientIdentifier#parse])
    /// — the type makes "no unvalidated slug reaches the aggregate" a fact.
    public static Client create(String name, ClientIdentifier identifier) {
        Instant now = Instant.now();
        return new Client(EntityType.CLIENT.generate(), name.trim(), identifier.value(), ClientStatus.ACTIVE,
                null, null, List.of(), now, now);
    }

    public boolean isActive() {
        return status == ClientStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return status == ClientStatus.SUSPENDED;
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// Replaces the name (trimmed). The identifier is immutable.
    public Client rename(String newName) {
        return new Client(id, newName.trim(), identifier, status, statusReason, statusChangedAt, notes, createdAt, updatedAt);
    }

    /// → `SUSPENDED` with `reason`, stamping `statusChangedAt`. No
    /// precondition: a suspended client is re-stamped (spec §2).
    public Client suspend(String reason) {
        Instant now = Instant.now();
        return new Client(id, name, identifier, ClientStatus.SUSPENDED, reason, now, notes, createdAt, now);
    }

    /// → `ACTIVE`, clearing the reason and stamping `statusChangedAt`. No
    /// precondition: an active client is re-stamped (spec §2).
    public Client activate() {
        Instant now = Instant.now();
        return new Client(id, name, identifier, ClientStatus.ACTIVE, null, now, notes, createdAt, now);
    }

    /// Appends `note` and bumps `updatedAt`.
    public Client addNote(ClientNote note) {
        var next = new ArrayList<>(notes);
        next.add(Objects.requireNonNull(note, "note"));
        return new Client(id, name, identifier, status, statusReason, statusChangedAt, next, createdAt, Instant.now());
    }
}
