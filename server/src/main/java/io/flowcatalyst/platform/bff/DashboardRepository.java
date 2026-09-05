package io.flowcatalyst.platform.bff;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;

/// The exact + approximate counts behind `GET /bff/dashboard/stats` (bff
/// spec §2). Exact counts are plain `COUNT(*)` against the control-plane
/// tables; the message-plane tables (`msg_events`, `msg_dispatch_jobs`,
/// `aud_logs`, `iam_login_attempts`) are approximated from
/// `pg_class.reltuples` so the endpoint stays constant-time regardless of
/// table size (spec §9 D2: zero until the first `ANALYZE`). `pg_class` is
/// outside our generated schema (Postgres system catalog), so this is the
/// one place a query is built directly against an untyped `pg_catalog`
/// table rather than generated jOOQ fields.
public final class DashboardRepository {

    private final DSLContext dsl;

    public DashboardRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// `{totalClients, activeUsers, rolesDefined}`.
    public record ExactCounts(long totalClients, long activeUsers, long rolesDefined) {
    }

    public ExactCounts exactCounts() {
        long totalClients = dsl.fetchCount(TNT_CLIENTS);
        long activeUsers = dsl.fetchCount(IAM_PRINCIPALS,
                IAM_PRINCIPALS.TYPE.eq("USER").and(IAM_PRINCIPALS.ACTIVE.isTrue()));
        long rolesDefined = dsl.fetchCount(IAM_ROLES);
        return new ExactCounts(totalClients, activeUsers, rolesDefined);
    }

    /// `GREATEST(reltuples, 0)::bigint` for every relation named in `tables`,
    /// keyed by table name; a table with no matching `pg_class` row (fresh
    /// install, not yet created) reads 0.
    public Map<String, Long> approximateCounts(String... tables) {
        Field<String> relname = DSL.field(DSL.name("relname"), SQLDataType.VARCHAR);
        Field<Double> reltuples = DSL.field(DSL.name("reltuples"), SQLDataType.FLOAT);
        Field<String> relkind = DSL.field(DSL.name("relkind"), SQLDataType.VARCHAR);
        Table<?> pgClass = DSL.table(DSL.name("pg_catalog", "pg_class"));

        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : tables) out.put(t, 0L);

        List<Record2<String, Double>> rows = dsl.select(relname, reltuples)
                .from(pgClass)
                .where(relname.in(Arrays.asList(tables))).and(relkind.eq("r"))
                .fetch();
        for (var row : rows) {
            double raw = row.value2() == null ? 0 : row.value2();
            out.put(row.value1(), Math.max((long) raw, 0L));
        }
        return out;
    }
}
