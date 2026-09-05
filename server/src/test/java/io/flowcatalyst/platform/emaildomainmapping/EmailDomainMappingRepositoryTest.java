package io.flowcatalyst.platform.emaildomainmapping;

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

import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `scope_type` fails the read loudly instead of defaulting
/// to `ANCHOR` — the most privileged scope (spec §1, open question 8).
///
/// Other tests call `findAll()` (a genuine global scan, e.g.
/// `EmailDomainMappingOperationsTest`), so a corrupt row left behind here
/// would fail those too (`TestPg` never truncates between tests).
/// [#cleanup] deletes everything this class inserts.
class EmailDomainMappingRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final EmailDomainMappingRepository repo = new EmailDomainMappingRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String identityProviderId, String scopeType) {
        String id = EntityType.EMAIL_DOMAIN_MAPPING.generate();
        DB.insertInto(TNT_EMAIL_DOMAIN_MAPPINGS)
                .set(TNT_EMAIL_DOMAIN_MAPPINGS.ID, id)
                .set(TNT_EMAIL_DOMAIN_MAPPINGS.EMAIL_DOMAIN, "corrupt-" + id + "." + RUN + ".test")
                .set(TNT_EMAIL_DOMAIN_MAPPINGS.IDENTITY_PROVIDER_ID, identityProviderId)
                .set(TNT_EMAIL_DOMAIN_MAPPINGS.SCOPE_TYPE, scopeType)
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.in(INSERTED)).execute();
    }

    /// `chk_tnt_email_domain_mappings_scope_type` (migration 051) now
    /// blocks a fresh write of an unrecognised scope type, so the
    /// constraint is dropped for the seed insert AND the assertions, and
    /// the row is deleted again before restoring — otherwise restoring it
    /// would itself fail by re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedScopeTypeInsteadOfDefaultingToTheMostPrivilegedScope() {
        TestPg.withConstraintDropped(DS, "tnt_email_domain_mappings", "chk_tnt_email_domain_mappings_scope_type", () -> {
            String id = insert("idp_" + RUN + "_a", "SUPERUSER");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptEmailDomainMappingException.class)
                        .satisfies(e -> assertThat(((CorruptEmailDomainMappingException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        // scoped to a fresh identity-provider id namespace, per TestPg's isolation rule
        String providerId = "idp_" + RUN + "_b";
        String good = insert(providerId, "CLIENT");
        TestPg.withConstraintDropped(DS, "tnt_email_domain_mappings", "chk_tnt_email_domain_mappings_scope_type", () -> {
            String corrupt = insert(providerId, "BOGUS");
            try {
                assertThatThrownBy(() -> repo.findByIdentityProvider(providerId))
                        .isInstanceOf(CorruptEmailDomainMappingException.class)
                        .satisfies(e -> assertThat(((CorruptEmailDomainMappingException) e).rowId()).isEqualTo(corrupt));
                // the good row on its own still reads fine — the failure is specific to the corrupt row
                assertThat(repo.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.eq(corrupt)).execute();
            }
        });
    }

    @Test
    void recognisedScopesStillReadCorrectly() {
        String id = insert("idp_" + RUN + "_c", "PARTNER");
        assertThat(repo.findById(id)).isPresent().get().satisfies(m -> assertThat(m.scopeType()).isEqualTo(ScopeType.PARTNER));
    }
}
