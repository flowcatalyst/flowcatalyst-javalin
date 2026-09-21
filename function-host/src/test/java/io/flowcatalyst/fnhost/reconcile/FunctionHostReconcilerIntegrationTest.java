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
import io.flowcatalyst.platform.function.artifact.SignatureVerifier;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.artifact.TestSigstore;
import io.flowcatalyst.platform.function.artifact.TrustRoot;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/// R12, the integration pin (`docs/spec/function-host-reconciler.md` §3): a
/// REAL platform `Server` on `TestPg`, `FC_FN_SIGNATURES=required` over a
/// `TestSigstore` root injected through `FC_FN_TRUST_ROOT` (the trust-root
/// seam this slice adds, spec §0), a real `platform:function-host` service
/// principal, and a real `Reconciler` driven by hand against real HTTP:
/// publish (signed) → reconcile ⇒ heartbeat ⇒ `READY` → promote → reconcile
/// ⇒ `LOADED` (warm) and `GET …/status` agrees → promote v2 → retire v1 ⇒
/// v1 unloaded within one reconcile.
@SuppressWarnings("deprecation")
class FunctionHostReconcilerIntegrationTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Server.Running running;
    private static String baseUrl;
    private static TestSigstore.Ecosystem ECO;
    private static TrustRoot TRUST_ROOT;
    /// Its own database, migrated fresh — not the shared `TestPg.dataSource()`:
    /// this class seeds the well-known, code-unique built-in roles/application
    /// the same way `RouterConfigEndpointTest`/`RouterStartupOrderTest` do, and a
    /// full-suite run interleaves this class with every other class that reads or
    /// writes the shared instance in an order surefire does not promise
    /// (`docs/STATUS.md`'s intermittent-failure investigation, 2026-09-20).
    private static javax.sql.DataSource DS;

    private static final String[] ADMIN = {
            // published_by/publishedBy etc. are VARCHAR(17) — "prn_" + RUN(8) fits exactly.
            Authenticator.TEST_PRINCIPAL, "prn_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, String.join(",",
                    "platform:function:function:manage", "platform:function:function:view",
                    "platform:function:version:publish", "platform:function:alias:promote",
                    "platform:function:policy:manage", "platform:admin:application:create",
                    "platform:iam:service-account:view")
    };

    @BeforeAll
    static void startPlatform(@TempDir Path sharedDir) throws Exception {
        DS = TestPg.newDatabase("fn_host_reconciler_integration_test");
        io.flowcatalyst.platform.shared.database.Migrator.migrate(DS);
        new Seeder(DS).run();

        Instant now = Instant.now();
        ECO = TestSigstore.build(TestSigstore.LeafSpec.valid(now.minusSeconds(60), now.plusSeconds(3600)));
        TRUST_ROOT = ECO.trustRootFor(now.minusSeconds(3600), null, now.minusSeconds(3600), null);
        Path trustRootFile = sharedDir.resolve("trusted_root.json");
        Files.writeString(trustRootFile, writeTrustedRootJson(TRUST_ROOT));

        String appKey = Encryption.generateKey();

        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                "FLOWCATALYST_APP_KEY", appKey,
                // The trust-root seam this slice adds (spec §0): the platform's OWN
                // publish-time verification (Signatures.Required) must trust the SAME
                // TestSigstore root the host re-verifies against, or step "publish
                // (signed)" below fails at the platform, before the host is ever involved.
                "FC_FN_TRUST_ROOT", trustRootFile.toString()));

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
    void publishReconcileHeartbeatReadyPromoteLoadThenRetireUnloadsWithinOneReconcile(@TempDir Path dir) throws Exception {
        DnsLabel pool = new DnsLabel("pool" + RUN);
        FunctionAddress address = FunctionAddress.of(new DnsLabel("pf" + RUN), new DnsLabel("svc"), new DnsLabel("fn"));

        // ── function, owned by the platform (created directly — no route contract to prove here) ──
        FunctionRepository functions = new FunctionRepository(DS);
        UnitOfWork uow = new UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        Application app = createApplication("pf-app-" + RUN);
        Function fn = Function.create(app.id(), address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(fn, tx.dbTx());
            return null;
        });

        // ── the host's real service principal, provisioned through the real routes ──
        String hostAppCode = "pf-host-" + RUN;
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
        String hostToken = mintToken(hostClientId, hostClientSecret);

        // ── the owner's policy permits this ecosystem's signer for jvm ──
        adminPut("/api/function-policies/platform",
                Json.MAPPER.createObjectNode()
                        .set("signers", array(obj("issuer", "https://example.test/issuer", "subject",
                                "https://example.test/workflow.yml", "runtimes", array("jvm"))))
                .set("ceilings", Json.MAPPER.createObjectNode()),
                200);

        // ── publish v1, signed — the platform verifies it for real against FC_FN_TRUST_ROOT ──
        Path jar1 = TestFixtures.functionJar(dir, "r12-v1", "r12-v1");
        Digest digest1 = TestFixtures.digestOf(jar1);
        String bundle1 = TestSigstore.validBundleJson(ECO, rawDigest(digest1), Instant.now(), 101L);
        JsonNode published1 = adminPost("/api/functions/" + address.render() + "/versions",
                obj("artifactRef", TestFixtures.fileRef(jar1), "digest", digest1.value(), "signatureBundle", bundle1,
                        "manifest", obj("runtime", "jvm", "entrypoint", TestFixtures.ENTRYPOINT, "pool", pool.value(), "warm", true)),
                201);
        assertThat(published1.path("signer").path("issuer").asString())
                .as("the platform's own verification (FC_FN_TRUST_ROOT) must have run and recorded the signer")
                .isEqualTo("https://example.test/issuer");

        // ── the host's real Reconciler, driven by hand ──
        FunctionRegistry registry = new FunctionRegistry(50);
        HttpControlPlane controlPlane = new HttpControlPlane(baseUrl,
                new TokenSource(HTTP, baseUrl, hostClientId, hostClientSecret));
        Reconciler reconciler = new Reconciler(pool, "host-" + RUN, controlPlane,
                new io.flowcatalyst.platform.function.artifact.FileArtifactStore(dir.resolve("cache")),
                new Signatures.Required(new SignatureVerifier(TRUST_ROOT)), new JvmFunctionLoader(), registry);

        // Cycle 1: v1 has never been promoted — it is delivered as a CANDIDATE (R3, function-registry.md
        // §9), prepared and verified but never loaded; REGISTERED is still enough to mark it READY.
        reconciler.reconcileOnce(Instant.now());
        assertThat(registry.peek(address)).as("a candidate is never loaded").isNull();

        awaitCondition(() -> readVersionState(address, 1).equals("READY"),
                "the heartbeat's REGISTERED report must mark v1 READY");

        // ── promote v1 (now READY) ──
        adminPut("/api/functions/" + address.render() + "/aliases/live", obj("version", 1), 200);

        // Cycle 2: v1 is now live+warm — must load eagerly (spec §1.2 step 3).
        reconciler.reconcileOnce(Instant.now());
        LoadedFunction loadedV1 = registry.peek(address);
        assertThat(loadedV1).as("mutant: a promoted warm version must load on the very next reconcile").isNotNull();
        assertThat(loadedV1.version()).isEqualTo(1);

        JsonNode statusAfterLoad = adminGet("/api/functions/" + address.render() + "/status");
        JsonNode hostEntryAfterLoad = findHost(statusAfterLoad, "host-" + RUN);
        assertThat(hostEntryAfterLoad).as("GET status must show this host").isNotNull();
        assertThat(findLoadedVersion(hostEntryAfterLoad, 1).path("state").asString()).isEqualTo("LOADED");

        // ── publish + promote v2 ──
        Path jar2 = TestFixtures.functionJar(dir, "r12-v2", "r12-v2");
        Digest digest2 = TestFixtures.digestOf(jar2);
        String bundle2 = TestSigstore.validBundleJson(ECO, rawDigest(digest2), Instant.now(), 102L);
        adminPost("/api/functions/" + address.render() + "/versions",
                obj("artifactRef", TestFixtures.fileRef(jar2), "digest", digest2.value(), "signatureBundle", bundle2,
                        "manifest", obj("runtime", "jvm", "entrypoint", TestFixtures.ENTRYPOINT, "pool", pool.value(), "warm", true)),
                201);

        // Cycle 3: v2 arrives as a candidate alongside live v1; heartbeat marks v2 READY too.
        reconciler.reconcileOnce(Instant.now());
        awaitCondition(() -> readVersionState(address, 2).equals("READY"), "v2 must become READY the same way v1 did");
        assertThat(registry.peek(address).version()).as("v1 is still live — v2 is a candidate, never loaded")
                .isEqualTo(1);

        adminPut("/api/functions/" + address.render() + "/aliases/live", obj("version", 2), 200);
        adminPost("/api/functions/" + address.render() + "/versions/1/retire", null, 200);

        // Cycle 4: v2 is now live — v1 must be unloaded WITHIN THIS ONE reconcile (new before old).
        reconciler.reconcileOnce(Instant.now());
        LoadedFunction afterPromoteV2 = registry.peek(address);
        assertThat(afterPromoteV2).as("mutant: v1 must actually be unloaded").isNotNull();
        assertThat(afterPromoteV2.version()).as("mutant: v2 must be serving — not v1 left behind, not neither")
                .isEqualTo(2);

        JsonNode finalStatus = adminGet("/api/functions/" + address.render() + "/status");
        assertThat(finalStatus.path("live").path("version").asInt()).isEqualTo(2);
        JsonNode finalHostEntry = findHost(finalStatus, "host-" + RUN);
        assertThat(finalLoadedAddresses(finalHostEntry)).as("v1 must no longer be reported at all — the wholesale heartbeat replace")
                .containsExactly(2);
    }

    // ── R13 (function-api.md, platform side): the trust-root seam works end to end ──
    // (implicitly pinned above: publish would 400 SIGNATURE_REJECTED if FC_FN_TRUST_ROOT were wrong)

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

    private static void addHeaders(HttpRequest.Builder b, String[] headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
    }

    private static String mintToken(String clientId, String clientSecret) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret;
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body()).path("access_token").asString();
    }

    private static Application createApplication(String code) {
        ApplicationRepository applications = new ApplicationRepository(DS);
        UnitOfWork uow = new UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        Application a = Application.create(ApplicationType.APPLICATION, code, "Reconciler R12 " + code);
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

    private static JsonNode findHost(JsonNode status, String hostId) {
        for (JsonNode h : status.path("hosts")) {
            if (hostId.equals(h.path("hostId").asString())) {
                return h;
            }
        }
        return null;
    }

    private static JsonNode findLoadedVersion(JsonNode hostEntry, int version) {
        for (JsonNode lv : hostEntry.path("loaded")) {
            if (lv.path("version").asInt() == version) {
                return lv;
            }
        }
        return Json.MAPPER.createObjectNode();
    }

    private static List<Integer> finalLoadedAddresses(JsonNode hostEntry) {
        return hostEntry.path("loaded").valueStream().map(n -> n.path("version").asInt()).toList();
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

    private static byte[] rawDigest(Digest digest) {
        String hex = digest.value().substring("sha256:".length());
        return java.util.HexFormat.of().parseHex(hex);
    }

    private static ObjectNode obj(Object... kv) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            switch (value) {
                case String s -> node.put(key, s);
                case Boolean b -> node.put(key, b);
                case Integer n -> node.put(key, n);
                case JsonNode n -> node.set(key, n);
                default -> throw new IllegalArgumentException("unsupported value type: " + value);
            }
        }
        return node;
    }

    private static ArrayNode array(String... values) {
        ArrayNode node = Json.MAPPER.createArrayNode();
        for (String v : values) {
            node.add(v);
        }
        return node;
    }

    private static ArrayNode array(JsonNode... values) {
        ArrayNode node = Json.MAPPER.createArrayNode();
        for (JsonNode v : values) {
            node.add(v);
        }
        return node;
    }

    // ── trusted_root.json writer (the inverse of TrustRoot.parse) ──────────

    private static String writeTrustedRootJson(TrustRoot trustRoot) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode cas = root.putArray("certificateAuthorities");
        for (TrustRoot.CertificateAuthority ca : trustRoot.cas()) {
            ObjectNode caNode = Json.MAPPER.createObjectNode();
            ArrayNode certs = caNode.putObject("certChain").putArray("certificates");
            for (byte[] der : ca.certChainDer()) {
                certs.addObject().put("rawBytes", b64(der));
            }
            ObjectNode validFor = caNode.putObject("validFor");
            validFor.put("start", ca.validFrom().toString());
            if (ca.validUntil() != null) {
                validFor.put("end", ca.validUntil().toString());
            }
            cas.add(caNode);
        }
        ArrayNode tlogs = root.putArray("tlogs");
        for (TrustRoot.TransparencyLog tlog : trustRoot.tlogs()) {
            ObjectNode tlogNode = Json.MAPPER.createObjectNode();
            tlogNode.putObject("logId").put("keyId", b64(tlog.keyId()));
            ObjectNode publicKey = tlogNode.putObject("publicKey");
            publicKey.put("rawBytes", b64(tlog.publicKeyDer()));
            ObjectNode validFor = publicKey.putObject("validFor");
            validFor.put("start", tlog.validFrom().toString());
            if (tlog.validUntil() != null) {
                validFor.put("end", tlog.validUntil().toString());
            }
            tlogs.add(tlogNode);
        }
        return Json.write(root);
    }

    private static String b64(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }
}
