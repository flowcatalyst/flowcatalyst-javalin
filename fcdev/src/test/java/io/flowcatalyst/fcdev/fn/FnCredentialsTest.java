package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.DevEnv;
import io.flowcatalyst.fcdev.DevPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit tests for the three-tier lookup (`docs/spec/function-developer-surface.md`
/// §2: "flags → env → fn-cli.json → a one-line error saying which three
/// places were looked at").
class FnCredentialsTest {

    @TempDir
    Path dir;

    private DevPaths paths() {
        return new DevPaths(dir, dir.resolve("cache"));
    }

    private void writeFile(String platformUrl, String clientId, String clientSecret, String hostUrl) throws Exception {
        Files.createDirectories(paths().fnCliCredentialsPath().getParent());
        Files.writeString(paths().fnCliCredentialsPath(), """
                {"platformUrl":"%s","clientId":"%s","clientSecret":"%s","hostUrl":"%s"}
                """.formatted(platformUrl, clientId, clientSecret, hostUrl));
    }

    @Test
    void flagsWinOverEverythingElse() throws Exception {
        writeFile("http://file", "file-id", "file-secret", "http://file-host");
        var env = DevEnv.of(Map.of("FLOWCATALYST_CLIENT_ID", "env-id", "FLOWCATALYST_CLIENT_SECRET", "env-secret",
                "FLOWCATALYST_PLATFORM_URL", "http://env"));
        var creds = FnCredentials.resolve("http://flag", "flag-id", "flag-secret", env, paths());
        assertThat(creds.platformUrl()).isEqualTo("http://flag");
        assertThat(creds.clientId()).isEqualTo("flag-id");
        assertThat(creds.clientSecret()).isEqualTo("flag-secret");
    }

    @Test
    void envWinsOverFileWhenNoFlags() throws Exception {
        writeFile("http://file", "file-id", "file-secret", "http://file-host");
        var env = DevEnv.of(Map.of("FLOWCATALYST_CLIENT_ID", "env-id", "FLOWCATALYST_CLIENT_SECRET", "env-secret",
                "FLOWCATALYST_PLATFORM_URL", "http://env"));
        var creds = FnCredentials.resolve(null, null, null, env, paths());
        assertThat(creds.clientId()).isEqualTo("env-id");
        assertThat(creds.platformUrl()).isEqualTo("http://env");
    }

    @Test
    void fileUsedWhenNoFlagsOrEnv() throws Exception {
        writeFile("http://file", "file-id", "file-secret", "http://file-host");
        var creds = FnCredentials.resolve(null, null, null, DevEnv.of(Map.of()), paths());
        assertThat(creds.platformUrl()).isEqualTo("http://file");
        assertThat(creds.clientId()).isEqualTo("file-id");
        assertThat(creds.clientSecret()).isEqualTo("file-secret");
        assertThat(creds.hostUrl()).isEqualTo("http://file-host");
    }

    /// Mixed precedence: flag id/secret win, but with no URL anywhere except
    /// the file, the file's platformUrl is still used to complete them —
    /// never left unresolved just because the flag pair didn't ALSO carry a URL.
    @Test
    void flagCredentialsFallBackToFilesPlatformUrl() throws Exception {
        writeFile("http://file", "file-id", "file-secret", "http://file-host");
        var creds = FnCredentials.resolve(null, "flag-id", "flag-secret", DevEnv.of(Map.of()), paths());
        assertThat(creds.clientId()).isEqualTo("flag-id");
        assertThat(creds.platformUrl()).isEqualTo("http://file");
    }

    @Test
    void missingEverythingNamesAllThreePlaces() {
        assertThatThrownBy(() -> FnCredentials.resolve(null, null, null, DevEnv.of(Map.of()), paths()))
                .isInstanceOf(FnCredentials.MissingException.class)
                .hasMessageContaining("--client-id/--client-secret")
                .hasMessageContaining("FLOWCATALYST_CLIENT_ID")
                .hasMessageContaining("FLOWCATALYST_CLIENT_SECRET")
                .hasMessageContaining(paths().fnCliCredentialsPath().toString());
    }

    @Test
    void flagCredentialsWithNoResolvableUrlAnywhereStillFails() {
        assertThatThrownBy(() -> FnCredentials.resolve(null, "flag-id", "flag-secret", DevEnv.of(Map.of()), paths()))
                .isInstanceOf(FnCredentials.MissingException.class);
    }

    @Test
    void corruptFileIsTreatedAsAbsent() throws Exception {
        Files.createDirectories(paths().fnCliCredentialsPath().getParent());
        Files.writeString(paths().fnCliCredentialsPath(), "{not json");
        assertThatThrownBy(() -> FnCredentials.resolve(null, null, null, DevEnv.of(Map.of()), paths()))
                .isInstanceOf(FnCredentials.MissingException.class);
    }

    @Test
    void hostUrlFromFileReadsJustTheHostUrl() throws Exception {
        writeFile("http://file", "file-id", "file-secret", "http://file-host");
        assertThat(FnCredentials.hostUrlFromFile(paths())).isEqualTo("http://file-host");
    }

    @Test
    void hostUrlFromFileIsNullWhenNoFile() {
        assertThat(FnCredentials.hostUrlFromFile(paths())).isNull();
    }
}
