package io.flowcatalyst.platform.emaildomainmapping.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;

/// The eight `/api/email-domain-mappings*` routes end to end through
/// Javalin: the authenticator's test headers, the anchor-only gates (and the
/// ungated lookup), route precedence between the literal and `{id}` paths,
/// the lockfile status codes and body shapes, and the error envelope.
@SuppressWarnings("deprecation")
class EmailDomainMappingApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String ANCHOR_PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, ANCHOR_PRINCIPAL,
            Authenticator.TEST_SCOPE, "ANCHOR"};
    /// A CLIENT-scoped principal holding every mapping permission, which must not help.
    private static final String[] CLIENT_SCOPED = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "clt_x",
            Authenticator.TEST_PERMISSIONS, "platform:iam:email-domain-mapping:view,platform:iam:email-domain-mapping:create,platform:iam:email-domain-mapping:update,platform:iam:email-domain-mapping:delete,platform:iam:email-domain-mapping:manage"};

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final EmailDomainMappingApi.State state = new EmailDomainMappingApi.State(
            new EmailDomainMappingRepository(TestPg.dataSource()), new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)));
    private static TestHttp http;
    private static String idp;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            EmailDomainMappingApi.register(routes, state);
        });
        idp = identityProvider("OIDC", "Okta " + RUN);
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String domain(String tag) {
        return tag + "-" + RUN + ".example.com";
    }

    private static String identityProvider(String type, String name) {
        String id = EntityType.IDENTITY_PROVIDER.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(OAUTH_IDENTITY_PROVIDERS).set(OAUTH_IDENTITY_PROVIDERS.ID, id)
                .set(OAUTH_IDENTITY_PROVIDERS.CODE, "edmapi-" + RUN + "-" + id.toLowerCase(Locale.ROOT))
                .set(OAUTH_IDENTITY_PROVIDERS.NAME, name).set(OAUTH_IDENTITY_PROVIDERS.TYPE, type)
                .set(OAUTH_IDENTITY_PROVIDERS.CREATED_AT, now).set(OAUTH_IDENTITY_PROVIDERS.UPDATED_AT, now).execute();
        return id;
    }

    private static String create(String body) {
        var r = http.post("/api/email-domain-mappings", body, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("edm_");
        return id;
    }

    private static String create(String emailDomain, String identityProviderId) {
        return create("{\"emailDomain\":\"" + emailDomain + "\",\"identityProviderId\":\"" + identityProviderId + "\",\"scopeType\":\"ANCHOR\"}");
    }

    private static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z";

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createThenReadByIdByDomainByLookupAndInList() {
        String id = create("{\"emailDomain\":\"" + domain("Acme").toUpperCase(Locale.ROOT) + "\",\"identityProviderId\":\"" + idp + "\","
                + "\"scopeType\":\"CLIENT\",\"primaryClientId\":\"clt_p\",\"additionalClientIds\":[\"clt_a\"],\"grantedClientIds\":[\"clt_g\"],"
                + "\"requiredOidcTenantId\":\"tenant-1\",\"require2fa\":true,\"allowed2faMethods\":[\"TOTP\",\"EMAIL_PIN\"],"
                + "\"rememberDeviceEnabled\":true,\"rememberDeviceDays\":14}");

        var created = http.post("/api/email-domain-mappings", "{\"emailDomain\":\"" + domain("beta") + "\",\"identityProviderId\":\"idp_unknown\",\"scopeType\":\"ANCHOR\"}", ANCHOR);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"edm_[0-9A-Z]{13}\"}\n");
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

        var get = http.get("/api/email-domain-mappings/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        var m = json(get);
        assertThat(m.get("id").asText()).isEqualTo(id);
        assertThat(m.get("emailDomain").asText()).as("normalised").isEqualTo(domain("acme"));
        assertThat(m.get("identityProviderId").asText()).isEqualTo(idp);
        assertThat(m.get("identityProviderName").asText()).as("enriched from the IDP row").isEqualTo("Okta " + RUN);
        assertThat(m.get("scopeType").asText()).isEqualTo("CLIENT");
        assertThat(m.get("primaryClientId").asText()).isEqualTo("clt_p");
        assertThat(m.get("additionalClientIds")).extracting(JsonNode::asText).containsExactly("clt_a");
        assertThat(m.get("grantedClientIds")).extracting(JsonNode::asText).containsExactly("clt_g");
        assertThat(m.get("requiredOidcTenantId").asText()).isEqualTo("tenant-1");
        assertThat(m.get("require2fa").asBoolean()).isTrue();
        assertThat(m.get("allowed2faMethods")).extracting(JsonNode::asText).containsExactly("TOTP", "EMAIL_PIN");
        assertThat(m.get("rememberDeviceEnabled").asBoolean()).isTrue();
        assertThat(m.get("rememberDeviceDays").asInt()).isEqualTo(14);
        assertThat(m.get("createdAt").asText()).matches(TS);
        assertThat(m.get("updatedAt").asText()).matches(TS);
        assertThat(m.propertyNames()).containsExactly("id", "emailDomain", "identityProviderId", "identityProviderName",
                "scopeType", "primaryClientId", "additionalClientIds", "grantedClientIds", "requiredOidcTenantId", "require2fa",
                "allowed2faMethods", "rememberDeviceEnabled", "rememberDeviceDays", "createdAt", "updatedAt");

        // A mapping on an unknown provider: name, primaryClientId and tenant omitted; lists present; default days.
        var beta = json(http.get("/api/email-domain-mappings/by-domain/" + domain("beta"), ANCHOR));
        assertThat(beta.has("identityProviderName")).isFalse();
        assertThat(beta.has("primaryClientId")).isFalse();
        assertThat(beta.has("requiredOidcTenantId")).isFalse();
        assertThat(beta.get("additionalClientIds").isArray()).isTrue();
        assertThat(beta.get("allowed2faMethods")).isEmpty();
        assertThat(beta.get("require2fa").asBoolean()).isFalse();
        assertThat(beta.get("rememberDeviceDays").asInt()).isEqualTo(30);
        assertThat(beta.propertyNames()).containsExactly("id", "emailDomain", "identityProviderId", "scopeType",
                "additionalClientIds", "grantedClientIds", "require2fa", "allowed2faMethods", "rememberDeviceEnabled",
                "rememberDeviceDays", "createdAt", "updatedAt");

        var lookup = http.get("/api/email-domain-mappings/lookup?domain=" + domain("acme"), ANCHOR);
        assertThat(lookup.statusCode()).as("lookup must win over /{id}").isEqualTo(200);
        assertThat(json(lookup).get("id").asText()).isEqualTo(id);

        var list = http.get("/api/email-domain-mappings", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.propertyNames()).containsExactly("mappings", "total");
        assertThat(body.get("total").asInt()).isEqualTo(body.get("mappings").size());
        assertThat(body.get("mappings")).extracting(n -> n.get("id").asText()).contains(id);
        assertThat(body.get("mappings")).filteredOn(n -> n.get("id").asText().equals(id))
                .extracting(n -> n.get("identityProviderName").asText()).containsExactly("Okta " + RUN);
    }

    @Test
    void lookupAnswersFoundFalseNeedsADomainAndHasNoGate() {
        var miss = http.get("/api/email-domain-mappings/lookup?domain=" + domain("nobody"), ANCHOR);
        assertThat(miss.statusCode()).isEqualTo(200);
        assertThat(miss.body()).isEqualTo("{\"found\":false}\n");

        var noDomain = http.get("/api/email-domain-mappings/lookup", ANCHOR);
        assertThat(noDomain.statusCode()).isEqualTo(400);
        assertThat(noDomain.body()).isEqualTo("{\"error\":\"DOMAIN_REQUIRED\",\"message\":\"domain query param is required\"}\n");

        String id = create(domain("open"), idp);
        var asClient = http.get("/api/email-domain-mappings/lookup?domain=" + domain("open"), CLIENT_SCOPED);
        assertThat(asClient.statusCode()).as("lookup is not anchor-gated (spec §3, open question 1)").isEqualTo(200);
        assertThat(json(asClient).get("id").asText()).isEqualTo(id);
        var anonymous = http.get("/api/email-domain-mappings/lookup?domain=" + domain("open"));
        assertThat(anonymous.statusCode()).isEqualTo(200);
        var asGiven = http.get("/api/email-domain-mappings/lookup?domain=" + domain("OPEN"), ANCHOR);
        assertThat(asGiven.body()).as("matched as given, not normalised").isEqualTo("{\"found\":false}\n");
    }

    @Test
    void updateReturns204AndPersists() {
        String id = create(domain("upd"), idp);
        var put = http.put("/api/email-domain-mappings/" + id, "{\"primaryClientId\":\"clt_p\",\"require2fa\":true,\"allowed2faMethods\":[\"TOTP\"],\"rememberDeviceDays\":7}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(put.body()).isEmpty();
        var m = json(http.get("/api/email-domain-mappings/" + id, ANCHOR));
        assertThat(m.get("primaryClientId").asText()).isEqualTo("clt_p");
        assertThat(m.get("require2fa").asBoolean()).isTrue();
        assertThat(m.get("allowed2faMethods")).extracting(JsonNode::asText).containsExactly("TOTP");
        assertThat(m.get("rememberDeviceDays").asInt()).isEqualTo(7);
        assertThat(m.get("identityProviderId").asText()).as("provider not updatable here").isEqualTo(idp);

        var empty = http.put("/api/email-domain-mappings/" + id, "{}", ANCHOR);
        assertThat(empty.statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/email-domain-mappings/" + id, ANCHOR)).has("primaryClientId")).as("absent primaryClientId clears").isFalse();

        var bad = http.put("/api/email-domain-mappings/" + id, "{\"allowed2faMethods\":[]}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("2FA_METHOD_REQUIRED");

        var badMethod = http.put("/api/email-domain-mappings/" + id, "{\"allowed2faMethods\":[\"SMS\"]}", ANCHOR);
        assertThat(badMethod.statusCode()).isEqualTo(400);
        assertThat(json(badMethod).get("error").asText()).isEqualTo("INVALID_2FA_METHOD");
        assertThat(json(badMethod).get("message").asText()).isEqualTo("allowed2faMethods entries must be TOTP or EMAIL_PIN");
    }

    @Test
    void moveProviderReturnsTheResultEnvelope() {
        String id = create(domain("move"), idp);
        String internal = identityProvider("INTERNAL", "Internal " + RUN);

        var move = http.post("/api/email-domain-mappings/" + id + "/move-provider", "{\"identityProviderId\":\"" + internal + "\"}", ANCHOR);
        assertThat(move.statusCode()).as(move.body()).isEqualTo(200);
        var r = json(move);
        assertThat(r.propertyNames()).containsExactly("mappingId", "emailDomain", "fromIdentityProviderId", "toIdentityProviderId", "usersReset");
        assertThat(r.get("mappingId").asText()).isEqualTo(id);
        assertThat(r.get("emailDomain").asText()).isEqualTo(domain("move"));
        assertThat(r.get("fromIdentityProviderId").asText()).isEqualTo(idp);
        assertThat(r.get("toIdentityProviderId").asText()).isEqualTo(internal);
        assertThat(r.get("usersReset").asInt()).isZero();
        assertThat(json(http.get("/api/email-domain-mappings/" + id, ANCHOR)).get("identityProviderName").asText()).isEqualTo("Internal " + RUN);

        var again = http.post("/api/email-domain-mappings/" + id + "/move-provider", "{\"identityProviderId\":\"" + internal + "\"}", ANCHOR);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json(again).get("error").asText()).isEqualTo("ALREADY_ON_PROVIDER");

        var unknown = http.post("/api/email-domain-mappings/" + id + "/move-provider", "{\"identityProviderId\":\"idp_doesnotexist1\"}", ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).isEqualTo("{\"error\":\"IdentityProvider_NOT_FOUND\",\"message\":\"IdentityProvider not found: idp_doesnotexist1\"}\n");

        // identityProviderId is schema-required on MoveProviderRequest — sent as "" so the
        // request reaches the domain check instead of 400 VALIDATION.
        var noTarget = http.post("/api/email-domain-mappings/" + id + "/move-provider", "{\"identityProviderId\":\"\"}", ANCHOR);
        assertThat(noTarget.statusCode()).isEqualTo(400);
        assertThat(json(noTarget).get("error").asText()).isEqualTo("IDP_REQUIRED");
    }

    @Test
    void deleteReturns204ThenReadsAre404Envelopes() {
        String id = create(domain("del"), idp);
        var del = http.delete("/api/email-domain-mappings/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/email-domain-mappings/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).isEqualTo("{\"error\":\"EmailDomainMapping_NOT_FOUND\",\"message\":\"EmailDomainMapping not found: " + id + "\"}\n");

        var again = http.delete("/api/email-domain-mappings/" + id, ANCHOR);
        assertThat(again.statusCode()).isEqualTo(404);

        var byDomain = http.get("/api/email-domain-mappings/by-domain/" + domain("del"), ANCHOR);
        assertThat(byDomain.statusCode()).isEqualTo(404);
        assertThat(json(byDomain).get("message").asText()).isEqualTo("EmailDomainMapping not found: " + domain("del"));

        var put = http.put("/api/email-domain-mappings/" + id, "{}", ANCHOR);
        assertThat(put.statusCode()).isEqualTo(404);
    }

    // ── Validation and conflict envelopes ──────────────────────────────────

    @Test
    void createValidationAndConflictEnvelopes() {
        // emailDomain is schema-required too — sent as "" so the request reaches the domain check.
        var noDomain = http.post("/api/email-domain-mappings", "{\"emailDomain\":\"\",\"identityProviderId\":\"idp_x\",\"scopeType\":\"ANCHOR\"}", ANCHOR);
        assertThat(noDomain.statusCode()).isEqualTo(400);
        assertThat(noDomain.body()).isEqualTo("{\"error\":\"EMAIL_DOMAIN_REQUIRED\",\"message\":\"Email domain is required\"}\n");

        var badDomain = http.post("/api/email-domain-mappings", "{\"emailDomain\":\"nodot\",\"identityProviderId\":\"idp_x\",\"scopeType\":\"ANCHOR\"}", ANCHOR);
        assertThat(json(badDomain).get("error").asText()).isEqualTo("INVALID_EMAIL_DOMAIN");

        var badScope = http.post("/api/email-domain-mappings", "{\"emailDomain\":\"" + domain("scope") + "\",\"identityProviderId\":\"idp_x\",\"scopeType\":\"GLOBAL\"}", ANCHOR);
        assertThat(badScope.statusCode()).isEqualTo(400);
        assertThat(json(badScope).get("error").asText()).isEqualTo("INVALID_SCOPE_TYPE");

        var noPrimary = http.post("/api/email-domain-mappings", "{\"emailDomain\":\"" + domain("partner") + "\",\"identityProviderId\":\"idp_x\",\"scopeType\":\"PARTNER\"}", ANCHOR);
        assertThat(json(noPrimary).get("error").asText()).isEqualTo("PRIMARY_CLIENT_REQUIRED");

        var noMethod = http.post("/api/email-domain-mappings", "{\"emailDomain\":\"" + domain("2fa") + "\",\"identityProviderId\":\"idp_x\",\"scopeType\":\"ANCHOR\",\"require2fa\":true}", ANCHOR);
        assertThat(json(noMethod).get("error").asText()).isEqualTo("2FA_METHOD_REQUIRED");

        create(domain("dup"), idp);
        var dup = http.post("/api/email-domain-mappings", "{\"emailDomain\":\"" + domain("DUP") + "\",\"identityProviderId\":\"idp_x\",\"scopeType\":\"ANCHOR\"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("DOMAIN_ALREADY_MAPPED");

        var malformed = http.post("/api/email-domain-mappings", "{not json", ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_JSON");
    }

    // ── Gates ──────────────────────────────────────────────────────────────

    @Test
    void everyRouteExceptLookupIsAnchorOnly() {
        String id = create(domain("gate"), idp);
        String body = "{\"emailDomain\":\"" + domain("gate2") + "\",\"identityProviderId\":\"idp_x\",\"scopeType\":\"ANCHOR\"}";

        for (var r : List.of(
                http.get("/api/email-domain-mappings", CLIENT_SCOPED),
                http.post("/api/email-domain-mappings", body, CLIENT_SCOPED),
                http.get("/api/email-domain-mappings/by-domain/" + domain("gate"), CLIENT_SCOPED),
                http.get("/api/email-domain-mappings/" + id, CLIENT_SCOPED),
                http.put("/api/email-domain-mappings/" + id, "{}", CLIENT_SCOPED),
                http.post("/api/email-domain-mappings/" + id + "/move-provider", "{\"identityProviderId\":\"idp_x\"}", CLIENT_SCOPED),
                http.delete("/api/email-domain-mappings/" + id, CLIENT_SCOPED))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(r.body()).isEqualTo("{\"error\":\"ANCHOR_REQUIRED\",\"message\":\"anchor scope required\"}\n");
        }

        var anonymous = http.get("/api/email-domain-mappings/" + id);
        assertThat(anonymous.statusCode()).isEqualTo(403);
        assertThat(json(anonymous).get("error").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(json(http.get("/api/email-domain-mappings/" + id, ANCHOR)).get("id").asText()).as("nothing was deleted").isEqualTo(id);
    }
}
