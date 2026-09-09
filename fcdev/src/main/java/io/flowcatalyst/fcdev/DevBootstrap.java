package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.database.Migrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/// The first-run sequence `start`, `fresh` and `db upgrade` share: migrate,
/// seed the dev defaults into the environment, persist the JWT signing key
/// and the field-encryption key, run the seeder. fc-server makes operators
/// supply every one of these explicitly; fcdev wants a usable login on the
/// first `fcdev start`.
public final class DevBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(DevBootstrap.class);

    /// Go `seed.EnvBootstrapEmail` / `…Password` / `…Name` (the names the
    /// [Seeder] reads: `FLOWCATALYST_BOOTSTRAP_ADMIN_*`) and the dev defaults.
    public static final String ENV_BOOTSTRAP_EMAIL = Seeder.ENV_BOOTSTRAP_EMAIL;
    public static final String ENV_BOOTSTRAP_PASSWORD = Seeder.ENV_BOOTSTRAP_PASSWORD;
    public static final String ENV_BOOTSTRAP_NAME = Seeder.ENV_BOOTSTRAP_NAME;
    public static final String DEV_ADMIN_EMAIL = "admin@flowcatalyst.local";
    public static final String DEV_ADMIN_PASSWORD = "DevPassword123!";
    public static final String DEV_ADMIN_NAME = "Local Admin";

    public static final String ENV_JWT_SIGNING_KEY_PATH = "FC_JWT_SIGNING_KEY_PATH";
    public static final String ENV_APP_KEY = "FLOWCATALYST_APP_KEY";
    public static final String JWT_KEY_FILE = "jwt-signing-key.pem";
    public static final String APP_KEY_FILE = "app-key";

    private DevBootstrap() {
    }

    /// `migrate.Run`.
    public static void migrate(DataSource pool) {
        var result = Migrator.migrate(pool);
        LOG.atInfo().setMessage("migrations applied")
                .addKeyValue("count", result.migrationsExecuted)
                .addKeyValue("schema_version", result.targetSchemaVersion)
                .log();
    }

    /// The three `setEnvDefault` calls: a usable bootstrap admin unless the
    /// operator set one.
    public static void seedAdminDefaults(DevEnv.Mutable env) {
        env.setDefault(ENV_BOOTSTRAP_EMAIL, DEV_ADMIN_EMAIL);
        env.setDefault(ENV_BOOTSTRAP_PASSWORD, DEV_ADMIN_PASSWORD);
        env.setDefault(ENV_BOOTSTRAP_NAME, DEV_ADMIN_NAME);
    }

    /// Persist a JWT signing key next to the embedded data dir (so tokens
    /// survive a restart) and point `FC_JWT_SIGNING_KEY_PATH` at it — unless
    /// the operator already set the variable. Non-fatal: falls back to the
    /// server's ephemeral key with a warning.
    public static void ensureSigningKey(DevEnv.Mutable env, Path embeddedDbPath) {
        if (!env.get(ENV_JWT_SIGNING_KEY_PATH).isEmpty()) return;
        var keyPath = stateDir(embeddedDbPath).resolve(JWT_KEY_FILE);
        try {
            var resolved = SigningKeys.ensureSigningKeyFile(keyPath);
            env.set(ENV_JWT_SIGNING_KEY_PATH, resolved.toString());
        } catch (IOException | RuntimeException e) {
            LOG.atWarn().setMessage("unable to persist JWT signing key — falling back to ephemeral")
                    .setCause(e)
                    .log();
        }
    }

    /// Persist the field-encryption key (`FLOWCATALYST_APP_KEY`) so OAuth
    /// client secrets stay decryptable across restarts — unless the operator
    /// set it. Non-fatal.
    public static void ensureAppKey(DevEnv.Mutable env, Path embeddedDbPath) {
        if (!env.get(ENV_APP_KEY).isEmpty()) return;
        var keyPath = stateDir(embeddedDbPath).resolve(APP_KEY_FILE);
        try {
            env.set(ENV_APP_KEY, ensureAppKeyFile(keyPath));
        } catch (IOException e) {
            LOG.atWarn().setMessage("unable to persist app encryption key — OAuth client secrets won't survive restart")
                    .setCause(e)
                    .log();
        }
    }

    /// `ensureAppKeyFile`: read, or generate and write (0600), a 32-byte
    /// base64 (standard alphabet, padded — Go `encryption.GenerateKey`) key.
    public static String ensureAppKeyFile(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            var existing = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (!existing.isEmpty()) return existing;
        }
        var raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        var key = Base64.getEncoder().encodeToString(raw);
        OwnerOnlyFile.write(path, key);
        return key;
    }

    /// `seed.NewSeeder(pool).Run`: permissions, roles, platform config, the
    /// bootstrap admin.
    public static void seed(DataSource pool, DevEnv env) {
        new Seeder(pool, new EnvReader(env.vars())).run();
        LOG.atInfo().setMessage("seed complete")
                .addKeyValue("bootstrap_admin", env.get(ENV_BOOTSTRAP_EMAIL))
                .log();
    }

    /// `bootstrapMCPCredentials`: provision the local MCP OAuth client and
    /// write `<userCacheDir>/flowcatalyst-dev/mcp-credentials.json`. Non-fatal.
    public static void bootstrapMcpCredentials(DataSource pool, String baseUrl, DevPaths paths) {
        // TODO(port): needs the auth / principal / service-account repositories and the
        //   encryption helper; writes {client_id, client_secret, base_url} (0600) to
        //   paths.mcpCredentialsPath(). Until then `fcdev mcp` has nothing to read.
        LOG.atInfo().setMessage("MCP credential bootstrap not yet ported; skipping (would write)")
                .addKeyValue("path", paths.mcpCredentialsPath())
                .addKeyValue("platform_url", baseUrl)
                .log();
    }

    /// The directory the persistent key files live in: the parent of the
    /// embedded data dir (`<userDataDir>/flowcatalyst` by default).
    static Path stateDir(Path embeddedDbPath) {
        var parent = embeddedDbPath.toAbsolutePath().getParent();
        return parent != null ? parent : embeddedDbPath.toAbsolutePath();
    }
}
