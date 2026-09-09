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

import io.flowcatalyst.platform.eventtype.SchemaType;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.server.EnvReader;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

/// Installs the built-in roles, the platform application, the platform
/// event-types catalogue, the example process and (optionally) the bootstrap
/// admin. Startup-time hydration: runs after migrations and before HTTP
/// serving begins. The behavioural contract is `docs/spec/seeder.md`; the
/// row set is pinned by `src/test/resources/seed/go-seed-expected.tsv`.
///
/// This is infrastructure processing: no unit of work, no executing
/// principal, no domain events. All writes are direct inserts that upsert
/// idempotently on every boot — each step is "skip if the row already
/// exists" plus `ON CONFLICT DO NOTHING` against a concurrent seeder, so
/// running [#run] twice leaves the database as it was (the one deliberate
/// exception: an existing event type gets its catalogue `name` and
/// `updated_at` refreshed).
///
/// The bootstrap-admin step runs inside one transaction wrapped with
/// [DbTx#wrapForBootstrap] — the loud marker that this path is outside the
/// use-case envelope on purpose.
public final class Seeder {

    private static final Logger LOG = LoggerFactory.getLogger(Seeder.class);

    // Bootstrap admin env vars — keep these names stable so existing
    // deployment configs keep working.
    public static final String ENV_BOOTSTRAP_EMAIL = "FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL";
    public static final String ENV_BOOTSTRAP_PASSWORD = "FLOWCATALYST_BOOTSTRAP_ADMIN_PASSWORD";
    public static final String ENV_BOOTSTRAP_NAME = "FLOWCATALYST_BOOTSTRAP_ADMIN_NAME";

    private static final String BOOTSTRAP_ROLE_SUPER_ADMIN = "platform:super-admin";
    private static final String BOOTSTRAP_ROLE_SOURCE = "BOOTSTRAP";
    private static final String BOOTSTRAP_DEFAULT_NAME = "Bootstrap Admin";

    private static final String PLATFORM_APPLICATION_CODE = "platform";
    private static final String PLATFORM_APPLICATION_NAME = "FlowCatalyst Platform";
    private static final String PLATFORM_APPLICATION_DESCRIPTION =
            "Core platform — its own OpenAPI document is published here as one of the applications";

    private static final String INTERNAL_IDP_CODE = "internal";
    private static final String INTERNAL_IDP_NAME = "Internal Authentication";
    private static final String INTERNAL_IDP_TYPE = "INTERNAL";

    private final DataSource dataSource;
    private final EnvReader env;

    /// Wires a seeder over the process environment.
    public Seeder(DataSource dataSource) {
        this(dataSource, EnvReader.system());
    }

