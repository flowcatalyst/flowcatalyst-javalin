package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.FunctionAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/// One loaded version of one function: its instance, its class loader, and
/// enough bookkeeping to unload it safely (`docs/spec/function-host-core.md`
/// §2.3). This is the one object that keeps a reference to anything the
/// function's loader defined — closing it is what makes the loader (and
/// every class it defined) collectable.
public final class LoadedFunction implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LoadedFunction.class);
    private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private final Function function;
    private final URLClassLoader loader;
    private final FunctionAddress address;
    private final int version;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object drainLock = new Object();
    private volatile boolean closed;

    LoadedFunction(Function function, URLClassLoader loader,
            FunctionAddress address, int version) {
        this.function = Objects.requireNonNull(function, "function");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.address = Objects.requireNonNull(address, "address");
        this.version = version;
    }

    public FunctionAddress address() {
        return address;
    }

    public int version() {
        return version;
    }

    /// Whether [#close()] has already run — the loader's classes are then
    /// collectable and [#invoke] no longer accepted. Test/diagnostic seam
    /// (D3: [io.flowcatalyst.fnhost.http.PinnedVersions#sweep] provably
    /// closes a pinned candidate once its version leaves desired state).
    public boolean isClosed() {
        return closed;
    }

    /// The function's own class loader — package-private, for the leak
    /// test ([FunctionLeakTest]) to build a `WeakReference` against.
    ClassLoader loaderForTest() {
        return loader;
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
    /// class loader. Idempotent. After this returns, [#invoke] throws
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
            loader.close();
        } catch (IOException e) {
            LOG.atWarn()
                    .setMessage("closing function class loader failed")
                    .addKeyValue("address", address.render())
                    .addKeyValue("version", version)
                    .setCause(e)
                    .log();
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
