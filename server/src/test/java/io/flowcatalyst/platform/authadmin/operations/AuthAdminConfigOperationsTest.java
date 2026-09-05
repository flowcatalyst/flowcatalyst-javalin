package io.flowcatalyst.platform.authadmin.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.authadmin.AnchorDomain;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.AuthProvider;
import io.flowcatalyst.platform.authadmin.ClientAuthConfig;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.ConfigType;
import io.flowcatalyst.platform.authadmin.CorruptClientAuthConfigException;
import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AnchorDomainCreated;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AuthConfigCreated;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.IdpRoleMappingCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static io.flowcatalyst.db.generated.Tables.TNT_CLIENT_AUTH_CONFIGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The three aggregates' use cases against the embedded Postgres (spec
/// §4–8): validation, normalisation, the envelope's guarantee that an
/// aggregate write lands together with its `msg_events` and `aud_logs`
/// rows, the 409s, the 404s, and the strict `config_type` / `auth_provider`
/// reads (X-06). The pure rules are covered by `AuthAdminConfigTest`; here
/// each operation is exercised once through the envelope.
///
/// The fixture never truncates (and the seeder owns rows), so every test
/// owns its rows: domains and role names are namespaced by a per-JVM suffix.
@SuppressWarnings("deprecation")
class AuthAdminConfigOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final AnchorDomainRepository anchorDomainRepo = new AnchorDomainRepository(DS);
    private static final ClientAuthConfigRepository authConfigRepo = new ClientAuthConfigRepository(DS);
    private static final IdpRoleMappingRepository idpRoleMappingRepo = new IdpRoleMappingRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}-{RUN}.example.com` — a valid, namespaced domain.
    private static String domain(String tag) {
        return tag + "-" + RUN + ".example.com";
    }

    /// `{tag}-{RUN}` — a namespaced, unique-enough token for role names.
    private static String tok(String tag) {
        return tag + "-" + RUN;
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Result<Record> eventsFor(String subject, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                subject, type);
    }

    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    // ── Anchor domains ───────────────────────────────────────────────────────

    @Test
    void createAnchorDomainWritesTheRowTheEventAndTheAuditTogether() {
        var cmd = new CreateAnchorDomainCommand("  " + domain("Anc-Create").toUpperCase(Locale.ROOT) + "  ");
        AnchorDomainCreated ev = runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), cmd);

        assertThat(ev.anchorDomainId()).startsWith("anc_");
        assertThat(ev.domain()).as("normalised").isEqualTo(domain("anc-create"));
        assertThat(ev.eventType()).isEqualTo(AuthAdminEvents.ANCHOR_DOMAIN_CREATED);
        assertThat(ev.source()).isEqualTo(AuthAdminEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(AuthAdminEvents.anchorDomainSubject(ev.anchorDomainId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:anchordomain:" + ev.anchorDomainId());

        var got = anchorDomainRepo.findById(ev.anchorDomainId()).orElseThrow();
        assertThat(got.domain()).isEqualTo(domain("anc-create"));

        var events = eventsFor(ev.subject(), AuthAdminEvents.ANCHOR_DOMAIN_CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:anchordomain:" + ev.anchorDomainId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("anchorDomainId").asText()).isEqualTo(ev.anchorDomainId());
        assertThat(data.get("domain").asText()).isEqualTo(domain("anc-create"));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("anchorDomainId", "domain");

        var audits = auditsFor(ev.anchorDomainId(), "CreateAnchorDomainCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Anchordomain");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("domain").asText()).contains("ANC-CREATE");
    }

    @Test
    void createAnchorDomainRejectsBlankAsRequiredAndMalformedAsInvalid() {
        assertUseCaseError(() -> runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand("")),
                UseCaseError.Validation.class, "DOMAIN_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(null)),
                UseCaseError.Validation.class, "DOMAIN_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand("nodot")),
                UseCaseError.Validation.class, "INVALID_DOMAIN");
    }

    @Test
    void createAnchorDomainRejectsADuplicateDomainCaseInsensitively() {
        runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-dup")));
        assertUseCaseError(() -> runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-dup").toUpperCase(Locale.ROOT))),
                UseCaseError.Conflict.class, "DOMAIN_EXISTS");
    }

    @Test
    void updateAnchorDomainReplacesTheDomainAndFoldsBlankIntoInvalidDomain() {
        var seeded = runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-upd")));

        var ev = runAsAnchor(UpdateAnchorDomain.of(anchorDomainRepo), new UpdateAnchorDomainCommand(seeded.anchorDomainId(), domain("anc-upd-2")));
        assertThat(ev.anchorDomainId()).isEqualTo(seeded.anchorDomainId());
        assertThat(ev.domain()).isEqualTo(domain("anc-upd-2"));
        assertThat(anchorDomainRepo.findById(seeded.anchorDomainId()).orElseThrow().domain()).isEqualTo(domain("anc-upd-2"));

        assertUseCaseError(() -> runAsAnchor(UpdateAnchorDomain.of(anchorDomainRepo), new UpdateAnchorDomainCommand(seeded.anchorDomainId(), "")),
                UseCaseError.Validation.class, "INVALID_DOMAIN");
        assertUseCaseError(() -> runAsAnchor(UpdateAnchorDomain.of(anchorDomainRepo), new UpdateAnchorDomainCommand("anc_doesnotexist1", domain("anc-upd-3"))),
                UseCaseError.NotFound.class, "AnchorDomain_NOT_FOUND");

        var audits = auditsFor(seeded.anchorDomainId(), "UpdateAnchorDomainCommand");
        assertThat(audits).hasSize(1);
    }

    /// Java pre-checks the collision Go leaves to the UNIQUE violation (spec §8 D2).
    @Test
    void updateAnchorDomainRejectsACollisionWithAnotherRowsDomain() {
        var a = runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-collide-a")));
        var b = runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-collide-b")));
        assertUseCaseError(() -> runAsAnchor(UpdateAnchorDomain.of(anchorDomainRepo), new UpdateAnchorDomainCommand(b.anchorDomainId(), domain("anc-collide-a"))),
                UseCaseError.Conflict.class, "DOMAIN_EXISTS");
        assertThat(anchorDomainRepo.findById(b.anchorDomainId()).orElseThrow().domain()).as("rejected update wrote nothing").isEqualTo(domain("anc-collide-b"));
        // Updating a row to its own current domain is not a collision with "another" row.
        var same = runAsAnchor(UpdateAnchorDomain.of(anchorDomainRepo), new UpdateAnchorDomainCommand(a.anchorDomainId(), domain("anc-collide-a")));
        assertThat(same.domain()).isEqualTo(domain("anc-collide-a"));
    }

    @Test
    void deleteAnchorDomainRemovesTheRowAndRejectsAnUnknownId() {
        var seeded = runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-del")));
        var ev = runAsAnchor(DeleteAnchorDomain.of(anchorDomainRepo), new DeleteAnchorDomainCommand(seeded.anchorDomainId()));
        assertThat(ev.anchorDomainId()).isEqualTo(seeded.anchorDomainId());
        assertThat(anchorDomainRepo.findById(seeded.anchorDomainId())).isEmpty();
        assertThat(eventsFor(ev.subject(), AuthAdminEvents.ANCHOR_DOMAIN_DELETED)).hasSize(1);

        assertUseCaseError(() -> runAsAnchor(DeleteAnchorDomain.of(anchorDomainRepo), new DeleteAnchorDomainCommand(seeded.anchorDomainId())),
                UseCaseError.NotFound.class, "AnchorDomain_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(DeleteAnchorDomain.of(anchorDomainRepo), new DeleteAnchorDomainCommand("")),
                UseCaseError.Validation.class, "ID_REQUIRED");
    }

    // ── Auth configs ─────────────────────────────────────────────────────────

    private static CreateAuthConfigCommand anchorScopedInternal(String emailDomain) {
        return new CreateAuthConfigCommand(emailDomain, "ANCHOR", null, null, null, "INTERNAL", null, null, false, null, null);
    }

    @Test
    void createAuthConfigWritesTheRowTheEventAndTheAuditTogether() {
        var cmd = new CreateAuthConfigCommand("  " + domain("Cfg-Create").toUpperCase(Locale.ROOT) + "  ", "CLIENT", "clt_primary",
                List.of("clt_add1", "clt_add2"), List.of("clt_grant1"), "OIDC", "https://issuer", "oidc-client", true, "https://*.issuer", "ref-1");
        AuthConfigCreated ev = runAsAnchor(CreateAuthConfig.of(authConfigRepo), cmd);

        assertThat(ev.authConfigId()).startsWith("cac_");
        assertThat(ev.emailDomain()).isEqualTo(domain("cfg-create"));
        assertThat(ev.eventType()).isEqualTo(AuthAdminEvents.AUTH_CONFIG_CREATED);
        assertThat(ev.messageGroup()).isEqualTo("platform:authconfig:" + ev.authConfigId());

        var got = authConfigRepo.findById(ev.authConfigId()).orElseThrow();
        assertThat(got.emailDomain()).isEqualTo(domain("cfg-create"));
        assertThat(got.configType()).isEqualTo(ConfigType.CLIENT);
        assertThat(got.primaryClientId()).isEqualTo("clt_primary");
        assertThat(got.additionalClientIds()).containsExactly("clt_add1", "clt_add2");
        assertThat(got.grantedClientIds()).containsExactly("clt_grant1");
        assertThat(got.authProvider()).isEqualTo(AuthProvider.OIDC);
        assertThat(got.oidcIssuerUrl()).isEqualTo("https://issuer");
        assertThat(got.oidcClientId()).isEqualTo("oidc-client");
        assertThat(got.oidcMultiTenant()).isTrue();
        assertThat(got.oidcIssuerPattern()).isEqualTo("https://*.issuer");
        assertThat(got.oidcClientSecretRef()).isEqualTo("ref-1");

        var events = eventsFor(ev.subject(), AuthAdminEvents.AUTH_CONFIG_CREATED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("authConfigId").asText()).isEqualTo(ev.authConfigId());
        assertThat(data.get("emailDomain").asText()).isEqualTo(domain("cfg-create"));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("authConfigId", "emailDomain");

        var audits = auditsFor(ev.authConfigId(), "CreateAuthConfigCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Authconfig");
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("emailDomain").asText()).contains("CFG-CREATE");
    }

    static Stream<Arguments> malformedCreateAuthConfigCommands() {
        return Stream.of(
                Arguments.of("blank domain", new CreateAuthConfigCommand("", "ANCHOR", null, null, null, "INTERNAL", null, null, false, null, null), "INVALID_EMAIL_DOMAIN"),
                Arguments.of("no dot", new CreateAuthConfigCommand("cfgnodot", "ANCHOR", null, null, null, "INTERNAL", null, null, false, null, null), "INVALID_EMAIL_DOMAIN"),
                Arguments.of("bad config type", new CreateAuthConfigCommand("cfg-badtype.example.com", "GLOBAL", null, null, null, "INTERNAL", null, null, false, null, null), "INVALID_CONFIG_TYPE"),
                Arguments.of("bad auth provider", new CreateAuthConfigCommand("cfg-badprovider.example.com", "ANCHOR", null, null, null, "SAML", null, null, false, null, null), "INVALID_AUTH_PROVIDER"),
                Arguments.of("oidc without issuer", new CreateAuthConfigCommand("cfg-noissuer.example.com", "ANCHOR", null, null, null, "OIDC", null, "client", false, null, null), "OIDC_ISSUER_REQUIRED"),
                Arguments.of("oidc without client id", new CreateAuthConfigCommand("cfg-noclient.example.com", "ANCHOR", null, null, null, "OIDC", "https://issuer", null, false, null, null), "OIDC_CLIENT_ID_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateAuthConfigCommands")
    void createAuthConfigRejectsAMalformedCommandInSpecOrder(String label, CreateAuthConfigCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateAuthConfig.of(authConfigRepo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createAuthConfigRejectsADuplicateDomain() {
        runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-dup")));
        assertUseCaseError(() -> runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-dup").toUpperCase(Locale.ROOT))),
                UseCaseError.Conflict.class, "DOMAIN_ALREADY_CONFIGURED");
    }

    @Test
    void updateAuthConfigAppliesOnlySuppliedFieldsAndNeverTouchesDomainOrConfigType() {
        var seeded = runAsAnchor(CreateAuthConfig.of(authConfigRepo), new CreateAuthConfigCommand(domain("cfg-upd"), "CLIENT", null,
                null, null, "INTERNAL", null, null, false, null, null));

        var ev = runAsAnchor(UpdateAuthConfig.of(authConfigRepo), new UpdateAuthConfigCommand(seeded.authConfigId(), "clt_p",
                List.of("clt_a"), null, "OIDC", "https://issuer", "client-1", true, "pattern", "ref-1"));
        assertThat(ev.authConfigId()).isEqualTo(seeded.authConfigId());
        assertThat(ev.emailDomain()).isEqualTo(domain("cfg-upd"));

        var got = authConfigRepo.findById(seeded.authConfigId()).orElseThrow();
        assertThat(got.emailDomain()).as("not updatable").isEqualTo(domain("cfg-upd"));
        assertThat(got.configType()).as("not updatable").isEqualTo(ConfigType.CLIENT);
        assertThat(got.primaryClientId()).isEqualTo("clt_p");
        assertThat(got.additionalClientIds()).containsExactly("clt_a");
        assertThat(got.grantedClientIds()).as("absent list = unchanged").isEmpty();
        assertThat(got.authProvider()).isEqualTo(AuthProvider.OIDC);
        assertThat(got.oidcIssuerUrl()).isEqualTo("https://issuer");
        assertThat(got.oidcMultiTenant()).isTrue();

        // A second update with everything absent changes nothing.
        runAsAnchor(UpdateAuthConfig.of(authConfigRepo), new UpdateAuthConfigCommand(seeded.authConfigId(), null, null, null, null, null, null, null, null, null));
        var again = authConfigRepo.findById(seeded.authConfigId()).orElseThrow();
        assertThat(again.primaryClientId()).as("absent = untouched").isEqualTo("clt_p");
        assertThat(again.additionalClientIds()).containsExactly("clt_a");
        assertThat(again.oidcIssuerUrl()).isEqualTo("https://issuer");

        // Empty lists clear.
        runAsAnchor(UpdateAuthConfig.of(authConfigRepo), new UpdateAuthConfigCommand(seeded.authConfigId(), null, List.of(), List.of(), null, null, null, null, null, null));
        assertThat(authConfigRepo.findById(seeded.authConfigId()).orElseThrow().additionalClientIds()).isEmpty();

        assertThat(eventsFor(ev.subject(), AuthAdminEvents.AUTH_CONFIG_UPDATED)).hasSize(3);
        assertThat(auditsFor(seeded.authConfigId(), "UpdateAuthConfigCommand")).hasSize(3);
    }

    @Test
    void updateAuthConfigNeverAppliesAnOidcCompletenessCheck() {
        var seeded = runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-upd-oidc")));
        runAsAnchor(UpdateAuthConfig.of(authConfigRepo), new UpdateAuthConfigCommand(seeded.authConfigId(), null, null, null, "OIDC", null, null, null, null, null));
        var got = authConfigRepo.findById(seeded.authConfigId()).orElseThrow();
        assertThat(got.authProvider()).isEqualTo(AuthProvider.OIDC);
        assertThat(got.oidcIssuerUrl()).as("spec §8 D3 — kept as Go").isNull();
    }

    @Test
    void updateAuthConfigRejectsABadIdOrAuthProvider() {
        var seeded = runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-upd-bad")));
        assertUseCaseError(() -> runAsAnchor(UpdateAuthConfig.of(authConfigRepo), new UpdateAuthConfigCommand(null, null, null, null, null, null, null, null, null, null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateAuthConfig.of(authConfigRepo), new UpdateAuthConfigCommand("cac_doesnotexist1", null, null, null, null, null, null, null, null, null)),
                UseCaseError.NotFound.class, "AuthConfig_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(UpdateAuthConfig.of(authConfigRepo), new UpdateAuthConfigCommand(seeded.authConfigId(), null, null, null, "SAML", null, null, null, null, null)),
                UseCaseError.Validation.class, "INVALID_AUTH_PROVIDER");
    }

    @Test
    void deleteAuthConfigRemovesTheRowAndRejectsAnUnknownId() {
        var seeded = runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-del")));
        var ev = runAsAnchor(DeleteAuthConfig.of(authConfigRepo), new DeleteAuthConfigCommand(seeded.authConfigId()));
        assertThat(authConfigRepo.findById(seeded.authConfigId())).isEmpty();
        assertThat(eventsFor(ev.subject(), AuthAdminEvents.AUTH_CONFIG_DELETED)).hasSize(1);
        assertUseCaseError(() -> runAsAnchor(DeleteAuthConfig.of(authConfigRepo), new DeleteAuthConfigCommand(seeded.authConfigId())),
                UseCaseError.NotFound.class, "AuthConfig_NOT_FOUND");
    }

    // ── Strict config_type / auth_provider reads (spec §2, X-06) ────────────

    @Test
    void findByIdRejectsAnUnrecognisedConfigTypeInsteadOfDefaultingSilently() {
        var seeded = runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-corrupt-single")));
        TestPg.withConstraintDropped(DS, "tnt_client_auth_configs", "chk_tnt_client_auth_configs_config_type", () -> {
            DB.update(TNT_CLIENT_AUTH_CONFIGS).set(TNT_CLIENT_AUTH_CONFIGS.CONFIG_TYPE, "BOGUS_TYPE")
                    .where(TNT_CLIENT_AUTH_CONFIGS.ID.eq(seeded.authConfigId())).execute();
            try {
                assertThatThrownBy(() -> authConfigRepo.findById(seeded.authConfigId()))
                        .isInstanceOf(CorruptClientAuthConfigException.class)
                        .satisfies(e -> assertThat(((CorruptClientAuthConfigException) e).clientAuthConfigId()).isEqualTo(seeded.authConfigId()));
            } finally {
                DB.update(TNT_CLIENT_AUTH_CONFIGS).set(TNT_CLIENT_AUTH_CONFIGS.CONFIG_TYPE, "ANCHOR")
                        .where(TNT_CLIENT_AUTH_CONFIGS.ID.eq(seeded.authConfigId())).execute();
            }
        });
        assertThat(authConfigRepo.findById(seeded.authConfigId())).as("restored row reads again").isPresent();
    }

    @Test
    void aCorruptAuthProviderFailsTheWholeListReadNotJustThatRow() {
        var good = runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-corrupt-list-good")));
        var corrupt = runAsAnchor(CreateAuthConfig.of(authConfigRepo), anchorScopedInternal(domain("cfg-corrupt-list-bad")));
        TestPg.withConstraintDropped(DS, "tnt_client_auth_configs", "chk_tnt_client_auth_configs_auth_provider", () -> {
            DB.update(TNT_CLIENT_AUTH_CONFIGS).set(TNT_CLIENT_AUTH_CONFIGS.AUTH_PROVIDER, "SAML")
                    .where(TNT_CLIENT_AUTH_CONFIGS.ID.eq(corrupt.authConfigId())).execute();
            try {
                assertThatThrownBy(authConfigRepo::findAll).isInstanceOf(CorruptClientAuthConfigException.class);
            } finally {
                DB.update(TNT_CLIENT_AUTH_CONFIGS).set(TNT_CLIENT_AUTH_CONFIGS.AUTH_PROVIDER, "INTERNAL")
                        .where(TNT_CLIENT_AUTH_CONFIGS.ID.eq(corrupt.authConfigId())).execute();
            }
        });
        assertThat(authConfigRepo.findAll()).as("list reads again once repaired").extracting(ClientAuthConfig::id)
                .contains(good.authConfigId(), corrupt.authConfigId());
    }

    // ── IdP role mappings ────────────────────────────────────────────────────

    @Test
    void createIdpRoleMappingWritesTheRowTheEventAndTheAuditTogether() {
        var cmd = new CreateIdpRoleMappingCommand("keycloak", tok("role-create"), "app:role-create");
        IdpRoleMappingCreated ev = runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), cmd);

        assertThat(ev.mappingId()).startsWith("irm_");
        assertThat(ev.idpType()).isEqualTo("keycloak");
        assertThat(ev.idpRoleName()).isEqualTo(tok("role-create"));
        assertThat(ev.platformRoleName()).isEqualTo("app:role-create");
        assertThat(ev.eventType()).isEqualTo(AuthAdminEvents.IDP_ROLE_MAPPING_CREATED);
        assertThat(ev.messageGroup()).isEqualTo("platform:idprolemapping:" + ev.mappingId());

        var got = idpRoleMappingRepo.findById(ev.mappingId()).orElseThrow();
        assertThat(got.idpType()).isEqualTo("keycloak");
        assertThat(got.idpRoleName()).isEqualTo(tok("role-create"));
        assertThat(got.platformRoleName()).isEqualTo("app:role-create");

        var events = eventsFor(ev.subject(), AuthAdminEvents.IDP_ROLE_MAPPING_CREATED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("mappingId").asText()).isEqualTo(ev.mappingId());
        assertThat(data.get("idpType").asText()).isEqualTo("keycloak");
        assertThat(data.get("idpRoleName").asText()).isEqualTo(tok("role-create"));
        assertThat(data.get("platformRoleName").asText()).isEqualTo("app:role-create");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("mappingId", "idpType", "idpRoleName", "platformRoleName");

        var audits = auditsFor(ev.mappingId(), "CreateIdpRoleMappingCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Idprolemapping");
    }

    static Stream<Arguments> malformedCreateIdpRoleMappingCommands() {
        return Stream.of(
                Arguments.of("missing idpType first", new CreateIdpRoleMappingCommand(null, tok("x"), "app:role"), "idpType is required"),
                Arguments.of("blank idpType first", new CreateIdpRoleMappingCommand(" ", tok("x"), "app:role"), "idpType is required"),
                Arguments.of("missing idpRoleName second", new CreateIdpRoleMappingCommand("keycloak", "", "app:role"), "idpRoleName is required"),
                Arguments.of("missing platformRoleName third", new CreateIdpRoleMappingCommand("keycloak", tok("y"), ""), "platformRoleName is required"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateIdpRoleMappingCommands")
    void createIdpRoleMappingRejectsTheFirstMissingFieldInOrder(String label, CreateIdpRoleMappingCommand cmd, String expectedMessage) {
        assertUseCaseError(() -> runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), cmd), UseCaseError.Validation.class, "FIELD_REQUIRED");
        assertThatThrownBy(() -> runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), cmd)).hasMessageContaining(expectedMessage);
    }

    @Test
    void createIdpRoleMappingRejectsADuplicateIdpRoleName() {
        runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), new CreateIdpRoleMappingCommand("keycloak", tok("dup"), "app:role"));
        assertUseCaseError(() -> runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), new CreateIdpRoleMappingCommand("entra", tok("dup"), "app:other")),
                UseCaseError.Conflict.class, "MAPPING_EXISTS");
    }

    @Test
    void deleteIdpRoleMappingRemovesTheRowAndRejectsAnUnknownId() {
        var seeded = runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), new CreateIdpRoleMappingCommand("keycloak", tok("del"), "app:role"));
        var ev = runAsAnchor(DeleteIdpRoleMapping.of(idpRoleMappingRepo), new DeleteIdpRoleMappingCommand(seeded.mappingId()));
        assertThat(idpRoleMappingRepo.findById(seeded.mappingId())).isEmpty();
        assertThat(eventsFor(ev.subject(), AuthAdminEvents.IDP_ROLE_MAPPING_DELETED)).hasSize(1);
        var deletedData = json(eventsFor(ev.subject(), AuthAdminEvents.IDP_ROLE_MAPPING_DELETED).getFirst().get("data", String.class));
        assertThat(deletedData.propertyNames()).as("deleted carries the same shape as created (spec §5)")
                .containsExactlyInAnyOrder("mappingId", "idpType", "idpRoleName", "platformRoleName");

        assertUseCaseError(() -> runAsAnchor(DeleteIdpRoleMapping.of(idpRoleMappingRepo), new DeleteIdpRoleMappingCommand(seeded.mappingId())),
                UseCaseError.NotFound.class, "IdpRoleMapping_NOT_FOUND");
    }

    // ── Repository reads (anchor domains + mappings, list ordering) ─────────

    @Test
    void findAllReturnsRowsInDomainOrder() {
        var b = runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-order-b")));
        var a = runAsAnchor(CreateAnchorDomain.of(anchorDomainRepo), new CreateAnchorDomainCommand(domain("anc-order-a")));
        assertThat(anchorDomainRepo.findAll()).extracting(AnchorDomain::id).containsSubsequence(a.anchorDomainId(), b.anchorDomainId());
    }

    @Test
    void idpRoleMappingFindAllReturnsRowsInIdpRoleNameOrder() {
        var b = runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), new CreateIdpRoleMappingCommand("keycloak", tok("order-b"), "app:role"));
        var a = runAsAnchor(CreateIdpRoleMapping.of(idpRoleMappingRepo), new CreateIdpRoleMappingCommand("keycloak", tok("order-a"), "app:role"));
        assertThat(idpRoleMappingRepo.findAll()).extracting(IdpRoleMapping::id).containsSubsequence(a.mappingId(), b.mappingId());
    }
}
