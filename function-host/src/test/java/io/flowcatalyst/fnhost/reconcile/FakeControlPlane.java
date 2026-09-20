package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.function.EventEmitException;
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
/// no reload" (R6). Public (widened for D3, same reasoning as D1's
/// `FixtureJars`, `function-host-listener.md` §6): the listener's own tests
/// (package `io.flowcatalyst.fnhost.http`) build their `Reconciler` fixtures
/// through this same fake rather than a second copy.
public final class FakeControlPlane implements ControlPlane {

    @FunctionalInterface
    public interface DesiredStateScript {
        Fetched apply(DnsLabel pool, String knownEtag) throws ControlPlaneException, InterruptedException;
    }

    @FunctionalInterface
    public interface HeartbeatScript {
        void apply(HeartbeatReport report) throws ControlPlaneException;
    }

    @FunctionalInterface
    public interface EmitScript {
        void apply(ControlPlane.EmitRequest request) throws EventEmitException;
    }

    private volatile DesiredStateScript desiredStateScript = (pool, etag) -> new Fetched.NotModified();
    private volatile HeartbeatScript heartbeatScript = report -> {
    };
    private volatile EmitScript emitScript = request -> {
    };

    private final List<HeartbeatReport> heartbeats = Collections.synchronizedList(new ArrayList<>());
    private final List<ControlPlane.EmitRequest> emits = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger desiredStateCalls = new AtomicInteger();
    private final AtomicInteger heartbeatCalls = new AtomicInteger();
    private final AtomicInteger emitCalls = new AtomicInteger();

    public void desiredStateReturns(DesiredStateScript script) {
        this.desiredStateScript = script;
    }

    public void heartbeatDoes(HeartbeatScript script) {
        this.heartbeatScript = script;
    }

    /// Scripts [#emit] (`docs/spec/function-context.md` §3, D4c) — e.g. throw
    /// [EventEmitException] to simulate the platform refusing an event.
    public void emitDoes(EmitScript script) {
        this.emitScript = script;
    }

    public List<HeartbeatReport> heartbeats() {
        synchronized (heartbeats) {
            return List.copyOf(heartbeats);
        }
    }

    public List<ControlPlane.EmitRequest> emits() {
        synchronized (emits) {
            return List.copyOf(emits);
        }
    }

    public int desiredStateCallCount() {
        return desiredStateCalls.get();
    }

    public int heartbeatCallCount() {
        return heartbeatCalls.get();
    }

    public int emitCallCount() {
        return emitCalls.get();
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

    @Override
    public void emit(ControlPlane.EmitRequest request) {
        emitCalls.incrementAndGet();
        emits.add(request);
        emitScript.apply(request);
    }
}
