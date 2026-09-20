package io.flowcatalyst.function;

import java.io.Serial;
import java.util.Objects;

/// Thrown by [Events#emit] when the host could not emit the event on this
/// function's behalf (`docs/spec/function-context.md` §3). Carries the
/// platform's own error `code` and HTTP `status` verbatim for a non-2xx
/// response from `POST /control/functions/events` (`HOST_UNKNOWN`,
/// `FUNCTION_NOT_SERVED_BY_HOST`, `DEDUP_ID_REQUIRED`, `DEDUP_ID_DUPLICATE`,
/// `EVENT_TYPE_NOT_OWNED`, …); `code="UNAVAILABLE"`, `status=503` for a
/// transport failure (the platform unreachable, a timeout, …) — so a
/// function can `Result.retry` on a 5xx and fail loudly on
/// `EVENT_TYPE_NOT_OWNED`. Unchecked: like [HttpCallRefusedException], this
/// is a routine outcome a function is expected to branch on, not a
/// programming error, but [Events#emit]'s checked `throws Exception` already
/// covers it without a declared `throws` clause here.
public final class EventEmitException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;
    private final int status;

    public EventEmitException(String code, int status) {
        this(code, status, "emit refused: " + code + " (" + status + ")");
    }

    public EventEmitException(String code, int status, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.status = status;
    }

    /// The platform's own error code, e.g. `EVENT_TYPE_NOT_OWNED`.
    public String code() {
        return code;
    }

    /// The HTTP status the platform answered with, or `503` for a transport failure.
    public int status() {
        return status;
    }
}
