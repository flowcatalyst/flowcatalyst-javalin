package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnAliases;
import io.flowcatalyst.db.generated.tables.FnFunctions;
import io.flowcatalyst.db.generated.tables.records.FnAliasesRecord;
import io.flowcatalyst.db.generated.tables.records.FnFunctionsRecord;
import io.flowcatalyst.platform.shared.auth.Visibility;
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
    /// `owner` = `Platform` filters to `client_id IS NULL` (spec §6.1, §8 M19).
    public record ListFilter(FunctionAddressPattern pattern, FunctionOwner owner, FunctionStatus status) {
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
        if (filter.owner() != null) {
            where = where.and(ownerCondition(filter.owner()));
        }
        if (filter.status() != null) {
            where = where.and(T.STATUS.eq(filter.status().name()));
        }
        return findMany(where);
    }

    /// The paginated `GET /api/functions` filter (spec `function-api.md`
    /// §4.2): [#pattern] / [#owner] / [#status] are the caller's OPTIONAL
    /// narrowing (`null` = no filter on that column, same as [ListFilter]);
    /// [#visibility] and [#applicationIds] are the caller's MANDATORY reach,
    /// always applied in addition — a caller can only narrow within its own
    /// reach, never widen past it.
    ///
    /// [#visibility] is deliberately NOT [io.flowcatalyst.platform.shared.database.VisibilitySql]'s
    /// usual `client_id IS NULL OR client_id IN (...)`: that shape treats a
    /// platform-scoped row as visible to every authenticated caller, which is
    /// right for a shared resource but wrong here — spec §2 requires a
    /// non-anchor to reach NO platform-owned function at all, so
    /// [Visibility.Tenants] here means "these clients' own rows", full stop
    /// (see [#reachCondition]).
    ///
    /// @param applicationIds empty = no restriction (anchor, or a principal
    ///                       with `allApplications`); non-empty = the
    ///                       application-scoped caller's own set (spec §2's
    ///                       third reach clause)
    public record PageFilter(FunctionAddressPattern pattern, FunctionOwner owner, FunctionStatus status,
                             Visibility visibility, List<String> applicationIds) {
        public PageFilter {
            Objects.requireNonNull(visibility, "visibility");
            applicationIds = applicationIds == null ? List.of() : List.copyOf(applicationIds);
        }
    }

    /// One page of functions matching [PageFilter], ordered by address.
    public List<Function> findWithFilters(PageFilter filter, int limit, int offset) {
        Objects.requireNonNull(filter, "filter");
        var rows = dsl.selectFrom(T).where(pageCondition(filter))
                .orderBy(T.APPLICATION_CODE.asc(), T.SERVICE_NAME.asc(), T.NAME.asc())
                .limit(limit).offset(offset)
                .fetch();
        if (rows.isEmpty()) {
            return List.of();
        }
        var ids = rows.getValues(T.ID);
        var aliases = aliasesFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row, aliases.getOrDefault(row.getId(), List.of()))));
    }

    /// The total for [#findWithFilters]'s filter, ignoring the page.
    public long countWithFilters(PageFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return dsl.fetchCount(T, pageCondition(filter));
    }

    private Condition pageCondition(PageFilter filter) {
        Condition where = DSL.noCondition();
        if (filter.pattern() != null) {
            where = where.and(patternCondition(filter.pattern()));
        }
        if (filter.owner() != null) {
            where = where.and(ownerCondition(filter.owner()));
        }
        if (filter.status() != null) {
            where = where.and(T.STATUS.eq(filter.status().name()));
        }
        where = where.and(reachCondition(filter.visibility()));
        if (!filter.applicationIds().isEmpty()) {
            where = where.and(T.APPLICATION_ID.in(filter.applicationIds()));
        }
        return where;
    }

    /// [Visibility.Everything] ⇒ no restriction (an anchor reaches every
    /// owner). [Visibility.Tenants] ⇒ `client_id IN (...)` ONLY — never `OR
    /// client_id IS NULL` (see [PageFilter]'s doc): an empty tenant list
    /// (a non-anchor with no accessible clients) reaches nothing.
    private static Condition reachCondition(Visibility visibility) {
        return switch (visibility) {
            case Visibility.Everything ignored -> DSL.noCondition();
            case Visibility.Tenants t -> t.clientIds().isEmpty() ? DSL.falseCondition() : T.CLIENT_ID.in(t.clientIds());
        };
    }

    /// How many functions an application owns — `DeleteApplication`'s guard
    /// (spec §4.2: `APPLICATION_HAS_FUNCTIONS`).
    public long countByApplication(String applicationId) {
        Objects.requireNonNull(applicationId, "applicationId");
        return dsl.fetchCount(T, T.APPLICATION_ID.eq(applicationId));
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

    /// `Platform` ⇒ `client_id IS NULL`; `Client` ⇒ `client_id = ?` (spec §6.1, §8 M19).
    private static Condition ownerCondition(FunctionOwner owner) {
        return switch (owner) {
            case FunctionOwner.Platform ignored -> T.CLIENT_ID.isNull();
            case FunctionOwner.Client(String clientId) -> T.CLIENT_ID.eq(clientId);
        };
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
    /// `owner` or `runtime`, so the upsert cannot either — §8 M11). Then
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
                .set(T.CLIENT_ID, f.owner().clientIdOrNull())
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

    /// Deletes the function; versions, aliases and routes cascade via FK
    /// (ruling R4, spec §6.1, §8 M18) — the database does it, this method
    /// need not.
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
                FunctionOwner.ofClientId(row.getClientId()),
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
