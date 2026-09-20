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
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENTS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_GRANT_TYPES;

/// The idempotent upsert of fcdev's two function-development OAuth clients
/// (`docs/spec/function-developer-surface.md` §1): `fcdev-fn-host` (the
/// function host's own control-plane principal, role `platform:function-host`
/// — exactly what `/control/functions/*` needs) and `fcdev-fn-cli` (the `fn`
/// CLI's principal, roles `platform:function-publisher` +
/// `platform:messaging-admin` — publish/promote/config/secret plus the
/// messaging-admin surface `fn status`/`fn watch` read from). Moved here
/// beside [RouterClientBootstrap], whose pattern this copies verbatim: fixed
/// client ids (idempotent across restarts), a `SERVICE`/`ANCHOR` principal
/// with no `ServiceAccount` aggregate at all, a FRESH secret every boot,
/// direct jOOQ — no unit of work, no domain events, same as [Seeder].
public final class FunctionDevBootstrap {

    /// The function host's fixed client id.
    public static final String HOST_CLIENT_ID = "fcdev-fn-host";
    /// The `fn` CLI's fixed client id.
    public static final String CLI_CLIENT_ID = "fcdev-fn-cli";
    private static final String HOST_PRINCIPAL_NAME = "fcdev function host";
    private static final String CLI_PRINCIPAL_NAME = "fcdev fn CLI";
    /// Not [io.flowcatalyst.platform.principal.RoleAssignment#ADMIN_ASSIGNED]:
    /// nobody assigned this by hand, the bootstrap did — same as
    /// [RouterClientBootstrap] and the bootstrap admin's own `BOOTSTRAP`
    /// source tag ([Seeder]).
    private static final String ROLE_SOURCE = "BOOTSTRAP";

    private FunctionDevBootstrap() {
    }

    /// The freshly minted credentials for one client — `secret` is the
    /// plaintext, shown once, never itself stored (only its verify-only hash
    /// is, [Secrets#hashedRef]).
    public record Credentials(String clientId, String secret) {
    }

    /// Both clients' credentials from one [#bootstrap] call.
    public record Result(Credentials host, Credentials cli) {
    }

    /// Upserts both clients/principals/roles/grants and returns FRESH secrets
    /// for both — meant to run on every boot, not just the first.
    public static Result bootstrap(DataSource pool, Encryption encryption) {
        DSLContext db = DSL.using(pool, SQLDialect.POSTGRES);
        Credentials host = upsertClient(db, encryption, HOST_CLIENT_ID, HOST_PRINCIPAL_NAME,
                List.of(PlatformRoles.APPLICATION_CODE + ":function-host"));
        Credentials cli = upsertClient(db, encryption, CLI_CLIENT_ID, CLI_PRINCIPAL_NAME,
                List.of(PlatformRoles.APPLICATION_CODE + ":function-publisher",
                        PlatformRoles.APPLICATION_CODE + ":messaging-admin"));
        return new Result(host, cli);
    }

    private static Credentials upsertClient(DSLContext db, Encryption encryption, String clientId,
                                             String principalName, List<String> roles) {
        String plaintext = Secrets.generatePlaintext();
        String secretRef = encryption.hashSecretRef(plaintext);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        String principalId = db.select(OAUTH_CLIENTS.SERVICE_ACCOUNT_PRINCIPAL_ID)
                .from(OAUTH_CLIENTS)
                .where(OAUTH_CLIENTS.CLIENT_ID.eq(clientId))
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
                    .set(IAM_PRINCIPALS.NAME, principalName)
                    .set(IAM_PRINCIPALS.ACTIVE, true)
                    .set(IAM_PRINCIPALS.EMAIL, (String) null)
                    .set(IAM_PRINCIPALS.EMAIL_DOMAIN, (String) null)
                    .set(IAM_PRINCIPALS.IDP_TYPE, (String) null)
                    .set(IAM_PRINCIPALS.EXTERNAL_IDP_ID, (String) null)
                    .set(IAM_PRINCIPALS.PASSWORD_HASH, (String) null)
                    .set(IAM_PRINCIPALS.LAST_LOGIN_AT, (OffsetDateTime) null)
                    // Deliberately no service-account row (RouterClientBootstrap's own
                    // precedent): unlike a provisioned application's principal, this one
                    // carries no ServiceAccount aggregate at all.
                    .set(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, (String) null)
                    .set(IAM_PRINCIPALS.ALL_APPLICATIONS, true)
                    .set(IAM_PRINCIPALS.CREATED_AT, now)
                    .set(IAM_PRINCIPALS.UPDATED_AT, now)
                    .set(IAM_PRINCIPALS.DEV_CLIENT_SECRET_REF, (String) null)
                    .set(IAM_PRINCIPALS.DEV_CLIENT_SECRET_UPDATED_AT, (OffsetDateTime) null)
                    .execute();
        }

        for (String role : roles) {
            db.insertInto(IAM_PRINCIPAL_ROLES)
                    .set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, principalId)
                    .set(IAM_PRINCIPAL_ROLES.ROLE_NAME, role)
                    .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, ROLE_SOURCE)
                    .set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, now)
                    .onConflictDoNothing()
                    .execute();
        }

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(OAUTH_CLIENTS.CLIENT_NAME, principalName);
        row.put(OAUTH_CLIENTS.CLIENT_TYPE, "CONFIDENTIAL");
        row.put(OAUTH_CLIENTS.CLIENT_SECRET_REF, secretRef);
        row.put(OAUTH_CLIENTS.SERVICE_ACCOUNT_PRINCIPAL_ID, principalId);
        row.put(OAUTH_CLIENTS.ACTIVE, true);
        row.put(OAUTH_CLIENTS.UPDATED_AT, now);
        db.insertInto(OAUTH_CLIENTS)
                .set(OAUTH_CLIENTS.ID, EntityType.OAUTH_CLIENT.generate())
                .set(OAUTH_CLIENTS.CLIENT_ID, clientId)
                .set(OAUTH_CLIENTS.CREATED_AT, now)
                .set(row)
                .onConflict(OAUTH_CLIENTS.CLIENT_ID).doUpdate().set(row)
                .execute();

        String oauthClientRowId = db.select(OAUTH_CLIENTS.ID).from(OAUTH_CLIENTS)
                .where(OAUTH_CLIENTS.CLIENT_ID.eq(clientId))
                .fetchOne(OAUTH_CLIENTS.ID);
        db.insertInto(OAUTH_CLIENT_GRANT_TYPES)
                .set(OAUTH_CLIENT_GRANT_TYPES.OAUTH_CLIENT_ID, oauthClientRowId)
                .set(OAUTH_CLIENT_GRANT_TYPES.GRANT_TYPE, "client_credentials")
                .onConflictDoNothing()
                .execute();

        return new Credentials(clientId, plaintext);
    }
}
