package io.flowcatalyst.platform.process;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;

/// The three-segment process code `application:subdomain:process-name`,
/// parsed. This is the one place the format rule lives: the admin create
/// command, the sync batch and [Process#create] all go through [#parse], so
/// every entry point rejects a malformed code with the same
/// `INVALID_CODE_FORMAT` error and the same message (spec §4).
///
/// @param application first segment; becomes the row's `application` column
/// @param subdomain   second segment
/// @param processName third segment
public record ProcessCode(String application, String subdomain, String processName) {

    public static final String FORMAT_MESSAGE =
            "Process code must follow format: application:subdomain:process-name";
    public static final String EMPTY_SEGMENT_MESSAGE = "Process code segments cannot be empty";

    private static final int SEGMENTS = 3;

    public ProcessCode {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(subdomain, "subdomain");
        Objects.requireNonNull(processName, "processName");
    }

    /// Splits and validates a raw code.
    ///
    /// @throws UseCaseException validation `INVALID_CODE_FORMAT` when the code
    ///                          is not exactly three segments or any segment is blank
    public static ProcessCode parse(String code) {
        String[] parts = (code == null ? "" : code).split(":", -1);
        if (parts.length != SEGMENTS) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", FORMAT_MESSAGE);
        }
        for (String part : parts) {
            if (part.isBlank()) {
                throw UseCaseException.validation("INVALID_CODE_FORMAT", EMPTY_SEGMENT_MESSAGE);
            }
        }
        return new ProcessCode(parts[0], parts[1], parts[2]);
    }
}
