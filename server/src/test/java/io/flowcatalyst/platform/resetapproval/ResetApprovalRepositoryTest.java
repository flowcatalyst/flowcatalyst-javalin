package io.flowcatalyst.platform.resetapproval;

import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_RESET_APPROVAL_REQUESTS;
import static org.assertj.core.api.Assertions.assertThat;

/// `ResetApprovalRepository` against embedded Postgres (spec §3.7, §8.6,
/// §11.10): the guarded [ResetApprovalRepository#decide] transition and
/// [ResetApprovalRepository#findPending]'s per-client visibility. `TestPg`
/// never truncates between tests — everything inserted here is scoped with
/// a fresh run tag and cleaned up in [#cleanup].
class ResetApprovalRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ResetApprovalRepository repo = new ResetApprovalRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final List<String> PRINCIPAL_IDS = new ArrayList<>();
    private static final List<String> REQUEST_IDS = new ArrayList<>();

    @AfterAll
    static void cleanup() {
        DB.deleteFrom(IAM_RESET_APPROVAL_REQUESTS).where(IAM_RESET_APPROVAL_REQUESTS.ID.in(REQUEST_IDS)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(PRINCIPAL_IDS)).execute();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /// A minimal `iam_principals` row — only there to satisfy the FK, the
    /// aggregate under test does not care about anything else on it.
    private static String seedPrincipal(String clientId) {
        String id = EntityType.PRINCIPAL.generate();
        OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id)
                .set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.SCOPE, "CLIENT")
                .set(IAM_PRINCIPALS.CLIENT_ID, clientId)
                .set(IAM_PRINCIPALS.NAME, "reset-approval-repo-test-" + RUN)
                .set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, ("rar-" + id + "@example.test").toLowerCase(Locale.ROOT))
                .set(IAM_PRINCIPALS.EMAIL_DOMAIN, "example.test")
                .set(IAM_PRINCIPALS.CREATED_AT, now)
                .set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();
        PRINCIPAL_IDS.add(id);
        return id;
    }

    private static ResetApprovalRequest seedPending(String principalId, String clientId, Instant createdAt) {
        var r = new ResetApprovalRequest(EntityType.RESET_APPROVAL_REQUEST.generate(), principalId, clientId,
                ResetApprovalStatus.PENDING, true, null, null, null, createdAt.plus(ResetApprovalRequest.TTL), createdAt);
        try (Connection c = DS.getConnection()) {
            c.setAutoCommit(true);
            repo.persist(r, io.flowcatalyst.sdk.usecase.jdbc.DbTx.wrapForBootstrap(c));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        REQUEST_IDS.add(r.id());
        return r;
    }

    private static ResetApprovalRequest reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("reset approval request " + id + " not found"));
    }

    // ── decide (mutant 1: drop the `status = 'PENDING'` guard) ──────────────

    @Test
    void decideUpdatesOnlyAPendingUnexpiredRowAndPersistsTheNote() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);
        var seeded = seedPending(principalId, clientId, Instant.now());

        int rows = repo.decide(seeded.id(), ResetApprovalStatus.APPROVED, "prn_admin1", "first review", Instant.now());
        assertThat(rows).as("first decision updates the row").isEqualTo(1);

        var decided = reload(seeded.id());
        assertThat(decided.status()).isEqualTo(ResetApprovalStatus.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("prn_admin1");
        assertThat(decided.note()).as("the reviewer's note is persisted (defect 11)").isEqualTo("first review");
        assertThat(decided.decidedAt()).isNotNull();
    }

    /// Pins the guard itself: a second `decide` call on the same id returns
    /// `0` rows and the row is untouched by the second call's values —
    /// this is the assertion mutant 1 (dropping `status = 'PENDING'` from
    /// the `WHERE` clause) breaks, because the second call would then
    /// match and overwrite the first decision.
    @Test
    void aSecondDecideCallOnTheSameRowIsRejectedAndTheFirstDecisionSurvives() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);
        var seeded = seedPending(principalId, clientId, Instant.now());

        int first = repo.decide(seeded.id(), ResetApprovalStatus.APPROVED, "prn_admin1", "approved first", Instant.now());
        assertThat(first).isEqualTo(1);

        int second = repo.decide(seeded.id(), ResetApprovalStatus.DENIED, "prn_admin2", "denied second", Instant.now());
        assertThat(second).as("only the first decision counts").isEqualTo(0);

        var row = reload(seeded.id());
        assertThat(row.status()).as("still APPROVED — the second call must not have applied").isEqualTo(ResetApprovalStatus.APPROVED);
        assertThat(row.decidedBy()).isEqualTo("prn_admin1");
        assertThat(row.note()).isEqualTo("approved first");
    }

    @Test
    void decideOnAnExpiredRowReturnsZeroRows() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);
        // Created far enough in the past that expiresAt (createdAt + 72h) has passed.
        var seeded = seedPending(principalId, clientId, Instant.now().minus(ResetApprovalRequest.TTL).minusSeconds(60));
        int rows = repo.decide(seeded.id(), ResetApprovalStatus.APPROVED, "prn_admin1", null, Instant.now());
        assertThat(rows).isEqualTo(0);
    }

    // ── findPending visibility (mutant 2: ignore the client list) ───────────

    @Test
    void findPendingForAnAnchorSeesEveryPendingUnexpiredRowOldestFirst() {
        String clientA = EntityType.CLIENT.generate();
        String clientB = EntityType.CLIENT.generate();
        Instant t0 = Instant.now().minusSeconds(30);
        var older = seedPending(seedPrincipal(clientA), clientA, t0);
        var newer = seedPending(seedPrincipal(clientB), clientB, t0.plusSeconds(5));

        List<ResetApprovalRequest> pending = repo.findPending(Visibility.Everything.INSTANCE);
        List<String> ids = pending.stream().map(ResetApprovalRequest::id).toList();
        assertThat(ids.indexOf(older.id())).as("oldest first").isLessThan(ids.indexOf(newer.id()));
        assertThat(ids).contains(older.id(), newer.id());
    }

    /// The visibility filter must actually restrict by client — an anchor
    /// query above (no filter) already proves both rows are visible in
    /// principle, so seeing only its own client here is the filter at work,
    /// not an accident of there being nothing else to see.
    @Test
    void findPendingForAClientAdminSeesOnlyItsOwnClient() {
        String own = EntityType.CLIENT.generate();
        String other = EntityType.CLIENT.generate();
        var mine = seedPending(seedPrincipal(own), own, Instant.now());
        var notMine = seedPending(seedPrincipal(other), other, Instant.now());

        List<ResetApprovalRequest> pending = repo.findPending(new Visibility.Tenants(List.of(own)));
        List<String> ids = pending.stream().map(ResetApprovalRequest::id).toList();
        assertThat(ids).contains(mine.id()).doesNotContain(notMine.id());
    }

    /// Spec §8.6: `client_id = ANY(clients)`, an empty list seeing **none**
    /// — not the generic `Visibility` "platform-scoped rows" fallback,
    /// since an approval row's `client_id` is never null.
    @Test
    void findPendingForAnEmptyClientListSeesNothing() {
        String clientId = EntityType.CLIENT.generate();
        seedPending(seedPrincipal(clientId), clientId, Instant.now());

        List<ResetApprovalRequest> pending = repo.findPending(new Visibility.Tenants(List.of()));
        assertThat(pending).as("an empty client list is not the same as 'everything'").isEmpty();
    }

    @Test
    void findPendingExcludesDecidedAndExpiredRows() {
        String clientId = EntityType.CLIENT.generate();
        var decided = seedPending(seedPrincipal(clientId), clientId, Instant.now());
        repo.decide(decided.id(), ResetApprovalStatus.APPROVED, "prn_admin1", null, Instant.now());
        var expired = seedPending(seedPrincipal(clientId), clientId, Instant.now().minus(ResetApprovalRequest.TTL).minusSeconds(60));

        List<String> ids = repo.findPending(new Visibility.Tenants(List.of(clientId))).stream()
                .map(ResetApprovalRequest::id).toList();
        assertThat(ids).doesNotContain(decided.id(), expired.id());
    }

    // ── hasPendingFor (queue's duplicate-suppression check) ─────────────────

    @Test
    void hasPendingForIsTrueOnlyWhileAnUnexpiredPendingRowExists() {
        String clientId = EntityType.CLIENT.generate();
        String principalId = seedPrincipal(clientId);
        assertThat(repo.hasPendingFor(principalId)).isFalse();

        var seeded = seedPending(principalId, clientId, Instant.now());
        assertThat(repo.hasPendingFor(principalId)).isTrue();

        repo.decide(seeded.id(), ResetApprovalStatus.DENIED, "prn_admin1", null, Instant.now());
        assertThat(repo.hasPendingFor(principalId)).as("decided rows no longer count as pending").isFalse();
    }
}
