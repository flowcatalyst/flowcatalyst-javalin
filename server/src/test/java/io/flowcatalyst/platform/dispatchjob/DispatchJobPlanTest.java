package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.dispatchjob.PlanDatabases.Source;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.flowcatalyst.platform.dispatchjob.PlanDatabases.exec;
import static io.flowcatalyst.platform.dispatchjob.PlanDatabases.lines;
import static org.assertj.core.api.Assertions.assertThat;

/// The plans of every statement the dispatch path runs against `msg_dispatch_jobs` (dispatch step 4: the scheduler
/// claims from the job table; the queue table is retired), on a real database, at about 5,000 and 200,000 PENDING
/// jobs inside a job table of mostly COMPLETED rows over four partitions, in the statistics states that matter:
///
///  1. freshly analysed;
///  2. never analysed;
///  3. the ACTIVE partition analysed while it held ZERO pending jobs, then a burst (the realistic case: the
///     statistics say PENDING is absent);
///  4. the empty forward partitions VACUUMed and ANALYZEd (the state that made the old claim sort every pending row);
///  5. all pending rows sharing one `created_at` and one `sequence` (ties);
///  6. the cached-plan case: the statements run several times on ONE connection while there are no pending jobs, then
///     the burst, then the plan that same connection would use;
///  7. an in-flight array of 1,000 and of 5,000 ids that are the FIRST rows of the walk (freshly analysed).
///
/// The scheduler's statements (claim, mark-QUEUED, hold-back) are planned on a connection with the scheduler pool's
/// two settings, as a prepared statement (`EXPLAIN EXECUTE`); the delivery callback's by-key transitions run on a pool
/// WITHOUT the settings, so they are planned as GENERIC plans (the worst a server-prepared statement can cache) on a
/// connection without them. Asserted: SHAPE, never time — the claim has no Sort and no Seq Scan and walks the plain
/// status index; mark-QUEUED and every by-key transition read by PRIMARY KEY and never seq-scan a populated partition
/// or walk the status index (the opaque `status || ''` guard; a sargable guard fails state 3); hold-back and the sweeps
/// use the plain index. The negative control runs state 3 and the cached-plan case WITHOUT the settings.
/// The report (plans, timings) is written to `target/dispatch-job-plans.txt`.
class DispatchJobPlanTest {

    private enum State { FRESH, NEVER_ANALYSED, ACTIVE_ZERO_THEN_BURST, EMPTY_PARTITIONS_VACUUMED, TIES, CACHED_PLAN, IN_FLIGHT_1000, IN_FLIGHT_5000 }

    private static final String STATUS_INDEX = "status_message_group_sequence_cre";
    private static final Set<String> POPULATED = PlanDatabases.populatedPartitions();
    private static final int FILLER = 60_000;

    private record Statements(String claim, String mark, String holdBack, String holdBefore) {
    }

    private static Statements capture(String db) throws Exception {
        var rec = new Source(db, true, false);
        var lifecycle = new DispatchJobLifecycle(rec);
        var repo = new DispatchJobRepository(rec);
        Instant created = Instant.now().minus(Duration.ofDays(1));
        rec.as("claim");
        repo.claimPending(3, Set.of("sub1"), Set.of("g0001"), List.of("x"));
        rec.as("mark");
        lifecycle.markQueued(List.of(new ClaimRow("J000000000001", null, "g0001", DispatchMode.BLOCK_ON_ERROR, null, null, created, 1, null, created)));
        rec.as("holdback");
        repo.heldBeforeIds(List.of(new ClaimRow("J000000000001", null, "g0001", DispatchMode.BLOCK_ON_ERROR, null, null, created, 1, null, created)));
        rec.as("holdbefore");
        repo.groupHeldBefore("g0001", 5, created, "J000000000001");
        return new Statements(rec.sqlByName.get("claim"), rec.sqlByName.get("mark"), rec.sqlByName.get("holdback"),
                rec.sqlByName.get("holdbefore"));
    }

    /// The by-key and by-id transitions and the sweeps, as the lifecycle issues them, captured on a plain pool.
    private static Map<String, String> captureTransitions(String db) throws Exception {
        var rec = new Source(db, false, false);
        var lifecycle = new DispatchJobLifecycle(rec);
        var repo = new DispatchJobRepository(rec);
        Instant created = Instant.now().minus(Duration.ofDays(1));
        String id = "J000000000001";
        rec.as("claim for delivery");
        lifecycle.claimForDelivery(id, created);
        rec.as("complete");
        lifecycle.markCompleted(id, created, Instant.now(), 5L);
        rec.as("fail");
        lifecycle.markFailed(id, created, "boom");
        rec.as("retry");
        lifecycle.scheduleRetry(id, created, Instant.now().plusSeconds(60), 1, "retry");
        rec.as("reschedule");
        lifecycle.reschedule(id, created, Instant.now().plusSeconds(60));
        rec.as("settle acked");
        lifecycle.settleAcked(List.of(id, "J000000000002"), "settled");
        rec.as("stale QUEUED");
        lifecycle.recoverStaleQueued(Instant.EPOCH);
        rec.as("reaper");
        lifecycle.sweepStrandedSiblings(Instant.EPOCH, "reaper");
        rec.as("backlog");
        repo.pendingBacklog();
        return rec.sqlByName;
    }

