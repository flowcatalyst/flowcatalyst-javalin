package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.JndiTxtResolver;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.FunctionOpenApiRoutes;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import io.flowcatalyst.platform.shared.openapi.SchemaValidation;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// Keeps `functions.openapi.json` honest against the real route registry and
/// against OpenAPI 3.1's own structural rules (spec `docs/spec/function-openapi.md`
/// §3, O1/O5/O6). `FunctionOpenApiConformanceTest` covers O2/O3/O4 — response,
/// request and error conformance, which need a running scenario, not a
/// document walk.
class FunctionOpenApiCoverageTest {

    private static final String RESOURCE = "openapi/functions.openapi.json";

    /// The function-platform path prefixes `functions.openapi.json` claims to
    /// document — the same four `Api` classes named in the spec's §1 (and
    /// `LockfileCoverageTest`'s own `/api/function` outside-lockfile entry).
    private static final List<String> FUNCTION_PREFIXES =
            List.of("/api/functions", "/api/function-pools", "/api/function-policies", "/api/function-domains",
                    "/api/function-routes", "/control/functions");

    private static Lockfile document() {
        return Lockfile.load(Json.MAPPER, RESOURCE);
    }

    // ── O1: coverage both ways ───────────────────────────────────────────────

    /// The four `Api` classes named in the spec's §1, registered exactly as
    /// `Platform#register` wires them — a package-private seam
    /// (`Server#buildApi`) means this test cannot reach the composition
    /// root's own registry from `platform.function.api` (spec §1's routes
    /// live in this package's own classes anyway; `LockfileCoverageTest`,
    /// which needs the WHOLE platform's routes, stays in `io.flowcatalyst.server`).
    private static RouteRegistry registerFunctionRoutes() {
        var applications = new ApplicationRepository(TestPg.dataSource());
        var clients = new ClientRepository(TestPg.dataSource());
        var functions = new FunctionRepository(TestPg.dataSource());
        var versions = new FunctionVersionRepository(TestPg.dataSource());
        var hosts = new FunctionHostRepository(TestPg.dataSource());
        var policies = new ClientPolicyRepository(TestPg.dataSource());
        var triggerObjects = new TriggerObjectRepository(TestPg.dataSource());
        var subscriptions = new SubscriptionRepository(TestPg.dataSource());
        var dispatchPools = new DispatchPoolRepository(TestPg.dataSource());
        var scheduledJobs = new ScheduledJobRepository(TestPg.dataSource());
        var settings = new FunctionSettingsRepository(TestPg.dataSource(), java.util.Optional.empty());
        var domains = new FunctionDomainRepository(TestPg.dataSource());
        var routes = new FunctionRouteRepository(TestPg.dataSource());
        var serviceAccounts = new ServiceAccountRepository(TestPg.dataSource(), java.util.Optional.empty());
        var eventTypes = new EventTypeRepository(TestPg.dataSource());
        var events = new EventRepository(TestPg.dataSource());
        var uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

        TestHttp http = TestHttp.routes(r -> {
            HttpError.install(r);
            FunctionApi.register(r, new FunctionApi.State(functions, applications, clients, uow, versions, hosts,
                    policies, FunctionLimits.defaults(), new Signatures.Off(), TriggerSync.none(), triggerObjects,
                    subscriptions, dispatchPools, scheduledJobs, settings, java.util.Optional.empty(), java.util.Optional.empty()));
            FunctionPolicyApi.register(r, new FunctionPolicyApi.State(policies, clients, uow, FunctionLimits.defaults()));
            FunctionDomainApi.register(r, new FunctionDomainApi.State(domains, routes, functions, uow,
                    new JndiTxtResolver(), false));
            FunctionControlApi.register(r, new FunctionControlApi.State(functions, versions, hosts, uow,
                    serviceAccounts, settings, applications, eventTypes, events, routes, java.util.Optional.empty()));
        });
        RouteRegistry registry = http.registry();
        http.close();
        return registry;
    }

    @Test
    void everyRegisteredFunctionRouteIsInTheDocumentAndViceVersa() {
        RouteRegistry registry = registerFunctionRoutes();

        Set<String> registered = new TreeSet<>();
        for (var reg : registry.registrations()) {
            String route = reg.method() + " " + reg.path();
            if (FUNCTION_PREFIXES.stream().anyMatch(p -> reg.path().equals(p) || reg.path().startsWith(p + "/") || reg.path().startsWith(p + "?"))) {
                registered.add(route);
            }
        }
        // Narrow to the EXACT prefixes above — a startsWith on "/api/function-domains" would
        // also match "/api/function-domains/{hostname}/verify", which is intended, but must
        // NOT match an unrelated sibling like "/api/functions-x"; the "/" join above already
        // guards that.

        var doc = document();
        Set<String> documented = new LinkedHashSet<>();
        for (var op : doc.operations()) {
            documented.add(op.method() + " " + op.path());
        }

        var missingFromDocument = registered.stream().filter(r -> !documented.contains(r)).toList();
        assertThat(missingFromDocument).as("routes registered under a function prefix but absent from " + RESOURCE)
                .isEmpty();

        var missingRoute = documented.stream().filter(r -> !registered.contains(r)).toList();
        assertThat(missingRoute).as("operations documented in " + RESOURCE + " with no registered route")
                .isEmpty();

        assertThat(registered).as("mutant: delete a route from the registry or the document").hasSize(30);
    }

