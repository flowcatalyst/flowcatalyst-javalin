package io.flowcatalyst.platform.subscription;

import io.flowcatalyst.db.generated.tables.MsgSubscriptionCustomConfigs;
import io.flowcatalyst.db.generated.tables.MsgSubscriptionEventTypes;
import io.flowcatalyst.db.generated.tables.MsgSubscriptions;
import io.flowcatalyst.db.generated.tables.records.MsgSubscriptionCustomConfigsRecord;
import io.flowcatalyst.db.generated.tables.records.MsgSubscriptionEventTypesRecord;
import io.flowcatalyst.db.generated.tables.records.MsgSubscriptionsRecord;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTION_CUSTOM_CONFIGS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTION_EVENT_TYPES;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `msg_subscriptions` + `msg_subscription_event_types` +
/// `msg_subscription_custom_configs` via jOOQ. Reads hydrate both junctions
/// in one `IN` query each; writes happen only on the unit of work's
/// transaction ([Persist]) and replace the junction rows wholesale (spec §9).
/// Pure CRUD — no domain decisions live here.
public final class SubscriptionRepository implements Persist<Subscription> {

    private static final MsgSubscriptions T = MSG_SUBSCRIPTIONS;
    private static final MsgSubscriptionEventTypes ET = MSG_SUBSCRIPTION_EVENT_TYPES;
    private static final MsgSubscriptionCustomConfigs CC = MSG_SUBSCRIPTION_CUSTOM_CONFIGS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public SubscriptionRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Equality filters for [#findWithFilters]; `null` = no filter on that column.
    public record ListFilter(String status, String clientId) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Subscription> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The subscription with `code` under `clientId`; a `null` client id
    /// matches platform-wide rows only (spec §6).
    public Optional<Subscription> findByCodeAndClient(String code, String clientId) {
        Condition client = clientId == null ? T.CLIENT_ID.isNull() : T.CLIENT_ID.eq(clientId);
        return findOne(T.CODE.eq(code).and(client));
    }

    /// Every subscription stamped with `applicationCode`, by code — the set
    /// sync reconciles against (spec §7).
    public List<Subscription> findByApplicationCode(String applicationCode) {
        return findMany(T.APPLICATION_CODE.eq(applicationCode));
    }

    /// Subscriptions matching every non-null filter, by code.
    public List<Subscription> findWithFilters(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.status() != null) where = where.and(T.STATUS.eq(f.status()));
        if (f.clientId() != null) where = where.and(T.CLIENT_ID.eq(f.clientId()));
        return findMany(where);
    }

