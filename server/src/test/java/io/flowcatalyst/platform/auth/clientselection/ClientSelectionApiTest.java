package io.flowcatalyst.platform.auth.clientselection;

import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.token.ClaimLabels;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_CLIENT_ACCESS_GRANTS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLE_PERMISSIONS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-core.md` §6.5 over the embedded Postgres: what each
/// scope may reach, that a switch refuses what it may not, and that the
/// token a switch hands back is the full-authority API token.
class ClientSelectionApiTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final String ISSUER = "http://localhost:8080";
    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final TokenIssuer TOKEN_ISSUER = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));
    private static final JwtVerifier VERIFIER = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
    private static final List<String> principals = new ArrayList<>();
    private static final List<String> clients = new ArrayList<>();
    private static String appId;
    private static String roleName;
    private static String active1;
    private static String active2;
    private static String inactive;
    private static TestHttp http;
    private static ClientSelectionApi.State state;

    @BeforeAll
    static void start() {
        active1 = client("alpha-" + RUN, "ACTIVE");
        active2 = client("zulu-" + RUN, "ACTIVE");
        inactive = client("gone-" + RUN, "INACTIVE");
        appId = EntityType.APPLICATION.generate();
        DB.insertInto(APP_APPLICATIONS).set(APP_APPLICATIONS.ID, appId).set(APP_APPLICATIONS.TYPE, "APPLICATION")
                .set(APP_APPLICATIONS.CODE, "cs-" + RUN).set(APP_APPLICATIONS.NAME, "cs").set(APP_APPLICATIONS.ACTIVE, true)
                .set(APP_APPLICATIONS.CREATED_AT, NOW).set(APP_APPLICATIONS.UPDATED_AT, NOW).execute();
        roleName = "cs-" + RUN + ":agent";
        String roleId = EntityType.ROLE.generate();
        DB.insertInto(IAM_ROLES).set(IAM_ROLES.ID, roleId).set(IAM_ROLES.APPLICATION_ID, appId).set(IAM_ROLES.APPLICATION_CODE, "cs-" + RUN)
                .set(IAM_ROLES.NAME, roleName).set(IAM_ROLES.DISPLAY_NAME, "agent").set(IAM_ROLES.SOURCE, "DATABASE").set(IAM_ROLES.CLIENT_MANAGED, false)
                .set(IAM_ROLES.CREATED_AT, NOW).set(IAM_ROLES.UPDATED_AT, NOW).execute();
        DB.insertInto(IAM_ROLE_PERMISSIONS).set(IAM_ROLE_PERMISSIONS.ROLE_ID, roleId).set(IAM_ROLE_PERMISSIONS.PERMISSION, "cs:read").execute();
        var resolver = new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS));
        state = new ClientSelectionApi.State(PRINCIPALS, new ClientRepository(DS), new ClientAccessGrantRepository(DS), TOKEN_ISSUER,
                resolver, ClaimLabels.of(new ClientRepository(DS), new ApplicationRepository(DS)));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/auth/client/*", new Authenticator(VERIFIER, resolver, Authenticator.Config.of(false)));
            ClientSelectionApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(IAM_CLIENT_ACCESS_GRANTS).where(IAM_CLIENT_ACCESS_GRANTS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPAL_ROLES).where(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(principals)).execute();
        DB.deleteFrom(IAM_ROLE_PERMISSIONS).where(IAM_ROLE_PERMISSIONS.ROLE_ID.in(DB.select(IAM_ROLES.ID).from(IAM_ROLES).where(IAM_ROLES.APPLICATION_ID.eq(appId)))).execute();
        DB.deleteFrom(IAM_ROLES).where(IAM_ROLES.APPLICATION_ID.eq(appId)).execute();
        DB.deleteFrom(APP_APPLICATIONS).where(APP_APPLICATIONS.ID.eq(appId)).execute();
        DB.deleteFrom(TNT_CLIENTS).where(TNT_CLIENTS.ID.in(clients)).execute();
    }

    @Test
    void anAnchorSeesEveryActiveClientSortedByNameAndMaySwitchToAny() throws Exception {
        String pid = user("ANCHOR", null);
        var r = json(http.get("/auth/client/accessible", "Cookie", session(pid)));
        List<String> ids = new ArrayList<>();
        r.get("clients").forEach(c -> ids.add(c.get("id").asString()));
        assertThat(ids).contains(active1, active2).doesNotContain(inactive);
        assertThat(ids.indexOf(active1)).as("alpha before zulu").isLessThan(ids.indexOf(active2));
        assertThat(r.get("globalAccess").asBoolean()).isTrue();
        assertThat(r.has("currentClientId")).isFalse();

        var sw = http.post("/auth/client/switch", Json.write(Map.of("clientId", active2)), "Cookie", session(pid));
        assertThat(sw.statusCode()).as(sw.body()).isEqualTo(200);
        JsonNode s = json(sw);
        assertThat(s.get("client").get("identifier").asString()).isEqualTo("zulu-" + RUN);
        assertThat(s.get("roles").toString()).isEqualTo("[\"" + roleName + "\"]");
        assertThat(s.get("permissions").toString()).isEqualTo("[\"cs:read\"]");
        var payload = SignedJWT.parse(s.get("token").asString()).getPayload().toJSONObject();
        assertThat(payload.get("sub")).isEqualTo(pid);
        assertThat(payload.get("token_use")).as("the full-authority API token").isEqualTo("api");
        assertThat(VERIFIER.verify(s.get("token").asString())).isInstanceOf(JwtVerifier.Verified.class);

        var inactiveSwitch = http.post("/auth/client/switch", Json.write(Map.of("clientId", inactive)), "Cookie", session(pid));
        assertThat(inactiveSwitch.statusCode()).isEqualTo(403);
        assertThat(json(inactiveSwitch).get("message").asString()).startsWith("Client is not active");
        var unknown = http.post("/auth/client/switch", Json.write(Map.of("clientId", "cli_nope")), "Cookie", session(pid));
        assertThat(unknown.statusCode()).isEqualTo(404);
        var cur = json(http.get("/auth/client/current", "Cookie", session(pid)));
        assertThat(cur.get("noClientContext").asBoolean()).isTrue();
    }

    @Test
    void aClientScopeUserReachesTheHomeClientAndGrantsOnly() {
        String pid = user("CLIENT", active1);
        DB.insertInto(IAM_CLIENT_ACCESS_GRANTS).set(IAM_CLIENT_ACCESS_GRANTS.ID, EntityType.CLIENT_ACCESS_GRANT.generate())
                .set(IAM_CLIENT_ACCESS_GRANTS.PRINCIPAL_ID, pid).set(IAM_CLIENT_ACCESS_GRANTS.CLIENT_ID, inactive)
                .set(IAM_CLIENT_ACCESS_GRANTS.GRANTED_BY, pid).set(IAM_CLIENT_ACCESS_GRANTS.GRANTED_AT, NOW)
                .set(IAM_CLIENT_ACCESS_GRANTS.CREATED_AT, NOW).set(IAM_CLIENT_ACCESS_GRANTS.UPDATED_AT, NOW).execute();
        var r = json(http.get("/auth/client/accessible", "Cookie", session(pid)));
        List<String> ids = new ArrayList<>();
        r.get("clients").forEach(c -> ids.add(c.get("id").asString()));
        assertThat(ids).as("home client; the granted one is inactive and dropped").containsExactly(active1);
        assertThat(r.get("currentClientId").asString()).isEqualTo(active1);
        assertThat(r.get("globalAccess").asBoolean()).isFalse();

        var denied = http.post("/auth/client/switch", Json.write(Map.of("clientId", active2)), "Cookie", session(pid));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("message").asString()).isEqualTo("Access denied to client: " + active2);
        var ok = http.post("/auth/client/switch", Json.write(Map.of("clientId", active1)), "Cookie", session(pid));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        var cur = json(http.get("/auth/client/current", "Cookie", session(pid)));
        assertThat(cur.get("client").get("id").asString()).isEqualTo(active1);
        assertThat(cur.get("noClientContext").asBoolean()).isFalse();
        assertThat(http.get("/auth/client/current").statusCode()).as("no session").isEqualTo(403);
    }

    /// S2.1: the switch mints the principal's FULL authority, so only the
    /// cookie session — which already holds exactly that — may ask. A bearer
    /// narrowed to nothing (no clients, no roles, no scope) for an anchor user
    /// is refused on all three routes, and no token comes back.
    @Test
    void aNarrowedBearerCannotSwitchIntoFullAuthority() {
        String pid = user("ANCHOR", null);
        String narrowed = "Bearer " + TOKEN_ISSUER.accessToken(PRINCIPALS.findById(pid).orElseThrow(),
                new TokenIssuer.Authority(List.of(), List.of(), List.of(), false, List.of()), "oac_narrow");
        var sw = http.post("/auth/client/switch", Json.write(Map.of("clientId", active1)), "Authorization", narrowed);
        assertThat(sw.statusCode()).as("mutant: any AuthContext accepted — " + sw.body()).isEqualTo(403);
        assertThat(json(sw).has("token")).isFalse();
        assertThat(json(sw).get("error").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(http.get("/auth/client/accessible", "Authorization", narrowed).statusCode()).isEqualTo(403);
        assertThat(http.get("/auth/client/current", "Authorization", narrowed).statusCode()).isEqualTo(403);
        assertThat(http.post("/auth/client/switch", Json.write(Map.of("clientId", active1)), "Cookie", session(pid)).statusCode())
                .as("the same principal through its cookie").isEqualTo(200);
    }

    /// S2.1: the principal must be active at the route itself, not only in
    /// whichever resolver built the session — pinned with a resolver that
    /// vouches for anyone, so only the route's own check stands between a
    /// deactivated user and a full-authority token.
    @Test
    void aDeactivatedPrincipalCannotSwitchEvenWhenTheResolverVouches() {
        String pid = user("ANCHOR", null);
        DB.update(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ACTIVE, false).where(IAM_PRINCIPALS.ID.eq(pid)).execute();
        ClaimsResolver vouchesForAnyone = id -> Optional.of(new AuthContext(id, Scope.ANCHOR, null, List.of(), List.of(), List.of(),
                true, List.of()));
        try (var lax = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/auth/client/*", new Authenticator(VERIFIER, vouchesForAnyone, Authenticator.Config.of(false)));
            ClientSelectionApi.register(routes, state);
        })) {
            var sw = lax.post("/auth/client/switch", Json.write(Map.of("clientId", active1)), "Cookie", session(pid));
            assertThat(sw.statusCode()).as("mutant: no active check — " + sw.body()).isEqualTo(403);
            assertThat(json(sw).has("token")).isFalse();
        }
    }

    private static String session(String pid) {
        return "fc_session=" + TOKEN_ISSUER.sessionToken(pid, pid + "@example.com");
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static String client(String identifier, String status) {
        String id = EntityType.CLIENT.generate();
        DB.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, id).set(TNT_CLIENTS.NAME, identifier).set(TNT_CLIENTS.IDENTIFIER, identifier)
                .set(TNT_CLIENTS.STATUS, status).set(TNT_CLIENTS.CREATED_AT, NOW).set(TNT_CLIENTS.UPDATED_AT, NOW).execute();
        clients.add(id);
        return id;
    }

    private static String user(String scope, String clientId) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, scope)
                .set(IAM_PRINCIPALS.CLIENT_ID, clientId).set(IAM_PRINCIPALS.NAME, "CS " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, false).set(IAM_PRINCIPALS.EMAIL, id + "@example.com").set(IAM_PRINCIPALS.EMAIL_DOMAIN, "example.com")
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW).execute();
        DB.insertInto(IAM_PRINCIPAL_ROLES).set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, id).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, roleName)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "ADMIN_ASSIGNED").set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, NOW).execute();
        principals.add(id);
        return id;
    }
}
