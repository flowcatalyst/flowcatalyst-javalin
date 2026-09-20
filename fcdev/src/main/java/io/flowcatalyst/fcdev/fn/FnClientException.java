package io.flowcatalyst.fcdev.fn;

import java.util.Map;
import java.util.Objects;

/// Everything [FnClient] throws for a non-2xx platform/host response — the
/// platform's `{error, message, details}` envelope
/// ([io.flowcatalyst.platform.shared.httperror.HttpError]) carried as a plain
/// exception so a CLI command can print `code: message` on one line
/// (`docs/spec/function-developer-surface.md` §2: "the platform's `code` and
/// `message` printed, never a stack trace") and inspect `details` (e.g.
/// `fn deploy`'s `VERSION_DIGEST_EXISTS` → `details.version`).
public final class FnClientException extends RuntimeException {

    private final String code;
    private final int status;
    private final Map<String, Object> details;

    public FnClientException(String code, String message, int status) {
        this(code, message, status, Map.of());
    }

    public FnClientException(String code, String message, int status, Map<String, Object> details) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.status = status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    /// The HTTP status, or `0` when the request never got a response
    /// (network failure — `docs/fcdev.md`'s "never a stack trace" applies
    /// there too).
    public int status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }

    /// The one line a CLI command prints to stderr on failure.
    public String oneLine() {
        return code + ": " + getMessage();
    }
}
