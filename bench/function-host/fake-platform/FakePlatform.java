import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Standalone fake platform for the function-host benchmark
/// (docs/spec/function-host-benchmark.md): a plain `java FakePlatform.java`
/// program (JDK HttpServer, zero dependencies) answering exactly the routes
/// `HostEnv`/`HttpControlPlane` need — `/oauth/token`, `/control/functions/desired-state`
/// (ETag, `pool` query param, reconfigurable N/warm count between runs),
/// `/control/functions/heartbeat` (recorded, inspectable), and a static
/// `/.well-known/jwks.json` (unused here — every bench endpoint's `auth` is
/// `none`, but the host's HostEnv wiring expects the route to exist).
///
/// Grows FnHostSmokeTest's loopback FakePlatform into a real control plane:
/// desired-state entries are built from a directory of pre-stamped, DISTINCT
/// fixture jars (bench/function-host/scripts/gen-artifacts.sh) rather than
/// served empty. Admin routes (`/admin/config`, `/admin/status`,
/// `/admin/heartbeats`) let a run script change N/warm-count/fixture between
/// runs without restarting the container — the host polls every 15s (or the
/// bench forces a faster poll by restarting the container, see README).
///
/// Usage: `java FakePlatform.java <port> <artifactsDir> <containerArtifactsMount>`
///   artifactsDir            host path holding lean/*.jar and typical/*.jar
///                            (gen-artifacts.sh's output directory)
///   containerArtifactsMount the path the SAME directory is mounted at
///                            inside the function-host container (the
///                            artifactRef the host reads back must resolve
///                            there, not on this process's own host path)
public final class FakePlatform {

    record Config(String fixture, int n, int warm, int maxConcurrency, int maxDurationMs, int version,
                   int overrideIndex, int overrideMaxConcurrency) {
        static Config initial() {
            return new Config("lean", 0, 0, 2000, 30000, 0, -1, -1);
        }
    }

    private final Path artifactsDir;
    private final String containerMount;
    private final AtomicReference<Config> config = new AtomicReference<>(Config.initial());
    // digest cache: "fixture/index" -> sha256 hex, invalidated never (files are stamped once, immutable)
    private final Map<String, String> digestCache = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastHeartbeatBody = new AtomicReference<>("(none yet)");
    private final AtomicInteger heartbeatCount = new AtomicInteger();
    private final AtomicLong desiredStateFetches = new AtomicLong();
    private final AtomicLong notModifiedFetches = new AtomicLong();

    private FakePlatform(Path artifactsDir, String containerMount) {
        this.artifactsDir = artifactsDir;
        this.containerMount = containerMount;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        Path artifactsDir = Path.of(args.length > 1 ? args[1] : "bench/function-host/artifacts").toAbsolutePath();
        String containerMount = args.length > 2 ? args[2] : "/artifacts";
        FakePlatform platform = new FakePlatform(artifactsDir, containerMount);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/oauth/token", platform::token);
        server.createContext("/control/functions/desired-state", platform::desiredState);
        server.createContext("/control/functions/heartbeat", platform::heartbeat);
        server.createContext("/control/functions/events", platform::events);
        server.createContext("/.well-known/jwks.json", platform::jwks);
        server.createContext("/admin/config", platform::adminConfig);
        server.createContext("/admin/status", platform::adminStatus);
        server.createContext("/admin/heartbeats", platform::adminHeartbeats);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("fake platform started on port " + port + ", artifacts=" + artifactsDir
                + ", containerMount=" + containerMount);
    }

    // ── control-plane routes ─────────────────────────────────────────────

    private void token(HttpExchange ex) throws IOException {
        byte[] body = "{\"access_token\":\"bench-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        send(ex, 200, body);
    }

