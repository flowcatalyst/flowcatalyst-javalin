package io.flowcatalyst.platform.client;

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

import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `status` column fails the read loudly instead of
/// defaulting silently (spec §1).
///
/// `Client` has no per-resource scoping dimension — `findAll` reads every
/// row in `tnt_clients` — so a corrupt row left behind here would be picked
/// up by any other test's broad read for the rest of the suite (`TestPg`
/// never truncates between tests). [#cleanup] deletes everything this class
/// inserts.
class ClientRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ClientRepository repo = new ClientRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String identifier, String status) {
        String id = EntityType.CLIENT.generate();
        DB.insertInto(TNT_CLIENTS)
                .set(TNT_CLIENTS.ID, id)
                .set(TNT_CLIENTS.NAME, "corrupt")
                .set(TNT_CLIENTS.IDENTIFIER, identifier)
                .set(TNT_CLIENTS.STATUS, status)
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(TNT_CLIENTS).where(TNT_CLIENTS.ID.in(INSERTED)).execute();
    }

    /// `chk_tnt_clients_status` (migration 051) now blocks a fresh write of
    /// an unrecognised status, so the constraint is dropped for the seed
    /// insert AND the assertions, and the row is deleted again before
    /// restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingToActive() {
        TestPg.withConstraintDropped(DS, "tnt_clients", "chk_tnt_clients_status", () -> {
            String id = insert("corrupt-" + RUN + "-a", "DELETED");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptClientException.class)
                        .satisfies(e -> assertThat(((CorruptClientException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(TNT_CLIENTS).where(TNT_CLIENTS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByIdentifierRejectsAnUnrecognisedStatusToo() {
        String identifier = "corrupt-" + RUN + "-b";
        TestPg.withConstraintDropped(DS, "tnt_clients", "chk_tnt_clients_status", () -> {
            String id = insert(identifier, "WHO_KNOWS");
            try {
                assertThatThrownBy(() -> repo.findByIdentifier(identifier))
                        .isInstanceOf(CorruptClientException.class)
                        .satisfies(e -> assertThat(((CorruptClientException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(TNT_CLIENTS).where(TNT_CLIENTS.ID.eq(id)).execute();
            }
        });
    }

    /// `findAll` reads the whole table (no scoping filter exists on this
    /// aggregate), so this only pins that a corrupt row anywhere fails the
    /// list rather than being silently dropped or coerced — not which row.
    @Test
    void aCorruptRowFailsTheWholeListRead() {
        TestPg.withConstraintDropped(DS, "tnt_clients", "chk_tnt_clients_status", () -> {
            String id = insert("corrupt-" + RUN + "-c", "NOT_A_STATUS");
            try {
                assertThatThrownBy(repo::findAll).isInstanceOf(CorruptClientException.class);
            } finally {
                DB.deleteFrom(TNT_CLIENTS).where(TNT_CLIENTS.ID.eq(id)).execute();
            }
        });
    }
}
