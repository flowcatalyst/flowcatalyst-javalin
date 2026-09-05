package io.flowcatalyst.platform.auth.claims;

import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_CLIENT_ACCESS_GRANTS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_APPLICATION_ACCESS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLE_PERMISSIONS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// auth-core §3.5 on the embedded Postgres: what a session cookie resolves to,
/// how roles flatten, and how they narrow for a relying party. Rows are
/// inserted directly and deleted in [#cleanup] — `TestPg` never truncates.
class DbClaimsResolverTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);

    private static final DbClaimsResolver RESOLVER = new DbClaimsResolver(new PrincipalRepository(DS), new RoleRepository(DS));

    private static String appOrders;
    private static String appBilling;
    private static String clientAcme;
    private static String roleOrdersAdmin;   // "orders-<run>:admin", app-scoped
    private static String roleBillingViewer; // "billing-<run>:viewer", app-scoped
    private static String rolePlatform;      // platform role, no application
    private static String principalActive;
    private static String principalInactive;

    @BeforeAll
    static void seed() {
        appOrders = app("orders-" + RUN);
        appBilling = app("billing-" + RUN);
        clientAcme = client("acme-" + RUN);
        roleOrdersAdmin = role(appOrders, "orders-" + RUN, "orders-" + RUN + ":admin",
                List.of("orders:order:order:read", "orders:order:order:write"));
        roleBillingViewer = role(appBilling, "billing-" + RUN, "billing-" + RUN + ":viewer",
                List.of("billing:invoice:invoice:read", "orders:order:order:read"));
        rolePlatform = role(null, null, "platform-" + RUN + ":auditor", List.of("platform:audit:log:read"));

        principalActive = principal(true, "PARTNER", null);
        assignRole(principalActive, roleOrdersAdmin);
        assignRole(principalActive, roleBillingViewer);
        assignRole(principalActive, rolePlatform);
        assignRole(principalActive, "ghost-" + RUN + ":deleted"); // no such role
        grantClient(principalActive, clientAcme);
        grantApplication(principalActive, appOrders);

        principalInactive = principal(false, "CLIENT", clientAcme);
    }

    @AfterAll
    static void cleanup() {
        for (String p : List.of(principalActive, principalInactive)) {
            DB.deleteFrom(IAM_PRINCIPAL_ROLES).where(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.eq(p)).execute();
            DB.deleteFrom(IAM_CLIENT_ACCESS_GRANTS).where(IAM_CLIENT_ACCESS_GRANTS.PRINCIPAL_ID.eq(p)).execute();
            DB.deleteFrom(IAM_PRINCIPAL_APPLICATION_ACCESS).where(IAM_PRINCIPAL_APPLICATION_ACCESS.PRINCIPAL_ID.eq(p)).execute();
            DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(p)).execute();
        }
        for (String r : List.of(roleOrdersAdmin, roleBillingViewer, rolePlatform)) {
            DB.deleteFrom(IAM_ROLE_PERMISSIONS).where(IAM_ROLE_PERMISSIONS.ROLE_ID.eq(r)).execute();
            DB.deleteFrom(IAM_ROLES).where(IAM_ROLES.ID.eq(r)).execute();
        }
        DB.deleteFrom(TNT_CLIENTS).where(TNT_CLIENTS.ID.eq(clientAcme)).execute();
        DB.deleteFrom(APP_APPLICATIONS).where(APP_APPLICATIONS.ID.in(appOrders, appBilling)).execute();
    }

    // ── resolveSession ─────────────────────────────────────────────────────

    @Test
    void anActiveSessionResolvesToTheFullAuthorityView() {
        var ac = RESOLVER.resolveSession(principalActive).orElseThrow();
        assertThat(ac.principalId()).isEqualTo(principalActive);
        assertThat(ac.scope()).isEqualTo(Scope.PARTNER);
        assertThat(ac.email()).isEqualTo("p-" + RUN + "@example.com");
        assertThat(ac.clients()).as("bare ids, post-boundary").containsExactly(clientAcme);
        // The repository orders role assignments by name, so order is the
        // store's, not the assignment's; the set is what the cookie path pins.
        assertThat(ac.roles()).containsExactlyInAnyOrder(roleName(roleOrdersAdmin), roleName(roleBillingViewer),
                roleName(rolePlatform), "ghost-" + RUN + ":deleted");
        assertThat(ac.applications()).containsExactly(appOrders);
        assertThat(ac.allApplications()).isFalse();
        assertThat(ac.permissions())
                .as("flattened across roles, de-duplicated, the deleted role skipped")
                .containsExactlyInAnyOrder("orders:order:order:read", "orders:order:order:write",
                        "billing:invoice:invoice:read", "platform:audit:log:read")
                .doesNotHaveDuplicates();
        assertThat(ac.canAccessClient(clientAcme)).isTrue();
        assertThat(ac.canAccessApplication(appOrders)).isTrue();
        assertThat(ac.canAccessApplication(appBilling)).isFalse();
    }

    @Test
    void aClientScopedSessionCarriesItsHomeClient() {
        // Reactivate briefly through the store to observe the home-client rule.
        DB.update(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ACTIVE, true).where(IAM_PRINCIPALS.ID.eq(principalInactive)).execute();
        try {
            var ac = RESOLVER.resolveSession(principalInactive).orElseThrow();
            assertThat(ac.scope()).isEqualTo(Scope.CLIENT);
            assertThat(ac.clients()).containsExactly(clientAcme);
        } finally {
            DB.update(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ACTIVE, false).where(IAM_PRINCIPALS.ID.eq(principalInactive)).execute();
        }
    }

    @Test
    void aDeactivatedOrUnknownPrincipalIsLoggedOutNotErrored() {
        assertThat(RESOLVER.resolveSession(principalInactive)).as("deactivated → logged out").isEmpty();
        assertThat(RESOLVER.resolveSession(EntityType.PRINCIPAL.generate())).as("unknown → logged out").isEmpty();
        assertThat(RESOLVER.resolveSession("")).isEmpty();
        assertThat(RESOLVER.resolveSession(null)).isEmpty();
    }

    // ── flattenPermissions ─────────────────────────────────────────────────

    @Test
    void flattenSkipsUnknownRolesAndDeduplicates() {
        var perms = RESOLVER.flattenPermissions(List.of(roleName(roleBillingViewer), "nope-" + RUN + ":x", roleName(roleOrdersAdmin)));
        assertThat(perms).containsExactly("billing:invoice:invoice:read", "orders:order:order:read", "orders:order:order:write");
        assertThat(RESOLVER.flattenPermissions(List.of())).isEmpty();
        assertThat(RESOLVER.flattenPermissions(null)).isEmpty();
    }

    // ── filterRolesForApplications ─────────────────────────────────────────

    @Test
    void narrowingKeepsOnlyTheClientsApplicationsRolesUnderTheirCanonicalNames() {
        var names = List.of(roleName(roleOrdersAdmin), roleName(roleBillingViewer), roleName(rolePlatform), "ghost-" + RUN + ":deleted");
        assertThat(RESOLVER.filterRolesForApplications(names, List.of(appOrders)))
                .as("other app dropped, platform role dropped, unknown dropped")
                .containsExactly(roleName(roleOrdersAdmin));
        assertThat(RESOLVER.filterRolesForApplications(names, List.of(appOrders, appBilling)))
                .containsExactly(roleName(roleOrdersAdmin), roleName(roleBillingViewer));
        assertThat(RESOLVER.filterRolesForApplications(names, List.of())).isEmpty();
    }

    @Test
    void anSdkSyncedShortNameResolvesWithinTheClientsApplicationsAndIsEmittedCanonically() {
        // The assignment carries the bare short name ("admin"); the client's
        // application is orders, so it resolves to orders-<run>:admin — and
        // is emitted under that canonical spelling, never the short one.
        assertThat(RESOLVER.filterRolesForApplications(List.of("admin"), List.of(appOrders)))
                .containsExactly(roleName(roleOrdersAdmin));
        assertThat(RESOLVER.filterRolesForApplications(List.of("admin"), List.of(appBilling)))
                .as("no such short name in billing").isEmpty();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static String app(String code) {
        String id = EntityType.APPLICATION.generate();
        DB.insertInto(APP_APPLICATIONS)
                .set(APP_APPLICATIONS.ID, id).set(APP_APPLICATIONS.TYPE, "APPLICATION")
                .set(APP_APPLICATIONS.CODE, code).set(APP_APPLICATIONS.NAME, code)
                .set(APP_APPLICATIONS.ACTIVE, true)
                .set(APP_APPLICATIONS.CREATED_AT, NOW).set(APP_APPLICATIONS.UPDATED_AT, NOW)
                .execute();
        return id;
    }

    private static String client(String identifier) {
        String id = EntityType.CLIENT.generate();
        DB.insertInto(TNT_CLIENTS)
                .set(TNT_CLIENTS.ID, id).set(TNT_CLIENTS.NAME, identifier).set(TNT_CLIENTS.IDENTIFIER, identifier)
                .set(TNT_CLIENTS.STATUS, "ACTIVE")
                .set(TNT_CLIENTS.CREATED_AT, NOW).set(TNT_CLIENTS.UPDATED_AT, NOW)
                .execute();
        return id;
    }

    private static String role(String applicationId, String applicationCode, String name, List<String> permissions) {
        String id = EntityType.ROLE.generate();
        DB.insertInto(IAM_ROLES)
                .set(IAM_ROLES.ID, id).set(IAM_ROLES.APPLICATION_ID, applicationId)
                .set(IAM_ROLES.APPLICATION_CODE, applicationCode)
                .set(IAM_ROLES.NAME, name).set(IAM_ROLES.DISPLAY_NAME, name)
                .set(IAM_ROLES.SOURCE, "DATABASE").set(IAM_ROLES.CLIENT_MANAGED, false)
                .set(IAM_ROLES.CREATED_AT, NOW).set(IAM_ROLES.UPDATED_AT, NOW)
                .execute();
        for (String p : permissions) {
            DB.insertInto(IAM_ROLE_PERMISSIONS).set(IAM_ROLE_PERMISSIONS.ROLE_ID, id).set(IAM_ROLE_PERMISSIONS.PERMISSION, p).execute();
        }
        return id;
    }

    private static String roleName(String roleId) {
        return DB.select(IAM_ROLES.NAME).from(IAM_ROLES).where(IAM_ROLES.ID.eq(roleId)).fetchOne(IAM_ROLES.NAME);
    }

    private static String principal(boolean active, String scope, String clientId) {
        String id = EntityType.PRINCIPAL.generate();
        String local = active ? "p-" + RUN : "q-" + RUN; // emails are unique
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, scope)
                .set(IAM_PRINCIPALS.CLIENT_ID, clientId)
                .set(IAM_PRINCIPALS.NAME, "P " + RUN).set(IAM_PRINCIPALS.ACTIVE, active)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, false) // the column defaults to true
                .set(IAM_PRINCIPALS.EMAIL, local + "@example.com").set(IAM_PRINCIPALS.EMAIL_DOMAIN, "example.com")
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW)
                .execute();
        return id;
    }

    private static void assignRole(String principalId, String roleId) {
        String name = roleId.contains(":") ? roleId : roleName(roleId);
        DB.insertInto(IAM_PRINCIPAL_ROLES)
                .set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, principalId).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, name)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "ADMIN_ASSIGNED").set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, NOW)
                .execute();
    }

    private static void grantClient(String principalId, String clientId) {
        DB.insertInto(IAM_CLIENT_ACCESS_GRANTS)
                .set(IAM_CLIENT_ACCESS_GRANTS.ID, EntityType.CLIENT_ACCESS_GRANT.generate())
                .set(IAM_CLIENT_ACCESS_GRANTS.PRINCIPAL_ID, principalId).set(IAM_CLIENT_ACCESS_GRANTS.CLIENT_ID, clientId)
                .set(IAM_CLIENT_ACCESS_GRANTS.GRANTED_BY, principalId).set(IAM_CLIENT_ACCESS_GRANTS.GRANTED_AT, NOW)
                .set(IAM_CLIENT_ACCESS_GRANTS.CREATED_AT, NOW).set(IAM_CLIENT_ACCESS_GRANTS.UPDATED_AT, NOW)
                .execute();
    }

    private static void grantApplication(String principalId, String applicationId) {
        DB.insertInto(IAM_PRINCIPAL_APPLICATION_ACCESS)
                .set(IAM_PRINCIPAL_APPLICATION_ACCESS.PRINCIPAL_ID, principalId)
                .set(IAM_PRINCIPAL_APPLICATION_ACCESS.APPLICATION_ID, applicationId)
                .set(IAM_PRINCIPAL_APPLICATION_ACCESS.GRANTED_AT, NOW)
                .execute();
    }
}
