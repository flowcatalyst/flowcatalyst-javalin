package io.flowcatalyst.platform.function.api;

/// `If-None-Match` matching for `GET /control/functions/desired-state`
/// (spec `function-api.md` §6.1): "weak validators and lists (`W/"…"`, `a,
/// b`) are compared by stripping `W/` and splitting on commas; `*`
/// matches." There is no other conditional-GET route in this codebase — this
/// is the one place the rule lives, kept as a tiny pure function so it can
/// be pinned by a `@CsvSource` table without a `TestHttp` round trip.
final class ETags {

    private ETags() {
    }

    /// @param header the raw `If-None-Match` request header, or `null` when absent
    /// @param etag   this response's own strong `ETag` (already quoted, e.g. `"abc123"`)
    static boolean matches(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String trimmed = header.trim();
        if (trimmed.equals("*")) {
            return true;
        }
        for (String candidate : trimmed.split(",")) {
            String value = candidate.trim();
            if (value.startsWith("W/")) {
                value = value.substring(2).trim();
            }
            if (value.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
