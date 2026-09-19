package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.function.artifact.ArtifactStore;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.SignatureVerifier;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.artifact.TestSigstore;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `Reconciler` (`docs/spec/function-host-reconciler.md` §1.2, §3, R1-R8,
/// R11). Every test uses a real [FileArtifactStore] over a `@TempDir` cache
/// and a real [JvmFunctionLoader] against jars [TestFixtures] builds — only
/// the control plane is faked. `FunctionRegistry` is always constructed by
/// the test and handed to the [Reconciler], so assertions read it directly
/// (`registry.peek(...)`) rather than through any test-only accessor.
class ReconcilerTest {

    private static final DnsLabel POOL = new DnsLabel("pool");

    // ── R1: warm loads eagerly + LOADED; lazy stays REGISTERED until ensureLoaded;
    //        candidate is never loaded ──────────────────────────────────────────

    @Test
    void warmLiveEntryIsLoadedEagerlyAndReportedLoaded(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "warm-v1", "warm-1");
        Digest digest = TestFixtures.digestOf(jar);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                digest, TestFixtures.fileRef(jar), null, null, true);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        LoadedFunction loaded = registry.peek(TestFixtures.ADDR_A);
        assertThat(loaded).as("mutant: never eagerly load a warm live entry").isNotNull();
        assertThat(loaded.version()).isEqualTo(1);

        HeartbeatReport last = fake.heartbeats().getLast();
        assertThat(last.loaded()).extracting(HeartbeatReport.LoadedEntry::state)
                .as("mutant: report REGISTERED instead of LOADED for a loaded warm entry")
                .allMatch(HeartbeatReport.LoadState.Loaded.class::isInstance);
    }

    @Test
    void lazyLiveEntryIsNotLoadedUntilEnsureLoadedAndLoadsOnceUnderTwoConcurrentCallers(@TempDir Path dir) throws Exception {
        Path jar = TestFixtures.functionJar(dir, "lazy-v1", "lazy-1");
        Digest digest = TestFixtures.digestOf(jar);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.LAZY,
                digest, TestFixtures.fileRef(jar), null, null, false);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: load a lazy entry eagerly instead of leaving it to first invocation").isNull();
        HeartbeatReport afterFirstCycle = fake.heartbeats().getLast();
        assertThat(afterFirstCycle.loaded()).extracting(HeartbeatReport.LoadedEntry::state)
                .allMatch(HeartbeatReport.LoadState.Registered.class::isInstance);

        // Two concurrent first-invocation callers must load it exactly once (spec
        // §1.2 step 3, R1) — proven by both returning the SAME instance: ensureLoaded
        // always constructs a brand-new LoadedFunction per call, so an unlocked race
        // (forced to genuinely overlap by the barrier) would deterministically
        // produce two DIFFERENT objects.
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<LoadedFunction> a = new AtomicReference<>();
        AtomicReference<LoadedFunction> b = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            await(barrier);
            a.set(r.ensureLoaded(TestFixtures.ADDR_A));
        });
        Thread t2 = new Thread(() -> {
            await(barrier);
            b.set(r.ensureLoaded(TestFixtures.ADDR_A));
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(a.get()).isNotNull();
        assertThat(a.get()).as("mutant: no per-address lock — two concurrent first callers load twice")
                .isSameAs(b.get());
        assertThat(registry.peek(TestFixtures.ADDR_A)).isSameAs(a.get());
    }

    @Test
    void candidateIsNeverLoadedAndIsReportedRegistered(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "cand-v2", "cand-2");
        Digest digest = TestFixtures.digestOf(jar);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry entry = new DesiredDocument.Entry(TestFixtures.ADDR_A, "fnc_a", "v2", 2,
                DesiredDocument.Role.CANDIDATE, DesiredDocument.Mode.LAZY, digest, TestFixtures.fileRef(jar), null,
                null, TestFixtures.jvmManifest(POOL.value(), true), null, null, null, Map.of(), Map.of(), List.of()); // warm manifest — must still never load
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A)).as("mutant: load a candidate").isNull();
        assertThat(fake.heartbeats().getLast().loaded()).extracting(HeartbeatReport.LoadedEntry::state)
                .allMatch(HeartbeatReport.LoadState.Registered.class::isInstance);
    }

    // ── R1b: live (lazy) + candidate, same address — ensureLoaded must never
    //         serve the candidate; a warm live entry must never be displaced by one ──

    @Test
    void liveLazyPlusCandidateSameAddress_ensureLoadedReturnsLiveNeverTheCandidate(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "r1b-lazy-v1", "r1b-lazy-1");
        Path jar2 = TestFixtures.functionJar(dir, "r1b-lazy-v2", "r1b-lazy-2");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry liveV1 = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.LAZY,
                TestFixtures.digestOf(jar1), TestFixtures.fileRef(jar1), null, null, false);
        DesiredDocument.Entry candidateV2 = new DesiredDocument.Entry(TestFixtures.ADDR_A, "fnc_a", "v2", 2,
                DesiredDocument.Role.CANDIDATE, DesiredDocument.Mode.LAZY, TestFixtures.digestOf(jar2),
                TestFixtures.fileRef(jar2), null, null, TestFixtures.jvmManifest(POOL.value(), false), null, null, null, Map.of(), Map.of(), List.of());
        DesiredDocument doc = new DesiredDocument(List.of(liveV1, candidateV2), List.of(), List.of());
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", doc));

        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: eagerly load a lazy live entry (unrelated to this test's own mutants, but would "
                        + "invalidate the setup)").isNull();

        LoadedFunction loaded = r.ensureLoaded(TestFixtures.ADDR_A);
        assertThat(loaded)
                .as("mutant: let the candidate through the role check in the load step — ensureLoaded found nothing routed")
                .isNotNull();
        assertThat(loaded.version())
                .as("mutant: candidate overwrites the lazyRoutes entry — ensureLoaded served an unpromoted version")
                .isEqualTo(1);
        assertThat(registry.peek(TestFixtures.ADDR_A).version()).isEqualTo(1);

        // A second cycle (same document, NotModified) so the heartbeat reflects the
        // load ensureLoaded just performed directly (spec §1.2 step 5).
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.NotModified());
        r.reconcileOnce(Instant.now());
        HeartbeatReport report = fake.heartbeats().getLast();
        HeartbeatReport.LoadedEntry v1Entry = report.loaded().stream().filter(e -> e.version() == 1).findFirst().orElseThrow();
        HeartbeatReport.LoadedEntry v2Entry = report.loaded().stream().filter(e -> e.version() == 2).findFirst().orElseThrow();
        assertThat(v1Entry.state()).as("the live entry, now loaded via ensureLoaded, must report LOADED")
                .isInstanceOf(HeartbeatReport.LoadState.Loaded.class);
        assertThat(v2Entry.state()).as("mutant: the candidate ever reports anything other than REGISTERED")
                .isInstanceOf(HeartbeatReport.LoadState.Registered.class);
    }

    @Test
    void liveWarmPlusCandidateSameAddress_candidateNeverLoadsOrDisplacesTheWarmLive(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "r1b-warm-v1", "r1b-warm-1");
        Path jar2 = TestFixtures.functionJar(dir, "r1b-warm-v2", "r1b-warm-2");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry liveV1 = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jar1), TestFixtures.fileRef(jar1), null, null, true);
        DesiredDocument.Entry candidateV2 = new DesiredDocument.Entry(TestFixtures.ADDR_A, "fnc_a", "v2", 2,
                DesiredDocument.Role.CANDIDATE, DesiredDocument.Mode.LAZY, TestFixtures.digestOf(jar2),
                TestFixtures.fileRef(jar2), null, null, TestFixtures.jvmManifest(POOL.value(), false), null, null, null, Map.of(), Map.of(), List.of());
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(liveV1, candidateV2), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: let the candidate through the role check — the warm live entry never loaded")
                .isNotNull();
        assertThat(registry.peek(TestFixtures.ADDR_A).version())
                .as("mutant: the candidate loaded and displaced the warm live version").isEqualTo(1);

        HeartbeatReport report = fake.heartbeats().getLast();
        HeartbeatReport.LoadedEntry v1Entry = report.loaded().stream().filter(e -> e.version() == 1).findFirst().orElseThrow();
        HeartbeatReport.LoadedEntry v2Entry = report.loaded().stream().filter(e -> e.version() == 2).findFirst().orElseThrow();
        assertThat(v1Entry.state()).isInstanceOf(HeartbeatReport.LoadState.Loaded.class);
        assertThat(v2Entry.state()).as("mutant: the candidate is ever reported as anything but REGISTERED")
                .isInstanceOf(HeartbeatReport.LoadState.Registered.class);
    }

    // ── R3b: the registry itself refusing a load (capacity, all warm) is a
    //         FAILED entry, never an exception out of reconcileOnce ─────────

    @Test
    void registryFullRefusalBecomesAFailedEntryAndDoesNotAbortTheRestOfTheDocument(@TempDir Path dir) {
        FunctionAddress addrA = FunctionAddress.parse("recon.svc.r3b-new");
        FunctionAddress addrB = TestFixtures.ADDR_A;
        FunctionAddress addrC = TestFixtures.ADDR_B;

        Path jarB1 = TestFixtures.functionJar(dir, "r3b-b-v1", "r3b-b-1");
        Path jarC1 = TestFixtures.functionJar(dir, "r3b-c-v1", "r3b-c-1");
        Path jarC2 = TestFixtures.functionJar(dir, "r3b-c-v2", "r3b-c-2");
        Path jarA1 = TestFixtures.functionJar(dir, "r3b-a-v1", "r3b-a-1");

        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(2); // capacity: exactly enough for B and C, none spare
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry entryB1 = liveEntry(addrB, "b-v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jarB1), TestFixtures.fileRef(jarB1), null, null, true);
        DesiredDocument.Entry entryC1 = liveEntry(addrC, "c-v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jarC1), TestFixtures.fileRef(jarC1), null, null, true);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entryB1, entryC1), List.of(), List.of())));
        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(addrB).version()).isEqualTo(1);
        assertThat(registry.peek(addrC).version()).isEqualTo(1);

        // Cycle 2: registry is completely full (2/2, both warm). A NEW address (A)
        // is ordered FIRST — it can only fail (no address-swap skips the capacity
        // check for a brand-new address). B is unchanged. C is promoted to v2 for
        // the SAME address it already occupies, which — unlike A — never consults
        // capacity at all (an existing address's slot is simply replaced), so it
        // must still succeed even though the registry never had room to spare and
        // A's attempt, right before it in iteration order, failed.
        DesiredDocument.Entry entryA1 = liveEntry(addrA, "a-v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jarA1), TestFixtures.fileRef(jarA1), null, null, true);
        DesiredDocument.Entry entryC2 = liveEntry(addrC, "c-v2", 2, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jarC2), TestFixtures.fileRef(jarC2), null, null, true);
        DesiredDocument doc2 = new DesiredDocument(List.of(entryA1, entryB1, entryC2), List.of(), List.of());
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag2", doc2));

        assertThatCode(() -> r.reconcileOnce(Instant.now()))
                .as("mutant: let the registry's IllegalStateException propagate out of reconcileOnce")
                .doesNotThrowAnyException();

        assertThat(registry.peek(addrA)).as("A must never have loaded — the registry refused it").isNull();
        assertThat(registry.peek(addrB).version())
                .as("mutant: A's registry-full failure must not disturb B, an unrelated already-loaded address")
                .isEqualTo(1);
        assertThat(registry.peek(addrC).version())
                .as("mutant: A's failure aborted the load loop before C's own (unrelated, same-address) promote ran")
                .isEqualTo(2);

        HeartbeatReport report = fake.heartbeats().getLast();
        HeartbeatReport.LoadedEntry aEntry = report.loaded().stream()
                .filter(e -> e.address().equals(addrA)).findFirst().orElseThrow();
        assertThat(aEntry.state()).isInstanceOf(HeartbeatReport.LoadState.Failed.class);
        assertThat(((HeartbeatReport.LoadState.Failed) aEntry.state()).error())
                .as("mutant: any reason string other than the one spec §1.2 names").isEqualTo("LOAD:REGISTRY_FULL");
    }

    // ── R2: promote — new before old ─────────────────────────────────────

    @Test
    void promoteSwapsToTheNewVersionBeforeClosingTheOld(@TempDir Path dir) throws Exception {
        Path jar1 = TestFixtures.functionJar(dir, "promote-v1", "p1");
        Path jar2 = TestFixtures.functionJar(dir, "promote-v2", "p2");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1", docWithOneWarmLive(1, jar1)));
        r.reconcileOnce(Instant.now());
        LoadedFunction v1 = registry.peek(TestFixtures.ADDR_A);
        assertThat(v1.version()).isEqualTo(1);
        v1.retain(); // forces close() to block until release() — a real gate, not a sleep

        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag2", docWithOneWarmLive(2, jar2)));
        Thread cycle2 = new Thread(() -> r.reconcileOnce(Instant.now()));
        cycle2.start();

        // Bounded poll (same idiom as RouterStartupOrderTest#await) for the swap to
        // become visible — with the "unload before load" mutant, close(v1) blocks
        // forever on the retain() above and put() never runs, so this poll times out.
        boolean sawV2 = pollUntil(() -> {
            LoadedFunction current = registry.peek(TestFixtures.ADDR_A);
            return current != null && current.version() == 2;
        }, Duration.ofSeconds(3));
        assertThat(sawV2).as("mutant: unload before load — v2 must be serving before v1 finishes closing").isTrue();

        v1.release();
        cycle2.join(Duration.ofSeconds(5).toMillis());
        assertThat(cycle2.isAlive()).isFalse();
        assertThatThrownBy(() -> v1.invoke(null, null)).as("v1 must actually be closed once release() unblocks it")
                .isInstanceOf(IllegalStateException.class);
    }

    // ── R3: prepare/load failure leaves the old version serving, retried next cycle ──

    @Test
    void digestMismatchLeavesTheOldVersionServingAndIsRetriedOnceFixed(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "r3-v1", "r3-1");
        Path jar2 = TestFixtures.functionJar(dir, "r3-v2", "r3-2");
        Digest realDigest2 = TestFixtures.digestOf(jar2);
        Digest wrongDigest2 = Digest.parse("sha256:" + "0".repeat(64));

        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1", docWithOneWarmLive(1, jar1)));
        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(TestFixtures.ADDR_A).version()).isEqualTo(1);

        DesiredDocument.Entry badV2 = liveEntry(TestFixtures.ADDR_A, "v2", 2, DesiredDocument.Mode.WARM,
                wrongDigest2, TestFixtures.fileRef(jar2), null, null, true);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag2",
                new DesiredDocument(List.of(badV2), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A).version())
                .as("mutant: swap before checking the prepare outcome").isEqualTo(1);
        HeartbeatReport afterFailure = fake.heartbeats().getLast();
        HeartbeatReport.LoadedEntry v2Entry = afterFailure.loaded().stream()
                .filter(e -> e.version() == 2).findFirst().orElseThrow();
        assertThat(v2Entry.state()).isInstanceOf(HeartbeatReport.LoadState.Failed.class);
        assertThat(((HeartbeatReport.LoadState.Failed) v2Entry.state()).error())
                .as("mutant: swallow the reason category").startsWith("ARTIFACT:DigestMismatch");

        // Fix the fixture: same versionId, correct digest — must be retried, never cached as a permanent failure.
        DesiredDocument.Entry fixedV2 = liveEntry(TestFixtures.ADDR_A, "v2", 2, DesiredDocument.Mode.WARM,
                realDigest2, TestFixtures.fileRef(jar2), null, null, true);
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag3",
                new DesiredDocument(List.of(fixedV2), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A).version())
                .as("mutant: cache the failure forever instead of retrying").isEqualTo(2);
    }

    @Test
    void aRefusedLoadLeavesTheOldVersionServing(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "refuse-v1", "ref-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1", docWithOneWarmLive(1, jar1)));
        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(TestFixtures.ADDR_A).version()).isEqualTo(1);

        // A jar with no such entrypoint class: JvmFunctionLoader refuses ENTRYPOINT_NOT_FOUND.
        Path jar2 = TestFixtures.functionJar(dir, "refuse-v2", "ref-2");
        Manifest badEntrypoint = Manifest.readStored(Json.MAPPER.readTree(
                "{\"runtime\":\"jvm\",\"entrypoint\":\"nope.NoSuchClass\",\"pool\":\"" + POOL.value() + "\",\"warm\":true}"));
        DesiredDocument.Entry entry = new DesiredDocument.Entry(TestFixtures.ADDR_A, "fnc_a", "v2", 2,
                DesiredDocument.Role.LIVE, DesiredDocument.Mode.WARM, TestFixtures.digestOf(jar2),
                TestFixtures.fileRef(jar2), null, null, badEntrypoint, null, null, null, Map.of(), Map.of(), List.of());
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag2",
                new DesiredDocument(List.of(entry), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A).version())
                .as("mutant: swap on a Refused outcome").isEqualTo(1);
        HeartbeatReport.LoadedEntry v2 = fake.heartbeats().getLast().loaded().stream()
                .filter(e -> e.version() == 2).findFirst().orElseThrow();
        assertThat(v2.state()).isInstanceOf(HeartbeatReport.LoadState.Failed.class);
    }

    // ── R4: signer equality is exact ─────────────────────────────────────

    @Test
    void signerMismatchIsRejectedEvenWhenTheIssuerMatches(@TempDir Path dir) throws Exception {
        Instant integratedTime = Instant.now();
        TestSigstore.Ecosystem eco = TestSigstore.build(TestSigstore.LeafSpec.valid(
                integratedTime.minusSeconds(60), integratedTime.plusSeconds(3600)));
        var trustRoot = eco.trustRootFor(integratedTime.minusSeconds(3600), null, integratedTime.minusSeconds(3600), null);
        Signatures signatures = new Signatures.Required(new SignatureVerifier(trustRoot));

        Path jar = TestFixtures.functionJar(dir, "signer-v1", "sig-1");
        Digest digest = TestFixtures.digestOf(jar);
        String bundle = TestSigstore.validBundleJson(eco, rawDigest(digest), integratedTime, 42L);

        // The real signer (from the leaf certificate, TestSigstore.LeafSpec.valid) is
        // issuer https://example.test/issuer, subject https://example.test/workflow.yml.
        // The entry records a DIFFERENT subject under the SAME issuer — kills both
        // "skip the comparison" (would load) and "compare issuer only" (issuer matches).
        SignerIdentity recorded = new SignerIdentity("https://example.test/issuer", "https://example.test/OTHER.yml");
        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                digest, TestFixtures.fileRef(jar), bundle, recorded, true);

        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = new Reconciler(POOL, "host-1", fake, fileArtifactStore(dir), signatures,
                new JvmFunctionLoader(), registry);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: skip the signer-equality comparison, or compare issuer only").isNull();
        HeartbeatReport.LoadedEntry loaded = fake.heartbeats().getLast().loaded().getFirst();
        assertThat(((HeartbeatReport.LoadState.Failed) loaded.state()).error()).isEqualTo("SIGNER_MISMATCH");
    }

    // ── R5: Off loads unsigned; Required does not ────────────────────────

    @Test
    void signaturesOffLoadsAnUnsignedEntry(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "off-v1", "off-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = new Reconciler(POOL, "host-1", fake, fileArtifactStore(dir), new Signatures.Off(),
                new JvmFunctionLoader(), registry);
        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), null, null, true);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A)).as("mutant: Off refuses an unsigned entry too").isNotNull();
    }

    @Test
    void signaturesRequiredRefusesAnUnsignedEntry(@TempDir Path dir) throws Exception {
        Instant now = Instant.now();
        TestSigstore.Ecosystem eco = TestSigstore.build(TestSigstore.LeafSpec.valid(now.minusSeconds(60), now.plusSeconds(3600)));
        var trustRoot = eco.trustRootFor(now.minusSeconds(3600), null, now.minusSeconds(3600), null);
        Signatures signatures = new Signatures.Required(new SignatureVerifier(trustRoot));

        Path jar = TestFixtures.functionJar(dir, "req-v1", "req-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = new Reconciler(POOL, "host-1", fake, fileArtifactStore(dir), signatures,
                new JvmFunctionLoader(), registry);
        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), null, null, true); // no bundle, no signer
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: Required loads an entry with no bundle").isNull();
        HeartbeatReport.LoadedEntry loaded = fake.heartbeats().getLast().loaded().getFirst();
        assertThat(((HeartbeatReport.LoadState.Failed) loaded.state()).error()).isEqualTo("UNSIGNED");
    }

    /// Isolates the "no bundle" half of the check from the "no recorded signer"
    /// half: [#signaturesRequiredRefusesAnUnsignedEntry] above has NEITHER, so it
    /// cannot tell whether the bundle check alone is load-bearing — a `signer` IS
    /// recorded here, so only removing the bundle check (and not the signer check)
    /// would let this load.
    @Test
    void signaturesRequiredRefusesAMissingBundleEvenWhenASignerIsRecorded(@TempDir Path dir) throws Exception {
        Instant now = Instant.now();
        TestSigstore.Ecosystem eco = TestSigstore.build(TestSigstore.LeafSpec.valid(now.minusSeconds(60), now.plusSeconds(3600)));
        var trustRoot = eco.trustRootFor(now.minusSeconds(3600), null, now.minusSeconds(3600), null);
        Signatures signatures = new Signatures.Required(new SignatureVerifier(trustRoot));

        Path jar = TestFixtures.functionJar(dir, "req-nobundle-v1", "req-nobundle-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = new Reconciler(POOL, "host-1", fake, fileArtifactStore(dir), signatures,
                new JvmFunctionLoader(), registry);
        SignerIdentity recorded = new SignerIdentity("https://example.test/issuer", "https://example.test/workflow.yml");
        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), null, recorded, true); // signer recorded, no bundle
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: Required loads an entry with a recorded signer but no bundle").isNull();
        HeartbeatReport.LoadedEntry loaded = fake.heartbeats().getLast().loaded().getFirst();
        assertThat(((HeartbeatReport.LoadState.Failed) loaded.state()).error()).isEqualTo("UNSIGNED");
    }

    /// Distinct from [#signaturesRequiredRefusesAnUnsignedEntry] above (no bundle at
    /// all): here the bundle is real and validly signed, but the platform never
    /// recorded a signer for it — spec §0/§1.2's "an entry with no bundle OR no
    /// recorded signer is a failure under `Required`" has two independent halves,
    /// and this pins the second one on its own (a test that only ever sends "no
    /// bundle, no signer" together cannot tell the two checks apart).
    @Test
    void signaturesRequiredRefusesAValidlySignedBundleWithNoRecordedSigner(@TempDir Path dir) throws Exception {
        Instant now = Instant.now();
        TestSigstore.Ecosystem eco = TestSigstore.build(TestSigstore.LeafSpec.valid(now.minusSeconds(60), now.plusSeconds(3600)));
        var trustRoot = eco.trustRootFor(now.minusSeconds(3600), null, now.minusSeconds(3600), null);
        Signatures signatures = new Signatures.Required(new SignatureVerifier(trustRoot));

        Path jar = TestFixtures.functionJar(dir, "req-nosigner-v1", "req-nosigner-1");
        Digest digest = TestFixtures.digestOf(jar);
        String bundle = TestSigstore.validBundleJson(eco, rawDigest(digest), now, 5L);

        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = new Reconciler(POOL, "host-1", fake, fileArtifactStore(dir), signatures,
                new JvmFunctionLoader(), registry);
        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                digest, TestFixtures.fileRef(jar), bundle, null, true); // real bundle, no recorded signer
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: Required loads a validly-signed bundle whose signer was never recorded").isNull();
        HeartbeatReport.LoadedEntry loaded = fake.heartbeats().getLast().loaded().getFirst();
        assertThat(((HeartbeatReport.LoadState.Failed) loaded.state()).error()).isEqualTo("UNSIGNED");
    }

    // ── R6: control-plane outage vs NotModified ──────────────────────────

    @Test
    void controlPlaneOutageKeepsWhatIsLoadedAndStillHeartbeats(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "outage-v1", "outage-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", docWithOneWarmLive(1, jar)));
        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(TestFixtures.ADDR_A)).isNotNull();
        int heartbeatsBefore = fake.heartbeatCallCount();

        fake.desiredStateReturns((p, etag) -> {
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "simulated outage");
        });
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: treat a control-plane exception as an empty document and unload everything")
                .isNotNull();
        assertThat(fake.heartbeatCallCount()).as("a failed fetch must still attempt a heartbeat")
                .isGreaterThan(heartbeatsBefore);
    }

    @Test
    void notModifiedDoesNotRefetchOrReloadButStillHeartbeats(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "nm-v1", "nm-1");
        AtomicInteger fetchCount = new AtomicInteger();
        ArtifactStore counting = countingStore(fileArtifactStore(dir), fetchCount);
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = new Reconciler(POOL, "host-1", fake, counting, new Signatures.Off(),
                new JvmFunctionLoader(), registry);

        DesiredDocument doc = docWithOneWarmLive(1, jar);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", doc));
        r.reconcileOnce(Instant.now());
        assertThat(fetchCount.get()).isEqualTo(1);
        LoadedFunction firstLoad = registry.peek(TestFixtures.ADDR_A);
        int heartbeatsBefore = fake.heartbeatCallCount();

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.NotModified());
        r.reconcileOnce(Instant.now());

        assertThat(fetchCount.get()).as("mutant: refetch on NotModified").isEqualTo(1);
        assertThat(registry.peek(TestFixtures.ADDR_A)).as("mutant: reload on NotModified").isSameAs(firstLoad);
        assertThat(fake.heartbeatCallCount()).as("mutant: skip the heartbeat on NotModified")
                .isGreaterThan(heartbeatsBefore);
    }

    // ── R7: unload clauses ────────────────────────────────────────────────

    /// Deliberately does NOT also promote the address to a new version: a
    /// same-cycle promotion would close the old version through the ordinary
    /// "new before old" swap (R2) regardless of whether the explicit unload
    /// list ran at all, so that shape cannot actually isolate clause 4a. Here
    /// the address stays LIVE, at the SAME version, throughout — the unload
    /// list is the only thing that can remove it (not clause 4b's "no longer
    /// live" either, since `keep` still contains it).
    @Test
    void explicitUnloadListClosesAndRemoves(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "r7a-v1", "r7a-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry v1 = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.LAZY,
                TestFixtures.digestOf(jar1), TestFixtures.fileRef(jar1), null, null, false);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(v1), List.of(), List.of())));
        r.reconcileOnce(Instant.now());
        LoadedFunction loaded = r.ensureLoaded(TestFixtures.ADDR_A);
        assertThat(loaded.version()).isEqualTo(1);

        DesiredDocument.UnloadRef unloadV1 = new DesiredDocument.UnloadRef(TestFixtures.ADDR_A, 1);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag2",
                new DesiredDocument(List.of(v1), List.of(unloadV1), List.of()))); // same v1 still LIVE, plus unload it
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: ignore the explicit unload list").isNull();
        assertThatThrownBy(() -> loaded.invoke(null, null)).as("mutant: never actually close the unloaded version")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAddressNoLongerLiveAtAllIsClosedAndRemoved(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "r7b-v1", "r7b-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", docWithOneWarmLive(1, jar1)));
        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(TestFixtures.ADDR_A)).isNotNull();

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag2",
                new DesiredDocument(List.of(), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: only the explicit unload list drops an address, never 'no longer live'").isNull();
    }

    @Test
    void aLazyFunctionAlreadyLoadedIsReplacedAsSoonAsLiveNamesADifferentVersion(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "r7c-v1", "r7c-1");
        Path jar2 = TestFixtures.functionJar(dir, "r7c-v2", "r7c-2");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry v1 = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.LAZY,
                TestFixtures.digestOf(jar1), TestFixtures.fileRef(jar1), null, null, false);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(v1), List.of(), List.of())));
        r.reconcileOnce(Instant.now());
        LoadedFunction loadedV1 = r.ensureLoaded(TestFixtures.ADDR_A);
        assertThat(loadedV1.version()).isEqualTo(1);

        DesiredDocument.Entry v2 = liveEntry(TestFixtures.ADDR_A, "v2", 2, DesiredDocument.Mode.LAZY,
                TestFixtures.digestOf(jar2), TestFixtures.fileRef(jar2), null, null, false);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag2",
                new DesiredDocument(List.of(v2), List.of(), List.of())));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: leave a resident lazy function on its old version until it idles out")
                .isNotNull();
        assertThat(registry.peek(TestFixtures.ADDR_A).version()).isEqualTo(2);
    }

    /// Design check (item 5): `loadLocks` (one lock object per address, so two
    /// concurrent [Reconciler#ensureLoaded] calls for the same address load it
    /// exactly once) only ever grew before this fix — the map must not carry a
    /// lock forever for an address that has left the document entirely.
    @Test
    void thePerAddressLoadLockIsRemovedWhenTheAddressLeavesTheDocument(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "loadlock-v1", "loadlock-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.LAZY,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), null, null, false);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));
        r.reconcileOnce(Instant.now());
        r.ensureLoaded(TestFixtures.ADDR_A); // creates the per-address lock entry
        assertThat(r.hasLoadLockForTest(TestFixtures.ADDR_A)).isTrue();

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag2",
                new DesiredDocument(List.of(), List.of(), List.of()))); // address no longer live at all
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A)).isNull();
        assertThat(r.hasLoadLockForTest(TestFixtures.ADDR_A))
                .as("loadLocks must not keep growing forever for addresses no longer in the document")
                .isFalse();
    }

    @Test
    void anIdleLazyEntryIsClosedAfterOneHourButKeepsItsRouteForReloadOnDemand(@TempDir Path dir) {
        Path jar = TestFixtures.functionJar(dir, "r7d-v1", "r7d-1");
        FakeControlPlane fake = new FakeControlPlane();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FunctionRegistry registry = new FunctionRegistry(50, clock);
        Reconciler r = new Reconciler(POOL, "host-1", fake, fileArtifactStore(dir), new Signatures.Off(),
                new JvmFunctionLoader(), registry);

        DesiredDocument.Entry entry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.LAZY,
                TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), null, null, false);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(entry), List.of(), List.of())));
        r.reconcileOnce(clock.instant());
        LoadedFunction first = r.ensureLoaded(TestFixtures.ADDR_A); // stamps lastAccessed at clock's current time
        assertThat(first).isNotNull();

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        // Same document (NotModified) — idle eviction must still run every cycle
        // (Reconciler's own deliberate reading, see its javadoc and the handback report).
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.NotModified());
        r.reconcileOnce(clock.instant());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: never evict an idle lazy entry").isNull();
        assertThatThrownBy(() -> first.invoke(null, null)).isInstanceOf(IllegalStateException.class);

        LoadedFunction reloaded = r.ensureLoaded(TestFixtures.ADDR_A);
        assertThat(reloaded).as("mutant: drop the route too, instead of only closing").isNotNull();
        assertThat(reloaded.version()).isEqualTo(1);
    }

    // ── R8: one unreadable entry is dropped and reported; the rest applies ──

    @Test
    void oneUnreadableEntryIsDroppedAndReportedWithoutTakingDownTheRestOfTheDocument(@TempDir Path dir) {
        Path goodJar = TestFixtures.functionJar(dir, "r8-good", "r8-good");
        Digest goodDigest = TestFixtures.digestOf(goodJar);

        String badAddressJson = """
                {"address":"not..valid","functionId":"fnc_b","versionId":"v9","version":9,"role":"live",
                 "mode":"warm","digest":"sha256:%s","artifactRef":"file:///nope"}
                """.formatted("0".repeat(64));
        String goodJson = """
                {"address":"%s","functionId":"fnc_a","versionId":"v1","version":1,"role":"live","mode":"warm",
                 "digest":"%s","artifactRef":"%s",
                 "manifest":{"runtime":"jvm","entrypoint":"%s","pool":"%s","warm":true}}
                """.formatted(TestFixtures.ADDR_A.render(), goodDigest.value(), TestFixtures.fileRef(goodJar),
                TestFixtures.ENTRYPOINT, POOL.value());
        String body = "{\"functions\":[" + goodJson + "," + badAddressJson + "]}";

        DesiredDocument doc = DesiredDocument.parse(body);
        assertThat(doc.functions()).as("mutant: fail the whole parse instead of dropping the bad entry").hasSize(1);
        assertThat(doc.unreadable()).isEmpty(); // bad address ⇒ unreadable even for the report (no address)

        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", doc));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A)).as("the rest of the document is still applied").isNotNull();
    }

    @Test
    void anUnreadableEntryWithAValidAddressAndVersionIsReportedFailedInTheHeartbeat(@TempDir Path dir) {
        String badDigestJson = """
                {"address":"%s","functionId":"fnc_a","versionId":"v9","version":9,"role":"live","mode":"warm",
                 "digest":"not-a-digest","artifactRef":"file:///nope"}
                """.formatted(TestFixtures.ADDR_A.render());
        DesiredDocument doc = DesiredDocument.parse("{\"functions\":[" + badDigestJson + "]}");

        assertThat(doc.functions()).isEmpty();
        assertThat(doc.unreadable()).as("mutant: only report an unreadable entry when address AND version are readable")
                .hasSize(1);
        assertThat(doc.unreadable().getFirst().address()).isEqualTo(TestFixtures.ADDR_A);
        assertThat(doc.unreadable().getFirst().version()).isEqualTo(9);

        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", doc));
        r.reconcileOnce(Instant.now());

        HeartbeatReport.LoadedEntry reported = fake.heartbeats().getLast().loaded().getFirst();
        assertThat(reported.version()).isEqualTo(9);
        assertThat(reported.state()).isInstanceOf(HeartbeatReport.LoadState.Failed.class);
    }

    /// The unload step's own reading of §1.1's "never takes the rest of the
    /// document down with it": a parse failure for an address that WAS
    /// already loaded and good must not itself read as "no longer live" and
    /// get it unloaded — spec §1.1's "an unreadable entry's address is
    /// protected from unloading for that cycle".
    @Test
    void anUnreadableEntrysAddressProtectsAnAlreadyLoadedGoodVersionFromBeingUnloaded(@TempDir Path dir) {
        Path jar1 = TestFixtures.functionJar(dir, "r8c-v1", "r8c-1");
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1", docWithOneWarmLive(1, jar1)));
        r.reconcileOnce(Instant.now());
        assertThat(registry.peek(TestFixtures.ADDR_A)).isNotNull();

        // Cycle 2: the SAME address's platform entry is now malformed (a bad digest) —
        // it drops out of doc.functions() entirely and becomes an UnreadableEntry
        // instead, so it is no longer a `live` entry in `keep`'s usual sense.
        String badDigestJson = """
                {"address":"%s","functionId":"fnc_a","versionId":"v9","version":9,"role":"live","mode":"warm",
                 "digest":"not-a-digest","artifactRef":"file:///nope"}
                """.formatted(TestFixtures.ADDR_A.render());
        DesiredDocument doc2 = DesiredDocument.parse("{\"functions\":[" + badDigestJson + "]}");
        assertThat(doc2.functions()).isEmpty();
        assertThat(doc2.unreadable()).hasSize(1);
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag2", doc2));
        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A))
                .as("mutant: drop the unload-protection of an unreadable entry's address — "
                        + "a parse failure must never itself unload an already-good version")
                .isNotNull();
        assertThat(registry.peek(TestFixtures.ADDR_A).version()).isEqualTo(1);
    }

    // ── R11: wasm ⇒ RUNTIME_UNSUPPORTED, others unaffected ───────────────

    @Test
    void wasmRuntimeIsRefusedWithRuntimeUnsupportedWhileOtherEntriesLoadNormally(@TempDir Path dir) {
        Path jvmJar = TestFixtures.functionJar(dir, "r11-jvm", "r11-jvm");
        Path wasmJar = TestFixtures.functionJar(dir, "r11-wasm", "r11-wasm"); // content irrelevant — never loaded
        FakeControlPlane fake = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler r = offReconciler(fake, dir, registry);

        DesiredDocument.Entry jvmEntry = liveEntry(TestFixtures.ADDR_A, "v1", 1, DesiredDocument.Mode.WARM,
                TestFixtures.digestOf(jvmJar), TestFixtures.fileRef(jvmJar), null, null, true);
        DesiredDocument.Entry wasmEntry = new DesiredDocument.Entry(TestFixtures.ADDR_B, "fnc_b", "w1", 1,
                DesiredDocument.Role.LIVE, DesiredDocument.Mode.WARM, TestFixtures.digestOf(wasmJar),
                TestFixtures.fileRef(wasmJar), null, null, TestFixtures.wasmManifest(POOL.value()), null, null, null, Map.of(), Map.of(), List.of());
        fake.desiredStateReturns((p, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(jvmEntry, wasmEntry), List.of(), List.of())));

        r.reconcileOnce(Instant.now());

        assertThat(registry.peek(TestFixtures.ADDR_A)).as("the jvm entry is unaffected").isNotNull();
        assertThat(registry.peek(TestFixtures.ADDR_B)).as("mutant: attempt to load a wasm entry").isNull();
        HeartbeatReport.LoadedEntry wasmReport = fake.heartbeats().getLast().loaded().stream()
                .filter(e -> e.address().equals(TestFixtures.ADDR_B)).findFirst().orElseThrow();
        assertThat(((HeartbeatReport.LoadState.Failed) wasmReport.state()).error()).isEqualTo("RUNTIME_UNSUPPORTED");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static Reconciler offReconciler(FakeControlPlane fake, Path dir, FunctionRegistry registry) {
        return new Reconciler(POOL, "host-1", fake, fileArtifactStore(dir), new Signatures.Off(),
                new JvmFunctionLoader(), registry);
    }

    private static ArtifactStore fileArtifactStore(Path dir) {
        return new FileArtifactStore(dir.resolve("cache"));
    }

    private static DesiredDocument docWithOneWarmLive(int version, Path jar) {
        return new DesiredDocument(List.of(liveEntry(TestFixtures.ADDR_A, "v" + version, version,
                DesiredDocument.Mode.WARM, TestFixtures.digestOf(jar), TestFixtures.fileRef(jar), null, null, true)),
                List.of(), List.of());
    }

    private static DesiredDocument.Entry liveEntry(FunctionAddress address, String versionId, int version,
                                                     DesiredDocument.Mode mode, Digest digest, String artifactRef,
                                                     String signatureBundle, SignerIdentity signer, boolean warm) {
        return new DesiredDocument.Entry(address, "fnc_" + address.name().value(), versionId, version,
                DesiredDocument.Role.LIVE, mode, digest, artifactRef, signatureBundle, signer,
                TestFixtures.jvmManifest(POOL.value(), warm), null, null, null, Map.of(), Map.of(), List.of());
    }

    private static byte[] rawDigest(Digest digest) {
        String hex = digest.value().substring("sha256:".length());
        return HexFormat.of().parseHex(hex);
    }

    private static ArtifactStore countingStore(ArtifactStore delegate, AtomicInteger counter) {
        return (artifactRef, expected) -> {
            counter.incrementAndGet();
            return delegate.fetch(artifactRef, expected);
        };
    }

    private static boolean pollUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration d) {
            instant = instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
