package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.pool.OrderedGroups.HeadFailure;
import io.flowcatalyst.router.wire.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// The ordered-group machinery, and with it the **Q1 ruling**
/// (`docs/spec/router.md` §2.6) — the largest deliberate deviation from the
/// Go router, which blocks the group for both ordered modes.
class OrderedGroupsTest {

    private final OrderedGroups groups = new OrderedGroups();

    @Test
    @DisplayName("messages in a group are drained in the order they arrived")
    void fifoWithinAGroup() {
        offerAll("orders", "a", "b", "c");

        assertThat(drainIds("orders")).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("only the message that finds a group idle starts a drainer")
    void oneDrainerPerGroup() {
        // Two drainers on one group would deliver its messages concurrently,
        // which is precisely what ordering forbids.
        assertThat(groups.offer(message("orders", "a", DispatchMode.BLOCK_ON_ERROR))).isTrue();
        assertThat(groups.offer(message("orders", "b", DispatchMode.BLOCK_ON_ERROR))).isFalse();
        assertThat(groups.offer(message("orders", "c", DispatchMode.BLOCK_ON_ERROR))).isFalse();
    }

    @Test
    @DisplayName("a different group gets its own drainer")
    void groupsDrainIndependently() {
        assertThat(groups.offer(message("orders", "a", DispatchMode.BLOCK_ON_ERROR))).isTrue();
        assertThat(groups.offer(message("invoices", "b", DispatchMode.BLOCK_ON_ERROR))).isTrue();
        assertThat(groups.groupCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("emptying a group releases it, so a later submit starts a fresh drainer")
    void emptyingReleasesTheGroup() {
        groups.offer(message("orders", "a", DispatchMode.BLOCK_ON_ERROR));
        groups.pollHead("orders");

        assertThat(groups.pollHead("orders")).isEmpty();
        assertThat(groups.groupCount()).isZero();
        // Otherwise every group id ever seen would leak an entry.
        assertThat(groups.offer(message("orders", "b", DispatchMode.BLOCK_ON_ERROR))).isTrue();
    }

    @Test
    @DisplayName("a retried head goes back to the front, keeping FIFO across the retry")
    void reFrontKeepsOrder() {
        offerAll("orders", "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        groups.reFront(head.retrying());

        assertThat(drainIds("orders")).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("NEXT_ON_ERROR: a failed head does not hold its siblings")
    void nextOnErrorContinues() {
        // The ruling, and the deviation: Go blocks the group here.
        offerAll("orders", DispatchMode.NEXT_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        var disposition = groups.onHeadFailure(head);

        assertThat(disposition).isEqualTo(new HeadFailure.Continue(head));
        // b and c are still queued and still in order.
        assertThat(drainIds("orders")).containsExactly("b", "c");
    }

    @Test
    @DisplayName("BLOCK_ON_ERROR: the group stops and its siblings come back to be ACKed")
    void blockOnErrorHandsBackSiblings() {
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        var disposition = groups.onHeadFailure(head);

        assertThat(disposition).isInstanceOf(HeadFailure.BlockGroup.class);
        var blocked = (HeadFailure.BlockGroup) disposition;
        assertThat(blocked.failed()).isEqualTo(head);
        // In FIFO order, so the caller can account for them as they would
        // have been delivered.
        assertThat(blocked.siblings().stream().map(QueuedMessage::id)).containsExactly("b", "c");
    }

    @Test
    @DisplayName("blocking empties the group and releases it, rather than parking it")
    void blockingReleasesTheGroup() {
        // The spec's phrase is "pending on the platform, not in router
        // memory": a head in backoff may wait indefinitely (Q2), so holding
        // siblings would pin unbounded memory and broker visibility.
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        groups.onHeadFailure(head);

        assertThat(groups.buffered()).isZero();
        assertThat(groups.groupCount()).isZero();
        // A redelivery after the platform re-sends starts a fresh drainer.
        assertThat(groups.offer(message("orders", "d", DispatchMode.BLOCK_ON_ERROR))).isTrue();
    }

    @Test
    @DisplayName("blocking a group with no siblings hands back nothing and still releases it")
    void blockingASoleMessage() {
        groups.offer(message("orders", "a", DispatchMode.BLOCK_ON_ERROR));
        var head = groups.pollHead("orders").orElseThrow();

        var disposition = groups.onHeadFailure(head);

        assertThat(((HeadFailure.BlockGroup) disposition).siblings()).isEmpty();
        assertThat(groups.groupCount()).isZero();
    }

    @Test
    @DisplayName("blocking one group leaves the others draining")
    void blockingIsPerGroup() {
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b");
        offerAll("invoices", DispatchMode.BLOCK_ON_ERROR, "x", "y");
        var head = groups.pollHead("orders").orElseThrow();

        groups.onHeadFailure(head);

        assertThat(drainIds("invoices")).containsExactly("x", "y");
    }

    @Test
    @DisplayName("releasing a drainer that still holds work asks for a fresh one")
    void releaseDrainerReportsRemainingWork() {
        // A drainer that gives up its slot mid-flight must not strand the
        // group: it holds work and nothing is draining it.
        offerAll("orders", "a", "b");
        var head = groups.pollHead("orders").orElseThrow();
        groups.reFront(head);

        assertThat(groups.releaseDrainer("orders")).isTrue();
        assertThat(groups.claimDrainer("orders")).isTrue();
        assertThat(groups.claimDrainer("orders")).isFalse();
    }

    @Test
    @DisplayName("releasing an emptied drainer removes the group")
    void releaseDrainerOnEmptyGroup() {
        groups.offer(message("orders", "a", DispatchMode.BLOCK_ON_ERROR));
        groups.pollHead("orders");

        assertThat(groups.releaseDrainer("orders")).isFalse();
        assertThat(groups.groupCount()).isZero();
    }

    @Test
    @DisplayName("an unknown group cannot be claimed")
    void claimUnknownGroup() {
        assertThat(groups.claimDrainer("nope")).isFalse();
    }

    @Test
    @DisplayName("ungrouped ordered messages share one bucket per pool")
    void ungroupedShareOneBucket() {
        // Q13, unruled: ordered messages with no group id all serialise
        // against "". Message.ordered() already prevents the router reaching
        // here for them, so this pins the buffer's own behaviour.
        groups.offer(message("", "a", DispatchMode.BLOCK_ON_ERROR));
        groups.offer(message("", "b", DispatchMode.BLOCK_ON_ERROR));

        assertThat(groups.groupCount()).isOne();
        assertThat(drainIds("")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("buffered counts every queued message and tracks every movement")
    void bufferedAccounting() {
        // Read on the submit path to decide backpressure, so a drift here
        // silently changes when the pool starts nacking.
        offerAll("orders", "a", "b");
        offerAll("invoices", "x");
        assertThat(groups.buffered()).isEqualTo(3);

        var head = groups.pollHead("orders").orElseThrow();
        assertThat(groups.buffered()).isEqualTo(2);

        groups.reFront(head);
        assertThat(groups.buffered()).isEqualTo(3);

        groups.onHeadFailure(groups.pollHead("orders").orElseThrow());
        assertThat(groups.buffered()).isOne();
    }

    @Test
    @DisplayName("stopping drains everything and releases every group")
    void drainAll() {
        offerAll("orders", "a", "b");
        offerAll("invoices", "x");

        assertThat(groups.drainAll().stream().map(QueuedMessage::id))
                .containsExactlyInAnyOrder("a", "b", "x");
        assertThat(groups.buffered()).isZero();
        assertThat(groups.groupCount()).isZero();
    }

    @Test
    @DisplayName("concurrent submits elect exactly one drainer")
    void concurrentSubmitsElectOneDrainer() throws Exception {
        int threads = 64;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var elected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int n = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (groups.offer(message("orders", "m" + n, DispatchMode.BLOCK_ON_ERROR))) {
                        elected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // Claiming the group and appending happen under one lock, so two
        // submits cannot both be told to start a drainer.
        assertThat(elected.get()).isOne();
        assertThat(groups.buffered()).isEqualTo(threads);
    }

    private void offerAll(String group, String... ids) {
        offerAll(group, DispatchMode.BLOCK_ON_ERROR, ids);
    }

    private void offerAll(String group, DispatchMode mode, String... ids) {
        IntStream.range(0, ids.length).forEach(i -> groups.offer(message(group, ids[i], mode)));
    }

    private List<String> drainIds(String group) {
        var drained = new java.util.ArrayList<String>();
        for (var head = groups.pollHead(group); head.isPresent(); head = groups.pollHead(group)) {
            drained.add(head.get().id());
        }
        return drained;
    }

    private static QueuedMessage message(String group, String id, DispatchMode mode) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h",
                        group.isEmpty() ? null : group, false, mode),
                "broker-" + id, "receipt-" + id, "queue-1");
    }
}