    private static String generic(String sql) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') sb.append('$').append(++n);
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static String genericPlan(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT * FROM explain_generic(?)")) {
            ps.setString(1, generic(sql));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return String.join("\n", out);
    }

    private static String explain(Connection c, String call) throws SQLException {
        return String.join("\n", lines(c, "EXPLAIN (COSTS OFF) EXECUTE " + call));
    }

    private static String arr(List<String> values) {
        return "'{" + String.join(",", values) + "}'::text[]";
    }

    private static String quotedArr(List<String> values) {
        List<String> q = new ArrayList<>();
        for (String v : values) q.add("\"" + v + "\"");
        return arr(q);
    }

    /// A Sort (or Incremental Sort) PLAN NODE — not the `Sort Key:` line a Merge Append carries.
    private static boolean hasSortNode(String plan) {
        return plan.lines().anyMatch(l -> l.matches("\\s*(->\\s*)?(Incremental )?Sort\\s*"));
    }

    private static boolean seqScansPopulated(String plan) {
        var m = java.util.regex.Pattern.compile("Seq Scan on (msg_dispatch_jobs_\\d{4}_\\d{2})").matcher(plan);
        while (m.find()) if (POPULATED.contains(m.group(1))) return true;
        return plan.lines().anyMatch(l -> l.matches(".*Seq Scan on msg_dispatch_jobs( .*)?$"));
    }

    // ── one scenario ───────────────────────────────────────────────────────

    private record Result(String claimPlan, String markPlan, String holdPlan, double claimMs, double markMs, double holdMs) {
    }

    private static final String CLAIM_ARGS = "'{sub1,sub2}'::text[], '{}'::text[], %s, 500";

    private static void prepare(Connection c, Statements st) throws SQLException {
        exec(c, "DEALLOCATE ALL");
        exec(c, "PREPARE dq_claim(text[], text[], text[], int) AS " + generic(st.claim()));
        exec(c, "PREPARE dq_mark(text[], text[], text[], timestamptz) AS " + generic(st.mark()));
        exec(c, "PREPARE dq_hold(text[], text[], text[], int[], text[], text[]) AS " + generic(st.holdBack()));
    }

