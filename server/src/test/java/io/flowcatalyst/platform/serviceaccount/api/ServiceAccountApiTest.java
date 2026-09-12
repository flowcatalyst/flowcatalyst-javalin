package io.flowcatalyst.platform.serviceaccount.api;

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
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.RsaServiceAccountTokenMinter;
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
    private static final String GRANTED_PERMISSION = "platform:events:create";

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

    private static TestHttp http;
    /// A second, independent server exercising only `POST /oauth/token`
    /// against the SAME `OAUTH_CLIENTS` repository the service-account API
    /// writes to — proves the minted pair is a real, usable credential
    /// (spec §8), not merely a row that exists.
    private static TestHttp oauthHttp;
    private static Role grantedRole;

    private static String[] anchor() {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR"};
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
        grantedRole = Role.create("saapi" + RUN, "granter", "Granter").update(new Role.Changes(null, null, List.of(GRANTED_PERMISSION), null));
        UOW.inTransaction(tx -> {
            ROLES.persist(grantedRole, tx.dbTx());
            return null;
        });

        var minter = new RsaServiceAccountTokenMinter(KEYS, ISSUER, ISSUER);
        var state = new ServiceAccountApi.State(SA_REPO, PRINCIPALS, UOW, OAUTH_CLIENTS, ENCRYPTION, minter,
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
    }

    @AfterAll
    static void stop() {
        http.close();
        oauthHttp.close();
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
                OAUTH_CLIENTS, ENCRYPTION, new RsaServiceAccountTokenMinter(KEYS, ISSUER, ISSUER), roleNames -> List.of());
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
}
