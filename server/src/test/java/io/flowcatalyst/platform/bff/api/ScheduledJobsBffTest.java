package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.api.ScheduledJobApi;
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
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The `/bff/scheduled-jobs` surface end to end: pagination arithmetic
/// (`totalPages` camelCase), the `clientIds=platform` filter, `hasActiveInstance`
/// true/false, the inaccessible-client 404 (spec §9 D5), instance filters
/// and log order. Writes go through `/api/scheduled-jobs`
/// ([ScheduledJobApi]), mounted alongside the BFF surface purely for setup.
@SuppressWarnings("deprecation")
class ScheduledJobsBffTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ScheduledJobRepository repo = new ScheduledJobRepository(TestPg.dataSource());
    private static final ScheduledJobInstanceRepository instanceRepo = new ScheduledJobInstanceRepository(TestPg.dataSource());
    private static final ClientRepository clientRepo = new ClientRepository(TestPg.dataSource());
    private static final ApplicationRepository applicationRepo = new ApplicationRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static TestHttp http;

    /// A real client row is needed (not just a bare id string) so
    /// [ScheduledJobsBff]'s `clientName` join and filter-options listing have
    /// something to find.
    private static final Client CLIENT_ROW = persistClient("SJB Client " + RUN, "sjb-" + RUN);
    private static final Client OTHER_CLIENT_ROW = persistClient("SJB Other Client " + RUN, "sjbo-" + RUN);
    private static final String CLIENT = CLIENT_ROW.id();
    private static final String OTHER_CLIENT = OTHER_CLIENT_ROW.id();

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL, Authenticator.TEST_SCOPE, "ANCHOR", Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, CLIENT,
            Authenticator.TEST_PERMISSIONS, "platform:messaging:scheduled-job:view"};

    private static Client persistClient(String name, String identifier) {
        Client c = Client.create(name, ClientIdentifier.parse(identifier));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c;
    }

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            routes.before("/bff/*", auth);
            ScheduledJobApi.register(routes, new ScheduledJobApi.State(repo, instanceRepo, uow));
            ScheduledJobsBff.register(routes, new ScheduledJobsBff.State(repo, instanceRepo, clientRepo, applicationRepo));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String code(String tag) {
        return "sjbff" + RUN + "-" + tag;
    }

    private static String create(String code, String extraJson, String... as) {
        var r = http.post("/api/scheduled-jobs",
                "{\"code\":\"" + code + "\",\"name\":\"Job " + code + "\",\"crons\":[\"0 0 * * * *\"],\"concurrent\":false,\"tracksCompletion\":false" + extraJson + "}",
                as.length == 0 ? ANCHOR : as);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r).get("id").asText();
    }

    // ── Job read shape + hasActiveInstance ──────────────────────────────────

    @Test
    void jobResponseKeySetAndHasActiveInstanceTransitions() {
        String id = create(code("shape"), ",\"description\":\"d\",\"clientId\":\"" + CLIENT + "\"");
        var before = json(http.get("/bff/scheduled-jobs/" + id, ANCHOR));
        assertThat(before.propertyNames()).containsExactlyInAnyOrder("id", "clientId", "clientName", "code", "name",
                "description", "status", "crons", "timezone", "concurrent", "tracksCompletion", "deliveryMaxAttempts",
                "createdAt", "updatedAt", "version", "hasActiveInstance");
        assertThat(before.get("clientId").asText()).isEqualTo(CLIENT);
        assertThat(before.get("clientName").asText()).isEqualTo("SJB Client " + RUN);
        assertThat(before.get("hasActiveInstance").asBoolean()).as("no instance fired yet").isFalse();

        http.post("/api/scheduled-jobs/" + id + "/fire", null, ANCHOR);
        var after = json(http.get("/bff/scheduled-jobs/" + id, ANCHOR));
        assertThat(after.get("hasActiveInstance").asBoolean()).as("a QUEUED instance exists").isTrue();
    }

    // ── Pagination arithmetic ────────────────────────────────────────────

    @Test
    void paginationArithmeticIsCamelCaseTotalPages() {
        String tag = code("page");
        for (int i = 0; i < 5; i++) create(tag + "-" + i, "");

        var page0 = json(http.get("/bff/scheduled-jobs?search=" + tag + "&page=0&size=2", ANCHOR));
        assertThat(page0.propertyNames()).containsExactlyInAnyOrder("data", "page", "size", "total", "totalPages");
        assertThat(page0.get("total").asLong()).isEqualTo(5);
        assertThat(page0.get("page").asInt()).isZero();
        assertThat(page0.get("size").asInt()).isEqualTo(2);
        assertThat(page0.get("totalPages").asInt()).as("ceil(5/2)").isEqualTo(3);
        assertThat(page0.get("data")).hasSize(2);

        var page2 = json(http.get("/bff/scheduled-jobs?search=" + tag + "&page=2&size=2", ANCHOR));
        assertThat(page2.get("data")).as("last page has the remainder").hasSize(1);
    }

    // ── The `clientIds=platform` filter ──────────────────────────────────

    @Test
    void clientIdsPlatformLiteralSelectsPlatformScopedJobsOnly() {
        String tag = code("platform-filter");
        create(tag + "-mine", ",\"clientId\":\"" + CLIENT + "\"");
        String platformJob = create(tag + "-plat", "");

        var body = json(http.get("/bff/scheduled-jobs?search=" + tag + "&clientIds=platform", ANCHOR));
        var ids = new java.util.ArrayList<String>();
        body.get("data").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).containsExactly(platformJob);
    }

    @Test
    void clientIdsCanCombineAnExplicitClientWithThePlatformLiteral() {
        String tag = code("combo");
        String mine = create(tag + "-mine", ",\"clientId\":\"" + CLIENT + "\"");
        String platformJob = create(tag + "-plat", "");
        String theirs = create(tag + "-theirs", ",\"clientId\":\"" + OTHER_CLIENT + "\"");

        var body = json(http.get("/bff/scheduled-jobs?search=" + tag + "&clientIds=platform," + CLIENT, ANCHOR));
        var ids = new java.util.ArrayList<String>();
        body.get("data").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder(mine, platformJob);
        assertThat(ids).doesNotContain(theirs);
    }

    // ── Load-bearing: inaccessible client's job is 404 (spec §9 D5) ─────────

    @Test
    void aJobOfAnInaccessibleClientIs404NotForbidden() {
        String theirs = create(code("inaccessible"), ",\"clientId\":\"" + OTHER_CLIENT + "\"");
        var r = http.get("/bff/scheduled-jobs/" + theirs, VIEWER);
        assertThat(r.statusCode()).as("404, not 403 — bff spec §9 D5").isEqualTo(404);
    }

    /// Owner ruling 2026-09-06 #6: a non-anchor sees only jobs of clients it can
    /// access — platform-scoped jobs are anchor-only — and `total`/`totalPages`
    /// count exactly those rows (Go 491d961 folds the same rule into its SQL).
    @Test
    void listShowsOnlyAccessibleClientJobsToANonAnchor() {
        String tag = code("scope-list");
        String mine = create(tag + "-mine", ",\"clientId\":\"" + CLIENT + "\"");
        String platformJob = create(tag + "-plat", "");
        String theirs = create(tag + "-theirs", ",\"clientId\":\"" + OTHER_CLIENT + "\"");

        var body = json(http.get("/bff/scheduled-jobs?search=" + tag, VIEWER));
        var ids = new java.util.ArrayList<String>();
        body.get("data").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).containsExactly(mine);
        assertThat(ids).doesNotContain(platformJob, theirs);
        assertThat(body.get("total").asLong()).isEqualTo(1);
        assertThat(body.get("totalPages").asInt()).isEqualTo(1);

        // Asking for the platform pseudo-client (or another client) yields nothing, with total 0 / totalPages 0.
        var platformOnly = json(http.get("/bff/scheduled-jobs?search=" + tag + "&clientIds=platform", VIEWER));
        assertThat(platformOnly.get("data")).isEmpty();
        assertThat(platformOnly.get("total").asLong()).isZero();
        assertThat(platformOnly.get("totalPages").asInt()).isZero();
        var theirsOnly = json(http.get("/bff/scheduled-jobs?search=" + tag + "&clientIds=" + OTHER_CLIENT, VIEWER));
        assertThat(theirsOnly.get("data")).isEmpty();
        assertThat(theirsOnly.get("total").asLong()).isZero();

        // The anchor still sees all three.
        var all = json(http.get("/bff/scheduled-jobs?search=" + tag, ANCHOR));
        assertThat(all.get("total").asLong()).isEqualTo(3);
    }

    // ── Instances: filters + inaccessible 404 ───────────────────────────────

    @Test
    void instancesOfAJobAreListedAndFilteredByStatus() {
        String id = create(code("inst"), "");
        String instanceId = json(http.post("/api/scheduled-jobs/" + id + "/fire", null, ANCHOR)).get("instanceId").asText();

        var queued = json(http.get("/bff/scheduled-jobs/" + id + "/instances?status=QUEUED", ANCHOR));
        assertThat(queued.get("data").findValuesAsString("id")).contains(instanceId);
        var delivered = json(http.get("/bff/scheduled-jobs/" + id + "/instances?status=DELIVERED", ANCHOR));
        assertThat(delivered.get("data").findValuesAsString("id")).doesNotContain(instanceId);

        var one = json(http.get("/bff/scheduled-jobs/instances/" + instanceId, ANCHOR));
        assertThat(one.propertyNames()).containsExactlyInAnyOrder("id", "scheduledJobId", "jobCode", "triggerKind",
                "firedAt", "status", "deliveryAttempts", "createdAt");
        assertThat(one.has("scheduledFor")).as("absent for a MANUAL fire").isFalse();
        assertThat(one.get("jobCode").asText()).isEqualTo(code("inst"));
    }

    @Test
    void instancesOfAnInaccessibleJobAre404() {
        String theirs = create(code("inst-inaccessible"), ",\"clientId\":\"" + OTHER_CLIENT + "\"");
        assertThat(http.get("/bff/scheduled-jobs/" + theirs + "/instances", VIEWER).statusCode()).isEqualTo(404);
    }

    @Test
    void anInstanceOfAnInaccessibleJobIs404() {
        String theirs = create(code("inst2-inaccessible"), ",\"clientId\":\"" + OTHER_CLIENT + "\"");
        String instanceId = json(http.post("/api/scheduled-jobs/" + theirs + "/fire", null, ANCHOR)).get("instanceId").asText();
        assertThat(http.get("/bff/scheduled-jobs/instances/" + instanceId, VIEWER).statusCode()).isEqualTo(404);
        assertThat(http.get("/bff/scheduled-jobs/instances/" + instanceId + "/logs", VIEWER).statusCode()).isEqualTo(404);
    }

    // ── Logs: oldest first ───────────────────────────────────────────────

    @Test
    void instanceLogsAreOldestFirst() {
        String id = create(code("logs"), "");
        String instanceId = json(http.post("/api/scheduled-jobs/" + id + "/fire", null, ANCHOR)).get("instanceId").asText();
        http.post("/api/scheduled-jobs/instances/" + instanceId + "/log", "{\"level\":\"INFO\",\"message\":\"first\"}", ANCHOR);
        http.post("/api/scheduled-jobs/instances/" + instanceId + "/log", "{\"level\":\"INFO\",\"message\":\"second\"}", ANCHOR);

        var body = json(http.get("/bff/scheduled-jobs/instances/" + instanceId + "/logs", ANCHOR));
        assertThat(body.isArray()).as("bare array").isTrue();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("message").asText()).isEqualTo("first");
        assertThat(body.get(1).get("message").asText()).isEqualTo("second");
        assertThat(body.get(0).has("metadata")).as("absent, none was given").isFalse();
        assertThat(body.get(0).propertyNames()).containsExactlyInAnyOrder("id", "instanceId", "level", "message", "createdAt");
    }

    // ── Filter options ───────────────────────────────────────────────────

    @Test
    void filterOptionsShapeAndAnchorPseudoOption() {
        Application app = Application.create(ApplicationType.APPLICATION, "sjbffapp" + RUN, "SJB App " + RUN);
        uow.inTransaction(tx -> {
            applicationRepo.persist(app, tx.dbTx());
            return null;
        });

        var anchorBody = json(http.get("/bff/scheduled-jobs/filter-options", ANCHOR));
        assertThat(anchorBody.propertyNames()).containsExactlyInAnyOrder("clients", "applications", "statuses");
        assertThat(anchorBody.get("clients").findValuesAsString("value")).contains("platform");
        assertThat(anchorBody.get("applications").findValuesAsString("value")).contains(app.id());
        assertThat(anchorBody.get("statuses").findValuesAsString("value")).containsExactlyInAnyOrder("ACTIVE", "PAUSED", "ARCHIVED");

        var viewerBody = json(http.get("/bff/scheduled-jobs/filter-options", VIEWER));
        assertThat(viewerBody.get("clients").findValuesAsString("value")).as("non-anchor gets no platform pseudo-option").doesNotContain("platform");
    }
}
