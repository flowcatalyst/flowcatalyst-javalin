package io.flowcatalyst.platform.seed;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPES;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPE_SPEC_VERSIONS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;

/// Seeded event-type schemas are version `1.0`, not the `v1` the seeder used
/// to write: V11 renames the rows an older seeder left behind, and the seeder
/// never attaches a second schema beside a legacy row.
class SeededSchemaVersionTest {

    private static final String V11 = "db/migration/V11__platform_event_schema_version.sql";

    @Test
    void migrationRenamesSeededV1RowsOnly() {
        DataSource ds = TestPg.newDatabase("schema_version_migration");
        Migrator.migrate(ds);
        DSLContext db = DSL.using(ds, SQLDialect.POSTGRES);

        String legacy = eventType(db, "platform:test:legacy:happened");
        String both = eventType(db, "platform:test:both:happened");
        String tenant = eventType(db, "acme:orders:order:placed");
        specVersion(db, legacy, "v1");
        specVersion(db, both, "v1");
        specVersion(db, both, "1.0");
        specVersion(db, tenant, "v1");

        db.execute(resource(V11));

        assertThat(versions(db, legacy)).as("a seeded v1 becomes 1.0").containsExactly("1.0");
        assertThat(versions(db, both)).as("a v1 beside an existing 1.0 is left, not collided")
                .containsExactly("1.0", "v1");
        assertThat(versions(db, tenant)).as("a tenant's own v1 is their data").containsExactly("v1");

        db.execute(resource(V11));
        assertThat(versions(db, legacy)).as("a second run changes nothing").containsExactly("1.0");
        assertThat(versions(db, both)).containsExactly("1.0", "v1");
    }

    @Test
    void seederCountsALegacyV1RowAsAttached() {
        DataSource ds = TestPg.newDatabase("schema_version_seeder");
        Migrator.migrate(ds);
        DSLContext db = DSL.using(ds, SQLDialect.POSTGRES);
        EnvReader env = new EnvReader(Map.of());

        new Seeder(ds, env).run();
        int seeded = db.fetchCount(MSG_EVENT_TYPE_SPEC_VERSIONS);
        assertThat(db.fetchCount(MSG_EVENT_TYPE_SPEC_VERSIONS, MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION.ne("1.0")))
                .as("every seeded schema is 1.0").isZero();

        // A database an older seeder populated and nobody migrated yet.
        String id = db.select(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID).from(MSG_EVENT_TYPE_SPEC_VERSIONS)
                .limit(1).fetchSingle(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID);
        db.update(MSG_EVENT_TYPE_SPEC_VERSIONS).set(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION, "v1")
                .where(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID.eq(id)).execute();

        new Seeder(ds, env).run();

        assertThat(versions(db, id)).as("no second schema beside the legacy row").containsExactly("v1");
        assertThat(db.fetchCount(MSG_EVENT_TYPE_SPEC_VERSIONS)).isEqualTo(seeded);
    }

    private static String eventType(DSLContext db, String code) {
        String[] parts = code.split(":");
        String id = EntityType.EVENT_TYPE.generate();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        db.insertInto(MSG_EVENT_TYPES)
                .set(MSG_EVENT_TYPES.ID, id)
                .set(MSG_EVENT_TYPES.CODE, code)
                .set(MSG_EVENT_TYPES.NAME, code)
                .set(MSG_EVENT_TYPES.STATUS, "CURRENT")
                .set(MSG_EVENT_TYPES.SOURCE, "UI")
                .set(MSG_EVENT_TYPES.CLIENT_SCOPED, false)
                .set(MSG_EVENT_TYPES.APPLICATION, parts[0])
                .set(MSG_EVENT_TYPES.SUBDOMAIN, parts[1])
                .set(MSG_EVENT_TYPES.AGGREGATE, parts[2])
                .set(MSG_EVENT_TYPES.CREATED_AT, now)
                .set(MSG_EVENT_TYPES.UPDATED_AT, now)
                .execute();
        return id;
    }

    private static void specVersion(DSLContext db, String eventTypeId, String version) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        db.insertInto(MSG_EVENT_TYPE_SPEC_VERSIONS)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.ID, EntityType.SCHEMA.generate())
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID, eventTypeId)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION, version)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.MIME_TYPE, "application/schema+json")
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.SCHEMA_CONTENT, JSONB.valueOf("{}"))
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.SCHEMA_TYPE, "JSON_SCHEMA")
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.STATUS, "CURRENT")
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.CREATED_AT, now)
                .set(MSG_EVENT_TYPE_SPEC_VERSIONS.UPDATED_AT, now)
                .execute();
    }

    private static List<String> versions(DSLContext db, String eventTypeId) {
        return db.select(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION).from(MSG_EVENT_TYPE_SPEC_VERSIONS)
                .where(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID.eq(eventTypeId))
                .orderBy(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION)
                .fetch(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION);
    }

    private static String resource(String path) {
        try (InputStream in = SeededSchemaVersionTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
