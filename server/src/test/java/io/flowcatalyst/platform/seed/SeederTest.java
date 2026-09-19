package io.flowcatalyst.platform.seed;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLE_PERMISSIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPES;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPE_SPEC_VERSIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_PROCESSES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;

/// Runs the seeder against a pristine, freshly migrated database and
/// compares the result with the rows a Go `fcdev start` produces
/// (`src/test/resources/seed/go-seed-expected.tsv`, extracted from a
/// `pg_dump --data-only` of that database with ids/timestamps stripped).
class SeederTest {

    static final EnvReader DEV_ENV = new EnvReader(Map.of(
            Seeder.ENV_BOOTSTRAP_EMAIL, "admin@flowcatalyst.local",
            Seeder.ENV_BOOTSTRAP_PASSWORD, "DevPassword123!",
            Seeder.ENV_BOOTSTRAP_NAME, "Local Admin"));

    static DataSource ds;
    static DSLContext db;
    static Map<String, List<String>> expected;
    static Map<String, List<String>> firstRun;
    static Map<String, List<String>> secondRun;

    @BeforeAll
    static void seedFreshDatabaseTwice() {
        ds = TestPg.newDatabase("seed");
        Migrator.migrate(ds);
        db = DSL.using(ds, SQLDialect.POSTGRES);
        expected = loadExpected();

        new Seeder(ds, DEV_ENV).run();
        firstRun = snapshot(db);
        new Seeder(ds, DEV_ENV).run();
        secondRun = snapshot(db);
    }

    // ── seeder-owned rows vs go-data.sql ─────────────────────────────────

    // `platform:router` (and its one permission row, in rolePermissionsMatchGo
    // below) is Java-first (`docs/spec/router-config-auth.md` R3′): Java seeds
    // it now, on purpose, ahead of Go. The 21 role/permission rows added by
    // `docs/spec/reach-only-routes.md` §2 (IdP + email-domain-mapping on
    // iam-admin/iam-readonly; config + CORS-origin on admin/admin-readonly/
    // viewer) are Java-first the same way. `platform:function-publisher` /
    // `platform:function-host` and the seven function permissions added to
    // `platform:messaging-admin` (`docs/spec/function-api.md` §2;
    // `platform:function:version:invoke` added by `docs/spec/function-invocation.md`
    // §1, package D slice D3; `platform:function:secret:manage` added by
    // `docs/spec/function-context.md` §1, package D slice D4a) are Java-first
    // again — there is no Go for the function platform. The fixture's
    // role/perm counts (17/186, not Go's 14/151)
    // and its own comment record that until Go mirrors all of it.
    @Test
    void rolesMatchGo() {
        List<String> actual = db.selectFrom(IAM_ROLES).fetch().stream()
                .map(r -> line("role", r.getName(), r.getDisplayName(), r.getDescription(), r.getSource(),
                        r.getApplicationCode(), r.getClientManaged()))
                .toList();
        assertThat(actual).hasSize(17).containsExactlyInAnyOrderElementsOf(expected.get("role"));
        assertThat(db.selectFrom(IAM_ROLES).fetch()).allSatisfy(r -> assertThat(r.getApplicationId()).isNull());
        assertThat(db.selectFrom(IAM_ROLES).fetch()).allSatisfy(r -> assertThat(r.getId()).startsWith("rol_"));
    }

    @Test
    void rolePermissionsMatchGo() {
        List<String> actual = db.select(IAM_ROLES.NAME, IAM_ROLE_PERMISSIONS.PERMISSION)
                .from(IAM_ROLE_PERMISSIONS)
                .join(IAM_ROLES).on(IAM_ROLES.ID.eq(IAM_ROLE_PERMISSIONS.ROLE_ID))
                .fetch().stream()
                .map(r -> line("perm", r.value1(), r.value2()))
                .toList();
        assertThat(actual).hasSize(186).containsExactlyInAnyOrderElementsOf(expected.get("perm"));
    }