    /// Wires a seeder reading the bootstrap-admin variables from `env`
    /// (unset and empty are the same thing). For tests and embedded callers.
    public Seeder(DataSource dataSource, EnvReader env) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.env = Objects.requireNonNull(env, "env");
    }

    /// Executes the full seed pass in the fixed order below. Throws
    /// [IllegalStateException] naming the failed step (`seed roles: …`);
    /// earlier steps' rows stay committed, later steps are not attempted.
    public void run() {
        DSLContext db = DSL.using(dataSource, SQLDialect.POSTGRES);
        step("seed platform application", () -> seedPlatformApplication(db));
        step("seed roles", () -> seedRoles(db));
        step("seed event types", () -> seedEventTypes(db));
        step("seed default processes", () -> seedDefaultProcesses(db));
        step("seed bootstrap admin", () -> seedBootstrapAdmin(db));
    }

    private static void step(String name, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            throw new IllegalStateException(name + ": " + e.getMessage(), e);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── platform application ─────────────────────────────────────────────

    /// Inserts the single `platform` application row if it doesn't already
    /// exist. Idempotent; leaves any existing row alone.
    private void seedPlatformApplication(DSLContext db) {
        if (db.fetchExists(APP_APPLICATIONS, APP_APPLICATIONS.CODE.eq(PLATFORM_APPLICATION_CODE))) {
            return; // already seeded
        }
        OffsetDateTime now = now();
        db.insertInto(APP_APPLICATIONS)
                .set(APP_APPLICATIONS.ID, EntityType.APPLICATION.generate())
                .set(APP_APPLICATIONS.TYPE, "APPLICATION")
                .set(APP_APPLICATIONS.CODE, PLATFORM_APPLICATION_CODE)
                .set(APP_APPLICATIONS.NAME, PLATFORM_APPLICATION_NAME)
                .set(APP_APPLICATIONS.DESCRIPTION, PLATFORM_APPLICATION_DESCRIPTION)
                .set(APP_APPLICATIONS.ICON_URL, (String) null)
                .set(APP_APPLICATIONS.WEBSITE, (String) null)
                .set(APP_APPLICATIONS.LOGO, (String) null)
                .set(APP_APPLICATIONS.LOGO_MIME_TYPE, (String) null)
                .set(APP_APPLICATIONS.DEFAULT_BASE_URL, (String) null)
                .set(APP_APPLICATIONS.SERVICE_ACCOUNT_ID, (String) null)
                .set(APP_APPLICATIONS.ACTIVE, true)
                .set(APP_APPLICATIONS.CREATED_AT, now)
                .set(APP_APPLICATIONS.UPDATED_AT, now)
                .onConflict(APP_APPLICATIONS.CODE).doNothing()
                .execute();
        LOG.info("seeded built-in platform application");
    }

    // ── roles ────────────────────────────────────────────────────────────

    /// Upserts the built-in roles: skip-if-name-exists (preserves any local
    /// edits to permissions).
    private void seedRoles(DSLContext db) {
        int inserted = 0;
        for (RoleDefinition r : PlatformRoles.all()) {
            if (db.fetchExists(IAM_ROLES, IAM_ROLES.NAME.eq(r.name()))) {
                continue; // role already exists, leave it alone
            }
            OffsetDateTime now = now();
            db.insertInto(IAM_ROLES)
                    .set(IAM_ROLES.ID, EntityType.ROLE.generate())
                    .set(IAM_ROLES.APPLICATION_ID, (String) null)
                    .set(IAM_ROLES.NAME, r.name())
                    .set(IAM_ROLES.DISPLAY_NAME, r.displayName())
                    .set(IAM_ROLES.DESCRIPTION, r.description())
                    .set(IAM_ROLES.APPLICATION_CODE, r.applicationCode())
                    .set(IAM_ROLES.SOURCE, r.source())
                    .set(IAM_ROLES.CLIENT_MANAGED, false)
                    .set(IAM_ROLES.CREATED_AT, now)
                    .set(IAM_ROLES.UPDATED_AT, now)
                    .onConflict(IAM_ROLES.NAME).doNothing()
                    .execute();
            // We may have raced with another seeder; re-lookup to get the
            // authoritative id before writing permissions.
            String id = db.select(IAM_ROLES.ID).from(IAM_ROLES)
                    .where(IAM_ROLES.NAME.eq(r.name()))
                    .fetchOptional(IAM_ROLES.ID)
                    .orElseThrow(() -> new IllegalStateException("lookup id for role " + r.name() + ": no rows"));
            for (String p : r.permissions()) {
                db.insertInto(IAM_ROLE_PERMISSIONS)
                        .set(IAM_ROLE_PERMISSIONS.ROLE_ID, id)
                        .set(IAM_ROLE_PERMISSIONS.PERMISSION, p)
                        .onConflictDoNothing()
                        .execute();
            }
            LOG.atInfo().setMessage("seeded built-in role")
                    .addKeyValue("role", r.name())
                    .log();
            inserted++;
        }
        if (inserted > 0) {
            LOG.atInfo().setMessage("built-in role seeding complete")
                    .addKeyValue("count", inserted)
                    .log();
        }
    }

    // ── event types ──────────────────────────────────────────────────────

    /// Upserts the full event-type catalogue: insert if not present (by
    /// code), update name on existing rows, and attach a `v1` spec version if
    /// the catalogue supplies a schema and no `v1` exists yet.
    private void seedEventTypes(DSLContext db) {
        int inserted = 0;
        for (PlatformEventTypes.Definition d : PlatformEventTypes.all()) {
            OffsetDateTime now = now();

            // Upsert by code. If a row already exists, just refresh name +
            // updated_at; never blow away source/status/client_scoped.
            String id = db.select(MSG_EVENT_TYPES.ID).from(MSG_EVENT_TYPES)
                    .where(MSG_EVENT_TYPES.CODE.eq(d.code()))
                    .fetchOptional(MSG_EVENT_TYPES.ID)
                    .orElse(null);
            if (id == null) {
                db.insertInto(MSG_EVENT_TYPES)
                        .set(MSG_EVENT_TYPES.ID, EntityType.EVENT_TYPE.generate())
                        .set(MSG_EVENT_TYPES.CODE, d.code())
                        .set(MSG_EVENT_TYPES.NAME, d.name())
                        .set(MSG_EVENT_TYPES.DESCRIPTION, (String) null)
                        .set(MSG_EVENT_TYPES.STATUS, "CURRENT")
                        .set(MSG_EVENT_TYPES.SOURCE, "UI")
                        .set(MSG_EVENT_TYPES.CLIENT_SCOPED, false)
                        .set(MSG_EVENT_TYPES.APPLICATION, d.application())
                        .set(MSG_EVENT_TYPES.SUBDOMAIN, d.subdomain())
                        .set(MSG_EVENT_TYPES.AGGREGATE, d.aggregate())
                        .set(MSG_EVENT_TYPES.CREATED_AT, now)
                        .set(MSG_EVENT_TYPES.UPDATED_AT, now)
                        .onConflict(MSG_EVENT_TYPES.CODE).doNothing()
                        .execute();
                // race-safe re-fetch
                id = db.select(MSG_EVENT_TYPES.ID).from(MSG_EVENT_TYPES)
                        .where(MSG_EVENT_TYPES.CODE.eq(d.code()))
                        .fetchOptional(MSG_EVENT_TYPES.ID)
                        .orElseThrow(() -> new IllegalStateException("lookup id " + d.code() + ": no rows"));
                inserted++;
            } else {
                db.update(MSG_EVENT_TYPES)
                        .set(MSG_EVENT_TYPES.NAME, d.name())
                        .set(MSG_EVENT_TYPES.UPDATED_AT, now)
                        .where(MSG_EVENT_TYPES.ID.eq(id))
                        .execute();
            }

            // Schema attach (idempotent). Version "v1" by convention.
            if (d.schema() == null) {
                continue;
            }
            boolean hasV1 = db.fetchExists(MSG_EVENT_TYPE_SPEC_VERSIONS,
                    MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID.eq(id)
                            .and(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION.eq("v1")));
            if (hasV1) {
                continue;
            }
            db.insertInto(MSG_EVENT_TYPE_SPEC_VERSIONS)
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.ID, EntityType.SCHEMA.generate())
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.EVENT_TYPE_ID, id)
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.VERSION, "v1")
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.MIME_TYPE, "application/schema+json")
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.SCHEMA_CONTENT, JSONB.valueOf(Json.write(d.schema())))
                    // "JSON" (not a SchemaType constant) predates chk_msg_event_type_spec_versions_schema_type
                    // (migration 051); ported from Go's internal/platform/seed/event_types.go, which still
                    // has the same defect as of flowcatalyst-go HEAD (fcdev's own seed run fails the same way).
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.SCHEMA_TYPE, SchemaType.JSON_SCHEMA.name())
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.STATUS, "CURRENT")
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.CREATED_AT, now)
                    .set(MSG_EVENT_TYPE_SPEC_VERSIONS.UPDATED_AT, now)
                    .execute();
        }
        if (inserted > 0) {
            LOG.atInfo().setMessage("seeded platform event types")
                    .addKeyValue("inserted", inserted)
                    .addKeyValue("total", PlatformEventTypes.all().size())
                    .log();
        }
    }

    // ── default processes ────────────────────────────────────────────────

    /// Inserts the example on-demand fulfilment Process if no row with its
    /// code exists. No-op once seeded — operator edits are left alone.
    private void seedDefaultProcesses(DSLContext db) {
        if (db.fetchExists(MSG_PROCESSES, MSG_PROCESSES.CODE.eq(DefaultProcesses.EXAMPLE_CODE))) {
            return; // already seeded — leave operator edits alone
        }
        OffsetDateTime now = now();
        db.insertInto(MSG_PROCESSES)
                .set(MSG_PROCESSES.ID, EntityType.PROCESS.generate())
                .set(MSG_PROCESSES.CODE, DefaultProcesses.EXAMPLE_CODE)
                .set(MSG_PROCESSES.NAME, DefaultProcesses.EXAMPLE_NAME)
                .set(MSG_PROCESSES.DESCRIPTION, DefaultProcesses.EXAMPLE_DESCRIPTION)
                .set(MSG_PROCESSES.STATUS, DefaultProcesses.EXAMPLE_STATUS)
                .set(MSG_PROCESSES.SOURCE, DefaultProcesses.EXAMPLE_SOURCE)
                .set(MSG_PROCESSES.APPLICATION, DefaultProcesses.EXAMPLE_APPLICATION)
                .set(MSG_PROCESSES.SUBDOMAIN, DefaultProcesses.EXAMPLE_SUBDOMAIN)
                .set(MSG_PROCESSES.PROCESS_NAME, DefaultProcesses.EXAMPLE_PROCESS_NAME)
                .set(MSG_PROCESSES.BODY, DefaultProcesses.EXAMPLE_BODY)
                .set(MSG_PROCESSES.DIAGRAM_TYPE, DefaultProcesses.EXAMPLE_DIAGRAM_TYPE)
                .set(MSG_PROCESSES.TAGS, DefaultProcesses.EXAMPLE_TAGS.toArray(String[]::new))
                .set(MSG_PROCESSES.CREATED_AT, now)
                .set(MSG_PROCESSES.UPDATED_AT, now)
                .onConflict(MSG_PROCESSES.CODE).doNothing()
                .execute();
        LOG.atInfo().setMessage("seeded example on-demand fulfilment process")
                .addKeyValue("code", DefaultProcesses.EXAMPLE_CODE)
                .log();
    }

    // ── bootstrap admin ──────────────────────────────────────────────────

    /// Creates the initial super-admin USER + ANCHOR principal when no anchor
    /// user exists yet. Idempotent: if any anchor user is already present (or
    /// the named user has been created externally), it returns after logging.
    ///
    /// Reads credentials from [#ENV_BOOTSTRAP_EMAIL] / [#ENV_BOOTSTRAP_PASSWORD]
    /// / [#ENV_BOOTSTRAP_NAME]. When email/password are unset on a fresh
    /// install it logs a warning and returns — production deployments must
    /// opt in explicitly so we don't bake a known password into prod. fcdev
    /// pre-sets the env to `admin@flowcatalyst.local` / `DevPassword123!` so
    /// the local workflow "just works".
    private void seedBootstrapAdmin(DSLContext db) {
        int anchorCount = db.fetchCount(IAM_PRINCIPALS,
                IAM_PRINCIPALS.TYPE.eq("USER").and(IAM_PRINCIPALS.SCOPE.eq("ANCHOR")));
        if (anchorCount > 0) {
            return;
        }

        String email = env.get(ENV_BOOTSTRAP_EMAIL).strip();
        String password = env.get(ENV_BOOTSTRAP_PASSWORD);
        if (email.isEmpty() || password.isEmpty()) {
            LOG.warn("no bootstrap admin configured — set {} + {} to create one email_set={} password_set={}",
                    ENV_BOOTSTRAP_EMAIL, ENV_BOOTSTRAP_PASSWORD, !email.isEmpty(), !password.isEmpty());
            return;
        }
        int at = email.indexOf('@');
        if (at < 0 || at == email.length() - 1) {
            LOG.atWarn().setMessage("invalid bootstrap email format; skipping")
                    .addKeyValue("email", email)
                    .log();
            return;
        }
        String name = env.or(ENV_BOOTSTRAP_NAME, BOOTSTRAP_DEFAULT_NAME);

        // Hashing costs tens of milliseconds; do it before the transaction opens.
        String hash = PasswordHash.hash(password);

        boolean created;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                DbTx tx = DbTx.wrapForBootstrap(conn);
                created = bootstrapAdminTx(DSL.using(tx.connection(), SQLDialect.POSTGRES), email, at, name, hash);
                conn.commit();
            } catch (RuntimeException | SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("bootstrap admin transaction: " + e.getMessage(), e);
        }
        if (created) {
            LOG.atInfo().setMessage("bootstrap admin created scope=ANCHOR")
                    .addKeyValue("email", email)
                    .addKeyValue("role", BOOTSTRAP_ROLE_SUPER_ADMIN)
                    .log();
        }
    }

    /// The body of the bootstrap-admin transaction. Returns false when the
    /// admin was already present (nothing written).
    private boolean bootstrapAdminTx(DSLContext tx, String email, int at, String name, String hash) {
        // Idempotency: someone may have created this user via SQL before
        // bootstrap ran. Skip with a log if so.
        if (tx.fetchExists(IAM_PRINCIPALS, IAM_PRINCIPALS.EMAIL.eq(email))) {
            LOG.atInfo().setMessage("bootstrap admin already present; skipping")
                    .addKeyValue("email", email)
                    .log();
            return false;
        }

        // Ensure the 'internal' identity provider row exists. Idempotent.
        String idpId = tx.select(OAUTH_IDENTITY_PROVIDERS.ID).from(OAUTH_IDENTITY_PROVIDERS)
                .where(OAUTH_IDENTITY_PROVIDERS.CODE.eq(INTERNAL_IDP_CODE))
                .fetchOptional(OAUTH_IDENTITY_PROVIDERS.ID)
                .orElse(null);
        if (idpId == null) {
            idpId = EntityType.IDENTITY_PROVIDER.generate();
            OffsetDateTime now = now();
            tx.insertInto(OAUTH_IDENTITY_PROVIDERS)
                    .set(OAUTH_IDENTITY_PROVIDERS.ID, idpId)
                    .set(OAUTH_IDENTITY_PROVIDERS.CODE, INTERNAL_IDP_CODE)
                    .set(OAUTH_IDENTITY_PROVIDERS.NAME, INTERNAL_IDP_NAME)
                    .set(OAUTH_IDENTITY_PROVIDERS.TYPE, INTERNAL_IDP_TYPE)
                    .set(OAUTH_IDENTITY_PROVIDERS.OIDC_ISSUER_URL, (String) null)
                    .set(OAUTH_IDENTITY_PROVIDERS.OIDC_CLIENT_ID, (String) null)
                    .set(OAUTH_IDENTITY_PROVIDERS.OIDC_CLIENT_SECRET_REF, (String) null)
                    .set(OAUTH_IDENTITY_PROVIDERS.OIDC_MULTI_TENANT, false)
                    .set(OAUTH_IDENTITY_PROVIDERS.OIDC_ISSUER_PATTERN, (String) null)
                    .set(OAUTH_IDENTITY_PROVIDERS.SYNC_ROLES_FROM_IDP, false)
                    .set(OAUTH_IDENTITY_PROVIDERS.CREATED_AT, now)
                    .set(OAUTH_IDENTITY_PROVIDERS.UPDATED_AT, now)
                    .execute();
        }

        // Ensure an ANCHOR email-domain mapping for the admin's domain
        // exists so subsequent logins resolve to the internal IDP.
        String domain = email.substring(at + 1);
        if (!tx.fetchExists(TNT_EMAIL_DOMAIN_MAPPINGS, TNT_EMAIL_DOMAIN_MAPPINGS.EMAIL_DOMAIN.eq(domain))) {
            OffsetDateTime now = now();
            tx.insertInto(TNT_EMAIL_DOMAIN_MAPPINGS)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.ID, EntityType.EMAIL_DOMAIN_MAPPING.generate())
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.EMAIL_DOMAIN, domain)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.IDENTITY_PROVIDER_ID, idpId)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.SCOPE_TYPE, "ANCHOR")
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.PRIMARY_CLIENT_ID, (String) null)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.REQUIRED_OIDC_TENANT_ID, (String) null)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.REQUIRE_2FA, false)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.REMEMBER_DEVICE_ENABLED, false)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.REMEMBER_DEVICE_DAYS, 30)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.CREATED_AT, now)
                    .set(TNT_EMAIL_DOMAIN_MAPPINGS.UPDATED_AT, now)
                    .execute();
        }

        // Stored the way the principal repository stores a USER: email
        // lower-cased, domain derived from the stored email, no explicit
        // provider → INTERNAL. (`email` is already stripped and contains a
        // non-final `@`, so the domain is never empty.)
        String storedEmail = email.toLowerCase(Locale.ROOT);
        String storedDomain = storedEmail.substring(storedEmail.indexOf('@') + 1);
        String principalId = EntityType.PRINCIPAL.generate();
        OffsetDateTime now = now();
        tx.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, principalId)
                .set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.CLIENT_ID, (String) null)
                .set(IAM_PRINCIPALS.APPLICATION_ID, (String) null)
                .set(IAM_PRINCIPALS.NAME, name)
                .set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, storedEmail)
                .set(IAM_PRINCIPALS.EMAIL_DOMAIN, storedDomain)
                .set(IAM_PRINCIPALS.IDP_TYPE, "INTERNAL")
                .set(IAM_PRINCIPALS.EXTERNAL_IDP_ID, (String) null)
                .set(IAM_PRINCIPALS.PASSWORD_HASH, hash)
                .set(IAM_PRINCIPALS.LAST_LOGIN_AT, (OffsetDateTime) null)
                .set(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, (String) null)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, true)
                .set(IAM_PRINCIPALS.CREATED_AT, now)
                .set(IAM_PRINCIPALS.UPDATED_AT, now)
                .set(IAM_PRINCIPALS.DEV_CLIENT_SECRET_REF, (String) null)
                .set(IAM_PRINCIPALS.DEV_CLIENT_SECRET_UPDATED_AT, (OffsetDateTime) null)
                .execute();

        // The principal repository does NOT sync iam_principal_roles —
        // junction writes are explicit. Add the super-admin grant directly so
        // the new admin can sign in with full permissions.
        tx.insertInto(IAM_PRINCIPAL_ROLES)
                .set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, principalId)
                .set(IAM_PRINCIPAL_ROLES.ROLE_NAME, BOOTSTRAP_ROLE_SUPER_ADMIN)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, BOOTSTRAP_ROLE_SOURCE)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, DSL.currentOffsetDateTime())
                .onConflictDoNothing()
                .execute();
        return true;
    }
}
