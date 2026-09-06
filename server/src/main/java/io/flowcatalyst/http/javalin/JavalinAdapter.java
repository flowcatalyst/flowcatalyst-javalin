package io.flowcatalyst.http.javalin;

import io.flowcatalyst.http.ExceptionMappers;
import io.flowcatalyst.http.HttpException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.ExceptionHandler;
import io.javalin.http.HttpResponseException;

/// Installs the Javalin adapter on a `JavalinConfig` and returns the
/// [JavalinRoutes] every handler registers against.
///
/// 1. `cfg.http.prefer405over404 = false` — a matched path with the wrong
///    method answers 404, never 405 (spec §2 rule 4).
/// 2. **Two** Javalin mappers share one translate-then-resolve handler:
///    `HttpResponseException.class` and `Exception.class`. Both are needed —
///    Javalin pre-registers its own default mapper for
///    `HttpResponseException.class` (unmatched-route 404s, method-not-allowed,
///    validator failures, …), and that default is a *closer* supertype match
///    than a bare `Exception.class` registration in Javalin's own
///    most-specific-wins walk, so it would otherwise always win and the
///    platform envelope (spec §4 row 2) would never render. Registering our
///    own handler under the exact same key overrides Javalin's default; the
///    `Exception.class` registration then catches everything else. The
///    shared handler translates a (possibly Javalin) `HttpResponseException`
///    into [HttpException] first, resolves the (possibly translated)
///    throwable through the shared [ExceptionMappers], and invokes the
///    resolved handler with a [JavalinExchange]. No handler or filter code
///    ever sees a Javalin exception type. If nothing resolves, the throwable
///    is rethrown and Javalin's own default 500 handling applies — in
///    practice this never happens because the platform always registers a
///    mapper for `Exception.class` (`HttpError.install`).
/// 3. A no-op mapper for [SkipRemainingHandlersSignal] — see that class for
///    why `Exchange.skipRemainingHandlers()` is implemented this way rather
///    than delegating to Javalin's own method of the same name.
/// 4. The bodiless-response rule (spec §2 rule 5) is installed as a Javalin
///    `after`: status 204, or a response whose handler wrote no body,
///    carries no `Content-Type`. This absorbed `ResponseDefaults`'s logic;
///    that class is deleted (unit b–d).
public final class JavalinAdapter {

    private JavalinAdapter() {
    }

    public static JavalinRoutes install(JavalinConfig cfg) {
        cfg.http.prefer405over404 = false;

        var mappers = new ExceptionMappers();
        ExceptionHandler<Exception> dispatch = (e, c) -> {
            Exception translated = e instanceof HttpResponseException hre
                    ? new HttpException(hre.getStatus(), hre.getMessage())
                    : e;
            var resolved = mappers.resolve(translated);
            if (resolved.isPresent()) {
                resolved.get().handle(translated, new JavalinExchange(c));
                return;
            }
            if (translated instanceof RuntimeException re) throw re;
            throw new RuntimeException(translated);
        };
        cfg.routes.exception(HttpResponseException.class, dispatch);
        cfg.routes.exception(Exception.class, dispatch);
        cfg.routes.exception(SkipRemainingHandlersSignal.class, (e, c) -> {
            // No-op: the before that threw this has already written the
            // response it wants. Reaching an exception mapper at all is
            // what makes Javalin still run `after` (see the marker class).
        });

        cfg.routes.after(c -> {
            if (c.statusCode() == 204 || c.resultInputStream() == null) {
                c.res().setContentType(null);
            }
        });

        return new JavalinRoutes(cfg.routes, mappers);
    }
}
