package io.flowcatalyst.stream;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTION_EVENT_TYPES;

/// Seeds `msg_events` / `msg_subscriptions` / `msg_subscription_event_types`
/// / `msg_dispatch_jobs` rows directly, the raw row shapes stream spec §3-§5
/// document, so `FanOutTest` / `EventProjectionTest` / `DispatchJobProjectionTest`
/// each get full control of `created_at`/`client_id` without going through
/// the use-case envelope. Each test class's [#RUN] namespace keeps it from
/// ever seeing another class's rows on the shared, never-truncated
/// [TestPg] database (CONVENTIONS §6).
final class StreamFixture {

    static final DataSource DS = TestPg.dataSource();
    static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private StreamFixture() {
    }

    /// An `ACTIVE`, platform-wide (unless `clientId` given) subscription
    /// matching every pattern in `eventTypePatterns`.
    static String subscription(String codeTag, String endpoint, DispatchMode mode, String clientId,
                                String... eventTypePatterns) {
        String id = EntityType.SUBSCRIPTION.generate();
        DB.insertInto(MSG_SUBSCRIPTIONS)
                .set(MSG_SUBSCRIPTIONS.ID, id)
                .set(MSG_SUBSCRIPTIONS.CODE, "fanout-" + codeTag + "-" + RUN)
                .set(MSG_SUBSCRIPTIONS.NAME, codeTag)
                .set(MSG_SUBSCRIPTIONS.CLIENT_ID, clientId)
                .set(MSG_SUBSCRIPTIONS.TARGET, endpoint)
                .set(MSG_SUBSCRIPTIONS.STATUS, "ACTIVE")
                .set(MSG_SUBSCRIPTIONS.MODE, mode.name())
                .execute();
        for (String pattern : eventTypePatterns) {
            DB.insertInto(MSG_SUBSCRIPTION_EVENT_TYPES)
                    .set(MSG_SUBSCRIPTION_EVENT_TYPES.SUBSCRIPTION_ID, id)
                    .set(MSG_SUBSCRIPTION_EVENT_TYPES.EVENT_TYPE_CODE, pattern)
                    .execute();
        }
        return id;
    }

    /// A raw `msg_events` row, `fanned_out_at`/`projected_at` both `NULL`.
    static String event(String type, String source, String subject, String data, String correlationId,
                         String messageGroup, String clientId, Instant createdAt) {
        String id = Tsid.generate();
        DB.insertInto(MSG_EVENTS)
                .set(MSG_EVENTS.ID, id)
                .set(MSG_EVENTS.TYPE, type)
                .set(MSG_EVENTS.SOURCE, source)
                .set(MSG_EVENTS.SUBJECT, subject)
                .set(MSG_EVENTS.TIME, createdAt.atOffset(ZoneOffset.UTC))
                .set(MSG_EVENTS.DATA, data == null ? null : org.jooq.JSONB.valueOf(data))
                .set(MSG_EVENTS.CORRELATION_ID, correlationId)
                .set(MSG_EVENTS.MESSAGE_GROUP, messageGroup)
                .set(MSG_EVENTS.CLIENT_ID, clientId)
                .set(MSG_EVENTS.CREATED_AT, createdAt.atOffset(ZoneOffset.UTC))
                .execute();
        return id;
    }

    /// `<type><RUN>` with four colon-separated segments, unique to the caller's tag.
    static String type(String tag) {
        return "app" + RUN + ":" + tag + ":thing:created";
    }

    /// A raw, minimal `msg_dispatch_jobs` row (write side) — for
    /// `DispatchJobProjectionTest`, which projects from here rather than
    /// from fan-out output.
    static String dispatchJob(String code, String status, Instant createdAt, Instant updatedAt) {
        String id = Tsid.generate();
        DB.insertInto(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.ID, id)
                .set(MSG_DISPATCH_JOBS.CODE, code)
                .set(MSG_DISPATCH_JOBS.TARGET_URL, "https://example.test/hook")
                .set(MSG_DISPATCH_JOBS.STATUS, status)
                .set(MSG_DISPATCH_JOBS.CREATED_AT, createdAt.atOffset(ZoneOffset.UTC))
                .set(MSG_DISPATCH_JOBS.UPDATED_AT, updatedAt.atOffset(ZoneOffset.UTC))
                .execute();
        return id;
    }
}
