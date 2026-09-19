package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.Loaded;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.load.LoadOutcome;
import io.flowcatalyst.fnhost.load.Refused;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.function.artifact.ArtifactException;
import io.flowcatalyst.platform.function.artifact.ArtifactStore;
import io.flowcatalyst.platform.function.artifact.SignatureVerifier;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.artifact.Verification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;

/// Desired state → fetch → verify → load → heartbeat, one cycle at a time
/// (`docs/spec/function-host-reconciler.md` §1.2). [ReconcileLoop] is what
/// actually calls [#reconcileOnce] on a schedule; this class owns no thread
/// of its own, which is what makes it directly testable.
///
/// State this owns (spec §1.2): the last etag/document; `prepared` —
/// `versionId → Prepared` for every version fetched and verified this
/// process has ever seen; `failures` — `(address, version) → reason`, always
/// retried the next cycle (never cached permanently). The [FunctionRegistry]
/// (D1) holds what is actually loaded; this class never duplicates that
/// state, only reads it (through [FunctionRegistry#peek]/[FunctionRegistry#snapshot],
/// never [FunctionRegistry#get] — a reconcile cycle must never itself count
/// as an invocation, or a lazy entry could never go idle).
public final class Reconciler {

    private static final Logger LOG = LoggerFactory.getLogger(Reconciler.class);

    /// A lazy entry idle longer than this is closed but keeps its route
    /// (spec §1.2 step 4) — a constant, never a knob (`feedback_no_tuning.md`:
    /// "a default that needs knobs is a defect").
    static final Duration IDLE_UNLOAD = Duration.ofHours(1);

    /// Entries are prepared at most this many at a time (spec §1.2 step 2).
    private static final int MAX_CONCURRENT_PREPARES = 4;

    private final DnsLabel pool;
    private final String hostId;
    private final ControlPlane controlPlane;
    private final ArtifactStore artifactStore;
    private final Signatures signatures;
    private final JvmFunctionLoader loader;
    private final FunctionRegistry registry;

    /// `versionId → Prepared`. Entries that failed to prepare are never
    /// added here, which is what makes them retried automatically the next
    /// cycle — no separate "give up" state.
    private final Map<String, Prepared> prepared = new ConcurrentHashMap<>();

    /// `(address, version) → reason`. Cleared for a key the moment that
    /// entry prepares or loads successfully, or the moment it is unloaded.
    private final Map<Key, String> failures = new ConcurrentHashMap<>();

    /// `(address, version) → versionId` — the reverse of `prepared`'s own
    /// key, kept only so an unload (which the wire only names by address and
    /// version, spec §6.1) can find and drop the matching `prepared` entry.
    private final Map<Key, String> versionIdByKey = new ConcurrentHashMap<>();

    /// `address → the live document entry currently routed lazily`. A
    /// lazy address that is not (yet, or ever) loaded is still present here
    /// once its document entry has been seen — [#ensureLoaded] is what D3
    /// calls on first invocation.
    private final Map<FunctionAddress, DesiredDocument.Entry> lazyRoutes = new ConcurrentHashMap<>();

    /// One lock object per address, so two concurrent [#ensureLoaded] calls
    /// for the same address load it exactly once (spec §1.2 step 3, R1).
    private final Map<FunctionAddress, Object> loadLocks = new ConcurrentHashMap<>();

    private volatile String etag;
    private volatile DesiredDocument document;
    private volatile boolean draining;

    public Reconciler(DnsLabel pool, String hostId, ControlPlane controlPlane, ArtifactStore artifactStore,
                       Signatures signatures, JvmFunctionLoader loader, FunctionRegistry registry) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /// Reported as `DRAINING` in every heartbeat from now on (spec §1.2
    /// step 5). One-way: a drained host is never un-drained.
    public void drain() {
        draining = true;
    }

    private record Prepared(Path artifact) {
    }

    private record Key(FunctionAddress address, int version) {
    }

