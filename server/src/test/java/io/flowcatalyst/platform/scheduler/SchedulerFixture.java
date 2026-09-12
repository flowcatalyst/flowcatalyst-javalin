package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.sdk.tsid.Tsid;

import static io.flowcatalyst.db.generated.Tables.MSG_CONNECTIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_POOLS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;

/// Seeds `tnt_clients` / `msg_dispatch_pools` / `msg_connections` /
/// `msg_subscriptions` rows for the scheduler's tests — none of which
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobFixture] covers, since
/// the dispatch-job read/list unit never needed pool-code resolution or the
/// paused-connection filter. Reuses that fixture's shared [DS] / [DB] so
/// every scheduler test runs against the one embedded database TestPg
/// already migrated.
final class SchedulerFixture {

    static final javax.sql.DataSource DATA_SOURCE = DS;

    private SchedulerFixture() {
    }

    /// A client with `identifier` — no FK to anything, so an arbitrary
    /// unused id is fine wherever one is needed but not asserted on.
    static String client(String identifier) {
        String id = Tsid.generate();
        DB.insertInto(TNT_CLIENTS)
                .set(TNT_CLIENTS.ID, id)
                .set(TNT_CLIENTS.NAME, identifier)
                .set(TNT_CLIENTS.IDENTIFIER, identifier)
                .execute();
        return id;
    }

    /// A dispatch pool coded `code`; `clientId`/`clientIdentifier` both
    /// `null` makes it platform-level.
    static String pool(String code, String clientId, String clientIdentifier) {
        String id = Tsid.generate();
        DB.insertInto(MSG_DISPATCH_POOLS)
                .set(MSG_DISPATCH_POOLS.ID, id)
                .set(MSG_DISPATCH_POOLS.CODE, code)
                .set(MSG_DISPATCH_POOLS.NAME, code)
                .set(MSG_DISPATCH_POOLS.CLIENT_ID, clientId)
                .set(MSG_DISPATCH_POOLS.CLIENT_IDENTIFIER, clientIdentifier)
                .execute();
        return id;
    }

    /// A connection with the given `status` (`ACTIVE` / `PAUSED`).
    /// `service_account_id` is NOT NULL with no FK enforcing it — an unused
    /// placeholder id satisfies the column. `code` is lowercase — see
    /// [#subscription] on why a raw (uppercase) TSID is unsafe there.
    static String connection(String status) {
        String id = Tsid.generate();
        String code = "schedfx-conn-" + id.toLowerCase(java.util.Locale.ROOT);
        DB.insertInto(MSG_CONNECTIONS)
                .set(MSG_CONNECTIONS.ID, id)
                .set(MSG_CONNECTIONS.CODE, code)
                .set(MSG_CONNECTIONS.NAME, code)
                .set(MSG_CONNECTIONS.SERVICE_ACCOUNT_ID, Tsid.generate())
                .set(MSG_CONNECTIONS.STATUS, status)
                .execute();
        return id;
    }

    /// A subscription targeting `connectionId` — the join
    /// [PausedConnectionCache] reads. `code` is lowercase: `msg_subscriptions`
    /// is a table `SubscriptionApiTest`'s unscoped, sort-order-asserting list
    /// endpoint also reads, and [Tsid#generate] is uppercase Crockford
    /// Base32 — sorting differently under Postgres's default collation than
    /// under Java's `String.compareTo` broke that other test the first time
    /// this fixture used the raw TSID as the code.
    static String subscription(String connectionId) {
        String id = Tsid.generate();
        String code = "schedfx-sub-" + id.toLowerCase(java.util.Locale.ROOT);
        DB.insertInto(MSG_SUBSCRIPTIONS)
                .set(MSG_SUBSCRIPTIONS.ID, id)
                .set(MSG_SUBSCRIPTIONS.CODE, code)
                .set(MSG_SUBSCRIPTIONS.NAME, code)
                .set(MSG_SUBSCRIPTIONS.TARGET, "https://hook.example/" + id)
                .set(MSG_SUBSCRIPTIONS.CONNECTION_ID, connectionId)
                .execute();
        return id;
    }

    /// A bare subscription (no connection — [PausedConnectionCache] is not
    /// under test here) carrying `queue`, for
    /// [SubscriptionPriorityCache]/[SqsDispatchPublisher] tests that only
    /// need `msg_subscriptions.queue` to exist against a real subscription
    /// id. `queue` may be `null` (the "nothing has ever written it" case,
    /// ruling R6) or any raw stored value, including legacy junk.
    static String subscriptionWithQueue(String queue) {
        String id = Tsid.generate();
        String code = "schedfx-subq-" + id.toLowerCase(java.util.Locale.ROOT);
        DB.insertInto(MSG_SUBSCRIPTIONS)
                .set(MSG_SUBSCRIPTIONS.ID, id)
                .set(MSG_SUBSCRIPTIONS.CODE, code)
                .set(MSG_SUBSCRIPTIONS.NAME, code)
                .set(MSG_SUBSCRIPTIONS.TARGET, "https://hook.example/" + id)
                .set(MSG_SUBSCRIPTIONS.QUEUE, queue)
                .execute();
        return id;
    }
}
