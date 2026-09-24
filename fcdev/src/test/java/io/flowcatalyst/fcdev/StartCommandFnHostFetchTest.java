package io.flowcatalyst.fcdev;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/fcdev-release-0.9.md` §3: `StartCommand`'s function-host jar
/// resolution — the static places, plus the native-only first-use fetch —
/// driven directly with picocli/DB/Server never in the loop (`resolveHostJar`
/// / `launchFunctionHost` need only `StartOptions`/`DevPaths`/`DevEnv` and
/// [FnHostLauncher]'s own injectable seams). Against the same fake GitHub API
/// `UpgradeCommandTest` uses (a stub `HttpServer`, never the real network).
class StartCommandFnHostFetchTest {

    private static final FnHostLauncher.JavaVersionResolver ADEQUATE_JAVA = java -> OptionalInt.of(25);

    private HttpServer github;
    private String apiBase;
    private AtomicInteger githubRequests;

    @BeforeEach
    void startGithubStub() {
        githubRequests = new AtomicInteger();
        github = null; // started per-test below when needed
    }

    @AfterEach
    void stopGithubStub() {
        if (github != null) {
            github.stop(0);
        }
    }

    private void startStubThatWouldServeTheOwnVersionRelease(byte[] jarBytes) throws Exception {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] shaBytes = (sha256Hex(jarBytes) + "  fc-fnhost.jar").getBytes(StandardCharsets.UTF_8);
        String tag = "fcdev/v" + Version.current();
        github.createContext("/repos/test/repo/releases/tags", exchange -> {
            githubRequests.incrementAndGet();
            String base = "http://127.0.0.1:" + github.getAddress().getPort();
            String json = """
                    {"tag_name":"%s","draft":false,"prerelease":false,"assets":[
                       {"name":"fc-fnhost.jar","browser_download_url":"%s/download/jar"},
                       {"name":"fc-fnhost.jar.sha256","browser_download_url":"%s/download/sha"}
                    ]}
                    """.formatted(tag, base, base);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.createContext("/download/jar", exchange -> {
            githubRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, jarBytes.length);
            exchange.getResponseBody().write(jarBytes);
            exchange.close();
        });
        github.createContext("/download/sha", exchange -> {
            githubRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, shaBytes.length);
            exchange.getResponseBody().write(shaBytes);
            exchange.close();
        });
        github.start();
        apiBase = "http://127.0.0.1:" + github.getAddress().getPort();
    }

    private void startStubWithNoOwnVersionRelease() throws Exception {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/repos/test/repo/releases/tags", exchange -> {
            githubRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        github.start();
        apiBase = "http://127.0.0.1:" + github.getAddress().getPort();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static StartOptions opts(DevEnv env, DevPaths paths) {
        return new StartOptions(env, paths);
    }

    // ── resolveHostJar: the static resolution order ─────────────────────

    @Test
    void resolveHostJarPrefersTheExplicitFlagOrEnvironment(@TempDir Path dataDir) {
        var paths = new DevPaths(dataDir, dataDir.resolve("cache"));
        var env = DevEnv.of(Map.of("FC_FN_HOST_JAR", "/explicit/host.jar"));
        var o = opts(env, paths);

        assertThat(StartCommand.resolveHostJar(o, paths)).isEqualTo(Path.of("/explicit/host.jar"));
    }

    @Test
    void resolveHostJarFallsBackToTheCachePathWhenNothingElseResolves(@TempDir Path dataDir) throws IOException {
        var paths = new DevPaths(dataDir, dataDir.resolve("cache"));
        Path cached = paths.fnHostCachePath(Version.current());
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "cached fn host jar");
        var o = opts(DevEnv.of(Map.of()), paths);

        assertThat(StartCommand.resolveHostJar(o, paths)).isEqualTo(cached);
    }

    @Test
    void resolveHostJarIsNullWhenNothingResolves(@TempDir Path dataDir) {
        var paths = new DevPaths(dataDir, dataDir.resolve("cache"));
        var o = opts(DevEnv.of(Map.of()), paths);

        assertThat(StartCommand.resolveHostJar(o, paths)).isNull();
    }

    // ── launchFunctionHost: an old Java is Disabled, wired through ───────

    /// The version check ([FnHostLauncher#MIN_JAVA_FEATURE_VERSION]) is
    /// reached through `#launchFunctionHost` too, not only a direct
    /// `FnHostLauncher#launch` call — a jar resolves statically (no fetch
    /// needed) but the injected `java` is reported too old: `Disabled`,
    /// never a spawned child. Mutant: `#launchFunctionHost` bypassing
    /// `FnHostLauncher#launch`'s own version gate.
    @Test
    void aStaticallyResolvedJarStillGoesThroughTheJavaVersionGate(@TempDir Path dataDir) throws IOException {
        var paths = new DevPaths(dataDir, dataDir.resolve("cache"));
        Path cached = paths.fnHostCachePath(Version.current());
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "cached fn host jar");
        var env = DevEnv.of(Map.of());
        var o = opts(DevEnv.of(Map.of()), paths);

        FnHostLauncher.Result result = StartCommand.launchFunctionHost(o, paths, env,
                "default", "http://localhost:18080", "fcdev-fn-host", "s3cr3t", 18090, 18092, 18091,
                () -> true, () -> Optional.of(Path.of("/usr/bin/java")), java -> OptionalInt.of(21),
                FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.Disabled.class);
        assertThat(((FnHostLauncher.Disabled) result).reason()).contains("21").contains("25");
    }

    // ── launchFunctionHost: the JVM branch never fetches (spec item 6) ───

    /// Mutant this pins: fetching even when NOT native. `isNative = () ->
    /// false` must never touch the (deliberately unreachable) GitHub stub —
    /// asserted by both a zero request count AND an `InProcess` result (the
    /// JVM branch actually ran, it did not silently no-op).
    @Test
    void jvmModeNeverFetchesEvenWithNoLocalJarResolved(@TempDir Path dataDir) throws Exception {
        var paths = new DevPaths(dataDir, dataDir.resolve("cache"));
        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo", "FC_DEV_UPGRADE_API_BASE", "http://127.0.0.1:1"));
        var o = opts(DevEnv.of(Map.of()), paths);

        int deadPort;
        try (var probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        FnHostLauncher.Result result = StartCommand.launchFunctionHost(o, paths, env,
                "default", "http://127.0.0.1:" + deadPort, "fcdev-fn-host", "s3cr3t", 0, 0, 0,
                () -> false, FnHostLauncher.DEFAULT_JAVA_RESOLVER, ADEQUATE_JAVA,
                FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).as("the JVM branch must still start in-process, not silently disable")
                .isInstanceOf(FnHostLauncher.InProcess.class);
        FnHostLauncher.close(result);
    }

    // ── launchFunctionHost: a cached jar means no HTTP request at all ────

    /// Mutant this pins: always fetching, even when the static resolution
    /// already found a jar. `githubRequests` would be nonzero if the fetch
    /// ran; the child process's argv proves the launcher was given the
    /// CACHED path, not a freshly downloaded one.
    @Test
    void aCachedJarIsUsedWithoutAnyHttpRequest(@TempDir Path dataDir) throws Exception {
        startStubWithNoOwnVersionRelease(); // would fail/404 if ever hit
        var paths = new DevPaths(dataDir, dataDir.resolve("cache"));
        Path cached = paths.fnHostCachePath(Version.current());
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "not a real jar — only its path/existence matter");
        Path fakeJava = writeFakeJavaScript(dataDir);
        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo", "FC_DEV_UPGRADE_API_BASE", apiBase));
        var o = opts(DevEnv.of(Map.of()), paths);

        FnHostLauncher.Result result = StartCommand.launchFunctionHost(o, paths, env,
                "default", "http://localhost:18080", "fcdev-fn-host", "s3cr3t", 18090, 18092, 18091,
                () -> true, () -> Optional.of(fakeJava), ADEQUATE_JAVA, FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.ChildProcess.class);
        awaitFile(dataDir.resolve("started.txt"));
        List<String> argv = Files.readAllLines(dataDir.resolve("argv.txt"));
        assertThat(argv).containsExactly("-jar", cached.toString());
        assertThat(githubRequests.get()).as("a cached jar must skip the fetch entirely").isZero();

        FnHostLauncher.close(result);
    }

    // ── launchFunctionHost: native + nothing resolved fetches ────────────
    //
    // The "fetch succeeds, the launcher is given the fetched bytes" behaviour
    // (spec item 3's beside-binary/cache-path choice and the byte-for-byte
    // write) is pinned directly against [UpgradeCommand#fetchOwnFunctionHostJar]
    // in `UpgradeCommandTest`, with explicit, disposable directories — NOT
    // here: under `mvn test`, `UpgradeCommand#selfPath()` resolves into this
    // module's real `target/`, which IS writable, so exercising the
    // "beside the binary" branch through `#launchFunctionHost` for real would
    // write a stray `fc-fnhost.jar` into the shared build output. What's
    // pinned here instead is that native mode with nothing resolved DOES
    // attempt exactly the fetch (a real HTTP request happens — mutant: skip
    // it and go straight to `Disabled` without ever asking).

    /// A failed fetch (no release published for this version) still leaves
    /// `fcdev start` able to boot — `Disabled`, never an exception — the
    /// reason names the extra remedy spec item 5 adds (`fcdev upgrade`), and
    /// the fetch was genuinely ATTEMPTED (mutant: skip the fetch and
    /// fabricate `Disabled` without ever asking GitHub — `githubRequests`
    /// would stay zero).
    @Test
    void aFailedFirstUseFetchIsDisabledNamingFcdevUpgradeAsARemedy(@TempDir Path dataDir) throws Exception {
        startStubWithNoOwnVersionRelease();
        var paths = new DevPaths(dataDir, dataDir.resolve("cache"));
        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo", "FC_DEV_UPGRADE_API_BASE", apiBase));
        var o = opts(DevEnv.of(Map.of()), paths);

        FnHostLauncher.Result result = StartCommand.launchFunctionHost(o, paths, env,
                "default", "http://localhost:18080", "fcdev-fn-host", "s3cr3t", 18090, 18092, 18091,
                () -> true, FnHostLauncher.DEFAULT_JAVA_RESOLVER, ADEQUATE_JAVA,
                FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.Disabled.class);
        String reason = ((FnHostLauncher.Disabled) result).reason();
        assertThat(reason).contains("fcdev upgrade").contains("--fn-host-jar").contains("--no-functions");
        assertThat(githubRequests.get()).as("mutant: skip the fetch -> Disabled without ever asking")
                .isPositive();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) return;
            Thread.sleep(20);
        }
        assertThat(Files.exists(file)).as(file + " must appear within 5s").isTrue();
    }

    /// Same fake `java` as `FnHostLauncherTest`: records argv, traps TERM.
    private static Path writeFakeJavaScript(Path dir) throws IOException {
        Path script = dir.resolve("fake-java.sh");
        String body = """
                #!/bin/sh
                JAR="$2"
                DIR=$(dirname "$0")
                : > "$DIR/argv.txt"
                for a in "$@"; do printf '%s\\n' "$a" >> "$DIR/argv.txt"; done
                trap "echo TERM > '$DIR/term.txt'; exit 0" TERM
                touch "$DIR/started.txt"
                while true; do sleep 1; done
                """;
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }
}
