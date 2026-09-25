package io.flowcatalyst.fcdev;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [MavenCentralPgBinaryResolver]: the download-and-cache resolution order
/// that replaces bundling every platform's `embedded-postgres-binaries-*`
/// into the fcdev jar.
class MavenCentralPgBinaryResolverTest {

    private static final String VERSION = "18.4.0";
    private static final byte[] TXZ_BYTES = "not really a txz, just some bytes to round-trip".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cacheDir;

    private HttpServer server;
    private AtomicInteger requestCount;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // ── artifact mapping ─────────────────────────────────────────────────

    @Test
    void mapsSystemAndArchitectureToTheZonkyArtifactId() throws IOException {
        assertThat(MavenCentralPgBinaryResolver.artifactId("Darwin", "aarch64", false)).isEqualTo("embedded-postgres-binaries-darwin-arm64v8");
        assertThat(MavenCentralPgBinaryResolver.artifactId("Darwin", "x86_64", false)).isEqualTo("embedded-postgres-binaries-darwin-amd64");
        assertThat(MavenCentralPgBinaryResolver.artifactId("Linux", "amd64", false)).isEqualTo("embedded-postgres-binaries-linux-amd64");
        assertThat(MavenCentralPgBinaryResolver.artifactId("Linux", "aarch64", false)).isEqualTo("embedded-postgres-binaries-linux-arm64v8");
        assertThat(MavenCentralPgBinaryResolver.artifactId("Windows", "amd64", false)).isEqualTo("embedded-postgres-binaries-windows-amd64");
    }

    @Test
    void alpineIsOnlyAppendedOnLinux() throws IOException {
        assertThat(MavenCentralPgBinaryResolver.artifactId("Linux", "amd64", true)).isEqualTo("embedded-postgres-binaries-linux-amd64-alpine");
        // Darwin/Windows have no alpine variant published — the flag is ignored there.
        assertThat(MavenCentralPgBinaryResolver.artifactId("Darwin", "x86_64", true)).isEqualTo("embedded-postgres-binaries-darwin-amd64");
    }

