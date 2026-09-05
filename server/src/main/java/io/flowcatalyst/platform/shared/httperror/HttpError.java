package io.flowcatalyst.platform.shared.httperror;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowcatalyst.platform.shared.CorruptRowException;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// The platform's canonical error envelope and everything that renders it —
/// the Java reading of Go `httperror` + `httpcompat` (the status/envelope half):
///
/// ```json
/// {"error": "ERR_CODE", "message": "human readable", "details": {...}}
/// ```
///
/// `error` carries the error **code**; `details` is omitted when empty. The
/// status comes from the [UseCaseError] kind (400 validation / 403
/// authorization / 404 not found / 409 conflict + business rule / 500
/// internal — there is no 422) or, for bare codes, from [#statusFor].
///
/// Install once per Javalin app with [#install]: `UseCaseException` →
/// envelope, anything unexpected → the same 500 `INTERNAL` envelope Go's
/// `httperror.Write` emits for a non-usecase error (and is logged, because the
/// envelope hides the cause from the wire).
public record HttpError(
        @JsonProperty("error") String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details) {

    private static final Logger LOG = LoggerFactory.getLogger(HttpError.class);

    /// The envelope written when nothing better is known (Go: `httperror.Write`
    /// with a non-usecase error).
    public static final HttpError INTERNAL = new HttpError("INTERNAL", "Internal server error", Map.of());

    public HttpError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public HttpError(String code, String message) {
        this(code, message, Map.of());
    }

    /// The envelope for a use-case error: code, message and details verbatim.
    public static HttpError of(UseCaseError error) {
        return new HttpError(error.code(), error.message(), error.details());
    }

    /// HTTP status for a use-case error — `UseCaseError.httpStatus()`, kept here
    /// so the transport table is visible next to the envelope (Go `httperror.Status`).
    public static int status(UseCaseError error) {
        return error == null ? 500 : error.httpStatus();
    }

    /// Fallback status for a bare envelope code (Go `httpcompat.statusFor`):
    /// `VALIDATION` / `INVALID_JSON` / `BAD_REQUEST` → 400, `FORBIDDEN` → 403,
    /// `UNAUTHORIZED` → 401, `*_NOT_FOUND` → 404, `*_EXISTS` → 409, anything
    /// else (including the empty code) → 500. The live path always has a kind;
    /// this only fires for bare code strings.
    public static int statusFor(String code) {
        if (code == null) return 500;
        return switch (code) {
            case "VALIDATION", "INVALID_JSON", "BAD_REQUEST" -> 400;
            case "FORBIDDEN" -> 403;
            case "UNAUTHORIZED" -> 401;
            case "" -> 500;
            default -> {
                if (code.length() > 10 && code.endsWith("_NOT_FOUND")) yield 404;
                if (code.length() > 7 && code.endsWith("_EXISTS")) yield 409;
                yield 500;
            }
        };
    }

    /// The serialised body, as Go's `json.Encoder` writes it (trailing `\n`).
    public String toJson() {
        return Json.writeLine(this);
    }

    // ── Writers ────────────────────────────────────────────────────────────

    /// Renders a use-case error as the envelope + status (Go `httperror.Write`).
    /// A `null` error renders the 500 `INTERNAL` envelope. Any 5xx is logged.
    public static void write(Context ctx, UseCaseError error) {
        var env = error == null ? INTERNAL : of(error);
        var status = status(error);
        if (status >= 500) {
            var cause = error instanceof UseCaseError.Internal internal ? internal.cause() : null;
            LOG.error("internal error response code={} message={}", env.code(), env.message(), cause);
        }
        writeRaw(ctx, status, env);
    }

    /// Renders an explicit envelope at an explicit status.
    public static void write(Context ctx, int status, String code, String message, Map<String, Object> details) {
        writeRaw(ctx, status, new HttpError(code, message, details));
    }

    /// Renders a bare code/message at [#statusFor] (the huma `ErrorModel`
    /// fallback when no kind is known).
    public static void write(Context ctx, String code, String message) {
        writeRaw(ctx, statusFor(code), new HttpError(code, message));
    }

    /// The RFC 6750-flavoured 401 the authenticator emits for a bad bearer
    /// (Go `middleware.writeInvalidTokenError`): body
    /// `{"error":"invalid_token","error_description":"…"}` and header
    /// `WWW-Authenticate: Bearer error="invalid_token"`. A blank description
    /// defaults to `invalid bearer token`.
    public static void writeInvalidToken(Context ctx, String description) {
        var desc = description == null || description.isEmpty() ? "invalid bearer token" : description;
        ctx.header("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        ctx.status(401)
                .contentType("application/json")
                .result(Json.writeLine(new InvalidToken("invalid_token", desc)));
    }

    /// Wire shape of the invalid-bearer body; key order matches Go's sorted map.
    public record InvalidToken(String error, @JsonProperty("error_description") String errorDescription) {
    }

    private static void writeRaw(Context ctx, int status, HttpError env) {
        ctx.status(status).contentType("application/json").result(env.toJson());
    }

    // ── Constructors (Go httperror.Forbidden / BadRequest / NotFound …) ────

    /// Handler-layer permission rejection: `FORBIDDEN` (403).
    public static UseCaseException forbidden(String message) {
        return UseCaseException.authorization("FORBIDDEN", message);
    }

    /// Handler-layer input failure: a validation error with the given code (400).
    public static UseCaseException badRequest(String code, String message) {
        return UseCaseException.validation(code, message);
    }

    /// The canonical not-found for `resource` + `id`:
    /// code `<resource>_NOT_FOUND`, message `<resource> not found: <id>` (404).
    public static UseCaseException notFound(String resource, String id) {
        return UseCaseException.resourceNotFound(resource, id);
    }

    /// Unparseable request body: `INVALID_JSON` (400).
    public static UseCaseException invalidJson(String message) {
        return UseCaseException.validation("INVALID_JSON", message);
    }

    /// Uniqueness / state conflict with an explicit code (409).
    public static UseCaseException conflict(String code, String message) {
        return UseCaseException.conflict(code, message);
    }

    /// Infrastructure failure (500); the cause is logged, never written.
    public static UseCaseException internal(String code, String message, Throwable cause) {
        return UseCaseException.internal(code, message, cause);
    }

    /// `UNAUTHENTICATED` as Go's check helpers raise it — an *authorization*
    /// kind, so it renders as **403**, not 401 (Go semantics).
    public static UseCaseException unauthenticated() {
        return UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
    }

    /// A 401 `UNAUTHORIZED` envelope. Go has no use-case kind for 401 — only
    /// the bare-code fallback in `statusFor` knows it — so this is written
    /// directly rather than thrown.
    public static void unauthorized(Context ctx, String message) {
        writeRaw(ctx, 401, new HttpError("UNAUTHORIZED", message));
    }

    /// Kind queries (Go `httperror.IsNotFound` / `IsValidation`).
    public static boolean isNotFound(Throwable t) {
        return UseCaseException.find(t).filter(e -> e instanceof UseCaseError.NotFound).isPresent();
    }

    public static boolean isValidation(Throwable t) {
        return UseCaseException.find(t).filter(e -> e instanceof UseCaseError.Validation).isPresent();
    }

    // ── Javalin wiring ─────────────────────────────────────────────────────

    /// Registers the exception handlers on `cfg.routes`:
    ///
    ///   - [UseCaseException] → its envelope at the kind's status;
    ///   - [CorruptRowException] (any stored-enum row a strict `parse`
    ///     rejected, X-06) → logged with the row id, 500 `CORRUPT_ROW` —
    ///     distinct from bare `INTERNAL` so an operator can tell "a row is
    ///     bad" from "something else broke";
    ///   - Javalin's own [HttpResponseException] (404 for an unmatched route
    ///     with `ctx.result` unset, validator failures, …) → envelope with a
    ///     status-derived code (`BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`,
    ///     `NOT_FOUND`, `CONFLICT`, else `INTERNAL`) — Java-only shape, Go's
    ///     chi answers those with plain text;
    ///   - any other [Exception] → logged, 500 `INTERNAL` envelope (what
    ///     `httperror.Write` emits for a non-usecase error; chi's Recoverer
    ///     answers a panic with an empty 500 — the envelope is the kinder
    ///     superset).
    public static void install(JavalinDefaultRoutingApi routes) {
        routes.exception(UseCaseException.class, (e, ctx) -> write(ctx, e.error()));
        routes.exception(CorruptRowException.class, (e, ctx) -> {
            LOG.error("corrupt row on {} {}: entity={} rowId={}", ctx.method(), ctx.path(), e.entity(), e.rowId(), e);
            writeRaw(ctx, 500, new HttpError("CORRUPT_ROW", e.getMessage()));
        });
        routes.exception(HttpResponseException.class, (e, ctx) -> {
            var status = e.getStatus();
            var code = switch (status) {
                case 400 -> "BAD_REQUEST";
                case 401 -> "UNAUTHORIZED";
                case 403 -> "FORBIDDEN";
                case 404 -> "NOT_FOUND";
                case 409 -> "CONFLICT";
                default -> "INTERNAL";
            };
            if (status >= 500) LOG.error("internal error response code={} message={}", code, e.getMessage(), e);
            writeRaw(ctx, status, new HttpError(code, e.getMessage() == null ? "" : e.getMessage()));
        });
        routes.exception(Exception.class, (e, ctx) -> {
            LOG.error("unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            writeRaw(ctx, 500, INTERNAL);
        });
    }
}
