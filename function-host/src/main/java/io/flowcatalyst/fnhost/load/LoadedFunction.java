package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.FunctionAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/// One loaded version of one function: its instance, the runtime resource
/// behind it, and enough bookkeeping to unload it safely
/// (`docs/spec/function-host-core.md` §2.3). This is the one object that
/// keeps a reference to anything the runtime defined for this version —
/// closing it is what makes that collectable.
///
/// Runtime-neutral (`docs/spec/function-wasm-runtime.md` §2): `resource` is
/// whatever the version's [FunctionLoader] must release on unload — a JVM
/// function's own `URLClassLoader`, a Wasm module's compiled code. When the
/// resource IS a [ClassLoader] (the JVM path), it is set as the thread's
/// context class loader for every call into the function; any other resource
/// (Wasm) leaves the context loader alone.
public final class LoadedFunction implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LoadedFunction.class);
    private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private final Function function;
    private final AutoCloseable resource;
    private final FunctionAddress address;
    private final int version;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object drainLock = new Object();
    private volatile boolean closed;

    /// This version's [FunctionContext] (D4b, `docs/spec/function-context.md`
    /// §2): built and attached exactly once, by whoever loaded this instance
    /// ([io.flowcatalyst.fnhost.reconcile.Reconciler]), before [#init] runs —
    /// the SAME instance for every [#invoke] over this object's whole
    /// lifetime. `null` until [#attachContext] runs (D1 isolation tests that
    /// never go through the reconciler pass their own context explicitly to
    /// [#init]/[#invoke] instead of relying on this field).
    private volatile FunctionContext context;

    LoadedFunction(Function function, AutoCloseable resource,
            FunctionAddress address, int version) {
        this.function = Objects.requireNonNull(function, "function");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.address = Objects.requireNonNull(address, "address");
        this.version = version;
    }

    public FunctionAddress address() {
        return address;
    }

    public int version() {
        return version;
    }

    /// Attaches this version's [FunctionContext] (D4b) — called exactly once
    /// by the loader ([io.flowcatalyst.fnhost.reconcile.Reconciler]), before
    /// [#init]. [#close] releases it (via [AutoCloseable] if the context
    /// implements it, e.g. [io.flowcatalyst.fnhost.context.HostFunctionContext]'s
    /// database-pool bookkeeping) exactly once, regardless of how this
    /// version's load attempt ends.
    ///
    /// @throws IllegalStateException a context is already attached
    public void attachContext(FunctionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (context != null) {
            throw new IllegalStateException("a FunctionContext is already attached to " + address + "@" + version);
        }
        context = ctx;
    }

    /// This version's attached [FunctionContext] (D4b), or `null` if
    /// [#attachContext] was never called.
    public FunctionContext context() {
        return context;
    }

    /// Whether [#close()] has already run — the loader's classes are then
    /// collectable and [#invoke] no longer accepted. Test/diagnostic seam
    /// (D3: [io.flowcatalyst.fnhost.http.PinnedVersions#sweep] provably
    /// closes a pinned candidate once its version leaves desired state).
    public boolean isClosed() {
        return closed;
    }

    /// The function's own class loader (JVM functions; `null` for any other
    /// runtime) — package-private, for the leak test ([FunctionLeakTest]) to
    /// build a `WeakReference` against.
    ClassLoader loaderForTest() {
        return resource instanceof ClassLoader loader ? loader : null;
    }

    /// The instance [#invoke] calls — package-private test seam, so a Wasm
    /// test can reach the adapter's pool without an invocation.
    Function functionForTest() {
        return function;
    }

    /// Marks one invocation as in flight — [#close()] waits for every
    /// retained call to [#release()] before it tears the loader down.
    public void retain() {
        inFlight.incrementAndGet();
    }

    /// Matches a prior [#retain()].
    ///
    /// @throws IllegalStateException if called without a matching `retain()`
    public void release() {
        int remaining = inFlight.decrementAndGet();
        if (remaining < 0) {
            inFlight.incrementAndGet();
            throw new IllegalStateException("release() without a matching retain() on " + address + "@" + version);
        }
        if (remaining == 0) {
            synchronized (drainLock) {
                drainLock.notifyAll();
            }
        }
    }

    /// Runs `ctx`'s function through one invocation with the calling
    /// thread's context class loader set to this function's loader for the
    /// duration of the call — restored in `finally`, so a throw restores it
    /// too.
    ///
    /// @throws IllegalStateException if this function has been [#close()]d
    public Result invoke(Request in, FunctionContext ctx) throws Exception {
        requireOpen();
        return withFunctionContextLoader(() -> function.handle(in, ctx));
    }

    /// Runs `Function#init` with the same context-class-loader swap as
    /// [#invoke]. Called once, before this function is registered for
    /// invocation.
    public void init(FunctionContext ctx) throws Exception {
        requireOpen();
        withFunctionContextLoader(() -> {
            function.init(ctx);
            return null;
        });
    }

    /// Waits (bounded) for every in-flight [#invoke] call to [#release()],
    /// then runs `Function#stop` (with the same context-class-loader swap;
    /// exceptions are logged, never propagated) and closes the underlying
    /// runtime resource. Idempotent. After this returns, [#invoke] throws
    /// `IllegalStateException`.
    @Override
    public void close() {
        close(DEFAULT_DRAIN_TIMEOUT);
    }

    /// [#close()] with an explicit drain timeout — package-private, for
    /// tests that need the bound tighter than 30s.
    void close(Duration drainTimeout) {
        if (closed) return;
        closed = true;

        boolean drained = awaitDrain(drainTimeout);
        if (!drained) {
            LOG.atWarn()
                    .setMessage("closing loaded function before every invocation released: forcing close")
                    .addKeyValue("address", address.render())
                    .addKeyValue("version", version)
                    .addKeyValue("count", inFlight.get())
                    .log();
        }

        try {
            withFunctionContextLoader(() -> {
                function.stop();
                return null;
            });
        } catch (Exception e) {
            LOG.atWarn()
                    .setMessage("function stop() threw")
                    .addKeyValue("address", address.render())
                    .addKeyValue("version", version)
                    .setCause(e)
                    .log();
        }

        try {
            resource.close();
        } catch (Exception e) {
            LOG.atWarn()
                    .setMessage("closing function runtime resource failed")
                    .addKeyValue("address", address.render())
                    .addKeyValue("version", version)
                    .setCause(e)
                    .log();
        }

        // D4b: release whatever this version's context holds (DbPools' reference
        // counts) — exactly once, regardless of how this close() was reached, and
        // regardless of whether a context was ever attached at all (D1 isolation
        // tests never attach one).
        if (context instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.atWarn()
                        .setMessage("closing function context failed")
                        .addKeyValue("address", address.render())
                        .addKeyValue("version", version)
                        .setCause(e)
                        .log();
            }
        }
    }

    private boolean awaitDrain(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        synchronized (drainLock) {
            while (inFlight.get() > 0) {
                long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMillis <= 0) {
                    return false;
                }
                try {
                    drainLock.wait(remainingMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("LoadedFunction is closed: " + address + "@" + version);
        }
    }

    private <T> T withFunctionContextLoader(ThrowingSupplier<T> body) throws Exception {
        if (!(resource instanceof ClassLoader loader)) {
            return body.get(); // not a JVM function: the context loader is not ours to touch
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return body.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