    private void desiredState(HttpExchange ex) throws IOException {
        Config c = config.get();
        String etag = "etag-" + c.version();
        String knownEtag = ex.getRequestHeaders().getFirst("If-None-Match");
        desiredStateFetches.incrementAndGet();
        if (etag.equals(knownEtag)) {
            notModifiedFetches.incrementAndGet();
            ex.getResponseHeaders().add("ETag", etag);
            send(ex, 304, new byte[0]);
            return;
        }
        String body = buildDocument(c);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("ETag", etag);
        send(ex, 200, body.getBytes(StandardCharsets.UTF_8));
    }

    private void heartbeat(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        lastHeartbeatBody.set(new String(body, StandardCharsets.UTF_8));
        heartbeatCount.incrementAndGet();
        send(ex, 204, new byte[0]);
    }

    /// `/control/functions/events` (spec `function-context.md` §3) — a
    /// function calling `Events.emit` posts here. Not exercised by B1-B5,
    /// but the route must exist and answer something sane so it never shows
    /// up as a spurious error in a run's logs.
    private void events(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();
        byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        send(ex, 200, body);
    }

    private void jwks(HttpExchange ex) throws IOException {
        byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        send(ex, 200, body);
    }

    // ── admin routes (the bench's own control surface) ───────────────────

    /// `POST /admin/config?fixture=lean|typical&n=NN&warm=WW[&maxConcurrency=MM][&maxDurationMs=DD]
    /// [&overrideIndex=I&overrideMaxConcurrency=M]`
    /// Replaces the served desired-state document and bumps the ETag so the
    /// host's next poll (or a forced reconcile — see README) picks it up.
    /// `overrideIndex`/`overrideMaxConcurrency` (B4, noisy neighbour): gives
    /// exactly ONE entry (by index) a different `limits.maxConcurrency` than
    /// every other entry in the document — B4 needs function A unconstrained
    /// and function B capped at 8 in the SAME desired-state document.
    private void adminConfig(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        Config prev = config.get();
        String fixture = q.getOrDefault("fixture", prev.fixture());
        int n = Integer.parseInt(q.getOrDefault("n", Integer.toString(prev.n())));
        int warm = Integer.parseInt(q.getOrDefault("warm", Integer.toString(prev.warm())));
        int maxConcurrency = Integer.parseInt(q.getOrDefault("maxConcurrency", Integer.toString(prev.maxConcurrency())));
        int maxDurationMs = Integer.parseInt(q.getOrDefault("maxDurationMs", Integer.toString(prev.maxDurationMs())));
        int overrideIndex = Integer.parseInt(q.getOrDefault("overrideIndex", "-1"));
        int overrideMaxConcurrency = Integer.parseInt(q.getOrDefault("overrideMaxConcurrency", "-1"));
        Config next = new Config(fixture, n, warm, maxConcurrency, maxDurationMs, prev.version() + 1,
                overrideIndex, overrideMaxConcurrency);
        config.set(next);
        String body = "{\"fixture\":\"" + next.fixture() + "\",\"n\":" + next.n() + ",\"warm\":" + next.warm()
                + ",\"maxConcurrency\":" + next.maxConcurrency() + ",\"maxDurationMs\":" + next.maxDurationMs()
                + ",\"version\":" + next.version()
                + ",\"overrideIndex\":" + next.overrideIndex() + ",\"overrideMaxConcurrency\":" + next.overrideMaxConcurrency()
                + "}";
        ex.getResponseHeaders().add("Content-Type", "application/json");
        send(ex, 200, body.getBytes(StandardCharsets.UTF_8));
        System.out.println("admin/config -> " + body);
    }

    private void adminStatus(HttpExchange ex) throws IOException {
        Config c = config.get();
        String body = "{\"fixture\":\"" + c.fixture() + "\",\"n\":" + c.n() + ",\"warm\":" + c.warm()
                + ",\"maxConcurrency\":" + c.maxConcurrency() + ",\"maxDurationMs\":" + c.maxDurationMs()
                + ",\"version\":" + c.version()
                + ",\"desiredStateFetches\":" + desiredStateFetches.get()
                + ",\"notModifiedFetches\":" + notModifiedFetches.get()
                + ",\"heartbeatCount\":" + heartbeatCount.get() + "}";
        ex.getResponseHeaders().add("Content-Type", "application/json");
        send(ex, 200, body.getBytes(StandardCharsets.UTF_8));
    }