    /// One full cycle: fetch, then prepare/load/unload, then always attempt
    /// a heartbeat — in that order, and no earlier step is skipped because a
    /// later one might fail (spec §1.2).
    ///
    /// A control-plane outage runs NEITHER prepare/load/unload NOR the
    /// heartbeat's refresh of what changed — spec §1.2 step 1's "nothing is
    /// unloaded", the strict reading: nothing above heartbeat even looks at
    /// a document during an outage, so nothing already loaded can be touched.
    ///
    /// `NotModified` DOES still run prepare/load/unload, against the same
    /// cached document — a deliberate reading beyond the spec's literal
    /// "skip to step 5" (flagged in the handback report): prepare and load
    /// are naturally idempotent (an already-prepared/-loaded entry is a fast
    /// no-op — R6's "no refetch, no reload" still holds), but unload's
    /// `IDLE_UNLOAD` clause (step 4) is a TIME-based decision, not a
    /// document-driven one. Skipping it whenever the ETag happens to be
    /// unchanged would mean a lazy function backed by a quiet desired-state
    /// document could never idle out at all.
    ///
    /// Interruption while blocked in the control plane ([ReconcileLoop]'s
    /// stop signal, R10) surfaces as a [ControlPlaneException] with the
    /// interrupt flag restored (`CONVENTIONS.md` §5) — [ControlPlane]
    /// implementations never let a bare `InterruptedException` escape their
    /// own checked signature, same as [HttpControlPlane]/[TokenSource]. This
    /// method itself never blocks directly, so it declares nothing checked;
    /// [ReconcileLoop] observes the restored flag on its own next blocking
    /// wait and stops there.
    public void reconcileOnce(Instant now) {
        Objects.requireNonNull(now, "now");
        DesiredDocument doc;
        boolean outage = false;
        try {
            ControlPlane.Fetched fetched = controlPlane.desiredState(pool, etag);
            switch (fetched) {
                case ControlPlane.Fetched.NotModified ignored -> {
                    // document unchanged — fall through to prepare/load/unload below
                }
                case ControlPlane.Fetched.Changed(String newEtag, DesiredDocument newDoc) -> {
                    etag = newEtag;
                    document = newDoc;
                }
            }
            doc = document;
        } catch (ControlPlaneException e) {
            // A platform outage must never unload a function (spec §1.2 step 1):
            // prepare/load/unload do not run at all this cycle.
            LOG.atWarn().setMessage("desired-state fetch failed; keeping what is already loaded")
                    .addKeyValue("host_id", hostId)
                    .addKeyValue("pool", pool.value())
                    .setCause(e)
                    .log();
            doc = document;
            outage = true;
        }

        if (doc != null && !outage) {
            prepare(doc);
            load(doc);
            unload(doc, now);
        }
        if (doc != null) {
            sendHeartbeat(doc);
        }
    }

    // ── step 2: prepare ──────────────────────────────────────────────────

