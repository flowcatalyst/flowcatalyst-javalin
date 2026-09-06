package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.database.Database;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `fcdev init` (spec `docs/spec/fcdev-commands.md` §1) against an embedded
/// PostgreSQL. Skipped when the bundled PostgreSQL cannot start here.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InitCommandTest {

    private Path scratch;
    private EmbeddedPg pg;
    private String url;

    @BeforeAll
    void boot() {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        try {
            scratch = Files.createTempDirectory("fcdev-init-it");
            Path dataPath = scratch.resolve("embedded-pg");
            Path cache = scratch.resolve("cache");
            pg = EmbeddedPg.start(dataPath, 0, new DevPaths(scratch, cache).embeddedPgCacheDir());
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(InitCommandTest.class).warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
        }
        url = pg.url();
    }

    @AfterAll
    void shutdown() throws Exception {
        if (pg != null) pg.close();
        if (scratch != null) EmbeddedPg.deleteTree(scratch);
    }

    /// Builds a fresh `InitCommand`, wired to `url`, with its picocli
    /// `@Spec` populated (so `spec.commandLine().getOut()` works) and its
    /// output captured — without going through full arg parsing, since the
    /// fields are package-private and this test lives in the same package.
    private InitCommand newCommand(StringWriter out) {
        InitCommand cmd = new InitCommand(DevEnv.of(Map.of()));
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out, true));
        cmd.databaseUrl = url;
        return cmd;
    }

    @Order(1)
    @Test
    void bootstrapsAdminClientApplicationServiceAccountPrincipalAndEnv(@TempDir Path root) throws Exception {
        var out = new StringWriter();
        InitCommand cmd = newCommand(out);
        cmd.root = root.toString();
        cmd.adminEmail = "owner@example.com";
        cmd.adminPassword = "Sup3rSecret!";
        cmd.code = "orders";
        cmd.name = "Orders";
        cmd.appType = "APPLICATION";
        cmd.yes = true;

        Integer exit = cmd.call();
        assertThat(exit).isZero();
        assertThat(out.toString()).contains("OAuth client: deferred until the auth aggregate lands (docs/auth-rulings.md)");

        try (GatedDataSource pool = Database.newPool(url, 2)) {
            var principalRepo = new PrincipalRepository(pool);
            Principal admin = principalRepo.findByEmail("owner@example.com").orElseThrow();
            assertThat(admin.scope()).isEqualTo(UserScope.ANCHOR);
            // Pins the super-admin grant, not just "a row was inserted somewhere".
            assertThat(admin.hasRole("platform:super-admin")).isTrue();

            var clientRepo = new ClientRepository(pool);
            var defaultClient = clientRepo.findByIdentifier("default").orElseThrow();

            var appRepo = new ApplicationRepository(pool);
            Application app = appRepo.findByCode("orders").orElseThrow();
            assertThat(app.type()).isEqualTo(ApplicationType.APPLICATION);
            assertThat(app.hasServiceAccount()).isTrue();

            var saRepo = new ServiceAccountRepository(pool, Optional.empty());
            ServiceAccount sa = saRepo.findByCode("app:orders").orElseThrow();
            Principal svcPrincipal = principalRepo.findByServiceAccount(sa.id()).orElseThrow();

            // The load-bearing assertion (mutant table): the FK stores the
            // SERVICE PRINCIPAL id, not the service-account row id. Swapping
            // them still leaves "a service account was attached" true, so
            // this must compare against both ids to fail on that mutant.
            assertThat(app.serviceAccountId()).isEqualTo(svcPrincipal.id());
            assertThat(app.serviceAccountId()).isNotEqualTo(sa.id());

            assertThat(svcPrincipal.applicationId()).isEqualTo(app.id());
            assertThat(svcPrincipal.clientId()).isEqualTo(defaultClient.id());
            assertThat(svcPrincipal.scope()).isEqualTo(UserScope.ANCHOR);
        }

        Path envPath = root.resolve(".env");
        String content = Files.readString(envPath);
        List<String> keys = content.lines()
                .filter(l -> l.contains("="))
                .map(l -> l.substring(0, l.indexOf('=')))
                .toList();
        assertThat(keys).containsExactlyInAnyOrder("FLOWCATALYST_BASE_URL", "FLOWCATALYST_APP_CODE", "FLOWCATALYST_APP_KEY");
        assertThat(content).doesNotContain("CLIENT_ID").doesNotContain("CLIENT_SECRET");
        if (envPath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(envPath))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    @Order(2)
    @Test
    void secondRunWithTheSameCodeFailsAndLeavesEnvUntouched(@TempDir Path root) throws Exception {
        var out1 = new StringWriter();
        InitCommand first = newCommand(out1);
        first.root = root.toString();
        first.adminEmail = "owner2@example.com";
        first.adminPassword = "Sup3rSecret!";
        first.code = "billing";
        first.name = "Billing";
        first.appType = "APPLICATION";
        first.yes = true;
        assertThat(first.call()).isZero();

        Path envPath = root.resolve(".env");
        String beforeContent = Files.readString(envPath);

        var out2 = new StringWriter();
        InitCommand second = newCommand(out2);
        second.root = root.toString();
        second.code = "billing";
        second.name = "Billing Two";
        second.appType = "APPLICATION";
        second.yes = true;

        assertThatThrownBy(second::call)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("application with code \"billing\" already exists (id=")
                .hasMessageEndingWith("Pick a different code or run fcdev fresh");

        // Admin already present + default client reused, both printed before the failure.
        assertThat(out2.toString()).contains("→ admin user already present, skipping creation");
        assertThat(out2.toString()).contains("reusing default client \"default\"");

        // .env is untouched by the failed second run.
        assertThat(Files.readString(envPath)).isEqualTo(beforeContent);
    }

    @Order(3)
    @Test
    void yesModeWithoutCodeFailsWithTheExactError(@TempDir Path root) throws Exception {
        var out = new StringWriter();
        InitCommand cmd = newCommand(out);
        cmd.root = root.toString();
        cmd.adminEmail = "owner3@example.com";
        cmd.adminPassword = "Sup3rSecret!";
        cmd.yes = true;
        // cmd.code deliberately left unset.

        assertThatThrownBy(cmd::call)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("--yes mode requires a flag value for: Application code (slug)");

        // Nothing application-shaped should have been written.
        assertThat(Files.exists(root.resolve(".env"))).isFalse();
    }

    /// End-to-end: the real `FcDev` command tree (its execution-exception
    /// handler, not a direct `call()`) turns the "already exists" failure
    /// into exit code 1 — the contract `docs/fcdev.md` §4 documents for
    /// every runtime failure.
    @Order(4)
    @Test
    void endToEndFailureExitsOne(@TempDir Path root) {
        var args1 = new String[]{"init", "--database-url", url, "--root", root.toString(),
                "--admin-email", "owner4@example.com", "--admin-password", "Sup3rSecret!",
                "--code", "reporting", "--name", "Reporting", "--app-type", "APPLICATION", "--yes"};
        assertThat(FcDev.commandLine(DevEnv.of(Map.of())).execute(args1)).isZero();

        var args2 = new String[]{"init", "--database-url", url, "--root", root.toString(),
                "--code", "reporting", "--name", "Reporting Two", "--app-type", "APPLICATION", "--yes"};
        assertThat(FcDev.commandLine(DevEnv.of(Map.of())).execute(args2)).isEqualTo(1);
    }
}
