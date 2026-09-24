package io.flowcatalyst.platform.identityprovider.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Decryption;
import io.flowcatalyst.platform.shared.encryption.Encryption;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;

/// The five `/api/identity-providers*` routes end to end through Javalin:
/// the authenticator's test headers, the anchor-only gates, the lockfile
/// status codes and body shapes, the error envelope, and the client
/// secret's at-rest conversion with and without an encryption key (spec §5).
@SuppressWarnings("deprecation")
class IdentityProviderApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    private static final String[] CLIENT_SCOPED = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "clt_idpapitest00",
            Authenticator.TEST_PERMISSIONS, "*"};
    /// An anchor holding every permission EXCEPT the IdP family (spec `reach-only-routes.md` §3).
    private static final String[] ANCHOR_NO_IDP_PERMS = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    /// An anchor holding only the specific view code (not the wildcard).
    private static final String[] ANCHOR_IDP_VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:iam:idp:view"};

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final Encryption ENCRYPTION = Encryption.withKey(Encryption.generateKey());
    private static final IdentityProviderRepository repo = new IdentityProviderRepository(TestPg.dataSource());
    private static final EmailDomainMappingRepository mappings = new EmailDomainMappingRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static TestHttp http;
    private static TestHttp httpNoKey;

    /// Provider ids the change hook (auth-identity ruling Q1) reported.
    private static final java.util.List<String> CHANGED = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        http = server(ClientSecretEncryption.of(Optional.of(ENCRYPTION)));
        httpNoKey = server(ClientSecretEncryption.of(Optional.empty()));
    }

    private static TestHttp server(ClientSecretEncryption secrets) {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        var state = new IdentityProviderApi.State(repo, mappings, uow, secrets, CHANGED::add);
        return TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            IdentityProviderApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        httpNoKey.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String code(String tag) {
        return tag + "-" + RUN;
    }

    private static String domain(String tag) {
        return tag + "-" + RUN + ".example.com";
    }

    private static String oidcBody(String code, String secretJson, String extra) {
        return "{\"code\":\"" + code + "\",\"name\":\"Name " + code + "\",\"type\":\"OIDC\","
                + "\"oidcIssuerUrl\":\"https://login.example.com/v2.0\",\"oidcClientId\":\"cid\","
                + (secretJson == null ? "" : "\"oidcClientSecretRef\":" + secretJson + ",")
                + "\"oidcMultiTenant\":false" + extra + "}";
    }

    private static String create(TestHttp via, String body) {
        var r = via.post("/api/identity-providers", body, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var id = json(r).get("id").asText();
        assertThat(id).startsWith("idp_");
        return id;
    }

    private static String storedSecret(String id) {
        return DB.select(OAUTH_IDENTITY_PROVIDERS.OIDC_CLIENT_SECRET_REF).from(OAUTH_IDENTITY_PROVIDERS)
                .where(OAUTH_IDENTITY_PROVIDERS.ID.eq(id)).fetchOne(OAUTH_IDENTITY_PROVIDERS.OIDC_CLIENT_SECRET_REF);
    }

    private static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z";

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    void createReturnsTheFullProviderThenReadByIdAndInList() {
        var created = http.post("/api/identity-providers", oidcBody(code("api-crt"), "\"plain-secret\"",
                ",\"allowedEmailDomains\":[\"" + domain("API-Crt").toUpperCase(Locale.ROOT) + "\"],\"mappingScope\":\"ANCHOR\",\"syncRolesFromIdp\":true,\"allowedRoleIds\":[\"rol_a\"]"), ANCHOR);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var c = json(created);
        String id = c.get("id").asText();
        assertThat(id).startsWith("idp_");
        assertThat(c.get("code").asText()).isEqualTo(code("api-crt"));
        assertThat(c.get("name").asText()).isEqualTo("Name " + code("api-crt"));
        assertThat(c.get("type").asText()).isEqualTo("OIDC");
        assertThat(c.get("oidcIssuerUrl").asText()).isEqualTo("https://login.example.com/v2.0");
        assertThat(c.get("oidcClientId").asText()).isEqualTo("cid");
        assertThat(c.get("hasClientSecret").asBoolean()).isTrue();
        assertThat(c.has("oidcClientSecretRef")).as("the secret ref is never serialised").isFalse();
        assertThat(c.get("oidcMultiTenant").asBoolean()).isFalse();
        assertThat(c.has("oidcIssuerPattern")).as("null optional omitted").isFalse();
        assertThat(c.get("allowedEmailDomains")).extracting(JsonNode::asText).containsExactly(domain("api-crt"));
        assertThat(c.get("syncRolesFromIdp").asBoolean()).isTrue();
        assertThat(c.get("allowedRoleIds")).extracting(JsonNode::asText).containsExactly("rol_a");
        assertThat(c.get("createdAt").asText()).matches(TS);
        assertThat(c.get("updatedAt").asText()).matches(TS);
        assertThat(c.propertyNames()).containsExactly("id", "code", "name", "type", "oidcIssuerUrl", "oidcClientId",
                "hasClientSecret", "oidcMultiTenant", "allowedEmailDomains", "syncRolesFromIdp", "allowedRoleIds", "createdAt", "updatedAt");

        // The plaintext was sealed before the command was built: stored as `encrypted:` and decryptable, never in the audit.
        String stored = storedSecret(id);
        assertThat(stored).startsWith("encrypted:");
        assertThat(ENCRYPTION.decrypt(stored)).isEqualTo(new Decryption.Plaintext("plain-secret"));
        assertThat(DB.fetchOne("SELECT operation_json::text AS j FROM aud_logs WHERE entity_id = ? AND operation = 'CreateCommand'", id).get("j", String.class))
                .as("docs/spec/audit-redaction.md: neither the plaintext nor the ciphertext reaches the audit row")
                .doesNotContain("plain-secret").doesNotContain("encrypted:").contains("\"oidcClientSecretRef\": \"***\"");

        var get = http.get("/api/identity-providers/" + id, ANCHOR);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(json(get).get("id").asText()).isEqualTo(id);

        var list = http.get("/api/identity-providers", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.propertyNames()).containsExactly("identityProviders", "total");
        assertThat(body.get("total").asInt()).isEqualTo(body.get("identityProviders").size());
        assertThat(body.get("identityProviders")).extracting(n -> n.get("id").asText()).contains(id);
    }

    @Test
    void updateReturns200WithTheProviderAndCanClearTheSecret() {
        String id = create(http, oidcBody(code("api-upd"), "\"encrypted:" + ENCRYPTION.encrypt("s") + "\"", ""));

        assertThat(CHANGED).as("create does not fire the change hook (nothing cached yet)").doesNotContain(id);
        var put = http.put("/api/identity-providers/" + id, "{\"name\":\"  After  \",\"oidcMultiTenant\":true}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(CHANGED).as("ruling Q1: an update reports the provider id so the OIDC cache drops it").contains(id);
        assertThat(json(put).get("name").asText()).isEqualTo("After");
        assertThat(json(put).get("oidcMultiTenant").asBoolean()).isTrue();
        assertThat(json(put).get("hasClientSecret").asBoolean()).as("untouched secret").isTrue();

        // A rotated plaintext secret is sealed in the handler: stored encrypted, never in any UpdateCommand audit row.
        var rotated = http.put("/api/identity-providers/" + id, "{\"oidcClientSecretRef\":\"rotated-plain\"}", ANCHOR);
        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(ENCRYPTION.decrypt(storedSecret(id))).isEqualTo(new Decryption.Plaintext("rotated-plain"));
        assertThat(DB.fetch("SELECT operation_json::text AS j FROM aud_logs WHERE entity_id = ? AND operation = 'UpdateCommand'", id))
                .isNotEmpty().allSatisfy(row -> assertThat(row.get("j", String.class)).doesNotContain("rotated-plain"));

        var cleared = http.put("/api/identity-providers/" + id, "{\"oidcClientSecretRef\":\"\"}", ANCHOR);
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(json(cleared).get("hasClientSecret").asBoolean()).as("\"\" clears the secret").isFalse();
        assertThat(storedSecret(id)).isNull();

        var noop = http.put("/api/identity-providers/" + id, "{}", ANCHOR);
        assertThat(noop.statusCode()).as("every field optional").isEqualTo(200);

        var bad = http.put("/api/identity-providers/" + id, "{\"name\":\"  \"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("NAME_REQUIRED");
    }

    @Test
    void deleteReturns204ThenTheProviderIsGoneAndIsRefusedWhileDomainsRouteToIt() {
        String id = create(http, oidcBody(code("api-del"), null, ""));
        var del = http.delete("/api/identity-providers/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(CHANGED).as("a delete reports the provider id too").contains(id);
        assertThat(del.body()).isEmpty();
        var gone = http.get("/api/identity-providers/" + id, ANCHOR);
        assertThat(gone.statusCode()).isEqualTo(404);
        assertThat(json(gone).get("error").asText()).isEqualTo("IdentityProvider_NOT_FOUND");

        String mapped = create(http, oidcBody(code("api-delguard"), null, ",\"allowedEmailDomains\":[\"" + domain("api-delguard") + "\"],\"mappingScope\":\"ANCHOR\""));
        var blocked = http.delete("/api/identity-providers/" + mapped, ANCHOR);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(json(blocked).get("error").asText()).isEqualTo("DOMAINS_STILL_MAPPED");
        assertThat(json(blocked).get("message").asText()).contains(domain("api-delguard"));
    }

    // ── Errors ─────────────────────────────────────────────────────────────

    @Test
    void validationAndConflictUseTheErrorEnvelope() {
        // name is schema-required too — sent as "" so the request reaches the domain check.
        var missingName = http.post("/api/identity-providers",
                "{\"code\":\"" + code("api-noname") + "\",\"name\":\"\",\"type\":\"INTERNAL\",\"oidcMultiTenant\":false}", ANCHOR);
        assertThat(missingName.statusCode()).isEqualTo(400);
        assertThat(json(missingName).get("error").asText()).isEqualTo("NAME_REQUIRED");
        assertThat(json(missingName).propertyNames()).containsExactly("error", "message");

        var oidcNoIssuer = http.post("/api/identity-providers", "{\"code\":\"" + code("api-noiss") + "\",\"name\":\"X\",\"type\":\"OIDC\",\"oidcMultiTenant\":false}", ANCHOR);
        assertThat(oidcNoIssuer.statusCode()).isEqualTo(400);
        assertThat(json(oidcNoIssuer).get("error").asText()).isEqualTo("OIDC_ISSUER_REQUIRED");

        create(http, oidcBody(code("api-dup"), null, ""));
        var dup = http.post("/api/identity-providers", oidcBody(code("api-dup"), null, ""), ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("CODE_EXISTS");

        var unknown = http.put("/api/identity-providers/idp_doesnotexist1", "{\"name\":\"X\"}", ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asText()).isEqualTo("IdentityProvider_NOT_FOUND");
    }

    /// Owner ruling 2026-09-15 (spec §4): `mappingScope` on the wire reaches
    /// the command and drives the mapping's actual scope — never a silent
    /// ANCHOR default — and a request that would create a mapping without
    /// choosing one is a 400 carrying the code, with nothing persisted.
    @Test
    void mappingScopeOnTheWireDrivesTheMappingAndAMissingScopeIsA400() {
        var noScope = http.post("/api/identity-providers", oidcBody(code("api-noscope"), null,
                ",\"allowedEmailDomains\":[\"" + domain("api-noscope") + "\"]"), ANCHOR);
        assertThat(noScope.statusCode()).isEqualTo(400);
        assertThat(json(noScope).get("error").asText()).isEqualTo("MAPPING_SCOPE_REQUIRED");
        assertThat(repo.findByCode(code("api-noscope"))).as("nothing persisted on a rolled-back create").isEmpty();

        var clientId = "clt_apicrtclient";
        String id = create(http, oidcBody(code("api-clientscope"), null,
                ",\"allowedEmailDomains\":[\"" + domain("api-clientscope") + "\"],\"mappingScope\":\"CLIENT\",\"primaryClientId\":\"" + clientId + "\""));
        var got = json(http.get("/api/identity-providers/" + id, ANCHOR));
        assertThat(got.get("allowedEmailDomains")).extracting(JsonNode::asText).containsExactly(domain("api-clientscope"));

        // PARTNER is rejected at the schema layer (the lockfile's mappingScope enum is ANCHOR|CLIENT only,
        // spec §4); INVALID_MAPPING_SCOPE from the operation itself is exercised at the operations layer.

        var clientNoScope = http.post("/api/identity-providers", oidcBody(code("api-clientnoscope"), null,
                ",\"primaryClientId\":\"clt_apinoscopeclient\""), ANCHOR);
        assertThat(clientNoScope.statusCode()).isEqualTo(400);
        assertThat(json(clientNoScope).get("error").asText()).isEqualTo("MAPPING_SCOPE_REQUIRED");

        var anchorWithClient = http.post("/api/identity-providers", oidcBody(code("api-anchorclient"), null,
                ",\"mappingScope\":\"ANCHOR\",\"primaryClientId\":\"clt_apianchorclient\""), ANCHOR);
        assertThat(anchorWithClient.statusCode()).isEqualTo(400);
        assertThat(json(anchorWithClient).get("error").asText()).isEqualTo("PRIMARY_CLIENT_NOT_ALLOWED");

        var clientNoPrimary = http.post("/api/identity-providers", oidcBody(code("api-clientnoprimary"), null,
                ",\"mappingScope\":\"CLIENT\""), ANCHOR);
        assertThat(clientNoPrimary.statusCode()).isEqualTo(400);
        assertThat(json(clientNoPrimary).get("error").asText()).isEqualTo("PRIMARY_CLIENT_REQUIRED");
    }

    @Test
    void everyRouteIsAnchorOnly() {
        String id = create(http, oidcBody(code("api-gate"), null, ""));
        assertThat(http.get("/api/identity-providers", CLIENT_SCOPED).statusCode()).isEqualTo(403);
        assertThat(http.get("/api/identity-providers/" + id, CLIENT_SCOPED).statusCode()).isEqualTo(403);
        assertThat(http.post("/api/identity-providers", oidcBody(code("api-gate2"), null, ""), CLIENT_SCOPED).statusCode()).isEqualTo(403);
        assertThat(http.put("/api/identity-providers/" + id, "{\"name\":\"X\"}", CLIENT_SCOPED).statusCode()).isEqualTo(403);
        var del = http.delete("/api/identity-providers/" + id, CLIENT_SCOPED);
        assertThat(del.statusCode()).isEqualTo(403);
        assertThat(json(del).get("error").asText()).isEqualTo("ANCHOR_REQUIRED");
        assertThat(repo.findById(id)).as("gate ran before the operation").isPresent();
    }

    /// docs/spec/reach-only-routes.md §1/§3: an anchor without IDP_VIEW is
    /// refused read, an anchor without IDP_CREATE is refused write, and
    /// IDP_VIEW alone (not the wildcard) is enough to read.
    @Test
    void anchorWithoutIdpPermissionIsRefusedButTheSpecificPermissionSucceeds() {
        var readDenied = http.get("/api/identity-providers", ANCHOR_NO_IDP_PERMS);
        assertThat(readDenied.statusCode()).isEqualTo(403);
        assertThat(json(readDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var writeDenied = http.post("/api/identity-providers", oidcBody(code("api-permdenied"), null, ""), ANCHOR_NO_IDP_PERMS);
        assertThat(writeDenied.statusCode()).isEqualTo(403);
        assertThat(json(writeDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");

        var readAllowed = http.get("/api/identity-providers", ANCHOR_IDP_VIEW_ONLY);
        assertThat(readAllowed.statusCode()).as(readAllowed.body()).isEqualTo(200);
    }

    // ── Secrets without a key (spec §5) ────────────────────────────────────

    @Test
    void withoutAKeyPlaintextSecretsAreRefusedAndAtRestFormsPassThrough() {
        var plain = httpNoKey.post("/api/identity-providers", oidcBody(code("api-nokey-plain"), "\"plain-secret\"", ""), ANCHOR);
        assertThat(plain.statusCode()).isEqualTo(400);
        assertThat(json(plain).get("error").asText()).isEqualTo("ENCRYPTION_NOT_CONFIGURED");
        assertThat(json(plain).get("message").asText()).contains("FLOWCATALYST_APP_KEY");
        assertThat(repo.findByCode(code("api-nokey-plain"))).as("nothing stored").isEmpty();

        var directive = httpNoKey.post("/api/identity-providers", oidcBody(code("api-nokey-dir"), "\"encrypt:plain-secret\"", ""), ANCHOR);
        assertThat(directive.statusCode()).isEqualTo(400);
        assertThat(json(directive).get("error").asText()).isEqualTo("ENCRYPTION_NOT_CONFIGURED");

        String literal = create(httpNoKey, oidcBody(code("api-nokey-lit"), "\"literal:dev-secret\"", ""));
        assertThat(storedSecret(literal)).isEqualTo("literal:dev-secret");
        assertThat(json(httpNoKey.get("/api/identity-providers/" + literal, ANCHOR)).get("hasClientSecret").asBoolean()).isTrue();

        String external = create(httpNoKey, oidcBody(code("api-nokey-ext"), "\"aws-sm://prod/idp/secret\"", ""));
        assertThat(storedSecret(external)).isEqualTo("aws-sm://prod/idp/secret");

        String envelope = "encrypted:" + ENCRYPTION.encrypt("sealed-elsewhere");
        String encrypted = create(httpNoKey, oidcBody(code("api-nokey-enc"), "\"" + envelope + "\"", ""));
        assertThat(storedSecret(encrypted)).isEqualTo(envelope);

        String none = create(httpNoKey, oidcBody(code("api-nokey-none"), "\"\"", ""));
        assertThat(storedSecret(none)).isNull();

        var malformed = httpNoKey.post("/api/identity-providers", oidcBody(code("api-nokey-bad"), "\"encrypted:not*base64\"", ""), ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_SECRET_REF");
    }

    @Test
    void withAKeyAnUnknownSecretManagerSchemeIsItsOwn400NotTheGenericMalformedRef() {
        var typo = http.post("/api/identity-providers", oidcBody(code("api-key-scheme"), "\"aws-smm://prod/idp\"", ""), ANCHOR);
        assertThat(typo.statusCode()).isEqualTo(400);
        assertThat(json(typo).get("error").asText()).isEqualTo("UNSUPPORTED_SECRET_SCHEME");
        // Go's message behind the field name (the one difference kept, J12).
        assertThat(json(typo).get("message").asText())
                .startsWith("oidcClientSecretRef: unsupported secret-manager scheme \"aws-smm://\"; supported: aws-sm://, ");

        var malformed = http.post("/api/identity-providers", oidcBody(code("api-key-bad2"), "\"encrypted:not*base64\"", ""), ANCHOR);
        assertThat(json(malformed).get("error").asText()).as("other malformed refs keep their code")
                .isEqualTo("INVALID_SECRET_REF");
    }

    @Test
    void withAKeyAMalformedEncryptedClaimIsRefusedAndAnEncryptedOneIsKeptVerbatim() {
        var malformed = http.post("/api/identity-providers", oidcBody(code("api-key-bad"), "\"encrypted:not*base64\"", ""), ANCHOR);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("error").asText()).isEqualTo("INVALID_SECRET_REF");

        String envelope = "encrypted:" + ENCRYPTION.encrypt("already-sealed");
        String id = create(http, oidcBody(code("api-key-enc"), "\"" + envelope + "\"", ""));
        assertThat(storedSecret(id)).as("idempotent: not double-wrapped").isEqualTo(envelope);
    }
}
