package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/// An adversarial multi-threaded run of the poller's claim loop against the
/// lanes, with a model of the queue table as the "database" (a claim sets
/// being deleted; a publish keeps it out; every other outcome restores the
/// claim): 3,000 jobs in 30 groups,
/// a buffer of 24, four lanes, claims of 8, lane batches of 5, 3% random publish
/// failures, 1% random status-update failures, and random jitter in the store
/// and the publisher. Every job must eventually be published (within 30 s — a
/// livelock shows as a timeout), and each group's FIRST delivery of every job
/// must be in claim order (a duplicate after a failed status update is allowed).
///
/// The iteration count comes from `-Dstress.iterations` (default 5).
/// A probabilistic backstop: the interleavings the ordering rule rests on are
/// driven deterministically by [DispatchLanesTest] and
/// `PendingJobPollerConcurrencyTest`.
class DispatchLanesStressTest {

    private static final int GROUPS = 30;
    private static final int PER_GROUP = 100;

    private static String id(int group, int seq) {
        return String.format("s-%02d-%03d", group, seq);
    }

    private static ClaimRow row(int group, int seq) {
        return new ClaimRow(id(group, seq), null, String.format("group-%02d", group), DispatchMode.IMMEDIATE,
                null, null, Instant.now(), seq, null, Instant.now());
    }

    private static PublishedMessage message(ClaimRow c) {
        return new PublishedMessage(c.id(), c.createdAt(), null, null, null,
                new Message(c.id(), "pool", "token", null, MediationType.HTTP, "http://localhost/x", c.messageGroup(),
                        false, c.mode()));
    }

