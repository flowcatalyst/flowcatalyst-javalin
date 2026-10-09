package io.flowcatalyst.platform.seed;

import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// V24 grants the three batch-ingest permissions to an EXISTING
/// `platform:application-service` role (the seeder never rewrites an existing
/// role's permissions) and to no other role, idempotently.
class ApplicationServiceBatchPermissionsMigrationTest {
    // TestPg.dataSource() is this class's own migrated database; no seeder has run in it.


    private static final List<String> BATCH = List.of(
            "platform:messaging:batch:events-write",
            "platform:messaging:batch:dispatch-jobs-write",
            "platform:admin:batch:audit-logs-write");

    private static String migrationSql() throws IOException {
        try (var in = ApplicationServiceBatchPermissionsMigrationTest.class.getResourceAsStream(
                "/db/migration/V24__application_service_batch_permissions.sql")) {
            assertThat(in).as("V24 migration on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void grantsTheThreePermissionsToTheExistingRoleOnlyAndIsIdempotent() throws Exception {
        DSLContext db = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
        db.execute("INSERT INTO iam_roles (id, name, display_name) VALUES ('rol_appsvc00001','platform:application-service','x')");
        db.execute("INSERT INTO iam_roles (id, name, display_name) VALUES ('rol_other000001','platform:viewer','y')");
        db.execute("INSERT INTO iam_role_permissions (role_id, permission) VALUES ('rol_appsvc00001','platform:application-service:event:create')");

        String sql = migrationSql();
        db.execute(sql);
        db.execute(sql); // idempotent: ON CONFLICT DO NOTHING, no duplicate-key failure

        var granted = db.fetch("SELECT permission FROM iam_role_permissions WHERE role_id = 'rol_appsvc00001'")
                .getValues(0, String.class);
        assertThat(granted).containsExactlyInAnyOrder(
                "platform:application-service:event:create", BATCH.get(0), BATCH.get(1), BATCH.get(2));
        assertThat(db.fetchCount(DSL.table("iam_role_permissions"), DSL.field("role_id").eq("rol_other000001")))
                .as("no other role is touched").isZero();
    }
}
