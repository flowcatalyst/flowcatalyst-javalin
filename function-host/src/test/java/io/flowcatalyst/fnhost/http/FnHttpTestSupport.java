package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.load.FixtureJars;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.reconcile.ControlPlane;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.reconcile.FakeControlPlane;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.shared.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/// Test support shared by the listener's own tests (spec
/// `function-host-listener.md` §6): real HTTP against a real [FnHttpServer],
/// fixture functions built at test time through D1's [FixtureJars] (never a
/// binary committed to the repo), a [FakeControlPlane]-fed [Reconciler].
final class FnHttpTestSupport {

    static final FunctionAddress ADDR_A = FunctionAddress.parse("h.svc.a");
    static final FunctionAddress ADDR_B = FunctionAddress.parse("h.svc.b");

    private FnHttpTestSupport() {
    }

    static final class Harness implements AutoCloseable {
        final Path dir;
        final FakeControlPlane controlPlane;
        final FunctionRegistry registry;
        final Reconciler reconciler;
        final FnHttpServer server;
        final HttpClient http = HttpClient.newHttpClient();

        private Harness(Path dir, FakeControlPlane controlPlane, FunctionRegistry registry, Reconciler reconciler,
                         FnHttpServer server) {
            this.dir = dir;
            this.controlPlane = controlPlane;
            this.registry = registry;
            this.reconciler = reconciler;
            this.server = server;
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.port() + path;
        }

        HttpResponse<byte[]> send(HttpRequest request) {
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        HttpResponse<byte[]> get(String path, String... headers) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(25)).GET();
            addHeaders(b, headers);
            return send(b.build());
        }

