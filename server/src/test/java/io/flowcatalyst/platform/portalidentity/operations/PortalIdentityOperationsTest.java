package io.flowcatalyst.platform.portalidentity.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.portalidentity.PortalIdentityStatus;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityDeleted;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityEnsured;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityStatusSet;
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

    private static String testClient(String tag) {
        Client c = Client.create("Portal Ops Test " + tag, ClientIdentifier.parse("pio-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
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
        var ev = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand(clientId, email, "  ", null));

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
        var ev = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo),
                new EnsureCommand(clientId, "jit-" + RUN + "@example.com", null, "JIT"));
        assertThat(ev.identitySource()).isEqualTo("JIT");
        assertThat(repo.findById(ev.identityId()).orElseThrow().source()).isEqualTo(PortalIdentitySource.JIT);
    }

    @Test
    void ensureOnAnExistingRowReactivatesAndOverridesNameOnlyWhenNonBlank() {
        String clientId = testClient("ensure-re");
        String email = "reensure-" + RUN + "@example.com";
        var first = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand(clientId, email, "First", "INVITE"));
        // Disable it, as an admin would, before the person re-registers / is re-invited.
        runAsAnchor(SetPortalIdentityStatus.of(repo), new SetStatusCommand(first.identityId(), clientId, null, "DISABLED"));
        assertThat(repo.findById(first.identityId()).orElseThrow().status()).isEqualTo(PortalIdentityStatus.DISABLED);

        var reEnsuredBlank = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand(clientId, email, "  ", "INVITE"));
        assertThat(reEnsuredBlank.identityId()).as("same row, not a new one").isEqualTo(first.identityId());
        assertThat(reEnsuredBlank.created()).isFalse();
        PortalIdentity afterBlank = repo.findById(first.identityId()).orElseThrow();
        assertThat(afterBlank.status()).as("re-ensure reactivates").isEqualTo(PortalIdentityStatus.ACTIVE);
        assertThat(afterBlank.name()).as("blank candidate name keeps the existing name").isEqualTo("First");

        runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand(clientId, email, "Second", "INVITE"));
        assertThat(repo.findById(first.identityId()).orElseThrow().name()).isEqualTo("Second");

        assertThat(eventsFor(first.identityId(), PortalIdentityEvents.ENSURED)).hasSize(3);
    }

    @Test
    void ensureRejectsMissingOrMalformedFieldsAndAnUnknownClient() {
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand("", "a@b.com", null, null)),
                UseCaseError.Validation.class, "CLIENT_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand("clt_x", "", null, null)),
                UseCaseError.Validation.class, "EMAIL_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand("clt_x", "@nolocalpart.com", null, null)),
                UseCaseError.Validation.class, "EMAIL_INVALID");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand("clt_x", "trailing@", null, null)),
                UseCaseError.Validation.class, "EMAIL_INVALID");
        assertUseCaseError(() -> runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo), new EnsureCommand("clt_doesnotexist1", "a@b.com", null, null)),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
    }

    // ── SetStatus ──────────────────────────────────────────────────────────

    @Test
    void setStatusActivatesAndDeactivatesEmittingTheEvent() {
        String clientId = testClient("status");
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo),
                new EnsureCommand(clientId, "status-" + RUN + "@example.com", null, "INVITE"));

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
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo),
                new EnsureCommand(owner, "cross-" + RUN + "@example.com", null, "INVITE"));
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
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo),
                new EnsureCommand(clientId, "delete-" + RUN + "@example.com", null, "INVITE"));

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
        var created = runAsAnchor(EnsurePortalIdentity.of(repo, clientRepo),
                new EnsureCommand(owner, "delcross-" + RUN + "@example.com", null, "INVITE"));

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
}
