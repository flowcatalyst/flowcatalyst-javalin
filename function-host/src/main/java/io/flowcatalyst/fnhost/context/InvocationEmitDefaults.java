package io.flowcatalyst.fnhost.context;

/// Per-invocation defaults for [io.flowcatalyst.function.Events#emit] (spec
/// `function-context.md` §3): `correlationId` defaults to the invocation's
/// own (`X-Correlation-Id` header value, else the invocation id);
/// `causationId` defaults to the inbound webhook delivery's event id when
/// the request was a webhook delivery, `null` otherwise. Bound as a
/// [ScopedValue] around the worker thread's call into the function
/// ([io.flowcatalyst.fnhost.http.InvocationRunner]), beside
/// [InvocationDeadline] — never a mutable field on the shared
/// [io.flowcatalyst.function.FunctionContext], which two concurrent
/// invocations of the same loaded version share.
public final class InvocationEmitDefaults {

    /// Bound for the duration of one invocation's [io.flowcatalyst.function.Function#handle]
    /// call. Unbound outside an invocation (e.g. during `Function#init`, or
    /// a test calling [io.flowcatalyst.function.Events] directly) — see
    /// [#NONE].
    public static final ScopedValue<Defaults> CURRENT = ScopedValue.newInstance();

    /// What applies when no invocation is bound — no default to offer.
    public static final Defaults NONE = new Defaults(null, null);

    private InvocationEmitDefaults() {
    }

    /// @param correlationId the invocation's own correlation id, or `null`
    /// @param causationId   the inbound webhook event's id, or `null`
    public record Defaults(String correlationId, String causationId) {
    }

    /// [#CURRENT] if bound, else [#NONE] — the one place callers ask,
    /// instead of each repeating the `isBound()` check.
    public static Defaults current() {
        return CURRENT.isBound() ? CURRENT.get() : NONE;
    }
}
