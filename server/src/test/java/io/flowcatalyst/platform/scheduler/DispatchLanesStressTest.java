package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// A real multi-threaded run of the poller's claim loop against the lanes:
/// many groups, random publish failures and jitter, several lanes — with a
/// model of the table as the "database" the claim reads. What the fake broker
/// receives, per group, must be each job exactly once and in order (the model
/// takes a job off the pending set when it is published, so a duplicate is not
/// possible here: a first delivery out of order is the only way to fail).
///
/// This is a probabilistic backstop for the ordering rule; the two interleavings
/// it rests on are driven deterministically by
/// [DispatchLanesTest#aClaimRunningWhileAFailureSettlesCannotOvertakeWhicheverStepComesFirst]
/// and `PendingJobPollerConcurrencyTest`'s snapshot test.
class DispatchLanesStressTest {

    private static final int GROUPS = 24;
    private static final int PER_GROUP = 40;

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

    /// The table: pending jobs per group, in sequence order.
    private static final class Model implements DispatchPublisher {
        final Map<Integer, TreeSet<Integer>> pending = new TreeMap<>();
        final Map<Integer, List<Integer>> received = new TreeMap<>();
        final int failPercent;
        final int jitterMicros;

        Model(int failPercent, int jitterMicros) {
            this.failPercent = failPercent;
            this.jitterMicros = jitterMicros;
            for (int g = 0; g < GROUPS; g++) {
                var set = new TreeSet<Integer>();
                for (int s = 0; s < PER_GROUP; s++) set.add(s);
                pending.put(g, set);
                received.put(g, new ArrayList<>());
            }
        }

        synchronized int pendingCount() {
            return pending.values().stream().mapToInt(Set::size).sum();
        }

        /// The claim query: pending rows in `(group, sequence)` order, minus the
        /// excluded (in-flight) ids, up to `limit`.
        synchronized List<ClaimRow> claim(int limit, Set<String> inFlight) {
            var rows = new ArrayList<ClaimRow>();
            for (var e : pending.entrySet()) {
                for (int seq : e.getValue()) {
                    if (rows.size() >= limit) return rows;
                    if (!inFlight.contains(id(e.getKey(), seq))) rows.add(row(e.getKey(), seq));
                }
            }
            return rows;
        }

        @Override
        public void publish(List<PublishedMessage> batch) throws PublishException {
            var rnd = ThreadLocalRandom.current();
            if (jitterMicros > 0 && rnd.nextInt(3) == 0) {
                try {
                    Thread.sleep(0, rnd.nextInt(jitterMicros) * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            Set<String> failedGroups = new HashSet<>();
            List<String> unpublished = new ArrayList<>();
            for (PublishedMessage m : batch) {
                String group = m.message().messageGroupId();
                boolean fails = failedGroups.contains(group) || rnd.nextInt(100) < failPercent;
                if (fails) {
                    failedGroups.add(group);
                    unpublished.add(m.jobId());
                } else {
                    int g = Integer.parseInt(m.jobId().substring(2, 4));
                    int seq = Integer.parseInt(m.jobId().substring(5));
                    synchronized (this) {
                        received.get(g).add(seq);
                        pending.get(g).remove(seq);
                    }
                }
            }
            if (!unpublished.isEmpty()) {
                throw new PublishException("random failure", null, unpublished);
            }
        }
    }

    /// The poller's loop, against the model: permits, generation, snapshot,
    /// claim, submit, release.
    private static void runPoller(DispatchLanes lanes, Model model, int batch, Instant deadline) throws Exception {
        while (model.pendingCount() > 0) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("stress run did not finish: "
                    + model.pendingCount() + " jobs still pending");
            int want = lanes.acquirePermits(batch);
            long generation = lanes.nextGeneration();
            var snapshot = new HashSet<>(lanes.inFlightSnapshot());
            var rows = model.claim(want, snapshot);
            lanes.submit(rows, generation);
            lanes.releasePermits(want - rows.size());
            if (rows.size() < want) Thread.sleep(1);
        }
    }

    @Test
    void whatTheBrokerReceivesIsInOrderPerGroupUnderRandomFailuresAndManyLanes() throws Exception {
        for (int round = 0; round < 6; round++) {
            var model = new Model(8, 400);
            var config = SchedulerConfig.DEFAULTS.withBufferCapacity(60 + round * 20)
                    .withDispatchers(2 + round % 4).withLaneBatch(1 + round * 7);
            var metrics = new SchedulerMetrics(config.dispatchers());
            try (var lanes = new DispatchLanes(config, new DispatchJobRepository(DATA_SOURCE), model,
                    DispatchLanesStressTest::message, metrics)) {
                lanes.start();
                runPoller(lanes, model, 25 + round * 10, Instant.now().plus(Duration.ofSeconds(60)));
                assertThat(lanes.awaitIdle(Duration.ofSeconds(15))).isTrue();
            }
            for (int g = 0; g < GROUPS; g++) {
                var expected = new ArrayList<Integer>();
                for (int s = 0; s < PER_GROUP; s++) expected.add(s);
                assertThat(model.received.get(g)).as("round %d group %d", round, g).containsExactlyElementsOf(expected);
            }
            assertThat(metrics.unpublishedTotal()).as("failures really happened").isPositive();
        }
    }
}
