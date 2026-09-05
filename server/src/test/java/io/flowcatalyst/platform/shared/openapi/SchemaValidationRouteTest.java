package io.flowcatalyst.platform.shared.openapi;

import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.api.EventTypeApi;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [SchemaValidation] wired through [TestHttp] exactly as production wires it
/// (`TestHttp` registers it unconditionally, next to `ResponseDefaults`) —
/// the end-to-end proof that a real lockfile operation gets the §1 envelope,
/// that it runs BEFORE the handler's own domain checks without replacing
/// them (spec §3), and that a non-lockfile route is untouched.
/// [SchemaValidationTest] proves the message catalogue and the recursive
/// engine directly; this class proves the wiring.
@SuppressWarnings("deprecation")
class SchemaValidationRouteTest {

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var eventTypeState = new EventTypeApi.State(new EventTypeRepository(TestPg.dataSource()),
                new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            EventTypeApi.register(cfg.routes, eventTypeState);
            // A minimal stand-in for LoginApi's own EMAIL_REQUIRED check (auth-core.md) —
            // /auth/login is outside the lockfile, so this proves SchemaValidation left
            // the request alone without dragging in the whole login dependency graph,
            // which has its own dedicated tests.
            cfg.routes.post("/auth/login", ctx -> {
                var body = Json.MAPPER.readTree(ctx.body());
                var email = body.path("email");
                if (!email.isString() || email.stringValue().isBlank()) {
                    HttpError.write(ctx, 400, "EMAIL_REQUIRED", "email is required", Map.of());
                    return;
                }
                ctx.status(200);
            });
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    @Test
    void missingRequiredBodyFieldAnswers400ValidationBeforeAnyHandlerRuns() {
        var r = http.post("/api/event-types", "{}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        var body = Json.MAPPER.readTree(r.body());
        assertThat(body.path("error").stringValue()).isEqualTo("VALIDATION");
        assertThat(body.path("message").stringValue()).isEqualTo("validation failed");
        var first = body.path("details").path("errors").get(0);
        assertThat(first.path("location").stringValue()).isEqualTo("body");
        assertThat(first.path("message").stringValue()).isEqualTo("expected required property code to be present");
        assertThat(first.path("value").toString()).isEqualTo("{}");
    }

    @Test
    void schemaValidPayloadThatFailsADomainRuleStillReachesTheDomainCode() {
        // "" passes the schema (the field IS present and IS a string; the lockfile
        // carries no minLength on `code`) but fails the domain's non-blank rule —
        // proves spec §3's ordering: schema runs first, but never REPLACES the
        // domain check for a value the schema itself lets through.
        var r = http.post("/api/event-types", "{\"code\":\"\",\"name\":\"x\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(Json.MAPPER.readTree(r.body()).path("error").stringValue()).isEqualTo("CODE_REQUIRED");
    }

    @Test
    void unknownPropertyInAnAdditionalPropertiesFalseSchemaIsRejected() {
        // Every top-level Create/Update request in the real lockfile is
        // additionalProperties:true (httpcompat.RelaxRequestBodies — see
        // SchemaValidator's class doc), so an unknown TOP-LEVEL field is
        // silently accepted, not an error. The lockfile's only
        // additionalProperties:false request schema left is a nested
        // bulk/sync array item (SyncRoleInputRequest) — that is what proves
        // the mechanism end to end through a real route.
        var r = http.post("/api/applications/anyapp/roles/sync", "{\"roles\":[{\"name\":\"x\",\"extra\":1}]}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        var body = Json.MAPPER.readTree(r.body());
        assertThat(body.path("error").stringValue()).isEqualTo("VALIDATION");
        var first = body.path("details").path("errors").get(0);
        assertThat(first.path("location").stringValue()).isEqualTo("body.roles[0].extra");
        assertThat(first.path("message").stringValue()).isEqualTo("unexpected property");
    }

    @Test
    void additionalTopLevelFieldOnACreateRequestIsAcceptedNotRejected() {
        // The flip side of the test above, pinned on a real route: CreateEventTypeRequest
        // is additionalProperties:true, so a superset field the Java DTO does not model
        // must NOT 400 — this is the Go behaviour httpcompat.RelaxRequestBodies exists for.
        var r = http.post("/api/event-types",
                "{\"code\":\"" + EntityType.PRINCIPAL.generate() + ":x:y:z\",\"name\":\"n\",\"notModeledByTheDTO\":true}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    }

    @Test
    void nonLockfileRouteIsUntouched() {
        var r = http.post("/auth/login", "{}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(Json.MAPPER.readTree(r.body()).path("error").stringValue()).isEqualTo("EMAIL_REQUIRED");
    }

    @Test
    void badIntegerQueryParameterAnswers400ValidationAtItsLocation() {
        var r = http.get("/api/events?limit=notanumber", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        var body = Json.MAPPER.readTree(r.body());
        assertThat(body.path("error").stringValue()).isEqualTo("VALIDATION");
        var first = body.path("details").path("errors").get(0);
        assertThat(first.path("location").stringValue()).isEqualTo("query.limit");
        assertThat(first.path("message").stringValue()).isEqualTo("invalid integer");
        assertThat(first.path("value").stringValue()).isEqualTo("notanumber");
    }

    @Test
    void handlerStillReceivesTheBodyAfterTheFilterReadsIt() {
        // Javalin caches ctx.body(): if the filter's own read consumed it, the
        // handler behind a VALID request would see an empty/blank body and either
        // 500 or answer with fields it never received. A 201 that echoes the sent
        // code/name back proves the handler read the same bytes the filter did.
        var code = EntityType.PRINCIPAL.generate() + ":still:reads:body";
        var r = http.post("/api/event-types", "{\"code\":\"" + code + "\",\"name\":\"Still Reads Body\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var getResponse = http.get("/api/event-types/by-code/" + code, ANCHOR);
        assertThat(getResponse.statusCode()).as(getResponse.body()).isEqualTo(200);
        var fetched = Json.MAPPER.readTree(getResponse.body());
        assertThat(fetched.path("code").stringValue()).isEqualTo(code);
        assertThat(fetched.path("name").stringValue()).isEqualTo("Still Reads Body");
    }
}
