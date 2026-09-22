package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionCode;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobKind;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.Protocol;
import io.flowcatalyst.platform.dispatchjob.RetryStrategy;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static io.flowcatalyst.db.generated.Tables.MSG_CONNECTIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [DeliveryCredentials#resolve] against real, TestPg-backed
/// `ApplicationRepository` / `SubscriptionRepository` / `ConnectionRepository`
/// / `ServiceAccountRepository` rows
/// (`docs/go-mirror/2026-09-22-delivery-credentials-handoff.md`, the
/// resolution-order reversal; `docs/spec/dispatch-delivery-credentials.md`
/// §2, S2-S6 kept and re-read under the new order). S1 (end to end through a
/// real HTTP delivery) and S7 (a throwing resolver) live in
/// [ProcessingApiTest], which already owns the loopback-subscriber fixture;
/// S8 (the `Platform` composition root) lives in
/// `io.flowcatalyst.server.DispatchDeliveryCredentialsWiringTest`.
class DeliveryCredentialsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ApplicationRepository APPLICATIONS = new ApplicationRepository(DS);
    private static final SubscriptionRepository SUBSCRIPTIONS = new SubscriptionRepository(DS);
    private static final ConnectionRepository CONNECTIONS = new ConnectionRepository(DS);
    private static final ServiceAccountRepository SERVICE_ACCOUNTS = new ServiceAccountRepository(DS, Optional.empty());

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final List<String> APPLICATION_IDS = new ArrayList<>();
    private static final List<String> SUBSCRIPTION_IDS = new ArrayList<>();
    private static final List<String> CONNECTION_IDS = new ArrayList<>();
    private static final List<String> SERVICE_ACCOUNT_IDS = new ArrayList<>();
    private static int seq = 0;

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(IAM_SERVICE_ACCOUNTS).where(IAM_SERVICE_ACCOUNTS.ID.in(SERVICE_ACCOUNT_IDS)).execute();
        DB.deleteFrom(MSG_CONNECTIONS).where(MSG_CONNECTIONS.ID.in(CONNECTION_IDS)).execute();
        DB.deleteFrom(MSG_SUBSCRIPTIONS).where(MSG_SUBSCRIPTIONS.ID.in(SUBSCRIPTION_IDS)).execute();
        DB.deleteFrom(APP_APPLICATIONS).where(APP_APPLICATIONS.ID.in(APPLICATION_IDS)).execute();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static String persistApplication(String code) {
        Application app = Application.create(ApplicationType.APPLICATION, code, code);
        persist(app, APPLICATIONS);
        APPLICATION_IDS.add(app.id());
        return app.id();
    }

    private static String persistSubscription(String code, String applicationCode) {
        Subscription sub = Subscription.create(code, code, "https://hook.example/" + code);
        if (applicationCode != null) {
            sub = sub.withApplicationCode(applicationCode);
        }
        persist(sub, SUBSCRIPTIONS);
        SUBSCRIPTION_IDS.add(sub.id());
        return sub.id();
    }

    /// A subscription naming `serviceAccountId` directly (step 1) and/or
    /// `connectionId` (step 2's route to a connection).
    private static String persistSubscription(String code, String serviceAccountId, String connectionId) {
        Subscription sub = Subscription.create(code, code, "https://hook.example/" + code);
        if (serviceAccountId != null) {
            sub = sub.withServiceAccountId(serviceAccountId);
        }
        if (connectionId != null) {
            sub = sub.withConnectionId(connectionId);
        }
        persist(sub, SUBSCRIPTIONS);
        SUBSCRIPTION_IDS.add(sub.id());
        return sub.id();
    }

    /// A connection naming `serviceAccountId` — `""` (never `null`, the
    /// aggregate requires non-null) represents "names no account" the same
    /// way Go's plain `string` does (hand-off: `strings.TrimSpace(...) != ""`).
    private static String persistConnection(String code, String serviceAccountId) {
        Connection conn = Connection.create(ConnectionCode.parse(code), code, serviceAccountId == null ? "" : serviceAccountId);
        persist(conn, CONNECTIONS);
        CONNECTION_IDS.add(conn.id());
        return conn.id();
    }

    private static <T extends HasId> void persist(T entity, Persist<T> repo) {
        try (var conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            repo.persist(entity, DbTx.wrapForBootstrap(conn));
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /// Inserts a raw `iam_service_accounts` row — `createdAt` is caller-controlled
    /// (unlike going through the aggregate's `create()`, which always stamps
    /// `Instant.now()`) so S4 can pin "oldest active wins" deterministically.
    private static String serviceAccount(String applicationId, String token, String signingSecret,
                                          boolean active, Instant createdAt) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DB.insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, id)
                .set(IAM_SERVICE_ACCOUNTS.CODE, "dc-svc-" + RUN + "-" + (seq++))
                .set(IAM_SERVICE_ACCOUNTS.NAME, "delivery-credentials test service account")
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, active)
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TOKEN_REF, token)
                .set(IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .set(IAM_SERVICE_ACCOUNTS.CREATED_AT, createdAt.atOffset(ZoneOffset.UTC))
                .execute();
        SERVICE_ACCOUNT_IDS.add(id);
        return id;
    }

    /// A named account with an explicit code — `named()`'s reasons quote the
    /// account's CODE, not its id (hand-off's table), so several tests need
    /// to control it.
    private static String serviceAccountWithCode(String applicationId, String code, String token, String signingSecret,
                                                  boolean active) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DB.insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, id)
                .set(IAM_SERVICE_ACCOUNTS.CODE, code)
                .set(IAM_SERVICE_ACCOUNTS.NAME, "delivery-credentials test service account")
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, active)
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TOKEN_REF, token)
                .set(IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .set(IAM_SERVICE_ACCOUNTS.CREATED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .execute();
        SERVICE_ACCOUNT_IDS.add(id);
        return id;
    }

    /// A minimal, otherwise-irrelevant [DispatchJob] varying only
    /// `subscriptionId`/`code` — [DeliveryCredentials] never touches any
    /// other field, and no dispatch-job row needs to exist in the database
    /// for `resolve` to run (it is a pure function of the object handed in).
    private static DispatchJob job(String subscriptionId, String code) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new DispatchJob(Tsid.generate(), null, DispatchJobKind.EVENT, code, null, null,
                "https://hook.example/x", Protocol.HTTP_WEBHOOK, null, "application/json", true,
                null, null, null, subscriptionId, null, null, null, DispatchMode.IMMEDIATE, 0, 30,
                null, 3, RetryStrategy.EXPONENTIAL, DispatchJobStatus.PENDING, 0, null, List.of(),
                null, null, null, now, now, null, null, null, null, null);
    }

    private static Function<String, OutboundCredentials.ById> byId() {
        return id -> OutboundCredentials.resolveById(SERVICE_ACCOUNTS, id);
    }

    private static Function<String, Optional<OutboundCredentials>> byApplication() {
        return applicationId -> OutboundCredentials.resolve(SERVICE_ACCOUNTS, applicationId);
    }

    /// No cache wrapper — S6 pins the cache in isolation, and every other
    /// scenario resolves at most once per test, so the un-cached resolver
    /// (still the same `OutboundCredentials` calls the cache wraps) is what
    /// keeps every other scenario independent of cache TTL timing.
    private static DeliveryCredentials resolver() {
        return DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, CONNECTIONS::findById, APPLICATIONS::findByCode,
                byId(), byApplication());
    }

    /// An [DeliveryCredentials.ApplicationLookup] that counts calls, so a
    /// test can assert the application fallback is NOT consulted (T1, T3).
    private static final class CountingApplicationLookup implements DeliveryCredentials.ApplicationLookup {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<Application> findByCode(String code) {
            calls.incrementAndGet();
            return APPLICATIONS.findByCode(code);
        }
    }

    // ── T1: the connection's account signs; the application is never consulted ──

    /// Mutant: the old application-first rule (prefer step 3 over the
    /// connection's account).
    @Test
    void t1_theConnectionsServiceAccountSignsAndTheApplicationIsNeverConsulted() {
        String appId = persistApplication("dc-t1-app-" + RUN);
        String appToken = "t1-app-token-" + RUN;
        serviceAccount(appId, appToken, "t1-app-secret-" + RUN, true, Instant.now());
        String connSecret = "t1-conn-secret-" + RUN;
        String connSaId = serviceAccountWithCode(appId, "dc-t1-conn-sa-" + RUN, "t1-conn-token-" + RUN, connSecret, true);
        String connId = persistConnection("dc-t1-conn-" + RUN, connSaId);
        String subId = persistSubscription("dc-t1-sub-" + RUN, null, connId);
        // The job's own code names the SAME application whose SA has a DIFFERENT
        // secret — proves resolution never fell through to it.
        persistSubscriptionApplicationCode(subId, "dc-t1-app-" + RUN);

        var appLookup = new CountingApplicationLookup();
        DeliveryCredentials creds = DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, CONNECTIONS::findById,
                appLookup, byId(), byApplication());

        DeliveryCredentials.Resolved resolved = creds.resolve(job(subId, "dc-t1-app-" + RUN + ":x:y"));

        assertThat(resolved.signingSecret()).as("the connection's account signs, not the application's").isEqualTo(connSecret);
        assertThat(resolved.reason()).isEmpty();
        assertThat(appLookup.calls.get()).as("the application lookup must not even be consulted").isZero();
    }

    // ── T2: the subscription's own account overrides its connection's ──────

    /// Mutant: drop step 1 (always use the connection's account).
    @Test
    void t2_theSubscriptionsOwnAccountOverridesItsConnection() {
        String appId = persistApplication("dc-t2-app-" + RUN);
        String connSaId = serviceAccountWithCode(appId, "dc-t2-conn-sa-" + RUN, "conn-token", "conn-secret", true);
        String connId = persistConnection("dc-t2-conn-" + RUN, connSaId);
        String subSecret = "t2-sub-secret-" + RUN;
        String subSaId = serviceAccountWithCode(appId, "dc-t2-sub-sa-" + RUN, "t2-sub-token-" + RUN, subSecret, true);
        String subId = persistSubscription("dc-t2-sub-" + RUN, subSaId, connId);

        DeliveryCredentials.Resolved resolved = resolver().resolve(job(subId, "irrelevant:code"));

        assertThat(resolved.signingSecret()).isEqualTo(subSecret);
        assertThat(resolved.signedBy()).isEqualTo("dc-t2-sub-sa-" + RUN);
    }

    // ── T3: a named account that cannot sign is declined, never replaced ───

    /// Mutant (×3): fall through to the application instead of declining —
    /// each of inactive / no-credentials / missing.
    @Test
    void t3_aNamedConnectionAccountThatIsInactiveIsDeclinedNotReplaced() {
        String appId = persistApplication("dc-t3a-app-" + RUN);
        String appSecret = "t3a-app-secret-" + RUN;
        serviceAccount(appId, "t3a-app-token-" + RUN, appSecret, true, Instant.now());
        String connSaId = serviceAccountWithCode(appId, "dc-t3a-conn-sa-" + RUN, "t3a-conn-token", "t3a-conn-secret", false);
        String connCode = "dc-t3a-conn-" + RUN;
        String connId = persistConnection(connCode, connSaId);
        String subId = persistSubscription("dc-t3a-sub-" + RUN, null, connId);
        persistSubscriptionApplicationCode(subId, "dc-t3a-app-" + RUN);

        var appLookup = new CountingApplicationLookup();
        DeliveryCredentials creds = DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, CONNECTIONS::findById,
                appLookup, byId(), byApplication());
        DeliveryCredentials.Resolved resolved = creds.resolve(job(subId, "dc-t3a-app-" + RUN + ":x:y"));

        assertThat(resolved.isBare()).as("declined, never silently signed by the application").isTrue();
        assertThat(resolved.reason()).contains("connection " + connCode).contains("is inactive");
        assertThat(appLookup.calls.get()).isZero();
    }

    /// The same rule one step up: a SUBSCRIPTION that names an inactive account
    /// is declined with `subscription <code>` — never quietly signed by the
    /// connection's account, which is active and would otherwise "work".
    @Test
    void t3_aNamedSubscriptionAccountThatIsInactiveIsNotReplacedByTheConnections() {
        String appId = persistApplication("dc-t3s-app-" + RUN);
        String connSaId = serviceAccountWithCode(appId, "dc-t3s-conn-sa-" + RUN, "t3s-conn-token", "t3s-conn-secret", true);
        String subSaId = serviceAccountWithCode(appId, "dc-t3s-sub-sa-" + RUN, "t3s-sub-token", "t3s-sub-secret", false);
        String connId = persistConnection("dc-t3s-conn-" + RUN, connSaId);
        String subCode = "dc-t3s-sub-" + RUN;
        String subId = persistSubscription(subCode, subSaId, connId);
        persistSubscriptionApplicationCode(subId, "dc-t3s-app-" + RUN);

        var appLookup = new CountingApplicationLookup();
        DeliveryCredentials creds = DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, CONNECTIONS::findById,
                appLookup, byId(), byApplication());
        DeliveryCredentials.Resolved resolved = creds.resolve(job(subId, "dc-t3s-app-" + RUN + ":x:y"));

        assertThat(resolved.isBare()).as("mutant: fall through to the connection's active account").isTrue();
        assertThat(resolved.reason()).contains("subscription " + subCode).contains("is inactive");
        assertThat(appLookup.calls.get()).isZero();
    }

    @Test
    void t3_aNamedConnectionAccountWithNoCredentialsIsDeclinedNotReplaced() {
        String appId = persistApplication("dc-t3b-app-" + RUN);
        serviceAccount(appId, "t3b-app-token-" + RUN, "t3b-app-secret-" + RUN, true, Instant.now());
        String connSaId = serviceAccountWithCode(appId, "dc-t3b-conn-sa-" + RUN, null, null, true);
        String connCode = "dc-t3b-conn-" + RUN;
        String connId = persistConnection(connCode, connSaId);
        String subId = persistSubscription("dc-t3b-sub-" + RUN, null, connId);
        persistSubscriptionApplicationCode(subId, "dc-t3b-app-" + RUN);

        var appLookup = new CountingApplicationLookup();
        DeliveryCredentials creds = DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, CONNECTIONS::findById,
                appLookup, byId(), byApplication());
        DeliveryCredentials.Resolved resolved = creds.resolve(job(subId, "dc-t3b-app-" + RUN + ":x:y"));

        assertThat(resolved.isBare()).isTrue();
        assertThat(resolved.reason()).contains("connection " + connCode).contains("has no webhook credentials");
        assertThat(appLookup.calls.get()).isZero();
    }

    @Test
    void t3_aNamedConnectionAccountThatDoesNotExistIsDeclinedNotReplaced() {
        String appId = persistApplication("dc-t3c-app-" + RUN);
        serviceAccount(appId, "t3c-app-token-" + RUN, "t3c-app-secret-" + RUN, true, Instant.now());
        String connCode = "dc-t3c-conn-" + RUN;
        String connId = persistConnection(connCode, "sac_0000000000000");
        String subId = persistSubscription("dc-t3c-sub-" + RUN, null, connId);
        persistSubscriptionApplicationCode(subId, "dc-t3c-app-" + RUN);

        var appLookup = new CountingApplicationLookup();
        DeliveryCredentials creds = DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, CONNECTIONS::findById,
                appLookup, byId(), byApplication());
        DeliveryCredentials.Resolved resolved = creds.resolve(job(subId, "dc-t3c-app-" + RUN + ":x:y"));

        assertThat(resolved.isBare()).isTrue();
        assertThat(resolved.reason()).contains("connection " + connCode).contains("sac_0000000000000").contains("does not exist");
        assertThat(appLookup.calls.get()).isZero();
    }

    // ── T4: no connection account, or no connection ⇒ application fallback ──

    @Test
    void t4_noConnectionAccountFallsBackToTheApplication() {
        String appId = persistApplication("dc-t4a-app-" + RUN);
        String appSecret = "t4a-app-secret-" + RUN;
        serviceAccount(appId, "t4a-app-token-" + RUN, appSecret, true, Instant.now());
        String connId = persistConnection("dc-t4a-conn-" + RUN, null); // names no account
        String subId = persistSubscription("dc-t4a-sub-" + RUN, null, connId);
        persistSubscriptionApplicationCode(subId, "dc-t4a-app-" + RUN);

        DeliveryCredentials.Resolved resolved = resolver().resolve(job(subId, "irrelevant:code"));

        assertThat(resolved.signingSecret()).isEqualTo(appSecret);
    }

    @Test
    void t4_noConnectionAtAllFallsBackToTheApplication() {
        String appId = persistApplication("dc-t4b-app-" + RUN);
        String appSecret = "t4b-app-secret-" + RUN;
        serviceAccount(appId, "t4b-app-token-" + RUN, appSecret, true, Instant.now());
        String subId = persistSubscription("dc-t4b-sub-" + RUN, "dc-t4b-app-" + RUN);

        DeliveryCredentials.Resolved resolved = resolver().resolve(job(subId, "irrelevant:code"));

        assertThat(resolved.signingSecret()).isEqualTo(appSecret);
    }

    @Test
    void t4_directJobWithQualifiedCodeResolvesTheApplication() {
        String appId = persistApplication("value-" + RUN);
        String secret = "t4-direct-secret-" + RUN;
        serviceAccount(appId, "t4-direct-token-" + RUN, secret, true, Instant.now());

        DeliveryCredentials.Resolved resolved = resolver().resolve(job(null, "value-" + RUN + ":invoice:created"));

        assertThat(resolved.signingSecret()).isEqualTo(secret);
    }

    @Test
    void t4_directJobWithNoColonNamesNothing() {
        DeliveryCredentials.Resolved resolved = resolver().resolve(job(null, "legacy-" + RUN));

        assertThat(resolved.isBare()).isTrue();
        assertThat(resolved.reason()).isEqualTo("no subscription, connection or application names a service account");
    }

    @Test
    void t4_directJobNamingAnUnknownApplicationIsDeclined() {
        DeliveryCredentials.Resolved resolved = resolver().resolve(job(null, "nosuchapp-" + RUN + ":x"));

        assertThat(resolved.isBare()).isTrue();
        assertThat(resolved.reason()).isEqualTo("application nosuchapp-" + RUN + " does not exist");
    }

    // ── T5: a lookup error propagates — the caller degrades it, not the resolver ──

    /// Mutant: swallow the connection lookup's error inside the resolver
    /// (turn it into a bare `Resolved` instead of letting it propagate).
    @Test
    void t5_aConnectionLookupErrorPropagatesUncaught() {
        String subId = persistSubscription("dc-t5-sub-" + RUN, null, "cnn_doesnotmatter");
        DeliveryCredentials.ConnectionLookup throwing = id -> {
            throw new RuntimeException("simulated connection lookup failure");
        };
        DeliveryCredentials creds = DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, throwing,
                APPLICATIONS::findByCode, byId(), byApplication());

        assertThatThrownBy(() -> creds.resolve(job(subId, "irrelevant:code")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated connection lookup failure");
    }

    /// Same guarantee for the by-id credential lookup itself (a named
    /// account, step 1) — `named()` must not catch it either.
    @Test
    void t5_aByServiceAccountIdLookupErrorPropagatesUncaught() {
        String subId = persistSubscription("dc-t5b-sub-" + RUN, "sac_whatever", null);
        Function<String, OutboundCredentials.ById> throwing = id -> {
            throw new RuntimeException("simulated by-id lookup failure");
        };
        DeliveryCredentials creds = DeliveryCredentials.resolve(SUBSCRIPTIONS::findById, CONNECTIONS::findById,
                APPLICATIONS::findByCode, throwing, byApplication());

        assertThatThrownBy(() -> creds.resolve(job(subId, "irrelevant:code")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated by-id lookup failure");
    }

    // ── S2: subscription applicationCode wins over the job code's segment ──
    // (Step 3, unchanged by the 2026-09-22 resolution-order rewrite — reached
    // only when neither the subscription nor its connection names an account.)

    /// Mutant: prefer the job code's own leading segment over the
    /// subscription's `applicationCode` when both exist and differ.
    @Test
    void s2_subscriptionApplicationCodeWinsOverTheJobCodesFirstSegmentWhenBothExistAndDiffer() {
        String winningApp = persistApplication("dc-s2-winner-" + RUN);
        String losingApp = persistApplication("dc-s2-loser-" + RUN);
        String token = "s2-token-" + RUN;
        String secret = "s2-secret-" + RUN;
        serviceAccount(winningApp, token, secret, true, Instant.now());
        serviceAccount(losingApp, "wrong-token", "wrong-secret", true, Instant.now());
        String subscriptionId = persistSubscription("dc-s2-sub-" + RUN, "dc-s2-winner-" + RUN);

        // The job's OWN code names the losing application's code as its leading
        // segment — the subscription's applicationCode must win regardless.
        DispatchJob j = job(subscriptionId, "dc-s2-loser-" + RUN + ":orders:created");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.bearerToken()).as("subscription's applicationCode wins").isEqualTo(token);
        assertThat(resolved.signingSecret()).isEqualTo(secret);
    }

    // ── S3: direct job, code-derived application ────────────────────────

    /// Mutant: take the whole code as the application code (rather than only
    /// the leading `:`-delimited segment).
    @Test
    void s3_directJobWithColonInCodeResolvesTheLeadingSegmentAsTheApplication() {
        String appId = persistApplication("billing-" + RUN);
        String token = "s3-token-" + RUN;
        String secret = "s3-secret-" + RUN;
        serviceAccount(appId, token, secret, true, Instant.now());

        DispatchJob j = job(null, "billing-" + RUN + ":invoice:created");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.bearerToken()).isEqualTo(token);
        assertThat(resolved.signingSecret()).isEqualTo(secret);
    }

    /// Spec §2 case 2, first half: a subscription that carries no application
    /// code falls back to the job code's leading segment — it is not "bare".
    /// Mutant: treat any non-null subscription application code as decisive
    /// (drop the blank check), or stop at the subscription.
    @Test
    void s3_subscriptionWithoutAnApplicationCodeFallsBackToTheJobCodesLeadingSegment() {
        String appId = persistApplication("fallback-" + RUN);
        String secret = "s3-fallback-secret-" + RUN;
        serviceAccount(appId, "s3-fallback-token-" + RUN, secret, true, Instant.now());

        DeliveryCredentials blankCode = DeliveryCredentials.resolve(
                id -> Optional.of(Subscription.create("s", "s", "https://hook.example/s").withApplicationCode("  ")),
                CONNECTIONS::findById, APPLICATIONS::findByCode, byId(), byApplication());
        String nullCodeSubscription = persistSubscription("s3-nullcode-" + RUN, null);

        assertThat(blankCode.resolve(job("sub_any", "fallback-" + RUN + ":thing:happened")).signingSecret())
                .as("blank subscription application code").isEqualTo(secret);
        assertThat(resolver().resolve(job(nullCodeSubscription, "fallback-" + RUN + ":thing:happened")).signingSecret())
                .as("null subscription application code").isEqualTo(secret);
    }

    /// Spec §2: "a subscription id that names no row is case 2, not an error"
    /// — and not bare either. Mutant: return no credentials when the row is
    /// missing.
    @Test
    void s3_subscriptionIdNamingNoRowFallsBackToTheJobCodesLeadingSegment() {
        String appId = persistApplication("norow-" + RUN);
        String secret = "s3-norow-secret-" + RUN;
        serviceAccount(appId, "s3-norow-token-" + RUN, secret, true, Instant.now());

        DeliveryCredentials.Resolved resolved = resolver().resolve(job("sub_0000000000000", "norow-" + RUN + ":thing:happened"));

        assertThat(resolved.signingSecret()).isEqualTo(secret);
    }

    /// Mutant: for a code with no colon at all, take the whole string as the
    /// application code anyway (instead of treating it as "no application
    /// code" ⇒ bare).
    @Test
    void s3_directJobWithNoColonInCodeIsBare() {
        // "legacy" (no colon) — an application named exactly "legacy" exists,
        // so a mutant that used the whole code would find real credentials
        // instead of correctly falling through to NONE.
        String appId = persistApplication("legacy" + RUN);
        serviceAccount(appId, "should-never-be-returned", "should-never-be-returned", true, Instant.now());

        DispatchJob j = job(null, "legacy" + RUN);

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.isBare()).as("no colon in the code — bare, not a lookup on the whole code").isTrue();
    }

    /// Mutant: accept an empty leading segment (`:x`) as a valid, empty
    /// application code instead of treating it as malformed ⇒ bare.
    ///
    /// Uses a fake [DeliveryCredentials.ApplicationLookup] that WOULD resolve
    /// real credentials for the empty string — a real `ApplicationRepository`
    /// can never hold a row with an empty code (`ApplicationCode.parse`
    /// rejects blank), so a resolver-only assertion against real repositories
    /// would pass even under the mutant (empty code ⇒ "not found" ⇒ bare
    /// either way) without actually exercising the empty-segment guard.
    @Test
    void s3_directJobWithEmptyLeadingSegmentIsBare() {
        DeliveryCredentials creds = DeliveryCredentials.resolve(id -> Optional.empty(), CONNECTIONS::findById,
                code -> code.isEmpty()
                        ? Optional.of(new Application("app_s3empty", ApplicationType.APPLICATION, "whatever", "n",
                                null, null, null, null, null, null, null, true, Instant.now(), Instant.now()))
                        : Optional.empty(),
                byId(),
                applicationId -> Optional.of(new OutboundCredentials("should-never-be-returned", "should-never-be-returned")));

        DispatchJob j = job(null, ":x");

        DeliveryCredentials.Resolved resolved = creds.resolve(j);

        assertThat(resolved.isBare()).as("empty leading segment — bare, never looked up as an empty-string application code").isTrue();
    }

    // ── S4: unknown code, no active account, oldest wins, status matters ──

    /// Mutant: none — this is the "no application by that code" branch.
    @Test
    void s4_unknownApplicationCodeIsBare() {
        DispatchJob j = job(null, "dc-s4-unknown-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.isBare()).isTrue();
    }

    @Test
    void s4_applicationWithNoActiveServiceAccountIsBare() {
        String appId = persistApplication("dc-s4-noactive-" + RUN);
        serviceAccount(appId, "inactive-token", "inactive-secret", false, Instant.now());

        DispatchJob j = job(null, "dc-s4-noactive-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.isBare()).as("only account is INACTIVE — bare").isTrue();
    }

    /// Mutant: pick the newest active account instead of the oldest.
    @Test
    void s4_theOldestActiveServiceAccountIsChosen() {
        String appId = persistApplication("dc-s4-oldest-" + RUN);
        Instant now = Instant.now();
        String olderToken = "s4-older-token-" + RUN;
        String olderSecret = "s4-older-secret-" + RUN;
        serviceAccount(appId, olderToken, olderSecret, true, now.minusSeconds(3600));
        serviceAccount(appId, "s4-newer-token-" + RUN, "s4-newer-secret-" + RUN, true, now.minusSeconds(60));

        DispatchJob j = job(null, "dc-s4-oldest-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.bearerToken()).as("the OLDER of the two active accounts wins").isEqualTo(olderToken);
        assertThat(resolved.signingSecret()).isEqualTo(olderSecret);
    }

    /// Mutant: ignore `active`, ordering by `created_at` alone.
    @Test
    void s4_aDeactivatedAccountOlderThanBothActiveOnesIsIgnored() {
        String appId = persistApplication("dc-s4-ignoreinactive-" + RUN);
        Instant now = Instant.now();
        // Oldest of all three, but INACTIVE — must never be chosen.
        serviceAccount(appId, "s4-deactivated-token-" + RUN, "s4-deactivated-secret-" + RUN, false, now.minusSeconds(7200));
        String wantedToken = "s4-active-older-token-" + RUN;
        String wantedSecret = "s4-active-older-secret-" + RUN;
        serviceAccount(appId, wantedToken, wantedSecret, true, now.minusSeconds(3600));
        serviceAccount(appId, "s4-active-newer-token-" + RUN, "s4-active-newer-secret-" + RUN, true, now.minusSeconds(60));

        DispatchJob j = job(null, "dc-s4-ignoreinactive-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.bearerToken()).as("the deactivated (oldest) account is skipped").isEqualTo(wantedToken);
        assertThat(resolved.signingSecret()).isEqualTo(wantedSecret);
    }

    // ── S5 (resolver half — the wire/header half is in ProcessingApiTest): ──
    // a missing credential resolves to `null`, never `""` (SubscriberDelivery's
    // header guard is `!= null && !isEmpty()`, so a wrongly-empty string here
    // would still (accidentally) omit the header — this pins the VALUE, not
    // just the header's absence).

    /// Mutant: send an empty string for the missing half instead of leaving
    /// it genuinely absent (`null`).
    @Test
    void s5_tokenOnlyAccountResolvesASecretThatIsAbsentNotEmpty() {
        String appId = persistApplication("dc-s5-tokenonly-" + RUN);
        String token = "s5-token-only-" + RUN;
        serviceAccount(appId, token, null, true, Instant.now());

        DispatchJob j = job(null, "dc-s5-tokenonly-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.bearerToken()).isEqualTo(token);
        assertThat(resolved.signingSecret()).as("the secret half is genuinely absent").isNull();
    }

    @Test
    void s5_secretOnlyAccountResolvesATokenThatIsAbsentNotEmpty() {
        String appId = persistApplication("dc-s5-secretonly-" + RUN);
        String secret = "s5-secret-only-" + RUN;
        serviceAccount(appId, null, secret, true, Instant.now());

        DispatchJob j = job(null, "dc-s5-secretonly-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved.bearerToken()).as("the token half is genuinely absent").isNull();
        assertThat(resolved.signingSecret()).isEqualTo(secret);
    }

    // ── S6: the shared one-minute cache (step 3, unchanged) ─────────────

    /// Mutant: no cache at all (every resolve hits the repository again) —
    /// the fake clock never advances, so a correct cache must show exactly
    /// one underlying call across two resolves for the same application.
    @Test
    void s6_twoDeliveriesForOneApplicationInsideAMinuteResolveOnce() {
        AtomicInteger calls = new AtomicInteger();
        var fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        Function<String, Optional<OutboundCredentials>> counting = applicationId -> {
            calls.incrementAndGet();
            return Optional.of(new OutboundCredentials("cached-token", "cached-secret"));
        };
        Function<String, Optional<OutboundCredentials>> cached = OutboundCredentials.cached(counting, fixed);
        DeliveryCredentials creds = DeliveryCredentials.resolve(id -> Optional.empty(), CONNECTIONS::findById,
                code -> Optional.of(new Application("app_s6", ApplicationType.APPLICATION, "dc-s6-" + RUN, "n", null,
                        null, null, null, null, null, null, true, Instant.now(), Instant.now())),
                byId(), cached);

        DispatchJob j1 = job(null, "dc-s6-" + RUN + ":x:y");
        DispatchJob j2 = job(null, "dc-s6-" + RUN + ":x:z");

        assertThat(creds.resolve(j1).bearerToken()).isEqualTo("cached-token");
        assertThat(creds.resolve(j2).bearerToken()).isEqualTo("cached-token");

        assertThat(calls.get()).as("one underlying resolve for two deliveries inside the TTL").isEqualTo(1);
    }

    /// Mutant: cache forever (no TTL expiry) — advancing the clock past one
    /// minute must trigger a second underlying resolve.
    @Test
    void s6_afterTheTtlTheApplicationResolvesAgain() {
        AtomicInteger calls = new AtomicInteger();
        var mutableNow = new Object() {
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
        };
        Clock driven = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return mutableNow.at;
            }
        };
        Function<String, Optional<OutboundCredentials>> counting = applicationId -> {
            calls.incrementAndGet();
            return Optional.of(new OutboundCredentials("driven-token", "driven-secret"));
        };
        Function<String, Optional<OutboundCredentials>> cached = OutboundCredentials.cached(counting, driven);
        DeliveryCredentials creds = DeliveryCredentials.resolve(id -> Optional.empty(), CONNECTIONS::findById,
                code -> Optional.of(new Application("app_s6b", ApplicationType.APPLICATION, "dc-s6b-" + RUN, "n", null,
                        null, null, null, null, null, null, true, Instant.now(), Instant.now())),
                byId(), cached);

        DispatchJob j = job(null, "dc-s6b-" + RUN + ":x:y");
        creds.resolve(j);
        assertThat(calls.get()).isEqualTo(1);

        // Still inside the minute: no second call.
        mutableNow.at = mutableNow.at.plusSeconds(30);
        creds.resolve(j);
        assertThat(calls.get()).as("still within the one-minute TTL").isEqualTo(1);

        // Past the minute: resolves again.
        mutableNow.at = mutableNow.at.plusSeconds(31);
        creds.resolve(j);
        assertThat(calls.get()).as("TTL expired — resolved again").isEqualTo(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void persistSubscriptionApplicationCode(String subscriptionId, String applicationCode) {
        DB.update(MSG_SUBSCRIPTIONS).set(MSG_SUBSCRIPTIONS.APPLICATION_CODE, applicationCode)
                .where(MSG_SUBSCRIPTIONS.ID.eq(subscriptionId)).execute();
    }
}
