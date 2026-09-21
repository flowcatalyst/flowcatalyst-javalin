package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.context.ContextFactory;
import io.flowcatalyst.fnhost.context.ContextLoadException;
import io.flowcatalyst.fnhost.context.HostFunctionContext;
import io.flowcatalyst.fnhost.context.SettingsFingerprint;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.Loaded;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.load.LoadOutcome;
import io.flowcatalyst.fnhost.load.MetaspaceGuard;
import io.flowcatalyst.fnhost.load.Reason;
import io.flowcatalyst.fnhost.load.Refused;
import io.flowcatalyst.fnhost.route.PublicRouteTable;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

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
    private final ContextFactory contextFactory;

    /// `function-host-process.md` §3 item 1: checked before every
    /// [JvmFunctionLoader#load] attempt (warm, lazy [#ensureLoaded], pinned)
    /// — defaults to the real MXBean-backed [MetaspaceGuard#system], overridable
    /// by the test-injection constructors below so a test can drive exact
    /// used/max readings deterministically.
    private final MetaspaceGuard metaspaceGuard;

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

    /// D4b (`function-context.md` §2, X5): the settings fingerprint the
    /// address's CURRENTLY LOADED version was built with — a live/lazy/pinned
    /// reload compares against this even when the version NUMBER is
    /// unchanged, since a config/secret edit reloads the function in place
    /// (same version, new context). Not cleaned up on unload, same convention
    /// as [#currentSecretByAddress]/[#previousSecretByAddress] below — a
    /// stale entry for an address that later reappears is harmless (the
    /// address is unloaded, so every load path reloads regardless of what
    /// this map says).
    private final Map<FunctionAddress, String> settingsFingerprintByAddress = new ConcurrentHashMap<>();

    /// D3 (`function-host-listener.md` §3, H3): the live entry's CURRENT
    /// webhook signing secret per address, as last seen in an applied
    /// document — kept so a rotation can be detected against the NEXT one.
    private final Map<FunctionAddress, String> currentSecretByAddress = new ConcurrentHashMap<>();

    /// D3: the secret a rotation displaced, and the reconcile number after
    /// which it stops being accepted (spec §3: "accepted until the second
    /// reconcile after the change, then dropped").
    private final Map<FunctionAddress, PreviousSecret> previousSecretByAddress = new ConcurrentHashMap<>();

    private record PreviousSecret(String secret, long validThroughReconcile) {
    }

    /// D3: incremented once per [#reconcileOnce] call (regardless of outage),
    /// so [#previousWebhookSecret] can answer "has the second reconcile after
    /// a rotation happened yet".
    private final AtomicLong reconcileCounter = new AtomicLong();

    private volatile String etag;
    private volatile DesiredDocument document;
    private volatile boolean draining;

    /// F2 (`function-public-routes.md` §3): an immutable snapshot built ONCE
    /// per reconcile from [DesiredDocument#publicRoutes] and swapped
    /// atomically here — [io.flowcatalyst.fnhost.http.FnHttpServer]'s public
    /// entry reads this field per request; it never recomputes a table
    /// itself.
    private volatile PublicRouteTable publicRouteTable = PublicRouteTable.EMPTY;

    /// D5 (`function-host-process.md` §2 P1): has a desired-state fetch ever
    /// RESOLVED (succeeded or failed — set only once [#reconcileOnce]'s fetch
    /// returns or throws, never while one is still in flight, so a slow
    /// first fetch reads as `STARTING`, not `PLATFORM_UNREACHABLE`), and has
    /// one ever succeeded (`Changed` or `NotModified` — never the heartbeat)
    /// — together with [#draining], what [#readiness] answers `/ready` from.
    private volatile boolean reconcileAttempted;
    private volatile boolean everReconciledSuccessfully;

    /// D5 (`function-host-process.md` §2): who to tell about load errors and
    /// reconcile outcomes — [io.flowcatalyst.fnhost.metrics.FnMetrics] via
    /// [#setObserver], `NOOP` otherwise. A field, not a constructor
    /// parameter, so every existing caller of the constructor above is
    /// untouched.
    private volatile ReconcileObserver observer = ReconcileObserver.NOOP;

    /// D3 (`function-host-listener.md` §4, `function-invocation.md` §2):
    /// run at the end of every [#reconcileOnce], regardless of outcome —
    /// [io.flowcatalyst.fnhost.http.PinnedVersions#sweep] is registered here
    /// by [io.flowcatalyst.fnhost.http.FnHttpServer#start] so a pinned
    /// candidate is closed the moment its version leaves desired state,
    /// without this class needing to know anything about pinned versions
    /// itself.
    private final List<Runnable> postReconcileListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /// A trivial [io.flowcatalyst.fnhost.context.ContextFactory] (16 database
    /// pools, a fresh shared [java.net.http.HttpClient], the system clock,
    /// and THIS SAME `controlPlane`/`hostId` — spec §3's `events()` speaks
    /// through the one control plane this reconciler itself polls/heartbeats
    /// through) — every pre-D4c test/caller that has no other opinion about
    /// database/HTTP context wiring keeps compiling unchanged.
    public Reconciler(DnsLabel pool, String hostId, ControlPlane controlPlane, ArtifactStore artifactStore,
                       Signatures signatures, JvmFunctionLoader loader, FunctionRegistry registry) {
        this(pool, hostId, controlPlane, artifactStore, signatures, loader, registry,
                ContextFactory.production(16, controlPlane, hostId));
    }

    /// D4b (`docs/spec/function-context.md` §2): `contextFactory` builds each
    /// loaded version's [io.flowcatalyst.function.FunctionContext] at load
    /// time — see [#attachContextAndInit].
    public Reconciler(DnsLabel pool, String hostId, ControlPlane controlPlane, ArtifactStore artifactStore,
                       Signatures signatures, JvmFunctionLoader loader, FunctionRegistry registry,
                       ContextFactory contextFactory) {
        this(pool, hostId, controlPlane, artifactStore, signatures, loader, registry, contextFactory,
                MetaspaceGuard.system());
    }

    /// Test-injection seam (`function-host-process.md` §3 item 1): an
    /// explicit [MetaspaceGuard] — normally one wrapping a deterministic
    /// test double for [io.flowcatalyst.fnhost.load.MetaspaceGauge] — instead
    /// of the real MXBean-backed default, so a test can drive exact used/max
    /// readings without forking a real JVM at a real fence. Production never
    /// calls this overload directly.
    public Reconciler(DnsLabel pool, String hostId, ControlPlane controlPlane, ArtifactStore artifactStore,
                       Signatures signatures, JvmFunctionLoader loader, FunctionRegistry registry,
                       ContextFactory contextFactory, MetaspaceGuard metaspaceGuard) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.metaspaceGuard = Objects.requireNonNull(metaspaceGuard, "metaspaceGuard");
    }

    /// Reported as `DRAINING` in every heartbeat from now on (spec §1.2
    /// step 5). One-way: a drained host is never un-drained.
    public void drain() {
        draining = true;
    }

    /// `/ready`'s own vocabulary (`function-host-process.md` §2 P1, extended
    /// by §3 item 3).
    public enum Readiness {
        /// No [#reconcileOnce] has completed yet.
        STARTING,
        /// At least one attempt ran, but none has ever succeeded.
        PLATFORM_UNREACHABLE,
        /// A reconcile has succeeded and this host is not draining, but the
        /// FUNCTION listener is not bound — §3 item 3's own defect: a
        /// reconcile that failed catastrophically enough could previously
        /// leave `/ready` reporting `UP` forever while the function port
        /// never opened at all.
        LISTENER_DOWN,
        /// A reconcile has succeeded and the listener is bound, but the
        /// reconcile loop's own thread has died (an `Error` other than a
        /// metaspace-family `OutOfMemoryError` — `ReconcileLoop`'s own
        /// amended §1.3) — nothing loaded is unloaded by this, but desired
        /// state will never move again.
        RECONCILER_DOWN,
        /// At least one attempt has ever succeeded, this host is not
        /// draining, the listener is bound and the loop is alive — stays
        /// `READY` through a LATER control-plane outage (D2 R6): once
        /// loaded, already-served functions keep serving.
        READY,
        /// [#drain] was called. One-way; wins over every other state.
        DRAINING
    }

    /// What `/ready` (and, after start-up, `/health`) report (D5, spec §2
    /// P1, extended by §3 item 3) — `DRAINING` first (it wins over
    /// everything else once [#drain] has been called), then whether any
    /// reconcile has EVER succeeded, then whether the FUNCTION listener is
    /// bound, then whether the reconcile loop's thread is still alive.
    ///
    /// @param listenerBound       [io.flowcatalyst.fnhost.FnHost]'s own live
    ///                            read of whether [io.flowcatalyst.fnhost.http.FnHttpServer#start]
    ///                            has returned
    /// @param reconcileLoopAlive  [ReconcileLoop#isAlive]
    public Readiness readiness(boolean listenerBound, boolean reconcileLoopAlive) {
        if (draining) {
            return Readiness.DRAINING;
        }
        if (!reconcileAttempted) {
            return Readiness.STARTING;
        }
        if (!everReconciledSuccessfully) {
            return Readiness.PLATFORM_UNREACHABLE;
        }
        if (!listenerBound) {
            return Readiness.LISTENER_DOWN;
        }
        if (!reconcileLoopAlive) {
            return Readiness.RECONCILER_DOWN;
        }
        return Readiness.READY;
    }

    /// Registers `listener` to run once at the end of every future
    /// [#reconcileOnce] call (after the heartbeat, spec §1.2's own final
    /// step). Never called for a listener the caller does not itself own —
    /// this class is otherwise oblivious to what a listener does.
    public void addPostReconcileListener(Runnable listener) {
        postReconcileListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /// D5: wires a [ReconcileObserver] (`FnMetrics`) in place of the default
    /// no-op — [io.flowcatalyst.fnhost.FnHost] calls this once, before
    /// [#reconcileOnce] first runs.
    public void setObserver(ReconcileObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
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
        // §3 item 1: exactly one System.gc() request is allowed per reconcile cycle,
        // never per load — reset the guard's own bookkeeping here, once, regardless of
        // how many loads this cycle goes on to attempt (or whether it hits an outage
        // and attempts none at all).
        metaspaceGuard.beginCycle();
        long reconcileNumber = reconcileCounter.incrementAndGet();
        DesiredDocument doc;
        boolean outage = false;
        try {
            ControlPlane.Fetched fetched = controlPlane.desiredState(pool, etag);
            switch (fetched) {
                case ControlPlane.Fetched.NotModified ignored -> {
                    // document unchanged — fall through to prepare/load/unload below.
                    // Spec `function-host-process.md` §2 P6: still a SUCCESS — the
                    // platform answered — so the reconcile-success timestamp moves.
                    everReconciledSuccessfully = true;
                    observer.reconciled("not_modified", true, now);
                }
                case ControlPlane.Fetched.Changed(String newEtag, DesiredDocument newDoc) -> {
                    etag = newEtag;
                    document = newDoc;
                    publicRouteTable = PublicRouteTable.of(newDoc.publicRoutes());
                    updateSecretHistory(newDoc, reconcileNumber);
                    everReconciledSuccessfully = true;
                    observer.reconciled("changed", true, now);
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
            observer.reconciled("failed", false, now);
        }
        // Set only once the fetch has RESOLVED (success or failure), never while it is still
        // in flight — a slow first fetch must read `/ready` as STARTING, not PLATFORM_UNREACHABLE
        // (spec `function-host-process.md` §2 P1: "STARTING before the first reconcile").
        reconcileAttempted = true;

        if (doc != null && !outage) {
            prepare(doc);
            load(doc);
            checkSigningSecrets(doc);
            unload(doc, now);
        }
        if (doc != null) {
            sendHeartbeat(doc);
        }
        for (Runnable listener : postReconcileListeners) {
            listener.run();
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
            // spec `function-artifact-upload.md` §5: the three-arg widening — the entry's OWN
            // version id, so a `platform://` ref (PlatformArtifactStore) can call the download
            // route, which is keyed by version id, not by ref.
            ArtifactStore.Fetched fetched = artifactStore.fetch(entry.artifactRef(), entry.digest(), entry.versionId());
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
            observer.loadError(reason);
            logPrepareFailure(entry, reason, e);
        } catch (PrepareFailure e) {
            failures.put(key, e.getMessage());
            observer.loadError(e.getMessage());
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
                observer.loadError("RUNTIME_UNSUPPORTED");
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
        if (current != null && current.version() == entry.version() && !settingsChanged(entry)) {
            return; // already the live version, unchanged settings
        }
        LoadOutcome outcome = attemptLoad(p, entry);
        applyLoadOutcome(outcome, key, true, entry);
        if (outcome instanceof Loaded) {
            lazyRoutes.remove(entry.address()); // it is warm now, not lazily routed
        }
    }

    /// A lazy function already resident must not keep serving an old version
    /// (or stale settings, D4b/X5) until it happens to idle out (spec §1.2
    /// step 3) — only when a DIFFERENT version, or the SAME version with
    /// changed settings, is currently loaded does this replace it now;
    /// otherwise the first invocation loads it ([#ensureLoaded]).
    private void loadLazy(DesiredDocument.Entry entry, Prepared p, Key key) {
        lazyRoutes.put(entry.address(), entry);
        LoadedFunction current = registry.peek(entry.address());
        if (current == null) {
            return; // not loaded yet at all — first invocation loads it
        }
        if (current.version() == entry.version() && !settingsChanged(entry)) {
            return; // already the right version, unchanged settings
        }
        LoadOutcome outcome = attemptLoad(p, entry);
        applyLoadOutcome(outcome, key, false, entry);
    }

    /// `function-host-process.md` §3 item 1: the ONE place every load
    /// attempt (warm, lazy replace, [#ensureLoaded], [#loadPinned]) funnels
    /// through — checks [#metaspaceGuard] BEFORE ever calling
    /// [JvmFunctionLoader#load], and refuses without attempting the load at
    /// all when headroom is below the reserve. The ternary is deliberate:
    /// `loader.load(...)` is never evaluated on the refusal branch, so a
    /// refused load costs nothing (no class definition, no reflection —
    /// exactly what "WITHOUT ATTEMPTING IT" requires).
    private LoadOutcome attemptLoad(Prepared p, DesiredDocument.Entry entry) {
        MetaspaceGuard.Result headroom = metaspaceGuard.check();
        return headroom.hasHeadroom()
                ? loader.load(p.artifact(), entry.manifest().entrypoint(), entry.address(), entry.version())
                : new Refused(Reason.METASPACE_HEADROOM, headroom.detail());
    }

    /// D4b (`function-context.md` §2, X5): true when `entry`'s config+secrets
    /// fingerprint differs from the one the address's currently loaded
    /// version was built with — a settings edit reloads the function even
    /// when the version NUMBER is unchanged, exactly like a promote (new
    /// context/instance before the old one is closed).
    private boolean settingsChanged(DesiredDocument.Entry entry) {
        String newFingerprint = SettingsFingerprint.of(entry.config(), entry.secrets());
        String oldFingerprint = settingsFingerprintByAddress.get(entry.address());
        return !Objects.equals(oldFingerprint, newFingerprint);
    }

    /// **New before old** (spec §1.2, pinned): [FunctionRegistry#put] returns
    /// the displaced version and this closes it only AFTER the put — an
    /// address is never without a version during a promote (R2).
    ///
    /// [FunctionRegistry#put] itself can refuse a NEW address when the
    /// registry is at capacity and every existing entry is warm (its own
    /// `IllegalStateException`, spec §1.2 step 3, R3b) — this is a load
    /// failure like any other (R3): reported `FAILED` and retried next
    /// cycle, never allowed to escape [#load] and abort the rest of the
    /// document's entries, and never the reconcile loop itself (R10 depends
    /// on [Reconciler#reconcileOnce] only ever throwing for something a run
    /// truly cannot recover from).
    private void applyLoadOutcome(LoadOutcome outcome, Key key, boolean warm, DesiredDocument.Entry entry) {
        switch (outcome) {
            case Loaded(LoadedFunction fn) -> {
                if (!attachContextAndInit(fn, entry, key)) {
                    return; // fn already closed, failure already recorded
                }
                LoadedFunction displaced;
                try {
                    displaced = registry.put(fn, warm);
                } catch (IllegalStateException e) {
                    failures.put(key, "LOAD:REGISTRY_FULL");
                    observer.loadError("LOAD:REGISTRY_FULL");
                    logRegistryFullFailure(key, e);
                    fn.close(); // never registered — release what was just loaded (and its context)
                    return;
                }
                failures.remove(key);
                if (displaced != null) {
                    displaced.close();
                }
            }
            case Refused(io.flowcatalyst.fnhost.load.Reason reason, String detail) -> {
                String loadReason = "LOAD:" + reason.name();
                failures.put(key, loadReason);
                observer.loadError(loadReason);
                if (reason == io.flowcatalyst.fnhost.load.Reason.OUT_OF_METASPACE) {
                    logMetaspaceRefusalSafely(entry.address(), entry.version(), detail);
                }
            }
        }
    }

    private void logRegistryFullFailure(Key key, Throwable cause) {
        LOG.atWarn().setMessage("registry refused to load a function version: at capacity and every loaded entry is warm")
                .addKeyValue("address", key.address().render())
                .addKeyValue("version", key.version())
                .setCause(cause)
                .log();
    }

    /// D4b (`function-context.md` §2): builds `entry`'s [io.flowcatalyst.fnhost.context.HostFunctionContext],
    /// attaches it to `fn` and runs `Function#init` — the same "load failure,
    /// old version keeps serving" treatment as every other prepare/load
    /// failure (spec §2 item 6, R3's own pattern): on either failure, `fn` is
    /// closed (which also releases any database pools its context already
    /// acquired, `LoadedFunction#close`) WITHOUT ever being registered, and
    /// this returns `false` so the caller leaves whatever was already loaded
    /// (if anything) serving.
    private boolean attachContextAndInit(LoadedFunction fn, DesiredDocument.Entry entry, Key key) {
        HostFunctionContext ctx;
        try {
            ctx = contextFactory.build(entry, fn);
        } catch (ContextLoadException e) {
            failures.put(key, e.code());
            observer.loadError(e.code());
            logContextFailure(entry, e.code(), e);
            fn.close();
            return false;
        }
        fn.attachContext(ctx);
        try {
            fn.init(ctx);
        } catch (Error e) {
            // `function-host-process.md` §3: a catchable `OutOfMemoryError: Metaspace` (or
            // Compressed class space) fails ONLY this one load — the class loader is closed
            // below so the space can be reclaimed, the old version (if any) keeps serving
            // (this method just returns false), and the rest of the document is unaffected.
            // A Java-heap OOM is not this call's to swallow — it is not one function's problem.
            //
            // Broadened from `OutOfMemoryError` alone to `Error`, then unwrapped via
            // JvmFunctionLoader#findMetaspaceOom: `init()` bootstrapping a lambda/string-concat
            // call site (or running a class's own static initialiser) under a tight fence does
            // NOT always throw a bare OutOfMemoryError — observed directly, forking a real JVM
            // at a real fence, `com.networknt.schema.ValidatorTypeCode`'s `<clinit>`
            // bootstrapping a lambda threw `InternalError` wrapping the real OOM one level down.
            OutOfMemoryError oom = io.flowcatalyst.fnhost.load.JvmFunctionLoader.findMetaspaceOom(e);
            if (oom == null) {
                throw e;
            }
            failures.put(key, "LOAD:OUT_OF_METASPACE");
            observer.loadError("LOAD:OUT_OF_METASPACE");
            // Close BEFORE logging: closing is the load-bearing side effect (reclaims the
            // loader; the old version, if any, is already known to be serving) — observed
            // directly, at a real fence, that the log call below can ITSELF throw a second
            // OutOfMemoryError once metaspace is this tight, and that must never undo the
            // close.
            fn.close();
            logMetaspaceFailureSafely(entry.address(), entry.version(), oom);
            return false;
        } catch (Exception e) {
            failures.put(key, "LOAD:INIT_FAILED");
            observer.loadError("LOAD:INIT_FAILED");
            logContextFailure(entry, "LOAD:INIT_FAILED", e);
            fn.close(); // releases ctx's acquired database pools too
            return false;
        }
        settingsFingerprintByAddress.put(entry.address(),
                SettingsFingerprint.of(entry.config(), entry.secrets()));
        return true;
    }

    private void logContextFailure(DesiredDocument.Entry entry, String reason, Throwable cause) {
        LOG.atWarn().setMessage("failed to build this version's context or run its init()")
                .addKeyValue("address", entry.address().render())
                .addKeyValue("version", entry.version())
                .addKeyValue("reason", reason)
                .setCause(cause)
                .log();
    }

    /// `function-host-process.md` §3: logged at ERROR (not WARN, unlike every
    /// other prepare/load failure here) — a load refused for lack of
    /// metaspace is evidence the fence is being approached and is worth an
    /// operator's attention even though the host itself handled it cleanly.
    /// `init()`'s own `OutOfMemoryError` is on hand — logged as the cause.
    /// Wrapped the same way as [#logMetaspaceRefusalSafely] below: at a real
    /// fence, metaspace can be tight enough that EVEN emitting this log line
    /// needs a class Logback has not loaded yet (observed directly, forking
    /// a real JVM at a 128 MB fence: `ch.qos.logback.classic.Logger.log`
    /// itself threw `OutOfMemoryError: Metaspace`). That must never undo the
    /// guarantee this whole catch exists to make.
    private void logMetaspaceFailureSafely(FunctionAddress address, int version, OutOfMemoryError cause) {
        try {
            LOG.atError().setMessage("function version failed to load: out of metaspace")
                    .addKeyValue("address", address.render())
                    .addKeyValue("version", version)
                    .setCause(cause)
                    .log();
        } catch (Error logFailure) {
            // Broadened from `OutOfMemoryError` alone: observed directly, forking a real JVM —
            // Logback's OWN `ThrowableProxy` (needed to render `.setCause(...)`) had never been
            // touched by this process before, and ITS <clinit> failing under the same exhausted
            // metaspace surfaces on every later reference as `NoClassDefFoundError: Could not
            // initialize class ...ThrowableProxy`, not a bare OutOfMemoryError.
            fallBackToStderrIfMetaspace(address, version, logFailure);
        }
    }

    /// Same shape as [#logMetaspaceFailureSafely], for the classloading-time
    /// refusal path (`JvmFunctionLoader#load` already turned its own
    /// `OutOfMemoryError` into a [Refused] — `detail` is that Error's
    /// message, not a live Throwable to attach as the cause).
    private void logMetaspaceRefusalSafely(FunctionAddress address, int version, String detail) {
        try {
            LOG.atError().setMessage("function version failed to load: out of metaspace")
                    .addKeyValue("address", address.render())
                    .addKeyValue("version", version)
                    .addKeyValue("detail", detail)
                    .log();
        } catch (Error logFailure) {
            fallBackToStderrIfMetaspace(address, version, logFailure);
        }
    }

    /// A Java-heap `OutOfMemoryError` (or any OTHER `Error` with no metaspace
    /// `OutOfMemoryError` anywhere in its cause chain) from the logging call
    /// itself is still a real emergency, same rule as everywhere else in
    /// this class — rethrown UNCHANGED, never routed here. Only one that
    /// traces back to a metaspace exhaustion falls back to a bare
    /// `System.err` line, built with `StringBuilder` rather than `+`:
    /// `StringBuilder#append` and `PrintStream` are plain virtual dispatch on
    /// already-loaded classes (unlike `+`, which is `invokedynamic` and can
    /// itself need to spin up a fresh hidden class — see that fix's own
    /// history), so this cannot fail the same way the logging attempt did.
    private static void fallBackToStderrIfMetaspace(FunctionAddress address, int version, Error logFailure) {
        OutOfMemoryError oom = io.flowcatalyst.fnhost.load.JvmFunctionLoader.findMetaspaceOom(logFailure);
        if (oom == null) {
            throw logFailure;
        }
        // `+` on a non-constant String compiles to `invokedynamic` (StringConcatFactory),
        // which spins up a fresh hidden class PER CALL SITE THE FIRST TIME IT RUNS — itself
        // capable of throwing OutOfMemoryError: Metaspace (observed directly: this exact
        // fallback line crashed the fork with a BootstrapMethodError wrapping one, at a 128 MB
        // fence, before this fix). StringBuilder#append is plain virtual dispatch on an
        // already-loaded class — no new class definition, so it cannot fail the same way.
        StringBuilder line = new StringBuilder(128);
        line.append("ERROR out of metaspace loading ").append(address.render()).append('@').append(version)
                .append(" — the structured log line for it could not itself be emitted "
                        + "(metaspace exhausted further still)");
        System.err.println(line);
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
        if (current != null && current.version() == route.version() && !settingsChanged(route)) {
            return current;
        }
        Object lock = loadLocks.computeIfAbsent(address, a -> new Object());
        synchronized (lock) {
            current = registry.get(address);
            if (current != null && current.version() == route.version() && !settingsChanged(route)) {
                return current;
            }
            Prepared p = prepared.get(route.versionId());
            if (p == null) {
                return current;
            }
            LoadOutcome outcome = attemptLoad(p, route);
            Key key = new Key(route.address(), route.version());
            return switch (outcome) {
                case Loaded(LoadedFunction fn) -> {
                    if (!attachContextAndInit(fn, route, key)) {
                        yield current; // failure recorded already; whatever was loaded (if anything) keeps serving
                    }
                    LoadedFunction displaced = registry.put(fn, false);
                    failures.remove(key);
                    if (displaced != null) {
                        displaced.close();
                    }
                    yield fn;
                }
                case Refused(io.flowcatalyst.fnhost.load.Reason reason, String detail) -> {
                    String loadReason = "LOAD:" + reason.name();
                    failures.put(key, loadReason);
                    observer.loadError(loadReason);
                    if (reason == io.flowcatalyst.fnhost.load.Reason.OUT_OF_METASPACE) {
                        logMetaspaceRefusalSafely(route.address(), route.version(), detail);
                    }
                    yield current;
                }
            };
        }
    }

    /// Test-only seam: whether [#loadLocks] still holds a lock for `address` —
    /// it must not grow forever over the life of the process once an address
    /// leaves the document (spec §1.2 step 3, R1); production code never calls
    /// this, same reasoning as [ReconcileLoop]'s short-interval constructor.
    boolean hasLoadLockForTest(FunctionAddress address) {
        return loadLocks.containsKey(address);
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
        // The address's per-address lock ([#loadLocks], R1) is never looked up again
        // once it leaves lazyRoutes for this same reason — [#ensureLoaded] only ever
        // computes one for an address it finds routed — so drop it here too, or the
        // map only ever grows over the life of the process.
        lazyRoutes.keySet().removeIf(address -> {
            boolean gone = !keep.contains(address);
            if (gone) {
                loadLocks.remove(address);
            }
            return gone;
        });

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

    // ── D3 (function-host-listener.md §3): webhook signing secret tracking ──

    /// Records a rotation the moment a NEW document changes a live entry's
    /// webhook secret: the displaced value becomes the "previous" secret,
    /// accepted through reconcile `reconcileNumber + 1` (spec §3: "accepted
    /// until the second reconcile after the change, then dropped" — this is
    /// that reconcile PLUS one more).
    private void updateSecretHistory(DesiredDocument doc, long reconcileNumber) {
        for (DesiredDocument.Entry entry : doc.functions()) {
            if (entry.role() != DesiredDocument.Role.LIVE) {
                continue;
            }
            String newSecret = entry.webhookSigningSecret();
            String oldSecret = currentSecretByAddress.get(entry.address());
            if (Objects.equals(oldSecret, newSecret)) {
                continue;
            }
            if (oldSecret != null) {
                previousSecretByAddress.put(entry.address(), new PreviousSecret(oldSecret, reconcileNumber + 1));
            }
            if (newSecret != null) {
                currentSecretByAddress.put(entry.address(), newSecret);
            } else {
                currentSecretByAddress.remove(entry.address());
            }
        }
    }

    /// Spec §3: "No secret in desired state ⇒ every webhook call is 401
    /// (fail closed) and the heartbeat reports the function FAILED:
    /// NO_SIGNING_SECRET" — a live entry with a `webhook` endpoint but no
    /// secret is flagged in `failures` AFTER [#load] (never before — it must
    /// not stop the entry from loading and serving its `platform`/`none`
    /// endpoints), so it shows FAILED in the heartbeat without ever being
    /// unloaded or refused.
    private void checkSigningSecrets(DesiredDocument doc) {
        for (DesiredDocument.Entry entry : doc.functions()) {
            if (entry.role() != DesiredDocument.Role.LIVE || !hasWebhookEndpoint(entry.manifest())) {
                continue;
            }
            Key key = new Key(entry.address(), entry.version());
            if (entry.webhookSigningSecret() == null) {
                failures.put(key, "NO_SIGNING_SECRET");
            } else if ("NO_SIGNING_SECRET".equals(failures.get(key))) {
                failures.remove(key);
            }
        }
    }

    private static boolean hasWebhookEndpoint(io.flowcatalyst.platform.function.Manifest manifest) {
        return manifest.endpoints().stream()
                .anyMatch(e -> e.auth() == io.flowcatalyst.platform.function.EndpointAuth.WEBHOOK);
    }

    /// The CURRENT webhook signing secret for `address`'s live entry, or
    /// empty when none is recorded (spec §3).
    public Optional<String> currentWebhookSecret(FunctionAddress address) {
        return Optional.ofNullable(currentSecretByAddress.get(address));
    }

    /// The PREVIOUS webhook signing secret for `address`, still accepted
    /// (spec §3's rotation window), or empty once it has expired or none was
    /// ever recorded.
    public Optional<String> previousWebhookSecret(FunctionAddress address) {
        PreviousSecret p = previousSecretByAddress.get(address);
        if (p == null) {
            return Optional.empty();
        }
        if (reconcileCounter.get() > p.validThroughReconcile()) {
            previousSecretByAddress.remove(address, p);
            return Optional.empty();
        }
        return Optional.of(p.secret());
    }

    /// How many times [#reconcileOnce] has run — the clock [#previousWebhookSecret]'s
    /// rotation window is measured against (spec §3).
    public long reconcileCount() {
        return reconcileCounter.get();
    }

    // ── D3 (function-host-listener.md §2-§4): what the listener needs ──────

    /// The current document's LIVE entry for `address`, or `null` — what an
    /// unversioned call resolves against (spec §2 step 3): endpoint match,
    /// auth mode and permits all read this entry's manifest, regardless of
    /// whether the version has finished loading yet.
    public DesiredDocument.Entry liveEntry(FunctionAddress address) {
        DesiredDocument doc = document;
        if (doc == null) {
            return null;
        }
        for (DesiredDocument.Entry entry : doc.functions()) {
            if (entry.role() == DesiredDocument.Role.LIVE && entry.address().equals(address)) {
                return entry;
            }
        }
        return null;
    }

    /// The current document's entry for `address` at exactly `version` —
    /// live OR candidate (spec §4 step 4: "the version must be an entry of
    /// the current desired-state document (live or candidate)") — or `null`
    /// when no such entry exists (`VERSION_NOT_AVAILABLE`).
    public DesiredDocument.Entry entryFor(FunctionAddress address, int version) {
        DesiredDocument doc = document;
        if (doc == null) {
            return null;
        }
        for (DesiredDocument.Entry entry : doc.functions()) {
            if (entry.address().equals(address) && entry.version() == version) {
                return entry;
            }
        }
        return null;
    }

    /// The current, immutable public-route snapshot (spec
    /// `function-public-routes.md` §3) — [PublicRouteTable#EMPTY] before the
    /// first reconcile or when the document names no public routes.
    public PublicRouteTable publicRouteTable() {
        return publicRouteTable;
    }

    /// Every address named anywhere in the current document — live or
    /// candidate — or empty before the first reconcile. D5 (spec
    /// `function-host-process.md` §2): what [io.flowcatalyst.fnhost.metrics.FnMetrics]
    /// diffs against, after each reconcile, to know which per-address
    /// series have left desired state and must be dropped.
    public Set<FunctionAddress> desiredAddresses() {
        DesiredDocument doc = document;
        if (doc == null) {
            return Set.of();
        }
        Set<FunctionAddress> out = new HashSet<>();
        for (DesiredDocument.Entry entry : doc.functions()) {
            out.add(entry.address());
        }
        return out;
    }

    /// Loads `entry`'s version fresh from `prepared` — for a versioned call
    /// pinning a candidate (spec §4: "the host loads the candidate lazily for
    /// it"), never touching the registry's live slot or `lazyRoutes`. Returns
    /// `null` when the version is not (yet) prepared, or its load is refused.
    /// The caller ([io.flowcatalyst.fnhost.http.PinnedVersions]) owns the
    /// resulting [LoadedFunction]'s lifecycle.
    public LoadedFunction loadPinned(DesiredDocument.Entry entry) {
        Objects.requireNonNull(entry, "entry");
        Prepared p = prepared.get(entry.versionId());
        if (p == null) {
            return null;
        }
        LoadOutcome outcome = attemptLoad(p, entry);
        return switch (outcome) {
            case Loaded(LoadedFunction fn) -> {
                Key key = new Key(entry.address(), entry.version());
                yield attachContextAndInit(fn, entry, key) ? fn : null;
            }
            case Refused ignored -> null;
        };
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