    @Test
    void eventTypesMatchGo() {
        List<String> actual = db.selectFrom(MSG_EVENT_TYPES).fetch().stream()
                .map(r -> line("eventtype", r.getCode(), r.getName(), r.getStatus(), r.getSource(),
                        r.getClientScoped(), r.getApplication(), r.getSubdomain(), r.getAggregate()))
                .toList();
        assertThat(actual).hasSize(72).containsExactlyInAnyOrderElementsOf(expected.get("eventtype"));
        assertThat(db.selectFrom(MSG_EVENT_TYPES).fetch()).allSatisfy(r -> {
            assertThat(r.getId()).startsWith("evt_");
            assertThat(r.getDescription()).isNull();
            assertThat(r.getCreatedBy()).isNull();
        });
    }

    @Test
    void specVersionsMatchGo() {
        // schema_content is jsonb: compare as JSON trees (key order is not significant).
        Map<String, JsonNode> expectedSchemas = new TreeMap<>();
        for (String l : expected.get("schema")) {
            String[] f = l.split("\t", -1);
            expectedSchemas.put(f[1], readTree(f[6]));
            assertThat(f[2]).isEqualTo("v1");
            assertThat(f[3]).isEqualTo("application/schema+json");
            // "JSON" here (not a SchemaType constant) is a flowcatalyst-go seed
            // defect: internal/platform/seed/event_types.go still writes the
            // literal "JSON" as of HEAD, which chk_msg_event_type_spec_versions_schema_type
            // (migration 051) would itself now reject on a fresh Go database
            // (confirmed: `go run ./cmd/fcdev start` fails seeding on this).
            // Java writes the correct SchemaType.JSON_SCHEMA and this fixture is
            // deliberately corrected rather than reproducing the Go defect.
            assertThat(f[4]).isEqualTo("JSON_SCHEMA");
            assertThat(f[5]).isEqualTo("CURRENT");
        }
        assertThat(expectedSchemas).hasSize(72);

        var rows = db.select(MSG_EVENT_TYPES.CODE, MSG_EVENT_TYPE_SPEC_VERSIONS.ID,
                        MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION, MSG_EVENT_TYPE_SPEC_VERSIONS.MIME_TYPE,
                        MSG_EVENT_TYPE_SPEC_VERSIONS.SCHEMA_TYPE, MSG_EVENT_TYPE_SPEC_VERSIONS.STATUS,
                        MSG_EVENT_TYPE_SPEC_VERSIONS.SCHEMA_CONTENT)
                .from(MSG_EVENT_TYPE_SPEC_VERSIONS)
                .join(MSG_EVENT_TYPES).on(MSG_EVENT_TYPES.ID.eq(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID))
                .fetch();
        assertThat(rows).hasSize(72);
        Map<String, JsonNode> actualSchemas = new TreeMap<>();
        for (var r : rows) {
            assertThat(r.value2()).startsWith("sch_");
            assertThat(r.value3()).isEqualTo("v1");
            assertThat(r.value4()).isEqualTo("application/schema+json");
            assertThat(r.value5()).isEqualTo("JSON_SCHEMA");
            assertThat(r.value6()).isEqualTo("CURRENT");
            actualSchemas.put(r.value1(), readTree(r.value7().data()));
        }
        assertThat(actualSchemas.keySet()).containsExactlyElementsOf(expectedSchemas.keySet());
        for (var e : expectedSchemas.entrySet()) {
            assertThat(actualSchemas.get(e.getKey())).as("schema for %s", e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void platformApplicationMatchesGo() {
        var apps = db.selectFrom(APP_APPLICATIONS).fetch();
        assertThat(apps).hasSize(1);
        var app = apps.getFirst();
        assertThat(line("application", app.getType(), app.getCode(), app.getName(), app.getDescription(), app.getActive()))
                .isEqualTo(expected.get("application").getFirst());
        assertThat(app.getId()).startsWith("app_");
        assertThat(app.getServiceAccountId()).isNull();
        assertThat(app.getIconUrl()).isNull();
    }

    @Test
    void exampleProcessMatchesGo() {
        var procs = db.selectFrom(MSG_PROCESSES).fetch();
        assertThat(procs).hasSize(1);
        var p = procs.getFirst();
        String tags = "{" + String.join(",", p.getTags()) + "}";
        assertThat(line("process", p.getCode(), p.getName(), p.getStatus(), p.getSource(), p.getApplication(),
                p.getSubdomain(), p.getProcessName(), p.getDiagramType(), tags))
                .isEqualTo(expected.get("process").getFirst());
        assertThat(p.getId()).startsWith("prc_");
        assertThat(p.getDescription()).isEqualTo(DefaultProcesses.EXAMPLE_DESCRIPTION);
        assertThat(p.getBody()).isEqualTo(DefaultProcesses.EXAMPLE_BODY);
        assertThat(p.getBody()).startsWith("flowchart TD\n    Start([Customer places order])").endsWith("terminal;\n");
    }

    @Test
    void internalIdpAndEmailDomainMappingMatchGo() {
        var idps = db.selectFrom(OAUTH_IDENTITY_PROVIDERS).fetch();
        assertThat(idps).hasSize(1);
        var idp = idps.getFirst();
        assertThat(line("idp", idp.getCode(), idp.getName(), idp.getType(), idp.getOidcMultiTenant(), idp.getSyncRolesFromIdp()))
                .isEqualTo(expected.get("idp").getFirst());
        assertThat(idp.getId()).startsWith("idp_");
        assertThat(idp.getOidcIssuerUrl()).isNull();

        var edms = db.selectFrom(TNT_EMAIL_DOMAIN_MAPPINGS).fetch();
        assertThat(edms).hasSize(1);
        var edm = edms.getFirst();
        assertThat(line("edm", edm.getEmailDomain(), edm.getScopeType(), edm.getSyncRolesFromIdp(), edm.getRequire_2fa(),
                edm.getRememberDeviceEnabled(), edm.getRememberDeviceDays()))
                .isEqualTo(expected.get("edm").getFirst());
        assertThat(edm.getId()).startsWith("edm_");
        assertThat(edm.getIdentityProviderId()).isEqualTo(idp.getId());
        assertThat(edm.getPrimaryClientId()).isNull();
    }

    @Test
    void bootstrapAdminCreatedFromEnv() {
        var principals = db.selectFrom(IAM_PRINCIPALS).fetch();
        assertThat(principals).hasSize(1);
        var admin = principals.getFirst();
        assertThat(admin.getId()).startsWith("prn_");
        assertThat(admin.getType()).isEqualTo("USER");
        assertThat(admin.getScope()).isEqualTo("ANCHOR");
        assertThat(admin.getName()).isEqualTo("Local Admin");
        assertThat(admin.getActive()).isTrue();
        assertThat(admin.getEmail()).isEqualTo("admin@flowcatalyst.local");
        assertThat(admin.getEmailDomain()).isEqualTo("flowcatalyst.local");
        assertThat(admin.getIdpType()).isEqualTo("INTERNAL");
        assertThat(admin.getExternalIdpId()).isNull();
        assertThat(admin.getClientId()).isNull();
        assertThat(admin.getApplicationId()).isNull();
        assertThat(admin.getServiceAccountId()).isNull();
        assertThat(admin.getAllApplications()).isTrue();
        assertThat(admin.getLastLoginAt()).isNull();
        assertThat(admin.getPasswordHash()).startsWith("$argon2id$v=19$m=65536,t=3,p=4$");
        assertThat(PasswordHash.matches("DevPassword123!", admin.getPasswordHash())).isTrue();

        var grants = db.selectFrom(IAM_PRINCIPAL_ROLES).fetch();
        assertThat(grants).hasSize(1);
        assertThat(grants.getFirst().getPrincipalId()).isEqualTo(admin.getId());
        assertThat(grants.getFirst().getRoleName()).isEqualTo("platform:super-admin");
        assertThat(grants.getFirst().getAssignmentSource()).isEqualTo("BOOTSTRAP");
        assertThat(grants.getFirst().getAssignedAt()).isNotNull();
    }

    // ── idempotency ──────────────────────────────────────────────────────

    @Test
    void secondRunChangesNothing() {
        assertThat(secondRun).isEqualTo(firstRun);
        assertThat(firstRun.get("iam_roles")).hasSize(17);
        assertThat(firstRun.get("iam_role_permissions")).hasSize(186);
        assertThat(firstRun.get("msg_event_types")).hasSize(72);
        assertThat(firstRun.get("msg_event_type_spec_versions")).hasSize(72);
        assertThat(firstRun.get("app_applications")).hasSize(1);
        assertThat(firstRun.get("msg_processes")).hasSize(1);
        assertThat(firstRun.get("oauth_identity_providers")).hasSize(1);
        assertThat(firstRun.get("tnt_email_domain_mappings")).hasSize(1);
        assertThat(firstRun.get("iam_principals")).hasSize(1);
        assertThat(firstRun.get("iam_principal_roles")).hasSize(1);
    }

    @Test
    void rerunRefreshesEventTypeNamesButKeepsIds() {
        // Go: an existing event type gets name + updated_at refreshed, never a new row.
        String id = db.select(MSG_EVENT_TYPES.ID).from(MSG_EVENT_TYPES)
                .where(MSG_EVENT_TYPES.CODE.eq("platform:iam:user:created")).fetchOne(MSG_EVENT_TYPES.ID);
        db.update(MSG_EVENT_TYPES).set(MSG_EVENT_TYPES.NAME, "renamed by operator")
                .where(MSG_EVENT_TYPES.ID.eq(id)).execute();
        new Seeder(ds, DEV_ENV).run();
        var row = db.selectFrom(MSG_EVENT_TYPES).where(MSG_EVENT_TYPES.ID.eq(id)).fetchOne();
        assertThat(row.getName()).isEqualTo("User Created");
        assertThat(db.fetchCount(MSG_EVENT_TYPES, MSG_EVENT_TYPES.CODE.eq("platform:iam:user:created"))).isEqualTo(1);
        assertThat(snapshot(db)).isEqualTo(firstRun);
    }

    @Test
    void rerunLeavesRoleEditsAlone() {
        // Go: skip-if-name-exists preserves any local edits to permissions.
        String id = db.select(IAM_ROLES.ID).from(IAM_ROLES)
                .where(IAM_ROLES.NAME.eq("platform:auth-readonly")).fetchOne(IAM_ROLES.ID);
        db.deleteFrom(IAM_ROLE_PERMISSIONS).where(IAM_ROLE_PERMISSIONS.ROLE_ID.eq(id)).execute();
        try {
            new Seeder(ds, DEV_ENV).run();
            assertThat(db.fetchCount(IAM_ROLE_PERMISSIONS, IAM_ROLE_PERMISSIONS.ROLE_ID.eq(id))).isZero();
        } finally {
            for (String p : List.of(Permissions.AUTH_CLIENT_AUTH_CONFIG_READ, Permissions.AUTH_OAUTH_CLIENT_READ)) {
                db.insertInto(IAM_ROLE_PERMISSIONS).set(IAM_ROLE_PERMISSIONS.ROLE_ID, id)
                        .set(IAM_ROLE_PERMISSIONS.PERMISSION, p).execute();
            }
        }
    }

    // ── bootstrap admin: the skip paths ──────────────────────────────────

    @Test
    void withoutEnvNoAdminIdpOrMappingIsCreated() {
        DataSource fresh = TestPg.newDatabase("seed_noenv");
        Migrator.migrate(fresh);
        DSLContext d = DSL.using(fresh, SQLDialect.POSTGRES);

        new Seeder(fresh, new EnvReader(Map.of())).run();
        assertThat(d.fetchCount(IAM_PRINCIPALS)).isZero();
        assertThat(d.fetchCount(OAUTH_IDENTITY_PROVIDERS)).isZero();
        assertThat(d.fetchCount(TNT_EMAIL_DOMAIN_MAPPINGS)).isZero();
        assertThat(d.fetchCount(IAM_PRINCIPAL_ROLES)).isZero();
        // everything else is still seeded
        assertThat(d.fetchCount(IAM_ROLES)).isEqualTo(17);
        assertThat(d.fetchCount(MSG_EVENT_TYPES)).isEqualTo(72);
        assertThat(d.fetchCount(APP_APPLICATIONS)).isEqualTo(1);

        // email only / password only → still skipped
        new Seeder(fresh, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_EMAIL, "a@b.c"))).run();
        new Seeder(fresh, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_PASSWORD, "pw"))).run();
        assertThat(d.fetchCount(IAM_PRINCIPALS)).isZero();

