package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// `DevBootstrap.bootstrapRouterCredentials` (`docs/spec/router-config-auth.md`
/// §3), against a real, migrated embedded Postgres — the same direct jOOQ
/// upserts `fcdev start` runs before the server boots. Skipped when the
/// bundled PostgreSQL cannot start here, same as [StartIntegrationTest].
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevBootstrapRouterCredentialsTest {

    private Path root;
    private EmbeddedPg pg;
    private GatedDataSource pool;

    @BeforeAll
    void boot() throws Exception {
        root = Files.createTempDirectory("fcdev-router-credentials-it");
        try {
            pg = EmbeddedPg.start(root.resolve("embedded-pg"), 0, root.resolve("cache"));
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(DevBootstrapRouterCredentialsTest.class)
                    .warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
        }
        pool = Database.newPool(pg.url());
        DevBootstrap.migrate(pool);
    }

    @AfterAll
    void shutdown() {
        if (pool != null) pool.close();
        if (pg != null) pg.close();
        if (root != null) {
            try {
                EmbeddedPg.deleteTree(root);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    /// Two boots: one client row, one principal, the `platform:router` role
    /// assigned exactly once — and, the assertion that actually proves the
    /// second boot did something rather than merely not-duplicating anything,
    /// the stored `client_secret_ref` changes between the two (a mutant that
    /// skipped the `ON CONFLICT ... DO UPDATE` and fell through to a no-op
    /// would leave the first boot's ref in place and this would fail).
    @Test
    void secondBootRotatesTheSecretWithoutDuplicatingTheClientOrPrincipal() throws Exception {
        String appKey = Encryption.generateKey();

        var first = DevEnv.of(Map.of(DevBootstrap.ENV_APP_KEY, appKey)).mutable();
        DevBootstrap.bootstrapRouterCredentials(pool, first, 18080);
        String firstPlaintext = first.get("FC_ROUTER_CLIENT_SECRET");
        assertThat(first.get("FC_ROUTER_CLIENT_ID")).isEqualTo(DevBootstrap.ROUTER_CLIENT_ID);
        assertThat(first.get("FC_ROUTER_PLATFORM_URL")).isEqualTo("http://localhost:18080");
        assertThat(firstPlaintext).isNotBlank();
        String firstSecretRef = storedSecretRef();

        // A fresh Mutable, the way a real second `fcdev start` builds one —
        // `setDefault` on an already-populated var is a no-op, so reusing
        // `first` here would just observe its own first-boot value again and
        // never actually exercise the DB-side rotation this test pins.
        var second = DevEnv.of(Map.of(DevBootstrap.ENV_APP_KEY, appKey)).mutable();
        DevBootstrap.bootstrapRouterCredentials(pool, second, 18080);
        String secondPlaintext = second.get("FC_ROUTER_CLIENT_SECRET");
        String secondSecretRef = storedSecretRef();

        assertThat(secondPlaintext).as("a fresh plaintext every boot").isNotBlank().isNotEqualTo(firstPlaintext);
        assertThat(secondSecretRef).as("the stored ref actually changed, not just the in-memory plaintext")
                .isNotEqualTo(firstSecretRef);

        assertThat(clientRowCount()).as("exactly one client row across two boots").isEqualTo(1);
        var roleRows = principalRoleRows();
        assertThat(roleRows).as("exactly one principal holding platform:router once").hasSize(1);
        assertThat(roleRows.getFirst().type()).isEqualTo("SERVICE");
        assertThat(roleRows.getFirst().scope()).isEqualTo("ANCHOR");
        assertThat(roleRows.getFirst().roleName()).isEqualTo("platform:router");
    }

    /// An operator who set `FC_ROUTER_CLIENT_ID` brought their own client:
    /// the bootstrap must not touch `fcdev-router` (its stored ref stays as
    /// it was) and must not disturb the operator's pair. Mutant: drop the
    /// guard — the ref rotates and this fails on the first assertion.
    @Test
    void anOperatorSuppliedClientIsLeftAlone() throws Exception {
        String appKey = Encryption.generateKey();
        var boot = DevEnv.of(Map.of(DevBootstrap.ENV_APP_KEY, appKey)).mutable();
        DevBootstrap.bootstrapRouterCredentials(pool, boot, 18080);
        String refBefore = storedSecretRef();

        var operator = DevEnv.of(Map.of(DevBootstrap.ENV_APP_KEY, appKey,
                "FC_ROUTER_CLIENT_ID", "my-router", "FC_ROUTER_CLIENT_SECRET", "my-secret",
                "FC_ROUTER_PLATFORM_URL", "https://platform.example")).mutable();
        DevBootstrap.bootstrapRouterCredentials(pool, operator, 18080);

        assertThat(storedSecretRef()).as("fcdev-router's secret was not rotated").isEqualTo(refBefore);
        assertThat(operator.get("FC_ROUTER_CLIENT_ID")).isEqualTo("my-router");
        assertThat(operator.get("FC_ROUTER_CLIENT_SECRET")).isEqualTo("my-secret");
        assertThat(operator.get("FC_ROUTER_PLATFORM_URL")).isEqualTo("https://platform.example");
    }

    /// `--api-port 0`: no platform address to mint against, so no credentials
    /// and no platform URL are put on the env (credentials without a platform
    /// URL would be refused at router start). Mutant: drop the port guard —
    /// the env gains a client id and this fails.
    @Test
    void anEphemeralApiPortBootstrapsNoCredentials() {
        String appKey = Encryption.generateKey();
        var env = DevEnv.of(Map.of(DevBootstrap.ENV_APP_KEY, appKey)).mutable();
        DevBootstrap.bootstrapRouterCredentials(pool, env, 0);
        assertThat(env.get("FC_ROUTER_CLIENT_ID")).isEmpty();
        assertThat(env.get("FC_ROUTER_CLIENT_SECRET")).isEmpty();
        assertThat(env.get("FC_ROUTER_PLATFORM_URL")).isEmpty();
    }

    private record PrincipalRole(String type, String scope, String roleName) {
    }

    private String storedSecretRef() throws Exception {
        try (Connection c = connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT client_secret_ref FROM oauth_clients WHERE client_id = 'fcdev-router'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private int clientRowCount() throws Exception {
        try (Connection c = connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM oauth_clients WHERE client_id = 'fcdev-router'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private java.util.List<PrincipalRole> principalRoleRows() throws Exception {
        var rows = new java.util.ArrayList<PrincipalRole>();
        try (Connection c = connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT p.type, p.scope, r.role_name FROM iam_principals p "
                             + "JOIN oauth_clients oc ON oc.service_account_principal_id = p.id "
                             + "JOIN iam_principal_roles r ON r.principal_id = p.id "
                             + "WHERE oc.client_id = 'fcdev-router'")) {
            while (rs.next()) {
                rows.add(new PrincipalRole(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return rows;
    }

    private Connection connection() throws Exception {
        var jdbc = Database.toJdbc(pg.url());
        return DriverManager.getConnection(jdbc.url(), jdbc.user(), jdbc.password());
    }
}