    // ── O5: the document is structurally valid OpenAPI 3.1 ──────────────────

    @Test
    void operationIdsAreUnique() {
        var doc = document();
        List<String> ids = doc.operations().stream().map(Lockfile.Operation::operationId).toList();
        assertThat(ids).as("mutant: duplicate an operationId").doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank());
    }

    @Test
    void everyRefResolves() {
        var doc = document();
        var visited = new HashSet<String>();
        walkAndResolve(doc, doc.json(), visited);
    }

    private static void walkAndResolve(Lockfile doc, JsonNode node, Set<String> visited) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has("$ref")) {
                String ref = node.path("$ref").asString();
                assertThat(ref).as("mutant: point a $ref at a schema that does not exist").startsWith("#/");
                if (!visited.add(ref)) return; // cycle guard
                JsonNode target = doc.json().at(ref.substring(1));
                assertThat(target.isMissingNode()).as("$ref target not found: " + ref).isFalse();
            }
            for (var entry : node.properties()) {
                walkAndResolve(doc, entry.getValue(), visited);
            }
        } else if (node.isArray()) {
            for (var item : node) {
                walkAndResolve(doc, item, visited);
            }
        }
    }

    @Test
    void everyPathParameterInTheTemplateIsDeclaredAndRequired() {
        var doc = document();
        JsonNode paths = doc.json().path("paths");
        for (var pathEntry : paths.properties()) {
            String template = pathEntry.getKey();
            Set<String> templateParams = new LinkedHashSet<>();
            var m = Pattern.compile("\\{(\\w+)\\}").matcher(template);
            while (m.find()) templateParams.add(m.group(1));

            for (var opEntry : pathEntry.getValue().properties()) {
                if (!Set.of("get", "put", "post", "delete").contains(opEntry.getKey())) continue;
                JsonNode op = opEntry.getValue();
                Set<String> declared = new LinkedHashSet<>();
                for (JsonNode param : op.path("parameters")) {
                    if ("path".equals(param.path("in").asString())) {
                        String name = param.path("name").asString();
                        declared.add(name);
                        assertThat(param.path("required").asBoolean(false))
                                .as("mutant: a path parameter declared but not required (" + template + " " + name + ")")
                                .isTrue();
                    }
                }
                assertThat(declared).as("path parameters for " + opEntry.getKey().toUpperCase() + " " + template)
                        .isEqualTo(templateParams);
            }
        }
    }

    @Test
    void everyOperationHasAtLeastOneTwoXxResponse() {
        var doc = document();
        JsonNode paths = doc.json().path("paths");
        for (var pathEntry : paths.properties()) {
            for (var opEntry : pathEntry.getValue().properties()) {
                if (!Set.of("get", "put", "post", "delete").contains(opEntry.getKey())) continue;
                JsonNode responses = opEntry.getValue().path("responses");
                boolean hasTwoXx = false;
                for (var respEntry : responses.properties()) {
                    if (respEntry.getKey().startsWith("2")) hasTwoXx = true;
                }
                assertThat(hasTwoXx)
                        .as("mutant: an operation with no 2xx response (" + opEntry.getKey() + " " + pathEntry.getKey() + ")")
                        .isTrue();
            }
        }
    }

    @Test
    void everySchemaNodeUsesOnlyImplementedKeywords() {
        var doc = document();
        var visitedRefs = new HashSet<String>();
        for (var op : doc.operations()) {
            JsonNode opNode = doc.operationNode(op.method(), op.path());
            JsonNode bodySchema = opNode.path("requestBody").path("content").path("application/json").path("schema");
            if (!bodySchema.isMissingNode()) {
                SchemaValidation.checkKeywords(doc, bodySchema, visitedRefs);
            }
            for (var respEntry : opNode.path("responses").properties()) {
                JsonNode schema = respEntry.getValue().path("content").path("application/json").path("schema");
                if (!schema.isMissingNode()) {
                    SchemaValidation.checkKeywords(doc, schema, visitedRefs);
                }
            }
        }
    }

    // ── O6: served verbatim, unauthenticated ─────────────────────────────────

    @Test
    void servesTheDocumentBytesVerbatimWithoutAToken() {
        try (TestHttp http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            new FunctionOpenApiRoutes(document()).register(routes);
        })) {
            HttpResponse<String> r = http.get("/api/openapi-functions.json");
            assertThat(r.statusCode()).as("mutant: require auth on the spec route").isEqualTo(200);
            byte[] expected = resourceBytes();
            assertThat(r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .as("mutant: serve a re-encoded/different copy instead of the resource's own bytes")
                    .isEqualTo(expected);
            assertThat(r.headers().firstValue("Content-Type")).contains("application/json");
        }
    }

    private static byte[] resourceBytes() {
        try (InputStream in = FunctionOpenApiCoverageTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
