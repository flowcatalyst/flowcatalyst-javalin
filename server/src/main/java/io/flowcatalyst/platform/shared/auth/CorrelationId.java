package io.flowcatalyst.platform.shared.auth;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.MDC;

import java.util.UUID;

/// The correlation-id middleware (Go `middleware.CorrelationID`): reads
/// `X-Correlation-ID` from the request or generates a UUID, echoes it on the
/// response, stores it on the request ([#from]) and on the MDC under
/// `correlation_id` for log enrichment. [Auth#scoped] additionally binds it
/// to [#CURRENT] for the duration of the route handler.
///
/// Install with [#install] (a `before` that sets and an `after` that clears
/// the MDC — the request thread is reused, so the MDC must not leak).
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";

    /// MDC key (same as Go's slog attribute).
    public static final String MDC_KEY = "correlation_id";

    /// MDC key for the acting principal (Go `logging.WithPrincipalID`).
    public static final String MDC_PRINCIPAL_KEY = "principal_id";

    /// Bound by [Auth#scoped] while a route handler runs.
    public static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    static final String ATTR = "io.flowcatalyst.platform.correlationId";

    private CorrelationId() {
    }

    /// The `before` handler: read-or-generate, echo, attach, MDC.
    public static Handler before() {
        return ctx -> {
            var id = ctx.header(HEADER);
            if (id == null || id.isEmpty()) id = UUID.randomUUID().toString();
            ctx.header(HEADER, id);
            ctx.attribute(ATTR, id);
            MDC.put(MDC_KEY, id);
        };
    }

    /// The `after` handler: clears the request-scoped MDC keys.
    public static Handler after() {
        return _ -> {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_PRINCIPAL_KEY);
        };
    }

    /// Registers [#before] and [#after] on `cfg.routes`.
    public static void install(JavalinDefaultRoutingApi routes) {
        routes.before(before());
        routes.after(after());
    }

    /// The request's correlation id, or `null` if the middleware did not run.
    public static String from(Context ctx) {
        return ctx.attribute(ATTR);
    }

    /// The correlation id bound by [Auth#scoped], or the MDC value, or `null`.
    public static String current() {
        if (CURRENT.isBound()) return CURRENT.get();
        return MDC.get(MDC_KEY);
    }
}
