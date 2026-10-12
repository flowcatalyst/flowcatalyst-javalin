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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// V27 (Go migration 072): a database an older seed populated holds 28 1.0
/// schemas that do not describe the data their operations emit. The migration
/// replaces each with the seed's corrected schema while it is still exactly
/// what a seed wrote, leaves an edited schema alone, changes nothing when run
/// again, and the seeder attaches no second schema. Runs on a scratch database:
/// V27 already ran (as a no-op) when it was migrated, so the old rows are
/// inserted afterwards and the migration's SQL is executed again over them.
class EventSchemaFixesMigrationTest {

    private static final Pattern ROW = Pattern.compile("\\('(platform:[a-z:-]+)',\\s*'([^']+)',\\s*'([^']+)'\\)");

    private record Fix(String old, String corrected) {
    }

    private static String migrationSql() throws IOException {
        try (var in = EventSchemaFixesMigrationTest.class.getResourceAsStream("/db/migration/V27__platform_event_schema_fixes.sql")) {
            assertThat(in).as("V27 migration on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// The migration's (code, seeded, corrected) rows.
    private static Map<String, Fix> fixes(String sql) {
        Map<String, Fix> out = new LinkedHashMap<>();
        var m = ROW.matcher(sql);
        while (m.find()) out.put(m.group(1), new Fix(m.group(2), m.group(3)));
        return out;
    }

    private static JsonNode tree(String json) {
        return Json.MAPPER.readTree(json);
    }

    private static JsonNode schemaOf(DSLContext db, String code) {
        return tree(db.fetchOne("SELECT sv.schema_content::text FROM msg_event_type_spec_versions sv "
                + "JOIN msg_event_types et ON et.id = sv.event_type_id WHERE et.code = ? AND sv.version = '1.0'", code)
                .get(0, String.class));
    }

    /// Each corrected schema is the seed's; two seeded ones spelled out, as
    /// every Java and Go seed before this one wrote them.
    @Test
    void theCorrectedSchemasAreTheSeedsAndTheOldOnesWhatTheSeedWrote() throws Exception {
        Map<String, Fix> fixes = fixes(migrationSql());
        assertThat(fixes).hasSize(28);
        for (var e : fixes.entrySet()) {
            assertThat(PlatformEventSchemas.all()).containsKey(e.getKey());
            assertThat(tree(e.getValue().corrected())).as(e.getKey()).isEqualTo(PlatformEventSchemas.all().get(e.getKey()));
        }
        assertThat(tree(fixes.get("platform:admin:dispatch-pool:created").old())).isEqualTo(tree(
                "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"clientId\":{\"type\":[\"string\",\"null\"]},\"code\":{\"type\":\"string\"},\"dispatchPoolId\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"dispatchPoolId\",\"code\",\"name\"],\"type\":\"object\"}"));
        assertThat(tree(fixes.get("platform:iam:client:applications-updated").old())).isEqualTo(tree(
                "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"additionalProperties\":false,\"properties\":{\"clientId\":{\"type\":\"string\"},\"disabledRemoved\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"enabledAdded\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"enabledApplicationIds\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"}},\"required\":[\"clientId\",\"enabledApplicationIds\",\"enabledAdded\",\"disabledRemoved\"],\"type\":\"object\"}"));
    }

    @Test
    void replacesTheSeededSchemasLeavesAnEditedOneAndIsIdempotent() throws Exception {
        DataSource ds = TestPg.newDatabase("v27_schema_fixes");
        Migrator.migrate(ds);
        DSLContext db = DSL.using(ds, SQLDialect.POSTGRES);
        String sql = migrationSql();
        Map<String, Fix> fixes = fixes(sql);

        // What an older seed wrote: every one of the 28 with its seeded 1.0.
        int i = 0;
        for (var e : fixes.entrySet()) {
            i++;
            String[] parts = e.getKey().split(":");
            String id = String.format("evt_f27_%09d", i);
            db.execute("INSERT INTO msg_event_types (id, code, name, status, source, application, subdomain, aggregate) "
                    + "VALUES (?, ?, 'Seeded', 'CURRENT', 'UI', 'platform', ?, ?)", id, e.getKey(), parts[1], parts[2]);
            db.execute("INSERT INTO msg_event_type_spec_versions (id, event_type_id, version, mime_type, schema_content, schema_type, status) "
                    + "VALUES (?, ?, '1.0', 'application/schema+json', ?::jsonb, 'JSON_SCHEMA', 'CURRENT')",
                    "sch" + id.substring(3), id, e.getValue().old());
        }
        // Someone edited one; an edited schema is theirs.
        String edited = "{\"type\":\"object\",\"properties\":{\"poolId\":{\"type\":\"string\"}}}";
        db.execute("UPDATE msg_event_type_spec_versions sv SET schema_content = ?::jsonb FROM msg_event_types et "
                + "WHERE et.id = sv.event_type_id AND et.code = 'platform:admin:dispatch-pool:deleted'", edited);

        for (int run = 0; run < 2; run++) { // the second run changes nothing
            db.execute(sql);
            for (String code : fixes.keySet()) {
                JsonNode expected = code.equals("platform:admin:dispatch-pool:deleted")
                        ? tree(edited) : PlatformEventSchemas.all().get(code);
                assertThat(schemaOf(db, code)).as("%s after run %d", code, run + 1).isEqualTo(expected);
            }
        }

        // The seeder (every boot, after migrations) attaches no second schema.
        new Seeder(ds, new EnvReader(Map.of())).run();
        assertThat(db.fetchOne("SELECT COUNT(*) FROM msg_event_type_spec_versions WHERE id LIKE 'sch_f27_%'").get(0, Integer.class))
                .isEqualTo(28);
        assertThat(db.fetchOne("SELECT COUNT(*) FROM msg_event_type_spec_versions sv JOIN msg_event_types et ON et.id = sv.event_type_id "
                + "WHERE et.code = 'platform:admin:dispatch-pool:created'").get(0, Integer.class)).isEqualTo(1);
    }
}