    private void adminHeartbeats(HttpExchange ex) throws IOException {
        String body = lastHeartbeatBody.get();
        ex.getResponseHeaders().add("Content-Type", "application/json");
        send(ex, 200, body.getBytes(StandardCharsets.UTF_8));
    }

    // ── document building ─────────────────────────────────────────────────

    private String buildDocument(Config c) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"functions\":[");
        String entrypoint = c.fixture().equals("typical") ? "fixture.typical.TypicalFn" : "fixture.lean.LeanFn";
        String manifest = manifestJson(entrypoint, c.maxConcurrency(), c.maxDurationMs());
        String overrideManifest = c.overrideIndex() >= 0
                ? manifestJson(entrypoint, c.overrideMaxConcurrency(), c.maxDurationMs()) : null;
        for (int i = 0; i < c.n(); i++) {
            if (i > 0) sb.append(',');
            String idx = pad(i);
            String digest = digestOf(c.fixture(), idx);
            String address = "bench." + c.fixture() + ".f" + idx;
            String mode = i < c.warm() ? "warm" : "lazy";
            String artifactRef = "file://" + containerMount + "/" + c.fixture() + "/" + c.fixture() + "-" + idx + ".jar";
            String entryManifest = (i == c.overrideIndex()) ? overrideManifest : manifest;
            sb.append("{\"address\":\"").append(address).append('"');
            sb.append(",\"functionId\":\"fnc_").append(c.fixture()).append('_').append(idx).append('"');
            sb.append(",\"versionId\":\"v_").append(c.fixture()).append('_').append(idx).append('"');
            sb.append(",\"version\":1");
            sb.append(",\"role\":\"live\"");
            sb.append(",\"mode\":\"").append(mode).append('"');
            sb.append(",\"digest\":\"sha256:").append(digest).append('"');
            sb.append(",\"artifactRef\":\"").append(artifactRef).append('"');
            sb.append(",\"applicationId\":\"app_bench\"");
            sb.append(",\"manifest\":").append(entryManifest);
            sb.append('}');
        }
        sb.append("],\"unload\":[]}");
        return sb.toString();
    }

    private static String manifestJson(String entrypoint, int maxConcurrency, int maxDurationMs) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"" + entrypoint + "\",\"pool\":\"default\",\"warm\":false,"
                + "\"limits\":{\"maxDurationMs\":" + maxDurationMs + ",\"maxConcurrency\":" + maxConcurrency + "},"
                + "\"endpoints\":[{\"path\":\"/*\",\"auth\":\"none\"}]}";
    }

    private static String pad(int i) {
        return String.format("%03d", i);
    }

    private String digestOf(String fixture, String idx) {
        String key = fixture + "/" + idx;
        return digestCache.computeIfAbsent(key, k -> {
            Path jar = artifactsDir.resolve(fixture).resolve(fixture + "-" + idx + ".jar");
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] bytes = Files.readAllBytes(jar);
                return HexFormat.of().formatHex(md.digest(bytes));
            } catch (Exception e) {
                throw new RuntimeException("cannot digest " + jar, e);
            }
        });
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 && status != 200 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } else {
            ex.close();
        }
    }

    private static final Pattern PAIR = Pattern.compile("([^&=]+)=([^&]*)");

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (raw == null) return out;
        Matcher m = PAIR.matcher(raw);
        while (m.find()) {
            out.put(URLDecoder.decode(m.group(1), StandardCharsets.UTF_8),
                    URLDecoder.decode(m.group(2), StandardCharsets.UTF_8));
        }
        return out;
    }
}
