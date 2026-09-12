package io.flowcatalyst.platform.dispatch;

import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_POOLS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;

/// Seeds `msg_dispatch_pools` / `msg_subscriptions` rows directly for
/// [RouterConfigDocumentBuilderTest] — neither table has a foreign key to
/// `tnt_clients` (same observation as
/// [io.flowcatalyst.platform.scheduler.SchedulerFixture]), so an arbitrary
/// client id is fine wherever it is not itself asserted on. Shares the one
/// embedded TestPg database; no truncation between tests, so every id and
/// code carries [#RUN] to stay out of other tests' way.
final class RouterConfigFixture {

    static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);

    /// Per-JVM namespace, lower-case so pool/subscription codes stay
    /// consistent with the collation note in `SchedulerFixture`.
    static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private RouterConfigFixture() {
    }

    /// A dispatch pool; `clientId`/`clientIdentifier` both `null` makes it
    /// platform-level.
    static String pool(String code, String clientId, String clientIdentifier, String status, int concurrency,
                        Integer rateLimit) {
        String id = Tsid.generate();
        DB.insertInto(MSG_DISPATCH_POOLS)
                .set(MSG_DISPATCH_POOLS.ID, id)
                .set(MSG_DISPATCH_POOLS.CODE, code)
                .set(MSG_DISPATCH_POOLS.NAME, code)
                .set(MSG_DISPATCH_POOLS.CLIENT_ID, clientId)
                .set(MSG_DISPATCH_POOLS.CLIENT_IDENTIFIER, clientIdentifier)
                .set(MSG_DISPATCH_POOLS.STATUS, status)
                .set(MSG_DISPATCH_POOLS.CONCURRENCY, concurrency)
                .set(MSG_DISPATCH_POOLS.RATE_LIMIT, rateLimit)
                .execute();
        return id;
    }

    /// An `ACTIVE` (unless `status` says otherwise) subscription with
    /// `queue`; `clientId`/`clientIdentifier` both `null` makes it
    /// platform-wide.
    static String subscription(String clientId, String clientIdentifier, String queue, String status) {
        String id = Tsid.generate();
        String code = "rcfx-sub-" + id.toLowerCase(Locale.ROOT);
        DB.insertInto(MSG_SUBSCRIPTIONS)
                .set(MSG_SUBSCRIPTIONS.ID, id)
                .set(MSG_SUBSCRIPTIONS.CODE, code)
                .set(MSG_SUBSCRIPTIONS.NAME, code)
                .set(MSG_SUBSCRIPTIONS.TARGET, "https://hook.example/" + id)
                .set(MSG_SUBSCRIPTIONS.CLIENT_ID, clientId)
                .set(MSG_SUBSCRIPTIONS.CLIENT_IDENTIFIER, clientIdentifier)
                .set(MSG_SUBSCRIPTIONS.QUEUE, queue)
                .set(MSG_SUBSCRIPTIONS.STATUS, status)
                .execute();
        return id;
    }
}
