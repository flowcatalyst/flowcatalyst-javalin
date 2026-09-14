package io.flowcatalyst.platform.dispatch.api;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The document builder is supplied per request (`RouterConfigApi.State`), so
/// an SQS deployment with no queue prefix answers 503 on this one route
/// instead of stopping the platform at boot (owner, 2026-09-14).
class RouterConfigApiTest {

    private static final AuthContext ROUTER = new AuthContext("prn_router", Scope.ANCHOR, null,
            List.of("*"), List.of("platform:router"), List.of(), true, List.of(Permission.DISPATCH_POOL_VIEW.code()));

    @Test
    @DisplayName("unusable dispatch-queue settings answer 503 DISPATCH_QUEUE_UNCONFIGURED with the reason, per request")
    void unconfiguredSettingsAnswer503() {
        String reason = "FC_DISPATCH_QUEUE_PREFIX is required when FC_DISPATCH_QUEUE_TYPE=SQS";
        try (var http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before(ctx -> Auth.bind(ctx, ROUTER));
            RouterConfigApi.register(routes, new RouterConfigApi.State(() -> {
                throw new IllegalStateException(reason);
            }));
        })) {
            HttpResponse<String> r = http.get("/api/dispatch/router-config");
            assertThat(r.statusCode()).as("mutant: the supplier's refusal escapes as a 500").isEqualTo(503);
            JsonNode body = Json.MAPPER.readTree(r.body());
            assertThat(body.get("error").asString()).isEqualTo(RouterConfigApi.UNCONFIGURED);
            assertThat(body.get("message").asString()).contains("FC_DISPATCH_QUEUE_PREFIX");
        }
    }
}
