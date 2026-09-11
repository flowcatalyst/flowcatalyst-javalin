package io.flowcatalyst.platform.portalidentity.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppCode;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrant;
import io.flowcatalyst.platform.portalidentity.PortalAppGrantSource;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.portalidentity.PortalIdentityStatus;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityDeleted;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityEnsured;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityStatusSet;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityAppGranted;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityAppRevoked;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
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

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The portal-identity use cases against the embedded Postgres (spec
/// `auth-identity.md` §5.7): events + audit rows through the `UnitOfWork`
/// envelope, and the cross-client-hidden 404 rule on `SetStatus` /
/// `Delete`. The pure rules are covered by `PortalIdentityTest`; the SQL
/// upsert guarantee by `PortalIdentityRepositoryTest`.
@SuppressWarnings("deprecation")
class PortalIdentityOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PortalIdentityRepository repo = new PortalIdentityRepository(DS);
    private static final ClientRepository clientRepo = new ClientRepository(DS);
    private static final PortalAppRepository portalAppRepo = new PortalAppRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static <C, R> R runTxAsAnchor(TxOperation<C, R> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static String testClient(String tag) {
        Client c = Client.create("Portal Ops Test " + tag, ClientIdentifier.parse("pio-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static PortalApp testApp(String clientId, String tag) {
        PortalApp a = PortalApp.create(clientId, PortalAppCode.parse(tag + "-" + RUN), "App " + tag, null);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(a, tx.dbTx());
            return null;
        });
        return a;
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

    private static Result<Record> eventsFor(String identityId, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                "platform.portal-identity." + identityId, type);
    }

    private static Result<Record> auditsFor(String identityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                identityId, operation);
    }

    // ── Ensure ─────────────────────────────────────────────────────────────

    @Test
    void ensureOnANewRowCreatesActiveWithNoPasswordAndWritesTheEventAndAudit() {
        String clientId = testClient("ensure-new");
        String email = "  Ensure.New@Example.COM  ";
        var ev = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand(clientId, email, "  ", null, null));

        assertThat(ev.identityId()).startsWith("ptu_");
        assertThat(ev.created()).isTrue();
        assertThat(ev.clientId()).isEqualTo(clientId);
        assertThat(ev.email()).isEqualTo("ensure.new@example.com");
        assertThat(ev.identitySource()).as("no explicit JIT source ⇒ INVITE").isEqualTo("INVITE");
        assertThat(ev.eventType()).isEqualTo(PortalIdentityEvents.ENSURED);
        // Pins the DomainEvent#source() vs. this aggregate's own "source" field
        // distinction (see the class doc on PortalIdentityEnsured): if a
        // record component named `source` ever shadowed the CloudEvents
        // envelope accessor again, `ev.source()` here would come back
        // "INVITE" instead of the envelope source.
        assertThat(ev.source()).isEqualTo(PortalIdentityEvents.SOURCE);
        assertThat(ev.messageGroup()).isEqualTo("platform:portal-identity:" + ev.identityId());

        PortalIdentity reloaded = repo.findById(ev.identityId()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(PortalIdentityStatus.ACTIVE);
        assertThat(reloaded.passwordHash()).isNull();
        assertThat(reloaded.name()).as("blank name ⇒ null").isNull();

        var events = eventsFor(ev.identityId(), PortalIdentityEvents.ENSURED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).as("the STORED msg_events.source column, not just the in-memory accessor")
                .isEqualTo(PortalIdentityEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:portal-identity:" + ev.identityId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("identityId").asText()).isEqualTo(ev.identityId());
        assertThat(data.get("created").asBoolean()).isTrue();
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("identityId", "clientId", "email", "created", "source");

        var expectedEntityType = EventConventions.extractAggregateType("platform.portal-identity." + ev.identityId());
        var audits = auditsFor(ev.identityId(), "EnsureCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo(expectedEntityType);
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
    }

    @Test
    void ensureWithJitSourceIsRecordedAsJit() {
        String clientId = testClient("ensure-jit");
        var ev = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "jit-" + RUN + "@example.com", null, "JIT", null));
        assertThat(ev.identitySource()).isEqualTo("JIT");
        assertThat(repo.findById(ev.identityId()).orElseThrow().source()).isEqualTo(PortalIdentitySource.JIT);
    }

    @Test
    void ensureOnAnExistingRowReactivatesAndOverridesNameOnlyWhenNonBlank() {
        String clientId = testClient("ensure-re");
        String email = "reensure-" + RUN + "@example.com";
        var first = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand(clientId, email, "First", "INVITE", null));
        // Disable it, as an admin would, before the person re-registers / is re-invited.
        runAsAnchor(SetPortalIdentityStatus.of(repo), new SetStatusCommand(first.identityId(), clientId, null, "DISABLED"));
        assertThat(repo.findById(first.identityId()).orElseThrow().status()).isEqualTo(PortalIdentityStatus.DISABLED);

        var reEnsuredBlank = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand(clientId, email, "  ", "INVITE", null));
        assertThat(reEnsuredBlank.identityId()).as("same row, not a new one").isEqualTo(first.identityId());
        assertThat(reEnsuredBlank.created()).isFalse();
        PortalIdentity afterBlank = repo.findById(first.identityId()).orElseThrow();
        assertThat(afterBlank.status()).as("re-ensure reactivates").isEqualTo(PortalIdentityStatus.ACTIVE);
        assertThat(afterBlank.name()).as("blank candidate name keeps the existing name").isEqualTo("First");

        runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand(clientId, email, "Second", "INVITE", null));
        assertThat(repo.findById(first.identityId()).orElseThrow().name()).isEqualTo("Second");

        assertThat(eventsFor(first.identityId(), PortalIdentityEvents.ENSURED)).hasSize(3);
    }

    @Test
    void ensureRejectsMissingOrMalformedFieldsAndAnUnknownClient() {
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand("", "a@b.com", null, null, null)),
                UseCaseError.Validation.class, "CLIENT_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand("clt_x", "", null, null, null)),
                UseCaseError.Validation.class, "EMAIL_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand("clt_x", "@nolocalpart.com", null, null, null)),
                UseCaseError.Validation.class, "EMAIL_INVALID");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand("clt_x", "trailing@", null, null, null)),
                UseCaseError.Validation.class, "EMAIL_INVALID");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo), new EnsureCommand("clt_doesnotexist1", "a@b.com", null, null, null)),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
    }

    // ── Ensure with a portal app (spec `portal-apps.md` §3.1) ────────────────

    private static Result<Record> grantsFor(String identityId) {
        return DB.fetch("SELECT identity_id, portal_app_id, source FROM portal_identity_apps WHERE identity_id = ?", identityId);
    }

    @Test
    void ensureWithAnAppGrantsItAndTheEventCarriesAppIdAndCode() {
        String clientId = testClient("ensure-app");
        PortalApp app = testApp(clientId, "ensure-app");
        var ev = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "ensure-app-" + RUN + "@example.com", null, "INVITE", app.id()));

        var grants = grantsFor(ev.identityId());
        assertThat(grants).hasSize(1);
        assertThat(grants.getFirst().get("portal_app_id")).isEqualTo(app.id());
        assertThat(grants.getFirst().get("source")).isEqualTo("INVITE");

        var events = eventsFor(ev.identityId(), PortalIdentityEvents.ENSURED);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("portalAppId").asText()).isEqualTo(app.id());
        assertThat(data.get("portalAppCode").asText()).isEqualTo(app.code());
    }

    @Test
    void ensureWithJitSourceAndAnAppGrantsItWithJitSource() {
        String clientId = testClient("ensure-app-jit");
        PortalApp app = testApp(clientId, "ensure-app-jit");
        var ev = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "ensure-app-jit-" + RUN + "@example.com", null, "JIT", app.id()));

        var grants = grantsFor(ev.identityId());
        assertThat(grants).hasSize(1);
        assertThat(grants.getFirst().get("source")).isEqualTo("JIT");
    }

    /// Mutant: the client-ownership check on `portalAppId` is dropped (any
    /// app found by id is accepted regardless of `clientId`). Asserts BOTH
    /// the 404 AND that no identity row was created — a mutant that throws
    /// for a different reason but still creates the row would slip past an
    /// assertion that only checks the exception.
    @Test
    void ensureWithAnotherClientsAppIsNotFoundAndCreatesNoIdentity() {
        String owner = testClient("ensure-app-owner");
        String intruder = testClient("ensure-app-intruder");
        PortalApp app = testApp(owner, "ensure-app-cross");
        String email = "ensure-app-cross-" + RUN + "@example.com";

        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                        new EnsureCommand(intruder, email, null, "INVITE", app.id())),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");

        assertThat(repo.findByClientAndEmail(intruder, email)).as("no identity row created").isEmpty();
    }

    @Test
    void ensureWithAnInactiveAppIsRejectedAndCreatesNoIdentity() {
        String clientId = testClient("ensure-app-inactive");
        PortalApp app = testApp(clientId, "ensure-app-inactive").update(null, null, false);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(app, tx.dbTx());
            return null;
        });
        String email = "ensure-app-inactive-" + RUN + "@example.com";

        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                        new EnsureCommand(clientId, email, null, "INVITE", app.id())),
                UseCaseError.Validation.class, "PORTAL_APP_INACTIVE");
        assertThat(repo.findByClientAndEmail(clientId, email)).as("no identity row created").isEmpty();
    }

    /// Mutant: `EnsurePortalIdentity` never calls `identity.grant(...)`.
    /// `ensureWithAnAppGrantsItAndTheEventCarriesAppIdAndCode` already kills
    /// the single-grant case (an empty `grantsFor` would fail its
    /// `hasSize(1)`); this pins the second grant is ADDED rather than
    /// replacing the first — a mutant that resets `apps` to a
    /// single-element list before granting would still pass a test that
    /// only checked the newest app.
    @Test
    void ensuringAnExistingIdentityWithASecondAppKeepsTheFirstGrant() {
        String clientId = testClient("ensure-app-second");
        PortalApp appA = testApp(clientId, "ensure-app-second-a");
        PortalApp appB = testApp(clientId, "ensure-app-second-b");
        String email = "ensure-app-second-" + RUN + "@example.com";

        var first = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, email, null, "INVITE", appA.id()));
        runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, email, null, "INVITE", appB.id()));

        assertThat(repo.findById(first.identityId()).orElseThrow().apps())
                .extracting(PortalAppGrant::appId).containsExactlyInAnyOrder(appA.id(), appB.id());
    }

    // ── SetStatus ──────────────────────────────────────────────────────────

    @Test
    void setStatusActivatesAndDeactivatesEmittingTheEvent() {
        String clientId = testClient("status");
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "status-" + RUN + "@example.com", null, "INVITE", null));

        var deactivated = runAsAnchor(SetPortalIdentityStatus.of(repo),
                new SetStatusCommand(created.identityId(), clientId, null, "DISABLED"));
        assertThat(deactivated.status()).isEqualTo("DISABLED");
        assertThat(repo.findById(created.identityId()).orElseThrow().status()).isEqualTo(PortalIdentityStatus.DISABLED);

        runAsAnchor(SetPortalIdentityStatus.of(repo), new SetStatusCommand(created.identityId(), clientId, null, "ACTIVE"));
        assertThat(repo.findById(created.identityId()).orElseThrow().status()).isEqualTo(PortalIdentityStatus.ACTIVE);

        assertThat(eventsFor(created.identityId(), PortalIdentityEvents.STATUS_SET)).hasSize(2);
    }

    /// Mutant 1: `SetStatus` ignores the `clientId` mismatch. Asserts BOTH
    /// the thrown 404 AND that the identity's status is still whatever it
    /// was before the call — a mutant that only drops the throw but still
    /// runs the transition would pass an assertion that checks the
    /// exception alone.
    @Test
    void setStatusTreatsAClientIdMismatchAsNotFoundAndChangesNothing() {
        String owner = testClient("cross-owner");
        String intruder = testClient("cross-intruder");
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(owner, "cross-" + RUN + "@example.com", null, "INVITE", null));
        assertThat(repo.findById(created.identityId()).orElseThrow().status()).isEqualTo(PortalIdentityStatus.ACTIVE);

        assertUseCaseError(() -> runAsAnchor(SetPortalIdentityStatus.of(repo),
                        new SetStatusCommand(created.identityId(), intruder, null, "DISABLED")),
                UseCaseError.NotFound.class, "PortalIdentity_NOT_FOUND");

        assertThat(repo.findById(created.identityId()).orElseThrow().status())
                .as("the cross-client call must not have deactivated the row").isEqualTo(PortalIdentityStatus.ACTIVE);
        assertThat(eventsFor(created.identityId(), PortalIdentityEvents.STATUS_SET)).as("no event either").isEmpty();
    }

    @Test
    void setStatusRejectsAMissingTargetAnUnknownStatusAndAnUnknownId() {
        assertUseCaseError(() -> runAsAnchor(SetPortalIdentityStatus.of(repo), new SetStatusCommand(null, null, null, "ACTIVE")),
                UseCaseError.Validation.class, "TARGET_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(SetPortalIdentityStatus.of(repo), new SetStatusCommand("ptu_x", null, null, "BOGUS")),
                UseCaseError.Validation.class, "STATUS_INVALID");
        assertUseCaseError(() -> runAsAnchor(SetPortalIdentityStatus.of(repo), new SetStatusCommand("ptu_doesnotexist1", null, null, "ACTIVE")),
                UseCaseError.NotFound.class, "PortalIdentity_NOT_FOUND");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndWritesTheEvent() {
        String clientId = testClient("delete");
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "delete-" + RUN + "@example.com", null, "INVITE", null));

        PortalIdentityDeleted ev = runAsAnchor(DeletePortalIdentity.of(repo), new DeleteCommand(clientId, created.identityId()));
        assertThat(ev.identityId()).isEqualTo(created.identityId());
        assertThat(repo.findById(created.identityId())).isEmpty();
        assertThat(eventsFor(created.identityId(), PortalIdentityEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(created.identityId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteTreatsAClientIdMismatchAsNotFoundAndKeepsTheRow() {
        String owner = testClient("del-cross-owner");
        String intruder = testClient("del-cross-intruder");
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(owner, "delcross-" + RUN + "@example.com", null, "INVITE", null));

        assertUseCaseError(() -> runAsAnchor(DeletePortalIdentity.of(repo), new DeleteCommand(intruder, created.identityId())),
                UseCaseError.NotFound.class, "PortalIdentity_NOT_FOUND");
        assertThat(repo.findById(created.identityId())).as("not deleted").isPresent();
    }

    @Test
    void deleteRejectsABlankOrUnknownId() {
        assertUseCaseError(() -> runAsAnchor(DeletePortalIdentity.of(repo), new DeleteCommand(null, "")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeletePortalIdentity.of(repo), new DeleteCommand(null, "ptu_doesnotexist1")),
                UseCaseError.NotFound.class, "PortalIdentity_NOT_FOUND");
    }

    // ── GrantApp / RevokeApp (spec `portal-apps.md` §3.2) ─────────────────────

    @Test
    void grantAddsTheAppWithAdminSourceAndWritesTheEventAndAudit() {
        String clientId = testClient("grant");
        PortalApp app = testApp(clientId, "grant");
        var identity = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "grant-" + RUN + "@example.com", null, "INVITE", null));

        var ev = runAsAnchor(GrantPortalIdentityApp.of(repo, portalAppRepo),
                new GrantPortalIdentityAppCommand(clientId, identity.identityId(), app.id()));
        assertThat(ev.eventType()).isEqualTo(PortalIdentityEvents.APP_GRANTED);
        assertThat(ev.subject()).isEqualTo("platform.portal-identity." + identity.identityId());
        assertThat(ev.messageGroup()).isEqualTo("platform:portal-identity:" + identity.identityId());
        assertThat(ev.portalAppId()).isEqualTo(app.id());
        assertThat(ev.portalAppCode()).isEqualTo(app.code());
        assertThat(ev.grantSource()).isEqualTo("ADMIN");

        var grants = grantsFor(identity.identityId());
        assertThat(grants).hasSize(1);
        assertThat(grants.getFirst().get("source")).isEqualTo("ADMIN");

        var events = eventsFor(identity.identityId(), PortalIdentityEvents.APP_GRANTED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("subject")).isEqualTo("platform.portal-identity." + identity.identityId());
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:portal-identity:" + identity.identityId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("identityId", "clientId", "portalAppId", "portalAppCode", "source");
        assertThat(data.get("portalAppId").asText()).isEqualTo(app.id());
        assertThat(data.get("portalAppCode").asText()).isEqualTo(app.code());
        assertThat(data.get("source").asText()).as("the wire field is `source`, not `grantSource`").isEqualTo("ADMIN");

        assertThat(auditsFor(identity.identityId(), "GrantPortalIdentityAppCommand")).hasSize(1);
    }

    /// Mutant: the operation skips `Plan.save`/event emission when the grant
    /// is already held. Asserts BOTH that a second grant leaves exactly one
    /// row (the aggregate-level idempotency) AND that a second event was
    /// still written (the spec's "always persist and emit") — a mutant that
    /// short-circuits on an already-held grant would pass a test that only
    /// checked the row count.
    @Test
    void grantIsIdempotentAtTheRowLevelButAlwaysPersistsAndEmits() {
        String clientId = testClient("grant-idem");
        PortalApp app = testApp(clientId, "grant-idem");
        var identity = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "grant-idem-" + RUN + "@example.com", null, "INVITE", null));

        runAsAnchor(GrantPortalIdentityApp.of(repo, portalAppRepo),
                new GrantPortalIdentityAppCommand(clientId, identity.identityId(), app.id()));
        runAsAnchor(GrantPortalIdentityApp.of(repo, portalAppRepo),
                new GrantPortalIdentityAppCommand(clientId, identity.identityId(), app.id()));

        assertThat(grantsFor(identity.identityId())).as("still exactly one grant row").hasSize(1);
        assertThat(eventsFor(identity.identityId(), PortalIdentityEvents.APP_GRANTED))
                .as("but the event fired twice").hasSize(2);
    }

    @Test
    void grantRejectsAnUnknownIdentityAnUnknownAppAndACrossClientApp() {
        String clientId = testClient("grant-404");
        String otherClient = testClient("grant-404-other");
        PortalApp app = testApp(clientId, "grant-404");
        PortalApp otherApp = testApp(otherClient, "grant-404-other");
        var identity = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "grant-404-" + RUN + "@example.com", null, "INVITE", null));

        assertUseCaseError(() -> runAsAnchor(GrantPortalIdentityApp.of(repo, portalAppRepo),
                        new GrantPortalIdentityAppCommand(clientId, "ptu_doesnotexist1", app.id())),
                UseCaseError.NotFound.class, "PortalIdentity_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(GrantPortalIdentityApp.of(repo, portalAppRepo),
                        new GrantPortalIdentityAppCommand(clientId, identity.identityId(), "pta_doesnotexist1")),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(GrantPortalIdentityApp.of(repo, portalAppRepo),
                        new GrantPortalIdentityAppCommand(clientId, identity.identityId(), otherApp.id())),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");
        assertThat(grantsFor(identity.identityId())).as("none of the rejected calls granted anything").isEmpty();
    }

    @Test
    void grantAndRevokeRejectBlankTargets() {
        assertUseCaseError(() -> runAsAnchor(GrantPortalIdentityApp.of(repo, portalAppRepo),
                        new GrantPortalIdentityAppCommand("", "ptu_x", "pta_x")),
                UseCaseError.Validation.class, "TARGET_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(RevokePortalIdentityApp.of(repo, portalAppRepo),
                        new RevokePortalIdentityAppCommand("clt_x", "", "pta_x")),
                UseCaseError.Validation.class, "TARGET_REQUIRED");
    }

    @Test
    void revokeRemovesTheGrantAndWritesTheEvent() {
        String clientId = testClient("revoke");
        PortalApp app = testApp(clientId, "revoke");
        var identity = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "revoke-" + RUN + "@example.com", null, "INVITE", app.id()));
        assertThat(grantsFor(identity.identityId())).hasSize(1);

        var ev = runAsAnchor(RevokePortalIdentityApp.of(repo, portalAppRepo),
                new RevokePortalIdentityAppCommand(clientId, identity.identityId(), app.id()));
        assertThat(ev.eventType()).isEqualTo(PortalIdentityEvents.APP_REVOKED);
        assertThat(ev.subject()).isEqualTo("platform.portal-identity." + identity.identityId());
        assertThat(ev.messageGroup()).isEqualTo("platform:portal-identity:" + identity.identityId());
        assertThat(ev.portalAppId()).isEqualTo(app.id());
        assertThat(ev.portalAppCode()).isEqualTo(app.code());

        assertThat(grantsFor(identity.identityId())).as("grant row is gone").isEmpty();
        var events = eventsFor(identity.identityId(), PortalIdentityEvents.APP_REVOKED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("identityId", "clientId", "portalAppId", "portalAppCode");
        assertThat(data.get("portalAppId").asText()).isEqualTo(app.id());
        assertThat(data.get("portalAppCode").asText()).isEqualTo(app.code());
        assertThat(auditsFor(identity.identityId(), "RevokePortalIdentityAppCommand")).hasSize(1);
    }

    /// Mutant: revoke short-circuits (no event, no persist) when the app was
    /// never granted. Asserts the event still fired even though nothing was
    /// removed.
    @Test
    void revokeOfAnUngrantedAppStillPersistsAndEmits() {
        String clientId = testClient("revoke-noop");
        PortalApp app = testApp(clientId, "revoke-noop");
        var identity = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo, portalAppRepo),
                new EnsureCommand(clientId, "revoke-noop-" + RUN + "@example.com", null, "INVITE", null));
        assertThat(grantsFor(identity.identityId())).isEmpty();

        runAsAnchor(RevokePortalIdentityApp.of(repo, portalAppRepo),
                new RevokePortalIdentityAppCommand(clientId, identity.identityId(), app.id()));

        assertThat(grantsFor(identity.identityId())).isEmpty();
        assertThat(eventsFor(identity.identityId(), PortalIdentityEvents.APP_REVOKED))
                .as("the event still fired on the no-op").hasSize(1);
    }

    // ── AssignUnassignedToApp (spec `portal-apps.md` §3.2a, §9 scenario 9) ────

    private static void persistIdentity(PortalIdentity pi) {
        uow.inTransaction(tx -> {
            repo.persist(pi, tx.dbTx());
            return null;
        });
    }

    /// One `app-granted` `msg_events` row AND one `aud_logs` row per
    /// assigned identity, none for the identity that already held a grant
    /// (spec §3.2a; CLAUDE.md testing policy: assert the actual rows, not
    /// just the aggregate response).
    @Test
    void assignsEveryUnassignedIdentityAndWritesOneEventAndAuditRowEach() {
        String clientId = testClient("assign");
        PortalApp appA = testApp(clientId, "assign-a");
        PortalApp appB = testApp(clientId, "assign-b");

        PortalIdentity unassigned1 = PortalIdentity.create(clientId, "assign-1-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persistIdentity(unassigned1);
        PortalIdentity unassigned2Suspended = PortalIdentity.create(clientId, "assign-2-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .deactivate();
        persistIdentity(unassigned2Suspended);
        PortalIdentity alreadyOnB = PortalIdentity.create(clientId, "assign-3-" + RUN + "@example.com", null, PortalIdentitySource.INVITE)
                .grant(appB.id(), PortalAppGrantSource.INVITE);
        persistIdentity(alreadyOnB);

        var result = runTxAsAnchor(AssignUnassignedToApp.of(repo, portalAppRepo),
                new AssignUnassignedToAppCommand(clientId, appA.id()));

        assertThat(result.appId()).isEqualTo(appA.id());
        assertThat(result.appCode()).isEqualTo(appA.code());
        assertThat(result.identityIds()).as("load order created_at, id")
                .containsExactly(unassigned1.id(), unassigned2Suspended.id());

        assertThat(repo.findById(unassigned1.id()).orElseThrow().hasApp(appA.id())).isTrue();
        PortalIdentity reloadedSuspended = repo.findById(unassigned2Suspended.id()).orElseThrow();
        assertThat(reloadedSuspended.hasApp(appA.id())).isTrue();
        assertThat(reloadedSuspended.status()).as("status untouched — still suspended").isEqualTo(PortalIdentityStatus.DISABLED);
        assertThat(repo.findById(alreadyOnB.id()).orElseThrow().hasApp(appA.id()))
                .as("already-assigned identity is left alone").isFalse();

        assertThat(eventsFor(unassigned1.id(), PortalIdentityEvents.APP_GRANTED)).hasSize(1);
        assertThat(eventsFor(unassigned2Suspended.id(), PortalIdentityEvents.APP_GRANTED)).hasSize(1);
        assertThat(eventsFor(alreadyOnB.id(), PortalIdentityEvents.APP_GRANTED))
                .as("no event for an identity that was never touched").isEmpty();

        assertThat(auditsFor(unassigned1.id(), "AssignUnassignedToAppCommand")).hasSize(1);
        assertThat(auditsFor(unassigned2Suspended.id(), "AssignUnassignedToAppCommand")).hasSize(1);
        assertThat(auditsFor(alreadyOnB.id(), "AssignUnassignedToAppCommand")).isEmpty();
    }

    /// A second run finds no unassigned identities left — mutant: dropping
    /// the `NOT EXISTS` load (assigning everyone) would instead re-grant the
    /// B user and report a non-empty result here.
    @Test
    void aSecondRunAssignsNobody() {
        String clientId = testClient("assign-twice");
        PortalApp app = testApp(clientId, "assign-twice");
        PortalIdentity unassigned = PortalIdentity.create(clientId, "assign-twice-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persistIdentity(unassigned);

        var first = runTxAsAnchor(AssignUnassignedToApp.of(repo, portalAppRepo), new AssignUnassignedToAppCommand(clientId, app.id()));
        assertThat(first.identityIds()).containsExactly(unassigned.id());

        var second = runTxAsAnchor(AssignUnassignedToApp.of(repo, portalAppRepo), new AssignUnassignedToAppCommand(clientId, app.id()));
        assertThat(second.identityIds()).as("nobody left unassigned").isEmpty();
    }

    @Test
    void assignRejectsAnInactiveAppAndGrantsNobody() {
        String clientId = testClient("assign-inactive");
        PortalApp app = testApp(clientId, "assign-inactive").update(null, null, false);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(app, tx.dbTx());
            return null;
        });
        PortalIdentity unassigned = PortalIdentity.create(clientId, "assign-inactive-" + RUN + "@example.com", null, PortalIdentitySource.INVITE);
        persistIdentity(unassigned);

        assertUseCaseError(() -> runTxAsAnchor(AssignUnassignedToApp.of(repo, portalAppRepo),
                        new AssignUnassignedToAppCommand(clientId, app.id())),
                UseCaseError.Validation.class, "PORTAL_APP_INACTIVE");
        assertThat(repo.findById(unassigned.id()).orElseThrow().hasApp(app.id())).isFalse();
    }

    @Test
    void assignRejectsAnUnknownOrCrossClientAppAndBlankTargets() {
        String clientId = testClient("assign-404");
        String otherClient = testClient("assign-404-other");
        PortalApp otherApp = testApp(otherClient, "assign-404-other");

        assertUseCaseError(() -> runTxAsAnchor(AssignUnassignedToApp.of(repo, portalAppRepo),
                        new AssignUnassignedToAppCommand(clientId, "pta_doesnotexist1")),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");
        assertUseCaseError(() -> runTxAsAnchor(AssignUnassignedToApp.of(repo, portalAppRepo),
                        new AssignUnassignedToAppCommand(clientId, otherApp.id())),
                UseCaseError.NotFound.class, "PortalApp_NOT_FOUND");
        assertUseCaseError(() -> runTxAsAnchor(AssignUnassignedToApp.of(repo, portalAppRepo),
                        new AssignUnassignedToAppCommand("", "pta_x")),
                UseCaseError.Validation.class, "TARGET_REQUIRED");
    }
}
