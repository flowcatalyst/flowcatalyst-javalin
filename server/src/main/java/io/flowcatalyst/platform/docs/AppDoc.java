package io.flowcatalyst.platform.docs;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// One application-synced documentation page (spec §3): a Markdown body an
/// application pushed through the SDK sync surface, identified within its
/// application by a kebab-case [AppDocSlug]. Pages are never edited one by
/// one — a sync replaces the application's whole set (spec §3, §5), so the
/// only transition is [#syncedFrom], the in-place update a sync applies to a
/// slug it already stores.
///
/// @param position the page's index in the last sync payload — the list order
public record AppDoc(
        String id,
        String applicationId,
        String slug,
        String title,
        String content,
        int position,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public AppDoc {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A page as first stored by a sync: fresh `doc_` id, `createdAt = updatedAt = now`.
    public static AppDoc create(String applicationId, AppDocSlug slug, String title, String content, int position, Instant now) {
        return new AppDoc(EntityType.APP_DOC.generate(), applicationId, slug.value(), title, content, position, now, now);
    }

    /// The in-place update a sync applies to a slug it already stores: title,
    /// content and position follow the payload, `updatedAt` is re-stamped, the
    /// id and `createdAt` are kept (spec §3).
    public AppDoc syncedFrom(String newTitle, String newContent, int newPosition, Instant now) {
        return new AppDoc(id, applicationId, slug, newTitle, newContent, newPosition, createdAt, now);
    }

    /// The page without its body — the list surfaces (spec §3).
    public record Summary(String slug, String title) {
        public Summary {
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(title, "title");
        }
    }

    /// One page of a sync payload after validation, in payload order: the
    /// parsed slug, the resolved title (see [#titleFor]) and the raw body.
    public record Input(AppDocSlug slug, String title, String content) {
        public Input {
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(content, "content");
        }
    }

    /// Resolves a page title (spec §5): the explicit title trimmed when
    /// non-blank, else the content's first `# ` heading, else the slug.
    /// `explicit` may be `null` (absent on the wire).
    public static String titleFor(String explicit, String content, AppDocSlug slug) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        return Markdown.firstHeading(content).orElse(slug.value());
    }
}
