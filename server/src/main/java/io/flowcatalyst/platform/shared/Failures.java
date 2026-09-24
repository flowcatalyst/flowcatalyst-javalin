package io.flowcatalyst.platform.shared;

import java.util.IdentityHashMap;
import java.util.Map;

/// One-line descriptions of a [Throwable] for a place that keeps a failure as
/// text (an attempt record, an outbox status, a client error message).
///
/// `e.getMessage()` alone is not enough: the JDK `HttpClient`'s
/// `ConnectException` often has a `null` message ("request failed: null"), and
/// a message never says whether the failure was DNS, refused, TLS or a reset —
/// the class and the cause chain do.
public final class Failures {

    /// Causes beyond this depth are dropped; a real chain rarely needs more.
    private static final int MAX_DEPTH = 4;

    private Failures() {
    }

    /// `"ConnectException: Connection refused; caused by …"` — each link's
    /// simple class name and its message when it has one, outermost first,
    /// at most [#MAX_DEPTH] links, cycles cut.
    public static String describe(Throwable t) {
        StringBuilder out = new StringBuilder();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.put(current, Boolean.TRUE) == null; depth++) {
            if (depth > 0) {
                out.append("; caused by ");
            }
            out.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                out.append(": ").append(message);
            }
            current = current.getCause();
        }
        return out.toString();
    }
}
