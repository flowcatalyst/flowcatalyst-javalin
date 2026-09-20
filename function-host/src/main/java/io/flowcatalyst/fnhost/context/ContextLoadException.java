package io.flowcatalyst.fnhost.context;

import java.io.Serial;

/// A [ContextFactory#build] failure — a load failure like any other (spec
/// `function-context.md` §2 item 6): the reconciler records `code` against
/// this (address, version) in its heartbeat as `FAILED`, closes whatever was
/// loaded so far, and leaves the previously-loaded version (if any) serving.
/// `code` never carries the raw DSN or any secret value (only `DB_UNSUPPORTED`
/// / `DB_POOL_LIMIT` today) — this exception's message is safe to log as-is.
public final class ContextLoadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    public ContextLoadException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
