package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The portal-app use cases against embedded Postgres (spec `portal-apps.md`
/// §3.3-§3.6): validation, the client-scoped 404 rule, and — for the two
/// `TxOperation` orchestrations (§3.4, §3.6) — that the OAuth client they
/// build/delete is exactly what the spec lists, atomically with the app, and
/// that both aggregates' events + audit rows land together.
@SuppressWarnings("deprecation")
class PortalAppOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ClientRepository clientRepo = new ClientRepository(DS);
    private static final PortalAppRepository portalAppRepo = new PortalAppRepository(DS);
    private static final OAuthClientRepository oauthClientRepo = new OAuthClientRepository(DS, new ApplicationRepository(DS));
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final Optional<Encryption> ENCRYPTION = Optional.of(Encryption.withKey(Encryption.generateKey()));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "usr_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    private static String testClient(String tag) {
        Client c = Client.create("Portal App Ops " + tag, ClientIdentifier.parse("pao-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static PortalApp reload(String id) {
        return portalAppRepo.findById(id).orElseThrow(() -> new AssertionError("portal app " + id + " not found"));
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

    private static Result<Record> eventsFor(String appId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                "platform.portal-app." + appId, type);
    }

    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    private static tools.jackson.databind.JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ── CreatePortalApp (§3.3, single aggregate) ─────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAudit() {
        String clientId = testClient("create");
        var ev = CreatePortalApp.of(portalAppRepo, clientRepo)
                .run(uow, new CreatePortalAppCommand(clientId, "Create-" + RUN, "Create App", "desc"), EC);

        assertThat(ev.portalAppId()).startsWith("pta_");
        assertThat(ev.code()).isEqualTo(("create-" + RUN).toLowerCase(Locale.ROOT));
        assertThat(ev.eventType()).isEqualTo(PortalAppEvents.CREATED);

        var got = reload(ev.portalAppId());
        assertThat(got.name()).isEqualTo("Create App");
        assertThat(got.description()).isEqualTo("desc");
        assertThat(got.active()).isTrue();

        var events = eventsFor(ev.portalAppId(), PortalAppEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(PortalAppEvents.SOURCE);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("portalAppId").asText()).isEqualTo(ev.portalAppId());
        assertThat(data.get("clientId").asText()).isEqualTo(clientId);
        assertThat(data.get("code").asText()).isEqualTo(got.code());
        assertThat(data.get("name").asText()).isEqualTo("Create App");

        assertThat(auditsFor(ev.portalAppId(), "CreatePortalAppCommand")).hasSize(1);
    }

    @Test
    void createRejectsBlankFieldsAnInvalidCodeAndAMissingClient() {
        assertUseCaseError(() -> CreatePortalApp.of(portalAppRepo, clientRepo)
                        .run(uow, new CreatePortalAppCommand(" ", "code", "Name", null), EC),
                UseCaseError.Validation.class, "CLIENT_ID_REQUIRED");
        assertUseCaseError(() -> CreatePortalApp.of(portalAppRepo, clientRepo)
                        .run(uow, new CreatePortalAppCommand("clt_x", "-leading-dash", "Name", null), EC),
                UseCaseError.Validation.class, "CODE_INVALID");
        assertUseCaseError(() -> CreatePortalApp.of(portalAppRepo, clientRepo)
                        .run(uow, new CreatePortalAppCommand("clt_x", "code", " ", null), EC),
                UseCaseError.Validation.class, "NAME_REQUIRED");
        assertUseCaseError(() -> CreatePortalApp.of(portalAppRepo, clientRepo)
                        .run(uow, new CreatePortalAppCommand("clt_doesnotexist1", "code-" + RUN, "Name", null), EC),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
    }

    @Test
    void createRejectsADuplicateCodeForTheSameClientButAllowsItForAnother() {
        String clientId = testClient("dupcode");
        String otherClientId = testClient("dupcode-other");
        String code = "dup-" + RUN;
        CreatePortalApp.of(portalAppRepo, clientRepo).run(uow, new CreatePortalAppCommand(clientId, code, "First", null), EC);

        assertUseCaseError(() -> CreatePortalApp.of(portalAppRepo, clientRepo)
                        .run(uow, new CreatePortalAppCommand(clientId, code.toUpperCase(Locale.ROOT), "Second", null), EC),
                UseCaseError.Conflict.class, "CODE_EXISTS");

        // Same code, different client: fine — uniqueness is per-client.
        var onOther = CreatePortalApp.of(portalAppRepo, clientRepo)
                .run(uow, new CreatePortalAppCommand(otherClientId, code, "Third", null), EC);
        assertThat(onOther.clientId()).isEqualTo(otherClientId);
    }

    // ── CreatePortalAppWithOAuthClient (§3.4, ONE transaction) ───────────────

    @Test
    void createWithOAuthClientBuildsExactlyTheSpecShapeForAConfidentialClient() {
        String clientId = testClient("orch-conf");
        var result = CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "orch-conf-" + RUN, "Orch Conf",
                        null, List.of(" https://a.example/cb ", "", "https://b.example/cb"), null), EC);

        assertThat(result.clientType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(result.clientSecret()).as("plaintext disclosed once").isNotBlank();

        PortalApp app = reload(result.appId());
        OAuthClient oc = oauthClientRepo.findById(result.oauthClientRowId()).orElseThrow();
        assertThat(oc.clientId()).isEqualTo(result.oauthClientId());
        assertThat(oc.clientName()).isEqualTo(app.name() + " (portal)");
        assertThat(oc.clientType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(oc.redirectUris()).as("trimmed, blank entries dropped")
                .containsExactly("https://a.example/cb", "https://b.example/cb");
        assertThat(oc.grantTypes()).as("never refresh_token — mutant guard").containsExactly("authorization_code");
        assertThat(oc.defaultScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(oc.pkceRequired()).as("PKCE always on — mutant guard").isTrue();
        assertThat(oc.apiAccess()).isFalse();
        assertThat(oc.portalClientId()).isEqualTo(app.clientId());
        assertThat(oc.portalAppId()).isEqualTo(app.id());
        assertThat(oc.secretRef()).as("keyed-hash, not reversible").startsWith("hashed:v1:");
        assertThat(oc.acceptsSecret(result.clientSecret(), java.time.Instant.now(),
                (ref, provided) -> ENCRYPTION.get().verifySecret(ref, provided) instanceof Encryption.SecretVerification.Matched))
                .as("the disclosed plaintext matches the stored hash").isTrue();

        // Both aggregates' events + audits landed together (spec §3.4 step 4).
        assertThat(eventsFor(app.id(), PortalAppEvents.CREATED)).hasSize(1);
        assertThat(auditsFor(app.id(), "CreatePortalAppWithOAuthClientCommand")).hasSize(1);
        var ocEvents = DB.fetch("SELECT 1 FROM msg_events WHERE subject = ? AND type = ?",
                "platform.oauthclient." + oc.id(), "platform:admin:oauth-client:created");
        assertThat(ocEvents).as("the orchestration's OAuth-client-created event, unchanged type").hasSize(1);
        assertThat(auditsFor(oc.id(), "CreatePortalAppWithOAuthClientCommand")).hasSize(1);
    }

    @Test
    void createWithOAuthClientPublicHasNoSecretAndCorrectType() {
        String clientId = testClient("orch-pub");
        var result = CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "orch-pub-" + RUN, "Orch Pub",
                        null, null, "PUBLIC"), EC);

        assertThat(result.clientType()).isEqualTo(ClientType.PUBLIC);
        assertThat(result.clientSecret()).as("PUBLIC clients have no secret").isNull();
        OAuthClient oc = oauthClientRepo.findById(result.oauthClientRowId()).orElseThrow();
        assertThat(oc.clientType()).isEqualTo(ClientType.PUBLIC);
        assertThat(oc.secretRef()).isNull();
    }

    @Test
    void createWithOAuthClientRejectsAnInvalidClientTypeOrAWildcardRedirectUri() {
        String clientId = testClient("orch-bad");
        assertUseCaseError(() -> CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                        .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "orch-badtype-" + RUN, "X", null, null, "BOGUS"), EC),
                UseCaseError.Validation.class, "INVALID_CLIENT_TYPE");
        assertUseCaseError(() -> CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                        .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "orch-wild-" + RUN, "X", null,
                                List.of("https://*.example.com/cb"), null), EC),
                UseCaseError.Validation.class, "REDIRECT_URI_INVALID");
        assertUseCaseError(() -> CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                        .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "orch-scheme-" + RUN, "X", null,
                                List.of("ftp://x.example/cb"), null), EC),
                UseCaseError.Validation.class, "REDIRECT_URI_INVALID");
        assertUseCaseError(() -> CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                        .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "orch-frag-" + RUN, "X", null,
                                List.of("https://x.example/cb#frag"), null), EC),
                UseCaseError.Validation.class, "REDIRECT_URI_INVALID");
    }

    /// The transaction really rolls back: a `CODE_EXISTS` conflict (detected
    /// before any write in THIS attempt) must leave the OAuth-client table's
    /// row count for this client completely unchanged — nothing was ever
    /// inserted for the failed attempt. Mutant: moving the code-uniqueness
    /// check after the OAuth-client insert (still inside the tx, it would
    /// still roll back and this test would not catch it) OR outside the
    /// transaction entirely (the real danger — an orphaned, committed OAuth
    /// client with no app) is what this pins: the count before and after the
    /// failed attempt must be identical.
    @Test
    void createWithOAuthClientRollsBackCompletelyOnACodeConflict() {
        String clientId = testClient("orch-rollback");
        String code = "orch-rb-" + RUN;
        var first = CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, code, "First", null,
                        List.of("https://first.example/cb"), "PUBLIC"), EC);

        int oauthClientCountBefore = countOAuthClientsFor(clientId);
        int portalAppCountBefore = portalAppRepo.findByClient(clientId).size();
        assertThat(oauthClientCountBefore).isEqualTo(1);
        assertThat(portalAppCountBefore).isEqualTo(1);

        assertUseCaseError(() -> CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                        .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, code.toUpperCase(Locale.ROOT), "Second",
                                null, List.of("https://second.example/cb"), "PUBLIC"), EC),
                UseCaseError.Conflict.class, "CODE_EXISTS");

        assertThat(countOAuthClientsFor(clientId)).as("no OAuth client leaked out of the rolled-back attempt")
                .isEqualTo(oauthClientCountBefore);
        assertThat(portalAppRepo.findByClient(clientId)).as("no extra portal app either")
                .hasSize(portalAppCountBefore);
        assertThat(reload(first.appId()).id()).isEqualTo(first.appId()); // the original survives untouched
    }

    private static int countOAuthClientsFor(String portalOwnerClientId) {
        return oauthClientRepo.findAll().stream().filter(c -> portalOwnerClientId.equals(c.portalClientId())).toList().size();
    }

    // ── UpdatePortalApp (§3.5) ────────────────────────────────────────────────

    @Test
    void updateAppliesFieldsLeavesCodeAloneAndIsAudited() {
        String clientId = testClient("update");
        var created = CreatePortalApp.of(portalAppRepo, clientRepo)
                .run(uow, new CreatePortalAppCommand(clientId, "update-" + RUN, "Before", null), EC);
        String originalCode = reload(created.portalAppId()).code();

        var ev = UpdatePortalApp.of(portalAppRepo).run(uow,
                new UpdatePortalAppCommand(clientId, created.portalAppId(), "After", "new desc", false), EC);
        assertThat(ev.eventType()).isEqualTo(PortalAppEvents.UPDATED);

        var got = reload(created.portalAppId());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("new desc");
        assertThat(got.active()).isFalse();
        assertThat(got.code()).as("code never changes").isEqualTo(originalCode);

        assertThat(eventsFor(created.portalAppId(), PortalAppEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(created.portalAppId(), "UpdatePortalAppCommand")).hasSize(1);
    }

    @Test
    void updateRejectsMissingIdBlankNameOrAMismatchedClient() {
        String clientId = testClient("update-neg");
        var created = CreatePortalApp.of(portalAppRepo, clientRepo)
                .run(uow, new CreatePortalAppCommand(clientId, "update-neg-" + RUN, "X", null), EC);

        assertUseCaseError(() -> UpdatePortalApp.of(portalAppRepo)
                        .run(uow, new UpdatePortalAppCommand(clientId, " ", "X", null, null), EC),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> UpdatePortalApp.of(portalAppRepo)
                        .run(uow, new UpdatePortalAppCommand(clientId, created.portalAppId(), " ", null, null), EC),
                UseCaseError.Validation.class, "NAME_REQUIRED");
        assertUseCaseError(() -> UpdatePortalApp.of(portalAppRepo)
                        .run(uow, new UpdatePortalAppCommand(clientId, "pta_doesnotexist1", "X", null, null), EC),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");
        // Cross-client hidden: a real id under a DIFFERENT client reads as not-found too.
        String otherClientId = testClient("update-neg-other");
        assertUseCaseError(() -> UpdatePortalApp.of(portalAppRepo)
                        .run(uow, new UpdatePortalAppCommand(otherClientId, created.portalAppId(), "X", null, null), EC),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");
    }

    // ── DeletePortalApp (§3.6, ONE transaction) ──────────────────────────────

    @Test
    void deleteRemovesTheAppAndEveryLinkedOAuthClientNotJustUnlinksThem() {
        String clientId = testClient("delete");
        var withOc = CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "delete-" + RUN, "ToDelete", null, null, "PUBLIC"), EC);
        String oauthRowId = withOc.oauthClientRowId();
        String oauthClientId = withOc.oauthClientId();

        var result = DeletePortalApp.of(portalAppRepo, oauthClientRepo)
                .run(uow, new DeletePortalAppCommand(clientId, withOc.appId()), EC);

        assertThat(result.deletedOAuthClientIds()).containsExactly(oauthClientId);
        assertThat(portalAppRepo.findById(withOc.appId())).as("the app row is gone").isEmpty();
        assertThat(oauthClientRepo.findById(oauthRowId))
                .as("the OAuth client is DELETED, not merely unlinked — an unlinked portal client "
                        + "would silently become a legacy client-wide portal (spec §3.6)")
                .isEmpty();

        assertThat(eventsFor(withOc.appId(), PortalAppEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(withOc.appId(), "DeletePortalAppCommand")).hasSize(1);
        var ocDeletedEvents = DB.fetch("SELECT 1 FROM msg_events WHERE subject = ? AND type = ?",
                "platform.oauthclient." + oauthRowId, "platform:admin:oauth-client:deleted");
        assertThat(ocDeletedEvents).hasSize(1);
        assertThat(auditsFor(oauthRowId, "DeletePortalAppCommand")).hasSize(1);
    }

    @Test
    void deleteLeavesOtherAppsAndClientsUntouched() {
        String clientId = testClient("delete-scoped");
        var appA = CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "delA-" + RUN, "A", null, null, "PUBLIC"), EC);
        var appB = CreatePortalAppWithOAuthClient.of(portalAppRepo, clientRepo, oauthClientRepo, ENCRYPTION)
                .run(uow, new CreatePortalAppWithOAuthClientCommand(clientId, "delB-" + RUN, "B", null, null, "PUBLIC"), EC);

        DeletePortalApp.of(portalAppRepo, oauthClientRepo).run(uow, new DeletePortalAppCommand(clientId, appB.appId()), EC);

        assertThat(portalAppRepo.findById(appA.appId())).as("app A survives").isPresent();
        assertThat(oauthClientRepo.findById(appA.oauthClientRowId())).as("A's OAuth client survives").isPresent();
        assertThat(portalAppRepo.findById(appB.appId())).isEmpty();
        assertThat(oauthClientRepo.findById(appB.oauthClientRowId())).isEmpty();
    }

    @Test
    void deleteOfAnAppWithNoOAuthClientsReturnsAnEmptyDeletedList() {
        String clientId = testClient("delete-none");
        var created = CreatePortalApp.of(portalAppRepo, clientRepo)
                .run(uow, new CreatePortalAppCommand(clientId, "delnone-" + RUN, "NoClient", null), EC);

        var result = DeletePortalApp.of(portalAppRepo, oauthClientRepo)
                .run(uow, new DeletePortalAppCommand(clientId, created.portalAppId()), EC);
        assertThat(result.deletedOAuthClientIds()).isEmpty();
        assertThat(portalAppRepo.findById(created.portalAppId())).isEmpty();
    }

    @Test
    void deleteRejectsMissingIdOrRow() {
        assertUseCaseError(() -> DeletePortalApp.of(portalAppRepo, oauthClientRepo)
                        .run(uow, new DeletePortalAppCommand(null, " "), EC),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> DeletePortalApp.of(portalAppRepo, oauthClientRepo)
                        .run(uow, new DeletePortalAppCommand(null, "pta_doesnotexist1"), EC),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");
    }
}
