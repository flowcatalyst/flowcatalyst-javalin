package io.flowcatalyst.outbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// [GroupDistributor] — per-group FIFO ordering, the `blockOnError`
/// abort-the-rest behaviour, and bounded concurrency across groups (spec §5).
class GroupDistributorTest {

    private record Recording(String event) {
    }

    private static GroupDistributor.GroupTask task(String name, boolean succeeds, List<Recording> events) {
        return new GroupDistributor.GroupTask() {
            @Override
            public boolean dispatch() {
                events.add(new Recording("dispatch:" + name));
                return succeeds;
            }

            @Override
            public void onAbort() {
                events.add(new Recording("abort:" + name));
            }
        };
    }

    @Test
    void aDrainerRunsOneGroupsItemsStrictlyInOrder() throws InterruptedException {
        var events = new CopyOnWriteArrayList<Recording>();
        var done = new CountDownLatch(3);
        var distributor = new GroupDistributor(0, true);
        try {
            for (String name : List.of("a", "b", "c")) {
                distributor.submit("g1", new GroupDistributor.GroupTask() {
                    @Override
                    public boolean dispatch() {
                        events.add(new Recording("dispatch:" + name));
                        done.countDown();
                        return true;
                    }

                    @Override
                    public void onAbort() {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).as("all three items ran").isTrue();
        } finally {
            distributor.close();
        }
        assertThat(events).extracting(Recording::event).containsExactly("dispatch:a", "dispatch:b", "dispatch:c");
    }

    @Test
    void blockOnErrorAbortsTheQueuedRestAfterAPermanentFailure() throws InterruptedException {
        var events = new CopyOnWriteArrayList<Recording>();
        var done = new CountDownLatch(3);
        var distributor = new GroupDistributor(0, true);
        try {
            distributor.submit("g1", wrap(task("head", false, events), done));
            distributor.submit("g1", wrap(task("mid", true, events), done));
            distributor.submit("g1", wrap(task("tail", true, events), done));

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            distributor.close();
        }

        assertThat(events).extracting(Recording::event)
                .as("the head is dispatched and fails; the rest are aborted, never dispatched")
                .containsExactly("dispatch:head", "abort:mid", "abort:tail");
    }

    @Test
    void blockOnErrorDisabledLetsTheDrainerContinuePastAFailure() throws InterruptedException {
        var events = new CopyOnWriteArrayList<Recording>();
        var done = new CountDownLatch(3);
        var distributor = new GroupDistributor(0, false);
        try {
            distributor.submit("g1", wrap(task("head", false, events), done));
            distributor.submit("g1", wrap(task("mid", true, events), done));
            distributor.submit("g1", wrap(task("tail", true, events), done));

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            distributor.close();
        }

        assertThat(events).extracting(Recording::event)
                .as("blockOnError off: every item is dispatched, none aborted")
                .containsExactly("dispatch:head", "dispatch:mid", "dispatch:tail");
    }

    private static GroupDistributor.GroupTask wrap(GroupDistributor.GroupTask inner, CountDownLatch done) {
        return new GroupDistributor.GroupTask() {
            @Override
            public boolean dispatch() {
                try {
                    return inner.dispatch();
                } finally {
                    done.countDown();
                }
            }

            @Override
            public void onAbort() {
                try {
                    inner.onAbort();
                } finally {
                    done.countDown();
                }
            }
        };
    }

    /// `maxConcurrentGroups = 2`, 3 groups submitted at once: exactly two
    /// rendezvous on a two-party barrier (proving they ran concurrently) and
    /// the third times out waiting alone (proving it did NOT run
    /// concurrently with the other two) — an observed-concurrency assertion,
    /// not an absence that would hold either way.
    @Test
    void boundedConcurrencyAdmitsAtMostMaxConcurrentGroupsAtOnce() throws InterruptedException {
        var barrier = new CyclicBarrier(2);
        var paired = new AtomicInteger();
        var timedOutAlone = new AtomicInteger();
        var done = new CountDownLatch(3);
        var distributor = new GroupDistributor(2, true);
        try {
            for (String group : List.of("g1", "g2", "g3")) {
                distributor.submit(group, new GroupDistributor.GroupTask() {
                    @Override
                    public boolean dispatch() {
                        try {
                            barrier.await(1, TimeUnit.SECONDS);
                            paired.incrementAndGet();
                        } catch (TimeoutException e) {
                            timedOutAlone.incrementAndGet();
                        } catch (InterruptedException | BrokenBarrierException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                        return true;
                    }

                    @Override
                    public void onAbort() {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            distributor.close();
        }

        assertThat(paired).as("exactly two groups delivered at once — the barrier needs both").hasValue(2);
        assertThat(timedOutAlone).as("the third never found a partner — it was not admitted concurrently").hasValue(1);
    }

    @Test
    void unboundedConcurrencyRunsEveryGroupAtOnce() throws InterruptedException {
        var barrier = new CyclicBarrier(3);
        var done = new CountDownLatch(3);
        var distributor = new GroupDistributor(0, true);
        try {
            for (String group : List.of("g1", "g2", "g3")) {
                distributor.submit(group, new GroupDistributor.GroupTask() {
                    @Override
                    public boolean dispatch() {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError("all three groups must rendezvous when unbounded", e);
                        } finally {
                            done.countDown();
                        }
                        return true;
                    }

                    @Override
                    public void onAbort() {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            distributor.close();
        }
    }
}
