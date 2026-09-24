package io.flowcatalyst.fcdev;

import io.flowcatalyst.server.EnvReader;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/// Where fcdev keeps its state — the Java reading of `userDataDir`,
/// `defaultEmbeddedPath`, `embeddedPGCacheDir` and `pidFilePath` in the Go
/// `cmd/fcdev`. Same directories, so a developer switching between the Go and
/// the Java binary keeps one embedded cluster, one signing key and one PID file.
///
/// ```
/// <userDataDir>/flowcatalyst/                 persistent, per-user (never /tmp)
/// ├── embedded-pg/                             FC_EMBEDDED_DB_PATH (--embedded-db-path)
/// │   └── data/                                the PostgreSQL cluster (PG_VERSION, postgresql.conf, …)
/// ├── jwt-signing-key.pem                      persistent JWT signing key (0600)
/// ├── app-key                                  FLOWCATALYST_APP_KEY field-encryption key (0600)
/// └── fcdev.pid                                PID of the running `fcdev start` (FC_DEV_PID_FILE)
///
/// <userCacheDir>/flowcatalyst/embedded-pg/     re-creatable: the extracted PG binaries
/// └── PG-<md5-of-archive>/{bin,lib,share}      (zonky unpacks the bundled .txz here once)
/// <userCacheDir>/flowcatalyst-dev/mcp-credentials.json   local MCP OAuth client (Go; not yet ported)
/// ```
///
/// `userDataDir` = `$XDG_DATA_HOME`, else Go's `os.UserConfigDir()`
/// (`~/Library/Application Support` on macOS, `%AppData%` on Windows,
/// `$XDG_CONFIG_HOME` / `~/.config` elsewhere), else `~/.local/share`, else `.`.
/// `userCacheDir` = Go's `os.UserCacheDir()` (`~/Library/Caches`,
/// `%LocalAppData%`, `$XDG_CACHE_HOME` / `~/.cache`), else `~/.cache`, else
/// `./.flowcatalyst-cache`.
public record DevPaths(Path userDataDir, Path userCacheDir) {

    /// Resolve for the running OS and the given environment.
    public static DevPaths resolve(Map<String, String> env) {
        return resolve(env, System.getProperty("os.name", ""), System.getProperty("user.home", ""));
    }

    /// Pure resolver — `osName` is `os.name`, `home` is `user.home` (the JVM
    /// reading of `$HOME` / `%USERPROFILE%`). Exposed for tests.
    public static DevPaths resolve(Map<String, String> env, String osName, String home) {
        var reader = new EnvReader(env);
        return new DevPaths(userDataDir(reader, osName, home), userCacheDir(reader, osName, home));
    }

    static Path userDataDir(EnvReader env, String osName, String home) {
        var xdgData = env.get("XDG_DATA_HOME");
        if (!xdgData.isEmpty()) return Path.of(xdgData);
        var cfg = userConfigDir(env, osName, home);
        if (cfg != null) return cfg;
        if (!home.isEmpty()) return Path.of(home, ".local", "share");
        return Path.of(".");
    }

    /// Go `os.UserConfigDir()`; `null` where Go returns an error.
    static Path userConfigDir(EnvReader env, String osName, String home) {
        var os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            var appData = env.get("APPDATA");
            return appData.isEmpty() ? null : Path.of(appData);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return home.isEmpty() ? null : Path.of(home, "Library", "Application Support");
        }
        var xdgCfg = env.get("XDG_CONFIG_HOME");
        if (!xdgCfg.isEmpty()) return Path.of(xdgCfg);
        return home.isEmpty() ? null : Path.of(home, ".config");
    }

    static Path userCacheDir(EnvReader env, String osName, String home) {
        var os = osName.toLowerCase(Locale.ROOT);
        Path cache = null;
        if (os.contains("win")) {
            var local = env.get("LOCALAPPDATA");
            if (!local.isEmpty()) cache = Path.of(local);
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (!home.isEmpty()) cache = Path.of(home, "Library", "Caches");
        } else {
            var xdgCache = env.get("XDG_CACHE_HOME");
            if (!xdgCache.isEmpty()) cache = Path.of(xdgCache);
            else if (!home.isEmpty()) cache = Path.of(home, ".cache");
        }
        if (cache != null) return cache;
        if (!home.isEmpty()) return Path.of(home, ".cache");
        return Path.of(".", ".flowcatalyst-cache");
    }

    /// `<userDataDir>/flowcatalyst` — the base every persistent file lives under.
    public Path flowcatalystDir() {
        return userDataDir.resolve("flowcatalyst");
    }

    /// `defaultEmbeddedPath`: `<userDataDir>/flowcatalyst/embedded-pg` (the
    /// cluster itself is `<that>/data`).
    public Path defaultEmbeddedPath() {
        return flowcatalystDir().resolve("embedded-pg");
    }

    /// `pidFilePath`: `<userDataDir>/flowcatalyst/fcdev.pid`.
    public Path pidFilePath() {
        return flowcatalystDir().resolve("fcdev.pid");
    }

    /// `embeddedPGCacheDir`: `<userCacheDir>/flowcatalyst/embedded-pg`.
    public Path embeddedPgCacheDir() {
        return userCacheDir.resolve("flowcatalyst").resolve("embedded-pg");
    }

    /// Go `mcp.CredentialsPath()`: `<userCacheDir>/flowcatalyst-dev/mcp-credentials.json`.
    public Path mcpCredentialsPath() {
        return userCacheDir.resolve("flowcatalyst-dev").resolve("mcp-credentials.json");
    }

    /// `docs/spec/function-developer-surface.md` §1: `fn-cli.json` — the
    /// `fcdev-fn-cli` credentials, written into the persistent state dir
    /// (not the re-creatable cache) so `fcdev fn …` needs no flags locally.
    /// Removed on `fcdev stop`.
    public Path fnCliCredentialsPath() {
        return flowcatalystDir().resolve("fn-cli.json");
    }

    /// `docs/spec/function-developer-surface.md` §2 (`fn publish`, local
    /// mode): `<state>/fn-artifacts/<hex-of-sha256>.jar` — the published copy
    /// a local `file://` artifactRef points at, since the build directory's
    /// jar is about to be overwritten by the next build. Persistent (not the
    /// re-creatable cache dir) and distinct from {@link #fnCacheDir()}, which
    /// is the function HOST's own download cache for `oci://`/`s3://`
    /// artifacts, not the CLI's local-publish store.
    public Path fnArtifactsDir() {
        return flowcatalystDir().resolve("fn-artifacts");
    }

    /// The function host's artifact cache directory — a sibling of the
    /// embedded-Postgres data dir under the persistent state dir, not the
    /// re-creatable cache dir (a developer's published functions should
    /// survive a `flowcatalyst/embedded-pg` cache clear the way the Go/Java
    /// switch already tolerates for everything else here).
    public Path fnCacheDir() {
        return flowcatalystDir().resolve("fn-cache");
    }

    /// `docs/spec/fcdev-release-0.9.md` §3: the first-use function-host
    /// fetch's cache path — `<userDataDir>/flowcatalyst/fnhost/<version>/fc-fnhost.jar` —
    /// used when the directory beside the running native binary is not
    /// writable. Versioned by `version` (`Version.current()`) so a later
    /// fcdev build never resolves a host jar cached by an older one.
    public Path fnHostCachePath(String version) {
        return flowcatalystDir().resolve("fnhost").resolve(version).resolve("fc-fnhost.jar");
    }
}
