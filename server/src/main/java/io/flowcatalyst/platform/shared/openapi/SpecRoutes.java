package io.flowcatalyst.platform.shared.openapi;

import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;
import tools.jackson.databind.ObjectMapper;
import io.flowcatalyst.http.Routes;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// The unauthenticated spec + Swagger UI routes, mounted OUTSIDE the auth
/// middleware (so tooling — oasdiff, the Hey-API codegen in the Vue frontend,
/// browser visitors — can fetch them without a bearer token), exactly as the
/// Go `registerSpecRoutes` does:
///
///   - `GET /api/openapi.json` — the lockfile bytes
///   - `GET /api/openapi.yaml` — the same document as YAML
///   - `GET /q/openapi`        — legacy alias for the JSON
///   - `GET /swagger-ui`       — a minimal CDN Swagger UI pointed at `/q/openapi`
public final class SpecRoutes {

    /// Byte-for-byte the Go `swaggerUIHTML` constant.
    static final String SWAGGER_UI_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8"/>
            <title>FlowCatalyst API</title>
            <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist/swagger-ui.css"/>
            </head>
            <body>
            <div id="swagger-ui"></div>
            <script src="https://unpkg.com/swagger-ui-dist/swagger-ui-bundle.js" crossorigin></script>
            <script>window.onload=function(){window.ui=SwaggerUIBundle({url:'/q/openapi',dom_id:'#swagger-ui'});};</script>
            </body>
            </html>""";

    private final byte[] json;
    private final byte[] yaml;

    public SpecRoutes(Lockfile lockfile) {
        Objects.requireNonNull(lockfile, "lockfile");
        this.json = lockfile.bytes();
        this.yaml = toYaml(lockfile);
    }

    public void register(Routes routes) {
        routes.get("/api/openapi.json", ctx -> ctx.contentType("application/json").result(json));
        routes.get("/api/openapi.yaml", ctx -> ctx.contentType("application/yaml").result(yaml));
        routes.get("/q/openapi", ctx -> ctx.contentType("application/json").result(json));
        routes.get("/swagger-ui", ctx -> ctx.contentType("text/html; charset=utf-8").result(SWAGGER_UI_HTML));
    }

    private static byte[] toYaml(Lockfile lockfile) {
        var factory = YAMLFactory.builder()
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                .build();
        try {
            return new ObjectMapper(factory).writeValueAsString(lockfile.json()).getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            throw new IllegalStateException("could not render the OpenAPI lockfile as YAML", e);
        }
    }
}
