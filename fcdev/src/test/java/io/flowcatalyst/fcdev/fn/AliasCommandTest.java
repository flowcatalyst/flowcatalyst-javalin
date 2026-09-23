package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn alias list|delete` (`docs/spec/function-zones-and-aliases.md` §6),
/// against [FakePlatform].
class AliasCommandTest {

    private Map<String, String> env(FakePlatform platform) {
        var env = new HashMap<String, String>();
        env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
        env.put("FLOWCATALYST_CLIENT_ID", "id");
        env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
        return env;
    }

    @Test
    void listPrintsEveryAlias() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/aliases", ex -> FakePlatform.writeJson(ex, 200, java.util.List.of(
                    Map.of("alias", "live", "version", 2, "versionId", "fnv_2", "updatedBy", "prn_1", "updatedAt", "2026-01-01T00:00:00.000000Z"),
                    Map.of("alias", "qa", "version", 1, "versionId", "fnv_1", "updatedBy", "prn_1", "updatedAt", "2026-01-01T00:00:00.000000Z")
            )));
            var r = FnCliTestSupport.run(env(platform), "fn", "alias", "list", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).as("mutant: drop a row, or drop the whole list").contains("live -> v2").contains("qa -> v1");
        }
    }

    @Test
    void listOnAFunctionWithNoAliasesPrintsNone() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/aliases", ex -> FakePlatform.writeJson(ex, 200, java.util.List.of()));
            var r = FnCliTestSupport.run(env(platform), "fn", "alias", "list", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("(no aliases)");
        }
    }

    @Test
    void listJsonOutputIsAMachineReadableArray() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/aliases", ex -> FakePlatform.writeJson(ex, 200, java.util.List.of(
                    Map.of("alias", "live", "version", 1, "versionId", "fnv_1", "updatedBy", "prn_1", "updatedAt", "2026-01-01T00:00:00.000000Z"))));
            var r = FnCliTestSupport.run(env(platform), "fn", "--output", "json", "alias", "list", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(r.out().strip());
            assertThat(node.isArray()).isTrue();
            assertThat(node.get(0).path("alias").asString()).isEqualTo("live");
        }
    }

    @Test
    void deleteCallsTheDeleteRouteForTheNamedAlias() throws Exception {
        try (var platform = FakePlatform.start()) {
            var called = new AtomicBoolean();
            platform.on("DELETE", "/api/functions/app.svc.fn/aliases/qa", ex -> {
                called.set(true);
                FakePlatform.writeNoBody(ex, 204);
            });
            var r = FnCliTestSupport.run(env(platform), "fn", "alias", "delete", "app.svc.fn", "qa");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(called.get()).as("mutant: never call the delete route").isTrue();
            assertThat(r.out()).contains("qa").contains("deleted");
        }
    }

    /// The platform's `ALIAS_PROTECTED` refusal (removing `live`) surfaces as
    /// an ordinary error — `fn alias delete` never special-cases it.
    @Test
    void deletingLiveSurfacesAliasProtected() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("DELETE", "/api/functions/app.svc.fn/aliases/live", ex ->
                    FakePlatform.writeError(ex, 409, "ALIAS_PROTECTED", "promote another version; live cannot be removed"));
            var r = FnCliTestSupport.run(env(platform), "fn", "alias", "delete", "app.svc.fn", "live");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("ALIAS_PROTECTED").contains("live cannot be removed");
        }
    }
}
