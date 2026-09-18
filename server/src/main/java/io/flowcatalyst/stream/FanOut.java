package io.flowcatalyst.stream;

import io.flowcatalyst.db.generated.tables.MsgDispatchJobs;
import io.flowcatalyst.db.generated.tables.MsgEvents;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.stream.jfr.FanOutBatchEvent;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;

/// `event_fan_out` (stream spec §3): claims unfanned `msg_events` rows and,
/// for every claimed event matched against the cached `ACTIVE` subscriptions,
/// inserts one `msg_dispatch_jobs` row per match. One [Projector.Step] call
/// is one claim-and-insert transaction ([StreamTx]); any failure rolls the
/// whole batch back so the events are retried next step.
public final class FanOut implements Projector.Step {

    private static final Logger LOG = LoggerFactory.getLogger(FanOut.class);

    private static final MsgEvents E = MSG_EVENTS;
    private static final MsgDispatchJobs D = MSG_DISPATCH_JOBS;

    /// The exclusive-claim statement (stream spec §3 step 2): one `UPDATE …
    /// FROM (SELECT … FOR UPDATE SKIP LOCKED)` so two concurrent steps can
    /// never claim the same row — a locked row is skipped by the other
    /// transaction's `SELECT`, not raced. Keyed `(id, created_at)` because
    /// `msg_events` is partitioned on `created_at`.
    private static final String CLAIM_SQL = """
            UPDATE msg_events e
            SET fanned_out_at = now()
            FROM (
                SELECT id, created_at FROM msg_events
                WHERE fanned_out_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            ) claim
            WHERE e.id = claim.id AND e.created_at = claim.created_at
            RETURNING e.id, e.type, e.source, e.subject, e.data::text AS data, e.correlation_id,
                      e.message_group, e.client_id, e.created_at
            """;

    private final DataSource dataSource;
    private final Supplier<List<Subscription>> subscriptionLoader;
    private final Duration subscriptionTtl;
    private final Clock clock;

    /// `null` until the first successful load; a first-load failure
    /// propagates (spec §3 "only a first-load failure fails the step").
    private volatile List<Subscription> cache;
    private volatile Instant cacheLoadedAt = Instant.MIN;

