package io.flowcatalyst.platform.function.artifact;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.function.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Exercises [OciArtifactStore] against real local [HttpServer]s (spec
/// `function-artifacts.md` §2.2) — no mocked [java.net.http.HttpClient].
class OciArtifactStoreTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    private HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.start();
        servers.add(server);
        return server;
    }

    /// C3: `maxBytes` stops the transfer — a source that would stream far
    /// past the cap is cut off well before it finishes, not merely rejected
    /// after fully downloading (the mutant this pins: "check the size only
    /// after the download").
    @Test
    void maxBytesAbortsAChunkedTransferEarly(@TempDir Path cacheDir) throws Exception {
        AtomicLong bytesWritten = new AtomicLong();
        long totalIfUnbounded = 50L * 1024 * 1024; // 50 MiB — never actually written if the abort works
        HttpServer server = startServer();
        server.createContext("/v2/acme/thing/blobs/", exchange -> {
            exchange.getResponseHeaders().set("Transfer-Encoding", "chunked"); // no Content-Length: forces the streaming check
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                for (long written = 0; written < totalIfUnbounded; written += chunk.length) {
                    out.write(chunk);
                    bytesWritten.addAndGet(chunk.length);
                }
            } catch (IOException e) {
                // expected once the client aborts the connection
            }
        });

        var store = new OciArtifactStore(cacheDir, 1000, RegistryCredentials.none());
        String ref = "oci://localhost:" + server.getAddress().getPort() + "/acme/thing";
        Digest digest = new Digest("sha256:" + "00".repeat(32));

        assertThatThrownBy(() -> store.fetch(ref, digest))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.TooLarge.class));

        // the load-bearing part: the transfer was cut off, not completed. What the
        // SERVER managed to write before the abort reached it is the kernel's
        // business — loopback socket buffers hold several MiB and a loaded machine
        // schedules the writer for a while — so the bound is "nowhere near the whole
        // body", not "close to the cap" (a full reactor run saw > 1 MiB written).
        // The mutant this guards against — size checked only after the download —
        // writes all 50 MiB.
        assertThat(bytesWritten.get()).isLessThan(totalIfUnbounded / 2);
    }

    /// C4: the bearer-token dance (401 + challenge → token → 200) against a
    /// real registry, redirected to a *second* server on a different port —
    /// and the `Authorization` header the first server required is **not**
    /// forwarded to the second.
    @Test
    void bearerTokenIsFetchedAndNotForwardedAcrossARedirect(@TempDir Path cacheDir) throws Exception {
        byte[] artifact = "artifact bytes from the storage backend".getBytes(StandardCharsets.UTF_8);
        Digest digest = digestOf(artifact);
        String expectedToken = "test-bearer-token-123";

        HttpServer storageServer = startServer();
        AtomicLong storageSawAuthorizationHeader = new AtomicLong();
        storageServer.createContext("/blob", exchange -> {
            if (exchange.getRequestHeaders().containsKey("Authorization")) {
                storageSawAuthorizationHeader.incrementAndGet();
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, artifact.length);
            try (var out = exchange.getResponseBody()) {
                out.write(artifact);
            }
        });
        int storagePort = storageServer.getAddress().getPort();

        HttpServer registryServer = startServer();
        int registryPort = registryServer.getAddress().getPort();
        registryServer.createContext("/token", exchange -> {
            String body = "{\"token\":\"" + expectedToken + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        registryServer.createContext("/v2/acme/thing/blobs/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + expectedToken).equals(authorization)) {
                exchange.getResponseHeaders().set("WWW-Authenticate",
                        "Bearer realm=\"http://localhost:" + registryPort + "/token\",service=\"registry\",scope=\"repository:acme/thing:pull\"");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Location", "http://localhost:" + storagePort + "/blob");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        var store = new OciArtifactStore(cacheDir, RegistryCredentials.none());
        String ref = "oci://localhost:" + registryPort + "/acme/thing";
        ArtifactStore.Fetched fetched = store.fetch(ref, digest);

        assertThat(java.nio.file.Files.readAllBytes(fetched.file())).isEqualTo(artifact);
        assertThat(storageSawAuthorizationHeader.get())
                .as("Authorization must never reach the redirect target on another port")
                .isZero();
    }

    @Test
    void tagOrDigestSuffixIsBadRef(@TempDir Path cacheDir) throws Exception {
        var store = new OciArtifactStore(cacheDir, RegistryCredentials.none());
        assertThatThrownBy(() -> store.fetch("oci://ghcr.io/acme/thing:v1", digestOf("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.BadRef.class));
    }

    @Test
    void notFoundIsMappedFromA404(@TempDir Path cacheDir) throws Exception {
        HttpServer server = startServer();
        server.createContext("/v2/acme/thing/blobs/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        var store = new OciArtifactStore(cacheDir, RegistryCredentials.none());
        String ref = "oci://localhost:" + server.getAddress().getPort() + "/acme/thing";
        assertThatThrownBy(() -> store.fetch(ref, digestOf("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.NotFound.class));
    }

    static Digest digestOf(byte[] content) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
        return new Digest("sha256:" + HexFormat.of().formatHex(hash));
    }
}
