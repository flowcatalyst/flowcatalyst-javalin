package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.Optional;

/// Where the request's [AuthContext] lives (Go `auth.WithContext` /
/// `FromContext` / `NewExecutionContext`).
///
/// Javalin runs `before` handlers, the route handler and `after` handlers
/// sequentially on one thread, so the [Authenticator] stores the context on
/// the request ([#bind]) and route code reads it either directly from the
/// [Context] ([#from]) or — the preferred, transport-free way for use cases —
/// from the [ScopedValue] [#CURRENT], which [#scoped] binds around a route
/// handler:
///
/// ```java
/// cfg.routes.post("/api/event-types", Auth.scoped(ctx -> {
///     Checks.canWriteEventTypes(Auth.current());
///     var event = CreateEventType.of(repo).run(uow, cmd, Auth.executionContext());
///     ...
/// }));
/// ```
///
/// Unauthenticated is `null`, exactly like Go's nil `*AuthContext`: the
/// [Checks] helpers treat it as `UNAUTHENTICATED`.
public final class Auth {

    /// The authenticated principal for the current route handler; unbound or
    /// bound to `null` when the request is unauthenticated.
    public static final ScopedValue<AuthContext> CURRENT = ScopedValue.newInstance();

    static final String ATTR = "io.flowcatalyst.platform.authContext";

    private Auth() {
    }

    /// Attaches the context to the request (the authenticator calls this;
    /// tests may too to bypass the HTTP layer — Go `middleware.WithAuth`).
    public static void bind(Context ctx, AuthContext ac) {
        ctx.attribute(ATTR, ac);
    }

    /// The request's context, or `null` when unauthenticated (Go `FromContext`).
    public static AuthContext from(Context ctx) {
        return ctx.attribute(ATTR);
    }

    /// The bound context, or `null` when unauthenticated / outside [#scoped].
    public static AuthContext current() {
        // Not `CURRENT.orElse(null)`: ScopedValue.orElse requires a non-null fallback.
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /// [#current] as an `Optional`.
    public static Optional<AuthContext> currentOptional() {
        return Optional.ofNullable(current());
    }

    /// Wraps a route handler so that [#CURRENT] (and [CorrelationId#CURRENT])
    /// are bound from the request for its duration. Exceptions propagate
    /// unchanged, so Javalin's exception handlers still apply.
    public static Handler scoped(Handler handler) {
        return ctx -> {
            var carrier = ScopedValue.where(CURRENT, from(ctx));
            var correlationId = CorrelationId.from(ctx);
            if (correlationId != null) carrier = carrier.where(CorrelationId.CURRENT, correlationId);
            carrier.call(() -> {
                handler.handle(ctx);
                return null;
            });
        };
    }

    /// Runs `body` with `ac` bound as [#CURRENT] — for use cases invoked off
    /// the HTTP path (tests, schedulers, event consumers).
    public static <R, X extends Throwable> R runAs(AuthContext ac, ScopedValue.CallableOp<? extends R, X> body) throws X {
        return ScopedValue.where(CURRENT, ac).call(body);
    }

    /// The use-case execution context seeded from the bound principal (Go
    /// `auth.NewExecutionContext`): a fresh execution id, correlation seeded
    /// from it, `principalId` `null` when unauthenticated (the use case's
    /// `Authorize` phase rejects that before anything is committed).
    ///
    /// NOTE: Go deliberately does NOT feed the inbound `X-Correlation-ID` into
    /// the execution context (`usecase.NewExecutionContext(principalID)`), so
    /// neither does this — `msg_events.correlation_id` stays a UUID the
    /// platform minted. Use `ExecutionContext.withCorrelation(...)` explicitly
    /// if a caller wants to continue an upstream trace.
    public static ExecutionContext executionContext() {
        return executionContext(current());
    }

    /// [#executionContext()] from the request rather than the scoped value.
    public static ExecutionContext executionContext(Context ctx) {
        return executionContext(from(ctx));
    }

    private static ExecutionContext executionContext(AuthContext ac) {
        return ExecutionContext.of(ac == null ? null : ac.principalId());
    }
}
