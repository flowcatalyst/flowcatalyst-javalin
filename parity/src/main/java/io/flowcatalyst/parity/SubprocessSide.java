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

/// A `fc-server` subprocess (parity-harness spec §1), generalised from the
/// original `GoSide` so the same launch/health/stop machinery drives the Go
/// binary and the Rust `fc-server` binary alike: stdout/stderr to
/// `<label>.log`, readiness = `GET /health` = 200 within 60 s, stop =
/// SIGTERM (`Process.destroy()`), then `destroyForcibly` if it has not
/// exited.
///
/// `label` is used only in log messages and exceptions (`"go"`, `"rust"`,
/// …) — the class has no other per-binary behaviour, since both fc-servers
/// speak the exact same startup contract (env-configured port, `/health`).
public final class SubprocessSide implements Side {

    private static final Logger LOG = LoggerFactory.getLogger(SubprocessSide.class);
    private static final Duration HEALTH_BUDGET = Duration.ofSeconds(60);
    private static final Duration STOP_GRACE = Duration.ofSeconds(15);

    private final String label;
    private final Process process;
    private final String baseUrl;
    private final Duration startDuration;
    private Duration stopDuration = Duration.ZERO;

    private SubprocessSide(String label, Process process, String baseUrl, Duration startDuration) {
        this.label = label;
        this.process = process;
        this.baseUrl = baseUrl;
        this.startDuration = startDuration;
    }

    /// Starts `serverBinary` labelled `"go"` — the shape every pre-existing
    /// call site (`Seed`, `Parity`) already used before this class was
    /// generalised from `GoSide`. Prefer [#start(String, Path, Map, Path)]
    /// for a non-Go binary so logs and error messages name it correctly.
    public static SubprocessSide start(Path serverBinary, Map<String, String> env, Path logFile) {
        return start("go", serverBinary, env, logFile);
    }

    /// [#start(String, String, Path, Map, Path)] with the host every
    /// pre-existing call site (Go, Java) already used: `"127.0.0.1"`.
    public static SubprocessSide start(String label, Path serverBinary, Map<String, String> env, Path logFile) {
        return start(label, "127.0.0.1", serverBinary, env, logFile);
    }

    /// Starts `serverBinary` with `env` overlaid onto the harness process's
    /// own environment (`putAll`, not a replacement — a subprocess still
    /// needs its normal runtime environment, `PATH`/`HOME`/`TMPDIR`, none of
    /// which the harness's `env` map carries), a freshly-picked
    /// `FC_API_PORT`, an ephemeral `FC_METRICS_PORT`, and
    /// `FC_JWT_ISSUER` / `FC_EXTERNAL_BASE_URL` / `FC_WEBAUTHN_ORIGINS` set to
    /// this side's own base URL, built from `host` (spec §2: "each side's
    /// own", "origin differs per side"). `host` exists because Rust's
    /// `webauthn-rs` (unlike Go's/Java's webauthn stacks) rejects a bare-IP
    /// origin against `FC_WEBAUTHN_RP_ID` — the Rust caller passes
    /// `"localhost"` and sets a matching RP ID; every other call site keeps
    /// `"127.0.0.1"` via the 4-arg overload above, unchanged. Blocks until
    /// `/health` answers 200 or [#HEALTH_BUDGET] elapses.
    ///
    /// @throws IllegalStateException the binary never became healthy; `logFile` holds its stderr/stdout
    public static SubprocessSide start(String label, String host, Path serverBinary, Map<String, String> env, Path logFile) {
        int port = freePort();
        String baseUrl = "http://" + host + ":" + port;

        Map<String, String> merged = new LinkedHashMap<>(env);
        merged.put("FC_API_PORT", String.valueOf(port));
        // An ephemeral metrics listener, as JavaSide has: the binary's
        // default metrics port collides with any fcdev/fc-server already
        // running on the machine, and fc-server exits when a listener
        // fails to bind.
        merged.put("FC_METRICS_PORT", "0");
        merged.put("FC_JWT_ISSUER", baseUrl);
        merged.put("FC_EXTERNAL_BASE_URL", baseUrl);
        merged.put("FC_WEBAUTHN_ORIGINS", baseUrl);

        ProcessBuilder pb = new ProcessBuilder(serverBinary.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.environment().putAll(merged);

        Instant t0 = Instant.now();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("start " + serverBinary, e);
        }
        boolean healthy = pollHealth(baseUrl);
        Duration startDuration = Duration.between(t0, Instant.now());
        if (!healthy) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    label + " fc-server did not answer GET " + baseUrl + "/health with 200 within " + HEALTH_BUDGET
                            + " — see " + logFile);
        }
        LOG.info("{} fc-server healthy at {} after {}", label, baseUrl, startDuration);
        return new SubprocessSide(label, process, baseUrl, startDuration);
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    public String label() {
        return label;
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
                LOG.warn("{} fc-server did not exit within {} of SIGTERM; killing", label, STOP_GRACE);
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
