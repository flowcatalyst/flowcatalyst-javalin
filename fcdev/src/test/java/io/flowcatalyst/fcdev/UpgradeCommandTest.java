package io.flowcatalyst.fcdev;

import com.sun.net.httpserver.HttpServer;
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

    private static final byte[] NEW_JAR_BYTES = "NEW-JAR-BYTES-v0.9.0".getBytes(StandardCharsets.UTF_8);

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
        github.createContext("/download/jar", exchange -> {
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
        shaSidecar.set(sha256Hex(NEW_JAR_BYTES) + "  fcdev-v0.9.0.jar");
    }

    @AfterEach
    void stopGithubStub() {
        github.stop(0);
    }

    private String releasesJson(String base) {
        return """
                [
                  {"tag_name":"fcdev/v0.9.0","draft":false,"prerelease":false,"assets":[
                     {"name":"fcdev-v0.9.0.jar","browser_download_url":"%s/download/jar"},
                     {"name":"fcdev-v0.9.0.jar.sha256","browser_download_url":"%s/download/sha"}
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
        shaSidecar.set("0000000000000000000000000000000000000000000000000000000000000000  fcdev-v0.9.0.jar");
        Path self = Files.createTempFile("fcdev-upgrade-test", ".jar");
        byte[] original = "OLD CONTENT — must survive".getBytes(StandardCharsets.UTF_8);
        Files.write(self, original);

        var cmd = build(self);

        assertThatThrownBy(cmd::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum mismatch");
        assertThat(Files.readAllBytes(self)).as("the self file must not be touched when the checksum fails")
                .isEqualTo(original);
    }

    // ── --check reports without installing ───────────────────────────────

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

        assertThat(release.version()).isEqualTo("0.9.0");
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
