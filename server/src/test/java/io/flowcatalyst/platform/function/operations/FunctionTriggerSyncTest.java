package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.PoolUrlTemplate;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.TriggerObject;
import io.flowcatalyst.platform.function.TriggerObjectKind;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.FunctionEvents.AliasChanged;
import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.subscription.EventTypeBinding;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FunctionTriggerSync` (spec `function-invocation.md` §4, §6, §10):
/// publish-time validation (nothing persists on failure) and promote/delete/
/// status-change reconciliation, against real TestPg-backed repositories —
/// the same aggregates the admin API writes through.
@SuppressWarnings("deprecation")
class FunctionTriggerSyncTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);

    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(DS);
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(DS);
    private static final EventTypeRepository eventTypes = new EventTypeRepository(DS);
    private static final TriggerObjectRepository triggerObjects = new TriggerObjectRepository(DS);
    private static final SubscriptionRepository subscriptions = new SubscriptionRepository(DS);
    private static final DispatchPoolRepository pools = new DispatchPoolRepository(DS);
    private static final ScheduledJobRepository jobs = new ScheduledJobRepository(DS);
    private static final ApplicationRepository applications = new ApplicationRepository(DS);
    private static final ServiceAccountRepository serviceAccounts = new ServiceAccountRepository(DS, Optional.empty());
    private static final FunctionSettingsRepository settings = new FunctionSettingsRepository(DS, Optional.empty());
    private static final FunctionDomainRepository domains = new FunctionDomainRepository(DS);
    private static final FunctionRouteRepository routes = new FunctionRouteRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final Signatures OFF = new Signatures.Off();
    private static final PoolUrlTemplate POOL_URL = PoolUrlTemplate.parse("http://fn-{pool}:8080");
    private static final FunctionTriggerSync SYNC = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes,
            triggerObjects, applications, serviceAccounts, versions, DEFAULTS, POOL_URL, domains, routes, functions);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "usr_fts_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final AuthContext ANCHOR =
            new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io", List.of("*"), List.of(), List.of(), true, List.of());
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    /// A client-scoped principal (spec `function-public-routes.md` §6 M5's
    /// own fixture need — `AccessTest`'s `clientScoped` helper, copied here
    /// so this class does not need a cross-package dependency for one helper).
    private static AuthContext clientScoped(String clientId, boolean allApplications, List<String> applications) {
        return new AuthContext("usr_fts_scoped_" + fresh(), Scope.CLIENT, null, List.of(clientId), List.of(),
                applications, allApplications, List.of());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static String persistApplication(String tag) {
        Application app = Application.create(ApplicationType.APPLICATION, "fts" + tag + RUN, "fts app " + tag);
        uow.inTransaction(tx -> {
            applications.persist(app, tx.dbTx());
            return null;
        });
        return app.id();
    }

    private static void persistEventType(String code) {
        EventType et = EventType.create(code, code);
        uow.inTransaction(tx -> {
            eventTypes.persist(et, tx.dbTx());
            return null;
        });
    }

    private static void persistArchivedEventType(String code) {
        EventType et = EventType.create(code, code).archive();
        uow.inTransaction(tx -> {
            eventTypes.persist(et, tx.dbTx());
            return null;
        });
    }

    /// A raw `iam_service_accounts` row, bypassing the aggregate's own
    /// encryption (mirrors `DeliveryCredentialsTest#serviceAccount` — the
    /// SAME resolver chain, `OutboundCredentials`, reads this column back
    /// through a repository built with no encryption configured, exactly as
    /// this test's `serviceAccounts` field is).
    private static void persistServiceAccount(String applicationId, String signingSecret, boolean active) {
        DB.insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, io.flowcatalyst.platform.shared.tsid.EntityType.SERVICE_ACCOUNT.generate())
                .set(IAM_SERVICE_ACCOUNTS.CODE, "fts-svc-" + fresh())
                .set(IAM_SERVICE_ACCOUNTS.NAME, "fts test service account")
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, active)
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, signingSecret == null ? "NONE" : "BEARER_TOKEN")
                .set(IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, signingSecret)
                .set(IAM_SERVICE_ACCOUNTS.CREATED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .execute();
    }

    private static Function createFunction(String applicationId, FunctionOwner owner) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("fts" + RUN), new DnsLabel("svc"), new DnsLabel(fresh()));
        Function f = Function.create(applicationId, address, owner, Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static PublishVersion.Result publish(FunctionAddress address, String digestSuffix, JsonNode manifest) {
        return publishAs(ANCHOR, address, digestSuffix, manifest);
    }

    private static PublishVersion.Result publishAs(AuthContext ac, FunctionAddress address, String digestSuffix,
            JsonNode manifest) {
        var cmd = new PublishCommand(address, "oci://artifact/" + digestSuffix, sha256(digestSuffix), null, manifest);
        return Auth.runAs(ac, () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, SYNC, java.util.Optional.empty()).run(uow, cmd, EC));
    }

    private static String sha256(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((RUN + ":" + s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AliasChanged promote(FunctionAddress address, int version) {
        return promoteAs(ANCHOR, address, version);
    }

    private static AliasChanged promoteAs(AuthContext ac, FunctionAddress address, int version) {
        markReady(address, version);
        return Auth.runAs(ac, () -> PromoteVersion.of(functions, versions, SYNC, settings)
                .run(uow, new PromoteCommand(address, Function.LIVE, version), EC));
    }

    /// A1 (spec `function-zones-and-aliases.md` §2, §8): promotes a NAMED
    /// (non-`live`) alias — the one path [SYNC] must never reconcile wiring
    /// for.
    private static AliasChanged promoteNamedAlias(FunctionAddress address, String alias, int version) {
        markReady(address, version);
        return Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, SYNC, settings)
                .run(uow, new PromoteCommand(address, alias, version), EC));
    }

    /// `PromoteVersion` requires `READY` (R3) — every test here promotes
    /// straight after publishing, so the promote helper marks the target
    /// version ready first rather than repeating this at every call site.
    private static void markReady(FunctionAddress address, int version) {
        Function f = functions.findByAddress(address).orElseThrow();
        FunctionVersion v = versions.findByFunctionAndVersion(f.id(), version).orElseThrow();
        if (v.state() instanceof FunctionVersion.VersionState.Published) {
            uow.inTransaction(tx -> {
                versions.persist(v.markReady(java.time.Instant.now()), tx.dbTx());
                return null;
            });
        }
    }

    private static FunctionEvents.FunctionUpdated updateStatus(FunctionAddress address, String status) {
        return Auth.runAs(ANCHOR, () -> UpdateFunction.of(functions, SYNC)
                .run(uow, new UpdateCommand(address, null, status), EC));
    }

    private static FunctionEvents.FunctionDeleted deleteFunction(FunctionAddress address) {
        return Auth.runAs(ANCHOR, () -> DeleteFunction.of(functions, SYNC).run(uow, new DeleteCommand(address), EC));
    }

    // ── Manifest builders ────────────────────────────────────────────────

    private static JsonNode manifest(String pool, boolean warm, Integer maxConcurrency, List<String> subs, List<String> scheds) {
        StringBuilder limits = new StringBuilder("{");
        if (maxConcurrency != null) limits.append("\"maxConcurrency\":").append(maxConcurrency);
        limits.append("}");
        String json = "{"
                + "\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\","
                + "\"pool\":\"" + pool + "\",\"warm\":" + warm + ","
                + "\"limits\":" + limits + ","
                + "\"endpoints\":[{\"path\":\"/events/*\",\"auth\":\"webhook\"},{\"path\":\"/jobs/*\",\"auth\":\"webhook\"}],"
                + "\"subscriptions\":[" + String.join(",", subs) + "],"
                + "\"schedules\":[" + String.join(",", scheds) + "]"
                + "}";
        return Json.MAPPER.readTree(json);
    }

    private static String sub(String eventType, String path) {
        return "{\"eventType\":\"" + eventType + "\",\"path\":\"" + path + "\"}";
    }

    /// A subscription entry with every dispatch-setting field spelled out —
    /// for the sameSubscription per-clause delta tests, where each test
    /// holds every field but one equal between v1 and v2.
    private static String subFull(String eventType, String path, String mode, int maxRetries, int timeoutSeconds,
            boolean dataOnly) {
        return "{\"eventType\":\"" + eventType + "\",\"path\":\"" + path + "\",\"mode\":\"" + mode
                + "\",\"maxRetries\":" + maxRetries + ",\"timeoutSeconds\":" + timeoutSeconds + ",\"dataOnly\":"
                + dataOnly + "}";
    }

    private static String sched(String cron, String path) {
        return "{\"cron\":\"" + cron + "\",\"path\":\"" + path + "\"}";
    }

    /// A minimal manifest declaring exactly one `public[]` entry (spec
    /// `function-public-routes.md` §1, §2) — no subscriptions/schedules, so
    /// none of `requireApplicationSigningSecret`'s ceremony is needed.
    private static JsonNode manifestWithPublic(String pool, String hostname, String pathPrefix) {
        String json = "{"
                + "\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\","
                + "\"pool\":\"" + pool + "\",\"warm\":false,"
                + "\"limits\":{},"
                + "\"endpoints\":[{\"path\":\"/\",\"auth\":\"none\"}],"
                + "\"public\":[{\"hostname\":\"" + hostname + "\",\"pathPrefix\":\"" + pathPrefix + "\"}]"
                + "}";
        return Json.MAPPER.readTree(json);
    }

    /// [#manifestWithPublic] plus opt-in `aliasPrefixes` (spec
    /// `function-zones-and-aliases.md` §3) on the one `public[]` entry.
    private static JsonNode manifestWithPublicAndAliasPrefixes(String pool, String hostname, String pathPrefix,
            List<String> aliasPrefixes) {
        String prefixesJson = aliasPrefixes.stream().map(p -> "\"" + p + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String json = "{"
                + "\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\","
                + "\"pool\":\"" + pool + "\",\"warm\":false,"
                + "\"limits\":{},"
                + "\"endpoints\":[{\"path\":\"/\",\"auth\":\"none\"}],"
                + "\"public\":[{\"hostname\":\"" + hostname + "\",\"pathPrefix\":\"" + pathPrefix
                + "\",\"aliasPrefixes\":" + prefixesJson + "}]"
                + "}";
        return Json.MAPPER.readTree(json);
    }

    // ── Domain fixtures (spec `function-public-routes.md` §1) ───────────────

    private static FunctionDomain persistVerifiedDomain(FunctionOwner owner, String hostname) {
        FunctionDomain d = FunctionDomain.claim(owner, Hostname.parse(hostname), "tok-" + fresh(), Instant.now())
                .verified(Instant.now());
        uow.inTransaction(tx -> {
            domains.persist(d, tx.dbTx());
            return null;
        });
        return d;
    }

    private static FunctionDomain persistPendingDomain(FunctionOwner owner, String hostname) {
        FunctionDomain d = FunctionDomain.claim(owner, Hostname.parse(hostname), "tok-" + fresh(), Instant.now());
        uow.inTransaction(tx -> {
            domains.persist(d, tx.dbTx());
            return null;
        });
        return d;
    }

    private static void assertUseCaseError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static String fid(Function f) {
        return f.id().substring(4).toLowerCase(Locale.ROOT);
    }

    // ── Queries ──────────────────────────────────────────────────────────

    private static List<TriggerObject> linked(Function f) {
        return triggerObjects.listByFunction(f.id());
    }

    private static Result<Record> eventsFor(String subject, String type) {
        return DB.fetch("SELECT type, message_group FROM msg_events WHERE subject = ? AND type = ?", subject, type);
    }

    // ═══════════════════════════════════════════════════════════════════
    // T — publish validations, each in isolation, each persisting NOTHING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void publishRejectsAnUnknownEventTypeAndPersistsNothing() {
        String appId = persistApplication("t1");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(sub("fts:t1:x:missing", "/events/a")), List.of());

        assertThatThrownBy(() -> publish(f.address(), "t1", m))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("EVENT_TYPE_NOT_FOUND");
        assertThat(versions.listByFunction(f.id())).as("publish must persist nothing on failure").isEmpty();
    }

    @Test
    void publishRejectsAnArchivedEventTypeAndPersistsNothing() {
        String appId = persistApplication("t2");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String et = "fts:t2:x:archived-" + fresh();
        persistArchivedEventType(et);
        JsonNode m = manifest("default", false, null, List.of(sub(et, "/events/a")), List.of());

        assertThatThrownBy(() -> publish(f.address(), "t2", m))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("EVENT_TYPE_NOT_FOUND");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    @Test
    void publishRejectsAnInvalidCronAndPersistsNothing() {
        String appId = persistApplication("t3");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(), List.of(sched("not a cron", "/jobs/a")));

        assertThatThrownBy(() -> publish(f.address(), "t3", m))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("CRON_INVALID");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    @Test
    void publishRejectsAnInvalidTimezoneAndPersistsNothing() {
        String appId = persistApplication("t4");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String json = manifest("default", false, null, List.of(),
                List.of("{\"cron\":\"0 0 * * * *\",\"timezone\":\"Not/AZone\",\"path\":\"/jobs/a\"}")).toString();

        assertThatThrownBy(() -> publish(f.address(), "t4", Json.MAPPER.readTree(json)))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("TIMEZONE_INVALID");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    @Test
    void publishRejectsSubscriptionsWithNoApplicationSigningSecretAndPersistsNothing() {
        String appId = persistApplication("t5"); // no service account at all
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String et = "fts:t5:x:evt-" + fresh();
        persistEventType(et);
        JsonNode m = manifest("default", false, null, List.of(sub(et, "/events/a")), List.of());

        assertThatThrownBy(() -> publish(f.address(), "t5", m))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("APPLICATION_SIGNING_SECRET_REQUIRED");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    /// The other half of the `||`: a manifest with schedules and no
    /// subscriptions needs the signing secret just the same — a scheduled-job
    /// delivery is a webhook too. Mutant: check subscriptions only.
    @Test
    void publishRejectsSchedulesAloneWithNoApplicationSigningSecretAndPersistsNothing() {
        String appId = persistApplication("t5c"); // no service account at all
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(), List.of(sched("0 0 * * * *", "/jobs/a")));

        assertThatThrownBy(() -> publish(f.address(), "t5c", m))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("APPLICATION_SIGNING_SECRET_REQUIRED");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    @Test
    void publishAcceptsAManifestWithNoSubscriptionsOrSchedulesEvenWithNoSigningSecret() {
        String appId = persistApplication("t5b"); // no service account
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(), List.of());

        PublishVersion.Result result = publish(f.address(), "t5b", m);
        assertThat(result.version().version()).isEqualTo(1);
    }

    @Test
    void publishRejectsWarmOverCapacityAndPersistsNothing() {
        String appId = persistApplication("t6");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        DnsLabel pool = new DnsLabel("wcap" + fresh());
        FunctionLimits tight = new FunctionLimits(DEFAULTS.maxDurationMs(), DEFAULTS.maxConcurrency(),
                DEFAULTS.wasmMemoryMb(), DEFAULTS.dbPoolSize(), 1);
        FunctionTriggerSync tightSync = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes, triggerObjects,
                applications, serviceAccounts, versions, tight, POOL_URL, domains, routes, functions);

        // First warm function in this pool: fits exactly at the cap of 1.
        Function first = createFunction(appId, new FunctionOwner.Platform());
        JsonNode warmManifest = manifest(pool.value(), true, null, List.of(), List.of());
        var cmd1 = new PublishCommand(first.address(), "oci://artifact/w1", sha256("w1"), null, warmManifest);
        FunctionVersion v1 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd1, EC)).version();
        markReady(first.address(), v1.version());
        Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, tightSync, settings)
                .run(uow, new PromoteCommand(first.address(), Function.LIVE, v1.version()), EC));

        // A second warm function in the SAME pool now exceeds the cap of 1.
        var cmd2 = new PublishCommand(f.address(), "oci://artifact/w2", sha256("w2"), null, warmManifest);
        assertThatThrownBy(() -> Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd2, EC)))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("WARM_CAPACITY_EXCEEDED");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Warm capacity excludes the function's OWN live warm version (spec §4)
    // ═══════════════════════════════════════════════════════════════════

    /// At the cap, the function whose OWN live version is warm can still
    /// republish a warm version — its own live version is about to be
    /// replaced, so counting it would stop it from ever republishing.
    /// Mutant "count own": the count would include `f1`'s own live version,
    /// so `1 (self, wrongly counted) + 1 (this one) > cap 1` throws.
    @Test
    void warmCapacityAtCapLetsTheFunctionRepublishItsOwnWarmVersion() {
        String appId = persistApplication("wex1");
        DnsLabel pool = new DnsLabel("wex1" + fresh());
        FunctionLimits tight = new FunctionLimits(DEFAULTS.maxDurationMs(), DEFAULTS.maxConcurrency(),
                DEFAULTS.wasmMemoryMb(), DEFAULTS.dbPoolSize(), 1);
        FunctionTriggerSync tightSync = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes, triggerObjects,
                applications, serviceAccounts, versions, tight, POOL_URL, domains, routes, functions);

        Function f1 = createFunction(appId, new FunctionOwner.Platform());
        JsonNode warmManifest = manifest(pool.value(), true, null, List.of(), List.of());
        var cmd1 = new PublishCommand(f1.address(), "oci://artifact/wex1a", sha256("wex1a"), null, warmManifest);
        FunctionVersion v1 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd1, EC)).version();
        markReady(f1.address(), v1.version());
        Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, tightSync, settings)
                .run(uow, new PromoteCommand(f1.address(), Function.LIVE, v1.version()), EC));
        // f1 is now the sole live warm function in `pool`, exactly at the cap of 1.

        var cmd2 = new PublishCommand(f1.address(), "oci://artifact/wex1b", sha256("wex1b"), null, warmManifest);
        PublishVersion.Result p2 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd2, EC));
        assertThat(p2.version().version()).as("f1 republishing its own warm version succeeds at the cap").isEqualTo(2);
    }

    /// The same cap still blocks a DIFFERENT function's warm publish — the
    /// exclusion is scoped to the publishing function's own id, never a
    /// blanket exemption. Mutant "count own" removed entirely (never
    /// exclude): would also throw here, so this alone would not catch a
    /// missing exclusion, but paired with the test above it pins that the
    /// exclusion is per-function, not "no cap at all".
    @Test
    void warmCapacityAtCapStillBlocksADifferentFunction() {
        String appId = persistApplication("wex2");
        DnsLabel pool = new DnsLabel("wex2" + fresh());
        FunctionLimits tight = new FunctionLimits(DEFAULTS.maxDurationMs(), DEFAULTS.maxConcurrency(),
                DEFAULTS.wasmMemoryMb(), DEFAULTS.dbPoolSize(), 1);
        FunctionTriggerSync tightSync = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes, triggerObjects,
                applications, serviceAccounts, versions, tight, POOL_URL, domains, routes, functions);

        Function f1 = createFunction(appId, new FunctionOwner.Platform());
        JsonNode warmManifest = manifest(pool.value(), true, null, List.of(), List.of());
        var cmd1 = new PublishCommand(f1.address(), "oci://artifact/wex2a", sha256("wex2a"), null, warmManifest);
        FunctionVersion v1 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd1, EC)).version();
        markReady(f1.address(), v1.version());
        Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, tightSync, settings)
                .run(uow, new PromoteCommand(f1.address(), Function.LIVE, v1.version()), EC));

        Function f2 = createFunction(appId, new FunctionOwner.Platform());
        var cmd2 = new PublishCommand(f2.address(), "oci://artifact/wex2b", sha256("wex2b"), null, warmManifest);
        assertThatThrownBy(() -> Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd2, EC)))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("WARM_CAPACITY_EXCEEDED");
        assertThat(versions.listByFunction(f2.id())).isEmpty();
    }

    /// A non-warm publish is unaffected by a full warm cap in the same pool.
    /// Mutant: the `if (manifest.warm())` guard dropped, applying the cap to
    /// every publish.
    @Test
    void warmCapacityDoesNotAffectANonWarmPublishAtTheCap() {
        String appId = persistApplication("wex3");
        DnsLabel pool = new DnsLabel("wex3" + fresh());
        FunctionLimits tight = new FunctionLimits(DEFAULTS.maxDurationMs(), DEFAULTS.maxConcurrency(),
                DEFAULTS.wasmMemoryMb(), DEFAULTS.dbPoolSize(), 1);
        FunctionTriggerSync tightSync = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes, triggerObjects,
                applications, serviceAccounts, versions, tight, POOL_URL, domains, routes, functions);

        Function f1 = createFunction(appId, new FunctionOwner.Platform());
        JsonNode warmManifest = manifest(pool.value(), true, null, List.of(), List.of());
        var cmd1 = new PublishCommand(f1.address(), "oci://artifact/wex3a", sha256("wex3a"), null, warmManifest);
        FunctionVersion v1 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd1, EC)).version();
        markReady(f1.address(), v1.version());
        Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, tightSync, settings)
                .run(uow, new PromoteCommand(f1.address(), Function.LIVE, v1.version()), EC));

        Function f2 = createFunction(appId, new FunctionOwner.Platform());
        JsonNode coldManifest = manifest(pool.value(), false, null, List.of(), List.of());
        var cmd2 = new PublishCommand(f2.address(), "oci://artifact/wex3b", sha256("wex3b"), null, coldManifest);
        PublishVersion.Result p2 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd2, EC));
        assertThat(p2.version().version()).isEqualTo(1);
    }

    /// A warm publish into a DIFFERENT pool is unaffected by this pool's
    /// full cap. Mutant "count all pools": would count `f1` against `f3`'s
    /// pool too and throw here.
    @Test
    void warmCapacityDoesNotAffectAnotherPoolAtTheCap() {
        String appId = persistApplication("wex4");
        DnsLabel pool = new DnsLabel("wex4" + fresh());
        DnsLabel otherPool = new DnsLabel("wex4o" + fresh());
        FunctionLimits tight = new FunctionLimits(DEFAULTS.maxDurationMs(), DEFAULTS.maxConcurrency(),
                DEFAULTS.wasmMemoryMb(), DEFAULTS.dbPoolSize(), 1);
        FunctionTriggerSync tightSync = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes, triggerObjects,
                applications, serviceAccounts, versions, tight, POOL_URL, domains, routes, functions);

        Function f1 = createFunction(appId, new FunctionOwner.Platform());
        JsonNode warmManifest = manifest(pool.value(), true, null, List.of(), List.of());
        var cmd1 = new PublishCommand(f1.address(), "oci://artifact/wex4a", sha256("wex4a"), null, warmManifest);
        FunctionVersion v1 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd1, EC)).version();
        markReady(f1.address(), v1.version());
        Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, tightSync, settings)
                .run(uow, new PromoteCommand(f1.address(), Function.LIVE, v1.version()), EC));

        Function f3 = createFunction(appId, new FunctionOwner.Platform());
        JsonNode otherPoolWarm = manifest(otherPool.value(), true, null, List.of(), List.of());
        var cmd2 = new PublishCommand(f3.address(), "oci://artifact/wex4b", sha256("wex4b"), null, otherPoolWarm);
        PublishVersion.Result p2 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd2, EC));
        assertThat(p2.version().version()).isEqualTo(1);
    }

    /// A warm version that was merely PUBLISHED, never promoted, does not
    /// count toward the cap — only the `live` alias does. Mutant "count
    /// lazy versions": dropping the `alias = live` join condition would
    /// count `f1`'s published-but-unpromoted version too and throw here.
    @Test
    void warmCapacityIgnoresAWarmVersionThatWasPublishedButNeverPromoted() {
        String appId = persistApplication("wex5");
        DnsLabel pool = new DnsLabel("wex5" + fresh());
        FunctionLimits tight = new FunctionLimits(DEFAULTS.maxDurationMs(), DEFAULTS.maxConcurrency(),
                DEFAULTS.wasmMemoryMb(), DEFAULTS.dbPoolSize(), 1);
        FunctionTriggerSync tightSync = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes, triggerObjects,
                applications, serviceAccounts, versions, tight, POOL_URL, domains, routes, functions);

        Function f1 = createFunction(appId, new FunctionOwner.Platform());
        JsonNode warmManifest = manifest(pool.value(), true, null, List.of(), List.of());
        var cmd1 = new PublishCommand(f1.address(), "oci://artifact/wex5a", sha256("wex5a"), null, warmManifest);
        PublishVersion.Result p1 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd1, EC));
        assertThat(p1.version().version()).isEqualTo(1);
        // f1's version is PUBLISHED only — never marked ready, never promoted, so
        // fn_aliases has no `live` row for it.

        Function f2 = createFunction(appId, new FunctionOwner.Platform());
        var cmd2 = new PublishCommand(f2.address(), "oci://artifact/wex5b", sha256("wex5b"), null, warmManifest);
        PublishVersion.Result p2 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync, java.util.Optional.empty()).run(uow, cmd2, EC));
        assertThat(p2.version().version()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Trigger key collision (spec §4): two entries of one function whose
    // keys collide are an internal error at promote, never a silent
    // overwrite — and, forced via the injected hasher, this also pins
    // "order and atomicity" (spec §10): the collision is detected before
    // any subscription write, and the transaction that also holds the
    // pool's own create/update and the alias change rolls all of it back.
    // ═══════════════════════════════════════════════════════════════════

    /// The ordinary (non-colliding) case: two different event types hash to
    /// two different keys, so both subscriptions are created and linked.
    @Test
    void twoDifferentEventTypesDoNotCollideBothSubscriptionsExistAndAreLinked() {
        String appId = persistApplication("kc1");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et1 = "fts:kc1:x:a-" + fresh();
        String et2 = "fts:kc1:x:b-" + fresh();
        persistEventType(et1);
        persistEventType(et2);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(sub(et1, "/events/a"), sub(et2, "/events/b")), List.of());
        PublishVersion.Result p = publish(f.address(), "kc1", m);
        promote(f.address(), p.version().version());

        List<TriggerObject> subs = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).toList();
        assertThat(subs).as("both subscriptions created and linked, no collision").hasSize(2);
        List<String> eventTypesSeen = subs.stream().map(TriggerObject::objectId)
                .map(id -> subscriptions.findById(id).orElseThrow())
                .map(s -> s.eventTypes().get(0).eventTypeCode()).toList();
        assertThat(eventTypesSeen).containsExactlyInAnyOrder(et1, et2);
    }

    /// A forced collision (every input hashes to the same 8 hex value) is an
    /// internal error at promote: nothing is written — not the colliding
    /// subscriptions, not the pool, and the alias stays unchanged. Mutant:
    /// drop `checkNoCollisions` entirely — the second subscription would
    /// then silently overwrite the trigger-object link of the first (both
    /// share one `fn_trigger_objects` row), losing track of the first's
    /// underlying `msg_subscriptions` row.
    @Test
    void twoSubscriptionsWithCollidingKeysThrowsAtPromoteAndWritesNothing() {
        String appId = persistApplication("kc2");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et1 = "fts:kc2:x:a-" + fresh();
        String et2 = "fts:kc2:x:b-" + fresh();
        persistEventType(et1);
        persistEventType(et2);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        FunctionTriggerSync collidingSync = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes,
                triggerObjects, applications, serviceAccounts, versions, DEFAULTS, POOL_URL, domains, routes, functions,
                ignored -> "deadbeef");

        JsonNode m = manifest("default", false, null, List.of(sub(et1, "/events/a"), sub(et2, "/events/b")), List.of());
        var cmd = new PublishCommand(f.address(), "oci://artifact/kc2", sha256("kc2"), null, m);
        PublishVersion.Result published = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, collidingSync, java.util.Optional.empty()).run(uow, cmd, EC));
        markReady(f.address(), published.version().version());

        assertThatThrownBy(() -> Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, collidingSync, settings)
                .run(uow, new PromoteCommand(f.address(), Function.LIVE, published.version().version()), EC)))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("TRIGGER_KEY_COLLISION");

        assertThat(linked(f)).as("nothing written at all on collision, not even the pool").isEmpty();
        assertThat(subscriptions.findByApplicationCode("fts" + "kc2" + RUN)).as("no subscription row created").isEmpty();
        assertThat(functions.findByAddress(f.address()).orElseThrow().liveVersionId())
                .as("alias unchanged — the promote's whole transaction rolled back").isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // sameSubscription — one delta per clause (spec §10 V3): a v2 that
    // differs from v1 in ONLY one respect produces exactly one
    // `subscription:updated` event and the row carries the new value.
    // Manifest-driven clauses (path, pool URL, mode, maxRetries,
    // timeoutSeconds, dataOnly) get a real v1→v2 promote; clauses the
    // manifest cannot drive (name, applicationCode, clientId,
    // dispatchPoolId, the binding's eventType) are simulated by mutating the
    // ROW by hand and confirming the next promote repairs it.
    // ═══════════════════════════════════════════════════════════════════

    private record OneSubFixture(Function function, String eventType, String subId) {
    }

    private static OneSubFixture publishAndPromoteOneSubscription(String tag, String pool, int maxConcurrency,
            String path, String mode, int maxRetries, int timeoutSeconds, boolean dataOnly) {
        String appId = persistApplication(tag);
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et = "fts:" + tag + ":x:a-" + fresh();
        persistEventType(et);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest(pool, false, maxConcurrency,
                List.of(subFull(et, path, mode, maxRetries, timeoutSeconds, dataOnly)), List.of());
        PublishVersion.Result p = publish(f.address(), tag + "a", m);
        promote(f.address(), p.version().version());
        String subId = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION)
                .findFirst().orElseThrow().objectId();
        return new OneSubFixture(f, et, subId);
    }

    private static long updatedEventCount(String subId) {
        return eventsFor(SubscriptionEvents.subjectFor(subId), SubscriptionEvents.UPDATED).size();
    }

    @Test
    void subscriptionEndpointUpdatesOnPathChangeOnly() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd1", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        long before = updatedEventCount(fx.subId());
        Subscription beforeRow = subscriptions.findById(fx.subId()).orElseThrow();

        JsonNode m2 = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/b", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd1b", m2);
        promote(fx.function().address(), p2.version().version());

        Subscription after = subscriptions.findById(fx.subId()).orElseThrow();
        assertThat(after.endpoint()).as("mutant: drop the endpoint comparison").endsWith("/events/b")
                .isNotEqualTo(beforeRow.endpoint());
        assertThat(after.mode()).isEqualTo(beforeRow.mode());
        assertThat(after.maxRetries()).isEqualTo(beforeRow.maxRetries());
        assertThat(after.timeoutSeconds()).isEqualTo(beforeRow.timeoutSeconds());
        assertThat(after.dataOnly()).isEqualTo(beforeRow.dataOnly());
        assertThat(updatedEventCount(fx.subId())).as("exactly one update").isEqualTo(before + 1);
    }

    @Test
    void subscriptionEndpointUpdatesOnPoolUrlChangeOnly() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd2", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        long before = updatedEventCount(fx.subId());
        Subscription beforeRow = subscriptions.findById(fx.subId()).orElseThrow();
        assertThat(beforeRow.endpoint()).contains("fn-default");

        JsonNode m2 = manifest("otherpool", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd2b", m2);
        promote(fx.function().address(), p2.version().version());

        Subscription after = subscriptions.findById(fx.subId()).orElseThrow();
        assertThat(after.endpoint()).as("mutant: drop the endpoint comparison").contains("fn-otherpool")
                .isNotEqualTo(beforeRow.endpoint());
        assertThat(after.mode()).isEqualTo(beforeRow.mode());
        assertThat(after.maxRetries()).isEqualTo(beforeRow.maxRetries());
        assertThat(after.timeoutSeconds()).isEqualTo(beforeRow.timeoutSeconds());
        assertThat(after.dataOnly()).isEqualTo(beforeRow.dataOnly());
        assertThat(updatedEventCount(fx.subId())).as("exactly one update").isEqualTo(before + 1);
    }

    @Test
    void subscriptionModeUpdatesOnModeChangeOnly() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd3", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        long before = updatedEventCount(fx.subId());

        JsonNode m2 = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "BLOCK_ON_ERROR", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd3b", m2);
        promote(fx.function().address(), p2.version().version());

        Subscription after = subscriptions.findById(fx.subId()).orElseThrow();
        assertThat(after.mode()).as("mutant: drop the mode comparison")
                .isEqualTo(io.flowcatalyst.platform.shared.dispatch.DispatchMode.BLOCK_ON_ERROR);
        assertThat(updatedEventCount(fx.subId())).as("exactly one update").isEqualTo(before + 1);
    }

    @Test
    void subscriptionMaxRetriesUpdatesOnMaxRetriesChangeOnly() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd4", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        long before = updatedEventCount(fx.subId());

        JsonNode m2 = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 7, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd4b", m2);
        promote(fx.function().address(), p2.version().version());

        Subscription after = subscriptions.findById(fx.subId()).orElseThrow();
        assertThat(after.maxRetries()).as("mutant: drop the maxRetries comparison").isEqualTo(7);
        assertThat(updatedEventCount(fx.subId())).as("exactly one update").isEqualTo(before + 1);
    }

    @Test
    void subscriptionTimeoutSecondsUpdatesOnTimeoutSecondsChangeOnly() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd5", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        long before = updatedEventCount(fx.subId());

        JsonNode m2 = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 60, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd5b", m2);
        promote(fx.function().address(), p2.version().version());

        Subscription after = subscriptions.findById(fx.subId()).orElseThrow();
        assertThat(after.timeoutSeconds()).as("mutant: drop the timeoutSeconds comparison").isEqualTo(60);
        assertThat(updatedEventCount(fx.subId())).as("exactly one update").isEqualTo(before + 1);
    }

    @Test
    void subscriptionDataOnlyUpdatesOnDataOnlyChangeOnly() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd6", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        long before = updatedEventCount(fx.subId());

        JsonNode m2 = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, true)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd6b", m2);
        promote(fx.function().address(), p2.version().version());

        Subscription after = subscriptions.findById(fx.subId()).orElseThrow();
        assertThat(after.dataOnly()).as("mutant: drop the dataOnly comparison").isTrue();
        assertThat(updatedEventCount(fx.subId())).as("exactly one update").isEqualTo(before + 1);
    }

    // ── clauses the manifest cannot drive: mutate the row, re-promote the
    // SAME manifest (a fresh version, byte-identical content — the pattern
    // `promotingTheSameManifestTwiceWritesNothingTheSecondTime` already
    // uses), and confirm the drift is repaired. ──────────────────────────

    @Test
    void subscriptionNameIsRepairedWhenDriftedByHand() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd7", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        String correctName = subscriptions.findById(fx.subId()).orElseThrow().name();
        uow.inTransaction(tx -> {
            subscriptions.persist(subscriptions.findById(fx.subId()).orElseThrow().withName("drifted-by-hand"), tx.dbTx());
            return null;
        });
        assertThat(subscriptions.findById(fx.subId()).orElseThrow().name()).isEqualTo("drifted-by-hand");
        long before = updatedEventCount(fx.subId());

        JsonNode same = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd7b", same);
        promote(fx.function().address(), p2.version().version());

        assertThat(subscriptions.findById(fx.subId()).orElseThrow().name())
                .as("mutant: drop the name comparison").isEqualTo(correctName);
        assertThat(updatedEventCount(fx.subId())).as("exactly one repair event").isEqualTo(before + 1);
    }

    @Test
    void subscriptionApplicationCodeIsRepairedWhenDriftedByHand() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd8", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        String correctApplicationCode = subscriptions.findById(fx.subId()).orElseThrow().applicationCode();
        uow.inTransaction(tx -> {
            subscriptions.persist(subscriptions.findById(fx.subId()).orElseThrow().withApplicationCode("drifted-app-code"),
                    tx.dbTx());
            return null;
        });
        long before = updatedEventCount(fx.subId());

        JsonNode same = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd8b", same);
        promote(fx.function().address(), p2.version().version());

        assertThat(subscriptions.findById(fx.subId()).orElseThrow().applicationCode())
                .as("mutant: drop the applicationCode comparison").isEqualTo(correctApplicationCode);
        assertThat(updatedEventCount(fx.subId())).as("exactly one repair event").isEqualTo(before + 1);
    }

    @Test
    void subscriptionClientIdIsRepairedWhenDriftedByHand() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd9", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        assertThat(subscriptions.findById(fx.subId()).orElseThrow().clientId())
                .as("platform-owned function: correct clientId is null").isNull();
        uow.inTransaction(tx -> {
            subscriptions.persist(subscriptions.findById(fx.subId()).orElseThrow().withClientId("cid" + fresh()),
                    tx.dbTx());
            return null;
        });
        long before = updatedEventCount(fx.subId());

        JsonNode same = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd9b", same);
        promote(fx.function().address(), p2.version().version());

        assertThat(subscriptions.findById(fx.subId()).orElseThrow().clientId())
                .as("mutant: drop the clientId comparison").isNull();
        assertThat(updatedEventCount(fx.subId())).as("exactly one repair event").isEqualTo(before + 1);
    }

    /// Drifts ONLY `dispatchPoolId`, leaving `dispatchPoolCode` correct — so
    /// dropping the `dispatchPoolId` clause specifically (and NOT the
    /// `dispatchPoolCode` clause, which stays active and would otherwise
    /// mask it) is the only way this test fails to detect the drift.
    @Test
    void subscriptionDispatchPoolIdIsRepairedWhenDriftedByHandAlone() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd10", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        Subscription correct = subscriptions.findById(fx.subId()).orElseThrow();
        String correctPoolId = correct.dispatchPoolId();
        uow.inTransaction(tx -> {
            subscriptions.persist(correct.withDispatchPoolId("dpl" + fresh()), tx.dbTx());
            return null;
        });
        long before = updatedEventCount(fx.subId());

        JsonNode same = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd10b", same);
        promote(fx.function().address(), p2.version().version());

        assertThat(subscriptions.findById(fx.subId()).orElseThrow().dispatchPoolId())
                .as("mutant: drop the dispatchPoolId comparison").isEqualTo(correctPoolId);
        assertThat(updatedEventCount(fx.subId())).as("exactly one repair event").isEqualTo(before + 1);
    }

    /// The `dispatchPoolCode` twin: drifts ONLY the code, leaving the id
    /// correct, so the `dispatchPoolId` clause staying active cannot mask a
    /// dropped `dispatchPoolCode` clause.
    @Test
    void subscriptionDispatchPoolCodeIsRepairedWhenDriftedByHandAlone() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd10c", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        Subscription correct = subscriptions.findById(fx.subId()).orElseThrow();
        String correctPoolId = correct.dispatchPoolId();
        String correctPoolCode = correct.dispatchPoolCode();
        uow.inTransaction(tx -> {
            subscriptions.persist(correct.withDispatchPool(correctPoolId, "drifted-code"), tx.dbTx());
            return null;
        });
        long before = updatedEventCount(fx.subId());

        JsonNode same = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd10cb", same);
        promote(fx.function().address(), p2.version().version());

        assertThat(subscriptions.findById(fx.subId()).orElseThrow().dispatchPoolCode())
                .as("mutant: drop the dispatchPoolCode comparison").isEqualTo(correctPoolCode);
        assertThat(updatedEventCount(fx.subId())).as("exactly one repair event").isEqualTo(before + 1);
    }

    @Test
    void subscriptionBindingEventTypeIsRepairedWhenDriftedByHand() {
        OneSubFixture fx = publishAndPromoteOneSubscription("sd11", "default", 5, "/events/a", "IMMEDIATE", 3, 30, false);
        Subscription correct = subscriptions.findById(fx.subId()).orElseThrow();
        uow.inTransaction(tx -> {
            subscriptions.persist(correct.withEventTypes(List.of(EventTypeBinding.of("fts:sd11:x:drifted"))), tx.dbTx());
            return null;
        });
        assertThat(subscriptions.findById(fx.subId()).orElseThrow().eventTypes().get(0).eventTypeCode())
                .isEqualTo("fts:sd11:x:drifted");
        long before = updatedEventCount(fx.subId());

        JsonNode same = manifest("default", false, 5,
                List.of(subFull(fx.eventType(), "/events/a", "IMMEDIATE", 3, 30, false)), List.of());
        PublishVersion.Result p2 = publish(fx.function().address(), "sd11b", same);
        promote(fx.function().address(), p2.version().version());

        assertThat(subscriptions.findById(fx.subId()).orElseThrow().eventTypes().get(0).eventTypeCode())
                .as("mutant: drop the binding eventType comparison").isEqualTo(fx.eventType());
        assertThat(updatedEventCount(fx.subId())).as("exactly one repair event").isEqualTo(before + 1);
    }

    // ═══════════════════════════════════════════════════════════════════
    // V2 — publish creates nothing; promote creates exactly the set
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void publishCreatesNoTriggerObjectsPromoteCreatesExactlyTheManifestsSetWithNoVersionInTheEndpoint() {
        String appId = persistApplication("v2");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et = "fts:v2:x:evt-" + fresh();
        persistEventType(et);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, 7, List.of(sub(et, "/events/a")), List.of(sched("0 0 * * * *", "/jobs/a")));

        PublishVersion.Result published = publish(f.address(), "v2", m);
        assertThat(linked(f)).as("publish must create nothing").isEmpty();
        assertThat(subscriptions.findByApplicationCode("fts" + "v2" + RUN)).isEmpty();

        promote(f.address(), published.version().version());

        List<TriggerObject> after = linked(f);
        assertThat(after).extracting(TriggerObject::kind)
                .containsExactlyInAnyOrder(TriggerObjectKind.POOL, TriggerObjectKind.SUBSCRIPTION, TriggerObjectKind.SCHEDULED_JOB);

        String fid = fid(f);
        assertThat(after).extracting(TriggerObject::triggerKey).contains("fn-" + fid);

        TriggerObject subLink = after.stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst().orElseThrow();
        Subscription s = subscriptions.findById(subLink.objectId()).orElseThrow();
        assertThat(s.endpoint()).as("no version in the endpoint")
                .isEqualTo("http://fn-default:8080/functions/" + f.address().render() + "/events/a")
                .doesNotContain(":" + published.version().version());

        DispatchPool p = pools.findById(after.stream().filter(o -> o.kind() == TriggerObjectKind.POOL).findFirst()
                .orElseThrow().objectId()).orElseThrow();
        assertThat(p.concurrency()).isEqualTo(7);
    }

    // ═══════════════════════════════════════════════════════════════════
    // V3/V6 — promote v2 adds X, drops Y, keeps Z (only concurrency
    // changes); rollback restores v1's set
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void promoteV2AddsXDropsYKeepsZUnchangedAndRollbackRestoresV1sSet() {
        String appId = persistApplication("v3");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String keepType = "fts:v3:x:keep-" + fresh();
        String dropType = "fts:v3:x:drop-" + fresh();
        String addType = "fts:v3:x:add-" + fresh();
        persistEventType(keepType);
        persistEventType(dropType);
        persistEventType(addType);
        Function f = createFunction(appId, new FunctionOwner.Platform());

        JsonNode m1 = manifest("default", false, 3,
                List.of(sub(keepType, "/events/keep"), sub(dropType, "/events/drop")), List.of());
        PublishVersion.Result p1 = publish(f.address(), "v3-1", m1);
        promote(f.address(), p1.version().version());

        List<TriggerObject> afterV1 = linked(f);
        assertThat(afterV1).hasSize(3); // pool + 2 subscriptions
        String keepObjectId = afterV1.stream()
                .filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION)
                .map(TriggerObject::objectId)
                .map(id -> subscriptions.findById(id).orElseThrow())
                .filter(s -> s.eventTypes().get(0).eventTypeCode().equals(keepType))
                .findFirst().orElseThrow().id();
        Instant keepUpdatedAtV1 = subscriptions.findById(keepObjectId).orElseThrow().updatedAt();
        long eventsForKeepBeforeV2 = eventsFor(SubscriptionEvents.subjectFor(keepObjectId), SubscriptionEvents.CREATED).size()
                + eventsFor(SubscriptionEvents.subjectFor(keepObjectId), SubscriptionEvents.UPDATED).size();

        // v2: drops dropType, adds addType, keeps keepType untouched, bumps concurrency.
        JsonNode m2 = manifest("default", false, 9,
                List.of(sub(keepType, "/events/keep"), sub(addType, "/events/add")), List.of());
        PublishVersion.Result p2 = publish(f.address(), "v3-2", m2);
        promote(f.address(), p2.version().version());

        List<TriggerObject> afterV2 = linked(f);
        List<String> v2EventTypes = afterV2.stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION)
                .map(TriggerObject::objectId).map(id -> subscriptions.findById(id).orElseThrow())
                .map(s -> s.eventTypes().get(0).eventTypeCode()).toList();
        assertThat(v2EventTypes).as("X created, Y deleted, Z kept").containsExactlyInAnyOrder(keepType, addType);

        // Z (keepType's subscription) is the SAME row, untouched: same id, same updated_at, no new event.
        Subscription keepAfterV2 = subscriptions.findById(keepObjectId).orElseThrow();
        assertThat(keepAfterV2.updatedAt()).as("Z's updated_at unchanged").isEqualTo(keepUpdatedAtV1);
        long eventsForKeepAfterV2 = eventsFor(SubscriptionEvents.subjectFor(keepObjectId), SubscriptionEvents.CREATED).size()
                + eventsFor(SubscriptionEvents.subjectFor(keepObjectId), SubscriptionEvents.UPDATED).size();
        assertThat(eventsForKeepAfterV2).as("no new msg_events row for Z").isEqualTo(eventsForKeepBeforeV2);

        // The pool DID change (concurrency 3 -> 9) and only that: same id, same code.
        TriggerObject poolLink = afterV2.stream().filter(o -> o.kind() == TriggerObjectKind.POOL).findFirst().orElseThrow();
        DispatchPool poolAfterV2 = pools.findById(poolLink.objectId()).orElseThrow();
        assertThat(poolAfterV2.concurrency()).isEqualTo(9);

        // Rollback: promote back to v1 restores v1's set (drop addType's sub, bring back dropType's).
        promote(f.address(), p1.version().version());
        List<TriggerObject> afterRollback = linked(f);
        List<String> rollbackEventTypes = afterRollback.stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION)
                .map(TriggerObject::objectId).map(id -> subscriptions.findById(id).orElseThrow())
                .map(s -> s.eventTypes().get(0).eventTypeCode()).toList();
        assertThat(rollbackEventTypes).as("rollback restores v1's set").containsExactlyInAnyOrder(keepType, dropType);
    }

    // ═══════════════════════════════════════════════════════════════════
    // A1 — promoting a NAMED alias runs no wiring reconciliation at all
    // ═══════════════════════════════════════════════════════════════════

    /// spec §2: "no wiring change — HTTP-only by ruling". Publishes v1 (with
    /// subscriptions + a scheduled job + a pool) and promotes it to `live` —
    /// the normal wiring materialises. Then publishes v2 with a DIFFERENT
    /// trigger set and promotes a NAMED alias (`qa`) to it. The mutant this
    /// pins ("apply live's wiring" to a named-alias promote) would make
    /// `linked(f)` change to v2's set, or `live` silently move — this test
    /// asserts BOTH stay exactly as v1 left them, and that `qa` itself really
    /// did move (so the pin is not vacuous — the promote itself worked).
    @Test
    void promotingANamedAliasRunsNoWiringAndLeavesLiveAndWiringUnchanged() {
        String appId = persistApplication("a1");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String liveType = "fts:a1:x:live-" + fresh();
        String qaType = "fts:a1:x:qa-" + fresh();
        persistEventType(liveType);
        persistEventType(qaType);
        Function f = createFunction(appId, new FunctionOwner.Platform());

        JsonNode m1 = manifest("default", false, 3, List.of(sub(liveType, "/events/live")), List.of(sched("0 0 * * * *", "/jobs/live")));
        PublishVersion.Result p1 = publish(f.address(), "a1-v1", m1);
        promote(f.address(), p1.version().version());

        List<TriggerObject> afterLive = linked(f);
        assertThat(afterLive).as("v1's wiring materialised on live").hasSize(3); // pool + sub + job
        List<String> afterLiveObjectIds = afterLive.stream().map(TriggerObject::objectId).sorted().toList();
        String liveVersionIdBefore = functions.findById(f.id()).orElseThrow().liveVersionId().orElseThrow();

        // v2: a DIFFERENT trigger set entirely, promoted only to the NAMED alias `qa`.
        JsonNode m2 = manifest("other", false, 9, List.of(sub(qaType, "/events/qa")), List.of());
        PublishVersion.Result p2 = publish(f.address(), "a1-v2", m2);
        promoteNamedAlias(f.address(), "qa", p2.version().version());

        // The trigger-object set is byte-for-byte the same set of rows — count AND identity —
        // as it was after the live promote: no create, no delete, no update.
        List<TriggerObject> afterQa = linked(f);
        assertThat(afterQa).as("mutant: reconcile wiring for a named-alias promote too").hasSize(3);
        assertThat(afterQa.stream().map(TriggerObject::objectId).sorted().toList())
                .as("mutant: the trigger-object rows themselves changed").isEqualTo(afterLiveObjectIds);
        assertThat(subscriptions.findByApplicationCode("fts" + "a1" + RUN))
                .as("mutant: v2's subscription materialised").extracting(s -> s.eventTypes().get(0).eventTypeCode())
                .containsExactly(liveType);

        // live itself did not move.
        Function reloaded = functions.findById(f.id()).orElseThrow();
        assertThat(reloaded.liveVersionId()).as("mutant: promoting qa also moved live")
                .contains(liveVersionIdBefore);

        // The promote itself was real: qa now points at v2 (not vacuous).
        String qaVersionId = reloaded.aliases().stream().filter(a -> a.alias().equals("qa")).findFirst()
                .orElseThrow().versionId();
        assertThat(qaVersionId).isEqualTo(p2.version().id());
    }

    @Test
    void promotingTheSameManifestTwiceWritesNothingTheSecondTime() {
        String appId = persistApplication("v3b");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, 4, List.of(), List.of());
        PublishVersion.Result p1 = publish(f.address(), "v3b-1", m);
        promote(f.address(), p1.version().version());
        TriggerObject poolLink = linked(f).get(0);
        DispatchPool before = pools.findById(poolLink.objectId()).orElseThrow();

        // Publish a second, byte-identical-manifest version and promote it: the
        // pool's desired concurrency is unchanged, so NO write, NO new event.
        PublishVersion.Result p2 = publish(f.address(), "v3b-2", m);
        promote(f.address(), p2.version().version());

        DispatchPool after = pools.findById(poolLink.objectId()).orElseThrow();
        assertThat(after.updatedAt()).as("mutant: always rewrite -> updated_at would move").isEqualTo(before.updatedAt());
    }

    // ═══════════════════════════════════════════════════════════════════
    // V5 — delete removes linked objects and ONLY those; disable pauses,
    // enable resumes
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void deleteRemovesOnlyThisFunctionsLinkedObjectsSiblingAndUnrelatedSubscriptionsSurvive() {
        String appId = persistApplication("v5");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et1 = "fts:v5:x:a-" + fresh();
        String et2 = "fts:v5:x:b-" + fresh();
        persistEventType(et1);
        persistEventType(et2);

        Function victim = createFunction(appId, new FunctionOwner.Platform());
        Function sibling = createFunction(appId, new FunctionOwner.Platform());

        JsonNode mv = manifest("default", false, null, List.of(sub(et1, "/events/a")), List.of());
        JsonNode ms = manifest("default", false, null, List.of(sub(et2, "/events/b")), List.of());
        PublishVersion.Result pv = publish(victim.address(), "v5v", mv);
        PublishVersion.Result ps = publish(sibling.address(), "v5s", ms);
        promote(victim.address(), pv.version().version());
        promote(sibling.address(), ps.version().version());

        // An unrelated, ordinary (non-function) subscription of the same application.
        Subscription unrelated = Subscription.create("v5-unrelated-" + fresh(), "unrelated", "https://hook.example/x")
                .withApplicationCode("fts" + "v5" + RUN);
        uow.inTransaction(tx -> {
            subscriptions.persist(unrelated, tx.dbTx());
            return null;
        });

        List<TriggerObject> victimLinks = linked(victim);
        List<TriggerObject> siblingLinksBefore = linked(sibling);
        assertThat(victimLinks).isNotEmpty();
        assertThat(siblingLinksBefore).isNotEmpty();

        deleteFunction(victim.address());

        assertThat(linked(victim)).as("victim's own links are gone (cascade)").isEmpty();
        for (TriggerObject o : victimLinks) {
            switch (o.kind()) {
                case SUBSCRIPTION -> assertThat(subscriptions.findById(o.objectId())).as("victim's subscription deleted").isEmpty();
                case SCHEDULED_JOB -> assertThat(jobs.findById(o.objectId())).isEmpty();
                case POOL -> assertThat(pools.findById(o.objectId())).as("victim's pool deleted").isEmpty();
            }
        }
        // The sibling function's own objects survive untouched.
        List<TriggerObject> siblingLinksAfter = linked(sibling);
        assertThat(siblingLinksAfter).containsExactlyInAnyOrderElementsOf(siblingLinksBefore);
        for (TriggerObject o : siblingLinksAfter) {
            if (o.kind() == TriggerObjectKind.SUBSCRIPTION) {
                assertThat(subscriptions.findById(o.objectId())).as("sibling's subscription survives").isPresent();
            } else if (o.kind() == TriggerObjectKind.POOL) {
                assertThat(pools.findById(o.objectId())).as("sibling's pool survives").isPresent();
            }
        }
        // The unrelated, non-function subscription of the same application survives.
        assertThat(subscriptions.findById(unrelated.id())).as("unrelated subscription survives").isPresent();
    }

    @Test
    void disablingAFunctionPausesItsSubscriptionsAndJobsEnablingResumesThem() {
        String appId = persistApplication("v5b");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et = "fts:v5b:x:a-" + fresh();
        persistEventType(et);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(sub(et, "/events/a")), List.of(sched("0 0 * * * *", "/jobs/a")));
        PublishVersion.Result p = publish(f.address(), "v5b", m);
        promote(f.address(), p.version().version());

        List<TriggerObject> ls = linked(f);
        String subId = ls.stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst().orElseThrow().objectId();
        String jobId = ls.stream().filter(o -> o.kind() == TriggerObjectKind.SCHEDULED_JOB).findFirst().orElseThrow().objectId();
        String poolId = ls.stream().filter(o -> o.kind() == TriggerObjectKind.POOL).findFirst().orElseThrow().objectId();

        updateStatus(f.address(), "DISABLED");
        assertThat(subscriptions.findById(subId).orElseThrow().isPaused()).as("subscription paused").isTrue();
        assertThat(jobs.findById(jobId).orElseThrow().status().name()).as("job paused").isEqualTo("PAUSED");
        assertThat(pools.findById(poolId).orElseThrow().status().name()).as("pool untouched by status change").isEqualTo("ACTIVE");

        updateStatus(f.address(), "ACTIVE");
        assertThat(subscriptions.findById(subId).orElseThrow().isActive()).as("subscription resumed").isTrue();
        assertThat(jobs.findById(jobId).orElseThrow().status().name()).as("job resumed").isEqualTo("ACTIVE");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scheduled job client scope: the owner's, null for a platform function
    // (spec §4 table). Mutant: always null.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void aPlatformOwnedFunctionsScheduledJobIsPlatformScoped() {
        String appId = persistApplication("cs1");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(), List.of(sched("0 0 * * * *", "/jobs/a")));
        PublishVersion.Result p = publish(f.address(), "cs1", m);
        promote(f.address(), p.version().version());

        TriggerObject jobLink = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SCHEDULED_JOB)
                .findFirst().orElseThrow();
        ScheduledJob job = jobs.findById(jobLink.objectId()).orElseThrow();
        assertThat(job.clientId()).as("platform-owned function's job is platform-scoped").isNull();
    }

    @Test
    void aClientOwnedFunctionsScheduledJobCarriesTheOwnersClientId() {
        String appId = persistApplication("cs2");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String clientId = "cid" + fresh();
        Function f = createFunction(appId, new FunctionOwner.Client(clientId));
        JsonNode m = manifest("default", false, null, List.of(), List.of(sched("0 0 * * * *", "/jobs/a")));
        PublishVersion.Result p = publish(f.address(), "cs2", m);
        promote(f.address(), p.version().version());

        TriggerObject jobLink = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SCHEDULED_JOB)
                .findFirst().orElseThrow();
        ScheduledJob job = jobs.findById(jobLink.objectId()).orElseThrow();
        assertThat(job.clientId()).as("client-owned function's job carries the owner's client id")
                .isEqualTo(clientId);
    }

    /// Spec §4: disabling pauses only `ACTIVE` linked objects; an object
    /// already in the target state (here, an operator's hand-pause) is left
    /// alone — no write, no event — and the function's own status update
    /// still succeeds. Then enabling resumes EVERY `PAUSED` linked object,
    /// which necessarily includes the hand-paused one: the platform cannot
    /// tell who paused it, so it cannot single it out to leave alone on the
    /// way back up. Mutant (drop the disable-side guard): subB would get a
    /// SECOND `paused` event even though it never left `PAUSED`, so the
    /// "exactly one" count below would be 2, not 1.
    @Test
    void disablingWithAHandPausedSubscriptionEmitsExactlyOnePausedEventAndEnablingResumesBoth() {
        String appId = persistApplication("pg1");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String etA = "fts:pg1:x:a-" + fresh();
        String etB = "fts:pg1:x:b-" + fresh();
        persistEventType(etA);
        persistEventType(etB);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null,
                List.of(sub(etA, "/events/a"), sub(etB, "/events/b")), List.of());
        PublishVersion.Result p = publish(f.address(), "pg1", m);
        promote(f.address(), p.version().version());

        List<TriggerObject> subLinks = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).toList();
        assertThat(subLinks).hasSize(2);
        String subAId = subLinks.stream().map(TriggerObject::objectId).map(id -> subscriptions.findById(id).orElseThrow())
                .filter(s -> s.eventTypes().get(0).eventTypeCode().equals(etA)).findFirst().orElseThrow().id();
        String subBId = subLinks.stream().map(TriggerObject::objectId).map(id -> subscriptions.findById(id).orElseThrow())
                .filter(s -> s.eventTypes().get(0).eventTypeCode().equals(etB)).findFirst().orElseThrow().id();

        // The operator hand-pauses subB directly (bypassing FunctionTriggerSync
        // entirely — no event, exactly like an admin PAUSE would look to this test).
        uow.inTransaction(tx -> {
            subscriptions.persist(subscriptions.findById(subBId).orElseThrow().pause(), tx.dbTx());
            return null;
        });
        assertThat(subscriptions.findById(subBId).orElseThrow().isPaused()).isTrue();

        updateStatus(f.address(), "DISABLED"); // must still succeed

        assertThat(subscriptions.findById(subAId).orElseThrow().isPaused()).as("subA newly paused").isTrue();
        assertThat(subscriptions.findById(subBId).orElseThrow().isPaused()).as("subB still paused").isTrue();
        assertThat(eventsFor(SubscriptionEvents.subjectFor(subAId), SubscriptionEvents.PAUSED))
                .as("subA got exactly one paused event").hasSize(1);
        assertThat(eventsFor(SubscriptionEvents.subjectFor(subBId), SubscriptionEvents.PAUSED))
                .as("subB, already paused, gets NO paused event from the function disable").isEmpty();

        updateStatus(f.address(), "ACTIVE"); // must still succeed

        assertThat(subscriptions.findById(subAId).orElseThrow().isActive()).as("subA resumed").isTrue();
        assertThat(subscriptions.findById(subBId).orElseThrow().isActive())
                .as("subB resumed too: it was PAUSED at enable time, hand-paused or not").isTrue();
    }

    /// The enable-side twin of the test above, pinned separately because
    /// that one cannot catch a dropped RESUME guard on its own (both
    /// subscriptions are PAUSED at enable time there, so either would
    /// resume with or without the guard). Here the operator hand-RESUMES
    /// one of two subscriptions while the FUNCTION itself is still
    /// `DISABLED` (only the function's own transition runs `onStatusChange`
    /// — `Function#enable`/`#disable` themselves refuse a no-op flip, so
    /// there is no other way to reach it with a subscription already in the
    /// target state). Enabling the function must resume subA (still
    /// `PAUSED`) and leave subB alone. Mutant: drop `if (!s.isPaused())
    /// return;` — subB would get a second, redundant `resumed` event.
    @Test
    void enablingWithAHandResumedSubscriptionEmitsExactlyOneResumedEvent() {
        String appId = persistApplication("pg2");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String etA = "fts:pg2:x:a-" + fresh();
        String etB = "fts:pg2:x:b-" + fresh();
        persistEventType(etA);
        persistEventType(etB);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null,
                List.of(sub(etA, "/events/a"), sub(etB, "/events/b")), List.of());
        PublishVersion.Result p = publish(f.address(), "pg2", m);
        promote(f.address(), p.version().version());

        List<TriggerObject> subLinks = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).toList();
        String subAId = subLinks.stream().map(TriggerObject::objectId).map(id -> subscriptions.findById(id).orElseThrow())
                .filter(s -> s.eventTypes().get(0).eventTypeCode().equals(etA)).findFirst().orElseThrow().id();
        String subBId = subLinks.stream().map(TriggerObject::objectId).map(id -> subscriptions.findById(id).orElseThrow())
                .filter(s -> s.eventTypes().get(0).eventTypeCode().equals(etB)).findFirst().orElseThrow().id();

        updateStatus(f.address(), "DISABLED"); // real transition: both subs paused via onStatusChange
        assertThat(subscriptions.findById(subAId).orElseThrow().isPaused()).isTrue();
        assertThat(subscriptions.findById(subBId).orElseThrow().isPaused()).isTrue();

        // The operator hand-resumes subB directly while the FUNCTION is still
        // DISABLED — bypassing FunctionTriggerSync entirely, no event.
        uow.inTransaction(tx -> {
            subscriptions.persist(subscriptions.findById(subBId).orElseThrow().resume(), tx.dbTx());
            return null;
        });
        assertThat(subscriptions.findById(subBId).orElseThrow().isActive()).isTrue();

        updateStatus(f.address(), "ACTIVE"); // real transition: must still succeed

        assertThat(subscriptions.findById(subAId).orElseThrow().isActive()).as("subA resumed").isTrue();
        assertThat(subscriptions.findById(subBId).orElseThrow().isActive()).as("subB stayed active").isTrue();
        assertThat(eventsFor(SubscriptionEvents.subjectFor(subAId), SubscriptionEvents.RESUMED))
                .as("subA got exactly one resumed event").hasSize(1);
        assertThat(eventsFor(SubscriptionEvents.subjectFor(subBId), SubscriptionEvents.RESUMED))
                .as("subB, already active, gets NO resumed event from the function enable").isEmpty();
    }

    /// The scheduled-job twin of the two tests above: same guard, same
    /// reasoning, in [#pauseOrResumeJob]. Mutant: drop either
    /// `j.status() != ACTIVE`/`!= PAUSED` guard — the hand-mutated job would
    /// get a redundant event.
    @Test
    void jobPauseAndResumeGuardsSkipAJobAlreadyInTheTargetState() {
        String appId = persistApplication("pg3");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(), List.of(sched("0 0 * * * *", "/jobs/a")));
        PublishVersion.Result p = publish(f.address(), "pg3", m);
        promote(f.address(), p.version().version());

        String jobId = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SCHEDULED_JOB)
                .findFirst().orElseThrow().objectId();

        // Hand-pause the job before disabling the function.
        uow.inTransaction(tx -> {
            jobs.persist(jobs.findById(jobId).orElseThrow().pause("operator"), tx.dbTx());
            return null;
        });
        updateStatus(f.address(), "DISABLED");
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(io.flowcatalyst.platform.scheduledjob.ScheduledJobStatus.PAUSED);
        assertThat(eventsFor(io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.subjectFor(jobId),
                io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.PAUSED))
                .as("hand-paused job gets NO paused event from the function disable").isEmpty();

        // Hand-resume the job while the function is still DISABLED.
        uow.inTransaction(tx -> {
            jobs.persist(jobs.findById(jobId).orElseThrow().resume("operator"), tx.dbTx());
            return null;
        });
        updateStatus(f.address(), "ACTIVE");
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(io.flowcatalyst.platform.scheduledjob.ScheduledJobStatus.ACTIVE);
        assertThat(eventsFor(io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.subjectFor(jobId),
                io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.RESUMED))
                .as("hand-resumed job gets NO resumed event from the function enable").isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Hand-deleted linked object is recreated at the next promote
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void aHandDeletedLinkedSubscriptionIsRecreatedAtTheNextPromote() {
        String appId = persistApplication("hd");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et = "fts:hd:x:a-" + fresh();
        persistEventType(et);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(sub(et, "/events/a")), List.of());
        PublishVersion.Result p1 = publish(f.address(), "hd1", m);
        promote(f.address(), p1.version().version());

        TriggerObject subLink = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst().orElseThrow();
        String oldSubscriptionId = subLink.objectId();

        // Hand-delete the underlying row directly (bypassing fn_trigger_objects
        // awareness — an admin DELETE on the subscription would do exactly this).
        uow.inTransaction(tx -> {
            subscriptions.delete(subscriptions.findById(oldSubscriptionId).orElseThrow(), tx.dbTx());
            return null;
        });
        assertThat(subscriptions.findById(oldSubscriptionId)).as("present:false in between").isEmpty();
        // The link row is still there, dangling, until the next promote.
        assertThat(triggerObjects.listByFunction(f.id())).extracting(TriggerObject::triggerKey)
                .contains(subLink.triggerKey());

        // Publish and promote a new version with the SAME manifest: reconciliation
        // must recreate the subscription rather than treat the dangling link as "present".
        PublishVersion.Result p2 = publish(f.address(), "hd2", m);
        promote(f.address(), p2.version().version());

        TriggerObject subLinkAfter = linked(f).stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst().orElseThrow();
        assertThat(subLinkAfter.objectId()).as("recreated with a NEW object id").isNotEqualTo(oldSubscriptionId);
        assertThat(subscriptions.findById(subLinkAfter.objectId())).isPresent();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Events: the subscription/pool/job events are the aggregates' own
    // types with their own message groups; alias:changed is still exactly one
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void reconciliationEventsAreEachAggregatesOwnTypeWithItsOwnMessageGroupAndAliasChangedIsStillExactlyOne() {
        String appId = persistApplication("ev");
        persistServiceAccount(appId, "secret-" + fresh(), true);
        String et = "fts:ev:x:a-" + fresh();
        persistEventType(et);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        JsonNode m = manifest("default", false, null, List.of(sub(et, "/events/a")), List.of(sched("0 0 * * * *", "/jobs/a")));
        PublishVersion.Result p = publish(f.address(), "ev", m);

        promote(f.address(), p.version().version());

        List<TriggerObject> ls = linked(f);
        String subId = ls.stream().filter(o -> o.kind() == TriggerObjectKind.SUBSCRIPTION).findFirst().orElseThrow().objectId();
        String jobId = ls.stream().filter(o -> o.kind() == TriggerObjectKind.SCHEDULED_JOB).findFirst().orElseThrow().objectId();
        String poolId = ls.stream().filter(o -> o.kind() == TriggerObjectKind.POOL).findFirst().orElseThrow().objectId();

        assertThat(eventsFor(SubscriptionEvents.subjectFor(subId), SubscriptionEvents.CREATED))
                .as("subscription's own event type + its own message group")
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.get("message_group", String.class)).isEqualTo(SubscriptionEvents.messageGroupFor(subId)));
        assertThat(eventsFor(io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.subjectFor(jobId),
                io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.CREATED))
                .as("job's own event type + its own message group")
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.get("message_group", String.class))
                        .isEqualTo(io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.messageGroupFor(jobId)));
        assertThat(eventsFor(io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.subjectFor(poolId),
                io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.CREATED))
                .as("pool's own event type + its own message group")
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.get("message_group", String.class))
                        .isEqualTo(io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.messageGroupFor(poolId)));

        // Exactly one alias:changed for this one promote — reconciliation must
        // not emit any extra function-level events.
        assertThat(eventsFor(io.flowcatalyst.sdk.usecase.EventConventions.buildSubject("platform", "function", f.id()),
                FunctionEvents.ALIAS_CHANGED)).hasSize(1);
    }

    // ── F2 (spec `function-public-routes.md` §6): publish is refused for an
    // unclaimed hostname, a pending one, and another owner's verified one —
    // SAME code for all three, each its own test so a mutant dropping any one
    // clause dies without killing the others ──────────────────────────────

    @Test
    void publishRefusesAnUnclaimedHostname() {
        String appId = persistApplication("f2a");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String host = "f2unclaimed-" + fresh() + ".example.com";

        assertUseCaseError(() -> publish(f.address(), "f2a", manifestWithPublic("default", host, "/")),
                UseCaseError.Validation.class, "PUBLIC_HOSTNAME_NOT_VERIFIED");
        assertThat(versions.listByFunction(f.id())).as("nothing persists on a refused publish").isEmpty();
    }

    @Test
    void publishRefusesAPendingHostname() {
        String appId = persistApplication("f2b");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String host = "f2pending-" + fresh() + ".example.com";
        persistPendingDomain(new FunctionOwner.Platform(), host);

        assertUseCaseError(() -> publish(f.address(), "f2b", manifestWithPublic("default", host, "/")),
                UseCaseError.Validation.class, "PUBLIC_HOSTNAME_NOT_VERIFIED");
    }

    @Test
    void publishRefusesAnotherOwnersVerifiedHostname() {
        String appId = persistApplication("f2c");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String host = "f2otherowner-" + fresh() + ".example.com";
        persistVerifiedDomain(FunctionOwner.ofClientId("clt_" + fresh()), host);

        assertUseCaseError(() -> publish(f.address(), "f2c", manifestWithPublic("default", host, "/")),
                UseCaseError.Validation.class, "PUBLIC_HOSTNAME_NOT_VERIFIED");
    }

    /// The positive control (mutant: "always throw" / "skip the owner
    /// comparison" masking a false negative) — a hostname verified by the
    /// SAME owner publishes cleanly.
    @Test
    void publishSucceedsWithAVerifiedHostnameOfTheSameOwner() {
        String appId = persistApplication("f2ok");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String host = "f2ok-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);

        PublishVersion.Result result = publish(f.address(), "f2ok", manifestWithPublic("default", host, "/"));
        assertThat(result.version().version()).isEqualTo(1);
    }

    // ── Z1 (spec `function-zones-and-aliases.md` §8): a ZONE claim covers a
    // deeper hostname under it — a claim of `acme.com` verifies
    // `myapp.acme.com`; it does NOT verify a hostname under a DIFFERENT
    // apex. Mutant: equality instead of covering. ────────────────────────

    @Test
    void publishSucceedsWithAHostnameCoveredByAZoneClaimOfTheSameOwner() {
        String appId = persistApplication("z1ok");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String apex = "z1zone-" + fresh() + ".acme.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), apex);
        String deep = "myapp." + apex;

        PublishVersion.Result result = publish(f.address(), "z1ok", manifestWithPublic("default", deep, "/"));
        assertThat(result.version().version()).isEqualTo(1);
    }

    @Test
    void publishRefusesAHostnameUnderAnUnrelatedApex() {
        String appId = persistApplication("z1no");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        // A zone claim exists, but for a DIFFERENT apex than the manifest's hostname —
        // pins "covering", not "any claim at all exists ⇒ pass".
        persistVerifiedDomain(new FunctionOwner.Platform(), "z1other-" + fresh() + ".acme.com");
        String unrelated = "myapp.z1unrelated-" + fresh() + ".other.com";

        assertUseCaseError(() -> publish(f.address(), "z1no", manifestWithPublic("default", unrelated, "/")),
                UseCaseError.Validation.class, "PUBLIC_HOSTNAME_NOT_VERIFIED");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    // ── F4 (spec §6): promote materialises exactly the manifest's set; a
    // dropped route frees it for another function; equal routes on two
    // functions conflict at publish AND at promote; the unique-violation
    // path is a 409, never a 500 ─────────────────────────────────────────

    @Test
    void promoteMaterialisesExactlyTheManifestsPublicRouteSet() {
        String appId = persistApplication("f4mat");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String host = "f4mat-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);

        var p = publish(f.address(), "f4mat", manifestWithPublic("default", host, "/api"));
        assertThat(routes.listByFunction(f.id())).as("publish validates only, never materialises").isEmpty();

        promote(f.address(), p.version().version());

        List<FunctionRoute> materialised = routes.listByFunction(f.id());
        assertThat(materialised).hasSize(1);
        assertThat(materialised.get(0).hostname().value()).isEqualTo(host);
        assertThat(materialised.get(0).pathPrefix().value()).isEqualTo("/api");
    }

    /// spec `function-zones-and-aliases.md` §3: the route's `alias_prefixes`
    /// column is copied verbatim from the manifest's own `public[].aliasPrefixes`.
    @Test
    void promoteCopiesAliasPrefixesFromTheManifestOntoTheRoute() {
        String appId = persistApplication("j3copy");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String host = "j3copy-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);

        var p = publish(f.address(), "j3copy",
                manifestWithPublicAndAliasPrefixes("default", host, "/", List.of("qa", "staging")));
        promote(f.address(), p.version().version());

        List<FunctionRoute> materialised = routes.listByFunction(f.id());
        assertThat(materialised).hasSize(1);
        assertThat(materialised.get(0).aliasPrefixes())
                .as("mutant: drop aliasPrefixes when materialising fn_routes")
                .containsExactly("qa", "staging");
    }

    /// spec §3: a manifest edit that changes ONLY `aliasPrefixes` (same
    /// hostname, same pathPrefix) is still a difference the row must be
    /// rewritten for — `sameRoutes`'s key must include `aliasPrefixes`, not
    /// just `hostname|pathPrefix` (mutant: compare hostname+prefix only, so
    /// the second promote's `reconcilePublicRoutes` sees "no difference" and
    /// skips the write, leaving the stale prefix list in place).
    @Test
    void changingOnlyAliasPrefixesRewritesTheRouteRow() {
        String appId = persistApplication("j3rewrite");
        Function f = createFunction(appId, new FunctionOwner.Platform());
        String host = "j3rewrite-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);

        var p1 = publish(f.address(), "j3rewrite1",
                manifestWithPublicAndAliasPrefixes("default", host, "/", List.of("qa")));
        promote(f.address(), p1.version().version());
        assertThat(routes.listByFunction(f.id()).get(0).aliasPrefixes()).containsExactly("qa");

        // v2: same (hostname, pathPrefix), only aliasPrefixes changed.
        var p2 = publish(f.address(), "j3rewrite2",
                manifestWithPublicAndAliasPrefixes("default", host, "/", List.of("staging")));
        promote(f.address(), p2.version().version());

        List<FunctionRoute> materialised = routes.listByFunction(f.id());
        assertThat(materialised).hasSize(1);
        assertThat(materialised.get(0).aliasPrefixes())
                .as("mutant: sameRoutes ignores aliasPrefixes, so the stale value survives the second promote")
                .containsExactly("staging");
    }

    @Test
    void promoteV2DropsARouteFreeingItForAnotherFunctionInTheSameTransactionVisibleWay() {
        String appId = persistApplication("f4free");
        String host = "f4free-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);

        Function a = createFunction(appId, new FunctionOwner.Platform());
        var pa1 = publish(a.address(), "f4freea1", manifestWithPublic("default", host, "/"));
        promote(a.address(), pa1.version().version());
        assertThat(routes.listByFunction(a.id())).hasSize(1);

        // v2 drops the public route entirely (an ordinary manifest() with no "public" key).
        var pa2 = publish(a.address(), "f4freea2", manifest("default", false, null, List.of(), List.of()));
        promote(a.address(), pa2.version().version());
        assertThat(routes.listByFunction(a.id())).as("dropped route is gone").isEmpty();

        // Function B, same owner, can now take the freed route — in the same
        // transaction-visible way: nothing but A's own promote had to run first.
        Function b = createFunction(appId, new FunctionOwner.Platform());
        var pb = publish(b.address(), "f4freeb", manifestWithPublic("default", host, "/"));
        promote(b.address(), pb.version().version());
        assertThat(routes.listByFunction(b.id())).hasSize(1);
    }

    @Test
    void equalRouteOnTwoFunctionsConflictsAtPublishFreeAndAtPromoteTaken() {
        String appId = persistApplication("f4race");
        String host = "f4race-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);

        Function a = createFunction(appId, new FunctionOwner.Platform());
        Function b = createFunction(appId, new FunctionOwner.Platform());

        // Both PUBLISH successfully — neither is materialised yet (spec §6 M4's race).
        var pa = publish(a.address(), "f4racea", manifestWithPublic("default", host, "/"));
        var pb = publish(b.address(), "f4raceb", manifestWithPublic("default", host, "/"));

        // A promotes first — fine.
        promote(a.address(), pa.version().version());

        // B promotes second — the SAME (hostname, prefix) is now taken, re-checked at promote.
        assertUseCaseError(() -> promote(b.address(), pb.version().version()), UseCaseError.Conflict.class,
                "PUBLIC_ROUTE_TAKEN");
        assertThat(routes.listByFunction(b.id())).as("refused promote materialises nothing for B").isEmpty();
        assertThat(routes.listByFunction(a.id())).as("A's own route is untouched by B's refused promote").hasSize(1);

        // The promote-time conflict names A too (ANCHOR reaches everything) — this is
        // ONLY true through the explicit re-check's own message; the unique-constraint
        // catch's fallback message never names anyone (mutant: skip the re-check).
        assertThatThrownBy(() -> promote(b.address(), pb.version().version()))
                .as("mutant: skip the promote-time re-check — the DB-catch fallback message names no one")
                .hasMessageContaining(a.address().render());
    }

    /// Spec §6 M4: "the unique-violation path is a 409, not a 500" — forced
    /// by REAL concurrency rather than a test double: two threads, released
    /// simultaneously by a latch, both promote a function wanting the SAME
    /// (hostname, prefix). Under Postgres READ COMMITTED, both `findPublic`
    /// pre-checks can legitimately see no conflict (neither has committed
    /// yet) — the two `INSERT`s then race for real, and the loser must hit
    /// `FunctionTriggerSync#isUniqueViolation`'s catch, not an unhandled 500.
    /// Mutant this pins: skip the promote-time re-check (already pinned by
    /// the sequential test above) and — the one THIS test alone can kill —
    /// let the constraint surface as an uncaught `DataAccessException`.
    @Test
    void concurrentPromotesRacingPastTheReCheckStillMapTheLosingInsertTo409NotA500() throws Exception {
        String appId = persistApplication("f4uv");
        String host = "f4uv-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);

        Function a = createFunction(appId, new FunctionOwner.Platform());
        Function b = createFunction(appId, new FunctionOwner.Platform());
        var pa = publish(a.address(), "f4uva", manifestWithPublic("default", host, "/"));
        var pb = publish(b.address(), "f4uvb", manifestWithPublic("default", host, "/"));
        markReady(a.address(), pa.version().version());
        markReady(b.address(), pb.version().version());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AliasChanged> fa = pool.submit(() -> {
                ready.countDown();
                go.await();
                return promote(a.address(), pa.version().version());
            });
            Future<AliasChanged> fb = pool.submit(() -> {
                ready.countDown();
                go.await();
                return promote(b.address(), pb.version().version());
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int succeeded = 0;
            int conflicted = 0;
            for (Future<AliasChanged> f : List.of(fa, fb)) {
                try {
                    f.get(20, TimeUnit.SECONDS);
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).as("mutant: let the constraint surface as an uncaught 500")
                            .isInstanceOf(UseCaseException.class);
                    UseCaseException uce = (UseCaseException) e.getCause();
                    assertThat(uce.error()).isInstanceOf(UseCaseError.Conflict.class);
                    assertThat(uce.error().code()).isEqualTo("PUBLIC_ROUTE_TAKEN");
                    conflicted++;
                }
            }
            assertThat(succeeded).as("exactly one promote wins the race").isEqualTo(1);
            assertThat(conflicted).as("the loser is a 409, not a crash").isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    // ── F5 (spec §6): PUBLIC_ROUTE_TAKEN names the other function only to a
    // caller who can reach it ────────────────────────────────────────────

    @Test
    void publicRouteTakenNamesTheOtherFunctionOnlyWhenTheCallerCanReachIt() {
        String clientId = "clt_" + fresh();
        String appIdA = persistApplication("f5a");
        String appIdB = persistApplication("f5b");
        String host = "f5-" + fresh() + ".example.com";
        persistVerifiedDomain(FunctionOwner.ofClientId(clientId), host);

        Function a = createFunction(appIdA, FunctionOwner.ofClientId(clientId));
        Function b = createFunction(appIdB, FunctionOwner.ofClientId(clientId));

        var pa = publish(a.address(), "f5a", manifestWithPublic("default", host, "/"));
        promote(a.address(), pa.version().version());

        // Reaches the owning CLIENT but not A's application — owner reach alone is
        // not enough (Access.canReach ANDs it with application reach).
        AuthContext strangerToAppA = clientScoped(clientId, false, List.of(appIdB));
        assertThatThrownBy(() -> publishAs(strangerToAppA, b.address(), "f5b-hidden",
                manifestWithPublic("default", host, "/")))
                .isInstanceOf(UseCaseException.class)
                .satisfies(t -> {
                    UseCaseException uce = (UseCaseException) t;
                    assertThat(uce.error().code()).isEqualTo("PUBLIC_ROUTE_TAKEN");
                    assertThat(uce.error().message()).as("mutant: always name it — caller cannot reach A")
                            .doesNotContain(a.address().render());
                });

        // The identical conflict, as a caller who reaches everything (anchor) — names A.
        assertThatThrownBy(() -> publish(b.address(), "f5b-named", manifestWithPublic("default", host, "/")))
                .isInstanceOf(UseCaseException.class)
                .satisfies(t -> {
                    UseCaseException uce = (UseCaseException) t;
                    assertThat(uce.error().code()).isEqualTo("PUBLIC_ROUTE_TAKEN");
                    assertThat(uce.error().message()).as("caller reaches A: address IS named")
                            .contains(a.address().render());
                });
    }

    // ── onDelete (spec §2): fn_routes cascades with the function row ───────

    @Test
    void deleteFunctionCascadesItsPublicRoutesToo() {
        String appId = persistApplication("f1del");
        String host = "f1del-" + fresh() + ".example.com";
        persistVerifiedDomain(new FunctionOwner.Platform(), host);
        Function f = createFunction(appId, new FunctionOwner.Platform());
        var p = publish(f.address(), "f1del", manifestWithPublic("default", host, "/"));
        promote(f.address(), p.version().version());
        assertThat(routes.listByFunction(f.id())).hasSize(1);

        deleteFunction(f.address());

        assertThat(routes.listByFunction(f.id())).as("fn_routes_function_id_fkey ON DELETE CASCADE").isEmpty();
    }
}
