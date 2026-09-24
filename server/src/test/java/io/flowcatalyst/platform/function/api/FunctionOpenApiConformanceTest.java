package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.RoutePattern;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.ArtifactBlobStore;
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
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import io.flowcatalyst.platform.shared.openapi.SchemaValidation;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Drives `functions.openapi.json` against the real function-platform API,
/// in-process, through one deterministic scenario (spec
/// `docs/spec/function-openapi.md` §3, O2/O3/O4): every operation's success
/// path at least once, every sent request body validated against its
/// operation's request schema (O3), every received body validated against
/// its operation+status's response schema (O2), and one refusal per route
/// family validated against `ErrorResponse` with its `error` code checked
/// against that operation+status's documented codes (O4).
///
/// One method, run top to bottom — deterministic by construction, no
/// `@TestMethodOrder` needed. `FunctionOpenApiCoverageTest` covers O1/O5/O6.
@SuppressWarnings("deprecation")
class FunctionOpenApiConformanceTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applications = new ApplicationRepository(TestPg.dataSource());
    private static final ClientRepository clients = new ClientRepository(TestPg.dataSource());
    private static final FunctionRepository functions = new FunctionRepository(TestPg.dataSource());
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(TestPg.dataSource());
    private static final FunctionHostRepository hosts = new FunctionHostRepository(TestPg.dataSource());
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(TestPg.dataSource());
    private static final TriggerObjectRepository triggerObjects = new TriggerObjectRepository(TestPg.dataSource());
    private static final SubscriptionRepository subscriptions = new SubscriptionRepository(TestPg.dataSource());
    private static final DispatchPoolRepository dispatchPools = new DispatchPoolRepository(TestPg.dataSource());
    private static final ScheduledJobRepository scheduledJobs = new ScheduledJobRepository(TestPg.dataSource());
    private static final FunctionDomainRepository domains = new FunctionDomainRepository(TestPg.dataSource());
    private static final FunctionRouteRepository routeRepo = new FunctionRouteRepository(TestPg.dataSource());
    private static final ServiceAccountRepository serviceAccounts =
            new ServiceAccountRepository(TestPg.dataSource(), Optional.empty());
    private static final EventTypeRepository eventTypes = new EventTypeRepository(TestPg.dataSource());
    private static final EventRepository events = new EventRepository(TestPg.dataSource());
    private static final Encryption ENCRYPTION = Encryption.withKey("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=");
    private static final FunctionSettingsRepository settings =
            new FunctionSettingsRepository(TestPg.dataSource(), Optional.of(ENCRYPTION));
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

    @TempDir
    static Path storeDir;

    private static ArtifactBlobStore store;
    private static TestHttp http;
    private static TestHttp httpNoStore;

    private static Lockfile document;
    private static Map<String, Lockfile.Operation> byOperationId;

    /// Every `operationId` this scenario has driven at least once — asserted
    /// against the document's own set at the very end (O2).
    private static final Set<String> EXERCISED = new LinkedHashSet<>();

    private static final String[] FULL = {
            Authenticator.TEST_PRINCIPAL, "usr_full_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, String.join(",",
                    "platform:function:function:view", "platform:function:function:manage",
                    "platform:function:version:publish", "platform:function:alias:promote",
                    "platform:function:secret:manage", "platform:function:domain:manage",
                    "platform:function:policy:manage", "platform:function:host:control")};

    private static final String[] VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, "usr_view_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};

    @BeforeAll
    static void start() {
        store = new FileArtifactBlobStore(storeDir);
        http = harness(Optional.of(store));
        httpNoStore = harness(Optional.empty());
        document = Lockfile.load(Json.MAPPER, "openapi/functions.openapi.json");
        byOperationId = new LinkedHashMap<>();
        for (var op : document.operations()) {
            byOperationId.put(op.operationId(), op);
        }
    }

    private static TestHttp harness(Optional<ArtifactBlobStore> artifactStore) {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        return TestHttp.routes(r -> {
            HttpError.install(r);
            r.before(auth);
            FunctionApi.register(r, new FunctionApi.State(functions, applications, clients, uow, versions, hosts,
                    policies, FunctionLimits.defaults(), new Signatures.Off(), TriggerSync.none(), triggerObjects,
                    subscriptions, dispatchPools, scheduledJobs, settings, Optional.of(ENCRYPTION), artifactStore));
            FunctionPolicyApi.register(r, new FunctionPolicyApi.State(policies, clients, uow, FunctionLimits.defaults()));
            FunctionDomainApi.register(r, new FunctionDomainApi.State(domains, routeRepo, functions, uow));
            FunctionControlApi.register(r, new FunctionControlApi.State(functions, versions, hosts, uow,
                    serviceAccounts, settings, applications, eventTypes, events, routeRepo, artifactStore));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        httpNoStore.close();
    }

    // ── schema plumbing ───────────────────────────────────────────────────

    private static JsonNode operationNode(String operationId) {
        var op = byOperationId.get(operationId);
        assertThat(op).as("unknown operationId: " + operationId).isNotNull();
        return document.operationNode(op.method(), op.path());
    }

    private static void assertRequestConforms(String operationId, JsonNode requestBody) {
        var schema = operationNode(operationId).path("requestBody").path("content").path("application/json").path("schema");
        if (schema.isMissingNode()) {
            return;
        }
        var errors = SchemaValidation.validate(document, schema, "body", requestBody);
        assertThat(errors).as(() -> operationId + " request body must conform to its schema: " + errors).isEmpty();
    }

    private static void assertResponseConforms(String operationId, int status, JsonNode responseBody) {
        var schema = operationNode(operationId).path("responses").path(String.valueOf(status))
                .path("content").path("application/json").path("schema");
        if (schema.isMissingNode()) {
            return; // 204/octet-stream — nothing JSON-shaped to check here
        }
        var errors = SchemaValidation.validate(document, schema, "response", responseBody);
        assertThat(errors).as(() -> operationId + " " + status + " response must conform to its schema: " + errors)
                .isEmpty();
    }

    /// O4: the body validates against `ErrorResponse`, and the `error` code
    /// it carries appears in that operation+status's documented description.
    private static void assertErrorConforms(String operationId, int status, JsonNode errorBody) {
        var statusNode = operationNode(operationId).path("responses").path(String.valueOf(status));
        assertThat(statusNode.isMissingNode()).as(operationId + " must document status " + status).isFalse();
        var schema = statusNode.path("content").path("application/json").path("schema");
        var errors = SchemaValidation.validate(document, schema, "error", errorBody);
        assertThat(errors).as(() -> operationId + " " + status + " error body must conform to ErrorResponse: " + errors)
                .isEmpty();
        String code = errorBody.get("error").asText();
        String description = statusNode.path("description").asText("");
        assertThat(description).as("mutant: remove the code from the operation+status description")
                .as(operationId + " " + status + " description must name " + code + " (was: " + description + ")")
                .contains(code);
    }

    private static JsonNode readBody(HttpResponse<String> r) {
        if (r.body() == null || r.body().isBlank()) {
            return null;
        }
        return Json.MAPPER.readTree(r.body());
    }

    /// Drives one JSON-in/JSON-out call: records `operationId` exercised,
    /// asserts the status, and checks both directions against the document.
    private static JsonNode call(String operationId, HttpResponse<String> r, int expectedStatus, String requestJson) {
        EXERCISED.add(operationId);
        assertThat(r.statusCode()).as(() -> operationId + ": " + r.body()).isEqualTo(expectedStatus);
        if (requestJson != null) {
            assertRequestConforms(operationId, Json.MAPPER.readTree(requestJson));
        }
        JsonNode body = readBody(r);
        if (body != null) {
            assertResponseConforms(operationId, expectedStatus, body);
        }
        return body;
    }

    private static String digestOf(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── the scenario ──────────────────────────────────────────────────────

    @Test
    void everyOperationRespondsRequestsAndErrorsConformToTheDocument() {
        String appCode = "fn-openapi-" + RUN;
        Application app = Application.create(ApplicationType.APPLICATION, appCode, "Function OpenAPI " + RUN);
        uow.inTransaction(tx -> {
            applications.persist(app, tx.dbTx());
            return null;
        });

        // 1. createFunction
        JsonNode created = call("createFunction", http.post("/api/functions",
                        "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"svc\",\"name\":\"fn\",\"runtime\":\"jvm\"}", FULL),
                201, "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"svc\",\"name\":\"fn\",\"runtime\":\"jvm\"}");
        String address = created.get("address").asText();
        String functionId = created.get("id").asText();

        // 2. updateFunction
        call("updateFunction", http.put("/api/functions/" + address, "{\"description\":\"updated\"}", FULL),
                204, "{\"description\":\"updated\"}");

        // 3. getFunction
        call("getFunction", http.get("/api/functions/" + address, FULL), 200, null);

        // 4. listFunctions
        call("listFunctions", http.get("/api/functions?address=" + appCode + ".svc.fn", FULL), 200, null);

        // 5/6. putFunctionPolicy / getFunctionPolicy (platform owner)
        String policyBody = "{\"signers\":[],\"ceilings\":{\"maxDurationMs\":30000,\"maxConcurrency\":10,"
                + "\"maxWasmMemoryMb\":128,\"maxDbPoolSize\":5}}";
        call("putFunctionPolicy", http.put("/api/function-policies/platform", policyBody, FULL), 200, policyBody);
        call("getFunctionPolicy", http.get("/api/function-policies/platform", FULL), 200, null);

        // 6b. listFunctionPolicies (S2) — the platform row we just wrote is in it.
        JsonNode policyList = call("listFunctionPolicies", http.get("/api/function-policies", FULL), 200, null);
        assertThat(policyList.get("policies")).as("the platform row we just wrote is listed")
                .anySatisfy(p -> assertThat(p.get("owner").asText()).isEqualTo("platform"));

        // 7. uploadFunctionArtifact (version 1)
        byte[] bytes1 = ("openapi-payload-1-" + RUN).repeat(100).getBytes(StandardCharsets.UTF_8);
        String digest1 = digestOf(bytes1);
        var upload1 = http.putBytes("/api/functions/" + address + "/artifacts/" + digest1, bytes1, FULL);
        EXERCISED.add("uploadFunctionArtifact");
        assertThat(upload1.statusCode()).as(new String(upload1.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        JsonNode upload1Body = Json.MAPPER.readTree(new String(upload1.body(), StandardCharsets.UTF_8));
        assertResponseConforms("uploadFunctionArtifact", 200, upload1Body);
        String ref1 = upload1Body.get("artifactRef").asText();

        // 8. publishFunctionVersion (version 1)
        String manifest1 = "{\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\",\"pool\":\"default\","
                + "\"endpoints\":[{\"path\":\"/\",\"auth\":\"none\",\"methods\":[\"GET\"]}]}";
        String publish1Body = "{\"artifactRef\":\"" + ref1 + "\",\"digest\":\"" + digest1 + "\",\"manifest\":" + manifest1 + "}";
        JsonNode published1 = call("publishFunctionVersion",
                http.post("/api/functions/" + address + "/versions", publish1Body, FULL), 201, publish1Body);
        String versionId1 = published1.get("id").asText();

        // 9/10. listFunctionVersions / getFunctionVersion
        call("listFunctionVersions", http.get("/api/functions/" + address + "/versions", FULL), 200, null);
        call("getFunctionVersion", http.get("/api/functions/" + address + "/versions/1", FULL), 200, null);

        // 10b. checkManifest (spec function-manifest-authoring.md M2.2) — a valid manifest,
        // dry-run promoted to live: writes nothing (no version is reserved here). This
        // harness wires TriggerSync.none() (same precedent as FunctionControlApiTest below),
        // whose plan() is the degenerate HttpOnly stub for every alias including live —
        // FunctionTriggerSyncTest exercises the REAL wiring-plan classification.
        String checkBody = "{\"manifest\":" + manifest1 + ",\"alias\":\"live\"}";
        JsonNode checked = call("checkManifest",
                http.post("/api/functions/" + address + "/manifest/check", checkBody, FULL), 200, checkBody);
        assertThat(checked.get("valid").asBoolean()).as("mutant: a valid manifest reported invalid").isTrue();
        assertThat(checked.get("errors")).isEmpty();
        assertThat(checked.get("plan").get("alias").asText()).isEqualTo("live");
        assertThat(checked.get("plan").get("toVersion").asInt()).as("preview: next after v1").isEqualTo(2);

        // O4 (checkManifest family): an invalid manifest is still 200, valid:false, never a
        // thrown error — raw http (not `call`) since the bad body itself must NOT conform to
        // the request schema (S4's publishFunctionVersion pair below does the same for that
        // reason).
        String badCheckBody = "{\"manifest\":{\"runtime\":\"cobol\",\"entrypoint\":\"com.acme.Fn\"}}";
        var badCheckResponse = http.post("/api/functions/" + address + "/manifest/check", badCheckBody, FULL);
        EXERCISED.add("checkManifest");
        assertThat(badCheckResponse.statusCode()).as(badCheckResponse.body()).isEqualTo(200);
        JsonNode badChecked = Json.MAPPER.readTree(badCheckResponse.body());
        assertResponseConforms("checkManifest", 200, badChecked);
        assertThat(badChecked.get("valid").asBoolean()).isFalse();
        assertThat(badChecked.get("errors").get(0).get("code").asText()).isEqualTo("RUNTIME_INVALID");
        assertThat(badChecked.has("plan")).as("mutant: compute a plan for an invalid manifest").isFalse();

        // 11. heartbeatFunctionHost — reports v1 LOADED, which marks it READY
        String hostId = "host-" + RUN;
        String heartbeatBody = "{\"hostId\":\"" + hostId + "\",\"pool\":\"default\",\"state\":\"ACTIVE\","
                + "\"loaded\":[{\"address\":\"" + address + "\",\"version\":1,\"state\":\"LOADED\"}]}";
        call("heartbeatFunctionHost", http.post("/control/functions/heartbeat", heartbeatBody, FULL), 204, heartbeatBody);

        // O4 (409, part of the promote/publish family): promoting an UN-ready version.
        String publishV2Manifest = manifest1;
        byte[] bytes2 = ("openapi-payload-2-" + RUN).repeat(100).getBytes(StandardCharsets.UTF_8);
        String digest2 = digestOf(bytes2);
        var upload2 = http.putBytes("/api/functions/" + address + "/artifacts/" + digest2, bytes2, FULL);
        assertThat(upload2.statusCode()).isEqualTo(200);
        JsonNode upload2Body = Json.MAPPER.readTree(new String(upload2.body(), StandardCharsets.UTF_8));
        String ref2 = upload2Body.get("artifactRef").asText();
        String publish2Body = "{\"artifactRef\":\"" + ref2 + "\",\"digest\":\"" + digest2 + "\",\"manifest\":" + publishV2Manifest + "}";
        JsonNode published2 = call("publishFunctionVersion",
                http.post("/api/functions/" + address + "/versions", publish2Body, FULL), 201, publish2Body);
        int version2Number = published2.get("version").asInt();

        var notReadyPromote = http.put("/api/functions/" + address + "/aliases/live",
                "{\"version\":" + version2Number + "}", FULL);
        assertThat(notReadyPromote.statusCode()).as(notReadyPromote.body()).isEqualTo(409);
        assertErrorConforms("promoteFunctionAlias", 409, Json.MAPPER.readTree(notReadyPromote.body()));

        // 12. promoteFunctionAlias (version 1, which IS ready)
        JsonNode promoted = call("promoteFunctionAlias",
                http.put("/api/functions/" + address + "/aliases/live", "{\"version\":1}", FULL), 200, "{\"version\":1}");
        assertThat(promoted.get("version").asInt()).isEqualTo(1);

        // 13. listFunctionAliases
        call("listFunctionAliases", http.get("/api/functions/" + address + "/aliases", FULL), 200, null);

        // 13b/13c/13d (spec function-zones-and-aliases.md §2): a NAMED alias — point `qa`
        // at v1 (already READY), confirm it is listed, then remove it.
        JsonNode promotedQa = call("promoteFunctionAlias",
                http.put("/api/functions/" + address + "/aliases/qa", "{\"version\":1}", FULL), 200, "{\"version\":1}");
        assertThat(promotedQa.get("alias").asText()).isEqualTo("qa");

        JsonNode aliasesWithQa = call("listFunctionAliases", http.get("/api/functions/" + address + "/aliases", FULL), 200, null);
        assertThat(aliasesWithQa).hasSize(2);

        call("deleteFunctionAlias", http.delete("/api/functions/" + address + "/aliases/qa", FULL), 204, null);
        JsonNode aliasesAfterDelete = call("listFunctionAliases", http.get("/api/functions/" + address + "/aliases", FULL), 200, null);
        assertThat(aliasesAfterDelete).hasSize(1);

        // O4 (deleteFunctionAlias family): live is protected, and an unknown alias is 404.
        var deleteLiveProtected = http.delete("/api/functions/" + address + "/aliases/live", FULL);
        assertThat(deleteLiveProtected.statusCode()).isEqualTo(409);
        assertErrorConforms("deleteFunctionAlias", 409, Json.MAPPER.readTree(deleteLiveProtected.body()));

        var deleteUnknown = http.delete("/api/functions/" + address + "/aliases/nosuch", FULL);
        assertThat(deleteUnknown.statusCode()).isEqualTo(404);
        assertErrorConforms("deleteFunctionAlias", 404, Json.MAPPER.readTree(deleteUnknown.body()));

        // 14. getFunctionStatus
        call("getFunctionStatus", http.get("/api/functions/" + address + "/status", FULL), 200, null);

        // 15. listFunctionPools
        call("listFunctionPools", http.get("/api/function-pools", FULL), 200, null);

        // 16/17. setFunctionConfig / getFunctionConfig
        String configBody = "{\"values\":{\"FOO\":\"bar\"}}";
        call("setFunctionConfig", http.put("/api/functions/" + address + "/config", configBody, FULL), 200, configBody);
        call("getFunctionConfig", http.get("/api/functions/" + address + "/config", FULL), 200, null);

        // 18/19/20. setFunctionSecret / listFunctionSecrets / deleteFunctionSecret
        String secretBody = "{\"value\":\"shh\"}";
        call("setFunctionSecret", http.put("/api/functions/" + address + "/secrets/mysecret", secretBody, FULL), 204, secretBody);
        call("listFunctionSecrets", http.get("/api/functions/" + address + "/secrets", FULL), 200, null);
        call("deleteFunctionSecret", http.delete("/api/functions/" + address + "/secrets/mysecret", FULL), 204, null);

        // 21. claimFunctionDomain — immediately usable, no verify step (function-domains-no-dns.md)
        String hostname = "fnopenapi-" + RUN + ".example.com";
        String claimBody = "{\"hostname\":\"" + hostname + "\"}";
        call("claimFunctionDomain", http.post("/api/function-domains", claimBody, FULL), 201, claimBody);

        // 23. listFunctionDomains
        call("listFunctionDomains", http.get("/api/function-domains?clientId=platform", FULL), 200, null);

        // 23b. getFunctionDomain (S3)
        call("getFunctionDomain", http.get("/api/function-domains/" + hostname, FULL), 200, null);

        // 24. listFunctionRoutes — persist the route a real promote would have materialised
        // (this scenario uses TriggerSync.none(), same precedent as FunctionControlApiTest).
        FunctionRoute route = FunctionRoute.of(functionId, Hostname.parse(hostname), RoutePattern.parse("/"), List.of(), Instant.now());
        uow.inTransaction(tx -> {
            routeRepo.replaceForFunction(functionId, List.of(route), tx.dbTx());
            return null;
        });
        call("listFunctionRoutes", http.get("/api/function-routes?address=" + address, FULL), 200, null);

        // 25. getDesiredState
        JsonNode desired = call("getDesiredState", http.get("/control/functions/desired-state?pool=default", FULL), 200, null);
        assertThat(desired.get("publicRoutes").size()).as("desired-state must carry our claimed public route").isEqualTo(1);

        // 26. emitFunctionEvents
        String eventType = appCode + ":orders:order:created";
        EventType et = EventType.create(eventType, "OpenAPI scenario event");
        uow.inTransaction(tx -> {
            eventTypes.persist(et, tx.dbTx());
            return null;
        });
        String emitBody = "{\"hostId\":\"" + hostId + "\",\"address\":\"" + address + "\",\"version\":1,"
                + "\"events\":[{\"type\":\"" + eventType + "\",\"dedupId\":\"dd-" + RUN + "\",\"data\":{\"k\":1}}]}";
        call("emitFunctionEvents", http.post("/control/functions/events", emitBody, FULL), 201, emitBody);

        // 27. downloadFunctionArtifact
        EXERCISED.add("downloadFunctionArtifact");
        var download = http.getBytes("/control/functions/artifacts/" + versionId1, FULL);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Type")).contains("application/octet-stream");
        assertThat(download.body()).isEqualTo(bytes1);

        // 28. retireFunctionVersion (version 2 — never promoted, safe to retire)
        JsonNode retired = call("retireFunctionVersion",
                http.post("/api/functions/" + address + "/versions/" + version2Number + "/retire", null, FULL), 200, null);
        assertThat(retired.get("state").asText()).isEqualTo("RETIRED");

        // Clear the route so releasing the domain does not hit DOMAIN_IN_USE (spec
        // `function-public-routes.md` §1) — a real promote-driven release would have
        // reconciled this away; this scenario writes the route by hand (see #24).
        uow.inTransaction(tx -> {
            routeRepo.replaceForFunction(functionId, List.of(), tx.dbTx());
            return null;
        });

        // 29. releaseFunctionDomain
        call("releaseFunctionDomain", http.delete("/api/function-domains/" + hostname, FULL), 204, null);

        // 30. deleteFunction
        call("deleteFunction", http.delete("/api/functions/" + address, FULL), 204, null);

        // ── O4: one refusal per route family, error-shape + documented-code checked ──

        // 404 unknown function.
        var notFound = http.get("/api/functions/nosuch." + RUN + ".fn", FULL);
        assertThat(notFound.statusCode()).isEqualTo(404);
        JsonNode notFoundBody = Json.MAPPER.readTree(notFound.body());
        assertThat(notFoundBody.get("error").asText()).isEqualTo("Function_NOT_FOUND");
        assertErrorConforms("getFunction", 404, notFoundBody);

        // 403 missing permission.
        var forbidden = http.post("/api/functions",
                "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"svc2\",\"name\":\"fn2\",\"runtime\":\"jvm\"}",
                VIEW_ONLY);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        JsonNode forbiddenBody = Json.MAPPER.readTree(forbidden.body());
        assertThat(forbiddenBody.get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertErrorConforms("createFunction", 403, forbiddenBody);

        // 422 from the upload/publish family: an empty upload against a throwaway function
        // (the scenario's own function was just deleted above).
        var created2 = http.post("/api/functions",
                "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"svc2\",\"name\":\"fn2\",\"runtime\":\"jvm\"}", FULL);
        assertThat(created2.statusCode()).as(created2.body()).isEqualTo(201);
        String address2 = Json.MAPPER.readTree(created2.body()).get("address").asText();
        var empty = http.putBytes("/api/functions/" + address2 + "/artifacts/" + digest1, new byte[0], FULL);
        assertThat(empty.statusCode()).as(new String(empty.body(), StandardCharsets.UTF_8)).isEqualTo(422);
        JsonNode emptyBody = Json.MAPPER.readTree(new String(empty.body(), StandardCharsets.UTF_8));
        assertThat(emptyBody.get("error").asText()).isEqualTo("ARTIFACT_EMPTY");
        assertErrorConforms("uploadFunctionArtifact", 422, emptyBody);

        // 503 store-not-configured, via the no-store harness — another throwaway function.
        var created3 = httpNoStore.post("/api/functions",
                "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"svc3\",\"name\":\"fn3\",\"runtime\":\"jvm\"}", FULL);
        assertThat(created3.statusCode()).as(created3.body()).isEqualTo(201);
        String address3 = Json.MAPPER.readTree(created3.body()).get("address").asText();
        var noStore = httpNoStore.putBytes("/api/functions/" + address3 + "/artifacts/" + digest1, bytes1, FULL);
        assertThat(noStore.statusCode()).isEqualTo(503);
        JsonNode noStoreBody = Json.MAPPER.readTree(new String(noStore.body(), StandardCharsets.UTF_8));
        assertThat(noStoreBody.get("error").asText()).isEqualTo("ARTIFACT_STORE_NOT_CONFIGURED");
        assertErrorConforms("uploadFunctionArtifact", 503, noStoreBody);

        // S4: two 400 refusals from publishFunctionVersion's manifest validation,
        // against another throwaway function — an unknown manifest key, and an
        // unrecognised runtime. Both codes must be named in the operation's 400
        // description (mutant: remove either code from the description).
        var created4 = http.post("/api/functions",
                "{\"applicationCode\":\"" + appCode + "\",\"serviceName\":\"svc4\",\"name\":\"fn4\",\"runtime\":\"jvm\"}", FULL);
        assertThat(created4.statusCode()).as(created4.body()).isEqualTo(201);
        String address4 = Json.MAPPER.readTree(created4.body()).get("address").asText();

        String unknownFieldManifest = "{\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\",\"bogus\":true}";
        String unknownFieldBody = "{\"artifactRef\":\"oci://artifact/s4a\",\"digest\":\"" + digestOf("s4a".getBytes(StandardCharsets.UTF_8))
                + "\",\"manifest\":" + unknownFieldManifest + "}";
        var unknownField = http.post("/api/functions/" + address4 + "/versions", unknownFieldBody, FULL);
        assertThat(unknownField.statusCode()).as(unknownField.body()).isEqualTo(400);
        JsonNode unknownFieldError = Json.MAPPER.readTree(unknownField.body());
        assertThat(unknownFieldError.get("error").asText()).isEqualTo("MANIFEST_UNKNOWN_FIELD");
        assertErrorConforms("publishFunctionVersion", 400, unknownFieldError);

        String badRuntimeManifest = "{\"runtime\":\"cobol\",\"entrypoint\":\"com.acme.Fn\"}";
        String badRuntimeBody = "{\"artifactRef\":\"oci://artifact/s4b\",\"digest\":\"" + digestOf("s4b".getBytes(StandardCharsets.UTF_8))
                + "\",\"manifest\":" + badRuntimeManifest + "}";
        var badRuntime = http.post("/api/functions/" + address4 + "/versions", badRuntimeBody, FULL);
        assertThat(badRuntime.statusCode()).as(badRuntime.body()).isEqualTo(400);
        JsonNode badRuntimeError = Json.MAPPER.readTree(badRuntime.body());
        assertThat(badRuntimeError.get("error").asText()).isEqualTo("RUNTIME_INVALID");
        assertErrorConforms("publishFunctionVersion", 400, badRuntimeError);

        // ── O2: every documented operation was exercised at least once ──────
        Set<String> documented = new LinkedHashSet<>(byOperationId.keySet());
        assertThat(EXERCISED).as("mutant: leave an operation out of the scenario").isEqualTo(documented);
    }
}
