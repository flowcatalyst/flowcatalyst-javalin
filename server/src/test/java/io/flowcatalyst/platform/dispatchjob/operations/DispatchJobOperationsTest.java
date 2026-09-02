package io.flowcatalyst.platform.dispatchjob.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents.DispatchJobCancelled;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents.DispatchJobCompleted;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents.DispatchJobsRequeued;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The requeue use case through the envelope against the embedded Postgres
/// (spec §5–6): validation, the per-row scope filter, the row reset, and the
/// guarantee that the reset lands together with one `msg_events` +
/// `aud_logs` pair per job plus the rollup pair. Rows are seeded directly
/// (no writer exists yet); every test owns its ids.
@SuppressWarnings("deprecation")
class DispatchJobOperationsTest {

    private static final DispatchJobRepository repo = new DispatchJobRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final String CLIENT_A = "cli_opa" + RUN;
    private static final String CLIENT_B = "cli_opb" + RUN;
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final AuthContext CLIENT_A_OPERATOR = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT,
            "ops@a.io", List.of(CLIENT_A), List.of(), List.of(), false, List.of("platform:messaging:dispatch-job:view"));

    // ── Fixture ────────────────────────────────────────────────────────────

    private static DispatchJobsRequeued requeueAs(AuthContext ac, String... ids) {
        return Auth.runAs(ac, () -> RequeueDispatchJobs.of(repo)
                .run(uow, new RequeueCommand(Arrays.asList(ids)), ExecutionContext.of(ac.principalId())));
    }

    private static String failedJob(String clientId) {
        return seedWriteRow(Seed.of(code("op")).withClientId(clientId).withMessageGroup("grp" + RUN).failed(3, "boom"));
    }

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static DispatchJob reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("job " + id + " not found"));
    }

    /// `msg_events` rows of one type on one subject.
    private static Result<Record> eventsOn(String subject, String type) {
        return DB.fetch("SELECT type, subject, source, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                subject, type);
    }

    /// `aud_logs` rows for one entity and command.
    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    // ── Requeue (spec §6) ──────────────────────────────────────────────────

    @Test
    void requeueResetsTheRowsAndWritesPerJobAndRollupEventsWithAudits() {
        String a = failedJob(CLIENT_A);
        String b = failedJob(null);

        DispatchJobsRequeued rollup = requeueAs(ANCHOR, a, b);

        assertThat(rollup.requeued()).isEqualTo(2);
        assertThat(rollup.dispatchJobIds()).containsExactly(a, b);
        for (String id : List.of(a, b)) {
            DispatchJob j = reload(id);
            assertThat(j.status()).isEqualTo(DispatchJobStatus.PENDING);
            assertThat(j.attemptCount()).isZero();
            assertThat(j.scheduledFor()).isNull();
            assertThat(j.completedAt()).isNull();
            assertThat(j.durationMillis()).isNull();
            assertThat(j.lastError()).isNull();

            var events = eventsOn(DispatchJobEvents.subjectFor(id), DispatchJobEvents.REQUEUED);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().get("source")).isEqualTo("platform:admin");
            JsonNode data = json(events.getFirst().get("data", String.class));
            assertThat(data.get("dispatchJobId").asText()).isEqualTo(id);
            assertThat(data.get("previousStatus").asText()).isEqualTo("FAILED");
            assertThat(data.get("messageGroup").asText()).isEqualTo("grp" + RUN);
            assertThat(data.get("code").asText()).isEqualTo(code("op"));
            assertThat(events.getFirst().get("deduplication_id", String.class)).startsWith(DispatchJobEvents.REQUEUED + "-");

            var audits = auditsFor(id, "RequeueCommand");
            assertThat(audits).hasSize(1);
            assertThat(audits.getFirst().get("entity_type")).isEqualTo("Dispatchjob");
            assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
            assertThat(audits.getFirst().get("operation_json", String.class)).contains(a).contains(b);
        }

        var rollups = eventsOn(DispatchJobEvents.batchSubjectFor(rollup.batchId()), DispatchJobEvents.BATCH_REQUEUED);
        assertThat(rollups).hasSize(1);
        assertThat(rollup.batchId()).hasSize(13);
        JsonNode rollupData = json(rollups.getFirst().get("data", String.class));
        assertThat(rollupData.get("requeued").asInt()).isEqualTo(2);
        assertThat(rollupData.get("batchId").asText()).isEqualTo(rollup.batchId());
        assertThat(rollupData.get("dispatchJobIds")).extracting(JsonNode::asText).containsExactly(a, b);
        assertThat(auditsFor(rollup.batchId(), "RequeueCommand")).hasSize(1)
                .first().satisfies(r -> assertThat(r.get("entity_type")).isEqualTo("Dispatchjobs"));
    }

    @Test
    void unknownIdsAreSkippedSilentlyAndDuplicatesCountOnce() {
        String a = failedJob(null);
        DispatchJobsRequeued rollup = requeueAs(ANCHOR, a, Tsid.generate(), a);
        assertThat(rollup.requeued()).isEqualTo(1);
        assertThat(rollup.dispatchJobIds()).containsExactly(a);
        assertThat(eventsOn(DispatchJobEvents.subjectFor(a), DispatchJobEvents.REQUEUED)).hasSize(1);
    }

    @Test
    void scopedOperatorResetsOnlyJobsOfItsOwnClients() {
        String own = failedJob(CLIENT_A);
        String other = failedJob(CLIENT_B);
        String platform = failedJob(null);

        DispatchJobsRequeued rollup = requeueAs(CLIENT_A_OPERATOR, own, other, platform);

        assertThat(rollup.dispatchJobIds()).containsExactly(own);
        assertThat(reload(own).status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(reload(other).status()).as("another tenant's job is untouched").isEqualTo(DispatchJobStatus.FAILED);
        assertThat(reload(platform).status()).as("platform-scoped job is anchor-only").isEqualTo(DispatchJobStatus.FAILED);
        assertThat(eventsOn(DispatchJobEvents.subjectFor(other), DispatchJobEvents.REQUEUED)).isEmpty();
    }

    @Test
    void emptyIdsIsANoOpThatStillRecordsTheRollup() {
        DispatchJobsRequeued rollup = requeueAs(ANCHOR);
        assertThat(rollup.requeued()).isZero();
        assertThat(eventsOn(DispatchJobEvents.batchSubjectFor(rollup.batchId()), DispatchJobEvents.BATCH_REQUEUED)).hasSize(1);
    }

    @Test
    void missingIdsIsRejectedBeforeAnythingIsTouched() {
        assertThatThrownBy(() -> Auth.runAs(ANCHOR, () -> RequeueDispatchJobs.of(repo)
                .run(uow, new RequeueCommand(null), ExecutionContext.of(PRINCIPAL))))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("IDS_REQUIRED");
                });
    }

    @Test
    void requeueIsTotalSoACompletedJobIsResetToo() {
        String done = seedWriteRow(Seed.of(code("op")).withStatus("COMPLETED"));
        assertThat(requeueAs(ANCHOR, done).requeued()).isEqualTo(1);
        assertThat(reload(done).status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(DB.fetchCount(MSG_DISPATCH_JOBS, MSG_DISPATCH_JOBS.ID.eq(done))).as("upsert, not a second row").isEqualTo(1);
    }

    // ── Cancel / Complete (spec §8) ──────────────────────────────────────────

    @Test
    void cancelFlipsFailedToCancelledStampsCompletedAtAndEmitsTheEventWithAudit() {
        String id = failedJob(CLIENT_A);
        DispatchJobCancelled event = Auth.runAs(ANCHOR, () -> CancelDispatchJob.of(repo)
                .run(uow, new CancelCommand(id), ExecutionContext.of(ANCHOR.principalId())));

        assertThat(event.dispatchJobId()).isEqualTo(id);
        DispatchJob after = reload(id);
        assertThat(after.status()).isEqualTo(DispatchJobStatus.CANCELLED);
        assertThat(after.completedAt()).isNotNull();
        assertThat(after.lastError()).as("evidence of the failure is preserved").isEqualTo("boom");

        var events = eventsOn(DispatchJobEvents.subjectFor(id), DispatchJobEvents.CANCELLED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo("platform:messaging");
        JsonNode data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("dispatchJobId").asText()).isEqualTo(id);

        var audits = auditsFor(id, "CancelCommand");
        assertThat(audits).hasSize(1);
    }

    @Test
    void completeFlipsFailedToCompletedAndEmitsTheEvent() {
        String id = failedJob(null);
        DispatchJobCompleted event = Auth.runAs(ANCHOR, () -> CompleteDispatchJob.of(repo)
                .run(uow, new CompleteCommand(id), ExecutionContext.of(ANCHOR.principalId())));

        assertThat(event.dispatchJobId()).isEqualTo(id);
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);
        assertThat(eventsOn(DispatchJobEvents.subjectFor(id), DispatchJobEvents.COMPLETED)).hasSize(1);
    }

    @Test
    void cancelAndCompleteRejectEveryNonFailedStatusWith409() {
        for (String status : List.of("PENDING", "QUEUED", "PROCESSING", "COMPLETED", "CANCELLED", "EXPIRED")) {
            String id = seedWriteRow(Seed.of(code("op")).withStatus(status));
            assertThatThrownBy(() -> Auth.runAs(ANCHOR, () -> CancelDispatchJob.of(repo)
                    .run(uow, new CancelCommand(id), ExecutionContext.of(ANCHOR.principalId()))))
                    .as("cancel from " + status)
                    .isInstanceOf(UseCaseException.class)
                    .extracting(t -> ((UseCaseException) t).code()).isEqualTo("NOT_FAILED");
            assertThatThrownBy(() -> Auth.runAs(ANCHOR, () -> CompleteDispatchJob.of(repo)
                    .run(uow, new CompleteCommand(id), ExecutionContext.of(ANCHOR.principalId()))))
                    .as("complete from " + status)
                    .isInstanceOf(UseCaseException.class)
                    .extracting(t -> ((UseCaseException) t).code()).isEqualTo("NOT_FAILED");
            assertThat(reload(id).status()).as("rejected — untouched").isEqualTo(DispatchJobStatus.valueOf(status));
        }
    }

    @Test
    void cancelAnswersTheSameNotFoundForAMissingIdAndForAnOutOfScopeId() {
        String missingId = Tsid.generate();
        String otherTenantId = failedJob(CLIENT_B);

        UseCaseError missing = catchUseCaseError(() -> Auth.runAs(CLIENT_A_OPERATOR, () -> CancelDispatchJob.of(repo)
                .run(uow, new CancelCommand(missingId), ExecutionContext.of(CLIENT_A_OPERATOR.principalId()))));
        UseCaseError outOfScope = catchUseCaseError(() -> Auth.runAs(CLIENT_A_OPERATOR, () -> CancelDispatchJob.of(repo)
                .run(uow, new CancelCommand(otherTenantId), ExecutionContext.of(CLIENT_A_OPERATOR.principalId()))));

        assertThat(missing).isInstanceOf(UseCaseError.NotFound.class);
        assertThat(outOfScope).as("out-of-scope answers the SAME shape as truly missing, not 403 SCOPE_FORBIDDEN")
                .isInstanceOf(UseCaseError.NotFound.class);
        assertThat(outOfScope.code()).isEqualTo(missing.code()).isEqualTo("DispatchJob_NOT_FOUND");
        assertThat(outOfScope.httpStatus()).isEqualTo(missing.httpStatus()).isEqualTo(404);
        assertThat(missing.message()).isEqualTo("DispatchJob not found: " + missingId);
        assertThat(outOfScope.message()).as("same format, the out-of-scope job's own id").isEqualTo("DispatchJob not found: " + otherTenantId);
        assertThat(reload(otherTenantId).status()).as("untouched").isEqualTo(DispatchJobStatus.FAILED);
    }

    @Test
    void cancellingTheHeadUnblocksGroupHeldBeforeForItsSiblings() {
        String group = "grp-cancel-unblock-" + RUN;
        String cancelCode = code("cancelunblock");
        var head = Seed.of(cancelCode).withMessageGroup(group).withSequence(1).failed(3, "x");
        String headId = seedWriteRow(head);
        var sibling = Seed.of(cancelCode).withMessageGroup(group).withSequence(2)
                .withCreatedAt(head.createdAt().plusSeconds(1)).withStatus("QUEUED");
        seedWriteRow(sibling);
        DispatchJob siblingLoaded = reload(sibling.id());

        assertThat(repo.groupHeldBefore(siblingLoaded)).as("held behind the FAILED head").isTrue();

        Auth.runAs(ANCHOR, () -> CancelDispatchJob.of(repo)
                .run(uow, new CancelCommand(headId), ExecutionContext.of(ANCHOR.principalId())));

        assertThat(repo.groupHeldBefore(reload(sibling.id())))
                .as("the head is no longer FAILED — GroupHolding stops matching the instant status changes")
                .isFalse();
    }

    /// Runs `call`, expecting it to throw [UseCaseException], and returns the carried [UseCaseError].
    private static UseCaseError catchUseCaseError(Runnable call) {
        try {
            call.run();
        } catch (UseCaseException e) {
            return e.error();
        }
        throw new AssertionError("expected a UseCaseException");
    }
}
