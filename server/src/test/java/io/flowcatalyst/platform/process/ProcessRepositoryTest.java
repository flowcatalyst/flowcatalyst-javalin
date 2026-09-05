package io.flowcatalyst.platform.process;

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

import static io.flowcatalyst.db.generated.Tables.MSG_PROCESSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `status`/`source` column fails the read loudly instead
/// of defaulting silently (spec §1). Rows are scoped under this run's own
/// `application`, and [#cleanup] deletes everything this class inserts —
/// belt-and-braces against any unfiltered read elsewhere (`TestPg` never
/// truncates between tests).
class ProcessRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ProcessRepository repo = new ProcessRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String application, String status, String source) {
        String id = EntityType.PROCESS.generate();
        DB.insertInto(MSG_PROCESSES)
                .set(MSG_PROCESSES.ID, id)
                .set(MSG_PROCESSES.CODE, "corrupt." + id + "." + RUN)
                .set(MSG_PROCESSES.NAME, "corrupt")
                .set(MSG_PROCESSES.STATUS, status)
                .set(MSG_PROCESSES.SOURCE, source)
                .set(MSG_PROCESSES.APPLICATION, application)
                .set(MSG_PROCESSES.SUBDOMAIN, "sub")
                .set(MSG_PROCESSES.PROCESS_NAME, "proc")
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(MSG_PROCESSES).where(MSG_PROCESSES.ID.in(INSERTED)).execute();
    }

    /// `chk_msg_processes_status` (migration 051) now blocks a fresh write
    /// of an unrecognised status, so the constraint is dropped for the seed
    /// insert AND the assertions, and the row is deleted again before
    /// restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingToCurrent() {
        TestPg.withConstraintDropped(DS, "msg_processes", "chk_msg_processes_status", () -> {
            String id = insert("app-" + RUN, "BOGUS", "UI");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptProcessException.class)
                        .satisfies(e -> assertThat(((CorruptProcessException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_PROCESSES).where(MSG_PROCESSES.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByIdRejectsAnUnrecognisedSourceInsteadOfDefaultingToUi() {
        TestPg.withConstraintDropped(DS, "msg_processes", "chk_msg_processes_source", () -> {
            String id = insert("app-" + RUN, "CURRENT", "BOGUS");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptProcessException.class)
                        .satisfies(e -> assertThat(((CorruptProcessException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_PROCESSES).where(MSG_PROCESSES.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        String application = "app2-" + RUN;
        String good = insert(application, "CURRENT", "UI");
        TestPg.withConstraintDropped(DS, "msg_processes", "chk_msg_processes_status", () -> {
            String corrupt = insert(application, "NOT_A_STATUS", "UI");
            try {
                assertThatThrownBy(() -> repo.findByApplication(application))
                        .isInstanceOf(CorruptProcessException.class)
                        .satisfies(e -> assertThat(((CorruptProcessException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(MSG_PROCESSES).where(MSG_PROCESSES.ID.eq(corrupt)).execute();
            }
        });
    }
}
