package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §4: every code of §4.3's rule table, the full
/// happy path of §4.1, and `readStored`'s tolerance contract (§4.2).
///
/// Note (report): §4.1's example JSON shows `wasmMemoryMb` on a `jvm`
/// manifest, but §4.3's `LIMIT_NOT_APPLICABLE` forbids exactly that
/// combination. The JVM fixtures below omit `wasmMemoryMb`; a dedicated Wasm
/// fixture exercises it instead.
class ManifestTest {

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "triggers": [ { "type": "event", "eventType": "billing:invoices:invoice:created" } ]
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

    // ── the full happy path — spec §4.1 ──────────────────────────────────────

    private static final String FULL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "pool": "default",
              "warm": false,
              "limits": { "maxDurationMs": 30000, "maxConcurrency": 32 },
              "triggers": [
                { "type": "event", "eventType": "billing:invoices:invoice:created", "messageGroupKey": "invoiceId" },
                { "type": "schedule", "cron": "0 * * * *", "timezone": "UTC" },
                { "type": "http", "routes": [
                  { "hostnames": ["api.acme.com"], "methods": ["GET", "POST"], "path": "/invoices/{id}",
                    "auth": "bearer", "cors": { "origins": ["https://app.acme.com"] },
                    "maxBodyBytes": 1048576, "timeoutMs": 10000 } ] }
              ],
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

        assertThat(manifest.triggers()).hasSize(3);
        assertThat(manifest.triggers().get(0)).isEqualTo(
                new Manifest.Trigger.Event("billing:invoices:invoice:created", "invoiceId"));
        assertThat(manifest.triggers().get(1)).isEqualTo(new Manifest.Trigger.Schedule("0 * * * *", "UTC"));

