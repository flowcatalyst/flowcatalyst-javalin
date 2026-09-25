package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Server;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// U13, the end-to-end integration pin (spec `function-artifact-upload.md`
/// §7 U13): a REAL platform `Server` on `TestPg` with a `file://`
/// [io.flowcatalyst.platform.function.artifact.ArtifactBlobStore], a real
/// upload through `PUT /api/functions/{address}/artifacts/{digest}`,
/// published by reference, and a real [Reconciler] whose [ArtifactStore] is
/// [PlatformArtifactStore] ONLY — this host is never given `file://` access
/// to the platform's blob directory, so a successful `LOADED` can only mean
/// the bytes travelled through the download route
/// (`GET /control/functions/artifacts/{versionId}`), not a shared filesystem.
/// Extends the shape of `FunctionHostReconcilerIntegrationTest` (R12) but
/// stays with `Signatures.Off` — this slice's own contribution is the
/// artifact transport, not re-proving signature verification.
@SuppressWarnings("deprecation")
class FunctionHostArtifactUploadIntegrationTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Server.Running running;
    private static String baseUrl;
    private static javax.sql.DataSource DS;
    private static Path blobDir;

    private static final String[] ADMIN = {
            Authenticator.TEST_PRINCIPAL, "prn_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, String.join(",",
                    "platform:function:function:manage", "platform:function:function:view",
                    "platform:function:version:publish", "platform:function:alias:promote",
                    "platform:function:policy:manage", "platform:admin:application:create",
                    // security-fixes S1.2: provisioning a service account and assigning its roles
                    // need these at the anchor tier too (the tier is reach, never authority).
                    "platform:admin:application:update", "platform:iam:service-account:create", "platform:iam:service-account:update",
                    "platform:iam:service-account:view",
                    // The role ceiling (owner ruling 2026-09-25): assigning the host account
                    // platform:application-service and platform:function-host needs their permissions.
                    "platform:application-service:*:*", "platform:function:host:control", "platform:messaging:router:view")
    };

    @BeforeAll
    static void startPlatform(@TempDir Path sharedDir) throws Exception {
        DS = TestPg.newDatabase("fn_host_artifact_upload_integration_test");
        io.flowcatalyst.platform.shared.database.Migrator.migrate(DS);
        new Seeder(DS).run();

        blobDir = sharedDir.resolve("platform-blobs");
        Files.createDirectories(blobDir);

        String appKey = Encryption.generateKey();

        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                "FLOWCATALYST_APP_KEY", appKey,
                "FC_FN_ARTIFACT_STORE", "file://" + blobDir,
                // This slice's own contribution is the artifact transport, not signature
                // verification (already proven by R12) — off on both sides, matching the
                // Reconciler's own Signatures.Off below.
                "FC_FN_SIGNATURES", "off",
                "FLOWCATALYST_DEV_MODE", "true"));

        running = new Server(env, new Server.Mode.Platform(Pools.ofSingle(DS)), Server.Spa.none(),
                new PrometheusRegistry()).start();
        baseUrl = "http://127.0.0.1:" + running.apiPort();
    }

    @AfterAll
    static void stopPlatform() {
        if (running != null) {
            running.stop();
        }
    }

    @Test
    void uploadPublishByReferenceThenHostDownloadsThroughTheControlPlaneAndLoads(@TempDir Path dir) throws Exception {
        DnsLabel pool = new DnsLabel("pool" + RUN);
        FunctionAddress address = FunctionAddress.of(new DnsLabel("au" + RUN), new DnsLabel("svc"), new DnsLabel("fn"));

        // ── function, owned by the platform ──
        FunctionRepository functions = new FunctionRepository(DS);
        UnitOfWork uow = new UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        Application app = createApplication("au-app-" + RUN);
        Function fn = Function.create(app.id(), address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(fn, tx.dbTx());
            return null;
        });

        // ── the host's real service principal ──
        String hostAppCode = "au-host-" + RUN;
        JsonNode hostApp = adminPost("/api/applications",
                obj("code", hostAppCode, "name", "Host " + RUN, "type", "APPLICATION"), 201);
        String hostAppId = hostApp.path("id").asString();
        JsonNode provisioned = adminPost("/api/applications/" + hostAppId + "/provision-service-account", null, 201);
        String hostClientId = provisioned.path("serviceAccount").path("oauthClient").path("clientId").asString();
        String hostClientSecret = provisioned.path("serviceAccount").path("oauthClient").path("clientSecret").asString();
        JsonNode hostServiceAccount = adminGet("/api/service-accounts/code/app:" + hostAppCode);
        String hostServiceAccountId = hostServiceAccount.path("id").asString();
        adminPut("/api/service-accounts/" + hostServiceAccountId + "/roles",
                obj("roles", array("platform:application-service", "platform:function-host")), 200);

        // ── build a real fixture jar and upload it (the route this slice adds) ──
        Path jar = TestFixtures.functionJar(dir, "u13-v1", "u13-v1");
        Digest digest = TestFixtures.digestOf(jar);
        byte[] jarBytes = Files.readAllBytes(jar);

        JsonNode uploadResponse = adminPutFile("/api/functions/" + address.render() + "/artifacts/" + digest.value(), jar, 200);
        String artifactRef = uploadResponse.path("artifactRef").asString();
        assertThat(artifactRef).as("mutant: build a ref locally instead of the upload route's own")
                .isEqualTo("platform://" + fn.id() + "/" + digest.value().substring("sha256:".length()));
        assertThat(uploadResponse.path("digest").asString()).isEqualTo(digest.value());
        assertThat(uploadResponse.path("bytes").asLong()).isEqualTo(jarBytes.length);

        // The blob really is where the platform's OWN store keeps it — proves this test's
        // upload actually reached the configured file:// backend, not a stub.
        Path blobPath = blobDir.resolve(fn.id()).resolve(digest.value().substring("sha256:".length()));
        assertThat(blobPath).exists();
        assertThat(Files.readAllBytes(blobPath)).isEqualTo(jarBytes);

        // ── publish BY REFERENCE — the ref the upload returned, never a locally-built one ──
        JsonNode published = adminPost("/api/functions/" + address.render() + "/versions",
                obj("artifactRef", artifactRef, "digest", digest.value(), "signatureBundle", (String) null,
                        "manifest", obj("runtime", "jvm", "entrypoint", TestFixtures.ENTRYPOINT, "pool", pool.value(), "warm", true)),
                201);
        assertThat(published.path("version").asInt()).isEqualTo(1);
        assertThat(published.path("digest").asString()).isEqualTo(digest.value());
        // VersionResponse's detail GET carries `artifactRef` (PublishResponse itself does
        // not, per the lockfile) — confirm the version really was published against the
        // upload's own ref, not a locally-built one.
        JsonNode versionDetail = adminGet("/api/functions/" + address.render() + "/versions/1");
        assertThat(versionDetail.path("artifactRef").asString()).isEqualTo(artifactRef);

        // ── the host's real Reconciler — PlatformArtifactStore ONLY, no file:// access
        //    to blobDir at all (this test never hands the host that path). ──
        Path hostCacheDir = dir.resolve("host-cache");
        FunctionRegistry registry = new FunctionRegistry(50);
        HttpControlPlane controlPlane = new HttpControlPlane(baseUrl,
                new TokenSource(HTTP, baseUrl, hostClientId, hostClientSecret));
        PlatformArtifactStore platformStore = new PlatformArtifactStore(HTTP, baseUrl,
                new TokenSource(HTTP, baseUrl, hostClientId, hostClientSecret), hostCacheDir);
        Reconciler reconciler = new Reconciler(pool, "host-" + RUN, controlPlane, platformStore,
                new Signatures.Off(), new JvmFunctionLoader(), registry);

        // Cycle 1: v1 is a candidate — prepared (downloaded through the control plane) and
        // verified, never loaded; REGISTERED is enough for the heartbeat to mark it READY.
        reconciler.reconcileOnce(Instant.now());
        assertThat(registry.peek(address)).as("a candidate is never loaded").isNull();

        Path cachedFile = hostCacheDir.resolve("sha256").resolve(digest.value().substring("sha256:".length()));
        assertThat(cachedFile).as("the host's cache must be populated by cycle 1's prepare, through the download route").exists();
        assertThat(Files.readAllBytes(cachedFile)).as("the cached bytes must be exactly the uploaded jar").isEqualTo(jarBytes);

        awaitCondition(() -> readVersionState(address, 1).equals("READY"),
                "the heartbeat's REGISTERED report must mark v1 READY");

        // ── promote v1 ──
        adminPut("/api/functions/" + address.render() + "/aliases/live", obj("version", 1), 200);

        // Cycle 2: v1 is now live+warm — must load eagerly.
        reconciler.reconcileOnce(Instant.now());
        LoadedFunction loaded = registry.peek(address);
        assertThat(loaded).as("mutant: the promoted warm version must load").isNotNull();
        assertThat(loaded.version()).isEqualTo(1);

        JsonNode status = adminGet("/api/functions/" + address.render() + "/status");
        assertThat(status.path("live").path("version").asInt()).isEqualTo(1);
    }

    // ── admin HTTP helpers (TEST_* headers — same convention as FunctionControlApiTest) ──

    private static JsonNode adminPost(String path, ObjectNode body, int expectedStatus) throws Exception {
        return adminSend("POST", path, body, expectedStatus);
    }

    private static JsonNode adminPut(String path, ObjectNode body, int expectedStatus) throws Exception {
        return adminSend("PUT", path, body, expectedStatus);
    }

    private static JsonNode adminGet(String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        addHeaders(b, ADMIN);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body());
    }

    private static JsonNode adminSend(String method, String path, ObjectNode body, int expectedStatus) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        }
        addHeaders(b, ADMIN);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expectedStatus);
        return r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(r.body());
    }

    /// `PUT .../artifacts/{digest}` with `file`'s raw bytes and
    /// `Content-Type: application/octet-stream` — the same shape
    /// [io.flowcatalyst.fcdev.fn.FnClient#putFile] uses.
    private static JsonNode adminPutFile(String path, Path file, int expectedStatus) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofFile(file));
        addHeaders(b, ADMIN);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expectedStatus);
        return Json.MAPPER.readTree(r.body());
    }

    private static void addHeaders(HttpRequest.Builder b, String[] headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
    }

    private static Application createApplication(String code) {
        ApplicationRepository applications = new ApplicationRepository(DS);
        UnitOfWork uow = new UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        Application a = Application.create(ApplicationType.APPLICATION, code, "Artifact upload U13 " + code);
        uow.inTransaction(tx -> {
            applications.persist(a, tx.dbTx());
            return null;
        });
        return a;
    }

    private static String readVersionState(FunctionAddress address, int version) throws Exception {
        JsonNode status = adminGet("/api/functions/" + address.render() + "/status");
        for (JsonNode v : status.path("versions")) {
            if (v.path("version").asInt() == version) {
                return v.path("state").asString();
            }
        }
        return "";
    }

    private static void awaitCondition(ThrowingBooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private static ObjectNode obj(Object... kv) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            switch (value) {
                case null -> node.putNull(key);
                case String s -> node.put(key, s);
                case Boolean bv -> node.put(key, bv);
                case Integer n -> node.put(key, n);
                case JsonNode n -> node.set(key, n);
                default -> throw new IllegalArgumentException("unsupported value type: " + value);
            }
        }
        return node;
    }

    private static tools.jackson.databind.node.ArrayNode array(String... values) {
        var node = Json.MAPPER.createArrayNode();
        for (String v : values) {
            node.add(v);
        }
        return node;
    }
}
