package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/// Test double for [InvocationObserver] — records every call verbatim, so a
/// test can assert exactly which method [FnHttpServer] called, with exactly
/// which arguments, for a given HTTP scenario (`docs/spec/function-host-process.md`
/// §2's P2-P4 wiring half; `FnMetricsTest` pins the OTHER half — that a real
/// [io.flowcatalyst.fnhost.metrics.FnMetrics] turns these same calls into the
/// right series).
final class RecordingObserver implements InvocationObserver {

    record Refused(String outcome, FunctionAddress address) {
    }

    record Completed(FunctionAddress address, int version, String outcome, Duration elapsed) {
    }

    private final List<Refused> refusals = new CopyOnWriteArrayList<>();
    private final List<FunctionAddress> entries = new CopyOnWriteArrayList<>();
    private final List<FunctionAddress> exits = new CopyOnWriteArrayList<>();
    private final List<Completed> completions = new CopyOnWriteArrayList<>();
    private final AtomicInteger permitsReadyCalls = new AtomicInteger();

    @Override
    public void permitsReady(Permits permits) {
        permitsReadyCalls.incrementAndGet();
    }

    @Override
    public void refused(String outcome, FunctionAddress address) {
        refusals.add(new Refused(outcome, address));
    }

    @Override
    public void entered(FunctionAddress address) {
        entries.add(address);
    }

    @Override
    public void exited(FunctionAddress address) {
        exits.add(address);
    }

    @Override
    public void completed(FunctionAddress address, int version, String outcome, Duration elapsed) {
        completions.add(new Completed(address, version, outcome, elapsed));
    }

    List<Refused> refusals() {
        return List.copyOf(refusals);
    }

    List<FunctionAddress> entries() {
        return List.copyOf(entries);
    }

    List<FunctionAddress> exits() {
        return List.copyOf(exits);
    }

    List<Completed> completions() {
        return List.copyOf(completions);
    }

    int permitsReadyCallCount() {
        return permitsReadyCalls.get();
    }
}
