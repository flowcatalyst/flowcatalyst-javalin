package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn domain claim|verify|list|release` (`docs/spec/function-public-routes.md`
/// §1, §5) against [FakePlatform] — the CLI's own logic (request shape,
/// output modes, error mapping), not the platform's own validation (that is
/// `FunctionDomainApiTest`'s job on the server side).
class DomainCommandTest {

    @TempDir
    Path dir;

    private Map<String, String> env(FakePlatform platform) {
        var env = new HashMap<String, String>();
        env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
        env.put("FLOWCATALYST_CLIENT_ID", "id");
        env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
        env.put("XDG_DATA_HOME", dir.resolve("state").toString());
        return env;
    }

    private static String pendingDomainJson(String hostname) {
        return """
                {"id":"dom_1","hostname":"%s","owner":"platform",
                 "verification":{"state":"PENDING","record":{"type":"TXT","name":"_flowcatalyst.%s","value":"fc-verify=abc123"}},
                 "createdAt":"2026-01-01T00:00:00Z"}
                """.formatted(hostname, hostname);
    }

    private static String verifiedDomainJson(String hostname) {
        return """
                {"id":"dom_1","hostname":"%s","owner":"platform",
                 "verification":{"state":"VERIFIED"},
                 "createdAt":"2026-01-01T00:00:00Z"}
                """.formatted(hostname);
    }

    /// spec `function-zones-and-aliases.md` §6: "`fn domain claim` help says
    /// a claim covers the zone" — pinned here since `FnCliHelpTest` only
    /// checks that every subcommand is LISTED, not what any one says.
    @Test
    void claimHelpTextSaysAClaimCoversTheZone() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "domain", "claim", "--help");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).as("mutant: revert to the old exact-hostname wording")
                .contains("covers every hostname under it");
    }

    // ── claim: prints the TXT record exactly as the platform returned it ──

    @Test
    void claimPrintsTheTxtRecordVerbatim() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> sentBody = new AtomicReference<>();
            platform.on("POST", "/api/function-domains", ex -> {
                sentBody.set(FakePlatform.bodyOf(ex));
                writeRaw(ex, 201, pendingDomainJson("api.acme.com"));
            });
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "claim", "api.acme.com");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("PENDING");
            assertThat(r.out()).as("mutant: reconstruct the record client-side instead of printing the platform's own")
                    .contains("_flowcatalyst.api.acme.com").contains("fc-verify=abc123");
            assertThat(sentBody.get()).contains("\"hostname\":\"api.acme.com\"");
            assertThat(sentBody.get()).as("no --client given: clientId is never sent").doesNotContain("clientId");
        }
    }

    @Test
    void claimWithClientSendsClientId() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> sentBody = new AtomicReference<>();
            platform.on("POST", "/api/function-domains", ex -> {
                sentBody.set(FakePlatform.bodyOf(ex));
                writeRaw(ex, 201, pendingDomainJson("shop.acme.com"));
            });
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "claim", "shop.acme.com", "--client", "clt_1");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(sentBody.get()).contains("\"clientId\":\"clt_1\"");
        }
    }

    @Test
    void claimJsonOutputModePrintsTheRawResponse() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("POST", "/api/function-domains", ex -> writeRaw(ex, 201, pendingDomainJson("api.acme.com")));
            var r = FnCliTestSupport.run(env(platform), "fn", "--output", "json", "domain", "claim", "api.acme.com");
            assertThat(r.exit()).as(r.err()).isZero();
            var json = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(r.out());
            assertThat(json.path("verification").path("state").asString()).isEqualTo("PENDING");
        }
    }

    /// Mutant: swallow/misreport the platform's conflict — DOMAIN_TAKEN must
    /// surface as exit 1 with the platform's own code/message, never a stack trace.
    @Test
    void claimConflictExitsOneWithThePlatformsCode() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("POST", "/api/function-domains", ex ->
                    FakePlatform.writeError(ex, 409, "DOMAIN_TAKEN", "hostname is already claimed"));
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "claim", "api.acme.com");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("DOMAIN_TAKEN");
            assertThat(r.err()).doesNotContain("Exception").doesNotContain("\tat ");
        }
    }

    // ── verify ──

    @Test
    void verifyPrintsTheResultingState() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("POST", "/api/function-domains/api.acme.com/verify",
                    ex -> writeRaw(ex, 200, verifiedDomainJson("api.acme.com")));
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "verify", "api.acme.com");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("VERIFIED");
        }
    }

    @Test
    void verifyNotYetVerifiedExitsOneWithThePlatformsCode() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("POST", "/api/function-domains/api.acme.com/verify", ex ->
                    FakePlatform.writeError(ex, 409, "DOMAIN_NOT_VERIFIED", "no matching TXT record found"));
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "verify", "api.acme.com");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("DOMAIN_NOT_VERIFIED");
        }
    }

    // ── list: defaults to platform-owned, --client passes through ──

    @Test
    void listWithNoClientDefaultsToPlatformOwned() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> path = new AtomicReference<>();
            platform.on("GET", "/api/function-domains", ex -> {
                path.set(ex.getRequestURI().toString());
                writeRaw(ex, 200, "[" + verifiedDomainJson("api.acme.com") + "]");
            });
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "list");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(path.get()).as("mutant: omit clientId, or default to something other than platform")
                    .contains("clientId=platform");
            assertThat(r.out()).contains("api.acme.com").contains("VERIFIED");
        }
    }

    @Test
    void listWithClientPassesItThrough() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> path = new AtomicReference<>();
            platform.on("GET", "/api/function-domains", ex -> {
                path.set(ex.getRequestURI().toString());
                writeRaw(ex, 200, "[]");
            });
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "list", "--client", "clt_9");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(path.get()).contains("clientId=clt_9");
            assertThat(r.out()).contains("no domains claimed");
        }
    }

    // ── release ──

    @Test
    void releaseCallsDeleteAndConfirms() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> method = new AtomicReference<>();
            platform.on("DELETE", "/api/function-domains/api.acme.com", ex -> {
                method.set(ex.getRequestMethod());
                FakePlatform.writeNoBody(ex, 204);
            });
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "release", "api.acme.com");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(method.get()).isEqualTo("DELETE");
            assertThat(r.out()).contains("api.acme.com").contains("released");
        }
    }

    @Test
    void releaseInUseExitsOneNamingTheFunctions() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("DELETE", "/api/function-domains/api.acme.com", ex ->
                    FakePlatform.writeError(ex, 409, "DOMAIN_IN_USE", "still routed: acme.default.billing"));
            var r = FnCliTestSupport.run(env(platform), "fn", "domain", "release", "api.acme.com");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("DOMAIN_IN_USE");
        }
    }

    private static void writeRaw(com.sun.net.httpserver.HttpExchange ex, int status, String body) {
        try {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
