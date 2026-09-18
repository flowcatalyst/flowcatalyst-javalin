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
import java.util.Objects;
import java.util.Optional;

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

    public List<FunctionDomain> listByClient(String clientId) {
        return List.copyOf(dsl.selectFrom(T).where(T.CLIENT_ID.eq(clientId)).orderBy(T.HOSTNAME.asc())
                .fetch().map(FunctionDomainRepository::toEntity));
    }

    private Optional<FunctionDomain> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(FunctionDomainRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upsert by id. `SET`: `verified_at` only — every other column is
    /// written once at [FunctionDomain#claim] (spec §6.5).
    @Override
    public void persist(FunctionDomain d, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        OffsetDateTime verifiedAt = verifiedAt(d.verification());
        txDsl.insertInto(T)
                .set(T.ID, d.id())
                .set(T.CLIENT_ID, d.clientId())
                .set(T.HOSTNAME, d.hostname().value())
                .set(T.VERIFICATION_TOKEN, d.verificationToken())
                .set(T.VERIFIED_AT, verifiedAt)
                .set(T.CREATED_AT, utc(d.createdAt()))
                .onConflict(T.ID).doUpdate()
                .set(T.VERIFIED_AT, verifiedAt)
                .execute();
    }

    @Override
    public void delete(FunctionDomain d, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(d.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static FunctionDomain toEntity(FnDomainsRecord row) {
        FunctionDomain.Verification verification = row.getVerifiedAt() == null
                ? new FunctionDomain.Verification.Pending()
                : new FunctionDomain.Verification.Verified(row.getVerifiedAt().toInstant());
        return new FunctionDomain(
                row.getId(),
                row.getClientId(),
                new Hostname(row.getHostname()),
                row.getVerificationToken(),
                verification,
                row.getCreatedAt().toInstant());
    }

    private static OffsetDateTime verifiedAt(FunctionDomain.Verification verification) {
        return verification instanceof FunctionDomain.Verification.Verified verified ? utc(verified.at()) : null;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
