package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.ArtifactBlobStore;
import io.flowcatalyst.platform.function.artifact.ArtifactException;
import io.flowcatalyst.platform.function.artifact.FileArtifactBlobStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `PUT /api/functions/{address}/artifacts/{digest}` and `GET
/// /control/functions/artifacts/{versionId}` end to end (spec
/// `function-artifact-upload.md` §3, §4, §1's `PublishVersion` checks, §5's
/// function-delete clause). Two harnesses: [#http] with a real
/// [FileArtifactBlobStore] (U1–U6, U9's HTTP-visible half, U10, U14's
/// happy path), [#httpNoStore] with no store configured at all (U7).
@SuppressWarnings("deprecation")
class FunctionArtifactUploadApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applications = new ApplicationRepository(TestPg.dataSource());
    private static final ClientRepository clients = new ClientRepository(TestPg.dataSource());
    private static final FunctionRepository functions = new FunctionRepository(TestPg.dataSource());
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(TestPg.dataSource());
    private static final FunctionHostRepository hosts = new FunctionHostRepository(TestPg.dataSource());
    private static final FunctionRouteRepository functionRoutes = new FunctionRouteRepository(TestPg.dataSource());
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(TestPg.dataSource());
    private static final TriggerObjectRepository triggerObjects = new TriggerObjectRepository(TestPg.dataSource());
    private static final SubscriptionRepository subscriptions = new SubscriptionRepository(TestPg.dataSource());
    private static final DispatchPoolRepository dispatchPools = new DispatchPoolRepository(TestPg.dataSource());
    private static final ScheduledJobRepository scheduledJobs = new ScheduledJobRepository(TestPg.dataSource());
    private static final ServiceAccountRepository serviceAccounts =
            new ServiceAccountRepository(TestPg.dataSource(), Optional.empty());
    private static final FunctionSettingsRepository settings =
            new FunctionSettingsRepository(TestPg.dataSource(), Optional.empty());
    private static final EventTypeRepository eventTypes = new EventTypeRepository(TestPg.dataSource());
    private static final EventRepository events = new EventRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();

    @TempDir
    static Path storeDir;

    private static FileArtifactBlobStore store;
    private static TestHttp http;
    private static TestHttp httpNoStore;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, "usr_anchor_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    /// FUNCTION_VIEW only — no publish rights (U5).
    private static final String[] VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, "usr_view_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};

    private static final String[] HOST_ROLE = {
            Authenticator.TEST_PRINCIPAL, "prn_host_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:host:control"};

    @BeforeAll
    static void start() {
        store = new FileArtifactBlobStore(storeDir);
        http = harness(Optional.of(store));
        httpNoStore = harness(Optional.empty());
    }

    private static TestHttp harness(Optional<ArtifactBlobStore> artifactStore) {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        return TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before(auth);
            FunctionApi.register(routes, new FunctionApi.State(functions, applications, clients, uow, versions, hosts,
                    policies, DEFAULTS, new Signatures.Off(), TriggerSync.none(), triggerObjects, subscriptions,
                    dispatchPools, scheduledJobs, settings, Optional.empty(), artifactStore));
            FunctionControlApi.register(routes, new FunctionControlApi.State(functions, versions, hosts, uow,
                    serviceAccounts, settings, applications, eventTypes, events, functionRoutes, artifactStore));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        httpNoStore.close();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static JsonNode jsonBytes(HttpResponse<byte[]> r) {
        return Json.MAPPER.readTree(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] bodyBytes(HttpResponse<String> r) {
        return r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static JsonNode create(TestHttp h, String appCode, String serviceName, String name) {
        if (applications.findByCode(appCode).isEmpty()) {
            var app = io.flowcatalyst.platform.application.Application.create(
                    io.flowcatalyst.platform.application.ApplicationType.APPLICATION, appCode, "Function Artifact " + appCode);
            uow.inTransaction(tx -> {
                applications.persist(app, tx.dbTx());
                return null;
            });
        }
        String body = "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"" + serviceName
                + "\",\"name\":\"" + name + "\",\"runtime\":\"jvm\"}";
        var r = h.post("/api/functions", body, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r);
    }

    private static String digestOf(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String manifestJson() {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\",\"pool\":\"default\"}";
    }

    private static HttpResponse<byte[]> upload(TestHttp h, String address, String digest, byte[] bytes, String... headers) {
        return h.putBytes("/api/functions/" + address + "/artifacts/" + digest, bytes, headers);
    }

    private static HttpResponse<String> publish(TestHttp h, String address, String artifactRef, String digest,
            String manifestJson, String... headers) {
        String body = "{\"artifactRef\":\"" + artifactRef + "\",\"digest\":\"" + digest + "\",\"manifest\":" + manifestJson + "}";
        return h.post("/api/functions/" + address + "/versions", body, headers);
    }

    /// A set of `java.io.tmpdir` entries matching the upload route's temp-file
    /// prefix — used before/after an upload attempt to prove nothing leaked
    /// (U3).
    private static Set<String> tmpUploadFiles() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmp, "fc-artifact-upload-*")) {
            for (Path p : ds) names.add(p.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    // ── U1: upload → publish with the returned ref → GET shows platform:// ref ──

    @Test
    void uploadThenPublishThenGetVersionShowsPlatformRefAndBlobBytesMatch() {
        var f = create(http, "u1-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String functionId = f.get("id").asText();
        byte[] bytes = ("u1-payload-" + RUN).repeat(1000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digestOf(bytes);

        var up = upload(http, address, digest, bytes, ANCHOR);
        assertThat(up.statusCode()).as(new String(up.body())).isEqualTo(200);
        JsonNode upBody = jsonBytes(up);
        String ref = upBody.get("artifactRef").asText();
        assertThat(ref).isEqualTo("platform://" + functionId + "/" + digest.substring("sha256:".length()));
        assertThat(upBody.get("bytes").asLong()).isEqualTo(bytes.length);

        var pub = publish(http, address, ref, digest, manifestJson(), ANCHOR);
        assertThat(pub.statusCode()).as(pub.body()).isEqualTo(201);
        String versionId = json(pub).get("id").asText();

        var got = json(http.get("/api/functions/" + address + "/versions/1", ANCHOR));
        assertThat(got.get("artifactRef").asText()).isEqualTo(ref);
        assertThat(got.get("id").asText()).isEqualTo(versionId);

        // the blob's bytes in the store equal the upload — read through the SAME
        // ArtifactBlobStore instance the routes use, never re-derived.
        try (InputStream in = store.open(functionId, Digest.parse(digest))) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── U2: wrong digest ⇒ 422 DIGEST_MISMATCH, and exists() is false afterwards ──

    @Test
    void wrongDigestIsRejectedAndLeavesNothingInTheStore() {
        var f = create(http, "u2-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String functionId = f.get("id").asText();
        byte[] bytes = "actual-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String wrongDigest = digestOf("something-else".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var before = tmpUploadFiles();
        var r = upload(http, address, wrongDigest, bytes, ANCHOR);
        assertThat(r.statusCode()).as(new String(r.body())).isEqualTo(422);
        assertThat(jsonBytes(r).get("error").asText()).isEqualTo("DIGEST_MISMATCH");
        assertThat(tmpUploadFiles()).as("mutant: leak the temp file").isEqualTo(before);

        assertThatExists(functionId, wrongDigest, false);
    }

    private static void assertThatExists(String functionId, String digest, boolean expected) {
        try {
            assertThat(store.exists(functionId, Digest.parse(digest))).isEqualTo(expected);
        } catch (ArtifactException e) {
            throw new AssertionError(e);
        }
    }

    // ── U3: over the cap ⇒ 413 both ways, nothing stored, no leaked temp file ──

    /// A declared `Content-Length` over the cap is refused before a single
    /// byte is read — proven the only fully deterministic way: a raw socket
    /// sends the request line and headers (`Content-Length` included) and
    /// then sends ZERO body bytes. If the handler answers 413 anyway, it
    /// never needed a body to reject; if a mutant dropped the precheck, the
    /// server would block waiting for a body that never arrives, and this
    /// read times out instead.
    @Test
    void declaredContentLengthOverTheCapIs413BeforeAnyByteIsRead() throws Exception {
        var f = create(http, "u3a-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String digest = digestOf("irrelevant".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long fakeLength = ArtifactBlobStore.MAX_BYTES + 1;

        var before = tmpUploadFiles();
        RawResponse r = rawPutHeadersOnly(http.port(), "/api/functions/" + address + "/artifacts/" + digest,
                fakeLength, ANCHOR);
        assertThat(r.status()).as(r.body()).isEqualTo(413);
        assertThat(Json.MAPPER.readTree(r.body()).get("error").asText()).isEqualTo("ARTIFACT_TOO_LARGE");
        assertThat(tmpUploadFiles()).isEqualTo(before);
    }

    /// A raw `PUT` that declares `Content-Length` but sends no body at all —
    /// the deterministic way to prove a handler answers from the header
    /// alone. Times out (`SocketTimeoutException`) if the server ever tries
    /// to read a body we never send.
    private record RawResponse(int status, String body) {
    }

    private static RawResponse rawPutHeadersOnly(int port, String path, long contentLength, String[] headers) throws IOException {
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append("PUT ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: 127.0.0.1:").append(port).append("\r\n");
            req.append("Content-Type: application/octet-stream\r\n");
            req.append("Content-Length: ").append(contentLength).append("\r\n");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                req.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            out.write(req.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();

            var in = socket.getInputStream();
            String statusLine = readLine(in);
            int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
            int contentLen = -1;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    contentLen = Integer.parseInt(line.substring(15).trim());
                }
            }
            String body = "";
            if (contentLen > 0) {
                byte[] buf = new byte[contentLen];
                int off = 0;
                while (off < contentLen) {
                    int n = in.read(buf, off, contentLen - off);
                    if (n == -1) break;
                    off += n;
                }
                body = new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
            }
            return new RawResponse(status, body);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') continue;
            if (c == '\n') break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    /// The running count passing the cap mid-stream (no declared
    /// `Content-Length` — chunked, discovered only once real bytes have
    /// flowed past it) is refused too, and the temp file it was writing
    /// into is deleted, not left behind. Pins the mutant "check only the
    /// [Content-Length] header": that mutant answers 200 here.
    @Test
    void aBodyOverTheCapDiscoveredMidStreamIs413AndCleansUpItsTempFile() {
        var f = create(http, "u3b-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String digest = digestOf("irrelevant".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var before = tmpUploadFiles();
        var r = http.putStreamedBytes("/api/functions/" + address + "/artifacts/" + digest,
                () -> new ZeroInputStream(ArtifactBlobStore.MAX_BYTES + 1), ANCHOR);
        assertThat(r.statusCode()).as(new String(r.body())).isEqualTo(413);
        assertThat(jsonBytes(r).get("error").asText()).isEqualTo("ARTIFACT_TOO_LARGE");
        assertThat(tmpUploadFiles()).as("mutant: leak the temp file").isEqualTo(before);
    }

    /// Emits `total` zero bytes then EOF, without ever materialising them —
    /// U3's mid-stream cap test needs to push real bytes past 256 MiB
    /// without allocating that much heap.
    private static final class ZeroInputStream extends InputStream {
        private long remaining;

        ZeroInputStream(long total) {
            this.remaining = total;
        }

        @Override
        public int read() {
            if (remaining <= 0) return -1;
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) return -1;
            int n = (int) Math.min(len, remaining);
            java.util.Arrays.fill(b, off, off + n, (byte) 0);
            remaining -= n;
            return n;
        }
    }

    // ── U4: a 3 MB upload succeeds while a 3 MB JSON body on an ordinary route is still 413 ──

    @Test
    void aThreeMegabyteUploadSucceedsWhileAnOrdinaryRouteStaysCapped() {
        var f = create(http, "u4-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        byte[] bytes = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(bytes, (byte) 7);
        String digest = digestOf(bytes);

        var up = upload(http, address, digest, bytes, ANCHOR);
        assertThat(up.statusCode()).as("mutant: raise the global cap instead of adding a streaming mode — this must still be 200")
                .isEqualTo(200);

        // The SAME 3 MB, as a JSON body on an ordinary (non-streaming) route,
        // is still capped at Javalin's 1 MB `MAX_BODY_BYTES` — untouched.
        String bigDescription = "x".repeat(3 * 1024 * 1024);
        String body = "{\"applicationCode\":\"" + f.get("applicationCode").asText() + "\",\"serviceName\":\"svc2\","
                + "\"name\":\"fn2\",\"runtime\":\"jvm\",\"description\":\"" + bigDescription + "\"}";
        var ordinary = http.post("/api/functions", body, ANCHOR);
        assertThat(ordinary.statusCode()).as("every OTHER route's 1 MB cap must stay byte-for-byte unchanged").isEqualTo(413);
    }

    // ── U5: unauthorised ⇒ 403, body unread ──────────────────────────────────

    /// A caller without `FUNCTION_PUBLISH` never gets far enough to open the
    /// body stream — proven the same deterministic way as U3a: a raw socket
    /// declares a 10 MB `Content-Length` and then sends NO body at all. The
    /// correct handler rejects on the coarse permission check alone and
    /// never asks for a byte, so the response arrives promptly; a mutant
    /// that "authorises after storing" would block waiting for a body that
    /// never comes, and the bounded socket read below times out instead of
    /// returning 403 — the java.net.http equivalent of this test blocked the
    /// WHOLE JVM for over ten minutes during development (java.net.http's
    /// synchronous `send()` does not return early just because the response
    /// is ready; it also waits out the request body write), which is why
    /// this route deliberately avoids it.
    @Test
    void callerWithoutPublishRightsIsRejectedWithTheBodyUnread() throws Exception {
        var f = create(http, "u5-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String digest = digestOf("irrelevant".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        RawResponse r = rawPutHeadersOnly(http.port(), "/api/functions/" + address + "/artifacts/" + digest,
                10L * 1024 * 1024, VIEW_ONLY);
        assertThat(r.status()).as("mutant: authorise after storing — this must answer without ever reading a body").isEqualTo(403);
    }

    /// Reach, not only the coarse permission (spec §3: "authorised exactly as
    /// publish is"): a publisher scoped to another client cannot see this
    /// function, so its upload is a 404 — and the body is never read.
    @Test
    void aPublisherWhoCannotReachTheFunctionGets404WithTheBodyUnread() throws Exception {
        var f = create(http, "u5r-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String digest = digestOf("irrelevant".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] otherTenantsPublisher = {
                Authenticator.TEST_PRINCIPAL, "usr_other_" + RUN, Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, "clt_someoneelse",
                Authenticator.TEST_PERMISSIONS, "platform:function:version:publish,platform:function:function:view"};

        RawResponse r = rawPutHeadersOnly(http.port(), "/api/functions/" + address + "/artifacts/" + digest,
                10L * 1024 * 1024, otherTenantsPublisher);
        assertThat(r.status()).isEqualTo(404);
    }

    // ── U6: platform:// ref mismatch / not-uploaded, no version row either way ──

    @Test
    void platformRefForAnotherFunctionsIdIsRejected() {
        var f1 = create(http, "u6a-" + RUN, "svc", "fn1");
        var f2 = create(http, "u6a-" + RUN, "svc", "fn2");
        String address1 = f1.get("address").asText();
        String functionId2 = f2.get("id").asText();
        byte[] bytes = "u6a".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digestOf(bytes);
        upload(http, address1, digest, bytes, ANCHOR);

        String wrongRef = "platform://" + functionId2 + "/" + digest.substring("sha256:".length());
        var pub = publish(http, address1, wrongRef, digest, manifestJson(), ANCHOR);
        assertThat(pub.statusCode()).as(pub.body()).isEqualTo(422);
        assertThat(json(pub).get("error").asText()).isEqualTo("ARTIFACT_REF_MISMATCH");
        assertThat(versions.listByFunction(f1.get("id").asText())).isEmpty();
    }

    @Test
    void platformRefWithAHexNotMatchingTheCommandDigestIsRejected() {
        var f = create(http, "u6b-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String functionId = f.get("id").asText();
        byte[] bytes = "u6b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digestOf(bytes);
        upload(http, address, digest, bytes, ANCHOR);

        String otherHex = "0".repeat(64);
        String wrongRef = "platform://" + functionId + "/" + otherHex;
        var pub = publish(http, address, wrongRef, digest, manifestJson(), ANCHOR);
        assertThat(pub.statusCode()).as(pub.body()).isEqualTo(422);
        assertThat(json(pub).get("error").asText()).isEqualTo("ARTIFACT_REF_MISMATCH");
        assertThat(versions.listByFunction(functionId)).isEmpty();
    }

    @Test
    void platformRefForTheRightFunctionButNeverUploadedIsRejected() {
        var f = create(http, "u6c-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String functionId = f.get("id").asText();
        byte[] bytes = "never-uploaded".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digestOf(bytes);
        String ref = "platform://" + functionId + "/" + digest.substring("sha256:".length());

        var pub = publish(http, address, ref, digest, manifestJson(), ANCHOR);
        assertThat(pub.statusCode()).as(pub.body()).isEqualTo(422);
        assertThat(json(pub).get("error").asText()).isEqualTo("ARTIFACT_NOT_UPLOADED");
        assertThat(versions.listByFunction(functionId)).isEmpty();
    }

    // ── U7: store unset ⇒ upload, platform:// publish, download all 503; oci:// unaffected ──

    @Test
    void everyArtifactRouteIs503WhenNoStoreIsConfiguredExceptAnOciPublish() {
        var f = create(httpNoStore, "u7-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String functionId = f.get("id").asText();
        byte[] bytes = "u7".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digestOf(bytes);

        var up = upload(httpNoStore, address, digest, bytes, ANCHOR);
        assertThat(up.statusCode()).as(new String(up.body())).isEqualTo(503);
        assertThat(jsonBytes(up).get("error").asText()).isEqualTo("ARTIFACT_STORE_NOT_CONFIGURED");

        String platformRef = "platform://" + functionId + "/" + digest.substring("sha256:".length());
        var platformPub = publish(httpNoStore, address, platformRef, digest, manifestJson(), ANCHOR);
        assertThat(platformPub.statusCode()).as(platformPub.body()).isEqualTo(503);
        assertThat(json(platformPub).get("error").asText()).isEqualTo("ARTIFACT_STORE_NOT_CONFIGURED");

        // An oci:// publish is unaffected — no store involved at all.
        var ociPub = publish(httpNoStore, address, "oci://example.test/repo", digest, manifestJson(), ANCHOR);
        assertThat(ociPub.statusCode()).as(ociPub.body()).isEqualTo(201);
        String versionId = json(ociPub).get("id").asText();

        var dl = httpNoStore.getBytes("/control/functions/artifacts/" + versionId, HOST_ROLE);
        assertThat(dl.statusCode()).as(new String(dl.body())).isEqualTo(503);
        assertThat(jsonBytes(dl).get("error").asText()).isEqualTo("ARTIFACT_STORE_NOT_CONFIGURED");
    }

    // ── U10: download — host role required, bytes match, unknown/oci:// 404 ──

    @Test
    void downloadRequiresTheHostRoleAndReturnsTheUploadedBytes() {
        var f = create(http, "u10-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        byte[] bytes = ("u10-payload-" + RUN).repeat(500).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digestOf(bytes);
        var up = upload(http, address, digest, bytes, ANCHOR);
        String ref = jsonBytes(up).get("artifactRef").asText();
        var pub = publish(http, address, ref, digest, manifestJson(), ANCHOR);
        String versionId = json(pub).get("id").asText();

        // a publisher token (no host role) is 403
        var forbidden = http.getBytes("/control/functions/artifacts/" + versionId, ANCHOR_WITHOUT_HOST_ROLE());
        assertThat(forbidden.statusCode()).isEqualTo(403);

        var ok = http.getBytes("/control/functions/artifacts/" + versionId, HOST_ROLE);
        assertThat(ok.statusCode()).as(new String(ok.body())).isEqualTo(200);
        assertThat(ok.body()).isEqualTo(bytes);
        assertThat(ok.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/octet-stream");

        var unknown = http.getBytes("/control/functions/artifacts/fnv_doesnotexist", HOST_ROLE);
        assertThat(unknown.statusCode()).isEqualTo(404);

        // A second function whose blob genuinely EXISTS in the store (uploaded, just
        // like the first) but is published by REFERENCE (oci://), never platform://.
        // A mutant that resolves (functionId, digest) straight off the version — never
        // checking the ref's scheme — would find this blob and answer 200; the real
        // route must still answer 404, since nothing was ever platform-published here.
        var f2 = create(http, "u10oci-" + RUN, "svc", "fn");
        String address2 = f2.get("address").asText();
        byte[] ociBytes = ("u10-oci-" + RUN).repeat(500).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String ociDigest = digestOf(ociBytes);
        upload(http, address2, ociDigest, ociBytes, ANCHOR);
        var ociPub = publish(http, address2, "oci://example.test/repo2", ociDigest, manifestJson(), ANCHOR);
        assertThat(ociPub.statusCode()).as(ociPub.body()).isEqualTo(201);
        String ociVersionId = json(ociPub).get("id").asText();
        var ociDl = http.getBytes("/control/functions/artifacts/" + ociVersionId, HOST_ROLE);
        assertThat(ociDl.statusCode()).as("an oci:// version's artifact is not downloadable through this route, "
                + "even when a blob happens to exist at (its functionId, its digest)").isEqualTo(404);
    }

    private static String[] ANCHOR_WITHOUT_HOST_ROLE() {
        return new String[]{Authenticator.TEST_PRINCIPAL, "usr_publisher_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:function:version:publish"};
    }

    // ── U14: function delete removes its blobs; a throwing store does not fail the delete ──

    @Test
    void deletingAFunctionRemovesItsUploadedBlobs() {
        var f = create(http, "u14a-" + RUN, "svc", "fn");
        String address = f.get("address").asText();
        String functionId = f.get("id").asText();
        byte[] bytes = "u14a".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digestOf(bytes);
        upload(http, address, digest, bytes, ANCHOR);
        assertThatExists(functionId, digest, true);

        var del = http.delete("/api/functions/" + address, ANCHOR);
        assertThat(del.statusCode()).as(del.body()).isEqualTo(204);

        assertThatExists(functionId, digest, false);
    }

    /// A store whose `deleteAll` always throws must never turn the function
    /// delete itself into a failure (spec §5: "a failure is a WARN, never a
    /// failed delete") — its own short-lived harness, so the throwing store
    /// never contaminates any other test.
    @Test
    void aStoreThatThrowsOnDeleteAllDoesNotFailTheFunctionDelete() {
        var throwing = new ThrowingDeleteAllStore(store);
        try (TestHttp h = harness(Optional.of(throwing))) {
            var f = create(h, "u14b-" + RUN, "svc", "fn");
            String address = f.get("address").asText();
            var del = h.delete("/api/functions/" + address, ANCHOR);
            assertThat(del.statusCode()).as("mutant: propagate the store's deleteAll failure").isEqualTo(204);
        }
    }

    /// Delegates everything to a real store except `deleteAll`, which always
    /// throws — the one behaviour U14's second half needs to pin.
    private record ThrowingDeleteAllStore(ArtifactBlobStore delegate) implements ArtifactBlobStore {
        @Override
        public void put(String functionId, Digest digest, Path file) throws ArtifactException {
            delegate.put(functionId, digest, file);
        }

        @Override
        public boolean exists(String functionId, Digest digest) throws ArtifactException {
            return delegate.exists(functionId, digest);
        }

        @Override
        public InputStream open(String functionId, Digest digest) throws ArtifactException {
            return delegate.open(functionId, digest);
        }

        @Override
        public long size(String functionId, Digest digest) throws ArtifactException {
            return delegate.size(functionId, digest);
        }

        @Override
        public void deleteAll(String functionId) throws ArtifactException {
            throw new ArtifactException(new ArtifactException.Transport(new IOException("simulated deleteAll failure")));
        }
    }
}
