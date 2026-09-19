package io.flowcatalyst.fnhost.reconcile;

import java.io.Serial;
import java.util.Objects;

/// Infrastructure outcomes from talking to the platform's control plane
/// (`docs/spec/function-host-reconciler.md` §1.1) — mirrors
/// `io.flowcatalyst.platform.function.artifact.ArtifactException`'s shape
/// (one checked exception, a closed reason set) rather than a family of
/// exception types.
public final class ControlPlaneException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /// A closed set (spec §1.1):
    /// - [#UNAVAILABLE] — non-2xx/304, an I/O error, or a timeout talking to
    ///   `/control/functions/*`. Routine: a platform outage must never unload
    ///   a function, so the reconciler treats this as "keep serving what is
    ///   loaded", not a fatal error.
    /// - [#UNAUTHORIZED] — a second 401 after [TokenSource] refreshed the
    ///   bearer token once and the request was retried once.
    public enum Reason {
        UNAVAILABLE, UNAUTHORIZED
    }

    private final Reason reason;

    public ControlPlaneException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ControlPlaneException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
