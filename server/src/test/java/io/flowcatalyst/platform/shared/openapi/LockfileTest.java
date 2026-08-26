package io.flowcatalyst.platform.shared.openapi;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("deprecation")
class LockfileTest {

    @Test
    void loadsTheEmbeddedContract() {
        var lock = Lockfile.load(new ObjectMapper());
        assertThat(lock.pathCount()).isEqualTo(178);
        var ops = lock.operations();
        assertThat(ops).hasSize(243);
        assertThat(ops).anyMatch(o -> o.method().equals("GET") && o.path().equals("/api/event-types"));
        assertThat(ops).anyMatch(o -> o.path().contains("{id}"));
        assertThat(lock.json().path("openapi").asText()).startsWith("3.");
        assertThat(lock.bytes()).isNotEmpty();
    }
}
