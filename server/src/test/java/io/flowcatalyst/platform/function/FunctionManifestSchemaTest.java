package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/function-manifest-authoring.md` M1: `function-manifest.schema.json` is an editor
/// aid over [Manifest]'s own shape, never a second definition — this test walks BOTH documents
/// (the schema, and `functions.openapi.json`'s `PublishManifestRequest`) and asserts they agree
/// with [Manifest] level by level, so the three can never quietly drift apart. `$schema`'s
/// carve-out (accepted, type-checked, never written back) is [Manifest]'s own behaviour, pinned
/// here alongside the shape checks.
class FunctionManifestSchemaTest {

    private static final String SCHEMA_RESOURCE = "schemas/function-manifest.schema.json";

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static JsonNode loadSchema() {
        try (InputStream in = FunctionManifestSchemaTest.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + SCHEMA_RESOURCE);
            return Json.MAPPER.readTree(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode loadPublishManifestRequest() {
        JsonNode doc = Lockfile.load(Json.MAPPER, "openapi/functions.openapi.json").json();
        JsonNode schema = doc.path("components").path("schemas").path("PublishManifestRequest");
        if (schema.isMissingNode()) {
            throw new IllegalStateException("functions.openapi.json has no components.schemas.PublishManifestRequest");
        }
        return schema;
    }

    private static Set<String> propertyNames(JsonNode objectSchema) {
        var names = new TreeSet<String>();
        objectSchema.path("properties").propertyNames().forEach(names::add);
        return names;
    }

    private static Set<String> requiredNames(JsonNode objectSchema) {
        var names = new TreeSet<String>();
        for (JsonNode entry : objectSchema.path("required")) {
            names.add(entry.stringValue());
        }
        return names;
    }

    private static Set<String> enumValues(JsonNode enumSchema) {
        var names = new TreeSet<String>();
        for (JsonNode entry : enumSchema.path("enum")) {
            names.add(entry.stringValue());
        }
        return names;
    }

    private static Set<String> plus(Set<String> keys, String extra) {
        var out = new TreeSet<>(keys);
        out.add(extra);
        return out;
    }

    // ── M1.5 first assertion: schema vs the parser's own key sets, level by level ──────────────

    @Test
    void topLevelPropertyNamesEqualTopKeysPlusSchemaEscape() {
        JsonNode schema = loadSchema();
        assertThat(propertyNames(schema)).isEqualTo(plus(Manifest.TOP_KEYS, "$schema"));
    }

    @Test
    void topLevelRequiredEqualsRuntimeAndEntrypointOnly() {
        JsonNode schema = loadSchema();
        assertThat(requiredNames(schema)).containsExactlyInAnyOrder("runtime", "entrypoint");
    }

    @Test
    void limitsPropertyNamesEqualLimitsKeys() {
        JsonNode limits = loadSchema().path("properties").path("limits");
        assertThat(propertyNames(limits)).isEqualTo(Manifest.LIMITS_KEYS);
    }

    @Test
    void endpointPropertyNamesEqualEndpointKeys() {
        JsonNode endpoint = loadSchema().path("properties").path("endpoints").path("items");
        assertThat(propertyNames(endpoint)).isEqualTo(Manifest.ENDPOINT_KEYS);
        assertThat(requiredNames(endpoint)).containsExactlyInAnyOrder("path", "auth");
    }

    @Test
    void corsPropertyNamesEqualCorsKeys() {
        JsonNode cors = loadSchema().path("properties").path("endpoints").path("items").path("properties").path("cors");
        assertThat(propertyNames(cors)).isEqualTo(Manifest.CORS_KEYS);
    }

    @Test
    void subscriptionPropertyNamesEqualSubscriptionKeys() {
        JsonNode subscription = loadSchema().path("properties").path("subscriptions").path("items");
        assertThat(propertyNames(subscription)).isEqualTo(Manifest.SUBSCRIPTION_KEYS);
        assertThat(requiredNames(subscription)).containsExactlyInAnyOrder("eventType", "path");
    }

    @Test
    void schedulePropertyNamesEqualScheduleKeys() {
        JsonNode schedule = loadSchema().path("properties").path("schedules").path("items");
        assertThat(propertyNames(schedule)).isEqualTo(Manifest.SCHEDULE_KEYS);
        assertThat(requiredNames(schedule)).containsExactlyInAnyOrder("cron", "path");
    }

    @Test
    void publicRoutePropertyNamesEqualPublicRouteKeys() {
        JsonNode publicRoute = loadSchema().path("properties").path("public").path("items");
        assertThat(propertyNames(publicRoute)).isEqualTo(Manifest.PUBLIC_ROUTE_KEYS);
        assertThat(requiredNames(publicRoute)).containsExactlyInAnyOrder("hostname");
    }

    @Test
    void dbPropertyNamesEqualDbKeys() {
        JsonNode db = loadSchema().path("properties").path("db").path("items");
        assertThat(propertyNames(db)).isEqualTo(Manifest.DB_KEYS);
        assertThat(requiredNames(db)).containsExactlyInAnyOrder("name", "secretRef");
    }

    /// Every enum in the schema mirrors the wire values of the Java enum it stands for —
    /// mutant: add/remove a constant on the Java side (or the schema's `enum` array) and this fails.
    @Test
    void enumsEqualTheJavaEnumsTheyMirror() {
        JsonNode schema = loadSchema();
        assertThat(enumValues(schema.path("properties").path("runtime")))
                .containsExactlyInAnyOrder(Runtime.JVM.wireValue(), Runtime.WASM.wireValue());
        JsonNode endpoint = schema.path("properties").path("endpoints").path("items").path("properties");
        assertThat(enumValues(endpoint.path("auth")))
                .containsExactlyInAnyOrder(EndpointAuth.WEBHOOK.wireValue(), EndpointAuth.PLATFORM.wireValue(),
                        EndpointAuth.NONE.wireValue());
        assertThat(enumValues(endpoint.path("methods").path("items")))
                .containsExactlyInAnyOrderElementsOf(
                        Stream.of(HttpMethod.values()).map(Enum::name).collect(Collectors.toSet()));
        JsonNode subscription = schema.path("properties").path("subscriptions").path("items").path("properties");
        assertThat(enumValues(subscription.path("mode"))).containsExactlyInAnyOrderElementsOf(
                Stream.of(io.flowcatalyst.platform.shared.dispatch.DispatchMode.values())
                        .map(Enum::name).collect(Collectors.toSet()));
    }

    /// `additionalProperties: false` on every object in the document — mutant: flip any one to
    /// `true` (or drop it) and this fails.
    @Test
    void everyObjectForbidsAdditionalProperties() {
        JsonNode schema = loadSchema();
        List<JsonNode> objects = List.of(
                schema,
                schema.path("properties").path("limits"),
                schema.path("properties").path("endpoints").path("items"),
                schema.path("properties").path("endpoints").path("items").path("properties").path("cors"),
                schema.path("properties").path("subscriptions").path("items"),
                schema.path("properties").path("schedules").path("items"),
                schema.path("properties").path("public").path("items"),
                schema.path("properties").path("db").path("items"));
        for (JsonNode object : objects) {
            assertThat(object.path("additionalProperties").isBoolean()).as(object.toString()).isTrue();
            assertThat(object.path("additionalProperties").booleanValue()).as(object.toString()).isFalse();
        }
    }

    // ── M1.5 second assertion: the schema vs functions.openapi.json's PublishManifestRequest ──

    @Test
    void propertyNamesMatchThePublishManifestRequestRenderingAtEveryLevel() {
        JsonNode schema = loadSchema();
        JsonNode openapi = loadPublishManifestRequest();

        // The schema's own $schema escape is not part of the wire shape PublishManifestRequest
        // documents — it is JSON-Schema-only glue (M1.2), so it is excluded from this comparison.
        var schemaTopLevel = new TreeSet<>(propertyNames(schema));
        schemaTopLevel.remove("$schema");
        assertThat(schemaTopLevel).isEqualTo(propertyNames(openapi));
        assertThat(requiredNames(schema)).isEqualTo(requiredNames(openapi));

        assertLevelMatches(schema.path("properties").path("limits"), openapi.path("properties").path("limits"));
        assertLevelMatches(schema.path("properties").path("endpoints").path("items"),
                openapi.path("properties").path("endpoints").path("items"));
        assertLevelMatches(
                schema.path("properties").path("endpoints").path("items").path("properties").path("cors"),
                openapi.path("properties").path("endpoints").path("items").path("properties").path("cors"));
        assertLevelMatches(schema.path("properties").path("subscriptions").path("items"),
                openapi.path("properties").path("subscriptions").path("items"));
        assertLevelMatches(schema.path("properties").path("schedules").path("items"),
                openapi.path("properties").path("schedules").path("items"));
        assertLevelMatches(schema.path("properties").path("public").path("items"),
                openapi.path("properties").path("public").path("items"));
        assertLevelMatches(schema.path("properties").path("db").path("items"),
                openapi.path("properties").path("db").path("items"));
    }

    private static void assertLevelMatches(JsonNode schemaObject, JsonNode openapiObject) {
        assertThat(propertyNames(schemaObject)).as("property names").isEqualTo(propertyNames(openapiObject));
        assertThat(requiredNames(schemaObject)).as("required").isEqualTo(requiredNames(openapiObject));
        // Enums too, on the property and on its array items: the two renderings once disagreed on
        // subscriptions[].mode (the OpenAPI document lacked BLOCK_ON_ERROR, which the parser accepts).
        for (String name : propertyNames(schemaObject)) {
            JsonNode a = schemaObject.path("properties").path(name);
            JsonNode b = openapiObject.path("properties").path(name);
            assertThat(enumValues(a)).as(name + " enum").isEqualTo(enumValues(b));
            assertThat(enumValues(a.path("items"))).as(name + "[] enum").isEqualTo(enumValues(b.path("items")));
        }
    }


    // ── M1.5 third assertion: every committed manifest parses under parseStrict ────────────────

    @Test
    void everyCommittedExampleManifestParsesUnderParseStrict() throws IOException {
        Path examples = examplesDir();
        List<Path> manifests;
        try (Stream<Path> walk = Files.walk(examples)) {
            manifests = walk.filter(p -> p.getFileName().toString().equals("manifest.json")).toList();
        }
        assertThat(manifests).as("committed example manifests").isNotEmpty();
        for (Path path : manifests) {
            JsonNode root = Json.MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            Runtime runtime = Runtime.parseStrict(root.path("runtime").asString());
            Manifest manifest = Manifest.parseStrict(root, runtime, DEFAULTS, UNRESTRICTED);
            assertThat(manifest).as(path.toString()).isNotNull();
            // M1.4: every committed manifest under examples/ carries the schema pointer.
            assertThat(root.path("$schema").isString()).as(path + " $schema").isTrue();
        }
    }

    private static Path examplesDir() {
        for (String candidate : new String[]{"../examples", "examples"}) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException(
                "could not locate examples/ from " + Path.of("").toAbsolutePath());
    }

    // ── $schema: accepted, type-checked, never written back ────────────────────────────────────

    private static final String MINIMAL_WITH_SCHEMA = """
            {
              "$schema": "https://example.test/function-manifest.schema.json",
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "endpoints": [ { "path": "/events/invoice-created", "auth": "webhook" } ],
              "subscriptions": [ { "eventType": "billing:invoices:invoice:created", "path": "/events/invoice-created" } ]
            }
            """;

    @Test
    void parseStrictAcceptsAStringSchemaField() {
        Manifest manifest = Manifest.parseStrict(Json.MAPPER.readTree(MINIMAL_WITH_SCHEMA), Runtime.JVM, DEFAULTS,
                UNRESTRICTED);
        assertThat(manifest.entrypoint()).isEqualTo("com.acme.billing.CreateInvoice");
    }

    @Test
    void parseStrictRejectsANonStringSchemaField() {
        String json = """
                {
                  "$schema": 42,
                  "runtime": "jvm",
                  "entrypoint": "com.acme.billing.CreateInvoice"
                }
                """;
        assertThatThrownBy(() -> Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("MANIFEST_INVALID");
                });
    }

    /// `toJson` round-trips through `readStored` to an equal record (the class doc's own
    /// contract) — and never re-introduces `$schema`, proven by parsing the round-tripped JSON
    /// back into a plain [tools.jackson.databind.JsonNode] and checking the key is gone, not
    /// merely by re-parsing with a reader that would silently ignore it either way.
    @Test
    void toJsonNeverWritesBackTheSchemaField() {
        Manifest manifest = Manifest.parseStrict(Json.MAPPER.readTree(MINIMAL_WITH_SCHEMA), Runtime.JVM, DEFAULTS,
                UNRESTRICTED);
        JsonNode written = manifest.toJson();
        assertThat(written.has("$schema")).isFalse();
        assertThat(Manifest.readStored(written)).isEqualTo(manifest);
    }

}
