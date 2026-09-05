package io.flowcatalyst.platform.role;

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

import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `source` column fails the read loudly instead of
/// defaulting silently (spec §1). [#cleanup] deletes everything this class
/// inserts — belt-and-braces against `RoleApi`'s `/by-source/{source}`
/// listing or any other unfiltered read (`TestPg` never truncates between
/// tests).
class RoleRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final RoleRepository repo = new RoleRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String applicationId, String name, String source) {
        String id = EntityType.ROLE.generate();
        DB.insertInto(IAM_ROLES)
                .set(IAM_ROLES.ID, id)
                .set(IAM_ROLES.APPLICATION_ID, applicationId)
                .set(IAM_ROLES.NAME, name)
                .set(IAM_ROLES.DISPLAY_NAME, "corrupt")
                .set(IAM_ROLES.SOURCE, source)
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(IAM_ROLES).where(IAM_ROLES.ID.in(INSERTED)).execute();
    }

    /// `chk_iam_roles_source` (migration 051) now blocks a fresh write of
    /// an unrecognised source, so the constraint is dropped for the seed
    /// insert AND the assertions, and the row is deleted again before
    /// restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByNameRejectsAnUnrecognisedSourceInsteadOfDefaultingToDatabase() {
        String name = "corrupt." + RUN + ".a";
        TestPg.withConstraintDropped(DS, "iam_roles", "chk_iam_roles_source", () -> {
            String id = insert(null, name, "IMPORTED");
            try {
                assertThatThrownBy(() -> repo.findByName(name))
                        .isInstanceOf(CorruptRoleException.class)
                        .satisfies(e -> assertThat(((CorruptRoleException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(IAM_ROLES).where(IAM_ROLES.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        String applicationId = "app_" + RUN;
        String goodName = "corrupt." + RUN + ".good";
        insert(applicationId, goodName, "DATABASE");
        TestPg.withConstraintDropped(DS, "iam_roles", "chk_iam_roles_source", () -> {
            String corrupt = insert(applicationId, "corrupt." + RUN + ".bad", "WHO_KNOWS");
            try {
                assertThatThrownBy(() -> repo.findByApplicationId(applicationId))
                        .isInstanceOf(CorruptRoleException.class)
                        .satisfies(e -> assertThat(((CorruptRoleException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findByName(goodName)).isPresent();
            } finally {
                DB.deleteFrom(IAM_ROLES).where(IAM_ROLES.ID.eq(corrupt)).execute();
            }
        });
    }
}
