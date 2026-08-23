package io.flowcatalyst.platform.docs.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.docs.AppDocFixture;
import io.flowcatalyst.platform.docs.PublishedDocs;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static io.flowcatalyst.platform.docs.AppDocFixture.input;
import static io.flowcatalyst.platform.docs.AppDocFixture.replace;
import static org.assertj.core.api.Assertions.assertThat;

/// The three `/api/docs` routes end to end through Javalin (spec §4): the
/// view gate, the grouped index, the platform and application page
/// envelopes, and the 404s.
class DocsApiTest {

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_docsapi_" + AppDocFixture.RUN,
            Authenticator.TEST_PERMISSIONS, "platform:admin:docs:view"};
    private static final String[] NO_PERMISSION = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_docsapi_" + AppDocFixture.RUN,
            Authenticator.TEST_PERMISSIONS, "platform:admin:audit-log:view"};

    private static TestHttp http;
    private static String appCode;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var state = new DocsApi.State(AppDocFixture.DOCS, AppDocFixture.APPS, PublishedDocs.load());
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            DocsApi.register(cfg.routes, state);
        });

        var app = AppDocFixture.application("api", "Docs API App " + AppDocFixture.RUN);
        appCode = app.code();
        replace(app.id(), List.of(
                input("guide", "Integration Guide", "# Integration Guide\nBody."),
                input("faq", "FAQ", "# FAQ\nQ&A.")));
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + r.body(), e);
        }
    }

    // ── Gate ──────────────────────────────────────────────────────────────

    @Test
    void anonymousAndPermissionlessCallersAreRefusedGrantedClientAndAnchorPass() {
        var anon = http.get("/api/docs");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");

        var noPerm = http.get("/api/docs", NO_PERMISSION);
        assertThat(noPerm.statusCode()).isEqualTo(403);
        assertThat(json(noPerm).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(json(noPerm).get("message").asText()).isEqualTo("permission required: platform:admin:docs:view");

        assertThat(http.get("/api/docs", VIEWER).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/docs", ANCHOR).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/docs/platform/platform-overview", NO_PERMISSION).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/docs/applications/" + appCode + "/guide", NO_PERMISSION).statusCode()).isEqualTo(403);
    }

    // ── Index ─────────────────────────────────────────────────────────────

    @Test
    void indexListsPublishedPagesInOrderAndSyncedApplicationsGrouped() {
        var r = http.get("/api/docs", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);

        JsonNode platform = body.get("platform");
        assertThat(platform.isArray()).isTrue();
        assertThat(platform.get(0).get("slug").asText()).isEqualTo("platform-overview");
        assertThat(platform.get(0).get("title").asText()).isEqualTo("Platform Overview");
        var slugs = new ArrayList<String>();
        platform.forEach(p -> slugs.add(p.get("slug").asText()));
        assertThat(slugs).contains("portal-users").doesNotContain("10-platform-overview");

        JsonNode apps = body.get("applications");
        assertThat(apps.isArray()).as("applications is [] never null").isTrue();
        JsonNode group = null;
        for (JsonNode g : apps) {
            if (g.get("applicationCode").asText().equals(appCode)) group = g;
        }
        assertThat(group).as("synced app appears in the grouped list").isNotNull();
        assertThat(group.get("applicationName").asText()).startsWith("Docs API App");
        assertThat(group.get("docs")).hasSize(2);
        assertThat(group.get("docs").get(0).get("slug").asText()).isEqualTo("guide");
        assertThat(group.get("docs").get(0).get("title").asText()).isEqualTo("Integration Guide");
        assertThat(group.get("docs").get(1).get("slug").asText()).isEqualTo("faq");
        assertThat(group.get("docs").get(0).has("content")).as("summaries carry no body").isFalse();
    }

    @Test
    void applicationGroupsAreSortedByName() {
        var b = AppDocFixture.application("sortb", "B docs " + AppDocFixture.RUN);
        var a = AppDocFixture.application("sorta", "A docs " + AppDocFixture.RUN);
        replace(b.id(), List.of(input("one", "One", "1")));
        replace(a.id(), List.of(input("one", "One", "1")));
        var names = new ArrayList<String>();
        json(http.get("/api/docs", ANCHOR)).get("applications").forEach(g -> names.add(g.get("applicationName").asText()));
        assertThat(names).isSorted();
        assertThat(names.indexOf(a.name())).isLessThan(names.indexOf(b.name()));
    }

    // ── Pages ─────────────────────────────────────────────────────────────

    @Test
    void platformPageReturnsRawMarkdown() {
        var r = http.get("/api/docs/platform/platform-overview", VIEWER);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode doc = json(r);
        assertThat(doc.get("slug").asText()).isEqualTo("platform-overview");
        assertThat(doc.get("title").asText()).isEqualTo("Platform Overview");
        assertThat(doc.get("content").asText()).startsWith("# Platform Overview");
    }

    @Test
    void platformPageUnknownOrPrefixedOrTraversalSlugsAre404() {
        for (String slug : List.of("10-platform-overview", "nope", "..%2Fembed.go")) {
            var r = http.get("/api/docs/platform/" + slug, ANCHOR);
            assertThat(r.statusCode()).as(slug).isEqualTo(404);
            assertThat(json(r).get("error").asText()).isEqualTo("Doc_NOT_FOUND");
        }
    }

    @Test
    void applicationPageReturnsTheStoredMarkdown() {
        var r = http.get("/api/docs/applications/" + appCode + "/guide", VIEWER);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode doc = json(r);
        assertThat(doc.get("slug").asText()).isEqualTo("guide");
        assertThat(doc.get("title").asText()).isEqualTo("Integration Guide");
        assertThat(doc.get("content").asText()).contains("Body.");
    }

    @Test
    void applicationPage404sDistinguishUnknownAppFromUnknownPage() {
        var missingDoc = http.get("/api/docs/applications/" + appCode + "/missing", ANCHOR);
        assertThat(missingDoc.statusCode()).isEqualTo(404);
        assertThat(json(missingDoc).get("error").asText()).isEqualTo("Doc_NOT_FOUND");
        assertThat(json(missingDoc).get("message").asText()).isEqualTo("Doc not found: missing");

        var missingApp = http.get("/api/docs/applications/no-such-app-" + AppDocFixture.RUN + "/guide", ANCHOR);
        assertThat(missingApp.statusCode()).isEqualTo(404);
        assertThat(json(missingApp).get("error").asText()).isEqualTo("Application_NOT_FOUND");
    }
}
