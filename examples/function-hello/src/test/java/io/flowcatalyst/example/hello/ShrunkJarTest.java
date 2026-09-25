package io.flowcatalyst.example.hello;

import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.Loaded;
import io.flowcatalyst.fnhost.load.LoadOutcome;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.load.Refused;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.EmitResult;
import io.flowcatalyst.function.OutboundEvent;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// **E9** (`docs/spec/function-developer-surface.md` §4): the sample's own
/// shrunk jar, loaded through the REAL host loader, invoked end to end.
/// Bound to the `verify` phase (see `pom.xml`'s own comment) — this test does
/// not exist, and cannot pass, until `package` has run both the shade AND the
/// ProGuard executions, so `mvn -Pexamples -pl examples/function-hello -am
/// verify` is the command that proves anything here.
///
/// Deliberately NOT split by `@Nested`/one-class-per-behaviour: every method
/// shares the one `BeforeAll`-located jar and each is independent — no method
/// depends on another's outcome.
class ShrunkJarTest {

    private static final io.flowcatalyst.platform.function.FunctionAddress PLATFORM_ADDRESS =
            io.flowcatalyst.platform.function.FunctionAddress.parse("hello.default.hello");
    private static final io.flowcatalyst.function.FunctionAddress API_ADDRESS =
            new io.flowcatalyst.function.FunctionAddress("hello", "default", "hello");
    private static final String ENTRYPOINT = "io.flowcatalyst.example.hello.HelloFunction";

    private static Path shrunkJar;

    @BeforeAll
    static void locateShrunkJar() throws IOException {
        Path target = Path.of("target");
        try (Stream<Path> files = Files.list(target)) {
            shrunkJar = files.filter(p -> p.getFileName().toString().endsWith("-shrunk.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no *-shrunk.jar under target/ — this test must run via `mvn verify` "
                                    + "(package + ProGuard), never `mvn test` alone; see pom.xml's surefire comment"));
        }
    }

    // ── the manifest, under the REAL publish reader ──────────────────────────────

    @Test
    void manifestParsesUnderTheRealParseStrict() throws IOException {
        var root = Json.MAPPER.readTree(Files.readString(Path.of("manifest.json")));
        FunctionLimits defaults = FunctionLimits.defaults();
        Manifest manifest = Manifest.parseStrict(root, Runtime.JVM, defaults, ClientCeilings.of(defaults));

        assertThat(manifest.runtime()).isEqualTo(Runtime.JVM);
        assertThat(manifest.entrypoint()).isEqualTo(ENTRYPOINT);
        assertThat(manifest.endpoints()).hasSize(3);
        assertThat(manifest.subscriptions()).hasSize(1);
        assertThat(manifest.subscriptions().get(0).eventType()).isEqualTo("hello:greeting:greeting:requested");
        assertThat(manifest.config()).containsExactly("GREETING");
        assertThat(manifest.secrets()).containsExactly("API_KEY");
        // The platform default limits still apply where the manifest sets nothing
        // stricter — proves this manifest is valid against them, not just parseable.
        assertThat(manifest.limits().maxDurationMs()).isEqualTo(10_000);
        assertThat(manifest.limits().maxConcurrency()).isEqualTo(8);
    }

    /// The event types the manifest subscribes to and the function emits are
    /// codes the PLATFORM will accept: four segments, application first
    /// (`EventTypeCode`). The sample once carried a three-segment code that
    /// parsed fine here and could never be created on a real platform.
    @Test
    void everyEventTypeCodeIsOneThePlatformCanCreate() throws IOException {
        var root = Json.MAPPER.readTree(Files.readString(Path.of("manifest.json")));
        for (var sub : root.path("subscriptions")) {
            var parsed = io.flowcatalyst.platform.eventtype.EventTypeCode.parse(sub.path("eventType").asString());
            assertThat(parsed.application()).as("owned by the sample's own application").isEqualTo("hello");
        }
        assertThat(io.flowcatalyst.platform.eventtype.EventTypeCode.parse("hello:greeting:greeting:sent").application())
                .isEqualTo("hello");
    }

    // ── the shrunk jar's own contents ─────────────────────────────────────────────

