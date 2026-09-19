package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/// Host-global then per-function invocation permits (spec
/// `function-host-listener.md` §2 step 7), both `tryAcquire` — never a
/// queue. A per-function semaphore is sized from the manifest's
/// `limits.maxConcurrency`, created lazily and replaced if a reconcile
/// changes the size (any permits already checked out of a replaced semaphore
/// simply release into an object nobody references again — harmless).
public final class Permits {

    private final Semaphore hostWide;
    private final ConcurrentHashMap<FunctionAddress, Sized> perFunction = new ConcurrentHashMap<>();

    private record Sized(int size, Semaphore semaphore) {
    }

    public Permits(int hostMaxConcurrency) {
        if (hostMaxConcurrency < 1) {
            throw new IllegalArgumentException("hostMaxConcurrency must be at least 1");
        }
        this.hostWide = new Semaphore(hostMaxConcurrency);
    }

    /// The outcome of a [#tryAcquire] — `granted()` decides whether the
    /// caller may proceed; either way this is what [#release] takes back.
    public record Grant(boolean granted, FunctionAddress address) {
    }

    /// Acquires the host-global permit, then the per-function one; either
    /// exhausted returns a non-granted [Grant] — the host permit is released
    /// again if the function one fails (spec §2 step 7).
    public Grant tryAcquire(FunctionAddress address, int maxConcurrency) {
        Objects.requireNonNull(address, "address");
        if (!hostWide.tryAcquire()) {
            return new Grant(false, address);
        }
        Semaphore fnSemaphore = semaphoreFor(address, maxConcurrency);
        if (!fnSemaphore.tryAcquire()) {
            hostWide.release();
            return new Grant(false, address);
        }
        return new Grant(true, address);
    }

    /// Releases both permits a granted [Grant] holds. A non-granted grant is
    /// a no-op — nothing was ever acquired for it.
    public void release(Grant grant) {
        Objects.requireNonNull(grant, "grant");
        if (!grant.granted()) {
            return;
        }
        Sized sized = perFunction.get(grant.address());
        if (sized != null) {
            sized.semaphore().release();
        }
        hostWide.release();
    }

    /// Test/diagnostic seam: the host-wide gauge.
    public int hostAvailable() {
        return hostWide.availablePermits();
    }

    /// Test/diagnostic seam: `address`'s own gauge, `-1` if never sized.
    public int functionAvailable(FunctionAddress address) {
        Sized sized = perFunction.get(address);
        return sized == null ? -1 : sized.semaphore().availablePermits();
    }

    private Semaphore semaphoreFor(FunctionAddress address, int size) {
        return perFunction.compute(address, (addr, existing) ->
                (existing == null || existing.size() != size) ? new Sized(size, new Semaphore(size)) : existing
        ).semaphore();
    }
}
