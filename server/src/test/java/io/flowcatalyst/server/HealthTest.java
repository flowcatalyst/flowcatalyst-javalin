package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Ruling C-Q23: readiness is a real signal — a failing check turns the
/// answer into 503 `DOWN` naming the problem, so a load balancer stops
/// routing to an instance whose backoff store cannot be written.
class HealthTest {

    @Test
    void withoutChecksTheBodyIsTheBareStatusAndVersion() {
        try (var h = new TestHttp(cfg -> cfg.routes.get("/health", Health.noChecks()::handle))) {
            var r = h.get("/health");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("{\"status\":\"UP\",\"version\":\"dev\"}\n");
        }
    }

    @Test
    void aFailingCheckIs503DownNamingTheProblemAndRecoversWhenItPasses() {
        var problem = new AtomicReference<>("missing partitions: iam_login_attempts_2026_q4");
        var health = new Health(List.of(
                new Health.Check("loginAttemptPartitions", problem::get),
                new Health.Check("alwaysFine", () -> "")));
        try (var h = new TestHttp(cfg -> cfg.routes.get("/health", health::handle))) {
            var down = h.get("/health");
            assertThat(down.statusCode()).isEqualTo(503);
            var j = Json.MAPPER.readTree(down.body());
            assertThat(j.get("status").asString()).isEqualTo("DOWN");
            assertThat(j.get("checks").get("loginAttemptPartitions").asString()).contains("iam_login_attempts_2026_q4");
            assertThat(j.get("checks").get("alwaysFine").asString()).isEqualTo("ok");

            problem.set("");
            var up = h.get("/health");
            assertThat(up.statusCode()).isEqualTo(200);
            assertThat(Json.MAPPER.readTree(up.body()).get("status").asString()).isEqualTo("UP");
        }
    }

    @Test
    void aCheckThatThrowsIsAFailureNotA500() {
        var health = new Health(List.of(new Health.Check("db", () -> { throw new IllegalStateException("pool closed"); })));
        try (var h = new TestHttp(cfg -> cfg.routes.get("/health", health::handle))) {
            var r = h.get("/health");
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(Json.MAPPER.readTree(r.body()).get("checks").get("db").asString()).isEqualTo("IllegalStateException: pool closed");
        }
    }
}
