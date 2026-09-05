package io.flowcatalyst.platform.principal;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06 (ruled 2026-09-01): a corrupt `type` or `scope` column fails the
/// read loudly instead of defaulting silently to `USER` / `CLIENT` (spec
/// §1). [#cleanup] deletes everything this class inserts — `TestPg` never
/// truncates between tests, and JUnit runs this suite without parallelism
/// (no `junit-platform.properties`), so the brief `withConstraintDropped`
/// window used here never overlaps another test's read.
class PrincipalRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final PrincipalRepository repo = new PrincipalRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String type, String scope, String email, String domain) {
        String id = EntityType.PRINCIPAL.generate();
        OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id)
                .set(IAM_PRINCIPALS.TYPE, type)
                .set(IAM_PRINCIPALS.SCOPE, scope)
                .set(IAM_PRINCIPALS.NAME, "corrupt")
                .set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, email)
                .set(IAM_PRINCIPALS.EMAIL_DOMAIN, domain)
                .set(IAM_PRINCIPALS.CREATED_AT, now)
                .set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(INSERTED)).execute();
    }

    /// `chk_iam_principals_type` (migration V6) now blocks a fresh write of
    /// an unrecognised type, so the constraint is dropped for the seed
    /// insert AND the assertion, and the row is deleted again before
    /// restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedTypeInsteadOfDefaultingToUser() {
        String domain = "corrupttype" + RUN + ".test";
        TestPg.withConstraintDropped(DS, "iam_principals", "chk_iam_principals_type", () -> {
            String id = insert("BOT", "CLIENT", "a@" + domain, domain);
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptPrincipalException.class)
                        .satisfies(e -> assertThat(((CorruptPrincipalException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByIdRejectsAnUnrecognisedScopeInsteadOfDefaultingToClient() {
        String domain = "corruptscope" + RUN + ".test";
        TestPg.withConstraintDropped(DS, "iam_principals", "chk_iam_principals_scope", () -> {
            String id = insert("USER", "GLOBAL", "b@" + domain, domain);
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptPrincipalException.class)
                        .satisfies(e -> assertThat(((CorruptPrincipalException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(id)).execute();
            }
        });
    }

    /// `findUsersByEmailDomain` filters by `type = 'USER'` and the domain
    /// only — it never filters on `scope` — so a corrupt `scope` value on an
    /// otherwise-USER row still reaches [PrincipalType#parse] via the entity
    /// mapper's [UserScope#parse] call and fails the whole list read, not
    /// just that row.
    @Test
    void aCorruptScopeFailsTheWholeListReadNotJustThatRow() {
        String domain = "corruptscopelist" + RUN + ".test";
        String good = insert("USER", "CLIENT", "good@" + domain, domain);
        TestPg.withConstraintDropped(DS, "iam_principals", "chk_iam_principals_scope", () -> {
            String corrupt = insert("USER", "WHO_KNOWS", "bad@" + domain, domain);
            try {
                assertThatThrownBy(() -> repo.findUsersByEmailDomain(domain))
                        .isInstanceOf(CorruptPrincipalException.class)
                        .satisfies(e -> assertThat(((CorruptPrincipalException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(corrupt)).execute();
            }
        });
    }

    /// No list read of this repository is scoped without also filtering on
    /// `type` (spec §1), so a corrupt `type` value can only be pinned via
    /// the unscoped [PrincipalRepository#findAll]; the brief
    /// `withConstraintDropped` window and this class's own row-scoped
    /// cleanup keep it from colliding with any other test's read.
    @Test
    void aCorruptTypeFailsTheWholeListReadNotJustThatRow() {
        String domain = "corrupttypelist" + RUN + ".test";
        String good = insert("USER", "CLIENT", "good@" + domain, domain);
        TestPg.withConstraintDropped(DS, "iam_principals", "chk_iam_principals_type", () -> {
            String corrupt = insert("ROBOT", "CLIENT", "bad@" + domain, domain);
            try {
                assertThatThrownBy(repo::findAll)
                        .isInstanceOf(CorruptPrincipalException.class)
                        .satisfies(e -> assertThat(((CorruptPrincipalException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(corrupt)).execute();
            }
        });
    }
}
