package io.flowcatalyst.parity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// The Go `fc-server` subprocess (parity-harness spec §1): stdout/stderr to
/// `go.log`, readiness = `GET /health` = 200 within 60 s, stop = SIGTERM
/// (`Process.destroy()`), then `destroyForcibly` if it has not exited.
public final class GoSide implements Side {

    private static final Logger LOG = LoggerFactory.getLogger(GoSide.class);
    private static final Duration HEALTH_BUDGET = Duration.ofSeconds(60);
    private static final Duration STOP_GRACE = Duration.ofSeconds(15);

    private final Process process;
    private final String baseUrl;
    private final Duration startDuration;
    private Duration stopDuration = Duration.ZERO;

    private GoSide(Process process, String baseUrl, Duration startDuration) {
        this.process = process;
        this.baseUrl = baseUrl;
        this.startDuration = startDuration;
    }

    /// Starts `fcServerBinary` with `env` overlaid onto the harness process's
    /// own environment (`putAll`, not a replacement — a Go binary still needs
    /// its normal runtime environment, `PATH`/`HOME`/`TMPDIR`, none of which
    /// the harness's `env` map carries), a freshly-picked `FC_API_PORT`, and
    /// `FC_JWT_ISSUER` / `FC_EXTERNAL_BASE_URL` / `FC_WEBAUTHN_ORIGINS` set to
    /// this side's own base URL (spec §2: "each side's own", "origin differs
    /// per side"). Blocks until `/health` answers 200 or [#HEALTH_BUDGET]
    /// elapses.
    ///
    /// @throws IllegalStateException Go never became healthy; `logFile` holds its stderr/stdout
    public static GoSide start(Path fcServerBinary, Map<String, String> env, Path logFile) {
        int port = freePort();
        String baseUrl = "http://127.0.0.1:" + port;

        Map<String, String> merged = new LinkedHashMap<>(env);
        merged.put("FC_API_PORT", String.valueOf(port));
        merged.put("FC_JWT_ISSUER", baseUrl);
        merged.put("FC_EXTERNAL_BASE_URL", baseUrl);
        merged.put("FC_WEBAUTHN_ORIGINS", baseUrl);

        ProcessBuilder pb = new ProcessBuilder(fcServerBinary.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.environment().putAll(merged);

        Instant t0 = Instant.now();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("start " + fcServerBinary, e);
        }
        boolean healthy = pollHealth(baseUrl);
        Duration startDuration = Duration.between(t0, Instant.now());
        if (!healthy) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "Go fc-server did not answer GET " + baseUrl + "/health with 200 within " + HEALTH_BUDGET
                            + " — see " + logFile);
        }
        LOG.info("Go fc-server healthy at {} after {}", baseUrl, startDuration);
        return new GoSide(process, baseUrl, startDuration);
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    public Duration startDuration() {
        return startDuration;
    }

    public Duration stopDuration() {
        return stopDuration;
    }

    @Override
    public void stop() {
        if (!process.isAlive()) return;
        Instant t0 = Instant.now();
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                LOG.warn("Go fc-server did not exit within {} of SIGTERM; killing", STOP_GRACE);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopDuration = Duration.between(t0, Instant.now());
    }

    private static boolean pollHealth(String baseUrl) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Instant deadline = Instant.now().plus(HEALTH_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return true;
            } catch (IOException | InterruptedException ignored) {
                // not up yet
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("pick a free port", e);
        }
    }
}
