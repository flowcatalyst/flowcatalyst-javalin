package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static io.flowcatalyst.db.generated.Tables.MSG_CONNECTIONS;

/// Test fixture: service-account rows written directly, and the production
/// [SigningReach] over a test database — for the tests of operations that
/// name a signing account (security-fixes-2026-09-24 S3.1/S3.2).
public final class SigningAccounts {

    private SigningAccounts() {
    }

    /// The real [SigningReach] over `ds`.
    public static SigningReach reach(DataSource ds) {
        return SigningReach.of(new ServiceAccountRepository(ds, Optional.empty()), new PrincipalRepository(ds),
                new ApplicationRepository(ds));
    }

    /// An active account with id `id` (created if absent; an existing row
    /// with that id is left as it is), linked to `clientIds` (empty =
    /// anchor-tier) and owned by `applicationId` (`null` = standalone).
    public static String seed(DataSource ds, String id, List<String> clientIds, String applicationId) {
        DSL.using(ds, SQLDialect.POSTGRES).insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, id)
                .set(IAM_SERVICE_ACCOUNTS.CODE, "signing-" + id.toLowerCase(java.util.Locale.ROOT))
                .set(IAM_SERVICE_ACCOUNTS.NAME, "signing fixture " + id)
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, true)
                .set(IAM_SERVICE_ACCOUNTS.CLIENT_IDS, clientIds.toArray(String[]::new))
                .onConflictDoNothing()
                .execute();
        return id;
    }

    /// A fresh account with a generated id.
    public static String seed(DataSource ds, List<String> clientIds, String applicationId) {
        return seed(ds, EntityType.SERVICE_ACCOUNT.generate(), clientIds, applicationId);
    }

    /// A persisted `SERVICE` principal linked to account `serviceAccountId`
    /// — the principal an application's own credentials authenticate as, so
    /// [SigningReach] can tell the caller IS that account's application.
    public static String servicePrincipal(DataSource ds, String serviceAccountId) {
        var principal = io.flowcatalyst.platform.principal.Principal.newService(serviceAccountId, "signing principal " + serviceAccountId);
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(true);
            new PrincipalRepository(ds).persist(principal, io.flowcatalyst.sdk.usecase.jdbc.DbTx.wrapForBootstrap(conn));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return principal.id();
    }

    /// A persisted subscription scoped to `clientId` (`null` = platform-wide)
    /// that names `serviceAccountId` (may be `null`) and belongs to
    /// `applicationCode` (may be `null`). Answers its id.
    public static String subscription(DataSource ds, String clientId, String serviceAccountId, String applicationCode) {
        String code = "signing-" + EntityType.SUBSCRIPTION.generate().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        var s = io.flowcatalyst.platform.subscription.Subscription.create(code, "signing fixture", "https://signing.example.test/hook")
                .withClientId(clientId)
                .withServiceAccountId(serviceAccountId)
                .withApplicationCode(applicationCode);
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            new io.flowcatalyst.platform.subscription.SubscriptionRepository(ds)
                    .persist(s, io.flowcatalyst.sdk.usecase.jdbc.DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return s.id();
    }

    /// An active connection with id `id` (created if absent), scoped to
    /// `clientId` (`null` = platform-wide), signing with `serviceAccountId`.
    public static String connection(DataSource ds, String id, String clientId, String serviceAccountId) {
        DSL.using(ds, SQLDialect.POSTGRES).insertInto(MSG_CONNECTIONS)
                .set(MSG_CONNECTIONS.ID, id)
                .set(MSG_CONNECTIONS.CODE, "signing-" + id.toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                .set(MSG_CONNECTIONS.NAME, "signing fixture " + id)
                .set(MSG_CONNECTIONS.STATUS, "ACTIVE")
                .set(MSG_CONNECTIONS.SERVICE_ACCOUNT_ID, serviceAccountId)
                .set(MSG_CONNECTIONS.CLIENT_ID, clientId)
                .onConflictDoNothing()
                .execute();
        return id;
    }
}
