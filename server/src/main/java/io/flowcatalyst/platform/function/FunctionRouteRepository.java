package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnRoutes;
import io.flowcatalyst.db.generated.tables.records.FnRoutesRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.FN_ROUTES;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `fn_routes` via jOOQ (spec `function-invocation.md` §3, amending
/// `function-registry.md` §6.6). Not an aggregate — [#replaceForFunction]
/// materialises a function's published manifest's `public[]` list wholesale;
/// nothing here writes a single route in isolation.
public final class FunctionRouteRepository {

    private static final FnRoutes T = FN_ROUTES;

    private final DSLContext dsl;

    public FunctionRouteRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    public List<FunctionRoute> listByFunction(String functionId) {
        return List.copyOf(dsl.selectFrom(T).where(T.FUNCTION_ID.eq(functionId))
                .orderBy(T.HOSTNAME.asc(), T.PATH_PREFIX.asc()).fetch().map(FunctionRouteRepository::toEntity));
    }

    public List<FunctionRoute> listByHostname(Hostname hostname) {
        Objects.requireNonNull(hostname, "hostname");
        return List.copyOf(dsl.selectFrom(T).where(T.HOSTNAME.eq(hostname.value()))
                .orderBy(T.HOSTNAME.asc(), T.PATH_PREFIX.asc()).fetch().map(FunctionRouteRepository::toEntity));
    }

    /// Every route whose hostname is covered by `zone` — `zone` itself or any
    /// hostname strictly under it (spec `function-zones-and-aliases.md` §1's
    /// `DOMAIN_IN_USE`: "any `fn_routes` row's hostname is covered by the
    /// zone"). The exact-hostname clause and the strictly-under clause stay
    /// two conditions `OR`ed in one query, not one collapsed into the other.
    public List<FunctionRoute> listUnder(Hostname zone) {
        Objects.requireNonNull(zone, "zone");
        return List.copyOf(dsl.selectFrom(T)
                .where(T.HOSTNAME.eq(zone.value()).or(T.HOSTNAME.endsWith("." + zone.value())))
                .orderBy(T.HOSTNAME.asc(), T.PATH_PREFIX.asc()).fetch().map(FunctionRouteRepository::toEntity));
    }

    /// The desired-state batch read: every route of every function named by
    /// `functionIds`, one query, grouped by function.
    public Map<String, List<FunctionRoute>> listByFunctions(Collection<String> functionIds) {
        if (functionIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(T).where(T.FUNCTION_ID.in(functionIds))
                .orderBy(T.FUNCTION_ID.asc(), T.HOSTNAME.asc(), T.PATH_PREFIX.asc())
                .fetch().stream()
                .collect(groupingBy(FnRoutesRecord::getFunctionId, mapping(FunctionRouteRepository::toEntity, toList())));
    }

    /// The conflict lookup RouteSync (package F) names the other function
    /// from: the public route with this exact hostname and path prefix.
    public Optional<FunctionRoute> findPublic(Hostname hostname, RoutePattern pathPrefix) {
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        return dsl.selectFrom(T)
                .where(T.HOSTNAME.eq(hostname.value())).and(T.PATH_PREFIX.eq(pathPrefix.value()))
                .fetchOptional().map(FunctionRouteRepository::toEntity);
    }

    /// Delete-then-insert, in the caller's transaction: a function's routes
    /// are always replaced wholesale from its manifest, never patched
    /// piecemeal (spec `function-registry.md` §6.6).
    public void replaceForFunction(String functionId, List<FunctionRoute> routes, DbTx tx) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(tx, "tx");
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(T).where(T.FUNCTION_ID.eq(functionId)).execute();
        for (FunctionRoute r : routes) {
            txDsl.insertInto(T)
                    .set(T.ID, r.id())
                    .set(T.FUNCTION_ID, r.functionId())
                    .set(T.HOSTNAME, r.hostname().value())
                    .set(T.PATH_PREFIX, r.pathPrefix().value())
                    .set(T.CREATED_AT, utc(r.createdAt()))
                    .execute();
        }
    }

    private static FunctionRoute toEntity(FnRoutesRecord row) {
        return new FunctionRoute(
                row.getId(),
                row.getFunctionId(),
                new Hostname(row.getHostname()),
                RoutePattern.parse(row.getPathPrefix()),
                row.getCreatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
