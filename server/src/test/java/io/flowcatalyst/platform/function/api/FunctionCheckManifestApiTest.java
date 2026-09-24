package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.PoolUrlTemplate;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.FunctionTriggerSync;
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
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;

/// `POST /api/functions/{address}/manifest/check` end to end (spec
/// `function-manifest-authoring.md` M2.2, M2 tests): unlike most other
/// function-api tests, this wires the REAL [FunctionTriggerSync] (not
/// [TriggerSync#none]) — the whole point of the route is to preview that
/// exact reconciliation, so a stub would make every assertion here vacuous.
@SuppressWarnings("deprecation")
class FunctionCheckManifestApiTest {

    private static final DataSource DS = TestPg.dataSource();

    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(DS);
    private static final ApplicationRepository applications = new ApplicationRepository(DS);
    private static final ClientRepository clients = new ClientRepository(DS);
    private static final FunctionHostRepository hosts = new FunctionHostRepository(DS);
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(DS);
    private static final TriggerObjectRepository triggerObjects = new TriggerObjectRepository(DS);
    private static final SubscriptionRepository subscriptions = new SubscriptionRepository(DS);
    private static final DispatchPoolRepository dispatchPools = new DispatchPoolRepository(DS);
    private static final ScheduledJobRepository scheduledJobs = new ScheduledJobRepository(DS);
    private static final EventTypeRepository eventTypes = new EventTypeRepository(DS);
    private static final ServiceAccountRepository serviceAccounts = new ServiceAccountRepository(DS, Optional.empty());
    private static final FunctionDomainRepository domains = new FunctionDomainRepository(DS);
    private static final FunctionRouteRepository routes = new FunctionRouteRepository(DS);
    private static final FunctionSettingsRepository settings = new FunctionSettingsRepository(DS, Optional.empty());
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final PoolUrlTemplate POOL_URL = PoolUrlTemplate.parse("http://fn-{pool}:8080");
    private static final TriggerSync SYNC = new FunctionTriggerSync(subscriptions, dispatchPools, scheduledJobs,
            eventTypes, triggerObjects, applications, serviceAccounts, versions, DEFAULTS, POOL_URL, domains, routes,
            functions, settings);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static final String[] FULL = {
            Authenticator.TEST_PRINCIPAL, "usr_cm_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:function:view,platform:function:function:manage,"
            + "platform:function:version:publish,platform:function:alias:promote"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(r -> {
            HttpError.install(r);
            r.before("/api/*", auth);
            FunctionApi.register(r, new FunctionApi.State(functions, applications, clients, uow, versions, hosts,
                    policies, DEFAULTS, new Signatures.Off(), SYNC, triggerObjects, subscriptions, dispatchPools,
                    scheduledJobs, settings, Optional.empty(), Optional.empty()));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static int seq = 0;

    private static String fresh() {
        return "t" + (++seq) + "-" + RUN;
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static String persistApplication(String tag) {
        Application app = Application.create(ApplicationType.APPLICATION, "cma" + tag + RUN, "check-manifest app " + tag);
        uow.inTransaction(tx -> {
            applications.persist(app, tx.dbTx());
            return null;
        });
        return app.id();
    }

    private static void persistEventType(String code) {
        EventType et = EventType.create(code, code);
        uow.inTransaction(tx -> {
            eventTypes.persist(et, tx.dbTx());
            return null;
        });
    }

    private static void persistServiceAccount(String applicationId, String signingSecret) {
        org.jooq.impl.DSL.using(DS, org.jooq.SQLDialect.POSTGRES)
                .insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, io.flowcatalyst.platform.shared.tsid.EntityType.SERVICE_ACCOUNT.generate())
                .set(IAM_SERVICE_ACCOUNTS.CODE, "cma-svc-" + fresh())
                .set(IAM_SERVICE_ACCOUNTS.NAME, "check-manifest test service account")
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, true)
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .set(IAM_SERVICE_ACCOUNTS.CREATED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .execute();
    }

    private static Function createFunction(String applicationId) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("cma" + RUN), new DnsLabel("svc"), new DnsLabel(fresh()));
        Function f = Function.create(applicationId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static String manifestJson(int maxConcurrency, List<String> subs) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\",\"pool\":\"default\",\"warm\":false,"
                + "\"limits\":{\"maxConcurrency\":" + maxConcurrency + "},"
                + "\"endpoints\":[{\"path\":\"/events/*\",\"auth\":\"webhook\"}],"
                + "\"subscriptions\":[" + String.join(",", subs) + "]}";
    }

    private static long totalWiringRows(String functionId) {
        return triggerObjects.listByFunction(functionId).size();
    }

    /// `PromoteVersion` requires `READY` (R3) — mark the version ready
    /// directly rather than standing up a whole function-host heartbeat.
    private static void markReady(FunctionAddress address, int version) {
        Function f = functions.findByAddress(address).orElseThrow();
        io.flowcatalyst.platform.function.FunctionVersion v = versions.findByFunctionAndVersion(f.id(), version).orElseThrow();
        if (v.state() instanceof io.flowcatalyst.platform.function.FunctionVersion.VersionState.Published) {
            uow.inTransaction(tx -> {
                versions.persist(v.markReady(Instant.now()), tx.dbTx());
                return null;
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // M2 test 2 (load-bearing): the route writes nothing, and the next
    // real publish still gets the next version number.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void checkWritesNothingAndTheNextRealPublishStillGetsTheNextVersionNumber() {
        String appId = persistApplication("v2");
        Function f = createFunction(appId);
        String manifest = manifestJson(4, List.of());

        // A real publish first, so there is a real version 1 to compare row counts around.
        String publishBody = "{\"artifactRef\":\"oci://artifact/v2-1\",\"digest\":\"" + sha256("v2-1")
                + "\",\"manifest\":" + manifest + "}";
        var published = http.post("/api/functions/" + f.address().render() + "/versions", publishBody, FULL);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);

        long versionsBefore = versions.listByFunction(f.id()).size();
        long wiringBefore = totalWiringRows(f.id());

        String checkBody = "{\"manifest\":" + manifest + ",\"alias\":\"live\"}";
        for (int i = 0; i < 3; i++) {
            var checked = http.post("/api/functions/" + f.address().render() + "/manifest/check", checkBody, FULL);
            assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
            assertThat(json(checked).get("valid").asBoolean()).isTrue();
        }

        assertThat(versions.listByFunction(f.id())).as("mutant: the route calls nextVersion")
                .hasSize((int) versionsBefore);
        assertThat(totalWiringRows(f.id())).as("mutant: the route writes wiring rows").isEqualTo(wiringBefore);

        // The next REAL publish still gets version 2, not 5 (three checks did not burn
        // three version numbers under the function's row lock).
        String publish2Body = "{\"artifactRef\":\"oci://artifact/v2-2\",\"digest\":\"" + sha256("v2-2")
                + "\",\"manifest\":" + manifest + "}";
        var published2 = http.post("/api/functions/" + f.address().render() + "/versions", publish2Body, FULL);
        assertThat(published2.statusCode()).as(published2.body()).isEqualTo(201);
        assertThat(json(published2).get("version").asInt())
                .as("mutant: checkManifest reserved real version numbers").isEqualTo(2);
    }

    /// [FunctionVersionRepository#nextVersion] locks the function row
    /// (`SELECT … FOR UPDATE`) so two concurrent publishes serialise — the
    /// check route must never take that lock (spec: "no row is locked for
    /// update"). A row-count assertion cannot tell a real `nextVersion` call
    /// from a plain read apart when nothing is ever inserted with the
    /// number, so this pins it directly: hold the SAME lock open in another
    /// transaction, then confirm the check call returns promptly instead of
    /// blocking behind it.
    @Test
    void checkNeverTakesTheFunctionRowLockNextVersionUses() throws Exception {
        String appId = persistApplication("v2c");
        Function f = createFunction(appId);
        String manifest = manifestJson(4, List.of());

        var lockHeld = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future = holder.submit(() -> uow.inTransaction(tx -> {
            versions.nextVersion(f.id(), tx.dbTx());
            lockHeld.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        try {
            assertThat(lockHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("lock-holder never started").isTrue();

            long startNanos = System.nanoTime();
            var checked = http.post("/api/functions/" + f.address().render() + "/manifest/check",
                    "{\"manifest\":" + manifest + "}", FULL);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
            assertThat(elapsedMs)
                    .as("mutant: the route calls nextVersion and blocks behind the held FOR UPDATE lock")
                    .isLessThan(3000);
        } finally {
            release.countDown();
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            holder.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // M2 test 3: errors carry the right codes; valid:true + settingsMissing
    // named; a real promote still 409s SETTINGS_MISSING.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void parserErrorIsReportedValidFalsePlanAbsent() {
        String appId = persistApplication("v3a");
        Function f = createFunction(appId);
        String badManifest = "{\"runtime\":\"cobol\",\"entrypoint\":\"com.acme.Fn\"}";
        var checked = http.post("/api/functions/" + f.address().render() + "/manifest/check",
                "{\"manifest\":" + badManifest + "}", FULL);
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        JsonNode body = json(checked);
        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("errors").get(0).get("code").asString()).isEqualTo("RUNTIME_INVALID");
        assertThat(body.has("plan")).as("no plan for an invalid manifest").isFalse();
    }

    @Test
    void unknownEventTypeIsReportedEventTypeNotFoundValidFalsePlanAbsent() {
        String appId = persistApplication("v3b");
        persistServiceAccount(appId, "secret-" + fresh());
        Function f = createFunction(appId);
        String manifest = manifestJson(4, List.of("{\"eventType\":\"nosuch:" + fresh() + "\",\"path\":\"/events/a\"}"));
        var checked = http.post("/api/functions/" + f.address().render() + "/manifest/check",
                "{\"manifest\":" + manifest + "}", FULL);
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        JsonNode body = json(checked);
        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("errors").get(0).get("code").asString()).isEqualTo("EVENT_TYPE_NOT_FOUND");
        assertThat(body.has("plan")).isFalse();
    }

    @Test
    void validManifestMissingADeclaredConfigKeyIsValidTrueWithSettingsMissingNamedAndARealPromoteStill409s() {
        String appId = persistApplication("v3c");
        Function f = createFunction(appId);
        String manifest = "{\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\",\"pool\":\"default\",\"warm\":false,"
                + "\"limits\":{},\"config\":[\"API_KEY\"]}";

        var checked = http.post("/api/functions/" + f.address().render() + "/manifest/check",
                "{\"manifest\":" + manifest + "}", FULL);
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        JsonNode body = json(checked);
        assertThat(body.get("valid").asBoolean()).as("settingsMissing alone must not fail validity").isTrue();
        assertThat(body.get("errors")).isEmpty();
        JsonNode settingsMissing = body.get("plan").get("settingsMissing");
        assertThat(settingsMissing).hasSize(1);
        assertThat(settingsMissing.get(0).asString()).isEqualTo("API_KEY");

        // A real promote of a version with this same manifest still 409s SETTINGS_MISSING —
        // the check route's optimism about `valid` does not change the real precondition.
        String publishBody = "{\"artifactRef\":\"oci://artifact/v3c\",\"digest\":\"" + sha256("v3c")
                + "\",\"manifest\":" + manifest + "}";
        var published = http.post("/api/functions/" + f.address().render() + "/versions", publishBody, FULL);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        markReady(f.address(), 1);

        var promoted = http.put("/api/functions/" + f.address().render() + "/aliases/live", "{\"version\":1}", FULL);
        assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(409);
        assertThat(json(promoted).get("error").asString()).isEqualTo("SETTINGS_MISSING");
    }

    // ═══════════════════════════════════════════════════════════════════
    // M2 test 4: a named alias's plan is HttpOnly; settingsMissing still computed.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void namedAliasPlanIsHttpOnlyButSettingsMissingIsStillComputed() {
        String appId = persistApplication("v4");
        Function f = createFunction(appId);
        String manifest = "{\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\",\"pool\":\"default\",\"warm\":false,"
                + "\"limits\":{},\"config\":[\"SOME_KEY\"]}";
        var checked = http.post("/api/functions/" + f.address().render() + "/manifest/check",
                "{\"manifest\":" + manifest + ",\"alias\":\"qa\"}", FULL);
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        JsonNode body = json(checked);
        assertThat(body.get("valid").asBoolean()).isTrue();
        JsonNode plan = body.get("plan");
        assertThat(plan.get("alias").asString()).isEqualTo("qa");
        assertThat(plan.get("httpOnly").asBoolean()).as("mutant: a named alias plans wiring too").isTrue();
        assertThat(plan.has("pool")).as("mutant: a named alias's plan carries a pool action").isFalse();
        assertThat(plan.get("settingsMissing")).as("settingsMissing is still computed for a named alias")
                .hasSize(1);
        assertThat(plan.get("settingsMissing").get(0).asString()).isEqualTo("SOME_KEY");
    }

    private static String sha256(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((RUN + ":" + s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
