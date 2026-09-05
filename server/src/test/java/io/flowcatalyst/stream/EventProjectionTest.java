package io.flowcatalyst.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS_READ;
import static io.flowcatalyst.stream.StreamFixture.DB;
import static org.assertj.core.api.Assertions.assertThat;

/// `EventProjection` (stream spec §4, `event_projection`) against a real
/// embedded Postgres. A very large claim batch size guards against the
/// `msg_events` backlog other test classes leave behind (CONVENTIONS §6).
class EventProjectionTest {

    private static final int BIG_BATCH = 200_000;
    private static final EventProjection PROJECTION = new EventProjection(StreamFixture.DS);

    @Test
    @DisplayName("derived columns, data-as-text, and created_at/projected_at are set exactly as spec §4")
    void derivedColumnsAndTimestamps() {
        String type = StreamFixture.type("proj");
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String eventId = StreamFixture.event(type, "test://src", "subj", "{\"x\":1}", "corr-1", "grp-1", null,
                createdAt);

        int claimed = PROJECTION.step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var row = DB.selectFrom(MSG_EVENTS_READ).where(MSG_EVENTS_READ.ID.eq(eventId)).fetchOne();
        assertThat(row).as("a projected row for the claimed event").isNotNull();
        String[] seg = type.split(":");
        assertThat(row.getApplication()).isEqualTo(seg[0]);
        assertThat(row.getSubdomain()).isEqualTo(seg[1]);
        assertThat(row.getAggregate()).isEqualTo(seg[2]);
        assertThat(row.getData()).isEqualTo("{\"x\": 1}");
        assertThat(row.getCreatedAt().toInstant()).as("same partition key as the source row").isEqualTo(createdAt);
        assertThat(row.getProjectedAt()).isNotNull();

        // The source row is marked projected.
        var sourceProjectedAt = DB.select(MSG_EVENTS.PROJECTED_AT).from(MSG_EVENTS)
                .where(MSG_EVENTS.ID.eq(eventId)).fetchOne(MSG_EVENTS.PROJECTED_AT);
        assertThat(sourceProjectedAt).isNotNull();
    }

    @Test
    @DisplayName("subdomain and aggregate are null when the type has fewer than three segments")
    void shortTypeLeavesTrailingSegmentsNull() {
        String type = "app" + StreamFixture.RUN + "shorttype";
        String eventId = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        PROJECTION.step(BIG_BATCH);

        var row = DB.selectFrom(MSG_EVENTS_READ).where(MSG_EVENTS_READ.ID.eq(eventId)).fetchOne();
        assertThat(row.getApplication()).isEqualTo(type);
        assertThat(row.getSubdomain()).isNull();
        assertThat(row.getAggregate()).isNull();
    }

    @Test
    @DisplayName("an already-projected event is not re-selected by a later step")
    void alreadyProjectedEventIsNotReclaimed() {
        String type = StreamFixture.type("noreclaim");
        String eventId = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        PROJECTION.step(BIG_BATCH);
        var firstProjectedAt = DB.select(MSG_EVENTS_READ.PROJECTED_AT).from(MSG_EVENTS_READ)
                .where(MSG_EVENTS_READ.ID.eq(eventId)).fetchOne(MSG_EVENTS_READ.PROJECTED_AT);

        PROJECTION.step(BIG_BATCH);
        var secondProjectedAt = DB.select(MSG_EVENTS_READ.PROJECTED_AT).from(MSG_EVENTS_READ)
                .where(MSG_EVENTS_READ.ID.eq(eventId)).fetchOne(MSG_EVENTS_READ.PROJECTED_AT);

        assertThat(secondProjectedAt).as("the row is not re-inserted/updated by a later step").isEqualTo(firstProjectedAt);
    }
}
