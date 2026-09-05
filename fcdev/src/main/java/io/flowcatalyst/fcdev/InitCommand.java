package io.flowcatalyst.fcdev;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountCode;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.server.EnvReader;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;

/// `fcdev init` (Go `init.go`): bootstrap a fresh local environment against
/// an already-running database (spec `docs/spec/fcdev-commands.md` §1).
/// Unlike `fcdev start`, `init` never starts the embedded Postgres itself —
/// it connects to `--database-url` (default: the port a running
/// `fcdev start` exposes).
///
/// Steps, in order: migrate + seed; create the anchor admin unless one
/// exists; resolve-or-create the default client; create the application
/// (fails if the code is taken); mint the service account + its `SERVICE`
/// principal; merge `.env`. Minting an OAuth `client_credentials` client for
/// the service account is deferred until the auth aggregate lands (Phase 3,
/// `docs/auth-rulings.md`) — step 5 prints a notice instead and `.env` never
/// gets `FLOWCATALYST_CLIENT_ID` / `_SECRET`.
///
/// All writes go through the repositories directly on a bootstrap-wrapped
/// transaction ([DbTx#wrapForBootstrap]) — no [io.flowcatalyst.sdk.usecase.UnitOfWork],
/// no domain events, no audit rows (`CONVENTIONS.md` §3's documented
/// exception for infrastructure bootstrap).
@Command(name = "init", description = "Bootstrap a fresh local environment (admin user + default tenant + .env)",
        mixinStandardHelpOptions = true, sortOptions = false)
public final class InitCommand implements Callable<Integer> {

    /// The embedded Postgres a running `fcdev start` exposes, unqualified by
    /// `sslmode` the way Go's own default reads (Go appends `?sslmode=disable`
    /// itself; `Database.newPool`'s JDBC translation doesn't need it).
    static final String DEFAULT_DATABASE_URL = "postgresql://postgres:postgres@localhost:15432/flowcatalyst";
    static final String DEFAULT_API_BASE_URL = "http://localhost:8080";

    @Spec
    CommandSpec spec;

    @Option(names = "--database-url", paramLabel = "<url>", description = "Postgres URL (defaults to local embedded) (FC_DATABASE_URL)")
    String databaseUrl;
    @Option(names = "--yes", description = "non-interactive — fail if any required value is missing from flags")
    boolean yes;
    @Option(names = "--root", paramLabel = "<dir>", description = "project root for the .env write (default: .)")
    String root = ".";
    @Option(names = "--admin-email", paramLabel = "<email>", description = "anchor admin email (FC_BOOTSTRAP_ADMIN_EMAIL)")
    String adminEmail;
    @Option(names = "--admin-password", paramLabel = "<password>", description = "anchor admin password (FC_BOOTSTRAP_ADMIN_PASSWORD)")
    String adminPassword;
    @Option(names = "--code", paramLabel = "<code>", description = "application code (URL-safe slug, e.g. \"orders\")")
    String code;
    @Option(names = "--name", paramLabel = "<name>", description = "application name")
    String name;
    @Option(names = "--app-type", paramLabel = "<type>", description = "application type: APPLICATION or INTEGRATION (default: APPLICATION)")
    String appType = "APPLICATION";
    @Option(names = "--description", paramLabel = "<text>", description = "application description (optional)")
    String description;
    @Option(names = "--default-base-url", paramLabel = "<url>", description = "application's deployed base URL (optional)")
    String defaultBaseUrl;
    @Option(names = "--client-identifier", paramLabel = "<id>", description = "default client identifier (default: default)")
    String clientIdentifier = "default";
    @Option(names = "--client-name", paramLabel = "<name>", description = "default client display name (default: Default Client)")
    String clientName = "Default Client";
    @Option(names = "--api-base-url", paramLabel = "<url>", description = "API base URL written to FLOWCATALYST_BASE_URL (default: http://localhost:8080)")
    String apiBaseUrl = DEFAULT_API_BASE_URL;

    private final DevEnv env;

    /// Where interactive prompts read from; picocli has no stdin
    /// abstraction (the pattern `DbCommand.Upgrade` established) — tests
    /// inject a script here.
    private final InputStream in;

    public InitCommand() {
        this(DevEnv.system(), System.in);
    }

    public InitCommand(DevEnv env) {
        this(env, System.in);
    }

    public InitCommand(DevEnv env, InputStream in) {
        this.env = env;
        this.in = in;
        this.databaseUrl = env.str("FC_DATABASE_URL", "");
        this.adminEmail = env.str("FC_BOOTSTRAP_ADMIN_EMAIL", "");
        this.adminPassword = env.str("FC_BOOTSTRAP_ADMIN_PASSWORD", "");
    }

