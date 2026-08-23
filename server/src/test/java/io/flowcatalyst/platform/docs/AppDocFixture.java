package io.flowcatalyst.platform.docs;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/// Shared wiring for the docs tests: one embedded database, the two
/// repositories, a unit of work, and a per-JVM namespace so application
/// codes never collide with another run on the same database.
public final class AppDocFixture {

    public static final DataSource DS = TestPg.dataSource();
    public static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    public static final AppDocRepository DOCS = new AppDocRepository(DS);
    public static final ApplicationRepository APPS = new ApplicationRepository(DS);

    /// Per-JVM namespace (lowercase alphanumerics — a valid code tail).
    public static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private AppDocFixture() {
    }

    /// A persisted application `docs<tag><RUN>` named `name`.
    public static Application application(String tag, String name) {
        var app = Application.create(ApplicationType.APPLICATION, "docs" + tag + RUN, name);
        UOW.inTransaction(s -> {
            APPS.persist(app, s.dbTx());
            return null;
        });
        return app;
    }

    /// The store's declarative replace, in its own transaction.
    public static AppDocRepository.ReplaceResult replace(String applicationId, List<AppDoc.Input> docs) {
        return UOW.inTransaction(s -> DOCS.replaceForApplication(s.dbTx(), applicationId, docs, Instant.now()));
    }

    public static AppDoc.Input input(String slug, String title, String content) {
        return new AppDoc.Input(new AppDocSlug(slug), title, content);
    }
}
