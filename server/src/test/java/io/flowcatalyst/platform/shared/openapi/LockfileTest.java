package io.flowcatalyst.platform.shared.openapi;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("deprecation")
class LockfileTest {

    @Test
    void loadsTheEmbeddedContract() {
        var lock = Lockfile.load(new ObjectMapper());
        // +4 paths / +6 operations since the 2fe6bf0 re-vendor (spec `portal-apps.md`):
        // grantPortalUserApp/revokePortalUserApp, listPortalApps, createPortalApp,
        // updatePortalApp, deletePortalApp — ensurePortalUser/listPortalUsers extended
        // in place, no new path. +1 path / +1 operation at the 13e80c9 re-vendor:
        // assignUnassignedPortalUsers (`POST /api/portal-apps/{id}/assign-unassigned`).
        assertThat(lock.pathCount()).isEqualTo(186);
        var ops = lock.operations();
        assertThat(ops).hasSize(253);
        assertThat(ops).anyMatch(o -> o.method().equals("GET") && o.path().equals("/api/event-types"));
        assertThat(ops).anyMatch(o -> o.path().contains("{id}"));
        assertThat(lock.json().path("openapi").asText()).startsWith("3.");
        assertThat(lock.bytes()).isNotEmpty();
    }
}
