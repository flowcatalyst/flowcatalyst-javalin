package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/// Staging, 2026-09-14: the Go platform exited at boot with "FC_DISPATCH_QUEUE_PREFIX
/// is required when FC_DISPATCH_QUEUE_TYPE=SQS", and the Java platform had the same
/// trap — `Platform.register` resolved the dispatch-queue settings eagerly to wire the
/// router-config document. The API tier publishes nothing, so it must boot; the
/// document route answers 503 instead (RouterConfigApi). The scheduler role keeps its
/// eager refusal (Server#schedulerPublisher).
class PlatformBootsWithoutDispatchPrefixTest {

    @Test
    @DisplayName("an SQS deployment with no queue prefix still wires the platform API")
    void platformWiresWithoutThePrefix() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_DISPATCH_QUEUE_TYPE", "SQS",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.eu-west-1.amazonaws.com/123456789012/inhance-fc-np-dispatch.fifo"));
        var server = new Server(env, new Server.Mode.Platform(Pools.ofSingle(TestPg.dataSource())), Server.Spa.none(),
                new PrometheusRegistry());
        assertThatCode(server::buildApi)
                .as("mutant: the settings resolved eagerly at wiring time")
                .doesNotThrowAnyException();
    }
}
