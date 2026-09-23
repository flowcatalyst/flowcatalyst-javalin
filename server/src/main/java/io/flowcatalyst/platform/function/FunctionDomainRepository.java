package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnDomains;
import io.flowcatalyst.db.generated.tables.records.FnDomainsRecord;
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
import java.util.stream.Collectors;

import static io.flowcatalyst.db.generated.Tables.FN_DOMAINS;

/// `fn_domains` via jOOQ (spec `function-registry.md` §6.5).
public final class FunctionDomainRepository implements Persist<FunctionDomain> {

    private static final FnDomains T = FN_DOMAINS;

    private final DSLContext dsl;

    public FunctionDomainRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<FunctionDomain> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    public Optional<FunctionDomain> findByHostname(Hostname hostname) {
        Objects.requireNonNull(hostname, "hostname");
        return findOne(T.HOSTNAME.eq(hostname.value()));
    }

    /// The one claim whose labels suffix `hostname`'s (spec
    /// `function-zones-and-aliases.md` §1) — at most one exists, by the
    /// no-nesting rule [ClaimFunctionDomain] enforces at claim time. One
    /// query over every candidate zone apex ([Hostname#zoneCandidates],
    /// most specific first); the longest match is picked in Java rather than
    /// by the database, since candidates come back in no particular order.
    public Optional<FunctionDomain> covering(Hostname hostname) {
        Objects.requireNonNull(hostname, "hostname");
        List<String> candidates = hostname.zoneCandidates();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<String, FnDomainsRecord> byHostname = dsl.selectFrom(T).where(T.HOSTNAME.in(candidates))
                .fetch().stream().collect(Collectors.toMap(FnDomainsRecord::getHostname, r -> r));
        for (String candidate : candidates) {
            FnDomainsRecord row = byHostname.get(candidate);
            if (row != null) {
                return Optional.of(toEntity(row));
            }
        }
        return Optional.empty();
    }

    /// Whether any existing claim (any owner) is a hostname STRICTLY under
    /// `zone` — a proper descendant, never `zone` itself (spec §1's "is
    /// covered by d" nesting clause). `endsWith` on the field, never a raw
    /// string concatenation done in Java over a `listAll` — one query.
    public boolean anyUnder(Hostname zone) {
        Objects.requireNonNull(zone, "zone");
        return dsl.fetchExists(dsl.selectOne().from(T).where(T.HOSTNAME.endsWith("." + zone.value())));
    }

    public List<FunctionDomain> listByOwner(FunctionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return List.copyOf(dsl.selectFrom(T).where(ownerCondition(owner)).orderBy(T.HOSTNAME.asc())
                .fetch().map(FunctionDomainRepository::toEntity));
    }

    /// `Platform` ⇒ `client_id IS NULL`; `Client` ⇒ `client_id = ?` (spec §6.5).
    private static Condition ownerCondition(FunctionOwner owner) {
        return switch (owner) {
            case FunctionOwner.Platform ignored -> T.CLIENT_ID.isNull();
            case FunctionOwner.Client(String clientId) -> T.CLIENT_ID.eq(clientId);
        };
    }

    private Optional<FunctionDomain> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(FunctionDomainRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Insert-only: every column is written once at [FunctionDomain#claim]
    /// (spec §6.5) and a claim never changes thereafter — nothing updates it
    /// (spec `function-domains-no-dns.md`: no verification state to move).
    @Override
    public void persist(FunctionDomain d, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.insertInto(T)
                .set(T.ID, d.id())
                .set(T.CLIENT_ID, d.owner().clientIdOrNull())
                .set(T.HOSTNAME, d.hostname().value())
                .set(T.CREATED_AT, utc(d.createdAt()))
                .onConflict(T.ID).doNothing()
                .execute();
    }

    @Override
    public void delete(FunctionDomain d, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(d.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static FunctionDomain toEntity(FnDomainsRecord row) {
        return new FunctionDomain(
                row.getId(),
                FunctionOwner.ofClientId(row.getClientId()),
                new Hostname(row.getHostname()),
                row.getCreatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
