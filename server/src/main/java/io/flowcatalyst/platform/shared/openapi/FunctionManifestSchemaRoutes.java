package io.flowcatalyst.platform.shared.openapi;

import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.util.Objects;

/// Serves `function-manifest.schema.json` verbatim (spec
/// `function-manifest-authoring.md` M1.3): `GET /api/schemas/function-manifest.json`,
/// unauthenticated, `Group.NO_DB` — the same reasoning as [FunctionOpenApiRoutes] /
/// [SpecRoutes]: an editor (VS Code, IntelliJ) fetches the document to validate
/// `manifest.json` as the author types, with no bearer token available at edit time.
/// Mounted beside [FunctionOpenApiRoutes] at the composition root (`Platform#register`).
public final class FunctionManifestSchemaRoutes {

    private final byte[] json;

    public FunctionManifestSchemaRoutes(Lockfile document) {
        Objects.requireNonNull(document, "document");
        this.json = document.bytes();
    }

    public void register(Routes routes) {
        routes.in(Group.NO_DB).get("/api/schemas/function-manifest.json",
                ctx -> ctx.contentType("application/schema+json").result(json));
    }
}
