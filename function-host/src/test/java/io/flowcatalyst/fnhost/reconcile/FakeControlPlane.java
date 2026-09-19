package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.DnsLabel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/// A scripted [ControlPlane] (`docs/spec/function-host-reconciler.md` §3):
/// every call to [#desiredState] is answered by the script currently
/// installed with [#desiredStateReturns]; every [#heartbeat] is recorded
/// (and can also be scripted to fail, for the "heartbeat itself fails" half
/// of R6). Both call counts are tracked for tests that assert "no refetch,
/// no reload" (R6).
final class FakeControlPlane implements ControlPlane {

    @FunctionalInterface
    interface DesiredStateScript {
        Fetched apply(DnsLabel pool, String knownEtag) throws ControlPlaneException, InterruptedException;
    }

    @FunctionalInterface
    interface HeartbeatScript {
        void apply(HeartbeatReport report) throws ControlPlaneException;
    }

    private volatile DesiredStateScript desiredStateScript = (pool, etag) -> new Fetched.NotModified();
    private volatile HeartbeatScript heartbeatScript = report -> {
    };

    private final List<HeartbeatReport> heartbeats = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger desiredStateCalls = new AtomicInteger();
    private final AtomicInteger heartbeatCalls = new AtomicInteger();

    void desiredStateReturns(DesiredStateScript script) {
        this.desiredStateScript = script;
    }

    void heartbeatDoes(HeartbeatScript script) {
        this.heartbeatScript = script;
    }

    List<HeartbeatReport> heartbeats() {
        synchronized (heartbeats) {
            return List.copyOf(heartbeats);
        }
    }

    int desiredStateCallCount() {
        return desiredStateCalls.get();
    }

    int heartbeatCallCount() {
        return heartbeatCalls.get();
    }

    @Override
    public Fetched desiredState(DnsLabel pool, String knownEtag) throws ControlPlaneException {
        desiredStateCalls.incrementAndGet();
        try {
            return desiredStateScript.apply(pool, knownEtag);
        } catch (InterruptedException e) {
            // Mirrors HttpControlPlane's own contract: an interrupted control-plane
            // call surfaces as UNAVAILABLE with the flag restored, never a bare
            // InterruptedException past this checked signature.
            Thread.currentThread().interrupt();
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "interrupted", e);
        }
    }

    @Override
    public void heartbeat(HeartbeatReport report) throws ControlPlaneException {
        heartbeatCalls.incrementAndGet();
        heartbeats.add(report);
        heartbeatScript.apply(report);
    }
}