    @Override
    public Integer call() throws IOException, SQLException {
        PrintWriter out = spec.commandLine().getOut();
        String url = databaseUrl.isEmpty() ? DEFAULT_DATABASE_URL : databaseUrl;
        var stdin = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

        try (HikariDataSource pool = Database.newPool(url, 4)) {
            // ── 1. Migrate + seed (idempotent) ──────────────────────────
            DevBootstrap.migrate(pool);
            new Seeder(pool).run();

            out.println("fcdev init");

            // ── 2. Anchor admin ──────────────────────────────────────────
            DSLContext db = DSL.using(pool, SQLDialect.POSTGRES);
            boolean anchorExists = db.fetchExists(IAM_PRINCIPALS,
                    IAM_PRINCIPALS.TYPE.eq("USER").and(IAM_PRINCIPALS.SCOPE.eq("ANCHOR")));
            if (anchorExists) {
                out.println("→ admin user already present, skipping creation");
            } else {
                String email = prompt(out, stdin, adminEmail, "Admin email", "", yes);
                String password = prompt(out, stdin, adminPassword, "Admin password", "", yes);
                // Reuse the Seeder's bootstrap-admin transaction (idempotent,
                // internal IDP + anchor email-domain mapping + hashed
                // password + platform:super-admin) rather than a second one.
                new Seeder(pool, new EnvReader(Map.of(
                        Seeder.ENV_BOOTSTRAP_EMAIL, email,
                        Seeder.ENV_BOOTSTRAP_PASSWORD, password))).run();
                out.printf("  → admin %s created%n", email);
            }

            // ── 3. Default client ────────────────────────────────────────
            var clientRepo = new ClientRepository(pool);
            ClientIdentifier identifier = ClientIdentifier.parse(clientIdentifier);
            String defaultClientId;
            Optional<Client> existingClient = clientRepo.findByIdentifier(identifier.value());
            if (existingClient.isPresent()) {
                Client c = existingClient.get();
                defaultClientId = c.id();
                out.printf("→ reusing default client \"%s\" (id=%s)%n", c.identifier(), c.id());
            } else {
                Client c = Client.create(clientName, identifier);
                infraPersist(pool, tx -> clientRepo.persist(c, tx));
                defaultClientId = c.id();
                out.printf("  → default client \"%s\" created (id=%s)%n", c.identifier(), c.id());
            }

            // ── 4. Application ───────────────────────────────────────────
            String appCodeRaw = prompt(out, stdin, code, "Application code (slug)", "", yes);
            String appName = prompt(out, stdin, name, "Application name", "", yes);
            String rawType = prompt(out, stdin, appType, "Application type [APPLICATION|INTEGRATION]", "APPLICATION", yes);
            String descriptionValue = promptOptional(out, stdin, description, "Description (optional)", yes);
            String appBaseUrlValue = promptOptional(out, stdin, defaultBaseUrl, "Application's deployed base URL (optional)", yes);

            var appRepo = new ApplicationRepository(pool);
            Optional<Application> existingApp = appRepo.findByCode(normaliseCode(appCodeRaw));
            if (existingApp.isPresent()) {
                Application existing = existingApp.get();
                throw new IllegalStateException("application with code \"" + appCodeRaw
                        + "\" already exists (id=" + existing.id() + "). Pick a different code or run fcdev fresh");
            }
            ApplicationType type = parseApplicationTypeStrict(rawType);
            Application appDraft = Application.create(type, appCodeRaw, appName);
            if (!descriptionValue.isEmpty()) appDraft = appDraft.withDescription(descriptionValue);
            if (!appBaseUrlValue.isEmpty()) appDraft = appDraft.withDefaultBaseUrl(appBaseUrlValue);
            Application app = appDraft;
            infraPersist(pool, tx -> appRepo.persist(app, tx));
            out.printf("  → application \"%s\" created (id=%s)%n", app.code(), app.id());

            // ── 5. Service account + SERVICE principal ───────────────────
            String saCode = "app:" + app.code();
            String saName = app.name() + " Service Account";
            // Not ServiceAccountCode.parse(saCode): that pattern rejects ':'
            // (it validates user-chosen codes), but "app:<code>" is the
            // system-generated convention for an application's own service
            // account — the same literal shape ApplicationOperationsTest's
            // AttachServiceAccount fixture uses, unvalidated there too.
            ServiceAccount sa = ServiceAccount.create(new ServiceAccountCode(saCode), saName)
                    .withDescription("Service account for application: " + app.name())
                    .withApplicationId(app.id());
            Principal principal = Principal.newService(sa.id(), saName)
                    .withApplicationId(app.id())
                    .withClientId(defaultClientId);
            // The FK (app_applications.service_account_id) points at
            // iam_principals, not iam_service_accounts — store the
            // *principal* id (spec §1 step 5).
            Application appWithSa = app.attachServiceAccount(principal.id());

            var saRepo = new ServiceAccountRepository(pool, Optional.<Encryption>empty());
            var principalRepo = new PrincipalRepository(pool);
            Application finalApp = appWithSa;
            infraPersist(pool, tx -> {
                principalRepo.persist(principal, tx);
                saRepo.persist(sa, tx);
                appRepo.persist(finalApp, tx);
            });
            out.printf("  → service account \"%s\" attached%n", saCode);
            out.println("  → OAuth client: deferred until the auth aggregate lands (docs/auth-rulings.md); FLOWCATALYST_CLIENT_ID/SECRET not written");

            // ── 6. .env ───────────────────────────────────────────────────
            String appKey = this.env.get("FLOWCATALYST_APP_KEY");
            if (appKey.isEmpty()) {
                appKey = Encryption.generateKey();
            }
            Path envPath = Path.of(root, ".env");
            var updates = List.of(
                    new EnvFileWriter.Update("FLOWCATALYST_BASE_URL", apiBaseUrl),
                    new EnvFileWriter.Update("FLOWCATALYST_APP_CODE", app.code()),
                    new EnvFileWriter.Update("FLOWCATALYST_APP_KEY", appKey));
            var outcome = EnvFileWriter.write(envPath, updates);
            switch (outcome) {
                case UNCHANGED -> out.printf("  → %s already current, no update needed%n", envPath);
                case CREATED -> out.printf("  → %s created%n", envPath);
                case UPDATED -> out.printf("  → %s updated%n", envPath);
            }

            out.println();
            out.println("✓ Application scaffolded.");
            out.printf("  Application:     %s (code=%s)%n", app.name(), app.code());
            out.printf("  Service account: %s%n", sa.id());
            out.printf("  Default client:  %s%n", defaultClientId);
            out.println();
            out.printf("  Credentials written to %s.%n", envPath);
            out.flush();
        }
        return 0;
    }

