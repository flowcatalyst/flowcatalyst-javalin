package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.pool.OrderedGroups.HeadFailure;
import io.flowcatalyst.router.wire.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /// The target ran the message and answered badly — R-57, terminal on the
    /// first attempt.
    private static MediationOutcome rejected() {
        return MediationOutcome.ErrorConfig.rejected(500, "boom");
    }

    /// The target could not be reached or was not ready.
    private static MediationOutcome unavailable(int status) {
        return new MediationOutcome.ErrorProcess(status, 30, "gateway");
    }

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
    @DisplayName("R-57: a rejected head never retries in place — it is terminal on the first attempt")
    void rejectedHeadNeverRetriesInPlace() {
        // The old retry-then-give-up budget is gone: the classifier now
        // decides "retry" (ErrorProcess, 502/503/504) vs "give up"
        // (ErrorConfig REJECTED) before OrderedGroups ever sees the outcome.
        offerAll("orders", DispatchMode.NEXT_ON_ERROR, "a", "b");
        var head = groups.pollHead("orders").orElseThrow();

        assertThat(groups.onHeadFailure(head, rejected()))
                .as("a fresh head (zero attempts) still goes straight to the per-mode decision")
                .isEqualTo(new HeadFailure.Continue(head));
    }

    @Test
    @DisplayName("NEXT_ON_ERROR: a rejected head is ACKed after one attempt, and the group continues")
    void nextOnErrorContinuesAfterOneAttempt() {
        // The ruling, and the deviation: Go blocks the group here.
        offerAll("orders", DispatchMode.NEXT_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        var disposition = groups.onHeadFailure(head, rejected());

        assertThat(disposition).isEqualTo(new HeadFailure.Continue(head));
        assertThat(drainIds("orders")).containsExactly("b", "c");
    }

    @Test
    @DisplayName("IMMEDIATE never reaches the ordered drainer, but the REJECTED switch stays total")
    void immediateRejectedIsContinueToo() {
        var head = message("orders", "a", DispatchMode.IMMEDIATE);

        assertThat(groups.onHeadFailure(head, rejected()))
                .isEqualTo(new HeadFailure.Continue(head));
    }

    /// The regression that motivated `MediationOutcome.disposition()`.
    ///
    /// These three outcomes mean **the message was never run**: the breaker
    /// was open, the limiter said wait, or the target asked for a delay. They
    /// carry no evidence at all about the message, so spending the rejection
    /// budget on them and then ACKing the group off the broker destroys work
    /// that nothing has yet found fault with — silently, at exactly the moment
    /// a target is unhealthy and the group is at its longest.
    ///
    /// The old `targetUnavailable()` default returned `false` for all three,
    /// so that is precisely what happened.
    @ParameterizedTest(name = "{0} at attempt 2 never ACKs the group")
    @MethodSource("neverRan")
    @DisplayName("an outcome that never ran the message cannot consume the group")
    void neverRanDoesNotDestroyTheGroup(String name, MediationOutcome outcome, boolean returnsToBroker) {
        for (var mode : DispatchMode.values()) {
            var groups = new OrderedGroups();
            offerAll(groups, "orders", mode, "a", "b", "c");
            var head = groups.pollHead("orders").orElseThrow();
            var spent = head.retrying().retrying();

            var failure = groups.onHeadFailure(spent, outcome);

            if (returnsToBroker) {
                // Handed back for redelivery: still on the broker, in order.
                assertThat(failure).isInstanceOf(HeadFailure.ReturnGroup.class);
                var returned = (HeadFailure.ReturnGroup) failure;
                assertThat(returned.head()).isEqualTo(spent);
                assertThat(returned.siblings().stream().map(QueuedMessage::id))
                        .as("%s under %s must hand every sibling back", name, mode)
                        .containsExactly("b", "c");
            } else {
                // Kept in place: the head is still the head, siblings untouched.
                assertThat(failure).isEqualTo(new HeadFailure.RetryHead(spent));
                assertThat(drainIds(groups, "orders"))
                        .as("%s under %s must not disturb the buffered siblings", name, mode)
                        .containsExactly("b", "c");
            }
        }
    }

    static List<Arguments> neverRan() {
        return List.of(
                // The breaker refused the call: the target is presumed down.
                Arguments.of("CircuitOpen", new MediationOutcome.CircuitOpen(30), true),
                // Our own limiter deferred it; the target never heard of it.
                Arguments.of("RateLimited", new MediationOutcome.RateLimited(5), false),
                // The target answered "not now" (429 / Retry-After).
                Arguments.of("Deferred", new MediationOutcome.Deferred(429, 5, "slow down"), false));
    }

    @Test
    @DisplayName("an endlessly deferring head does not pin its group for ever")
    void inPlaceRetriesAreBoundedForOrderedGroups() {
        // The same defect that was fixed for IMMEDIATE messages, still live on
        // the ordered path: a 429 forever kept the head AND everything queued
        // behind it in memory, off the broker, for the life of the process.
        // Worse here than for a lone message, because a whole group is held.
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();
        var deferring = new MediationOutcome.RateLimited(5);

        // Within budget the group keeps its head, which is the normal case.
        assertThat(groups.onHeadFailure(head, deferring))
                .isEqualTo(new HeadFailure.RetryHead(head));

        var spent = head;
        for (int i = 0; i < Pool.MAX_IN_PIPELINE_ATTEMPTS - 1; i++) {
            spent = spent.retrying();
        }
        var failure = groups.onHeadFailure(spent, deferring);

        assertThat(failure).isInstanceOf(HeadFailure.ReturnGroup.class);
        var returned = (HeadFailure.ReturnGroup) failure;
        // Handed back, not ACKed: nothing is wrong with any of them.
        assertThat(returned.head()).isEqualTo(spent);
        assertThat(returned.siblings().stream().map(QueuedMessage::id)).containsExactly("b", "c");
    }

    @Test
    @DisplayName("BLOCK_ON_ERROR: a rejected head and its siblings are ACKed after ONE attempt, and the group stops")
    void blockOnErrorHandsBackSiblings() {
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        var disposition = groups.onHeadFailure(head, rejected());

        assertThat(disposition).isInstanceOf(HeadFailure.BlockGroup.class);
        var blocked = (HeadFailure.BlockGroup) disposition;
        assertThat(blocked.failed()).isEqualTo(head);
        assertThat(blocked.siblings().stream().map(QueuedMessage::id)).containsExactly("b", "c");
    }

    @Test
    @DisplayName("an unavailable target returns the whole group to the broker, budget or not")
    void unavailableReturnsTheGroupImmediately() {
        // No number of retries makes a down target reachable, and nothing is
        // wrong with these messages — so they go back to the broker on the
        // first failure rather than burning the budget first.
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        var disposition = groups.onHeadFailure(head, unavailable(503));

        assertThat(disposition).isInstanceOf(HeadFailure.ReturnGroup.class);
        var returned = (HeadFailure.ReturnGroup) disposition;
        assertThat(returned.head()).isEqualTo(head);
        assertThat(returned.siblings().stream().map(QueuedMessage::id)).containsExactly("b", "c");
        assertThat(groups.groupCount()).isZero();
    }

    // Note: the old status-by-status unavailability table (500/501/502/…)
    // tested MediationOutcome.disposition()'s own conditional, not anything
    // OrderedGroups decides — R-57 moved that boundary into HttpMediator's
    // classifier (pinned there now: nonGatewayServerErrorsAreRejected /
    // gatewayErrorsReturnToBroker), and ErrorProcess.disposition() is a
    // constant, so there is nothing left here to parameterise by status.

    @Test
    @DisplayName("a transport error is unavailability whatever the mode")
    void transportErrorIsUnavailability() {
        offerAll("orders", DispatchMode.NEXT_ON_ERROR, "a", "b");
        var head = groups.pollHead("orders").orElseThrow();

        assertThat(groups.onHeadFailure(head, new MediationOutcome.ErrorConnection(30, "refused")))
                .isInstanceOf(HeadFailure.ReturnGroup.class);
    }

    @Test
    @DisplayName("blocking empties the group and releases it, rather than parking it")
    void blockingReleasesTheGroup() {
        // "pending on the platform, not in router memory": a head in backoff
        // may wait indefinitely (Q2), so holding siblings would pin unbounded
        // memory and broker visibility.
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b", "c");
        var head = groups.pollHead("orders").orElseThrow();

        groups.onHeadFailure(head, rejected());

        assertThat(groups.buffered()).isZero();
        assertThat(groups.groupCount()).isZero();
        assertThat(groups.offer(message("orders", "d", DispatchMode.BLOCK_ON_ERROR))).isTrue();
    }

    @Test
    @DisplayName("blocking a group with no siblings hands back nothing and still releases it")
    void blockingASoleMessage() {
        groups.offer(message("orders", "a", DispatchMode.BLOCK_ON_ERROR));
        var head = groups.pollHead("orders").orElseThrow();

        var disposition = groups.onHeadFailure(head, rejected());

        assertThat(((HeadFailure.BlockGroup) disposition).siblings()).isEmpty();
        assertThat(groups.groupCount()).isZero();
    }

    @Test
    @DisplayName("blocking one group leaves the others draining")
    void blockingIsPerGroup() {
        offerAll("orders", DispatchMode.BLOCK_ON_ERROR, "a", "b");
        offerAll("invoices", DispatchMode.BLOCK_ON_ERROR, "x", "y");
        var head = groups.pollHead("orders").orElseThrow();

        groups.onHeadFailure(head, rejected());

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

        groups.onHeadFailure(groups.pollHead("orders").orElseThrow(), rejected());
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
        offerAll(groups, group, mode, ids);
    }

    private static void offerAll(OrderedGroups target, String group, DispatchMode mode, String... ids) {
        IntStream.range(0, ids.length).forEach(i -> target.offer(message(group, ids[i], mode)));
    }

    private List<String> drainIds(String group) {
        return drainIds(groups, group);
    }

    private static List<String> drainIds(OrderedGroups target, String group) {
        var drained = new java.util.ArrayList<String>();
        for (var head = target.pollHead(group); head.isPresent(); head = target.pollHead(group)) {
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
