package io.flowcatalyst.platform.resetapproval.operations;

import io.flowcatalyst.platform.mail.Mail;
import io.flowcatalyst.platform.mail.MailService;
import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.resetapproval.ResetApprovalQueue;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRepository;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRequest;
import io.flowcatalyst.platform.resetapproval.ResetApprovalStatus;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
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
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_RESET_APPROVAL_REQUESTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `QueueResetApproval` / `DecideResetApproval` through the envelope, and
/// the real queue seam ([ResetApprovalQueue]) built on top of them (spec
/// §8.6): events + audit rows land together, the queue's two no-op checks
/// (no client, already pending) really do nothing, and a queued request
/// notifies every client-admin e-mail with the review link.
class ResetApprovalOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ResetApprovalRepository repo = new ResetApprovalRepository(DS);
    private static final PrincipalRepository principals = new PrincipalRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final ExecutionContext SYSTEM_EC = ExecutionContext.of("system");
    private static final String BASE_URL = "https://app.example.test";

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static String seedPrincipal(String clientId) {
        String email = "rar-op-" + UUID.randomUUID() + "@example.test";
        var p = Principal.newUser(EmailAddress.parse(email), UserScope.CLIENT).withClientId(clientId);
        persist(p);
        return p.id();
    }

    private static Principal seedClientAdmin(String clientId) {
        String email = "rar-admin-" + UUID.randomUUID() + "@example.test";
        var p = Principal.newUser(EmailAddress.parse(email), UserScope.CLIENT).withClientId(clientId)
                .withRoles(List.of(new RoleAssignment("platform:client-admin", RoleAssignment.ADMIN_ASSIGNED, Instant.now())));
        persistWithRoles(p);
        return p;
    }

    private static void persist(Principal p) {
        try (Connection c = DS.getConnection()) {
            c.setAutoCommit(true);
            principals.persist(p, DbTx.wrapForBootstrap(c));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void persistWithRoles(Principal p) {
        try (Connection c = DS.getConnection()) {
            c.setAutoCommit(true);
            principals.withRoles().persist(p, DbTx.wrapForBootstrap(c));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ResetApprovalRequest reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("reset approval request " + id + " not found"));
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

    private static Result<Record> eventsFor(String requestId, String type) {
        return DB.fetch("SELECT type, subject, source, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                ResetApprovalEvents.subjectFor(requestId), type);
    }

    private static Result<Record> auditsFor(String requestId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                requestId, operation);
    }

    // ── QueueResetApproval (through the envelope) ───────────────────────────

    @Test
    void queueInsertsAPendingRowAndEmitsQueuedWithAnAuditRow() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);

        var event = QueueResetApproval.of(repo).run(uow, new QueueCommand(principalId, clientId), SYSTEM_EC);

        assertThat(event.requestId()).startsWith("rar_");
        assertThat(event.userId()).isEqualTo(principalId);
        assertThat(event.clientId()).isEqualTo(clientId);
        assertThat(event.eventType()).isEqualTo(ResetApprovalEvents.QUEUED);
        assertThat(event.subject()).isEqualTo(ResetApprovalEvents.subjectFor(event.requestId()));

        var row = reload(event.requestId());
        assertThat(row.status()).isEqualTo(ResetApprovalStatus.PENDING);
        assertThat(row.reset2fa()).isTrue();

        assertThat(eventsFor(event.requestId(), ResetApprovalEvents.QUEUED)).hasSize(1);
        var audits = auditsFor(event.requestId(), "QueueCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("principal_id")).isEqualTo("system");
    }

    @Test
    void queueRejectsABlankPrincipalId() {
        assertUseCaseError(() -> QueueResetApproval.of(repo).run(uow, new QueueCommand(" ", "cli_x"), SYSTEM_EC),
                UseCaseError.Validation.class, "PRINCIPAL_ID_REQUIRED");
    }

    // ── DecideResetApproval (through the envelope) ──────────────────────────

    @Test
    void decideApprovedEmitsDecidedWithAnAuditRowAndPersistsTheAdminAndNote() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);
        var seeded = QueueResetApproval.of(repo).run(uow, new QueueCommand(principalId, clientId), SYSTEM_EC);

        var admin = ExecutionContext.of("prn_admin_" + RUN);
        var event = DecideResetApproval.of(repo)
                .run(uow, new DecideCommand(seeded.requestId(), ResetApprovalStatus.APPROVED, "checks out"), admin);

        assertThat(event.status()).isEqualTo("APPROVED");
        assertThat(event.decidedBy()).isEqualTo("prn_admin_" + RUN);
        assertThat(event.userId()).isEqualTo(principalId);
        assertThat(event.eventType()).isEqualTo(ResetApprovalEvents.DECIDED);

        var row = reload(seeded.requestId());
        assertThat(row.status()).isEqualTo(ResetApprovalStatus.APPROVED);
        assertThat(row.decidedBy()).isEqualTo("prn_admin_" + RUN);
        assertThat(row.note()).isEqualTo("checks out");

        assertThat(eventsFor(seeded.requestId(), ResetApprovalEvents.DECIDED)).hasSize(1);
        assertThat(auditsFor(seeded.requestId(), "DecideCommand")).hasSize(1);
    }

    @Test
    void decideRejectsAMissingRow() {
        assertUseCaseError(() -> DecideResetApproval.of(repo)
                        .run(uow, new DecideCommand("rar_doesnotexist1", ResetApprovalStatus.APPROVED, null), SYSTEM_EC),
                UseCaseError.NotFound.class, "ResetApprovalRequest_NOT_FOUND");
    }

    /// The envelope-level pin of the same guard `ResetApprovalRepositoryTest`
    /// exercises directly: a second decision is rejected and emits nothing.
    @Test
    void decideRejectsASecondDecisionOnTheSameRow() {
        String clientId = EntityType.CLIENT.generate();
        var seeded = QueueResetApproval.of(repo).run(uow, new QueueCommand(seedPrincipal(clientId), clientId), SYSTEM_EC);
        DecideResetApproval.of(repo).run(uow, new DecideCommand(seeded.requestId(), ResetApprovalStatus.APPROVED, null), SYSTEM_EC);

        assertUseCaseError(() -> DecideResetApproval.of(repo)
                        .run(uow, new DecideCommand(seeded.requestId(), ResetApprovalStatus.DENIED, null), SYSTEM_EC),
                UseCaseError.Validation.class, "ALREADY_DECIDED");
        assertThat(eventsFor(seeded.requestId(), ResetApprovalEvents.DECIDED)).as("the rejected second decision emits nothing").hasSize(1);
    }

    // ── ResetApprovalQueue (the seam PasswordResetApi calls) ────────────────

    private static MailService capturing(List<Mail> sent) {
        return sent::add;
    }

    @Test
    void queueSkipsAPrincipalWithNoClient() {
        List<Mail> sent = new CopyOnWriteArrayList<>();
        var queue = new ResetApprovalQueue(repo, principals, new Notifications(capturing(sent), () -> "Test"), uow, BASE_URL);
        // A real row (not just an in-memory object) so a mutant that drops the
        // guard actually inserts a request instead of failing on the
        // `principal_id` foreign key — a false pass the FK would otherwise mask.
        var anchor = Principal.newService(EntityType.SERVICE_ACCOUNT.generate(), "anchor-svc-" + RUN); // clientId() == null
        persist(anchor);

        queue.queue(anchor);

        assertThat(repo.hasPendingFor(anchor.id())).as("no client ⇒ no approval path").isFalse();
        assertThat(sent).as("nothing to notify").isEmpty();
    }

    @Test
    void queueSkipsADuplicateWhenAnUnexpiredPendingRequestAlreadyExists() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);
        List<Mail> sent = new CopyOnWriteArrayList<>();
        var queue = new ResetApprovalQueue(repo, principals, new Notifications(capturing(sent), () -> "Test"), uow, BASE_URL);
        var principal = principals.findById(principalId).orElseThrow();

        queue.queue(principal);
        assertThat(repo.hasPendingFor(principalId)).isTrue();
        int afterFirst = repo.findPending(Visibility.Everything.INSTANCE).size();

        queue.queue(principal); // second call: must be a no-op

        int afterSecond = repo.findPending(Visibility.Everything.INSTANCE).size();
        assertThat(afterSecond).as("the duplicate must not have inserted a second row").isEqualTo(afterFirst);
    }

    @Test
    void queueNotifiesEveryClientAdminWithTheReviewLink() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);
        Principal admin1 = seedClientAdmin(clientId);
        Principal admin2 = seedClientAdmin(clientId);
        // An admin for a DIFFERENT client must not be notified.
        seedClientAdmin(EntityType.CLIENT.generate());

        List<Mail> sent = new CopyOnWriteArrayList<>();
        var queue = new ResetApprovalQueue(repo, principals, new Notifications(capturing(sent), () -> "Test"), uow, BASE_URL);
        var principal = principals.findById(principalId).orElseThrow();

        queue.queue(principal);

        assertThat(sent).extracting(Mail::to).containsExactlyInAnyOrder(admin1.email(), admin2.email());
        var pending = repo.findPending(new Visibility.Tenants(List.of(clientId)));
        assertThat(pending).hasSize(1);
        String link = BASE_URL + "/authentication/reset-approvals/" + pending.getFirst().id();
        assertThat(sent).allSatisfy(m -> assertThat(m.html()).contains(link));
    }
}
