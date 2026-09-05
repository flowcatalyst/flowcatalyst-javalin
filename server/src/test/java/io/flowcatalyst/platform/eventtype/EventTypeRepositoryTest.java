package io.flowcatalyst.platform.eventtype;

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

import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPES;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPE_SPEC_VERSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `status`/`source`/`schema_type` column on either
/// `msg_event_types` or `msg_event_type_spec_versions` fails the read
/// loudly instead of defaulting silently (spec §1).
///
/// `EventTypeApiTest` lists unfiltered (`listDefaultsToCurrentWhenUnfiltered`),
/// so a corrupt row left behind here would fail that too (`TestPg` never
/// truncates between tests). Every row is scoped under this run's own
/// `application`, and [#cleanup] deletes everything this class inserts.
class EventTypeRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final EventTypeRepository repo = new EventTypeRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String APPLICATION = "app-" + RUN;
    private static final List<String> EVENT_TYPE_IDS = new ArrayList<>();
    private static final List<String> SPEC_VERSION_IDS = new ArrayList<>();

    private static String insertEventType(String status, String source) {
        String id = EntityType.EVENT_TYPE.generate();
        DB.insertInto(MSG_EVENT_TYPES)
                .set(MSG_EVENT_TYPES.ID, id)
                .set(MSG_EVENT_TYPES.CODE, "corrupt." + id + "." + RUN)
                .set(MSG_EVENT_TYPES.NAME, "corrupt")
                .set(MSG_EVENT_TYPES.STATUS, status)
                .set(MSG_EVENT_TYPES.SOURCE, source)
                .set(MSG_EVENT_TYPES.APPLICATION, APPLICATION)
                .set(MSG_EVENT_TYPES.SUBDOMAIN, "sub")
                .set(MSG_EVENT_TYPES.AGGREGATE, "agg")
                .execute();
        EVENT_TYPE_IDS.add(id);
        return id;
    }

    private static String insertSpecVersion(String eventTypeId, String schemaType, String status) {
        String id = "sv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        DB.insertInto(MSG_EVENT_TYPE_SPEC_VERSIONS)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.ID, id)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID, eventTypeId)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION, "1.0")
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.MIME_TYPE, "application/json")
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.SCHEMA_TYPE, schemaType)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.STATUS, status)
                .execute();
        SPEC_VERSION_IDS.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(MSG_EVENT_TYPE_SPEC_VERSIONS).where(MSG_EVENT_TYPE_SPEC_VERSIONS.ID.in(SPEC_VERSION_IDS)).execute();
        DB.deleteFrom(MSG_EVENT_TYPES).where(MSG_EVENT_TYPES.ID.in(EVENT_TYPE_IDS)).execute();
    }

    /// `chk_msg_event_types_status` (migration 051) now blocks a fresh
    /// write of an unrecognised status, so the constraint is dropped for
    /// the seed insert AND the assertions, and the row is deleted again
    /// before restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingToCurrent() {
        TestPg.withConstraintDropped(DS, "msg_event_types", "chk_msg_event_types_status", () -> {
            String id = insertEventType("BOGUS", "UI");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptEventTypeException.class)
                        .satisfies(e -> assertThat(((CorruptEventTypeException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_EVENT_TYPES).where(MSG_EVENT_TYPES.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByIdRejectsAnUnrecognisedSourceInsteadOfDefaultingToUi() {
        TestPg.withConstraintDropped(DS, "msg_event_types", "chk_msg_event_types_source", () -> {
            String id = insertEventType("CURRENT", "BOGUS");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptEventTypeException.class)
                        .satisfies(e -> assertThat(((CorruptEventTypeException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_EVENT_TYPES).where(MSG_EVENT_TYPES.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void aCorruptSpecVersionFailsTheEventTypeReadNotJustThatVersion() {
        String eventTypeId = insertEventType("CURRENT", "UI");
        TestPg.withConstraintDropped(DS, "msg_event_type_spec_versions", "chk_msg_event_type_spec_versions_schema_type", () -> {
            String svId = insertSpecVersion(eventTypeId, "BOGUS_SCHEMA", "FINALISING");
            try {
                assertThatThrownBy(() -> repo.findById(eventTypeId))
                        .isInstanceOf(CorruptEventTypeException.class)
                        .satisfies(e -> assertThat(((CorruptEventTypeException) e).rowId()).isEqualTo(svId));
            } finally {
                DB.deleteFrom(MSG_EVENT_TYPE_SPEC_VERSIONS).where(MSG_EVENT_TYPE_SPEC_VERSIONS.ID.eq(svId)).execute();
            }
        });
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        String good = EntityType.EVENT_TYPE.generate();
        DB.insertInto(MSG_EVENT_TYPES)
                .set(MSG_EVENT_TYPES.ID, good)
                .set(MSG_EVENT_TYPES.CODE, "good." + good + "." + RUN)
                .set(MSG_EVENT_TYPES.NAME, "good")
                .set(MSG_EVENT_TYPES.APPLICATION, APPLICATION)
                .set(MSG_EVENT_TYPES.SUBDOMAIN, "sub")
                .set(MSG_EVENT_TYPES.AGGREGATE, "agg")
                .execute();
        EVENT_TYPE_IDS.add(good);
        TestPg.withConstraintDropped(DS, "msg_event_types", "chk_msg_event_types_status", () -> {
            String corrupt = insertEventType("WHO_KNOWS", "UI");
            try {
                assertThatThrownBy(() -> repo.findByApplication(APPLICATION))
                        .isInstanceOf(CorruptEventTypeException.class)
                        .satisfies(e -> assertThat(((CorruptEventTypeException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(MSG_EVENT_TYPES).where(MSG_EVENT_TYPES.ID.eq(corrupt)).execute();
            }
        });
    }

    @Test
    void legacySchemaTypeAliasesStillReadCorrectly() {
        String eventTypeId = insertEventType("CURRENT", "UI");
        String svId = insertSpecVersion(eventTypeId, "XML_SCHEMA", "CURRENT");
        var et = repo.findById(eventTypeId).orElseThrow();
        assertThat(et.specVersions()).singleElement().satisfies(sv -> {
            assertThat(sv.id()).isEqualTo(svId);
            assertThat(sv.schemaType()).isEqualTo(SchemaType.XSD);
        });
    }
}
