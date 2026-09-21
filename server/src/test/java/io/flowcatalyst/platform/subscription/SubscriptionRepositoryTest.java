package io.flowcatalyst.platform.subscription;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// X-06: a corrupt `status`/`source` column fails the read loudly instead
/// of defaulting silently (spec §1).
///
/// `SubscriptionApiTest` lists platform-wide subscriptions unfiltered, so a
/// corrupt row left behind here (these rows carry no `clientId`) would fail
/// that too (`TestPg` never truncates between tests). [#cleanup] deletes
/// everything this class inserts.
class SubscriptionRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final SubscriptionRepository repo = new SubscriptionRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> INSERTED = new ArrayList<>();

    private static String insert(String applicationCode, String status, String source) {
        String id = EntityType.SUBSCRIPTION.generate();
        DB.insertInto(MSG_SUBSCRIPTIONS)
                .set(MSG_SUBSCRIPTIONS.ID, id)
                .set(MSG_SUBSCRIPTIONS.CODE, "corrupt-" + id + "-" + RUN)
                .set(MSG_SUBSCRIPTIONS.APPLICATION_CODE, applicationCode)
                .set(MSG_SUBSCRIPTIONS.NAME, "corrupt")
                .set(MSG_SUBSCRIPTIONS.TARGET, "https://example.test/hook")
                .set(MSG_SUBSCRIPTIONS.STATUS, status)
                .set(MSG_SUBSCRIPTIONS.SOURCE, source)
                .execute();
        INSERTED.add(id);
        return id;
    }

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(MSG_SUBSCRIPTIONS).where(MSG_SUBSCRIPTIONS.ID.in(INSERTED)).execute();
    }

    /// Inserts a row with an explicit `(applicationCode, clientId, code)` key
    /// for the [#findByCodeTreatsNullAsARealKeyValueOnEveryPart] fixture
    /// below — distinct from [#insert] above (X-06 fixture), which never
    /// sets `clientId`.
    private static String insertKeyed(String applicationCode, String clientId, String code) {
        String id = EntityType.SUBSCRIPTION.generate();
        DB.insertInto(MSG_SUBSCRIPTIONS)
                .set(MSG_SUBSCRIPTIONS.ID, id)
                .set(MSG_SUBSCRIPTIONS.CODE, code)
                .set(MSG_SUBSCRIPTIONS.APPLICATION_CODE, applicationCode)
                .set(MSG_SUBSCRIPTIONS.NAME, "c4-fixture")
                .set(MSG_SUBSCRIPTIONS.TARGET, "https://example.test/hook")
                .set(MSG_SUBSCRIPTIONS.STATUS, "ACTIVE")
                .set(MSG_SUBSCRIPTIONS.SOURCE, "UI")
                .set(MSG_SUBSCRIPTIONS.CLIENT_ID, clientId)
                .execute();
        INSERTED.add(id);
        return id;
    }

    /// V12's three-part key (spec `code-first-connections.md` §2, C4):
    /// `NULL` is a real value on both `applicationCode` and `clientId`, never
    /// a wildcard. Four rows share one code, each under a different
    /// combination of the two nullable parts — `findByCode` must resolve
    /// each combination to exactly its own row.
    @Test
    void findByCodeTreatsNullAsARealKeyValueOnEveryPart() {
        String code = "c4-" + RUN;
        String appA = "sapp-" + RUN;
        String clientA = "cli_" + RUN + "_c4";

        String shared = insertKeyed(null, null, code);
        String appOnly = insertKeyed(appA, null, code);
        String clientOnly = insertKeyed(null, clientA, code);
        String both = insertKeyed(appA, clientA, code);

        assertThat(repo.findByCode(code, null, null)).map(Subscription::id)
                .as("(null, null) resolves the shared row, not the app- or client-scoped ones").contains(shared);
        assertThat(repo.findByCode(code, appA, null)).map(Subscription::id)
                .as("(A, null) does not fall back to the shared (null, null) row").contains(appOnly);
        assertThat(repo.findByCode(code, null, clientA)).map(Subscription::id)
                .as("(null, B) does not fall back to the shared (null, null) row").contains(clientOnly);
        assertThat(repo.findByCode(code, appA, clientA)).map(Subscription::id)
                .as("(A, B) resolves only the fully-scoped row").contains(both);

        // Cross-checks: none of the four ever answers for another's key.
        assertThat(repo.findByCode(code, appA, null)).map(Subscription::id).isNotEqualTo(Optional.of(shared));
        assertThat(repo.findByCode(code, null, clientA)).map(Subscription::id).isNotEqualTo(Optional.of(shared));
        assertThat(repo.findByCode(code, appA, clientA)).map(Subscription::id)
                .isNotEqualTo(Optional.of(appOnly)).isNotEqualTo(Optional.of(clientOnly));

        // A different application entirely finds nothing under this code.
        assertThat(repo.findByCode(code, "no-such-app-" + RUN, null)).isEmpty();
        // A different client entirely finds nothing under this code.
        assertThat(repo.findByCode(code, null, "cli_" + RUN + "_other")).isEmpty();
    }

    /// `chk_msg_subscriptions_status` (migration 051) now blocks a fresh
    /// write of an unrecognised status, so the constraint is dropped for
    /// the seed insert AND the assertions, and the row is deleted again
    /// before restoring — otherwise restoring it would itself fail by
    /// re-validating against the row we just inserted
    /// (io.flowcatalyst.testpg.TestPg, ported from Go's
    /// testpg.WithConstraintDropped).
    @Test
    void findByIdRejectsAnUnrecognisedStatusInsteadOfDefaultingToActive() {
        TestPg.withConstraintDropped(DS, "msg_subscriptions", "chk_msg_subscriptions_status", () -> {
            String id = insert("app-" + RUN, "BOGUS", "UI");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptSubscriptionException.class)
                        .satisfies(e -> assertThat(((CorruptSubscriptionException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_SUBSCRIPTIONS).where(MSG_SUBSCRIPTIONS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void findByIdRejectsAnUnrecognisedSourceInsteadOfDefaultingToUi() {
        TestPg.withConstraintDropped(DS, "msg_subscriptions", "chk_msg_subscriptions_source", () -> {
            String id = insert("app-" + RUN, "ACTIVE", "BOGUS");
            try {
                assertThatThrownBy(() -> repo.findById(id))
                        .isInstanceOf(CorruptSubscriptionException.class)
                        .satisfies(e -> assertThat(((CorruptSubscriptionException) e).rowId()).isEqualTo(id));
            } finally {
                DB.deleteFrom(MSG_SUBSCRIPTIONS).where(MSG_SUBSCRIPTIONS.ID.eq(id)).execute();
            }
        });
    }

    @Test
    void aCorruptRowFailsTheWholeListReadNotJustThatRow() {
        String application = "app2-" + RUN;
        String good = insert(application, "ACTIVE", "UI");
        TestPg.withConstraintDropped(DS, "msg_subscriptions", "chk_msg_subscriptions_status", () -> {
            String corrupt = insert(application, "NOT_A_STATUS", "UI");
            try {
                assertThatThrownBy(() -> repo.findByApplicationCode(application))
                        .isInstanceOf(CorruptSubscriptionException.class)
                        .satisfies(e -> assertThat(((CorruptSubscriptionException) e).rowId()).isEqualTo(corrupt));
                assertThat(repo.findById(good)).isPresent();
            } finally {
                DB.deleteFrom(MSG_SUBSCRIPTIONS).where(MSG_SUBSCRIPTIONS.ID.eq(corrupt)).execute();
            }
        });
    }
}