        Manifest.Trigger.Http http = (Manifest.Trigger.Http) manifest.triggers().get(2);
        assertThat(http.routes()).hasSize(1);
        Manifest.HttpRoute route = http.routes().get(0);
        assertThat(route.hostnames()).containsExactly(Hostname.parse("api.acme.com"));
        assertThat(route.methods()).containsExactly(HttpMethod.GET, HttpMethod.POST);
        assertThat(route.path()).isEqualTo(RoutePattern.parse("/invoices/{id}"));
        assertThat(route.auth()).isEqualTo(AuthMode.BEARER);
        assertThat(route.cors()).isEqualTo(new Manifest.Cors(java.util.List.of("https://app.acme.com"),
                java.util.List.of(), java.util.List.of(), false));
        assertThat(route.maxBodyBytes()).isEqualTo(1_048_576);
        assertThat(route.timeoutMs()).isEqualTo(10_000);

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
        JsonNode route = tree.path("triggers").get(2).path("routes").get(0);
        assertThat(route.path("auth").asString()).as("lower-case auth").isEqualTo("bearer");
        assertThat(route.path("methods").get(0).asString()).as("upper-case methods").isEqualTo("GET");
        assertThat(route.path("methods").get(1).asString()).isEqualTo("POST");
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
                  "triggers": [ { "type": "event", "eventType": "billing:invoices:invoice:created" } ]
                }
                """;
        Manifest manifest = parseWasm(json);
        assertThat(manifest.limits().wasmMemoryMb()).isEqualTo(32);
        Manifest reread = Manifest.readStored(manifest.toJson());
        assertThat(reread).isEqualTo(manifest);
    }

    // ── §4.3 rule table — one violation per code ─────────────────────────────

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
                 "triggers":[{"type":"event","eventType":"a:b:c"}]}
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
        // the exact example named by spec §4.3: "limits.maxConcurency"
        String json = """
                {"runtime":"jvm","entrypoint":"x","limits":{"maxConcurency":1},
                 "triggers":[{"type":"event","eventType":"a:b:c"}]}
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
    void manifestUnknownFieldDeeplyNestedInRoute() {
        // the exact example named by spec §4.3: "triggers[2].routes[0].pth"
        String json = """
                {"runtime":"jvm","entrypoint":"x","triggers":[
                    {"type":"event","eventType":"a:b:c"},
                    {"type":"schedule","cron":"* * * * *"},
                    {"type":"http","routes":[{"pth":"/a","methods":["GET"]}]}
                ]}
                """;
        assertThatThrownBy(() -> parseJvm(json))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("MANIFEST_UNKNOWN_FIELD");
                    assertThat(err.message()).contains("triggers[2].routes[0].pth");
                });
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

    @Test
    void limitInvalidNotPositiveInteger() {
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": 0")), "LIMIT_INVALID");
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": -1")), "LIMIT_INVALID");
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": 1.5")), "LIMIT_INVALID");
        assertCode(() -> parseJvm(withLimits("\"maxDurationMs\": \"30000\"")), "LIMIT_INVALID");
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

    @Test
    void triggerInvalidTypeAbsentOrUnknown() {
        assertCode(() -> parseJvm(withTriggers("{\"eventType\":\"a:b:c\"}")), "TRIGGER_INVALID");
        assertCode(() -> parseJvm(withTriggers("{\"type\":\"cron-job\"}")), "TRIGGER_INVALID");
    }

    @Test
    void triggerInvalidEventWithoutEventType() {
        assertCode(() -> parseJvm(withTriggers("{\"type\":\"event\"}")), "TRIGGER_INVALID");
    }

    @Test
    void triggerInvalidScheduleWithoutCron() {
        assertCode(() -> parseJvm(withTriggers("{\"type\":\"schedule\"}")), "TRIGGER_INVALID");
    }

    @Test
    void triggerInvalidHttpWithNoRoutes() {
        assertCode(() -> parseJvm(withTriggers("{\"type\":\"http\",\"routes\":[]}")), "TRIGGER_INVALID");
    }

    @Test
    void triggerInvalidTriggersWrongType() {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"triggers\":{}}";
        assertCode(() -> parseJvm(json), "TRIGGER_INVALID");
    }

    @Test
    void triggerDuplicateEventType() {
        String json = withTriggers(
                "{\"type\":\"event\",\"eventType\":\"a:b:c\"}, {\"type\":\"event\",\"eventType\":\"a:b:c\"}");
        assertCode(() -> parseJvm(json), "TRIGGER_DUPLICATE");
    }

    @Test
    void triggerDuplicateMoreThanOneHttp() {
        String route = "{\"path\":\"/a\",\"methods\":[\"GET\"]}";
        String json = withTriggers(
                "{\"type\":\"http\",\"routes\":[" + route + "]}, {\"type\":\"http\",\"routes\":[" + route + "]}");
        assertCode(() -> parseJvm(json), "TRIGGER_DUPLICATE");
    }

    @Test
    void routeInvalidNoMethods() {
        assertCode(() -> parseJvm(withHttpRoute("\"path\":\"/a\",\"methods\":[]")), "ROUTE_INVALID");
    }

    @Test
    void routeInvalidUnknownMethod() {
        assertCode(() -> parseJvm(withHttpRoute("\"path\":\"/a\",\"methods\":[\"TRACE\"]")), "ROUTE_INVALID");
    }

    @Test
    void routeInvalidPathNotARoutePattern() {
        assertCode(() -> parseJvm(withHttpRoute("\"path\":\"not-a-path\",\"methods\":[\"GET\"]")), "ROUTE_INVALID");
    }

    @Test
    void routeInvalidHostnameNotAHostname() {
        assertCode(() -> parseJvm(withHttpRoute(
                "\"path\":\"/a\",\"methods\":[\"GET\"],\"hostnames\":[\"not a hostname\"]")), "ROUTE_INVALID");
    }

    @Test
    void routeInvalidAuthNotBearerOrNone() {
        assertCode(() -> parseJvm(withHttpRoute(
                "\"path\":\"/a\",\"methods\":[\"GET\"],\"auth\":\"basic\"")), "ROUTE_INVALID");
    }

    @Test
    void routeInvalidMaxBodyBytesNotPositive() {
        assertCode(() -> parseJvm(withHttpRoute(
                "\"path\":\"/a\",\"methods\":[\"GET\"],\"maxBodyBytes\":0")), "ROUTE_INVALID");
    }

    @Test
    void routeInvalidTimeoutMsNotPositive() {
        assertCode(() -> parseJvm(withHttpRoute(
                "\"path\":\"/a\",\"methods\":[\"GET\"],\"timeoutMs\":-5")), "ROUTE_INVALID");
    }

    @Test
    void routeInvalidCorsBlankEntry() {
        assertCode(() -> parseJvm(withHttpRoute(
                "\"path\":\"/a\",\"methods\":[\"GET\"],\"cors\":{\"origins\":[\"  \"]}")), "ROUTE_INVALID");
    }

    @Test
    void routeAmbiguousNamesBothPatterns() {
        String json = withTriggers("""
                {"type":"http","routes":[
                    {"path":"/a/{x}","methods":["GET"]},
                    {"path":"/a/{y}","methods":["GET"]}
                ]}""");
        assertThatThrownBy(() -> parseJvm(json))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err.code()).isEqualTo("ROUTE_AMBIGUOUS");
                    assertThat(err.message()).contains("/a/{x}").contains("/a/{y}");
                });
    }

    @Test
    void routeNotAmbiguousWhenHostnamesDiffer() {
        String json = withTriggers("""
                {"type":"http","routes":[
                    {"path":"/a/{x}","methods":["GET"],"hostnames":["api.acme.com"]},
                    {"path":"/a/{y}","methods":["GET"],"hostnames":["other.acme.com"]}
                ]}""");
        Manifest manifest = parseJvm(json);
        assertThat(manifest.triggers()).hasSize(1);
    }

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

    @Test
    void manifestInvalidWarmWrongType() {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"warm\":\"yes\"}";
        assertCode(() -> parseJvm(json), "MANIFEST_INVALID");
    }

    // ── §8 M6: auth absent ⇒ BEARER ───────────────────────────────────────────

    @Test
    void authAbsentDefaultsToBearer() {
        Manifest manifest = parseJvm(withHttpRoute("\"path\":\"/a\",\"methods\":[\"GET\"]"));
        Manifest.Trigger.Http http = (Manifest.Trigger.Http) manifest.triggers().get(0);
        assertThat(http.routes().get(0).auth()).isEqualTo(AuthMode.BEARER);
    }

    // ── §8 M5: absent limit frozen to min(default, ceiling) ──────────────────

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

    // ── readStored — tolerance contract (spec §4.2) ──────────────────────────

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
                 "triggers":[{"type":"http","routes":[
                    {"path":"/a","methods":["GET"],"weird":true}]}]}
                """;
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.runtime()).isEqualTo(Runtime.JVM);
        assertThat(manifest.triggers()).hasSize(1);
    }

    @Test
    void readStoredToleratesMissingOptionals() {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"x\"}";
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.pool()).isEqualTo(Manifest.DEFAULT_POOL);
        assertThat(manifest.warm()).isFalse();
        assertThat(manifest.limits().maxDurationMs()).isEqualTo(FunctionLimits.DEFAULT_MAX_DURATION_MS);
        assertThat(manifest.limits().wasmMemoryMb()).isNull();
        assertThat(manifest.triggers()).isEmpty();
        assertThat(manifest.config()).isEmpty();
        assertThat(manifest.secrets()).isEmpty();
        assertThat(manifest.db()).isEmpty();
        assertThat(manifest.httpAllow()).isEmpty();
    }

    @Test
    void readStoredDropsAMalformedTriggerButKeepsTheRest() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","triggers":[
                    {"type":"event"},
                    {"type":"event","eventType":"a:b:c"}
                ]}
                """;
        Manifest manifest = Manifest.readStored(readTree(json));
        assertThat(manifest.triggers()).containsExactly(new Manifest.Trigger.Event("a:b:c", null));
    }

    @Test
    void readStoredDropsAMalformedRouteButKeepsTheRest() {
        String json = """
                {"runtime":"jvm","entrypoint":"x","triggers":[
                    {"type":"http","routes":[
                        {"path":"not-a-path","methods":["GET"]},
                        {"path":"/a","methods":["GET"]}
                    ]}
                ]}
                """;
        Manifest manifest = Manifest.readStored(readTree(json));
        Manifest.Trigger.Http http = (Manifest.Trigger.Http) manifest.triggers().get(0);
        assertThat(http.routes()).hasSize(1);
        assertThat(http.routes().get(0).path()).isEqualTo(RoutePattern.parse("/a"));
    }

    // ── fixture builders ──────────────────────────────────────────────────────

    private static String withLimits(String limitsBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"limits\":{" + limitsBody + "},"
                + "\"triggers\":[{\"type\":\"event\",\"eventType\":\"a:b:c\"}]}";
    }

    private static String withTriggers(String triggersBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"triggers\":[" + triggersBody + "]}";
    }

    private static String withHttpRoute(String routeBody) {
        return withTriggers("{\"type\":\"http\",\"routes\":[{" + routeBody + "}]}");
    }

    private static String withDb(String dbBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"db\":[" + dbBody + "],"
                + "\"triggers\":[{\"type\":\"event\",\"eventType\":\"a:b:c\"}]}";
    }

    private static String withConfig(String configBody) {
        return "{\"runtime\":\"jvm\",\"entrypoint\":\"x\",\"config\":[" + configBody + "],"
                + "\"triggers\":[{\"type\":\"event\",\"eventType\":\"a:b:c\"}]}";
    }
}