    private static Result scenario(State state, int n, boolean settings, String db, Statements st) throws Exception {
        try (Connection admin = new Source(db, false, false).getConnection()) {
            switch (state) {
                case FRESH, TIES, IN_FLIGHT_1000, IN_FLIGHT_5000 -> {
                    PlanDatabases.seedCompleted(admin, FILLER);
                    PlanDatabases.burst(admin, n, state == State.TIES);
                    exec(admin, "ANALYZE msg_dispatch_jobs");
                }
                case NEVER_ANALYSED -> {
                    PlanDatabases.seedCompleted(admin, FILLER);
                    PlanDatabases.burst(admin, n, false);
                }
                case ACTIVE_ZERO_THEN_BURST -> {
                    PlanDatabases.seedCompleted(admin, FILLER);
                    exec(admin, "ANALYZE msg_dispatch_jobs");   // the active partition holds no PENDING job
                    PlanDatabases.burst(admin, n, false);       // the burst, no analyze
                }
                case EMPTY_PARTITIONS_VACUUMED -> {
                    PlanDatabases.seedCompleted(admin, FILLER);
                    PlanDatabases.burst(admin, n, false);
                    for (String p : PlanDatabases.emptyPartitions()) exec(admin, "VACUUM ANALYZE " + p);
                }
                case CACHED_PLAN -> {
                    PlanDatabases.seedCompleted(admin, FILLER);
                    exec(admin, "ANALYZE msg_dispatch_jobs");
                }
            }
        }
        List<String> inFlight = List.of();
        if (state == State.IN_FLIGHT_1000 || state == State.IN_FLIGHT_5000) {
            int k = Math.min(state == State.IN_FLIGHT_1000 ? 1000 : 5000, n / 2); // at 5,000 pending, half of them
            try (Connection admin = new Source(db, false, false).getConnection()) {
                inFlight = lines(admin, "SELECT id FROM msg_dispatch_jobs WHERE status = 'PENDING'"
                        + " ORDER BY message_group NULLS LAST, sequence, created_at, id LIMIT " + k);
            }
        }
        String claimArgs = CLAIM_ARGS.formatted(arr(inFlight));
        try (Connection c = new Source(db, settings, true).getConnection()) {
            prepare(c, st);
            if (state == State.CACHED_PLAN) {
                for (int i = 0; i < 8; i++) {
                    exec(c, "EXECUTE dq_claim(" + claimArgs + ")");
                    exec(c, "EXECUTE dq_mark('{}'::text[], '{}'::text[], '{}'::text[], now())");
                    exec(c, "EXECUTE dq_hold('{FAILED,ERROR}'::text[], '{}'::text[], '{}'::text[], '{}'::int[], '{}'::text[], '{}'::text[])");
                }
                try (Connection admin = new Source(db, false, false).getConnection()) {
                    PlanDatabases.burst(admin, n, false);
                }
            }
            String claimPlan = explain(c, "dq_claim(" + claimArgs + ")");
            long t = System.nanoTime();
            List<String[]> rows = new ArrayList<>();
            try (Statement stmt = c.createStatement(); ResultSet rs = stmt.executeQuery("EXECUTE dq_claim(" + claimArgs + ")")) {
                while (rs.next()) rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(11), rs.getString(3), rs.getString(4)});
            }
            double claimMs = (System.nanoTime() - t) / 1e6;
            assertThat(rows).as("the claim returned rows").isNotEmpty();
            List<String> ids = new ArrayList<>(), createds = new ArrayList<>(), versions = new ArrayList<>();
            for (String[] r : rows) {
                ids.add(r[0]);
                createds.add(r[1]);
                versions.add(r[2]);
            }
            String markArgs = arr(ids) + ", " + quotedArr(createds) + ", " + quotedArr(versions) + ", now()";
            String markPlan = explain(c, "dq_mark(" + markArgs + ")");
            exec(c, "BEGIN");
            t = System.nanoTime();
            exec(c, "EXECUTE dq_mark(" + markArgs + ")");
            double markMs = (System.nanoTime() - t) / 1e6;
            exec(c, "ROLLBACK");
            // the claim-time hold-back over the claimed rows' groups; positions near the head of each group
            List<String> groups = new ArrayList<>(), seqs = new ArrayList<>(), ca = new ArrayList<>(), jid = new ArrayList<>();
            for (String[] r : rows) {
                groups.add(r[3]);
                seqs.add(r[4]);
                ca.add("\"" + r[1] + "\"");
                jid.add(r[0]);
            }
            String holdArgs = "'{FAILED,ERROR}'::text[], " + arr(groups) + ", " + arr(groups) + ", '{" + String.join(",", seqs)
                    + "}'::int[], " + arr(ca) + ", " + arr(jid);
            String holdPlan = explain(c, "dq_hold(" + holdArgs + ")");
            t = System.nanoTime();
            exec(c, "EXECUTE dq_hold(" + holdArgs + ")");
            double holdMs = (System.nanoTime() - t) / 1e6;
            return new Result(claimPlan, markPlan, holdPlan, claimMs, markMs, holdMs);
        }
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    void theDispatchPathIsWellPlannedInEveryStateWithTheSchedulerSettings() throws Exception {
        var report = new StringBuilder();
        List<String> failures = new ArrayList<>();
        Statements st = capture(PlanDatabases.clone("dq_jplan_capture"));
        report.append(String.format("%-26s %8s %10s %10s %10s%n", "state", "pending", "claim ms", "mark ms", "hold ms"));
        for (int n : new int[] {5_000, 200_000}) {
            for (State state : State.values()) {
                String db = PlanDatabases.clone("dq_jplan_" + state.name().toLowerCase(java.util.Locale.ROOT) + "_" + n);
                Result r = scenario(state, n, true, db, st);
                String where = state + " @" + n;
                report.append(String.format("%-26s %8d %10.1f %10.1f %10.1f%n", state, n, r.claimMs(), r.markMs(), r.holdMs()));
                report.append("--- ").append(where).append(" claim\n").append(r.claimPlan()).append("\n--- mark-QUEUED\n")
                        .append(r.markPlan()).append("\n--- hold-back\n").append(r.holdPlan()).append("\n");
                if (hasSortNode(r.claimPlan())) failures.add(where + ": the claim has a Sort node");
                if (seqScansPopulated(r.claimPlan())) failures.add(where + ": the claim seq-scans the job table");
                if (!r.claimPlan().contains(STATUS_INDEX)) failures.add(where + ": the claim does not use the plain status index");
                if (!r.markPlan().contains("_pkey")) failures.add(where + ": mark-QUEUED does not read by primary key");
                if (r.markPlan().contains(STATUS_INDEX)) failures.add(where + ": mark-QUEUED walks the status index");
                if (seqScansPopulated(r.markPlan())) failures.add(where + ": mark-QUEUED seq-scans the job table");
                if (seqScansPopulated(r.holdPlan())) failures.add(where + ": the hold-back seq-scans the job table");
                if (!r.holdPlan().contains(STATUS_INDEX)) failures.add(where + ": the hold-back does not use the plain status index");
            }
        }
        // the callback's transitions and the sweeps: GENERIC plans on a pool WITHOUT the settings
        String big = PlanDatabases.clone("dq_jplan_transitions");
        try (Connection admin = new Source(big, false, false).getConnection()) {
            PlanDatabases.seedCompleted(admin, FILLER);
            exec(admin, "ANALYZE msg_dispatch_jobs");
            PlanDatabases.burst(admin, 200_000, false); // ACTIVE_ZERO_THEN_BURST: statistics say PENDING is absent
        }
        Map<String, String> transitions = captureTransitions(big);
        report.append("\n=== transitions and sweeps, generic plans, no settings, 200,000 PENDING, active partition analysed while empty ===\n");
        try (Connection c = new Source(big, false, false).getConnection()) {
            for (var e : transitions.entrySet()) {
                String name = e.getKey();
                String plan = genericPlan(c, e.getValue().split("\n;;\n")[0]);
                report.append("--- ").append(name).append("\n").append(plan).append("\n");
                switch (name) {
                    case "claim for delivery", "complete", "fail", "retry", "reschedule" -> {
                        if (!plan.contains("_pkey")) failures.add(name + ": not read by primary key");
                        if (plan.contains(STATUS_INDEX)) failures.add(name + ": walks the status index");
                        if (seqScansPopulated(plan)) failures.add(name + ": seq-scans the job table");
                    }
                    case "settle acked" -> {
                        if (plan.contains(STATUS_INDEX)) failures.add(name + ": walks the status index");
                        if (seqScansPopulated(plan)) failures.add(name + ": seq-scans the job table");
                    }
                    case "stale QUEUED", "reaper", "backlog" -> {
                        if (!plan.contains(STATUS_INDEX)) failures.add(name + ": does not use the plain status index");
                        if (seqScansPopulated(plan)) failures.add(name + ": seq-scans the job table");
                    }
                    default -> { }
                }
            }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/dispatch-job-plans.txt"), report.toString());
        System.out.println("dispatch job plans written to target/dispatch-job-plans.txt");
        assertThat(failures).isEmpty();
    }

    /// The negative control: WITHOUT the scheduler pool's two settings, which states go bad.
    @Test
    void withoutTheSettingsTheCachedPlanAfterABurstIsBad() throws Exception {
        Statements st = capture(PlanDatabases.clone("dq_jplan_capture2"));
        var report = new StringBuilder("NEGATIVE CONTROL: no planner settings\n");
        boolean bad = false;
        for (State state : new State[] {State.CACHED_PLAN, State.ACTIVE_ZERO_THEN_BURST, State.EMPTY_PARTITIONS_VACUUMED}) {
            Result r = scenario(state, 200_000, false, PlanDatabases.clone("dq_jplan_neg_" + state.name().toLowerCase(java.util.Locale.ROOT)), st);
            boolean stateBad = hasSortNode(r.claimPlan()) || seqScansPopulated(r.claimPlan()) || !r.claimPlan().contains(STATUS_INDEX);
            report.append(String.format("%s: claim %.1f ms, mark %.1f ms, hold-back %.1f ms: %s%n", state, r.claimMs(), r.markMs(), r.holdMs(),
                    stateBad ? "BAD CLAIM PLAN" : "claim plan ok"));
            report.append(r.claimPlan()).append('\n');
            bad |= stateBad;
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/dispatch-job-plans-negative.txt"), report.toString());
        System.out.println(report);
        // Whether it reproduces depends on the Postgres version and its statistics; the report records what happened.
        // On this Postgres at least one of the states must show the bad plan, or the settings would be unneeded.
        assertThat(bad).as("without the settings some state should plan the claim badly:%n%s", report).isTrue();
    }

    @Test
    void thePlanTestUsesTheSchedulerPoolsSettings() {
        assertThat(Pools.SCHEDULER_SERVER_SETTINGS).containsEntry("plan_cache_mode", "force_custom_plan")
                .containsEntry("enable_sort", "off").hasSize(2);
        assertThat(PlanDatabases.SETTINGS_OPTIONS).contains("plan_cache_mode=force_custom_plan").contains("enable_sort=off");
    }
}
