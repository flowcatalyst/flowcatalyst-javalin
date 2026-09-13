package io.flowcatalyst.platform.client.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.client.ClientRepository;
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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.APP_CLIENT_CONFIGS;
import static org.assertj.core.api.Assertions.assertThat;

/// The sixteen `/api/clients*` routes end to end through Javalin:
/// the authenticator's test headers, the anchor-only gates, route precedence
/// between the literal and `{id}` paths, the lockfile status codes and body
/// shapes, and the error envelope.
@SuppressWarnings("deprecation")
class ClientApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final ClientApi.State state = new ClientApi.State(new ClientRepository(TestPg.dataSource()),
            new ApplicationRepository(TestPg.dataSource()), new ClientConfigRepository(TestPg.dataSource()),
            new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            ClientApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String ident(String tag) {
        return tag + "-" + RUN;
    }

    /// A CLIENT-scoped principal bound to `clientId` (holding every client permission, which must not help).
    private static String[] clientScoped(String clientId) {
        return new String[]{
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId,
                Authenticator.TEST_PERMISSIONS, "platform:admin:client:view,platform:admin:client:create,platform:admin:client:update,platform:admin:client:delete"};
    }

    private static String create(String name, String identifier) {
        var r = http.post("/api/clients", "{\"name\":\"" + name + "\",\"identifier\":\"" + identifier + "\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("clt_");
        return id;
    }

    private static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z";

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdByIdentifierAndInList() {
        String id = create("Acme Corp", ident("ACME-Corp"));

        // POST body is exactly the CreatedResponse envelope.
        var created = http.post("/api/clients", "{\"name\":\"Beta\",\"identifier\":\"" + ident("beta") + "\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"clt_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        // GET by id: the ClientResponse shape, identifier normalised, optional fields omitted.
        var get = http.get("/api/clients/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var c = json(get);
        assertThat(c.get("id").asText()).isEqualTo(id);
        assertThat(c.get("name").asText()).isEqualTo("Acme Corp");
        assertThat(c.get("identifier").asText()).isEqualTo(ident("acme-corp"));
        assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.has("statusReason")).as("null statusReason omitted").isFalse();
        assertThat(c.has("statusChangedAt")).as("null statusChangedAt omitted").isFalse();
        assertThat(c.get("notes").isArray()).isTrue();
        assertThat(c.get("notes")).isEmpty();
        assertThat(c.get("createdAt").asText()).matches(TS);
        assertThat(c.get("updatedAt").asText()).matches(TS);
        assertThat(c.propertyNames()).containsExactly("id", "name", "identifier", "status", "notes", "createdAt", "updatedAt");

        // GET by identifier (the stored, normalised form).
        var byIdent = http.get("/api/clients/by-identifier/" + ident("acme-corp"), ANCHOR);
        assertThat(byIdent.statusCode()).isEqualTo(200);
        assertThat(json(byIdent).get("id").asText()).isEqualTo(id);

        // List: {"clients": [...], "total": n}, ordered by identifier.
        var list = http.get("/api/clients", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.propertyNames()).containsExactly("clients", "total");
        assertThat(body.get("clients").isArray()).isTrue();
        assertThat(body.get("total").asInt()).isEqualTo(body.get("clients").size());
        assertThat(body.get("clients")).extracting(n -> n.get("id").asText()).contains(id);
    }

    @Test
    void searchByBodyAndByQueryAreTheSameSearch() {
        String a = create("Search Alpha " + RUN, ident("srch") + "-a");
        String b = create("Search Beta " + RUN, ident("srch") + "-b");

        var exact = http.post("/api/clients/search", "{\"term\":\"" + ident("srch") + "\"}", ANCHOR);
        assertThat(exact.statusCode()).as(exact.body()).isEqualTo(200);
        assertThat(json(exact).propertyNames()).containsExactly("clients", "total");
        assertThat(json(exact).get("clients")).extracting(n -> n.get("id").asText()).as("ordered by identifier").containsExactly(a, b);
        assertThat(json(exact).get("total").asInt()).isEqualTo(2);

        var byName = http.post("/api/clients/search", "{\"term\":\"search beta " + RUN + "\"}", ANCHOR);
        assertThat(json(byName).get("clients")).extracting(n -> n.get("id").asText()).as("case-insensitive on the name").containsExactly(b);

        var get = http.get("/api/clients/search?q=" + ident("srch"), ANCHOR);
        assertThat(get.statusCode()).as(get.body()).isEqualTo(200);
        assertThat(json(get).get("clients")).extracting(n -> n.get("id").asText()).containsExactly(a, b);

        var noQuery = http.get("/api/clients/search", ANCHOR);
        assertThat(noQuery.statusCode()).as("search must win over /{id}").isEqualTo(200);
        assertThat(json(noQuery).get("clients").size()).isLessThanOrEqualTo(ClientRepository.SEARCH_LIMIT);

        // term is schema-required on SearchClientRequest (request-schema-validation.md) — Go's
        // own lockfile requires it too, so an absent term now 400s before ever reaching the
        // domain's tolerant "no term = list all" behaviour; the GET surface above (whose `q`
        // query parameter is NOT schema-required) still proves that tolerance is real.
        var emptyBody = http.post("/api/clients/search", "{}", ANCHOR);
        assertThat(emptyBody.statusCode()).as("term is schema-required").isEqualTo(400);
        assertThat(json(emptyBody).get("error").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void updateReturns204AndPersistsTrimmedName() {
        String id = create("Before", ident("upd"));
        var put = http.put("/api/clients/" + id, "{\"name\":\"  After  \"}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(put.body()).isEmpty();
        assertThat(json(http.get("/api/clients/" + id, ANCHOR)).get("name").asText()).isEqualTo("After");

        var noop = http.put("/api/clients/" + id, "{}", ANCHOR);
        assertThat(noop.statusCode()).as("absent name is allowed").isEqualTo(204);

        var bad = http.put("/api/clients/" + id, "{\"name\":\"  \"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");
        assertThat(json(bad).get("message").asText()).isEqualTo("name cannot be empty");
    }

    @Test
    void suspendActivateAndNotesReturnStatusChangeEnvelopes() {
        String id = create("Lifecycle", ident("life"));

        var suspend = http.post("/api/clients/" + id + "/suspend", "{\"reason\":\"billing overdue\"}", ANCHOR);
        assertThat(suspend.statusCode()).as(suspend.body()).isEqualTo(200);
        assertThat(suspend.body()).isEqualTo("{\"message\":\"Client suspended\"}\n");
        var suspended = json(http.get("/api/clients/" + id, ANCHOR));
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("statusReason").asText()).isEqualTo("billing overdue");
        assertThat(suspended.get("statusChangedAt").asText()).matches(TS);
        assertThat(suspended.propertyNames()).containsExactly("id", "name", "identifier", "status",
                "statusReason", "statusChangedAt", "notes", "createdAt", "updatedAt");

        // reason is schema-required (request-schema-validation.md) — sent as "" so the request
        // reaches the domain's own blank check instead of 400 VALIDATION.
        var noReason = http.post("/api/clients/" + id + "/suspend", "{\"reason\":\"\"}", ANCHOR);
        assertThat(noReason.statusCode()).isEqualTo(400);
        assertThat(json(noReason).get("error").asText()).isEqualTo("REASON_REQUIRED");

        var activate = http.post("/api/clients/" + id + "/activate", null, ANCHOR);
        assertThat(activate.statusCode()).as(activate.body()).isEqualTo(200);
        assertThat(activate.body()).isEqualTo("{\"message\":\"Client activated\"}\n");
        var active = json(http.get("/api/clients/" + id, ANCHOR));
        assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(active.has("statusReason")).as("activation clears the reason").isFalse();
        assertThat(active.get("statusChangedAt").asText()).matches(TS);

        var note = http.post("/api/clients/" + id + "/notes", "{\"category\":\"billing\",\"text\":\"annual plan\"}", ANCHOR);
        assertThat(note.statusCode()).as(note.body()).isEqualTo(200);
        assertThat(note.body()).isEqualTo("{\"message\":\"Note added\"}\n");
        var notes = json(http.get("/api/clients/" + id, ANCHOR)).get("notes");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("category").asText()).isEqualTo("billing");
        assertThat(notes.get(0).get("text").asText()).isEqualTo("annual plan");
        assertThat(notes.get(0).get("addedBy").asText()).isEqualTo(ANCHOR_PRINCIPAL);
        assertThat(notes.get(0).get("addedAt").asText()).matches(TS);
        assertThat(notes.get(0).propertyNames()).containsExactly("category", "text", "addedBy", "addedAt");

        // text is schema-required on AddNoteRequest too — sent as "" so the request reaches
        // the domain check instead of 400 VALIDATION.
        var badNote = http.post("/api/clients/" + id + "/notes", "{\"category\":\"billing\",\"text\":\"\"}", ANCHOR);
        assertThat(badNote.statusCode()).isEqualTo(400);
        assertThat(json(badNote).get("error").asText()).isEqualTo("TEXT_REQUIRED");
    }

    @Test
    void deleteReturns204ThenGetIs404Envelope() {
        String id = create("Doomed", ident("del"));
        var del = http.delete("/api/clients/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/clients/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"Client_NOT_FOUND\",\"message\":\"Client not found: " + id + "\"}\n");

        var again = http.delete("/api/clients/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json(again).get("error").asText()).isEqualTo("Client_NOT_FOUND");

        var byIdent = http.get("/api/clients/by-identifier/" + ident("del"), ANCHOR);
        assertThat(byIdent.statusCode()).isEqualTo(404);
        assertThat(json(byIdent).get("message").asText()).isEqualTo("Client not found: " + ident("del"));
    }

    /// Deactivate is the delete alias: a hard delete answering with the status-change envelope.
    @Test
    void deactivateIsAHardDeleteWithAMessage() {
        String id = create("Deactivate Me", ident("deact"));
        var r = http.post("/api/clients/" + id + "/deactivate", "{\"reason\":\"churned\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"message\":\"Client deactivated\"}\n");
        assertThat(http.get("/api/clients/" + id, ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void applicationsListsEveryApplicationWithTheClientsEnabledFlag() {
        String id = create("With Apps", ident("apps"));
        String enabledApp = seedApplication("on");
        String disabledApp = seedApplication("off");
        String unconfiguredApp = seedApplication("none");
        seedConfig(enabledApp, id, true);
        seedConfig(disabledApp, id, false);

        var r = http.get("/api/clients/" + id + "/applications", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.propertyNames()).containsExactly("applications", "total");
        assertThat(body.get("total").asInt()).isEqualTo(body.get("applications").size());
        var byId = new HashMap<String, JsonNode>();
        body.get("applications").forEach(n -> byId.put(n.get("id").asText(), n));
        assertThat(byId.get(enabledApp).get("enabledForClient").asBoolean()).isTrue();
        assertThat(byId.get(disabledApp).get("enabledForClient").asBoolean()).isFalse();
        assertThat(byId.get(unconfiguredApp).get("enabledForClient").asBoolean()).as("no config row → false").isFalse();
        assertThat(byId.get(enabledApp).propertyNames()).containsExactly("id", "code", "name", "description", "active", "enabledForClient");
        assertThat(byId.get(enabledApp).get("active").asBoolean()).isTrue();

        // The one non-anchor route: a principal with access to this client may read it…
        var own = http.get("/api/clients/" + id + "/applications", clientScoped(id));
        assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
        // …another client's principal may not.
        var other = http.get("/api/clients/" + id + "/applications", clientScoped("clt_other_" + RUN));
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(json(other).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(other).get("message").asText()).isEqualTo("No access to this client");

        var missing = http.get("/api/clients/clt_doesnotexist1/applications", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("Client_NOT_FOUND");
    }

    /// The three client → application writes run the application aggregate's
    /// operations behind this surface's anchor gate (spec §10).
    @Test
    void enableDisableAndBulkUpdateDriveTheClientsApplicationConfigs() {
        String id = create("Link Apps", ident("link"));
        String one = seedApplication("one");
        String two = seedApplication("two");

        var enable = http.post("/api/clients/" + id + "/applications/" + one + "/enable", null, ANCHOR);
        assertThat(enable.statusCode()).as(enable.body()).isEqualTo(204);
        assertThat(enabledFor(id, one)).isTrue();

        var disable = http.post("/api/clients/" + id + "/applications/" + one + "/disable", null, ANCHOR);
        assertThat(disable.statusCode()).as(disable.body()).isEqualTo(204);
        assertThat(enabledFor(id, one)).as("disable keeps the row, flips the flag").isFalse();

        var bulk = http.put("/api/clients/" + id + "/applications", "{\"enabledApplicationIds\":[\"" + two + "\"]}", ANCHOR);
        assertThat(bulk.statusCode()).as(bulk.body()).isEqualTo(204);
        assertThat(enabledFor(id, two)).isTrue();
        assertThat(enabledFor(id, one)).isFalse();

        var unknownApp = http.post("/api/clients/" + id + "/applications/app_doesnotexist1/enable", null, ANCHOR);
        assertThat(unknownApp.statusCode()).isEqualTo(404);
        assertThat(json(unknownApp).get("error").asText()).isEqualTo("Application_NOT_FOUND");

        var noConfig = http.post("/api/clients/" + id + "/applications/" + seedApplication("three") + "/disable", null, ANCHOR);
        assertThat(noConfig.statusCode()).isEqualTo(404);
        assertThat(json(noConfig).get("error").asText()).isEqualTo("ClientConfig_NOT_FOUND");

        var unknownClient = http.put("/api/clients/clt_doesnotexist1/applications", "{\"enabledApplicationIds\":[]}", ANCHOR);
        assertThat(unknownClient.statusCode()).isEqualTo(404);
        assertThat(json(unknownClient).get("error").asText()).isEqualTo("Client_NOT_FOUND");

        // Anchor-only, even for a principal scoped to this very client.
        var denied = http.post("/api/clients/" + id + "/applications/" + one + "/enable", null, clientScoped(id));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");
    }

    private static boolean enabledFor(String clientId, String applicationId) {
        var r = http.get("/api/clients/" + clientId + "/applications", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        for (JsonNode n : json(r).get("applications")) {
            if (n.get("id").asText().equals(applicationId)) return n.get("enabledForClient").asBoolean();
        }
        throw new AssertionError("application " + applicationId + " not listed");
    }

    // ── Negative paths ─────────────────────────────────────────────────────

    /// Every other route is anchor-only; client permissions do not help a CLIENT-scoped principal.
    @Test
    void nonAnchorOrAnonymousIs403Envelope() {
        String id = create("Gated", ident("gate"));
        String[] client = clientScoped(id);

        List<HttpResponse<String>> denied = List.of(
                http.get("/api/clients", client),
                http.post("/api/clients", "{\"name\":\"X\",\"identifier\":\"" + ident("denied") + "\"}", client),
                http.post("/api/clients/search", "{\"term\":\"x\"}", client),
                http.get("/api/clients/search?q=x", client),
                http.get("/api/clients/by-identifier/" + ident("gate"), client),
                http.get("/api/clients/" + id, client),
                http.put("/api/clients/" + id, "{\"name\":\"Y\"}", client),
                http.delete("/api/clients/" + id, client),
                http.post("/api/clients/" + id + "/activate", null, client),
                http.post("/api/clients/" + id + "/suspend", "{\"reason\":\"r\"}", client),
                http.post("/api/clients/" + id + "/notes", "{\"category\":\"c\",\"text\":\"t\"}", client),
                http.post("/api/clients/" + id + "/deactivate", "{\"reason\":\"r\"}", client));
        for (var r : denied) {
            assertThat(r.statusCode()).as(r.uri() + " → " + r.body()).isEqualTo(403);
            assertThat(json(r).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");
        }
        assertThat(http.get("/api/clients/" + id, ANCHOR).statusCode()).as("nothing was deleted by the denied calls").isEqualTo(200);

        var anon = http.get("/api/clients");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void validationConflictAndMalformedJsonAreEnvelopes() {
        var bad = http.post("/api/clients", "{\"name\":\"X\",\"identifier\":\"my_client\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("INVALID_IDENTIFIER");
        assertThat(json(bad).get("message").asText()).isEqualTo("identifier must be lowercase alphanumeric with optional hyphens (URL-safe)");

        // name is schema-required too — sent as "" so the request reaches the domain check.
        var noName = http.post("/api/clients", "{\"name\":\"\",\"identifier\":\"" + ident("noname") + "\"}", ANCHOR);
        assertThat(noName.statusCode()).isEqualTo(400);
        assertThat(json(noName).get("error").asText()).isEqualTo("NAME_REQUIRED");

        create("Twice", ident("twice"));
        var dup = http.post("/api/clients", "{\"name\":\"Twice\",\"identifier\":\"" + ident("TWICE") + "\"}", ANCHOR);
        assertThat(dup.statusCode()).as("uniqueness on the normalised identifier").isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("IDENTIFIER_EXISTS");

        var malformed = http.post("/api/clients", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }

    // ── Seeds for the application projection (another aggregate's tables) ──

    private static String seedApplication(String tag) {
        String id = EntityType.APPLICATION.generate();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(APP_APPLICATIONS)
                .set(APP_APPLICATIONS.ID, id)
                .set(APP_APPLICATIONS.TYPE, "APPLICATION")
                .set(APP_APPLICATIONS.CODE, "cliapi-" + tag + "-" + RUN)
                .set(APP_APPLICATIONS.NAME, "Client API " + tag)
                .set(APP_APPLICATIONS.DESCRIPTION, "seeded")
                .set(APP_APPLICATIONS.ACTIVE, true)
                .set(APP_APPLICATIONS.CREATED_AT, now)
                .set(APP_APPLICATIONS.UPDATED_AT, now)
                .execute();
        return id;
    }

    private static void seedConfig(String applicationId, String clientId, boolean enabled) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(APP_CLIENT_CONFIGS)
                .set(APP_CLIENT_CONFIGS.ID, EntityType.APP_CLIENT_CONFIG.generate())
                .set(APP_CLIENT_CONFIGS.APPLICATION_ID, applicationId)
                .set(APP_CLIENT_CONFIGS.CLIENT_ID, clientId)
                .set(APP_CLIENT_CONFIGS.ENABLED, enabled)
                .set(APP_CLIENT_CONFIGS.CREATED_AT, now)
                .set(APP_CLIENT_CONFIGS.UPDATED_AT, now)
                .execute();
    }
}
