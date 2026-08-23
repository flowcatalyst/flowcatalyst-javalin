package io.flowcatalyst.platform.docs.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.docs.AppDoc;
import io.flowcatalyst.platform.docs.AppDocRepository;
import io.flowcatalyst.platform.docs.PublishedDocs;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.DOCS_VIEW;

/// The `/api/docs` surface (spec §4) — read-only. Every handler does exactly:
/// coarse permission → read → response; there are no use cases behind these
/// routes. Every handler runs inside [Auth#scoped].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/docs` | 200 [DocListResponse] |
/// | GET | `/api/docs/platform/{slug}` | 200 [DocResponse] |
/// | GET | `/api/docs/applications/{appCode}/{slug}` | 200 [DocResponse] |
public final class DocsApi {

    private DocsApi() {
    }

    /// The handlers' dependencies: the application-synced pages, the
    /// applications they belong to, and the embedded platform pages.
    public record State(AppDocRepository appDocs, ApplicationRepository apps, PublishedDocs published) {
        public State {
            Objects.requireNonNull(appDocs, "appDocs");
            Objects.requireNonNull(apps, "apps");
            Objects.requireNonNull(published, "published");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/docs", Auth.scoped(ctx -> list(ctx, s)));
        routes.get("/api/docs/platform/{slug}", Auth.scoped(ctx -> getPlatform(ctx, s)));
        routes.get("/api/docs/applications/{appCode}/{slug}", Auth.scoped(ctx -> getApplication(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        Checks.require(Auth.current(), DOCS_VIEW);
        ctx.json(new DocListResponse(platformSummaries(s), applicationGroups(s)));
    }

    private static void getPlatform(Context ctx, State s) {
        Checks.require(Auth.current(), DOCS_VIEW);
        PublishedDocs.Page page = publishedPage(s, ctx.pathParam("slug"));
        ctx.json(new DocResponse(page.slug(), page.title(), s.published().content(page)));
    }

    private static void getApplication(Context ctx, State s) {
        Checks.require(Auth.current(), DOCS_VIEW);
        Application app = applicationByCode(s, ctx.pathParam("appCode"));
        ctx.json(DocResponse.from(appDoc(s, app.id(), ctx.pathParam("slug"))));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static List<DocSummary> platformSummaries(State s) {
        return s.published().pages().stream().map(DocSummary::from).toList();
    }

    /// One group per application that has pages *and* still exists — an
    /// orphaned `application_id` never breaks the index (spec §4); groups by
    /// application name, then code for a deterministic tie-break.
    private static List<AppDocsGroup> applicationGroups(State s) {
        return s.appDocs().applicationIdsWithDocs().stream()
                .flatMap(id -> s.apps().findById(id).stream())
                .sorted(Comparator.comparing(Application::name).thenComparing(Application::code))
                .map(app -> new AppDocsGroup(app.code(), app.name(),
                        s.appDocs().listByApplication(app.id()).stream().map(DocSummary::from).toList()))
                .toList();
    }

    private static PublishedDocs.Page publishedPage(State s, String slug) {
        return s.published().find(slug).orElseThrow(() -> HttpError.notFound("Doc", slug));
    }

    private static Application applicationByCode(State s, String code) {
        return s.apps().findByCode(code).orElseThrow(() -> HttpError.notFound("Application", code));
    }

    private static AppDoc appDoc(State s, String applicationId, String slug) {
        return s.appDocs().findByApplicationAndSlug(applicationId, slug).orElseThrow(() -> HttpError.notFound("Doc", slug));
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// `{slug, title}` — one page reference.
    public record DocSummary(String slug, String title) {
        static DocSummary from(AppDoc.Summary s) {
            return new DocSummary(s.slug(), s.title());
        }

        static DocSummary from(PublishedDocs.Page p) {
            return new DocSummary(p.slug(), p.title());
        }
    }

    /// `{applicationCode, applicationName, docs[]}` — one application's synced pages.
    public record AppDocsGroup(String applicationCode, String applicationName, List<DocSummary> docs) {
        public AppDocsGroup {
            docs = docs == null ? List.of() : List.copyOf(docs);
        }
    }

    /// `{platform[], applications[]}` — both arrays always present, never `null`.
    public record DocListResponse(List<DocSummary> platform, List<AppDocsGroup> applications) {
        public DocListResponse {
            platform = platform == null ? List.of() : List.copyOf(platform);
            applications = applications == null ? List.of() : List.copyOf(applications);
        }
    }

    /// `{slug, title, content}` — `content` is raw Markdown; rendering is the SPA's job.
    public record DocResponse(String slug, String title, String content) {
        static DocResponse from(AppDoc d) {
            return new DocResponse(d.slug(), d.title(), d.content());
        }
    }
}
