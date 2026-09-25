package io.flowcatalyst.platform.emaildomainmapping.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents.EmailDomainMappingCreated;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_ADDITIONAL_CLIENTS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_GRANTED_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The email-domain-mapping use cases against the embedded Postgres
/// (spec §2, §4–8): validation, normalisation, junction persistence, the
/// move's direction-aware side effects, and the envelope's guarantee that
/// an aggregate write lands together with its `msg_events` and `aud_logs`
/// rows. The pure rules are covered by `EmailDomainMappingTest`; here each
/// operation is exercised once through the envelope.
///
/// The fixture never truncates (and the seeder owns a row), so every test
/// owns its rows: domains are namespaced by a per-JVM suffix.
@SuppressWarnings("deprecation")
class EmailDomainMappingOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final EmailDomainMappingRepository repo = new EmailDomainMappingRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    /// The seeded-by-string provider id every test's mappings start on; never a real row (spec §1, open question 2).
    private static final String IDP = "idp_edmtestseed1";

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    /// `{tag}-{RUN}.example.com` — a valid, namespaced domain.
    private static String domain(String tag) {
        return tag + "-" + RUN + ".example.com";
    }

    private static CreateCommand anchorCreate(String emailDomain) {
        return new CreateCommand(emailDomain, IDP, "ANCHOR", null, null, null, null, false, null, false, null);
    }

    private static EmailDomainMappingCreated created(String emailDomain) {
        return runAsAnchor(CreateEmailDomainMapping.of(repo), anchorCreate(emailDomain));
    }

    private static EmailDomainMapping reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("mapping " + id + " not found"));
    }

    /// An identity-provider row of `type`; returns its id.
    private static String identityProvider(String type) {
        String id = EntityType.IDENTITY_PROVIDER.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(OAUTH_IDENTITY_PROVIDERS).set(OAUTH_IDENTITY_PROVIDERS.ID, id)
                .set(OAUTH_IDENTITY_PROVIDERS.CODE, "edm-" + RUN + "-" + id.toLowerCase(Locale.ROOT))
                .set(OAUTH_IDENTITY_PROVIDERS.NAME, "IdP " + id).set(OAUTH_IDENTITY_PROVIDERS.TYPE, type)
                .set(OAUTH_IDENTITY_PROVIDERS.CREATED_AT, now).set(OAUTH_IDENTITY_PROVIDERS.UPDATED_AT, now).execute();
        return id;
    }

    /// A `USER` principal on `emailDomain` with the given `idp_type` and one role per source; returns its id.
    private static String user(String emailDomain, String idpType, String externalId) {
        String id = EntityType.PRINCIPAL.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.NAME, "user " + id).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, id.toLowerCase(Locale.ROOT) + "@" + emailDomain).set(IAM_PRINCIPALS.EMAIL_DOMAIN, emailDomain)
                .set(IAM_PRINCIPALS.IDP_TYPE, idpType).set(IAM_PRINCIPALS.EXTERNAL_IDP_ID, externalId)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now).execute();
        DB.insertInto(IAM_PRINCIPAL_ROLES).set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, id).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, "synced-" + RUN)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "IDP_SYNC").set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, now).execute();
        DB.insertInto(IAM_PRINCIPAL_ROLES).set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, id).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, "manual-" + RUN)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "ADMIN").set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, now).execute();
        return id;
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

    private static Result<Record> eventsFor(String mappingId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                EmailDomainMappingEvents.subjectFor(mappingId), type);
    }

    private static Result<Record> auditsFor(String mappingId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                mappingId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheJunctionsTheEventAndTheAuditTogether() {
        var cmd = new CreateCommand("  " + domain("EDM-Create").toUpperCase(Locale.ROOT) + "  ", IDP, "CLIENT", "clt_primary",
                List.of("clt_add1", "clt_add2"), List.of("clt_grant1"), "tenant-1", true, List.of("TOTP", "EMAIL_PIN"), true, 14);
        var ev = runAsAnchor(CreateEmailDomainMapping.of(repo), cmd);

        assertThat(ev.mappingId()).startsWith("edm_");
        assertThat(ev.emailDomain()).as("domain is trimmed and lower-cased").isEqualTo(domain("edm-create"));
        assertThat(ev.eventType()).isEqualTo(EmailDomainMappingEvents.CREATED);
        assertThat(ev.source()).isEqualTo(EmailDomainMappingEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(EmailDomainMappingEvents.subjectFor(ev.mappingId()));
        assertThat(ev.messageGroup()).isEqualTo("platform:emaildomainmapping:" + ev.mappingId());

        var got = reload(ev.mappingId());
        assertThat(got.emailDomain()).isEqualTo(domain("edm-create"));
        assertThat(got.identityProviderId()).isEqualTo(IDP);
        assertThat(got.scopeType()).isEqualTo(ScopeType.CLIENT);
        assertThat(got.primaryClientId()).isEqualTo("clt_primary");
        assertThat(got.additionalClientIds()).containsExactly("clt_add1", "clt_add2");
        assertThat(got.grantedClientIds()).containsExactly("clt_grant1");
        assertThat(got.requiredOidcTenantId()).isEqualTo("tenant-1");
        assertThat(got.twoFactor().required()).isTrue();
        assertThat(got.twoFactor().allowedMethods()).containsExactly(MfaMethod.TOTP, MfaMethod.EMAIL_PIN);
        assertThat(got.twoFactor().rememberDeviceEnabled()).isTrue();
        assertThat(got.twoFactor().rememberDeviceDays()).isEqualTo(14);
        assertThat(DB.fetchOne(TNT_EMAIL_DOMAIN_MAPPINGS, TNT_EMAIL_DOMAIN_MAPPINGS.ID.eq(ev.mappingId())).getSyncRolesFromIdp())
                .as("dead column left at its default").isFalse();

        var events = eventsFor(ev.mappingId(), EmailDomainMappingEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(EmailDomainMappingEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:emaildomainmapping:" + ev.mappingId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(EmailDomainMappingEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("mappingId").asText()).isEqualTo(ev.mappingId());
        assertThat(data.get("emailDomain").asText()).isEqualTo(domain("edm-create"));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("mappingId", "emailDomain");

        var audits = auditsFor(ev.mappingId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Emaildomainmapping");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("emailDomain").asText()).as("the audit stores the command as sent").contains("EDM-CREATE");
        assertThat(opJson.get("allowed2faMethods")).hasSize(2);
    }

    @Test
    void createAppliesTheThirtyDayDefaultWhenDaysAreAbsentOrNotPositive() {
        var absent = created(domain("edm-days-absent"));
        assertThat(reload(absent.mappingId()).twoFactor().rememberDeviceDays()).isEqualTo(30);
        var zero = runAsAnchor(CreateEmailDomainMapping.of(repo),
                new CreateCommand(domain("edm-days-zero"), IDP, "ANCHOR", null, null, null, null, false, null, false, 0));
        assertThat(reload(zero.mappingId()).twoFactor().rememberDeviceDays()).isEqualTo(30);
        var negative = runAsAnchor(CreateEmailDomainMapping.of(repo),
                new CreateCommand(domain("edm-days-neg"), IDP, "ANCHOR", null, null, null, null, false, null, false, -5));
        assertThat(reload(negative.mappingId()).twoFactor().rememberDeviceDays()).isEqualTo(30);
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("empty domain", new CreateCommand("", "idp_x", "ANCHOR", null, null, null, null, false, null, false, null), "EMAIL_DOMAIN_REQUIRED"),
                Arguments.of("null domain", new CreateCommand(null, "idp_x", "ANCHOR", null, null, null, null, false, null, false, null), "EMAIL_DOMAIN_REQUIRED"),
                Arguments.of("no dot", new CreateCommand("edmnodot", "idp_x", "ANCHOR", null, null, null, null, false, null, false, null), "INVALID_EMAIL_DOMAIN"),
                Arguments.of("contains @", new CreateCommand("user@edm.example.com", "idp_x", "ANCHOR", null, null, null, null, false, null, false, null), "INVALID_EMAIL_DOMAIN"),
                Arguments.of("missing idp", new CreateCommand("edm-noidp.example.com", " ", "ANCHOR", null, null, null, null, false, null, false, null), "IDP_REQUIRED"),
                Arguments.of("bad scope", new CreateCommand("edm-badscope.example.com", "idp_x", "GLOBAL", null, null, null, null, false, null, false, null), "INVALID_SCOPE_TYPE"),
                Arguments.of("partner without primary client", new CreateCommand("edm-noprimary.example.com", "idp_x", "PARTNER", null, null, null, null, false, null, false, null), "PRIMARY_CLIENT_REQUIRED"),
                Arguments.of("client without primary client", new CreateCommand("edm-noprimary.example.com", "idp_x", "CLIENT", null, null, null, null, false, null, false, null), "PRIMARY_CLIENT_REQUIRED"),
                Arguments.of("unknown 2fa method", new CreateCommand("edm-badmethod.example.com", "idp_x", "CLIENT", "clt_p", null, null, null, false, List.of("SMS"), false, null), "INVALID_2FA_METHOD"),
                Arguments.of("require2fa without methods", new CreateCommand("edm-nomethod.example.com", "idp_x", "ANCHOR", null, null, null, null, true, null, false, null), "2FA_METHOD_REQUIRED"),
                Arguments.of("require2fa with empty methods", new CreateCommand("edm-nomethod.example.com", "idp_x", "ANCHOR", null, null, null, null, true, List.of(), false, null), "2FA_METHOD_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateEmailDomainMapping.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    /// Uniqueness applies to the normalised domain: the duplicate is submitted upper-case.
    @Test
    void createRejectsADuplicateDomainCaseInsensitively() {
        created(domain("edm-dup"));
        assertUseCaseError(() -> runAsAnchor(CreateEmailDomainMapping.of(repo), anchorCreate(domain("edm-dup").toUpperCase(Locale.ROOT))),
                UseCaseError.Conflict.class, "DOMAIN_ALREADY_MAPPED");
        assertThatThrownBy(() -> runAsAnchor(CreateEmailDomainMapping.of(repo), anchorCreate(domain("edm-dup"))))
                .hasMessageContaining("Email domain '" + domain("edm-dup") + "' is already mapped");
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesGrantsAndPolicyButNeverDomainOrProvider() {
        var seeded = created(domain("edm-upd"));

        var ev = runAsAnchor(UpdateEmailDomainMapping.of(repo), new UpdateCommand(seeded.mappingId(), "clt_primary",
                List.of("clt_a"), null, "tenant-9", true, List.of("TOTP"), true, 7));
        assertThat(ev.mappingId()).isEqualTo(seeded.mappingId());
        assertThat(ev.emailDomain()).isEqualTo(domain("edm-upd"));
        assertThat(ev.eventType()).isEqualTo(EmailDomainMappingEvents.UPDATED);
        assertThat(ev.messageGroup()).isEqualTo("platform:emaildomainmapping:" + seeded.mappingId());

        var got = reload(seeded.mappingId());
        assertThat(got.emailDomain()).as("domain is immutable on update").isEqualTo(domain("edm-upd"));
        assertThat(got.identityProviderId()).as("provider is immutable on update").isEqualTo(IDP);
        assertThat(got.scopeType()).isEqualTo(ScopeType.ANCHOR);
        assertThat(got.primaryClientId()).isEqualTo("clt_primary");
        assertThat(got.additionalClientIds()).containsExactly("clt_a");
        assertThat(got.grantedClientIds()).as("absent list = unchanged").isEmpty();
        assertThat(got.requiredOidcTenantId()).isEqualTo("tenant-9");
        assertThat(got.twoFactor().required()).isTrue();
        assertThat(got.twoFactor().allowedMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(got.twoFactor().rememberDeviceEnabled()).isTrue();
        assertThat(got.twoFactor().rememberDeviceDays()).isEqualTo(7);

        // Absent scalars clear; absent lists keep; empty lists clear; unchanged 2FA booleans keep; any days value stored.
        runAsAnchor(UpdateEmailDomainMapping.of(repo), new UpdateCommand(seeded.mappingId(), null, null, null, null, null, null, null, 0));
        var again = reload(seeded.mappingId());
        assertThat(again.primaryClientId()).as("absent primaryClientId clears (spec §1)").isNull();
        assertThat(again.requiredOidcTenantId()).as("absent requiredOidcTenantId clears").isNull();
        assertThat(again.additionalClientIds()).as("absent list unchanged").containsExactly("clt_a");
        assertThat(again.twoFactor().required()).isTrue();
        assertThat(again.twoFactor().allowedMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(again.twoFactor().rememberDeviceDays()).as("update stores any supplied value").isZero();

        runAsAnchor(UpdateEmailDomainMapping.of(repo), new UpdateCommand(seeded.mappingId(), null, List.of(), null, null, false, List.of(), null, null));
        var cleared = reload(seeded.mappingId());
        assertThat(cleared.additionalClientIds()).isEmpty();
        assertThat(cleared.twoFactor().required()).isFalse();
        assertThat(cleared.twoFactor().allowedMethods()).isEmpty();

        assertThat(eventsFor(seeded.mappingId(), EmailDomainMappingEvents.UPDATED)).hasSize(3);
        var audits = auditsFor(seeded.mappingId(), "UpdateCommand");
        assertThat(audits).hasSize(3);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Emaildomainmapping");
    }

    @Test
    void updateRejectsAnInconsistentOrUnknownTwoFactorPolicy() {
        var seeded = created(domain("edm-upd-2fa"));
        assertUseCaseError(() -> runAsAnchor(UpdateEmailDomainMapping.of(repo),
                        new UpdateCommand(seeded.mappingId(), null, null, null, null, null, List.of("SMS"), null, null)),
                UseCaseError.Validation.class, "INVALID_2FA_METHOD");
        assertUseCaseError(() -> runAsAnchor(UpdateEmailDomainMapping.of(repo),
                        new UpdateCommand(seeded.mappingId(), null, null, null, null, true, null, null, null)),
                UseCaseError.Validation.class, "2FA_METHOD_REQUIRED");
        assertThat(reload(seeded.mappingId()).twoFactor().required()).as("rejected update wrote nothing").isFalse();
    }

    static Stream<Arguments> badUpdateCommands() {
        return Stream.of(
                Arguments.of("missing id", new UpdateCommand(null, null, null, null, null, null, null, null, null), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank id", new UpdateCommand("  ", null, null, null, null, null, null, null, null), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("unknown id", new UpdateCommand("edm_doesnotexist1", null, null, null, null, null, null, null, null), UseCaseError.NotFound.class, "EmailDomainMapping_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badUpdateCommands")
    void updateRejectsABadCommand(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String code) {
        assertUseCaseError(() -> runAsAnchor(UpdateEmailDomainMapping.of(repo), cmd), kind, code);
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndEveryJunctionIncludingTheLegacyOne() {
        var seeded = runAsAnchor(CreateEmailDomainMapping.of(repo), new CreateCommand(domain("edm-del"), IDP, "CLIENT", "clt_p",
                List.of("clt_a"), List.of("clt_g"), null, true, List.of("EMAIL_PIN"), false, null));
        DB.insertInto(TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES).set(TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES.EMAIL_DOMAIN_MAPPING_ID, seeded.mappingId())
                .set(TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES.ROLE_ID, "rol_legacy").execute();

        var ev = runAsAnchor(DeleteEmailDomainMapping.of(repo), new DeleteCommand(seeded.mappingId()));
        assertThat(ev.mappingId()).isEqualTo(seeded.mappingId());
        assertThat(ev.emailDomain()).isEqualTo(domain("edm-del"));
        assertThat(ev.eventType()).isEqualTo(EmailDomainMappingEvents.DELETED);

        assertThat(repo.findById(seeded.mappingId())).isEmpty();
        assertThat(DB.fetchExists(TNT_EMAIL_DOMAIN_MAPPING_ADDITIONAL_CLIENTS, TNT_EMAIL_DOMAIN_MAPPING_ADDITIONAL_CLIENTS.EMAIL_DOMAIN_MAPPING_ID.eq(seeded.mappingId()))).isFalse();
        assertThat(DB.fetchExists(TNT_EMAIL_DOMAIN_MAPPING_GRANTED_CLIENTS, TNT_EMAIL_DOMAIN_MAPPING_GRANTED_CLIENTS.EMAIL_DOMAIN_MAPPING_ID.eq(seeded.mappingId()))).isFalse();
        assertThat(DB.fetchExists(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS, TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS.EMAIL_DOMAIN_MAPPING_ID.eq(seeded.mappingId()))).isFalse();
        assertThat(DB.fetchExists(TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES, TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES.EMAIL_DOMAIN_MAPPING_ID.eq(seeded.mappingId()))).isFalse();

        assertThat(eventsFor(seeded.mappingId(), EmailDomainMappingEvents.DELETED)).hasSize(1);
        var audits = auditsFor(seeded.mappingId(), "DeleteCommand");
        assertThat(audits).hasSize(1);
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("id").asText()).isEqualTo(seeded.mappingId());
    }

    @Test
    void deleteRejectsABlankOrUnknownId() {
        assertUseCaseError(() -> runAsAnchor(DeleteEmailDomainMapping.of(repo), new DeleteCommand("")), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteEmailDomainMapping.of(repo), new DeleteCommand("edm_doesnotexist1")), UseCaseError.NotFound.class, "EmailDomainMapping_NOT_FOUND");
    }

    // ── Move provider ──────────────────────────────────────────────────────

    @Test
    void moveTowardOidcRePointsTheMappingAndTouchesNoPrincipal() {
        var seeded = created(domain("edm-move-fwd"));
        String oidcUser = user(domain("edm-move-fwd"), "OIDC", "ext-1");
        String target = identityProvider("OIDC");

        var res = Auth.runAs(ANCHOR, () -> MoveEmailDomainMappingProvider.of(repo).run(uow, new MoveProviderCommand(seeded.mappingId(), target), EC));
        assertThat(res).isEqualTo(new MoveProviderResult(seeded.mappingId(), domain("edm-move-fwd"), IDP, target, 0));
        assertThat(reload(seeded.mappingId()).identityProviderId()).isEqualTo(target);
        assertThat(DB.fetchOne(IAM_PRINCIPALS, IAM_PRINCIPALS.ID.eq(oidcUser)).getIdpType()).as("moving toward OIDC never touches principals").isEqualTo("OIDC");

        var events = eventsFor(seeded.mappingId(), EmailDomainMappingEvents.PROVIDER_CHANGED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:emaildomainmapping:" + seeded.mappingId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("fromIdentityProviderId").asText()).isEqualTo(IDP);
        assertThat(data.get("toIdentityProviderId").asText()).isEqualTo(target);
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("mappingId", "emailDomain", "fromIdentityProviderId", "toIdentityProviderId");
        var audits = auditsFor(seeded.mappingId(), "MoveProviderCommand");
        assertThat(audits).hasSize(1);
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("identityProviderId").asText()).isEqualTo(target);
    }

    @Test
    void moveToInternalConvertsTheDomainsOidcUsersBackAndDropsTheirSyncedRoles() {
        var seeded = created(domain("edm-move-back"));
        String oidcUser = user(domain("edm-move-back"), "OIDC", "ext-2");
        String internalUser = user(domain("edm-move-back"), "INTERNAL", null);
        String otherDomainUser = user(domain("edm-move-other"), "OIDC", "ext-3");
        String target = identityProvider("INTERNAL");

        var res = Auth.runAs(ANCHOR, () -> MoveEmailDomainMappingProvider.of(repo).run(uow, new MoveProviderCommand(seeded.mappingId(), target), EC));
        assertThat(res.usersReset()).isEqualTo(1);
        assertThat(res.toIdentityProviderId()).isEqualTo(target);

        var reset = DB.fetchOne(IAM_PRINCIPALS, IAM_PRINCIPALS.ID.eq(oidcUser));
        assertThat(reset.getIdpType()).isEqualTo("INTERNAL");
        assertThat(reset.getExternalIdpId()).isNull();
        assertThat(DB.fetch(IAM_PRINCIPAL_ROLES, IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.eq(oidcUser)).getValues(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE))
                .as("IDP_SYNC roles dropped, admin-assigned kept").containsExactly("ADMIN");
        assertThat(DB.fetch(IAM_PRINCIPAL_ROLES, IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.eq(internalUser))).as("internal users untouched").hasSize(2);
        assertThat(DB.fetchOne(IAM_PRINCIPALS, IAM_PRINCIPALS.ID.eq(otherDomainUser)).getIdpType()).as("other domains untouched").isEqualTo("OIDC");
    }

    @Test
    void moveRejectsTheSameProviderAnUnknownTargetAndAnUnknownMapping() {
        var seeded = created(domain("edm-move-err"));
        var op = MoveEmailDomainMappingProvider.of(repo);
        assertUseCaseError(() -> Auth.runAs(ANCHOR, () -> op.run(uow, new MoveProviderCommand(seeded.mappingId(), IDP), EC)),
                UseCaseError.Conflict.class, "ALREADY_ON_PROVIDER");
        assertUseCaseError(() -> Auth.runAs(ANCHOR, () -> op.run(uow, new MoveProviderCommand(seeded.mappingId(), "idp_doesnotexist1"), EC)),
                UseCaseError.NotFound.class, "IdentityProvider_NOT_FOUND");
        assertUseCaseError(() -> Auth.runAs(ANCHOR, () -> op.run(uow, new MoveProviderCommand("edm_doesnotexist1", IDP), EC)),
                UseCaseError.NotFound.class, "EmailDomainMapping_NOT_FOUND");
        assertUseCaseError(() -> Auth.runAs(ANCHOR, () -> op.run(uow, new MoveProviderCommand(" ", IDP), EC)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> Auth.runAs(ANCHOR, () -> op.run(uow, new MoveProviderCommand(seeded.mappingId(), ""), EC)),
                UseCaseError.Validation.class, "IDP_REQUIRED");
        assertThat(reload(seeded.mappingId()).identityProviderId()).as("nothing moved").isEqualTo(IDP);
    }

    // ── Repository reads ───────────────────────────────────────────────────

    /// The `method` junction is a foreign-writable column: a value outside
    /// the closed set (hand-edited, or a future method this build predates)
    /// must not take the read down — it is dropped (spec §1, open question 8).
    @Test
    void readsDropAnUnknownStoredMethodInsteadOfFailing() {
        var seeded = runAsAnchor(CreateEmailDomainMapping.of(repo), new CreateCommand(domain("edm-read-mfa"), IDP, "ANCHOR", null,
                null, null, null, true, List.of("TOTP"), false, null));
        DB.insertInto(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS).set(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS.EMAIL_DOMAIN_MAPPING_ID, seeded.mappingId())
                .set(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS.METHOD, "SMS").execute();

        assertThat(reload(seeded.mappingId()).twoFactor().allowedMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(repo.findAll()).extracting(EmailDomainMapping::id).contains(seeded.mappingId());
    }

    @Test
    void readsFindByDomainByProviderAndAllInDomainOrder() {
        String target = identityProvider("OIDC");
        var b = runAsAnchor(CreateEmailDomainMapping.of(repo), new CreateCommand(domain("edm-read-b"), target, "ANCHOR", null, null, null, null, false, null, false, null));
        var a = runAsAnchor(CreateEmailDomainMapping.of(repo), new CreateCommand(domain("edm-read-a"), target, "ANCHOR", null, null, null, null, false, null, false, null));

        assertThat(repo.findByEmailDomain(domain("edm-read-a"))).map(EmailDomainMapping::id).contains(a.mappingId());
        assertThat(repo.findByEmailDomain(domain("edm-read-a").toUpperCase(Locale.ROOT))).as("exact match on the stored form").isEmpty();
        assertThat(repo.findByIdentityProvider(target)).extracting(EmailDomainMapping::id).containsExactly(a.mappingId(), b.mappingId());
        assertThat(repo.findAll()).extracting(EmailDomainMapping::id).containsSubsequence(a.mappingId(), b.mappingId());
        assertThat(repo.identityProvider(target)).map(EmailDomainMappingRepository.IdentityProviderRef::name).contains("IdP " + target);
        assertThat(repo.identityProviderNames(List.of(target, "idp_nope"))).containsOnlyKeys(target);
    }

    // ── Tenant pin (owner ruling 2026-09-25, backlog "Overnight review" item 3) ──

    /// A multi-tenant OIDC provider row, optionally pinning `tenants` at the provider level.
    private static String multiTenantProvider(String... tenants) {
        String id = identityProvider("OIDC");
        DB.update(OAUTH_IDENTITY_PROVIDERS).set(OAUTH_IDENTITY_PROVIDERS.OIDC_MULTI_TENANT, true)
                .where(OAUTH_IDENTITY_PROVIDERS.ID.eq(id)).execute();
        var t = io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDER_ALLOWED_TENANTS;
        for (String tenant : tenants) {
            DB.insertInto(t).set(t.IDENTITY_PROVIDER_ID, id).set(t.TENANT_ID, tenant).execute();
        }
        return id;
    }

    @Test
    void aMappingToAMultiTenantProviderMustBePinnedByItselfOrTheProvider() {
        String bare = multiTenantProvider();
        String pinnedProvider = multiTenantProvider("tid-" + RUN);

        assertUseCaseError(() -> runAsAnchor(CreateEmailDomainMapping.of(repo),
                        new CreateCommand(domain("pin-none"), bare, "ANCHOR", null, null, null, null, false, null, false, null)),
                UseCaseError.Validation.class, "TENANT_PIN_REQUIRED");
        assertThat(repo.findByEmailDomain(domain("pin-none"))).as("nothing stored").isEmpty();

        var own = runAsAnchor(CreateEmailDomainMapping.of(repo),
                new CreateCommand(domain("pin-own"), bare, "ANCHOR", null, null, null, "tid-own", false, null, false, null));
        assertThat(own.mappingId()).isNotBlank();
        var byProvider = runAsAnchor(CreateEmailDomainMapping.of(repo),
                new CreateCommand(domain("pin-provider"), pinnedProvider, "ANCHOR", null, null, null, null, false, null, false, null));
        assertThat(byProvider.mappingId()).isNotBlank();

        assertUseCaseError(() -> runAsAnchor(UpdateEmailDomainMapping.of(repo),
                        new UpdateCommand(own.mappingId(), null, null, null, null, null, null, null, null)),
                UseCaseError.Validation.class, "TENANT_PIN_REQUIRED");
        assertThat(reload(own.mappingId()).requiredOidcTenantId()).as("the pin survives the refused update").isEqualTo("tid-own");

        var single = created(domain("pin-move"));
        assertUseCaseError(() -> Auth.runAs(ANCHOR, () ->
                        MoveEmailDomainMappingProvider.of(repo).run(uow, new MoveProviderCommand(single.mappingId(), bare), EC)),
                UseCaseError.Validation.class, "TENANT_PIN_REQUIRED");
        assertThat(reload(single.mappingId()).identityProviderId()).as("not moved").isNotEqualTo(bare);
    }
}
