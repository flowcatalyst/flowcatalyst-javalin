package io.flowcatalyst.server;

import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// R3′ (`docs/spec/router-config-auth.md`): `/api/dispatch/router-config` moved
/// from the internal listener (unauthenticated) to the API listener, behind
/// the platform's ordinary bearer auth and the new `platform:router` role.
/// Every assertion here drives the REAL operator workflow spec §4 describes —
/// create an application, provision its service account, assign (or
/// withhold) the role, mint a client-credentials token — never a shortcut
/// through the auth internals, because that workflow IS the contract.
@SuppressWarnings("deprecation")
class RouterConfigEndpointTest {

    private static Server.Running running;
    private static final HttpClient http = HttpClient.newHttpClient();

    // This fixture is only ever used to set up fixtures (create an application,
    // provision its service account, assign roles) via the real anchor operator
    // workflow — never to pin a permission assertion itself — so it carries the
    // wildcard (permissions-from-roles.md §2: anchor scope alone is reach, not
    // authority, since 2026-09-13).
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    @BeforeAll
    static void start() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_DISPATCH_QUEUE_TYPE", "SQS",
                "FC_DISPATCH_QUEUE_PREFIX", "FC-endpointtest",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/fc-dispatch",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                // Provisioning a service account encrypts its webhook credential and
                // hashes its OAuth client secret under the app key (spec §4's workflow).
                "FLOWCATALYST_APP_KEY", io.flowcatalyst.platform.shared.encryption.Encryption.generateKey()));
        // The built-in roles — `platform:router` among them — live in `iam_roles`,
        // written by the seeder; `Main`/`fcdev` always run it before `Server.start()`
        // and a client-credentials mint reads permissions through exactly that table
        // (`DbClaimsResolver#ceiling`), so a token for a role the seeder never wrote
        // carries no permissions at all, seeded or not.
        new Seeder(TestPg.dataSource()).run();
        // Spa.none(), deliberately, not the embedded SPA: with a real SPA
        // mounted, an unmatched GET on the API listener falls through to the
        // SPA's index.html (200), the same as any other unknown path
        // (ServerTest#specAndSpaAreServedFromTheApiListener) — that would
        // mask exactly the absence this test wants to prove on the metrics
        // listener side.
        running = new Server(env, new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(TestPg.dataSource())), Server.Spa.none(),
                new PrometheusRegistry()).start();
    }

    @AfterAll
    static void stop() {
        running.stop();
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private static HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(int port, String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    /// Provisions an application's service account (spec §4's operator
    /// workflow) and, optionally, assigns it `platform:router`.
    private record Credentials(String clientId, String clientSecret) {
    }

    private static Credentials provision(String code, boolean grantRouterRole) throws Exception {
        var created = post(running.apiPort(), "/api/applications",
                "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}", ANCHOR);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String appId = json(created).get("id").asText();

        var provisioned = post(running.apiPort(), "/api/applications/" + appId + "/provision-service-account",
                null, ANCHOR);
        assertThat(provisioned.statusCode()).as(provisioned.body()).isEqualTo(201);
        var sa = json(provisioned).get("serviceAccount");
        var oauth = sa.get("oauthClient");
        String clientId = oauth.get("clientId").asText();
        String clientSecret = oauth.get("clientSecret").asText();

        if (grantRouterRole) {
            // Roles live on the SERVICE ACCOUNT aggregate for a provisioned
            // account, not the principal directly — `PUT /api/principals/{id}/roles`
            // (`AssignRoles`) refuses non-USER principals (`NOT_A_USER`), so the
            // real operator workflow (spec §4) goes through
            // `PUT /api/service-accounts/{id}/roles` instead, keyed by the
            // service account's own id — looked up by its `app:<code>` code
            // (`ProvisionServiceAccount`'s convention), since the provisioning
            // response never discloses that id directly.
            var byCode = get(running.apiPort(), "/api/service-accounts/code/app:" + code, ANCHOR);
            assertThat(byCode.statusCode()).as(byCode.body()).isEqualTo(200);
            String serviceAccountId = json(byCode).get("id").asText();

            var assigned = put(running.apiPort(), "/api/service-accounts/" + serviceAccountId + "/roles",
                    "{\"roles\":[\"platform:router\"]}", ANCHOR);
            assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);
        }
        return new Credentials(clientId, clientSecret);
    }

    private static String mintToken(Credentials c) throws Exception {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(c.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(c.clientSecret(), StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/oauth/token"))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).get("access_token").asText();
    }

    // ── Tests ────────────────────────────────────────────────────────────

    /// Pins the whole point of R3′: a client-credentials token minted for a
    /// principal holding `platform:router` reaches the document on the API
    /// listener, and — what actually proves the contract, not just a "200
    /// OK" — the served bytes parse through the exact [RouterConfig]
    /// deserialisation path [io.flowcatalyst.router.config.http.HttpConfigSource]
    /// uses, with the always-present `platform` `DEFAULT` queue's composed
    /// name intact. A mutant that answered 200 with some other shape, or a
    /// mutant that dropped the role gate entirely (letting an unrelated
    /// token through), would not by itself be caught by THIS assertion —
    /// the other tests below pin those.
    @Test
    void aTokenHoldingPlatformRouterGetsTheDocumentOnTheApiListener() throws Exception {
        var creds = provision("routercfg-ok", true);
        String token = mintToken(creds);

        var response = get(running.apiPort(), "/api/dispatch/router-config", "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        RouterConfig config = Json.MAPPER.readValue(response.body(), RouterConfig.class);
        assertThat(config.queues())
                .anyMatch(q -> q.queueName().equals("FC-endpointtest-platform-DEFAULT.fifo")
                        && q.queueUri().equals("https://sqs.us-east-1.amazonaws.com/123456789012/FC-endpointtest-platform-DEFAULT.fifo"));
    }

    /// No credentials at all: 401, not the platform's usual 403
    /// `UNAUTHENTICATED` — a mutant that fell back to the ordinary
    /// `Checks.requireAnchor`/`Checks.require` 403 would fail this exact
    /// status assertion.
    @Test
    void noTokenIs401() throws Exception {
        var response = get(running.apiPort(), "/api/dispatch/router-config");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /// A real, working client-credentials token — minted for a genuinely
    /// provisioned application service account — but never assigned
    /// `platform:router`: 403. This is the assertion that actually pins the
    /// role gate rather than merely "some token exists": a mutant that
    /// dropped the permission check (leaving only `requireAnchor`) would
    /// turn this into a 200, because a provisioned service account is
    /// anchor-scoped by construction ([io.flowcatalyst.platform.principal.Principal#newService])
    /// and would otherwise sail through on scope alone.
    @Test
    void aTokenWithoutThePermissionGets403() throws Exception {
        var creds = provision("routercfg-forbidden", false);
        String token = mintToken(creds);

        var response = get(running.apiPort(), "/api/dispatch/router-config", "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(json(response).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    /// Spec §3.2 (`docs/spec/permissions-from-roles.md`): a provisioned
    /// application service account's client-credentials token is refused on
    /// an admin read it holds no permission for (`GET /api/principals`
    /// needs `USER_VIEW`; the account's default `platform:application-service`
    /// role has no such thing), and accepted on the action its own default
    /// role DOES grant — `POST /api/applications/{code}/event-types/sync`
    /// needs one of `EVENT_TYPE_SYNC`/`EVENT_TYPE_MANAGE`/
    /// `APP_SVC_EVENT_TYPE_CREATE`/`UPDATE`/`DELETE`, and
    /// `platform:application-service` grants `APP_SVC_EVENT_TYPE_CREATE`.
    /// This is the API-level twin of `ChecksTest`'s unit assertions: it
    /// proves the withdrawn bypass is gone on a REAL route reached through a
    /// REAL minted token, not just through `Checks` called directly. A
    /// mutant that reinstated the anchor bypass in `Checks.require`/
    /// `requireAny` would turn the 403 into a 200 (every provisioned
    /// service account is anchor-scoped by construction).
    @Test
    void aProvisionedServiceAccountIsRefusedAnAdminReadButKeepsItsOwnRole() throws Exception {
        var creds = provision("routercfg-appsvc", false);
        String token = mintToken(creds);

        var principals = get(running.apiPort(), "/api/principals", "Authorization", "Bearer " + token);
        assertThat(principals.statusCode()).as(principals.body()).isEqualTo(403);
        assertThat(json(principals).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var sync = post(running.apiPort(), "/api/applications/routercfg-appsvc/event-types/sync",
                "{\"eventTypes\":[]}", "Authorization", "Bearer " + token);
        assertThat(sync.statusCode()).as(sync.body()).isEqualTo(200);
    }

    /// Spec §3.2's other half: an anchor test principal holding only
    /// `platform:viewer`'s permissions is refused a write it could
    /// previously perform under the anchor bypass. `PlatformRoles` is the
    /// authority for what `platform:viewer` actually grants (read-only —
    /// `ADMIN_EVENT_TYPE_READ`/`EVENT_TYPE_VIEW`, never
    /// `EVENT_TYPE_CREATE`/`UPDATE`/`DELETE`) — read from there rather than
    /// hand-picking a permission, so this stays true if the role's list
    /// changes. `POST /api/clients` is NOT usable for this: `ClientApi`
    /// gates every route with `Checks.requireAnchor` alone, no permission
    /// check at all, so an anchor reaches it regardless of role — a
    /// pre-existing, separate design choice this unit does not touch.
    /// `POST /api/event-types`, by contrast, goes through
    /// `Checks.requireAny(EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE,
    /// EVENT_TYPE_DELETE)`, which is exactly what the withdrawn anchor
    /// bypass used to short-circuit.
    @Test
    void anAnchorWithOnlyViewerPermissionsIsRefusedAWrite() throws Exception {
        var viewerPermissions = io.flowcatalyst.platform.seed.PlatformRoles.all().stream()
                .filter(r -> r.name().equals("platform:viewer"))
                .findFirst().orElseThrow()
                .permissions();
        String[] anchorViewer = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, String.join(",", viewerPermissions)};

        var response = post(running.apiPort(), "/api/event-types",
                "{\"code\":\"anchor-viewer-write-" + System.nanoTime() + "\",\"name\":\"n\"}", anchorViewer);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(json(response).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    /// R3′'s other half: the document is now absent from the INTERNAL
    /// listener — the inverse of R3's own assertion. [Metrics] installs no
    /// `HttpError` 404 envelope of its own (only the platform API listener
    /// does), so an unmatched route there surfaces as a bare Javalin 500 —
    /// the same "never registered" signature R3's own test documented, not
    /// a regression introduced here. A mutant that left the route
    /// registered on [Metrics] (or moved it back there) would turn this 500
    /// into a 200 with a real document body.
    @Test
    void theRouteIsAbsentFromTheInternalListener() throws Exception {
        var response = get(running.metricsPort(), "/api/dispatch/router-config");

        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("processingPools");
    }
}
