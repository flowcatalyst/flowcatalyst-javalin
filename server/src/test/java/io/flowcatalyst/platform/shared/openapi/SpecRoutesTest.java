package io.flowcatalyst.platform.shared.openapi;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SpecRoutesTest {

    private static Javalin app;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() {
        var lockfile = Lockfile.load(mapper);
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            new SpecRoutes(lockfile).register(cfg.routes);
        }).start(0);
        base = "http://localhost:" + app.port();
    }

    @AfterAll
    static void stop() {
        app.stop();
    }

    private static HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void jsonEndpointsServeTheLockfileBytes() throws Exception {
        byte[] expected = Lockfile.load(mapper).bytes();
        for (String path : new String[]{"/api/openapi.json", "/q/openapi"}) {
            var r = get(path);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(r.body()).isEqualTo(expected);
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
    void swaggerUiIsTheGoConstant() throws Exception {
        var r = get("/swagger-ui");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(new String(r.body())).isEqualTo(SpecRoutes.SWAGGER_UI_HTML);
    }
}
