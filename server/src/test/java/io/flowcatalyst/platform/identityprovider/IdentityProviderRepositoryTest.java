package io.flowcatalyst.platform.identityprovider;

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

import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `type` column fails the read loudly instead of
/// defaulting silently (spec §1).
///
/// `IdentityProvider` has no per-resource scoping dimension — `findAll`
/// reads every row — so a corrupt row left behind here would be picked up
/// by any other test's broad read for the rest of the suite (`TestPg` never
/// truncates between tests). [#cleanup] deletes everything this class
/// inserts.
class IdentityProviderRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final IdentityProviderRepository repo = new IdentityProviderRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String code, String type) {
        String id = EntityType.IDENTITY_PROVIDER.generate();
        DB.insertInto(OAUTH_IDENTITY_PROVIDERS)
                .set(OAUTH_IDENTITY_PROVIDERS.ID, id)
                .set(OAUTH_IDENTITY_PROVIDERS.CODE, code)
                .set(OAUTH_IDENTITY_PROVIDERS.NAME, "corrupt")
                .set(OAUTH_IDENTITY_PROVIDERS.TYPE, type)
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.in(INSERTED)).execute();
    }

    /// `chk_oauth_identity_providers_type` (migration 051) now blocks a
    /// fresh write of an unrecognised type, so the constraint is dropped
    /// for the seed insert AND the assertions, and the row is deleted again
    /// before restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedTypeInsteadOfDefaultingToInternal() {
        TestPg.withConstraintDropped(DS, "oauth_identity_providers", "chk_oauth_identity_providers_type", () -> {
            String id = insert("corrupt-" + RUN + "-a", "SAML");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptIdentityProviderException.class)
                        .satisfies(e -> assertThat(((CorruptIdentityProviderException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByCodeRejectsAnUnrecognisedTypeToo() {
        String code = "corrupt-" + RUN + "-b";
        TestPg.withConstraintDropped(DS, "oauth_identity_providers", "chk_oauth_identity_providers_type", () -> {
            String id = insert(code, "WHO_KNOWS");
            try {
                assertThatThrownBy(() -> repo.findByCode(code))
                        .isInstanceOf(CorruptIdentityProviderException.class)
                        .satisfies(e -> assertThat(((CorruptIdentityProviderException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.eq(id)).execute();
            }
        });
    }

    /// `findAll` reads the whole table (no scoping filter on this
    /// aggregate), so this only pins that a corrupt row anywhere fails the
    /// list rather than being silently dropped or coerced — not which row.
    @Test
    void aCorruptRowFailsTheWholeListRead() {
        TestPg.withConstraintDropped(DS, "oauth_identity_providers", "chk_oauth_identity_providers_type", () -> {
            String id = insert("corrupt-" + RUN + "-c", "NOT_A_TYPE");
            try {
                assertThatThrownBy(repo::findAll).isInstanceOf(CorruptIdentityProviderException.class);
            } finally {
                DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.eq(id)).execute();
            }
        });
    }
}