    private static void jitter(int maxMicros) {
        var rnd = ThreadLocalRandom.current();
        if (rnd.nextInt(3) != 0) return;
        try {
            Thread.sleep(0, rnd.nextInt(maxMicros) * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /// The table (pending jobs per group), the broker (what was received, in
    /// order) and the publisher in front of it.
    private static final class Model implements DispatchPublisher {
        final Map<Integer, TreeSet<Integer>> pending = new TreeMap<>();
        /// Ids whose queue row was deleted by a claim: out of every claim until restored.
        final Set<String> claimed = new HashSet<>();
        final Map<Integer, List<Integer>> received = new TreeMap<>();
        final Map<Integer, Set<Integer>> seen = new TreeMap<>();
        final List<String> orderViolations = new ArrayList<>();

        Model() {
            for (int g = 0; g < GROUPS; g++) {
                var set = new TreeSet<Integer>();
                for (int s = 0; s < PER_GROUP; s++) set.add(s);
                pending.put(g, set);
                received.put(g, new ArrayList<>());
                seen.put(g, new HashSet<>());
            }
        }

        synchronized int pendingCount() {
            return pending.values().stream().mapToInt(Set::size).sum();
        }

        /// The claim: rows still in the queue, in `(group, sequence)` order, which it deletes.
        List<ClaimRow> claim(int limit) {
            jitter(300);
            synchronized (this) {
                var rows = new ArrayList<ClaimRow>();
                for (var e : pending.entrySet()) {
                    for (int seq : e.getValue()) {
                        if (rows.size() >= limit) return rows;
                        String id = id(e.getKey(), seq);
                        if (claimed.add(id)) rows.add(row(e.getKey(), seq));
                    }
                }
                return rows;
            }
        }

        /// The restore: the rows go back.
        void release(List<ClaimRow> rows) {
            jitter(200);
            synchronized (this) {
                for (ClaimRow r : rows) claimed.remove(r.id());
            }
        }

        /// The status update: removes the rows from the pending set; sometimes fails.
        int mark(List<ClaimRow> rows) {
            jitter(200);
            if (ThreadLocalRandom.current().nextInt(100) < 1) throw new IllegalStateException("random mark failure");
            synchronized (this) {
                int n = 0;
                for (ClaimRow r : rows) {
                    int g = Integer.parseInt(r.id().substring(2, 4));
                    if (pending.get(g).remove(Integer.parseInt(r.id().substring(5)))) n++;
                    claimed.remove(r.id());
                }
                return n;
            }
        }

        @Override
        public void publish(List<PublishedMessage> batch) throws PublishException {
            jitter(300);
            var rnd = ThreadLocalRandom.current();
            Set<String> failedGroups = new HashSet<>();
            List<String> unpublished = new ArrayList<>();
            for (PublishedMessage m : batch) {
                String group = m.message().messageGroupId();
                boolean fails = failedGroups.contains(group) || rnd.nextInt(100) < 3;
                if (fails) {
                    failedGroups.add(group);
                    unpublished.add(m.jobId());
                } else {
                    int g = Integer.parseInt(m.jobId().substring(2, 4));
                    int seq = Integer.parseInt(m.jobId().substring(5));
                    synchronized (this) {
                        received.get(g).add(seq);
                        if (seen.get(g).add(seq)) {
                            // A first delivery: it must be later than every earlier first delivery.
                            int highest = seen.get(g).stream().mapToInt(Integer::intValue).max().orElse(-1);
                            if (highest != seq) orderViolations.add(id(g, seq) + " first-delivered after a later job");
                        }
                    }
                }
            }
            if (!unpublished.isEmpty()) {
                throw new PublishException("random failure", null, unpublished);
            }
        }
    }

    /// The poller loop against the model, honouring the same steps as
    /// [PendingJobPoller#pollOnce] (permits, generation, snapshot, claim, the
    /// doomed-job check, submit, release).
    private static void runPoller(DispatchLanes lanes, Model model, int batch) throws InterruptedException {
        while (model.pendingCount() > 0) {
            int want = lanes.acquirePermits(batch);
            long generation = lanes.nextGeneration();
            var snapshot = lanes.inFlightSnapshot();
            var rows = model.claim(want);
            var submit = lanes.withoutGroupsBehindDoomedJobs(rows, snapshot);
            if (submit.size() != rows.size()) {
                var kept = new HashSet<String>();
                for (var r : submit) kept.add(r.id());
                model.release(rows.stream().filter(r -> !kept.contains(r.id())).toList());
            }
            lanes.submit(submit, generation);
            lanes.releasePermits(want - submit.size());
            if (rows.size() < want) Thread.sleep(1);
        }
    }

    @Test
    void whatTheBrokerReceivesIsInOrderPerGroupAndEveryJobArrivesUnderAdversarialLoad() throws Exception {
        int iterations = Integer.getInteger("stress.iterations", 5);
        var failures = new ArrayList<String>();
        for (int i = 0; i < iterations; i++) {
            var model = new Model();
            var config = SchedulerConfig.DEFAULTS.withBufferCapacity(24).withDispatchers(4).withLaneBatch(5);
            var metrics = new SchedulerMetrics(config.dispatchers());
            long claims = 0;
            try (var lanes = new DispatchLanes(config, model::mark, model::release, model, DispatchLanesStressTest::message,
                    metrics, System::nanoTime)) {
                lanes.start();
                var poller = new Thread(() -> {
                    try {
                        runPoller(lanes, model, 8);
                    } catch (InterruptedException e) {
                        // timed out
                    }
                }, "stress-poller");
                poller.start();
                poller.join(Duration.ofSeconds(30));
                if (poller.isAlive()) {
                    poller.interrupt();
                    poller.join(Duration.ofSeconds(5));
                    failures.add("iteration " + i + ": TIMEOUT with " + model.pendingCount()
                            + " jobs pending, claims=" + metrics.claims());
                    continue;
                }
                lanes.awaitIdle(Duration.ofSeconds(15));
            }
            synchronized (model) {
                if (!model.orderViolations.isEmpty()) {
                    failures.add("iteration " + i + ": " + model.orderViolations.getFirst());
                }
                for (int g = 0; g < GROUPS; g++) {
                    if (model.seen.get(g).size() != PER_GROUP) {
                        failures.add("iteration " + i + ": group " + g + " received " + model.seen.get(g).size());
                    }
                }
            }
        }
        assertThat(failures).as("%d iterations", iterations).isEmpty();
    }
}
