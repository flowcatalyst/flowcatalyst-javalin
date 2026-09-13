package io.flowcatalyst.platform.loginattempt.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `GET /api/login-attempts` end to end through Javalin (spec §2–4): the
/// anchor gate, the lockfile envelope and entry shape, the filters, the
/// cursor walk and the lenient inputs.
@SuppressWarnings("deprecation")
class LoginAttemptApiTest {

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] CLIENT_ADMIN = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "cli_latapi",
            Authenticator.TEST_PERMISSIONS, "platform:admin:*"};
    /// An anchor holding every permission EXCEPT the login-attempt family (spec `reach-only-routes.md` §3).
    private static final String[] ANCHOR_NO_LOGIN_ATTEMPT_PERMS = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    /// An anchor holding only the specific view code (not the wildcard).
    private static final String[] ANCHOR_LOGIN_ATTEMPT_VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:admin:login-attempt:view"};

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String ADA = "ada.api." + RUN + "@example.test";
    private static final String BOB = "bob.api." + RUN + "@example.test";
    private static final Instant BASE = Instant.parse("2026-04-01T08:00:00.500000Z");
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();

    private static final LoginAttemptRepository repo = new LoginAttemptRepository(TestPg.dataSource());
    private static List<String> adaNewestFirst;
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            LoginAttemptApi.register(routes, new LoginAttemptApi.State(repo));
        });
        var ids = new ArrayList<String>();
        ids.add(record(AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, ADA, PRINCIPAL, "10.0.0.1", "Mozilla", null, BASE));
        for (int i = 1; i < 5; i++) {
            ids.add(record(AttemptType.USER_LOGIN, AttemptOutcome.FAILURE, ADA, null, "10.0.0.1", null, "Invalid credentials", BASE.minusSeconds(i)));
        }
        adaNewestFirst = List.copyOf(ids);
        record(AttemptType.SERVICE_ACCOUNT_TOKEN, AttemptOutcome.FAILURE, BOB, null, null, null, "invalid_client", BASE.minusSeconds(20));
        // a row with no identifier at all — the wire shows ""
        record(AttemptType.SERVICE_ACCOUNT_TOKEN, AttemptOutcome.FAILURE, null, PRINCIPAL, null, null, "no client id", BASE.minusSeconds(30));
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static String record(AttemptType type, AttemptOutcome outcome, String identifier, String principalId,
                                 String ip, String userAgent, String failureReason, Instant at) {
        var a = new LoginAttempt(EntityType.LOGIN_ATTEMPT.generate(), type, outcome, failureReason, identifier, principalId, ip, userAgent, at);
        repo.recordAttempt(a);
        return a.id();
    }

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static JsonNode ok(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        return json(r);
    }

    // ── Envelope + entry shape ─────────────────────────────────────────────

    @Test
    void listEnvelopeAndEntryShapeAreTheLockfiles() {
        var body = ok(http.get("/api/login-attempts?identifier=" + q(ADA), ANCHOR));
        assertThat(body.propertyNames()).containsExactly("items", "hasMore");
        assertThat(body.get("hasMore").asBoolean()).isFalse();
        var items = body.get("items");
        assertThat(items).extracting(n -> n.get("id").asText()).containsExactlyElementsOf(adaNewestFirst);
        var first = items.get(0);
        assertThat(first.propertyNames()).containsExactly(
                "id", "attemptType", "outcome", "failureReason", "identifier", "principalId", "ipAddress", "userAgent", "attemptedAt");
        assertThat(first.get("attemptType").asText()).isEqualTo("USER_LOGIN");
        assertThat(first.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(first.get("failureReason").isNull()).as("absent optionals are JSON null, not omitted").isTrue();
        assertThat(first.get("identifier").asText()).isEqualTo(ADA);
        assertThat(first.get("principalId").asText()).isEqualTo(PRINCIPAL);
        assertThat(first.get("ipAddress").asText()).isEqualTo("10.0.0.1");
        assertThat(first.get("userAgent").asText()).isEqualTo("Mozilla");
        assertThat(first.get("attemptedAt").asText()).isEqualTo("2026-04-01T08:00:00.500000Z");
        var failure = items.get(1);
        assertThat(failure.get("failureReason").asText()).isEqualTo("Invalid credentials");
        assertThat(failure.get("principalId").isNull()).isTrue();
        assertThat(failure.get("userAgent").isNull()).isTrue();
    }

    @Test
    void aRowWithoutIdentifierShowsAnEmptyStringOnTheWire() {
        var items = ok(http.get("/api/login-attempts?principalId=" + PRINCIPAL + "&attemptType=SERVICE_ACCOUNT_TOKEN", ANCHOR)).get("items");
        assertThat(items).singleElement().satisfies(n -> {
            assertThat(n.get("identifier").isTextual()).isTrue();
            assertThat(n.get("identifier").asText()).isEmpty();
            assertThat(n.get("failureReason").asText()).isEqualTo("no client id");
        });
    }

    // ── Cursor walk ────────────────────────────────────────────────────────

    @Test
    void cursorWalkVisitsEveryRowOnceNewestFirst() {
        var seen = new ArrayList<String>();
        String path = "/api/login-attempts?pageSize=2&identifier=" + q(ADA);
        var page = ok(http.get(path, ANCHOR));
        int pages = 1;
        while (true) {
            page.get("items").forEach(n -> seen.add(n.get("id").asText()));
            if (!page.get("hasMore").asBoolean()) {
                assertThat(page.has("nextCursor")).isFalse();
                break;
            }
            assertThat(page.get("items")).hasSize(2);
            assertThat(page.get("nextCursor").asText()).isNotBlank();
            page = ok(http.get(path + "&after=" + page.get("nextCursor").asText(), ANCHOR));
            pages++;
        }
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(adaNewestFirst);
    }

    // ── Filters + lenient inputs ───────────────────────────────────────────

    @Test
    void filtersNarrowTheWindow() {
        assertThat(ok(http.get("/api/login-attempts?identifier=" + q(ADA) + "&outcome=FAILURE", ANCHOR)).get("items")).hasSize(4);
        assertThat(ok(http.get("/api/login-attempts?identifier=" + q(BOB) + "&attemptType=SERVICE_ACCOUNT_TOKEN", ANCHOR)).get("items")).hasSize(1);
        assertThat(ok(http.get("/api/login-attempts?identifier=" + q(BOB) + "&attemptType=USER_LOGIN", ANCHOR)).get("items")).isEmpty();
        assertThat(ok(http.get("/api/login-attempts?identifier=" + q(ADA) + "&principalId=" + PRINCIPAL, ANCHOR)).get("items")).hasSize(1);
        // inclusive RFC 3339 bounds, any offset
        String from = q("2026-04-01T09:59:58+02:00");
        String to = q("2026-04-01T07:59:59.5Z");
        assertThat(ok(http.get("/api/login-attempts?identifier=" + q(ADA) + "&dateFrom=" + from + "&dateTo=" + to, ANCHOR)).get("items"))
                .extracting(n -> n.get("id").asText()).containsExactlyElementsOf(adaNewestFirst.subList(1, 3));
        // an unknown enum value lists nothing
        assertThat(ok(http.get("/api/login-attempts?identifier=" + q(ADA) + "&outcome=bogus", ANCHOR)).get("items")).isEmpty();
    }

    @Test
    void malformedCursorAndDatesAreIgnoredAndPageSizeOutOfRangeResets() {
        String base = "/api/login-attempts?identifier=" + q(ADA);
        assertThat(ok(http.get(base + "&after=not-a-cursor", ANCHOR)).get("items")).as("first page").hasSize(5);
        assertThat(ok(http.get(base + "&dateFrom=yesterday&dateTo=", ANCHOR)).get("items")).as("no bound").hasSize(5);
        assertThat(ok(http.get(base + "&pageSize=0", ANCHOR)).get("items")).hasSize(5);
        assertThat(ok(http.get(base + "&pageSize=201", ANCHOR)).get("items")).hasSize(5);
        assertThat(ok(http.get(base + "&pageSize=3", ANCHOR)).get("items")).hasSize(3);
    }

    @Test
    void nonIntegerPageSizeIsA400Envelope() {
        var size = http.get("/api/login-attempts?pageSize=ten", ANCHOR);
        assertThat(size.statusCode()).isEqualTo(400);
        var env = json(size);
        assertThat(env.get("error").asText()).isEqualTo("VALIDATION");
        var detail = env.get("details").get("errors").get(0);
        assertThat(detail.get("message").asText()).isEqualTo("invalid integer");
        assertThat(detail.get("location").asText()).isEqualTo("query.pageSize");
        assertThat(detail.get("value").asText()).isEqualTo("ten");
    }

    // ── Gate ───────────────────────────────────────────────────────────────

    @Test
    void theListIsAnchorOnly() {
        var anon = http.get("/api/login-attempts");
        assertThat(anon.statusCode()).isEqualTo(403);
        assertThat(json(anon).get("error").asText()).isEqualTo("UNAUTHENTICATED");
        var client = http.get("/api/login-attempts", CLIENT_ADMIN);
        assertThat(client.statusCode()).isEqualTo(403);
        assertThat(client.body()).isEqualTo("{\"error\":\"ANCHOR_REQUIRED\",\"message\":\"anchor scope required\"}\n");
    }

    /// docs/spec/reach-only-routes.md §1/§3: an anchor without
    /// LOGIN_ATTEMPT_VIEW is refused, and the specific view code (not the
    /// wildcard) is enough to read. This aggregate has no write route.
    /// Filtered by `identifier` (like every other read in this class) so an
    /// unrelated corrupt row seeded elsewhere in the shared database
    /// (`LoginAttemptRepositoryTest`'s deliberately-malformed `SOMETHING_NEW`
    /// row) cannot 500 the unfiltered list.
    @Test
    void anchorWithoutLoginAttemptPermissionIsRefusedButTheSpecificPermissionSucceeds() {
        var denied = http.get("/api/login-attempts?identifier=" + q(ADA), ANCHOR_NO_LOGIN_ATTEMPT_PERMS);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var allowed = http.get("/api/login-attempts?identifier=" + q(ADA), ANCHOR_LOGIN_ATTEMPT_VIEW_ONLY);
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
    }
}
