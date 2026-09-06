package io.flowcatalyst.platform.shared.openapi;

import io.flowcatalyst.platform.shared.TestHttp;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SpecRoutesTest {

    private static TestHttp http;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() {
        var lockfile = Lockfile.load(mapper);
        http = TestHttp.routes(routes -> new SpecRoutes(lockfile).register(routes));
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static HttpResponse<String> get(String path) {
        return http.get(path);
    }

    @Test
    void jsonEndpointsServeTheLockfileBytes() {
        byte[] expected = Lockfile.load(mapper).bytes();
        for (String path : new String[]{"/api/openapi.json", "/q/openapi"}) {
            var r = get(path);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(r.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(expected);
        }
    }

    @Test
    void yamlIsTheSameDocument() throws Exception {
        var r = get("/api/openapi.yaml");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/yaml");
        var yaml = new ObjectMapper(new YAMLFactory()).readTree(r.body());
        assertThat(yaml).isEqualTo(Lockfile.load(mapper).json());
    }

    @Test
    void swaggerUiIsTheGoConstant() {
        var r = get("/swagger-ui");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(SpecRoutes.SWAGGER_UI_HTML);
    }
}
