package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.Facet;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ListFilter;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS_READ;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seed;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedAttempt;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedProjection;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The reads and the upsert over directly seeded rows (spec §1, §4, §9):
/// the detail read off the write table (incl. the JSONB metadata column in
/// foreign shapes), the projection list with SQL-side tenant scoping and
/// every filter, by-event, the facets, the attempt history, and the
/// partition-keyed upsert round trip.
class DispatchJobRepositoryTest {

    private static final DispatchJobRepository repo = new DispatchJobRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String CLIENT_A = "cli_a" + RUN;
    private static final String CLIENT_B = "cli_b" + RUN;
    private static final String SCOPE_CODE = code("scope");
    private static final Instant BASE = Instant.now().minusSeconds(60);

    private static String jobA;
    private static String jobB;
    private static String jobPlatform;

    @BeforeAll
    static void seedRows() {
        jobA = seed(Seed.of(SCOPE_CODE).withClientId(CLIENT_A).withCreatedAt(BASE.plusSeconds(3)));
        jobB = seed(Seed.of(SCOPE_CODE).withClientId(CLIENT_B).withCreatedAt(BASE.plusSeconds(2)).withStatus("COMPLETED"));
        jobPlatform = seed(Seed.of(SCOPE_CODE).withCreatedAt(BASE.plusSeconds(1)).withSource("src" + RUN));
    }

    private static ListFilter filter(Visibility visibility, List<String> codes, List<String> clientIds) {
        return new ListFilter(null, null, null, null, null, null, null, null, false, 0, 0,
                clientIds, null, codes, null, null, null, visibility);
    }

    private static List<String> ids(List<DispatchJobProjection> rows) {
        return rows.stream().map(DispatchJobProjection::id).toList();
    }

    // ── Tenant scoping (spec §4) ───────────────────────────────────────────

    @Test
    void anchorSeesEveryTenantAndPlatformScopedRows() {
        var rows = repo.findWithFilters(filter(Visibility.Everything.INSTANCE, List.of(SCOPE_CODE), null));
        assertThat(ids(rows)).containsExactly(jobA, jobB, jobPlatform); // newest first
    }

    @Test
    void scopedCallerSeesOwnTenantPlusPlatformScopedNeverAnother() {
        var rows = repo.findWithFilters(filter(new Visibility.Tenants(List.of(CLIENT_A)), List.of(SCOPE_CODE), null));
        assertThat(ids(rows)).containsExactly(jobA, jobPlatform);
    }

    @Test
    void scopedCallerFilteringForAnotherTenantGetsNothing() {
        var rows = repo.findWithFilters(filter(new Visibility.Tenants(List.of(CLIENT_A)), List.of(SCOPE_CODE), List.of(CLIENT_B)));
        assertThat(rows).as("cross-tenant filter must not leak another tenant's jobs").isEmpty();
    }

    @Test
    void scopedCallerWithNoClientsSeesPlatformScopedOnly() {
        var rows = repo.findWithFilters(filter(new Visibility.Tenants(List.of()), List.of(SCOPE_CODE), null));
        assertThat(ids(rows)).containsExactly(jobPlatform);
    }

    // ── Filters, order, window (spec §4) ───────────────────────────────────

