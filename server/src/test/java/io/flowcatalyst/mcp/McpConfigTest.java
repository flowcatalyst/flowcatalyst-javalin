package io.flowcatalyst.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [McpConfig] resolution (`docs/spec/mcp.md` §1/§5): env wins per field over
/// the credentials file, a still-blank `baseUrl` falls back to the local API
/// port, the file round-trips, and [McpConfig#requireCredentials] carries the
/// missing-credentials message verbatim.
class McpConfigTest {

    @Test
    void envValuesWinOverTheFileForEveryFieldWhenAllAreSet(@org.junit.jupiter.api.io.TempDir Path dir) {
        var file = dir.resolve("mcp-credentials.json");
        McpConfig.writeCredentialsFileAt(file, "file-id", "file-secret", "https://file.example");

        var cfg = McpConfig.resolve("https://env.example", "env-id", "env-secret", 9999, file);

        assertThat(cfg.baseUrl()).isEqualTo("https://env.example");
        assertThat(cfg.clientId()).isEqualTo("env-id");
        assertThat(cfg.clientSecret()).isEqualTo("env-secret");
    }

    @Test
    void aFieldBlankInEnvIsFilledFromTheFilePerField(@org.junit.jupiter.api.io.TempDir Path dir) {
        var file = dir.resolve("mcp-credentials.json");
        McpConfig.writeCredentialsFileAt(file, "file-id", "file-secret", "https://file.example");

        // baseUrl set by env, clientId/clientSecret blank -> filled from the file.
        var cfg = McpConfig.resolve("https://env.example", "", "", 9999, file);

        assertThat(cfg.baseUrl()).as("env wins for baseUrl").isEqualTo("https://env.example");
        assertThat(cfg.clientId()).as("blank env falls back to the file").isEqualTo("file-id");
        assertThat(cfg.clientSecret()).as("blank env falls back to the file").isEqualTo("file-secret");
    }

    @Test
    void aBaseUrlStillBlankAfterTheFileFallsBackToTheLocalApiPort(@org.junit.jupiter.api.io.TempDir Path dir) {
        var noFile = dir.resolve("does-not-exist.json");

        var cfg = McpConfig.resolve("", "id", "secret", 8123, noFile);

        assertThat(cfg.baseUrl()).isEqualTo("http://localhost:8123");
    }

    @Test
    void aMissingCredentialsFileIsTreatedAsNoFileRatherThanFailingResolution(@org.junit.jupiter.api.io.TempDir Path dir) {
        var noFile = dir.resolve("nope.json");

        var cfg = McpConfig.resolve("https://env.example", "", "", 8080, noFile);

        assertThat(cfg.clientId()).isEmpty();
        assertThat(cfg.clientSecret()).isEmpty();
    }

    @Test
    void credentialsFileRoundTrips(@org.junit.jupiter.api.io.TempDir Path dir) {
        var file = dir.resolve("nested").resolve("mcp-credentials.json");

        McpConfig.writeCredentialsFileAt(file, "cid", "csecret", "https://p.test");
        var read = McpConfig.readCredentialsFileAt(file);

        assertThat(read).isPresent();
        assertThat(read.get().clientId()).isEqualTo("cid");
        assertThat(read.get().clientSecret()).isEqualTo("csecret");
        assertThat(read.get().baseUrl()).isEqualTo("https://p.test");
    }

    @Test
    void requireCredentialsThrowsWithTheVerbatimMessageWhenNeitherIdNorSecretResolved() {
        var cfg = new McpConfig("https://p.test", "", "");

        assertThatThrownBy(cfg::requireCredentials)
                .isInstanceOf(McpConfig.NoCredentialsException.class)
                .hasMessage(McpConfig.MISSING_CREDENTIALS_MESSAGE);
        assertThat(McpConfig.MISSING_CREDENTIALS_MESSAGE)
                .contains("FLOWCATALYST_CLIENT_ID/FLOWCATALYST_CLIENT_SECRET")
                .contains("fcdev start");
    }

    @Test
    void requireCredentialsPassesWhenEitherFieldIsPresent() {
        assertThatCode(() -> new McpConfig("https://p.test", "id", "").requireCredentials()).doesNotThrowAnyException();
        assertThatCode(() -> new McpConfig("https://p.test", "", "secret").requireCredentials()).doesNotThrowAnyException();
    }
}
