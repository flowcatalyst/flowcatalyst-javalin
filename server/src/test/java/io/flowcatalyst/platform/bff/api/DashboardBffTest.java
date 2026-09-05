package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.bff.DashboardRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.role.Role;
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

/// `GET /bff/dashboard/stats` end to end: the exact counts genuinely reflect
/// the tables they claim to (not just "some number came back"), the
/// approximate counts are well-formed, and the gate is "any authenticated
/// principal" — and the admin gate (spec §2).
@SuppressWarnings("deprecation")
class DashboardBffTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static TestHttp http;

    private static final String[] ADMIN = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};
    private static final String[] VIEWER = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT"}; // no special permission at all

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/bff/*", auth);
            DashboardBff.register(cfg.routes, new DashboardBff.State(new DashboardRepository(TestPg.dataSource())));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode stats(String... headers) {
        var r = http.get("/bff/dashboard/stats", headers);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r);
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static void persistClient(String name) {
        uow.inTransaction(tx -> {
            new io.flowcatalyst.platform.client.ClientRepository(TestPg.dataSource())
                    .persist(Client.create(name, ClientIdentifier.parse("dashboard-" + RUN + "-" + UUID.randomUUID())), tx.dbTx());
            return null;
        });
    }

    private static void persistRole(String shortName) {
        uow.inTransaction(tx -> {
            new io.flowcatalyst.platform.role.RoleRepository(TestPg.dataSource())
                    .persist(Role.create("dash" + RUN, shortName, shortName), tx.dbTx());
            return null;
        });
    }

    private static void persistPrincipal(Principal p) {
        uow.inTransaction(tx -> {
            new io.flowcatalyst.platform.principal.PrincipalRepository(TestPg.dataSource()).persist(p, tx.dbTx());
            return null;
        });
    }

    // ── Load-bearing: the exact counters actually move on the exact rows ───

    @Test
    void totalClientsMovesByExactlyTheClientsAdded() {
        long before = stats(ADMIN).get("totalClients").asLong();
        persistClient("Dash Co A " + RUN);
        persistClient("Dash Co B " + RUN);
        long after = stats(ADMIN).get("totalClients").asLong();
        assertThat(after - before).as("two clients inserted").isEqualTo(2);
    }

    @Test
    void rolesDefinedMovesByExactlyTheRolesAdded() {
        long before = stats(ADMIN).get("rolesDefined").asLong();
        persistRole("dash-role-a-" + RUN);
        persistRole("dash-role-b-" + RUN);
        long after = stats(ADMIN).get("rolesDefined").asLong();
        assertThat(after - before).as("two roles inserted").isEqualTo(2);
    }

    /// Pins the exact WHERE clause (`type='USER' AND active=true`), not just
    /// "a principal was inserted": an inactive user and a service principal
    /// must NOT move the counter, only the active user does.
    @Test
    void activeUsersCountsOnlyActiveUserPrincipals() throws Exception {
        long before = stats(ADMIN).get("activeUsers").asLong();

        Principal activeUser = Principal.newUser(EmailAddress.parse("dash-active-" + RUN + "@example.com"), UserScope.CLIENT);
        Principal inactiveUser = Principal.newUser(EmailAddress.parse("dash-inactive-" + RUN + "@example.com"), UserScope.CLIENT).deactivate();
        Principal service = Principal.newService(EntityType.SERVICE_ACCOUNT.generate(), "dash-service-" + RUN);

        persistPrincipal(activeUser);
        persistPrincipal(inactiveUser);
        persistPrincipal(service);

        long after = stats(ADMIN).get("activeUsers").asLong();
        assertThat(after - before).as("only the active USER principal counts").isEqualTo(1);
    }

    @Test
    void approximateFieldsAreWellFormedIntegers() {
        var j = stats(ADMIN);
        for (String field : new String[]{"eventsApprox", "dispatchJobsApprox", "auditLogsApprox", "loginAttemptsApprox"}) {
            assertThat(j.has(field)).as(field + " present").isTrue();
            assertThat(j.get(field).asLong()).as(field + " >= 0").isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void everyResponseKeyIsPresent() {
        var j = stats(ADMIN);
        assertThat(j.propertyNames()).containsExactlyInAnyOrder("totalClients", "activeUsers", "rolesDefined",
                "eventsApprox", "dispatchJobsApprox", "auditLogsApprox", "loginAttemptsApprox");
    }

    // ── Gate: admin only (spec §2, Go auth.IsAdmin) ────────────────────────

    @Test
    void clientScopedPrincipalWithoutSuperAdminIsForbidden() {
        var r = http.get("/bff/dashboard/stats", VIEWER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("ADMIN_REQUIRED");
    }

    @Test
    void anchorCanRead() {
        assertThat(http.get("/bff/dashboard/stats", ADMIN).statusCode()).isEqualTo(200);
    }

    @Test
    void unauthenticatedIsRejected() {
        // Go semantics, as everywhere else on the platform: the check helpers
        // raise UNAUTHENTICATED as an *authorization* kind, which renders 403.
        var r = http.get("/bff/dashboard/stats");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("UNAUTHENTICATED");
    }
}
