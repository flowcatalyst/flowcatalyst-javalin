package io.flowcatalyst.platform.process;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Optional;

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
        problem(code).ifPresent(message -> {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", message);
        });
        String[] parts = code.split(":", -1);
        return new ProcessCode(parts[0], parts[1], parts[2]);
    }

    /// The format check as an outcome: the message [#parse] would refuse
    /// with, or empty when `code` is well-formed. For callers that want to
    /// name the offending row themselves (the sync batch) rather than catch
    /// and re-throw.
    public static Optional<String> problem(String code) {
        String[] parts = (code == null ? "" : code).split(":", -1);
        if (parts.length != SEGMENTS) {
            return Optional.of(FORMAT_MESSAGE);
        }
        for (String part : parts) {
            if (part.isBlank()) {
                return Optional.of(EMPTY_SEGMENT_MESSAGE);
            }
        }
        return Optional.empty();
    }
}
