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
import java.util.Optional;
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

    /// Inserts a row with an explicit `(applicationCode, clientId, code)` key
    /// for the [#findByCodeTreatsNullAsARealKeyValueOnEveryPart] fixture
    /// below — distinct from [#insert] above (X-06 fixture), which never
    /// sets `applicationCode`.
    private static String insertKeyed(String applicationCode, String clientId, String code) {
        String id = EntityType.CONNECTION.generate();
        DB.insertInto(MSG_CONNECTIONS)
                .set(MSG_CONNECTIONS.ID, id)
                .set(MSG_CONNECTIONS.CODE, code)
                .set(MSG_CONNECTIONS.APPLICATION_CODE, applicationCode)
                .set(MSG_CONNECTIONS.NAME, "c4-fixture")
                .set(MSG_CONNECTIONS.STATUS, "ACTIVE")
                .set(MSG_CONNECTIONS.SERVICE_ACCOUNT_ID, "sva_nonexistent")
                .set(MSG_CONNECTIONS.CLIENT_ID, clientId)
                .execute();
        INSERTED.add(id);
        return id;
    }

    /// V12's three-part key (spec `code-first-connections.md` §2, C4):
    /// `NULL` is a real value on both `applicationCode` and `clientId`, never
    /// a wildcard. Four rows share one code, each under a different
    /// combination of the two nullable parts — `findByCode` must resolve
    /// each combination to exactly its own row.
    @Test
    void findByCodeTreatsNullAsARealKeyValueOnEveryPart() {
        String code = "c4-" + RUN;
        String appA = "capp-" + RUN;
        String clientA = "cli_" + RUN + "_c4";

        String shared = insertKeyed(null, null, code);
        String appOnly = insertKeyed(appA, null, code);
        String clientOnly = insertKeyed(null, clientA, code);
        String both = insertKeyed(appA, clientA, code);

        assertThat(repo.findByCode(code, null, null)).map(Connection::id)
                .as("(null, null) resolves the shared row, not the app- or client-scoped ones").contains(shared);
        assertThat(repo.findByCode(code, appA, null)).map(Connection::id)
                .as("(A, null) does not fall back to the shared (null, null) row").contains(appOnly);
        assertThat(repo.findByCode(code, null, clientA)).map(Connection::id)
                .as("(null, B) does not fall back to the shared (null, null) row").contains(clientOnly);
        assertThat(repo.findByCode(code, appA, clientA)).map(Connection::id)
                .as("(A, B) resolves only the fully-scoped row").contains(both);

        // Cross-checks: none of the four ever answers for another's key.
        assertThat(repo.findByCode(code, appA, null)).map(Connection::id).isNotEqualTo(Optional.of(shared));
        assertThat(repo.findByCode(code, null, clientA)).map(Connection::id).isNotEqualTo(Optional.of(shared));
        assertThat(repo.findByCode(code, appA, clientA)).map(Connection::id)
                .isNotEqualTo(Optional.of(appOnly)).isNotEqualTo(Optional.of(clientOnly));

        // A different application entirely finds nothing under this code.
        assertThat(repo.findByCode(code, "no-such-app-" + RUN, null)).isEmpty();
        // A different client entirely finds nothing under this code.
        assertThat(repo.findByCode(code, null, "cli_" + RUN + "_other")).isEmpty();
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
