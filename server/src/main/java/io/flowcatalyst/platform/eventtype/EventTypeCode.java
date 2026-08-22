package io.flowcatalyst.platform.eventtype;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;

/// The four-segment event-type code `application:subdomain:aggregate:event`,
/// parsed. This is the one place the format rule lives: the admin create
/// command, the sync batch and [EventType#create] all go through [#parse],
/// so every entry point rejects a malformed code with the same
/// `INVALID_CODE_FORMAT` error and the same message.
///
/// @param application first segment; becomes the row's `application` column
/// @param subdomain   second segment
/// @param aggregate   third segment
/// @param event       fourth segment; not a column, derived from the code on read
public record EventTypeCode(String application, String subdomain, String aggregate, String event) {

    public static final String FORMAT_MESSAGE =
            "Event type code must follow format: application:subdomain:aggregate:event";

    private static final String[] SEGMENT_NAMES = {"application", "subdomain", "aggregate", "event"};

    public EventTypeCode {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(subdomain, "subdomain");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(event, "event");
    }

    /// Splits and validates a raw code.
    ///
    /// @throws UseCaseException validation `INVALID_CODE_FORMAT` when the code
    ///                          is not exactly four segments or any segment is blank
    public static EventTypeCode parse(String code) {
        String[] parts = (code == null ? "" : code).split(":", -1);
        if (parts.length != SEGMENT_NAMES.length) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", FORMAT_MESSAGE);
        }
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isBlank()) {
                throw UseCaseException.validation("INVALID_CODE_FORMAT",
                        "Event type code part '" + SEGMENT_NAMES[i] + "' cannot be empty");
            }
        }
        return new EventTypeCode(parts[0], parts[1], parts[2], parts[3]);
    }

    /// The event segment of a *stored* code, or `""` when the stored code does
    /// not have four segments (pre-validation rows). Never throws — a bad row
    /// must not make the aggregate unreadable.
    static String eventNameOf(String storedCode) {
        String[] parts = storedCode.split(":", -1);
        return parts.length == SEGMENT_NAMES.length ? parts[3] : "";
    }
}
