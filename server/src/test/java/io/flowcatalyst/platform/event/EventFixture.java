package io.flowcatalyst.platform.event;

import io.flowcatalyst.db.generated.tables.records.MsgEventsReadRecord;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS_READ;

/// Seeds the two event tables the way the platform does (spec §9): the
/// write side through a real [UnitOfWork] with the [PlatformSink], the read
/// side either by running the stream processor's projection over those rows
/// ([#project]) or by inserting a projected row directly ([#readRow] +
/// [#insert]) when a test needs to choose `created_at` / `client_id`. Each
/// test class owns a namespace — the `application` segment of every type —
/// so tests never see one another's rows.
public final class EventFixture {

    public static final DataSource DS = TestPg.dataSource();
    public static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    public static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace; each test class adds its own tag, so the first `:`
    /// segment of every seeded type (`app<tag><run>`) is unique to the class.
    public static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    /// Both tables are partitioned by month around "now" — every seeded
    /// `created_at` stays inside that window (spec §9).
    public static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private EventFixture() {
    }

    /// A minimal domain event whose subject is `platform.<aggregate>.<id>`.
    public record Seeded(EventMetadata metadata, Object data) implements DomainEvent {
    }

    /// A payload record for seeded events.
    public record Payload(String note, int n) {
    }

    /// The `application` segment for one test class: `app<tag><RUN>`.
    public static String application(String tag) {
        return "app" + tag + RUN;
    }

    /// `<application>:<subdomain>:<aggregate>:<verb>` — a type inside a class's namespace.
    public static String type(String application, String subdomain, String aggregate, String verb) {
        return application + ":" + subdomain + ":" + aggregate + ":" + verb;
    }

    /// Emits one event through the unit of work (the sink stamps
    /// `created_at = now()`, `client_id = NULL`) and returns its id.
    public static String emit(String type, String subject, String principalId, Instant occurredAt,
                              String correlationId, String messageGroup, Object data) {
        var metadata = new EventMetadata(Tsid.generate(), EventMetadata.SPEC_VERSION, "platform:admin",
                type, subject, occurredAt, correlationId, null, principalId, UUID.randomUUID().toString(), messageGroup);
        return UOW.emitEvent(new Seeded(metadata, data), data).eventId();
    }

    /// The stream processor's projection (spec §9) over the given write-side
    /// rows; returns how many read rows were inserted.
    public static int project(List<String> ids) {
        int inserted = DB.execute("""
                INSERT INTO msg_events_read
                    (id, spec_version, type, source, subject, time, data,
                     correlation_id, causation_id, deduplication_id, message_group,
                     client_id, application, subdomain, aggregate, created_at, projected_at)
                SELECT e.id, e.spec_version, e.type, e.source, e.subject, e.time, e.data::text,
                       e.correlation_id, e.causation_id, e.deduplication_id, e.message_group,
                       e.client_id,
                       split_part(e.type, ':', 1),
                       NULLIF(split_part(e.type, ':', 2), ''),
                       NULLIF(split_part(e.type, ':', 3), ''),
                       e.created_at,
                       NOW()
                  FROM msg_events e
                 WHERE e.id = ANY(?)
                ON CONFLICT (id, created_at) DO NOTHING
                """, (Object) ids.toArray(String[]::new));
        DB.execute("UPDATE msg_events SET projected_at = NOW() WHERE id = ANY(?)", (Object) ids.toArray(String[]::new));
        return inserted;
    }

    /// A projected row with the projection's defaults — the three segments
    /// derived from `type`, `spec_version = '1.0'`, `deduplication_id =
    /// <type>-<id>`, `time = created_at`, `projected_at = now` — for a test to
    /// adjust before [#insert]. The id is a fresh untyped TSID.
    public static MsgEventsReadRecord readRow(String type, String clientId, Instant createdAt) {
        var r = DB.newRecord(MSG_EVENTS_READ);
        String id = Tsid.generate();
        String[] seg = type.split(":");
        r.setId(id);
        r.setSpecVersion("1.0");
        r.setType(type);
        r.setSource("test://" + seg[0]);
        r.setSubject("platform." + (seg.length > 2 ? seg[2] : "x") + "." + id);
        r.setTime(createdAt.atOffset(ZoneOffset.UTC));
        r.setData("{\"seeded\":true}");
        r.setDeduplicationId(type + "-" + id);
        r.setClientId(clientId);
        r.setApplication(seg[0]);
        r.setSubdomain(seg.length > 1 ? seg[1] : null);
        r.setAggregate(seg.length > 2 ? seg[2] : null);
        r.setCreatedAt(createdAt.atOffset(ZoneOffset.UTC));
        r.setProjectedAt(NOW.atOffset(ZoneOffset.UTC));
        return r;
    }

    /// Inserts a row built by [#readRow] and returns its id.
    public static String insert(MsgEventsReadRecord r) {
        DB.insertInto(MSG_EVENTS_READ).set(r).execute();
        return r.getId();
    }
}
