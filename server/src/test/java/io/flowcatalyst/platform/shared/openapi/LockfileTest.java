package io.flowcatalyst.platform.shared.openapi;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("deprecation")
class LockfileTest {

    @Test
    void loadsTheEmbeddedContract() {
        var lock = Lockfile.load(new ObjectMapper());
        // +2 paths / +2 operations: cancelDispatchJob, completeDispatchJob (dispatch-seam spec §8).
        assertThat(lock.pathCount()).isEqualTo(180);
        var ops = lock.operations();
        assertThat(ops).hasSize(245);
        assertThat(ops).anyMatch(o -> o.method().equals("GET") && o.path().equals("/api/event-types"));
        assertThat(ops).anyMatch(o -> o.path().contains("{id}"));
        assertThat(lock.json().path("openapi").asText()).startsWith("3.");
        assertThat(lock.bytes()).isNotEmpty();
    }
}