    @Test
    void shrunkJarNeverBundlesTheApiJar() throws IOException {
        assertThat(entryNames()).noneMatch(ShrunkJarTest::isApiJarClass);
    }

    private static boolean isApiJarClass(String entryName) {
        return entryName.startsWith("io/flowcatalyst/function/") && entryName.endsWith(".class");
    }

    @Test
    void shrunkJarContainsTheShadedJacksonDependency() throws IOException {
        assertThat(entryNames()).anyMatch(n -> n.startsWith("tools/jackson/databind/"));
    }

    @Test
    void shrunkJarContainsTheEntrypoint() throws IOException {
        assertThat(entryNames()).contains("io/flowcatalyst/example/hello/HelloFunction.class");
    }

    private List<String> entryNames() throws IOException {
        try (JarFile jar = new JarFile(shrunkJar.toFile())) {
            return Collections.list(jar.entries()).stream().map(JarEntry::getName).toList();
        }
    }

    // ── every endpoint, invoked through the real loader ──────────────────────────

    @Test
    void everyEndpointWorksThroughTheRealLoader() throws Exception {
        LoadedFunction fn = loadOrFail();
        try {
            RecordingEvents events = new RecordingEvents();
            FakeFunctionContext ctx = new FakeFunctionContext(API_ADDRESS, 1,
                    Map.of("GREETING", "Howdy"), Map.of("API_KEY", "sekret-value-do-not-log"), events);
            fn.init(ctx);

            // `none` — no caller checked.
            Result health = fn.invoke(
                    request("GET", "/healthz", Caller.Anonymous.INSTANCE, new byte[0], Map.of()), ctx);
            assertThat(health.status()).isEqualTo(200);
            assertThat(bodyOf(health)).contains("\"ok\"");

            // `platform` — a caller WITH the `hello:greeting:greet` permission reaches
            // the function and its principal id comes back in the body.
            Caller.Principal principal = new Caller.Principal("usr_ada", "user", "CLIENT", List.of(), List.of(),
                    List.of(), false, Set.of("hello:greeting:greet"));
            Result hello = fn.invoke(
                    request("GET", "/api/hello/ada", principal, new byte[0], Map.of("name", "ada")), ctx);
            assertThat(hello.status()).isEqualTo(200);
            assertThat(bodyOf(hello)).contains("ada").contains("usr_ada");
            assertThat(ctx.capturingLogger().lines()).anyMatch(l -> l.contains("hello handled"));

            // `webhook` — parses the envelope, reads config+secret, emits, acks.
            Result ack = fn.invoke(
                    request("POST", "/events/greeting-requested", Caller.Platform.INSTANCE,
                            eventEnvelope("world", "corr-1"), Map.of()),
                    ctx);
            assertThat(ack.status()).isEqualTo(200);
            assertThat(ack.body()).isEmpty();

            assertThat(events.emitted).hasSize(1);
            OutboundEvent emitted = events.emitted.get(0);
            assertThat(emitted.type()).isEqualTo("hello:greeting:greeting:sent");
            assertThat(emitted.correlationId()).isEqualTo("corr-1");
            String data = new String(emitted.data(), StandardCharsets.UTF_8);
            assertThat(data).contains("\"world\"").contains("Howdy, world!");

            // The secret's VALUE never reaches a captured log line — its PRESENCE
            // does (function-context.md §1 X1: "a secret value is in no ... log
            // line ... search every captured artefact for the literal").
            assertThat(ctx.capturingLogger().lines()).noneMatch(l -> l.contains("sekret-value-do-not-log"));
            assertThat(ctx.capturingLogger().lines()).anyMatch(l -> l.contains("apiKeyPresent=true"));
        } finally {
            fn.close();
        }
    }

