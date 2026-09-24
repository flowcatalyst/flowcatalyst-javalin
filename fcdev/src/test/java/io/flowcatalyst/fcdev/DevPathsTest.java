package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Go `userDataDir` / `embeddedPGCacheDir` / `pidFilePath` per OS.
class DevPathsTest {

    @Test
    void macOs() {
        var p = DevPaths.resolve(Map.of(), "Mac OS X", "/Users/dev");
        assertThat(p.userDataDir()).isEqualTo(Path.of("/Users/dev/Library/Application Support"));
        assertThat(p.userCacheDir()).isEqualTo(Path.of("/Users/dev/Library/Caches"));
        assertThat(p.defaultEmbeddedPath()).isEqualTo(Path.of("/Users/dev/Library/Application Support/flowcatalyst/embedded-pg"));
        assertThat(p.pidFilePath()).isEqualTo(Path.of("/Users/dev/Library/Application Support/flowcatalyst/fcdev.pid"));
        assertThat(p.embeddedPgCacheDir()).isEqualTo(Path.of("/Users/dev/Library/Caches/flowcatalyst/embedded-pg"));
        assertThat(p.mcpCredentialsPath()).isEqualTo(Path.of("/Users/dev/Library/Caches/flowcatalyst-dev/mcp-credentials.json"));
    }

    @Test
    void linuxDefaults() {
        var p = DevPaths.resolve(Map.of(), "Linux", "/home/dev");
        assertThat(p.userDataDir()).isEqualTo(Path.of("/home/dev/.config"));
        assertThat(p.userCacheDir()).isEqualTo(Path.of("/home/dev/.cache"));
    }

    @Test
    void linuxHonoursXdg() {
        var env = Map.of("XDG_CONFIG_HOME", "/xdg/config", "XDG_CACHE_HOME", "/xdg/cache");
        var p = DevPaths.resolve(env, "Linux", "/home/dev");
        assertThat(p.userDataDir()).isEqualTo(Path.of("/xdg/config"));
        assertThat(p.userCacheDir()).isEqualTo(Path.of("/xdg/cache"));
    }

    @Test
    void xdgDataHomeWinsEverywhere() {
        assertThat(DevPaths.resolve(Map.of("XDG_DATA_HOME", "/data"), "Mac OS X", "/Users/dev").userDataDir()).isEqualTo(Path.of("/data"));
        assertThat(DevPaths.resolve(Map.of("XDG_DATA_HOME", "/data"), "Linux", "/home/dev").userDataDir()).isEqualTo(Path.of("/data"));
    }

    @Test
    void windows() {
        var env = Map.of("APPDATA", "C:\\Users\\dev\\AppData\\Roaming", "LOCALAPPDATA", "C:\\Users\\dev\\AppData\\Local");
        var p = DevPaths.resolve(env, "Windows 11", "C:\\Users\\dev");
        assertThat(p.userDataDir()).isEqualTo(Path.of("C:\\Users\\dev\\AppData\\Roaming"));
        assertThat(p.userCacheDir()).isEqualTo(Path.of("C:\\Users\\dev\\AppData\\Local"));
    }

    /// `docs/spec/fcdev-release-0.9.md` §3: the first-use fetch's cache path
    /// — versioned, under `flowcatalyst/fnhost/<version>`.
    @Test
    void fnHostCachePathIsVersionedUnderTheDataDir() {
        var p = DevPaths.resolve(Map.of(), "Linux", "/home/dev");
        assertThat(p.fnHostCachePath("0.9.0"))
                .isEqualTo(Path.of("/home/dev/.config/flowcatalyst/fnhost/0.9.0/fc-fnhost.jar"));
    }

    @Test
    void fallbacksWithoutAHome() {
        var p = DevPaths.resolve(Map.of(), "Linux", "");
        assertThat(p.userDataDir()).isEqualTo(Path.of("."));
        assertThat(p.userCacheDir()).isEqualTo(Path.of(".", ".flowcatalyst-cache"));
        var w = DevPaths.resolve(Map.of(), "Windows 10", "C:\\Users\\dev");   // no %AppData%
        assertThat(w.userDataDir()).isEqualTo(Path.of("C:\\Users\\dev", ".local", "share"));
        assertThat(w.userCacheDir()).isEqualTo(Path.of("C:\\Users\\dev", ".cache"));
    }
}
