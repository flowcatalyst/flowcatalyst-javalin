package io.flowcatalyst.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS_READ;
import static io.flowcatalyst.stream.StreamFixture.DB;
import static org.assertj.core.api.Assertions.assertThat;

/// `DispatchJobProjection` (stream spec §5, `dispatch_job_projection`)
/// against a real embedded Postgres. A very large claim batch size guards
/// against the `msg_dispatch_jobs` backlog other test classes leave behind
/// (CONVENTIONS §6).
class DispatchJobProjectionTest {

    private static final int BIG_BATCH = 200_000;
    private static final DispatchJobProjection PROJECTION = new DispatchJobProjection(StreamFixture.DS);

    private static Instant readProjectedAt(String id) {
        var v = DB.select(MSG_DISPATCH_JOBS_READ.PROJECTED_AT).from(MSG_DISPATCH_JOBS_READ)
                .where(MSG_DISPATCH_JOBS_READ.ID.eq(id)).fetchOne(MSG_DISPATCH_JOBS_READ.PROJECTED_AT);
        return v == null ? null : v.toInstant();
    }

    @Test
    @DisplayName("derived columns and initial projection of a PENDING job")
    void initialProjection() {
        String code = StreamFixture.type("djp");
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = StreamFixture.dispatchJob(code, "PENDING", createdAt, createdAt);

        int claimed = PROJECTION.step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var row = DB.selectFrom(MSG_DISPATCH_JOBS_READ).where(MSG_DISPATCH_JOBS_READ.ID.eq(id)).fetchOne();
        assertThat(row).isNotNull();
        String[] seg = code.split(":");
        assertThat(row.getApplication()).isEqualTo(seg[0]);
        assertThat(row.getSubdomain()).isEqualTo(seg[1]);
        assertThat(row.getAggregate()).isEqualTo(seg[2]);
        assertThat(row.getIsCompleted()).isFalse();
        assertThat(row.getIsTerminal()).isFalse();
        assertThat(row.getCreatedAt().toInstant()).isEqualTo(createdAt);
        assertThat(row.getProjectedAt()).isNotNull();

        var sourceProjectedAt = DB.select(MSG_DISPATCH_JOBS.PROJECTED_AT).from(MSG_DISPATCH_JOBS)
                .where(MSG_DISPATCH_JOBS.ID.eq(id)).fetchOne(MSG_DISPATCH_JOBS.PROJECTED_AT);
        assertThat(sourceProjectedAt).isNotNull();
    }

    /// T11 (catch-up-2026-09-22.md C1): the read projection carries the
    /// write row's `descriptor` and `metadata` verbatim. Mutant: drop either
    /// column from the UPSERT's SELECT list.
    @Test
    @DisplayName("descriptor and metadata are copied onto the read projection")
    void descriptorAndMetadataAreCopiedOntoTheProjection() {
        String code = StreamFixture.type("djp-descmeta");
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = StreamFixture.dispatchJob(code, "PENDING", createdAt, createdAt);
        DB.update(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.DESCRIPTOR, "Notify Value of user logins")
                .set(MSG_DISPATCH_JOBS.METADATA, org.jooq.JSONB.valueOf("[{\"key\":\"tenant\",\"value\":\"acme\"}]"))
                .where(MSG_DISPATCH_JOBS.ID.eq(id))
                .execute();

        int claimed = PROJECTION.step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var row = DB.selectFrom(MSG_DISPATCH_JOBS_READ).where(MSG_DISPATCH_JOBS_READ.ID.eq(id)).fetchOne();
        assertThat(row.getDescriptor()).isEqualTo("Notify Value of user logins");
        assertThat(row.getMetadata().data()).isEqualTo("[{\"key\": \"tenant\", \"value\": \"acme\"}]");
    }

    @ParameterizedTest(name = "{0} -> completed={1} terminal={2}")
    @CsvSource({
            "PENDING,    false, false",
            "QUEUED,     false, false",
            "PROCESSING, false, false",
            "COMPLETED,  true,  true",
            "FAILED,     false, true",
            "CANCELLED,  false, true",
            "EXPIRED,    false, true",
    })
    void isCompletedAndIsTerminalDerivation(String status, boolean completed, boolean terminal) {
        Instant now = Instant.now();
        String id = StreamFixture.dispatchJob(StreamFixture.type("status-" + status.toLowerCase(java.util.Locale.ROOT)),
                status, now, now);

        PROJECTION.step(BIG_BATCH);

        var row = DB.selectFrom(MSG_DISPATCH_JOBS_READ).where(MSG_DISPATCH_JOBS_READ.ID.eq(id)).fetchOne();
        assertThat(row.getIsCompleted()).isEqualTo(completed);
        assertThat(row.getIsTerminal()).isEqualTo(terminal);
    }

    @Test
    @DisplayName("updated_at > projected_at re-projects a status change; an untouched row is left alone")
    void dirtyRuleReprojectsOnStatusChange() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = StreamFixture.dispatchJob(StreamFixture.type("dirty"), "PENDING", createdAt, createdAt);

        PROJECTION.step(BIG_BATCH);
        Instant firstProjectedAt = readProjectedAt(id);
        assertThat(DB.selectFrom(MSG_DISPATCH_JOBS_READ).where(MSG_DISPATCH_JOBS_READ.ID.eq(id)).fetchOne().getStatus())
                .isEqualTo("PENDING");

        // Not dirty: a second step must not touch it.
        PROJECTION.step(BIG_BATCH);
        assertThat(readProjectedAt(id)).as("untouched row is left alone").isEqualTo(firstProjectedAt);

        // Mutate the source row's status with updated_at strictly after projected_at.
        Instant statusChangedAt = firstProjectedAt.plusSeconds(5);
        DB.update(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.STATUS, "COMPLETED")
                .set(MSG_DISPATCH_JOBS.ATTEMPT_COUNT, 3)
                .set(MSG_DISPATCH_JOBS.COMPLETED_AT, statusChangedAt.atOffset(ZoneOffset.UTC))
                .set(MSG_DISPATCH_JOBS.DURATION_MILLIS, 1234L)
                .set(MSG_DISPATCH_JOBS.UPDATED_AT, statusChangedAt.atOffset(ZoneOffset.UTC))
                .where(MSG_DISPATCH_JOBS.ID.eq(id))
                .execute();

        int reclaimed = PROJECTION.step(BIG_BATCH);
        assertThat(reclaimed).isGreaterThanOrEqualTo(1);

        var updated = DB.selectFrom(MSG_DISPATCH_JOBS_READ).where(MSG_DISPATCH_JOBS_READ.ID.eq(id)).fetchOne();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        assertThat(updated.getAttemptCount()).isEqualTo(3);
        assertThat(updated.getDurationMillis()).isEqualTo(1234L);
        assertThat(updated.getIsCompleted()).isTrue();
        assertThat(updated.getIsTerminal()).isTrue();
        assertThat(updated.getProjectedAt().toInstant()).isAfter(firstProjectedAt);
    }
}
