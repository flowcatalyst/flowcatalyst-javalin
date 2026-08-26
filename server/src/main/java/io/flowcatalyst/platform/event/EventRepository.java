package io.flowcatalyst.platform.event;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.MsgEvents;
import io.flowcatalyst.db.generated.tables.MsgEventsRead;
import io.flowcatalyst.db.generated.tables.records.MsgEventsReadRecord;
import io.flowcatalyst.db.generated.tables.records.MsgEventsRecord;
import io.flowcatalyst.platform.event.Event.ContextEntry;
import io.flowcatalyst.platform.event.Event.Projection;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.database.VisibilitySql;
import io.flowcatalyst.platform.shared.json.Json;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS_READ;

/// `msg_events` (write side) + `msg_events_read` (read side) via jOOQ —
/// reads only (spec §9): the rows are written by the unit-of-work sink and
/// projected by the stream processor, never here. Every lockfile route reads
/// the projected table; [#findRecentRaw] is the one read over the write-side
/// table (spec §6). Pure CRUD — no domain decisions live here.
/// `asText()`/`isTextual()` are deprecated in Jackson 3 for `stringValue()`/
/// `isString()`, which are NOT equivalent (throws on non-string, `null` not
/// `""` for JSON `null`) — kept deliberately, suppressed rather than migrated.
@SuppressWarnings("deprecation")
public final class EventRepository {

    private static final MsgEventsRead R = MSG_EVENTS_READ;
    private static final MsgEvents W = MSG_EVENTS;

