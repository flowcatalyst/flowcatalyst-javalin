package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.APP_PLATFORM_CONFIGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `scope`/`value_type` column fails the read loudly
/// instead of defaulting silently (spec §1.1). Rows are scoped under this
/// run's own `applicationCode`, and [#cleanup] deletes everything this
/// class inserts — belt-and-braces against any unfiltered read elsewhere
/// (`TestPg` never truncates between tests).
class PlatformConfigRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PlatformConfigRepository repo = new PlatformConfigRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String applicationCode, String section, String property, String scope, String valueType) {
        String id = EntityType.PLATFORM_CONFIG.generate();
        DB.insertInto(APP_PLATFORM_CONFIGS)
                .set(APP_PLATFORM_CONFIGS.ID, id)
                .set(APP_PLATFORM_CONFIGS.APPLICATION_CODE, applicationCode)
                .set(APP_PLATFORM_CONFIGS.SECTION, section)
                .set(APP_PLATFORM_CONFIGS.PROPERTY, property)
                .set(APP_PLATFORM_CONFIGS.SCOPE, scope)
                .set(APP_PLATFORM_CONFIGS.VALUE_TYPE, valueType)
                .set(APP_PLATFORM_CONFIGS.VALUE, "v")
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(APP_PLATFORM_CONFIGS).where(APP_PLATFORM_CONFIGS.ID.in(INSERTED)).execute();
    }

    /// `chk_app_platform_configs_scope` (migration 051) now blocks a fresh
    /// write of an unrecognised scope, so the constraint is dropped for the
    /// seed insert AND the assertions, and the row is deleted again before
    /// restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedScopeInsteadOfDefaultingToGlobal() {
        String application = "app-" + RUN + "-a";
        TestPg.withConstraintDropped(DS, "app_platform_configs", "chk_app_platform_configs_scope", () -> {
            String id = insert(application, "sec", "prop", "TENANT", "PLAIN");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptPlatformConfigException.class)
                        .satisfies(e -> assertThat(((CorruptPlatformConfigException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(APP_PLATFORM_CONFIGS).where(APP_PLATFORM_CONFIGS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByIdRejectsAnUnrecognisedValueTypeInsteadOfDefaultingToPlain() {
        String application = "app-" + RUN + "-b";
        TestPg.withConstraintDropped(DS, "app_platform_configs", "chk_app_platform_configs_value_type", () -> {
            String id = insert(application, "sec", "prop", "GLOBAL", "ENCRYPTED");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptPlatformConfigException.class)
                        .satisfies(e -> assertThat(((CorruptPlatformConfigException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(APP_PLATFORM_CONFIGS).where(APP_PLATFORM_CONFIGS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        String application = "app-" + RUN + "-c";
        String good = insert(application, "sec", "good", "GLOBAL", "PLAIN");
        TestPg.withConstraintDropped(DS, "app_platform_configs", "chk_app_platform_configs_scope", () -> {
            String corrupt = insert(application, "sec", "bad", "WHO_KNOWS", "PLAIN");
            try {
                assertThatThrownBy(() -> repo.findByApplication(application))
                        .isInstanceOf(CorruptPlatformConfigException.class)
                        .satisfies(e -> assertThat(((CorruptPlatformConfigException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findByCoordinate(new ConfigCoordinate(application, "sec", "good", null))).isPresent();
            } finally {
                DB.deleteFrom(APP_PLATFORM_CONFIGS).where(APP_PLATFORM_CONFIGS.ID.eq(corrupt)).execute();
            }
        });
    }
}
