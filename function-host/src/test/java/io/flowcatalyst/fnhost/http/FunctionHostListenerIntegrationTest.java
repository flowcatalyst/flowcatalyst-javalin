package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.load.FixtureJars;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.reconcile.HttpControlPlane;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.fnhost.reconcile.TokenSource;
import io.flowcatalyst.fnhost.route.TrustedProxies;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.TriggerObject;
import io.flowcatalyst.platform.function.TriggerObjectKind;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.ingest.DispatchJobIngestMapper;
import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Server;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;

import java.net.ServerSocket;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// H15, the package's acceptance (`docs/spec/function-host-listener.md` §6):
/// a real platform [Server] on [TestPg] and a real [FnHttpServer], wired
/// together over real HTTP, end to end — signed publish of a function with a
/// subscription → heartbeat → `READY` → promote (the subscription appears,
/// target `…/functions/<address>/events/…`) → a dispatch job for that
/// subscription, created the way the ingest API would for a matched
/// subscription (`DispatchJobIngestMapper` + `DispatchJobRepository`
/// directly — there is no in-process event-ingest → subscription-match →
/// job-creation pipeline yet; the router/scheduler that would normally call
/// `POST /api/dispatch-jobs` for a match is out of tree, so this is the
/// smallest faithful substitute: the exact row shape a match would insert),
/// processed through `/api/dispatch/process` exactly as the router calls it
/// → the platform's `SubscriberDelivery` POSTs a SIGNED webhook to this host
/// → the function runs with `Caller.Platform` and `Webhook.event(request)`
/// yields the event → `Result.ack()` → the job is `COMPLETED`. Then a second
/// job whose payload asks for `Result.retry(...)` is deferred with no
/// attempt spent.
///
/// Signatures are `off` (`FC_FN_SIGNATURES=off` + `FLOWCATALYST_DEV_MODE=true`
/// on the platform; `Signatures.Off()` on the host) — publish-time artifact
/// signing (Sigstore) is orthogonal to this test's subject (webhook HMAC
/// delivery + dispatch processing) and is already exhaustively pinned by D2's
/// own R12 integration test; turning it off here removes an entire unrelated
/// TestSigstore/TrustRoot setup from a test that is already large.
///
/// Pool-URL/ephemeral-port resolution: `{pool}` is optional in
/// `PoolUrlTemplate` (spec `function-invocation.md` §4 R8) — a single-pool
/// setup like this test names the host directly, `http://127.0.0.1:<port>`,
/// with no placeholder at all. `PoolUrlTemplate` also now rejects userinfo
/// outright (a prior version of this test placed `{pool}` in the URL's
/// userinfo, `http://{pool}@127.0.0.1:<port>`, to route around the
/// then-mandatory-placeholder rule — that was a hack around a rule that was
/// wrong for exactly this shape of environment, not a legitimate URL, and is
/// rejected structurally now).
@SuppressWarnings("deprecation")
class FunctionHostListenerIntegrationTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Server.Running running;
    private static String baseUrl;
    private static String appKey;
    private static int hostPort;
    /// Its own database, migrated fresh — not the shared `TestPg.dataSource()`:
    /// this class seeds the well-known, code-unique built-in roles/application
    /// the same way `RouterConfigEndpointTest`/`RouterStartupOrderTest` do, and a
    /// full-suite run interleaves this class with every other class that reads or
    /// writes the shared instance in an order surefire does not promise
    /// (`docs/STATUS.md`'s intermittent-failure investigation, 2026-09-20).
    private static javax.sql.DataSource DS;

    private static final String[] ADMIN = {
            io.flowcatalyst.platform.shared.auth.Authenticator.TEST_PRINCIPAL, "prn_" + RUN,
            io.flowcatalyst.platform.shared.auth.Authenticator.TEST_SCOPE, "ANCHOR",
            io.flowcatalyst.platform.shared.auth.Authenticator.TEST_PERMISSIONS, String.join(",",
                    "platform:function:function:manage", "platform:function:function:view",
                    "platform:function:version:publish", "platform:function:alias:promote",
                    "platform:function:policy:manage", "platform:admin:application:create",
                    // security-fixes S1.2: provisioning a service account and assigning its roles
                    // need these at the anchor tier too (the tier is reach, never authority).
                    "platform:admin:application:update", "platform:iam:service-account:create", "platform:iam:service-account:update",
                    "platform:iam:service-account:view",
                    // The role ceiling (owner ruling 2026-09-25): assigning the host account
                    // platform:application-service and platform:function-host needs their permissions.
                    "platform:application-service:*:*", "platform:function:host:control", "platform:messaging:router:view", "platform:function:domain:manage")
    };

    @BeforeAll
    static void startPlatformAndHost(@TempDir Path sharedDir) throws Exception {
        DS = TestPg.newDatabase("fn_host_listener_integration_test");
        io.flowcatalyst.platform.shared.database.Migrator.migrate(DS);
        new Seeder(DS).run();
        appKey = Encryption.generateKey();

        // hostPort alone is still probed-and-released: it has to be known BEFORE
        // this Env is built, because FC_FN_POOL_URL is parsed into the platform's
        // PoolUrlTemplate at Env.load time, and the real FnHttpServer that will
        // eventually bind it doesn't exist yet — it starts per-@Test, driven by
        // hand, AFTER the platform (below) is already up and its own baseUrl is
        // known (FnHttpServer's Reconciler needs the platform's real apiPort to
        // make control-plane calls). Chicken-and-egg between the two ports: ask
        // the platform for its bound port (below), but there is no host listener
        // yet to ask for this one.
        try (ServerSocket h = new ServerSocket(0)) {
            hostPort = h.getLocalPort();
        }
        // R8: {pool} is optional — a single-pool template names the host directly (see the class doc).
        String poolUrlTemplate = "http://127.0.0.1:" + hostPort;

        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true",
                "FLOWCATALYST_APP_KEY", appKey,
                "FLOWCATALYST_DEV_MODE", "true",
                "FC_FN_SIGNATURES", "off",
                "FC_FN_POOL_URL", poolUrlTemplate));

        running = new Server(env, new Server.Mode.Platform(io.flowcatalyst.platform.shared.database.Pools.ofSingle(DS)),
                Server.Spa.none(), new PrometheusRegistry()).start();
        baseUrl = "http://127.0.0.1:" + running.apiPort();
    }

    @AfterAll
    static void stopPlatform() {
        if (running != null) {
            running.stop();
        }
    }

    @Test
    void signedPublishThroughSubscriptionMatchThroughDispatchProcessingToTheHost(@TempDir Path dir) throws Exception {
        DnsLabel pool = new DnsLabel("pool" + RUN);
        FunctionAddress address = FunctionAddress.of(new DnsLabel("pf" + RUN), new DnsLabel("svc"), new DnsLabel("fn"));

        // ── the function's owning application: a service account gives it a webhook signing secret ──
        String appCode = "h15-app-" + RUN;
        JsonNode app = adminPost("/api/applications", obj("code", appCode, "name", "H15 " + RUN, "type", "APPLICATION"), 201);
        String applicationId = app.path("id").asString();
        adminPost("/api/applications/" + applicationId + "/provision-service-account", null, 201);

        // ── the host's own control-plane service principal ──
        String hostAppCode = "h15-host-" + RUN;
        JsonNode hostApp = adminPost("/api/applications", obj("code", hostAppCode, "name", "Host " + RUN, "type", "APPLICATION"), 201);
        String hostAppId = hostApp.path("id").asString();
        JsonNode provisioned = adminPost("/api/applications/" + hostAppId + "/provision-service-account", null, 201);
        String hostClientId = provisioned.path("serviceAccount").path("oauthClient").path("clientId").asString();
        String hostClientSecret = provisioned.path("serviceAccount").path("oauthClient").path("clientSecret").asString();
        JsonNode hostServiceAccount = adminGet("/api/service-accounts/code/app:" + hostAppCode);
        String hostServiceAccountId = hostServiceAccount.path("id").asString();
        adminPut("/api/service-accounts/" + hostServiceAccountId + "/roles",
                obj("roles", array("platform:application-service", "platform:function-host")), 200);

        // ── the event type the subscription binds ──
        String eventTypeCode = "h15" + RUN + ":orders:order:created";
        var eventTypes = new EventTypeRepository(DS);
        var uow = new UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        EventType eventType = EventType.create(eventTypeCode, "H15 order created");
        uow.inTransaction(tx -> {
            eventTypes.persist(eventType, tx.dbTx());
            return null;
        });

        // ── the function itself, platform-owned (created directly, same as R12 — no route contract to prove here) ──
        FunctionRepository functions = new FunctionRepository(DS);
        Function fn = Function.create(applicationId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(fn, tx.dbTx());
            return null;
        });

        // ── publish v1 with a webhook endpoint and a subscription to the event type ──
        Path jar = functionJar(dir);
        Digest digest = digestOf(jar);
        JsonNode manifest = obj("runtime", "jvm", "entrypoint", "fixture.h15.WebhookFn", "pool", pool.value(), "warm", false,
                "endpoints", array(obj("path", "/events/*", "auth", "webhook")),
                "subscriptions", array(obj("eventType", eventTypeCode, "path", "/events/created", "mode", "IMMEDIATE")));
        JsonNode published = adminPost("/api/functions/" + address.render() + "/versions",
                obj("artifactRef", fileRef(jar), "digest", digest.value(), "manifest", manifest), 201);
        assertThat(published.path("signer").isMissingNode()).as("signatures are off: no signer recorded").isTrue();

        // ── the host's real Reconciler + real FnHttpServer, driven by hand ──
        FunctionRegistry registry = new FunctionRegistry(50);
        HttpControlPlane controlPlane = new HttpControlPlane(baseUrl,
                new TokenSource(HTTP, baseUrl, hostClientId, hostClientSecret));
        Reconciler reconciler = new Reconciler(pool, "host-" + RUN, controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        try (FnHttpServer fnServer = FnHttpServer.start(reconciler, new FnHttpServer.Options(
                "127.0.0.1", hostPort, 512, baseUrl, java.time.Clock.systemUTC()))) {
            assertThat(fnServer.port()).isEqualTo(hostPort);

            // Cycle 1: v1 is a CANDIDATE (never promoted) — prepared, never loaded; REGISTERED marks it READY.
            reconciler.reconcileOnce(Instant.now());
            awaitCondition(() -> readVersionState(address, 1).equals("READY"), "v1 must become READY");

            // ── promote ──
            adminPut("/api/functions/" + address.render() + "/aliases/live", obj("version", 1), 200);

            // Cycle 2: v1 is now live (lazy) — the reconciler sees it, but does not eagerly load it.
            reconciler.reconcileOnce(Instant.now());

            // ── the subscription now exists, source FUNCTION, target under /functions/<address>/… ──
            TriggerObjectRepository triggerObjects = new TriggerObjectRepository(DS);
            SubscriptionRepository subscriptions = new SubscriptionRepository(DS);
            TriggerObject subLink = triggerObjects.listByFunction(fn.id()).stream()
                    .filter(t -> t.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst()
                    .orElseThrow(() -> new AssertionError("promote must have created the subscription"));
            Subscription subscription = subscriptions.findById(subLink.objectId()).orElseThrow();
            assertThat(subscription.endpoint()).as("target is …/functions/<address>/events/… — no version in it")
                    .isEqualTo("http://127.0.0.1:" + hostPort + "/functions/" + address.render() + "/events/created");
            assertThat(subscription.applicationCode()).isEqualTo(appCode);

            // ── the dispatch job a matched subscription would produce (spec: "through the dispatch-jobs
            // repository exactly as a matched subscription would" — there is no in-process event-ingest
            // → subscription-match → job-creation pipeline in this tree yet to drive instead) ──
            String jobId = createDispatchJobForSubscription(subscription, eventTypeCode, "{\"orderId\":123}");

            // ── driven through /api/dispatch/process exactly as ProcessingApiTest does ──
            var processResp = processDispatchJob(jobId);
            assertThat(processResp.path("ack").asBoolean()).isTrue();

            Path evidence = dir.resolve("evidence.jsonl");
            awaitCondition(() -> Files.exists(evidence) && !Files.readAllLines(evidence).isEmpty(),
                    "the function must have run and recorded what it saw");
            EvidenceLine seen = lastEvidenceLine(evidence);
            assertThat(seen.caller()).as("mutant: the function saw something other than Caller.Platform")
                    .isEqualTo("Platform");
            assertThat(seen.type()).isEqualTo(eventTypeCode);
            assertThat(seen.dataJson()).contains("orderId").contains("123");

            DispatchJobRepository dispatchJobs = new DispatchJobRepository(DS);
            DispatchJob completed = dispatchJobs.findById(jobId).orElseThrow();
            assertThat(completed.status()).as("mutant: ack() must complete the job").isEqualTo(DispatchJobStatus.COMPLETED);

            // ── a second job whose payload asks the fixture to retry ──
            String retryJobId = createDispatchJobForSubscription(subscription, eventTypeCode, "{\"marker\":\"RETRY_ME\"}");
            Instant beforeProcess = Instant.now();
            var retryResp = processDispatchJob(retryJobId);
            assertThat(retryResp.path("ack").asBoolean()).isTrue();

            awaitCondition(() -> dispatchJobs.findById(retryJobId).orElseThrow().attemptCount() == 0
                            && dispatchJobs.findById(retryJobId).orElseThrow().scheduledFor() != null,
                    "the deferred job must be rescheduled");
            DispatchJob deferred = dispatchJobs.findById(retryJobId).orElseThrow();
            assertThat(deferred.status()).as("mutant: retry() must not complete or fail the job")
                    .isEqualTo(DispatchJobStatus.PENDING);
            assertThat(deferred.attemptCount()).as("mutant: a deferral must spend no retry budget").isEqualTo(0);
            long delaySeconds = Duration.between(beforeProcess, deferred.scheduledFor()).getSeconds();
            assertThat(delaySeconds).as("mutant: the requested 7s Retry-After must reach the reschedule")
                    .isBetween(4L, 20L);
        }
    }

    /// X10 (`docs/spec/function-context.md` §4, extends H15 above): the
    /// function handles a webhook delivery and calls `ctx.events().emit(…)`
    /// twice — once with a type its OWN application owns, once with a type
    /// owned by ANOTHER application. The owned emit lands a real
    /// `msg_events` row, written by the platform's `/control/functions/events`
    /// route through the SAME path the acceptance test above drives for
    /// subscriptions; its `correlation_id`/`causation_id` are the DEFAULTS
    /// the host filled in (spec §3): `correlation_id` = this invocation's
    /// own id (no `X-Correlation-Id` header reaches this delivery — the
    /// dispatch job carries no `correlationId`, `DeliveryPayload` never sets
    /// the header), `causation_id` = the inbound webhook event's id (the
    /// dispatch job's own id, [Event#id]). The not-owned emit answers
    /// [io.flowcatalyst.function.EmitResult.Refused] with `EVENT_TYPE_NOT_OWNED`/403,
    /// seen by the fixture and reported through evidence rather than the response (the
    /// dispatch/webhook plumbing does not hand the response body back to
    /// this test) — and writes NO row.
    @Test
    void emitEventsThroughTheHostOwnedSucceedsAndNotOwnedIsRefused(@TempDir Path dir) throws Exception {
        DnsLabel pool = new DnsLabel("emitpool" + RUN);
        FunctionAddress address = FunctionAddress.of(new DnsLabel("em" + RUN), new DnsLabel("svc"), new DnsLabel("fn"));

        // ── the function's owning application — its CODE is the owned event type's first segment ──
        String appCode = "h15emit" + RUN;
        JsonNode app = adminPost("/api/applications", obj("code", appCode, "name", "H15 Emit " + RUN, "type", "APPLICATION"), 201);
        String applicationId = app.path("id").asString();
        adminPost("/api/applications/" + applicationId + "/provision-service-account", null, 201);

        // ── the host's own control-plane service principal ──
        String hostAppCode = "h15emit-host-" + RUN;
        JsonNode hostApp = adminPost("/api/applications", obj("code", hostAppCode, "name", "Emit Host " + RUN, "type", "APPLICATION"), 201);
        String hostAppId = hostApp.path("id").asString();
        JsonNode provisioned = adminPost("/api/applications/" + hostAppId + "/provision-service-account", null, 201);
        String hostClientId = provisioned.path("serviceAccount").path("oauthClient").path("clientId").asString();
        String hostClientSecret = provisioned.path("serviceAccount").path("oauthClient").path("clientSecret").asString();
        JsonNode hostServiceAccount = adminGet("/api/service-accounts/code/app:" + hostAppCode);
        String hostServiceAccountId = hostServiceAccount.path("id").asString();
        adminPut("/api/service-accounts/" + hostServiceAccountId + "/roles",
                obj("roles", array("platform:application-service", "platform:function-host")), 200);

        // ── one event type this function's OWN application owns, one owned by ANOTHER ──
        String ownedType = appCode + ":orders:order:created";
        String notOwnedType = "h15emit-other" + RUN + ":orders:order:created";
        var eventTypes = new EventTypeRepository(DS);
        var uow = new UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        EventType owned = EventType.create(ownedType, "H15 emit owned");
        EventType notOwned = EventType.create(notOwnedType, "H15 emit not owned");
        uow.inTransaction(tx -> {
            eventTypes.persist(owned, tx.dbTx());
            eventTypes.persist(notOwned, tx.dbTx());
            return null;
        });

        // ── the function itself, platform-owned, publish v1 with a webhook endpoint + subscription ──
        FunctionRepository functions = new FunctionRepository(DS);
        Function fn = Function.create(applicationId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(fn, tx.dbTx());
            return null;
        });

        Path jar = emitFunctionJar(dir, ownedType, notOwnedType);
        Digest digest = digestOf(jar);
        JsonNode manifest = obj("runtime", "jvm", "entrypoint", "fixture.h15emit.EmitFn", "pool", pool.value(), "warm", false,
                "endpoints", array(obj("path", "/events/*", "auth", "webhook")),
                "subscriptions", array(obj("eventType", ownedType, "path", "/events/created", "mode", "IMMEDIATE")));
        adminPost("/api/functions/" + address.render() + "/versions",
                obj("artifactRef", fileRef(jar), "digest", digest.value(), "manifest", manifest), 201);

        FunctionRegistry registry = new FunctionRegistry(50);
        HttpControlPlane controlPlane = new HttpControlPlane(baseUrl,
                new TokenSource(HTTP, baseUrl, hostClientId, hostClientSecret));
        Reconciler reconciler = new Reconciler(pool, "emit-host-" + RUN, controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        try (FnHttpServer fnServer = FnHttpServer.start(reconciler, new FnHttpServer.Options(
                "127.0.0.1", hostPort, 512, baseUrl, java.time.Clock.systemUTC()))) {
            assertThat(fnServer.port()).isEqualTo(hostPort);

            reconciler.reconcileOnce(Instant.now());
            awaitCondition(() -> readVersionState(address, 1).equals("READY"), "v1 must become READY");

            adminPut("/api/functions/" + address.render() + "/aliases/live", obj("version", 1), 200);
            reconciler.reconcileOnce(Instant.now());

            TriggerObjectRepository triggerObjects = new TriggerObjectRepository(DS);
            SubscriptionRepository subscriptions = new SubscriptionRepository(DS);
            TriggerObject subLink = triggerObjects.listByFunction(fn.id()).stream()
                    .filter(t -> t.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst()
                    .orElseThrow(() -> new AssertionError("promote must have created the subscription"));
            Subscription subscription = subscriptions.findById(subLink.objectId()).orElseThrow();

            String jobId = createDispatchJobForSubscription(subscription, ownedType, "{}");
            var processResp = processDispatchJob(jobId);
            assertThat(processResp.path("ack").asBoolean()).isTrue();

            Path evidence = dir.resolve("emit-evidence.jsonl");
            awaitCondition(() -> Files.exists(evidence) && !Files.readAllLines(evidence).isEmpty(),
                    "the function must have run and recorded what it saw");
            EmitEvidenceLine seen = lastEmitEvidenceLine(evidence);

            assertThat(seen.caller()).as("mutant: the function saw something other than Caller.Platform")
                    .isEqualTo("Platform");
            assertThat(seen.ownedOk()).as("mutant: the owned emit did not succeed: " + seen.ownedError()).isTrue();
            assertThat(seen.notOwnedRefused())
                    .as("mutant: emitting a type owned by another application was not refused").isTrue();
            assertThat(seen.notOwnedCode())
                    .as("mutant: the refusal carries the wrong code").isEqualTo("EVENT_TYPE_NOT_OWNED");
            assertThat(seen.notOwnedStatus())
                    .as("mutant: the refusal carries the wrong status").isEqualTo("403");

            DSLContext db = DSL.using(DS, SQLDialect.POSTGRES);
            Record ownedRow = db.selectFrom(MSG_EVENTS)
                    .where(MSG_EVENTS.DEDUPLICATION_ID.eq("dedup-owned-" + jobId)).fetchOne();
            assertThat(ownedRow).as("mutant: the owned event was never written").isNotNull();
            assertThat(seen.ownedEventId())
                    .as("mutant: Emitted does not carry the id the event was stored under")
                    .isEqualTo(ownedRow.get(MSG_EVENTS.ID));
            assertThat(ownedRow.get(MSG_EVENTS.CORRELATION_ID))
                    .as("mutant: correlationId does not default to this invocation's own id")
                    .isEqualTo(seen.invocationId());
            assertThat(ownedRow.get(MSG_EVENTS.CAUSATION_ID))
                    .as("mutant: causationId does not default to the inbound webhook event's id")
                    .isEqualTo(jobId);

            Record notOwnedRow = db.selectFrom(MSG_EVENTS)
                    .where(MSG_EVENTS.DEDUPLICATION_ID.eq("dedup-notowned-" + jobId)).fetchOne();
            assertThat(notOwnedRow).as("mutant: a row was written even though ownership was refused").isNull();
        }
    }

    /// **F11** (`docs/spec/function-public-routes.md` §6): claim → publish
    /// with `public` → promote → reconcile → `GET` on the PUBLIC port with
    /// `Host: <hostname>` reaches the function with `path=/x`, and the SAME
    /// function by address on the PRIVATE port sees the same path. A claim is
    /// verified by being made (`function-domains-no-dns.md`), so there is no
    /// verify step and no verification state to assert.
    @Test
    void publicRouteReachesTheFunctionAndThePrivateEntrySeesTheSamePath(@TempDir Path dir) throws Exception {
        DnsLabel pool = new DnsLabel("f11pool" + RUN);
        FunctionAddress address = FunctionAddress.of(new DnsLabel("f11" + RUN), new DnsLabel("svc"), new DnsLabel("fn"));
        String hostname = "f11-" + RUN + ".localhost";

        JsonNode claimed = adminPost("/api/function-domains", obj("hostname", hostname), 201);
        assertThat(claimed.path("hostname").asString()).isEqualTo(hostname);

        // ── the function itself, platform-owned (same convention as H15/X10 above) ──
        String appCode = "f11-app-" + RUN;
        JsonNode app = adminPost("/api/applications", obj("code", appCode, "name", "F11 " + RUN, "type", "APPLICATION"), 201);
        String applicationId = app.path("id").asString();

        FunctionRepository functions = new FunctionRepository(DS);
        Function fn = Function.create(applicationId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        var uow = new UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        uow.inTransaction(tx -> {
            functions.persist(fn, tx.dbTx());
            return null;
        });

        // ── publish v1 with a public route + promote ──
        Path jar = publicRouteFunctionJar(dir);
        Digest digest = digestOf(jar);
        JsonNode manifest = obj("runtime", "jvm", "entrypoint", "fixture.f11.PathFn", "pool", pool.value(), "warm", false,
                "endpoints", array(obj("path", "/*", "auth", "none")),
                "public", array(obj("hostname", hostname, "pathPrefix", "/")));
        adminPost("/api/functions/" + address.render() + "/versions",
                obj("artifactRef", fileRef(jar), "digest", digest.value(), "manifest", manifest), 201);

        // ── the host's own control-plane service principal (same pattern as H15 above) ──
        String hostAppCode = "f11-host-" + RUN;
        JsonNode hostApp = adminPost("/api/applications", obj("code", hostAppCode, "name", "F11 Host " + RUN, "type", "APPLICATION"), 201);
        String hostAppId = hostApp.path("id").asString();
        JsonNode provisioned = adminPost("/api/applications/" + hostAppId + "/provision-service-account", null, 201);
        String hostClientId = provisioned.path("serviceAccount").path("oauthClient").path("clientId").asString();
        String hostClientSecret = provisioned.path("serviceAccount").path("oauthClient").path("clientSecret").asString();
        JsonNode hostServiceAccount = adminGet("/api/service-accounts/code/app:" + hostAppCode);
        adminPut("/api/service-accounts/" + hostServiceAccount.path("id").asString() + "/roles",
                obj("roles", array("platform:application-service", "platform:function-host")), 200);

        // ── the host's own Reconciler + FnHttpServer — BOTH ports ephemeral (port 0), read
        // back after bind (never probe-and-release: a live TCP listener binds 0 and reports
        // its own bound port). Independent of the class-level `hostPort`/pool URL: this test
        // uses no subscription, so no PoolUrlTemplate target is ever constructed. ──
        FunctionRegistry registry = new FunctionRegistry(50);
        HttpControlPlane controlPlane = new HttpControlPlane(baseUrl, new TokenSource(HTTP, baseUrl,
                hostClientId, hostClientSecret));
        Reconciler reconciler = new Reconciler(pool, "f11-host-" + RUN, controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        try (FnHttpServer fnServer = FnHttpServer.start(reconciler,
                FnHttpServer.Options.of(0, 512, baseUrl, io.flowcatalyst.fnhost.http.InvocationObserver.NOOP, 0,
                        TrustedProxies.DEFAULT).withHost("127.0.0.1"))) {

            reconciler.reconcileOnce(Instant.now());
            awaitCondition(() -> readVersionState(address, 1).equals("READY"), "v1 must become READY");

            adminPut("/api/functions/" + address.render() + "/aliases/live", obj("version", 1), 200);
            reconciler.reconcileOnce(Instant.now());

            // ── PUBLIC port: Host-based routing (java.net.http forbids setting Host directly
            // — see RawHttpClient's own doc) ──
            var pub = RawHttpClient.send(fnServer.publicPort(), "GET", "/x", Map.of("Host", hostname), null);
            assertThat(pub.status()).as(pub.bodyAsString()).isEqualTo(200);
            JsonNode pubBody = Json.MAPPER.readTree(pub.bodyAsString());
            assertThat(pubBody.path("path").asString()).isEqualTo("/x");

            // ── PRIVATE port: same function, by address, same function-path ──
            var priv = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + fnServer.port()
                            + "/functions/" + address.render() + "/x")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(priv.statusCode()).isEqualTo(200);
            JsonNode privBody = Json.MAPPER.readTree(priv.body());
            assertThat(privBody.path("path").asString()).as("mutant: prefix stripping differs between listeners")
                    .isEqualTo(pubBody.path("path").asString());
            // F7's own dedicated tests (`FnHttpServerPublicListenerTest`) pin "never by
            // address" with a NARROW prefix — this route's prefix is "/" (owns the whole
            // host), so an address-shaped path would 200 here too, for an unrelated reason
            // (the function legitimately owns every path on this host); asserting 404 for
            // it here would be a false pin, not a real one.
        }
    }

    private static Path publicRouteFunctionJar(Path dir) {
        Path jar = dir.resolve("f11-fn.jar");
        FixtureJars.builder().source("fixture.f11.PathFn", """
                package fixture.f11;
                import io.flowcatalyst.function.*;
                public final class PathFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        return Result.json(200, "{\\"path\\":\\"" + in.path() + "\\"}");
                    }
                }
                """).build(jar);
        return jar;
    }

    private static Path emitFunctionJar(Path dir, String ownedType, String notOwnedType) {
        Path jar = dir.resolve("h15-emit-fn.jar");
        FixtureJars.builder().source("fixture.h15emit.EmitFn",
                emitFnSource(dir.resolve("emit-evidence.jsonl"), ownedType, notOwnedType)).build(jar);
        return jar;
    }

    /// Pipe-delimited, Base64-valued fields — same convention as
    /// [#webhookFnSource] and for the same reason (no nested-quote escaping
    /// through the double text-block nesting).
    private static String emitFnSource(Path evidenceFile, String ownedType, String notOwnedType) {
        return """
                package fixture.h15emit;
                import io.flowcatalyst.function.*;
                import java.nio.file.*;
                import java.nio.charset.StandardCharsets;
                import java.util.Base64;

                public final class EmitFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        Event event = Webhook.event(in);
                        String caller = in.caller().getClass().getSimpleName();
                        String invocationId = in.invocationId();

                        boolean ownedOk = false;
                        String ownedError = "";
                        String ownedEventId = "";
                        switch (ctx.events().emit(new OutboundEvent("%s", "h15emit-test", "subj-" + event.id(),
                                "application/json", "{}".getBytes(StandardCharsets.UTF_8), null, null, null,
                                "dedup-owned-" + event.id()))) {
                            case EmitResult.Emitted e -> { ownedOk = true; ownedEventId = e.eventId(); }
                            case EmitResult.Refused r -> ownedError = r.message();
                        }

                        boolean notOwnedRefused = false;
                        String notOwnedCode = "";
                        int notOwnedStatus = 0;
                        if (ctx.events().emit(new OutboundEvent("%s", "h15emit-test", "subj-not-owned",
                                "application/json", "{}".getBytes(StandardCharsets.UTF_8), null, null, null,
                                "dedup-notowned-" + event.id())) instanceof EmitResult.Refused r) {
                            notOwnedRefused = true;
                            notOwnedCode = r.code();
                            notOwnedStatus = r.status();
                        }

                        String line = b64(caller) + "|" + b64(invocationId) + "|" + b64(String.valueOf(ownedOk)) + "|"
                                + b64(ownedError) + "|" + b64(String.valueOf(notOwnedRefused)) + "|" + b64(notOwnedCode)
                                + "|" + b64(String.valueOf(notOwnedStatus)) + "|" + b64(ownedEventId) + System.lineSeparator();
                        Files.writeString(Path.of("%s"), line, StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        return Result.ack();
                    }
                    private static String b64(String s) {
                        if (s == null) return "";
                        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
                    }
                }
                """.formatted(ownedType, notOwnedType, evidenceFile.toString().replace("\\", "\\\\"));
    }

    /// The wire shape [#emitFnSource]'s fixture writes: pipe-delimited, each
    /// field Base64-encoded.
    private record EmitEvidenceLine(String caller, String invocationId, boolean ownedOk, String ownedError,
                                     boolean notOwnedRefused, String notOwnedCode, String notOwnedStatus,
                                     String ownedEventId) {
    }

    private static EmitEvidenceLine lastEmitEvidenceLine(Path evidence) throws Exception {
        List<String> lines = Files.readAllLines(evidence);
        String[] fields = lines.get(lines.size() - 1).split("\\|", -1);
        return new EmitEvidenceLine(b64(fields[0]), b64(fields[1]), Boolean.parseBoolean(b64(fields[2])), b64(fields[3]),
                Boolean.parseBoolean(b64(fields[4])), b64(fields[5]), b64(fields[6]), b64(fields[7]));
    }

    // ── dispatch job creation "the way a matched subscription would" ───────

    private static String createDispatchJobForSubscription(Subscription subscription, String eventTypeCode, String payload) {
        var mapper = new DispatchJobIngestMapper.RawItem(
                null, null, "EVENT", eventTypeCode, "h15-test", null, subscription.endpoint(), payload,
                "application/json", false, null, null, subscription.clientId(), subscription.id(), null,
                subscription.dispatchPoolId(), null, "IMMEDIATE", 0, null, 30, 3, null, List.of(), null,
                /* descriptor */ null, /* queue */ null);
        DispatchJob job = DispatchJobIngestMapper.toJob(mapper);
        new DispatchJobRepository(DS).insertBatch(List.of(job));
        return job.id();
    }

    private static JsonNode processDispatchJob(String jobId) throws Exception {
        String authToken = HmacTokenVerifier.fromAppKey(appKey).sign(jobId);
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/dispatch/process"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"messageId\":\"" + jobId + "\"}"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Json.MAPPER.readTree(response.body());
    }

    // ── fixture jar ──────────────────────────────────────────────────────

    private static Path functionJar(Path dir) {
        Path jar = dir.resolve("h15-fn.jar");
        FixtureJars.builder().source("fixture.h15.WebhookFn", webhookFnSource(dir.resolve("evidence.jsonl"))).build(jar);
        return jar;
    }

    /// Pipe-delimited, Base64-valued fields — never JSON — so the fixture
    /// source embedded in the OUTER text block below needs no nested quote
    /// escaping at all (a prior JSON-with-manual-escaping version of this
    /// fixture round-tripped incorrectly through the double text-block
    /// nesting and produced invalid JSON; this sidesteps that entirely).
    private static String webhookFnSource(Path evidenceFile) {
        return """
                package fixture.h15;
                import io.flowcatalyst.function.*;
                import java.nio.file.*;
                import java.nio.charset.StandardCharsets;
                import java.time.Duration;
                import java.util.Base64;

                public final class WebhookFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        Event event = Webhook.event(in);
                        String caller = in.caller().getClass().getSimpleName();
                        String line = b64(caller) + "|" + b64(event.id()) + "|" + b64(event.type()) + "|"
                                + b64(event.dataJson()) + System.lineSeparator();
                        Files.writeString(Path.of("%s"), line, StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        if (event.dataJson() != null && event.dataJson().contains("RETRY_ME")) {
                            return Result.retry(Duration.ofSeconds(7));
                        }
                        return Result.ack();
                    }
                    private static String b64(String s) {
                        if (s == null) return "";
                        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
                    }
                }
                """.formatted(evidenceFile.toString().replace("\\", "\\\\"));
    }

    /// The wire shape [#webhookFnSource]'s fixture writes: pipe-delimited,
    /// each field Base64-encoded (see that method's own doc for why).
    private record EvidenceLine(String caller, String jobId, String type, String dataJson) {
    }

    private static EvidenceLine lastEvidenceLine(Path evidence) throws Exception {
        List<String> lines = Files.readAllLines(evidence);
        String[] fields = lines.get(lines.size() - 1).split("\\|", -1);
        return new EvidenceLine(b64(fields[0]), b64(fields[1]), b64(fields[2]), b64(fields[3]));
    }

    private static String b64(String s) {
        return s.isEmpty() ? null : new String(java.util.Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static Digest digestOf(Path file) {
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
        } catch (NoSuchAlgorithmException | java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String fileRef(Path jar) {
        return jar.toUri().toString();
    }

    // ── admin HTTP helpers (TEST_* headers — same convention as FunctionHostReconcilerIntegrationTest) ──

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
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
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
}
