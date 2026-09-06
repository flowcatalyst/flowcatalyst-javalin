package io.flowcatalyst.platform.shared.httperror;

import io.flowcatalyst.platform.shared.CorruptRowException;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpErrorTest {

    record Body(String name) {
    }

    static TestHttp http;

    @BeforeAll
    static void start() {
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.get("/validation", _ -> { throw UseCaseException.validation("CODE_REQUIRED", "Event type code is required"); });
            routes.get("/authz", _ -> { throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required"); });
            routes.get("/notfound", _ -> { throw HttpError.notFound("EventType", "evt_123"); });
            routes.get("/conflict", _ -> { throw UseCaseException.conflict("CODE_EXISTS", "Event type with code 'x' already exists"); });
            routes.get("/rule", _ -> { throw UseCaseException.businessRule("ALREADY_ACTIVE", "already active"); });
            routes.get("/internal", _ -> { throw UseCaseException.internal("PERSIST", "persist failed", new RuntimeException("boom")); });
            routes.get("/details", _ -> {
                // Insertion-ordered: the assertion pins the wire bytes, and Map.of() iterates in no fixed order.
                var detail = new LinkedHashMap<String, Object>();
                detail.put("message", "invalid integer");
                detail.put("location", "query.page");
                detail.put("value", "abc");
                throw new UseCaseException(UseCaseError.validation("VALIDATION", "validation failed")
                        .withDetails(Map.of("errors", List.of(detail))));
            });
            routes.get("/boom", _ -> { throw new IllegalStateException("unexpected"); });
            routes.get("/corrupt-row", _ -> {
                throw new CorruptRowException("widget", "wid_123", new IllegalStateException("unrecognised status: BOGUS"));
            });
            routes.get("/npe", _ -> { throw new NullPointerException(); });
            routes.post("/echo", ctx -> ctx.json(ctx.bodyAsClass(Body.class)));
            routes.get("/bare", ctx -> HttpError.write(ctx, "INVALID_JSON", "bad body"));
            routes.get("/unauthorized", ctx -> HttpError.unauthorized(ctx, "no session"));
            routes.get("/invalid-token", ctx -> HttpError.writeInvalidToken(ctx, ""));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    @Test
    void validationIs400() {
        var r = http.get("/validation");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(r.body()).isEqualTo("{\"error\":\"CODE_REQUIRED\",\"message\":\"Event type code is required\"}\n");
    }

    @Test
    void authorizationIs403EvenForUnauthenticated() {
        var r = http.get("/authz");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).isEqualTo("{\"error\":\"UNAUTHENTICATED\",\"message\":\"authentication required\"}\n");
    }

    @Test
    void notFoundUsesTheGoCodeAndMessageFormat() {
        var r = http.get("/notfound");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"error\":\"EventType_NOT_FOUND\",\"message\":\"EventType not found: evt_123\"}\n");
    }

    @Test
    void conflictAndBusinessRuleAre409() {
        assertThat(http.get("/conflict").statusCode()).isEqualTo(409);
        assertThat(http.get("/conflict").body()).isEqualTo("{\"error\":\"CODE_EXISTS\",\"message\":\"Event type with code 'x' already exists\"}\n");
        assertThat(http.get("/rule").statusCode()).isEqualTo(409);
    }

    @Test
    void internalIs500WithItsOwnCodeButNoCause() {
        var r = http.get("/internal");
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).isEqualTo("{\"error\":\"PERSIST\",\"message\":\"persist failed\"}\n");
    }

    @Test
    void detailsAreEmittedWhenPresent() {
        var r = http.get("/details");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"VALIDATION\",\"message\":\"validation failed\","
                + "\"details\":{\"errors\":[{\"message\":\"invalid integer\",\"location\":\"query.page\",\"value\":\"abc\"}]}}\n");
    }

    @Test
    void unexpectedExceptionsAreTheInternalEnvelope() {
        for (var path : new String[]{"/boom", "/npe"}) {
            var r = http.get(path);
            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(r.body()).isEqualTo("{\"error\":\"INTERNAL\",\"message\":\"Internal server error\"}\n");
        }
    }

    @Test
    void corruptRowExceptionIsADistinct500NotBareInternal() {
        var r = http.get("/corrupt-row");
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).isEqualTo("{\"error\":\"CORRUPT_ROW\",\"message\":\"widget wid_123 has a corrupt row: unrecognised status: BOGUS\"}\n");
    }

    @Test
    void malformedJsonBodyIsInvalidJson400() {
        var r = http.post("/echo", "{not json", "Content-Type", "application/json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).startsWith("{\"error\":\"INVALID_JSON\",\"message\":\"");
        var ok = http.post("/echo", "{\"name\":\"x\",\"extra\":true}", "Content-Type", "application/json");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"name\":\"x\"}\n");
    }

    @Test
    void bareCodeUsesStatusFor() {
        var r = http.get("/bare");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"INVALID_JSON\",\"message\":\"bad body\"}\n");
        assertThat(http.get("/unauthorized").statusCode()).isEqualTo(401);
        assertThat(http.get("/unauthorized").body()).isEqualTo("{\"error\":\"UNAUTHORIZED\",\"message\":\"no session\"}\n");
    }

    @Test
    void invalidTokenBodyAndHeader() {
        var r = http.get("/invalid-token");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Bearer error=\"invalid_token\"");
        assertThat(r.body()).isEqualTo("{\"error\":\"invalid_token\",\"error_description\":\"invalid bearer token\"}\n");
    }

    @Test
    void javalinNotFoundIsAnEnvelopeToo() {
        var r = http.get("/nope");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).startsWith("{\"error\":\"NOT_FOUND\",\"message\":");
    }

    @Test
    void statusForTable() {
        assertThat(HttpError.statusFor("VALIDATION")).isEqualTo(400);
        assertThat(HttpError.statusFor("INVALID_JSON")).isEqualTo(400);
        assertThat(HttpError.statusFor("BAD_REQUEST")).isEqualTo(400);
        assertThat(HttpError.statusFor("FORBIDDEN")).isEqualTo(403);
        assertThat(HttpError.statusFor("UNAUTHORIZED")).isEqualTo(401);
        assertThat(HttpError.statusFor("")).isEqualTo(500);
        assertThat(HttpError.statusFor(null)).isEqualTo(500);
        assertThat(HttpError.statusFor("EVENT_TYPE_NOT_FOUND")).isEqualTo(404);
        assertThat(HttpError.statusFor("_NOT_FOUND")).isEqualTo(500); // len must exceed the suffix
        assertThat(HttpError.statusFor("CODE_EXISTS")).isEqualTo(409);
        assertThat(HttpError.statusFor("_EXISTS")).isEqualTo(500);
        assertThat(HttpError.statusFor("WHATEVER")).isEqualTo(500);
        assertThat(HttpError.statusFor("STATE_INVALID")).isEqualTo(500); // no 422 anywhere
    }

    @Test
    void constructorsProduceTheGoCodes() {
        assertThat(HttpError.forbidden("nope").error()).isEqualTo(UseCaseError.authorization("FORBIDDEN", "nope"));
        assertThat(HttpError.badRequest("X", "y").error()).isEqualTo(UseCaseError.validation("X", "y"));
        assertThat(HttpError.invalidJson("bad").error()).isEqualTo(UseCaseError.validation("INVALID_JSON", "bad"));
        assertThat(HttpError.conflict("C", "m").error()).isEqualTo(UseCaseError.conflict("C", "m"));
        assertThat(HttpError.unauthenticated().error()).isEqualTo(UseCaseError.authorization("UNAUTHENTICATED", "authentication required"));
        assertThat(HttpError.isNotFound(HttpError.notFound("A", "1"))).isTrue();
        assertThat(HttpError.isValidation(HttpError.badRequest("A", "1"))).isTrue();
        assertThat(HttpError.isValidation(new RuntimeException())).isFalse();
        assertThat(HttpError.of(UseCaseError.notFound("A_NOT_FOUND", "m")).toJson())
                .isEqualTo("{\"error\":\"A_NOT_FOUND\",\"message\":\"m\"}\n");
    }
}
