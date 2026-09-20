package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.context.ContextFactory;
import io.flowcatalyst.fnhost.load.FakeMetaspaceGauge;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.load.MetaspaceGuard;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// The metaspace headroom guard, wired into [Reconciler]
/// (`docs/spec/function-host-process.md` §3 item 1) — a real,
/// genuinely-loadable jar every time, so the ONLY variable is whether
/// [FakeMetaspaceGauge] reports headroom: a mutant that ignores the guard
/// entirely loads anyway (caught by the "below reserve" cases below); a
/// mutant that always refuses fails the "above reserve" cases. Together
/// these prove the load is refused WITHOUT being attempted (below reserve),
/// not merely reported as failed after an attempt.
class ReconcilerMetaspaceHeadroomTest {

    private static final DnsLabel POOL = new DnsLabel("pool");
    private static final long MIB = 1024L * 1024;
    // 512 MiB pool max ⇒ reserve = max(64 MiB, 5%) = 64 MiB (function-host-process.md §3 item 1).
    private static final long POOL_MAX = 512 * MIB;
    private static final long RESERVE = 64 * MIB;

    // ── warm ─────────────────────────────────────────────────────────────

    @Test
    void warmLoadIsRefusedWithoutAttemptingWhenBelowReserveAndSucceedsOnceHeadroomReturns(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "warm-v1", "warm-1");
        Digest digest = TestFixtures.digestOf(jar);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(POOL_MAX).used(POOL_MAX - RESERVE + 1); // 1 byte short
        List<String> loadErrors = new CopyOnWriteArrayList<>();
        Reconciler r = reconcilerWithGuard(fake, dir, registry, gauge, loadErrors);

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                digest, TestFixtures.fileRef(jar), true);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: attempt the load anyway despite insufficient headroom").isNull();
        HeartbeatReport first = fake.heartbeats().getLast();
        assertThat(first.loaded()).extracting(HeartbeatReport.LoadedEntry::state)
                .anySatisfy(state -> assertThat(state).isInstanceOfSatisfying(HeartbeatReport.LoadState.Failed.class,
                        f -> assertThat(f.error()).as("mutant: report OUT_OF_METASPACE instead — that means a real "
                                        + "OOM was caught, not a pre-emptive refusal")
                                .isEqualTo("LOAD:METASPACE_HEADROOM")));
        assertThat(loadErrors).contains("LOAD:METASPACE_HEADROOM");

        // Headroom returns (e.g. another function unloaded elsewhere): the SAME entry, next
        // cycle, loads normally — the guard re-checks every cycle rather than caching a refusal.
        gauge.used(0);
        r.reconcileOnce(Instant.now());
        LoadedFunction loaded = registry.peek(TestFixtures.ADDR_A);
        assertThat(loaded).as("mutant: cache the refusal instead of re-checking headroom next cycle").isNotNull();
        assertThat(loaded.version()).isEqualTo(1);
    }

    @Test
    void warmLoadSucceedsExactlyAtTheReserveBoundary(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "warm-v1", "warm-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(POOL_MAX).used(POOL_MAX - RESERVE); // free == reserve
        Reconciler r = reconcilerWithGuard(fake, dir, registry, gauge, new CopyOnWriteArrayList<>());

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), true);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: refuse even AT the reserve boundary (off-by-one)").isNotNull();
    }

    // ── unbounded max ⇒ guard off ────────────────────────────────────────

    @Test
    void unboundedMetaspaceMaxNeverRefuses(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "warm-v1", "warm-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(-1).used(Long.MAX_VALUE / 2);
        Reconciler r = reconcilerWithGuard(fake, dir, registry, gauge, new CopyOnWriteArrayList<>());

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), true);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: treat an unbounded (-1) max as a real, tiny cap").isNotNull();
        assertThat(gauge.gcRequests()).isZero();
    }

    // ── lazy: ensureLoaded ───────────────────────────────────────────────

    @Test
    void ensureLoadedIsRefusedWithoutAttemptingWhenBelowReserveAndLazyRouteStaysRecorded(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "lazy-v1", "lazy-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(POOL_MAX).used(POOL_MAX - RESERVE + 1);
        Reconciler r = reconcilerWithGuard(fake, dir, registry, gauge, new CopyOnWriteArrayList<>());

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.LAZY,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), false);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        // The route is recorded regardless of headroom (spec: "lazy entries still get their
        // routes — they cost nothing until called") — proven by the heartbeat reporting
        // REGISTERED, not FAILED, before any invocation was ever attempted.
        HeartbeatReport beforeInvocation = fake.heartbeats().getLast();
        assertThat(beforeInvocation.loaded()).extracting(HeartbeatReport.LoadedEntry::state)
                .as("mutant: refuse to even register the lazy route when headroom is short")
                .allMatch(HeartbeatReport.LoadState.Registered.class::isInstance);

        LoadedFunction result = r.ensureLoaded(TestFixtures.ADDR_A);
        assertThat(result).as("mutant: attempt the lazy load anyway despite insufficient headroom").isNull();

        gauge.used(0);
        LoadedFunction loadedAfterHeadroomReturns = r.ensureLoaded(TestFixtures.ADDR_A);
        assertThat(loadedAfterHeadroomReturns)
                .as("mutant: never retry a lazy load once headroom returns").isNotNull();
    }

    // ── pinned ───────────────────────────────────────────────────────────

    @Test
    void loadPinnedIsRefusedWithoutAttemptingWhenBelowReserveAndSucceedsOnceHeadroomReturns(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "cand-v2", "cand-2");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(POOL_MAX).used(POOL_MAX - RESERVE + 1);
        Reconciler r = reconcilerWithGuard(fake, dir, registry, gauge, new CopyOnWriteArrayList<>());

        DesiredDocument.Entry candidate = candidateEntry(TestFixtures.ADDR_A, "v2", 2,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar));
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(candidate), List.of(), List.of())));
        r.reconcileOnce(Instant.now()); // prepares (but never loads) the candidate

        assertThat(r.loadPinned(candidate))
                .as("mutant: attempt loading a pinned candidate anyway despite insufficient headroom").isNull();

        gauge.used(0);
        LoadedFunction pinned = r.loadPinned(candidate);
        assertThat(pinned).as("mutant: never retry a pinned load once headroom returns").isNotNull();
        pinned.close();
    }

    // ── at most one GC request per reconcile cycle, even across several loads ──

    @Test
    void atMostOneGcRequestPerReconcileCycleAcrossMultipleLoads(@TempDir Path dir) {
        Path jarA = TestFixtures.functionJar(dir, "multi-a", "a");
        Path jarB = TestFixtures.functionJar(dir, "multi-b", "b");
        Path jarC = TestFixtures.functionJar(dir, "multi-c", "c");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        // Stays below reserve even after the (no-op) GC the fake never reclaims from.
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(POOL_MAX).used(POOL_MAX - RESERVE + 1);
        Reconciler r = reconcilerWithGuard(fake, dir, registry, gauge, new CopyOnWriteArrayList<>());

        DesiredDocument.Entry a = liveEntry(TestFixtures.ADDR_A, "va", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jarA), TestFixtures.fileRef(jarA), true);
        DesiredDocument.Entry b = liveEntry(TestFixtures.ADDR_B, "vb", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jarB), TestFixtures.fileRef(jarB), true);
        DesiredDocument.Entry c = liveEntry(FunctionAddress.parse("recon.svc.c"), "vc", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jarC), TestFixtures.fileRef(jarC), true);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(a, b, c), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.snapshot()).as("all three refused — none should have loaded").isEmpty();
        assertThat(gauge.gcRequests())
                .as("mutant: request a GC per load attempt instead of once per reconcile cycle")
                .isEqualTo(1);

        // A second, later cycle is allowed one more.
        r.reconcileOnce(Instant.now());
        assertThat(gauge.gcRequests()).isEqualTo(2);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static Reconciler reconcilerWithGuard(FakeControlPlane fake, Path dir, FunctionRegistry registry,
                                                    FakeMetaspaceGauge gauge, List<String> loadErrorSink) {
        ContextFactory contextFactory = ContextFactory.production(16, fake, "host-1");
        Reconciler r = new Reconciler(POOL, "host-1", fake, new FileArtifactStore(dir.resolve("cache")),
                new Signatures.Off(), new JvmFunctionLoader(), registry, contextFactory, new MetaspaceGuard(gauge));
        r.setObserver(new ReconcileObserver() {
            @Override
            public void loadError(String reason) {
                loadErrorSink.add(reason);
            }
        });
        return r;
    }

    private static DesiredDocument.Entry liveEntry(FunctionAddress address, String versionId, int version,
                                                     DesiredDocument.Mode mode, Digest digest, String artifactRef,
                                                     boolean warm) {
        return new DesiredDocument.Entry(address, "fnc_" + address.name().value(), versionId, version,
                DesiredDocument.Role.LIVE, mode, digest, artifactRef, null, null,
                TestFixtures.jvmManifest(POOL.value(), warm), null, null, null, Map.of(), Map.of(), List.of());
    }

    private static DesiredDocument.Entry candidateEntry(FunctionAddress address, String versionId, int version,
                                                          Digest digest, String artifactRef) {
        return new DesiredDocument.Entry(address, "fnc_" + address.name().value(), versionId, version,
                DesiredDocument.Role.CANDIDATE, DesiredDocument.Mode.LAZY, digest, artifactRef, null, null,
                TestFixtures.jvmManifest(POOL.value(), false), null, null, null, Map.of(), Map.of(), List.of());
    }
}
