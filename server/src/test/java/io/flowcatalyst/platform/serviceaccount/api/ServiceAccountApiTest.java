package io.flowcatalyst.platform.serviceaccount.api;

import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.serviceaccount.SigningAccounts;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.oauth.AccessTokenReader;
import io.flowcatalyst.platform.auth.oauth.OAuthState;
import io.flowcatalyst.platform.auth.oauth.OAuthTokenApi;
import io.flowcatalyst.platform.auth.ratelimit.Governor;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.token.ClaimLabels;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.api.ClientApi;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.RsaServiceAccountTokenMinter;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.api.SubscriptionApi;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The `/api/service-accounts` surface end to end through Javalin (spec §3):
/// the lockfile's status codes, the anchor-only pair (role assignment, token
/// mint) versus the permission-gated rest, the one-shot secret disclosure
/// (spec §5, no stash), and the token mint's real HMAC-signed JWT (spec §8).
@SuppressWarnings("deprecation")
class ServiceAccountApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    /// The platform signing key the test server verifies with — and the mint signs under.
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final String ISSUER = "https://fc.test";
    /// The role every mint-token test grants, and the permission it flattens to.
    private static final String GRANTED_PERMISSION = "platform:events:event:create";

    /// Shared by the service account's webhook-secret encryption AND the
    /// minted OAuth client's secret ref, so the `/oauth/token` wiring below
    /// (a separate `OAuthState`) can decrypt what `create` just encrypted.
    private static final Optional<Encryption> ENCRYPTION = Optional.of(Encryption.withKey(Encryption.generateKey()));
    private static final String OAUTH_ISSUER = "https://fc.test/oauth";
    private static final SigningKeys OAUTH_KEYS = SigningKeys.generateEphemeral();

    private static final ServiceAccountRepository SA_REPO = new ServiceAccountRepository(TestPg.dataSource(), ENCRYPTION);
    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(TestPg.dataSource());
    private static final RoleRepository ROLES = new RoleRepository(TestPg.dataSource());
    private static final OAuthClientRepository OAUTH_CLIENTS =
            new OAuthClientRepository(TestPg.dataSource(), new ApplicationRepository(TestPg.dataSource()));
    private static final UnitOfWork UOW = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    /// The service-account-reach tests (spec `docs/spec/service-account-reach.md`)
    /// need real clients to link to, a subscription per client to prove the
    /// resulting token is really client-filtered, and a second live server
    /// (`apiHttp`) that verifies a real client-credentials bearer — not the
    /// `X-FC-Test-*` header bypass `http`'s authenticator accepts.
    private static final ClientRepository CLIENTS = new ClientRepository(TestPg.dataSource());
    private static final ApplicationRepository APPLICATIONS = new ApplicationRepository(TestPg.dataSource());
    private static final ClientConfigRepository CLIENT_CONFIGS = new ClientConfigRepository(TestPg.dataSource());
    private static final SubscriptionRepository SUBSCRIPTIONS = new SubscriptionRepository(TestPg.dataSource());

    private static TestHttp http;
    /// A second, independent server exercising only `POST /oauth/token`
    /// against the SAME `OAUTH_CLIENTS` repository the service-account API
    /// writes to — proves the minted pair is a real, usable credential
    /// (spec §8), not merely a row that exists.
    private static TestHttp oauthHttp;
    /// A third server, `/api/clients` and `/api/subscriptions` only, whose
    /// authenticator verifies a bearer with the SAME key + issuer `oauthHttp`
    /// mints under — so a service-account reach test's token is checked for
    /// real (RS256 signature + claims), never the test-header bypass.
    private static TestHttp apiHttp;
    private static Role grantedRole;
    /// Seeded fresh here rather than relying on the built-in `platform:viewer`
    /// catalogue role being present in `iam_roles` — this fixture never runs
    /// the platform seeder, so an unseeded role name would assign but grant
    /// no real permission at mint time (roles are free-form strings on the
    /// principal; the ceiling comes from a role LOOKUP by name).
    private static Role subscriptionViewerRole;

    private static String[] anchor() {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    }

    private static String[] withPermissions(String... permissions) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, "clt_saapi_" + RUN,
                Authenticator.TEST_PERMISSIONS, String.join(",", permissions)};
    }

    private static final String[] VIEWER = withPermissions("platform:iam:service-account:view");
    private static final String[] WRITER = withPermissions(
            "platform:iam:service-account:view", "platform:iam:service-account:create");
    private static final String[] DELETER = withPermissions(
            "platform:iam:service-account:view", "platform:iam:service-account:delete");

    @BeforeAll
    static void start() {
        grantedRole = Role.create("platform", "saapi" + RUN + "-granter", "Granter").update(new Role.Changes(null, null, List.of(GRANTED_PERMISSION), null));
        subscriptionViewerRole = Role.create("platform", "saapi" + RUN + "-subscription-viewer", "Subscription Viewer")
                .update(new Role.Changes(null, null, List.of(Permission.SUBSCRIPTION_VIEW.code()), null));
        UOW.inTransaction(tx -> {
            ROLES.persist(grantedRole, tx.dbTx());
            ROLES.persist(subscriptionViewerRole, tx.dbTx());
            return null;
        });

        var minter = new RsaServiceAccountTokenMinter(KEYS, ISSUER, ISSUER);
        var state = new ServiceAccountApi.State(SA_REPO, PRINCIPALS, UOW, OAUTH_CLIENTS, CLIENTS, ENCRYPTION, minter,
                roleNames -> roleNames.stream().flatMap(n -> ROLES.findByName(n).stream()).flatMap(r -> r.permissions().stream()).distinct().toList());

        var keys = KEYS;
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            ServiceAccountApi.register(routes, state);
        });

        var grants = new GrantStore(TestPg.dataSource());
        var issuer = new TokenIssuer(OAUTH_KEYS, TokenIssuer.Config.of(OAUTH_ISSUER));
        var oauthVerifier = new JwtVerifier(new JwtVerifier.Config(OAUTH_ISSUER, new JwtVerifier.RsaKeys(OAUTH_KEYS.publicKey())));
        var oauthState = new OAuthState(OAUTH_CLIENTS, PRINCIPALS, SA_REPO, grants, new RefreshRotation(grants, Clock.systemUTC(), RefreshToken.TTL_SECONDS),
                issuer, new AccessTokenReader(oauthVerifier), new DbClaimsResolver(PRINCIPALS, ROLES),
                ClaimLabels.of(new ClientRepository(TestPg.dataSource()), new ApplicationRepository(TestPg.dataSource())),
                ENCRYPTION, null, new RateLimit.NoopStore(), RateLimit.Policies.fromEnv(new EnvReader(Map.of())),
                new Governor(new Governor.Config(1000, 1_000_000)), OAUTH_KEYS, OAUTH_ISSUER, Clock.systemUTC(), null,
                new io.flowcatalyst.platform.portalapp.PortalAppRepository(TestPg.dataSource()), RefreshToken.TTL_SECONDS);
        oauthHttp = TestHttp.routes(routes -> {
            HttpError.install(routes);
            OAuthTokenApi.register(routes, oauthState);
        });

        // Verifies with the SAME key + issuer `oauthState.issuer()` mints under (real
        // RS256 verification, never the `http` server's `X-FC-Test-*` bypass) — a
        // client-credentials token is self-contained, so no ClaimsResolver is needed
        // on this "from header" path (Authenticator's own contract).
        var apiAuth = new Authenticator(oauthVerifier, ClaimsResolver.none(), Authenticator.Config.of(false));
        apiHttp = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", apiAuth);
            ClientApi.register(routes, new ClientApi.State(CLIENTS, APPLICATIONS, CLIENT_CONFIGS, UOW));
            SubscriptionApi.register(routes, new SubscriptionApi.State(SUBSCRIPTIONS, UOW,
                    new ConnectionRepository(TestPg.dataSource()), SigningAccounts.reach(TestPg.dataSource())));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        oauthHttp.close();
        apiHttp.close();
    }

    private static HttpResponse<String> tokenRequest(Map<String, String> form, String... headers) {
        var b = new StringBuilder();
        form.forEach((k, v) -> b.append(b.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        String[] all = new String[headers.length + 2];
        all[0] = "Content-Type";
        all[1] = "application/x-www-form-urlencoded";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return oauthHttp.post("/oauth/token", b.toString(), all);
    }

    private static String[] basicAuth(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return new String[] {"Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8))};
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String code(String tag) {
        return tag + "-" + RUN;
    }

    /// Creates a service account as an anchor and returns the full create response.
    private static JsonNode create(String code, String name) {
        var r = http.post("/api/service-accounts", "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r);
    }

    /// Same, with `clientIds` on the wire (service-account-reach.md §1).
    private static JsonNode createWithClientIds(String code, String name, List<String> clientIds) {
        String ids = clientIds.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        var r = http.post("/api/service-accounts",
                "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"clientIds\":[" + ids + "]}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r);
    }

    private static String seedClient(String tag) {
        var c = Client.create("Client " + tag, ClientIdentifier.parse(tag + RUN));
        UOW.inTransaction(tx -> { CLIENTS.persist(c, tx.dbTx()); return null; });
        return c.id();
    }

    /// A minimal, `ACTIVE` client-scoped subscription, for the reach tests'
    /// "only my client's rows come back" assertion.
    private static Subscription seedSubscription(String code, String clientId) {
        var sub = Subscription.create(code, "Sub " + code, "https://example.test/" + code).withClientId(clientId);
        UOW.inTransaction(tx -> { SUBSCRIPTIONS.persist(sub, tx.dbTx()); return null; });
        return sub;
    }

    /// The JWT's middle (payload) segment, base64url-decoded and parsed —
    /// deliberately NOT going through [JwtVerifier] here: the reach tests read
    /// the claims the mint actually wrote, the same way a relying party's
    /// naive decode would.
    private static JsonNode decodePayload(String jwt) {
        String[] parts = jwt.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        try {
            return Json.MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + new String(payload, StandardCharsets.UTF_8), e);
        }
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createReturns201WithOneTimeSecretsAndTheAccountShape() {
        var body = create(code("create"), "Create Me");

        assertThat(body.get("serviceAccount").get("id").asText()).startsWith("sac_");
        assertThat(body.get("serviceAccount").get("code").asText()).isEqualTo(code("create"));
        assertThat(body.get("serviceAccount").get("authType").asText()).isEqualTo("BEARER_TOKEN");
        assertThat(body.get("serviceAccount").has("principalId"))
                .as("principalId is omitted on the nested shape, matching Go (spec §9.1, §10 Q5)").isFalse();
        assertThat(body.get("principalId").asText()).as("but present on the top-level, required field").startsWith("prn_");
        // A real, persisted CONFIDENTIAL OAuth client — see clientCredentialsAgainstTheMintedPairIssuesATokenForThePrincipal.
        assertThat(body.get("oauth").get("clientId").asText()).startsWith("oac_");
        assertThat(body.get("oauth").get("clientSecret").asText()).isNotBlank();
        assertThat(body.get("webhook").get("authToken").asText()).isNotBlank();
        assertThat(body.get("webhook").get("signingSecret").asText()).isNotBlank();
    }

    /// Go `a8ff165`: a new account starts with no application access, and the
    /// token minted for it says so — `applications` empty, `all_applications`
    /// false — until the create asks for every application. Read off the
    /// minted token, because that is what a resource server acts on.
    @Test
    void aNewAccountsTokenReachesNoApplicationUnlessTheCreateAskedForAll() {
        String plainId = create(code("noapps"), "No Apps").get("serviceAccount").get("id").asText();
        var plain = decodePayload(json(http.post("/api/service-accounts/" + plainId + "/token", null, anchor()))
                .get("accessToken").asText());
        assertThat(plain.get("all_applications").asBoolean()).isFalse();
        assertThat(plain.get("applications")).isEmpty();

        var r = http.post("/api/service-accounts",
                "{\"code\":\"" + code("allapps") + "\",\"name\":\"All Apps\",\"allApplications\":true}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String allId = json(r).get("serviceAccount").get("id").asText();
        var all = decodePayload(json(http.post("/api/service-accounts/" + allId + "/token", null, anchor()))
                .get("accessToken").asText());
        assertThat(all.get("all_applications").asBoolean()).isTrue();
        assertThat(all.get("applications").get(0).asText()).isEqualTo("*");
    }

    /// Only a caller that itself holds all-applications access may grant it
    /// (the same rule as assigning a principal's application access); and it
    /// cannot be combined with an `applicationId`.
    @Test
    void allApplicationsNeedsACallerWhoHoldsItAndExcludesAnApplicationId() {
        String[] confinedWriter = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_ALL_APPLICATIONS, "false",
                Authenticator.TEST_PERMISSIONS, "platform:iam:service-account:create"};
        String withFlag = "{\"code\":\"" + code("allappsdenied") + "\",\"name\":\"X\",\"allApplications\":true}";
        assertThat(http.post("/api/service-accounts", withFlag, confinedWriter).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/service-accounts/code/" + code("allappsdenied"), anchor()).statusCode())
                .as("nothing was created").isEqualTo(404);
        assertThat(http.post("/api/service-accounts",
                        "{\"code\":\"" + code("allappsplain") + "\",\"name\":\"X\"}", confinedWriter).statusCode())
                .as("the same caller may still create an account without it").isEqualTo(201);

        var both = http.post("/api/service-accounts", "{\"code\":\"" + code("allappsboth") + "\",\"name\":\"X\","
                + "\"allApplications\":true,\"applicationId\":\"" + EntityType.APPLICATION.generate() + "\"}", anchor());
        assertThat(both.statusCode()).isEqualTo(400);
        assertThat(json(both).get("error").asText()).isEqualTo("ALL_APPLICATIONS_WITH_APPLICATION_ID");
    }

    /// Proves the minted pair (spec §4.1, §8) is a REAL, usable credential —
    /// not merely a row that exists. Drives `/oauth/token` through a second,
    /// independent server (`oauthHttp`) wired against the same
    /// `OAUTH_CLIENTS` repository the create handler just wrote to, exactly
    /// as `OAuthProviderTest` wires the provider. Kills "return the secret
    /// ref instead of the plaintext" (a leaked ref would never authenticate
    /// here — `acceptClientSecret` decrypts the stored ref and compares) and
    /// "drop withPrincipalId on the client" (a client with no principal 500s
    /// `serverError`, never mints).
    @Test
    void clientCredentialsAgainstTheMintedPairIssuesATokenForThePrincipal() {
        var created = create(code("oauthlive"), "OAuth Live");
        String clientId = created.get("oauth").get("clientId").asText();
        String clientSecret = created.get("oauth").get("clientSecret").asText();
        String principalId = created.get("principalId").asText();

        var r = tokenRequest(Map.of("grant_type", "client_credentials"), basicAuth(clientId, clientSecret));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("token_type").asText()).isEqualTo("Bearer");

        var verifier = new JwtVerifier(new JwtVerifier.Config(OAUTH_ISSUER, new JwtVerifier.RsaKeys(OAUTH_KEYS.publicKey())));
        var verified = verifier.verify(body.get("access_token").asText());
        assertThat(verified).as("a real signature this key verifies").isInstanceOf(JwtVerifier.Verified.class);
        assertThat(((JwtVerifier.Verified) verified).claims().subject())
                .as("the token's subject is the account's linked SERVICE principal").isEqualTo(principalId);

        // The wrong secret is rejected outright — the stored ref really gates access.
        var wrong = tokenRequest(Map.of("grant_type", "client_credentials"), basicAuth(clientId, "not-the-secret"));
        assertThat(wrong.statusCode()).isEqualTo(401);
    }

    @Test
    void createRejectsMissingCodeOrName() {
        // code and name are both schema-required — sent as "" so the request reaches
        // CreateServiceAccount's own blank checks instead of 400 VALIDATION.
        var noCode = http.post("/api/service-accounts", "{\"code\":\"\",\"name\":\"X\"}", anchor());
        assertThat(noCode.statusCode()).isEqualTo(400);
        assertThat(json(noCode).get("error").asText()).isEqualTo("CODE_REQUIRED");

        var noName = http.post("/api/service-accounts", "{\"code\":\"" + code("noname") + "\",\"name\":\"\"}", anchor());
        assertThat(noName.statusCode()).isEqualTo(400);
        assertThat(json(noName).get("error").asText()).isEqualTo("NAME_REQUIRED");
    }

    @Test
    void createRejectsAnUnknownWebhookAuthType() {
        var r = http.post("/api/service-accounts",
                "{\"code\":\"" + code("badauth") + "\",\"name\":\"X\",\"webhookCredentials\":{\"authType\":\"BEARER_TOEKN\"}}", anchor());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("INVALID_AUTH_TYPE");
    }

    /// docs/spec/audit-redaction.md test 2: `webhookCredentials` submitted on
    /// create is accepted for wire parity (`CreateCommand`'s doc comment —
    /// this create path always mints its own credentials, the submitted
    /// ones are discarded), but the audit row for this write still records
    /// the WHOLE submitted `CreateCommand`, including `webhookCredentials`
    /// (`PlatformSink#writeAudit`). Before redaction, the submitted secrets
    /// landed in `aud_logs.operation_json` in the clear. Mutant: remove the
    /// `SinkSupport.redactedCommandJson` call in `PlatformSink#writeAudit`
    /// -> this fails, because the row would then contain the literal secret
    /// strings asserted absent below.
    @Test
    void webhookCredentialsSubmittedOnCreateNeverAppearInTheAuditRow() throws Exception {
        String submittedToken = "audit-leak-token-" + RUN;
        String submittedSigningSecret = "audit-leak-signing-secret-" + RUN;
        var r = http.post("/api/service-accounts",
                "{\"code\":\"" + code("auditredact") + "\",\"name\":\"Audit Redact\","
                        + "\"webhookCredentials\":{\"authType\":\"BEARER_TOKEN\",\"token\":\"" + submittedToken + "\","
                        + "\"signingSecret\":\"" + submittedSigningSecret + "\"}}",
                anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = json(r).get("serviceAccount").get("id").asText();

        try (var c = TestPg.dataSource().getConnection();
             var ps = c.prepareStatement(
                     "SELECT operation_json::text FROM aud_logs WHERE operation = 'CreateCommand' AND entity_id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).as("a CreateCommand audit row was written for this account").isTrue();
                String operationJson = rs.getString(1);
                assertThat(operationJson)
                        .as("the submitted webhook token/signing secret never land in the audit row")
                        .doesNotContain(submittedToken)
                        .doesNotContain(submittedSigningSecret);
                var parsed = Json.MAPPER.readTree(operationJson);
                assertThat(parsed.get("code").asString()).as("non-secret fields are untouched").isEqualTo(code("auditredact"));
                assertThat(parsed.get("webhookCredentials").get("token").asString()).isEqualTo("***");
                assertThat(parsed.get("webhookCredentials").get("signingSecret").asString()).isEqualTo("***");
            }
        }
    }

    @Test
    void createRejectsADuplicateCode() {
        create(code("dup"), "First");
        var r = http.post("/api/service-accounts", "{\"code\":\"" + code("dup") + "\",\"name\":\"Second\"}", anchor());
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(json(r).get("error").asText()).isEqualTo("CODE_EXISTS");
    }

    @Test
    void createRequiresTheCreatePermission() {
        var r = http.post("/api/service-accounts", "{\"code\":\"" + code("forbidden") + "\",\"name\":\"X\"}", VIEWER);
        assertThat(r.statusCode()).isEqualTo(403);
    }

    // ── Read ───────────────────────────────────────────────────────────────

    @Test
    void getByIdAndByCodeAgreeAndListOmitsPrincipalId() {
        var created = create(code("read"), "Read Me");
        String id = created.get("serviceAccount").get("id").asText();

        var byId = json(http.get("/api/service-accounts/" + id, VIEWER));
        assertThat(byId.get("id").asText()).isEqualTo(id);
        assertThat(byId.get("principalId").asText()).isEqualTo(created.get("principalId").asText());
        assertThat(byId.has("token")).as("webhook secrets are never exposed on a read").isFalse();
        assertThat(byId.has("signingSecret")).isFalse();

        var byCode = json(http.get("/api/service-accounts/code/" + code("read"), VIEWER));
        assertThat(byCode.get("id").asText()).isEqualTo(id);
        assertThat(byCode.has("principalId")).as("by-code omits principalId too, matching Go").isFalse();

        var list = json(http.get("/api/service-accounts", VIEWER));
        var match = list.get("serviceAccounts").valueStream().filter(n -> n.get("id").asText().equals(id)).findFirst().orElseThrow();
        assertThat(match.has("principalId")).as("principalId is omitted from the list read (spec §9.1)").isFalse();
    }

    /// T1 (`docs/spec/login-attempt-links.md`): the single-account read must
    /// surface the OAuth client's PUBLIC `client_id` — the value a
    /// `SERVICE_ACCOUNT_TOKEN` login attempt's `identifier` actually carries
    /// — never the OAuth client row's own `id`, a different string that
    /// would resolve nothing on the wire. Mutant: return the row id.
    @Test
    void getByIdReturnsTheOAuthClientsPublicClientIdNotItsRowId() {
        var created = create(code("oauthid"), "OAuth Id");
        String id = created.get("serviceAccount").get("id").asText();
        String publicClientId = created.get("oauth").get("clientId").asText();
        String rowId = OAUTH_CLIENTS.findByClientId(publicClientId).orElseThrow().id();
        assertThat(rowId).as("fixture sanity: the row id and the public client_id must be different strings")
                .isNotEqualTo(publicClientId);

        var byId = json(http.get("/api/service-accounts/" + id, VIEWER));
        assertThat(byId.get("oauthClientId").asText())
                .as("oauthClientId must be the public client_id, the value /oauth/token callers send")
                .isEqualTo(publicClientId);
        assertThat(byId.get("oauthClientId").asText())
                .as("oauthClientId must NOT be the OAuth client row's own id")
                .isNotEqualTo(rowId);
    }

    /// T2: the list endpoint's items carry no `oauthClientId` — no per-row
    /// OAuth-client lookup on a list read, matching the `principalId`
    /// omission above. Mutant: populate it on list.
    @Test
    void listOmitsOAuthClientId() {
        var created = create(code("oauthlist"), "OAuth List");
        String id = created.get("serviceAccount").get("id").asText();

        var list = json(http.get("/api/service-accounts", VIEWER));
        var match = list.get("serviceAccounts").valueStream().filter(n -> n.get("id").asText().equals(id)).findFirst().orElseThrow();
        assertThat(match.has("oauthClientId")).as("list items must never carry oauthClientId").isFalse();
    }

    /// T3: a service account whose linked principal has no OAuth client at
    /// all reports the field ABSENT, never present-but-empty — a caller that
    /// checks for the field's presence must not be fooled by `""` standing
    /// in for "none". Mutant: return "" instead of absent.
    @Test
    void getByIdOmitsOAuthClientIdWhenNoneIsLinked() {
        var created = create(code("oauthorphan"), "OAuth Orphan");
        String id = created.get("serviceAccount").get("id").asText();
        String publicClientId = created.get("oauth").get("clientId").asText();

        // Sever the link: delete the provisioned OAuth client row outright, so the
        // account's principal has none — the same shape as an independently removed client.
        var client = OAUTH_CLIENTS.findByClientId(publicClientId).orElseThrow();
        UOW.inTransaction(tx -> { OAUTH_CLIENTS.delete(client, tx.dbTx()); return null; });

        var byId = json(http.get("/api/service-accounts/" + id, VIEWER));
        assertThat(byId.has("oauthClientId")).as("no linked OAuth client must render as an absent field").isFalse();
    }

    @Test
    void unknownIdOrCodeIs404() {
        assertThat(http.get("/api/service-accounts/sac_doesnotexist1", VIEWER).statusCode()).isEqualTo(404);
        assertThat(http.get("/api/service-accounts/code/no-such-code", VIEWER).statusCode()).isEqualTo(404);
    }

    @Test
    void readRequiresAuthentication() {
        // No bound principal -> Checks.require throws UNAUTHENTICATED, an
        // authorization-kind UseCaseError, which renders as 403 (401 is
        // reserved for a rejected bearer itself, HttpError.writeInvalidToken).
        var r = http.get("/api/service-accounts");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }

    // ── Update / deactivate / delete ─────────────────────────────────────────

    @Test
    void updateReplacesFieldsAndCodeStaysImmutable() {
        var created = create(code("update"), "Before");
        String id = created.get("serviceAccount").get("id").asText();

        var upd = http.put("/api/service-accounts/" + id, "{\"name\":\"After\",\"description\":\"new desc\"}", WRITER);
        assertThat(upd.statusCode()).isEqualTo(204);

        var got = json(http.get("/api/service-accounts/" + id, VIEWER));
        assertThat(got.get("name").asText()).isEqualTo("After");
        assertThat(got.get("description").asText()).isEqualTo("new desc");
        assertThat(got.get("code").asText()).isEqualTo(code("update"));
    }

    @Test
    void deactivateFlipsActive() {
        var created = create(code("deact"), "Deact");
        String id = created.get("serviceAccount").get("id").asText();

        assertThat(http.post("/api/service-accounts/" + id + "/deactivate", null, WRITER).statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/service-accounts/" + id, VIEWER)).get("active").asBoolean()).isFalse();
    }

    @Test
    void deleteRequiresTheDeletePermissionSpecifically() {
        var created = create(code("del"), "Del");
        String id = created.get("serviceAccount").get("id").asText();

        // A caller with only view+create cannot delete (spec §3: "delete = SERVICE_ACCOUNT_DELETE specifically").
        assertThat(http.delete("/api/service-accounts/" + id, WRITER).statusCode()).isEqualTo(403);

        assertThat(http.delete("/api/service-accounts/" + id, DELETER).statusCode()).isEqualTo(204);
        assertThat(http.get("/api/service-accounts/" + id, VIEWER).statusCode()).isEqualTo(404);
    }

    // ── Roles (anchor-only) ────────────────────────────────────────────────

    @Test
    void assignRolesIsAnchorOnlyAndRoutesAgreeWithGetById() {
        var created = create(code("roles"), "Roles");
        String id = created.get("serviceAccount").get("id").asText();

        var forbidden = http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[\"" + grantedRole.name() + "\"]}", WRITER);
        assertThat(forbidden.statusCode()).as("write permission is not anchor").isEqualTo(403);

        var assigned = http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[\"" + grantedRole.name() + "\"]}", anchor());
        assertThat(assigned.statusCode()).isEqualTo(200);
        var assignedBody = json(assigned);
        assertThat(assignedBody.get("addedRoles").valueStream().map(JsonNode::asText).toList()).containsExactly(grantedRole.name());
        assertThat(assignedBody.get("roles").valueStream().map(n -> n.get("roleName").asText()).toList()).containsExactly(grantedRole.name());

        var rolesRoute = json(http.get("/api/service-accounts/" + id + "/roles", VIEWER));
        assertThat(rolesRoute.get("roles").valueStream().map(n -> n.get("roleName").asText()).toList()).containsExactly(grantedRole.name());

        // Fix 1 (spec §9.1): the single-account read must agree with the sub-route, as equal sets.
        var accountRead = json(http.get("/api/service-accounts/" + id, VIEWER));
        assertThat(accountRead.get("roles").valueStream().map(JsonNode::asText).toList()).containsExactly(grantedRole.name());
    }

    /// Security-fixes S1.2: role assignment and token mint need
    /// `SERVICE_ACCOUNT_UPDATE` at the anchor tier too. An anchor holding the
    /// rest of the service-account family (view/create/delete) and the whole
    /// user family is refused both with `PERMISSION_REQUIRED`; the account's
    /// roles are unchanged afterwards (the observable effect); the specific
    /// code (not the wildcard) admits both.
    @Test
    void assignRolesAndMintNeedServiceAccountUpdateEvenForAnAnchor() {
        String id = create(code("s12"), "S12").get("serviceAccount").get("id").asText();
        String[] anchorWithoutUpdate = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:service-account:view,platform:iam:service-account:create,"
                        + "platform:iam:service-account:delete,platform:iam:user:update,platform:iam:user:assign-roles"};

        var assign = http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[\"" + grantedRole.name() + "\"]}", anchorWithoutUpdate);
        assertThat(assign.statusCode()).as(assign.body()).isEqualTo(403);
        assertThat(json(assign).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(json(http.get("/api/service-accounts/" + id + "/roles", VIEWER)).get("roles").size())
                .as("no role landed").isZero();

        var mint = http.post("/api/service-accounts/" + id + "/token", null, anchorWithoutUpdate);
        assertThat(mint.statusCode()).as(mint.body()).isEqualTo(403);
        assertThat(json(mint).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(mint.body()).doesNotContain("accessToken");

        // The role ceiling (owner ruling 2026-09-25) also needs the role's own permission.
        String[] anchorUpdater = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:service-account:update," + GRANTED_PERMISSION};
        assertThat(http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[\"" + grantedRole.name() + "\"]}", anchorUpdater)
                .statusCode()).isEqualTo(200);
        assertThat(http.post("/api/service-accounts/" + id + "/token", null, anchorUpdater).statusCode()).isEqualTo(200);
    }

    /// Owner ruling 2026-09-25 (backlog "Overnight review" item 14): SERVICE_ACCOUNT_UPDATE
    /// may not give an account a role whose permissions the caller lacks, nor take one away:
    /// otherwise it could give an account super-admin and mint its token.
    @Test
    void serviceAccountRolesAreBoundedByTheCallersPermissions() {
        String id = create(code("ceil"), "Ceil").get("serviceAccount").get("id").asText();
        String[] updaterOnly = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:service-account:update,platform:iam:service-account:view"};
        // grantedRole holds GRANTED_PERMISSION, which updaterOnly lacks (this fixture seeds no
        // catalogue roles, so an unseeded name like platform:super-admin would carry nothing).
        var refused = http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[\"" + grantedRole.name() + "\"]}", updaterOnly);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused).get("error").asText()).isEqualTo("ROLE_ABOVE_CALLER");
        assertThat(json(http.get("/api/service-accounts/" + id + "/roles", VIEWER)).get("roles").size()).isZero();

        assertThat(http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[\"" + grantedRole.name() + "\"]}", anchor())
                .statusCode()).isEqualTo(200);
        assertThat(http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[]}", updaterOnly).statusCode())
                .as("removal counts").isEqualTo(403);
    }

    // ── Regenerate token / secret: one-shot disclosure (spec §5) ────────────

    @Test
    void regenerateAuthTokenIsPermissionGatedNotAnchorOnlyAndDisclosesOnce() {
        var created = create(code("regtok"), "RegTok");
        String id = created.get("serviceAccount").get("id").asText();
        String originalToken = created.get("webhook").get("authToken").asText();

        assertThat(http.post("/api/service-accounts/" + id + "/regenerate-token", null, VIEWER).statusCode())
                .as("view alone must not rotate").isEqualTo(403);

        var r = http.post("/api/service-accounts/" + id + "/regenerate-token", null, WRITER);
        assertThat(r.statusCode()).isEqualTo(200);
        String newToken = json(r).get("authToken").asText();
        assertThat(newToken).isNotBlank().isNotEqualTo(originalToken);

        // The alias behaves identically.
        var r2 = http.post("/api/service-accounts/" + id + "/regenerate-auth-token", null, WRITER);
        assertThat(r2.statusCode()).isEqualTo(200);
        assertThat(json(r2).get("authToken").asText()).isNotBlank().isNotEqualTo(newToken);

        // The plaintext is never returned again on a normal read.
        var got = json(http.get("/api/service-accounts/" + id, VIEWER));
        assertThat(got.has("authToken")).isFalse();
        assertThat(got.has("token")).isFalse();
    }

    @Test
    void regenerateSigningSecretDisclosesOnceAndTheAliasMatches() {
        var created = create(code("regsec"), "RegSec");
        String id = created.get("serviceAccount").get("id").asText();
        String originalSecret = created.get("webhook").get("signingSecret").asText();

        var r = http.post("/api/service-accounts/" + id + "/regenerate-secret", null, WRITER);
        assertThat(r.statusCode()).isEqualTo(200);
        String newSecret = json(r).get("signingSecret").asText();
        assertThat(newSecret).isNotBlank().isNotEqualTo(originalSecret);

        var r2 = http.post("/api/service-accounts/" + id + "/regenerate-signing-secret", null, WRITER);
        assertThat(r2.statusCode()).isEqualTo(200);
        assertThat(json(r2).get("signingSecret").asText()).isNotBlank().isNotEqualTo(newSecret);
    }

    // ── Token mint (spec §8) ─────────────────────────────────────────────────

    @Test
    void mintTokenIsAnchorOnlyAndReturnsAJwtTheServerItselfVerifies() {
        var created = create(code("mint"), "Mint");
        String id = created.get("serviceAccount").get("id").asText();
        String principalId = created.get("principalId").asText();
        http.put("/api/service-accounts/" + id + "/roles", "{\"roles\":[\"" + grantedRole.name() + "\"]}", anchor());

        assertThat(http.post("/api/service-accounts/" + id + "/token", null, WRITER).statusCode())
                .as("write permission is not anchor").isEqualTo(403);

        var r = http.post("/api/service-accounts/" + id + "/token", null, anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(3600);
        assertThat(body.get("scope").asText()).isEqualTo(GRANTED_PERMISSION);

        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
        var verified = verifier.verify(body.get("accessToken").asText());
        assertThat(verified).as("the mint really produced a signature this key verifies").isInstanceOf(JwtVerifier.Verified.class);
        var claims = ((JwtVerifier.Verified) verified).claims();
        assertThat(claims.subject()).as("the token's subject is the linked SERVICE principal").isEqualTo(principalId);
        assertThat(claims.permissions()).containsExactly(GRANTED_PERMISSION);
        assertThat(claims.tokenUse()).isEqualTo("api");

        // last_used_at is stamped by the mint (spec §9.2).
        var got = json(http.get("/api/service-accounts/" + id, VIEWER));
        assertThat(got.has("lastUsedAt")).isTrue();

        // Owner ruling 2026-09-06 #15: the mint is audited — who obtained a credential
        // for which account — and neither the audit row nor the event carries the token.
        var db = org.jooq.impl.DSL.using(TestPg.dataSource(), org.jooq.SQLDialect.POSTGRES);
        var audits = db.fetch("SELECT principal_id, operation_json::text AS operation_json FROM aud_logs WHERE entity_id = ? AND operation = ?",
                id, "MintServiceAccountTokenCommand");
        assertThat(audits).as("one audit row per mint").hasSize(1);
        assertThat(audits.getFirst().get("principal_id", String.class)).as("the actor is the anchor who minted, not the service principal")
                .isNotEqualTo(principalId).isNotNull();
        assertThat(audits.getFirst().get("operation_json", String.class)).doesNotContain(body.get("accessToken").asText());
        var events = db.fetch("SELECT data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                "platform.serviceaccount." + id, "platform:iam:serviceaccount:token-minted");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("data", String.class)).contains(principalId).doesNotContain(body.get("accessToken").asText());
    }

    /// The audit is best-effort (spec §8 step 8): a unit of work that cannot
    /// commit must not refuse the credential.
    @Test
    void mintTokenStillAnswersWhenTheAuditCannotBeWritten() {
        var created = create(code("mintnoaudit"), "MintNoAudit");
        String id = created.get("serviceAccount").get("id").asText();
        javax.sql.DataSource broken = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
                (proxy, method, args) -> { throw new java.sql.SQLException("audit store down"); });
        var state = new ServiceAccountApi.State(SA_REPO, PRINCIPALS, new UnitOfWork(broken, new PlatformSink(Json.MAPPER)),
                OAUTH_CLIENTS, CLIENTS, ENCRYPTION, new RsaServiceAccountTokenMinter(KEYS, ISSUER, ISSUER), roleNames -> List.of());
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(KEYS.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        try (var h = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            ServiceAccountApi.register(routes, state);
        })) {
            var r = h.post("/api/service-accounts/" + id + "/token", null, anchor());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(json(r).get("accessToken").asText()).isNotBlank();
        }
        var db = org.jooq.impl.DSL.using(TestPg.dataSource(), org.jooq.SQLDialect.POSTGRES);
        assertThat(db.fetch("SELECT 1 FROM aud_logs WHERE entity_id = ? AND operation = ?", id, "MintServiceAccountTokenCommand"))
                .as("nothing was audited — and the mint answered anyway").isEmpty();
    }

    @Test
    void mintTokenRefusesADeactivatedAccountWithA400() {
        var created = create(code("mintdeact"), "MintDeact");
        String id = created.get("serviceAccount").get("id").asText();
        http.post("/api/service-accounts/" + id + "/deactivate", null, WRITER);

        var r = http.post("/api/service-accounts/" + id + "/token", null, anchor());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("SERVICE_ACCOUNT_INACTIVE");
    }

    // ── Service-account reach (spec: docs/spec/service-account-reach.md) ────

    /// spec §3.2, end to end: one `clientIds` entry at create mints a real
    /// `client_credentials` token whose decoded `tier`/`clients` are `CLIENT`
    /// / that one client; the anchor-only `/api/clients` refuses it
    /// (`ANCHOR_REQUIRED`, permissions notwithstanding); once granted
    /// `platform:viewer` and re-minted, `/api/subscriptions` — a
    /// client-scoped list — returns only this account's own client's row,
    /// never the other client's. Mutant: skip the derivation in
    /// `CreateServiceAccountWithCredentials` → `tier` stays `ANCHOR` and
    /// `GET /api/clients` answers 200, not 403.
    @Test
    void clientCredentialsTokenForAOneClientAccountIsClientScopedAndClientFilteredButAnchorRoutesRefuseIt() {
        String myClient = seedClient("reachmine");
        String otherClient = seedClient("reachother");
        var mine = seedSubscription(code("reachsubmine"), myClient);
        var other = seedSubscription(code("reachsubother"), otherClient);

        var created = createWithClientIds(code("reachclient"), "ReachClient", List.of(myClient));
        String saId = created.get("serviceAccount").get("id").asText();
        String oauthClientId = created.get("oauth").get("clientId").asText();
        String oauthClientSecret = created.get("oauth").get("clientSecret").asText();

        var tokenResp = tokenRequest(Map.of("grant_type", "client_credentials"), basicAuth(oauthClientId, oauthClientSecret));
        assertThat(tokenResp.statusCode()).as(tokenResp.body()).isEqualTo(200);
        String accessToken = json(tokenResp).get("access_token").asText();

        var payload = decodePayload(accessToken);
        assertThat(payload.get("tier").asText()).isEqualTo("CLIENT");
        var clientsClaim = payload.get("clients").valueStream().map(JsonNode::asText).toList();
        assertThat(clientsClaim).as("exactly the one linked client").hasSize(1);
        assertThat(clientsClaim.getFirst()).startsWith(myClient);

        // Anchor-only route: refused regardless of permission (spec §2, "reach, not authority").
        var forbidden = apiHttp.get("/api/clients", "Authorization", "Bearer " + accessToken);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");

        // Grant the account a view permission as the anchor admin, then mint a FRESH
        // token — the token is self-contained, so permissions are baked in at mint time.
        var roleAssign = http.put("/api/service-accounts/" + saId + "/roles",
                "{\"roles\":[\"" + subscriptionViewerRole.name() + "\"]}", anchor());
        assertThat(roleAssign.statusCode()).as(roleAssign.body()).isEqualTo(200);
        var tokenResp2 = tokenRequest(Map.of("grant_type", "client_credentials"), basicAuth(oauthClientId, oauthClientSecret));
        assertThat(tokenResp2.statusCode()).as(tokenResp2.body()).isEqualTo(200);
        String accessToken2 = json(tokenResp2).get("access_token").asText();

        var list = json(apiHttp.get("/api/subscriptions", "Authorization", "Bearer " + accessToken2));
        var visibleCodesAmongOurs = list.get("subscriptions").valueStream().map(n -> n.get("code").asText())
                .filter(c -> c.equals(mine.code()) || c.equals(other.code())).toList();
        assertThat(visibleCodesAmongOurs).as("only this account's own client's subscription is visible")
                .containsExactly(mine.code());
    }

    /// spec §3.1/§3.2: several `clientIds` at create make the linked principal
    /// `PARTNER` with each id a grant — the token's decoded `tier`/`clients`
    /// carry that directly. Mutant: derivation skipped → `tier` stays `ANCHOR`
    /// (killed the same way as the single-client test above, from the other end).
    @Test
    void clientCredentialsTokenForATwoClientAccountIsPartnerWithBothClients() {
        String clientA = seedClient("reachpa");
        String clientB = seedClient("reachpb");

        var created = createWithClientIds(code("reachpartner"), "ReachPartner", List.of(clientA, clientB));
        String oauthClientId = created.get("oauth").get("clientId").asText();
        String oauthClientSecret = created.get("oauth").get("clientSecret").asText();

        var tokenResp = tokenRequest(Map.of("grant_type", "client_credentials"), basicAuth(oauthClientId, oauthClientSecret));
        assertThat(tokenResp.statusCode()).as(tokenResp.body()).isEqualTo(200);
        var payload = decodePayload(json(tokenResp).get("access_token").asText());

        assertThat(payload.get("tier").asText()).isEqualTo("PARTNER");
        var clientsClaim = payload.get("clients").valueStream().map(JsonNode::asText).toList();
        assertThat(clientsClaim).hasSize(2);
        assertThat(clientsClaim).anySatisfy(pair -> assertThat(pair).startsWith(clientA));
        assertThat(clientsClaim).anySatisfy(pair -> assertThat(pair).startsWith(clientB));
    }
}
