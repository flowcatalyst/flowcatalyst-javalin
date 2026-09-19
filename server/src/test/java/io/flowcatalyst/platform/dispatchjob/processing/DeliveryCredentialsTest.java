package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
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
import java.sql.Connection;
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
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;

/// [DeliveryCredentials#forApplications] against real, TestPg-backed
/// `ApplicationRepository` / `SubscriptionRepository` / `ServiceAccountRepository`
/// rows (`docs/spec/dispatch-delivery-credentials.md` §2, load-bearing
/// behaviours S2–S6). S1 (end to end through a real HTTP delivery) and S7
/// (a throwing resolver) live in [ProcessingApiTest], which already owns the
/// loopback-subscriber fixture; S8 (the `Platform` composition root) lives in
/// `io.flowcatalyst.server.DispatchDeliveryCredentialsWiringTest`.
class DeliveryCredentialsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ApplicationRepository APPLICATIONS = new ApplicationRepository(DS);
    private static final SubscriptionRepository SUBSCRIPTIONS = new SubscriptionRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final List<String> APPLICATION_IDS = new ArrayList<>();
    private static final List<String> SUBSCRIPTION_IDS = new ArrayList<>();
    private static final List<String> SERVICE_ACCOUNT_IDS = new ArrayList<>();
    private static int seq = 0;

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(IAM_SERVICE_ACCOUNTS).where(IAM_SERVICE_ACCOUNTS.ID.in(SERVICE_ACCOUNT_IDS)).execute();
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

    private static <T extends HasId> void persist(T entity, Persist<T> repo) {
        try (Connection conn = DS.getConnection()) {
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
                null, null, now, now, null, null, null, null, null);
    }

    /// No cache wrapper here — S6 pins the cache in isolation, and every
    /// other scenario resolves at most once per test, so the un-cached
    /// resolver (still `OutboundCredentials.resolve`, the same call the
    /// cache wraps) is what keeps S2–S5 independent of cache TTL timing.
    private static DeliveryCredentials resolver() {
        var serviceAccounts = new ServiceAccountRepository(DS, Optional.empty());
        return DeliveryCredentials.forApplications(SUBSCRIPTIONS::findById, APPLICATIONS::findByCode,
                applicationId -> OutboundCredentials.resolve(serviceAccounts, applicationId));
    }

    // ── S2: subscription applicationCode wins over the job code's segment ──

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

        DeliveryCredentials blankCode = DeliveryCredentials.forApplications(
                id -> Optional.of(Subscription.create("s", "s", "https://hook.example/s").withApplicationCode("  ")),
                APPLICATIONS::findByCode,
                applicationId -> OutboundCredentials.resolve(new ServiceAccountRepository(DS, Optional.empty()), applicationId));
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

        assertThat(resolved).as("no colon in the code — bare, not a lookup on the whole code")
                .isEqualTo(DeliveryCredentials.Resolved.NONE);
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
        DeliveryCredentials creds = DeliveryCredentials.forApplications(id -> Optional.empty(),
                code -> code.isEmpty()
                        ? Optional.of(new Application("app_s3empty", ApplicationType.APPLICATION, "whatever", "n",
                                null, null, null, null, null, null, null, true, Instant.now(), Instant.now()))
                        : Optional.empty(),
                applicationId -> Optional.of(new OutboundCredentials("should-never-be-returned", "should-never-be-returned")));

        DispatchJob j = job(null, ":x");

        DeliveryCredentials.Resolved resolved = creds.resolve(j);

        assertThat(resolved).as("empty leading segment — bare, never looked up as an empty-string application code")
                .isEqualTo(DeliveryCredentials.Resolved.NONE);
    }

    // ── S4: unknown code, no active account, oldest wins, status matters ──

    /// Mutant: none — this is the "no application by that code" branch.
    @Test
    void s4_unknownApplicationCodeIsBare() {
        DispatchJob j = job(null, "dc-s4-unknown-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved).isEqualTo(DeliveryCredentials.Resolved.NONE);
    }

    @Test
    void s4_applicationWithNoActiveServiceAccountIsBare() {
        String appId = persistApplication("dc-s4-noactive-" + RUN);
        serviceAccount(appId, "inactive-token", "inactive-secret", false, Instant.now());

        DispatchJob j = job(null, "dc-s4-noactive-" + RUN + ":x:y");

        DeliveryCredentials.Resolved resolved = resolver().resolve(j);

        assertThat(resolved).as("only account is INACTIVE — bare").isEqualTo(DeliveryCredentials.Resolved.NONE);
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

    // ── S6: the shared one-minute cache ─────────────────────────────────

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
        DeliveryCredentials creds = DeliveryCredentials.forApplications(id -> Optional.empty(), code -> Optional.of(
                new Application("app_s6", ApplicationType.APPLICATION, "dc-s6-" + RUN, "n", null, null, null, null,
                        null, null, null, true, Instant.now(), Instant.now())), cached);

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
        DeliveryCredentials creds = DeliveryCredentials.forApplications(id -> Optional.empty(), code -> Optional.of(
                new Application("app_s6b", ApplicationType.APPLICATION, "dc-s6b-" + RUN, "n", null, null, null, null,
                        null, null, null, true, Instant.now(), Instant.now())), cached);

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
}
