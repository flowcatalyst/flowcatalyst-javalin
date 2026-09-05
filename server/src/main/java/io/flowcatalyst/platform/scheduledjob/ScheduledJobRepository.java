package io.flowcatalyst.platform.scheduledjob;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.MsgScheduledJobs;
import io.flowcatalyst.db.generated.tables.records.MsgScheduledJobsRecord;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.database.VisibilitySql;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_SCHEDULED_JOBS;

/// `msg_scheduled_jobs` via jOOQ. Aggregate writes happen only on the unit
/// of work's transaction ([Persist]); the one direct write, [#markFired], is
/// the poller's bookkeeping (spec §7). Pure CRUD — no domain decisions.
public final class ScheduledJobRepository implements Persist<ScheduledJob> {

    private static final MsgScheduledJobs T = MSG_SCHEDULED_JOBS;

    /// Reads (and the poller's bookkeeping): jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ScheduledJobRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// The client dimension of a list or a scope lookup (spec §4, §8). This
    /// is *not* the caller's [Visibility] (which only narrows whose rows are
    /// seen): it is the explicit scope the caller asks for — no filter, the
    /// platform scope (`client_id IS NULL`, the wire's `clientId=platform`
    /// literal and a sync's `clientId = null`), or one client's rows — so it
    /// stays a package-local type alongside the shared one.
    public sealed interface ClientFilter {
        record Any() implements ClientFilter {
        }

        record PlatformOnly() implements ClientFilter {
        }

        record Of(String clientId) implements ClientFilter {
            public Of {
                Objects.requireNonNull(clientId, "clientId");
            }
        }

        /// `null` = platform scope, else that client — the command-side spelling (sync, by-code).
        static ClientFilter scope(String clientId) {
            return clientId == null ? new PlatformOnly() : new Of(clientId);
        }
    }

    /// Filters for [#findWithFilters] / [#countWithFilters]; `null` = no
    /// filter on `status` / `search`; `client` and `visibility` (whose view
    /// this is, enforced in SQL so `total` and the page agree) are required
    /// — a caller states them, they never default open.
    ///
    /// @param status raw stored value (`ACTIVE` …); an unknown value matches nothing
    /// @param search case-insensitive substring of `code` or `name`
    public record ListFilter(ClientFilter client, String status, String search, Visibility visibility) {
        public ListFilter {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<ScheduledJob> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The job with `code` in the given scope: `clientId` null = platform-scoped (`client_id IS NULL`).
    public Optional<ScheduledJob> findByCode(String code, String clientId) {
        return findOne(T.CODE.eq(code).and(clientCondition(ClientFilter.scope(clientId))));
    }

    /// Every job in one client scope, by code — the sync's existing set (spec §8).
    public List<ScheduledJob> findInScope(ClientFilter scope) {
        return findMany(clientCondition(scope));
    }

    /// `ACTIVE` jobs, by code — the poller's set (spec §7).
    public List<ScheduledJob> findActive() {
        return findMany(T.STATUS.eq(ScheduledJobStatus.ACTIVE.name()));
    }

    /// One page of jobs matching the filter, by code.
    public List<ScheduledJob> findWithFilters(ListFilter f, int limit, int offset) {
        return List.copyOf(dsl.selectFrom(T).where(condition(f)).orderBy(T.CODE.asc()).limit(limit).offset(offset)
                .fetch().map(ScheduledJobRepository::toEntity));
    }

    /// The total for [#findWithFilters]'s filter, ignoring the page.
    public long countWithFilters(ListFilter f) {
        return dsl.fetchCount(T, condition(f));
    }

    private Condition condition(ListFilter f) {
        Condition where = clientCondition(f.client());
        if (f.status() != null) where = where.and(T.STATUS.eq(f.status()));
        if (f.search() != null) {
            String pattern = "%" + f.search() + "%";
            where = where.and(T.CODE.likeIgnoreCase(pattern).or(T.NAME.likeIgnoreCase(pattern)));
        }
        return where.and(VisibilitySql.toCondition(f.visibility(), T.CLIENT_ID));
    }

    private static Condition clientCondition(ClientFilter c) {
        return switch (c) {
            case ClientFilter.Any _ -> DSL.noCondition();
            case ClientFilter.PlatformOnly _ -> T.CLIENT_ID.isNull();
            case ClientFilter.Of of -> T.CLIENT_ID.eq(of.clientId());
        };
    }

    private Optional<ScheduledJob> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(ScheduledJobRepository::toEntity);
    }

