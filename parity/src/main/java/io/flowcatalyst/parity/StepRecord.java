package io.flowcatalyst.parity;

import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/// What one side answered for one step (parity-harness spec §4): the status,
/// the [ComparedHeaders], and the body — JSON when the content type says so,
/// else raw text, else (a known binary body: the QR PNG, the OpenAPI
/// document) its SHA-256. Exactly one of [#jsonBody] / [#textBody] /
/// [#sha256Body] is non-null.
public record StepRecord(int status, Map<String, String> headers, JsonNode jsonBody, String textBody,
                          String sha256Body) {

    public StepRecord {
        headers = Map.copyOf(headers);
        long populated = (jsonBody != null ? 1 : 0) + (textBody != null ? 1 : 0) + (sha256Body != null ? 1 : 0);
        if (populated != 1) {
            throw new IllegalArgumentException("exactly one of jsonBody/textBody/sha256Body must be set");
        }
    }

    public static StepRecord json(int status, Map<String, String> headers, JsonNode body) {
        return new StepRecord(status, headers, Objects.requireNonNull(body, "body"), null, null);
    }

    public static StepRecord text(int status, Map<String, String> headers, String text) {
        return new StepRecord(status, headers, null, Objects.requireNonNull(text, "text"), null);
    }

    public static StepRecord hashed(int status, Map<String, String> headers, String sha256) {
        return new StepRecord(status, headers, null, null, Objects.requireNonNull(sha256, "sha256"));
    }
}
