package io.flowcatalyst.platform.shared.openapi;

import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.util.Objects;

/// Serves `functions.openapi.json` verbatim (spec `function-openapi.md` §2):
/// `GET /api/openapi-functions.json`, unauthenticated, `Group.NO_DB` — the
/// same reasoning as [SpecRoutes]: tooling fetches the function API's wire
/// contract without a bearer token. Mounted beside [SpecRoutes] at the
/// composition root (`Platform#register`).
///
/// Not under `/api/functions/…`: that prefix's next path segment is always a
/// function address (`function-api.md` §1), so a literal `openapi-functions.json`
/// segment there would collide with a real (if oddly named) address.
public final class FunctionOpenApiRoutes {

    private final byte[] json;

    public FunctionOpenApiRoutes(Lockfile document) {
        Objects.requireNonNull(document, "document");
        this.json = document.bytes();
    }

    public void register(Routes routes) {
        routes.in(Group.NO_DB).get("/api/openapi-functions.json",
                ctx -> ctx.contentType("application/json").result(json));
    }
}
