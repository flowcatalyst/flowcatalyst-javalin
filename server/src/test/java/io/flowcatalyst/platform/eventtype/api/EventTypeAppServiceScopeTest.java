package io.flowcatalyst.platform.eventtype.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// An application's service account lists its event types and pushes their
/// schemas through the plain event-type endpoints (the SDK's schema sync does
/// exactly this). It holds the application-service permissions, not the
/// messaging ones, and is confined to the applications it is bound to.
/// Mirrors Go's `app_service_scope_pg_test.go`, through the real routes and
/// database.
@SuppressWarnings("deprecation")
class EventTypeAppServiceScopeTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String MINE = "etscopemine" + RUN;
    private static final String OTHER = "etscopeother" + RUN;
    private static final String MINE_CODE = MINE + ":orders:order:created";
    private static final String OTHER_CODE = OTHER + ":orders:order:created";
    private static final String SCHEMA = "{\"version\":\"1.0.0\",\"schema\":{\"type\":\"object\"}}";

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    private static final HttpClient client = HttpClient.newHttpClient();
    private static TestHttp http;
    private static EventTypeRepository repo;
    private static String mineAppId;
    private static String ownId;
    private static String foreignId;
    private static String[] svc;
    private static String[] viewOnly;

    @BeforeAll
    static void start() {
        var ds = TestPg.dataSource();
        var uow = new UnitOfWork(ds, new PlatformSink(Json.MAPPER));
        repo = new EventTypeRepository(ds);
        var apps = new ApplicationRepository(ds);
        var mine = Application.create(ApplicationType.INTEGRATION, MINE, "Mine");
        var other = Application.create(ApplicationType.INTEGRATION, OTHER, "Other");
        uow.inTransaction(tx -> {
            apps.persist(mine, tx.dbTx());
            apps.persist(other, tx.dbTx());
            return null;
        });
        mineAppId = mine.id();

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            EventTypeApi.register(routes, new EventTypeApi.State(repo, apps, uow));
        });

        ownId = create(MINE_CODE);
        foreignId = create(OTHER_CODE);
        svc = bound("platform:application-service:event-type:view,platform:application-service:event-type:create,"
                + "platform:application-service:event-type:update");
        viewOnly = bound("platform:application-service:event-type:view");
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static String[] bound(String permissions) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_APPLICATIONS, mineAppId,
                Authenticator.TEST_PERMISSIONS, permissions};
    }

    private static HttpResponse<String> send(String method, String path, String body, String... headers) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + http.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        b.headers(headers);
        try {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String create(String code) {
        var r = send("POST", "/api/event-types", "{\"code\":\"" + code + "\",\"name\":\"Order created\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r).get("id").asText();
    }

    private static List<String> codes(HttpResponse<String> r) {
        var out = new ArrayList<String>();
        json(r).get("items").forEach(n -> out.add(n.get("code").asText()));
        return out;
    }

    @Test
    @DisplayName("list shows only its own application's event types")
    void confinedList() {
        var r = send("GET", "/api/event-types?status=CURRENT", null, svc);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var codes = codes(r);
        assertThat(codes).contains(MINE_CODE).doesNotContain(OTHER_CODE);
        assertThat(codes).allSatisfy(c -> assertThat(c).startsWith(MINE + ":"));
    }

    @Test
    @DisplayName("list filtered to another application is empty")
    void listFilteredToAnotherApplicationIsEmpty() {
        var r = send("GET", "/api/event-types?application=" + OTHER, null, svc);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(codes(r)).isEmpty();
    }

    @Test
    @DisplayName("reads its own event type by id and by code")
    void readsOwn() {
        assertThat(send("GET", "/api/event-types/" + ownId, null, svc).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/event-types/by-code/" + MINE_CODE, null, svc).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("is refused another application's event type")
    void refusedAnotherApplications() {
        var byId = send("GET", "/api/event-types/" + foreignId, null, svc);
        assertThat(byId.statusCode()).as(byId.body()).isEqualTo(403);
        assertThat(json(byId).get("message").asText()).isEqualTo("No access to this event type");
        assertThat(send("GET", "/api/event-types/by-code/" + OTHER_CODE, null, svc).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("adds a schema version to its own event type")
    void addsSchemaToOwn() {
        var r = send("POST", "/api/event-types/" + ownId + "/versions", SCHEMA, svc);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("specVersions")).isNotEmpty();
    }

    @Test
    @DisplayName("cannot add a schema version to another application's event type")
    void cannotAddToAnothers() {
        String id = create(OTHER + ":refused:order:created");
        var r = send("POST", "/api/event-types/" + id + "/versions", SCHEMA, svc);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(repo.findById(id).orElseThrow().specVersions())
                .as("a refused schema push must not be stored").isEmpty();
    }

    @Test
    @DisplayName("a view-only service account cannot add a schema")
    void viewOnlyCannotAddSchema() {
        var r = send("POST", "/api/event-types/" + ownId + "/versions",
                "{\"version\":\"1.1.0\",\"schema\":{\"type\":\"object\"}}", viewOnly);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
    }

    @Test
    @DisplayName("the messaging permissions still read and write any application")
    void messagingPermissionsUnaffected() {
        assertThat(send("GET", "/api/event-types/" + foreignId, null, ANCHOR).statusCode()).isEqualTo(200);
        var r = send("POST", "/api/event-types/" + foreignId + "/versions", SCHEMA, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }
}