    /// [io.flowcatalyst.platform.application.ApplicationCode#parse] without the
    /// `CODE_REQUIRED` / `INVALID_CODE_FORMAT` error — used only to normalise a
    /// code for the existence lookup; a genuinely malformed code still fails,
    /// loudly, once [Application#create] parses it for real.
    private static String normaliseCode(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    /// Strict reader for `--app-type` (spec §1 step 4): unlike
    /// [ApplicationType#parse] (the lenient stored/wire reader), an
    /// unrecognised value here is a CLI input error, not "default to
    /// APPLICATION".
    private static ApplicationType parseApplicationTypeStrict(String raw) {
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "APPLICATION" -> ApplicationType.APPLICATION;
            case "INTEGRATION" -> ApplicationType.INTEGRATION;
            default -> throw new IllegalStateException(
                    "invalid application type \"" + raw + "\": must be APPLICATION or INTEGRATION");
        };
    }

    // ── Prompts (spec §1, "Prompts" paragraph) ──────────────────────────────

    /// A flag value wins (trimmed); in `--yes` mode a missing flag value
    /// falls back to `def`, or fails when `def` is blank too; interactively,
    /// an empty line takes `def` or fails. `def` is shown as a `[default]`
    /// suffix when non-blank.
    private static String prompt(PrintWriter out, BufferedReader stdin, String flagValue, String question, String def, boolean yes)
            throws IOException {
        String v = flagValue == null ? "" : flagValue.strip();
        if (!v.isEmpty()) return v;
        if (yes) {
            if (def.strip().isEmpty()) {
                throw new IllegalStateException("--yes mode requires a flag value for: " + question);
            }
            return def;
        }
        String defTrimmed = def.strip();
        out.print(question + (defTrimmed.isEmpty() ? "" : " [" + defTrimmed + "]") + ": ");
        out.flush();
        String line = stdin.readLine();
        if (line == null) {
            throw new IllegalStateException(question + " is required");
        }
        if (line.isEmpty()) {
            if (!def.isEmpty()) return def;
            throw new IllegalStateException(question + " is required");
        }
        return line;
    }

    /// Like [#prompt] but never errors: `--yes` with no flag value, or an
    /// empty/absent line, both yield `""` — the field the platform accepts
    /// as absent.
    private static String promptOptional(PrintWriter out, BufferedReader stdin, String flagValue, String question, boolean yes)
            throws IOException {
        String v = flagValue == null ? "" : flagValue.strip();
        if (!v.isEmpty()) return v;
        if (yes) return "";
        out.print(question + ": ");
        out.flush();
        String line = stdin.readLine();
        return line == null ? "" : line.strip();
    }

    // ── infraPersist: a single-tx bootstrap write (CONVENTIONS.md §3) ──────

    @FunctionalInterface
    private interface TxBody {
        void run(DbTx tx) throws SQLException;
    }

    private static void infraPersist(HikariDataSource pool, TxBody body) throws SQLException {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                body.run(DbTx.wrapForBootstrap(conn));
                conn.commit();
            } catch (RuntimeException | SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}
