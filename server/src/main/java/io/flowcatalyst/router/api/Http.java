package io.flowcatalyst.router.api;

import io.javalin.http.Context;

import java.time.Duration;

/// Request reading and error responses shared by every route group.
///
/// Small on purpose. What lives here is the handling that must be *identical*
/// across groups — a junk query parameter is an operator typo on a read-only
/// call rather than a 500, and the two error shapes are the ones §9.1's
/// provider-absent rule names.
final class Http {

    static String queryParam(Context ctx, String name) {
        var v = ctx.queryParam(name);
        return v == null ? "" : v;
    }

    static int queryInt(Context ctx, String name, int def) {
        var v = ctx.queryParam(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /// Status codes are the spec's contract; the error body shape is not
    /// pinned by §9.1 beyond that, so a minimal envelope is used here rather
    /// than the platform's lockfile-driven [io.flowcatalyst.platform.shared.httperror.HttpError],
    /// which belongs to the `/api/**` lockfile surface, not this legacy router API.
    static void notFound(Context ctx, String message) {
        ctx.status(404).json(new ErrorBody(message));
    }

    static void serviceUnavailable(Context ctx, String message) {
        ctx.status(503).json(new ErrorBody(message));
    }

    /// 409, for a request refused because of *who is asking*, not what the
    /// data looks like — currently only `POST /config/reload` on a follower
    /// (R-33: a follower must never start consumers).
    static void conflict(Context ctx, String message) {
        ctx.status(409).json(new ErrorBody(message));
    }

    /// The error envelope for this surface. Package-private rather than
    /// private to [Http] because the mock targets render it directly — a
    /// deliberate 500 from `/api/test/fail` must look like a real one.
    record ErrorBody(String error) {
    }

    static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            // A junk limit is an operator typo, not a reason to 500 on a
            // read-only dashboard call.
            return fallback;
        }
    }

    static Duration parseTimeWindow(String raw) {
        if (raw == null) {
            return Duration.ZERO;
        }
        return switch (raw.trim()) {
            case "5min", "5m" -> Duration.ofMinutes(5);
            case "30min", "30m" -> Duration.ofMinutes(30);
            default -> Duration.ZERO;
        };
    }

    private Http() {
    }
}