    private void prepare(DesiredDocument doc) {
        List<DesiredDocument.Entry> toPrepare = doc.functions().stream()
                .filter(e -> !prepared.containsKey(e.versionId()))
                .toList();
        if (toPrepare.isEmpty()) {
            return;
        }
        Semaphore gate = new Semaphore(MAX_CONCURRENT_PREPARES);
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll())) {
            for (DesiredDocument.Entry entry : toPrepare) {
                scope.fork(() -> {
                    gate.acquire();
                    try {
                        prepareOne(entry);
                    } finally {
                        gate.release();
                    }
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException e) {
            // Interruption is the loop's stop signal (CONVENTIONS §5): restore
            // and stop preparing — load/unload still run against whatever
            // prepared before the interrupt, and ReconcileLoop's own next
            // blocking wait observes the restored flag and stops the loop.
            Thread.currentThread().interrupt();
        }
    }

    private void prepareOne(DesiredDocument.Entry entry) {
        Key key = new Key(entry.address(), entry.version());
        try {
            ArtifactStore.Fetched fetched = artifactStore.fetch(entry.artifactRef(), entry.digest());
            switch (signatures) {
                case Signatures.Off ignored -> {
                    // fetch and digest only (spec §1.2 step 2)
                }
                case Signatures.Required(SignatureVerifier verifier) -> verifySignature(verifier, entry);
            }
            prepared.put(entry.versionId(), new Prepared(fetched.file()));
            versionIdByKey.put(key, entry.versionId());
            failures.remove(key);
        } catch (ArtifactException e) {
            // The record's own simple name IS the reason spelling spec §1.2 names
            // verbatim: "ARTIFACT:DigestMismatch".
            String reason = "ARTIFACT:" + e.reason().getClass().getSimpleName();
            failures.put(key, reason);
            logPrepareFailure(entry, reason, e);
        } catch (PrepareFailure e) {
            failures.put(key, e.getMessage());
            logPrepareFailure(entry, e.getMessage(), e);
        }
    }

    /// Spec §1.2 step 2: `Required` demands a bundle AND a recorded signer —
    /// either absent is a failure — verifies it, and requires the extracted
    /// identity to equal what the entry recorded at publish EXACTLY (spec §0,
    /// R4 — not issuer-only).
    private void verifySignature(SignatureVerifier verifier, DesiredDocument.Entry entry) {
        if (entry.signatureBundle() == null || entry.signatureBundle().isBlank()) {
            throw new PrepareFailure("UNSIGNED");
        }
        if (entry.signer() == null) {
            throw new PrepareFailure("UNSIGNED");
        }
        Verification verification = verifier.verify(entry.signatureBundle(), entry.digest());
        switch (verification) {
            case Verification.Rejected(Verification.Reason reason, String ignored) ->
                    throw new PrepareFailure("SIGNATURE:" + reason.name());
            case Verification.Verified(SignerIdentity signer, Instant ignored) -> {
                if (!signer.equals(entry.signer())) {
                    throw new PrepareFailure("SIGNER_MISMATCH");
                }
            }
        }
    }

    private void logPrepareFailure(DesiredDocument.Entry entry, String reason, Throwable cause) {
        LOG.atWarn().setMessage("failed to prepare a function version")
                .addKeyValue("address", entry.address().render())
                .addKeyValue("version", entry.version())
                .addKeyValue("reason", reason)
                .setCause(cause)
                .log();
    }

    private static final class PrepareFailure extends RuntimeException {
        PrepareFailure(String reason) {
            super(reason);
        }
    }

    // ── step 3: load ─────────────────────────────────────────────────────

    private void load(DesiredDocument doc) {
        for (DesiredDocument.Entry entry : doc.functions()) {
            if (entry.role() != DesiredDocument.Role.LIVE) {
                continue; // a candidate is never loaded (spec §1.2 step 3)
            }
            Prepared p = prepared.get(entry.versionId());
            if (p == null) {
                continue; // still pending, or failed to prepare this cycle
            }
            Key key = new Key(entry.address(), entry.version());
            if (entry.manifest().runtime() == Runtime.WASM) {
                failures.put(key, "RUNTIME_UNSUPPORTED");
                continue;
            }
            switch (entry.mode()) {
                case WARM -> loadWarm(entry, p, key);
                case LAZY -> loadLazy(entry, p, key);
            }
        }
    }

    private void loadWarm(DesiredDocument.Entry entry, Prepared p, Key key) {
        LoadedFunction current = registry.peek(entry.address());
        if (current != null && current.version() == entry.version()) {
            return; // already the live version
        }
        LoadOutcome outcome = loader.load(p.artifact(), entry.manifest().entrypoint(), entry.address(), entry.version());
        applyLoadOutcome(outcome, key, true);
        if (outcome instanceof Loaded) {
            lazyRoutes.remove(entry.address()); // it is warm now, not lazily routed
        }
    }

    /// A lazy function already resident must not keep serving an old version
    /// until it happens to idle out (spec §1.2 step 3) — only when a
    /// DIFFERENT version is currently loaded does this replace it now;
    /// otherwise the first invocation loads it ([#ensureLoaded]).
    private void loadLazy(DesiredDocument.Entry entry, Prepared p, Key key) {
        lazyRoutes.put(entry.address(), entry);
        LoadedFunction current = registry.peek(entry.address());
        if (current == null || current.version() == entry.version()) {
            return;
        }
        LoadOutcome outcome = loader.load(p.artifact(), entry.manifest().entrypoint(), entry.address(), entry.version());
        applyLoadOutcome(outcome, key, false);
    }

    /// **New before old** (spec §1.2, pinned): [FunctionRegistry#put] returns
    /// the displaced version and this closes it only AFTER the put — an
    /// address is never without a version during a promote (R2).
    private void applyLoadOutcome(LoadOutcome outcome, Key key, boolean warm) {
        switch (outcome) {
            case Loaded(LoadedFunction fn) -> {
                LoadedFunction displaced = registry.put(fn, warm);
                failures.remove(key);
                if (displaced != null) {
                    displaced.close();
                }
            }
            case Refused(io.flowcatalyst.fnhost.load.Reason reason, String ignored) ->
                    failures.put(key, "LOAD:" + reason.name());
        }
    }

    /// What D3 calls on first invocation of a lazy address (spec §1.2 step 3).
    /// Loads from `prepared` under a per-address lock, so two concurrent
    /// first callers load exactly once (R1). Returns whatever ends up
    /// registered for `address` — `null` if nothing is prepared yet, or the
    /// load was refused and nothing was ever loaded.
    public LoadedFunction ensureLoaded(FunctionAddress address) {
        Objects.requireNonNull(address, "address");
        LoadedFunction current = registry.get(address); // a real access: about to be invoked
        DesiredDocument.Entry route = lazyRoutes.get(address);
        if (route == null) {
            return current;
        }
        if (current != null && current.version() == route.version()) {
            return current;
        }
        Object lock = loadLocks.computeIfAbsent(address, a -> new Object());
        synchronized (lock) {
            current = registry.get(address);
            if (current != null && current.version() == route.version()) {
                return current;
            }
            Prepared p = prepared.get(route.versionId());
            if (p == null) {
                return current;
            }
            LoadOutcome outcome = loader.load(p.artifact(), route.manifest().entrypoint(), route.address(), route.version());
            Key key = new Key(route.address(), route.version());
            return switch (outcome) {
                case Loaded(LoadedFunction fn) -> {
                    LoadedFunction displaced = registry.put(fn, false);
                    failures.remove(key);
                    if (displaced != null) {
                        displaced.close();
                    }
                    yield fn;
                }
                case Refused(io.flowcatalyst.fnhost.load.Reason reason, String ignored) -> {
                    failures.put(key, "LOAD:" + reason.name());
                    yield current;
                }
            };
        }
    }

    // ── step 4: unload ───────────────────────────────────────────────────

    private void unload(DesiredDocument doc, Instant now) {
        Set<FunctionAddress> keep = new HashSet<>();
        for (DesiredDocument.Entry e : doc.functions()) {
            if (e.role() == DesiredDocument.Role.LIVE) {
                keep.add(e.address());
            }
        }
        // An entry the host could not even read must never itself trigger an
        // unload of a perfectly good, already-loaded version (spec §1.1's
        // "never takes the rest of the document down with it" — this is the
        // unload step's own reading of that rule, R8).
        for (DesiredDocument.UnreadableEntry u : doc.unreadable()) {
            keep.add(u.address());
        }

        // 4a: the document's own unload list, exact (address, version) — this
        // fires REGARDLESS of whether the address itself is still live (an
        // older retired version of an address whose newer version IS live).
        for (DesiredDocument.UnloadRef ref : doc.unload()) {
            unloadExact(ref.address(), ref.version());
        }

        // 4b: loaded or lazily routed, but the address is no longer live at all.
        for (FunctionRegistry.Snapshot s : registry.snapshot()) {
            if (!keep.contains(s.address())) {
                closeAndRemove(s.address());
            }
        }
        lazyRoutes.keySet().removeIf(address -> !keep.contains(address));

        // 4c: idle lazy eviction — closed, but the route stays so a later
        // invocation reloads it (spec §1.2 step 4).
        Instant cutoff = now.minus(IDLE_UNLOAD);
        for (FunctionRegistry.Snapshot s : registry.snapshot()) {
            if (!s.warm() && lazyRoutes.containsKey(s.address()) && s.lastAccessed().isBefore(cutoff)) {
                closeAndRemove(s.address());
            }
        }
    }

    private void unloadExact(FunctionAddress address, int version) {
        LoadedFunction current = registry.peek(address);
        if (current != null && current.version() == version) {
            closeAndRemove(address);
        }
        DesiredDocument.Entry route = lazyRoutes.get(address);
        if (route != null && route.version() == version) {
            lazyRoutes.remove(address);
        }
        Key key = new Key(address, version);
        String versionId = versionIdByKey.remove(key);
        if (versionId != null) {
            prepared.remove(versionId);
        }
        failures.remove(key);
    }

    private void closeAndRemove(FunctionAddress address) {
        LoadedFunction removed = registry.remove(address);
        if (removed != null) {
            removed.close();
        }
    }

    // ── step 5: heartbeat ────────────────────────────────────────────────

    private void sendHeartbeat(DesiredDocument doc) {
        List<HeartbeatReport.LoadedEntry> loaded = new ArrayList<>();
        for (DesiredDocument.Entry entry : doc.functions()) {
            Key key = new Key(entry.address(), entry.version());
            String failureReason = failures.get(key);
            HeartbeatReport.LoadState state;
            if (failureReason != null) {
                state = new HeartbeatReport.LoadState.Failed(failureReason);
            } else {
                LoadedFunction current = registry.peek(entry.address());
                if (current != null && current.version() == entry.version()) {
                    state = new HeartbeatReport.LoadState.Loaded();
                } else if (prepared.containsKey(entry.versionId())) {
                    state = new HeartbeatReport.LoadState.Registered();
                } else {
                    continue; // neither loaded, prepared, nor failed yet — still fetching
                }
            }
            loaded.add(new HeartbeatReport.LoadedEntry(entry.address(), entry.version(), state));
        }
        for (DesiredDocument.UnreadableEntry u : doc.unreadable()) {
            loaded.add(new HeartbeatReport.LoadedEntry(u.address(), u.version(),
                    new HeartbeatReport.LoadState.Failed(u.reason())));
        }

        HeartbeatReport.HostState hostState = draining ? HeartbeatReport.HostState.DRAINING : HeartbeatReport.HostState.ACTIVE;
        try {
            controlPlane.heartbeat(new HeartbeatReport(hostId, pool, hostState, loaded));
        } catch (ControlPlaneException e) {
            LOG.atWarn().setMessage("heartbeat failed").addKeyValue("host_id", hostId).setCause(e).log();
        }
    }
}
