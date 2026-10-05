package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;

/// The lost update in mark-QUEUED, deterministically, with two connections.
///
/// The scheduler's claim holds no lock, so a callback can move a claimed job on between the claim and the
/// mark-QUEUED. The mark checks the claimed version (`updated_at`) — but a version check made only inside the
/// statement's snapshot is not enough: under READ COMMITTED, an UPDATE that WAITS on a row lock held by a concurrent
/// transaction re-evaluates only the UPDATE's own WHERE against the NEW row version. If the callback re-enters the job
/// to PENDING (a retry, a deferral, a requeue) at a new `updated_at` and commits while the mark waits, a mark whose
/// version test lives anywhere else marks the job QUEUED at its new version — with no message published for it. Nothing
/// recovers that but the 15-minute stale sweep.
///
/// A: an uncommitted re-enter holding the job's row lock. B: mark-QUEUED for the OLD version, confirmed blocked in
/// `pg_stat_activity`. A commits. B must mark 0 rows and the job must stay PENDING at the new version.
class MarkQueuedLostUpdateTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DS);

    private enum ReEnter {
        REQUEUE("UPDATE msg_dispatch_jobs SET status = 'PENDING', scheduled_for = NULL, attempt_count = 0, updated_at = ? WHERE id = ? AND created_at = ?"),
        RETRY("UPDATE msg_dispatch_jobs SET status = 'PENDING', scheduled_for = now() + interval '30 seconds', attempt_count = attempt_count + 1, last_error = 'retry', updated_at = ? WHERE id = ? AND created_at = ?"),
        DEFER("UPDATE msg_dispatch_jobs SET status = 'PENDING', scheduled_for = now() + interval '5 seconds', updated_at = ? WHERE id = ? AND created_at = ?");

        final String sql;

        ReEnter(String sql) {
            this.sql = sql;
        }
    }

    @Test
    void aMarkWaitingOnARowLockHeldByAReEnterDoesNotMarkTheNewVersion() throws Exception {
        for (ReEnter how : ReEnter.values()) {
            // the job as the claim read it: PENDING at version V1, an hour ago
            Instant v1 = Instant.now().minusSeconds(3600);
            String id = seedWriteRow(Seed.of(code("lostupd")).withStatus("PENDING").withMode("BLOCK_ON_ERROR")
                    .withMessageGroup("lu-" + how).withUpdatedAt(v1));
            Instant created = createdAt(id);
            Instant claimedVersion = updatedAt(id);

            Instant v2 = Instant.now();
            try (Connection a = DS.getConnection()) {
                a.setAutoCommit(false);
                try (PreparedStatement ps = a.prepareStatement(how.sql)) {
                    ps.setObject(1, v2.atOffset(java.time.ZoneOffset.UTC));
                    ps.setString(2, id);
                    ps.setObject(3, created.atOffset(java.time.ZoneOffset.UTC));
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                }
                // B: marks the OLD version; its snapshot still sees the job PENDING at V1
                var claim = new ClaimRow(id, null, "lu-" + how, null, null, null, created, 1, null, claimedVersion);
                CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> LIFECYCLE.markQueued(java.util.List.of(claim)));
                awaitBlockedOnALock();
                assertThat(b).as("%s: the mark must be waiting on A's row lock", how).isNotDone();
                a.commit();
                int marked = b.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(marked).as("%s: a mark for the old version marked the job's NEW version", how).isZero();
            }
            assertThat(status(id)).as("%s: the job must stay PENDING", how).isEqualTo("PENDING");
            assertThat(updatedAt(id)).as("%s: at the new version", how).isEqualTo(v2.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }
    }

    /// The same class in the reaper: its CTE reads a PROCESSING sibling's age in the statement's snapshot. A delivery
    /// claim that refreshes `updated_at` while the sweep waits on the row lock must not have its live job swept.
    @Test
    void aSweepWaitingOnARowLockDoesNotResetAJobWhoseProcessingAgeWasJustRefreshed() throws Exception {
        String group = "lu-reaper-" + DispatchJobFixture.RUN;
        Instant old = Instant.now().minusSeconds(3600);
        seedWriteRow(Seed.of(code("lureap")).withStatus("FAILED").withMode("BLOCK_ON_ERROR").withMessageGroup(group)
                .withSequence(1).withUpdatedAt(old));
        String id = seedWriteRow(Seed.of(code("lureap")).withStatus("PROCESSING").withMode("BLOCK_ON_ERROR")
                .withMessageGroup(group).withSequence(2).withUpdatedAt(old));
        Instant created = createdAt(id);
        Instant v2 = Instant.now();
        try (Connection a = DS.getConnection()) {
            a.setAutoCommit(false);
            try (PreparedStatement ps = a.prepareStatement("UPDATE msg_dispatch_jobs SET updated_at = ? WHERE id = ? AND created_at = ?")) {
                ps.setObject(1, v2.atOffset(java.time.ZoneOffset.UTC));
                ps.setString(2, id);
                ps.setObject(3, created.atOffset(java.time.ZoneOffset.UTC));
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            CompletableFuture<java.util.List<String>> b = CompletableFuture.supplyAsync(
                    () -> LIFECYCLE.sweepStrandedSiblings(Instant.now().minusSeconds(600), "reaper"));
            awaitBlockedOnALock();
            assertThat(b).isNotDone();
            a.commit();
            assertThat(b.get(30, java.util.concurrent.TimeUnit.SECONDS)).as("the refreshed job must not be swept").doesNotContain(id);
        }
        assertThat(status(id)).isEqualTo("PROCESSING");
    }

    /// Waits until a backend is blocked on a lock behind an UPDATE of the job table (the mark).
    private static void awaitBlockedOnALock() throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        try (Connection c = DS.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                     + " AND wait_event_type = 'Lock' AND query LIKE '%UPDATE msg_dispatch_jobs%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) return;
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("the mark never blocked on the row lock");
    }

    private static Instant createdAt(String id) throws Exception {
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT created_at FROM msg_dispatch_jobs WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static Instant updatedAt(String id) throws Exception {
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT updated_at FROM msg_dispatch_jobs WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static String status(String id) throws Exception {
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT status FROM msg_dispatch_jobs WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
