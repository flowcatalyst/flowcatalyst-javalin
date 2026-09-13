package io.flowcatalyst.platform.seed;

import io.flowcatalyst.platform.oauthclient.operations.Secrets;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.tsid.EntityType;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENTS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_GRANT_TYPES;

/// The idempotent upsert of the `fcdev-router` client + `SERVICE`/`ANCHOR`
/// principal + `platform:router` role + `client_credentials` grant
/// (`docs/spec/router-config-auth.md` §3) — moved here from `fcdev`'s
/// `DevBootstrap` so both `fcdev` (a real boot) and
/// `server/…/RouterStartupOrderTest` (which needs credentials before a
/// [io.flowcatalyst.server.Server] exists to bootstrap them through) can call
/// it directly. `DevBootstrap.bootstrapRouterCredentials` is now a thin
/// wrapper: the operator-supplied-client guard, then this, then `setDefault`
/// on the three `FC_ROUTER_*` env vars.
public final class RouterClientBootstrap {

    /// The router's fixed client id — what makes this idempotent across
    /// restarts: the same OAuth client row (and its linked `SERVICE`
    /// principal) every boot, a fresh secret each time.
    public static final String CLIENT_ID = "fcdev-router";
    private static final String PRINCIPAL_NAME = "fcdev router";
    /// Not [io.flowcatalyst.platform.principal.RoleAssignment#ADMIN_ASSIGNED]:
    /// nobody assigned this by hand, the bootstrap did — same reasoning as
    /// the bootstrap admin's own `BOOTSTRAP` source tag ([Seeder]).
    private static final String ROLE_SOURCE = "BOOTSTRAP";

    private RouterClientBootstrap() {
    }

    /// The freshly minted credentials for [#CLIENT_ID] — `secret` is the
    /// plaintext, shown once, and never itself stored (only its verify-only
    /// hash is, [Secrets#hashedRef]).
    public record Credentials(String clientId, String secret) {
    }

    /// Upserts the client/principal/role/grant and returns a FRESH secret —
    /// this is meant to run on every boot, not just the first. Direct jOOQ,
    /// [Seeder]-style: infrastructure hydration, no unit of work, no domain
    /// events.
    public static Credentials bootstrap(DataSource pool, Encryption encryption) {
        String plaintext = Secrets.generatePlaintext();
        String secretRef = encryption.hashSecretRef(plaintext);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        DSLContext db = DSL.using(pool, SQLDialect.POSTGRES);
        String principalId = db.select(OAUTH_CLIENTS.SERVICE_ACCOUNT_PRINCIPAL_ID)
                .from(OAUTH_CLIENTS)
                .where(OAUTH_CLIENTS.CLIENT_ID.eq(CLIENT_ID))
                .fetchOptional(OAUTH_CLIENTS.SERVICE_ACCOUNT_PRINCIPAL_ID)
                .orElse(null);

        if (principalId == null) {
            principalId = EntityType.PRINCIPAL.generate();
            db.insertInto(IAM_PRINCIPALS)
                    .set(IAM_PRINCIPALS.ID, principalId)
                    .set(IAM_PRINCIPALS.TYPE, "SERVICE")
                    .set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                    .set(IAM_PRINCIPALS.CLIENT_ID, (String) null)
                    .set(IAM_PRINCIPALS.APPLICATION_ID, (String) null)
                    .set(IAM_PRINCIPALS.NAME, PRINCIPAL_NAME)
                    .set(IAM_PRINCIPALS.ACTIVE, true)
                    .set(IAM_PRINCIPALS.EMAIL, (String) null)
                    .set(IAM_PRINCIPALS.EMAIL_DOMAIN, (String) null)
                    .set(IAM_PRINCIPALS.IDP_TYPE, (String) null)
                    .set(IAM_PRINCIPALS.EXTERNAL_IDP_ID, (String) null)
                    .set(IAM_PRINCIPALS.PASSWORD_HASH, (String) null)
                    .set(IAM_PRINCIPALS.LAST_LOGIN_AT, (OffsetDateTime) null)
                    // Deliberately no service-account row (spec §3): unlike a
                    // provisioned application's principal, this one carries no
                    // ServiceAccount aggregate at all.
                    .set(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, (String) null)
                    .set(IAM_PRINCIPALS.ALL_APPLICATIONS, true)
                    .set(IAM_PRINCIPALS.CREATED_AT, now)
                    .set(IAM_PRINCIPALS.UPDATED_AT, now)
                    .set(IAM_PRINCIPALS.DEV_CLIENT_SECRET_REF, (String) null)
                    .set(IAM_PRINCIPALS.DEV_CLIENT_SECRET_UPDATED_AT, (OffsetDateTime) null)
                    .execute();
        }

        db.insertInto(IAM_PRINCIPAL_ROLES)
                .set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, principalId)
                .set(IAM_PRINCIPAL_ROLES.ROLE_NAME, PlatformRoles.APPLICATION_CODE + ":router")
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, ROLE_SOURCE)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, now)
                .onConflictDoNothing()
                .execute();

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(OAUTH_CLIENTS.CLIENT_NAME, PRINCIPAL_NAME);
        row.put(OAUTH_CLIENTS.CLIENT_TYPE, "CONFIDENTIAL");
        row.put(OAUTH_CLIENTS.CLIENT_SECRET_REF, secretRef);
        row.put(OAUTH_CLIENTS.SERVICE_ACCOUNT_PRINCIPAL_ID, principalId);
        row.put(OAUTH_CLIENTS.ACTIVE, true);
        row.put(OAUTH_CLIENTS.UPDATED_AT, now);
        db.insertInto(OAUTH_CLIENTS)
                .set(OAUTH_CLIENTS.ID, EntityType.OAUTH_CLIENT.generate())
                .set(OAUTH_CLIENTS.CLIENT_ID, CLIENT_ID)
                .set(OAUTH_CLIENTS.CREATED_AT, now)
                .set(row)
                .onConflict(OAUTH_CLIENTS.CLIENT_ID).doUpdate().set(row)
                .execute();

        String oauthClientRowId = db.select(OAUTH_CLIENTS.ID).from(OAUTH_CLIENTS)
                .where(OAUTH_CLIENTS.CLIENT_ID.eq(CLIENT_ID))
                .fetchOne(OAUTH_CLIENTS.ID);
        db.insertInto(OAUTH_CLIENT_GRANT_TYPES)
                .set(OAUTH_CLIENT_GRANT_TYPES.OAUTH_CLIENT_ID, oauthClientRowId)
                .set(OAUTH_CLIENT_GRANT_TYPES.GRANT_TYPE, "client_credentials")
                .onConflictDoNothing()
                .execute();

        return new Credentials(CLIENT_ID, plaintext);
    }
}