    /// P4 (`docs/spec/function-caller-claims.md` §5): a caller without
    /// `hello:greeting:greet` gets 403 `PERMISSION_REQUIRED`, and the
    /// handler body itself never ran — asserted through the sample's own
    /// logger, exactly as it already does for the secret-presence check
    /// (`everyEndpointWorksThroughTheRealLoader` above), since `handleHello`
    /// logs "hello handled" only once past the permission check. Mutant:
    /// drop the check in `HelloFunction#handleHello` ⇒ this returns 200 and
    /// the log line appears.
    @Test
    void permissionRequiredForHelloWhenCallerLacksIt() throws Exception {
        LoadedFunction fn = loadOrFail();
        try {
            RecordingEvents events = new RecordingEvents();
            FakeFunctionContext ctx = new FakeFunctionContext(API_ADDRESS, 1,
                    Map.of("GREETING", "Howdy"), Map.of("API_KEY", "k"), events);
            fn.init(ctx);

            Caller.Principal noPermission = new Caller.Principal("usr_bob", "user", "CLIENT", List.of(), List.of(),
                    List.of(), false, Set.of());
            Result denied = fn.invoke(
                    request("GET", "/api/hello/bob", noPermission, new byte[0], Map.of("name", "bob")), ctx);

            assertThat(denied.status()).isEqualTo(403);
            assertThat(bodyOf(denied)).contains("PERMISSION_REQUIRED");
            assertThat(ctx.capturingLogger().lines())
                    .as("the handler body must not have run")
                    .noneMatch(l -> l.contains("hello handled"));
        } finally {
            fn.close();
        }
    }

    @Test
    void retriesOnA5xxEventEmitFailure() throws Exception {
        LoadedFunction fn = loadOrFail();
        try {
            RecordingEvents events = new RecordingEvents();
            FakeFunctionContext ctx = new FakeFunctionContext(API_ADDRESS, 1,
                    Map.of("GREETING", "Hi"), Map.of("API_KEY", "k"), events);
            fn.init(ctx);

            events.refuseNext(new EmitResult.Refused("UNAVAILABLE", 503, "platform unreachable"));
            Result retry = fn.invoke(
                    request("POST", "/events/greeting-requested", Caller.Platform.INSTANCE,
                            eventEnvelope("x", "corr-2"), Map.of()),
                    ctx);

            assertThat(retry.status()).isEqualTo(429);
            assertThat(retry.headers()).containsKey("Retry-After");
            assertThat(events.emitted).isEmpty();
        } finally {
            fn.close();
        }
    }

    @Test
    void failsRatherThanRetriesOnA4xxEventEmitFailure() throws Exception {
        LoadedFunction fn = loadOrFail();
        try {
            RecordingEvents events = new RecordingEvents();
            FakeFunctionContext ctx = new FakeFunctionContext(API_ADDRESS, 1,
                    Map.of("GREETING", "Hi"), Map.of("API_KEY", "k"), events);
            fn.init(ctx);

            events.refuseNext(new EmitResult.Refused("EVENT_TYPE_NOT_OWNED", 403, "not owned"));
            Result fail = fn.invoke(
                    request("POST", "/events/greeting-requested", Caller.Platform.INSTANCE,
                            eventEnvelope("x", "corr-3"), Map.of()),
                    ctx);

            assertThat(fail.status()).isEqualTo(500);
            assertThat(fail.headers()).doesNotContainKey("Retry-After");
            assertThat(bodyOf(fail)).contains("EVENT_TYPE_NOT_OWNED");
            assertThat(events.emitted).isEmpty();
        } finally {
            fn.close();
        }
    }

    private static LoadedFunction loadOrFail() {
        LoadOutcome outcome = new JvmFunctionLoader().load(shrunkJar, ENTRYPOINT, PLATFORM_ADDRESS, 1);
        if (outcome instanceof Refused refused) {
            throw new AssertionError("expected the shrunk jar to load, was refused: " + refused);
        }
        return ((Loaded) outcome).function();
    }

    private static byte[] eventEnvelope(String name, String correlationId) {
        String json = "{\"id\":\"evt_1\",\"type\":\"hello:greeting:greeting:requested\",\"attemptNumber\":1,"
                + "\"subject\":\"greeting-" + name + "\",\"correlationId\":\"" + correlationId + "\","
                + "\"data\":{\"name\":\"" + name + "\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Request request(String method, String path, Caller caller, byte[] body,
            Map<String, String> pathParams) {
        return new Request(API_ADDRESS, 1, "inv_" + UUID.randomUUID(), method, path, "localhost", path,
                pathParams, Map.of(), Map.of(), body, "127.0.0.1", caller);
    }

    private static String bodyOf(Result result) {
        return new String(result.body(), StandardCharsets.UTF_8);
    }
}
