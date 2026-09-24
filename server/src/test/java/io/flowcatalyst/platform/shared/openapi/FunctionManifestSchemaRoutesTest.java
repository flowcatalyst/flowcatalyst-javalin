package io.flowcatalyst.platform.shared.openapi;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/function-manifest-authoring.md` M1.3: the manifest's JSON Schema is served
/// verbatim, unauthenticated — the same contract [SpecRoutesTest] pins for the lockfile.
class FunctionManifestSchemaRoutesTest {

    private static final String RESOURCE = "schemas/function-manifest.schema.json";
    private static TestHttp http;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() {
        var document = Lockfile.load(mapper, RESOURCE);
        http = TestHttp.routes(routes -> new FunctionManifestSchemaRoutes(document).register(routes));
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    /// No `Authorization` header is sent — [TestHttp] wires no authenticator at all, but the
    /// route itself is registered under `Group.NO_DB`, the same unauthenticated group
    /// `FunctionOpenApiRoutes`/`SpecRoutes` use; this asserts the response an editor actually
    /// gets, byte for byte, not merely that the handler was reached.
    @Test
    void servesTheSchemaFilesExactBytesWithTheSchemaContentType() {
        byte[] expected = Lockfile.load(mapper, RESOURCE).bytes();
        HttpResponse<String> r = http.get("/api/schemas/function-manifest.json");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/schema+json");
        assertThat(r.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(expected);
    }
}
