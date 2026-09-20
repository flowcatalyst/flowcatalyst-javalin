package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.shared.json.Json;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// docs/spec/jvm-memory.md §4: `/ready`'s body carries a `memory` object
/// naming the split `docker/jvm-opts.sh` actually fenced this process into,
/// read live from the JVM ([FnMemorySnapshot]) — end-to-end through the real
/// HTTP handler, not just the snapshot class in isolation
/// ([FnMemorySnapshotTest] covers that).
class FnObservabilityReadyMemoryTest {

    @Test
    void readyBodyCarriesTheMemoryObjectWithHeapAlwaysPresentAndUnboundedPoolsOmitted(@TempDir Path dir)
            throws Exception {
        try (var h = FnHttpTestSupport.start(dir, FnHttpTestSupport.document(List.of()));
             var observability = FnObservability.start(h.reconciler, new PrometheusRegistry(),
                     FnObservability.Options.of(0))) {

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + observability.port() + "/ready"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(resp.statusCode()).as("empty document reconciles to READY").isEqualTo(200);

            JsonNode body = Json.MAPPER.readTree(resp.body());
            assertThat(body.path("status").asString()).isEqualTo("UP");

            JsonNode memory = body.path("memory");
            assertThat(memory.isMissingNode()).as("mutant: no memory object at all. body: " + resp.body()).isFalse();

            // heapMaxBytes: always present, always a real positive number.
            assertThat(memory.has("heapMaxBytes")).as("body: " + resp.body()).isTrue();
            assertThat(memory.path("heapMaxBytes").asLong())
                    .as("mutant: report -1. body: " + resp.body())
                    .isPositive();

            // This test JVM (maven-surefire's forked process) sets neither
            // -XX:MaxMetaspaceSize nor -XX:MaxDirectMemorySize, and no
            // FC_JVM_MEMORY_LIMIT_FILE/cgroup file is present in this
            // environment either -- all three are genuinely unbounded/unknown
            // here, so the mutant this pins is "report -1" rather than
            // "report a fabricated positive number", which FnMemorySnapshotTest's
            // forked-JVM case already covers for the bounded direction.
            assertThat(memory.has("metaspaceMaxBytes"))
                    .as("mutant: report -1 instead of omitting. body: " + resp.body()).isFalse();
            assertThat(memory.has("directMaxBytes"))
                    .as("mutant: report -1 instead of omitting. body: " + resp.body()).isFalse();
            assertThat(memory.has("limitBytes"))
                    .as("mutant: report -1 instead of omitting. body: " + resp.body()).isFalse();
        }
    }
}
