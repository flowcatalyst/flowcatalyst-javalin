package io.flowcatalyst.platform.application.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationRepository.ListFilter;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationCreated;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_APPLICATION_IDS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The application use cases against the embedded Postgres (spec §4–9):
/// validation, the per-client authorization of the config operations,
/// persistence, and the envelope's guarantee that an aggregate write lands
/// together with its `msg_events` and `aud_logs` rows. The pure rules are
/// covered by `ApplicationTest`; here each operation is exercised once
/// through the envelope.
///
/// The fixture never truncates, so every test owns its rows: codes carry a
/// per-JVM suffix, client and principal rows are minted per test.
@SuppressWarnings("deprecation")
class ApplicationOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ApplicationRepository repo = new ApplicationRepository(DS);
    private static final ClientConfigRepository configs = new ClientConfigRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final Optional<Encryption> ENCRYPTION = Optional.of(Encryption.withKey(Encryption.generateKey()));
    private static final ServiceAccountRepository serviceAccounts = new ServiceAccountRepository(DS, ENCRYPTION);
    private static final PrincipalRepository principals = new PrincipalRepository(DS);
    private static final OAuthClientRepository oauthClients = new OAuthClientRepository(DS, repo);

    /// Per-JVM namespace so codes never collide with another run on the same database.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static <C, R> R runAsAnchorTx(TxOperation<C, R> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static String code(String tag) {
        return tag + "_" + RUN;
    }

    private static ApplicationCreated created(String tag, String name) {
        return runAsAnchor(CreateApplication.of(repo), new CreateCommand(code(tag), name, null, null, null, null, null, null, null));
    }

    private static Application reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("application " + id + " not found"));
    }

    /// A bare `tnt_clients` row — the client aggregate's operations are not this test's business.
    private static String client(String name) {
        String id = EntityType.CLIENT.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, id).set(TNT_CLIENTS.NAME, name).set(TNT_CLIENTS.IDENTIFIER, name + "-" + RUN)
                .set(TNT_CLIENTS.STATUS, "ACTIVE").set(TNT_CLIENTS.CREATED_AT, now).set(TNT_CLIENTS.UPDATED_AT, now).execute();
        return id;
    }

    /// A `SERVICE` principal linked to service account `serviceAccountId`; returns the principal id.
    private static String servicePrincipal(String serviceAccountId) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "SERVICE")
                .set(IAM_PRINCIPALS.NAME, "sa " + serviceAccountId).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, serviceAccountId)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now).execute();
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

    /// `msg_events` rows of one type on a subject.
    private static Result<Record> eventsOn(String subject, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                subject, type);
    }

    private static Result<Record> eventsFor(String applicationId, String type) {
        return eventsOn(ApplicationEvents.subjectFor(applicationId), type);
    }

    /// `aud_logs` rows for one aggregate and command.
    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        var ev = runAsAnchor(CreateApplication.of(repo), new CreateCommand("  " + code("AppCreate").toUpperCase(Locale.ROOT) + " ",
                "  First App  ", null, "the first", "https://icon", "https://example.com", "<svg/>", "image/svg+xml", "https://base"));

        assertThat(ev.applicationId()).startsWith("app_");
        assertThat(ev.code()).as("code is trimmed + lower-cased").isEqualTo(code("appcreate"));
        assertThat(ev.name()).as("name is trimmed").isEqualTo("First App");
        assertThat(ev.eventType()).isEqualTo(ApplicationEvents.CREATED);
        assertThat(ev.source()).isEqualTo(ApplicationEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(ApplicationEvents.subjectFor(ev.applicationId()));
        assertThat(ev.messageGroup()).isEqualTo(ApplicationEvents.groupFor(ev.applicationId()));

        var got = reload(ev.applicationId());
        assertThat(got.type()).as("default type").isEqualTo(ApplicationType.APPLICATION);
        assertThat(got.active()).as("new applications start active").isTrue();
        assertThat(got.description()).isEqualTo("the first");
        assertThat(got.iconUrl()).isEqualTo("https://icon");
        assertThat(got.website()).isEqualTo("https://example.com");
        assertThat(got.logo()).isEqualTo("<svg/>");
        assertThat(got.logoMimeType()).isEqualTo("image/svg+xml");
        assertThat(got.defaultBaseUrl()).isEqualTo("https://base");
        assertThat(got.serviceAccountId()).isNull();

        var events = eventsFor(ev.applicationId(), ApplicationEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(ApplicationEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:application:" + ev.applicationId());
        assertThat(events.getFirst().get("deduplication_id")).isEqualTo(ApplicationEvents.CREATED + "-" + ev.eventId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("applicationId").asText()).isEqualTo(ev.applicationId());
        assertThat(data.get("code").asText()).isEqualTo(code("appcreate"));
        assertThat(data.get("name").asText()).isEqualTo("First App");

        var audits = auditsFor(ev.applicationId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Application");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        var opJson = json(audits.getFirst().get("operation_json", String.class));
        assertThat(opJson.get("name").asText()).isEqualTo("  First App  ");
    }

    @Test
    void createTypeIsLenientAndIntegrationIsKept() {
        var integration = runAsAnchor(CreateApplication.of(repo),
                new CreateCommand(code("appint"), "CRM", "INTEGRATION", null, null, null, null, null, null));
        assertThat(reload(integration.applicationId()).type()).isEqualTo(ApplicationType.INTEGRATION);

        var bogus = runAsAnchor(CreateApplication.of(repo),
                new CreateCommand(code("appbogus"), "Bogus", "WHATEVER", null, null, null, null, null, null));
        assertThat(reload(bogus.applicationId()).type()).as("unknown type → APPLICATION (spec §4)").isEqualTo(ApplicationType.APPLICATION);
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("null code", new CreateCommand(null, "X", null, null, null, null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("blank code", new CreateCommand("  ", "X", null, null, null, null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("leading digit", new CreateCommand("1bad", "X", null, null, null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("bad character", new CreateCommand("has space", "X", null, null, null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("null name", new CreateCommand("appcrtval", null, null, null, null, null, null, null, null), "NAME_REQUIRED"),
                Arguments.of("blank name", new CreateCommand("appcrtval", "  ", null, null, null, null, null, null, null), "NAME_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateApplication.of(repo), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createRejectsADuplicateCodeAfterNormalisation() {
        created("appdup", "First");
        assertUseCaseError(() -> runAsAnchor(CreateApplication.of(repo),
                        new CreateCommand(" " + code("APPDUP") + " ", "Second", null, null, null, null, null, null, null)),
                UseCaseError.Conflict.class, "CODE_EXISTS");
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesOnlyTheSuppliedFields() {
        var seeded = runAsAnchor(CreateApplication.of(repo),
                new CreateCommand(code("appupd"), "Before", null, "keep me", "icon", null, null, null, null));

        var ev = runAsAnchor(UpdateApplication.of(repo),
                new UpdateCommand(seeded.applicationId(), "  After ", null, null, "https://site", null, null, ""));
        assertThat(ev.applicationId()).isEqualTo(seeded.applicationId());
        assertThat(ev.name()).isEqualTo("After");
        assertThat(ev.eventType()).isEqualTo(ApplicationEvents.UPDATED);

        var got = reload(seeded.applicationId());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).as("absent field is unchanged").isEqualTo("keep me");
        assertThat(got.iconUrl()).isEqualTo("icon");
        assertThat(got.website()).isEqualTo("https://site");
        assertThat(got.defaultBaseUrl()).as("an explicit empty string is stored (spec §1.1)").isEmpty();
        assertThat(got.code()).as("code is immutable").isEqualTo(code("appupd"));

        assertThat(eventsFor(seeded.applicationId(), ApplicationEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(seeded.applicationId(), "UpdateCommand")).hasSize(1);
    }

    @Test
    void updateRejectsMissingIdBlankNameOrRow() {
        assertUseCaseError(() -> runAsAnchor(UpdateApplication.of(repo), new UpdateCommand(null, "X", null, null, null, null, null, null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateApplication.of(repo), new UpdateCommand("app_doesnotexist1", "  ", null, null, null, null, null, null)),
                UseCaseError.Validation.class, "NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateApplication.of(repo), new UpdateCommand("app_doesnotexist1", "X", null, null, null, null, null, null)),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRow() {
        var seeded = created("appdel", "Doomed");

        var ev = runAsAnchor(DeleteApplication.of(repo), new DeleteCommand(seeded.applicationId()));
        assertThat(ev.applicationId()).isEqualTo(seeded.applicationId());
        assertThat(ev.code()).isEqualTo(code("appdel"));

        assertThat(repo.findById(seeded.applicationId())).as("deleted row must be gone").isEmpty();
        assertThat(eventsFor(seeded.applicationId(), ApplicationEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.applicationId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteRejectsMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteApplication.of(repo), new DeleteCommand("")), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteApplication.of(repo), new DeleteCommand("app_doesnotexist1")),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
    }

    // ── Activate / deactivate ──────────────────────────────────────────────

    @Test
    void deactivateThenActivateFlipTheFlagAndAreIdempotent() {
        var seeded = created("appflag", "Flag");

        var off = runAsAnchor(DeactivateApplication.of(repo), new DeactivateCommand(seeded.applicationId()));
        assertThat(off.applicationId()).isEqualTo(seeded.applicationId());
        assertThat(off.eventType()).isEqualTo(ApplicationEvents.DEACTIVATED);
        assertThat(reload(seeded.applicationId()).active()).isFalse();

        var on = runAsAnchor(ActivateApplication.of(repo), new ActivateCommand(seeded.applicationId()));
        assertThat(on.eventType()).isEqualTo(ApplicationEvents.ACTIVATED);
        assertThat(reload(seeded.applicationId()).active()).isTrue();

        runAsAnchor(ActivateApplication.of(repo), new ActivateCommand(seeded.applicationId()));
        assertThat(eventsFor(seeded.applicationId(), ApplicationEvents.ACTIVATED)).as("a no-op activate still emits (spec §2)").hasSize(2);
        assertThat(auditsFor(seeded.applicationId(), "ActivateCommand")).hasSize(2);
        assertThat(auditsFor(seeded.applicationId(), "DeactivateCommand")).hasSize(1);
    }

    @Test
    void activateAndDeactivateRejectMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(ActivateApplication.of(repo), new ActivateCommand(" ")), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeactivateApplication.of(repo), new DeactivateCommand(null)), UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(ActivateApplication.of(repo), new ActivateCommand("app_doesnotexist1")),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(DeactivateApplication.of(repo), new DeactivateCommand("app_doesnotexist1")),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
    }

    // ── Attach service account ─────────────────────────────────────────────

    @Test
    void attachStoresThePrincipalIdAndIsWriteOnce() {
        var seeded = created("appsa", "With SA");
        String saId = EntityType.SERVICE_ACCOUNT.generate();
        String principalId = servicePrincipal(saId);

        var ev = runAsAnchor(AttachServiceAccount.of(repo), new AttachServiceAccountCommand(seeded.applicationId(), saId, "app:" + code("appsa")));
        assertThat(ev.eventType()).isEqualTo(ApplicationEvents.SERVICE_ACCOUNT_PROVISIONED);
        assertThat(ev.applicationCode()).isEqualTo(code("appsa"));
        assertThat(ev.serviceAccountId()).as("event carries the SA id (spec §8)").isEqualTo(saId);
        assertThat(ev.serviceAccountCode()).isEqualTo("app:" + code("appsa"));
        assertThat(reload(seeded.applicationId()).serviceAccountId()).as("row stores the principal id").isEqualTo(principalId);

        var data = json(eventsFor(seeded.applicationId(), ApplicationEvents.SERVICE_ACCOUNT_PROVISIONED).getFirst().get("data", String.class));
        assertThat(data.get("serviceAccountCode").asText()).isEqualTo("app:" + code("appsa"));
        assertThat(auditsFor(seeded.applicationId(), "AttachServiceAccountCommand")).hasSize(1);

        assertUseCaseError(() -> runAsAnchor(AttachServiceAccount.of(repo), new AttachServiceAccountCommand(seeded.applicationId(), saId, null)),
                UseCaseError.BusinessRule.class, "APPLICATION_HAS_SERVICE_ACCOUNT");
    }

    @Test
    void attachRejectsMissingFieldsRowOrUnlinkedServiceAccount() {
        assertUseCaseError(() -> runAsAnchor(AttachServiceAccount.of(repo), new AttachServiceAccountCommand(null, "sac_x", null)),
                UseCaseError.Validation.class, "APPLICATION_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(AttachServiceAccount.of(repo), new AttachServiceAccountCommand("app_doesnotexist1", " ", null)),
                UseCaseError.Validation.class, "SERVICE_ACCOUNT_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(AttachServiceAccount.of(repo), new AttachServiceAccountCommand("app_doesnotexist1", "sac_x", null)),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
        var seeded = created("appsaerr", "No principal");
        assertUseCaseError(() -> runAsAnchor(AttachServiceAccount.of(repo),
                        new AttachServiceAccountCommand(seeded.applicationId(), EntityType.SERVICE_ACCOUNT.generate(), null)),
                UseCaseError.NotFound.class, "ServiceAccountPrincipal_NOT_FOUND");
    }

    // ── Provision service account ──────────────────────────────────────────

    private static int countServiceAccountsForApp(String applicationId) {
        return DB.fetchCount(IAM_SERVICE_ACCOUNTS, IAM_SERVICE_ACCOUNTS.APPLICATION_ID.eq(applicationId));
    }

    private static int countPrincipalsForApp(String applicationId) {
        return DB.fetchCount(DB.selectFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID.in(
                DB.select(IAM_SERVICE_ACCOUNTS.ID).from(IAM_SERVICE_ACCOUNTS).where(IAM_SERVICE_ACCOUNTS.APPLICATION_ID.eq(applicationId)))));
    }

    private static int countOAuthClientsForApp(String applicationId) {
        return DB.fetchCount(OAUTH_CLIENT_APPLICATION_IDS, OAUTH_CLIENT_APPLICATION_IDS.APPLICATION_ID.eq(applicationId));
    }

    private static boolean matches(String ref, String provided) {
        return ENCRYPTION.get().verifySecret(ref, provided) instanceof Encryption.SecretVerification.Matched;
    }

    @Test
    void provisionServiceAccountWritesAllFourAggregatesWithTheirJunctionsAndTheirEvents() {
        var app = created("appprov", "Provisioned Co");

        var result = runAsAnchorTx(ProvisionServiceAccount.of(repo, serviceAccounts, principals, oauthClients, ENCRYPTION),
                new ProvisionServiceAccountCommand(app.applicationId()));

        // 1. Service account row.
        ServiceAccount sa = serviceAccounts.findByCode("app:" + code("appprov")).orElseThrow();
        assertThat(sa.applicationId()).isEqualTo(app.applicationId());
        assertThat(sa.name()).isEqualTo("Provisioned Co Service Account");
        assertThat(sa.description()).isEqualTo("Service account for application: Provisioned Co");
        assertThat(sa.webhookCredentials().token()).as("webhook credentials really minted").isNotBlank();

        // 2. SERVICE principal row + role junction + application-access junction — the
        // observable effect a token depends on, not merely "a principal exists".
        Principal principal = principals.findById(result.principalId()).orElseThrow();
        assertThat(principal.isService()).isTrue();
        assertThat(principal.allApplications()).as("confined, not all-applications").isFalse();
        assertThat(principal.accessibleApplicationIds()).containsExactly(app.applicationId());
        assertThat(principal.roleNames()).as("the seeded application-service role is actually granted")
                .containsExactly("platform:application-service");
        assertThat(principal.roles().getFirst().assignmentSource()).isEqualTo("PROVISIONED");

        // 3. Application row: serviceAccountId is the PRINCIPAL id (spec §1.1 FK), not the SA id.
        assertThat(reload(app.applicationId()).serviceAccountId())
                .as("row stores the principal id, distinct from the service-account id")
                .isEqualTo(result.principalId()).isNotEqualTo(sa.id());

        // 4. OAuth client row, scoped to the principal and the application, and its
        // secret really decrypts to the plaintext handed back once (works, not exists).
        OAuthClient oc = oauthClients.findById(result.oauthClientId()).orElseThrow();
        assertThat(oc.clientType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(oc.principalId()).isEqualTo(result.principalId());
        assertThat(oc.grantTypes()).containsExactlyInAnyOrder("client_credentials", "refresh_token");
        assertThat(oc.defaultScopes()).containsExactly("openid");
        assertThat(oc.applicationIds()).containsExactly(app.applicationId());
        assertThat(oc.acceptsSecret(result.oauthClientSecret(), Instant.now(), ApplicationOperationsTest::matches))
                .as("the disclosed plaintext round-trips through the stored ciphertext").isTrue();

        // Events + audit: the service-account-provisioned event carries the SA id, not
        // the principal id (spec §8, kept accident).
        var events = eventsFor(app.applicationId(), ApplicationEvents.SERVICE_ACCOUNT_PROVISIONED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("serviceAccountId").asText()).isEqualTo(sa.id());
        assertThat(auditsFor(app.applicationId(), "ProvisionServiceAccountCommand")).hasSize(1);
    }

    @Test
    void provisionServiceAccountTwiceIsRejectedAndWritesNoDuplicateRows() {
        var app = created("appprovdup", "Dup Co");
        runAsAnchorTx(ProvisionServiceAccount.of(repo, serviceAccounts, principals, oauthClients, ENCRYPTION),
                new ProvisionServiceAccountCommand(app.applicationId()));

        int saBefore = countServiceAccountsForApp(app.applicationId());
        int principalsBefore = countPrincipalsForApp(app.applicationId());
        int oauthBefore = countOAuthClientsForApp(app.applicationId());

        assertUseCaseError(() -> runAsAnchorTx(ProvisionServiceAccount.of(repo, serviceAccounts, principals, oauthClients, ENCRYPTION),
                        new ProvisionServiceAccountCommand(app.applicationId())),
                UseCaseError.Conflict.class, "ALREADY_PROVISIONED");

        assertThat(countServiceAccountsForApp(app.applicationId())).as("no duplicate service account").isEqualTo(saBefore);
        assertThat(countPrincipalsForApp(app.applicationId())).as("no duplicate principal").isEqualTo(principalsBefore);
        assertThat(countOAuthClientsForApp(app.applicationId())).as("no duplicate oauth client").isEqualTo(oauthBefore);
    }

    @Test
    void provisionServiceAccountFailsAtomicallyWithNoAppKeyConfigured() {
        var app = created("appprovnokey", "No Key Co");

        assertUseCaseError(() -> runAsAnchorTx(ProvisionServiceAccount.of(repo, serviceAccounts, principals, oauthClients, Optional.empty()),
                        new ProvisionServiceAccountCommand(app.applicationId())),
                UseCaseError.Internal.class, "SECRET");

        assertThat(countServiceAccountsForApp(app.applicationId())).as("no service account row survives the rollback").isZero();
        assertThat(countPrincipalsForApp(app.applicationId())).as("no principal row survives the rollback").isZero();
        assertThat(countOAuthClientsForApp(app.applicationId())).as("no oauth client row survives the rollback").isZero();
        assertThat(reload(app.applicationId()).serviceAccountId()).as("the application row is untouched").isNull();
    }

    @Test
    void provisionServiceAccountRejectsAMissingApplicationIdOrUnknownRow() {
        assertUseCaseError(() -> runAsAnchorTx(ProvisionServiceAccount.of(repo, serviceAccounts, principals, oauthClients, ENCRYPTION),
                        new ProvisionServiceAccountCommand(" ")),
                UseCaseError.Validation.class, "APPLICATION_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchorTx(ProvisionServiceAccount.of(repo, serviceAccounts, principals, oauthClients, ENCRYPTION),
                        new ProvisionServiceAccountCommand("app_doesnotexist1")),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
    }

    // ── Enable / disable for client ────────────────────────────────────────

    @Test
    void enableCreatesTheConfigDisableKeepsItAndReenableReusesIt() {
        var app = created("appen", "Enable Me");
        String clientId = client("enable-" + RUN);

        var enabled = runAsAnchor(EnableApplicationForClient.of(repo, configs), new EnableForClientCommand(app.applicationId(), clientId));
        assertThat(enabled.eventType()).isEqualTo(ApplicationEvents.ENABLED_FOR_CLIENT);
        assertThat(enabled.applicationId()).isEqualTo(app.applicationId());
        assertThat(enabled.clientId()).isEqualTo(clientId);
        assertThat(enabled.configId()).startsWith("apc_");
        var cfg = configs.findByApplicationAndClient(app.applicationId(), clientId).orElseThrow();
        assertThat(cfg.id()).isEqualTo(enabled.configId());
        assertThat(cfg.enabled()).isTrue();
        var audits = auditsFor(app.applicationId(), "EnableForClientCommand");
        assertThat(audits).as("audited under the application, the event's subject (spec §8)").hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Application");

        var disabled = runAsAnchor(DisableApplicationForClient.of(configs), new DisableForClientCommand(app.applicationId(), clientId));
        assertThat(disabled.configId()).as("disable flips the same row").isEqualTo(cfg.id());
        assertThat(configs.findByApplicationAndClient(app.applicationId(), clientId)).get()
                .satisfies(c -> assertThat(c.enabled()).as("disable keeps the row").isFalse());
        assertThat(eventsFor(app.applicationId(), ApplicationEvents.DISABLED_FOR_CLIENT)).hasSize(1);

        var reenabled = runAsAnchor(EnableApplicationForClient.of(repo, configs), new EnableForClientCommand(app.applicationId(), clientId));
        assertThat(reenabled.configId()).as("re-enable reuses the existing row").isEqualTo(cfg.id());
        assertThat(configs.findByApplication(app.applicationId())).hasSize(1);
        assertThat(configs.findByClient(clientId)).extracting(ClientConfig::enabled).containsExactly(true);
    }

    @Test
    void enableAndDisableRejectMissingFieldsAndUnknownRows() {
        assertUseCaseError(() -> runAsAnchor(EnableApplicationForClient.of(repo, configs), new EnableForClientCommand(null, "clt_x")),
                UseCaseError.Validation.class, "APPLICATION_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(EnableApplicationForClient.of(repo, configs), new EnableForClientCommand("app_x", " ")),
                UseCaseError.Validation.class, "CLIENT_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DisableApplicationForClient.of(configs), new DisableForClientCommand("", "clt_x")),
                UseCaseError.Validation.class, "APPLICATION_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DisableApplicationForClient.of(configs), new DisableForClientCommand("app_x", null)),
                UseCaseError.Validation.class, "CLIENT_ID_REQUIRED");

        String clientId = client("enable-err-" + RUN);
        assertUseCaseError(() -> runAsAnchor(EnableApplicationForClient.of(repo, configs), new EnableForClientCommand("app_doesnotexist1", clientId)),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
        var app = created("appenerr", "Enable Errors");
        assertUseCaseError(() -> runAsAnchor(EnableApplicationForClient.of(repo, configs), new EnableForClientCommand(app.applicationId(), "clt_doesnotexist1")),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(DisableApplicationForClient.of(configs), new DisableForClientCommand(app.applicationId(), clientId)),
                UseCaseError.NotFound.class, "ClientConfig_NOT_FOUND");
        assertThatThrownBy(() -> runAsAnchor(DisableApplicationForClient.of(configs), new DisableForClientCommand(app.applicationId(), clientId)))
                .hasMessageContaining(app.applicationId() + ":" + clientId);
    }

    /// The config operations' resource is the client: a CLIENT-scoped
    /// principal may only bind applications to a client it can access.
    @Test
    void enableEnforcesScopeOnTheTargetClient() {
        var app = created("appenscope", "Scope");
        String own = client("scope-own-" + RUN);
        String other = client("scope-other-" + RUN);
        var clientCtx = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "c@x.io", List.of(own),
                List.of(), List.of(), true, List.of());
        var clientEc = ExecutionContext.of(clientCtx.principalId());

        assertUseCaseError(() -> Auth.runAs(clientCtx, () -> EnableApplicationForClient.of(repo, configs)
                        .run(uow, new EnableForClientCommand(app.applicationId(), other), clientEc)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
        assertUseCaseError(() -> EnableApplicationForClient.of(repo, configs)
                        .run(uow, new EnableForClientCommand(app.applicationId(), own), ExecutionContext.of(null)),
                UseCaseError.Authorization.class, "UNAUTHENTICATED");
        assertThat(configs.findByClient(own)).isEmpty();

        var ev = Auth.runAs(clientCtx, () -> EnableApplicationForClient.of(repo, configs)
                .run(uow, new EnableForClientCommand(app.applicationId(), own), clientEc));
        assertThat(ev.clientId()).isEqualTo(own);
    }

    // ── Update client applications ─────────────────────────────────────────

    @Test
    void updateClientApplicationsAppliesTheDiffAndAlwaysEmitsTheRollup() {
        String clientId = client("bulk-" + RUN);
        var a = created("appbulka", "A");
        var b = created("appbulkb", "B");
        var c = created("appbulkc", "C");
        String subject = ApplicationEvents.clientSubjectFor(clientId);

        var first = runAsAnchor(UpdateClientApplications.of(repo, configs),
                new UpdateClientApplicationsCommand(clientId, List.of(a.applicationId(), b.applicationId())));
        assertThat(first.eventType()).isEqualTo(ApplicationEvents.CLIENT_APPLICATIONS_UPDATED);
        assertThat(first.subject()).isEqualTo(subject);
        assertThat(first.messageGroup()).isEqualTo("platform:client:" + clientId);
        assertThat(first.enabledAdded()).containsExactlyInAnyOrder(a.applicationId(), b.applicationId());
        assertThat(first.disabledRemoved()).isEmpty();
        assertThat(enabledByApp(clientId)).containsExactlyInAnyOrder(a.applicationId(), b.applicationId());

        var second = runAsAnchor(UpdateClientApplications.of(repo, configs),
                new UpdateClientApplicationsCommand(clientId, List.of(b.applicationId(), c.applicationId())));
        assertThat(second.enabledAdded()).containsExactly(c.applicationId());
        assertThat(second.disabledRemoved()).containsExactly(a.applicationId());
        assertThat(enabledByApp(clientId)).containsExactlyInAnyOrder(b.applicationId(), c.applicationId());
        assertThat(configs.findByClient(clientId)).as("disabled rows are kept").hasSize(3);

        var third = runAsAnchor(UpdateClientApplications.of(repo, configs),
                new UpdateClientApplicationsCommand(clientId, List.of(b.applicationId(), c.applicationId())));
        assertThat(third.enabledAdded()).isEmpty();
        assertThat(third.disabledRemoved()).isEmpty();

        var rollups = eventsOn(subject, ApplicationEvents.CLIENT_APPLICATIONS_UPDATED);
        assertThat(rollups).as("an empty diff still emits the rollup").hasSize(3);
        var data = json(rollups.getFirst().get("data", String.class));
        assertThat(data.get("enabledApplicationIds")).extracting(JsonNode::asText).containsExactly(a.applicationId(), b.applicationId());
        assertThat(data.get("enabledAdded")).hasSize(2);
        assertThat(data.get("disabledRemoved")).isEmpty();
        var audits = auditsFor(clientId, "UpdateClientApplicationsCommand");
        assertThat(audits).as("the rollup is audited under the client").hasSize(3);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Client");
    }

    @Test
    void updateClientApplicationsRejectsMissingClientBlankOrUnknownApplication() {
        assertUseCaseError(() -> runAsAnchor(UpdateClientApplications.of(repo, configs), new UpdateClientApplicationsCommand(" ", List.of())),
                UseCaseError.Validation.class, "CLIENT_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateClientApplications.of(repo, configs), new UpdateClientApplicationsCommand("clt_doesnotexist1", List.of())),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
        String clientId = client("bulk-err-" + RUN);
        assertUseCaseError(() -> runAsAnchor(UpdateClientApplications.of(repo, configs), new UpdateClientApplicationsCommand(clientId, List.of("  "))),
                UseCaseError.Validation.class, "APPLICATION_ID_REQUIRED");
        var a = created("appbulkerr", "A");
        assertUseCaseError(() -> runAsAnchor(UpdateClientApplications.of(repo, configs),
                        new UpdateClientApplicationsCommand(clientId, List.of(a.applicationId(), "app_doesnotexist1"))),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");
        assertThat(configs.findByClient(clientId)).as("nothing is written when one id is unknown").isEmpty();
    }

    private static List<String> enabledByApp(String clientId) {
        return configs.findByClient(clientId).stream().filter(ClientConfig::enabled).map(ClientConfig::applicationId).toList();
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void listFiltersCombineAndResultsAreOrderedByCode() {
        var b = created("applistb", "B");
        var a = created("applista", "A");
        var i = runAsAnchor(CreateApplication.of(repo), new CreateCommand(code("applisti"), "I", "INTEGRATION", null, null, null, null, null, null));
        runAsAnchor(DeactivateApplication.of(repo), new DeactivateCommand(b.applicationId()));

        var all = repo.findWithFilters(new ListFilter(null, null)).stream().map(Application::id).toList();
        assertThat(all).containsSubsequence(a.applicationId(), b.applicationId(), i.applicationId());

        assertThat(repo.findWithFilters(new ListFilter(ApplicationType.INTEGRATION, null))).extracting(Application::id)
                .contains(i.applicationId()).doesNotContain(a.applicationId(), b.applicationId());
        assertThat(repo.findWithFilters(new ListFilter(null, true))).extracting(Application::id)
                .contains(a.applicationId(), i.applicationId()).doesNotContain(b.applicationId());
        assertThat(repo.findWithFilters(new ListFilter(null, false))).extracting(Application::id)
                .contains(b.applicationId()).doesNotContain(a.applicationId());
        assertThat(repo.findWithFilters(new ListFilter(ApplicationType.APPLICATION, false))).extracting(Application::id)
                .contains(b.applicationId()).doesNotContain(a.applicationId(), i.applicationId());
        assertThat(repo.findByCode(code("applista"))).get().extracting(Application::id).isEqualTo(a.applicationId());
        assertThat(repo.findByCode(code("nope"))).isEmpty();
    }
}
