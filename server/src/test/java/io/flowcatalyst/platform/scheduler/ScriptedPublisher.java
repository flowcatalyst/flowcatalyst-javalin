package io.flowcatalyst.platform.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// A [DispatchPublisher] a test can steer from outside: it records the ids it
/// publishes in order, can block a job's publish on a latch (so a test controls
/// exactly when a lane is busy), and fails chosen jobs once — and, like the SQS
/// publisher, never publishes a later job of a group whose earlier job failed in
/// the same call (publisher rule 2 of [SqsDispatchPublisher]).
final class ScriptedPublisher implements DispatchPublisher {

    private final List<String> published = Collections.synchronizedList(new ArrayList<>());
    private final List<String> attempted = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failOnce = ConcurrentHashMap.newKeySet();
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
    private volatile boolean failEverything;
    private volatile int jitterMillis;

    /// The next time `id` is published it fails (once).
    ScriptedPublisher failOnce(String id) {
        failOnce.add(id);
        return this;
    }

    /// Every publish call sleeps a random 0..`maxMillis` first, so lanes run at
    /// different speeds (what exposes an ordering that only holds when they
    /// happen to run in step).
    ScriptedPublisher jitter(int maxMillis) {
        jitterMillis = maxMillis;
        return this;
    }

    ScriptedPublisher failEverything(boolean on) {
        failEverything = on;
        return this;
    }

    /// Blocks any publish call containing `id` until [#open] is called.
    ScriptedPublisher gate(String id) {
        gates.put(id, new CountDownLatch(1));
        entered.put(id, new CountDownLatch(1));
        return this;
    }

    void open(String id) {
        gates.get(id).countDown();
    }

    /// Waits (bounded) until a publish call containing `id` has started.
    boolean awaitEntered(String id) throws InterruptedException {
        return entered.get(id).await(15, TimeUnit.SECONDS);
    }

    List<String> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }

    List<String> attempted() {
        synchronized (attempted) {
            return List.copyOf(attempted);
        }
    }

    /// The published ids of one group, in publish order.
    List<String> publishedOf(String idPrefix) {
        return published().stream().filter(id -> id.startsWith(idPrefix)).toList();
    }

    @Override
    public void publish(List<PublishedMessage> batch) throws PublishException {
        for (PublishedMessage m : batch) {
            CountDownLatch in = entered.get(m.jobId());
            if (in != null) in.countDown();
            CountDownLatch gate = gates.get(m.jobId());
            if (gate != null) {
                try {
                    if (!gate.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate for " + m.jobId() + " was never opened");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
        if (jitterMillis > 0) {
            try {
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(jitterMillis + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        Set<String> failedGroups = new HashSet<>();
        List<String> unpublished = new ArrayList<>();
        for (PublishedMessage m : batch) {
            attempted.add(m.jobId());
            String group = m.message().messageGroupId();
            boolean fails = failEverything || failOnce.remove(m.jobId()) || (group != null && failedGroups.contains(group));
            if (fails) {
                unpublished.add(m.jobId());
                if (group != null) failedGroups.add(group);
            } else {
                published.add(m.jobId());
            }
        }
        if (!unpublished.isEmpty()) {
            throw new PublishException("scripted failure", null, unpublished);
        }
    }
}
