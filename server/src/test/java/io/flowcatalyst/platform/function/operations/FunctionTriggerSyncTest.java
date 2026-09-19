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
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
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
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final Signatures OFF = new Signatures.Off();
    private static final PoolUrlTemplate POOL_URL = PoolUrlTemplate.parse("http://fn-{pool}:8080");
    private static final FunctionTriggerSync SYNC = new FunctionTriggerSync(subscriptions, pools, jobs, eventTypes,
            triggerObjects, applications, serviceAccounts, versions, DEFAULTS, POOL_URL);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "usr_fts_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final AuthContext ANCHOR =
            new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io", List.of("*"), List.of(), List.of(), true, List.of());
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
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
        var digest = "sha256:" + "0".repeat(63) + (digestSuffix.hashCode() & 0xF);
        var cmd = new PublishCommand(address, "oci://artifact/" + digestSuffix, sha256(digestSuffix), null, manifest);
        return Auth.runAs(ANCHOR, () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, SYNC).run(uow, cmd, EC));
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
        markReady(address, version);
        return Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, SYNC)
                .run(uow, new PromoteCommand(address, Function.LIVE, version), EC));
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

    private static String sched(String cron, String path) {
        return "{\"cron\":\"" + cron + "\",\"path\":\"" + path + "\"}";
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
                applications, serviceAccounts, versions, tight, POOL_URL);

        // First warm function in this pool: fits exactly at the cap of 1.
        Function first = createFunction(appId, new FunctionOwner.Platform());
        JsonNode warmManifest = manifest(pool.value(), true, null, List.of(), List.of());
        var cmd1 = new PublishCommand(first.address(), "oci://artifact/w1", sha256("w1"), null, warmManifest);
        FunctionVersion v1 = Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync).run(uow, cmd1, EC)).version();
        markReady(first.address(), v1.version());
        Auth.runAs(ANCHOR, () -> PromoteVersion.of(functions, versions, tightSync)
                .run(uow, new PromoteCommand(first.address(), Function.LIVE, v1.version()), EC));

        // A second warm function in the SAME pool now exceeds the cap of 1.
        var cmd2 = new PublishCommand(f.address(), "oci://artifact/w2", sha256("w2"), null, warmManifest);
        assertThatThrownBy(() -> Auth.runAs(ANCHOR,
                () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, tightSync).run(uow, cmd2, EC)))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).code()).isEqualTo("WARM_CAPACITY_EXCEEDED");
        assertThat(versions.listByFunction(f.id())).isEmpty();
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
}
