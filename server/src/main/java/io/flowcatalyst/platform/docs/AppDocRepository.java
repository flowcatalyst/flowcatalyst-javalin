package io.flowcatalyst.platform.docs;

import io.flowcatalyst.db.generated.tables.AppDocs;
import io.flowcatalyst.db.generated.tables.records.AppDocsRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.flowcatalyst.db.generated.Tables.APP_DOCS;

/// `app_docs` via jOOQ (spec §3). Reads are per application; the single
/// write is the set-level declarative replace a sync performs — pages are
/// never persisted one at a time, so this repository is deliberately not a
/// `Persist<AppDoc>`: the unit of change is the application's whole doc set.
public final class AppDocRepository {

    private static final AppDocs T = APP_DOCS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public AppDocRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// What a declarative replace did (spec §3): the counts and the payload's
    /// slugs in payload order.
    public record ReplaceResult(int created, int updated, int deleted, List<String> slugs) {
        public ReplaceResult {
            slugs = slugs == null ? List.of() : List.copyOf(slugs);
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    /// The application's page summaries in sync order (`position, slug`).
    public List<AppDoc.Summary> listByApplication(String applicationId) {
        return dsl.select(T.SLUG, T.TITLE).from(T)
                .where(T.APPLICATION_ID.eq(applicationId))
                .orderBy(T.POSITION.asc(), T.SLUG.asc())
                .fetch(r -> new AppDoc.Summary(r.get(T.SLUG), r.get(T.TITLE)));
    }

    /// The distinct application ids holding at least one page — the grouped
    /// index's spine; the caller resolves and orders the applications.
    public List<String> applicationIdsWithDocs() {
        return dsl.selectDistinct(T.APPLICATION_ID).from(T).fetch(T.APPLICATION_ID);
    }

    public Optional<AppDoc> findByApplicationAndSlug(String applicationId, String slug) {
        return findOne(T.APPLICATION_ID.eq(applicationId).and(T.SLUG.eq(slug)));
    }

    /// Every page of the application in sync order — the replace's "existing" view.
    public List<AppDoc> findByApplication(String applicationId) {
        return dsl.selectFrom(T)
                .where(T.APPLICATION_ID.eq(applicationId))
                .orderBy(T.POSITION.asc(), T.SLUG.asc())
                .fetch(AppDocRepository::toEntity);
    }

    private Optional<AppDoc> findOne(Condition condition) {
        return dsl.selectFrom(T).where(condition).fetchOptional().map(AppDocRepository::toEntity);
    }

    // ── Write ──────────────────────────────────────────────────────────────

    /// Makes the application's stored set equal `docs`, in payload order,
    /// inside the caller's transaction (spec §3): listed slugs are upserted —
    /// a stored slug keeps its id and `created_at` — and unlisted stored
    /// slugs are deleted. `now` stamps every touched row.
    public ReplaceResult replaceForApplication(DbTx tx, String applicationId, List<AppDoc.Input> docs, Instant now) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        Map<String, AppDoc> existing = findByApplication(applicationId).stream()
                .collect(Collectors.toMap(AppDoc::slug, Function.identity(), (a, _) -> a, LinkedHashMap::new));

        int created = 0;
        int updated = 0;
        var slugs = new ArrayList<String>(docs.size());
        for (int position = 0; position < docs.size(); position++) {
            AppDoc.Input in = docs.get(position);
            AppDoc stored = existing.remove(in.slug().value());
            AppDoc page;
            if (stored == null) {
                page = AppDoc.create(applicationId, in.slug(), in.title(), in.content(), position, now);
                created++;
            } else {
                page = stored.syncedFrom(in.title(), in.content(), position, now);
                updated++;
            }
            upsert(txDsl, page);
            slugs.add(page.slug());
        }
        int deleted = existing.isEmpty() ? 0 : txDsl.deleteFrom(T)
                .where(T.APPLICATION_ID.eq(applicationId)).and(T.SLUG.in(existing.keySet()))
                .execute();
        return new ReplaceResult(created, updated, deleted, slugs);
    }

    /// Upserts one page `ON CONFLICT (application_id, slug)`; `id` and
    /// `created_at` are written once and never updated.
    private static void upsert(DSLContext txDsl, AppDoc d) {
        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.TITLE, d.title());
        row.put(T.CONTENT, d.content());
        row.put(T.POSITION, d.position());
        row.put(T.UPDATED_AT, utc(d.updatedAt()));
        txDsl.insertInto(T)
                .set(T.ID, d.id())
                .set(T.APPLICATION_ID, d.applicationId())
                .set(T.SLUG, d.slug())
                .set(T.CREATED_AT, utc(d.createdAt()))
                .set(row)
                .onConflict(T.APPLICATION_ID, T.SLUG).doUpdate().set(row)
                .execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static AppDoc toEntity(AppDocsRecord r) {
        return new AppDoc(
                r.getId(),
                r.getApplicationId(),
                r.getSlug(),
                r.getTitle(),
                r.getContent(),
                r.getPosition(),
                r.getCreatedAt().toInstant(),
                r.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