        HttpResponse<byte[]> post(String path, byte[] body, String... headers) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            addHeaders(b, headers);
            return send(b.build());
        }

        private static void addHeaders(HttpRequest.Builder b, String[] headers) {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
        }

        /// Replaces the desired-state document the fake control plane serves
        /// and re-runs the reconciler once, so the change takes effect.
        void publish(DesiredDocument doc) {
            controlPlane.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed(
                    "etag-" + java.util.UUID.randomUUID(), doc));
            reconciler.reconcileOnce(Instant.now());
        }

        @Override
        public void close() {
            server.close(Duration.ofSeconds(3));
        }
    }

    static Harness start(Path dir, DesiredDocument initialDoc) {
        return start(dir, initialDoc, 512, 50);
    }

    static Harness start(Path dir, DesiredDocument initialDoc, int maxConcurrency, int maxLoaded) {
        return start(dir, initialDoc, maxLoaded, FnHttpServer.Options.of(0, maxConcurrency, "http://127.0.0.1:1"));
    }

    static Harness start(Path dir, DesiredDocument initialDoc, int maxLoaded, FnHttpServer.Options options) {
        FakeControlPlane controlPlane = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(maxLoaded);
        Reconciler reconciler = new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        controlPlane.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1", initialDoc));
        reconciler.reconcileOnce(Instant.now());
        FnHttpServer server = FnHttpServer.start(reconciler, options);
        return new Harness(dir, controlPlane, registry, reconciler, server);
    }

    // ── fixture jars ─────────────────────────────────────────────────────

    static Path functionJar(Path dir, String name, String className, String source) {
        Path jar = dir.resolve(name + ".jar");
        FixtureJars.builder().source(className, source).build(jar);
        return jar;
    }

    static Digest digestOf(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            return Digest.parse("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String fileRef(Path jar) {
        return jar.toUri().toString();
    }

    // ── manifest / entry builders ────────────────────────────────────────

    static Manifest manifest(String pool, boolean warm, int maxConcurrency, int maxDurationMs, String entrypoint,
                              String endpointsJson) {
        String json = """
                {"runtime":"jvm","entrypoint":"%s","pool":"%s","warm":%s,
                 "limits":{"maxDurationMs":%d,"maxConcurrency":%d},
                 "endpoints":%s}
                """.formatted(entrypoint, pool, warm, maxDurationMs, maxConcurrency, endpointsJson);
        return Manifest.readStored(Json.MAPPER.readTree(json));
    }

    static DesiredDocument.Entry liveEntry(FunctionAddress address, String functionId, String versionId, int version,
                                            Path jar, Manifest manifest, String webhookSigningSecret,
                                            String applicationId, String clientId) {
        return new DesiredDocument.Entry(address, functionId, versionId, version, DesiredDocument.Role.LIVE,
                DesiredDocument.Mode.LAZY, digestOf(jar), fileRef(jar), null, null, manifest, webhookSigningSecret,
                applicationId, clientId, Map.of(), Map.of(), List.of(), List.of());
    }

    static DesiredDocument.Entry warmEntry(FunctionAddress address, String functionId, String versionId, int version,
                                            Path jar, Manifest manifest, String webhookSigningSecret,
                                            String applicationId, String clientId) {
        return new DesiredDocument.Entry(address, functionId, versionId, version, DesiredDocument.Role.LIVE,
                DesiredDocument.Mode.WARM, digestOf(jar), fileRef(jar), null, null, manifest, webhookSigningSecret,
                applicationId, clientId, Map.of(), Map.of(), List.of(), List.of());
    }

    static DesiredDocument.Entry candidateEntry(FunctionAddress address, String functionId, String versionId,
                                                 int version, Path jar, Manifest manifest, String applicationId,
                                                 String clientId) {
        return new DesiredDocument.Entry(address, functionId, versionId, version, DesiredDocument.Role.CANDIDATE,
                DesiredDocument.Mode.LAZY, digestOf(jar), fileRef(jar), null, null, manifest, null, applicationId,
                clientId, Map.of(), Map.of(), List.of(), List.of());
    }

    /// package J3 (`function-zones-and-aliases.md` §5): a version pointed at
    /// ONLY by named aliases — served (unlike a candidate), always lazy.
    static DesiredDocument.Entry aliasEntry(FunctionAddress address, String functionId, String versionId,
                                             int version, Path jar, Manifest manifest, String applicationId,
                                             String clientId, List<String> aliases) {
        return new DesiredDocument.Entry(address, functionId, versionId, version, DesiredDocument.Role.ALIAS,
                DesiredDocument.Mode.LAZY, digestOf(jar), fileRef(jar), null, null, manifest, null, applicationId,
                clientId, Map.of(), Map.of(), List.of(), aliases);
    }

    static DesiredDocument oneFunction(DesiredDocument.Entry entry) {
        return new DesiredDocument(List.of(entry), List.of(), List.of());
    }

    /// F6-F9/F11: a document whose top-level `publicRoutes` names `entry`
    /// under `(hostname, pathPrefix)` — the public listener's own route
    /// table is built from exactly this list (spec `function-public-routes.md`
    /// §3).
    static DesiredDocument oneFunctionWithPublicRoute(DesiredDocument.Entry entry, String hostname,
                                                       String pathPrefix) {
        return new DesiredDocument(List.of(entry), List.of(), List.of(),
                List.of(new DesiredDocument.PublicRouteRef(hostname, pathPrefix, entry.address(), List.of())));
    }

    /// Same, with opt-in `aliasPrefixes` on the route (package J3, spec
    /// `function-zones-and-aliases.md` §3).
    static DesiredDocument oneFunctionWithPublicRoute(DesiredDocument.Entry entry, String hostname,
                                                       String pathPrefix, List<String> aliasPrefixes) {
        return new DesiredDocument(List.of(entry), List.of(), List.of(),
                List.of(new DesiredDocument.PublicRouteRef(hostname, pathPrefix, entry.address(), aliasPrefixes)));
    }

    /// Same, with several `(hostname, pathPrefix)` pairs for ONE entry —
    /// prefix-overlap / longest-wins fixtures.
    static DesiredDocument oneFunctionWithPublicRoutes(DesiredDocument.Entry entry,
                                                        List<DesiredDocument.PublicRouteRef> routes) {
        return new DesiredDocument(List.of(entry), List.of(), List.of(), routes);
    }

    /// A fixture bug class of its own (H6's own finding: two entries sharing one
    /// `versionId` raced for the SAME `prepared` slot in the reconciler and hung a run one
    /// time in three) — `versionId` keys `Reconciler`'s own `prepared` map regardless of
    /// address/version, so two entries of a multi-entry document must never share one.
    /// Fails loudly here rather than letting the bug resurface as a flake somewhere else.
    static DesiredDocument document(List<DesiredDocument.Entry> entries) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DesiredDocument.Entry entry : entries) {
            if (!seen.add(entry.versionId())) {
                throw new IllegalArgumentException(
                        "fixture bug: two entries of this document share versionId '" + entry.versionId()
                                + "' — Reconciler#prepared is keyed by it alone, so they would race for one slot");
            }
        }
        return new DesiredDocument(entries, List.of(), List.of());
    }

    static tools.jackson.databind.JsonNode json(byte[] body) {
        return Json.MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
    }
}
