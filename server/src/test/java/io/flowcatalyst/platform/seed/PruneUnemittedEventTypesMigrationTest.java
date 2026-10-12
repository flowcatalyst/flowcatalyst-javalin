package io.flowcatalyst.platform.seed;

import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/// V26 (Go migration 071): a database an older seed populated holds 16 event
/// types nothing emits. The migration removes them and their schema versions,
/// moves the subscription bindings that name them (by code, or by row) into
/// `msg_subscription_event_types_archive` as they were (a wildcard binding and
/// bindings to emitted types stay), leaves emitted types alone, and the seeder
/// does not add them back. Running it again changes nothing. Runs on scratch
/// databases: V26 already ran (as a no-op) on each when it was migrated, so the
/// old rows are inserted afterwards and the migration's SQL is executed again
/// over them (as `ClientEventCodesMigrationTest` does for V25).
class PruneUnemittedEventTypesMigrationTest {

    /// The 16 codes the old seed catalogued and nothing emits.
    static final List<String> UNEMITTED = List.of(
            "platform:admin:edm:created", "platform:admin:edm:deleted", "platform:admin:edm:updated",
            "platform:admin:idp:created", "platform:admin:idp:deleted", "platform:admin:idp:updated",
            "platform:iam:anchor-domain:created", "platform:iam:anchor-domain:deleted",
            "platform:iam:auth-config:created", "platform:iam:auth-config:deleted", "platform:iam:auth-config:updated",
            "platform:iam:role:created", "platform:iam:role:deleted", "platform:iam:role:updated",
            "platform:iam:roles:synced", "platform:iam:user:password-reset-requested");

    private static String migrationSql() throws IOException {
        try (var in = PruneUnemittedEventTypesMigrationTest.class.getResourceAsStream("/db/migration/V26__prune_unemitted_event_types.sql")) {
            assertThat(in).as("V26 migration on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// `evt_p26_000000NN`, 17 characters.
    private static String id(int n) {
        return String.format("evt_p26_%09d", n);
    }

    private static void insertType(DSLContext db, String id, String code) {
        String[] parts = code.split(":");
        db.execute("INSERT INTO msg_event_types (id, code, name, status, source, application, subdomain, aggregate) "
                + "VALUES (?, ?, 'Seeded', 'CURRENT', 'UI', 'platform', ?, ?)", id, code, parts[1], parts[2]);
        db.execute("INSERT INTO msg_event_type_spec_versions (id, event_type_id, version, mime_type, schema_content, schema_type, status) "
                + "VALUES (?, ?, '1.0', 'application/schema+json', '{\"type\":\"object\"}'::jsonb, 'JSON_SCHEMA', 'CURRENT')",
                "sch" + id.substring(3), id);
    }

    private static int count(DSLContext db, String sql, Object... args) {
        return db.fetchOne(sql, args).get(0, Integer.class);
    }

    private static Map<Integer, String> bindings(DSLContext db) {
        Map<Integer, String> out = new TreeMap<>();
        for (Record r : db.fetch("SELECT id, event_type_code FROM msg_subscription_event_types WHERE subscription_id LIKE 'sub_p26_%'")) {
            out.put(r.get(0, Integer.class), r.get(1, String.class));
        }
        return out;
    }

    private static List<List<Object>> archive(DSLContext db) {
        return db.fetch("SELECT id, subscription_id, event_type_id, event_type_code, spec_version, archived_reason "
                        + "FROM msg_subscription_event_types_archive WHERE subscription_id LIKE 'sub_p26_%' ORDER BY id")
                .stream().map(r -> Arrays.asList(r.intoArray())).toList();
    }

    @Test
    void removesTheUnemittedTypesArchivesTheirBindingsKeepsTheRestAndIsIdempotent() throws Exception {
        DataSource ds = TestPg.newDatabase("v26_prune_unemitted");
        Migrator.migrate(ds);
        DSLContext db = DSL.using(ds, SQLDialect.POSTGRES);

        // What an older seed wrote: all 16 unemitted types and one emitted, each with its 1.0 schema.
        for (int i = 0; i < UNEMITTED.size(); i++) insertType(db, id(i + 1), UNEMITTED.get(i));
        String roleCreated = id(UNEMITTED.indexOf("platform:iam:role:created") + 1);
        insertType(db, id(99), "platform:admin:role:created");
        // Bindings: one by row and code to an unemitted type next to one to an
        // emitted type; one by code only; one wildcard.
        db.execute("INSERT INTO msg_subscription_event_types (id, subscription_id, event_type_id, event_type_code, spec_version) VALUES "
                + "(926101, 'sub_p26_00000001', ?, 'platform:iam:role:created', '1.0'), "
                + "(926102, 'sub_p26_00000001', ?, 'platform:admin:role:created', '1.0'), "
                + "(926103, 'sub_p26_00000002', NULL, 'platform:admin:idp:updated', NULL), "
                + "(926104, 'sub_p26_00000003', NULL, 'platform:iam:role:*', NULL)", roleCreated, id(99));

        String sql = migrationSql();
        db.execute(sql);

        assertThat(count(db, "SELECT COUNT(*) FROM msg_event_types WHERE id LIKE 'evt_p26_%' AND id <> ?", id(99))).isZero();
        assertThat(count(db, "SELECT COUNT(*) FROM msg_event_type_spec_versions WHERE event_type_id LIKE 'evt_p26_%' AND event_type_id <> ?", id(99))).isZero();
        assertThat(count(db, "SELECT COUNT(*) FROM msg_event_types WHERE id = ?", id(99))).as("an emitted type stays").isEqualTo(1);
        assertThat(count(db, "SELECT COUNT(*) FROM msg_event_type_spec_versions WHERE event_type_id = ?", id(99))).isEqualTo(1);
        assertThat(bindings(db)).isEqualTo(Map.of(926102, "platform:admin:role:created", 926104, "platform:iam:role:*"));

        List<List<Object>> expected = List.of(
                Arrays.asList(926101, "sub_p26_00000001", roleCreated, "platform:iam:role:created", "1.0", "071_event_type_not_emitted"),
                Arrays.asList(926103, "sub_p26_00000002", null, "platform:admin:idp:updated", null, "071_event_type_not_emitted"));
        assertThat(archive(db)).isEqualTo(expected);

        // The seeder (every boot, after migrations) does not add them back, and
        // running the migration again changes nothing.
        new Seeder(ds, new EnvReader(Map.of())).run();
        for (String code : UNEMITTED) {
            assertThat(count(db, "SELECT COUNT(*) FROM msg_event_types WHERE code = ?", code)).as(code).isZero();
        }
        db.execute(sql);
        assertThat(archive(db)).isEqualTo(expected);
        assertThat(bindings(db)).isEqualTo(Map.of(926102, "platform:admin:role:created", 926104, "platform:iam:role:*"));
    }

    /// The migration names exactly the 16 codes the seed dropped, and none of
    /// them is catalogued now.
    @Test
    void theMigrationNamesExactlyTheSixteenDroppedCodes() throws Exception {
        String sql = migrationSql();
        List<String> named = new java.util.ArrayList<>();
        var m = java.util.regex.Pattern.compile("\\('(platform:[a-z:-]+)'\\)").matcher(sql);
        while (m.find()) named.add(m.group(1));
        assertThat(named).containsExactlyInAnyOrderElementsOf(UNEMITTED);
        var catalogued = PlatformEventTypes.all().stream().map(PlatformEventTypes.Definition::code).toList();
        assertThat(catalogued).doesNotContainAnyElementsOf(UNEMITTED);
    }
}
