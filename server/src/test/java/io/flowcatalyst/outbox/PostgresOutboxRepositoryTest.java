package io.flowcatalyst.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.flowcatalyst.outbox.OutboxFixture.DS;
import static io.flowcatalyst.outbox.OutboxFixture.VALID_PAYLOAD;
import static io.flowcatalyst.outbox.OutboxFixture.id;
import static io.flowcatalyst.outbox.OutboxFixture.row;
import static io.flowcatalyst.outbox.OutboxFixture.seedRow;
import static org.assertj.core.api.Assertions.assertThat;

/// [PostgresOutboxRepository] against a real embedded Postgres (spec §3):
/// the exclusive, ordered claim; `initSchema` idempotence; and every
/// transition (`markSuccess`, `markFailed`, `release`, `requeue`,
/// `recoverStuck`).
class PostgresOutboxRepositoryTest {

    private static final PostgresOutboxRepository REPO = new PostgresOutboxRepository(DS);

    @Test
    void initSchemaIsIdempotent() {
        REPO.initSchema();
        REPO.initSchema();
        assertThat(REPO.healthy()).isTrue();
    }

    // ── claim: exclusive and ordered (spec §9, the load-bearing test) ───────

    @Test
    void twoConcurrentClaimersNeverBothGetTheSameRow() throws Exception {
        String group = "grp-" + id("excl");
        List<String> seeded = new ArrayList<>();
        Instant t = Instant.now().minusSeconds(30);
        for (int i = 0; i < 20; i++) {
            String rowId = id("excl-" + i);
            seedRow(rowId, OutboxItemType.EVENT, group, VALID_PAYLOAD, 0, 0, t.plusMillis(i));
            seeded.add(rowId);
        }

        var barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<List<OutboxItem>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS); // both claimers enter claimPending together
                    return REPO.claimPending(1000);
                }));
            }
            List<OutboxItem> a = futures.get(0).get(10, TimeUnit.SECONDS);
            List<OutboxItem> b = futures.get(1).get(10, TimeUnit.SECONDS);

            var idsA = a.stream().map(OutboxItem::id).filter(seeded::contains).collect(Collectors.toSet());
            var idsB = b.stream().map(OutboxItem::id).filter(seeded::contains).collect(Collectors.toSet());

            assertThat(intersection(idsA, idsB)).as("no id claimed by both concurrent claimers").isEmpty();
            var counts = new ConcurrentHashMap<String, AtomicInteger>();
            for (String claimedId : idsA) counts.computeIfAbsent(claimedId, k -> new AtomicInteger()).incrementAndGet();
            for (String claimedId : idsB) counts.computeIfAbsent(claimedId, k -> new AtomicInteger()).incrementAndGet();
            assertThat(counts).as("no id claimed by both").allSatisfy((k, v) -> assertThat(v).hasValue(1));
            assertThat(counts.keySet()).as("every seeded row was claimed by exactly one of the two")
                    .containsExactlyInAnyOrderElementsOf(seeded);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aClaimHeldOpenInOneTransactionIsSkippedByAnotherClaimer() throws Exception {
        // The property the row lock exists for, pinned deterministically: claimer A
        // runs the claim statements and does NOT commit; claimer B, on another
        // connection, must not receive any of A's rows. With `FOR UPDATE SKIP
        // LOCKED` B skips them at once. Without the lock B's SELECT would still
        // see them PENDING (A's update is uncommitted) and B's UPDATE would then
        // wait on A's row locks — so A is released after a grace period rather
        // than never, and B then comes back holding A's rows, which fails.
        String group = "grp-" + id("held");
        List<String> seeded = new ArrayList<>();
        Instant t = Instant.now().minusSeconds(30);
        for (int i = 0; i < 10; i++) {
            String rowId = id("held-" + i);
            seedRow(rowId, OutboxItemType.EVENT, group, VALID_PAYLOAD, 0, 0, t.plusMillis(i));
            seeded.add(rowId);
        }
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (var held = DS.getConnection()) {
            held.setAutoCommit(false);
            List<OutboxItem> a = REPO.claimInTx(held, 1000);
            assertThat(a.stream().map(OutboxItem::id).filter(seeded::contains)).as("A holds every seeded row").hasSize(10);

            var b = pool.submit(() -> REPO.claimPending(1000));
            List<OutboxItem> bItems;
            try {
                bItems = b.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException blockedOnAsLocks) {
                held.commit();                       // release A so B can finish — and expose what it took
                bItems = b.get(10, TimeUnit.SECONDS);
            }
            assertThat(bItems.stream().map(OutboxItem::id).filter(seeded::contains).toList())
                    .as("B must skip every row A holds; a non-empty list means two claimers own the same rows")
                    .isEmpty();
            if (!held.isClosed()) held.rollback();
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        var out = new java.util.HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    @Test
    void claimOrdersByMessageGroupThenCreatedAt() {
        String groupA = id("order-a-group");
        String groupB = id("order-b-group");
        Instant t = Instant.now().minusSeconds(30);
        // groupB rows created earlier in time, but groupA sorts first lexically.
        String b2 = id("order-b2");
        String b1 = id("order-b1");
        String a2 = id("order-a2");
        String a1 = id("order-a1");
        seedRow(b2, OutboxItemType.EVENT, groupB, VALID_PAYLOAD, 0, 0, t);
        seedRow(b1, OutboxItemType.EVENT, groupB, VALID_PAYLOAD, 0, 0, t.plusMillis(1));
        seedRow(a2, OutboxItemType.EVENT, groupA, VALID_PAYLOAD, 0, 0, t.plusMillis(2));
        seedRow(a1, OutboxItemType.EVENT, groupA, VALID_PAYLOAD, 0, 0, t.plusMillis(3));

        List<OutboxItem> claimed = REPO.claimPending(5000);
        List<String> order = claimed.stream().map(OutboxItem::id)
                .filter(rid -> Set.of(a1, a2, b1, b2).contains(rid)).toList();

        assertThat(order).as("ordered by message_group first, then created_at within the group")
                .containsExactly(a2, a1, b2, b1);
    }

    @Test
    void claimMarksSurvivorsInProgressAndReturnsTheirFields() {
        String rowId = id("claim-fields");
        String group = id("claim-fields-group");
        seedRow(rowId, OutboxItemType.DISPATCH_JOB, group, VALID_PAYLOAD, 0, 2, Instant.now());

        List<OutboxItem> claimed = REPO.claimPending(5000);
        OutboxItem item = claimed.stream().filter(i -> i.id().equals(rowId)).findFirst().orElseThrow();

        assertThat(item.type()).isEqualTo(OutboxItemType.DISPATCH_JOB);
        assertThat(item.messageGroup()).isEqualTo(group);
        assertThat(item.payload()).isEqualTo(VALID_PAYLOAD);
        assertThat(item.retryCount()).isEqualTo(2);
        assertThat(row(rowId).status()).as("claimed rows flip to IN_PROGRESS (9)").isEqualTo(9);
    }

    // ── release vs failure (spec §9) ────────────────────────────────────────

    @Test
    void releaseReturnsToPendingWithRetryCountUnchanged() {
        String rowId = id("release");
        seedRow(rowId, OutboxItemType.EVENT, null, VALID_PAYLOAD, 9, 3, Instant.now());

        REPO.release(List.of(rowId));

        var r = row(rowId);
        assertThat(r.status()).isEqualTo(0);
        assertThat(r.retryCount()).as("release must not touch retry_count — a counter that must not move").isEqualTo(3);
    }

    @Test
    void releaseOnlyAffectsARowStillInProgress() {
        String rowId = id("release-guard");
        seedRow(rowId, OutboxItemType.EVENT, null, VALID_PAYLOAD, 3, 1, Instant.now()); // already BAD_REQUEST

        REPO.release(List.of(rowId));

        assertThat(row(rowId).status()).as("a non-IN_PROGRESS row is untouched by release").isEqualTo(3);
    }

    @Test
    void markFailedIncrementsRetryCountAndRecordsTheMessage() {
        String rowId = id("mark-failed");
        seedRow(rowId, OutboxItemType.EVENT, null, VALID_PAYLOAD, 9, 1, Instant.now());

        REPO.markFailed(List.of(rowId), OutboxStatus.GATEWAY_ERROR, "502", false);

        var r = row(rowId);
        assertThat(r.status()).isEqualTo(OutboxStatus.GATEWAY_ERROR.code());
        assertThat(r.retryCount()).as("a failure increments retry_count").isEqualTo(2);
        assertThat(r.errorMessage()).isEqualTo("502");
    }

    @Test
    void markFailedWithRequeueWritesPendingInsteadOfTheTerminalStatus() {
        String rowId = id("mark-failed-requeue");
        seedRow(rowId, OutboxItemType.EVENT, null, VALID_PAYLOAD, 9, 0, Instant.now());

        REPO.markFailed(List.of(rowId), OutboxStatus.INTERNAL_ERROR, "boom", true);

        var r = row(rowId);
        assertThat(r.status()).as("requeue writes PENDING regardless of the failing status").isEqualTo(0);
        assertThat(r.retryCount()).isEqualTo(1);
    }

    @Test
    void markSuccessDeletesTheRow() {
        String rowId = id("mark-success");
        seedRow(rowId, OutboxItemType.EVENT, null, VALID_PAYLOAD, 9, 0, Instant.now());

        REPO.markSuccess(List.of(rowId));

        assertThat(OutboxFixture.exists(rowId)).as("a delivered item leaves no trace").isFalse();
    }

    @Test
    void requeueResetsRetryCountAndClearsTheError() {
        String rowId = id("requeue");
        seedRow(rowId, OutboxItemType.EVENT, null, VALID_PAYLOAD, 2, 3, Instant.now());
        REPO.markFailed(List.of(rowId), OutboxStatus.BAD_REQUEST, "old failure", false);

        REPO.requeue(List.of(rowId));

        var r = row(rowId);
        assertThat(r.status()).isEqualTo(0);
        assertThat(r.retryCount()).isEqualTo(0);
        assertThat(r.errorMessage()).isNull();
    }

    // ── recovery (spec §9) ───────────────────────────────────────────────────

    @Test
    void recoverStuckReturnsOldInProgressRowsButNotFreshOnes() {
        String stale = id("recover-stale");
        String fresh = id("recover-fresh");
        seedRow(stale, OutboxItemType.EVENT, null, VALID_PAYLOAD, 9, 0, Instant.now());
        seedRow(fresh, OutboxItemType.EVENT, null, VALID_PAYLOAD, 9, 0, Instant.now());
        backdateUpdatedAt(stale, Instant.now().minus(Duration.ofMinutes(10)));

        int recovered = REPO.recoverStuck(Duration.ofMinutes(5));

        assertThat(row(stale).status()).as("older than the threshold -> PENDING").isEqualTo(0);
        assertThat(row(fresh).status()).as("younger than the threshold -> untouched").isEqualTo(9);
        assertThat(recovered).isGreaterThanOrEqualTo(1);
    }

    private static void backdateUpdatedAt(String rowId, Instant updatedAt) {
        try (var conn = DS.getConnection();
             var ps = conn.prepareStatement("UPDATE outbox_messages SET updated_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(updatedAt));
            ps.setString(2, rowId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void healthyProbesTheConnection() {
        assertThat(REPO.healthy()).isTrue();
    }
}