    /// Filtered and raw reads: `limit <= 0` or `> MAX` falls back to the default (spec §7).
    static final int LIST_MAX_LIMIT = 1000;
    static final int LIST_DEFAULT_LIMIT = 100;
    /// Facet read: same guard family (spec §7).
    static final int FACET_MAX_LIMIT = 1000;
    static final int FACET_DEFAULT_LIMIT = 200;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public EventRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Filters for [#findWithFilters] (spec §3); `null` = no filter on that
    /// column, and an empty list is no filter either. `since` / `until` are
    /// inclusive bounds on `created_at` — the partition key. `visibility` is
    /// not a filter but whose view this is (spec §8) and is required — a
    /// caller states it, it never defaults open.
    public record ListFilter(String type, String source, String subject, String clientId, String correlationId,
                             Instant since, Instant until,
                             List<String> types, List<String> clientIds, List<String> applications,
                             List<String> subdomains, List<String> aggregates,
                             Visibility visibility) {
        public ListFilter {
            types = types == null ? List.of() : List.copyOf(types);
            clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
            applications = applications == null ? List.of() : List.copyOf(applications);
            subdomains = subdomains == null ? List.of() : List.copyOf(subdomains);
            aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
            Objects.requireNonNull(visibility, "visibility");
        }

        /// No filters, every row — the anchor's unfiltered view.
        public static ListFilter none() {
            return new ListFilter(null, null, null, null, null, null, null, null, null, null, null, null, Visibility.Everything.INSTANCE);
        }
    }

    /// The closed set of `msg_events_read` columns a facet may be taken over (spec §4).
    public enum Facet {
        TYPE(R.TYPE),
        SOURCE(R.SOURCE),
        SUBJECT(R.SUBJECT),
        CLIENT_ID(R.CLIENT_ID),
        CORRELATION_ID(R.CORRELATION_ID),
        APPLICATION(R.APPLICATION),
        SUBDOMAIN(R.SUBDOMAIN),
        AGGREGATE(R.AGGREGATE);

        private final Field<String> column;

        Facet(Field<String> column) {
            this.column = column;
        }
    }

    // ── Reads (msg_events_read) ────────────────────────────────────────────

    /// One projected row by id (spec §5) — `context` is empty, the projection present.
    public Optional<Event> findById(String id) {
        return dsl.selectFrom(R)
                .where(R.ID.eq(id))
                .fetchOptional()
                .map(EventRepository::toEntity);
    }

    /// Projected rows matching every filter and the visibility, newest first
    /// (`created_at DESC`), `offset` rows in, at most `limit` rows.
    public List<Event> findWithFilters(ListFilter f, int limit, int offset) {
        Condition where = DSL.noCondition();
        if (f.type() != null) where = where.and(R.TYPE.eq(f.type()));
        if (!f.types().isEmpty()) where = where.and(R.TYPE.in(f.types()));
        if (f.source() != null) where = where.and(R.SOURCE.eq(f.source()));
        if (f.subject() != null) where = where.and(R.SUBJECT.eq(f.subject()));
        if (f.clientId() != null) where = where.and(R.CLIENT_ID.eq(f.clientId()));
        if (!f.clientIds().isEmpty()) where = where.and(R.CLIENT_ID.in(f.clientIds()));
        where = where.and(VisibilitySql.toCondition(f.visibility(), R.CLIENT_ID)); // spec §8
        if (!f.applications().isEmpty()) where = where.and(R.APPLICATION.in(f.applications()));
        if (!f.subdomains().isEmpty()) where = where.and(R.SUBDOMAIN.in(f.subdomains()));
        if (!f.aggregates().isEmpty()) where = where.and(R.AGGREGATE.in(f.aggregates()));
        if (f.correlationId() != null) where = where.and(R.CORRELATION_ID.eq(f.correlationId()));
        if (f.since() != null) where = where.and(R.CREATED_AT.ge(f.since().atOffset(ZoneOffset.UTC)));
        if (f.until() != null) where = where.and(R.CREATED_AT.le(f.until().atOffset(ZoneOffset.UTC)));
        return dsl.selectFrom(R)
                .where(where)
                .orderBy(R.CREATED_AT.desc())
                .limit(guard(limit, LIST_MAX_LIMIT, LIST_DEFAULT_LIMIT))
                .offset(Math.max(offset, 0))
                .fetch(EventRepository::toEntity);
    }

    /// The distinct non-null values of one facet column, ascending, at most `limit`.
    public List<String> distinctValues(Facet facet, int limit) {
        return dsl.selectDistinct(facet.column).from(R)
                .where(facet.column.isNotNull())
                .orderBy(facet.column.asc())
                .limit(guard(limit, FACET_MAX_LIMIT, FACET_DEFAULT_LIMIT))
                .fetch(facet.column);
    }

    // ── Reads (msg_events) ─────────────────────────────────────────────────

    /// The most recent `limit` write-side rows, newest first, with their
    /// `context_data` and no projection (spec §6).
    public List<Event> findRecentRaw(int limit) {
        return dsl.selectFrom(W)
                .orderBy(W.CREATED_AT.desc())
                .limit(guard(limit, LIST_MAX_LIMIT, LIST_DEFAULT_LIMIT))
                .fetch(EventRepository::toEntity);
    }

    /// Out-of-range limits are corrected, not rejected (spec §7).
    private static int guard(int limit, int max, int fallback) {
        return limit <= 0 || limit > max ? fallback : limit;
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Event toEntity(MsgEventsReadRecord row) {
        return new Event(
                row.getId(),
                row.getSpecVersion(),
                row.getType(),
                row.getSource(),
                row.getSubject(),
                row.getTime().toInstant(),
                fromJsonText(row.getData(), "msg_events_read.data"),
                List.of(),
                row.getDeduplicationId(),
                row.getClientId(),
                row.getMessageGroup(),
                row.getCorrelationId(),
                row.getCausationId(),
                row.getCreatedAt().toInstant(),
                new Projection(row.getApplication(), row.getSubdomain(), row.getAggregate(), row.getProjectedAt().toInstant()));
    }

    private static Event toEntity(MsgEventsRecord row) {
        return new Event(
                row.getId(),
                row.getSpecVersion(),
                row.getType(),
                row.getSource(),
                row.getSubject(),
                row.getTime().toInstant(),
                fromJsonb(row.getData(), "msg_events.data"),
                contextEntries(fromJsonb(row.getContextData(), "msg_events.context_data")),
                row.getDeduplicationId(),
                row.getClientId(),
                row.getMessageGroup(),
                row.getCorrelationId(),
                row.getCausationId(),
                row.getCreatedAt().toInstant(),
                null);
    }

    /// `context_data` → entries; anything but an array of `{key, value}` objects reads as empty (spec §6).
    private static List<ContextEntry> contextEntries(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var out = new ArrayList<ContextEntry>(node.size());
        for (JsonNode e : node) {
            if (e.hasNonNull("key") && e.hasNonNull("value")) out.add(new ContextEntry(e.get("key").asText(), e.get("value").asText()));
        }
        return out;
    }

    private static JsonNode fromJsonb(JSONB jsonb, String column) {
        return jsonb == null ? null : fromJsonText(jsonb.data(), column);
    }

    /// A `NULL`/empty column is absent; text that is not JSON is a data
    /// fault surfaced as a failure (spec §9), never silently read as absent.
    private static JsonNode fromJsonText(String text, String column) {
        if (text == null || text.isBlank()) return null;
        try {
            return Json.MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalStateException(column + " is not valid JSON", e);
        }
    }
}
