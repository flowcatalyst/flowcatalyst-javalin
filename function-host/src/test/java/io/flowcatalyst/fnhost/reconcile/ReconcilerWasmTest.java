package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.context.ContextFactory;
import io.flowcatalyst.fnhost.load.FakeMetaspaceGauge;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.load.MetaspaceGuard;
import io.flowcatalyst.fnhost.wasm.WasmFixtures;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.Request;
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

/// The reconciler's side of Wasm functions (`docs/spec/function-wasm-runtime.md`
/// §2, §6 tests 6 and 8, R11 as amended): the loader chosen by
/// `manifest.runtime()`, [MetaspaceGuard] run before a Wasm load exactly as
/// before a JVM one, and an unload closing the version.
class ReconcilerWasmTest {

    private static final DnsLabel POOL = new DnsLabel("pool");
    private static final FunctionAddress WASM_ADDR = TestFixtures.ADDR_B;
    private static final long MIB = 1024L * 1024;
    private static final long POOL_MAX = 512 * MIB;
    private static final long RESERVE = 64 * MIB;

    // ── R11: a wasm entry loads beside a JVM one ───────────────────────────

    @Test
    void aWasmEntryLoadsAndIsReportedLoadedBesideAJvmEntry(@TempDir Path dir) throws Exception {
        Path jar = TestFixtures.functionJar(dir, "jvm-v1", "jvm-1");
        Path wasm = WasmFixtures.guest(dir);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = reconciler(fake, dir, registry, new FakeMetaspaceGauge().max(-1));

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(jvmEntry(jar), wasmEntry(wasm, "w1", 1)), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A)).as("the JVM entry").isNotNull();
        LoadedFunction loaded = registry.peek(WASM_ADDR);
        assertThat(loaded).as("the Wasm entry, loaded by the Wasm loader").isNotNull();
        assertThat(fake.heartbeats().getLast().loaded())
                .allSatisfy(e -> assertThat(e.state()).isInstanceOf(HeartbeatReport.LoadState.Loaded.class));
        assertThat(loaded.invoke(request(), loaded.context()).status())
                .as("init() bound the version's context: the guest answers").isEqualTo(200);
    }

    // ── test 6: MetaspaceGuard refuses a Wasm load when headroom is gone ───

    @Test
    void theMetaspaceGuardRefusesAWasmLoadWithoutAttemptingItAndItLoadsOnceHeadroomReturns(@TempDir Path dir) {
        Path wasm = WasmFixtures.guest(dir);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(POOL_MAX).used(POOL_MAX - RESERVE + 1); // 1 byte short
        List<String> loadErrors = new CopyOnWriteArrayList<>();
        Reconciler r = reconciler(fake, dir, registry, gauge);
        r.setObserver(new ReconcileObserver() {
            @Override
            public void loadError(String reason) {
                loadErrors.add(reason);
            }
        });

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(wasmEntry(wasm, "w1", 1)), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(WASM_ADDR)).as("mutant: skip the guard for a Wasm load").isNull();
        assertThat(fake.heartbeats().getLast().loaded()).singleElement().satisfies(e -> assertThat(e.state())
                .isInstanceOfSatisfying(HeartbeatReport.LoadState.Failed.class,
                        f -> assertThat(f.error()).isEqualTo("LOAD:METASPACE_HEADROOM")));
        assertThat(loadErrors).contains("LOAD:METASPACE_HEADROOM");

        gauge.used(0);
        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(WASM_ADDR)).as("re-checked next cycle, not cached").isNotNull();
    }

    // ── test 8 (through the reconciler): leaving desired state closes it ───

    @Test
    void aWasmVersionThatLeavesDesiredStateIsClosedAndNoLongerServes(@TempDir Path dir) throws Exception {
        Path wasm = WasmFixtures.guest(dir);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = reconciler(fake, dir, registry, new FakeMetaspaceGauge().max(-1));

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(wasmEntry(wasm, "w1", 1)), List.of(), List.of())));
        r.reconcileOnce(Instant.now());
        LoadedFunction loaded = registry.peek(WASM_ADDR);
        assertThat(loaded.invoke(request(), loaded.context()).status()).isEqualTo(200);

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag2",
                new DesiredDocument(List.of(), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(WASM_ADDR)).isNull();
        assertThat(loaded.isClosed()).as("mutant: drop it from the registry without closing it").isTrue();
        assertThat(r.ensureLoaded(WASM_ADDR)).as("a later call finds nothing to serve").isNull();
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static Reconciler reconciler(FakeControlPlane fake, Path dir, FunctionRegistry registry,
                                         FakeMetaspaceGauge gauge) {
        return new Reconciler(POOL, "host-1", fake, new FileArtifactStore(dir.resolve("cache")),
                new Signatures.Off(), new JvmFunctionLoader(), registry,
                ContextFactory.production(4, fake, "host-1"), new MetaspaceGuard(gauge));
    }

    private static DesiredDocument.Entry jvmEntry(Path jar) {
        return new DesiredDocument.Entry(TestFixtures.ADDR_A, "fnc_a", "v1", 1, DesiredDocument.Role.LIVE,
                DesiredDocument.Mode.WARM, TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), null, null,
                TestFixtures.jvmManifest(POOL.value(), true), null, null, null, Map.of(), Map.of(), List.of(),
                List.of());
    }

    private static DesiredDocument.Entry wasmEntry(Path wasm, String versionId, int version) {
        return new DesiredDocument.Entry(WASM_ADDR, "fnc_b", versionId, version, DesiredDocument.Role.LIVE,
                DesiredDocument.Mode.WARM, TestFixtures.digestOf(wasm), TestFixtures.fileRef(wasm), null, null,
                WasmFixtures.manifest("echo", 2, 5000, 16), null, null, null, Map.of(), Map.of(), List.of(),
                List.of());
    }

    private static Request request() {
        return new Request(new io.flowcatalyst.function.FunctionAddress("recon", "svc", "b"), 1, "inv-1", "GET",
                "/x", null, null, Map.of(), Map.of(), Map.of(), new byte[0], "127.0.0.1", Caller.Anonymous.INSTANCE);
    }
}