    private List<ScheduledJob> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch().map(ScheduledJobRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_by` and `created_at` are
    /// written once and never updated; `updated_at` is stamped `now()` here
    /// (spec §7).
    @Override
    public void persist(ScheduledJob j, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CLIENT_ID, j.clientId());
        row.put(T.APPLICATION_ID, j.applicationId());
        row.put(T.CODE, j.code());
        row.put(T.NAME, j.name());
        row.put(T.DESCRIPTION, j.description());
        row.put(T.STATUS, j.status().name());
        row.put(T.CRONS, j.crons().toArray(String[]::new));
        row.put(T.TIMEZONE, j.timezone());
        row.put(T.PAYLOAD, toJsonb(j.payload()));
        row.put(T.CONCURRENT, j.concurrent());
        row.put(T.TRACKS_COMPLETION, j.tracksCompletion());
        row.put(T.TIMEOUT_SECONDS, j.timeoutSeconds());
        row.put(T.DELIVERY_MAX_ATTEMPTS, j.deliveryMaxAttempts());
        row.put(T.TARGET_URL, j.targetUrl());
        row.put(T.LAST_FIRED_AT, utc(j.lastFiredAt()));
        row.put(T.UPDATED_AT, utc(Instant.now()));
        row.put(T.UPDATED_BY, j.updatedBy());
        row.put(T.VERSION, j.version());
        txDsl.insertInto(T)
                .set(T.ID, j.id())
                .set(T.CREATED_BY, j.createdBy())
                .set(T.CREATED_AT, utc(j.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Removes the job row only; instances and logs stay (spec §4, open question 4).
    @Override
    public void delete(ScheduledJob j, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(j.id())).execute();
    }

    // ── Poller bookkeeping (direct, spec §7) ───────────────────────────────

    /// `last_fired_at = GREATEST(last_fired_at, slot)` — monotonic, so a slow
    /// or duplicate poll can never re-open an already-fired window; `version`
    /// is deliberately not bumped. A direct write: the fired slot is
    /// bookkeeping, not a use case.
    public void markFired(String id, Instant slot) {
        dsl.update(T)
                .set(T.LAST_FIRED_AT, DSL.greatest(T.LAST_FIRED_AT, DSL.val(utc(slot), T.LAST_FIRED_AT)))
                .where(T.ID.eq(id))
                .execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ScheduledJob toEntity(MsgScheduledJobsRecord row) {
        String[] crons = row.getCrons();
        return new ScheduledJob(
                row.getId(),
                row.getClientId(),
                row.getApplicationId(),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                status(row.getId(), row.getStatus()),
                crons == null ? List.of() : Arrays.asList(crons),
                row.getTimezone(),
                fromJsonb(row.getPayload()),
                row.getConcurrent(),
                row.getTracksCompletion(),
                row.getTimeoutSeconds(),
                row.getDeliveryMaxAttempts(),
                row.getTargetUrl(),
                instant(row.getLastFiredAt()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant(),
                row.getCreatedBy(),
                row.getUpdatedBy(),
                row.getVersion());
    }

    static JSONB toJsonb(JsonNode node) {
        return node == null ? null : JSONB.jsonb(Json.write(node));
    }

    /// A stored JSON `null` (another writer's "cleared") reads as no value.
    static JsonNode fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isEmpty()) return null;
        try {
            JsonNode node = Json.MAPPER.readTree(jsonb.data());
            return node.isNull() ? null : node;
        } catch (JacksonException e) {
            throw new IllegalStateException("stored jsonb is not valid JSON", e);
        }
    }

    /// [ScheduledJobStatus#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06).
    private static ScheduledJobStatus status(String rowId, String stored) {
        try {
            return ScheduledJobStatus.parse(stored);
        } catch (ScheduledJobStatus.UnrecognisedScheduledJobStatusException e) {
            throw new CorruptScheduledJobException("scheduled job", rowId, e);
        }
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
