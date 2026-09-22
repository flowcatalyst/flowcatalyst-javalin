package io.flowcatalyst.stream;

import io.flowcatalyst.db.generated.tables.records.MsgDispatchJobsRecord;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static io.flowcatalyst.stream.StreamFixture.DB;
import static io.flowcatalyst.stream.StreamFixture.DS;
import static io.flowcatalyst.stream.StreamFixture.RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FanOut` (stream spec §3, `event_fan_out`) against a real embedded
/// Postgres. A very large claim batch size is used throughout (matching
/// `PendingJobPollerTest`'s idiom) since `TestPg` is shared and never
/// truncated (CONVENTIONS §6) — every assertion checks THIS test's own
/// seeded ids, never a table-wide count.
///
/// **The exclusive-claim test is the load-bearing one** ([#exclusiveClaim]):
/// it is mutation-checked by literally removing `FOR UPDATE SKIP LOCKED`
/// from [FanOut]'s claim SQL and re-running — see the class-level report for
/// the result.
class FanOutTest {

    private static final int BIG_BATCH = 200_000;
    private static final SubscriptionRepository SUBSCRIPTIONS = new SubscriptionRepository(DS);

    private static FanOut fanOut(java.util.function.Supplier<List<Subscription>> loader) {
        return new FanOut(DS, loader, Duration.ofSeconds(9999), Clock.systemUTC());
    }

    /// The one subscription (real DB row) as a fixed, constant loader — for
    /// tests that only care about the event-claim mechanics, not the
    /// subscription cache.
    private static java.util.function.Supplier<List<Subscription>> fixed(String... subscriptionIds) {
        var ids = List.of(subscriptionIds);
        List<Subscription> subs = SUBSCRIPTIONS.findActiveOrderedById().stream()
                .filter(s -> ids.contains(s.id())).toList();
        return () -> subs;
    }

    private static MsgDispatchJobsRecord jobFor(String eventId) {
        return DB.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.EVENT_ID.eq(eventId)).fetchOne();
    }

    private static Boolean fannedOutAt(String eventId) {
        var v = DB.select(MSG_EVENTS.FANNED_OUT_AT).from(MSG_EVENTS).where(MSG_EVENTS.ID.eq(eventId)).fetchOne();
        return v != null && v.value1() != null;
    }

    // ── Job shape (spec §3 table, every column) ─────────────────────────────

    @Test
    @DisplayName("every column of the job shape table is populated from the event and the subscription")
    void jobShapeEveryColumn() {
        String type = StreamFixture.type("shape");
        String subId = StreamFixture.subscription("shape", "https://example.test/shape-hook",
                DispatchMode.BLOCK_ON_ERROR, null, type);
        String poolId = Tsid.generate();
        String serviceAccountId = Tsid.generate();
        DB.update(MSG_SUBSCRIPTIONS)
                .set(MSG_SUBSCRIPTIONS.DISPATCH_POOL_ID, poolId)
                .set(MSG_SUBSCRIPTIONS.SEQUENCE, 7)
                .set(MSG_SUBSCRIPTIONS.TIMEOUT_SECONDS, 45)
                .set(MSG_SUBSCRIPTIONS.MAX_RETRIES, 9)
                .set(MSG_SUBSCRIPTIONS.SERVICE_ACCOUNT_ID, serviceAccountId)
                .set(MSG_SUBSCRIPTIONS.DATA_ONLY, false)
                .where(MSG_SUBSCRIPTIONS.ID.eq(subId))
                .execute();

        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String eventId = StreamFixture.event(type, "test://shape-source", "shape-subject", "{\"n\":1}",
                "corr-" + RUN, "grp-" + RUN, null, createdAt);

        int claimed = fanOut(fixed(subId)).step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var job = jobFor(eventId);
        assertThat(job).as("a job row for the seeded event").isNotNull();
        assertThat(job.getId()).as("untyped 13-char TSID").hasSize(13);
        assertThat(job.getCode()).isEqualTo(type);
        assertThat(job.getSource()).isEqualTo("test://shape-source");
        assertThat(job.getSubject()).isEqualTo("shape-subject");
        assertThat(job.getEventId()).isEqualTo(eventId);
        assertThat(job.getCorrelationId()).isEqualTo("corr-" + RUN);
        assertThat(job.getClientId()).isNull();
        assertThat(job.getMessageGroup()).isEqualTo("grp-" + RUN);
        assertThat(job.getPayload()).isEqualTo("{\"n\": 1}");
        assertThat(job.getTargetUrl()).isEqualTo("https://example.test/shape-hook");
        assertThat(job.getDataOnly()).isFalse();
        assertThat(job.getServiceAccountId()).isEqualTo(serviceAccountId);
        assertThat(job.getSubscriptionId()).isEqualTo(subId);
        assertThat(job.getDispatchPoolId()).isEqualTo(poolId);
        assertThat(job.getSequence()).isEqualTo(7);
        assertThat(job.getTimeoutSeconds()).isEqualTo(45);
        assertThat(job.getMaxRetries()).isEqualTo(9);
        assertThat(job.getMode()).isEqualTo("BLOCK_ON_ERROR");
        assertThat(job.getProtocol()).isEqualTo("HTTP_WEBHOOK");
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getIdempotencyKey()).isEqualTo(eventId + ":" + subId);
        assertThat(job.getCreatedAt().toInstant()).isEqualTo(createdAt);
        assertThat(job.getUpdatedAt().toInstant()).isEqualTo(createdAt);
    }

    /// T5 (dispatch-job-priority spec R2): a job raised from a
    /// `HIGH_PRIORITY` subscription carries that value verbatim on its own
    /// `queue` column; one raised from a subscription with no queue set
    /// carries none. Mutant: leave the column `null` and rely on the
    /// publish-time subscription lookup instead — the HIGH_PRIORITY
    /// assertion below must fail under it.
    @Test
    @DisplayName("the raising subscription's queue is copied verbatim onto the job")
    void subscriptionQueueCopiedVerbatimOntoTheJob() {
        String type = StreamFixture.type("queuecopy");
        String hiSubId = StreamFixture.subscription("queuecopy-hi", "https://example.test/hi", DispatchMode.IMMEDIATE,
                null, type);
        String loSubId = StreamFixture.subscription("queuecopy-lo", "https://example.test/lo", DispatchMode.IMMEDIATE,
                null, type);
        DB.update(MSG_SUBSCRIPTIONS).set(MSG_SUBSCRIPTIONS.QUEUE, "HIGH_PRIORITY")
                .where(MSG_SUBSCRIPTIONS.ID.eq(hiSubId)).execute();
        // loSubId's queue stays NULL — the "subscription has none set" case.

        String eventId = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        int claimed = fanOut(fixed(hiSubId, loSubId)).step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var jobs = DB.selectFrom(MSG_DISPATCH_JOBS).where(MSG_DISPATCH_JOBS.EVENT_ID.eq(eventId)).fetch();
        assertThat(jobs).hasSize(2);
        // Collectors.toMap rejects null values (Collectors.java:180) — a HashMap
        // tolerates the loSubId job's null queue, which is exactly what this pins.
        var byRaiser = new java.util.HashMap<String, String>();
        jobs.forEach(r -> byRaiser.put(r.getSubscriptionId(), r.getQueue()));
        assertThat(byRaiser.get(hiSubId)).as("the HIGH_PRIORITY subscription's job must carry that queue value")
                .isEqualTo("HIGH_PRIORITY");
        assertThat(byRaiser).as("a subscription with no queue set must write none onto its job")
                .containsEntry(loSubId, null);
    }

    @Test
    @DisplayName("an event with no data gets the JSON literal null as its payload")
    void emptyDataBecomesJsonNullLiteral() {
        String type = StreamFixture.type("nulldata");
        String subId = StreamFixture.subscription("nulldata", "https://example.test/hook", DispatchMode.IMMEDIATE,
                null, type);
        String eventId = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        fanOut(fixed(subId)).step(BIG_BATCH);

        assertThat(jobFor(eventId).getPayload()).isEqualTo("null");
    }

    // ── Matching (restated from subscription.md §3) ─────────────────────────

    @Test
    @DisplayName("a platform-wide subscription (no clientId) matches an event with any client, including none")
    void platformWideMatchesAnyClient() {
        String type = StreamFixture.type("platformwide");
        String subId = StreamFixture.subscription("platformwide", "https://example.test/hook",
                DispatchMode.IMMEDIATE, null, type);
        String withClient = StreamFixture.event(type, "test://src", null, null, null, null, Tsid.generate(),
                Instant.now());
        String noClient = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        fanOut(fixed(subId)).step(BIG_BATCH);

        assertThat(jobFor(withClient)).as("client-bearing event still matches a platform-wide subscription").isNotNull();
        assertThat(jobFor(noClient)).isNotNull();
    }

    @Test
    @DisplayName("a client-bound subscription matches only its own client; a client-less event never matches")
    void clientBoundMatchesOnlyItsClient() {
        String type = StreamFixture.type("clientbound");
        String myClient = Tsid.generate();
        String otherClient = Tsid.generate();
        String subId = StreamFixture.subscription("clientbound", "https://example.test/hook",
                DispatchMode.IMMEDIATE, myClient, type);
        String matching = StreamFixture.event(type, "test://src", null, null, null, null, myClient, Instant.now());
        String otherClientEvent = StreamFixture.event(type, "test://src", null, null, null, null, otherClient,
                Instant.now());
        String noClientEvent = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        fanOut(fixed(subId)).step(BIG_BATCH);

        assertThat(jobFor(matching)).as("same client").isNotNull();
        assertThat(jobFor(otherClientEvent)).as("a different client never matches").isNull();
        assertThat(jobFor(noClientEvent)).as("a client-less event never matches a client-bound subscription").isNull();
    }

    @Test
    @DisplayName("a subscription with no event-type patterns matches nothing")
    void noPatternsMatchesNothing() {
        String type = StreamFixture.type("nopatterns");
        String subId = StreamFixture.subscription("nopatterns", "https://example.test/hook", DispatchMode.IMMEDIATE,
                null); // no patterns at all
        String eventId = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        fanOut(fixed(subId)).step(BIG_BATCH);

        assertThat(fannedOutAt(eventId)).as("still claimed").isTrue();
        assertThat(jobFor(eventId)).as("but no job, since the subscription binds no patterns").isNull();
    }

    @Test
    @DisplayName("a wildcard segment matches any single segment, not partial words or extra segments")
    void wildcardSegmentMatching() {
        String app = "app" + RUN;
        String pattern = app + ":*:thing:created";
        String matchingType = app + ":anysubdomain:thing:created";
        String wrongSegmentCount = app + ":x:thing:created:extra";
        String subId = StreamFixture.subscription("wildcard", "https://example.test/hook", DispatchMode.IMMEDIATE,
                null, pattern);
        String matchEvent = StreamFixture.event(matchingType, "test://src", null, null, null, null, null, Instant.now());
        String noMatchEvent = StreamFixture.event(wrongSegmentCount, "test://src", null, null, null, null, null,
                Instant.now());

        fanOut(fixed(subId)).step(BIG_BATCH);

        assertThat(jobFor(matchEvent)).isNotNull();
        assertThat(jobFor(noMatchEvent)).isNull();
    }

    // ── Zero subscriptions (spec §3 step 1, D1/D2) ──────────────────────────

    @Test
    @DisplayName("with zero (loader-scoped) subscriptions, events are stamped and no job is created")
    void zeroSubscriptionsStampsWithNoJobs() {
        String type = StreamFixture.type("zerosubs");
        String eventId = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());

        int claimed = fanOut(List::of).step(BIG_BATCH);

        assertThat(claimed).isGreaterThanOrEqualTo(1);
        assertThat(fannedOutAt(eventId)).isTrue();
        assertThat(jobFor(eventId)).isNull();
    }

    // ── Subscription cache TTL and reload-failure behaviour (spec §3) ───────

    @Test
    @DisplayName("a subscription activated after the first load is not applied until the TTL lapses")
    void cacheTtlDelaysNewSubscriptions() {
        String type = StreamFixture.type("ttl");
        String subId = StreamFixture.subscription("ttl", "https://example.test/hook", DispatchMode.IMMEDIATE, null,
                type);
        List<Subscription> empty = List.of();
        List<Subscription> withSub = SUBSCRIPTIONS.findActiveOrderedById().stream()
                .filter(s -> s.id().equals(subId)).toList();
        assertThat(withSub).hasSize(1);

        AtomicInteger loads = new AtomicInteger();
        AtomicReference<List<Subscription>> current = new AtomicReference<>(empty);
        java.util.function.Supplier<List<Subscription>> loader = () -> {
            loads.incrementAndGet();
            return current.get();
        };
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        Duration ttl = Duration.ofSeconds(30);
        var fanOut = new FanOut(DS, loader, ttl, clock);

        // Trigger the first load with an empty claim (LIMIT 0): no event is
        // consumed, only the cache is populated.
        fanOut.step(0);
        assertThat(loads.get()).isEqualTo(1);

        // "Activate" subB — but the cache has already loaded and the TTL has not lapsed.
        current.set(withSub);
        String beforeTtl = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());
        fanOut.step(BIG_BATCH);
        assertThat(loads.get()).as("no reload before the TTL lapses").isEqualTo(1);
        assertThat(fannedOutAt(beforeTtl)).as("still claimed").isTrue();
        assertThat(jobFor(beforeTtl)).as("the new subscription is not yet visible").isNull();

        // Lapse the TTL and try again with a fresh event.
        clock.advance(ttl.plusSeconds(1));
        String afterTtl = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());
        fanOut.step(BIG_BATCH);
        assertThat(loads.get()).as("exactly one reload, once the TTL lapses").isEqualTo(2);
        assertThat(jobFor(afterTtl)).as("now visible").isNotNull();
    }

    @Test
    @DisplayName("a reload failure keeps serving the previous cache instead of failing the step")
    void reloadFailureKeepsPreviousCache() {
        String type = StreamFixture.type("reloadfail");
        String subId = StreamFixture.subscription("reloadfail", "https://example.test/hook", DispatchMode.IMMEDIATE,
                null, type);
        List<Subscription> withSub = SUBSCRIPTIONS.findActiveOrderedById().stream()
                .filter(s -> s.id().equals(subId)).toList();

        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<List<Subscription>> loader = () -> {
            if (calls.incrementAndGet() == 1) return withSub;
            throw new RuntimeException("subscription store unavailable");
        };
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        Duration ttl = Duration.ofSeconds(10);
        var fanOut = new FanOut(DS, loader, ttl, clock);

        fanOut.step(0); // first, successful load
        clock.advance(ttl.plusSeconds(1)); // lapse the TTL so the next step reloads (and fails)

        String eventId = StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now());
        int claimed = fanOut.step(BIG_BATCH); // reload throws; must keep serving withSub, not fail the step

        assertThat(claimed).isGreaterThanOrEqualTo(1);
        assertThat(jobFor(eventId)).as("the stale-but-valid cache is still applied").isNotNull();
    }

    @Test
    @DisplayName("a first-load failure fails the step")
    void firstLoadFailureFailsTheStep() {
        java.util.function.Supplier<List<Subscription>> alwaysThrows = () -> {
            throw new RuntimeException("subscription store unavailable");
        };
        var fanOut = new FanOut(DS, alwaysThrows, Duration.ofSeconds(5), Clock.systemUTC());

        assertThatThrownBy(() -> fanOut.step(BIG_BATCH)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("subscription store unavailable");
    }

    // ── Exclusive claim (mutation-checked) and rollback ─────────────────────

    @Test
    @DisplayName("two concurrent steps never both claim the same event; exactly one job per (event, subscription)")
    void exclusiveClaim() throws Exception {
        String type = StreamFixture.type("exclusive");
        String subId = StreamFixture.subscription("exclusive", "https://example.test/hook", DispatchMode.IMMEDIATE,
                null, type);
        int n = 24;
        List<String> eventIds = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            eventIds.add(StreamFixture.event(type, "test://src", null, null, null, null, null, Instant.now()));
        }

        var loader = fixed(subId);
        FanOut a = fanOut(loader);
        FanOut b = fanOut(loader);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Integer> task = () -> {
            barrier.await();
            return a.step(BIG_BATCH);
        };
        Callable<Integer> task2 = () -> {
            barrier.await();
            return b.step(BIG_BATCH);
        };

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Integer> f1 = pool.submit(task);
            Future<Integer> f2 = pool.submit(task2);
            int claimedA = f1.get();
            int claimedB = f2.get();

            // No event was left unclaimed by both.
            for (String eventId : eventIds) {
                assertThat(fannedOutAt(eventId)).as("event " + eventId + " must be claimed by exactly one step")
                        .isTrue();
            }
            // No (event, subscription) pair has more than one job — the safety property SKIP LOCKED exists for.
            for (String eventId : eventIds) {
                int jobCount = DB.selectCount().from(MSG_DISPATCH_JOBS)
                        .where(MSG_DISPATCH_JOBS.EVENT_ID.eq(eventId))
                        .and(MSG_DISPATCH_JOBS.SUBSCRIPTION_ID.eq(subId))
                        .fetchOne(0, int.class);
                assertThat(jobCount).as("exactly one job for (event, subscription) " + eventId).isEqualTo(1);
            }
            assertThat(claimedA + claimedB).as("the two steps' claims are disjoint and cover every event")
                    .isGreaterThanOrEqualTo(n);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("an insert failure rolls back the whole claim: fanned_out_at stays NULL")
    void insertFailureRollsBackTheClaim() throws Exception {
        String forceFailCode = "forcefail-" + RUN;
        String constraintName = "fanout_test_force_fail_" + RUN;
        DB.execute("ALTER TABLE msg_dispatch_jobs ADD CONSTRAINT " + constraintName
                + " CHECK (code <> '" + forceFailCode + "')");
        try {
            String subId = StreamFixture.subscription("forcefail", "https://example.test/hook",
                    DispatchMode.IMMEDIATE, null, forceFailCode);
            String eventId = StreamFixture.event(forceFailCode, "test://src", null, null, null, null, null,
                    Instant.now());

            assertThatThrownBy(() -> fanOut(fixed(subId)).step(BIG_BATCH)).isInstanceOf(RuntimeException.class);

            assertThat(fannedOutAt(eventId)).as("the claim's UPDATE must roll back with the failed insert").isFalse();
            assertThat(jobFor(eventId)).isNull();
        } finally {
            DB.execute("ALTER TABLE msg_dispatch_jobs DROP CONSTRAINT " + constraintName);
        }
    }

    // ── T11 (catch-up-2026-09-22.md C1): descriptor + metadata at fan-out ──

    /// The raised job's `descriptor` is the raising subscription's NAME
    /// (never its code), trimmed. Mutant: use the subscription's code
    /// instead of its name — this must fail under it, since [StreamFixture#subscription]
    /// gives the two different values (`fanout-<tag>-<RUN>` vs `<tag>`).
    @Test
    @DisplayName("descriptor is the raising subscription's name")
    void descriptorIsTheRaisingSubscriptionsName() {
        String type = StreamFixture.type("descriptor");
        String subId = StreamFixture.subscription("Notify Value of user logins", "https://example.test/descriptor-hook",
                DispatchMode.IMMEDIATE, null, type);

        String eventId = StreamFixture.event(type, "test://descriptor-source", null, "{}", null, null, null,
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        int claimed = fanOut(fixed(subId)).step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var job = jobFor(eventId);
        assertThat(job.getDescriptor()).as("the subscription's NAME, not its code")
                .isEqualTo("Notify Value of user logins");
    }

    /// A subscription whose name is blank/whitespace-only raises a job with
    /// `descriptor = NULL` — absent, not an empty string (spec: "nil is the
    /// legacy state"). Mutant: store the blank string verbatim.
    @Test
    @DisplayName("a blank subscription name raises a job with no descriptor")
    void blankSubscriptionNameRaisesNoDescriptor() {
        String type = StreamFixture.type("blankdescriptor");
        String subId = StreamFixture.subscription("blankdescriptor", "https://example.test/blankdescriptor-hook",
                DispatchMode.IMMEDIATE, null, type);
        DB.update(MSG_SUBSCRIPTIONS).set(MSG_SUBSCRIPTIONS.NAME, "   ").where(MSG_SUBSCRIPTIONS.ID.eq(subId)).execute();

        String eventId = StreamFixture.event(type, "test://blankdescriptor-source", null, "{}", null, null, null,
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        int claimed = fanOut(fixed(subId)).step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        assertThat(jobFor(eventId).getDescriptor()).isNull();
    }

    /// The raised job's `metadata` is the raising event's `context_data`,
    /// copied VERBATIM — same `[{key,value}]` shape. Mutant: drop the copy
    /// (job metadata stays `[]` even though the event carries entries).
    @Test
    @DisplayName("metadata copies the event's context_data verbatim")
    void metadataCopiesTheEventsContextDataVerbatim() {
        String type = StreamFixture.type("metadata");
        String subId = StreamFixture.subscription("metadata", "https://example.test/metadata-hook",
                DispatchMode.IMMEDIATE, null, type);
        String id = Tsid.generate();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DB.insertInto(MSG_EVENTS)
                .set(MSG_EVENTS.ID, id)
                .set(MSG_EVENTS.TYPE, type)
                .set(MSG_EVENTS.SOURCE, "test://metadata-source")
                .set(MSG_EVENTS.TIME, createdAt.atOffset(ZoneOffset.UTC))
                .set(MSG_EVENTS.DATA, org.jooq.JSONB.valueOf("{}"))
                .set(MSG_EVENTS.CONTEXT_DATA, org.jooq.JSONB.valueOf("[{\"key\":\"tenant\",\"value\":\"acme\"}]"))
                .set(MSG_EVENTS.CREATED_AT, createdAt.atOffset(ZoneOffset.UTC))
                .execute();

        int claimed = fanOut(fixed(subId)).step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var job = jobFor(id);
        assertThat(job.getMetadata().data()).as("the event's context_data, verbatim")
                .isEqualTo("[{\"key\": \"tenant\", \"value\": \"acme\"}]");
    }

    /// An event with no `context_data` raises a job with `metadata = []`,
    /// never `NULL` (the column is `NOT NULL DEFAULT '[]'`). Mutant: leave
    /// the column unset / write literal `NULL`.
    @Test
    @DisplayName("metadata is an empty array when the event carries no context_data")
    void metadataIsEmptyArrayWhenEventHasNoContextData() {
        String type = StreamFixture.type("nometadata");
        String subId = StreamFixture.subscription("nometadata", "https://example.test/nometadata-hook",
                DispatchMode.IMMEDIATE, null, type);
        String eventId = StreamFixture.event(type, "test://nometadata-source", null, "{}", null, null, null,
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        int claimed = fanOut(fixed(subId)).step(BIG_BATCH);
        assertThat(claimed).isGreaterThanOrEqualTo(1);

        var job = jobFor(eventId);
        assertThat(job.getMetadata()).isNotNull();
        assertThat(job.getMetadata().data()).isEqualTo("[]");
    }

    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
