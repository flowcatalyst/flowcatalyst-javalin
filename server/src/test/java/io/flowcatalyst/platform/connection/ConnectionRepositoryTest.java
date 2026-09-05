package io.flowcatalyst.platform.connection;

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

import static io.flowcatalyst.db.generated.Tables.MSG_CONNECTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `status` column fails the read loudly instead of
/// defaulting silently (spec §1).
///
/// `ConnectionApiTest` reads `GET /api/connections` unfiltered as an anchor,
/// so a corrupt row left behind here would fail that too (`TestPg` never
/// truncates between tests). [#cleanup] deletes everything this class
/// inserts.
class ConnectionRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ConnectionRepository repo = new ConnectionRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String clientId, String status) {
        String id = EntityType.CONNECTION.generate();
        DB.insertInto(MSG_CONNECTIONS)
                .set(MSG_CONNECTIONS.ID, id)
                .set(MSG_CONNECTIONS.CODE, "corrupt-" + id + "-" + RUN)
                .set(MSG_CONNECTIONS.NAME, "corrupt")
                .set(MSG_CONNECTIONS.STATUS, status)
                .set(MSG_CONNECTIONS.SERVICE_ACCOUNT_ID, "sva_nonexistent")
                .set(MSG_CONNECTIONS.CLIENT_ID, clientId)
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(MSG_CONNECTIONS).where(MSG_CONNECTIONS.ID.in(INSERTED)).execute();
    }

    /// `chk_msg_connections_status` (migration 051) now blocks a fresh
    /// write of an unrecognised status, so the constraint is dropped for
    /// the seed insert AND the assertions, and the row is deleted again
    /// before restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingToActive() {
        TestPg.withConstraintDropped(DS, "msg_connections", "chk_msg_connections_status", () -> {
            String id = insert("cli_" + RUN + "_a", "SUSPENDED");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptConnectionException.class)
                        .satisfies(e -> assertThat(((CorruptConnectionException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_CONNECTIONS).where(MSG_CONNECTIONS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        String clientId = "cli_" + RUN + "_b";
        String good = insert(clientId, "ACTIVE");
        TestPg.withConstraintDropped(DS, "msg_connections", "chk_msg_connections_status", () -> {
            String corrupt = insert(clientId, "WHO_KNOWS");
            try {
                assertThatThrownBy(() -> repo.findWithFilters(new ConnectionRepository.ListFilter(null, clientId)))
                        .isInstanceOf(CorruptConnectionException.class)
                        .satisfies(e -> assertThat(((CorruptConnectionException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(MSG_CONNECTIONS).where(MSG_CONNECTIONS.ID.eq(corrupt)).execute();
            }
        });
    }
}