    /// Every `ACTIVE` subscription, ordered by id — the fan-out subscription
    /// cache's load (stream spec §3): "every `ACTIVE` `msg_subscriptions` row
    /// with its `msg_subscription_event_types` patterns (`LEFT JOIN`, so a
    /// subscription with no patterns is loaded and matches nothing), ordered
    /// by id". [#bindingsFor] already implements the effective `LEFT JOIN` —
    /// a subscription with no bindings gets `List.of()`, and
    /// [Subscription#matchesEventType] on an empty list matches nothing.
    public List<Subscription> findActiveOrderedById() {
        var rows = dsl.selectFrom(T).where(T.STATUS.eq(SubscriptionStatus.ACTIVE.name())).orderBy(T.ID.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var ids = rows.getValues(T.ID);
        var bindings = bindingsFor(ids);
        var configs = configsFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row,
                bindings.getOrDefault(row.getId(), List.of()), configs.getOrDefault(row.getId(), List.of()))));
    }

    private Optional<Subscription> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(row -> {
            var ids = List.of(row.getId());
            return toEntity(row, bindingsFor(ids).getOrDefault(row.getId(), List.of()),
                    configsFor(ids).getOrDefault(row.getId(), List.of()));
        });
    }

    private List<Subscription> findMany(Condition where) {
        var rows = dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var ids = rows.getValues(T.ID);
        var bindings = bindingsFor(ids);
        var configs = configsFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row,
                bindings.getOrDefault(row.getId(), List.of()), configs.getOrDefault(row.getId(), List.of()))));
    }

    /// Bindings for many subscriptions in one query, each list in row order.
    private Map<String, List<EventTypeBinding>> bindingsFor(List<String> subscriptionIds) {
        return dsl.selectFrom(ET)
                .where(ET.SUBSCRIPTION_ID.in(subscriptionIds))
                .orderBy(ET.SUBSCRIPTION_ID.asc(), ET.ID.asc())
                .fetch().stream()
                .collect(groupingBy(MsgSubscriptionEventTypesRecord::getSubscriptionId,
                        mapping(SubscriptionRepository::toBinding, toList())));
    }

    /// Config entries for many subscriptions in one query, each list in row order.
    private Map<String, List<ConfigEntry>> configsFor(List<String> subscriptionIds) {
        return dsl.selectFrom(CC)
                .where(CC.SUBSCRIPTION_ID.in(subscriptionIds))
                .orderBy(CC.SUBSCRIPTION_ID.asc(), CC.ID.asc())
                .fetch().stream()
                .collect(groupingBy(MsgSubscriptionCustomConfigsRecord::getSubscriptionId,
                        mapping(SubscriptionRepository::toConfigEntry, toList())));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)` and replaces the junction rows
    /// wholesale. `created_by` and `created_at` are written once and never
    /// updated; `updated_at` is stamped `now()` here, not taken from the
    /// aggregate (spec §9). A binding's `filter` has no column (spec §1).
    @Override
    public void persist(Subscription s, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CODE, s.code());
        row.put(T.APPLICATION_CODE, s.applicationCode());
        row.put(T.NAME, s.name());
        row.put(T.DESCRIPTION, s.description());
        row.put(T.CLIENT_ID, s.clientId());
        row.put(T.CLIENT_IDENTIFIER, s.clientIdentifier());
        row.put(T.CLIENT_SCOPED, s.clientScoped());
        row.put(T.CONNECTION_ID, s.connectionId());
        row.put(T.TARGET, s.endpoint());
        row.put(T.QUEUE, s.queue());
        row.put(T.SOURCE, s.source().name());
        row.put(T.STATUS, s.status().name());
        row.put(T.MAX_AGE_SECONDS, s.maxAgeSeconds());
        row.put(T.DISPATCH_POOL_ID, s.dispatchPoolId());
        row.put(T.DISPATCH_POOL_CODE, s.dispatchPoolCode());
        row.put(T.DELAY_SECONDS, s.delaySeconds());
        row.put(T.SEQUENCE, s.sequence());
        row.put(T.MODE, s.mode().name());
        row.put(T.TIMEOUT_SECONDS, s.timeoutSeconds());
        row.put(T.MAX_RETRIES, s.maxRetries());
        row.put(T.SERVICE_ACCOUNT_ID, s.serviceAccountId());
        row.put(T.DATA_ONLY, s.dataOnly());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, s.id())
                .set(T.CREATED_BY, s.createdBy())
                .set(T.CREATED_AT, utc(s.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();

        txDsl.deleteFrom(ET).where(ET.SUBSCRIPTION_ID.eq(s.id())).execute();
        txDsl.deleteFrom(CC).where(CC.SUBSCRIPTION_ID.eq(s.id())).execute();
        for (EventTypeBinding b : s.eventTypes()) {
            txDsl.insertInto(ET)
                    .set(ET.SUBSCRIPTION_ID, s.id())
                    .set(ET.EVENT_TYPE_ID, b.eventTypeId())
                    .set(ET.EVENT_TYPE_CODE, b.eventTypeCode())
                    .set(ET.SPEC_VERSION, b.specVersion())
                    .execute();
        }
        for (ConfigEntry c : s.customConfig()) {
            txDsl.insertInto(CC)
                    .set(CC.SUBSCRIPTION_ID, s.id())
                    .set(CC.CONFIG_KEY, c.key())
                    .set(CC.CONFIG_VALUE, c.value())
                    .execute();
        }
    }

    /// Removes the junction rows, then the subscription.
    @Override
    public void delete(Subscription s, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(ET).where(ET.SUBSCRIPTION_ID.eq(s.id())).execute();
        txDsl.deleteFrom(CC).where(CC.SUBSCRIPTION_ID.eq(s.id())).execute();
        txDsl.deleteFrom(T).where(T.ID.eq(s.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Subscription toEntity(MsgSubscriptionsRecord row, List<EventTypeBinding> eventTypes,
                                         List<ConfigEntry> customConfig) {
        return new Subscription(
                row.getId(),
                row.getCode(),
                row.getApplicationCode(),
                row.getName(),
                row.getDescription(),
                row.getClientId(),
                row.getClientIdentifier(),
                row.getClientScoped(),
                eventTypes,
                row.getConnectionId(),
                row.getTarget(),
                row.getQueue(),
                customConfig,
                source(row.getId(), row.getSource()),
                status(row.getId(), row.getStatus()),
                row.getMaxAgeSeconds(),
                row.getDispatchPoolId(),
                row.getDispatchPoolCode(),
                row.getDelaySeconds(),
                row.getSequence(),
                DispatchMode.parse(row.getMode()),
                row.getTimeoutSeconds(),
                row.getMaxRetries(),
                row.getServiceAccountId(),
                row.getDataOnly(),
                row.getCreatedBy(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static EventTypeBinding toBinding(MsgSubscriptionEventTypesRecord row) {
        return new EventTypeBinding(row.getEventTypeId(), row.getEventTypeCode(), row.getSpecVersion(), null);
    }

    private static ConfigEntry toConfigEntry(MsgSubscriptionCustomConfigsRecord row) {
        return new ConfigEntry(row.getConfigKey(), row.getConfigValue());
    }

    /// [SubscriptionStatus#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06).
    private static SubscriptionStatus status(String rowId, String stored) {
        try {
            return SubscriptionStatus.parse(stored);
        } catch (SubscriptionStatus.UnrecognisedSubscriptionStatusException e) {
            throw new CorruptSubscriptionException(rowId, e);
        }
    }

    /// [SubscriptionSource#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06).
    private static SubscriptionSource source(String rowId, String stored) {
        try {
            return SubscriptionSource.parse(stored);
        } catch (SubscriptionSource.UnrecognisedSubscriptionSourceException e) {
            throw new CorruptSubscriptionException(rowId, e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
