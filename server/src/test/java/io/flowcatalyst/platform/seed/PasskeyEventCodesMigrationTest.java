package io.flowcatalyst.platform.seed;

import io.flowcatalyst.platform.passkey.operations.PasskeyEvents;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// V28 (Go migration 073's statements, for the passkey codes): Java emitted the
/// passkey events as `platform:iam:passkey:*`; they move to Go's and Rust's
/// `platform:admin:passkey:*` (owner ruling 2026-10-12). The migration renames
/// the rows an older seed wrote (id, status and schema versions kept; subdomain
/// `admin`) and re-points the subscription bindings naming them, by row or by
/// code. Where the new code is already a row (a Go or Rust seeder ran first),
/// the bindings move to it, a binding that would duplicate one the subscription
/// has is dropped, and the old row goes with its schema versions. Wildcards and
/// other codes are left alone; re-running is a no-op. Runs on scratch databases:
/// V28 already ran (as a no-op) when each was migrated, so the old rows are
/// inserted afterwards and the migration's SQL is executed again over them.
class PasskeyEventCodesMigrationTest {

    private static final List<String> EVENTS = List.of("registered", "authenticated", "revoked");

    private static String migrationSql() throws IOException {
        try (var in = PasskeyEventCodesMigrationTest.class.getResourceAsStream("/db/migration/V28__passkey_event_type_codes.sql")) {
            assertThat(in).as("V28 migration on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DSLContext freshDatabase(String name, DataSource[] out) {
        DataSource ds = TestPg.newDatabase(name);
        Migrator.migrate(ds);
        out[0] = ds;
        return DSL.using(ds, SQLDialect.POSTGRES);
    }

    /// `evt_v28old000000N` / `evt_v28new000000N`, 17 characters.
    private static String oldId(String event) {
        return "evt_v28old000000" + EVENTS.indexOf(event);
    }

    private static String newId(String event) {
        return "evt_v28new000000" + EVENTS.indexOf(event);
    }

    private static void insertType(DSLContext db, String id, String code, String subdomain, String schema) {
        db.execute("INSERT INTO msg_event_types (id, code, name, status, source, application, subdomain, aggregate) "
                + "VALUES (?, ?, 'Passkey', 'CURRENT', 'UI', 'platform', ?, 'passkey')", id, code, subdomain);
        db.execute("INSERT INTO msg_event_type_spec_versions (id, event_type_id, version, mime_type, schema_content, schema_type, status) "
                + "VALUES (?, ?, '1.0', 'application/schema+json', ?::jsonb, 'JSON_SCHEMA', 'CURRENT')",
                "sch" + id.substring(3), id, schema);
    }

    /// The three rows an older Java seed wrote, each with its 1.0 schema.
    private static void insertOldSeed(DSLContext db) {
        for (String ev : EVENTS) {
            insertType(db, oldId(ev), "platform:iam:passkey:" + ev, "iam", "{\"type\":\"object\",\"title\":\"old " + ev + "\"}");
        }
    }

    private static void bind(DSLContext db, int id, String subscription, String eventTypeId, String code) {
        db.execute("INSERT INTO msg_subscription_event_types (id, subscription_id, event_type_id, event_type_code, spec_version) "
                + "VALUES (?, ?, ?, ?, '1.0')", id, subscription, eventTypeId, code);
    }

    /// id → [subscription, event type id, code], for this test's subscriptions.
    private static Map<Integer, List<Object>> bindings(DSLContext db) {
        Map<Integer, List<Object>> out = new java.util.TreeMap<>();
        for (Record r : db.fetch("SELECT id, subscription_id, event_type_id, event_type_code FROM msg_subscription_event_types "
                + "WHERE subscription_id LIKE 'sub_v28_%'")) {
            out.put(r.get(0, Integer.class), Arrays.asList(r.get(1), r.get(2), r.get(3)));
        }
        return out;
    }

    private static int count(DSLContext db, String sql, Object... args) {
        return db.fetchOne(sql, args).get(0, Integer.class);
    }

    @Test
    void renamesTheOldRowsKeepingIdsAndSchemasRepointsBindingsAndIsIdempotent() throws Exception {
        DataSource[] ds = new DataSource[1];
        DSLContext db = freshDatabase("v28_passkey_codes", ds);
        insertOldSeed(db);
        // By row and code; by code only; a wildcard; another code.
        bind(db, 928101, "sub_v28_00000001", oldId("registered"), "platform:iam:passkey:registered");
        bind(db, 928102, "sub_v28_00000002", null, "platform:iam:passkey:revoked");
        bind(db, 928103, "sub_v28_00000003", null, "platform:iam:passkey:*");
        bind(db, 928104, "sub_v28_00000004", null, "platform:iam:reset-approval:queued");

        String sql = migrationSql();
        db.execute(sql);

        for (String ev : EVENTS) {
            var row = db.fetchOne("SELECT code, subdomain, aggregate, status FROM msg_event_types WHERE id = ?", oldId(ev));
            assertThat(row).as("row %s keeps its id", ev).isNotNull();
            assertThat(row.get("code", String.class)).isEqualTo("platform:admin:passkey:" + ev);
            assertThat(row.get("subdomain", String.class)).isEqualTo("admin");
            assertThat(row.get("aggregate", String.class)).isEqualTo("passkey");
            assertThat(row.get("status", String.class)).isEqualTo("CURRENT");
            assertThat(db.fetchValue("SELECT schema_content->>'title' FROM msg_event_type_spec_versions WHERE event_type_id = ?", oldId(ev)))
                    .as("schema of %s kept", ev).isEqualTo("old " + ev);
        }
        assertThat(count(db, "SELECT COUNT(*) FROM msg_event_types WHERE code LIKE 'platform:iam:passkey:%'")).isZero();
        Map<Integer, List<Object>> expected = Map.of(
                928101, Arrays.asList("sub_v28_00000001", oldId("registered"), "platform:admin:passkey:registered"),
                928102, Arrays.asList("sub_v28_00000002", null, "platform:admin:passkey:revoked"),
                928103, Arrays.asList("sub_v28_00000003", null, "platform:iam:passkey:*"),
                928104, Arrays.asList("sub_v28_00000004", null, "platform:iam:reset-approval:queued"));
        assertThat(bindings(db)).isEqualTo(expected);

        // Re-run: a no-op.
        var before = db.fetch("SELECT id, code, updated_at FROM msg_event_types WHERE id LIKE 'evt_v28%' ORDER BY id");
        db.execute(sql);
        assertThat(db.fetch("SELECT id, code, updated_at FROM msg_event_types WHERE id LIKE 'evt_v28%' ORDER BY id")).isEqualTo(before);
        assertThat(bindings(db)).isEqualTo(expected);

        // The seeder (every boot, after migrations) adds no second row or schema.
        new Seeder(ds[0], new EnvReader(Map.of())).run();
        for (String ev : EVENTS) {
            assertThat(db.fetchValue("SELECT id FROM msg_event_types WHERE code = ?", "platform:admin:passkey:" + ev)).isEqualTo(oldId(ev));
            assertThat(count(db, "SELECT COUNT(*) FROM msg_event_type_spec_versions WHERE event_type_id = ?", oldId(ev))).isEqualTo(1);
        }
        assertThat(count(db, "SELECT COUNT(*) FROM msg_event_types WHERE code LIKE 'platform:iam:passkey:%'")).isZero();
    }

    /// The new codes already exist (a Go or Rust seeder ran first, or the
    /// Java seeder after a partial rename): the bindings move to the new rows,
    /// a duplicate binding is dropped, the old rows go with their schemas, the
    /// new rows keep theirs, and nothing fails on a unique constraint.
    @Test
    void mergesIntoExistingNewRows() throws Exception {
        DataSource[] ds = new DataSource[1];
        DSLContext db = freshDatabase("v28_passkey_codes_merge", ds);
        insertOldSeed(db);
        for (String ev : EVENTS) {
            insertType(db, newId(ev), "platform:admin:passkey:" + ev, "admin", "{\"type\":\"object\",\"title\":\"new " + ev + "\"}");
        }
        // sub 1: the old row by row and code. sub 2: the old code, and already
        // the new code (the move would duplicate it). sub 3: the old row by id
        // with a stale code. sub 4: a wildcard.
        bind(db, 928201, "sub_v28_00000011", oldId("registered"), "platform:iam:passkey:registered");
        bind(db, 928202, "sub_v28_00000012", null, "platform:iam:passkey:authenticated");
        bind(db, 928203, "sub_v28_00000012", newId("authenticated"), "platform:admin:passkey:authenticated");
        bind(db, 928204, "sub_v28_00000013", oldId("revoked"), "platform:iam:passkey:revoked-typo");
        bind(db, 928205, "sub_v28_00000014", null, "platform:iam:passkey:*");

        String sql = migrationSql();
        db.execute(sql);

        for (String ev : EVENTS) {
            assertThat(count(db, "SELECT COUNT(*) FROM msg_event_types WHERE id = ?", oldId(ev))).as("old %s row gone", ev).isZero();
            assertThat(count(db, "SELECT COUNT(*) FROM msg_event_type_spec_versions WHERE event_type_id = ?", oldId(ev))).isZero();
            assertThat(db.fetchValue("SELECT schema_content->>'title' FROM msg_event_type_spec_versions WHERE event_type_id = ?", newId(ev)))
                    .isEqualTo("new " + ev);
        }
        Map<Integer, List<Object>> expected = Map.of(
                928201, Arrays.asList("sub_v28_00000011", newId("registered"), "platform:admin:passkey:registered"),
                928203, Arrays.asList("sub_v28_00000012", newId("authenticated"), "platform:admin:passkey:authenticated"),
                928204, Arrays.asList("sub_v28_00000013", newId("revoked"), "platform:admin:passkey:revoked"),
                928205, Arrays.asList("sub_v28_00000014", null, "platform:iam:passkey:*"));
        assertThat(bindings(db)).isEqualTo(expected);

        db.execute(sql);
        assertThat(bindings(db)).isEqualTo(expected);
        assertThat(count(db, "SELECT COUNT(*) FROM msg_event_types WHERE id LIKE 'evt_v28%'")).isEqualTo(3);
    }

    /// The migration maps exactly the three old codes to the codes the passkey
    /// operations emit (and the seed catalogues), and touches no stored event
    /// or dispatch job.
    @Test
    void theMigrationMapsExactlyTheThreePasskeyCodes() throws Exception {
        String sql = migrationSql();
        var pairs = new ArrayList<String>();
        var m = Pattern.compile("\\('(platform:iam:passkey:[a-z]+)',\\s*'(platform:admin:passkey:[a-z]+)'").matcher(sql);
        while (m.find()) pairs.add(m.group(1) + " -> " + m.group(2));
        List<String> want = new ArrayList<>();
        for (String ev : EVENTS) want.add("platform:iam:passkey:" + ev + " -> platform:admin:passkey:" + ev);
        // Five statements, each naming the three pairs.
        assertThat(pairs).hasSize(15).isSubsetOf(want).containsAll(want);
        assertThat(List.of(PasskeyEvents.REGISTERED, PasskeyEvents.AUTHENTICATED, PasskeyEvents.REVOKED))
                .containsExactly("platform:admin:passkey:registered", "platform:admin:passkey:authenticated", "platform:admin:passkey:revoked");
        var catalogued = PlatformEventTypes.all().stream().map(PlatformEventTypes.Definition::code).toList();
        assertThat(catalogued).contains(PasskeyEvents.REGISTERED, PasskeyEvents.AUTHENTICATED, PasskeyEvents.REVOKED);
        assertThat(sql).doesNotContain("msg_events").doesNotContain("msg_dispatch_jobs");
    }
}