    @Test
    void windowsOnArm64IsUnsupported() {
        assertThatThrownBy(() -> MavenCentralPgBinaryResolver.artifactId("Windows", "aarch64", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Windows/aarch64")
                .hasMessageContaining("github.com/zonkyio/embedded-postgres");
    }

    // ── download + cache ─────────────────────────────────────────────────

    @Test
    void firstCallDownloadsAndCachesTheSecondDoesNot() throws Exception {
        startFakeRepo(TXZ_BYTES, sha1Hex(fakeJarBytes(TXZ_BYTES)));
        var resolver = resolver(throwingDelegate(), () -> false);

        try (InputStream in = resolver.getPgBinary("Linux", "amd64")) {
            assertThat(in.readAllBytes()).isEqualTo(TXZ_BYTES);
        }
        assertThat(requestCount.get()).isEqualTo(2); // jar + .sha1

        // Second call: same cache dir, no further network activity.
        try (InputStream in = resolver.getPgBinary("Linux", "amd64")) {
            assertThat(in.readAllBytes()).isEqualTo(TXZ_BYTES);
        }
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void wrongSha1FailsAndLeavesNoPartialFileCached() throws Exception {
        startFakeRepo(TXZ_BYTES, "0".repeat(40)); // deliberately wrong sha1
        var resolver = resolver(throwingDelegate(), () -> false);

        assertThatThrownBy(() -> resolver.getPgBinary("Linux", "amd64"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha1 mismatch");

        Path downloads = cacheDir.resolve("downloads");
        if (Files.exists(downloads)) {
            try (var files = Files.list(downloads)) {
                assertThat(files.toList()).as("no partial/cached file left behind after a sha1 mismatch").isEmpty();
            }
        }
    }

    @Test
    void offlineOverrideIsReturnedVerbatimAndNeverTouchesTheServer(@TempDir Path otherDir) throws Exception {
        startFakeRepo(TXZ_BYTES, sha1Hex(fakeJarBytes(TXZ_BYTES)));
        byte[] overrideBytes = "the operator's own .txz".getBytes(StandardCharsets.UTF_8);
        Path overrideFile = otherDir.resolve("override.txz");
        Files.write(overrideFile, overrideBytes);

        var resolver = new MavenCentralPgBinaryResolver(throwingDelegate(), cacheDir, "http://127.0.0.1:" + server.getAddress().getPort(),
                VERSION, overrideFile, () -> false);

        try (InputStream in = resolver.getPgBinary("Linux", "amd64")) {
            assertThat(in.readAllBytes()).isEqualTo(overrideBytes);
        }
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void classpathDelegateWinsWhenItHasAnArchive() throws Exception {
        startFakeRepo(TXZ_BYTES, sha1Hex(fakeJarBytes(TXZ_BYTES)));
        byte[] classpathBytes = "bundled-on-the-classpath".getBytes(StandardCharsets.UTF_8);
        var resolver = resolver((system, arch) -> new ByteArrayInputStream(classpathBytes), () -> false);

        try (InputStream in = resolver.getPgBinary("Linux", "amd64")) {
            assertThat(in.readAllBytes()).isEqualTo(classpathBytes);
        }
        assertThat(requestCount.get()).isZero();
    }

    /// A mirror that sends headers and then stalls the body must fail the download,
    /// not hold a first `fcdev start` forever (the JDK request timeout stops once
    /// headers arrive). Mutant: the plain blocking send — the test times out.
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void aStalledBodyFailsTheDownloadWithinItsDeadline() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 10_000_000);
            exchange.getResponseBody().write(new byte[16]);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(60_000); // never finishes the body
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        var resolver = new MavenCentralPgBinaryResolver(throwingDelegate(), cacheDir,
                "http://127.0.0.1:" + server.getAddress().getPort(), VERSION, null, () -> false,
                java.time.Duration.ofSeconds(2));

        long t0 = System.nanoTime();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.getPgBinary("Linux", "amd64"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish within");
        assertThat((System.nanoTime() - t0) / 1_000_000).as("bounded by the deadline").isLessThan(15_000);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private MavenCentralPgBinaryResolver resolver(io.zonky.test.db.postgres.embedded.PgBinaryResolver delegate,
                                                    java.util.function.BooleanSupplier alpine) {
        return new MavenCentralPgBinaryResolver(delegate, cacheDir, "http://127.0.0.1:" + server.getAddress().getPort(),
                VERSION, null, alpine);
    }

    private static io.zonky.test.db.postgres.embedded.PgBinaryResolver throwingDelegate() {
        // Mirrors DefaultPostgresBinaryResolver: an unchecked IllegalStateException
        // when nothing is bundled, despite the checked `throws IOException` signature.
        return (system, arch) -> {
            throw new IllegalStateException("Missing embedded postgres binaries");
        };
    }

    /// Starts a local HTTP server serving the fake jar at `.../<artifact>/<version>/<artifact>-<version>.jar`
    /// and `sha1Body` at the same path plus `.sha1`, for any artifact id (matched by suffix), counting every
    /// request handled.
    private void startFakeRepo(byte[] txzBytes, String sha1Body) throws IOException {
        byte[] jarBytes = fakeJarBytes(txzBytes);
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] body;
            if (exchange.getRequestURI().getPath().endsWith(".sha1")) {
                body = sha1Body.getBytes(StandardCharsets.UTF_8);
            } else if (exchange.getRequestURI().getPath().endsWith(".jar")) {
                body = jarBytes;
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    private static byte[] fakeJarBytes(byte[] txzBytes) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var jos = new JarOutputStream(baos)) {
            jos.putNextEntry(new JarEntry("postgres-linux-x86_64.txz"));
            jos.write(txzBytes);
            jos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static String sha1Hex(byte[] data) throws Exception {
        var digest = MessageDigest.getInstance("SHA-1");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
