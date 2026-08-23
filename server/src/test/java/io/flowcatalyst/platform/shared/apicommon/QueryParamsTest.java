package io.flowcatalyst.platform.shared.apicommon;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The one query-parameter parse helper: absent/empty is absent, whitespace
/// is trimmed, and a bad value is the pinned `VALIDATION` envelope — the
/// same bytes `PageQuery`, the audit list and the login-attempt list emit.
class QueryParamsTest {

    static TestHttp http;

    @BeforeAll
    static void start() {
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.get("/one", ctx -> ctx.result(QueryParams.intParam(ctx, "n").isPresent()
                    ? Integer.toString(QueryParams.intParam(ctx, "n").getAsInt()) : "absent"));
            cfg.routes.get("/many", ctx -> {
                var errors = new ArrayList<Map<String, Object>>();
                int a = QueryParams.intParam(ctx, "a", errors).orElse(-1);
                int b = QueryParams.intParam(ctx, "b", errors).orElse(-1);
                if (!errors.isEmpty()) throw QueryParams.validation(errors);
                ctx.result(a + "/" + b);
            });
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    @Test
    void absentOrEmptyIsAbsentAndAValueIsTrimmed() {
        assertThat(http.get("/one").body()).isEqualTo("absent");
        assertThat(http.get("/one?n=").body()).isEqualTo("absent");
        assertThat(http.get("/one?n=%2042%20").body()).isEqualTo("42");
        assertThat(http.get("/one?n=-7").body()).isEqualTo("-7");
    }

    @Test
    void aNonIntegerIsTheValidationEnvelope() {
        var r = http.get("/one?n=ten");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"VALIDATION\",\"message\":\"validation failed\","
                + "\"details\":{\"errors\":[{\"message\":\"invalid integer\",\"location\":\"query.n\",\"value\":\"ten\"}]}}\n");
    }

    @Test
    void theAccumulatingFormReportsEveryBadParameterInReadOrder() {
        assertThat(http.get("/many?a=1&b=2").body()).isEqualTo("1/2");
        assertThat(http.get("/many?b=2").body()).isEqualTo("-1/2");
        var r = http.get("/many?a=x&b=y");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"VALIDATION\",\"message\":\"validation failed\",\"details\":{\"errors\":["
                + "{\"message\":\"invalid integer\",\"location\":\"query.a\",\"value\":\"x\"},"
                + "{\"message\":\"invalid integer\",\"location\":\"query.b\",\"value\":\"y\"}]}}\n");
    }

    @Test
    void theErrorEntryAndEnvelopeAreOneShape() {
        var entry = QueryParams.error("invalid integer", "pageSize", "ten");
        assertThat(entry).containsExactly(Map.entry("message", "invalid integer"),
                Map.entry("location", "query.pageSize"), Map.entry("value", "ten"));
        var err = QueryParams.validation(List.of(entry)).error();
        assertThat(err).isInstanceOf(UseCaseError.Validation.class);
        assertThat(err.code()).isEqualTo("VALIDATION");
        assertThat(err.message()).isEqualTo("validation failed");
        assertThat(err.details()).isEqualTo(Map.of("errors", List.of(entry)));
    }
}
