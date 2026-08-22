package io.flowcatalyst.platform.client;

import java.time.Instant;
import java.util.Objects;

/// One audit-trail note on a client (spec §1). The record is also the JSON
/// shape stored in `tnt_clients.notes` and the `NoteResponse` payload —
/// field names verbatim; `addedBy` is `null` (omitted) when the execution
/// context had no principal.
///
/// @param category free-text grouping, stored verbatim
/// @param text     the note, stored verbatim
/// @param addedBy  acting principal id, or `null`
/// @param addedAt  when the note was added (UTC)
public record ClientNote(String category, String text, String addedBy, Instant addedAt) {

    public ClientNote {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(addedAt, "addedAt");
    }

    /// A note stamped now.
    public static ClientNote of(String category, String text, String addedBy) {
        return new ClientNote(category, text, addedBy, Instant.now());
    }
}
