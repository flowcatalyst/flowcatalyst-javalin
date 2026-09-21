package io.flowcatalyst.fnhost.reconcile;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.artifact.ArtifactException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [PlatformArtifactStore] against a fake platform (spec
/// `function-artifact-upload.md` §5, U12) — a real local [HttpServer], same
/// convention as `HttpControlPlaneTest`/`OciArtifactStoreTest`: no mocked
/// `java.net.http.HttpClient`. Every scenario runs the SAME
/// [io.flowcatalyst.platform.function.artifact.ArtifactStoreSupport] cache
/// path [io.flowcatalyst.platform.function.artifact.FileArtifactStore] uses
/// — this class asserts what lands on disk, not just the return value.
class PlatformArtifactStoreTest {

    private static final String REF = "platform://fnc_1/deadbeef";
    private static final byte[] REAL_BYTES = "the real artifact bytes for U12".getBytes(StandardCharsets.UTF_8);

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

    private static String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void tokenContext(HttpServer server, AtomicInteger mints) {
        server.createContext("/oauth/token", exchange -> {
            mints.incrementAndGet();
            respondJson(exchange, 200, "{\"access_token\":\"tok-" + mints.get() + "\",\"expires_in\":3600}");
        });
    }

    private static Digest digestOf(byte[] bytes) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return new Digest("sha256:" + HexFormat.of().formatHex(sha256.digest(bytes)));
    }

    private static Path cachedPath(Path cacheDir, Digest digest) {
        return cacheDir.resolve("sha256").resolve(digest.value().substring("sha256:".length()));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ── happy path ───────────────────────────────────────────────────────

    @Test
    void happyPathLandsTheFileUnderTheCachePathWithTheRightBytes(@TempDir Path cacheDir) throws Exception {
        Digest expected = digestOf(REAL_BYTES);
        HttpServer server = startServer();
        AtomicInteger mints = new AtomicInteger();
        tokenContext(server, mints);
        server.createContext("/control/functions/artifacts/ver-1", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer tok-1");
            respond(exchange, 200, REAL_BYTES);
        });

        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"), cacheDir);

        var fetched = store.fetch(REF, expected, "ver-1");

        assertThat(fetched.file()).isEqualTo(cachedPath(cacheDir, expected));
        assertThat(Files.readAllBytes(fetched.file())).isEqualTo(REAL_BYTES);
        assertThat(fetched.bytes()).isEqualTo(REAL_BYTES.length);
    }

    /// A SECOND fetch (a different digest/version, same store) must reuse the
    /// cached token rather than minting again — distinguishes "use the
    /// cached token, refresh only after a 401" from a bug that refreshes on
    /// every call, which a single-fetch scenario cannot tell apart (both
    /// mint once on the very first request). Mutant: refresh on every call.
    @Test
    void aSecondFetchReusesTheCachedTokenNoNewMint(@TempDir Path cacheDir) throws Exception {
        byte[] otherBytes = "a second, different artifact".getBytes(StandardCharsets.UTF_8);
        Digest expected1 = digestOf(REAL_BYTES);
        Digest expected2 = digestOf(otherBytes);
        HttpServer server = startServer();
        AtomicInteger mints = new AtomicInteger();
        tokenContext(server, mints);
        server.createContext("/control/functions/artifacts/ver-1a", exchange -> respond(exchange, 200, REAL_BYTES));
        server.createContext("/control/functions/artifacts/ver-1b", exchange -> respond(exchange, 200, otherBytes));

        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"), cacheDir);

        store.fetch(REF, expected1, "ver-1a");
        store.fetch(REF, expected2, "ver-1b");

        assertThat(mints.get()).as("mutant: refresh on every call").isEqualTo(1);
    }

    // ── tampered response ⇒ DigestMismatch, nothing left behind ────────────

    /// Mutant: skip the digest recompute (trust the platform's bytes).
    @Test
    void aTamperedResponseIsDigestMismatchAndLeavesNothingBehind(@TempDir Path cacheDir) throws Exception {
        byte[] tampered = "these are NOT the bytes the digest names".getBytes(StandardCharsets.UTF_8);
        Digest expected = digestOf(REAL_BYTES); // the digest the CALLER expects — not tampered's own hash
        HttpServer server = startServer();
        tokenContext(server, new AtomicInteger());
        server.createContext("/control/functions/artifacts/ver-2", exchange -> respond(exchange, 200, tampered));

        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"), cacheDir);

        assertThatThrownBy(() -> store.fetch(REF, expected, "ver-2"))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason())
                        .as("mutant: skip the digest recompute").isInstanceOf(ArtifactException.DigestMismatch.class));

        assertThat(cachedPath(cacheDir, expected))
                .as("mutant: store the tampered bytes anyway").doesNotExist();
        // no leftover temp file either — the cache dir must be otherwise empty
        try (var files = Files.list(cacheDir.resolve("sha256"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    // ── 401 refresh-once ─────────────────────────────────────────────────

    /// Mutant: refresh on every call, or never refresh at all.
    @Test
    void a401RefreshesTheTokenExactlyOnceThenSucceeds(@TempDir Path cacheDir) throws Exception {
        Digest expected = digestOf(REAL_BYTES);
        HttpServer server = startServer();
        AtomicInteger mints = new AtomicInteger();
        tokenContext(server, mints);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/control/functions/artifacts/ver-3", exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                respondJson(exchange, 401, "{}");
            } else {
                respond(exchange, 200, REAL_BYTES);
            }
        });

        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"), cacheDir);

        var fetched = store.fetch(REF, expected, "ver-3");

        assertThat(Files.readAllBytes(fetched.file())).isEqualTo(REAL_BYTES);
        assertThat(calls.get()).as("mutant: retry more than once, or never retry").isEqualTo(2);
        assertThat(mints.get()).as("a 401 must trigger exactly one refresh mint").isEqualTo(2);
    }

    /// Mutant: keep retrying past a second consecutive 401 instead of failing.
    @Test
    void a401TwiceFailsAndNeverLeaksTheTokenOrSecret(@TempDir Path cacheDir) throws Exception {
        String secret = "u12-secret-must-not-leak-anywhere";
        Digest expected = digestOf(REAL_BYTES);
        HttpServer server = startServer();
        AtomicInteger mints = new AtomicInteger();
        server.createContext("/oauth/token", exchange -> {
            mints.incrementAndGet();
            respondJson(exchange, 200, "{\"access_token\":\"u12-token-must-not-leak-" + mints.get() + "\",\"expires_in\":3600}");
        });
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/control/functions/artifacts/ver-4", exchange -> {
            calls.incrementAndGet();
            respondJson(exchange, 401, "{}");
        });

        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", secret), cacheDir);

        assertThatThrownBy(() -> store.fetch(REF, expected, "ver-4"))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason())
                        .isInstanceOf(ArtifactException.Unauthorized.class))
                .satisfies(e -> {
                    assertThat(e.getMessage().toLowerCase(Locale.ROOT))
                            .as("mutant: leak the secret in the exception message").doesNotContain(secret.toLowerCase(Locale.ROOT));
                    assertThat(e.getMessage()).as("mutant: leak the token in the exception message")
                            .doesNotContain("u12-token-must-not-leak");
                });

        assertThat(calls.get()).as("mutant: retry more than once on a second consecutive 401").isEqualTo(2);
        assertThat(mints.get()).as("exactly one refresh — no retry loop").isEqualTo(2);
        assertThat(cachedPath(cacheDir, expected)).doesNotExist();
    }

    // ── over the cap ─────────────────────────────────────────────────────

    /// Mutant: ignore `maxBytes` and store the oversized response anyway.
    @Test
    void aResponseOverTheCapIsAbandoned(@TempDir Path cacheDir) throws Exception {
        byte[] big = "0123456789".repeat(5).getBytes(StandardCharsets.UTF_8); // 50 bytes
        Digest expected = digestOf(big);
        HttpServer server = startServer();
        tokenContext(server, new AtomicInteger());
        server.createContext("/control/functions/artifacts/ver-5", exchange -> respond(exchange, 200, big));

        // maxBytes well under the response size — declared Content-Length alone must abandon it.
        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"), cacheDir, 10);

        assertThatThrownBy(() -> store.fetch(REF, expected, "ver-5"))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason())
                        .as("mutant: ignore maxBytes").isInstanceOf(ArtifactException.TooLarge.class));

        assertThat(cachedPath(cacheDir, expected)).doesNotExist();
    }

    // ── two-arg fetch on a platform:// ref ⇒ a clear exception, never a guess ──

    /// Mutant: fall back to some default version, or silently succeed.
    @Test
    void twoArgFetchWithNoVersionIdIsAClearException(@TempDir Path cacheDir) throws Exception {
        Digest expected = digestOf(REAL_BYTES);
        HttpServer server = startServer();
        tokenContext(server, new AtomicInteger());
        // No handler registered for the download route at all — a correct implementation
        // never even tries to reach it without a version id.

        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"), cacheDir);

        assertThatThrownBy(() -> store.fetch(REF, expected))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason())
                        .as("mutant: guess/default a version instead of raising a clear reason")
                        .isInstanceOf(ArtifactException.VersionRequired.class));
    }

    // ── R9: never logged ────────────────────────────────────────────────

    @Test
    void theSecretAndMintedTokenNeverAppearInALogLine(@TempDir Path cacheDir) throws Exception {
        String secret = "u12-log-secret-must-not-leak";
        Digest expected = digestOf(REAL_BYTES);
        HttpServer server = startServer();
        AtomicInteger mints = new AtomicInteger();
        server.createContext("/oauth/token", exchange -> {
            mints.incrementAndGet();
            respondJson(exchange, 200, "{\"access_token\":\"u12-log-token-must-not-leak-" + mints.get() + "\",\"expires_in\":3600}");
        });
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/control/functions/artifacts/ver-6", exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                respondJson(exchange, 401, "{}");
            } else {
                respond(exchange, 200, REAL_BYTES);
            }
        });

        TokenSource tokenSource = new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", secret);
        PlatformArtifactStore store = new PlatformArtifactStore(HttpClient.newHttpClient(), baseUrl(server), tokenSource, cacheDir);

        List<ILoggingEvent> events = captureLogs(() -> {
            try {
                store.fetch(REF, expected, "ver-6");
            } catch (ArtifactException e) {
                throw new RuntimeException(e);
            }
        });

        for (ILoggingEvent event : events) {
            String rendered = renderForLeakCheck(event);
            assertThat(rendered.toLowerCase(Locale.ROOT)).doesNotContain(secret.toLowerCase(Locale.ROOT));
            assertThat(rendered).doesNotContain("u12-log-token-must-not-leak");
        }
        assertThat(store.toString()).doesNotContain(secret);
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        ch.qos.logback.classic.Logger loggerA = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TokenSource.class);
        ch.qos.logback.classic.Logger loggerB = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlatformArtifactStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        var previousA = loggerA.getLevel();
        var previousB = loggerB.getLevel();
        loggerA.setLevel(ch.qos.logback.classic.Level.TRACE);
        loggerB.setLevel(ch.qos.logback.classic.Level.TRACE);
        loggerA.addAppender(appender);
        loggerB.addAppender(appender);
        try {
            action.run();
        } finally {
            loggerA.detachAppender(appender);
            loggerB.detachAppender(appender);
            loggerA.setLevel(previousA);
            loggerB.setLevel(previousB);
        }
        return List.copyOf(appender.list);
    }

    private static String renderForLeakCheck(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(event.getFormattedMessage());
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(kv -> sb.append(' ').append(kv.key).append('=').append(kv.value));
        }
        var throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            sb.append(' ').append(throwableProxy.getMessage());
        }
        return sb.toString();
    }
}
