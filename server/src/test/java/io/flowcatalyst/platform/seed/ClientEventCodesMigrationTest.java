package io.flowcatalyst.platform.seed;

import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// V25 (Go migration 070): the client events were catalogued as
/// `platform:iam:client:*` while the client operations emit
/// `platform:admin:client:*`. The migration renames the six rows an older seed
/// wrote (id, status and schema versions kept; subdomain `admin`), re-points
/// subscription bindings, replaces a 1.0 schema only while it is exactly what
/// the old seed wrote, leaves both rows alone where the emitted code already
/// exists, and is idempotent; the seeder then adds the two codes the old seed
/// never had, once. Runs on scratch databases: V25 already ran (as a no-op) on
/// each when it was migrated, so the old rows are inserted afterwards and the
/// migration's SQL is executed again over them.
class ClientEventCodesMigrationTest {

    private static final String[] EVENTS = {"created", "updated", "activated", "suspended", "deleted", "note-added"};

    /// The schemas the old seed wrote (the Java seeder's output then, pinned
    /// equal to Go's by `SeederTest.specVersionsMatchGo` against the Go dump).
    private static final Map<String, String> OLD_SCHEMAS = Map.of(
            "created", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"clientId\":{\"type\":\"string\"},\"description\":{\"type\":[\"string\",\"null\"]},\"identifier\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"clientId\",\"name\",\"identifier\"],\"type\":\"object\"}",
            "updated", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"clientId\":{\"type\":\"string\"},\"description\":{\"type\":[\"string\",\"null\"]},\"name\":{\"type\":[\"string\",\"null\"]}},\"required\":[\"clientId\"],\"type\":\"object\"}",
            "activated", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"clientId\":{\"type\":\"string\"},\"previousStatus\":{\"type\":\"string\"}},\"required\":[\"clientId\",\"previousStatus\"],\"type\":\"object\"}",
            "suspended", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"clientId\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"}},\"required\":[\"clientId\",\"reason\"],\"type\":\"object\"}",
            "deleted", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"clientId\":{\"type\":\"string\"},\"identifier\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"clientId\",\"name\",\"identifier\"],\"type\":\"object\"}",
            "note-added", "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"author\":{\"type\":\"string\"},\"category\":{\"type\":\"string\"},\"clientId\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}},\"required\":[\"clientId\",\"category\",\"text\",\"author\"],\"type\":\"object\"}");

    private static final String EDITED_DELETED_SCHEMA = "{\"type\":\"object\",\"title\":\"someone's own\"}";

    private static String migrationSql() throws IOException {
        try (var in = ClientEventCodesMigrationTest.class.getResourceAsStream("/db/migration/V25__client_event_type_codes.sql")) {
            assertThat(in).as("V25 migration on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DataSource freshDatabase(String name) {
        DataSource ds = TestPg.newDatabase(name);
        Migrator.migrate(ds);
        return ds;
    }

    /// A fixed 17-character id per event (`evt_v25old000000<n>`).
    private static String id(String event) {
        return "evt_v25old00000" + List.of(EVENTS).indexOf(event) + "0";
    }

    /// The six rows (and 1.0 schemas) an older seed wrote; `deleted`'s schema
    /// was edited afterwards.
    private static void insertOldSeed(DSLContext db) {
        for (String ev : EVENTS) {
            db.execute("INSERT INTO msg_event_types (id, code, name, status, source, application, subdomain, aggregate) "
                    + "VALUES (?, ?, ?, 'CURRENT', 'UI', 'platform', 'iam', 'client')", id(ev), "platform:iam:client:" + ev, "Client " + ev);
            db.execute("INSERT INTO msg_event_type_spec_versions (id, event_type_id, version, mime_type, schema_content, schema_type, status) "
                    + "VALUES (?, ?, '1.0', 'application/schema+json', ?::jsonb, 'JSON_SCHEMA', 'CURRENT')",
                    "sch" + id(ev).substring(3), id(ev), ev.equals("deleted") ? EDITED_DELETED_SCHEMA : OLD_SCHEMAS.get(ev));
        }
        // A binding by id, and a code-only binding (no event type id).
        db.execute("INSERT INTO msg_subscription_event_types (subscription_id, event_type_id, event_type_code) VALUES ('sub_v25bind00001', ?, 'platform:iam:client:created')", id("created"));
        db.execute("INSERT INTO msg_subscription_event_types (subscription_id, event_type_id, event_type_code) VALUES ('sub_v25bind00002', NULL, 'platform:iam:client:updated')");
    }

    private static JsonNode schemaOf(DSLContext db, String eventTypeId) {
        return Json.MAPPER.readTree(db.fetchValue(
                "SELECT schema_content::text FROM msg_event_type_spec_versions WHERE event_type_id = ? AND version = '1.0'", eventTypeId).toString());
    }

    @Test
    void renamesTheOldRowsKeepingIdsRepointsBindingsReplacesUneditedSchemasAndIsIdempotent() throws Exception {
        DataSource ds = freshDatabase("v25_client_codes");
        DSLContext db = DSL.using(ds, SQLDialect.POSTGRES);
        insertOldSeed(db);
        String sql = migrationSql();
        db.execute(sql);

        for (String ev : EVENTS) {
            var row = db.fetchOne("SELECT code, subdomain, status FROM msg_event_types WHERE id = ?", id(ev));
            assertThat(row).as("row %s keeps its id", ev).isNotNull();
            assertThat(row.get("code", String.class)).isEqualTo("platform:admin:client:" + ev);
            assertThat(row.get("subdomain", String.class)).isEqualTo("admin");
            assertThat(row.get("status", String.class)).isEqualTo("CURRENT");
        }
        assertThat(db.fetchCount(DSL.table("msg_event_types"), DSL.field("code").like("platform:iam:client:%"))).isZero();
        assertThat(db.fetch("SELECT event_type_code FROM msg_subscription_event_types WHERE subscription_id IN ('sub_v25bind00001','sub_v25bind00002') ORDER BY subscription_id")
                .getValues(0, String.class))
                .containsExactly("platform:admin:client:created", "platform:admin:client:updated");

        // Unedited schemas now describe what is emitted; the edited one is kept.
        var emitted = PlatformEventSchemas.all();
        for (String ev : EVENTS) {
            if (ev.equals("deleted")) continue;
            assertThat(schemaOf(db, id(ev))).as("schema of %s", ev).isEqualTo(emitted.get("platform:admin:client:" + ev));
        }
        assertThat(schemaOf(db, id("deleted"))).isEqualTo(Json.MAPPER.readTree(EDITED_DELETED_SCHEMA));

        // Re-run: a no-op.
        var before = db.fetch("SELECT id, code, updated_at FROM msg_event_types WHERE id LIKE 'evt_v25%' ORDER BY id");
        db.execute(sql);
        assertThat(db.fetch("SELECT id, code, updated_at FROM msg_event_types WHERE id LIKE 'evt_v25%' ORDER BY id")).isEqualTo(before);

        // The seeder (every boot, after migrations) adds the two codes the old
        // seed never had, once, and leaves the renamed rows' ids alone.
        new Seeder(ds, new EnvReader(Map.of())).run();
        new Seeder(ds, new EnvReader(Map.of())).run();
        assertThat(db.fetch("SELECT code FROM msg_event_types WHERE code LIKE 'platform:admin:client:%' OR code LIKE 'platform:iam:client:%'")
                .getValues(0, String.class))
                .containsExactlyInAnyOrder(
                        "platform:admin:client:created", "platform:admin:client:updated", "platform:admin:client:activated",
                        "platform:admin:client:suspended", "platform:admin:client:deactivated", "platform:admin:client:deleted",
                        "platform:admin:client:note-added", "platform:iam:client:applications-updated");
        for (String ev : EVENTS) {
            assertThat(db.fetchValue("SELECT id FROM msg_event_types WHERE code = ?", "platform:admin:client:" + ev)).isEqualTo(id(ev));
        }
    }

    /// Where the emitted code is already an event type, both rows (and their
    /// bindings) are left as they are.
    @Test
    void leavesBothRowsAloneWhenTheEmittedCodeAlreadyExists() throws Exception {
        DSLContext db = DSL.using(freshDatabase("v25_client_codes_clash"), SQLDialect.POSTGRES);
        insertOldSeed(db);
        db.execute("INSERT INTO msg_event_types (id, code, name, status, source, application, subdomain, aggregate) "
                + "VALUES ('evt_v25clash00001', 'platform:admin:client:created', 'Existing', 'CURRENT', 'API', 'platform', 'admin', 'client')");
        db.execute(migrationSql());

        assertThat(db.fetchValue("SELECT code FROM msg_event_types WHERE id = ?", id("created"))).isEqualTo("platform:iam:client:created");
        assertThat(db.fetchValue("SELECT code FROM msg_event_types WHERE id = 'evt_v25clash00001'")).isEqualTo("platform:admin:client:created");
        assertThat(db.fetchValue("SELECT event_type_code FROM msg_subscription_event_types WHERE subscription_id = 'sub_v25bind00001'"))
                .isEqualTo("platform:iam:client:created");
        assertThat(schemaOf(db, id("created"))).isEqualTo(Json.MAPPER.readTree(OLD_SCHEMAS.get("created")));
        // The others were free and are renamed.
        assertThat(db.fetchValue("SELECT code FROM msg_event_types WHERE id = ?", id("updated"))).isEqualTo("platform:admin:client:updated");
    }
}
