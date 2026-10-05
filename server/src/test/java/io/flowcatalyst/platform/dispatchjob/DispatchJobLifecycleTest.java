package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle.Transition;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.assertQueueMirrorsJob;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.queueRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedQueued;
import static org.assertj.core.api.Assertions.assertThat;

/// The dispatch-job lifecycle in one place. [#theWholeLifecycleEveryTransitionFromEveryStatus]
/// IS the documentation: for every (transition, from-status) pair, the status
/// the job ends in, or `-` where the transition is refused (the row is left
/// byte-for-byte alone and the refusal is counted). Against a real database.
class DispatchJobLifecycleTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DS);
    private static final DispatchJobRepository REPO = new DispatchJobRepository(DS);

    /// Every status the schema admits, legacy aliases included.
    private static final List<String> STATUSES = List.of("PENDING", "QUEUED", "PROCESSING", "IN_PROGRESS",
            "COMPLETED", "FAILED", "ERROR", "CANCELLED", "EXPIRED");

    /// The table. Row = transition; each entry = from-status -> resulting status. A status
    /// absent from a row is REFUSED for that transition.
    private static final Map<Transition, Map<String, String>> LIFECYCLE_TABLE = new EnumMap<>(Transition.class);

    static {
        LIFECYCLE_TABLE.put(Transition.MARK_QUEUED, Map.of("PENDING", "QUEUED"));
        LIFECYCLE_TABLE.put(Transition.CLAIM_FOR_DELIVERY, Map.of("PENDING", "PROCESSING", "QUEUED", "PROCESSING"));
        LIFECYCLE_TABLE.put(Transition.COMPLETE, live("COMPLETED"));
        LIFECYCLE_TABLE.put(Transition.FAIL, live("FAILED"));
        LIFECYCLE_TABLE.put(Transition.SCHEDULE_RETRY, live("PENDING"));
        LIFECYCLE_TABLE.put(Transition.RESCHEDULE, live("PENDING"));
        LIFECYCLE_TABLE.put(Transition.SETTLE_ACKED, Map.of("QUEUED", "PENDING", "PROCESSING", "PENDING"));
        LIFECYCLE_TABLE.put(Transition.SWEEP_STRANDED, Map.of("QUEUED", "PENDING", "PROCESSING", "PENDING"));
        LIFECYCLE_TABLE.put(Transition.STALE_QUEUED, Map.of("QUEUED", "PENDING"));
        LIFECYCLE_TABLE.put(Transition.REQUEUE, Map.of("PENDING", "PENDING", "QUEUED", "PENDING",
                "PROCESSING", "PENDING", "IN_PROGRESS", "PENDING", "COMPLETED", "PENDING", "FAILED", "PENDING",
                "ERROR", "PENDING", "CANCELLED", "PENDING", "EXPIRED", "PENDING"));
        LIFECYCLE_TABLE.put(Transition.CANCEL, Map.of("FAILED", "CANCELLED", "ERROR", "CANCELLED"));
        LIFECYCLE_TABLE.put(Transition.OPERATOR_COMPLETE, Map.of("FAILED", "COMPLETED", "ERROR", "COMPLETED"));
    }

    /// PENDING, QUEUED, PROCESSING and the legacy IN_PROGRESS all end in `to`.
    private static Map<String, String> live(String to) {
        return Map.of("PENDING", to, "QUEUED", to, "PROCESSING", to, "IN_PROGRESS", to);
    }

    private record Case(String id, Instant createdAt) {
    }

    private static Case seedJob(String status, String group, int sequence) {
        // updated_at far in the past, so "changed updated_at" is unambiguous
        Seed seed = Seed.of(code("life")).withStatus(status).withMode("BLOCK_ON_ERROR")
                .withUpdatedAt(Instant.now().minusSeconds(3600));
        if (group != null) seed = seed.withMessageGroup(group).withSequence(sequence);
        String id = seedQueued(seed);
        return new Case(id, createdAt(id));
    }

    private static Instant createdAt(String id) {
        OffsetDateTime at = DB.select(MSG_DISPATCH_JOBS.CREATED_AT).from(MSG_DISPATCH_JOBS)
                .where(MSG_DISPATCH_JOBS.ID.eq(id)).fetchOne(MSG_DISPATCH_JOBS.CREATED_AT);
        return at.toInstant();
    }

    private static Map<String, Object> rawRow(String id) {
        return DB.fetchOne(MSG_DISPATCH_JOBS, MSG_DISPATCH_JOBS.ID.eq(id)).intoMap();
    }

    private static void inTx(java.util.function.Consumer<DbTx> work) {
        try (Connection conn = DS.getConnection()) {
            work.accept(DbTx.wrapForBootstrap(conn));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /// The driver of each transition: how a caller invokes it for one job.
    private static BiConsumer<Case, Instant> driver(Transition t) {
        return switch (t) {
            case MARK_QUEUED -> (c, ignored) -> {
                DispatchJob j = REPO.findById(c.id()).orElseThrow();
                LIFECYCLE.markQueued(List.of(new ClaimRow(c.id(), null, null, null, null, null, c.createdAt(), 0,
                        null, j.updatedAt())));
            };
            case CLAIM_FOR_DELIVERY -> (c, ignored) -> LIFECYCLE.claimForDelivery(c.id(), c.createdAt());
            case COMPLETE -> (c, ignored) -> LIFECYCLE.markCompleted(c.id(), c.createdAt(), Instant.now(), 5L);
            case FAIL -> (c, ignored) -> LIFECYCLE.markFailed(c.id(), c.createdAt(), "boom");
            case SCHEDULE_RETRY -> (c, ignored) -> LIFECYCLE.scheduleRetry(c.id(), c.createdAt(),
                    Instant.now().plusSeconds(60), 1, "retry");
            case RESCHEDULE -> (c, ignored) -> LIFECYCLE.reschedule(c.id(), c.createdAt(), Instant.now().plusSeconds(60));
            case SETTLE_ACKED -> (c, ignored) -> LIFECYCLE.settleAcked(List.of(c.id()), "settled: test");
            case SWEEP_STRANDED -> (c, ignored) -> LIFECYCLE.sweepStrandedSiblings(Instant.now().plusSeconds(60), "reaper: test");
            case STALE_QUEUED -> (c, ignored) -> LIFECYCLE.recoverStaleQueued(Instant.now().plusSeconds(60));
            case REQUEUE -> (c, ignored) -> inTx(tx -> {
                try {
                    DispatchJobLifecycle.requeueWriter().persist(REPO.findById(c.id()).orElseThrow(), tx);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            case CANCEL -> (c, ignored) -> operator(DispatchJobLifecycle.cancelWriter(), c);
            case OPERATOR_COMPLETE -> (c, ignored) -> operator(DispatchJobLifecycle.completeWriter(), c);
            case CREATE -> throw new IllegalArgumentException("CREATE has no from-status");
        };
    }

    private static void operator(io.flowcatalyst.sdk.usecase.jdbc.Persist<DispatchJob> writer, Case c) {
        try {
            inTx(tx -> {
                try {
                    writer.persist(REPO.findById(c.id()).orElseThrow(), tx);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException refused) {
            // a refused operator transition fails the write (so the unit of work rolls back)
            assertThat(refused.getMessage()).contains("refused");
        }
    }

    @Test
    void theWholeLifecycleEveryTransitionFromEveryStatus() {
        assertThat(LIFECYCLE_TABLE.keySet()).as("the table covers every transition but CREATE")
                .containsExactlyInAnyOrder(java.util.Arrays.stream(Transition.values())
                        .filter(t -> t != Transition.CREATE).toArray(Transition[]::new));
        for (Transition t : LIFECYCLE_TABLE.keySet()) {
            Map<String, String> row = LIFECYCLE_TABLE.get(t);
            for (String from : STATUSES) {
                String expected = row.get(from); // null = refused
                // the enum states the same thing as the table
                assertThat(t.fromAnyStatus() || t.from().contains(from))
                        .as("%s from %s: enum agrees with the table", t, from).isEqualTo(expected != null);
                if (expected != null) assertThat(expected).as("%s: the table's result is the enum's `to`", t).isEqualTo(t.to());

                String group = t == Transition.SWEEP_STRANDED ? "life-" + RUN + "-" + Tsid.generate() : null;
                if (group != null) {
                    // a FAILED head ahead of the sibling in its BLOCK_ON_ERROR group
                    seedJob("FAILED", group, 1);
                }
                Case c = seedJob(from, group, 2);
                Map<String, Object> before = rawRow(c.id());
                Map<String, Object> queueBefore = queueRow(c.id());
                assertQueueMirrorsJob(c.id(), t + " from " + from + ", seeded", false);
                long refusedBefore = DispatchJobLifecycle.refused(t);

                driver(t).accept(c, null);

                Map<String, Object> after = rawRow(c.id());
                String label = t + " from " + from;
                if (expected == null) {
                    assertThat(after).as(label + ": refused, row untouched (status, updated_at, every column)")
                            .isEqualTo(before);
                    assertThat(queueRow(c.id())).as(label + ": refused, queue row untouched (byte-identical, or still absent)")
                            .isEqualTo(queueBefore);
                    if (t != Transition.SWEEP_STRANDED && t != Transition.STALE_QUEUED) {
                        assertThat(DispatchJobLifecycle.refused(t)).as(label + ": refusal counted")
                                .isEqualTo(refusedBefore + 1);
                    }
                } else {
                    assertThat(after.get("status")).as(label + ": resulting status").isEqualTo(expected);
                    assertThat((OffsetDateTime) after.get("updated_at")).as(label + ": updated_at stamped")
                            .isAfter((OffsetDateTime) before.get("updated_at"));
                    assertThat(DispatchJobLifecycle.refused(t)).as(label + ": no refusal").isEqualTo(refusedBefore);
                }
                // after EVERY pair: a queue row iff the job is PENDING, mirroring it, unclaimed
                // (a refused transition leaves a consistent pair consistent)
                assertQueueMirrorsJob(c.id(), label, expected != null);
            }
        }
    }

    // ── columns each transition writes, for a job in an allowed status ─────

    @Test
    void anAllowedTransitionWritesExactlyItsColumns() {
        Instant retryAt = Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Case c = seedJob("PROCESSING", null, 1);
        LIFECYCLE.scheduleRetry(c.id(), c.createdAt(), retryAt, 2, "http 500");
        DispatchJob j = REPO.findById(c.id()).orElseThrow();
        assertThat(j.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(j.scheduledFor()).isEqualTo(retryAt);
        assertThat(j.attemptCount()).isEqualTo(2);
        assertThat(j.lastError()).isEqualTo("http 500");

        Case d = seedJob("PROCESSING", null, 1);
        Instant done = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        LIFECYCLE.markCompleted(d.id(), d.createdAt(), done, 123L);
        DispatchJob dj = REPO.findById(d.id()).orElseThrow();
        assertThat(dj.status()).isEqualTo(DispatchJobStatus.COMPLETED);
        assertThat(dj.completedAt()).isEqualTo(done);
        assertThat(dj.durationMillis()).isEqualTo(123L);
    }

    @Test
    void aLateCallbackNeverOverwritesASettledJob() {
        for (String settled : List.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED")) {
            Case c = seedJob(settled, null, 1);
            Map<String, Object> before = rawRow(c.id());
            LIFECYCLE.markCompleted(c.id(), c.createdAt(), Instant.now(), 1L);
            LIFECYCLE.markFailed(c.id(), c.createdAt(), "late");
            LIFECYCLE.scheduleRetry(c.id(), c.createdAt(), Instant.now(), 3, "late");
            LIFECYCLE.reschedule(c.id(), c.createdAt(), Instant.now());
            assertThat(rawRow(c.id())).as("%s survives every late callback outcome", settled).isEqualTo(before);
        }
    }

    // ── enter / leave refusals are visible ─────────────────────────────────

    @Test
    void aRefusedTransitionIsExposedOnTheCollector() {
        Case c = seedJob("COMPLETED", null, 1);
        LIFECYCLE.markFailed(c.id(), c.createdAt(), "late");
        var snapshots = DispatchJobLifecycle.collector().collect();
        var counter = snapshots.stream().filter(s -> s.getMetadata().getName().equals("fc_dispatch_job_transition_refused"))
                .findFirst().orElseThrow();
        var fail = counter.getDataPoints().stream()
                .filter(dp -> "fail".equals(dp.getLabels().get("transition"))).findFirst().orElseThrow();
        assertThat(((io.prometheus.metrics.model.snapshots.CounterSnapshot.CounterDataPointSnapshot) fail).getValue())
                .isEqualTo((double) DispatchJobLifecycle.refused(Transition.FAIL));
        assertThat(DispatchJobLifecycle.refused(Transition.FAIL)).isGreaterThanOrEqualTo(1);
    }

    // ── creation is always PENDING ─────────────────────────────────────────

    @Test
    void creationBornPendingEvenWhenTheEntityCarriesAnotherStatus() {
        DispatchJob template = REPO.findById(seedJob("PENDING", null, 1).id()).orElseThrow();
        String id = Tsid.generate();
        DispatchJob settled = new DispatchJob(id, template.externalId(), template.kind(), code("born"),
                template.source(), template.subject(), template.targetUrl(), template.protocol(), template.payload(),
                template.payloadContentType(), template.dataOnly(), template.eventId(), template.correlationId(),
                template.clientId(), template.subscriptionId(), template.serviceAccountId(), template.dispatchPoolId(),
                template.messageGroup(), template.mode(), template.sequence(), template.timeoutSeconds(),
                template.schemaId(), template.maxRetries(), template.retryStrategy(), DispatchJobStatus.COMPLETED,
                template.attemptCount(), template.lastError(), template.metadata(), template.idempotencyKey(),
                template.descriptor(), template.queue(), Instant.now(), Instant.now(), null, null, null, null, null);
        LIFECYCLE.insertBatch(List.of(settled));
        assertThat(rawRow(id).get("status")).isEqualTo("PENDING");
    }
}