    @Test
    void filtersNarrowByEveryColumnAndSortFlipsTheOrder() {
        var unscoped = Visibility.Everything.INSTANCE;
        assertThat(ids(repo.findWithFilters(new ListFilter("COMPLETED", null, null, null, SCOPE_CODE, null, null, null,
                false, 0, 0, null, null, null, null, null, null, unscoped)))).containsExactly(jobB);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, CLIENT_A, null, null, null, null, null, null,
                false, 0, 0, null, null, List.of(SCOPE_CODE), null, null, null, unscoped)))).containsExactly(jobA);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, "src" + RUN, null, null,
                false, 0, 0, null, null, null, null, null, null, unscoped)))).containsExactly(jobPlatform);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null,
                false, 0, 0, null, List.of("PENDING", "COMPLETED"), List.of(SCOPE_CODE), null, null, null, unscoped))))
                .containsExactly(jobA, jobB, jobPlatform);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null,
                false, 0, 0, null, null, null, List.of("scope" + RUN), List.of("orders"), List.of("order"), unscoped))))
                .containsExactly(jobA, jobB, jobPlatform);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, BASE.plusSeconds(2), BASE.plusSeconds(2),
                false, 0, 0, null, null, List.of(SCOPE_CODE), null, null, null, unscoped)))).containsExactly(jobB);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null,
                true, 0, 0, null, null, List.of(SCOPE_CODE), null, null, null, unscoped)))).containsExactly(jobPlatform, jobB, jobA);
    }

    @Test
    void limitAndOffsetWindowTheListAndOutOfRangeLimitsFallBack() {
        var unscoped = Visibility.Everything.INSTANCE;
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null,
                false, 1, 1, null, null, List.of(SCOPE_CODE), null, null, null, unscoped)))).containsExactly(jobB);
        assertThat(ids(repo.findWithFilters(new ListFilter(null, null, null, null, null, null, null, null,
                false, 5000, 0, null, null, List.of(SCOPE_CODE), null, null, null, unscoped)))).hasSize(3);
    }

    @Test
    void aFilterMustStateWhoseViewItIs() {
        assertThatThrownBy(() -> new ListFilter(null, null, null, null, null, null, null, null,
                false, 0, 0, null, null, null, null, null, null, null))
                .as("visibility never defaults open").isInstanceOf(NullPointerException.class).hasMessage("visibility");
    }

    @Test
    void findByEventIdReadsTheProjectionNewestFirst() {
        String eventId = Tsid.generate();
        String older = seed(Seed.of(code("byevent")).withEventId(eventId).withCreatedAt(BASE.plusSeconds(10)));
        String newer = seed(Seed.of(code("byevent")).withEventId(eventId).withCreatedAt(BASE.plusSeconds(11)).withClientId(CLIENT_A));
        assertThat(ids(repo.findByEventId(eventId))).containsExactly(newer, older);
        assertThat(repo.findByEventId(Tsid.generate())).isEmpty();
    }

    @Test
    void facetsAreDistinctNonNullValuesAscending() {
        String pool = "dpl_" + RUN;
        seed(Seed.of(code("facet")).withDispatchPoolId(pool).withSubscriptionId("sub_" + RUN));
        seed(Seed.of(code("facet")).withDispatchPoolId(pool));
        assertThat(repo.distinctValues(Facet.DISPATCH_POOL_ID, 1000)).containsOnlyOnce(pool);
        assertThat(repo.distinctValues(Facet.SUBSCRIPTION_ID, 1000)).contains("sub_" + RUN);
        assertThat(repo.distinctValues(Facet.CODE, 1000)).contains(code("facet"), SCOPE_CODE).isSorted();
        assertThat(repo.distinctValues(Facet.KIND, 1000)).contains("EVENT");
        assertThat(repo.distinctValues(Facet.STATUS, 1000)).contains("PENDING", "COMPLETED");
        assertThat(repo.distinctValues(Facet.CLIENT_ID, 1000)).contains(CLIENT_A, CLIENT_B);
    }

    // ── Detail read (spec §1.1, §9) ────────────────────────────────────────

    @Test
    void findByIdReadsTheWriteRowWithDefaultsAndTerminalStamps() {
        String id = seedWriteRow(Seed.of(code("detail")).withClientId(CLIENT_A).withPayload("{\"x\":1}")
                .withMessageGroup("grp").failed(3, "boom"));
        DispatchJob j = repo.findById(id).orElseThrow();

        assertThat(j.id()).isEqualTo(id).hasSize(13);
        assertThat(j.code()).isEqualTo(code("detail"));
        assertThat(j.clientId()).isEqualTo(CLIENT_A);
        assertThat(j.status()).isEqualTo(DispatchJobStatus.FAILED);
        assertThat(j.isTerminal()).isTrue();
        assertThat(j.attemptCount()).isEqualTo(3);
        assertThat(j.lastError()).isEqualTo("boom");
        assertThat(j.durationMillis()).isEqualTo(777L);
        assertThat(j.completedAt()).isNotNull();
        assertThat(j.scheduledFor()).isNotNull();
        assertThat(j.payload()).isEqualTo("{\"x\":1}");
        assertThat(j.messageGroup()).isEqualTo("grp");
        // column defaults read through the lenient readers
        assertThat(j.kind()).isEqualTo(DispatchJobKind.EVENT);
        assertThat(j.protocol()).isEqualTo(Protocol.HTTP_WEBHOOK);
        assertThat(j.payloadContentType()).isEqualTo("application/json");
        assertThat(j.dataOnly()).isTrue();
        assertThat(j.sequence()).isEqualTo(99);
        assertThat(j.timeoutSeconds()).isEqualTo(30);
        assertThat(j.maxRetries()).isEqualTo(3);
        assertThat(j.retryStrategy()).isEqualTo(RetryStrategy.EXPONENTIAL);
        assertThat(j.metadata()).as("column default '[]'").isEmpty();
        assertThat(repo.findById(Tsid.generate())).isEmpty();
    }

    @Test
    void findByIdsReturnsOnlyTheKnownOnes() {
        String a = seedWriteRow(Seed.of(code("byids")));
        String b = seedWriteRow(Seed.of(code("byids")));
        assertThat(repo.findByIds(List.of(a, Tsid.generate(), b)).stream().map(DispatchJob::id))
                .containsExactlyInAnyOrder(a, b);
        assertThat(repo.findByIds(List.of())).isEmpty();
    }

    @Test
    void metadataJsonColumnReadsWhateverAnotherWriterProduced() {
        String array = seedWriteRow(Seed.of(code("meta")).withMetadataJson("[{\"key\":\"a\",\"value\":\"1\"},{\"key\":\"b\",\"value\":\"2\"}]"));
        String empty = seedWriteRow(Seed.of(code("meta")).withMetadataJson("[]"));
        String nul = seedWriteRow(Seed.of(code("meta")).withMetadataJson(null));
        String object = seedWriteRow(Seed.of(code("meta")).withMetadataJson("{\"a\":\"1\"}"));
        String malformedPair = seedWriteRow(Seed.of(code("meta")).withMetadataJson("[{\"key\":\"a\"},{\"key\":\"b\",\"value\":\"2\"}]"));

        assertThat(repo.findById(array).orElseThrow().metadata())
                .containsExactly(new DispatchJob.Metadata("a", "1"), new DispatchJob.Metadata("b", "2"));
        assertThat(repo.findById(empty).orElseThrow().metadata()).isEmpty();
        assertThat(repo.findById(nul).orElseThrow().metadata()).isEmpty();
        assertThat(repo.findById(object).orElseThrow().metadata()).as("legacy object shape reads as none").isEmpty();
        assertThat(repo.findById(malformedPair).orElseThrow().metadata())
                .as("a pair without a value is dropped").containsExactly(new DispatchJob.Metadata("b", "2"));
    }

    // ── Attempts (spec §1.2) ───────────────────────────────────────────────

    @Test
    void attemptsReadOldestFirstWithSuccessDerivedFromStatus() {
        String id = seedWriteRow(Seed.of(code("attempts")));
        Instant t0 = Instant.now().minusSeconds(30);
        seedAttempt(id, 2, true, 200, null, null, t0.plusSeconds(10));
        seedAttempt(id, 1, false, 503, "upstream down", "HTTP_ERROR", t0);

        List<Attempt> attempts = repo.attemptsByJob(id);
        assertThat(attempts).extracting(Attempt::attemptNumber).containsExactly(1, 2);
        Attempt first = attempts.getFirst();
        assertThat(first.success()).isFalse();
        assertThat(first.responseCode()).isEqualTo(503);
        assertThat(first.errorMessage()).isEqualTo("upstream down");
        assertThat(first.errorType()).isEqualTo(AttemptErrorType.HTTP_ERROR);
        assertThat(first.durationMillis()).isEqualTo(42L);
        assertThat(first.completedAt()).isEqualTo(t0.plusMillis(42));
        Attempt second = attempts.get(1);
        assertThat(second.success()).isTrue();
        assertThat(second.responseBody()).isEqualTo("ok");
        assertThat(second.errorType()).as("no error type on success").isNull();
        assertThat(repo.attemptsByJob(Tsid.generate())).isEmpty();
    }

    // ── Persist (spec §9) ──────────────────────────────────────────────────

    @Test
    void persistUpsertsByIdAndCreatedAtAndRoundTripsEveryColumn() {
        String id = seedWriteRow(Seed.of(code("persist")).withClientId(CLIENT_A).withMessageGroup("g")
                .withMetadataJson("[{\"key\":\"k\",\"value\":\"v\"}]").failed(2, "err"));
        DispatchJob before = repo.findById(id).orElseThrow();
        DispatchJob after = before.requeue();

        uow.inTransaction(tx -> {
            repo.persist(after, tx.dbTx());
            return null;
        });

        DispatchJob stored = repo.findById(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(stored.attemptCount()).isZero();
        assertThat(stored.scheduledFor()).isNull();
        assertThat(stored.completedAt()).isNull();
        assertThat(stored.durationMillis()).isNull();
        assertThat(stored.lastError()).isNull();
        assertThat(stored.metadata()).containsExactly(new DispatchJob.Metadata("k", "v"));
        assertThat(stored.createdAt()).isEqualTo(before.createdAt());
        assertThat(stored.updatedAt()).as("stamped at persist time").isAfter(before.updatedAt());
        assertThat(stored.clientId()).isEqualTo(CLIENT_A);
        assertThat(stored.messageGroup()).isEqualTo("g");
        assertThat(DB.fetchCount(MSG_DISPATCH_JOBS, MSG_DISPATCH_JOBS.ID.eq(id))).as("upsert, not a second row").isEqualTo(1);
    }

    @Test
    void deleteRemovesTheRowByItsFullKey() {
        String id = seedWriteRow(Seed.of(code("delete")));
        DispatchJob j = repo.findById(id).orElseThrow();
        uow.inTransaction(tx -> {
            repo.delete(j, tx.dbTx());
            return null;
        });
        assertThat(repo.findById(id)).isEmpty();
    }

    // ── X-06: corrupt status fails loudly (spec §4, §15) ────────────────────

    @Test
    void findByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingSilently() {
        // Since migration 052, chk_msg_dispatch_jobs_status blocks a fresh write
        // of an unrecognised status at the DB boundary. The read-side defence
        // this test pins exists for a row that predates the constraint (or
        // arrives out-of-band), so the constraint is dropped for the seed
        // insert AND the assertion, and the corrupt row is deleted again
        // before the constraint is restored — otherwise restoring it would
        // itself fail by re-validating against the row we just inserted
        // (io.flowcatalyst.testpg.TestPg, ported from Go's testpg.WithConstraintDropped).
        TestPg.withConstraintDropped(DS, "msg_dispatch_jobs", "chk_msg_dispatch_jobs_status", () -> {
            String id = seedWriteRow(Seed.of(code("corrupt")).withStatus("BOGUS_STATUS"));
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptDispatchJobException.class)
                        .satisfies(e -> assertThat(((CorruptDispatchJobException) e).dispatchJobId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByIdReadsTheLegacyErrorStatusAsFailed() {
        String id = seedWriteRow(Seed.of(code("corrupt")).withStatus("ERROR"));
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.FAILED);
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        String goodCode = code("corruptlist");
        seed(Seed.of(goodCode).withCreatedAt(BASE.plusSeconds(20)));
        // chk_msg_dispatch_jobs_read_status (migration 052) guards this projection
        // table too; drop it for the corrupt seed insert AND the assertion, then
        // delete the row before restoring (see the comment on
        // findByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingSilently above).
        TestPg.withConstraintDropped(DS, "msg_dispatch_jobs_read", "chk_msg_dispatch_jobs_read_status", () -> {
            Seed corrupt = Seed.of(goodCode).withCreatedAt(BASE.plusSeconds(21)).withStatus("NOT_A_REAL_STATUS");
            seedProjection(corrupt);
            try {
                assertThatThrownBy(() -> repo.findWithFilters(filter(Visibility.Everything.INSTANCE, List.of(goodCode), null)))
                        .isInstanceOf(CorruptDispatchJobException.class);
            } finally {
                DB.deleteFrom(MSG_DISPATCH_JOBS_READ).where(MSG_DISPATCH_JOBS_READ.ID.eq(corrupt.id())).execute();
            }
        });
    }

    // ── GroupHolding / groupHeldBefore (spec §9) ────────────────────────────

    @Test
    void groupHeldBeforeIsPositionalOverSequenceCreatedAtId() {
        String group = "grp-hold-" + RUN;
        String holdCode = code("hold");
        Instant t = BASE.plusSeconds(30);
        seedWriteRow(Seed.of(holdCode).withMessageGroup(group).withSequence(1).withCreatedAt(t).failed(3, "boom"));
        String ahead = seedWriteRow(Seed.of(holdCode).withMessageGroup(group).withSequence(0).withCreatedAt(t.minusSeconds(1)));
        String behind = seedWriteRow(Seed.of(holdCode).withMessageGroup(group).withSequence(2).withCreatedAt(t.plusSeconds(1)));

        assertThat(repo.groupHeldBefore(repo.findById(behind).orElseThrow()))
                .as("behind a FAILED head, in the same group").isTrue();
        assertThat(repo.groupHeldBefore(repo.findById(ahead).orElseThrow()))
                .as("positioned BEFORE the failed head is never held — proves positional, not set membership").isFalse();
    }

    @Test
    void groupHeldBeforeCountsAPendingRowMidBackoffButNotOneAlreadyEligible() {
        String group = "grp-backoff-" + RUN;
        String bckCode = code("bck");
        Instant t = BASE.plusSeconds(40);
        String midBackoff = seedWriteRow(Seed.of(bckCode).withMessageGroup(group).withSequence(1).withCreatedAt(t)
                .withStatus("PENDING"));
        DB.update(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.SCHEDULED_FOR, Instant.now().plusSeconds(60).atOffset(ZoneOffset.UTC))
                .where(MSG_DISPATCH_JOBS.ID.eq(midBackoff))
                .execute();
        String behind = seedWriteRow(Seed.of(bckCode).withMessageGroup(group).withSequence(2).withCreatedAt(t.plusSeconds(1)));
        assertThat(repo.groupHeldBefore(repo.findById(behind).orElseThrow()))
                .as("PENDING with a FUTURE scheduled_for still holds — the easy-to-miss disjunct").isTrue();

        // A SEPARATE group: an otherwise-identical PENDING head whose scheduled_for is NULL
        // (immediately eligible) must NOT hold — reusing the first group would still be "held"
        // by the still-backed-off `midBackoff` row above.
        String group2 = "grp-backoff2-" + RUN;
        String eligibleNow = seedWriteRow(Seed.of(bckCode).withMessageGroup(group2).withSequence(1).withCreatedAt(t.plusSeconds(5))
                .withStatus("PENDING")); // scheduled_for NULL: immediately eligible, not holding
        String behind2 = seedWriteRow(Seed.of(bckCode).withMessageGroup(group2).withSequence(2).withCreatedAt(t.plusSeconds(6)));
        assertThat(repo.groupHeldBefore(repo.findById(behind2).orElseThrow())).isFalse();
        assertThat(eligibleNow).isNotNull();
    }

    @Test
    void groupHeldBeforeIsFalseWithNoMessageGroup() {
        String id = seedWriteRow(Seed.of(code("nogroup")));
        assertThat(repo.groupHeldBefore(repo.findById(id).orElseThrow())).isFalse();
    }

    // ── Infra writes (spec §4, direct — outside the envelope) ───────────────

    @Test
    void markInProgressFlipsQueuedToProcessingAndStampsLastAttemptAt() {
        String id = seedWriteRow(Seed.of(code("mip")).withStatus("QUEUED"));
        DispatchJob before = repo.findById(id).orElseThrow();
        repo.markInProgress(id, before.createdAt());
        DispatchJob after = repo.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(DispatchJobStatus.PROCESSING);
        assertThat(after.lastAttemptAt()).isNotNull();
    }

    @Test
    void markCompletedStampsTerminalFields() {
        String id = seedWriteRow(Seed.of(code("mc")).withStatus("PROCESSING"));
        DispatchJob before = repo.findById(id).orElseThrow();
        Instant completedAt = Instant.now();
        repo.markCompleted(id, before.createdAt(), completedAt, 123L);
        DispatchJob after = repo.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(DispatchJobStatus.COMPLETED);
        assertThat(after.durationMillis()).isEqualTo(123L);
        assertThat(after.completedAt()).isNotNull();
    }

    @Test
    void markFailedRecordsTheError() {
        String id = seedWriteRow(Seed.of(code("mf")).withStatus("PROCESSING"));
        DispatchJob before = repo.findById(id).orElseThrow();
        repo.markFailed(id, before.createdAt(), "budget exhausted");
        DispatchJob after = repo.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(DispatchJobStatus.FAILED);
        assertThat(after.lastError()).isEqualTo("budget exhausted");
    }

    @Test
    void scheduleRetryBumpsAttemptCountAndSetsScheduledFor() {
        String id = seedWriteRow(Seed.of(code("sr")).withStatus("PROCESSING"));
        DispatchJob before = repo.findById(id).orElseThrow();
        Instant scheduledFor = Instant.now().plusSeconds(30);
        repo.scheduleRetry(id, before.createdAt(), scheduledFor, 2, "http 500");
        DispatchJob after = repo.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.attemptCount()).isEqualTo(2);
        assertThat(after.scheduledFor()).isNotNull();
        assertThat(after.lastError()).isEqualTo("http 500");
    }

    @Test
    void rescheduleSpendsNoRetryBudget() {
        String id = seedWriteRow(Seed.of(code("resched")).withStatus("PROCESSING"));
        DispatchJob before = repo.findById(id).orElseThrow();
        Instant scheduledFor = Instant.now().plusSeconds(5);
        repo.reschedule(id, before.createdAt(), scheduledFor);
        DispatchJob after = repo.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(after.attemptCount()).as("no budget spend — hold-back/deferral, not a failure").isEqualTo(before.attemptCount());
    }

    // ── settleAcked (spec §6) ────────────────────────────────────────────────

    @Test
    void settleAckedResetsOnlyQueuedAndProcessingRowsAndIsIdempotent() {
        String queued = seedWriteRow(Seed.of(code("settle")).withStatus("QUEUED"));
        String processing = seedWriteRow(Seed.of(code("settle")).withStatus("PROCESSING"));
        String completed = seedWriteRow(Seed.of(code("settle")).withStatus("COMPLETED"));

        List<String> settled = repo.settleAcked(List.of(queued, processing, completed), "settled: test reason");
        assertThat(settled).containsExactlyInAnyOrder(queued, processing);

        DispatchJob q = repo.findById(queued).orElseThrow();
        assertThat(q.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(q.scheduledFor()).isNull();
        assertThat(q.lastError()).isEqualTo("settled: test reason");
        assertThat(repo.findById(completed).orElseThrow().status())
                .as("a terminal row is never resurrected").isEqualTo(DispatchJobStatus.COMPLETED);

        // idempotent: the same call again settles nothing more, since both rows already left QUEUED/PROCESSING
        assertThat(repo.settleAcked(List.of(queued, processing, completed), "settled: test reason")).isEmpty();
        assertThat(repo.settleAcked(List.of(), "unused")).isEmpty();
    }

    // ── sweepStrandedSiblings — the reaper's predicate (spec §7) ────────────

    @Test
    void sweepResetsStrandedBlockOnErrorSiblingsButLeavesEverythingElseAlone() {
        String group = "grp-sweep-" + RUN;
        String sweepCode = code("sweep");
        Instant t = BASE.plusSeconds(50);
        Instant liveBefore = Instant.now().minusSeconds(60);

        String head = seedWriteRow(Seed.of(sweepCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withCreatedAt(t).failed(3, "head failed"));
        String queuedSibling = seedWriteRow(Seed.of(sweepCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(2).withCreatedAt(t.plusSeconds(1)).withStatus("QUEUED"));
        String staleProcessing = seedWriteRow(Seed.of(sweepCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(3).withCreatedAt(t.plusSeconds(2)).withStatus("PROCESSING")
                .withUpdatedAt(Instant.now().minusSeconds(3600))); // stale: older than liveBefore
        String freshProcessing = seedWriteRow(Seed.of(sweepCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(4).withCreatedAt(t.plusSeconds(3)).withStatus("PROCESSING")
                .withUpdatedAt(Instant.now().minusSeconds(60))); // fresh (1 minute old): left alone
        String nextOnErrorSibling = seedWriteRow(Seed.of(sweepCode).withMessageGroup(group).withMode("NEXT_ON_ERROR")
                .withSequence(5).withCreatedAt(t.plusSeconds(4)).withStatus("QUEUED"));
        String aheadOfHead = seedWriteRow(Seed.of(sweepCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(0).withCreatedAt(t.minusSeconds(1)).withStatus("QUEUED"));

        List<String> reset = repo.sweepStrandedSiblings(liveBefore, "reaper: test sweep");

        assertThat(reset).as("QUEUED regardless of age, and stale PROCESSING")
                .containsExactlyInAnyOrder(queuedSibling, staleProcessing);
        assertThat(repo.findById(queuedSibling).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(repo.findById(queuedSibling).orElseThrow().lastError()).isEqualTo("reaper: test sweep");
        assertThat(repo.findById(staleProcessing).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(repo.findById(freshProcessing).orElseThrow().status())
                .as("a fresh PROCESSING row is presumed a genuine in-flight delivery").isEqualTo(DispatchJobStatus.PROCESSING);
        assertThat(repo.findById(nextOnErrorSibling).orElseThrow().status())
                .as("NEXT_ON_ERROR is never swept").isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(repo.findById(aheadOfHead).orElseThrow().status())
                .as("positioned BEFORE the head is untouched").isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(head).isNotNull();

        // idempotent: sweeping again resets nothing more
        assertThat(repo.sweepStrandedSiblings(liveBefore, "reaper: test sweep")).isEmpty();
    }

    @Test
    void sweepTreatsALegacyErrorHeadExactlyLikeFailed() {
        String group = "grp-legacy-" + RUN;
        String legacyCode = code("legacy");
        Instant t = BASE.plusSeconds(70);
        seedWriteRow(Seed.of(legacyCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withCreatedAt(t).withStatus("ERROR"));
        String sibling = seedWriteRow(Seed.of(legacyCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(2).withCreatedAt(t.plusSeconds(1)).withStatus("QUEUED"));

        List<String> reset = repo.sweepStrandedSiblings(Instant.now().minusSeconds(60), "reaper: legacy");
        assertThat(reset).containsExactly(sibling);
    }
}
