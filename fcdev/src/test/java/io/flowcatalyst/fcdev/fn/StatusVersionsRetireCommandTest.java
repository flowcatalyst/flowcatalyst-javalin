package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn status`, `fn versions`, `fn retire` — thin read/write wrappers; one
/// happy-path test each, plus `--output json` producing parseable JSON.
class StatusVersionsRetireCommandTest {

    private Map<String, String> env(FakePlatform platform) {
        var env = new HashMap<String, String>();
        env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
        env.put("FLOWCATALYST_CLIENT_ID", "id");
        env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
        return env;
    }

    @Test
    void statusPrintsVersionsAliasesHostsAndWiring() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/status", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "address", "app.svc.fn", "status", "ACTIVE", "live", Map.of("version", 2),
                    "versions", java.util.List.of(Map.of("version", 1, "state", "RETIRED"), Map.of("version", 2, "state", "READY")),
                    "hosts", java.util.List.of(Map.of("hostId", "h1", "pool", "default", "state", "ACTIVE",
                            "lastHeartbeat", "2026-01-01T00:00:00.000000Z", "stale", false,
                            "loaded", java.util.List.of(Map.of("version", 2, "state", "LOADED")))),
                    "wiring", java.util.List.of(Map.of("kind", "SUBSCRIPTION", "code", "x:y:z", "objectId", "sub_1", "present", true))
            )));
            platform.on("GET", "/api/functions/app.svc.fn/aliases", ex -> FakePlatform.writeJson(ex, 200, java.util.List.of(
                    Map.of("alias", "live", "version", 2, "versionId", "fnv_2", "updatedBy", "prn_1", "updatedAt", "2026-01-01T00:00:00.000000Z"),
                    Map.of("alias", "qa", "version", 1, "versionId", "fnv_1", "updatedBy", "prn_1", "updatedAt", "2026-01-01T00:00:00.000000Z")
            )));
            var r = FnCliTestSupport.run(env(platform), "fn", "status", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("v2").contains("READY").contains("h1").contains("SUBSCRIPTION");
            assertThat(r.out()).as("mutant: drop the aliases section").contains("qa -> v1").contains("live -> v2");
        }
    }

    @Test
    void statusJsonOutputIsOneMachineReadableObject() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/status", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "address", "app.svc.fn", "status", "ACTIVE",
                    "versions", java.util.List.of(), "hosts", java.util.List.of(), "wiring", java.util.List.of())));
            platform.on("GET", "/api/functions/app.svc.fn/aliases", ex -> FakePlatform.writeJson(ex, 200, java.util.List.of()));
            var r = FnCliTestSupport.run(env(platform), "fn", "--output", "json", "status", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(r.out().strip());
            assertThat(node.path("status").path("address").asString()).isEqualTo("app.svc.fn");
            assertThat(node.path("aliases").isArray()).as("mutant: drop aliases from json output").isTrue();
        }
    }

    @Test
    void versionsListsNewestFirstAsGivenByThePlatform() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/versions", ex -> FakePlatform.writeJson(ex, 200, java.util.List.of(
                    Map.of("id", "fnv_2", "version", 2, "state", "PUBLISHED", "digest", "sha256:" + "b".repeat(64), "live", false),
                    Map.of("id", "fnv_1", "version", 1, "state", "RETIRED", "digest", "sha256:" + "a".repeat(64), "live", false)
            )));
            var r = FnCliTestSupport.run(env(platform), "fn", "versions", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out().indexOf("v2")).isLessThan(r.out().indexOf("v1"));
        }
    }

    @Test
    void retirePrintsTheRetiredVersion() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("POST", "/api/functions/app.svc.fn/versions/1/retire", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "id", "fnv_1", "version", 1, "state", "RETIRED", "digest", "sha256:" + "a".repeat(64), "live", false)));
            var r = FnCliTestSupport.run(env(platform), "fn", "retire", "app.svc.fn", "--version", "1");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("version 1 retired");
        }
    }

    /// The platform's `VERSION_IS_LIVE` refusal surfaces as an ordinary
    /// error — retire never guesses around it.
    @Test
    void retiringTheLiveVersionSurfacesVersionIsLive() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("POST", "/api/functions/app.svc.fn/versions/2/retire", ex ->
                    FakePlatform.writeError(ex, 409, "VERSION_IS_LIVE", "promote another version first"));
            var r = FnCliTestSupport.run(env(platform), "fn", "retire", "app.svc.fn", "--version", "2");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("VERSION_IS_LIVE").contains("promote another version first");
        }
    }
}
