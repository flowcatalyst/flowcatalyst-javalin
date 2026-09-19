package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnTriggerObjects;
import io.flowcatalyst.db.generated.tables.records.FnTriggerObjectsRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.flowcatalyst.db.generated.Tables.FN_TRIGGER_OBJECTS;

/// `fn_trigger_objects` via jOOQ (spec `function-invocation.md` §4, §4.2).
///
/// Two reads carry this slice: [#objectIds] is what the two generic SDK
/// syncs (`SyncDispatchPools`, `SyncScheduledJobs`) use to skip a
/// function-owned object in their remove/archive sweep without knowing
/// anything about functions themselves; [#listByFunction] is what a later
/// package's promote reconciliation diffs a new manifest against. [#link]
/// and [#unlink] are for that reconciliation (`TriggerSync`, spec §7 slice
/// I2) — this slice wires no caller for them yet.
public final class TriggerObjectRepository {

    private static final FnTriggerObjects T = FN_TRIGGER_OBJECTS;

    private final DSLContext dsl;

    public TriggerObjectRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Every `object_id` linked to any function under `kind` — one query,
    /// no function context. This is the whole contract the two generic
    /// syncs need: "is this row (by its own id) protected".
    public Set<String> objectIds(TriggerObjectKind kind) {
        Objects.requireNonNull(kind, "kind");
        return Set.copyOf(dsl.select(T.OBJECT_ID).from(T).where(T.KIND.eq(kind.name()))
                .fetchSet(T.OBJECT_ID));
    }

    public List<TriggerObject> listByFunction(String functionId) {
        Objects.requireNonNull(functionId, "functionId");
        return List.copyOf(dsl.selectFrom(T).where(T.FUNCTION_ID.eq(functionId))
                .orderBy(T.KIND.asc(), T.TRIGGER_KEY.asc()).fetch().map(TriggerObjectRepository::toEntity));
    }

    /// Upserts one row `ON CONFLICT (function_id, kind, trigger_key)` —
    /// promote re-linking the same trigger key to a new `object_id` (a
    /// subscription's id does not change across updates today, but the
    /// upsert keeps this correct if that ever changes).
    public void link(TriggerObject obj, DbTx tx) {
        Objects.requireNonNull(obj, "obj");
        Objects.requireNonNull(tx, "tx");
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.insertInto(T)
                .set(T.FUNCTION_ID, obj.functionId())
                .set(T.KIND, obj.kind().name())
                .set(T.OBJECT_ID, obj.objectId())
                .set(T.TRIGGER_KEY, obj.triggerKey())
                .set(T.CREATED_AT, utc(obj.createdAt()))
                .onConflict(T.FUNCTION_ID, T.KIND, T.TRIGGER_KEY)
                .doUpdate()
                .set(T.OBJECT_ID, obj.objectId())
                .execute();
    }

    public void unlink(String functionId, TriggerObjectKind kind, String triggerKey, DbTx tx) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(triggerKey, "triggerKey");
        Objects.requireNonNull(tx, "tx");
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(T)
                .where(T.FUNCTION_ID.eq(functionId)).and(T.KIND.eq(kind.name())).and(T.TRIGGER_KEY.eq(triggerKey))
                .execute();
    }

    private static TriggerObject toEntity(FnTriggerObjectsRecord row) {
        return new TriggerObject(row.getFunctionId(), TriggerObjectKind.parse(row.getKind()), row.getObjectId(),
                row.getTriggerKey(), row.getCreatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
