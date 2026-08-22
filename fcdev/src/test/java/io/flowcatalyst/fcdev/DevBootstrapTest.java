package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevBootstrapTest {

    @TempDir
    Path dir;

    @Test
    void adminDefaultsNeverTrampleExplicitValues() {
        var env = DevEnv.of(Map.of(DevBootstrap.ENV_BOOTSTRAP_EMAIL, "ops@example.com", DevBootstrap.ENV_BOOTSTRAP_NAME, "")).mutable();
        DevBootstrap.seedAdminDefaults(env);
        assertThat(env.get(DevBootstrap.ENV_BOOTSTRAP_EMAIL)).isEqualTo("ops@example.com");
        assertThat(env.get(DevBootstrap.ENV_BOOTSTRAP_PASSWORD)).isEqualTo("DevPassword123!");
        assertThat(env.get(DevBootstrap.ENV_BOOTSTRAP_NAME)).isEqualTo("Local Admin"); // empty counts as unset, as in Go
    }

    @Test
    void appKeyIsA32ByteBase64KeyPersistedOnce() throws Exception {
        var path = dir.resolve("state/app-key");
        var key = DevBootstrap.ensureAppKeyFile(path);
        assertThat(Base64.getDecoder().decode(key)).hasSize(32);
        assertThat(Files.readString(path)).isEqualTo(key);
        assertThat(DevBootstrap.ensureAppKeyFile(path)).isEqualTo(key);
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(path)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void keysLandNextToTheEmbeddedDataDirUnlessConfigured() throws Exception {
        var embedded = dir.resolve("flowcatalyst/embedded-pg");
        var env = DevEnv.of(Map.of()).mutable();
        DevBootstrap.ensureSigningKey(env, embedded);
        DevBootstrap.ensureAppKey(env, embedded);
        assertThat(env.get(DevBootstrap.ENV_JWT_SIGNING_KEY_PATH)).isEqualTo(dir.resolve("flowcatalyst/jwt-signing-key.pem").toString());
        assertThat(Files.readString(Path.of(env.get(DevBootstrap.ENV_JWT_SIGNING_KEY_PATH)))).contains("PRIVATE KEY");
        assertThat(dir.resolve("flowcatalyst/app-key")).exists();

        var preset = DevEnv.of(Map.of(DevBootstrap.ENV_JWT_SIGNING_KEY_PATH, "/elsewhere/key.pem", DevBootstrap.ENV_APP_KEY, "abc")).mutable();
        DevBootstrap.ensureSigningKey(preset, embedded);
        DevBootstrap.ensureAppKey(preset, embedded);
        assertThat(preset.get(DevBootstrap.ENV_JWT_SIGNING_KEY_PATH)).isEqualTo("/elsewhere/key.pem");
        assertThat(preset.get(DevBootstrap.ENV_APP_KEY)).isEqualTo("abc");
    }

    @Test
    void freshTruncatesTheGoTableListInOneStatement() {
        assertThat(FreshCommand.FRESH_TABLES).hasSize(43).startsWith("aud_logs").endsWith("app_platform_configs");
        assertThat(FreshCommand.truncateSql()).startsWith("TRUNCATE TABLE aud_logs, msg_events_read").endsWith(" RESTART IDENTITY CASCADE");
    }

    @Test
    void dbUpgradeBackupName() {
        var p = DbCommand.Upgrade.backupPath(Path.of("/x/embedded-pg/data"), "17", java.time.LocalDateTime.of(2026, 8, 22, 13, 5, 9));
        assertThat(p).isEqualTo(Path.of("/x/embedded-pg/data.bak-pg17-20260822-130509"));
    }

    @Test
    void embeddedPgVersionIsPinnedByTheBuild() throws Exception {
        assertThat(Version.embeddedPgVersion()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(EmbeddedPg.pinnedMajor()).isEqualTo(Version.embeddedPgVersion().split("\\.")[0]);
        assertThat(EmbeddedPg.dataMajor(dir)).isEmpty();
        EmbeddedPg.assertCompatible(dir);                              // no cluster: fine
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/PG_VERSION"), EmbeddedPg.pinnedMajor() + "\n");
        EmbeddedPg.assertCompatible(dir);                              // same major: fine
        Files.writeString(dir.resolve("data/PG_VERSION"), "9\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EmbeddedPg.assertCompatible(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PG9").hasMessageContaining("fcdev db upgrade").hasMessageContaining("--embedded-db-reset");
    }

    @Test
    void versionComesFromTheGoVersionFile() {
        assertThat(Version.current()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(Version.line()).startsWith("fcdev " + Version.current());
    }
}
