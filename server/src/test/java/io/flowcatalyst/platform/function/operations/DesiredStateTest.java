package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// `DesiredState.build` (spec `function-api.md` §6.1, §8 P13, P14). Every
/// clause of P13 pinned with its own fixture; P14's determinism pinned by
/// building twice and comparing bytes, and by seeding a 3-function fixture
/// in reverse-address order to prove the sort is on `address`, not
/// insertion order.
class DesiredStateTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(DS);
    private static final FunctionHostRepository hosts = new FunctionHostRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final DesiredState DESIRED = new DesiredState(functions, versions, hosts);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static Manifest manifestForPool(String pool, boolean warm) {
        String json = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","pool":"%s","warm":%s}
                """.formatted(pool, warm);
        return Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static Function createFunction(String tag) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("ds" + RUN), new DnsLabel("svc"), new DnsLabel(tag));
        Function f = Function.create("app_" + RUN, address, FunctionOwner.ofClientId("clt_" + RUN), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static void save(Function f) {
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
    }

    private static FunctionVersion publish(Function f, int version, Manifest manifest) {
        FunctionVersion v = FunctionVersion.publish(f.id(), version, "oci://artifact", digest(f.id() + version),
                null, null, null, manifest, "prn_publisher", Instant.now());
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    private static void save(FunctionVersion v) {
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
    }

    private static Digest digest(String seed) {
        String hex = Integer.toHexString(seed.hashCode()) + "0".repeat(64);
        return Digest.parse("sha256:" + hex.substring(0, 64));
    }

    /// Note (task): `Promote` does not exist yet (slice B3) — a live alias is
    /// set directly through `Function.promote` + `FunctionRepository.persist`.
    private static Function promote(Function f, FunctionVersion v) {
        Function.Promoted p = f.promote(Function.LIVE, v, "prn_promoter", Instant.now());
        save(p.function());
        return p.function();
    }

    private static void heartbeatHost(String hostId, DnsLabel pool, Instant lastHeartbeat, List<FunctionHost.LoadedVersion> loaded) {
        FunctionHost h = new FunctionHost(hostId, pool, FunctionHost.HostState.ACTIVE, loaded, lastHeartbeat, lastHeartbeat);
        uow.inTransaction(tx -> {
            hosts.persist(h, tx.dbTx());
            return null;
        });
    }

    // ── P13: live ─────────────────────────────────────────────────────────

    @Test
    void liveVersionIsIncludedWithRoleLive() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("live" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false));
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        DesiredState.FunctionEntry entry = doc.functions().getFirst();
        assertThat(entry.address()).isEqualTo(f.address().render());
        assertThat(entry.role()).isEqualTo("live");
        assertThat(entry.version()).isEqualTo(1);
        assertThat(entry.versionId()).isEqualTo(v.id());
        assertThat(entry.digest()).isEqualTo(v.digest().value());
        assertThat(entry.mode()).as("mutant: mode ignores manifest.warm() for a live entry").isEqualTo("lazy");
    }

    @Test
    void liveEntryModeIsWarmWhenTheManifestSaysSo() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("warm" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), true));
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        assertThat(doc.functions().getFirst().mode()).isEqualTo("warm");
    }

    // ── P13: newer candidate ──────────────────────────────────────────────

    @Test
    void aNewerPublishedVersionIsIncludedAsAnAlwaysLazyCandidate() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("cand" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        save(v1.markReady(Instant.now())); // v1 leaves PUBLISHED so it is unambiguously "live", not also "newest published"
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), true)); // warm manifest — candidate must still be lazy

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(2);
        assertThat(doc.functions()).extracting(DesiredState.FunctionEntry::role).containsExactly("live", "candidate");
        DesiredState.FunctionEntry candidate = doc.functions().get(1);
        assertThat(candidate.version()).isEqualTo(2);
        assertThat(candidate.versionId()).isEqualTo(v2.id());
        assertThat(candidate.mode()).as("mutant: a candidate is never warm, regardless of its own manifest").isEqualTo("lazy");
    }

    /// R3 (`function-registry.md` §9): a function that has NEVER been
    /// promoted still delivers its first `PUBLISHED` version as a candidate —
    /// otherwise a first version could never become `READY` at all, since
    /// only a `READY` version may be promoted (B3).
    @Test
    void aFirstPublishedVersionWithNoLiveYetIsStillACandidate() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("nolive" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).as("mutant: require a live version before ever considering a candidate").hasSize(1);
        assertThat(doc.functions().getFirst().role()).isEqualTo("candidate");
        assertThat(doc.functions().getFirst().version()).isEqualTo(1);
        assertThat(doc.functions().getFirst().versionId()).isEqualTo(v1.id());
    }

    // ── P13: candidate OLDER than live is absent ─────────────────────────

    @Test
    void aPublishedVersionOlderThanLiveIsNotACandidate() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("older" + fresh());
        // Two versions published; only v2 ever gets marked ready and promoted (the
        // function-registry.md M10 scenario) — v1 is left dangling, forever PUBLISHED,
        // at a LOWER version number than live.
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), false));
        save(v2.markReady(Instant.now()));
        promote(f, v2);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).as("mutant: drop the > live version check").hasSize(1);
        assertThat(doc.functions().getFirst().role()).isEqualTo("live");
        assertThat(doc.functions().getFirst().version()).isEqualTo(2);
    }

    // ── P13: per-version pool filter ─────────────────────────────────────

    @Test
    void aFunctionWithLiveInOnePoolAndCandidateInAnotherAppearsOnceInEachPoolsDocument() {
        DnsLabel poolA = new DnsLabel("poola" + fresh());
        DnsLabel poolB = new DnsLabel("poolb" + fresh());
        Function f = createFunction("split" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(poolA.value(), false));
        save(v1.markReady(Instant.now()));
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(poolB.value(), false));

        DesiredState.Document docA = DESIRED.build(poolA, Instant.now());
        assertThat(docA.functions()).hasSize(1);
        assertThat(docA.functions().getFirst().role()).isEqualTo("live");
        assertThat(docA.functions().getFirst().version()).isEqualTo(1);

        DesiredState.Document docB = DESIRED.build(poolB, Instant.now());
        assertThat(docB.functions()).hasSize(1);
        assertThat(docB.functions().getFirst().role()).isEqualTo("candidate");
        assertThat(docB.functions().getFirst().version()).isEqualTo(2);
    }

    // ── P13: disabled absent ──────────────────────────────────────────────

    @Test
    void aDisabledFunctionContributesNothing() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("disabled" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false));
        f = promote(f, v);
        save(f.disable(Instant.now()));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).as("mutant: skip the ACTIVE filter").isEmpty();
    }

    // ── P13: unload — reported while a live host reports it, gone once stale ──

    @Test
    void unloadListsAVersionALiveHostStillReportsAndDropsItOnceTheHostIsStale() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("unload" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        save(v1.markReady(Instant.now()));
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), false));
        save(v2.markReady(Instant.now()));
        promote(f, v2); // v1 is no longer live
        save(v1.retire(Instant.now()));

        Instant now = Instant.now();
        heartbeatHost("host-" + fresh(), pool, now,
                List.of(new FunctionHost.LoadedVersion(f.address(), 1, new FunctionHost.LoadState.Loaded())));

        DesiredState.Document live = DESIRED.build(pool, now);
        assertThat(live.functions()).extracting(DesiredState.FunctionEntry::version).containsExactly(2);
        assertThat(live.unload()).as("mutant: drop the unload computation entirely")
                .containsExactly(new DesiredState.UnloadEntry(f.address().render(), 1));

        // Driven past FunctionHost.LIVE_WINDOW (45s) with no new heartbeat — never sleeps.
        Instant later = now.plus(FunctionHost.LIVE_WINDOW).plusSeconds(1);
        DesiredState.Document staleDoc = DESIRED.build(pool, later);
        assertThat(staleDoc.unload()).as("mutant: ignore the host-liveness cutoff").isEmpty();
    }

    @Test
    void unloadNeverListsAVersionThatIsStillInFunctions() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("keep" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false));
        f = promote(f, v);

        Instant now = Instant.now();
        heartbeatHost("host-" + fresh(), pool, now,
                List.of(new FunctionHost.LoadedVersion(f.address(), 1, new FunctionHost.LoadState.Loaded())));

        DesiredState.Document doc = DESIRED.build(pool, now);
        assertThat(doc.functions()).hasSize(1);
        assertThat(doc.unload()).as("the live version is desired, not stray — must not be double-listed as unload").isEmpty();
    }

    // ── P14: deterministic ordering + byte-identical repeats ────────────────

    @Test
    void functionsAreSortedByAddressThenVersionRegardlessOfInsertionOrder() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String tag = fresh();
        // Inserted in REVERSE address order on purpose.
        Function c = createFunction("z" + tag);
        Function b = createFunction("m" + tag);
        Function a = createFunction("a" + tag);
        promote(c, publish(c, 1, manifestForPool(pool.value(), false)));
        promote(b, publish(b, 1, manifestForPool(pool.value(), false)));
        promote(a, publish(a, 1, manifestForPool(pool.value(), false)));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).extracting(DesiredState.FunctionEntry::address)
                .containsExactly(a.address().render(), b.address().render(), c.address().render());
    }

    @Test
    void twoConsecutiveBuildsOfTheSameStateProduceByteIdenticalJson() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f1 = createFunction("bytes1" + fresh());
        Function f2 = createFunction("bytes2" + fresh());
        promote(f1, publish(f1, 1, manifestForPool(pool.value(), false)));
        promote(f2, publish(f2, 1, manifestForPool(pool.value(), true)));

        Instant now = Instant.now();
        String first = Json.write(DESIRED.build(pool, now));
        String second = Json.write(DESIRED.build(pool, now));
        assertThat(second).as("mutant: any nondeterministic ordering breaks byte-identity").isEqualTo(first);
    }
}