    /// `subscriptionLoader` is a plain supplier — not the concrete
    /// `SubscriptionRepository` — so a test can substitute a scoped or
    /// failing loader without needing distinct global subscription state on
    /// the shared, never-truncated test database (CONVENTIONS §6). Production
    /// wiring ([StreamProcessor]) passes `repository::findActiveOrderedById`.
    public FanOut(DataSource dataSource, Supplier<List<Subscription>> subscriptionLoader, Duration subscriptionTtl,
                  Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.subscriptionLoader = Objects.requireNonNull(subscriptionLoader, "subscriptionLoader");
        this.subscriptionTtl = Objects.requireNonNull(subscriptionTtl, "subscriptionTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int step(int batchSize) {
        List<Subscription> subs = loadSubscriptions();
        boolean noSubscriptions = subs.isEmpty();
        // A one-element holder so the FanOutResult (event fields) survives
        // StreamTx.run's ToIntFunction<DbTx> contract, which only returns the
        // int Projector.Step needs; the flight-recorder event is committed
        // below, outside the transaction, once it is known to have committed.
        FanOutResult[] captured = new FanOutResult[1];
        int eventsClaimed = StreamTx.run(dataSource, tx -> {
            FanOutResult result = noSubscriptions
                    ? claimNoSubscriptions(tx, batchSize)
                    : claimAndFanOut(tx, subs, batchSize);
            captured[0] = result;
            return result.eventsClaimed();
        });
        if (eventsClaimed > 0) {
            recordBatch(captured[0], subs.size(), noSubscriptions);
        }
        return eventsClaimed;
    }

    /// Records the batch, if anyone is recording (`docs/spec/jfr-events.md`
    /// §1). `shouldCommit()` first so a disabled recording costs one virtual
    /// call and no field writes.
    private static void recordBatch(FanOutResult result, int subscriptions, boolean noSubscriptions) {
        var event = new FanOutBatchEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.eventsClaimed = result.eventsClaimed();
        event.jobsInserted = result.jobsInserted();
        event.subscriptions = subscriptions;
        event.noSubscriptions = noSubscriptions;
        event.commit();
    }

    /// `claimAndFanOut`/`claimNoSubscriptions`'s result: events claimed
    /// always matters to [Projector.Step]; jobs inserted only matters to the
    /// flight-recorder event, so it rides along rather than needing its own
    /// return path.
    private record FanOutResult(int eventsClaimed, int jobsInserted) {
    }

    /// Reloads the cache when its TTL has lapsed; keeps the previous cache on
    /// a reload failure (spec §3 "a reload failure keeps the previous cache
    /// if there was one").
    private List<Subscription> loadSubscriptions() {
        Instant now = clock.instant();
        List<Subscription> current = cache;
        if (current != null && now.isBefore(cacheLoadedAt.plus(subscriptionTtl))) {
            return current;
        }
        try {
            List<Subscription> fresh = subscriptionLoader.get();
            cache = fresh;
            cacheLoadedAt = now;
            return fresh;
        } catch (RuntimeException e) {
            if (current != null) {
                LOG.warn("subscription cache reload failed; keeping previous cache", e);
                return current;
            }
            throw e;
        }
    }

    /// Stream spec §3 step 1: with zero cached subscriptions, stamp the
    /// oldest `batchSize` unfanned events and discard them — no jobs, no
    /// `SKIP LOCKED` (spec D1/D2).
    private static FanOutResult claimNoSubscriptions(DbTx tx, int batchSize) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        List<String> ids = txDsl.select(E.ID).from(E).where(E.FANNED_OUT_AT.isNull())
                .orderBy(E.CREATED_AT.asc()).limit(batchSize).fetch(E.ID);
        if (ids.isEmpty()) return new FanOutResult(0, 0);
        int updated = txDsl.update(E).set(E.FANNED_OUT_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(E.ID.in(ids)).execute();
        return new FanOutResult(updated, 0);
    }

    /// Stream spec §3 steps 2-4: claim, match, insert, return the number of
    /// **events** claimed (not jobs) alongside how many jobs were inserted.
    private static FanOutResult claimAndFanOut(DbTx tx, List<Subscription> subs, int batchSize) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        List<ClaimedEvent> claimed = claim(txDsl, batchSize);
        if (claimed.isEmpty()) return new FanOutResult(0, 0);

        var insert = txDsl.insertInto(D, D.ID, D.CODE, D.SOURCE, D.SUBJECT, D.EVENT_ID, D.CORRELATION_ID,
                D.CLIENT_ID, D.MESSAGE_GROUP, D.PAYLOAD, D.TARGET_URL, D.DATA_ONLY, D.SERVICE_ACCOUNT_ID,
                D.SUBSCRIPTION_ID, D.DISPATCH_POOL_ID, D.SEQUENCE, D.TIMEOUT_SECONDS, D.MAX_RETRIES, D.MODE,
                D.PROTOCOL, D.STATUS, D.IDEMPOTENCY_KEY, D.QUEUE, D.CREATED_AT, D.UPDATED_AT);
        int jobCount = 0;
        for (ClaimedEvent event : claimed) {
            for (Subscription sub : subs) {
                if (!sub.matchesClient(event.clientId()) || !sub.matchesEventType(event.type())) {
                    continue;
                }
                OffsetDateTime createdAt = event.createdAt().atOffset(ZoneOffset.UTC);
                // The raising subscription's queue is copied verbatim onto the job
                // (dispatch-job-priority spec R2) — including `null`, and including
                // legacy text the read side alone tolerates (spec R4).
                insert = insert.values(Tsid.generate(), event.type(), event.source(), event.subject(), event.id(),
                        event.correlationId(), event.clientId(), event.messageGroup(), payloadOf(event.data()),
                        sub.endpoint(), sub.dataOnly(), sub.serviceAccountId(), sub.id(), sub.dispatchPoolId(),
                        sub.sequence(), sub.timeoutSeconds(), sub.maxRetries(), sub.mode().name(), "HTTP_WEBHOOK",
                        "PENDING", event.id() + ":" + sub.id(), sub.queue(), createdAt, createdAt);
                jobCount++;
            }
        }
        if (jobCount > 0) {
            insert.onConflict(D.ID, D.CREATED_AT).doNothing().execute();
        }
        return new FanOutResult(claimed.size(), jobCount);
    }

    /// The event's `data` as text, or the JSON literal `null` when the
    /// column is empty (spec §3 job shape table).
    private static String payloadOf(String data) {
        return data == null ? "null" : data;
    }

    private static List<ClaimedEvent> claim(DSLContext txDsl, int batchSize) {
        return txDsl.fetch(CLAIM_SQL, batchSize).stream()
                .map(r -> new ClaimedEvent(
                        r.get("id", String.class),
                        r.get("type", String.class),
                        r.get("source", String.class),
                        r.get("subject", String.class),
                        r.get("data", String.class),
                        r.get("correlation_id", String.class),
                        r.get("message_group", String.class),
                        r.get("client_id", String.class),
                        r.get("created_at", OffsetDateTime.class).toInstant()))
                .toList();
    }

    private record ClaimedEvent(String id, String type, String source, String subject, String data,
                                 String correlationId, String messageGroup, String clientId, Instant createdAt) {
    }
}
