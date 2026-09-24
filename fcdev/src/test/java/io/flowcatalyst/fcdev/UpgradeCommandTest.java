package io.flowcatalyst.fcdev;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.sdk.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `fcdev upgrade` (`docs/fcdev.md` §4, Go `upgrade.go`) against a stub
/// GitHub API — never the real network. Pins two load-bearing rules: the
/// SHA256 sidecar gates the install (a mismatch must abort BEFORE the
/// destination file is touched), and the "self path" is never the real
/// running jar in a test — [UpgradeCommand#selfPathOverride] redirects it to
/// a temp file.
class UpgradeCommandTest {

    private static final byte[] NEW_JAR_BYTES = "NEW-JAR-BYTES-v0.9.1".getBytes(StandardCharsets.UTF_8);

    private HttpServer github;
    private String apiBase;
    private final AtomicReference<String> shaSidecar = new AtomicReference<>();

    @BeforeEach
    void startGithubStub() throws Exception {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/repos/test/repo/releases", exchange -> {
            String json = releasesJson("http://127.0.0.1:" + github.getAddress().getPort());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // Like GitHub: the asset's browser_download_url answers 302 to its storage host.
        github.createContext("/download/jar", exchange -> {
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + github.getAddress().getPort() + "/objects/jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        github.createContext("/objects/jar", exchange -> {
            exchange.sendResponseHeaders(200, NEW_JAR_BYTES.length);
            exchange.getResponseBody().write(NEW_JAR_BYTES);
            exchange.close();
        });
        github.createContext("/download/sha", exchange -> {
            byte[] body = shaSidecar.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.start();
        apiBase = "http://127.0.0.1:" + github.getAddress().getPort();
        shaSidecar.set(sha256Hex(NEW_JAR_BYTES) + "  fcdev-v0.9.1.jar");
    }

    @AfterEach
    void stopGithubStub() {
        github.stop(0);
    }

    private String releasesJson(String base) {
        return """
                [
                  {"tag_name":"fcdev/v0.9.1","draft":false,"prerelease":false,"assets":[
                     {"name":"fcdev-v0.9.1.jar","browser_download_url":"%s/download/jar"},
                     {"name":"fcdev-v0.9.1.jar.sha256","browser_download_url":"%s/download/sha"}
                  ]},
                  {"tag_name":"fcdev/v99.0.0-beta","draft":false,"prerelease":true,"assets":[]},
                  {"tag_name":"typescript-sdk/v2.0.0","draft":false,"prerelease":false,"assets":[]},
                  {"tag_name":"fcdev/v0.1.0","draft":true,"prerelease":false,"assets":[]}
                ]
                """.formatted(base, base);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private UpgradeCommand build(Path selfFile, String... args) throws Exception {
        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo"));
        var cmd = new UpgradeCommand(env, apiBase);
        cmd.selfPathOverride = selfFile;
        // Under `mvn test` the code source is an exploded target/test-classes
        // directory, not a jar — force the jar path so this test exercises
        // the same replace-the-artifact logic a real jar launch would.
        cmd.launchKindOverride = UpgradeCommand.LaunchKind.JAR;
        var cl = new CommandLine(cmd);
        var sw = new StringWriter();
        cl.setOut(new PrintWriter(sw));
        cl.parseArgs(args);
        return cmd;
    }

    // ── happy path: verified checksum installs the new content ──────────

    @Test
    void aVerifiedDownloadReplacesTheSelfFileWithTheDownloadedBytes() throws Exception {
        Path self = Files.createTempFile("fcdev-upgrade-test", ".jar");
        Files.writeString(self, "OLD CONTENT");

        var cmd = build(self);
        Integer exitCode = cmd.call();

        assertThat(exitCode).isZero();
        assertThat(Files.readAllBytes(self)).isEqualTo(NEW_JAR_BYTES);
    }

    // ── the mutant this test exists to kill: "the sha256 check skipped" ──

    /// If [UpgradeCommand#verifySha256] is ever called-but-ignored (or not
    /// called at all), this test still passes on the exception alone is not
    /// enough — a mutant could throw for some OTHER reason and still leave
    /// the file replaced first. This asserts BOTH: the call fails, AND the
    /// self file is byte-for-byte unchanged from before the call.
    @Test
    void aMismatchedChecksumAbortsAndLeavesTheSelfFileUntouched() throws Exception {
        shaSidecar.set("0000000000000000000000000000000000000000000000000000000000000000  fcdev-v0.9.1.jar");
        Path self = Files.createTempFile("fcdev-upgrade-test", ".jar");
        byte[] original = "OLD CONTENT — must survive".getBytes(StandardCharsets.UTF_8);
        Files.write(self, original);

        var cmd = build(self);

        assertThatThrownBy(cmd::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum mismatch");
        assertThat(Files.readAllBytes(self)).as("the self file must not be touched when the checksum fails")
                .isEqualTo(original);
    }

    // ── --check reports without installing ───────────────────────────────

    /// fcdev's own binary needs its sidecar too (it used to warn and install
    /// unverified). Mutant: fall back to the warning.
    @Test
    void aReleaseWithoutTheSidecarRefusesAndLeavesTheSelfFileUntouched() throws Exception {
        github.removeContext("/repos/test/repo/releases");
        github.createContext("/repos/test/repo/releases", exchange -> {
            String base = "http://127.0.0.1:" + github.getAddress().getPort();
            byte[] body = ("""
                    [{"tag_name":"fcdev/v0.9.1","draft":false,"prerelease":false,"assets":[
                       {"name":"fcdev-v0.9.1.jar","browser_download_url":"%s/download/jar"}]}]
                    """.formatted(base)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        Path self = Files.createTempFile("fcdev-upgrade-test", ".jar");
        Files.writeString(self, "OLD CONTENT");

        var cmd = build(self);

        assertThatThrownBy(cmd::call).isInstanceOf(IllegalStateException.class).hasMessageContaining(".sha256");
        assertThat(Files.readString(self)).isEqualTo("OLD CONTENT");
    }

    @Test
    void checkOnlyReportsAndNeverTouchesTheSelfFile() throws Exception {
        Path self = Files.createTempFile("fcdev-upgrade-test", ".jar");
        byte[] original = "OLD CONTENT".getBytes(StandardCharsets.UTF_8);
        Files.write(self, original);

        var cmd = build(self, "--check");
        Integer exitCode = cmd.call();

        assertThat(exitCode).isZero();
        assertThat(Files.readAllBytes(self)).isEqualTo(original);
    }

    // ── release selection: drafts/prereleases/other-tag-namespaces ignored ──

    @Test
    void selectsTheHighestNonDraftNonPrereleaseFcdevRelease() throws Exception {
        var cmd = build(Files.createTempFile("fcdev-upgrade-test", ".jar"));
        var release = cmd.latestRelease("test/repo");

        assertThat(release.version()).isEqualTo("0.9.1");
    }

    /// A release fixture strictly BELOW [#CURRENT_VERSION] regardless of
    /// what this build's real `VERSION` resource says (`0.0.1` can never
    /// exceed a real X.Y.Z build version), so this test never depends on
    /// the repo's current version number.
    @Test
    void alreadyUpToDateWithoutForceDoesNotDownload() throws Exception {
        github.createContext("/repos/old/repo/releases", exchange -> {
            byte[] body = """
                    [{"tag_name":"fcdev/v0.0.1","draft":false,"prerelease":false,"assets":[]}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Path self = Files.createTempFile("fcdev-upgrade-test", ".jar");
        byte[] original = "OLD CONTENT".getBytes(StandardCharsets.UTF_8);
        Files.write(self, original);

        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "old/repo"));
        var cmd = new UpgradeCommand(env, apiBase);
        cmd.selfPathOverride = self;
        cmd.launchKindOverride = UpgradeCommand.LaunchKind.JAR;
        var cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(new StringWriter()));
        cl.parseArgs();

        Integer exitCode = cmd.call();

        assertThat(exitCode).isZero();
        assertThat(Files.readAllBytes(self)).as("no download attempted — the file is untouched").isEqualTo(original);
    }

    // ── refuses when not running from a release artifact ─────────────────

    @Test
    void refusesToInstallWhenNotRunningFromAReleaseArtifact() throws Exception {
        var cmd = build(Files.createTempFile("fcdev-upgrade-test", ".jar"));
        cmd.launchKindOverride = UpgradeCommand.LaunchKind.OTHER;

        assertThatThrownBy(cmd::call).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running from a release artifact");
    }

    // ── fc-fnhost.jar (docs/spec/function-developer-surface.md §1) ───────

    /// The asset is present: fetched, verified against its sha256 sidecar,
    /// and written beside the given directory under the FIXED name
    /// `fc-fnhost.jar` (no version in the filename — `fcdev upgrade` always
    /// looks for that exact name).
    @Test
    void fetchesAndVerifiesFcFnhostJarWhenTheAssetIsPublished() throws Exception {
        byte[] fnhostBytes = "FN-HOST-JAR-BYTES".getBytes(StandardCharsets.UTF_8);
        byte[] shaSidecarBytes = (sha256Hex(fnhostBytes) + "  fc-fnhost.jar").getBytes(StandardCharsets.UTF_8);
        github.createContext("/download/fnhost", exchange -> {
            exchange.sendResponseHeaders(200, fnhostBytes.length);
            exchange.getResponseBody().write(fnhostBytes);
            exchange.close();
        });
        github.createContext("/download/fnhost-sha", exchange -> {
            exchange.sendResponseHeaders(200, shaSidecarBytes.length);
            exchange.getResponseBody().write(shaSidecarBytes);
            exchange.close();
        });
        var cmd = build(Files.createTempFile("fcdev-upgrade-test", ".jar"));
        var rel = new UpgradeCommand.Release("0.9.0", Map.of(
                "fc-fnhost.jar", apiBase + "/download/fnhost",
                "fc-fnhost.jar.sha256", apiBase + "/download/fnhost-sha"));
        Path dir = Files.createTempDirectory("fcdev-upgrade-fnhost-test");

        cmd.fetchFunctionHostJarIfPresent(rel, dir, new PrintWriter(new StringWriter()));

        assertThat(dir.resolve("fc-fnhost.jar")).exists();
        assertThat(Files.readAllBytes(dir.resolve("fc-fnhost.jar"))).isEqualTo(fnhostBytes);
    }

    /// No asset published (an older release): silently skipped, no file
    /// written, never an error — mutant: this refusal must not be
    /// mistaken for a checksum failure or thrown as an exception.
    @Test
    void skipsSilentlyWhenNoFcFnhostJarAssetIsPublished() throws Exception {
        var cmd = build(Files.createTempFile("fcdev-upgrade-test", ".jar"));
        var rel = new UpgradeCommand.Release("0.9.0", Map.of());
        Path dir = Files.createTempDirectory("fcdev-upgrade-fnhost-test");

        cmd.fetchFunctionHostJarIfPresent(rel, dir, new PrintWriter(new StringWriter()));

        assertThat(dir.resolve("fc-fnhost.jar")).doesNotExist();
    }

    /// `docs/spec/fcdev-release-0.9.md` §3, last line: the checksum is now
    /// REQUIRED here too — an asset with no `.sha256` sidecar must refuse to
    /// install rather than falling back to an unverified copy. Mutant: treat
    /// a missing sidecar as ok (the old behaviour).
    @Test
    void fcFnhostJarRefusesToInstallWithoutASha256Sidecar() throws Exception {
        byte[] fnhostBytes = "FN-HOST-NO-SHA".getBytes(StandardCharsets.UTF_8);
        github.createContext("/download/fnhost-req", exchange -> {
            exchange.sendResponseHeaders(200, fnhostBytes.length);
            exchange.getResponseBody().write(fnhostBytes);
            exchange.close();
        });
        var cmd = build(Files.createTempFile("fcdev-upgrade-test", ".jar"));
        var rel = new UpgradeCommand.Release("0.9.1", Map.of("fc-fnhost.jar", apiBase + "/download/fnhost-req"));
        Path dir = Files.createTempDirectory("fcdev-upgrade-fnhost-test");

        assertThatThrownBy(() -> cmd.fetchFunctionHostJarIfPresent(rel, dir, new PrintWriter(new StringWriter())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sha256");
        assertThat(dir.resolve("fc-fnhost.jar")).as("must not install an unverified jar").doesNotExist();
    }

    // ── first-use fetch (fcdev start, docs/spec/fcdev-release-0.9.md §3) ──

    /// Happy path: the release tagged for THIS fcdev's own version publishes
    /// `fc-fnhost.jar` + a valid `.sha256`. `installDir=null` forces the
    /// cache-path fallback (spec item 3). Pins BOTH "written to the cache
    /// path with the served bytes" AND "asks for `fcdev/v<Version.current()>`,
    /// never latest" — a mutant calling [UpgradeCommand#latestRelease]
    /// instead would hit the `@BeforeEach` fixture (which publishes
    /// `fcdev-v0.9.1.jar`, not `fc-fnhost.jar`) and this would observe an
    /// `Err`, not an `Ok`.
    @Test
    void fetchesTheReleaseTaggedForFcdevsOwnVersionAndWritesItToTheCachePath() throws Exception {
        byte[] fnhostBytes = "FN-HOST-JAR-FIRST-USE".getBytes(StandardCharsets.UTF_8);
        byte[] shaBytes = (sha256Hex(fnhostBytes) + "  fc-fnhost.jar").getBytes(StandardCharsets.UTF_8);
        String tag = "fcdev/v" + Version.current();
        var requestedPath = new AtomicReference<String>();
        github.createContext("/repos/test/repo/releases/tags", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            String base = "http://127.0.0.1:" + github.getAddress().getPort();
            if (!exchange.getRequestURI().getPath().equals("/repos/test/repo/releases/tags/" + tag)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String json = """
                    {"tag_name":"%s","draft":false,"prerelease":false,"assets":[
                       {"name":"fc-fnhost.jar","browser_download_url":"%s/download/fnhost-first-use"},
                       {"name":"fc-fnhost.jar.sha256","browser_download_url":"%s/download/fnhost-first-use-sha"}
                    ]}
                    """.formatted(tag, base, base);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.createContext("/download/fnhost-first-use-sha", exchange -> {
            exchange.sendResponseHeaders(200, shaBytes.length);
            exchange.getResponseBody().write(shaBytes);
            exchange.close();
        });
        github.createContext("/download/fnhost-first-use", exchange -> {
            exchange.sendResponseHeaders(200, fnhostBytes.length);
            exchange.getResponseBody().write(fnhostBytes);
            exchange.close();
        });

        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo"));
        var cmd = new UpgradeCommand(env, apiBase);
        Path cacheDir = Files.createTempDirectory("fcdev-fnhost-cache-test");

        Result<Path, UpgradeCommand.FetchError> result = cmd.fetchOwnFunctionHostJar("test/repo", null, cacheDir);

        assertThat(result).isInstanceOf(Result.Ok.class);
        Path written = ((Result.Ok<Path, UpgradeCommand.FetchError>) result).value();
        assertThat(written).isEqualTo(cacheDir.resolve("fc-fnhost.jar"));
        assertThat(Files.readAllBytes(written)).isEqualTo(fnhostBytes);
        assertThat(requestedPath.get()).as("must ask for THIS fcdev's own tagged release, never \"latest\"")
                .isEqualTo("/repos/test/repo/releases/tags/" + tag);
    }

    /// Spec item 3: beside the binary is tried FIRST, only falling back to
    /// the cache path when that directory is not writable.
    @Test
    void writesBesideTheBinaryWhenThatDirectoryIsWritable() throws Exception {
        byte[] fnhostBytes = "FN-HOST-JAR-BESIDE".getBytes(StandardCharsets.UTF_8);
        byte[] shaBytes = (sha256Hex(fnhostBytes) + "  fc-fnhost.jar").getBytes(StandardCharsets.UTF_8);
        String tag = "fcdev/v" + Version.current();
        github.createContext("/repos/test/repo/releases/tags", exchange -> {
            String base = "http://127.0.0.1:" + github.getAddress().getPort();
            String json = """
                    {"tag_name":"%s","draft":false,"prerelease":false,"assets":[
                       {"name":"fc-fnhost.jar","browser_download_url":"%s/download/fnhost-beside"},
                       {"name":"fc-fnhost.jar.sha256","browser_download_url":"%s/download/fnhost-beside-sha"}
                    ]}
                    """.formatted(tag, base, base);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.createContext("/download/fnhost-beside", exchange -> {
            exchange.sendResponseHeaders(200, fnhostBytes.length);
            exchange.getResponseBody().write(fnhostBytes);
            exchange.close();
        });
        github.createContext("/download/fnhost-beside-sha", exchange -> {
            exchange.sendResponseHeaders(200, shaBytes.length);
            exchange.getResponseBody().write(shaBytes);
            exchange.close();
        });

        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo"));
        var cmd = new UpgradeCommand(env, apiBase);
        Path installDir = Files.createTempDirectory("fcdev-fnhost-beside-test");
        Path cacheDir = Files.createTempDirectory("fcdev-fnhost-cache-test");

        Result<Path, UpgradeCommand.FetchError> result = cmd.fetchOwnFunctionHostJar("test/repo", installDir, cacheDir);

        assertThat(result).isInstanceOf(Result.Ok.class);
        Path written = ((Result.Ok<Path, UpgradeCommand.FetchError>) result).value();
        assertThat(written).isEqualTo(installDir.resolve("fc-fnhost.jar"));
        assertThat(cacheDir.resolve("fc-fnhost.jar")).as("must not ALSO write the cache path").doesNotExist();
    }

    /// Spec item 2: no `.sha256` asset published is a failed fetch — nothing
    /// written. Mutant: treat a missing checksum as ok.
    @Test
    void firstUseFetchMissingChecksumSidecarIsAFailedFetchWithNothingWritten() throws Exception {
        String tag = "fcdev/v" + Version.current();
        github.createContext("/repos/test/repo/releases/tags", exchange -> {
            String base = "http://127.0.0.1:" + github.getAddress().getPort();
            String json = """
                    {"tag_name":"%s","draft":false,"prerelease":false,"assets":[
                       {"name":"fc-fnhost.jar","browser_download_url":"%s/download/fnhost-nosha"}
                    ]}
                    """.formatted(tag, base);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.createContext("/download/fnhost-nosha", exchange -> {
            byte[] body = "SHOULD-NOT-BE-WRITTEN".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo"));
        var cmd = new UpgradeCommand(env, apiBase);
        Path cacheDir = Files.createTempDirectory("fcdev-fnhost-cache-test");

        Result<Path, UpgradeCommand.FetchError> result = cmd.fetchOwnFunctionHostJar("test/repo", null, cacheDir);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Path, UpgradeCommand.FetchError>) result).error())
                .isInstanceOf(UpgradeCommand.FetchError.NoChecksum.class);
        assertThat(cacheDir.resolve("fc-fnhost.jar")).doesNotExist();
    }

    /// Spec item 2: a mismatch is a failed fetch — nothing written. Mutant:
    /// skip the compare.
    @Test
    void firstUseFetchChecksumMismatchIsAFailedFetchWithNothingWritten() throws Exception {
        byte[] fnhostBytes = "FN-HOST-JAR-MISMATCH".getBytes(StandardCharsets.UTF_8);
        byte[] wrongSha = "0000000000000000000000000000000000000000000000000000000000000000  fc-fnhost.jar"
                .getBytes(StandardCharsets.UTF_8);
        String tag = "fcdev/v" + Version.current();
        github.createContext("/repos/test/repo/releases/tags", exchange -> {
            String base = "http://127.0.0.1:" + github.getAddress().getPort();
            String json = """
                    {"tag_name":"%s","draft":false,"prerelease":false,"assets":[
                       {"name":"fc-fnhost.jar","browser_download_url":"%s/download/fnhost-mismatch"},
                       {"name":"fc-fnhost.jar.sha256","browser_download_url":"%s/download/fnhost-mismatch-sha"}
                    ]}
                    """.formatted(tag, base, base);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        github.createContext("/download/fnhost-mismatch", exchange -> {
            exchange.sendResponseHeaders(200, fnhostBytes.length);
            exchange.getResponseBody().write(fnhostBytes);
            exchange.close();
        });
        github.createContext("/download/fnhost-mismatch-sha", exchange -> {
            exchange.sendResponseHeaders(200, wrongSha.length);
            exchange.getResponseBody().write(wrongSha);
            exchange.close();
        });

        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo"));
        var cmd = new UpgradeCommand(env, apiBase);
        Path cacheDir = Files.createTempDirectory("fcdev-fnhost-cache-test");

        Result<Path, UpgradeCommand.FetchError> result = cmd.fetchOwnFunctionHostJar("test/repo", null, cacheDir);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Path, UpgradeCommand.FetchError>) result).error())
                .isInstanceOf(UpgradeCommand.FetchError.ChecksumMismatch.class);
        assertThat(cacheDir.resolve("fc-fnhost.jar")).doesNotExist();
    }

    /// No `fcdev/v<version>` release published yet (a build ahead of its
    /// first tag): a 404 from GitHub is [UpgradeCommand.FetchError.NoRelease],
    /// never an exception.
    @Test
    void firstUseFetchNoReleaseYetYieldsNoRelease() {
        github.createContext("/repos/test/repo/releases/tags", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        var env = DevEnv.of(Map.of("FC_DEV_UPGRADE_REPO", "test/repo"));
        var cmd = new UpgradeCommand(env, apiBase);
        Path cacheDir = Path.of("/tmp/never-used-fcdev-fnhost-cache");

        Result<Path, UpgradeCommand.FetchError> result = cmd.fetchOwnFunctionHostJar("test/repo", null, cacheDir);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Path, UpgradeCommand.FetchError>) result).error())
                .isInstanceOf(UpgradeCommand.FetchError.NoRelease.class);
    }

    // ── version / repo ownership (spec §1) ─────────────────────────────────

    @Test
    void versionAndDefaultRepoOwnTheReleaseStreamForThisRepo() {
        assertThat(Version.current()).isEqualTo("0.9.0");
        assertThat(UpgradeCommand.DEFAULT_REPO).isEqualTo("flowcatalyst/flowcatalyst-javalin");
    }

    // ── pure semver helpers ───────────────────────────────────────────────

    @Test
    void semverComparisonAndCleanlinessChecks() {
        assertThat(UpgradeCommand.isCleanSemver("1.2.3")).isTrue();
        assertThat(UpgradeCommand.isCleanSemver("1.2.3-beta")).isFalse();
        assertThat(UpgradeCommand.isCleanSemver("1.2")).isFalse();
        assertThat(UpgradeCommand.compareSemver("1.2.3", "1.2.4")).isLessThan(0);
        assertThat(UpgradeCommand.compareSemver("2.0.0", "1.9.9")).isGreaterThan(0);
        assertThat(UpgradeCommand.compareSemver("1.2.3", "1.2.3")).isZero();
    }
}
