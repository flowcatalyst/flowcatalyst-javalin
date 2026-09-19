package io.flowcatalyst.fnhost;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// P9 (`docs/spec/function-host-process.md` §4): the exec jar actually
/// starts, standalone — `java -jar target/*-exec.jar` against a fake
/// platform (a loopback `HttpServer` answering the token and control-plane
/// routes), reaching `/ready` 200. No new Maven plugin: this is a plain
/// surefire test that skips itself (a JUnit assumption, not a failure) when
/// the exec jar hasn't been built — `make fnhost-smoke` = `package` then
/// this test.
class FnHostSmokeTest {

    @Test
    void execJarStartsAndReachesReadyAgainstAFakePlatform() throws Exception {
        Path jar = findExecJar();
        assumeTrue(jar != null, "no function-host exec jar under target/ — run `make fnhost-smoke` "
                + "(mvn -pl function-host -am -DskipTests package) first; this test is skipped, not failed, when it's absent");

        Path cacheDir = Files.createTempDirectory("fnhost-smoke-cache-");
        try (FakePlatform platform = FakePlatform.start()) {
            Process process = launch(jar, platform.baseUrl(), cacheDir);
            try {
                boolean ready = awaitReady(process, Duration.ofSeconds(60));
                assertThat(ready).as("the exec jar's /ready never reached 200 — see captured output in the failure log")
                        .isTrue();
            } finally {
                process.destroy();
                process.waitFor(10, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }

    private static Path findExecJar() throws IOException {
        Path targetDir = Path.of("target");
        if (!Files.isDirectory(targetDir)) {
            return null;
        }
        try (var stream = Files.list(targetDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith("-exec.jar")).findFirst().orElse(null);
        }
    }

    private static Process launch(Path jar, String platformUrl, Path cacheDir) throws IOException {
        List<String> command = List.of(
                System.getProperty("java.home") + "/bin/java",
                "--enable-preview",
                "-jar", jar.toString());
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("FC_FN_PLATFORM_URL", platformUrl);
        pb.environment().put("FC_FN_CLIENT_ID", "smoke-client");
        pb.environment().put("FC_FN_CLIENT_SECRET", "smoke-secret");
        pb.environment().put("FC_FN_PORT", "0");
        pb.environment().put("FC_METRICS_PORT", "0");
        // Signatures.resolve refuses FC_FN_SIGNATURES=off outside dev mode (a deliberate
        // safety rail — see HostEnv#load) — this is a smoke test against a fake platform
        // with no real signing to verify, so both are needed together.
        pb.environment().put("FC_FN_SIGNATURES", "off");
        pb.environment().put("FLOWCATALYST_DEV_MODE", "true");
        pb.environment().put("FC_FN_CACHE_DIR", cacheDir.toString());
        return pb.start();
    }

    /// Polls the launched process's own stdout/stderr for the "function host
    /// started" line to learn its bound metrics port, then polls that port's
    /// `/ready` until 200 or `timeout` elapses. The process's stdout is JSON
    /// lines (`Logging.init`'s default format); reading it is simpler and
    /// more robust than guessing the port ahead of time (`FC_METRICS_PORT=0`).
    private static boolean awaitReady(Process process, Duration timeout) throws IOException, InterruptedException {
        StringBuilder captured = new StringBuilder();
        Integer metricsPort = null;
        var reader = process.inputReader(StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (metricsPort == null && System.nanoTime() < deadline) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                captured.append(line).append('\n');
                if (line.contains("function host started")) {
                    metricsPort = extractMetricsPort(line);
                }
            } else {
                Thread.sleep(50);
            }
            if (!process.isAlive()) {
                throw new AssertionError("the exec jar process exited before starting:\n" + captured);
            }
        }
        if (metricsPort == null) {
            throw new AssertionError("never saw a \"function host started\" line within " + timeout + ":\n" + captured);
        }

        HttpClient http = HttpClient.newHttpClient();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + metricsPort + "/ready"))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return true;
                }
            } catch (IOException ignored) {
                // not listening yet
            }
            Thread.sleep(100);
        }
        return false;
    }

    /// Matches the field however `Logging`'s configured format renders it —
    /// JSON (`"metrics_port":"9123"`) or the TEXT/logfmt default
    /// (`metrics_port="9123"`).
    private static Integer extractMetricsPort(String logLine) {
        var m = java.util.regex.Pattern.compile("\"?metrics_port\"?\\s*[:=]\\s*\"?(\\d+)\"?").matcher(logLine);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /// A loopback `HttpServer` answering exactly the two routes
    /// [io.flowcatalyst.fnhost.reconcile.TokenSource] and
    /// [io.flowcatalyst.fnhost.reconcile.HttpControlPlane] need: a token mint
    /// and a desired-state fetch — enough for the reconciler's first cycle to
    /// SUCCEED (an empty document), which is what `/ready` 200 requires.
    private static final class FakePlatform implements AutoCloseable {
        private final HttpServer server;

        private FakePlatform(HttpServer server) {
            this.server = server;
        }

        static FakePlatform start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/oauth/token", ex -> {
                byte[] body = "{\"access_token\":\"smoke-token\",\"expires_in\":3600}"
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            server.createContext("/control/functions/desired-state", ex -> {
                byte[] body = "{\"functions\":[],\"unload\":[]}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("ETag", "smoke-etag-1");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            server.createContext("/control/functions/heartbeat", ex -> {
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            server.start();
            return new FakePlatform(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