        // malformed email → skipped with a warning
        new Seeder(fresh, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_EMAIL, "nobody", Seeder.ENV_BOOTSTRAP_PASSWORD, "pw"))).run();
        new Seeder(fresh, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_EMAIL, "nobody@", Seeder.ENV_BOOTSTRAP_PASSWORD, "pw"))).run();
        assertThat(d.fetchCount(IAM_PRINCIPALS)).isZero();
        assertThat(d.fetchCount(OAUTH_IDENTITY_PROVIDERS)).isZero();

        // a later boot WITH the env creates the admin (default name, trimmed email)
        new Seeder(fresh, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_EMAIL, "  Ops@Example.COM ", Seeder.ENV_BOOTSTRAP_PASSWORD, "pw"))).run();
        var admin = d.selectFrom(IAM_PRINCIPALS).fetchOne();
        assertThat(admin).isNotNull();
        assertThat(admin.getName()).isEqualTo("Bootstrap Admin");
        assertThat(admin.getEmail()).isEqualTo("ops@example.com");
        assertThat(admin.getEmailDomain()).isEqualTo("example.com");
        assertThat(d.selectFrom(TNT_EMAIL_DOMAIN_MAPPINGS).fetchOne().getEmailDomain()).isEqualTo("Example.COM");
        assertThat(d.fetchCount(OAUTH_IDENTITY_PROVIDERS, OAUTH_IDENTITY_PROVIDERS.CODE.eq("internal"))).isEqualTo(1);
    }

    @Test
    void existingAnchorUserSuppressesBootstrap() {
        DataSource fresh = TestPg.newDatabase("seed_anchor");
        Migrator.migrate(fresh);
        DSLContext d = DSL.using(fresh, SQLDialect.POSTGRES);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        d.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, EntityType.PRINCIPAL.generate())
                .set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, "Pre-existing")
                .set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, "someone@else.test")
                .set(IAM_PRINCIPALS.EMAIL_DOMAIN, "else.test")
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, true)
                .set(IAM_PRINCIPALS.CREATED_AT, now)
                .set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();

        new Seeder(fresh, DEV_ENV).run();
        assertThat(d.fetchCount(IAM_PRINCIPALS)).isEqualTo(1);
        assertThat(d.fetchCount(IAM_PRINCIPALS, IAM_PRINCIPALS.EMAIL.eq("admin@flowcatalyst.local"))).isZero();
        assertThat(d.fetchCount(OAUTH_IDENTITY_PROVIDERS)).isZero();
        assertThat(d.fetchCount(TNT_EMAIL_DOMAIN_MAPPINGS)).isZero();
        assertThat(d.fetchCount(IAM_ROLES)).isEqualTo(17);
    }

    @Test
    void existingEmailSuppressesBootstrapInsideTheTransaction() {
        DataSource fresh = TestPg.newDatabase("seed_email");
        Migrator.migrate(fresh);
        DSLContext d = DSL.using(fresh, SQLDialect.POSTGRES);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // A CLIENT-scoped user with the bootstrap email: not an anchor user, so the
        // count check passes, but the by-email check inside the tx must skip.
        d.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, EntityType.PRINCIPAL.generate())
                .set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.SCOPE, "CLIENT")
                .set(IAM_PRINCIPALS.NAME, "Taken")
                .set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, "admin@flowcatalyst.local")
                .set(IAM_PRINCIPALS.EMAIL_DOMAIN, "flowcatalyst.local")
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, true)
                .set(IAM_PRINCIPALS.CREATED_AT, now)
                .set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();

        new Seeder(fresh, DEV_ENV).run();
        assertThat(d.fetchCount(IAM_PRINCIPALS)).isEqualTo(1);
        assertThat(d.fetchCount(OAUTH_IDENTITY_PROVIDERS)).isZero();
        assertThat(d.fetchCount(IAM_PRINCIPAL_ROLES)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /// Every seeder-owned table → its rows as stable key strings (ids and the
    /// columns the seeder may rewrite), so two passes can be compared.
    static Map<String, List<String>> snapshot(DSLContext db) {
        var s = new LinkedHashMap<String, List<String>>();
        s.put("app_applications", keys(db.select(APP_APPLICATIONS.ID, APP_APPLICATIONS.CODE, APP_APPLICATIONS.NAME)
                .from(APP_APPLICATIONS).fetch()));
        s.put("iam_roles", keys(db.select(IAM_ROLES.ID, IAM_ROLES.NAME, IAM_ROLES.DISPLAY_NAME, IAM_ROLES.UPDATED_AT)
                .from(IAM_ROLES).fetch()));
        s.put("iam_role_permissions", keys(db.select(IAM_ROLE_PERMISSIONS.ROLE_ID, IAM_ROLE_PERMISSIONS.PERMISSION)
                .from(IAM_ROLE_PERMISSIONS).fetch()));
        // updated_at deliberately excluded: Go refreshes it on every pass.
        s.put("msg_event_types", keys(db.select(MSG_EVENT_TYPES.ID, MSG_EVENT_TYPES.CODE, MSG_EVENT_TYPES.NAME,
                MSG_EVENT_TYPES.CREATED_AT).from(MSG_EVENT_TYPES).fetch()));
        s.put("msg_event_type_spec_versions", keys(db.select(MSG_EVENT_TYPE_SPEC_VERSIONS.ID,
                MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID, MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION,
                MSG_EVENT_TYPE_SPEC_VERSIONS.UPDATED_AT).from(MSG_EVENT_TYPE_SPEC_VERSIONS).fetch()));
        s.put("msg_processes", keys(db.select(MSG_PROCESSES.ID, MSG_PROCESSES.CODE, MSG_PROCESSES.UPDATED_AT)
                .from(MSG_PROCESSES).fetch()));
        s.put("oauth_identity_providers", keys(db.select(OAUTH_IDENTITY_PROVIDERS.ID, OAUTH_IDENTITY_PROVIDERS.CODE,
                OAUTH_IDENTITY_PROVIDERS.UPDATED_AT).from(OAUTH_IDENTITY_PROVIDERS).fetch()));
        s.put("tnt_email_domain_mappings", keys(db.select(TNT_EMAIL_DOMAIN_MAPPINGS.ID, TNT_EMAIL_DOMAIN_MAPPINGS.EMAIL_DOMAIN,
                TNT_EMAIL_DOMAIN_MAPPINGS.UPDATED_AT).from(TNT_EMAIL_DOMAIN_MAPPINGS).fetch()));
        s.put("iam_principals", keys(db.select(IAM_PRINCIPALS.ID, IAM_PRINCIPALS.EMAIL, IAM_PRINCIPALS.PASSWORD_HASH,
                IAM_PRINCIPALS.UPDATED_AT).from(IAM_PRINCIPALS).fetch()));
        s.put("iam_principal_roles", keys(db.select(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, IAM_PRINCIPAL_ROLES.ROLE_NAME,
                IAM_PRINCIPAL_ROLES.ASSIGNED_AT).from(IAM_PRINCIPAL_ROLES).fetch()));
        return s;
    }

    static List<String> keys(List<? extends Record> rows) {
        return rows.stream()
                .map(r -> {
                    var parts = new ArrayList<String>();
                    for (int i = 0; i < r.size(); i++) {
                        parts.add(String.valueOf(r.get(i)));
                    }
                    return String.join("|", parts);
                })
                .sorted()
                .toList();
    }

    static String line(Object... fields) {
        return java.util.Arrays.stream(fields).map(String::valueOf).collect(Collectors.joining("\t"));
    }

    static JsonNode readTree(String json) {
        try {
            return Json.MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    /// `seed/go-seed-expected.tsv` grouped by its first column.
    static Map<String, List<String>> loadExpected() {
        try (InputStream in = SeederTest.class.getResourceAsStream("/seed/go-seed-expected.tsv")) {
            assertThat(in).as("fixture seed/go-seed-expected.tsv on the test classpath").isNotNull();
            var out = new LinkedHashMap<String, List<String>>();
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                out.computeIfAbsent(l.substring(0, l.indexOf('\t')), _ -> new ArrayList<>()).add(l);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
