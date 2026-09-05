package io.flowcatalyst.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowcatalyst.platform.shared.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// The resolved MCP server configuration — where the platform lives and the
/// `client_credentials` credentials used to mint API tokens (`docs/spec/mcp.md`
/// §1; Go `internal/mcp/config.go`).
///
/// [#resolve] is the value-taking factory `Server` calls (CONVENTIONS §8: no
/// `EnvReader.system()` inside a subsystem); every field is env-first,
/// falling back to the on-disk credentials file [fcdev] bootstraps, falling
/// back in turn — for `baseUrl` alone — to the local API listener's own port.
public record McpConfig(String baseUrl, String clientId, String clientSecret) {

    public McpConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
    }

    /// `no MCP credentials: …` — copied verbatim from Go's `errNoCredentials`
    /// (`RequireCredentials`); the hint still names `CredentialsPath` since
    /// that is this port's accessor too ([#credentialsPath]).
    public static final String MISSING_CREDENTIALS_MESSAGE =
            "no MCP credentials: set FLOWCATALYST_CLIENT_ID/FLOWCATALYST_CLIENT_SECRET, "
                    + "or run `fcdev start` to bootstrap the mcp-credentials.json in the OS cache dir "
                    + "(macOS ~/Library/Caches, Linux ~/.cache; see CredentialsPath)";

    /// Resolves the config: `envBaseUrl` / `envClientId` / `envClientSecret`
    /// (already carrying `FLOWCATALYST_URL` → `FC_MCP_PLATFORM_URL` /
    /// `FLOWCATALYST_CLIENT_ID` / `FLOWCATALYST_CLIENT_SECRET` precedence —
    /// [Env]'s job) win per field; whatever is still blank is filled from the
    /// credentials file [fcdev start] bootstraps; a `baseUrl` still blank
    /// after that falls back to the local API listener on `apiPort`.
    public static McpConfig resolve(String envBaseUrl, String envClientId, String envClientSecret, int apiPort) {
        return resolve(envBaseUrl, envClientId, envClientSecret, apiPort, credentialsPath());
    }

    /// The testable core of [#resolve]: same precedence, but reading the
    /// credentials file from `credentialsFilePath` instead of always the
    /// real [#credentialsPath] — lets [McpConfigTest] exercise the merge
    /// against a temp-dir fixture instead of the machine's real cache dir.
    static McpConfig resolve(String envBaseUrl, String envClientId, String envClientSecret, int apiPort,
                              Path credentialsFilePath) {
        var baseUrl = envBaseUrl == null ? "" : envBaseUrl;
        var clientId = envClientId == null ? "" : envClientId;
        var clientSecret = envClientSecret == null ? "" : envClientSecret;

        if (baseUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            var file = readCredentialsFileAt(credentialsFilePath);
            if (file.isPresent()) {
                if (baseUrl.isBlank()) baseUrl = file.get().baseUrl();
                if (clientId.isBlank()) clientId = file.get().clientId();
                if (clientSecret.isBlank()) clientSecret = file.get().clientSecret();
            }
        }
        if (baseUrl.isBlank()) {
            // Deliberately the *actual* local API port rather than Go's hardcoded
            // `http://localhost:8080`: the platform API and the MCP server share
            // one process's Env, so the port it is really bound to is always known.
            baseUrl = "http://localhost:" + apiPort;
        }
        return new McpConfig(baseUrl, clientId, clientSecret);
    }

    /// Whether both halves of the `client_credentials` grant are configured.
    public boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /// Throws with [#MISSING_CREDENTIALS_MESSAGE] when neither `clientId` nor
    /// `clientSecret` resolved to anything (Go `RequireCredentials`); a lone
    /// field (e.g. a `clientSecret` with no `clientId`) does not trip this —
    /// it will simply fail the token request itself.
    public void requireCredentials() {
        if (clientId.isBlank() && clientSecret.isBlank()) {
            throw new NoCredentialsException(MISSING_CREDENTIALS_MESSAGE);
        }
    }

    /// Thrown by [#requireCredentials]; also raised (with the same message) by
    /// [PlatformClient#get] when a tool call has no way at all to
    /// authenticate — see [PlatformClient.AuthMode#resolve].
    public static final class NoCredentialsException extends RuntimeException {
        public NoCredentialsException(String message) {
            super(message);
        }
    }

    // ── credentials file ──────────────────────────────────────────────────

    /// `<user cache dir>/flowcatalyst-dev/mcp-credentials.json` — the file
    /// `fcdev start` bootstraps and this server reads as a fallback. Mirrors
    /// Go's `os.UserCacheDir()`: macOS `~/Library/Caches`, Linux
    /// `$XDG_CACHE_HOME` (else `~/.cache`), Windows `%LocalAppData%`.
    public static Path credentialsPath() {
        return userCacheDir().resolve("flowcatalyst-dev").resolve("mcp-credentials.json");
    }

    private static Path userCacheDir() {
        var xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg);
        }
        var home = System.getProperty("user.home");
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Caches");
        }
        if (os.contains("win")) {
            var localAppData = System.getenv("LocalAppData");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData);
            }
        }
        return Path.of(home, ".cache");
    }

    /// The on-disk shape ([fcdev]'s bootstrap and this server's fallback read
    /// agree on it): `{"client_id","client_secret","base_url"}`.
    public record CredentialsFile(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_secret") String clientSecret,
            @JsonProperty("base_url") String baseUrl) {
    }

    static Optional<CredentialsFile> readCredentialsFileAt(Path path) {
        try {
            var bytes = Files.readAllBytes(path);
            return Optional.of(Json.read(new String(bytes, StandardCharsets.UTF_8), CredentialsFile.class));
        } catch (IOException | RuntimeException e) {
            // Missing file, unreadable, or unparseable JSON: all treated the
            // same as "no file" — a corrupt bootstrap file must not crash
            // config resolution, only leave the fields it would have filled blank.
            return Optional.empty();
        }
    }

    /// Persists local MCP credentials to `path` — used by [fcdev]'s bootstrap
    /// and by the round-trip test. Not called by the server itself.
    static void writeCredentialsFileAt(Path path, String clientId, String clientSecret, String baseUrl) {
        try {
            Files.createDirectories(path.getParent());
            var json = Json.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new CredentialsFile(clientId, clientSecret, baseUrl));
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
            // 0600: readable/writable by the owner only — the file carries a client secret.
            var posix = Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            try {
                Files.setPosixFilePermissions(path, posix);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (Windows): no equivalent, nothing to do.
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
