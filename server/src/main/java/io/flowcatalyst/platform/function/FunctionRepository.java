package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnAliases;
import io.flowcatalyst.db.generated.tables.FnFunctions;
import io.flowcatalyst.db.generated.tables.records.FnAliasesRecord;
import io.flowcatalyst.db.generated.tables.records.FnFunctionsRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.flowcatalyst.db.generated.Tables.FN_ALIASES;
import static io.flowcatalyst.db.generated.Tables.FN_FUNCTIONS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/// `fn_functions` + `fn_aliases` via jOOQ (spec `function-registry.md` §6.1).
/// Aliases are hydrated in one `IN` query per read (`CONVENTIONS.md` §8) and
/// replaced wholesale on write; writes happen only on the unit of work's
/// transaction ([Persist]). Pure CRUD — no domain decisions live here.
public final class FunctionRepository implements Persist<Function> {

    private static final FnFunctions T = FN_FUNCTIONS;
    private static final FnAliases A = FN_ALIASES;

    private final DSLContext dsl;

    public FunctionRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// `null` = no filter on that column. [#pattern] never becomes a `LIKE`
    /// — it is turned into whole-segment column equalities (spec §3.3, §8 M3).
    public record ListFilter(FunctionAddressPattern pattern, String clientId, FunctionStatus status) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Function> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    public Optional<Function> findByAddress(FunctionAddress address) {
        Objects.requireNonNull(address, "address");
        return findOne(addressCondition(address));
    }

    /// Every function matching every non-null filter, ordered by address.
    public List<Function> list(ListFilter filter) {
        Objects.requireNonNull(filter, "filter");
        Condition where = DSL.noCondition();
        if (filter.pattern() != null) {
            where = where.and(patternCondition(filter.pattern()));
        }
        if (filter.clientId() != null) {
            where = where.and(T.CLIENT_ID.eq(filter.clientId()));
        }
        if (filter.status() != null) {
            where = where.and(T.STATUS.eq(filter.status().name()));
        }
        return findMany(where);
    }

    private Optional<Function> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(row -> {
            var ids = List.of(row.getId());
            return toEntity(row, aliasesFor(ids).getOrDefault(row.getId(), List.of()));
        });
    }

    private List<Function> findMany(Condition where) {
        var rows = dsl.selectFrom(T).where(where)
                .orderBy(T.APPLICATION_CODE.asc(), T.SERVICE_NAME.asc(), T.NAME.asc())
                .fetch();
        if (rows.isEmpty()) {
            return List.of();
        }
        var ids = rows.getValues(T.ID);
        var aliases = aliasesFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row, aliases.getOrDefault(row.getId(), List.of()))));
    }

    /// Aliases for many functions in one query, each list in stored order.
    private Map<String, List<Function.FunctionAlias>> aliasesFor(List<String> functionIds) {
        return dsl.selectFrom(A)
                .where(A.FUNCTION_ID.in(functionIds))
                .orderBy(A.FUNCTION_ID.asc(), A.ALIAS.asc())
                .fetch().stream()
                .collect(groupingBy(FnAliasesRecord::getFunctionId, mapping(FunctionRepository::toAlias, toList())));
    }

    private static Condition addressCondition(FunctionAddress address) {
        return T.APPLICATION_CODE.eq(address.application().value())
                .and(T.SERVICE_NAME.eq(address.service().value()))
                .and(T.NAME.eq(address.name().value()));
    }

    /// A pattern becomes column equalities, never `LIKE` (spec §3.3, §8 M3):
    /// whole-segment comparison, matching [FunctionAddressPattern#matches] exactly.
    private static Condition patternCondition(FunctionAddressPattern pattern) {
        return switch (pattern) {
            case FunctionAddressPattern.Exact(FunctionAddress address) -> addressCondition(address);
            case FunctionAddressPattern.Service(DnsLabel application, DnsLabel service) ->
                    T.APPLICATION_CODE.eq(application.value()).and(T.SERVICE_NAME.eq(service.value()));
            case FunctionAddressPattern.Application(DnsLabel application) -> T.APPLICATION_CODE.eq(application.value());
        };
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts `fn_functions` — `SET`: `description`, `status`, `updated_at`
    /// only (spec §6.1: no transition moves `application_id`, `address`,
    /// `client_id` or `runtime`, so the upsert cannot either — §8 M11). Then
    /// deletes alias rows absent from [Function#aliases] and upserts the
    /// rest (`SET`: `version_id`, `updated_by`, `updated_at` — §8 M16).
    @Override
    public void persist(Function f, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.insertInto(T)
                .set(T.ID, f.id())
                .set(T.APPLICATION_ID, f.applicationId())
                .set(T.APPLICATION_CODE, f.address().application().value())
                .set(T.SERVICE_NAME, f.address().service().value())
                .set(T.NAME, f.address().name().value())
                .set(T.CLIENT_ID, f.clientId())
                .set(T.RUNTIME, f.runtime().name())
                .set(T.DESCRIPTION, f.description())
                .set(T.STATUS, f.status().name())
                .set(T.CREATED_AT, utc(f.createdAt()))
                .set(T.UPDATED_AT, utc(f.updatedAt()))
                .onConflict(T.ID).doUpdate()
                .set(T.DESCRIPTION, f.description())
                .set(T.STATUS, f.status().name())
                .set(T.UPDATED_AT, utc(f.updatedAt()))
                .execute();

        Set<String> keep = f.aliases().stream().map(Function.FunctionAlias::alias).collect(toSet());
        Condition staleAliases = keep.isEmpty()
                ? A.FUNCTION_ID.eq(f.id())
                : A.FUNCTION_ID.eq(f.id()).and(A.ALIAS.notIn(keep));
        txDsl.deleteFrom(A).where(staleAliases).execute();

        for (Function.FunctionAlias a : f.aliases()) {
            txDsl.insertInto(A)
                    .set(A.FUNCTION_ID, f.id())
                    .set(A.ALIAS, a.alias())
                    .set(A.VERSION_ID, a.versionId())
                    .set(A.UPDATED_BY, a.updatedBy())
                    .set(A.UPDATED_AT, utc(a.updatedAt()))
                    .onConflict(A.FUNCTION_ID, A.ALIAS).doUpdate()
                    .set(A.VERSION_ID, a.versionId())
                    .set(A.UPDATED_BY, a.updatedBy())
                    .set(A.UPDATED_AT, utc(a.updatedAt()))
                    .execute();
        }
    }

    /// Aliases and routes cascade via FK; a function with published versions
    /// fails on the `fn_versions` FK — deleting a function that has versions
    /// is package B's decision (spec §6.1, open question 4).
    @Override
    public void delete(Function f, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(f.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Function toEntity(FnFunctionsRecord row, List<Function.FunctionAlias> aliases) {
        FunctionAddress address = FunctionAddress.of(
                new DnsLabel(row.getApplicationCode()), new DnsLabel(row.getServiceName()), new DnsLabel(row.getName()));
        return new Function(
                row.getId(),
                row.getApplicationId(),
                address,
                row.getClientId(),
                Runtime.parse(row.getRuntime()),
                row.getDescription(),
                FunctionStatus.parse(row.getStatus()),
                aliases,
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static Function.FunctionAlias toAlias(FnAliasesRecord row) {
        return new Function.FunctionAlias(row.getAlias(), row.getVersionId(), row.getUpdatedBy(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
