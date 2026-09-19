package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-invocation.md` §3 (amending `function-registry.md` §4):
/// the full happy path, every rule of the amended manifest, and
/// `readStored`'s tolerance contract.
///
/// Note (report): §4.1's example JSON of `function-registry.md` shows
/// `wasmMemoryMb` on a `jvm` manifest, but §4.3's `LIMIT_NOT_APPLICABLE`
/// forbids exactly that combination. The JVM fixtures below omit
/// `wasmMemoryMb`; a dedicated Wasm fixture exercises it instead.
class ManifestTest {

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "endpoints": [ { "path": "/events/invoice-created", "auth": "webhook" } ],
              "subscriptions": [ { "eventType": "billing:invoices:invoice:created", "path": "/events/invoice-created" } ]
            }
            """;

    private static Manifest parseJvm(String json) {
        return Manifest.parseStrict(readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static Manifest parseJvm(String json, ClientCeilings ceilings) {
        return Manifest.parseStrict(readTree(json), Runtime.JVM, DEFAULTS, ceilings);
    }

    private static Manifest parseWasm(String json) {
        return Manifest.parseStrict(readTree(json), Runtime.WASM, DEFAULTS, UNRESTRICTED);
    }

    private static JsonNode readTree(String json) {
        return Json.MAPPER.readTree(json);
    }

    private static void assertCode(ThrowableFn call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    @FunctionalInterface
    private interface ThrowableFn {
        void run();
    }

    // ── the full happy path — spec §3 ────────────────────────────────────────

    private static final String FULL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "pool": "default",
              "warm": false,
              "limits": { "maxDurationMs": 30000, "maxConcurrency": 32 },
              "endpoints": [
                { "path": "/events/*",  "auth": "webhook" },
                { "path": "/jobs/*",    "auth": "webhook" },
                { "path": "/api/*",     "auth": "platform", "methods": ["GET","POST"],
                  "cors": { "origins": ["https://app.acme.com"] },
                  "maxBodyBytes": 1048576, "timeoutMs": 10000 },
                { "path": "/hooks/stripe", "auth": "none" }
              ],
              "subscriptions": [
                { "eventType": "billing:invoices:invoice:created", "path": "/events/invoice-created",
                  "mode": "BLOCK_ON_ERROR", "maxRetries": 3, "timeoutSeconds": 30, "dataOnly": false }
              ],
              "schedules": [ { "cron": "0 * * * *", "timezone": "UTC", "path": "/jobs/hourly", "payload": { "x": 1 } } ],
              "public":    [ { "hostname": "api.acme.com", "pathPrefix": "/" } ],
              "config": ["INVOICE_PREFIX"],
              "secrets": ["billing/stripe-key"],
              "db": [ { "name": "main", "secretRef": "billing/dsn", "poolSize": 4 } ],
              "httpAllow": ["api.stripe.com"]
            }
            """;

    @Test
    void fullManifestEveryComponent() {
        Manifest manifest = parseJvm(FULL_JVM);

        assertThat(manifest.runtime()).isEqualTo(Runtime.JVM);
        assertThat(manifest.entrypoint()).isEqualTo("com.acme.billing.CreateInvoice");
        assertThat(manifest.pool()).isEqualTo(new DnsLabel("default"));
        assertThat(manifest.warm()).isFalse();
        assertThat(manifest.limits().maxDurationMs()).isEqualTo(30_000);
        assertThat(manifest.limits().maxConcurrency()).isEqualTo(32);
        assertThat(manifest.limits().wasmMemoryMb()).isNull();

        assertThat(manifest.endpoints()).hasSize(4);
        Manifest.Endpoint api = manifest.endpoints().get(2);
        assertThat(api.path()).isEqualTo(RoutePattern.parse("/api/*"));
        assertThat(api.auth()).isEqualTo(EndpointAuth.PLATFORM);
        assertThat(api.methods()).containsExactly(HttpMethod.GET, HttpMethod.POST);
        assertThat(api.cors()).isEqualTo(new Manifest.Cors(java.util.List.of("https://app.acme.com"),
                java.util.List.of(), java.util.List.of(), false));
        assertThat(api.maxBodyBytes()).isEqualTo(1_048_576);
        assertThat(api.timeoutMs()).isEqualTo(10_000);
        assertThat(manifest.endpoints().get(0).auth()).isEqualTo(EndpointAuth.WEBHOOK);
        assertThat(manifest.endpoints().get(3).auth()).isEqualTo(EndpointAuth.NONE);

        assertThat(manifest.subscriptions()).hasSize(1);
        Manifest.SubscriptionSpec sub = manifest.subscriptions().get(0);
        assertThat(sub.eventType()).isEqualTo("billing:invoices:invoice:created");
        assertThat(sub.path()).isEqualTo(RoutePattern.parse("/events/invoice-created"));
        assertThat(sub.mode()).isEqualTo(DispatchMode.BLOCK_ON_ERROR);
        assertThat(sub.filter()).isNull();
        assertThat(sub.maxRetries()).isEqualTo(3);
        assertThat(sub.timeoutSeconds()).isEqualTo(30);
        assertThat(sub.dataOnly()).isFalse();

        assertThat(manifest.schedules()).hasSize(1);
        Manifest.ScheduleSpec sched = manifest.schedules().get(0);
        assertThat(sched.cron()).isEqualTo("0 * * * *");
        assertThat(sched.timezone()).isEqualTo("UTC");
        assertThat(sched.path()).isEqualTo(RoutePattern.parse("/jobs/hourly"));
        assertThat(sched.payload().path("x").asInt()).isEqualTo(1);

        assertThat(manifest.publicRoutes()).hasSize(1);
        Manifest.PublicRoute pub = manifest.publicRoutes().get(0);
        assertThat(pub.hostname()).isEqualTo(Hostname.parse("api.acme.com"));
        assertThat(pub.pathPrefix()).isEqualTo(RoutePattern.parse("/"));

        assertThat(manifest.config()).containsExactly("INVOICE_PREFIX");
        assertThat(manifest.secrets()).containsExactly("billing/stripe-key");
        assertThat(manifest.db()).containsExactly(
                new Manifest.DbRef(new DnsLabel("main"), "billing/dsn", 4));
        assertThat(manifest.httpAllow()).containsExactly("api.stripe.com");
    }

    @Test
    void toJsonSpellings() {
        JsonNode tree = parseJvm(FULL_JVM).toJson();
        assertThat(tree.path("runtime").asString()).as("lower-case runtime").isEqualTo("jvm");
        JsonNode endpoint = tree.path("endpoints").get(2);
        assertThat(endpoint.path("auth").asString()).as("lower-case auth").isEqualTo("platform");
        assertThat(endpoint.path("methods").get(0).asString()).as("upper-case methods").isEqualTo("GET");
        assertThat(endpoint.path("methods").get(1).asString()).isEqualTo("POST");
        assertThat(tree.path("subscriptions").get(0).path("mode").asString()).as("upper-case mode")
                .isEqualTo("BLOCK_ON_ERROR");
    }

    @Test
    void readStoredOfParseStrictToJsonRoundTrips() {
        Manifest original = parseJvm(FULL_JVM);
        Manifest reread = Manifest.readStored(original.toJson());
        assertThat(reread).isEqualTo(original);
    }

    @Test
    void wasmManifestResolvesWasmMemoryMb() {
        String json = """
                {
                  "runtime": "wasm",
                  "entrypoint": "handle",
                  "limits": { "wasmMemoryMb": 32 },
                  "endpoints": [ { "path": "/events/*", "auth": "webhook" } ],
                  "subscriptions": [ { "eventType": "billing:invoices:invoice:created", "path": "/events/invoice-created" } ]
                }
                """;
        Manifest manifest = parseWasm(json);
        assertThat(manifest.limits().wasmMemoryMb()).isEqualTo(32);
        Manifest reread = Manifest.readStored(manifest.toJson());
        assertThat(reread).isEqualTo(manifest);
    }

    // ── unknown-key / basic structural rules ─────────────────────────────────

    @Test
    void manifestRequiredWhenNull() {
        assertCode(() -> Manifest.parseStrict(null, Runtime.JVM, DEFAULTS, UNRESTRICTED), "MANIFEST_REQUIRED");
    }

    @Test
    void manifestRequiredWhenNotAnObject() {
        assertCode(() -> parseJvm("[]"), "MANIFEST_REQUIRED");
        assertCode(() -> parseJvm("null"), "MANIFEST_REQUIRED");
        assertCode(() -> parseJvm("\"x\""), "MANIFEST_REQUIRED");
    }

    @Test
    void manifestUnknownFieldTopLevel() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","bogus":1,
                 "endpoints":[{"path":"/a","auth":"none"}]}
                """;
        assertThatThrownBy(() -> parseJvm(json))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("MANIFEST_UNKNOWN_FIELD");
                    assertThat(err.message()).contains("bogus");
                });
    }

    @Test
    void manifestUnknownFieldNestedInLimits() {
        // the exact example named by spec (function-registry.md §4.3): "limits.maxConcurency"
        String json = """
                {"runtime":"jvm","entrypoint":"x","limits":{"maxConcurency":1},
                 "endpoints":[{"path":"/a","auth":"none"}]}
                """;
        assertThatThrownBy(() -> parseJvm(json))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("MANIFEST_UNKNOWN_FIELD");
                    assertThat(err.message()).contains("limits.maxConcurency");
                });
    }

    @Test
    void manifestUnknownFieldDeeplyNestedInEndpoint() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[{"pth":"/a","auth":"none"}]}
                """;
        assertThatThrownBy(() -> parseJvm(json))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("MANIFEST_UNKNOWN_FIELD");
                    assertThat(err.message()).contains("endpoints[0].pth");
                });
    }

    @Test
    void manifestUnknownFieldInSubscription() {
        String json = withWebhookAndSubscription("\"eventType\":\"a:b:c\",\"path\":\"/events/a\",\"bogus\":1");
        assertThatThrownBy(() -> parseJvm(json))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("MANIFEST_UNKNOWN_FIELD");
                    assertThat(err.message()).contains("subscriptions[0].bogus");
                });
    }

    @Test
    void manifestInvalidWarmWrongType() {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"warm\":\"yes\"}";
        assertCode(() -> parseJvm(json), "MANIFEST_INVALID");
    }

    @Test
    void runtimeInvalidWhenAbsentOrUnknown() {
        assertCode(() -> parseJvm("{\"entrypoint\":\"x\"}"), "RUNTIME_INVALID");
        assertCode(() -> parseJvm("{\"runtime\":\"dotnet\",\"entrypoint\":\"x\"}"), "RUNTIME_INVALID");
    }

    @Test
    void runtimeMismatch() {
        String json = "{\"runtime\":\"wasm\",\"entrypoint\":\"handle\"}";
        assertCode(() -> parseJvm(json), "RUNTIME_MISMATCH");
    }

    @Test
    void entrypointRequiredWhenAbsentOrBlank() {
        assertCode(() -> parseJvm("{\"runtime\":\"jvm\"}"), "ENTRYPOINT_REQUIRED");
        assertCode(() -> parseJvm("{\"runtime\":\"jvm\",\"entrypoint\":\"  \"}"), "ENTRYPOINT_REQUIRED");
    }

    @Test
    void entrypointInvalidForJvm() {
        assertCode(() -> parseJvm("{\"runtime\":\"jvm\",\"entrypoint\":\"123bad\"}"), "ENTRYPOINT_INVALID");
    }

    @Test
    void entrypointInvalidForWasm() {
        assertCode(() -> parseWasm("{\"runtime\":\"wasm\",\"entrypoint\":\"not a name\"}"), "ENTRYPOINT_INVALID");
    }

    @Test
    void poolInvalid() {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"pool\":\"Bad Pool\"}";
        assertCode(() -> parseJvm(json), "POOL_INVALID");
    }

    @Test
    void poolAbsentDefaultsToDefault() {
        Manifest manifest = parseJvm(MINIMAL_JVM);
        assertThat(manifest.pool()).isEqualTo(Manifest.DEFAULT_POOL);
        assertThat(manifest.pool().value()).isEqualTo("default");
    }

    // ── limits ────────────────────────────────────────────────────────────

    @Test
    void limitInvalidNotPositiveInteger() {
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": 0")), "LIMIT_INVALID");
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": -1")), "LIMIT_INVALID");
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": 1.5")), "LIMIT_INVALID");
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": \"30000\"")), "LIMIT_INVALID");
    }

    @Test
    void limitInvalidOutOfIntRange() {
        // 5_000_000_000 is an integral JSON number but overflows int; asInt()
        // would silently truncate it rather than reject it
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": 5000000000")), "LIMIT_INVALID");
    }

    @Test
    void limitOverCeilingNamesLimitValueAndCeiling() {
        // ceiling (10) is BELOW the platform default (32); the value (20) sits
        // strictly between them so a mutant that compares against the default
        // instead of the ceiling would wrongly accept it.
        ClientCeilings tightCeilings = new ClientCeilings(30_000, 10, 64, 4);
        String json = withLimits("\"maxConcurrency\": 20");
        assertThatThrownBy(() -> parseJvm(json, tightCeilings))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("LIMIT_OVER_CEILING");
                    assertThat(err.message()).contains("maxConcurrency").contains("20").contains("10");
                });
    }

    @Test
    void limitNotApplicableWasmMemoryOnJvm() {
        String json = withLimits("\"wasmMemoryMb\": 64");
        assertCode(() -> parseJvm(json), "LIMIT_NOT_APPLICABLE");
    }

    // ── §8 M5 (function-registry.md): absent limit frozen to min(default, ceiling) ──

    @Test
    void absentLimitFrozenToMinOfDefaultAndCeiling() {
        ClientCeilings tightCeilings = new ClientCeilings(30_000, 10, 64, 4);
        Manifest manifest = parseJvm(MINIMAL_JVM, tightCeilings);
        assertThat(manifest.limits().maxConcurrency())
                .as("min(default 32, ceiling 10)").isEqualTo(10);

        Manifest reread = Manifest.readStored(manifest.toJson());
        assertThat(reread.limits().maxConcurrency())
                .as("the clamped value round-trips through the stored row, not the platform default")
                .isEqualTo(10);
    }

    // ── §10 V1: endpoint `auth` has no default ───────────────────────────────

    @Test
    void endpointAuthAbsentIsRejected() {
        assertCode(() -> parseJvm(withEndpoint("\"path\":\"/a\"")), "ENDPOINT_AUTH_REQUIRED");
    }

    @Test
    void endpointAuthUnrecognisedIsRejected() {
        assertCode(() -> parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"bearer\"")), "ENDPOINT_INVALID");
    }

    // ── endpoints: rule table ─────────────────────────────────────────────

    @Test
    void endpointInvalidPathNotARoutePattern() {
        assertCode(() -> parseJvm(withEndpoint("\"path\":\"not-a-path\",\"auth\":\"none\"")), "ENDPOINT_INVALID");
    }

    @Test
    void endpointMethodsAbsentMeansAll() {
        Manifest manifest = parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"none\""));
        assertThat(manifest.endpoints().get(0).methods()).isEmpty();
    }

    @Test
    void endpointInvalidEmptyMethodsArray() {
        assertCode(() -> parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"none\",\"methods\":[]")), "ENDPOINT_INVALID");
    }

    @Test
    void endpointInvalidUnknownMethod() {
        assertCode(() -> parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"none\",\"methods\":[\"TRACE\"]")), "ENDPOINT_INVALID");
    }

    @Test
    void endpointInvalidDuplicateMethodCaseInsensitive() {
        assertCode(() -> parseJvm(withEndpoint(
                "\"path\":\"/a\",\"auth\":\"none\",\"methods\":[\"GET\",\"get\"]")), "ENDPOINT_INVALID");
    }

    @Test
    void endpointInvalidMaxBodyBytesNotPositive() {
        assertCode(() -> parseJvm(withEndpoint(
                "\"path\":\"/a\",\"auth\":\"none\",\"maxBodyBytes\":0")), "ENDPOINT_INVALID");
    }

    @Test
    void endpointInvalidMaxBodyBytesOutOfIntRange() {
        assertCode(() -> parseJvm(withEndpoint(
                "\"path\":\"/a\",\"auth\":\"none\",\"maxBodyBytes\":5000000000")), "ENDPOINT_INVALID");
    }

    @Test
    void endpointInvalidTimeoutMsNotPositive() {
        assertCode(() -> parseJvm(withEndpoint(
                "\"path\":\"/a\",\"auth\":\"none\",\"timeoutMs\":-5")), "ENDPOINT_INVALID");
    }

    @Test
    void endpointInvalidCorsBlankEntry() {
        assertCode(() -> parseJvm(withEndpoint(
                "\"path\":\"/a\",\"auth\":\"none\",\"cors\":{\"origins\":[\"  \"]}")), "ENDPOINT_INVALID");
    }

    // ── §3: webhook endpoint methods must be exactly ["POST"] ────────────────

    @Test
    void webhookEndpointWithNoMethodsIsAccepted() {
        Manifest manifest = parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"webhook\""));
        assertThat(manifest.endpoints().get(0).methods()).isEmpty();
    }

    @Test
    void webhookEndpointWithExactlyPostIsAccepted() {
        Manifest manifest = parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"webhook\",\"methods\":[\"POST\"]"));
        assertThat(manifest.endpoints().get(0).methods()).containsExactly(HttpMethod.POST);
    }

    @Test
    void webhookEndpointWithGetIsRejected() {
        assertCode(() -> parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"webhook\",\"methods\":[\"GET\"]")), "ENDPOINT_INVALID");
    }

    @Test
    void webhookEndpointWithPostAndGetIsRejected() {
        assertCode(() -> parseJvm(withEndpoint("\"path\":\"/a\",\"auth\":\"webhook\",\"methods\":[\"POST\",\"GET\"]")), "ENDPOINT_INVALID");
    }

    // ── ROUTE_AMBIGUOUS for endpoints ────────────────────────────────────────

    @Test
    void routeAmbiguousNamesBothPatterns() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[
                    {"path":"/a/{x}","auth":"none"},
                    {"path":"/a/{y}","auth":"none"}
                ]}""";
        assertThatThrownBy(() -> parseJvm(json))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("ROUTE_AMBIGUOUS");
                    assertThat(err.message()).contains("/a/{x}").contains("/a/{y}");
                });
    }

    @Test
    void routeNotAmbiguousWhenMethodsDisjoint() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[
                    {"path":"/a/{x}","auth":"none","methods":["GET"]},
                    {"path":"/a/{y}","auth":"none","methods":["POST"]}
                ]}""";
        Manifest manifest = parseJvm(json);
        assertThat(manifest.endpoints()).hasSize(2);
    }

    @Test
    void routeAmbiguousWhenOneSideHasNoMethods() {
        // absent methods == "all" for the ambiguity check too
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[
                    {"path":"/a/{x}","auth":"none"},
                    {"path":"/a/{y}","auth":"none","methods":["POST"]}
                ]}""";
        assertCode(() -> parseJvm(json), "ROUTE_AMBIGUOUS");
    }

    // ── §10 V1: subscriptions/schedules — literal path + webhook endpoint ────

    static Stream<org.junit.jupiter.params.provider.Arguments> noWebhookMatchCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("no endpoint at all", "[]"),
                org.junit.jupiter.params.provider.Arguments.of("only a platform endpoint",
                        "[{\"path\":\"/events/invoice-created\",\"auth\":\"platform\"}]"),
                org.junit.jupiter.params.provider.Arguments.of("only a none endpoint",
                        "[{\"path\":\"/events/invoice-created\",\"auth\":\"none\"}]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noWebhookMatchCases")
    void subscriptionPathNotWebhookWhenNoWebhookEndpointMatches(String label, String endpoints) {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":%s,
                 "subscriptions":[{"eventType":"a:b:c","path":"/events/invoice-created"}]}
                """.formatted(endpoints);
        assertCode(() -> parseJvm(json), "SUBSCRIPTION_PATH_NOT_WEBHOOK");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noWebhookMatchCases")
    void schedulePathNotWebhookWhenNoWebhookEndpointMatches(String label, String endpoints) {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":%s,
                 "schedules":[{"cron":"* * * * *","path":"/jobs/hourly"}]}
                """.formatted(endpoints);
        assertCode(() -> parseJvm(json), "SCHEDULE_PATH_NOT_WEBHOOK");
    }

    @Test
    void subscriptionPathMatchingAWebhookEndpointIsAccepted() {
        Manifest manifest = parseJvm(withWebhookAndSubscription("\"eventType\":\"a:b:c\",\"path\":\"/events/a\""));
        assertThat(manifest.subscriptions()).hasSize(1);
    }

    @Test
    void mostSpecificEndpointDecidesWhichAuthGoverns() {
        // /events/* is webhook, but the MORE SPECIFIC /events/special is
        // platform — the subscription path resolves to the specific one and
        // must be rejected, even though a broader webhook endpoint also matches.
        String json = """
                {"runtime":"jvm","entrypoint":"x",
                 "endpoints":[{"path":"/events/*","auth":"webhook"},{"path":"/events/special","auth":"platform"}],
                 "subscriptions":[{"eventType":"a:b:c","path":"/events/special"}]}
                """;
        assertCode(() -> parseJvm(json), "SUBSCRIPTION_PATH_NOT_WEBHOOK");
    }

    @Test
    void subscriptionInvalidPathIsAPattern() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[{"path":"/events/*","auth":"webhook"}],
                 "subscriptions":[{"eventType":"a:b:c","path":"/events/{id}"}]}
                """;
        assertCode(() -> parseJvm(json), "SUBSCRIPTION_INVALID");
    }

    @Test
    void subscriptionInvalidMissingEventType() {
        assertCode(() -> parseJvm(withWebhookAndSubscription("\"path\":\"/events/a\"")), "SUBSCRIPTION_INVALID");
    }

    @Test
    void subscriptionInvalidUnrecognisedMode() {
        assertCode(() -> parseJvm(withWebhookAndSubscription(
                "\"eventType\":\"a:b:c\",\"path\":\"/events/a\",\"mode\":\"immediate\"")), "SUBSCRIPTION_INVALID");
    }

    @Test
    void subscriptionDuplicateEventType() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[{"path":"/events/*","auth":"webhook"}],
                 "subscriptions":[
                    {"eventType":"a:b:c","path":"/events/a"},
                    {"eventType":"a:b:c","path":"/events/a"}]}
                """;
        assertCode(() -> parseJvm(json), "SUBSCRIPTION_DUPLICATE");
    }

    @Test
    void subscriptionDefaultsWhenAbsent() {
        Manifest manifest = parseJvm(withWebhookAndSubscription("\"eventType\":\"a:b:c\",\"path\":\"/events/a\""));
        Manifest.SubscriptionSpec sub = manifest.subscriptions().get(0);
        assertThat(sub.mode()).as("manifest default is IMMEDIATE, not DispatchMode.DEFAULT (NEXT_ON_ERROR)")
                .isEqualTo(DispatchMode.IMMEDIATE);
        assertThat(sub.maxRetries()).isEqualTo(io.flowcatalyst.platform.subscription.Subscription.DEFAULT_MAX_RETRIES);
        assertThat(sub.timeoutSeconds()).isEqualTo(io.flowcatalyst.platform.subscription.Subscription.DEFAULT_TIMEOUT_SECONDS);
        assertThat(sub.dataOnly()).as("dataOnly default is false, unlike the subscription aggregate's own true default").isFalse();
    }

    @Test
    void scheduleInvalidMissingCron() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[{"path":"/jobs/*","auth":"webhook"}],
                 "schedules":[{"path":"/jobs/hourly"}]}
                """;
        assertCode(() -> parseJvm(json), "SCHEDULE_INVALID");
    }

    @Test
    void scheduleDuplicateCronAndTimezone() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[{"path":"/jobs/*","auth":"webhook"}],
                 "schedules":[
                    {"cron":"0 * * * *","timezone":"UTC","path":"/jobs/hourly"},
                    {"cron":"0 * * * *","timezone":"UTC","path":"/jobs/hourly"}]}
                """;
        assertCode(() -> parseJvm(json), "SCHEDULE_DUPLICATE");
    }

    @Test
    void scheduleSameCronDifferentTimezoneIsNotADuplicate() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[{"path":"/jobs/*","auth":"webhook"}],
                 "schedules":[
                    {"cron":"0 * * * *","timezone":"UTC","path":"/jobs/hourly"},
                    {"cron":"0 * * * *","timezone":"America/New_York","path":"/jobs/hourly"}]}
                """;
        Manifest manifest = parseJvm(json);
        assertThat(manifest.schedules()).hasSize(2);
    }

    // ── public routes ─────────────────────────────────────────────────────

    @Test
    void publicRouteInvalidBadHostname() {
        assertCode(() -> parseJvm(withPublic("\"hostname\":\"not a hostname\"")), "PUBLIC_ROUTE_INVALID");
    }

    @Test
    void publicRoutePathPrefixDefaultsToRoot() {
        Manifest manifest = parseJvm(withPublic("\"hostname\":\"api.acme.com\""));
        assertThat(manifest.publicRoutes().get(0).pathPrefix()).isEqualTo(RoutePattern.parse("/"));
    }

    @Test
    void publicRouteInvalidPathPrefixIsAPattern() {
        assertCode(() -> parseJvm(withPublic("\"hostname\":\"api.acme.com\",\"pathPrefix\":\"/a/{id}\"")), "PUBLIC_ROUTE_INVALID");
    }

    @Test
    void publicRouteDuplicateHostnameAndPrefix() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","public":[
                    {"hostname":"api.acme.com","pathPrefix":"/a"},
                    {"hostname":"api.acme.com","pathPrefix":"/a"}]}
                """;
        assertCode(() -> parseJvm(json), "PUBLIC_ROUTE_DUPLICATE");
    }

    @Test
    void publicRouteSameHostnameDifferentPrefixIsNotADuplicate() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","public":[
                    {"hostname":"api.acme.com","pathPrefix":"/a"},
                    {"hostname":"api.acme.com","pathPrefix":"/b"}]}
                """;
        Manifest manifest = parseJvm(json);
        assertThat(manifest.publicRoutes()).hasSize(2);
    }

    // ── db / config (unchanged from function-registry.md §4.3) ──────────────

    @Test
    void dbInvalidNameNotADnsLabel() {
        assertCode(() -> parseJvm(withDb("{\"name\":\"Bad Name\",\"secretRef\":\"s\"}")), "DB_INVALID");
    }

    @Test
    void dbInvalidBlankSecretRef() {
        assertCode(() -> parseJvm(withDb("{\"name\":\"main\",\"secretRef\":\"  \"}")), "DB_INVALID");
    }

    @Test
    void dbInvalidDuplicateName() {
        assertCode(() -> parseJvm(withDb(
                "{\"name\":\"main\",\"secretRef\":\"a\"},{\"name\":\"main\",\"secretRef\":\"b\"}")), "DB_INVALID");
    }

    @Test
    void configInvalidBlankEntry() {
        assertCode(() -> parseJvm(withConfig("\"  \"")), "CONFIG_INVALID");
    }

    @Test
    void configInvalidDuplicateEntry() {
        assertCode(() -> parseJvm(withConfig("\"A\", \"A\"")), "CONFIG_INVALID");
    }

    @Test
    void configInvalidWrongType() {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"config\":{}}";
        assertCode(() -> parseJvm(json), "CONFIG_INVALID");
    }

    // ── readStored — tolerance contract ──────────────────────────────────────

    @Test
    void readStoredThrowsOnlyWhenRuntimeUnreadable() {
        assertThatThrownBy(() -> Manifest.readStored(readTree("{}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Manifest.readStored(readTree("{\"runtime\":\"cobol\",\"entrypoint\":\"x\"}")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readStoredThrowsOnlyWhenEntrypointUnreadable() {
        assertThatThrownBy(() -> Manifest.readStored(readTree("{\"runtime\":\"jvm\"}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Manifest.readStored(readTree("{\"runtime\":\"jvm\",\"entrypoint\":\"  \"}")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readStoredNotAnObjectThrows() {
        assertThatThrownBy(() -> Manifest.readStored(readTree("[]"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Manifest.readStored(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readStoredToleratesUnknownKeysAtEveryLevel() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","bogus":1,
                 "limits":{"maxDurationMs":30000,"maxConcurrency":32,"extra":true},
                 "endpoints":[{"path":"/a","auth":"none","weird":true}]}
                """;
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.runtime()).isEqualTo(Runtime.JVM);
        assertThat(manifest.endpoints()).hasSize(1);
    }

    @Test
    void readStoredToleratesMissingOptionals() {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\"}";
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.pool()).isEqualTo(Manifest.DEFAULT_POOL);
        assertThat(manifest.warm()).isFalse();
        assertThat(manifest.limits().maxDurationMs()).isEqualTo(FunctionLimits.DEFAULT_MAX_DURATION_MS);
        assertThat(manifest.limits().wasmMemoryMb()).isNull();
        assertThat(manifest.endpoints()).isEmpty();
        assertThat(manifest.subscriptions()).isEmpty();
        assertThat(manifest.schedules()).isEmpty();
        assertThat(manifest.publicRoutes()).isEmpty();
        assertThat(manifest.config()).isEmpty();
        assertThat(manifest.secrets()).isEmpty();
        assertThat(manifest.db()).isEmpty();
        assertThat(manifest.httpAllow()).isEmpty();
    }

    @Test
    void readStoredFallsBackToDefaultWhenLimitOutOfIntRange() {
        // an out-of-range stored value falls back to the default, never throws
        // and never silently truncates through asInt()
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"limits\":{\"maxDurationMs\":5000000000}}";
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.limits().maxDurationMs()).isEqualTo(FunctionLimits.DEFAULT_MAX_DURATION_MS);
    }

    @Test
    void readStoredDropsAMalformedEndpointButKeepsTheRest() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[
                    {"path":"not-a-path","auth":"none"},
                    {"path":"/a","auth":"none"}
                ]}
                """;
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.endpoints()).hasSize(1);
        assertThat(manifest.endpoints().get(0).path()).isEqualTo(RoutePattern.parse("/a"));
    }

    @Test
    void readStoredDropsASubscriptionThatNoLongerMatchesAWebhookEndpoint() {
        // the manifest was stored valid; if a later reader's rules ever
        // tightened, the stored reader drops rather than throws
        String json = """
                {"runtime":"jvm","entrypoint":"x","endpoints":[{"path":"/events/*","auth":"platform"}],
                 "subscriptions":[{"eventType":"a:b:c","path":"/events/a"}]}
                """;
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.subscriptions()).isEmpty();
    }

    // ── fixture builders ──────────────────────────────────────────────────────

    private static String withLimits(String limitsBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"limits\":{" + limitsBody + "},"
                + "\"endpoints\":[{\"path\":\"/a\",\"auth\":\"none\"}]}";
    }

    private static String withEndpoint(String endpointBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"endpoints\":[{" + endpointBody + "}]}";
    }

    private static String withWebhookAndSubscription(String subscriptionBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"endpoints\":[{\"path\":\"/events/*\",\"auth\":\"webhook\"}],"
                + "\"subscriptions\":[{" + subscriptionBody + "}]}";
    }

    private static String withPublic(String publicBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"public\":[{" + publicBody + "}]}";
    }

    private static String withDb(String dbBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"db\":[" + dbBody + "],"
                + "\"endpoints\":[{\"path\":\"/a\",\"auth\":\"none\"}]}";
    }

    private static String withConfig(String configBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"config\":[" + configBody + "],"
                + "\"endpoints\":[{\"path\":\"/a\",\"auth\":\"none\"}]}";
    }
}
