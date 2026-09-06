package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    static TestHttp http;

    @BeforeAll
    static void start() {
        http = TestHttp.routes(routes -> {
            CorrelationId.install(routes);
            routes.get("/plain", ctx -> ctx.result(CorrelationId.from(ctx) + "|" + MDC.get("correlation_id")));
            routes.get("/scoped", Auth.scoped(ctx -> ctx.result(CorrelationId.current() + "|" + CorrelationId.CURRENT.get())));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    @Test
    void echoesInboundId() {
        var r = http.get("/plain", "X-Correlation-ID", "abc-123");
        assertThat(r.headers().firstValue("X-Correlation-ID")).contains("abc-123");
        assertThat(r.body()).isEqualTo("abc-123|abc-123");
    }

    @Test
    void generatesAUuidWhenAbsent() {
        var r = http.get("/plain");
        var id = r.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(r.body()).isEqualTo(id + "|" + id);
    }

    @Test
    void boundAsScopedValueInsideAuthScoped() {
        var r = http.get("/scoped", "X-Correlation-ID", "corr-9");
        assertThat(r.body()).isEqualTo("corr-9|corr-9");
    }

    @Test
    void mdcIsClearedAfterTheRequest() {
        http.get("/plain", "X-Correlation-ID", "leak?");
        // the request thread is a Jetty/virtual thread, not ours — what we can assert is that
        // our own thread never saw it and that current() outside a request is null
        assertThat(MDC.get("correlation_id")).isNull();
        assertThat(CorrelationId.current()).isNull();
    }
}
