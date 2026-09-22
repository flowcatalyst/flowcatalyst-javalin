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
        // +1 path / +1 operation at the f81fd5a re-vendor: `GET /api/dispatch/router-config`
        // (`router-config-auth.md` R3′, until then a Java-first route outside the lockfile);
        // the same re-vendor added the CreatePrincipalResponse schema (`app-managed-invitations.md`).
        // +1 path / +1 operation at the 07184da re-vendor (spec `code-first-connections.md`):
        // `POST /api/applications/{appCode}/connections/sync` (syncConnections) — not yet
        // routed by Java (K1); see LockfileCoverageTest#KNOWN_MISSING, owed to K2.
        // +1 path / +1 operation at the b60d75c re-vendor (catch-up-2026-09-22.md slice C1/C2):
        // `POST /api/dispatch-jobs/{id}/sign` (signDispatchJob) — not yet routed by Java,
        // owed to C3; see LockfileCoverageTest#KNOWN_MISSING.
        assertThat(lock.pathCount()).isEqualTo(189);
        var ops = lock.operations();
        assertThat(ops).hasSize(256);
        assertThat(ops).anyMatch(o -> o.method().equals("GET") && o.path().equals("/api/event-types"));
        assertThat(ops).anyMatch(o -> o.path().contains("{id}"));
        assertThat(lock.json().path("openapi").asText()).startsWith("3.");
        assertThat(lock.bytes()).isNotEmpty();
    }
}
