package io.flowcatalyst.platform.authadmin.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
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

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The eleven `/api/anchor-domains*`, `/api/auth-configs*` and
/// `/api/idp-role-mappings*` routes end to end through Javalin: the
/// authenticator's test headers, the anchor-only gate on every single route
/// including the lists, the lockfile status codes and body shapes, and the
/// error envelope.
@SuppressWarnings("deprecation")
class AuthAdminConfigApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
    /// A CLIENT-scoped principal holding every relevant permission, which must not help (spec §1, §7).
    private static final String[] CLIENT_SCOPED = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "CLIENT",
            Authenticator.TEST_CLIENTS, "clt_x",
            Authenticator.TEST_PERMISSIONS, "platform:iam:anchor-domain:view,platform:iam:anchor-domain:manage,"
                    + "platform:iam:auth-config:view,platform:iam:auth-config:manage,"
                    + "platform:iam:idp-role-mapping:view,platform:iam:idp-role-mapping:manage"};

    /// An anchor holding every permission EXCEPT the anchor-domain / auth-config / IdP families (spec `reach-only-routes.md` §3).
    private static final String[] ANCHOR_NO_FAMILY_PERMS = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:messaging:event-type:view"};
    private static final String[] ANCHOR_DOMAIN_VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:admin:anchor-domain:view"};
    private static final String[] AUTH_CONFIG_VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:auth:client-auth-config:view"};
    private static final String[] IDP_VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
            Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:iam:idp:view"};

    private static final io.flowcatalyst.platform.shared.encryption.Encryption ENCRYPTION =
            io.flowcatalyst.platform.shared.encryption.Encryption.withKey(io.flowcatalyst.platform.shared.encryption.Encryption.generateKey());
    private static final AuthAdminConfigApi.State state = new AuthAdminConfigApi.State(
            new AnchorDomainRepository(TestPg.dataSource()), new ClientAuthConfigRepository(TestPg.dataSource()),
            new IdpRoleMappingRepository(TestPg.dataSource()), new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER)),
            io.flowcatalyst.platform.identityprovider.api.ClientSecretEncryption.of(java.util.Optional.of(ENCRYPTION)),
            io.flowcatalyst.platform.role.RoleCeiling.RolePermissions.from(new io.flowcatalyst.platform.role.RoleRepository(TestPg.dataSource())));
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            AuthAdminConfigApi.register(routes, state);
        });
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

    private static String tok(String tag) {
        return tag + "-" + RUN;
    }

    private static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z";

    private static String createAnchorDomain(String domain) {
        var r = http.post("/api/anchor-domains", "{\"domain\":\"" + domain + "\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = json(r).get("id").asText();
        assertThat(id).startsWith("anc_");
        return id;
    }

    private static String createAuthConfig(String emailDomain) {
        var r = http.post("/api/auth-configs", "{\"emailDomain\":\"" + emailDomain + "\",\"configType\":\"ANCHOR\","
                + "\"authProvider\":\"INTERNAL\",\"oidcMultiTenant\":false}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = json(r).get("id").asText();
        assertThat(id).startsWith("cac_");
        return id;
    }

    private static String createIdpRoleMapping(String idpRoleName) {
        var r = http.post("/api/idp-role-mappings", "{\"idpType\":\"keycloak\",\"idpRoleName\":\"" + idpRoleName + "\",\"platformRoleName\":\"app:role\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = json(r).get("id").asText();
        assertThat(id).startsWith("irm_");
        return id;
    }

    /// The OIDC client secret ref is sealed before the command is built — the
    /// same at-rest policy as the identity-provider API; Go encrypts it in the
    /// handler too. The parity harness (S1-A) found Java storing it verbatim.
    @Test
    void oidcClientSecretRefIsSealedAtRest() {
        var r = http.post("/api/auth-configs", "{\"emailDomain\":\"sealed-" + RUN + ".example.com\",\"configType\":\"ANCHOR\","
                + "\"authProvider\":\"OIDC\",\"oidcIssuerUrl\":\"https://idp.example.com\",\"oidcClientId\":\"cid\","
                + "\"oidcMultiTenant\":false,\"oidcClientSecretRef\":\"plain-secret-" + RUN + "\"}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String id = json(r).get("id").asText();
        String stored = state.authConfigRepo().findById(id).orElseThrow().oidcClientSecretRef();
        assertThat(stored).startsWith("encrypted:");
        assertThat(ENCRYPTION.decrypt(stored)).isInstanceOf(io.flowcatalyst.platform.shared.encryption.Decryption.Plaintext.class);
        assertThat(((io.flowcatalyst.platform.shared.encryption.Decryption.Plaintext) ENCRYPTION.decrypt(stored)).value())
                .isEqualTo("plain-secret-" + RUN);
    }

    // ── Anchor domains ───────────────────────────────────────────────────────

    @Test
    void anchorDomainsCreateListUpdateAndDelete() {
        String id = createAnchorDomain(domain("Api-Anc").toUpperCase(Locale.ROOT));

        var list = http.get("/api/anchor-domains", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = json(list);
        assertThat(body.propertyNames()).containsExactly("items");
        var item = body.get("items");
        assertThat(item).anySatisfy(n -> {
            assertThat(n.get("id").asText()).isEqualTo(id);
            assertThat(n.get("domain").asText()).isEqualTo(domain("api-anc"));
            assertThat(n.get("createdAt").asText()).matches(TS);
            assertThat(n.get("updatedAt").asText()).matches(TS);
            assertThat(n.propertyNames()).containsExactlyInAnyOrder("id", "domain", "createdAt", "updatedAt");
        });

        var put = http.put("/api/anchor-domains/" + id, "{\"domain\":\"" + domain("api-anc-2") + "\"}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(put.body()).isEmpty();
        assertThat(json(http.get("/api/anchor-domains", ANCHOR)).get("items"))
                .anySatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(id))
                .extracting(n -> n.get("domain").asText()).contains(domain("api-anc-2"));

        var badPut = http.put("/api/anchor-domains/" + id, "{\"domain\":\"nodot\"}", ANCHOR);
        assertThat(badPut.statusCode()).isEqualTo(400);
        assertThat(json(badPut).get("error").asText()).isEqualTo("INVALID_DOMAIN");

        var del = http.delete("/api/anchor-domains/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/anchor-domains", ANCHOR)).get("items")).noneSatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(id));

        var againDel = http.delete("/api/anchor-domains/" + id, ANCHOR);
        assertThat(againDel.statusCode()).isEqualTo(404);
        var againPut = http.put("/api/anchor-domains/" + id, "{\"domain\":\"" + domain("api-anc-3") + "\"}", ANCHOR);
        assertThat(againPut.statusCode()).isEqualTo(404);
    }

    @Test
    void anchorDomainCreateValidationAndConflictEnvelopes() {
        // domain is schema-required (request-schema-validation.md) — sent as "" rather than
        // omitted so the request reaches CreateAnchorDomain's own blank check.
        var noDomain = http.post("/api/anchor-domains", "{\"domain\":\"\"}", ANCHOR);
        assertThat(noDomain.statusCode()).isEqualTo(400);
        assertThat(json(noDomain).get("error").asText()).isEqualTo("DOMAIN_REQUIRED");

        var badDomain = http.post("/api/anchor-domains", "{\"domain\":\"nodot\"}", ANCHOR);
        assertThat(json(badDomain).get("error").asText()).isEqualTo("INVALID_DOMAIN");

        createAnchorDomain(domain("api-anc-dup"));
        var dup = http.post("/api/anchor-domains", "{\"domain\":\"" + domain("API-ANC-DUP") + "\"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("DOMAIN_EXISTS");
    }

    // ── Auth configs ─────────────────────────────────────────────────────────

    @Test
    void authConfigsCreateListUpdateAndDelete() {
        String id = createAuthConfig(domain("Api-Cfg").toUpperCase(Locale.ROOT));

        var list = http.get("/api/auth-configs", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        var item = json(list).get("items");
        assertThat(item).anySatisfy(n -> {
            assertThat(n.get("id").asText()).isEqualTo(id);
            assertThat(n.get("emailDomain").asText()).isEqualTo(domain("api-cfg"));
            assertThat(n.get("configType").asText()).isEqualTo("ANCHOR");
            assertThat(n.get("authProvider").asText()).isEqualTo("INTERNAL");
            assertThat(n.get("oidcMultiTenant").asBoolean()).isFalse();
            assertThat(n.get("additionalClientIds").isArray()).isTrue();
            assertThat(n.get("grantedClientIds").isArray()).isTrue();
            assertThat(n.has("primaryClientId")).as("omitted when null").isFalse();
            assertThat(n.has("oidcIssuerUrl")).as("omitted when null").isFalse();
            assertThat(n.get("createdAt").asText()).matches(TS);
        });

        var put = http.put("/api/auth-configs/" + id, "{\"primaryClientId\":\"clt_p\",\"authProvider\":\"OIDC\","
                + "\"oidcIssuerUrl\":\"https://issuer\",\"oidcClientId\":\"client-1\",\"additionalClientIds\":[\"clt_a\"]}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        var updated = json(http.get("/api/auth-configs", ANCHOR)).get("items");
        assertThat(updated).filteredOn(n -> n.get("id").asText().equals(id)).first().satisfies(n -> {
            assertThat(n.get("primaryClientId").asText()).isEqualTo("clt_p");
            assertThat(n.get("authProvider").asText()).isEqualTo("OIDC");
            assertThat(n.get("oidcIssuerUrl").asText()).isEqualTo("https://issuer");
            assertThat(n.get("additionalClientIds")).extracting(JsonNode::asText).containsExactly("clt_a");
            assertThat(n.get("emailDomain").asText()).as("not updatable").isEqualTo(domain("api-cfg"));
            assertThat(n.get("configType").asText()).as("not updatable").isEqualTo("ANCHOR");
        });

        var badProvider = http.put("/api/auth-configs/" + id, "{\"authProvider\":\"SAML\"}", ANCHOR);
        assertThat(badProvider.statusCode()).isEqualTo(400);
        assertThat(json(badProvider).get("error").asText()).isEqualTo("INVALID_AUTH_PROVIDER");

        var del = http.delete("/api/auth-configs/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/auth-configs", ANCHOR)).get("items")).noneSatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(id));
        assertThat(http.delete("/api/auth-configs/" + id, ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void authConfigCreateValidationOrderAndConflictEnvelope() {
        // emailDomain is schema-required too — sent as "" so the request reaches
        // CreateAuthConfig's own validate phase instead of 400 VALIDATION.
        var noDomain = http.post("/api/auth-configs",
                "{\"emailDomain\":\"\",\"configType\":\"ANCHOR\",\"authProvider\":\"INTERNAL\",\"oidcMultiTenant\":false}", ANCHOR);
        assertThat(json(noDomain).get("error").asText()).isEqualTo("INVALID_EMAIL_DOMAIN");

        var badType = http.post("/api/auth-configs", "{\"emailDomain\":\"" + domain("api-cfg-bad1") + "\",\"configType\":\"GLOBAL\","
                + "\"authProvider\":\"INTERNAL\",\"oidcMultiTenant\":false}", ANCHOR);
        assertThat(json(badType).get("error").asText()).isEqualTo("INVALID_CONFIG_TYPE");

        var badProvider = http.post("/api/auth-configs", "{\"emailDomain\":\"" + domain("api-cfg-bad2") + "\",\"configType\":\"ANCHOR\","
                + "\"authProvider\":\"SAML\",\"oidcMultiTenant\":false}", ANCHOR);
        assertThat(json(badProvider).get("error").asText()).isEqualTo("INVALID_AUTH_PROVIDER");

        var noIssuer = http.post("/api/auth-configs", "{\"emailDomain\":\"" + domain("api-cfg-bad3") + "\",\"configType\":\"ANCHOR\","
                + "\"authProvider\":\"OIDC\",\"oidcMultiTenant\":false,\"oidcClientId\":\"c\"}", ANCHOR);
        assertThat(json(noIssuer).get("error").asText()).isEqualTo("OIDC_ISSUER_REQUIRED");

        createAuthConfig(domain("api-cfg-dup"));
        var dup = http.post("/api/auth-configs", "{\"emailDomain\":\"" + domain("API-CFG-DUP") + "\",\"configType\":\"ANCHOR\","
                + "\"authProvider\":\"INTERNAL\",\"oidcMultiTenant\":false}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("DOMAIN_ALREADY_CONFIGURED");
    }

    // ── IdP role mappings ────────────────────────────────────────────────────

    @Test
    void idpRoleMappingsCreateListAndDelete() {
        String id = createIdpRoleMapping(tok("api-role"));

        var list = http.get("/api/idp-role-mappings", ANCHOR);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).get("items")).anySatisfy(n -> {
            assertThat(n.get("id").asText()).isEqualTo(id);
            assertThat(n.get("idpType").asText()).isEqualTo("keycloak");
            assertThat(n.get("idpRoleName").asText()).isEqualTo(tok("api-role"));
            assertThat(n.get("platformRoleName").asText()).isEqualTo("app:role");
            assertThat(n.propertyNames()).containsExactlyInAnyOrder("id", "idpType", "idpRoleName", "platformRoleName", "createdAt", "updatedAt");
        });

        var del = http.delete("/api/idp-role-mappings/" + id, ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(json(http.get("/api/idp-role-mappings", ANCHOR)).get("items")).noneSatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(id));
        assertThat(http.delete("/api/idp-role-mappings/" + id, ANCHOR).statusCode()).isEqualTo(404);
    }

    @Test
    void idpRoleMappingCreateValidationAndConflictEnvelopes() {
        // idpType is schema-required too — sent as "" so the request reaches the domain check.
        var missing = http.post("/api/idp-role-mappings", "{\"idpType\":\"\",\"idpRoleName\":\"x\",\"platformRoleName\":\"app:role\"}", ANCHOR);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asText()).isEqualTo("FIELD_REQUIRED");
        assertThat(json(missing).get("message").asText()).isEqualTo("idpType is required");

        createIdpRoleMapping(tok("api-role-dup"));
        var dup = http.post("/api/idp-role-mappings", "{\"idpType\":\"entra\",\"idpRoleName\":\"" + tok("api-role-dup") + "\",\"platformRoleName\":\"app:other\"}", ANCHOR);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(json(dup).get("error").asText()).isEqualTo("MAPPING_EXISTS");
    }

    // ── Gates ──────────────────────────────────────────────────────────────

    @Test
    void everyRouteIsAnchorOnlyIncludingTheLists() {
        String anchorDomainId = createAnchorDomain(domain("api-gate-anc"));
        String authConfigId = createAuthConfig(domain("api-gate-cfg"));
        String mappingId = createIdpRoleMapping(tok("api-gate-role"));

        for (var r : List.of(
                http.get("/api/anchor-domains", CLIENT_SCOPED),
                http.post("/api/anchor-domains", "{\"domain\":\"" + domain("api-gate-anc2") + "\"}", CLIENT_SCOPED),
                http.put("/api/anchor-domains/" + anchorDomainId, "{\"domain\":\"" + domain("api-gate-anc3") + "\"}", CLIENT_SCOPED),
                http.delete("/api/anchor-domains/" + anchorDomainId, CLIENT_SCOPED),
                http.get("/api/auth-configs", CLIENT_SCOPED),
                http.post("/api/auth-configs", "{\"emailDomain\":\"" + domain("api-gate-cfg2") + "\",\"configType\":\"ANCHOR\",\"authProvider\":\"INTERNAL\",\"oidcMultiTenant\":false}", CLIENT_SCOPED),
                http.put("/api/auth-configs/" + authConfigId, "{}", CLIENT_SCOPED),
                http.delete("/api/auth-configs/" + authConfigId, CLIENT_SCOPED),
                http.get("/api/idp-role-mappings", CLIENT_SCOPED),
                http.post("/api/idp-role-mappings", "{\"idpType\":\"keycloak\",\"idpRoleName\":\"" + tok("api-gate-role2") + "\",\"platformRoleName\":\"app:role\"}", CLIENT_SCOPED),
                http.delete("/api/idp-role-mappings/" + mappingId, CLIENT_SCOPED))) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
            assertThat(r.body()).isEqualTo("{\"error\":\"ANCHOR_REQUIRED\",\"message\":\"anchor scope required\"}\n");
        }

        var anonymous = http.get("/api/anchor-domains");
        assertThat(anonymous.statusCode()).isEqualTo(403);
        assertThat(json(anonymous).get("error").asText()).isEqualTo("UNAUTHENTICATED");

        // Nothing the CLIENT_SCOPED principal attempted actually took effect.
        assertThat(json(http.get("/api/anchor-domains", ANCHOR)).get("items")).anySatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(anchorDomainId));
        assertThat(json(http.get("/api/auth-configs", ANCHOR)).get("items")).anySatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(authConfigId));
        assertThat(json(http.get("/api/idp-role-mappings", ANCHOR)).get("items")).anySatisfy(n -> assertThat(n.get("id").asText()).isEqualTo(mappingId));
    }

    /// docs/spec/reach-only-routes.md §1/§3: an anchor without the family's
    /// permission is refused read and write for each of the three families,
    /// and the family's specific view code (not the wildcard) is enough to read.
    @Test
    void anchorWithoutTheFamilyPermissionIsRefusedButTheSpecificPermissionSucceeds() {
        // Anchor domains.
        var adReadDenied = http.get("/api/anchor-domains", ANCHOR_NO_FAMILY_PERMS);
        assertThat(adReadDenied.statusCode()).isEqualTo(403);
        assertThat(json(adReadDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        var adWriteDenied = http.post("/api/anchor-domains", "{\"domain\":\"" + domain("api-permdenied-anc") + "\"}", ANCHOR_NO_FAMILY_PERMS);
        assertThat(adWriteDenied.statusCode()).isEqualTo(403);
        assertThat(json(adWriteDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(http.get("/api/anchor-domains", ANCHOR_DOMAIN_VIEW_ONLY).statusCode()).isEqualTo(200);

        // Auth configs.
        var acReadDenied = http.get("/api/auth-configs", ANCHOR_NO_FAMILY_PERMS);
        assertThat(acReadDenied.statusCode()).isEqualTo(403);
        assertThat(json(acReadDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        var acWriteDenied = http.post("/api/auth-configs", "{\"emailDomain\":\"" + domain("api-permdenied-cfg")
                + "\",\"configType\":\"ANCHOR\",\"authProvider\":\"INTERNAL\",\"oidcMultiTenant\":false}", ANCHOR_NO_FAMILY_PERMS);
        assertThat(acWriteDenied.statusCode()).isEqualTo(403);
        assertThat(json(acWriteDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(http.get("/api/auth-configs", AUTH_CONFIG_VIEW_ONLY).statusCode()).isEqualTo(200);

        // IdP role mappings.
        var irmReadDenied = http.get("/api/idp-role-mappings", ANCHOR_NO_FAMILY_PERMS);
        assertThat(irmReadDenied.statusCode()).isEqualTo(403);
        assertThat(json(irmReadDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        var irmWriteDenied = http.post("/api/idp-role-mappings",
                "{\"idpType\":\"keycloak\",\"idpRoleName\":\"" + tok("api-permdenied-role") + "\",\"platformRoleName\":\"app:role\"}", ANCHOR_NO_FAMILY_PERMS);
        assertThat(irmWriteDenied.statusCode()).isEqualTo(403);
        assertThat(json(irmWriteDenied).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        assertThat(http.get("/api/idp-role-mappings", IDP_VIEW_ONLY).statusCode()).isEqualTo(200);
    }

    /// Owner ruling 2026-09-25: a mapping confers its platform role on every login carrying
    /// the IdP role, so only a caller holding that role's permissions may create or delete one.
    @Test
    void idpRoleMappingsAreBoundedByTheCallersPermissions() {
        var wide = io.flowcatalyst.platform.role.Role.create("platform", "ceil-idp-" + java.util.UUID.randomUUID().toString().substring(0, 8), "C")
                .withPermissions(java.util.List.of("platform:iam:role:update"));
        state.uow().inTransaction(tx -> {
            new io.flowcatalyst.platform.role.RoleRepository(TestPg.dataSource()).persist(wide, tx.dbTx());
            return null;
        });
        String[] idpAdmin = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:iam:idp:update,platform:iam:idp:view"};
        var refused = http.post("/api/idp-role-mappings",
                "{\"idpType\":\"keycloak\",\"idpRoleName\":\"ceil-" + java.util.UUID.randomUUID() + "\",\"platformRoleName\":\"" + wide.name() + "\"}",
                idpAdmin);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused).get("error").asText()).isEqualTo("ROLE_ABOVE_CALLER");

        var created = http.post("/api/idp-role-mappings",
                "{\"idpType\":\"keycloak\",\"idpRoleName\":\"ceil-" + java.util.UUID.randomUUID() + "\",\"platformRoleName\":\"" + wide.name() + "\"}",
                ANCHOR);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(http.delete("/api/idp-role-mappings/" + json(created).get("id").asText(), idpAdmin).statusCode())
                .as("removal counts").isEqualTo(403);
    }
}
